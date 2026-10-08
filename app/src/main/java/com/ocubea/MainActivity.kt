package com.ocubea

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.ocubea.model.OcuBeaConfig
import com.ocubea.service.StreamService
import com.ocubea.ui.ClipActivity
import com.ocubea.ui.EdgeToEdgeInsets
import com.ocubea.ui.KeepScreenOn
import com.ocubea.ui.LivePreviewView
import java.net.NetworkInterface
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * Kiosk home screen.
 *
 * OcuBea can be set as the device's Home app, so this is what the user sees
 * after unlocking the phone: a live preview of what the camera sees plus direct
 * control of every feature. The button in the top right corner hands control back
 * to the real launcher.
 *
 * The activity only drives [StreamService] — the service owns the camera, so the
 * stream survives this activity being destroyed.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val PERMS_REQUEST = 1001

        /**
         * How often to look for StreamService.instance.frameHub while waiting for
         * the service to come up. The hub appears within a frame or two of the
         * service starting, so this only ever runs a handful of times; it is a
         * short retry because a preview that stays black while the camera runs
         * is indistinguishable from a broken app.
         */
        private const val PREVIEW_ATTACH_RETRY_MS = 250L

        /**
         * Compiled status-field patterns, built on first use and kept.
         *
         * The field names are a fixed set this class itself passes, so this
         * map cannot grow from request data — it is a lookup table, not a
         * cache with an attacker-chosen key.
         */
        private val NUM_FIELDS = java.util.concurrent.ConcurrentHashMap<String, Regex>()

        /** Known launcher packages, tried in order when leaving kiosk mode. */
        val HOME_PACKAGES = arrayOf(
            "com.google.android.apps.nexuslauncher",
            "com.miui.home",
            "com.android.launcher3",
            "com.android.launcher",
            "com.sec.android.app.launcher"
        )
    }

    private lateinit var config: OcuBeaConfig
    private lateinit var btnToggle: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvUrl: TextView
    private lateinit var preview: LivePreviewView

    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ocubea-ui").apply { isDaemon = true }
    }

    private var torchOn = false
    private var nightOn = false
    private var motionOn = false
    private var zoom = 1f
    private var maxZoom = 1f
    private var pollBusy = false
    // Written on the poll worker, read on the main thread. Without volatile the
    // main thread can keep reading a stale null after a failure, which is the
    // same class of bug as the black preview: a value written and never seen.
    @Volatile
    private var lastPollError: String? = null
    private var runningButtonLabel: String? = null
    private var stoppedButtonLabel: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // Measured on a Redmi Note 12 Pro, Android 16 (API 36): before this
        // call tvTitle sat at y=27-86 while the status bar occupied y=0-94,
        // so 67 px of the title was under the clock. See EdgeToEdgeInsets.
        EdgeToEdgeInsets.padForSystemBars(findViewById(android.R.id.content))
        // See EdgeToEdgeInsets: the theme attribute alone did not remove the
        // platform's contrast scrim on API 30+, measured on an Android 16 device.
        EdgeToEdgeInsets.disableNavigationBarContrast(window)
        config = OcuBeaConfig(this)
        // The `awake` setting can only be honoured on a real window, and this is
        // the one window this app owns. Registering here is what lets
        // /settings/awake answer from a window instead of from a service that
        // has none; see KeepScreenOn for why it is a registry rather than a
        // reference passed through.
        KeepScreenOn.attach(window)

        btnToggle = findViewById(R.id.btnToggleStream)
        tvStatus = findViewById(R.id.tvStatus)
        tvUrl = findViewById(R.id.tvUrl)
        preview = findViewById(R.id.livePreview)

        wireControls()

        nightOn = config.nightVision
        motionOn = config.securityEnabled
        torchOn = config.torchOn

        if (StreamService.instance != null) renderRunning() else checkPermsAndStart()
        updateUrlLabel()
    }

    // ── Controls ───────────────────────────────────────────────

    private fun wireControls() {
        // WHY THIS NO LONGER CHECKS `StreamService.instance`.

        // It did, and that made the Start button unreachable. Stop deliberately
        // stops the camera only and leaves the service running so the WebUI can
        // still answer /status.json -- see StreamService.stopCameraOnly(). So after
        // a Stop, `instance` is still non-null while the camera is off, and the
        // condition below sent every subsequent tap to stopStreaming() again.
        //
        // Measured on the Sony F3311 (Android 6): click Stop, `camera_active` in
        // /status.json went true -> false, correctly. Click the button again and
        // `camera_active` stayed false, and the button label stayed "Zatrzymaj".
        // The camera never restarted. `StreamService.isCameraActive` is what
        // /status.json reports as `camera_active`, so it is the same fact the user
        // is looking at -- asking the camera instead of asking whether a service
        // object happens to exist. `instance` alone is wrong because Stop keeps the
        // service alive on purpose; see isCameraActive for the full reasoning.
        btnToggle.setOnClickListener {
            if (StreamService.instance?.isCameraActive == true) {
                stopStreaming()
            } else {
                checkPermsAndStart()
            }
        }

        findViewById<Button>(R.id.btnTorch).setOnClickListener {
            callServer(if (torchOn) "torchoff" else "torchon") { ok ->
                if (ok) {
                    torchOn = !torchOn
                    config.torchOn = torchOn
                    toast(getString(if (torchOn) R.string.torch_on else R.string.torch_off))
                } else toast(getString(R.string.torch_missing))
            }
        }

        findViewById<Button>(R.id.btnFlip).setOnClickListener {
            callServer("settings/ffc?set=toggle") { ok ->
                if (ok) {
                    config.frontCamera = !config.frontCamera
                    toast(getString(if (config.frontCamera) R.string.cam_front else R.string.cam_back))
                }
            }
        }

        findViewById<Button>(R.id.btnZoomIn).setOnClickListener { stepZoom(1f) }
        findViewById<Button>(R.id.btnZoomOut).setOnClickListener { stepZoom(-1f) }

        findViewById<Button>(R.id.btnNightVision).setOnClickListener {
            nightOn = !nightOn
            config.nightVision = nightOn
            callServer("settings/night_vision?set=" + if (nightOn) "on" else "off")
            toast(getString(if (nightOn) R.string.night_on else R.string.night_off))
        }

        findViewById<Button>(R.id.btnMotion).setOnClickListener {
            motionOn = !motionOn
            config.securityEnabled = motionOn
            callServer("settings/motion_detection?set=" + if (motionOn) "on" else "off")
            toast(getString(if (motionOn) R.string.motion_armed else R.string.motion_off))
        }

        findViewById<Button>(R.id.btnScreenOff).setOnClickListener { screenOff() }

        findViewById<View>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<View>(R.id.btnClips).setOnClickListener {
            startActivity(Intent(this, ClipActivity::class.java))
        }
        findViewById<View>(R.id.btnPerf).setOnClickListener {
            startActivity(Intent(this, com.ocubea.perf.PerfActivity::class.java))
        }

        // Two ways out of kiosk mode, identical behaviour
        findViewById<View>(R.id.btnRealLauncher).setOnClickListener { launchSystemLauncher() }
        findViewById<View>(R.id.btnExitKiosk).setOnClickListener { launchSystemLauncher() }
    }

    private fun stepZoom(delta: Float) {
        val ceiling = if (maxZoom > 1f) maxZoom else 1f
        val next = (zoom + delta).coerceIn(1f, ceiling)
        if (next == zoom) {
            toast(getString(if (delta > 0) R.string.zoom_max else R.string.zoom_min))
            return
        }
        zoom = next
        callServer("ptz?zoom=$next")
    }

    // ── Permissions ────────────────────────────────────────────

    private fun requiredPerms() = buildList {
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    private fun checkPermsAndStart() {
        val missing = requiredPerms().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startStreaming()
        else ActivityCompat.requestPermissions(this, missing.toTypedArray(), PERMS_REQUEST)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code == PERMS_REQUEST && results.isNotEmpty() &&
            results[0] == PackageManager.PERMISSION_GRANTED
        ) {
            startStreaming()
        } else {
            toast(getString(R.string.camera_permission_required))
        }
    }

    /**
     * Resume streaming after a Stop, or start it the first time.
     *
     * WHY THIS SENDS `ACTION_START_CAMERA` AND NOT A BARE INTENT.
     *
     * A bare Intent reaches `onStartCommand`'s `else -> startEverything()` branch,
     * and `startEverything()` begins with
     *
     *     if (!started.compareAndSet(false, true)) return
     *
     * `started` is only cleared by `stopEverything()`, which a Stop deliberately does
     * not call -- `stopCameraOnly()` stops the camera and leaves the server up so the
     * WebUI keeps answering /status.json. So the flag is already true, that compareAndSet
     * fails, `startEverything()` returns immediately having done nothing, and the Start
     * button appears dead.
     *
     * Measured on the Sony F3311 (Android 6): after a Stop the button label correctly
     * changed to "Startuj" and the tap changed nothing -- `camera_active` stayed false
     * in /status.json. Tapping the same button twice in a row was the signature: the
     * first tap worked, the second never could.
     *
     * `ACTION_START_CAMERA` maps to `startCamera()`, which is guarded by
     * `cameraRunning` instead -- and that flag IS cleared by Stop. So it is the branch
     * that means "resume the camera", and it is reachable exactly when the user presses
     * Start after a Stop.
     *
     * On a first launch the service is not running yet, so `ACTION_START_CAMERA` would
     * be handled by a service whose `cameraManager` is not initialised yet and whose
     * HTTP server does not exist. That is why this still sends the bare intent when
     * there is no instance: first start needs `startEverything()`, and only a resume
     * needs the action.
     */
    private fun startStreaming() {
        val svc = StreamService.instance
        val intent = if (svc != null) {
            Intent(this, StreamService::class.java).setAction(StreamService.ACTION_START_CAMERA)
        } else {
            Intent(this, StreamService::class.java)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)
        renderRunning()
        schedulePolling()
    }

    private fun stopStreaming() {
        startService(
            Intent(this, StreamService::class.java).setAction(StreamService.ACTION_STOP)
        )
        renderStopped()
        preview.stop()
    }

    // ── Status polling ─────────────────────────────────────────

    private fun schedulePolling() {
        handler.removeCallbacks(pollTask)
        handler.postDelayed(pollTask, 500)
    }

    private val pollTask = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed) return
            pollOnce()
            handler.postDelayed(this, 2000)
        }
    }

    private fun pollOnce() {
        val url = loopbackUrl()
        if (pollBusy) return
        if (worker.isShutdown || isFinishing || isDestroyed) return
        pollBusy = true
        try {
            worker.execute {
                var result: String? = null
                try {
                    val conn = URL("$url/status.json").openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 1500
                    conn.readTimeout = 2000
                    result = conn.inputStream.bufferedReader().use { it.readText() }
                    conn.disconnect()
                } catch (e: Exception) {
                    // The reason has to travel with the failure. A cleartext
                    // block, a wrong IP and a server that is off all arrive here
                    // as the same null body, and "no connection" is a lie for
                    // two of them.
                    lastPollError = "${e.javaClass.simpleName}: ${e.message}"
                }
                val body = result
                handler.post {
                    pollBusy = false
                    // A reply can land after onDestroy - onPause only stops the
                    // next poll, it does not cancel one already on the wire.
                    // Touching a destroyed view used to throw, and MainActivity
                    // is a HOME candidate recreated whenever the user leaves.
                    if (isFinishing || isDestroyed) return@post
                    if (body == null) {
                        // Before this, "no answer on our port" always meant the
                        // red offline text, even when the port was answered by a
                        // different app entirely, or when our own server had
                        // failed to bind. Reading the service's own last error
                        // separates the two with no second network round trip.
                        val svc = StreamService.instance
                        if (svc != null) {
                            val last = svc.lastError
                            if (last != null && last.contains("failed to bind")) {
                                showServiceError(last)
                                return@post
                            }
                        }
                        tvStatus.text = getString(R.string.status_offline)
                        tvStatus.setTextColor(0xFFFF5252.toInt())
                        // A cleartext block is not a disconnected server, and the
                        // user is the only one who can fix it, so the reason has to
                        // be on screen instead of swallowed into a null body.
                        tvStatus.text = getString(
                            R.string.status_offline_reason,
                            lastPollError ?: getString(R.string.status_offline)
                        )
                    } else {
                        lastPollError = null
                        applyStatus(body)
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            // onDestroy shut the executor down between the check above and the
            // submit. Dropping the poll is correct: the Activity is gone.
            pollBusy = false
        }
    }

    private fun applyStatus(json: String) {
        // Something answered on our port but it is not us. Another IP-webcam app
        // holding 8080 replies with its own status.json, and numField() finds
        // none of our fields, so the screen showed a confident green
        // "0 fps, 0 viewers, 0 frames" for a camera that was streaming fine one
        // port over. Measured on the Android 6 phone, where com.pas.webcam owns
        // 8080 and this app can only reach its own RTSP port.
        if (!json.contains("\"pipeline\"")) {
            tvStatus.text = getString(R.string.status_port_taken, config.port)
            tvStatus.setTextColor(0xFFFF9800.toInt())
            return
        }
        // A live server is proof the service exists, which is not the same as
                  // the camera running. Stop deliberately leaves the server up (see
                  // StreamService.stopCameraOnly()) so the WebUI keeps answering, so a
                  // reachable /status.json means the service is alive, not that the camera
                  // is streaming. This code used to force the label to "Stop" whenever the
                  // server replied, which is why a stopped camera kept showing "Zatrzymaj"
                  // and no tap could ever start it again.
                  //
                  // Follow the camera flag the response actually carries. `camera_active`
                  // is what the service reports from the same `isCameraActive` the button
                  // click consults, so the label and the next tap cannot disagree.
                  //
                  // Compared against a held string: getString() in the condition would run
                  // a resource lookup every poll, every 2s, forever.
                  val cameraOn = json.contains("\"camera_active\":true")
                  if (runningButtonLabel == null) runningButtonLabel = getString(R.string.stop_stream)
                  if (stoppedButtonLabel == null) stoppedButtonLabel = getString(R.string.start_stream)
                  val wanted = if (cameraOn) runningButtonLabel else stoppedButtonLabel
                  if (btnToggle.text != wanted) btnToggle.text = wanted
        val fps = numField(json, "fps")
        val viewers = numField(json, "viewers")
        val frames = numField(json, "frames")
        val torch = json.contains("\"torch\":true")
        zoom = numField(json, "level") ?: zoom
        val max = numField(json, "max")
        if (max != null && max > 1f) maxZoom = max
        if (torch != torchOn) torchOn = torch
        tvStatus.text = getString(
            R.string.status_live,
            // %d rejects a Float outright - Formatter throws rather than
            // printing a decimal - and numField returns null when a field is
            // absent, so a missing value must become 0 and not a crash.
            fps?.toInt() ?: 0,
            viewers?.toInt() ?: 0,
            frames?.toInt() ?: 0,
        )
        tvStatus.setTextColor(0xFF4CAF50.toInt())
    }

    /**
     * Reads a numeric field, returning null when absent.
     *
     * The patterns are built once, not per call. This runs five times per
     * status poll and the poll is every 2 s, so compiling five regexes each
     * time is 15 Pattern objects a minute for a screen that shows three
     * numbers. Precompiled, it is a field lookup.
     */
    private fun numField(json: String, name: String): Float? {
        var re = NUM_FIELDS[name]
        if (re == null) {
            re = Regex("\"$name\":([0-9.]+)")
            NUM_FIELDS[name] = re
        }
        return re.find(json)?.groupValues?.get(1)?.toFloatOrNull()
    }

    // ── Rendering ──────────────────────────────────────────────

    private fun renderRunning() {
        btnToggle.text = getString(R.string.stop_stream)
        attachPreviewHub()
        preview.start()
    }

    /**
     * Points the on-screen preview at the service's FrameHub so it shows the
     * exact frames remote viewers get, without a second camera session.
     *
     * startService() is asynchronous: on the first launch StreamService.instance
     * is still null when renderRunning() runs, so this hub is null the first
     * time and only that time. The preview thread reads the hub once at start
     * and gave up on null, so the view stayed black for the rest of the session
     * while the camera was streaming fine -- measured on the Android 6 phone,
     * where a fresh launch showed a black preview with the server reporting
     * frames.
     */
    private fun attachPreviewHub() {
        val hub = StreamService.instance?.frameHub
        if (hub != null) {
            preview.frameHub = hub
        } else {
            // Wait for the service to publish its hub rather than giving up on it.
            // Bound to the activity lifetime: a callback into a destroyed window
            // throws, and this is the window the app is recreated into.
            handler.postDelayed(retryAttachPreviewHub, PREVIEW_ATTACH_RETRY_MS)
        }
    }

    private val retryAttachPreviewHub = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed) return
            val hub = StreamService.instance?.frameHub
            if (hub != null) {
                preview.frameHub = hub
                preview.start()
            } else {
                handler.postDelayed(this, PREVIEW_ATTACH_RETRY_MS)
            }
        }
    }

    private fun renderStopped() {
        tvStatus.text = getString(R.string.status_stopped)
        tvStatus.setTextColor(0xFF9E9E9E.toInt())
        btnToggle.text = getString(R.string.start_stream)
    }

    private fun updateUrlLabel() {
        tvUrl.text = baseUrl() ?: getString(R.string.no_wifi)
    }


    /**
     * The manifest declares `android:configChanges="orientation|screenSize"`, so the
     * platform does not recreate the Activity and does not re-inflate its layout.
     * Everything below exists because of that promise.
     *
     * `onCreate` inflates once and `wireControls()` attaches listeners by id. On a
     * rotation the platform delivers this callback instead of a fresh onCreate, so
     * without the re-inflate below the portrait layout stayed in place and
     * `res/layout-land/activity_main.xml` was never loaded at all -- the resource
     * would resolve correctly in an isolated test and never run on a device.
     *
     * What had to happen on rotation, and what did not:
     *
     *   1. Re-inflate. Without `configChanges` the framework would do this itself.
     *      With it declared, only code can.
     *   2. Re-attach listeners, because the new views are new objects and the ones
     *      wired in `wireControls()` belonged to the discarded hierarchy.
     *   3. Re-resolve the cached field references (`preview`, `btnToggle`,
     *      `tvStatus`, `tvUrl`). They point at the old views, so `renderRunning()`
     *      and `updateUrlLabel()` would write into detached views and the screen
     *      would show nothing.
     *   4. Re-apply the window insets, for the new orientation's bar heights.
     *      `EdgeToEdgeInsets` is safe to call again on the NEW view -- the base
     *      padding is captured per view instance, and this is a fresh instance with
     *      the layout's own padding. See that file for why a repeated call on the
     *      SAME view used to stack a bar per rotation.
     *   5. Restore the running/night/motion/torch labels from config, so the buttons
     *      come back showing the real state instead of the defaults.
     *
     * The service, the camera and the HTTP server are untouched by this. Only the
     * view tree is rebuilt, so streaming does not restart on rotation.
     *
     * `super.onConfigurationChanged` is still called first: it is the platform
     * contract, and the framework uses it to refresh resource-backed state.
     */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // Re-inflate against the new orientation. R.layout.activity_main resolves
        // to res/layout-land/activity_main.xml under a landscape configuration and
        // to res/layout/activity_main.xml otherwise, which is what makes the two
        // layouts possible at all under configChanges.
        setContentView(R.layout.activity_main)
        EdgeToEdgeInsets.padForSystemBars(findViewById(android.R.id.content))

        // Cached references from the discarded hierarchy. Re-resolve every one.
        btnToggle = findViewById(R.id.btnToggleStream)
        tvStatus = findViewById(R.id.tvStatus)
        tvUrl = findViewById(R.id.tvUrl)
        preview = findViewById(R.id.livePreview)

        // The new views have no listeners yet.
        wireControls()

        // Re-apply state the old views were showing.
        nightOn = config.nightVision
        motionOn = config.securityEnabled
        torchOn = config.torchOn
        if (StreamService.instance != null) renderRunning() else renderStopped()
        updateUrlLabel()
    }

    override fun onResume() {
        super.onResume()
        // Re-register rather than trusting onCreate to still be the truth: this
        // Activity may be resuming after a window recreation, and the flag has
        // to go back onto the new window.
        KeepScreenOn.attach(window)
        // Settings may have changed while we were in SettingsActivity
        config = OcuBeaConfig(this)
        // The manifest sets android:keepScreenOn="true", which is a View flag and
        // not the same thing as FLAG_KEEP_SCREEN_ON. This is where the user's
        // own `awake` choice is put onto the window, so an awake=off request
        // actually clears it. After the config reload, because the request may
        // have arrived over HTTP while this Activity was in the background.
        KeepScreenOn.applyTo(config.keepScreenOn, window)
        nightOn = config.nightVision
        motionOn = config.securityEnabled
        torchOn = config.torchOn
        updateUrlLabel()
        // The service reports a failed bind -- "Server failed to bind port 8080"
        // -- to its log, its notification and this listener list, and the list had
        // no subscriber. So the one failure that makes the whole app useless (the
        // HTTP port is already taken by another app on the device) surfaced as a
        // bare red "no connection", identical to the server being switched off.
        // Measured on the Android 6 phone, where another IP-webcam app owns 8080.
        StreamService.instance?.let { svc ->
            svc.errorListeners.add(serviceErrorListener)
            svc.lastError?.let { showServiceError(it) }
        }
        if (StreamService.instance != null) {
            renderRunning()
            schedulePolling()
        } else {
            renderStopped()
            // The service is created by an asynchronous startService() from
            // onCreate, so onResume almost always wins the race and sees
            // instance == null. Nothing was scheduled in that branch, so the
            // status text kept whatever the layout shipped with -- on the
            // Android 6 phone that read as a confident red "no connection"
            // while the server was up and answering. Polling has to start
            // regardless; the poll itself is what discovers the service.
            schedulePolling()
        }
    }

    private val serviceErrorListener: (String) -> Unit = { msg ->
        handler.post {
            if (isFinishing || isDestroyed) return@post
            showServiceError(msg)
        }
    }

    /**
     * Shows a service-reported failure where the user is already looking.
     *
     * Only a bind failure earns the red text. A camera restart failure is
     * transient and recovers on its own, and a permanent-looking error that
     * clears itself teaches the user to ignore the error colour.
     */
    private fun showServiceError(msg: String) {
        if (!msg.contains("failed to bind")) return
        tvStatus.text = getString(R.string.status_bind_failed, msg)
        tvStatus.setTextColor(0xFFFF5252.toInt())
    }

    override fun onPause() {
        super.onPause()
        // The window is still registered but off screen, so `awake` reports
        // applied=false from here on -- which is the truth, and the reason a
        // request arriving with the app in the background gets a 400 rather than
        // an "Ok" for a flag on a window nobody can see.
        KeepScreenOn.setVisible(window, false)
        handler.removeCallbacks(pollTask)
        // Symmetry with the subscription in onResume: this Activity is a HOME
        // candidate, so it is recreated constantly, and a listener that only
        // ever grows makes every past window a leak that still receives errors.
        StreamService.instance?.errorListeners?.remove(serviceErrorListener)
        // Keep streaming, but stop burning CPU and radio on the on-screen preview
        preview.stop()
    }

    override fun onDestroy() {
        // Identity-checked inside, so an Activity being replaced does not clear
        // the registration of the one replacing it.
        KeepScreenOn.detach(window)
        handler.removeCallbacksAndMessages(null)
        // The executor is per-Activity, so every recreation without this left
        // another daemon thread behind holding an Activity and a Handler. As a
        // HOME candidate the launcher re-creates this screen constantly, so the
        // threads accumulated over a day of use. shutdown() rather than
        // shutdownNow(): a request already in flight should finish and be
        // discarded by the isDestroyed check above, not be interrupted.
        worker.shutdown()
        super.onDestroy()
    }

    // ── Helpers ────────────────────────────────────────────────

    private fun baseUrl(): String? {
        val ip = localIpAddress()
        return if (ip == "0.0.0.0") null else "http://$ip:${config.port}"
    }

    /**
     * URL the app uses to talk to its own HTTP server.
     *
     * Deliberately 127.0.0.1 and not the LAN address that baseUrl() reports. The
     * cleartext policy in network_security_config.xml permits cleartext for the
     * loopback interface only, and the app's own server is reached from inside
     * the app, so a self-request over the LAN address is blocked by the platform
     * before a byte leaves: measured on the Android 6 phone, where
     * HttpURLConnection threw
     *
     *     UnknownServiceException: CLEARTEXT communication not supported: []
     *
     * and the status line read a red "no connection" for a server that was up
     * and answering from every other client on the network.
     *
     * The LAN address stays in baseUrl() and is still what tvUrl shows the
     * user, because that is the address other machines have to use. Widening the
     * cleartext policy to the LAN to make this work would be the wrong fix: it
     * would let the app send anything in cleartext to any host, to work around a
     * self-request that has no reason to leave the device.
     */
    private fun loopbackUrl(): String = "http://127.0.0.1:${config.port}"

    private fun localIpAddress(): String {
        try {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                for (addr in nif.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        return addr.hostAddress ?: ""
                    }
                }
            }
        } catch (_: Exception) {}
        return "0.0.0.0"
    }

    /** Fire-and-forget API call on a background thread. */
    private fun callServer(path: String, onDone: (Boolean) -> Unit = {}) {
        val url = loopbackUrl()
        try {
            worker.execute {
                var ok = false
                try {
                    val conn = URL("$url/$path").openConnection() as java.net.HttpURLConnection
                    conn.requestMethod = "GET"
                    conn.connectTimeout = 1500
                    conn.readTimeout = 2000
                    ok = conn.responseCode in 200..299
                    conn.disconnect()
                } catch (_: Exception) {}
                handler.post {
                    // Same reason as in pollOnce: a button press can race
                    // onDestroy, and the callback touches views.
                    if (!isFinishing && !isDestroyed) onDone(ok)
                }
            }
        } catch (_: RejectedExecutionException) {
            // Executor already shut down; the Activity is on its way out.
        }
    }

    private fun screenOff() {
        try {
            getSystemService(android.app.admin.DevicePolicyManager::class.java).lockNow()
        } catch (_: Exception) {
            // No device admin: dim and let the normal screen timeout finish the job
            window.attributes = window.attributes.apply { screenBrightness = 0f }
            toast(getString(R.string.screen_dimmed))
        }
        moveTaskToBack(true)
    }

    /** Hand control back to the device's real launcher. */
    private fun launchSystemLauncher() {
        for (pkg in HOME_PACKAGES) {
            if (pkg == packageName) continue
            val intent = packageManager.getLaunchIntentForPackage(pkg)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                startActivity(intent)
                return
            }
        }
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val candidates = packageManager.queryIntentActivities(home, 0)
            .filter { it.activityInfo.packageName != packageName }
        if (candidates.isNotEmpty()) {
            startActivity(
                Intent(home).apply {
                    setClassName(
                        candidates[0].activityInfo.packageName,
                        candidates[0].activityInfo.name
                    )
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                }
            )
            return
        }
        toast(getString(R.string.no_launcher_found))
        startActivity(Intent(android.provider.Settings.ACTION_HOME_SETTINGS))
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}

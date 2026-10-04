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
        btnToggle.setOnClickListener {
            if (StreamService.instance != null) stopStreaming() else checkPermsAndStart()
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

    private fun startStreaming() {
        val intent = Intent(this, StreamService::class.java)
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
        val url = baseUrl() ?: return
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
                } catch (_: Exception) {
                    // server down or restarting
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
                        tvStatus.text = getString(R.string.status_offline)
                        tvStatus.setTextColor(0xFFFF5252.toInt())
                    } else {
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

    private fun renderStopped() {
        tvStatus.text = getString(R.string.status_stopped)
        tvStatus.setTextColor(0xFF9E9E9E.toInt())
        btnToggle.text = getString(R.string.start_stream)
    }

    private fun updateUrlLabel() {
        tvUrl.text = baseUrl() ?: getString(R.string.no_wifi)
    }

    /**
     * Points the on-screen preview at the service's FrameHub so it shows the
     * exact frames remote viewers get, without a second camera session.
     */
    private fun attachPreviewHub() {
        val hub = StreamService.instance?.frameHub
        if (hub != null) preview.frameHub = hub
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
        if (StreamService.instance != null) {
            renderRunning()
            schedulePolling()
        } else {
            renderStopped()
        }
    }

    override fun onPause() {
        super.onPause()
        // The window is still registered but off screen, so `awake` reports
        // applied=false from here on -- which is the truth, and the reason a
        // request arriving with the app in the background gets a 400 rather than
        // an "Ok" for a flag on a window nobody can see.
        KeepScreenOn.setVisible(window, false)
        handler.removeCallbacks(pollTask)
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
        val url = baseUrl() ?: return onDone(false)
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

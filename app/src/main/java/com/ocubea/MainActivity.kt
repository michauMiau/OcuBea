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
import com.ocubea.ui.LivePreviewView
import java.net.NetworkInterface
import java.net.URL
import java.util.concurrent.Executors

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
        config = OcuBeaConfig(this)

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
                    toast(if (torchOn) "Torch on" else "Torch off")
                } else toast("No torch on this device")
            }
        }

        findViewById<Button>(R.id.btnFlip).setOnClickListener {
            callServer("settings/ffc?set=toggle") { ok ->
                if (ok) {
                    config.frontCamera = !config.frontCamera
                    toast(if (config.frontCamera) "Front camera" else "Back camera")
                }
            }
        }

        findViewById<Button>(R.id.btnZoomIn).setOnClickListener { stepZoom(1f) }
        findViewById<Button>(R.id.btnZoomOut).setOnClickListener { stepZoom(-1f) }

        findViewById<Button>(R.id.btnNightVision).setOnClickListener {
            nightOn = !nightOn
            config.nightVision = nightOn
            callServer("settings/night_vision?set=" + if (nightOn) "on" else "off")
            toast(if (nightOn) "Night vision on" else "Night vision off")
        }

        findViewById<Button>(R.id.btnMotion).setOnClickListener {
            motionOn = !motionOn
            config.securityEnabled = motionOn
            callServer("settings/motion_detection?set=" + if (motionOn) "on" else "off")
            toast(if (motionOn) "Motion recording armed" else "Motion detection off")
        }

        findViewById<Button>(R.id.btnScreenOff).setOnClickListener { screenOff() }

        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // Two ways out of kiosk mode, identical behaviour
        findViewById<View>(R.id.btnRealLauncher).setOnClickListener { launchSystemLauncher() }
        findViewById<View>(R.id.btnExitKiosk).setOnClickListener { launchSystemLauncher() }
    }

    private fun stepZoom(delta: Float) {
        val ceiling = if (maxZoom > 1f) maxZoom else 1f
        val next = (zoom + delta).coerceIn(1f, ceiling)
        if (next == zoom) {
            toast(if (delta > 0) "Max zoom" else "Min zoom")
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
            toast("Camera permission is required")
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
        pollBusy = true
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
                if (body == null) {
                    tvStatus.text = "Offline"
                    tvStatus.setTextColor(0xFFFF5252.toInt())
                } else {
                    applyStatus(body)
                }
            }
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
        tvStatus.text = "● $fps fps · $viewers viewer(s) · $frames frames"
        tvStatus.setTextColor(0xFF4CAF50.toInt())
    }

    /** Reads a numeric field, returning null when absent. */
    private fun numField(json: String, name: String): Float? =
        Regex("\"$name\":([0-9.]+)").find(json)?.groupValues?.get(1)?.toFloatOrNull()

    // ── Rendering ──────────────────────────────────────────────

    private fun renderRunning() {
        btnToggle.text = getString(R.string.stop_stream)
        attachPreviewHub()
        preview.start()
    }

    private fun renderStopped() {
        tvStatus.text = "Stopped"
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
        // Settings may have changed while we were in SettingsActivity
        config = OcuBeaConfig(this)
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
        handler.removeCallbacks(pollTask)
        // Keep streaming, but stop burning CPU and radio on the on-screen preview
        preview.stop()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
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
            handler.post { onDone(ok) }
        }
    }

    private fun screenOff() {
        try {
            getSystemService(android.app.admin.DevicePolicyManager::class.java).lockNow()
        } catch (_: Exception) {
            // No device admin: dim and let the normal screen timeout finish the job
            window.attributes = window.attributes.apply { screenBrightness = 0f }
            toast("Dimmed — grant device admin for true screen-off")
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
        toast("No other launcher found")
        startActivity(Intent(android.provider.Settings.ACTION_HOME_SETTINGS))
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}

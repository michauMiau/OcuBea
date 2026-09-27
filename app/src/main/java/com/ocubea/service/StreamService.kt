package com.ocubea.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.ocubea.camera.CameraManager
import com.ocubea.CrashLogger
import com.ocubea.model.OcuBeaConfig
import com.ocubea.onvif.OnvifDiscovery
import com.ocubea.security.MotionDetector
import com.ocubea.security.MotionRecorder
import com.ocubea.server.StreamServer
import java.io.File
import java.net.NetworkInterface
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground service owning the camera, HTTP server and ONVIF discovery.
 *
 * Being a foreground service is what makes OcuBea reliable: the camera keeps
 * running when the activity is minimized, when the screen is off, and after the
 * system kills the app for memory.
 */
class StreamService : LifecycleService() {

    companion object {
        const val CHANNEL_ID = "ocubea_stream"
        const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "com.ocubea.STOP_STREAM"
        const val ACTION_START_CAMERA = "com.ocubea.START_CAMERA"
        const val ACTION_RESTART_CAMERA = "com.ocubea.RESTART_CAMERA"

        @Volatile var instance: StreamService? = null
            private set
    }

    private val started = AtomicBoolean(false)

    /**
     * Camera-only running state, independent of the HTTP server.
     *
     * Tracked separately so "Stop stream" can leave the server listening: the
     * WebUI has to be able to reach /status.json to learn the stream stopped.
     */
    private val cameraRunning = AtomicBoolean(false)

    private var wakeLock: PowerManager.WakeLock? = null
    private val watchdog = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "ocubea-watchdog").apply { isDaemon = true }
    }

    private val config: OcuBeaConfig by lazy { OcuBeaConfig(applicationContext) }
    private val onvifDiscovery by lazy { OnvifDiscovery(this) }

    // NOTE: created in onCreate() — the context is not attached during construction
    private lateinit var cameraManager: CameraManager
    private lateinit var motionDetector: MotionDetector
    private lateinit var motionRecorder: MotionRecorder
    private var streamServer: StreamServer? = null

    /**
     * Live frame hub, or null before onCreate()/after onDestroy().
     *
     * The in-app preview registers as an extra viewer here so the screen shows
     * the exact frames remote clients get, without a second camera session.
     */
    val frameHub: com.ocubea.stream.FrameHub?
        get() = if (::cameraManager.isInitialized) cameraManager.frameHub else null

    @Volatile var lastError: String? = null
        private set

    @Volatile var lastFrameAt: Long = 0L
        private set

    val errorListeners = CopyOnWriteArrayList<(String) -> Unit>()

    override fun onCreate() {
        super.onCreate()
        instance = this

        val recordDir = File(getExternalFilesDir(null) ?: filesDir, "recordings")
        motionRecorder = MotionRecorder(recordDir)
        motionDetector = MotionDetector(config.motionSensitivity)
        cameraManager = CameraManager(applicationContext, config).also {
            it.lifecycleOwner = this
            it.onCameraError = { msg -> reportError(msg) }
        }

        CrashLogger.installIfNeeded(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> {
                // Camera only — the HTTP server deliberately stays up so the
                // WebUI can still report the new state. See stopCameraOnly().
                stopCameraOnly()
                return START_STICKY
            }
            ACTION_START_CAMERA -> {
                startCamera()
                return START_STICKY
            }
            ACTION_RESTART_CAMERA -> {
                restartCamera()
                return START_STICKY
            }
            else -> startEverything()
        }
        return START_STICKY // restart after a system kill — reliability first
    }

    // ═══ Start / stop ═══════════════════════════════════════

    /**
     * (Re)starts just the camera, assuming the server is already listening.
     *
     * Split out of startEverything() so the "Start stream" button can resume
     * streaming without tearing down and rebinding the HTTP listener, which
     * would drop every other connected client for no reason.
     */
    private fun startCamera() {
        if (!cameraRunning.compareAndSet(false, true)) return
        try {
            cameraManager.onFrameCaptured = { jpeg, _ -> onCameraFrame(jpeg) }
            cameraManager.onFrameHeartbeat = { lastFrameAt = System.currentTimeMillis() }
            cameraManager.start { msg -> reportError(msg) }
            lastFrameAt = System.currentTimeMillis()
            updateNotification("Streaming on port ${config.port}")
        } catch (e: Exception) {
            cameraRunning.set(false)
            reportError("Camera start failed: ${e.message}")
        }
    }

    private fun startEverything() {
        if (!started.compareAndSet(false, true)) return

        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Starting camera…"))
        acquireWakeLock()

        val port = config.port
        streamServer = StreamServer(
            applicationContext, cameraManager, motionDetector, motionRecorder,
            onvifDiscovery, config, port
        )

        // Security pipeline: every frame → motion detector → recorder
        if (config.securityEnabled) {
            motionDetector.enabled = true
            motionRecorder.enabled = config.motionRecord
            motionDetector.setSensitivity(config.motionSensitivity)
            motionRecorder.preRecordSeconds = config.preRecordSeconds
            motionRecorder.maxClipSeconds = config.maxClipSeconds
        }
        MotionRecorder.lastWidth = cameraManager.currentTargetWidth
        MotionRecorder.lastHeight = cameraManager.currentTargetHeight

        cameraManager.onFrameCaptured = { jpeg, _ -> onCameraFrame(jpeg) }
        cameraManager.onFrameHeartbeat = { lastFrameAt = System.currentTimeMillis() }
        cameraManager.start { msg -> reportError(msg) }
        cameraRunning.set(true)

        try {
            streamServer?.start()
        } catch (e: Exception) {
            reportError("Server failed to bind port $port: ${e.message}")
        }

        onvifDiscovery.setLocalIp(localIpAddress())
        onvifDiscovery.httpPort = port
        onvifDiscovery.deviceName = config.deviceName
        onvifDiscovery.start()

        startWatchdog()
        updateNotification("Streaming on port $port")
    }

    private fun onCameraFrame(jpeg: ByteArray) {
        lastFrameAt = System.currentTimeMillis()
        if (!motionDetector.enabled && !motionRecorder.enabled) {
            motionRecorder.stopAllIfIdle()
            return
        }
        try {
            val bmp = android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
            if (bmp != null) {
                val motion = motionDetector.process(bmp)
                bmp.recycle()
                motionRecorder.onFrame(
                    jpeg,
                    isMotion = motion,
                    fps = cameraManager.frameHub.fps.coerceAtLeast(1)
                )
            }
        } catch (_: Exception) {}
    }

    /**
     * Stops only the camera, leaving the HTTP server up.
     *
     * The WebUI has to stay reachable after "Stop stream" — otherwise the page
     * loses the very endpoint it needs to report that the stream stopped, and
     * the button is stuck showing "Stop stream" forever. The server is torn
     * down only when the service itself dies.
     */
    private fun stopCameraOnly() {
        if (!cameraRunning.compareAndSet(true, false)) return
        try { cameraManager.stopHls() } catch (_: Exception) {}
        try { cameraManager.stop() } catch (_: Exception) {}
        try { motionRecorder.finishClip() } catch (_: Exception) {}
        updateNotification("Camera stopped")
    }

    /** Full teardown: camera, server, discovery, wakelock. */
    private fun stopEverything() {
        if (!started.compareAndSet(true, false)) return
        stopCameraOnly()
        try { streamServer?.stopServer() } catch (_: Exception) {}
        streamServer = null
        try { onvifDiscovery.stop() } catch (_: Exception) {}
        try { cameraManager.stop() } catch (_: Exception) {}
        try { motionRecorder.finishClip() } catch (_: Exception) {}
        releaseWakeLock()
    }

    override fun onDestroy() {
        stopEverything()
        try { watchdog.shutdownNow() } catch (_: Exception) {}
        instance = null
        super.onDestroy()
    }

    // ═══ Reliability ═════════════════════════════════════════

    private fun startWatchdog() {
        watchdog.execute {
            while (started.get()) {
                try { Thread.sleep(15_000) } catch (_: InterruptedException) { return@execute }
                if (!started.get()) return@execute
                val now = System.currentTimeMillis()
                // No frames for 30s while we think we are streaming → rebind camera
                if (cameraManager.isStreaming && lastFrameAt > 0 && now - lastFrameAt > 30_000) {
                    reportError("No frames for 30s — restarting camera")
                    restartCamera()
                    lastFrameAt = now
                }
            }
        }
    }

    /** Rebind the camera without tearing down the HTTP server. */
    private fun restartCamera() {
        try {
            cameraManager.stop()
            cameraManager.start { msg -> reportError(msg) }
        } catch (e: Exception) {
            reportError("Camera restart failed: ${e.message}")
        }
    }

    fun restartCameraNow() = restartCamera()

    private fun reportError(msg: String) {
        lastError = msg
        android.util.Log.w("OcuBea", msg)
        for (l in errorListeners) {
            try { l(msg) } catch (_: Exception) {}
        }
        updateNotification("Error: $msg")
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OcuBea:stream").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {}
    }

    private fun releaseWakeLock() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        wakeLock = null
    }

    // ═══ Notification ════════════════════════════════════════

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "Camera streaming", NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("OcuBea is streaming")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(
                android.app.PendingIntent.getActivity(
                    this, 0,
                    Intent(this, com.ocubea.MainActivity::class.java),
                    android.app.PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()

    private fun updateNotification(text: String) {
        try {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildNotification(text))
        } catch (_: Exception) {}
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
}

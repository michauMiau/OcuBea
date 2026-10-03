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
import com.ocubea.CrashLogger
import com.ocubea.R
import com.ocubea.camera.CameraManager
import com.ocubea.model.OcuBeaConfig
import com.ocubea.onvif.OnvifDiscovery
import com.ocubea.security.MotionDetector
import com.ocubea.security.MotionRecorder
import com.ocubea.security.MotionTonePlayer
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

        /**
         * Frames of stillness that end a motion clip. At the real frame rate
         * this camera delivers under load, 30 frames is a few seconds — long
         * enough to bridge a pause in the action, short enough that the clip
         * does not keep recording an empty room.
         */
        const val MOTION_POST_FRAMES = 30
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

    /**
     * The audible motion notification.
     *
     * Created here rather than in StreamServer because the trigger is a motion
     * edge in this service's frame callback: a tone played from the HTTP handler
     * would sound once per *request* instead of once per *event*, which is a
     * different feature wearing the same name.
     *
     * Reachable from the HTTP layer through [instance], and /status.json reads
     * it there -- one player, one set of counters.
     */
    @Volatile var motionTone: MotionTonePlayer? = null
        private set

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
        motionTone = MotionTonePlayer(OcuBeaConfig(applicationContext))
        motionDetector = MotionDetector(config.motionSensitivity)
        cameraManager = CameraManager(applicationContext, config).also {
            it.lifecycleOwner = this
            it.onCameraError = { msg -> reportError(msg) }
            // Motion detection needs frames even with zero MJPEG viewers, or
            // the feature silently never runs. The service owns both objects,
            // so it answers the question rather than the camera reaching in.
            it.motionActiveProvider = { motionDetector.enabled || motionRecorder.enabled }
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
            updateNotification(getString(R.string.streaming_started, config.port))
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
            // Clip path: the encoder sits idle until motion shows up, so arming
            // it costs one MediaCodec instance and nothing else. The AVI
            // recorder above stays for /recordings, which is the IP Webcam
            // compatibility surface and must keep its old format.
            if (config.motionRecord) cameraManager.startClipRecording(0, onDemand = false)
        }
        // Retention runs regardless of the security toggle: a user who turned
        // motion recording off may still have old clips on disk, and the limits
        // have to keep applying to them.
        ensureClipRetention()
        MotionRecorder.lastWidth = cameraManager.currentTargetWidth
        MotionRecorder.lastHeight = cameraManager.currentTargetHeight

        cameraManager.onFrameCaptured = { jpeg, _ -> onCameraFrame(jpeg) }
        cameraManager.onFrameHeartbeat = { lastFrameAt = System.currentTimeMillis() }
        // Marked running BEFORE start(). start() is asynchronous -- the provider
        // future resolves later -- so a start arriving in that window used to
        // pass startCamera()'s compare-and-set and open the camera twice.
        cameraRunning.set(true)
        cameraManager.start { msg -> reportError(msg) }

        try {
            streamServer?.start()
        } catch (e: Exception) {
            reportError("Server failed to bind port $port: ${e.message}")
        }

        onvifDiscovery.setLocalIp(localIpAddress())
        onvifDiscovery.httpPort = port
        onvifDiscovery.deviceName = config.deviceName
        onvifDiscovery.start()

        // Restore RTSP when the user asked for it and this is a restart.
        //
        // Measured missing: after any restart the preference still said enabled
        // while nothing was listening -- status.json reported running: false,
        // port: 0, which is a setting that survived but nothing it promised.
        // HLS recovers on its own via startHls() on the first playlist request;
        // RTSP is not asked for that way, so it has to be restored here, beside
        // the other listeners. Reported rather than swallowed, because a bind
        // failure the user cannot see is the same silent lie as before.
        if (config.rtspEnabled) {
            streamServer?.restoreRtspListener()
        }

        startWatchdog()
        updateNotification(getString(R.string.streaming_started, port))
    }

    private fun onCameraFrame(jpeg: ByteArray) {
        lastFrameAt = System.currentTimeMillis()
        val clipArmed = cameraManager.isClipArmed
        if (!motionDetector.enabled && !motionRecorder.enabled && !clipArmed) {
            motionRecorder.stopAllIfIdle()
            return
        }
        try {
            // The detector decodes the JPEG itself, with inSampleSize, so the
            // frame is never expanded to two megapixels just to be shrunk to a
            // 32x24 grid. It owns recycling whatever it allocates.
            val motion = if (motionDetector.enabled) motionDetector.processJpeg(jpeg) else false
            // The tone fires on the RISING EDGE only, which is what
            // `sound_event` names in the API. It is checked against the detector's
            // own state rather than `motion`, because `motion` stays true for
            // MotionDetector's 2s grace period after the movement has stopped --
            // a tone on every frame of that grace would be a burst of beeps
            // where the client asked for one notification.
            if (motion && !motionWasActive) motionTone?.onMotionEvent(System.currentTimeMillis())
            motionWasActive = motion
            motionRecorder.onFrame(
                jpeg,
                isMotion = motion,
                fps = cameraManager.frameHub.fps.coerceAtLeast(1)
            )
            // Motion now gates the fMP4 clip path too, not just the old AVI
            // recorder. A separate on-demand clip in progress is not
            // disturbed: it was started by the user and runs on its own
            // duration or an explicit stop.
            if (clipArmed) {
                if (motion) {
                    if (!cameraManager.isRecordingClip) startMotionClip()
                } else if (cameraManager.isRecordingClip && !cameraManager.isOnDemandClip) {
                    motionIdleFrames++
                    if (motionIdleFrames >= MOTION_POST_FRAMES) {
                        cameraManager.stopClipRecording()
                        motionIdleFrames = 0
                    }
                }
            }
        } catch (_: Exception) {}
    }

    /**
     * Starts a clip because motion was seen.
     *
     * The clip is armed rather than opened: the file appears on the first
     * keyframe, which is a frame or two away, and motion is already past by
     * then. What we keep is the encoded samples themselves, so the clip still
     * starts at the moment of the motion.
     */
    private fun startMotionClip() {
        val seconds = config.maxClipSeconds
        if (cameraManager.startClipRecording(seconds, onDemand = false)) {
            motionIdleFrames = 0
        }
    }

    private var motionIdleFrames = 0

    /**
     * Whether motion was active on the previous analysed frame.
     *
     * The edge detector's own state, kept here rather than read back from the
     * detector: `motionDetected` is cleared after a 2s grace, so it already
     * tracks edges -- but a reset when the detector is switched off has to
     * re-arm it, and doing that from the same field that fires the tone keeps
     * the two in step by construction.
     */
    @Volatile private var motionWasActive = false

    private var clipRetention: com.ocubea.security.ClipRetentionScheduler? = null

    private fun ensureClipRetention() {
        if (clipRetention != null) return
        clipRetention = com.ocubea.security.ClipRetentionScheduler(
            applicationContext,
            limits = {
                com.ocubea.security.ClipRetentionScheduler.Limits(
                    maxBytes = config.clipMaxSpaceMb.coerceIn(64, 1024 * 64) * 1024L * 1024L,
                    maxAgeMs = config.clipMaxAgeHours.coerceIn(1, 24 * 90) * 3600_000L,
                    maxFiles = config.clipMaxFiles.coerceIn(10, 5000),
                )
            },
        ).also { scheduler ->
            scheduler.start()
            // One hook covers every path that ends a clip: on-demand stop, the
            // motion timeout, the duration deadline and camera shutdown.
            cameraManager.onClipClosed = {
                scheduler.protect(null)
                scheduler.scheduleAfterClose()
            }
            cameraManager.onClipOpened = { name -> scheduler.protect(name) }
        }
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
        updateNotification(getString(R.string.streaming_stopped))
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
        // The native audio handle a ToneGenerator holds. Not releasing it leaks
        // an audio resource per stop, and a phone that has been started and
        // stopped a few times by the watchdog starts failing to open one --
        // which would then be reported as `sound unavailable`, a lie caused by
        // this class rather than by the device.
        try { motionTone?.release() } catch (_: Exception) {}
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
                if (cameraManager.isStreaming && !cameraManager.isRetryPending &&
                    lastFrameAt > 0 && now - lastFrameAt > 30_000) {
                    reportError("No frames for 30s — restarting camera")
                    restartCamera()
                    lastFrameAt = now
                }
            }
        }
    }

    /** Rebind the camera without tearing down the HTTP server. */
    private fun restartCamera() {
        // The watchdog must not resurrect a camera the user deliberately
        // stopped: cameraRunning is false exactly when the camera is meant off,
        // and this method bypassed the flag entirely, so a Stop followed by a
        // 30 s gap would silently turn the camera back on.
        if (!cameraRunning.get()) return
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

package com.ocubea.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.ocubea.camera.CameraManager
import com.ocubea.onvif.OnvifDiscovery
import com.ocubea.security.MotionDetector
import com.ocubea.security.MotionRecorder
import com.ocubea.server.StreamServer
import java.io.File
import java.net.NetworkInterface

/**
 * Foreground service keeping camera + HTTP server alive while the app is
 * minimized or the screen is off. This is what makes OcuBea a *reliable*
 * IP webcam instead of a screen-bound toy.
 */
class StreamService : LifecycleService() {

    private lateinit var cameraManager: CameraManager
    private lateinit var streamServer: StreamServer
    private val onvifDiscovery by lazy { OnvifDiscovery(this) }
    private val motionDetector = MotionDetector()
    private val motionRecorder = MotionRecorder(File(getExternalFilesDir(null) ?: filesDir, "recordings"))

    companion object {
        const val CHANNEL_ID = "ocubea_stream"
        const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "com.ocubea.STOP_STREAM"

        @Volatile var instance: StreamService? = null
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        cameraManager = CameraManager(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> { stopEverything(); stopSelf(); return START_NOT_STICKY }
            else -> startEverything()
        }
        return START_STICKY // restart after system kill — reliability first
    }

    private fun startEverything() {
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        val prefs = getSharedPreferences("ocubea", Context.MODE_PRIVATE)
        val port = prefs.getInt("port", 8080)
        val securityEnabled = prefs.getBoolean("security_enabled", false)

        streamServer = StreamServer(applicationContext, cameraManager, motionDetector, motionRecorder, onvifDiscovery, port)
        streamServer.setFrontCameraStateProvider { cameraManager.isUsingFrontCamera() }

        // Security pipeline: every frame → motion detector → recorder
        if (securityEnabled) { motionDetector.enabled = true; motionRecorder.enabled = true }
        MotionRecorder.lastWidth = cameraManager.currentTargetWidth
        MotionRecorder.lastHeight = cameraManager.currentTargetHeight
        cameraManager.onFrameCaptured = { jpeg, _ ->
            if (motionDetector.enabled || motionRecorder.enabled) {
                try {
                    val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
                    if (bmp != null) {
                        val motion = motionDetector.process(bmp)
                        bmp.recycle()
                        motionRecorder.onFrame(
                            jpeg, isMotion = motion,
                            fps = cameraManager.frameHub.fps.coerceAtLeast(1)
                        )
                    }
                } catch (_: Exception) {}
            } else {
                motionRecorder.stopAllIfIdle()
            }
        }

        cameraManager.start(lifecycleOwner = this)
        try { streamServer.start() } catch (e: Exception) { println("Server start failed: ${e.message}") }
        onvifDiscovery.setLocalIp(localIpAddress())
        onvifDiscovery.httpPort = port
        onvifDiscovery.deviceName = prefs.getString("device_name", "OcuBea") ?: "OcuBea"
        onvifDiscovery.start()
    }

    private fun stopEverything() {
        try { onvifDiscovery.stop() } catch (_: Exception) {}
        try { if (this::streamServer.isInitialized) streamServer.stopServer() } catch (_: Exception) {}
        try { cameraManager.stop() } catch (_: Exception) {}
        instance = null
    }

    override fun onDestroy() {
        stopEverything()
        super.onDestroy()
    }

    private fun localIpAddress(): String {
        try {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                for (addr in nif.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) return addr.hostAddress ?: "0.0.0.0"
                }
            }
        } catch (_: Exception) {}
        return "0.0.0.0"
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Camera streaming", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("OcuBea is streaming")
            .setContentText("Camera server running — tap to manage")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
}

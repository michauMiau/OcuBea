package com.ocubea.server

import android.content.Context
import com.ocubea.stream.VideoEncoder.EncodingConfig
import fi.iki.elonen.NanoHTTPD
import java.util.concurrent.locks.ReentrantLock
import kotlin.math.min

/**
 * HTTP server serving the streaming interface and IP Webcam-compatible API.
 * Built on Nanohttpd v3.x for lightweight embedded use.
 */
class StreamServer(private val port: Int = 8080) : NanoHTTPD(port) {

    var streamUrl: String = ""

    private lateinit var cameraManager: com.ocubea.camera.CameraManager
    private lateinit var videoEncoder: com.ocubea.stream.VideoEncoder
    private val encoderConfig = EncodingConfig()

    // MJPEG state
    private val frameLock = ReentrantLock()
    private var lastFrameBytes: ByteArray? = null
    private var streamRunning = false
    private var nightVisionOn = true
    private var ffcEnabled = false
    private var zoomLevel = 1.0f

    /** Initialize with context reference */
    fun init(cameraMgr: com.ocubea.camera.CameraManager, encoder: com.ocubea.stream.VideoEncoder) {
        this.cameraManager = cameraMgr
        this.videoEncoder = encoder
    }

    override fun start() {
        super.start(NanoHTTPD.SOCKET_READ_TIMEOUT)
        println("Stream server started on port $port")
    }

    override fun stop() {
        videoEncoder.stop()
        cameraManager.stop()
        super.stop()
        streamRunning = false
        lastFrameBytes = null
        println("Stream server stopped")
    }

    /** Exposed for MJPEG streaming */
    fun updateLastFrame(frame: ByteArray) {
        frameLock.lock()
        try {
            lastFrameBytes = frame.copyOf()
        } finally {
            frameLock.unlock()
        }
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri ?: "/"
        val method = session.method ?: NanoHTTPD.Method.GET

        return when (uri) {
            // --- MJPEG stream endpoint (IP Webcam compatible) ---
            "/video" -> handleMjpegStream(session)

            // --- Single frame snapshot ---
            "/shot.jpg" -> handleShotJpg()

            // --- Audio streams (stubbed - require separate audio encoder) ---
            "/audio.wav", "/audio.aac", "/audio.opus" -> handleAudioStub(uri.substringAfterLast('.'))

            // --- Focus control ---
            "/focus" -> { cameraManager.setFocus(1.0f); createResponse(Response.Status.OK, "text/plain", "OK") }
            "/nofocus" -> { cameraManager.setFocus(0f); createResponse(Response.Status.OK, "text/plain", "OK") }

            // --- Settings (POST endpoints) ---
            inUriAndMethod(uri, NanoHTTPD.Method.POST, "/settings/") -> handleSettingsPost(session)

            // --- PTZ / Zoom control ---
            inUriAndMethod(uri, NanoHTTPD.Method.POST, "/ptz") -> handlePtzPost(session)

            // --- Main streaming page (loaded from assets/index.html) ---
            "/", "/index.html" -> createResponse(Response.Status.OK, "text/html", loadHtmlFromAssets())

            else -> createResponse(Response.Status.NOT_FOUND, "text/plain", "404 Not Found")
        }
    }

    // ==================== ASSETS LOADING ====================

    /** Load HTML page from assets folder */
    private fun loadHtmlFromAssets(): String {
        return try {
            val inputStream = javaClass.classLoader?.getResourceAsStream("assets/index.html")
                ?: throw IllegalStateException("index.html not found in assets/")
            inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            "<html><body><h1>OcuBea - IP Camera Streamer</h1><p>Error loading index.html from assets.</p></body></html>"
        }
    }

    // ==================== MJPEG STREAM ====================

    private fun handleMjpegStream(session: IHTTPSession): NanoHTTPD.Response {
        return StreamingMjpegResponse(session, "--BoundaryString")
    }

    private inner class StreamingMjpegResponse(
        private val session: IHTTPSession,
        private val boundary: String
    ) : Response(NanoHTTPD.Status.OK) {

        init {
            addHeader("Content-Type", "multipart/x-mixed-replace; boundary=$boundary")
            addHeader("Access-Control-Allow-Origin", "*")

            // Stream MJPEG frames in a background thread
            Thread({
                try {
                    while (streamRunning || lastFrameBytes != null) {
                        frameLock.lock()
                        val frame = lastFrameBytes?.copyOf()
                        frameLock.unlock()

                        if (frame != null) {
                            // Write MJPEG boundary and headers directly to output stream
                            bodyOutputStream.write("--$boundary\r\n".toByteArray())
                            bodyOutputStream.write("Content-Type: image/jpeg\r\n".toByteArray())
                            bodyOutputStream.write("Content-Length: ${frame.size}\r\n\r\n".toByteArray())

                            // Write frame data in chunks
                            var offset = 0
                            while (offset < frame.size) {
                                val toSend = min(8192, frame.size - offset)
                                bodyOutputStream.write(frame, offset, toSend)
                                offset += toSend
                            }
                            bodyOutputStream.flush()
                        } else {
                            Thread.sleep(50) // Wait for next frame
                        }
                    }

                    // Send final boundary
                    bodyOutputStream.write("--$boundary--\r\n".toByteArray())
                    bodyOutputStream.flush()
                } catch (e: Exception) {
                    // Client disconnected or error — normal during streaming
                } finally {
                    try { bodyOutputStream.close() } catch (_: Exception) {}
                }
            }, "mjpeg-stream").start()
        }
    }

    private fun inUriAndMethod(uri: String, method: NanoHTTPD.Method, prefix: String): Boolean {
        return uri.startsWith(prefix) && method == NanoHTTPD.Method.POST
    }

    // ==================== SHOT.JPG ====================

    private fun handleShotJpg(): Response {
        frameLock.lock()
        val frame = lastFrameBytes?.copyOf()
        frameLock.unlock()

        if (frame != null) {
            return createRawByteResponse(Response.Status.OK, "image/jpeg", frame)
        }

        // Return a minimal valid JPEG (1x1 pixel) as fallback
        val fallbackJpeg = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00.toByte(), 0x10.toByte(),
            0x4A.toByte(), 0x46.toByte(), 0x49.toByte(), 0x46.toByte(), 0x00.toByte(), 0x01.toByte(),
            0x01.toByte(), 0x00.toByte(), 0x00.toByte(), 0x01.toByte(), 0x00.toByte(), 0x01.toByte(),
            0x00.toByte(), 0x00.toByte(), 0xFF.toByte(), 0xDB.toByte(), 0x00.toByte(), 0x43.toByte()
        )
        return createRawByteResponse(Response.Status.OK, "image/jpeg", fallbackJpeg)
    }

    // ==================== AUDIO STUBS ====================

    private fun handleAudioStub(format: String): Response {
        // Audio streaming requires a separate audio encoder (MediaRecorder / AAC encoder)
        // This is a stub — returns empty response with correct content type
        return createResponse(
            Response.Status.NOT_IMPLEMENTED,
            "audio/${format}",
            "Audio streaming not yet implemented. Use video stream for visual monitoring."
        )
    }

    // ==================== SETTINGS (POST) ====================

    private fun handleSettingsPost(session: IHTTPSession): Response {
        val path = session.uri ?: ""
        val params = session.queryParameterMap ?: emptyMap()

        when {
            path.contains("night_vision") -> {
                val setValue = params["set"]?.lowercase()
                nightVisionOn = (setValue != "off")
                cameraManager.applyNightVision(nightVisionOn)
                return createResponse(Response.Status.OK, "text/plain", if (nightVisionOn) "ON" else "OFF")
            }

            path.contains("ffc") -> {
                val setValue = params["set"]?.lowercase()
                ffcEnabled = (setValue == "on")
                cameraManager.setFrontFacingCamera(ffcEnabled)
                return createResponse(Response.Status.OK, "text/plain", if (ffcEnabled) "ON" else "OFF")
            }

            path.contains("quality") -> {
                val qualityValue = params["set"]?.toIntOrNull() ?: 90
                encoderConfig.bitrateKbps = qualityValue * 10 // rough mapping
                return createResponse(Response.Status.OK, "text/plain", "Quality set to $qualityValue")
            }

            path.contains("resolution") -> {
                val resValue = params["set"] ?: "720"
                val config = EncodingConfig(
                    width = encoderConfig.width,
                    height = when (resValue.toIntOrNull()) {
                        in 1080..Int.MAX_VALUE -> 1920
                        else -> 1280 // default to HD720
                    },
                    frameRate = encoderConfig.frameRate,
                    bitrateKbps = encoderConfig.bitrateKbps,
                    codec = encoderConfig.codec
                )
                videoEncoder.stop()
                if (videoEncoder.start(config)) {
                    return createResponse(Response.Status.OK, "text/plain", "Resolution changed to $resValue")
                }
            }

            else -> return createResponse(Response.Status.BAD_REQUEST, "text/plain", "Unknown setting: $path")
        }

        return createResponse(Response.Status.OK, "text/plain", "OK")
    }

    // ==================== PTZ / ZOOM (POST) ====================

    private fun handlePtzPost(session: IHTTPSession): Response {
        val params = session.queryParameterMap ?: emptyMap()

        when {
            params.containsKey("zoom") -> {
                zoomLevel = params["zoom"]?.toFloatOrNull()?.div(10f)?.coerceIn(1f, 5f) ?: 1f
                cameraManager.setZoom(zoomLevel)
                return createResponse(Response.Status.OK, "text/plain", "Zoom: ${params["zoom"]}")
            }

            params.containsKey("pan") -> {
                val panValue = params["pan"]?.toFloatOrNull() ?: 0f
                return createResponse(Response.Status.OK, "text/plain", "Pan: $panValue")
            }

            params.containsKey("tilt") -> {
                val tiltValue = params["tilt"]?.toFloatOrNull() ?: 0f
                return createResponse(Response.Status.OK, "text/plain", "Tilt: $tiltValue")
            }

            else -> return createResponse(Response.Status.BAD_REQUEST, "text/plain", "PTZ requires zoom/pan/tilt parameter")
        }
    }

    // ==================== HELPERS ====================

    private fun createResponse(status: NanoHTTPD.Response.Status, contentType: String, body: String): Response {
        return object : NanoHTTPD.Response(status) {
            override fun sendHeaders() {}
            override fun sendBody() {}
        }.apply {
            addHeader("Content-Type", "$contentType; charset=utf-8")
            addHeader("Access-Control-Allow-Origin", "*")
        }
    }

    private fun createRawByteResponse(status: NanoHTTPD.Response.Status, contentType: String, data: ByteArray): Response {
        return object : NanoHTTPD.Response(status) {
            override fun sendHeaders() {}
            override fun sendBody() {}
        }.apply {
            addHeader("Content-Type", "$contentType; charset=binary")
            addHeader("Access-Control-Allow-Origin", "*")
        }
    }
}

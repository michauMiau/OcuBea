package com.ocubea.server

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.OutputStream
import java.net.URLDecoder

/**
 * HTTP server for MJPEG streaming, snapshots, and torch control.
 */
class StreamServer(private val context: Context) : NanoHTTPD(9090) {

    private var cameraManager: com.ocubea.camera.CameraManager? = null
    @Volatile private var isStreaming = false

    fun setCameraManager(cm: com.ocubea.camera.CameraManager) {
        this.cameraManager = cm
    }

    override fun start() {
        try {
            super.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            println("Stream server started on port 9090")
        } catch (e: Exception) {
            println("Failed to start stream server: ${e.message}")
        }
    }

    fun stopServer() {
        try {
            cameraManager?.stopStreaming()
            super.stop()
            isStreaming = false
            println("Stream server stopped")
        } catch (e: Exception) {
            println("Error stopping server: ${e.message}")
        }
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri ?: "/"

        return when {
            uri == "/" || uri == "/index.html" -> serveWebpage(session)

            // MJPEG streaming — both /video and /mjpeg are valid per IP Webcam spec
            uri == "/video" || uri == "/mjpeg" || uri == "/stream" -> handleMjpegStream()

            // Snapshot endpoint (JPEG binary) with optional quality parameter
            uri.startsWith("/shot.jpg") || uri == "/snapshot.jpg" -> handleShot(session)

            // Camera focus control — IP Webcam spec uses /focus and /nofocus
            uri == "/focus" && session.method == Method.POST -> handleFocus()
            uri == "/nofocus" && session.method == Method.POST -> handleNoFocus()

            // Settings endpoints per IP Webcam spec: /settings/<name>?set=<value>
            uri.startsWith("/settings/") && session.method == Method.POST -> handleSettings(session)

            // PTZ control (pan/tilt/zoom) — IP Webcam uses /ptt endpoint
            uri == "/ptt" && session.method == Method.POST -> handlePtz(session)

            // Audio streaming endpoints — wav, aac, opus formats
            uri.startsWith("/audio/") && session.method == Method.GET -> handleAudioStream(session)

            // Settings endpoint for various IP Webcam features
            uri == "/settings" && session.method == Method.POST -> handleSettingsBulk(session)

            // Status/info endpoint (commonly used by clients)
            uri == "/status.json" || uri == "/info" -> handleStatus()
            uri == "/api/torch" && session.method == Method.POST -> handleTorch(session)
            uri == "/api/camera" && session.method == Method.POST -> handleCameraSwitch(session)

            else -> newFixedLengthResponse(
                NanoHTTPD.Response.Status.NOT_FOUND, "text/plain", "Not found"
            )
        }
    }

    /** Handle MJPEG streaming — returns a response with multipart/x-mixed-replace content type. */
    private fun handleMjpegStream(): Response {
        if (!isStreaming && cameraManager != null) {
            startVideoStream()
        }

        // Return a response with empty body — real data streamed via send()
        val boundary = "--frame".toByteArray(Charsets.UTF_8)
        return StreamingMjpegResponse(boundary)
    }

    /** Start MJPEG video stream from camera */
    private fun startVideoStream() {
        isStreaming = true
        try {
            cameraManager?.startStreaming(onError = { error ->
                println("Streaming error: $error")
                isStreaming = false
            })
            println("MJPEG streaming started")
        } catch (e: Exception) {
            isStreaming = false
            println("Failed to start video stream: ${e.message}")
        }
    }

    /** Serve single snapshot JPEG with optional quality/rotation parameters */
    private fun handleShot(session: IHTTPSession): Response {
        val params = parseQueryParameters(session)
        
        // IP Webcam supports ?quality=N (JPEG quality 1-100, default 85) and ?rotation=90|180|270
        // For now we just acknowledge these parameters; actual implementation would apply them
        if (params.containsKey("quality")) {
            val quality = params["quality"]?.toIntOrNull() ?: 85
            println("Shot quality requested: $quality% (using default JPEG encoding)")
        }
        
        if (params.containsKey("rotation")) {
            val rotation = params["rotation"]?.toIntOrNull() ?: 0
            println("Shot rotation requested: ${rotation}° (not yet implemented — returning unrotated)")
        }

        return try {
            val frameData = cameraManager?.getLatestFrame() ?: run {
                newFixedLengthResponse(
                    NanoHTTPD.Response.Status.NOT_FOUND, "image/jpeg", ""
                )
            }

            if (frameData.isEmpty()) {
                newFixedLengthResponse(
                    NanoHTTPD.Response.Status.NO_CONTENT, "image/jpeg", ""
                )
            } else {
                // Return raw bytes via ChunkedResponse — binary-safe in NanoHTTPD 2.3.1
                newChunkedResponse(
                    NanoHTTPD.Response.Status.OK,
                    "image/jpeg",
                    ByteArrayInputStream(frameData)
                )
            }
        } catch (e: Exception) {
            newFixedLengthResponse(
                NanoHTTPD.Response.Status.INTERNAL_ERROR, "text/plain",
                "error: ${e.message}"
            )
        }
    }

    /** Handle focus via POST /focus */
    private fun handleFocus(session: IHTTPSession): Response {
        return try {
            cameraManager?.setFocus(0.5f) // center focus default
            newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "text/plain", "ok")
        } catch (e: Exception) {
            newFixedLengthResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, "text/plain", "error: ${e.message}")
        }
    }

    /** Handle nofocus via POST /nofocus — release focus */
    private fun handleNoFocus(session: IHTTPSession): Response {
        // IP Webcam spec: /nofocus releases focus (continuous AF off)
        return newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "text/plain", "ok")
    }

    /** Handle settings endpoints — /settings/night_vision?set=on|off etc. */
    private fun handleSettings(session: IHTTPSession): Response {
        val params = parseQueryParameters(session)
        return try {
            // Parse path to determine which setting: /settings/<name>
            val uri = session.uri ?: ""
            val settingName = uri.substringAfterLast("/")

            when (settingName.lowercase()) {
                "night_vision" -> {
                    val value = params["set"]?.lowercase() ?: "off"
                    if (value != "on" && value != "off") {
                        return newFixedLengthResponse(
                            NanoHTTPD.Response.Status.BAD_REQUEST, "text/plain",
                            "Invalid value. Use: on|off"
                        )
                    }
                    val enabled = value == "on"
                    cameraManager?.setNightVision(enabled)
                }
                "ffc" -> {
                    val value = params["set"]?.lowercase() ?: "off"
                    if (value != "on" && value != "off") {
                        return newFixedLengthResponse(
                            NanoHTTPD.Response.Status.BAD_REQUEST, "text/plain",
                            "Invalid value. Use: on|off"
                        )
                    }
                    cameraManager?.setFrontFacingCamera(value == "on")
                }
                "quality" -> {
                    val qualityStr = params["set"] ?: "720"
                    // IP Webcam uses quality as a number (e.g., 90, 80) mapped to resolution
                    val quality = qualityStr.toIntOrNull() ?: 720
                    val res = when {
                        quality >= 1080 -> CameraConfig.Resolution.FullHD
                        quality >= 720 -> CameraConfig.Resolution.HD720
                        quality >= 480 -> CameraConfig.Resolution.VGA
                        else -> CameraConfig.Resolution.QVGA
                    }
                    cameraManager?.setQuality(res)
                }
                else -> {
                    return newFixedLengthResponse(
                        NanoHTTPD.Response.Status.NOT_FOUND, "text/plain",
                        "Unknown setting: $settingName"
                    )
                }
            }

            newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "text/plain", "ok")
        } catch (e: Exception) {
            newFixedLengthResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, "text/plain", "error: ${e.message}")
        }
    }

    /** Handle PTZ (pan/tilt/zoom) via POST /ptt?zoom=22 */
    private fun handlePtz(session: IHTTPSession): Response {
        val params = parseQueryParameters(session)
        return try {
            // Pan/Tilt not supported (fixed mount), but zoom is handled here
            if (params.containsKey("zoom")) {
                val level = params["zoom"]?.toFloatOrNull() ?: 1.0f
                cameraManager?.setZoom(level)
            }

            newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "text/plain", "ok")
        } catch (e: Exception) {
            newFixedLengthResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, "text/plain", "error: ${e.message}")
        }
    }

    /** Handle torch control via POST /api/torch?on=true|false */
    private fun handleTorch(session: IHTTPSession): Response {
        val params = parseQueryParameters(session)
        return try {
            if (context.checkSelfPermission(android.Manifest.permission.CAMERA) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                return newFixedLengthResponse(
                    NanoHTTPD.Response.Status.FORBIDDEN, "application/json",
                    """{"status": "error", "message": "Camera permission required"}"""
                )
            }

            val cm = context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager

            var foundCameraId: String? = null
            for (cameraId in cm.cameraIdList) {
                val chars = cm.getCameraCharacteristics(cameraId)

                val hasFlash = try {
                    chars.get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                } catch (_: Exception) { continue }

                if (!hasFlash) continue

                val facing = try {
                    chars.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING)
                } catch (_: Exception) { continue }

                if (facing == 1) { // CameraCharacteristics.LENS_FACING_BACK
                    foundCameraId = cameraId
                    break
                }
            }

            if (foundCameraId == null) {
                return newFixedLengthResponse(
                    NanoHTTPD.Response.Status.BAD_REQUEST, "application/json",
                    """{"status": "error", "message": "No torch available on back camera"}"""
                )
            }

            val shouldEnable = params["on"]?.toBooleanStrictOrNull() ?: false
            cm.setTorchMode(foundCameraId, shouldEnable)

            newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK, "application/json",
                """{"status": "ok", "torch": ${if (shouldEnable) "true" else "false"}, "camera_id": "$foundCameraId"}"""
            )
        } catch (e: Exception) {
            newFixedLengthResponse(
                NanoHTTPD.Response.Status.INTERNAL_ERROR, "application/json",
                """{"status": "error", "message": "${e.message}"}"""
            )
        }
    }

    /** Handle camera switch via POST /api/camera?set=true|false */
    private fun handleCameraSwitch(session: IHTTPSession): Response {
        val params = parseQueryParameters(session)
        return try {
            val isFront = params["set"]?.toBooleanStrictOrNull() ?: false
            cameraManager?.setFrontFacingCamera(isFront)

            newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK, "application/json",
                """{"status": "ok", "camera": ${if (isFront) "front" else "back"}}"""
            )
        } catch (e: Exception) {
            newFixedLengthResponse(
                NanoHTTPD.Response.Status.INTERNAL_ERROR, "application/json",
                """{"status": "error", "message": "${e.message}"}"""
            )
        }
    }

    /** Handle audio streaming endpoints — /audio.wav, /audio.aac, /audio.opus */
    private fun handleAudioStream(session: IHTTPSession): Response {
        val uri = session.uri ?: ""
        val format = uri.substringAfterLast("/") // "wav", "aac", or "opus"

        return try {
            // Audio capture not yet implemented — return placeholder silence
            // IP Webcam spec expects raw PCM/WAV data from microphone
            println("Audio stream requested: $format (not yet implemented)")

            newFixedLengthResponse(
                NanoHTTPD.Response.Status.NOT_IMPLEMENTED, "audio/x-wav",
                ""  // Empty response until audio capture is added
            )
        } catch (e: Exception) {
            newFixedLengthResponse(
                NanoHTTPD.Response.Status.INTERNAL_ERROR, "text/plain",
                "error: ${e.message}"
            )
        }
    }

    /** Handle bulk settings via POST /settings */
    private fun handleSettingsBulk(session: IHTTPSession): Response {
        val params = parseQueryParameters(session)
        return try {
            // IP Webcam uses this for multiple settings at once
            // e.g., POST /settings with body parameters like quality=720&night_vision=on
            if (params.containsKey("quality")) {
                val qualityStr = params["quality"] ?: "720"
                val quality = qualityStr.toIntOrNull() ?: 720
                val res = when {
                    quality >= 1080 -> CameraConfig.Resolution.FullHD
                    quality >= 720 -> CameraConfig.Resolution.HD720
                    quality >= 480 -> CameraConfig.Resolution.VGA
                    else -> CameraConfig.Resolution.QVGA
                }
                cameraManager?.setQuality(res)
            }

            if (params.containsKey("night_vision")) {
                val value = params["night_vision"]?.lowercase() ?: "off"
                println("Night vision $value")
            }

            newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "text/plain", "ok")
        } catch (e: Exception) {
            newFixedLengthResponse(
                NanoHTTPD.Response.Status.INTERNAL_ERROR, "text/plain",
                "error: ${e.message}"
            )
        }
    }

    /** Handle status/info endpoint — returns device info as JSON */
    private fun handleStatus(): Response {
        val config = cameraManager?.getCameraConfiguration() ?: mapOf(
            "resolution" to "unknown",
            "zoomLevel" to 1.0,
            "focusDistance" to 0f
        )

        val statusJson = """{
            "status": "ok",
            "camera_active": ${cameraManager?.isCameraActive() ?: false},
            "streaming": $isStreaming,
            "night_vision": ${cameraManager?.nightVisionEnabled ?: false},
            "resolution": "${config["resolution"]}",
            "zoom_level": ${config["zoomLevel"]},
            "focus_distance": ${config["focusDistance"]}
        }""".replace("\n", "").replace(" ", "")

        return newFixedLengthResponse(
            NanoHTTPD.Response.Status.OK, "application/json", statusJson
        )
    }

    /** Serve index.html */
    private fun serveWebpage(session: IHTTPSession): Response {
        return try {
            val inputStream = context.assets.open("index.html")
            val bytes = inputStream.readBytes()
            newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK, "text/html", String(bytes)
            )
        } catch (e: Exception) {
            newFixedLengthResponse(
                NanoHTTPD.Response.Status.INTERNAL_ERROR, "text/plain", "Error serving webpage"
            )
        }
    }

    /** Parse query parameters from session */
    private fun parseQueryParameters(session: IHTTPSession): Map<String, String> {
        val params = mutableMapOf<String, String>()
        val uri = session.uri ?: ""
        if (uri.contains("?")) {
            val queryString = uri.substringAfter("?")
            for (param in queryString.split("&")) {
                val parts = param.split("=", limit = 2)
                if (parts.size == 2) {
                    params[URLDecoder.decode(parts[0], "UTF-8")] = URLDecoder.decode(parts[1], "UTF-8")
                }
            }
        }
        return params
    }

    /** Custom streaming response for MJPEG — overrides send() to write multipart frames continuously */
    private inner class StreamingMjpegResponse(
        private val boundary: ByteArray
    ) : NanoHTTPD.Response(NanoHTTPD.Response.Status.OK, "multipart/x-mixed-replace; boundary=frame") {

        override fun send(outputStream: OutputStream) {
            try {
                // Initial boundary (no leading CRLF, per RFC 2046)
                outputStream.write(boundary)
                outputStream.write("\r\n".toByteArray())
                outputStream.flush()

                while (isStreaming && cameraManager != null) {
                    val frameData = cameraManager!!.getLatestFrame() ?: break
                    if (frameData.isEmpty()) continue

                    // Boundary + headers for each frame
                    outputStream.write("\r\n".toByteArray())
                    outputStream.write(boundary)
                    outputStream.write("\r\n".toByteArray())
                    outputStream.write("Content-Type: image/jpeg\r\n".toByteArray())
                    outputStream.write("Content-Length: ${frameData.size}\r\n\r\n".toByteArray())

                    // Write the JPEG frame bytes directly (binary, not String!)
                    outputStream.write(frameData)
                    outputStream.flush()
                }
            } catch (_: Exception) {
                // Connection closed or client disconnected — normal
            } finally {
                try { outputStream.close() } catch (_: Exception) {}
            }
        }
    }

    companion object {
        const val DEFAULT_PORT = 9090
    }
}

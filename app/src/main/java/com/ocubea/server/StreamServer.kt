package com.ocubea.server

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.OutputStream
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicLong

/**
 * HTTP server for MJPEG streaming, snapshots, and torch control.
 */
class StreamServer(private val context: Context) : NanoHTTPD(9090) {

    private var cameraManager: com.ocubea.camera.CameraManager? = null
    private var isStreaming = false

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
            uri == "/mjpeg" || uri == "/video" -> handleMjpegStream()
            uri == "/shot.jpg" -> handleShot()
            uri == "/api/torch" && session.method == Method.POST -> handleTorch(session)
            else -> newFixedLengthResponse(
                NanoHTTPD.Response.Status.NOT_FOUND, "text/plain", "Not found"
            )
        }
    }

    /** Handle MJPEG streaming — returns a response with multipart/x-mixed-replace content type.
     *  The actual frame streaming is done in send() which writes to the output stream continuously. */
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

    /** Serve single snapshot JPEG — returns raw binary bytes */
    private fun handleShot(): Response {
        val frameData = cameraManager?.getLatestFrame() ?: return newFixedLengthResponse(
            NanoHTTPD.Response.Status.NOT_FOUND, "image/jpeg", ""
        )

        if (frameData.isEmpty()) {
            return newFixedLengthResponse(
                NanoHTTPD.Response.Status.NO_CONTENT, "image/jpeg", ""
            )
        }

        // Return raw bytes directly via ChunkedResponse with InputStream — binary-safe in NanoHTTPD 2.3.1
        return newChunkedResponse(
            NanoHTTPD.Response.Status.OK,
            "image/jpeg",
            ByteArrayInputStream(frameData)
        )
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

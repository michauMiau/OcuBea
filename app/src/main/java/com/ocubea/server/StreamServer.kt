package com.ocubea.server

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import java.io.OutputStream
import java.net.URLDecoder

/**
 * HTTP server for MJPEG streaming, snapshots, and torch control.
 */
class StreamServer(private val context: Context) : NanoHTTPD(9090) {

    private var cameraManager: com.ocubea.camera.CameraManager? = null
    private var isStreaming = false
    private var frameCounter = 0L

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
                Response.Status.NOT_FOUND, "text/plain", "Not found"
            )
        }
    }

    /** Handle MJPEG streaming via multipart/x-mixed-replace */
    private fun handleMjpegStream(): Response {
        if (!isStreaming && cameraManager != null) {
            startVideoStream()
        }

        return newChunkedResponse(
            fi.iki.elonen.NanoHTTPD.Response.IStatus.HTTP_OK,
            "multipart/x-mixed-replace; boundary=frame",
            object : OutputStream() {
                override fun write(b: Int) {
                    // MJPEG streaming uses continuous writes — not used for chunked
                }

                override fun write(bytes: ByteArray?, off: Int, len: Int) {
                    if (bytes == null || !isStreaming || cameraManager == null) return

                    val frameData = cameraManager?.getLatestFrame() ?: return
                    if (frameData.isEmpty()) return

                    try {
                        // Send boundary + JPEG frame headers
                        sendMjpegFrame(bytes, off, len, frameData)
                    } catch (e: Exception) {
                        println("Error sending MJPEG frame: ${e.message}")
                    }
                }
            }.let { output ->
                // Start streaming in background thread
                Thread({
                    while (isStreaming && cameraManager != null) {
                        try {
                            val frameData = cameraManager!!.getLatestFrame() ?: break
                            if (frameData.isNotEmpty()) {
                                sendMjpegFrame(output, 0, frameData.size, frameData)
                            }
                        } catch (_: Exception) {
                            Thread.sleep(50) // brief pause on error
                        }
                    }
                }, "mjpeg-streamer").apply { isDaemon = true; start() }

                output
            }
        )
    }

    /** Send a single MJPEG frame with proper boundaries */
    private fun sendMjpegFrame(output: OutputStream, offset: Int, length: Int, frameData: ByteArray) {
        val boundary = "--frame\r\n".toByteArray(Charsets.UTF_8)
        val contentType = "Content-Type: image/jpeg\r\n".toByteArray(Charsets.UTF_8)
        val contentLength = "Content-Length: ${frameData.size}\r\n\r\n".toByteArray(Charsets.UTF_8)

        output.write(boundary)
        output.write(contentType)
        output.write(contentLength)
        output.write(frameData, offset, length)
        output.flush()
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
            Response.Status.INTERNAL_ERROR, "image/jpeg", ""
        )

        if (frameData.isEmpty()) {
            return newFixedLengthResponse(
                Response.Status.NO_CONTENT, "image/jpeg", ""
            )
        }

        // Return raw bytes directly — do NOT wrap in String!
        return newFixedLengthResponse(
            Response.Status.OK,
            "image/jpeg",
            frameData.size.toLong(),
            frameData
        )
    }

    /** Handle torch control via POST /api/torch?on=true|false */
    private fun handleTorch(session: IHTTPSession): Response {
        val params = parseQueryParameters(session)
        return try {
            // Check camera permission using ContextCompat for API 33+ safety
            if (context.checkSelfPermission(android.Manifest.permission.CAMERA) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                return newFixedLengthResponse(
                    Response.Status.FORBIDDEN, "application/json",
                    """{"status": "error", "message": "Camera permission required"}"""
                )
            }

            val cm = context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager

            // Find the first back camera with torch support
            var foundCameraId: String? = null
            for (cameraId in cm.cameraIdList) {
                val chars = cm.getCameraCharacteristics(cameraId)

                // Check flash available — use toBoolean() since API level varies
                val hasFlash = try {
                    chars.get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                } catch (_: Exception) { continue }

                if (!hasFlash) continue

                // Check it's a back camera (1 = LENS_FACING_BACK)
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
                    Response.Status.BAD_REQUEST, "application/json",
                    """{"status": "error", "message": "No torch available on back camera"}"""
                )
            }

            val shouldEnable = params["on"]?.toBooleanStrictOrNull() ?: false
            cm.setTorchMode(foundCameraId, shouldEnable)

            newFixedLengthResponse(
                Response.Status.OK, "application/json",
                """{"status": "ok", "torch": ${if (shouldEnable) "true" else "false"}, "camera_id": "$foundCameraId"}"""
            )
        } catch (e: Exception) {
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR, "application/json",
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
                Response.Status.OK, "text/html", String(bytes)
            )
        } catch (e: Exception) {
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR, "text/plain", "Error serving webpage"
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

    companion object {
        const val DEFAULT_PORT = 9090
    }
}

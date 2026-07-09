package com.ocubea.server

import fi.iki.elonen.NanoHTTPD
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*

/**
 * HTTP server serving the streaming interface and IP Webcam-compatible API.
 * Built on NanoHttpd for lightweight embedded use.
 */
class StreamServer(private val port: Int = 8080) : NanoHTTPD(port) {

    var streamUrl: String = "" // e.g., "http://192.168.1.5"

    private lateinit var cameraManager: com.ocubea.camera.CameraManager
    private lateinit var videoEncoder: com.ocubea.stream.VideoEncoder
    private val encoderConfig = com.ocubea.stream.VideoEncoder.EncodingConfig()

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
        println("Stream server stopped")
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri ?: "/"

        return when {
            // Main streaming page with MJPEG viewer
            uri == "/" || uri.startsWith("/index.html") -> {
                createResponse(Response.Status.OK, "text/html", webPageHTML())
            }

            // IP Webcam-compatible API endpoints
            uri == "/start" -> handleApiStart()
            uri == "/stop" -> handleApiStop()
            uri == "/status" -> handleApiStatus()
            uri == "/settings" -> handleApiSettings(session)
            uri == "/capture" -> handleCapture()

            // Default - return HTML page
            else -> createResponse(Response.Status.NOT_FOUND, "text/html", "<h1>404</h1>")
        }
    }

    private fun handleApiStart(): Response {
        try {
            val config = videoEncoder.EncodingConfig(
                width = encoderConfig.width,
                height = encoderConfig.height,
                frameRate = encoderConfig.frameRate,
                bitrateKbps = encoderConfig.bitrateKbps,
                codec = encoderConfig.codec
            )
            if (videoEncoder.start(config)) {
                cameraManager.captureFrame()
                return createResponse(Response.Status.OK, "text/plain", "OK")
            }
            return createResponse(Response.Status.INTERNAL_ERROR, "text/plain", "ERROR: Encoder start failed")
        } catch (e: Exception) {
            return createResponse(Response.Status.INTERNAL_ERROR, "text/plain", "ERROR: ${e.message}")
        }
    }

    private fun handleApiStop(): Response {
        try {
            videoEncoder.stop()
            cameraManager.stop()
            return createResponse(Response.Status.OK, "text/plain", "OK")
        } catch (e: Exception) {
            return createResponse(Response.Status.INTERNAL_ERROR, "text/plain", "ERROR: ${e.message}")
        }
    }

    private fun handleApiStatus(): Response {
        val status = """{"status":"running","resolution":"${encoderConfig.width}x${encoderConfig.height}","fps":${encoderConfig.frameRate},"bitrate":${encoderConfig.bitrateKbps}}"""
        return createResponse(Response.Status.OK, "application/json", status)
    }

    private fun handleApiSettings(session: IHTTPSession): Response {
        val params = session.pars ?: emptyMap()
        if (params.containsKey("resolution")) {
            // Handle resolution change based on parameter
        }
        return createResponse(Response.Status.OK, "text/plain", "OK")
    }

    private fun handleCapture(): Response {
        try {
            val config = videoEncoder.EncodingConfig(
                width = encoderConfig.width,
                height = encoderConfig.height,
                frameRate = encoderConfig.frameRate,
                bitrateKbps = encoderConfig.bitrateKbps,
                codec = encoderConfig.codec
            )
            if (videoEncoder.start(config)) {
                val output = videoEncoder.encodeFrame(ByteArray(encoderConfig.width * encoderConfig.height * 3 / 2))
                if (output.isNotEmpty()) {
                    return createResponse(Response.Status.OK, "image/jpeg", String(output[0]))
                }
            }
        } catch (e: Exception) {
            // ignore
        }
        videoEncoder.stop()
        return createResponse(Response.Status.INTERNAL_ERROR, "text/plain", "ERROR: Capture failed")
    }

    private fun webPageHTML(): String = """<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>OcuBea</title>
    <style>
        body { font-family: Arial; margin: 0; padding: 20px; background: #1a1a1a; color: white; }
        h1 { text-align: center; color: #4CAF50; }
        .container { max-width: 800px; margin: 0 auto; }
        img#stream { width: 100%; border-radius: 8px; }
        .controls { display: flex; gap: 10px; margin: 20px 0; }
        button { padding: 10px 20px; background: #4CAF50; color: white; border: none; border-radius: 4px; cursor: pointer; }
        button:hover { background: #45a049; }
    </style>
</head>
<body>
    <div class="container">
        <h1>OcuBea - IP Camera Streamer</h1>
        <img id="stream" src="/start" alt="Camera stream">

        <div class="controls">
            <button onclick="startStream()">Start Stream</button>
            <button onclick="stopStream()">Stop Stream</button>
        </div>

        <p>Status: <span id="status">Stopped</span></p>
    </div>

    <script>
        async function startStream() {
            const response = await fetch('/start');
            document.getElementById('stream').src = '/start';
            document.getElementById('status').textContent = 'Running';
        }

        async function stopStream() {
            const response = await fetch('/stop');
            document.getElementById('status').textContent = 'Stopped';
        }
    </script>
</body>
</html>""".trimIndent()
}

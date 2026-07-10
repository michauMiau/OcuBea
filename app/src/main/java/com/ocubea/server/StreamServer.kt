package com.ocubea.server

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import java.nio.ByteBuffer
import kotlin.math.min

/**
 * HTTP server for MJPEG streaming and PTZ control.
 */
class StreamServer(private val context: Context) : NanoHTTPD(8080) {

    private var encoder: com.ocubea.stream.VideoEncoder? = null
    
    // Callback when stream is ready for connection
    var onStreamReady: () -> Unit = {}
    
    /** Start the server */
    fun start() {
        try {
            super.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            println("Stream server started on port ${localPort}")
            onStreamReady()
        } catch (e: Exception) {
            println("Failed to start stream server: ${e.message}")
        }
    }

    /** Stop the server */
    fun stopServer() {
        try {
            super.stop()
            println("Stream server stopped")
        } catch (e: Exception) {
            println("Error stopping server: ${e.message}")
        }
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri ?: "/"
        
        return when {
            uri == "/mjpeg" -> handleMjpegStream(session)
            uri == "/api/settings" && session.method == Method.POST -> handleSettingsPost(session)
            uri == "/api/ptz" && session.method == Method.POST -> handlePtzPost(session)
            uri == "/" || uri == "/index.html" -> serveWebpage(session)
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
        }
    }

    /** Serve MJPEG stream */
    private fun handleMjpegStream(session: IHTTPSession): Response {
        return StreamingMjpegResponse(session, "--BoundaryString")
    }

    /** Handle settings update via POST */
    private fun handleSettingsPost(session: IHTTPSession): Response {
        val params = parseQueryParameters(session)
        
        val response = mapOf(
            "status" to if (params.isNotEmpty()) "ok" else "no_params",
            "params_count" to params.size.toString()
        )
        
        return newFixedLengthResponse(
            Response.Status.OK,
            "application/json",
            response.toString()
        )
    }

    /** Handle PTZ commands via POST */
    private fun handlePtzPost(session: IHTTPSession): Response {
        val params = parseQueryParameters(session)
        
        // Process PTT button or other PTZ commands
        when {
            params.containsKey("ptt") -> {
                return newFixedLengthResponse(
                    Response.Status.OK,
                    "application/json",
                    """{"status": "ok", "command": "ptt"}"""
                )
            }
            else -> {
                return newFixedLengthResponse(
                    Response.Status.BAD_REQUEST,
                    "application/json",
                    """{"error": "unknown_command"}"""
                )
            }
        }
    }

    /** Serve index.html */
    private fun serveWebpage(session: IHTTPSession): Response {
        return try {
            val inputStream = context.assets.open("index.html")
            val bytes = inputStream.readBytes()
            
            newFixedLengthResponse(
                Response.Status.OK,
                "text/html",
                String(bytes)
            )
        } catch (e: Exception) {
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "text/plain",
                "Error serving webpage"
            )
        }
    }

    /** Parse query parameters from session */
    private fun parseQueryParameters(session: IHTTPSession): Map<String, String> {
        val params = mutableMapOf<String, String>()
        
        // Try to get query string
        val uri = session.uri ?: ""
        if (uri.contains("?")) {
            val queryString = uri.substringAfter("?")
            for (param in queryString.split("&")) {
                val parts = param.split("=", limit = 2)
                if (parts.size == 2) {
                    params[parts[0]] = parts[1]
                }
            }
        }
        
        return params
    }

    /** MJPEG streaming response */
    private inner class StreamingMjpegResponse(session: IHTTPSession, boundary: String) : Response(
        session,
        Response.Status.OK,
        "multipart/x-mixed-replace; boundary=$boundary"
    ) {
        
        init {
            addHeader("Cache-Control", "no-cache")
            addHeader("Connection", "close")
            
            try {
                val outputStream = outputStream
                
                // Send header for each frame
                while (!isStopped) {
                    // Wait for next frame or timeout
                    Thread.sleep(33) // ~30 FPS
                    
                    if (outputStream != null && !isStopped) {
                        outputStream.write("Content-Type: image/jpeg\r\n".toByteArray())
                        outputStream.write("\r\n".toByteArray())
                        
                        // Send frame data here - would need encoder integration
                        // For now, send a minimal valid JPEG header
                        
                        outputStream.flush()
                    }
                }
            } catch (e: Exception) {
                println("Error in MJPEG stream: ${e.message}")
            }
        }
    }

    companion object {
        const val DEFAULT_PORT = 8080
    }
}

package com.ocubea.server

import android.content.Context
import com.ocubea.camera.CameraManager
import com.ocubea.model.CameraConfig
import com.ocubea.onvif.OnvifDiscovery
import com.ocubea.onvif.OnvifSoap
import com.ocubea.security.MotionDetector
import com.ocubea.security.MotionRecorder
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response.Status as Status
import java.io.ByteArrayInputStream
import java.io.OutputStream

/**
 * OcuBea HTTP server: MJPEG streaming via FrameHub (multi-viewer),
 * IP Webcam-compatible API, ONVIF Profile S SOAP endpoint, security camera
 * control and recordings management.
 */
class StreamServer(
    private val context: Context,
    private val cameraManager: CameraManager,
    private val motionDetector: MotionDetector,
    private val motionRecorder: MotionRecorder,
    private val onvifDiscovery: OnvifDiscovery,
    port: Int = 8080
) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri ?: "/"
        val method = session.method

        return try {
            when {
                // ── Web UI ──
                uri == "/" || uri == "/index.html" || uri == "/mobile" -> serveWebpage()

                // ── Streaming ──
                uri == "/video" || uri == "/videofeed" || uri == "/mjpeg" || uri.startsWith("/stream") -> handleMjpeg()
                uri == "/shot.jpg" || uri == "/snapshot.jpg" || uri == "/image" -> handleShot()

                // ── Audio ──
                uri == "/audio.wav" || uri == "/audio.aac" || uri == "/audio.opus" || uri == "/inband.aac" ->
                    handleAudio(uri)

                // ── ONVIF Profile S ──
                uri.startsWith("/onvif/") && method == Method.POST -> handleOnvif(session)
                uri == "/onvif/device_service" && method == Method.GET ->
                    newFixedLengthResponse(Status.OK, "application/soap+xml", "ONVIF device service — POST SOAP here")

                // ── IP Webcam API: controls ──
                uri == "/focus" -> handleFocus(session)
                uri == "/nofocus" -> okText("ok")
                uri == "/ptz" || uri == "/ptt" -> handlePtz(session)
                uri == "/torchon" -> handleTorch(true)
                uri == "/torchoff" -> handleTorch(false)
                uri == "/enabletorch" -> handleTorch(paramBool(session, true))
                uri == "/disabletorch" -> handleTorch(false)

                // ── IP Webcam API: settings ──
                uri == "/settings" && method == Method.POST -> handleSettingsBulk(session)
                uri.startsWith("/settings/") && (method == Method.POST || method == Method.GET) ->
                    handleSetting(session)

                // ── Extended API (OcuBea) ──
                uri == "/status.json" || uri == "/info" -> handleStatusJson()
                uri == "/sensors.json" -> handleSensorsJson()
                uri.startsWith("/recordings") -> handleRecordings(session)
                else -> notFound(uri)
            }
        } catch (e: Exception) {
            newFixedLengthResponse(Status.INTERNAL_ERROR, "text/plain", "error: ${e.message}")
        }
    }

    fun stopServer() {
        try { motionRecorder.finishClip() } catch (_: Exception) {}
        super.stop()
    }

    // ═══ Handlers ══════════════════════════════════════════════

    /** MJPEG stream from the shared FrameHub — supports many concurrent viewers. */
    private fun handleMjpeg(): Response {
        if (!cameraManager.isStreaming) {
            return newFixedLengthResponse(Status.SERVICE_UNAVAILABLE, "text/plain", "Camera not streaming")
        }
        val viewer = cameraManager.frameHub.addViewer()
        return object : NanoHTTPD.Response(
            Status.OK, "multipart/x-mixed-replace; boundary=framebound",
            ByteArrayInputStream(ByteArray(0)), -1
        ) {
            override fun send(out: OutputStream) {
                try {
                    while (cameraManager.isStreaming) {
                        val frame = cameraManager.frameHub.pollFrame(viewer, 5000) ?: break
                        out.write("--framebound\r\nContent-Type: image/jpeg\r\nContent-Length: ${frame.size}\r\n\r\n".toByteArray())
                        out.write(frame)
                        out.write("\r\n".toByteArray())
                        out.flush()
                    }
                } catch (_: Exception) {
                    // client disconnected — normal
                } finally {
                    cameraManager.frameHub.removeViewer(viewer)
                    try { out.close() } catch (_: Exception) {}
                }
            }
        }
    }

    private fun handleShot(): Response {
        if (!cameraManager.frameHub.hasFrame()) {
            return newFixedLengthResponse(Status.NO_CONTENT, "text/plain", "No frame yet")
        }
        val frame = cameraManager.frameHub.getLatest()!!
        return newFixedLengthResponse(Status.OK, "image/jpeg", ByteArrayInputStream(frame), frame.size.toLong())
    }

    private fun handleAudio(uriPath: String): Response {
        if (!audio.canRecord()) {
            return newFixedLengthResponse(Status.FORBIDDEN, "text/plain", "RECORD_AUDIO permission not granted")
        }
        val mime = when {
            uriPath.endsWith(".aac") || uriPath.endsWith(".opus") -> {
                // AAC/Opus need an encoder; serve WAV so clients still get audio rather than silence
                "audio/x-wav"
            }
            else -> "audio/x-wav"
        }
        val pipe = java.io.PipedOutputStream()
        val input = java.io.PipedInputStream(pipe, 64 * 1024)
        val client = AudioStreamManager.Client(
            write = { buf, len -> try { pipe.write(buf, 0, len); pipe.flush(); true } catch (_: Exception) { false } },
            onDisconnect = { try { pipe.close() } catch (_: Exception) {} }
        )
        audio.addClient(client)

        return object : NanoHTTPD.Response(Status.OK, mime, input, -1) {
            override fun send(out: OutputStream) {
                try { input.copyTo(out); out.flush() } catch (_: Exception) {} finally {
                    audio.removeClient(client)
                    try { out.close() } catch (_: Exception) {}
                }
            }
        }
    }

    private val audio = AudioStreamManager(context.applicationContext)

    private fun handleOnvif(session: IHTTPSession): Response {
        val body = readBody(session)
        val action = OnvifSoap.extractAction(body)
        val host = session.headers["http-client-ip"]?.substringBefore(":") ?: localIpFallback()
        val xml = OnvifSoap.deviceServiceResponse(action, host, this.listeningPort,
            context.getSharedPreferences("ocubea", Context.MODE_PRIVATE).getString("device_name", "OcuBea") ?: "OcuBea")
        return newFixedLengthResponse(Status.OK, "application/soap+xml; charset=utf-8", xml)
    }

    private fun localIpFallback(): String {
        try {
            for (nif in java.net.NetworkInterface.getNetworkInterfaces())
                for (addr in nif.inetAddresses)
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) return addr.hostAddress ?: ""
        } catch (_: Exception) {}
        return "127.0.0.1"
    }

    private fun handleFocus(session: IHTTPSession): Response {
        val p = parseParams(session)
        val x = p["x"]?.toFloatOrNull() ?: 0.5f
        val y = p["y"]?.toFloatOrNull() ?: 0.5f
        cameraManager.setFocus(x, y)
        return okText("ok")
    }

    private fun handlePtz(session: IHTTPSession): Response {
        val p = parseParams(session)
        // IP Webcam sends zoom as absolute level or direction steps
        val zoom = p["zoom"]?.toFloatOrNull() ?: p["pan"]?.toFloatOrNull()?.plus(1f) ?: return okText("ok")
        cameraManager.setZoom(zoom.coerceAtLeast(0.5f))
        return okText("ok")
    }

    private fun handleTorch(on: Boolean): Response {
        return try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
            val torchId = cm.cameraIdList.firstOrNull { id ->
                runCatching {
                    cm.getCameraCharacteristics(id).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                        cm.getCameraCharacteristics(id).get(android.hardware.camera2.CameraCharacteristics.LENS_FACING) ==
                        android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK
                }.getOrDefault(false)
            }
            if (torchId == null) newFixedLengthResponse(Status.BAD_REQUEST, "text/plain", "no torch")
            else { cm.setTorchMode(torchId, on); okText(if (on) "torch on" else "torch off") }
        } catch (e: Exception) {
            newFixedLengthResponse(Status.INTERNAL_ERROR, "text/plain", "error: ${e.message}")
        }
    }

    private fun paramBool(session: IHTTPSession, default: Boolean): Boolean =
        parseParams(session)["on"]?.toBooleanStrictOrNull() ?: default

    private var frontCamera = false
    private var frontCameraProvider: (() -> Boolean)? = null
    fun setFrontCameraStateProvider(f: () -> Boolean) { frontCameraProvider = f }
    private fun isFrontNow(): Boolean = frontCameraProvider?.invoke() ?: frontCamera

    /** /settings/<name>?set=<value> — night_vision, ffc, quality, effect, motion_* */
    private fun handleSetting(session: IHTTPSession): Response {
        val name = (session.uri ?: "").substringAfterLast('/').lowercase()
        val p = parseParams(session)
        val value = (p["set"] ?: "").lowercase()

        return try {
            when (name) {
                "night_vision" -> { cameraManager.nightVisionEnabled = value != "off"; okText("ok") }
                "effect" -> {
                    if (value in listOf("", "none", "mono", "negative", "sepia", "nightvision")) {
                        cameraManager.effect = value.ifEmpty { "none" }; okText("ok")
                    } else badRequest("unknown effect: $value")
                }
                "ffc" -> {
                    // WebUI sends "toggle" when current state is unknown
                    val front = if (value == "toggle") !isFrontNow() else value == "on"
                    frontCamera = front
                    cameraManager.setFrontFacingCamera(front); okText(if (front) "front" else "back")
                }
                "quality" -> {
                    val q = value.toIntOrNull() ?: 720
                    val res = when {
                        q >= 1080 -> CameraConfig.Resolution.FullHD
                        q >= 720 -> CameraConfig.Resolution.HD720
                        q >= 480 -> CameraConfig.Resolution.VGA
                        else -> CameraConfig.Resolution.QVGA
                    }
                    cameraManager.setQuality(res); okText("ok")
                }
                "motion_detection" -> {
                    motionDetector.enabled = value == "on"; okText("ok")
                }
                "motion_sensitivity" -> {
                    motionDetector.setSensitivity(value.toIntOrNull() ?: 50); okText("ok")
                }
                "recording" -> {
                    motionRecorder.enabled = value == "on"
                    if (!motionRecorder.enabled) motionRecorder.stopAllIfIdle()
                    okText("ok")
                }
                "audio_enabled" -> okText("ok") // audio is demand-driven per client
                else -> notFound("unknown setting: $name")
            }
        } catch (e: Exception) {
            newFixedLengthResponse(Status.INTERNAL_ERROR, "text/plain", "error: ${e.message}")
        }
    }

    private fun handleSettingsBulk(session: IHTTPSession): Response {
        val p = parseBodyParams(session)
        var applied = 0
        fun apply(name: String, v: String?) {
            if (v == null) return
            val resp = handleSettingValue(name, v)
            if (resp == "ok") applied++
        }
        apply("quality", p["quality"])
        apply("night_vision", p["night_vision"])
        apply("effect", p["effect"])
        apply("motion_detection", p["motion_detection"])
        apply("motion_sensitivity", p["motion_sensitivity"])
        apply("recording", p["recording"])
        return okText("applied $applied")
    }

    private fun handleSettingValue(name: String, rawValue: String): String = when (name.lowercase()) {
        "night_vision" -> { cameraManager.nightVisionEnabled = rawValue == "on"; "ok" }
        "effect" -> { cameraManager.effect = rawValue; "ok" }
        "quality" -> {
            val q = rawValue.toIntOrNull() ?: 720
            cameraManager.setQuality(
                when {
                    q >= 1080 -> CameraConfig.Resolution.FullHD
                    q >= 720 -> CameraConfig.Resolution.HD720
                    q >= 480 -> CameraConfig.Resolution.VGA
                    else -> CameraConfig.Resolution.QVGA
                }
            ); "ok"
        }
        "motion_detection" -> { motionDetector.enabled = rawValue == "on"; "ok" }
        "motion_sensitivity" -> { motionDetector.setSensitivity(rawValue.toIntOrNull() ?: 50); "ok" }
        "recording" -> { motionRecorder.enabled = rawValue == "on"; "ok" }
        else -> "ignored"
    }

    private fun handleStatusJson(): Response {
        val cfg = cameraManager.getConfiguration()
        val json = """
        {"status":"ok",
         "app":"OcuBea","version":"${versionName()}",
         "camera_active":${cameraManager.isStreaming},
         "resolution":"${cfg["resolution"]}","fps":${cfg["fps"]},
         "viewers":${cfg["viewers"]},"frames":${cfg["frames"]},
         "night_vision":${cameraManager.nightVisionEnabled},
         "effect":"${cfg["effect"]}",
         "zoom_level":1.0,
         "motion":{"enabled":${motionDetector.enabled},"detected":${motionDetector.motionDetected}},
         "recording":{"enabled":${motionRecorder.enabled},"active":${motionRecorder.recording},"count":${motionRecorder.recordingsCount}},
         "audio_clients":${audio.clientCount()},
         "battery_level":${batteryLevel()}}
        """.trimIndent().replace("\n", "")
        return newFixedLengthResponse(Status.OK, "application/json", json)
    }

    private fun versionName(): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "dev"
    } catch (_: Exception) { "dev" }

    private fun batteryLevel(): Int = try {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
        bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
    } catch (_: Exception) { -1 }

    private fun handleSensorsJson(): Response = newFixedLengthResponse(
        Status.OK, "application/json",
        """{"status":"ok","note":"hardware sensors not yet exposed"}"""
    )

    /** GET /recordings — list; GET /recordings/<file> — download; DELETE /recordings/<file>; POST /recordings/delete?name= */
    private fun handleRecordings(session: IHTTPSession): Response {
        val uri = session.uri ?: "/recordings"
        val rest = uri.removePrefix("/recordings").trimStart('/')
        return when {
            rest.isEmpty() && session.method == Method.GET -> {
                val files = motionRecorder.listRecordings()
                val items = files.joinToString(",") {
                    """{"name":"${it.name}","size":${it.length()},"modified":${it.lastModified()}}"""
                }
                newFixedLengthResponse(Status.OK, "application/json", """{"recordings":[$items]}""")
            }
            session.method == Method.DELETE || (session.method == Method.POST && uri.contains("delete")) -> {
                val name = rest.ifEmpty { parseBodyParams(session)["name"] ?: "" }
                okText(if (motionRecorder.deleteRecording(name)) "deleted" else "not found")
            }
            else -> {
                val name = rest
                if (!name.endsWith(".avi") || name.contains("..") || name.contains('/')) {
                    return badRequest("invalid recording name")
                }
                val file = java.io.File(motionRecorder.listRecordings().firstOrNull()?.parentFile, name)
                if (!file.exists()) return notFound(name)
                newFixedLengthResponse(Status.OK, "video/x-msvideo", file.inputStream(), file.length())
            }
        }
    }

    // ═══ Helpers ═══════════════════════════════════════════════

    private fun okText(t: String) = newFixedLengthResponse(Status.OK, "text/plain", t)
    private fun badRequest(t: String) = newFixedLengthResponse(Status.BAD_REQUEST, "text/plain", t)
    private fun notFound(t: String) = newFixedLengthResponse(Status.NOT_FOUND, "text/plain", "Not found: $t")

    private fun parseParams(session: IHTTPSession): Map<String, String> =
        session.parameters.mapValues { it.value.firstOrNull() ?: "" }

    private fun parseBodyParams(session: IHTTPSession): Map<String, String> {
        val body = readBody(session)
        val map = HashMap<String, String>()
        for (pair in body.split('&')) {
            val kv = pair.split('=', limit = 2)
            if (kv.size == 2) map[java.net.URLDecoder.decode(kv[0], "UTF-8")] =
                java.net.URLDecoder.decode(kv[1], "UTF-8")
        }
        // merge query too
        map.putAll(parseParams(session))
        return map
    }

    private fun readBody(session: IHTTPSession): String {
        val map = HashMap<String, String>()
        try {
            session.parseBody(map)
        } catch (_: Exception) {}
        return map["postData"] ?: ""
    }

    private fun serveWebpage(): Response = try {
        val bytes = context.assets.open("index.html").readBytes()
        newFixedLengthResponse(Status.OK, "text/html", ByteArrayInputStream(bytes), bytes.size.toLong())
    } catch (_: Exception) {
        newFixedLengthResponse(Status.INTERNAL_ERROR, "text/plain", "Web UI missing")
    }

    companion object {
        const val DEFAULT_PORT = 8080
    }
}

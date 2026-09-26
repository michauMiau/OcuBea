package com.ocubea.server

import android.content.Context
import android.content.Intent
import android.os.Build
import com.ocubea.camera.CameraManager
import com.ocubea.model.CameraConfig
import com.ocubea.model.OcuBeaConfig
import com.ocubea.onvif.OnvifDiscovery
import com.ocubea.onvif.OnvifSoap
import com.ocubea.security.MotionDetector
import com.ocubea.security.MotionRecorder
import com.ocubea.sensors.DeviceSensors
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response.Status as Status
import java.io.ByteArrayInputStream
import java.io.File

/**
 * OcuBea HTTP server.
 *
 * Surface:
 *  - Web UI at `/` (single file, no dependencies)
 *  - MJPEG stream + snapshots + audio
 *  - IP Webcam-compatible control API
 *  - ONVIF Profile S (WS-Discovery + SOAP)
 *  - Security camera (motion detection + AVI recording)
 *  - Device telemetry and settings
 */
class StreamServer(
    private val context: Context,
    private val cameraManager: CameraManager,
    private val motionDetector: MotionDetector,
    private val motionRecorder: MotionRecorder,
    private val onvifDiscovery: OnvifDiscovery,
    private val config: OcuBeaConfig = OcuBeaConfig(context),
    port: Int = config.port
) : NanoHTTPD(port) {

    private val auth = ApiAuth { config.accessToken }
    private val sensors = DeviceSensors(context.applicationContext)
    private val audio = AudioStreamManager(context.applicationContext)

    // Torch state, toggled through Camera2
    private var torchOn = false

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri ?: "/"
        val method = session.method

        return try {
            // CORS preflight for browser clients
            if (method == Method.OPTIONS) return withCors(okText(""))

            auth.check(session, uri)?.let { return withCors(it) }

            val response = route(session, uri, method)
            withCors(response)
        } catch (e: Exception) {
            withCors(newFixedLengthResponse(
                Status.INTERNAL_ERROR, "text/plain", "error: ${e.message}"
            ))
        }
    }

    private fun route(session: IHTTPSession, uri: String, method: Method): Response = when {
        // ── Web UI ──
        uri == "/" || uri == "/index.html" || uri == "/mobile" || uri == "/login" -> serveWebpage()

        // ── Streaming ──
        uri == "/video" || uri == "/videofeed" || uri == "/mjpeg" || uri.startsWith("/stream") -> handleMjpeg()
        uri == "/shot.jpg" || uri == "/snapshot.jpg" || uri == "/image" -> handleShot()

        // ── Audio ──
        uri == "/audio.wav" || uri == "/audio.aac" || uri == "/audio.opus" ||
            uri == "/inband.aac" || uri == "/talk" -> handleAudio(uri)

        // ── ONVIF ──
        uri.startsWith("/onvif/") && method == Method.POST -> handleOnvif(session)
        uri == "/onvif/device_service" -> newFixedLengthResponse(
            Status.OK, "application/soap+xml; charset=utf-8",
            "ONVIF device service — POST SOAP requests here"
        )

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
        uri.startsWith("/settings/") -> handleSetting(session)
        uri == "/api/camera" -> {
            val on = paramBool(session, false)
            cameraManager.setFrontFacingCamera(on); okText(if (on) "front" else "back")
        }

        // ── Extended API ──
        uri == "/status.json" || uri == "/info" -> handleStatusJson()
        uri == "/sensors.json" -> handleSensorsJson()
        uri == "/config.json" -> handleConfigJson()
        uri.startsWith("/recordings") -> handleRecordings(session)
        uri == "/onvif/describe" -> newFixedLengthResponse(Status.OK, "text/plain", describe())

        else -> notFound(uri)
    }

    fun stopServer() {
        try { motionRecorder.finishClip() } catch (_: Exception) {}
        try { audio.stop() } catch (_: Exception) {}
        try { setTorch(false) } catch (_: Exception) {}
        super.stop()
    }

    // ═══ Handlers ══════════════════════════════════════════════

    /** MJPEG stream from the shared FrameHub — many concurrent viewers supported. */
    private fun handleMjpeg(): Response {
        if (!cameraManager.isStreaming) {
            return newFixedLengthResponse(Status.SERVICE_UNAVAILABLE, "text/plain", "Camera not streaming")
        }
        val viewer = cameraManager.frameHub.addViewer()
        // Frames are fed through an InputStream so NanoHTTPD writes the HTTP
        // headers itself — overriding Response.send() skips them and browsers
        // then reject the response as HTTP/0.9.
        val stream = object : java.io.InputStream() {
            var buf: ByteArray = ByteArray(0)
            var pos = 0

            override fun read(): Int {
                if (pos >= buf.size) nextFrame()
                return if (pos < buf.size) buf[pos++].toInt() and 0xFF else -1
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (pos >= buf.size) nextFrame()
                if (pos >= buf.size) return -1
                val n = minOf(len, buf.size - pos)
                System.arraycopy(buf, pos, b, off, n)
                pos += n
                return n
            }

            private fun nextFrame() {
                while (cameraManager.isStreaming) {
                    val frame = cameraManager.frameHub.pollFrame(viewer, 5000) ?: continue
                    val head = ("--framebound\r\nContent-Type: image/jpeg\r\n" +
                        "Content-Length: ${frame.size}\r\n\r\n").toByteArray()
                    val tail = "\r\n".toByteArray()
                    buf = head + frame + tail
                    pos = 0
                    return
                }
            }

            override fun close() {
                cameraManager.frameHub.removeViewer(viewer)
                super.close()
            }
        }
        return newChunkedResponse(Status.OK, "multipart/x-mixed-replace; boundary=framebound", stream)
    }

    private fun handleShot(): Response {
        val frame = cameraManager.frameHub.getLatest()
            ?: return newFixedLengthResponse(Status.NO_CONTENT, "text/plain", "No frame yet")
        return newFixedLengthResponse(Status.OK, "image/jpeg", ByteArrayInputStream(frame), frame.size.toLong())
    }

    private fun handleAudio(uriPath: String): Response {
        if (!config.audioEnabled) {
            return newFixedLengthResponse(Status.FORBIDDEN, "text/plain", "Audio disabled in settings")
        }
        if (!audio.canRecord()) {
            return newFixedLengthResponse(Status.FORBIDDEN, "text/plain", "RECORD_AUDIO permission not granted")
        }
        // AAC/Opus need an encoder we do not ship; serve WAV so clients get
        // real audio rather than silence.
        val mime = "audio/x-wav"
        val pipe = java.io.PipedOutputStream()
        val input = java.io.PipedInputStream(pipe, 64 * 1024)
        val client = AudioStreamManager.Client(
            write = { buf, len -> try { pipe.write(buf, 0, len); pipe.flush(); true } catch (_: Exception) { false } },
            onDisconnect = { try { pipe.close() } catch (_: Exception) {} }
        )
        audio.addClient(client)
        return newChunkedResponse(Status.OK, mime, input)
    }

    private fun handleOnvif(session: IHTTPSession): Response {
        val body = readBody(session)
        val action = OnvifSoap.extractAction(body)
        val host = auth.clientIp(session).takeIf { it != "unknown" } ?: localIpFallback()
        val xml = OnvifSoap.deviceServiceResponse(action, host, listeningPort, config.deviceName)
        return newFixedLengthResponse(Status.OK, "application/soap+xml; charset=utf-8", xml)
    }

    private fun localIpFallback(): String {
        try {
            for (nif in java.net.NetworkInterface.getNetworkInterfaces())
                for (addr in nif.inetAddresses)
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        return addr.hostAddress ?: "127.0.0.1"
                    }
        } catch (_: Exception) {}
        return "127.0.0.1"
    }

    private fun handleFocus(session: IHTTPSession): Response {
        val p = parseParams(session)
        val x = p["x"]?.toFloatOrNull() ?: 0.5f
        val y = p["y"]?.toFloatOrNull() ?: 0.5f
        cameraManager.setFocus(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))
        return okText("ok")
    }

    private fun handlePtz(session: IHTTPSession): Response {
        val p = parseParams(session)
        // IP Webcam sends zoom as an absolute level; also accept pan/ptt steps
        val zoom = p["zoom"]?.toFloatOrNull()
        if (zoom != null) {
            cameraManager.setZoom(if (zoom <= 1f) 1f + zoom else zoom)
            return okText("ok")
        }
        p["pan"]?.toFloatOrNull()?.let { cameraManager.setZoom(1f + it) }
        p["ptt"]?.toFloatOrNull()?.let { cameraManager.setZoom(1f + it) }
        return okText("ok")
    }

    private fun handleTorch(on: Boolean): Response {
        val ok = setTorch(on)
        return if (ok) okText(if (on) "torch on" else "torch off")
        else newFixedLengthResponse(Status.BAD_REQUEST, "text/plain", "No torch on this device")
    }

    private fun setTorch(on: Boolean): Boolean = try {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
        val torchId = cm.cameraIdList.firstOrNull { id ->
            runCatching {
                val ch = cm.getCameraCharacteristics(id)
                ch.get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                    ch.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING) ==
                    android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK
            }.getOrDefault(false)
        }
        if (torchId == null) {
            false
        } else {
            cm.setTorchMode(torchId, on)
            torchOn = on
            config.torchOn = on
            true
        }
    } catch (_: Exception) {
        false
    }

    private fun paramBool(session: IHTTPSession, default: Boolean): Boolean =
        parseParams(session)["on"]?.toBooleanStrictOrNull() ?: default

    // ── Settings ───────────────────────────────────────────────

    /** POST /settings/<name>?set=<value> — every key from OcuBeaConfig. */
    private fun handleSetting(session: IHTTPSession): Response {
        val name = (session.uri ?: "").substringAfterLast('/').lowercase()
        val value = (parseParams(session)["set"] ?: "").lowercase()
        return applySetting(name, value)
    }

    private fun applySetting(name: String, raw: String): Response = try {
        val value = raw.lowercase()
        when (name) {
            "quality" -> {
                val res = resolutionFor(value.toIntOrNull() ?: 720)
                cameraManager.setQuality(res)
                okText("ok")
            }
            "resolution" -> {
                val res = resolutionFor(value.toIntOrNull() ?: 720)
                cameraManager.setQuality(res)
                okText("${res.width}x${res.height}")
            }
            "fps" -> {
                val fps = value.toIntOrNull() ?: 15
                cameraManager.setFrameRate(fps)
                okText("ok")
            }
            "jpeg_quality" -> {
                cameraManager.setJpegQuality(value.toIntOrNull() ?: 82)
                okText("ok")
            }
            "effect" -> {
                if (value in EFFECTS) {
                    cameraManager.applyEffect(value)
                    okText("ok")
                } else badRequest("unknown effect: $value (${EFFECTS.joinToString(",")})")
            }
            "night_vision" -> {
                cameraManager.setNightVision(value != "off" && value != "false" && value != "0")
                okText("ok")
            }
            "ffc" -> {
                // WebUI sends "toggle" when it does not know the current state
                val front = if (value == "toggle") !cameraManager.isUsingFrontCamera()
                else value == "on" || value == "true" || value == "1"
                cameraManager.setFrontFacingCamera(front)
                okText(if (front) "front" else "back")
            }
            "motion_detection", "security" -> {
                val on = value == "on" || value == "true" || value == "1"
                config.securityEnabled = on
                motionDetector.enabled = on
                motionRecorder.enabled = on && config.motionRecord
                okText("ok")
            }
            "motion_sensitivity" -> {
                motionDetector.setSensitivity(value.toIntOrNull() ?: 50)
                config.motionSensitivity = motionDetector.sensitivity
                okText("ok")
            }
            "recording" -> {
                val on = value == "on" || value == "true" || value == "1"
                config.motionRecord = on
                motionRecorder.enabled = on && config.securityEnabled
                if (!on) motionRecorder.stopAllIfIdle()
                okText("ok")
            }
            "pre_record_seconds" -> {
                motionRecorder.preRecordSeconds = value.toIntOrNull() ?: 2
                config.preRecordSeconds = motionRecorder.preRecordSeconds
                okText("ok")
            }
            "max_clip_seconds" -> {
                motionRecorder.maxClipSeconds = value.toIntOrNull() ?: 300
                config.maxClipSeconds = motionRecorder.maxClipSeconds
                okText("ok")
            }
            "audio_enabled", "audio" -> {
                config.audioEnabled = value != "off" && value != "false" && value != "0"
                okText("ok")
            }
            "torch" -> handleTorch(value != "off" && value != "false")
            "force_start", "start" -> { requestServiceStart(); okText("starting") }
            "force_stop", "stop" -> { requestServiceStop(); okText("stopping") }
            else -> notFound("unknown setting: $name")
        }
    } catch (e: Exception) {
        newFixedLengthResponse(Status.INTERNAL_ERROR, "text/plain", "error: ${e.message}")
    }

    private fun resolutionFor(q: Int): CameraConfig.Resolution = when {
        q >= 1080 -> CameraConfig.Resolution.FullHD
        q >= 720 -> CameraConfig.Resolution.HD720
        q >= 480 -> CameraConfig.Resolution.VGA
        else -> CameraConfig.Resolution.QVGA
    }

    /** Lets the WebUI restart the streaming service (e.g. after a camera error). */
    private fun requestServiceStart() {
        try {
            val intent = Intent(context, com.ocubea.service.StreamService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        } catch (_: Exception) {}
    }

    private fun requestServiceStop() {
        try {
            context.startService(
                Intent(context, com.ocubea.service.StreamService::class.java)
                    .setAction(com.ocubea.service.StreamService.ACTION_STOP)
            )
        } catch (_: Exception) {}
    }

    private fun handleSettingsBulk(session: IHTTPSession): Response {
        val p = parseBodyParams(session)
        var applied = 0
        for ((k, v) in p) {
            if (k == "postData") continue
            val resp = applySetting(k.lowercase(), v)
            if (resp == okText("ok")) applied++
        }
        return okText("applied $applied")
    }

    // ── Telemetry ──────────────────────────────────────────────

    private fun handleStatusJson(): Response {
        val cfg = cameraManager.getConfiguration()
        val json = buildString {
            append("{")
            append("\"status\":\"ok\",")
            append("\"app\":\"OcuBea\",")
            append("\"version\":\"${versionName()}\",")
            append("\"uptime_s\":${uptimeSeconds()},")
            append("\"camera_active\":${cameraManager.isStreaming},")
            append("\"resolution\":\"${cfg["resolution"]}\",")
            append("\"fps\":${cfg["fps"]},")
            append("\"target_fps\":${cfg["target_fps"]},")
            append("\"frames\":${cfg["frames"]},")
            append("\"dropped\":${cfg["dropped"]},")
            append("\"viewers\":${cfg["viewers"]},")
            append("\"jpeg_quality\":${cfg["jpeg_quality"]},")
            append("\"night_vision\":${cfg["night_vision"]},")
            append("\"effect\":\"${cfg["effect"]}\",")
            append("\"front_camera\":${cfg["front_camera"]},")
            append("\"torch\":$torchOn,")
            append("\"zoom\":{\"level\":${cameraManager.zoomRatio()},")
            append("\"max\":${cameraManager.maxZoomRatio()}},")
            append("\"motion\":{\"enabled\":${motionDetector.enabled},")
            append("\"detected\":${motionDetector.motionDetected},")
            append("\"sensitivity\":${motionDetector.sensitivity}},")
            append("\"recording\":{\"enabled\":${motionRecorder.enabled},")
            append("\"active\":${motionRecorder.recording},")
            append("\"count\":${motionRecorder.listRecordings().size},")
            append("\"last\":\"${motionRecorder.lastRecordingFile.orEmpty()}\"},")
            append("\"audio\":{\"enabled\":${config.audioEnabled},")
            append("\"clients\":${audio.clientCount()}},")
            append("\"auth_required\":${auth.isEnabled()},")
            append("\"battery_level\":${batteryLevel()}")
            append("}")
        }
        return newFixedLengthResponse(Status.OK, "application/json", json)
    }

    private fun handleSensorsJson(): Response =
        newFixedLengthResponse(Status.OK, "application/json", sensors.snapshotJson(listeningPort, cameraManager.isStreaming))

    private fun handleConfigJson(): Response {
        val entries = config.snapshot().entries.joinToString(",") { (k, v) ->
            val jv = when (v) {
                is String -> "\"${v.jsonEscape()}\""
                is Boolean, is Int, is Long, is Float -> v.toString()
                else -> "null"
            }
            "\"$k\":$jv"
        }
        return newFixedLengthResponse(Status.OK, "application/json", "{$entries}")
    }

    /** Escapes a string for safe inclusion inside a JSON string literal. */
    private fun String.jsonEscape(): String {
        val sb = StringBuilder(length + 8)
        for (c in this) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c.code < 0x20) sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun versionName(): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "dev"
    } catch (_: Exception) { "dev" }

    private val startedAt = System.currentTimeMillis()
    private fun uptimeSeconds(): Long = (System.currentTimeMillis() - startedAt) / 1000

    private fun batteryLevel(): Int = try {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
        bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
    } catch (_: Exception) { -1 }

    // ── Recordings ─────────────────────────────────────────────

    /**
     * GET  /recordings            — list
     * GET  /recordings/<file>     — download
     * DELETE /recordings/<file>   — delete
     * POST /recordings/delete     — delete with ?name=
     */
    private fun handleRecordings(session: IHTTPSession): Response {
        val uri = session.uri ?: "/recordings"
        val rest = uri.removePrefix("/recordings").substringBefore('?').trimStart('/')
        val dir = recordingsDir()

        return when {
            rest.isEmpty() && session.method == Method.GET -> {
                val files = motionRecorder.listRecordings()
                val items = files.joinToString(",") {
                    """{"name":"${it.name}","size":${it.length()},"modified":${it.lastModified()}}"""
                }
                newFixedLengthResponse(Status.OK, "application/json", """{"recordings":[$items]}""")
            }

            (session.method == Method.DELETE && rest.isNotEmpty()) ||
                (session.method == Method.POST && rest.startsWith("delete")) -> {
                val name = if (rest.isNotEmpty() && rest != "delete") rest else parseParams(session)["name"].orEmpty()
                okText(if (motionRecorder.deleteRecording(name)) "deleted" else "not found")
            }

            else -> {
                // Reject traversal before touching the filesystem
                if (rest.isEmpty() || !rest.endsWith(".avi") || rest.contains("..") || rest.contains('/')) {
                    return badRequest("invalid recording name")
                }
                val file = File(dir, rest)
                if (!file.exists() || !file.canonicalPath.startsWith(dir.canonicalPath)) {
                    return notFound(rest)
                }
                newFixedLengthResponse(Status.OK, "video/x-msvideo", file.inputStream(), file.length())
            }
        }
    }

    private fun recordingsDir(): File =
        (context.getExternalFilesDir(null) ?: context.filesDir).resolve("recordings")

    // ═══ Helpers ═══════════════════════════════════════════════

    private fun withCors(resp: Response): Response = resp.apply {
        addHeader("Access-Control-Allow-Origin", "*")
        addHeader("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS")
        addHeader("Access-Control-Allow-Headers", "Authorization, Content-Type, X-Auth-Token")
    }

    private fun describe(): String = buildString {
        appendLine("OcuBea ${versionName()} — IP Webcam compatible IP camera")
        appendLine("stream:      /video  /shot.jpg  /audio.wav")
        appendLine("status:      /status.json  /sensors.json  /config.json")
        appendLine("controls:    /focus  /ptz?zoom=  /torchon  /torchoff  /settings/<name>?set=<v>")
        appendLine("onvif:       POST /onvif/device_service")
        appendLine("recordings:  /recordings  /recordings/<name>.avi")
        if (auth.isEnabled()) appendLine("auth:        token required (?token=, Bearer, X-Auth-Token)")
    }

    private fun okText(t: String) = newFixedLengthResponse(Status.OK, "text/plain", t)
    private fun badRequest(t: String) = newFixedLengthResponse(Status.BAD_REQUEST, "text/plain", t)
    private fun notFound(t: String) = newFixedLengthResponse(Status.NOT_FOUND, "text/plain", "Not found: $t")

    private fun parseParams(session: IHTTPSession): Map<String, String> =
        session.parameters.mapValues { it.value.firstOrNull() ?: "" }

    private fun parseBodyParams(session: IHTTPSession): Map<String, String> {
        val body = readBody(session)
        val map = HashMap<String, String>()
        for (pair in body.split('&')) {
            if (pair.isBlank()) continue
            val kv = pair.split('=', limit = 2)
            if (kv.size == 2) {
                map[java.net.URLDecoder.decode(kv[0], "UTF-8")] =
                    java.net.URLDecoder.decode(kv[1], "UTF-8")
            }
        }
        map.putAll(parseParams(session))
        return map
    }

    private fun readBody(session: IHTTPSession): String {
        val map = HashMap<String, String>()
        try { session.parseBody(map) } catch (_: Exception) {}
        return map["postData"] ?: ""
    }

    private fun serveWebpage(): Response = try {
        val bytes = context.assets.open("index.html").readBytes()
        newFixedLengthResponse(Status.OK, "text/html; charset=utf-8", ByteArrayInputStream(bytes), bytes.size.toLong())
    } catch (_: Exception) {
        newFixedLengthResponse(Status.INTERNAL_ERROR, "text/plain", "Web UI missing")
    }

    companion object {
        const val DEFAULT_PORT = 8080

        val EFFECTS = listOf("none", "mono", "negative", "sepia", "nightvision")
    }
}

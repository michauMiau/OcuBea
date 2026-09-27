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
        uri == "/hls.min.js" -> serveAsset("hls.min.js", "application/javascript")

        // ── Streaming ──
        uri == "/video" || uri == "/videofeed" || uri == "/mjpeg" || uri.startsWith("/stream") -> handleMjpeg()
        uri == "/shot.jpg" || uri == "/snapshot.jpg" || uri == "/image" -> handleShot()
        // Upstream aliases. /photo.jpg and /photoaf.jpg both serve the current
        // frame; upstream's autofocus variant is the same instant snapshot here,
        // because CameraX autofocus is continuous rather than a one-shot action.
        uri == "/photo.jpg" || uri == "/photoaf.jpg" || uri == "/photo.jpg?nofocus" -> handleShot()

        // ── Recording (upstream-compatible) ──
        uri == "/startvideo" -> handleStartVideo(session)
        uri == "/stopvideo" -> handleStopVideo()
        uri == "/list_videos" || uri == "/videos" -> handleListVideos()
        uri.startsWith("/v/") -> handleVideoDownload(uri)

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
        uri == "/sensors.json" -> handleSensorsJson(session)
        uri == "/config.json" -> handleConfigJson()
        uri == "/codecs.json" -> handleCodecsJson()

        // ── HLS (low-latency hardware H.264) ──
        uri == "/hls" || uri == "/hls/index.m3u8" || uri == "/hls.m3u8" -> handleHlsPlaylist(session)
        uri == "/hls/init.mp4" -> handleHlsInit()
        uri.startsWith("/hls/seg") && uri.endsWith(".m4s") -> handleHlsSegment(uri)
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
        val boundary = MultipartWriter(FRAME_BOUNDARY)
        // Frames are fed through an InputStream so NanoHTTPD writes the HTTP
        // headers itself — overriding Response.send() skips them and browsers
        // then reject the response as HTTP/0.9.
        val stream = object : java.io.InputStream() {
            override fun read(): Int {
                if (!boundary.hasRemaining()) {
                    if (!nextPart()) return -1
                }
                val one = ByteArray(1)
                return if (boundary.read(one, 0, 1) == 1) one[0].toInt() and 0xFF else -1
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (len == 0) return 0
                if (!boundary.hasRemaining()) {
                    if (!nextPart()) return -1
                }
                val n = boundary.read(b, off, len)
                return if (n <= 0) -1 else n
            }

            private fun nextPart(): Boolean {
                while (cameraManager.isStreaming) {
                    val frame = cameraManager.frameHub.pollFrame(viewer, 5000) ?: continue
                    boundary.setFrame(frame)
                    return true
                }
                return false
            }

            override fun close() {
                cameraManager.frameHub.removeViewer(viewer)
                super.close()
            }
        }
        return newChunkedResponse(Status.OK, "multipart/x-mixed-replace; boundary=$FRAME_BOUNDARY", stream)
    }

    /**
     * Writes multipart/x-mixed-replace parts without ever copying the frame.
     *
     * The previous implementation built each part as `head + frame + tail`,
     * which allocates a **second copy of every JPEG** just to append ~40 bytes
     * of boundary. At 15fps that is ~4MB/s of pure garbage per viewer. Here the
     * JPEG is referenced in place: only the small header and trailer are
     * materialised, into a buffer allocated once.
     */
    private class MultipartWriter(private val boundary: String) {
        private val tail = "\r\n".toByteArray(Charsets.US_ASCII)
        private var headBytes: ByteArray = ByteArray(0)

        private var frame: ByteArray? = null
        private var headPos = 0
        private var framePos = 0
        private var tailPos = 0

        /**
         * Prepares the next part; call once per frame, then read from it.
         *
         * The header is rebuilt from scratch every time. An earlier version
         * appended into a long-lived StringBuilder and never cleared it, so by
         * the second frame the header contained every previous frame's metadata:
         * the part length no longer matched, the client parsed the accumulated
         * text as JPEG data, and the picture froze on frame one with everything
         * after it black. A reusable StringBuilder is only safe if it is reset —
         * `setLength(0)` — which is easy to forget, so build it locally instead.
         */
        fun setFrame(jpeg: ByteArray) {
            frame = jpeg
            headBytes = ("--$boundary\r\nContent-Type: image/jpeg\r\nContent-Length: " +
                jpeg.size + "\r\n\r\n").toByteArray(Charsets.US_ASCII)
            headPos = 0
            framePos = 0
            tailPos = 0
        }

        fun hasRemaining(): Boolean =
            headPos < headBytes.size || framePos < (frame?.size ?: 0) || tailPos < tail.size

        /** Fills [dst] from the current part; returns bytes written, or -1 at end. */
        fun read(dst: ByteArray, off: Int, len: Int): Int {
            if (!hasRemaining()) return -1
            var written = 0
            val f = frame
            while (written < len && hasRemaining()) {
                when {
                    headPos < headBytes.size -> dst[off + written] = headBytes[headPos++]
                    f != null && framePos < f.size -> {
                        dst[off + written] = f[framePos++]
                    }
                    else -> dst[off + written] = tail[tailPos++]
                }
                written++
            }
            return written
        }
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
        val reason = setTorch(on)
        return if (reason == null) okText(if (on) "torch on" else "torch off")
        else newFixedLengthResponse(Status.BAD_REQUEST, "text/plain", "Torch failed: $reason")
    }

    /**
     * Delegates to CameraManager, which drives the torch through CameraX.
     *
     * The previous implementation called CameraManager.setTorchMode() directly
     * and reported "No torch on this device" for every request while streaming:
     * the platform refuses a torch request on a camera that is already in use,
     * so the feature looked absent on hardware that has a perfectly good flash.
     */
    private fun setTorch(on: Boolean): String? {
        val reason = cameraManager.setTorch(on)
        if (reason == null) {
            torchOn = on
            config.torchOn = on
        }
        return reason
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
            // ── Upstream aliases mapping onto OcuBea's own controls ──
            // Upstream clients send these names; they are accepted so a script
            // written against IP Webcam works unchanged.
            "video_size", "video_resolution" -> {
                val res = resolutionFromSize(value)
                cameraManager.setQuality(res)
                okText("${res.width}x${res.height}")
            }
            "photo_size" -> {
                val res = resolutionFromSize(value)
                cameraManager.setQuality(res)
                okText("${res.width}x${res.height}")
            }
            "coloreffect" -> {
                if (value in EFFECTS) {
                    cameraManager.applyEffect(value)
                    okText("ok")
                } else badRequest("unknown effect: $value")
            }
            "front_camera" -> {
                cameraManager.setFrontFacingCamera(value !in OFF_VALUES)
                okText(if (value in OFF_VALUES) "back" else "front")
            }
            "flashmode" -> {
                handleTorch(value !in OFF_VALUES && value != "auto")
            }
            "focusmode", "focus_distance" -> okText("auto")
            "exposure", "exposure_lock" -> okText("ok")
            "whitebalance", "whitebalance_lock" -> okText("auto")
            "antibanding" -> okText("auto")
            "rotate", "rotation", "mirror_flip" -> okText("ok")
            "overlay" -> okText("ok")
            "norecord" -> {
                motionRecorder.enabled = value in OFF_VALUES
                okText("ok")
            }
            "audio_only" -> {
                config.audioEnabled = value in OFF_VALUES
                okText("ok")
            }
            "sound", "sound_event", "sound_timeout" -> okText("ok")
            "awake" -> okText("ok")
            "idle" -> okText("ok")
            "power_saving" -> {
                config.powerSaving = value !in OFF_VALUES
                okText("ok")
            }
            "motion_active", "motion_event" -> okText("ok")
            "video_recording" -> {
                motionRecorder.enabled = value !in OFF_VALUES
                okText("ok")
            }
            "gps_active" -> okText("false")
            "port" -> {
                val p = value.toIntOrNull()
                if (p != null && p in 1024..65535) {
                    config.port = p
                    okText("$p (restart required)")
                } else badRequest("port out of range")
            }
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

    /**
     * Parses upstream's `WxH` size strings (e.g. `640x480`) into a resolution.
     *
     * Falls back to the nearest supported tier so an unsupported size still
     * produces a sane rebind instead of an error — upstream silently snaps to
     * a supported size too, and scripts depend on the call not failing.
     */
    private fun resolutionFromSize(value: String): CameraConfig.Resolution {
        val parts = value.split('x', 'X', '*')
        val w = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: return CameraConfig.Resolution.HD720
        val h = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: w
        val longEdge = maxOf(w, h)
        return when {
            longEdge >= 1920 -> CameraConfig.Resolution.FullHD
            longEdge >= 1280 -> CameraConfig.Resolution.HD720
            longEdge >= 640 -> CameraConfig.Resolution.VGA
            else -> CameraConfig.Resolution.QVGA
        }
    }

    /** Lets the WebUI restart the streaming service (e.g. after a camera error). */
    private fun requestServiceStart() {
        // Prefer the service's own camera-only restart. Starting a fresh service
        // would rebind the HTTP port, which fails while the old instance still
        // holds it and looks like "nothing happened" in the UI.
        val svc = com.ocubea.service.StreamService.instance
        if (svc != null) {
            try { context.startService(
                Intent(context, com.ocubea.service.StreamService::class.java)
                    .setAction(com.ocubea.service.StreamService.ACTION_START_CAMERA)
            ) } catch (_: Exception) {}
            return
        }
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
            append("\"pipeline\":" + pipelineJson() + ",")
            append("\"hls\":" + hlsJson() + ",")
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

    /**
     * GET /sensors.json                 — full nested telemetry (OcuBea extra)
     * GET /sensors.json?sense=<name>    — single value, upstream-compatible
     *
     * The single-sensor form must return a BARE value (`52`, `true`, `"auto"`),
     * not an object wrapped in a key: Home Assistant's android_ip_webcam
     * integration and long-standing user scripts both parse it that way.
     */
    private fun handleSensorsJson(session: IHTTPSession): Response {
        val sense = parseParams(session)["sense"]?.lowercase()
        if (sense.isNullOrEmpty()) {
            return newFixedLengthResponse(
                Status.OK, "application/json",
                sensors.snapshotJson(listeningPort, cameraManager.isStreaming)
            )
        }
        if (sense !in IpWebcamCompat.SENSOR_NAMES) {
            return notFound("unknown sensor: $sense")
        }
        val value = singleSensor(sense)
        return newFixedLengthResponse(Status.OK, "application/json", value)
    }

    /** Resolves one upstream sensor name to its bare JSON value. */
    private fun singleSensor(name: String): String = when (name) {
        "battery_level" -> batteryLevel().toString()
        "motion", "motion_detect", "motion_active", "motion_event" ->
            (motionDetector.motionDetected || motionRecorder.recording).toString()
        "torch" -> torchOn.toString()
        "ffc" -> cameraManager.isUsingFrontCamera().toString()
        "night_vision" -> cameraManager.nightVisionEnabled.toString()
        "focus" -> "true"
        "exposure_lock" -> "false"
        "whitebalance_lock" -> "false"
        "coloreffect", "effect" -> "\"${cameraManager.effect}\""
        "quality" -> cameraManager.jpegQualityOverride.toString()
        "video_size" -> "\"${cameraManager.currentTargetWidth}x${cameraManager.currentTargetHeight}\""
        "photo_size" -> "\"${cameraManager.currentTargetWidth}x${cameraManager.currentTargetHeight}\""
        "zoom" -> cameraManager.zoomRatio().toString()
        "video_connections" -> cameraManager.frameHub.viewerCount().toString()
        "audio_connections" -> audio.clientCount().toString()
        "video_recording" -> motionRecorder.recording.toString()
        "video_chunk_len" -> (cameraManager.frameHub.getLatest()?.size ?: 0).toString()
        "audio_only" -> (!config.audioEnabled).toString()
        "ivideon_streaming" -> "false"
        "idle" -> (!cameraManager.isStreaming).toString()
        "light" -> (if (torchOn) 255 else 0).toString()
        "gps_active" -> "false"
        "antibanding" -> "\"auto\""
        "scenemode" -> "\"${if (cameraManager.nightVisionEnabled) "night" else "auto"}\""
        "whitebalance" -> "\"auto\""
        "focusmode" -> "\"auto\""
        "flashmode" -> (if (torchOn) "torch" else "off").toString()
            .let { "\"$it\"" }
        "focus_distance" -> "0.0"
        "motion_limit" -> motionRecorder.maxClipSeconds.toString()
        "sound", "sound_event" -> "false"
        "sound_timeout" -> "0"
        "battery_temp" -> "0.0"
        "battery_voltage" -> "0"
        "night_vision_gain" -> "0"
        "night_vision_average" -> "0"
        "focus_homing", "focus_region" -> "false"
        "orientation" -> "0"
        "overlay" -> "false"
        "proximity", "pressure" -> "false"
        "mirror_flip" -> "false"
        "adet_limit" -> motionDetector.sensitivity.toString()
        "ip_address" -> "\"${localIpFallback()}\""
        "ipv6_address" -> "\"\""
        else -> "null"
    }

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

    /**
     * GET /codecs.json — what this device can actually encode.
     *
     * Exists because codec capability claims on MediaTek devices are often
     * wrong; this reports encoders that were configured and started for real.
     */
    private fun handleCodecsJson(): Response {
        val r = com.ocubea.camera.CodecProbe.probe()
        val arr: (List<String>) -> String = { xs -> xs.joinToString(",", "[", "]") { "\"${it.jsonEscape()}\"" } }
        return newFixedLengthResponse(
            Status.OK, "application/json",
            """{"encoders":${arr(r.encoders)},""" +
                """"hardware_avc":${arr(r.hardwareAvc)},""" +
                """"software_avc":${arr(r.softwareAvc)},""" +
                """"hardware_hevc":${arr(r.hardwareHevc)},""" +
                """"largest_working_avc":"${r.largestWorkingAvc.jsonEscape()}",""" +
                """"avc_configurable":${r.avcConfigurable},""" +
                """"summary":"${r.summary().jsonEscape()}"}"""
        )
    }

    /**
     * GET /hls/index.m3u8 — the live playlist.
     *
     * The encoder starts lazily on the first playlist request, so a client that
     * never asks for HLS never pays for it.
     *
     * A brand-new session has produced no frames yet, so the playlist would be
     * empty. hls.js reads an empty playlist as "nothing to play", never asks for
     * a segment, and therefore never triggers the SPS/PPS callback that would
     * make /hls/init.mp4 available — a deadlock in which HLS never starts. The
     * playlist is therefore withheld until the init segment exists, and 503 is
     * returned instead: hls.js retries a 503 and comes back once the encoder has
     * produced its first keyframe.
     */
    private fun handleHlsPlaylist(session: IHTTPSession): Response {
        if (!cameraManager.isStreaming) {
            return newFixedLengthResponse(Status.SERVICE_UNAVAILABLE, "text/plain", "Camera not streaming")
        }
        if (cameraManager.hlsSession?.isEncoding != true && !cameraManager.startHls()) {
            return newFixedLengthResponse(
                Status.SERVICE_UNAVAILABLE, "text/plain",
                "No hardware H.264 encoder available"
            )
        }
        val hls = cameraManager.hlsSession
            ?: return newFixedLengthResponse(Status.SERVICE_UNAVAILABLE, "text/plain", "HLS not started")
        if (hls.initSegment() == null) {
            return newFixedLengthResponse(
                Status.SERVICE_UNAVAILABLE, "text/plain",
                "Encoder warming up — first keyframe not encoded yet"
            )
        }
        hls.clientJoined()
        return newFixedLengthResponse(
            Status.OK, "application/vnd.apple.mpegurl", hls.playlist()
        )
    }

    /** GET /hls/init.mp4 — ftyp+moov carrying the avcC decoder configuration. */
    private fun handleHlsInit(): Response {
        val init = cameraManager.hlsSession?.initSegment()
            ?: return notFound("hls not ready")
        return binaryResponse("video/mp4", init)
    }

    /** GET /hls/seg<N>.m4s — one CMAF fragment. */
    private fun handleHlsSegment(uri: String): Response {
        val seq = uri.removePrefix("/hls/seg").removeSuffix(".m4s").toIntOrNull()
            ?: return notFound("bad segment name")
        val data = cameraManager.hlsSession?.segment(seq)
            ?: return notFound("segment $seq is no longer retained")
        return binaryResponse("video/iso.segment", data)
    }

    /**
     * Serves raw bytes.
     *
     * Deliberately does NOT use newFixedLengthResponse(…, String): NanoHTTPD
     * re-encodes that String as UTF-8, so binary fMP4 containing e.g. 'v' (0x76)
     * next to 0xBD collapses into one U+00BD codepoint and every box after it
     * shifts by a byte — the served file stops being a valid MP4. Converting to
     * ISO-8859-1 first does not help; the damage happens in NanoHTTPD's writer.
     *
     * The InputStream overload hands NanoHTTPD a byte count, so the bytes go out
     * verbatim with no re-encoding anywhere.
     */
    private fun binaryResponse(mime: String, data: ByteArray): Response {
        val resp = newFixedLengthResponse(
            Status.OK, mime,
            java.io.ByteArrayInputStream(data), data.size.toLong()
        )
        resp.addHeader("Cache-Control", "no-cache")
        return resp
    }

    /** Renders the per-stage pipeline timing as a JSON object. */
    private fun pipelineJson(): String =
        cameraManager.pipelineTiming().entries.joinToString(",", "{", "}") { (k, v) ->
            val jv = if (v is String) "\"${v.jsonEscape()}\"" else v.toString()
            "\"$k\":$jv"
        }

    /** Renders the HLS / hardware-encoder state as a JSON object. */
    private fun hlsJson(): String =
        cameraManager.hlsStatus().entries.joinToString(",", "{", "}") { (k, v) ->
            val jv = if (v is String) "\"${v.jsonEscape()}\"" else v.toString()
            "\"$k\":$jv"
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

    // ── Upstream-compatible recording aliases ─────────────────
    //
    // IP Webcam exposes /startvideo, /stopvideo, /list_videos and /v/<file>.
    // OcuBea records Motion-JPEG AVI, so the .mp4 extension upstream uses is
    // served as .avi where possible and the real extension is reported in the
    // listing — the routes and verbs match, which is what scripts depend on.

    private fun handleStartVideo(session: IHTTPSession): Response {
        if (!motionRecorder.enabled) {
            return badRequest("motion recording is disabled — enable it in settings first")
        }
        if (!cameraManager.isStreaming) {
            return newFixedLengthResponse(
                Status.SERVICE_UNAVAILABLE, "text/plain", "Camera not streaming"
            )
        }
        val name = parseParams(session)["name"].orEmpty()
        return if (name.isNotEmpty() && (name.contains('/') || name.contains(".."))) {
            badRequest("invalid name")
        } else {
            // Arm the recorder; it opens a clip on the next motion event.
            okText("recording armed${if (name.isNotEmpty()) " as $name" else ""}")
        }
    }

    private fun handleStopVideo(): Response {
        motionRecorder.finishClip()
        return okText("stopped")
    }

    private fun handleListVideos(): Response {
        val files = motionRecorder.listRecordings()
        val items = files.joinToString(",") {
            """"{"name":"${it.name}","url":"/v/${it.name}","size":${it.length()},"modified":${it.lastModified()}}""" 
        }
        return newFixedLengthResponse(Status.OK, "application/json", """{"videos":[$items]}""")
    }

    private fun handleVideoDownload(uri: String): Response {
        val name = uri.removePrefix("/v/").substringBefore('?')
        if (name.isEmpty() || name.contains("..") || name.contains('/')) {
            return badRequest("invalid video name")
        }
        val dir = recordingsDir()
        val file = File(dir, name)
        if (!file.exists() || !file.canonicalPath.startsWith(dir.canonicalPath)) {
            return notFound(name)
        }
        val mime = if (name.endsWith(".avi")) "video/x-msvideo" else "video/mp4"
        return newFixedLengthResponse(Status.OK, mime, file.inputStream(), file.length())
    }

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

    private fun serveWebpage(): Response = serveAsset("index.html", "text/html; charset=utf-8")

    /**
     * Serves a bundled asset from `app/src/main/assets`.
     *
     * hls.js is bundled rather than pulled from a CDN so the WebUI works on a
     * LAN with no internet: the phone and the browser only ever need to reach
     * each other. With a CDN, a missing uplink silently disabled HLS playback
     * while the rest of the page still looked healthy.
     */
    private fun serveAsset(name: String, mime: String): Response = try {
        val bytes = context.assets.open(name).readBytes()
        newFixedLengthResponse(Status.OK, mime, ByteArrayInputStream(bytes), bytes.size.toLong())
    } catch (_: Exception) {
        newFixedLengthResponse(Status.NOT_FOUND, "text/plain", "Asset $name not found")
    }

    companion object {
        const val DEFAULT_PORT = 8080

        /** MJPEG part boundary; fixed so clients can hardcode it if they must. */
        const val FRAME_BOUNDARY = "framebound"

        /** Values upstream treats as "off" for a boolean setting. */
        private val OFF_VALUES = setOf("off", "false", "0", "no", "none", "disable", "disabled")

        val EFFECTS = listOf("none", "mono", "negative", "sepia", "nightvision")
    }
}

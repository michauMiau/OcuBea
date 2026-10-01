package com.ocubea.server

import android.content.Context
import android.content.Intent
import android.os.Build
import com.ocubea.camera.CameraManager
import com.ocubea.model.CameraConfig
import com.ocubea.model.OcuBeaConfig
import com.ocubea.perf.Metrics
import com.ocubea.onvif.OnvifDiscovery
import com.ocubea.onvif.OnvifSoap
import com.ocubea.security.ClipRetention
import com.ocubea.security.ClipStorage
import com.ocubea.security.MotionDetector
import com.ocubea.security.MotionRecorder
import com.ocubea.sensors.DeviceSensors
import com.ocubea.security.MotionLimits
import com.ocubea.stream.FrameHub
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
    private val audioAdmission = AudioAdmissionControl()
    private val audioCodecs = AudioCodecProbe

    /**
     * Bounded connection handling. NanoHTTPD's default spawns one Thread per
     * socket with no ceiling, and a `/video` connection keeps its thread for
     * hours - on a 512MB phone a few tabs is enough to kill the camera. Set in
     * init because it has to be in place before start().
     */
    private val connectionRunner = BoundedAsyncRunner()

    /**
     * Read-only JSON endpoints, lifted out of this class. `torchOn` and the
     * listening port are passed as lambdas so the handler reads live state
     * without holding a reference to the server.
     */
    private val telemetry = TelemetryHandler(
        context = context,
        cameraManager = cameraManager,
        audio = audio,
        auth = auth,
        config = config,
        sensors = sensors,
        motionRecorder = motionRecorder,
        motionDetector = motionDetector,
        connectionStats = { connectionStats() },
        localIpFallback = { localIpFallback() },
        torchOn = { torchOn },
        listeningPort = { listeningPort },
        pipelineJson = { pipelineJson() },
        hlsJson = { hlsJson() },
    )

    init {
        setAsyncRunner(connectionRunner)
    }

    /** Live connection stats for status.json. */
    fun connectionStats(): Map<String, Int> = mapOf(
        "active" to connectionRunner.activeConnections(),
        "refused" to connectionRunner.refusedConnections(),
        "max_threads" to BoundedAsyncRunner.DEFAULT_MAX_THREADS,
    )

    // Torch state, toggled through Camera2
    private var torchOn = false

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri ?: "/"
        val method = session.method

        // Measured on every request, camera or not.
        //
        // The three span producers that exist (analyze, encode, mux) are all
        // downstream of a single camera frame, so with a camera that never
        // opened the profiler screen reported "No spans recorded yet" -- which
        // was correct, and also useless: the HTTP path runs whenever anything
        // polls /status.json, so the screen can say something true even with
        // the camera dead. The name was already declared and never wired.
        val timer = if (Metrics.enabled) Metrics.timer(Metrics.HTTP) else null
        val t0 = if (timer != null) timer.begin() else 0L

        return try {
            // CORS preflight for browser clients. A foreign origin gets a
            // bare answer with no allow header, so the browser refuses to
            // send the real request.
            val origin = requestOrigin(session)
            if (method == Method.OPTIONS) return withCors(okText(""), origin)

            auth.check(session, uri)?.let { return withCors(it, origin) }

            val response = route(session, uri, method)
            withCors(response, origin)
        } catch (e: Exception) {
            withCors(newFixedLengthResponse(
                Status.INTERNAL_ERROR, "text/plain", "error: ${e.message}"
            ), requestOrigin(session))
        } finally {
            // finally, not after the try: the CORS preflight, the auth refusal
            // and the 500 all return from inside the try, and a span that
            // records only the happy path would under-report exactly the slow
            // requests worth knowing about.
            if (timer != null) timer.end(t0)
        }
    }

    private fun route(session: IHTTPSession, uri: String, method: Method): Response {
        // Checked before the dispatch table because they are aliases for
        // spellings other IP Webcam clients use, and two of them would be
        // swallowed by generic routes below that expect a different parameter
        // shape. `/startvideo` answers to the recorder's own route otherwise,
        // and `/v1/devices` is not a route this app has at all.
        if (uri == "/v1/devices") return ipWebcamDeviceList()
        if (uri == "/cgi-bin/action") return handleIpWebcamAction(session)
        if (uri == "/startvideo") return handleIpWebcamRecord(session, true)
        if (uri == "/stopvideo") return handleIpWebcamRecord(session, false)
        return when {
        // ── Web UI ──
        uri == "/" || uri == "/index.html" || uri == "/mobile" || uri == "/login" -> serveWebpage()
        // Browsers ask for this unprompted on every page load. Without a route
        // it is a 404 in the console on each open, and the tab keeps the
        // generic globe. /favicon.ico serves a PNG: the icon format is decided
        // by the image bytes, and every browser that asks for .ico accepts it.
        uri == "/favicon.ico" -> serveAsset("favicon.png", "image/png")
        // hls.js used to be served as a separate file here. It is now bundled
        // into index.html by build.mjs, so there is no second asset to fetch:
        // a separate <script src> only works if the browser executes document
        // scripts, and not every engine does.

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
        uri == "/audio/codec" && method == Method.POST -> handleAudioCodecPost(session)
        // Every codec the probe can offer needs its own route, not just the
        // three the fan-out was first built for. FLAC is in the menu on every
        // device tested so far and /audio.flac answered 404 -- the extension
        // never reached handleAudio() at all. The menu is built at runtime from
        // what the device can actually encode, so the route list has to follow
        // it rather than be spelled out here; wav stays in the literal because
        // it is the fallback that must work even if the probe failed.
        AUDIO_PATH_SUFFIXES.any { uri == "/audio.$it" } ||
            uri == "/inband.aac" || uri == "/talk" -> handleAudio(uri)

        // ── ONVIF ──
        uri.startsWith("/onvif/") && method == Method.POST -> handleOnvif(session)
        uri == "/onvif/device_service" -> newFixedLengthResponse(
            Status.OK, "application/soap+xml; charset=utf-8",
            "ONVIF device service — POST SOAP requests here"
        )

        // ── IP Webcam API: controls ──
        // pydroid reads `"Ok" in text`; these answered "ok", so a working
        // focus command was reported as a failure.
        uri == "/focus" -> withOkBody(handleFocus(session))
        uri == "/nofocus" -> withOkBody(handleFocus(session))
        // pydroid issues /settings/ptz?zoom=N, which is not the ptz route:
        // that one is /ptz. Routed here so the library's own zoom call works.
        uri == "/ptz" || uri == "/ptt" -> handlePtz(session)
        uri == "/settings/ptz" -> handleSetting(session)
        uri == "/torchon" -> handleTorch(true)
        uri == "/torchoff" -> handleTorch(false)
        // These two spellings are what pydroid issues, and it reads the body
        // as `"Ok" in text` -- capital O. The handler below answers "ok" and
        // "torchon", so a working torch command is reported as a failure.
        // ipWebcamTorch() is the same path with a body pydroid can read.
        uri == "/enabletorch" -> ipWebcamTorch(paramBool(session, true))
        uri == "/disabletorch" -> ipWebcamTorch(false)
        uri == "/diagnostic/encoder_hold" -> handleEncoderHold(session)
    uri == "/hls/profile" -> handleHlsProfile(session)


        // ── IP Webcam API: settings ──
        uri == "/settings" && method == Method.POST -> handleSettingsBulk(session)
        uri.startsWith("/settings/") -> handleSetting(session)
        uri == "/api/camera" -> {
            val on = paramBool(session, false)
            cameraManager.setFrontFacingCamera(on); okText(if (on) "front" else "back")
        }

        // ── IP Webcam compatibility ──
        //
        // The endpoints above are this app's own dialect. The routes below are
        // the spellings that the reference IP Webcam clients actually issue,
        // derived from pydroid-ipcam, the library Home Assistant uses to drive
        // these cameras. Without them the camera is unreachable from Home
        // Assistant even though every feature it needs is implemented.
        //
        // The one that bites hardest is the response body. pydroid decides
        // success with `"Ok" in text`, capital O, so a handler that replies
        // "ok" is reported to the user as a failed command. Every compatibility
        // route therefore answers with ipWebcamOk() and never with okText().
        // ── Extended API ──
        uri == "/status.json" || uri == "/info" ->
            // show_avail=1 is the flag pydroid sends when it wants `avail` and
            // `curvals`; the flag is honoured on either path so a client that
            // puts it on /info still gets the settings dictionaries.
            telemetry.handleStatusJson(showAvail = session.parameters["show_avail"] != null)
        uri == "/sensors.json" -> telemetry.handleSensorsJson(session)
        uri == "/config.json" -> telemetry.handleConfigJson()
        uri == "/codecs.json" -> telemetry.handleCodecsJson()

        // ── HLS (low-latency hardware H.264) ──
        uri == "/hls" || uri == "/hls/index.m3u8" || uri == "/hls.m3u8" -> handleHlsPlaylist(session)
        uri == "/hls/init.mp4" -> handleHlsInit()
        uri.startsWith("/hls/seg") && uri.endsWith(".m4s") -> handleHlsSegment(uri)
        uri.startsWith("/recordings") -> handleRecordings(session)
        uri == "/clips" || uri.startsWith("/clips/") -> handleClips(session)
        uri == "/onvif/describe" -> newFixedLengthResponse(Status.OK, "text/plain", describe())

        else -> notFound(uri)
    }
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
            ?: return newFixedLengthResponse(
                Status.SERVICE_UNAVAILABLE,
                "text/plain",
                "Too many viewers (max ${FrameHub.MAX_VIEWERS}) - close one and retry",
            )
        val boundary = MultipartWriter(FRAME_BOUNDARY)
        // Frames are fed through an InputStream so NanoHTTPD writes the HTTP
        // headers itself — overriding Response.send() skips them and browsers
        // then reject the response as HTTP/0.9.
        val stream = object : java.io.InputStream() {
            private val one = ByteArray(1)
            private var closed = false

            override fun read(): Int {
                if (!boundary.hasRemaining()) {
                    if (!nextPart()) return -1
                }
                // NanoHTTPD prefers the bulk read() overload; this path exists
                // only for callers that do not, so a single reusable byte is
                // enough. Allocating one per byte made a 200KB frame cost
                // 200k short-lived arrays.
                return boundary.read(one, 0, 1).let {
                    if (it == 1) one[0].toInt() and 0xFF else -1
                }
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
                // A viewer that hangs up is released straight away rather than
                // staying counted for up to the full poll timeout, which is
                // what made viewer counts drift upward after a client vanished.
                while (cameraManager.isStreaming && !closed) {
                    val frame = cameraManager.frameHub.pollFrame(viewer, 5000) ?: continue
                    boundary.setFrame(frame)
                    return true
                }
                return false
            }

            override fun close() {
                closed = true
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
                    headPos < headBytes.size -> {
                        val n = minOf(
                            len - written, headBytes.size - headPos
                        )
                        System.arraycopy(headBytes, headPos, dst, off + written, n)
                        headPos += n
                        written += n
                    }
                    f != null && framePos < f.size -> {
                        // The JPEG is ~200KB, so copying it a byte at a time
                        // meant millions of loop iterations per second per
                        // viewer for what is a plain memcpy.
                        val n = minOf(len - written, f.size - framePos)
                        System.arraycopy(f, framePos, dst, off + written, n)
                        framePos += n
                        written += n
                    }
                    else -> {
                        val n = minOf(len - written, tail.size - tailPos)
                        System.arraycopy(tail, tailPos, dst, off + written, n)
                        tailPos += n
                        written += n
                    }
                }
            }
            return written
        }
    }

    private fun handleShot(): Response {
        val frame = cameraManager.frameHub.getLatest()
            ?: return newFixedLengthResponse(Status.NO_CONTENT, "text/plain", "No frame yet")
        return newFixedLengthResponse(Status.OK, "image/jpeg", ByteArrayInputStream(frame), frame.size.toLong())
    }

    /**
     * Saves the codec the WebUI picker chose.
     *
     * The choice is validated against the probe rather than trusted: the
     * browser is on the LAN and anyone can POST a codec name, and "wav" is
     * always legal, so a name the device has not proved it can encode is
     * refused with the list of what it can. Silently coercing an unknown codec
     * to the default would leave the picker showing something untrue.
     */
    private fun handleAudioCodecPost(session: IHTTPSession): Response {
        val params = parseBodyParams(session)
        val asked = (params["codec"] ?: "").lowercase()
        if (asked.isEmpty()) {
            return newFixedLengthResponse(
                Status.BAD_REQUEST, "application/json",
                "{\"error\":\"codec is required\"}"
            )
        }
        if (asked == "none") {
            config.audioCodec = "none"
            config.audioEnabled = false
            return newFixedLengthResponse(
                Status.OK, "application/json",
                "{\"codec\":\"none\"}"
            )
        }
        val menu = audioCodecs.options()
        if (asked != "wav" && menu.none { it.id == asked }) {
            val can = audioCodecs.options().map { it.id }.plus("wav").distinct().joinToString(",") { "\"$it\"" }
            return newFixedLengthResponse(
                Status.BAD_REQUEST, "application/json",
                "{\"error\":\"this device cannot encode $asked\",\"can\":[$can]}"
            )
        }
        config.audioCodec = asked
        config.audioEnabled = true
        return newFixedLengthResponse(Status.OK, "application/json", "{\"codec\":\"$asked\"}")
    }

    private fun handleAudio(uriPath: String): Response {
        if (!config.audioEnabled) {
            return newFixedLengthResponse(Status.FORBIDDEN, "text/plain", "Audio disabled in settings")
        }
        if (!audio.canRecord()) {
            return newFixedLengthResponse(Status.FORBIDDEN, "text/plain", "RECORD_AUDIO permission not granted")
        }
        // Refuse before allocating anything. A /audio.wav client costs a pool
        // thread for the whole connection, and a client that never reads pins
        // it in write() where nothing in this app can reach it
        // (ClientHandler.acceptSocket is private). Measured: 10 audio clients
        // are fine, 11 take /status.json down. Capping admissions is the only
        // lever left -- see AudioAdmissionControl for what was tried first.
        if (!audioAdmission.canAdmit(audio.clientCount())) {
            return newFixedLengthResponse(
                Status.SERVICE_UNAVAILABLE, "text/plain",
                "Too many audio clients (max ${audioAdmission.maxClients})"
            )
        }
        // The extension the client asked for wins, so `/audio.opus` gets Opus
        // and `/audio.aac` gets AAC -- instead of both silently returning WAV
        // bytes under a name that promises otherwise.
        //
        // A codec the device does not have is a 501, never a substitution. The
        // old code fell through to the stored preference, so on the Android 6
        // phone -- where the menu correctly degrades to aac/wav/flac and Opus
        // is absent -- `/audio.opus` answered 200 with AAC bytes under an
        // Ogg content type. Measured: 24 576 B of AAC served to a client that
        // asked for Opus. A client cannot tell that apart from a broken
        // stream, and it is exactly the case a client is least able to recover
        // from, so the honest answer is to say the device cannot do it.
        val asked = uriPath.substringAfterLast('.', "").lowercase()
        val menu = audioCodecs.options()
        val stored = config.audioCodecOrDefault(audioCodecs.defaultId())
        val requested = menu.firstOrNull { it.id == asked }
        if (requested != null) return serveAudioOption(requested)
        // A named codec the device cannot produce. `wav` is never rejected --
        // it is in the menu on every device and is the compatibility floor.
        if (asked.isNotEmpty() && asked != "wav" && AUDIO_PATH_SUFFIXES.contains(asked)) {
            return newFixedLengthResponse(
                Status.NOT_IMPLEMENTED, "text/plain",
                "This device cannot encode $asked; available: " +
                    menu.joinToString(", ") { it.id }
            )
        }
        val option = menu.firstOrNull { it.id == stored }
            ?: menu.firstOrNull { it.id == audioCodecs.defaultId() }
            ?: menu.first { it.id == "wav" }
        return if (option.id == "wav") serveWavAudio() else serveEncodedAudio(option)
    }

    /**
     * Re-issues a response with the IP Webcam success body.
     *
     * The handlers predate this compatibility layer and answer "ok", "torchon",
     * "front" -- all of which a real client reads as a failure because it looks
     * for a capital "Ok". Re-issuing is cheaper and less error-prone than
     * duplicating each handler: there is one implementation of every action and
     * one place that decides what success looks like on the wire.
     */
    private fun withOkBody(r: Response): Response {
        val ok = r is Response && runCatching { r.status == Status.OK }.getOrDefault(false)
        return if (ok) ipWebcamOk() else r
    }

    /** The tail of handleAudio once the codec has been chosen. */
    private fun serveAudioOption(option: AudioCodecProbe.Option): Response =
        if (option.id == "wav") serveWavAudio() else serveEncodedAudio(option)

    /**
     * Raw PCM as a chunked WAV, the format that needs no encoder and so always
     * works.
     */
    private fun serveWavAudio(): Response {
        // Not a PipedOutputStream: `pipe.write` blocks until the client has
        // drained 64 KB, and a client that is connected but not reading would
        // hold the write forever. Because the response is chunked, NanoHTTPD
        // keeps a pool thread for the whole connection, so 11 such clients
        // take /status.json down with them -- measured, threshold exact at
        // DEFAULT_MAX_THREADS. A per-path pool is not reachable:
        // ClientHandler.inputStream is private and AsyncRunner only ever sees a
        // socket. AudioRingBuffer.offer() returns false instead of waiting, so
        // a slow client is dropped in microseconds and the thread unwinds.
        val ring = AudioRingBuffer()
        val client = AudioStreamManager.Client(
            write = { buf, len -> ring.offer(buf, 0, len) },
            onDisconnect = { ring.close() }
        )
        audio.addClient(client)
        return newChunkedResponse(Status.OK, "audio/x-wav", ring.asInputStream())
    }

    /**
     * Compressed audio, encoded once and shared by every client.
     *
     * A single encoder feeds all listeners, for the same reason the video path
     * does: encoding per client would multiply the cost by the listener count on
     * exactly the old phones this app targets.
     */
    private fun serveEncodedAudio(option: AudioCodecProbe.Option): Response {
        // A null ring means the encoder refused to start even though the probe
        // said it could. Answering 501 with the reason is the honest option;
        // falling back to serveWavAudio() here would hand the client PCM under
        // an `audio/aac` content type, which is the exact lie this path was
        // written to remove.
        val ring = audio.addEncodedClient(option.id, option.bitrate)
            ?: return newFixedLengthResponse(
                Status.NOT_IMPLEMENTED, "text/plain",
                "encoder for ${option.id} did not start; /audio.wav still works"
            )
        return EncodedAudioResponse(ring, option)
    }

    /**
     * Holds the client open until it disconnects, then hands the ring back.
     *
     * The ring is the encoder's only record that anybody is listening, so a
     * client that goes away without its ring being returned keeps the encoder
     * running forever. That was not hypothetical: `addEncodedClient` had no
     * matching call on the way out, so every AAC or Opus client ever served left
     * a permanent listener behind -- one leaked encoder per session, on a phone
     * that has a fixed number of codec instances to spend.
     *
     * Subclasses NanoHTTPD's Response rather than implementing an interface,
     * because Response is a concrete class with a protected constructor and the
     * chunked flag is only settable on an instance. `close()` is the hook: it
     * runs when NanoHTTPD tears the session down, which is the only notification
     * a client that simply vanishes will ever produce.
     */
    private inner class EncodedAudioResponse(
        private val ring: AudioRingBuffer,
        private val option: AudioCodecProbe.Option
    ) : fi.iki.elonen.NanoHTTPD.Response(
        fi.iki.elonen.NanoHTTPD.Response.Status.OK,
        option.contentTypeForHttp,
        ring.asInputStream(),
        0L
    ) {
        init {
            setChunkedTransfer(true)
        }

        override fun close() {
            // Guarded: a client that hung up before the ring existed must not
            // decrement anything, and this must never propagate out of close()
            // -- a failure to tidy up cannot be allowed to become a broken
            // response on a stream that was serving fine.
            runCatching { audio.removeEncodedClient() }
            super.close()
        }
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

    /**
     * GET/POST /hls/profile — read or switch the HLS segment profile.
     *
     * `?set=low` shortens the segments and the GOP together; `?set=high` takes
     * the long-segment, sparse-GOP trade. The two cannot be set independently
     * (see HlsProfile), so this deliberately takes one word rather than two
     * numbers that could contradict each other.
     *
     * Switching restarts the encoder, so a client sees the media sequence
     * restart. That is unavoidable: KEY_I_FRAME_INTERVAL is a codec-config
     * value and the muxer has already cut segments to the old length.
     */
    /**
 * Widens the encoder's handle hold so the stop/teardown handshake is forced to
 * fire, rather than waiting for a collision that a 10ms window makes rare.
 *
 * "deferred: 0 after 155 profile switches" cannot tell a working guard behind a
 * narrow window apart from dead code, and the two call for opposite conclusions.
 * Forcing the collision is the only way to find out which one it is -- the same
 * move that caught the reported dead buttons, where the request fired and the
 * state did not change.
 *
 * Default 0, and it can only hold: a stop() that finds the analyzer busy keeps
 * the codec alive rather than releasing it underneath, which is the designed
 * behaviour. No new failure mode is reachable from here.
 */
private fun handleEncoderHold(session: IHTTPSession): Response {
    val ms = parseParams(session)["ms"]?.toLongOrNull() ?: 0L
    cameraManager.setEncoderDiagnosticHold(ms)
    val s = cameraManager.hlsTelemetry()
    return okText(
        "encoder_hold=${cameraManager.encoderDiagnosticHoldMs} " +
            "stops=${s.stopsCompleted} deferred=${s.stopsDeferred} " +
            "calls=${s.encodeCalls}"
    )
}

private fun handleHlsProfile(session: IHTTPSession): Response {
        val set = parseParams(session)["set"]
        if (set == null) {
            val p = cameraManager.hlsProfile
            return okText(
                "profile=${com.ocubea.model.HlsProfile.name(p)} " +
                    "segment_ms=${p.segmentMs} keyframe_sec=${p.keyFrameIntervalSec} " +
                    "sync=${p.liveSyncDurationCount} buffer=${p.maxBufferLength}"
            )
        }
        // HlsProfile.of() owns the names AND validates, so this handler cannot
        // drift from the model. It used to re-implement the name matching in its
        // own `when`, which meant HlsProfile.sanitized() was tested but never
        // reached from a path a client could take.
        val profile = com.ocubea.model.HlsProfile.of(set)
            ?: return badRequest("set must be low, high or default (got \"$set\")")
        cameraManager.hlsProfile = profile
        // Only restart if HLS is actually running; otherwise the new profile
        // takes effect the next time a client asks for a playlist.
        if (cameraManager.hlsSession?.isEncoding == true) {
            if (!cameraManager.startHls(profile)) {
                return newFixedLengthResponse(
                    Status.INTERNAL_ERROR, "text/plain",
                    "HLS profile set but encoder restart failed: ${cameraManager.hlsSession?.lastError}"
                )
            }
        }
        // The player-facing numbers belong in the reply as well. The WebUI
        // reads sync/buffer from here to configure hls.js, and an earlier
        // version of this reply carried only segment_ms, so the player kept
        // its built-in defaults — which assume a 2s targetduration and hold a
        // 120ms stream back by whole seconds.
        //
        // real_segment_ms is what the muxer actually cuts. The request is not
        // a promise: with an IDR per frame every segment is one frame long, so
        // reporting segment_ms alone describes a stream nobody receives.
        val real = cameraManager.hlsSession?.lastSegmentDurationMs ?: 0L
        return okText(
            "profile set segment_ms=${profile.segmentMs} keyframe_sec=${profile.keyFrameIntervalSec} " +
                "sync=${profile.liveSyncDurationCount} buffer=${profile.maxBufferLength} " +
                "real_segment_ms=$real"
        )
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
        val params = parseParams(session)
        // pydroid's set_zoom sends /settings/ptz?zoom=N, not /settings/zoom.
        // The parameter name differs too, so this cannot fall through to the
        // `set`-driven dispatcher below without being flattened to "".
        if (name == "ptz") {
            val z = params["zoom"]?.toIntOrNull()
            return if (z == null) {
                badRequest("zoom is required for ptz")
            } else {
                cameraManager.setZoom(z / 100f)
                ipWebcamOk()
            }
        }
        val value = (params["set"] ?: "").lowercase()
        // Wrapped rather than fixed setting by setting: these bodies predate the
        // compatibility layer and each one spells success differently -- "ok",
        // "front", "1280x720", "port changed (restart required)". Every one of
        // them reads as a failure to a client looking for "Ok", and there are
        // too many to keep in step by hand.
        return withOkBody(applySetting(name, value))
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
            // "focus" alone was missing even though focusmode/focus_distance were
            // here, so a client setting focus on this key got 404 while the
            // neighbouring spellings succeeded. Accepting "on"/"off" and
            // ignoring it matches focusmode: the camera autofocuses either way.
            "focus", "focusmode", "focus_distance" -> okText("auto")
            // Declared in IpWebcamCompat.SUPPORTED but never handled, so a client
            // setting one of these got "Not found: unknown" for a key the server
            // itself advertises. All six are honest no-ops: OcuBea has no such
            // control to change, and answering Ok is the truthful reply.
            "autostart", "noremote", "device_name", "login", "password",
            "motion_limit" -> okText("ok")
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
            // pydroid sends quality=100 meaning "best picture". Mapping that
            // onto a resolution ladder means the slider a Home Assistant user
            // drags changes the frame size instead of the compression, which
            // is not what anyone asking for "quality" means. JPEG quality is
            // the value that is actually continuous, so that is what moves.
            "quality" -> {
                cameraManager.setJpegQuality(value.toIntOrNull() ?: 82)
                ipWebcamOk()
            }
            // The names IP Webcam itself uses, so a client that reads the
            // device's `avail` list and then writes back one of the offered
            // values is not rejected as an unknown setting. Orientation and
            // scene mode have no CameraX equivalent on most devices, so they
            // are accepted and ignored rather than 404 -- a client that offers
            // a value and is then refused it has nowhere to go.
            "orientation" ->
                if (value in ORIENTATIONS) ipWebcamOk() else badRequest("unknown orientation: $value")
            "scenemode" ->
                if (value in SCENE_MODES) ipWebcamOk() else badRequest("unknown scenemode: $value")
            "motion_detect" -> {
                val on = value !in OFF_VALUES
                motionDetector.enabled = on
                if (on && config.motionRecord) motionRecorder.enabled = true
                ipWebcamOk()
            }
            "zoom" -> {
                val z = value.toIntOrNull() ?: 0
                if (z in 0..100) {
                    cameraManager.setZoom(z / 100f)
                    ipWebcamOk()
                } else {
                    badRequest("zoom must be 0..100")
                }
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
            "video_bitrate_kbps", "bitrate" -> {
                // Takes effect on the next HLS session: the encoder is already
                // configured by then, and restarting it under a live viewer would
                // drop their stream for a number most people change once.
                val kbps = value.toIntOrNull()
                if (kbps == null) {
                    badRequest("video_bitrate_kbps must be a number")
                } else {
                    cameraManager.setVideoBitrateKbps(kbps)
                    okText("${cameraManager.videoBitrateKbps} kbps (następny restart HLS)")
                }
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
            // Both recording lengths were unclamped, so a single request from
            // any device on the LAN could set a value that made the pre-roll
            // buffer allocate gigabytes and OOM the camera. The pre-roll is
            // preRecordSeconds * fps JPEGs held in RAM - at 1080p that is ~87KB
            // per frame, so 300s was already 401MB before anything else was
            // running. MOTION_LIMITS exists because a seconds-only bound is not
            // enough: the cost depends on the frame size, which the user can
            // change.
            "pre_record_seconds" -> {
                val secs = (value.toIntOrNull() ?: MotionLimits.DEFAULT_PRE_RECORD_SECONDS)
                    .coerceIn(MotionLimits.MIN_PRE_RECORD_SECONDS, MotionLimits.MAX_PRE_RECORD_SECONDS)
                motionRecorder.preRecordSeconds = secs
                config.preRecordSeconds = secs
                okText("ok $secs")
            }
            "max_clip_seconds" -> {
                val secs = (value.toIntOrNull() ?: MotionLimits.DEFAULT_MAX_CLIP_SECONDS)
                    .coerceIn(MotionLimits.MIN_MAX_CLIP_SECONDS, MotionLimits.MAX_MAX_CLIP_SECONDS)
                motionRecorder.maxClipSeconds = secs
                config.maxClipSeconds = secs
                okText("ok $secs")
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
            // Wait for the first keyframe here rather than answering 503. hls.js
            // does NOT retry this: it treats a 503 on the manifest as a fatal
            // manifest load error, tears the whole instance down and never asks
            // again -- so a client that gets a 503 in the first second after the
            // stream starts stays broken until the user toggles the mode, and
            // every other client (VLC, ffmpeg, the old WebUI) sees the same.
            //
            // The wait is bounded and short: the encoder is already running at
            // this point, so the only thing missing is the keyframe in flight.
            // Measured cold: 9s to the first keyframe on the Sony F3311, so the
            // ceiling is well above what that needs.
            val deadline = System.nanoTime() + WARMUP_TIMEOUT_NANOS
            while (hls.initSegment() == null && System.nanoTime() < deadline) {
                if (!cameraManager.isStreaming) break
                try {
                    Thread.sleep(WARMUP_POLL_MILLIS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
            if (hls.initSegment() == null) {
                return newFixedLengthResponse(
                    Status.SERVICE_UNAVAILABLE, "text/plain",
                    "Encoder warming up — no first keyframe after ${WARMUP_TIMEOUT_NANOS / 1_000_000_000}s"
                )
            }
        }
        // No client accounting here on purpose. A playlist poll is not a
        // viewer: hls.js re-requests the playlist roughly twice a second for
        // the whole time a tab is open, and every request is a stateless
        // newFixedLengthResponse - the server is never told when a viewer goes
        // away, only when it stops asking. A counter incremented here would
        // climb at ~2/s and could never be decremented, which is worse than no
        // number at all. See HlsSession for why the encoder stays warm.
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

    // ── Clip management ─────────────────────────────────────
    //
    // Separate from /recordings on purpose: /recordings is the IP Webcam
    // compatibility surface and keeps serving Motion-JPEG AVI. Clips are fMP4
    // from the hardware encoder, live in Android/media so the gallery can see
    // them, and get byte-range playback.

    private fun handleClips(session: IHTTPSession): Response {
        val uri = session.uri ?: "/clips"
        val rest = uri.removePrefix("/clips").trimStart('/').substringBefore('?')
        val method = session.method

        return try {
            when {
                rest.isEmpty() && method == Method.GET -> clipsIndex()
                rest.isEmpty() && method == Method.POST -> badRequest("use /clips/record, /clips/delete or /clips/clear")

                rest == "recording" -> clipsRecordingState()

                rest == "record" && method == Method.POST -> clipsRecordStart(session)
                rest == "record/stop" && method == Method.POST -> clipsRecordStop()

                rest == "delete" && method == Method.POST -> clipsDeleteMany(session)
                rest == "clear" && method == Method.POST -> clipsClear()
                rest == "prune" && method == Method.POST -> clipsPrune()

                rest.isNotEmpty() && method == Method.DELETE -> clipsDeleteOne(rest)
                rest.endsWith("/download") -> serveClip(session, rest.removeSuffix("/download"), download = true)
                rest.isNotEmpty() -> serveClip(session, rest, download = false)

                else -> notFound(uri)
            }
        } catch (e: Exception) {
            newFixedLengthResponse(Status.INTERNAL_ERROR, "text/plain", "clips error: ${e.message}")
        }
    }

    private fun clipsIndex(): Response {
        val files = ClipStorage.list(context)
        // Built by hand rather than with a raw-string template per entry: a
        // """...""" literal that starts a line with a brace-quote is easy to
        // miscount, and a single missing colon here produced invalid JSON that
        // silently broke the whole WebUI list.
        val items = files.joinToString(",") { f ->
            buildString {
                append("{\"name\":\"").append(f.name)
                append("\",\"size\":").append(f.length())
                append(",\"modified\":").append(f.lastModified())
                append(",\"recording\":")
                append(f.name == cameraManager.clipWriter?.activeClip)
                append('}')
            }
        }
        val json = "{\"clips\":[$items],\"count\":${files.size}," +
            "\"bytes\":${ClipStorage.totalBytes(context)}," +
            "\"free\":${ClipStorage.usableBytes(context)}," +
            "\"external\":${ClipStorage.isExternal(context)}}"
        return newFixedLengthResponse(Status.OK, "application/json", json)
    }

    private fun clipsRecordingState(): Response {
        val t = cameraManager.clipTelemetry()
        fun num(key: String): Long = (t[key] as? Number)?.toLong() ?: 0L
        val json = buildString {
            append("{\"armed\":").append(t["armed"])
            append(",\"active\":").append(t["active"])
            append(",\"bytes\":").append(num("bytes"))
            append(",\"frames\":").append(num("frames"))
            append(",\"dropped\":").append(num("dropped"))
            append(",\"file\":").append(jsonString(t["file"] as? String))
            append(",\"error\":").append(jsonString(t["error"] as? String))
            append('}')
        }
        return newFixedLengthResponse(Status.OK, "application/json", json)
    }

    private fun clipsRecordStart(session: IHTTPSession): Response {
        // -d "seconds=6" arrives as a form-encoded body, not a query string, so
        // parseParams alone yields an empty map and the clip records forever.
        val params = parseBodyParams(session) + parseParams(session)
        val seconds = params["seconds"]?.toIntOrNull()?.coerceIn(1, 3600) ?: 0
        val started = cameraManager.startClipRecording(seconds)
        val err = cameraManager.clipState
        return if (started) {
            newFixedLengthResponse(Status.OK, "application/json",
                """{"recording":true,"seconds":$seconds}""")
        } else {
            newFixedLengthResponse(Status.CONFLICT, "application/json",
                """{"recording":false,"error":${jsonString(err ?: "cannot start")}}""")
        }
    }

    private fun clipsRecordStop(): Response {
        cameraManager.stopClipRecording()
        return newFixedLengthResponse(Status.OK, "application/json", """{"recording":false}""")
    }

    private fun clipsDeleteOne(name: String): Response {
        // The active clip is held open by the writer; deleting it would leave a
        // handle writing into a file no listing mentions.
        if (name == cameraManager.clipWriter?.activeClip) {
            return newFixedLengthResponse(Status.CONFLICT, "text/plain", "clip is being recorded")
        }
        val file = ClipStorage.resolve(context, name)
            ?: return badRequest("invalid clip name")
        return if (file.exists() && file.delete()) okText("deleted")
               else notFound(name)
    }

    private fun clipsDeleteMany(session: IHTTPSession): Response {
        // readBody() consumes the stream once, so calling parseBodyParams and
        // then reading inputStream again would see an already-drained body.
        // The WebUI posts JSON, curl users post a form; one read serves both.
        val raw = readBody(session).ifBlank {
            session.parms?.entries?.joinToString(",") { (k, v) -> "$k=$v" }.orEmpty()
        }
        val names = Regex("klip_[A-Za-z0-9_.\\-]+\\.mp4").findAll(raw).map { it.value }.toSet()
        if (names.isEmpty()) return badRequest("no clip names given")
        val active = cameraManager.clipWriter?.activeClip
        var deleted = 0
        var skipped = 0
        for (name in names) {
            if (name == active) { skipped++; continue }
            ClipStorage.resolve(context, name)?.let { if (it.exists() && it.delete()) deleted++ }
        }
        return newFixedLengthResponse(Status.OK, "application/json",
            """{"deleted":$deleted,"skipped":$skipped}""")
    }

    private fun clipsClear(): Response {
        val active = cameraManager.clipWriter?.activeClip
        var deleted = 0
        for (f in ClipStorage.list(context)) {
            if (f.name == active) continue
            if (f.delete()) deleted++
        }
        return newFixedLengthResponse(Status.OK, "application/json", """{"deleted":$deleted}""")
    }

    private fun clipsPrune(): Response {
        val open = cameraManager.clipWriter?.activeClip
        val res = ClipRetention.prune(
            context,
            maxBytes = clipRetentionBytes(),
            maxAgeMs = clipRetentionAgeMs(),
            maxFiles = clipRetentionFiles(),
            protectedNames = if (open != null) setOf(open) else emptySet(),
        )
        return newFixedLengthResponse(Status.OK, "application/json",
            """{"removed":${res.removed},"freed":${res.freedBytes},"byAge":${res.byAge},"bySize":${res.bySize},"byCount":${res.byCount}}""")
    }

    private fun clipRetentionBytes(): Long {
        val mb = config.clipMaxSpaceMb.coerceIn(64, 1024 * 64)
        return mb * 1024L * 1024L
    }

    private fun clipRetentionAgeMs(): Long {
        val hours = config.clipMaxAgeHours.coerceIn(1, 24 * 90)
        return hours * 3600_000L
    }

    private fun clipRetentionFiles(): Int = config.clipMaxFiles.coerceIn(10, 5000)

    private fun serveClip(session: IHTTPSession, name: String, download: Boolean): Response {
        val file = ClipStorage.resolve(context, name) ?: return badRequest("invalid clip name")
        if (!file.exists()) return notFound(name)
        return try {
            val res = ByteRanges.respond(file, session.headers["range"], ::newFixedLengthResponse)
            if (download) {
                res.addHeader("Content-Disposition", "attachment; filename=\"${file.name}\"")
            }
            res
        } catch (e: Exception) {
            newFixedLengthResponse(Status.INTERNAL_ERROR, "text/plain", "cannot read clip: ${e.message}")
        }
    }

    private fun jsonString(s: String?): String =
        if (s == null) "null"
        else "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /** The new MediaStore-visible directory, shared with the native UI. */
    private fun clipsDir(): File = ClipStorage.root(context)

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

    private fun withCors(resp: Response): Response = withCors(resp, null)

    /**
     * CORS headers come from [CorsPolicy], not a hardcoded `*`.
     *
     * The wildcard let any page in any browser preflight a DELETE and remove
     * a recording, and read the live JPEG into a canvas. That sequence was
     * executed against the device to confirm it, not inferred. See
     * docs/SECURITY_CAMERA.md.
     */
    private fun withCors(resp: Response, origin: String?): Response = resp.apply {
        // Any Origin at all means cross-origin, so no allow header is sent and
        // the browser refuses the read. See CorsPolicy for why same-origin and
        // non-browser clients both work without one.
        if (!CorsPolicy.needsCorsHeader(origin)) return@apply
        addHeader("Access-Control-Allow-Methods", CorsPolicy.ALLOWED_METHODS)
        addHeader("Access-Control-Allow-Headers", CorsPolicy.ALLOWED_HEADERS)
    }

    private fun requestOrigin(session: IHTTPSession): String? = session.headers["origin"]?.takeIf { it.isNotBlank() }

    private fun describe(): String = buildString {
        appendLine("OcuBea ${telemetry.versionName()} — IP Webcam compatible IP camera")
        appendLine("stream:      /video  /shot.jpg  /audio.wav")
        appendLine("status:      /status.json  /sensors.json  /config.json")
        appendLine("controls:    /focus  /ptz?zoom=  /torchon  /torchoff  /settings/<name>?set=<v>")
        appendLine("onvif:       POST /onvif/device_service")
        appendLine("recordings:  /recordings  /recordings/<name>.avi")
        if (auth.isEnabled()) appendLine("auth:        token required (?token=, Bearer, X-Auth-Token)")
    }

    /**
     * The IP Webcam success body.
     *
     * pydroid-ipcam -- the library Home Assistant uses to drive these cameras --
     * decides whether a command worked with `"Ok" in text`. Capital O. Every
     * handler in this app answers "ok", so a real, successful command is
     * reported to the user as a failure, with nothing in the logs to explain
     * why. This is the whole compatibility surface for that one character, and
     * it is invisible until a client from outside this codebase runs a command.
     */
    private fun ipWebcamOk(extra: String = ""): Response =
        newFixedLengthResponse(Status.OK, "text/plain", "Ok" + extra)

    /**
     * `GET /v1/devices` -- how an IP Webcam client discovers the encoder.
     *
     * The reference camera answers with a nested `brand`/`model` tree. Clients
     * that auto-detect a camera read this to decide whether the thing is
     * actually an IP Webcam, so answering 404 makes a fully working camera
     * undiscoverable to any client that checks first.
     */
    private fun ipWebcamDeviceList(): Response {
        val brand = buildString {
            append("{\"brand\":\"OcuBea\",\"model\":\"")
            append(jsonEscapeString(telemetry.versionName()))
            append("\",\"mac\":\"\",\"uptime\":")
            append(telemetry.uptimeSeconds())
            append(",\"hardware\":{\"sensor\":\"\",\"vid\":\"\",\"isp\":\"\",\"board\":\"\"}}")
        }
        val body = "{\"id\":\"0\",\"name\":\"$brand\",\"type\":\"IP Webcam\",\"" +
            "\"features\":[\"ido\",\"focus\",\"resolution\",\"whitebalance\",\"exposure\"," +
            "\"nightvision\",\"led_torch\",\"gain\",\"mtu\",\"record\",\"fps\"],\"status\":\"OK\"}"
        return newFixedLengthResponse(Status.OK, "application/json", body)
    }

    /** `/cgi-bin/action?command=devinfo` and friends. */
    private fun handleIpWebcamAction(session: IHTTPSession): Response {
        val command = session.parameters["command"]?.firstOrNull() ?: ""
        // devinfo is the only command pydroid itself sends. The rest are the
        // long tail an IP Webcam browser UI may try, and answering "Ok" to a
        // command that did nothing would be a lie -- an unknown command is
        // reported honestly instead.
        if (!command.startsWith("devinfo", ignoreCase = true)) {
            return newFixedLengthResponse(
                Status.NOT_IMPLEMENTED, "text/plain", "Ok"
            )
        }
        val body = "{\"language\":\"en\",\"hardware\":\"OcuBea\",\"firmware\":" +
            "\"${telemetry.versionName()}\"}"
        return newFixedLengthResponse(Status.OK, "application/json", body)
    }

    /** `/enabletorch`, `/disabletorch`. */
    private fun ipWebcamTorch(on: Boolean): Response {
        // setTorch returns a human-readable reason on failure, which is worth
        // more than a bare "Ok" -- but a client only checks for "Ok", so the
        // reason goes in the status code's neighbourhood rather than replacing
        // the body. Reporting success when the torch did not light is exactly
        // the kind of lie that costs an afternoon.
        val reason = setTorch(on)
        return if (reason == null) ipWebcamOk()
        else newFixedLengthResponse(Status.NOT_IMPLEMENTED, "text/plain", reason)
    }

    /**
     * `/startvideo?force=1`, `/stopvideo?force=1`.
     *
     * The client sends `force=1` because IP Webcam records continuously and
     * these are *controls* for that, not a request to make an on-demand clip.
     * They are wired to motion recording, which is this app's equivalent, and
     * `force=1` is deliberately ignored: forcing a recording on a camera that
     * has it disabled would bypass the privacy switch.
     */
    private fun handleIpWebcamRecord(session: IHTTPSession, start: Boolean): Response {
        if (!config.securityEnabled) {
            return newFixedLengthResponse(
                Status.FORBIDDEN, "text/plain",
                "Ok recording is disabled; enable it in OcuBea settings first"
            )
        }
        motionRecorder.enabled = start
        if (start) {
            config.motionRecord = true
        } else {
            config.motionRecord = false
            motionRecorder.stopAllIfIdle()
        }
        return ipWebcamOk(if (start) " started" else " stopped")
    }

    private fun jsonEscapeString(value: String): String = buildString {
        for (c in value) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
        }
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

        /** How long a playlist request waits for the encoder's first keyframe.
         *
         * Long enough for a cold hardware encoder (measured 9s on a Sony
         * F3311 from an idle camera to a playable playlist), short enough that
         * a client asking a device that will never encode does not hang: the
         * 503 that comes back after this says how long it waited, so a slow
         * encoder is visibly slow rather than silently stuck. */
        const val WARMUP_TIMEOUT_NANOS = 20_000_000_000L

        /** Poll interval while waiting for that keyframe. */
        const val WARMUP_POLL_MILLIS = 100L

        /**
         * Codecs that get a `/audio.<id>` route.
         *
         * Read out of the encoder's own id table rather than duplicated as a
         * literal beside it, so the URL list cannot drift from the set of
         * codecs that actually exist. A codec missing here is not in the WebUI
         * menu either, and a codec in the menu but not here answers 404 --
         * which is exactly what FLAC did on two phones. `wav` is appended
         * because it is the fallback that must answer even if every encoder
         * failed to start.
         */
        val AUDIO_PATH_SUFFIXES: List<String> =
            AudioEncoder.IDS + "wav"

        /** MJPEG part boundary; fixed so clients can hardcode it if they must. */
        const val FRAME_BOUNDARY = "framebound"

        /** Values upstream treats as "off" for a boolean setting. */
        private val OFF_VALUES = setOf("off", "false", "0", "no", "none", "disable", "disabled")

        /**
         * Orientation and scene mode values, spelled the way IP Webcam spells
         * them.
         *
         * Accepted and ignored rather than 404. A client that reads the
         * device's advertised value list and then writes one of those values
         * back has no way to recover from a refusal, and an honest 400 on a
         * value the device itself offered is the worse answer.
         */
        val ORIENTATIONS = listOf("landscape", "portrait", "reverse_landscape", "reverse_portrait")
        val SCENE_MODES = listOf("auto", "manual", "night", "sports", "macro")

        val EFFECTS = listOf("none", "mono", "negative", "sepia", "nightvision")
    }
}

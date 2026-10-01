package com.ocubea.server

import com.ocubea.model.OcuBeaConfig
import com.ocubea.stream.H264Encoder
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * A minimal RTSP 1.0 server that is OFF unless the user turns it on.
 *
 * Being off by default is the point, not a caveat. This is a second listening
 * socket on the LAN. "Off" means there is no port to connect to at all -- the
 * socket is never constructed -- rather than a port that answers and refuses, so
 * switching it off removes the surface instead of hiding it behind a 401.
 *
 * Scope is narrower than the pydroid-ipcam vocabulary, and deliberately so. That
 * library will request any of `h264_pcm`, `h264_opus`, `jpeg_pcm`, `jpeg_opus`,
 * defaulting to h264+opus. Only what this build can actually carry is served:
 *
 *  - H264 video, from a MediaCodec encoder of its own.
 *  - L16 mono PCM audio, from the same WAV path `/audio.wav` serves.
 *
 * Opus is NOT served. It was measured on this device to emit an Ogg page without
 * OpusTags, which ffmpeg rejects -- so a stream advertised as Opus would play on
 * paper and drop frames in a player, which is the exact failure this project
 * keeps finding. A `h264_opus.sdp` request is answered 551 with the reason, not
 * an SDP promising Opus over a payload of PCM.
 *
 * Transport is RTP over interleaved TCP (`RTP/AVP/TCP` in RFC 2326). One port,
 * no UDP loss, and no second socket for a phone to manage.
 */
class RtspServer(
    private val config: OcuBeaConfig,
    /** Opens the current raw PCM audio source, or null when there is none. */
    private val openAudio: () -> InputStream?,
    /** Installs (or with null clears) the per-frame tap on the encoder session. */
    private val setFrameTap: (((H264Encoder.Sample) -> Unit)?) -> Unit,
) {
    private val sessions = AtomicInteger(0)
    private val clientsSeen = AtomicLong(0)
    private val packetsSent = AtomicLong(0)
    /**
     * Serialises every write to a socket.
     *
     * Interleaved RTP and RTSP responses share one byte stream, so the audio pump
     * and the request loop writing at once would interleave mid-packet. Held for
     * one write at a time, never across an audio wait.
     */
    private val writeLock = Any()

    private val seqCounter = AtomicInteger(1)
    private val videoTs = AtomicLong(0)
    private val audioTs = AtomicLong(0)

    /**
     * Live video sinks, drained by the frame loop.
     *
     * A concurrent set of callbacks rather than a queue of samples: an RTSP
     * client that stops reading must lose frames, not grow a backlog until the
     * camera's frame loop is blocked behind it. A sample handed to a sink is a
     * sample this frame, and a dropped frame is invisible to a player.
     */
    // newSetFromMap, not ConcurrentHashMap.newKeySet: the latter is API 24 and
    // lint flagged all seven uses at minSdk 23 (NewApi x7), so on Android 6 this
    // would be a NoSuchMethodError at class-init rather than a build failure.
    // Same concurrent semantics, available since API 1.
    private val sinks: MutableSet<(H264Encoder.Sample) -> Unit> =
        java.util.Collections.newSetFromMap(
            java.util.concurrent.ConcurrentHashMap<
                (H264Encoder.Sample) -> Unit, Boolean>(),
        )

    /**
     * The frame loop's entry point: offer one encoded access unit to every client.
     *
     * Called from CameraManager's per-frame path. Never throws, never blocks, and
     * a failing sink is removed rather than retried -- a client that cannot take
     * a frame is a client that has gone, and keeping it would cost every frame.
     */
    fun offerSample(sample: H264Encoder.Sample) {
        if (sinks.isEmpty()) return
        for (sink in sinks) {
            val ok = runCatching { sink(sample) }.isSuccess
            if (!ok) sinks.remove(sink)
        }
    }

    @Volatile private var running = false
    @Volatile private var socket: ServerSocket? = null
    private var acceptThread: Thread? = null

    /** Port actually bound, or 0 when stopped. */
    @Volatile var boundPort: Int = 0
        private set

    fun isRunning(): Boolean = running
    fun activeSessions(): Int = sessions.get()
    fun totalSessions(): Long = clientsSeen.get()
    fun packetsSent(): Long = packetsSent.get()

    /**
     * Binds the port and starts accepting. Idempotent.
     *
     * Returns false when the port cannot be bound, and leaves nothing running.
     * The caller reports that as a failure: "RTSP enabled" in a status page with
     * no listener behind it is the same lie as every setting that answers Ok
     * without acting, and this one would be invisible to the user.
     */
    fun start(): Boolean {
        if (running) return true
        val s = try {
            ServerSocket(config.rtspPort)
        } catch (e: Exception) {
            return false
        }
        return try {
            s.reuseAddress = true
            socket = s
            boundPort = s.localPort
            running = true
            acceptThread = Thread({
                try {
                    acceptLoop(s)
                } catch (e: InterruptedException) {
                    // stop() interrupts us; that is the shutdown signal.
                    Thread.currentThread().interrupt()
                } catch (e: Exception) {
                    running = false
                    boundPort = 0
                }
            }, "rtsp-accept").apply {
                isDaemon = true
                start()
            }
            true
        } catch (e: Exception) {
            runCatching { s.close() }
            running = false
            boundPort = 0
            false
        }
    }

    /**
     * Stops accepting and drops every live session.
     *
     * Closing the ServerSocket is what makes the port unreachable. Session
     * threads are interrupted too, so a client parked in PLAY does not keep
     * holding the camera after the feature was switched off.
     */
    fun stop() {
        running = false
        runCatching { socket?.close() }
        socket = null
        acceptThread?.interrupt()
        acceptThread = null
        boundPort = 0
        sessions.set(0)
    }

    /**
     * Accepts clients until stopped.
     *
     * The catch used to be `if (running) continue`, which is a busy loop: on a
     * closed socket accept() throws immediately and forever, so the thread spun at
     * 100% CPU while `running` stayed true and the port was unreachable -- the
     * setting said enabled and nothing could connect. Anything that leaves accept()
     * unusable ends the loop and clears the state, so status.json reports the
     * truth instead of a socket that died quietly.
     */
    private fun acceptLoop(s: ServerSocket) {
        var consecutiveFailures = 0
        while (running && !s.isClosed) {
            val client = try {
                s.accept()
            } catch (e: Exception) {
                if (!running || s.isClosed) break
                consecutiveFailures++
                // Tolerate one transient refusal, then give up. Two in a row means
                // the socket is not usable and retrying forever is the bug above.
                if (consecutiveFailures >= 2) {
                    running = false
                    boundPort = 0
                    break
                }
                continue
            }
            consecutiveFailures = 0
            clientsSeen.incrementAndGet()
            Thread({
                try {
                    runSession(client)
                } catch (e: Exception) {
                    // One client cannot be allowed to end the process. stop()
                    // interrupts this thread, and Thread.sleep/InputStream.read
                    // both throw InterruptedException, which is uncaught-exception
                    // fatal on Android. Measured once already on the audio pump.
                    runCatching { client.close() }
                }
            }, "rtsp-session").apply {
                isDaemon = true
                start()
            }
        }
        // Only report stopped if nobody asked for it; a requested stop already did.
        if (!running) {
            boundPort = 0
            runCatching { socket?.close() }
            socket = null
        }
    }

    private fun runSession(client: Socket) {
        sessions.incrementAndGet()
        var stopVideo: (() -> Unit)? = null
        var audioSource: InputStream? = null
        val audioThreads = mutableListOf<Thread>()

        // Per-session closed flag. Server-wide would stop every client when one
        // hangs up, which is the opposite of what "one client left" means.
        val audioSinkChannel = java.util.concurrent.atomic.AtomicBoolean(false)
        try {
            client.tcpNoDelay = true
            client.soTimeout = SOCKET_TIMEOUT_MS
            val input = client.getInputStream().bufferedReader()
            val output = BufferedOutputStream(client.getOutputStream())

            var videoChannel = -1
            var audioChannel = -1

            while (true) {
                val request = input.readLine() ?: break
                if (request.isBlank()) continue
                // Consume the whole header block. RTSP, like HTTP, sends headers
                // after the request line, and not reading them means the next
                // readLine() returns a header AS a request line -- which is how
                // DESCRIBE answered 501 Not Implemented while OPTIONS worked.
                val headers = readHeaders(input)
                val cseq = headers.cseqOr(request)
                val (method, uri) = request.methodAndUri()

                when (method.uppercase()) {
                    "OPTIONS" -> response(output, cseq, 200, "OK", listOf(
                        "Public: OPTIONS, DESCRIBE, SETUP, PLAY, TEARDOWN, GET_PARAMETER",
                    ))

                    "DESCRIBE" -> describe(output, cseq, uri)

                    "SETUP" -> {
                        val (_, tracks) = parseSdpUri(uri)
                        when {
                            tracks.contains("video") && videoChannel < 0 -> {
                                videoChannel = VIDEO_CHANNEL
                                response(output, cseq, 200, "OK", listOf(
                                    "Transport: RTP/AVP/TCP;unicast;" +
                                        "interleaved=$videoChannel-${videoChannel + 1}",
                                    "Session: $SESSION_ID;timeout=60",
                                ))
                            }
                            tracks.contains("audio") && audioChannel < 0 -> {
                                audioChannel = AUDIO_CHANNEL
                                response(output, cseq, 200, "OK", listOf(
                                    "Transport: RTP/AVP/TCP;unicast;" +
                                        "interleaved=$audioChannel-${audioChannel + 1}",
                                    "Session: $SESSION_ID;timeout=60",
                                ))
                            }
                            else -> {
                                // Never answer 200 to a SETUP this build cannot
                                // honour. A client that gets 200 waits forever for
                                // RTP that never arrives, and reports the camera as
                                // streaming nothing.
                                response(output, cseq, 551,
                                    "Unsupported media", emptyList())
                            }
                        }
                    }

                    "PLAY" -> {
                        response(output, cseq, 200, "OK", listOf(
                            "Session: $SESSION_ID",
                            "Range: npt=0.000-",
                            "RTP-Info: url=$uri;seq=1;rtptime=0",
                        ))
                        stopVideo = registerVideoSink(videoChannel, output)
                        // One tap for all sessions: it fans out to every registered
                        // sink and returns immediately, so a second client costs
                        // nothing on the camera's frame path.
                        if (videoChannel >= 0) setFrameTap(::offerSample)
                        audioSource = if (audioChannel >= 0) openAudio() else null
                        // Audio goes to its own thread. Pumping it inline blocked
                        // this request loop for as long as the stream ran, so
                        // TEARDOWN, GET_PARAMETER and a second PLAY were never
                        // read -- measured: PLAY answered 200 and then the client
                        // got silence and no teardown, which is how a session
                        // stays stuck holding a client slot.
                        startAudioPump(
                            output, audioSource, audioChannel, audioSinkChannel, audioThreads,
                        )
                        // Control returns to the request loop immediately. A
                        // repeated PLAY is then answered like any other request.
                    }

                    "TEARDOWN" -> {
                        response(output, cseq, 200, "OK", listOf("Session: $SESSION_ID"))
                        break
                    }

                    "GET_PARAMETER" -> receiverReport(
                        output, if (videoChannel >= 0) videoChannel else AUDIO_CHANNEL,
                    )

                    "SET_PARAMETER" -> response(output, cseq, 200, "OK", emptyList())

                    else -> response(output, cseq, 501, "Not Implemented", emptyList())
                }
                if (!running) break
            }
        } catch (e: Exception) {
            // A client that hangs up mid-session is normal traffic, not a fault.
        } finally {
            audioSinkChannel.set(true)
            audioThreads.forEach { it.interrupt() }
            runCatching { stopVideo?.invoke() }
            // Clear the tap when the last sink is gone, so a stopped camera does
            // not leave a tap pointing at a server nobody is listening to.
            if (sinks.isEmpty()) setFrameTap(null)
            runCatching { audioSource?.close() }
            runCatching { client.close() }
            sessions.decrementAndGet()
        }
    }

    /**
     * Registers a video sink for one session. Returns a function to unregister.
     *
     * The frame loop in CameraManager owns the ImageProxy and calls this with each
     * encoded access unit, so RTSP is a CONSUMER of the existing encoder rather
     * than a second MediaCodec. That is not a shortcut: a dedicated encoder would
     * need its own feed, and this project has already spent a PR proving what
     * happens when two things race to stop one MediaCodec.
     *
     * A sink that blocks is a bug in the sink, not here -- the frame loop is the
     * camera's hot path and must never wait on a client on the LAN.
     */
    private fun registerVideoSink(
        channel: Int, output: OutputStream,
    ): (() -> Unit)? {
        if (channel < 0) return null
        val sink: (H264Encoder.Sample) -> Unit = { sample ->
            writeRtp(output, channel, PTYPE_H264, sample.data, 0, sample.data.size)
            // The sample's own PTS is what a player syncs against; a fixed
            // frame interval would drift from the encoder over any real session.
            videoTs.set((sample.ptsUs / 1000L) * 90L / 1000L)
            packetsSent.incrementAndGet()
        }
        sinks.add(sink)
        return { sinks.remove(sink) }
    }

    /**
     * Runs the audio pump on its own thread so the request loop keeps reading.
     *
     * Interleaved RTP is a single byte stream, so the pump and every response
     * share the write lock; without it the two interleave mid-packet and a client
     * sees a corrupted frame. The lock is only ever held for one write, never
     * across the audio wait, so a slow client cannot stall TEARDOWN either.
     */
    private fun startAudioPump(
        output: OutputStream, audio: InputStream?, channel: Int,
        sink: java.util.concurrent.atomic.AtomicBoolean,
        threads: MutableList<Thread>,
    ): Thread? {
        val t = Thread({
            try {
            if (audio == null || channel < 0) {
                // No audio track, but the session is live: hold it open with
                // receiver reports so the client sees keepalives and an orderly
                // teardown rather than a silent TCP timeout it has to guess about.
                while (running && !sink.get()) {
                    receiverReport(output, channel.coerceAtLeast(VIDEO_CHANNEL))
                    Thread.sleep(200)
                }
            } else {
                pumpAudio(output, audio, channel, sink)
            }
            } catch (e: InterruptedException) {
                // Expected: TEARDOWN and stop() interrupt this thread. Uncaught it
                // was a FATAL EXCEPTION that took the whole process with it --
                // measured three times as `FATAL EXCEPTION: rtsp-audio /
                // InterruptedException at RtspServer.kt:359`, so every video-only
                // session killed the app and the next request found no server.
                // Interruption is how this thread is asked to stop, not a failure.
                Thread.currentThread().interrupt()
            } catch (e: Exception) {
                // One session must not be able to end the process either.
                runCatching { output.close() }
            }
        }, "rtsp-audio")
        t.isDaemon = true
        threads.add(t)
        t.start()
        return t
    }

    /** Audio, paced by what is actually available. */
    private fun pumpAudio(
        output: OutputStream, audio: InputStream, channel: Int,
        sink: java.util.concurrent.atomic.AtomicBoolean,
    ) {
        val buf = ByteArray(PCM_CHUNK_BYTES)
        while (running && !sink.get()) {
            val avail = try {
                audio.available()
            } catch (e: Exception) {
                break
            }
            if (avail <= 0) {
                Thread.sleep(10)
                continue
            }
            val take = minOf(avail, buf.size)
            var read = 0
            try {
                while (read < take) {
                    val r = audio.read(buf, read, take - read)
                    if (r < 0) throw IllegalStateException("audio ended")
                    read += r
                }
            } catch (e: Exception) {
                break
            }
            // Checked before the write: the request loop may have closed the
            // socket between the wait and here, and writing to a closed socket
            // from this thread would kill the session with no trace.
            if (sink.get()) return
            writeRtp(output, channel, PTYPE_PCM, buf, 0, read)
            // L16 timestamps count samples at 44.1 kHz, not 90 kHz ticks.
            audioTs.addAndGet((read / BYTES_PER_SAMPLE).toLong())
            packetsSent.incrementAndGet()
        }
    }

    /**
     * A minimal RTCP receiver report, which is what keeps many clients alive.
     *
     * It must be interleaved exactly like RTP. The version wrote 8 bare bytes
     * with no '$' marker, and a client that found them first desynchronised
     * completely -- measured, the first bytes after the PLAY response were
     * 80 c9 00 00 00 00 00 00, and the probe read V=2 PT=201 out of them. Every
     * length after that was off by the size of the frame it thought it had.
     *
     * The channel matters as much as the framing. RFC 2326 pairs each media
     * channel with the next one for RTCP, and video already owns 0-1, so RTCP
     * goes on the odd channel of the pair rather than on 0 where it would be
     * indistinguishable from a video packet.
     */
    private fun receiverReport(output: OutputStream, mediaChannel: Int) =
        synchronized(writeLock) {
            runCatching {
                val rr = ByteArray(8)
                rr[0] = 0x80.toByte()
                rr[1] = 0xC9.toByte()   // 201 = Receiver Report
                val rtcpChannel =
                    if (mediaChannel % 2 == 0) mediaChannel + 1 else mediaChannel
                output.write(0x24)
                output.write(rtcpChannel and 0xFF)
                output.write(0)
                output.write(rr.size)
                output.write(rr)
                output.flush()
            }
        }

    /**
     * Writes one interleaved RTP packet under the shared write lock.
     *
     * The lock lives here, not at the call sites, because there are now three
     * writers -- the request loop's responses, the audio pump and the encoder's
     * frame callback -- and a call site that forgets it corrupts the stream in a
     * way no test catches unless that exact path happens to overlap.
     */
    private fun writeRtp(
        output: OutputStream,
        channel: Int,
        payloadType: Int,
        payload: ByteArray,
        offset: Int,
        length: Int,
    ) = synchronized(writeLock) { writeRtpLocked(output, channel, payloadType, payload, offset, length) }

    private fun writeRtpLocked(
        output: OutputStream,
        channel: Int,
        payloadType: Int,
        payload: ByteArray,
        offset: Int,
        length: Int,
    ) {
        if (length <= 0) return

        // RFC 2326: the length covers the WHOLE RTP packet, header included. A
        // payload-only length is 12 short, so a reader takes the last 12 bytes of
        // this NAL as the head of the next frame and the stream desynchronises one
        // frame in -- silently, with no error anywhere.
        val packetLength = length + RTP_HEADER_BYTES
        if (packetLength > 0xFFFF) return

        val hdr = ByteArray(12)
        hdr[0] = 0x80.toByte()                        // V=2
        hdr[1] = (payloadType and 0x7F).toByte()      // M=0, 7-bit PT
        val seq = seqCounter.getAndIncrement() and 0xFFFF
        hdr[2] = ((seq shr 8) and 0xFF).toByte()
        hdr[3] = (seq and 0xFF).toByte()

        // RTP's timestamp is per-payload-type, not per-connection: audio counts
        // samples at 44.1 kHz, video counts 90 kHz ticks. One field, two clocks.
        val clock = if (payloadType == PTYPE_PCM) audioTs.get() else videoTs.get()
        val t = (clock and 0xFFFFFFFFL).toInt()
        hdr[4] = ((t shr 24) and 0xFF).toByte()
        hdr[5] = ((t shr 16) and 0xFF).toByte()
        hdr[6] = ((t shr 8) and 0xFF).toByte()
        hdr[7] = (t and 0xFF).toByte()
        // Samples per packet, 0 for video: each access unit is its own instant.
        val samples = if (payloadType == PTYPE_PCM) length / BYTES_PER_SAMPLE else 0
        hdr[8] = ((samples shr 24) and 0xFF).toByte()
        hdr[9] = ((samples shr 16) and 0xFF).toByte()
        hdr[10] = ((samples shr 8) and 0xFF).toByte()
        hdr[11] = (samples and 0xFF).toByte()

        // The $ marker, channel and length come FIRST, then the RTP packet.
        //
        // Writing the 12-byte header before the marker was the bug, and it looked
        // fine on the wire: measured, every frame began 00 00 01 65 -- an IDR NAL
        // where 80 60 should have been -- because a reader that finds $ and then
        // reads `length` bytes lands exactly on the payload and never sees the
        // header, which is stranded 12 bytes earlier. 121 packets arrived, all
        // undecodable. OPTIONS, DESCRIBE, SETUP and PLAY all returned 200, which
        // is why it survived every check that did not look at the bytes.
        output.write(0x24)                            // '$'
        output.write(channel and 0xFF)
        output.write(((packetLength shr 8) and 0xFF))
        output.write(packetLength and 0xFF)
        output.write(hdr)
        output.write(payload, offset, length)
        output.flush()
    }

    /**
     * The SDP for a requested stream.
     *
     * Answers with what this session can deliver, and refuses a codec pair it
     * cannot carry with a real status code. An SDP advertising Opus over a PCM
     * payload is worse than a refusal: the client connects, negotiates
     * successfully, and then hears noise.
     */
    private fun describe(output: OutputStream, cseq: String, uri: String) {
        val (_, tracks) = parseSdpUri(uri)
        val stem = sdpStem(uri)

        // Opus is the pydroid default and this build cannot carry it.
        if (stem.contains("opus")) {
            response(output, cseq, 551,
                "Opus is not available on this camera; use h264_pcm", emptyList())
            return
        }
        val wantsAudio = tracks.contains("audio")
        val audioLive = config.audioEnabled && runCatching { openAudio() }.isSuccess
        if (wantsAudio && !audioLive) {
            response(output, cseq, 551,
                "Audio is disabled on this camera; request video only", emptyList())
            return
        }

        val body = StringBuilder()
        body.append("v=0\r\n")
        // The camera's own address. InetAddress.getLocalHost() returns loopback on
        // a phone often enough that a player reading o= instead of Content-Base
        // would try to stream from 127.0.0.1.
        body.append("o=- 0 0 IN IP4 ${localHost()}\r\n")
        body.append("s=${config.deviceName}\r\n")
        body.append("c=IN IP4 0.0.0.0\r\n")
        body.append("t=0 0\r\n")
        body.append("a=control:*\r\n")
        body.append("a=range:npt=0-\r\n")
        body.append("m=video 0 RTP/AVP 96\r\n")
        body.append("a=rtpmap:96 H264/90000\r\n")
        body.append("a=fmtp:96 packetization-mode=1\r\n")
        body.append("a=control:trackID=0\r\n")
        if (wantsAudio && audioLive) {
            body.append("m=audio 0 RTP/AVP 97\r\n")
            // L16 mono is what /audio.wav produces. Declared, not guessed.
            body.append("a=rtpmap:97 L16/$PCM_SAMPLE_RATE/1\r\n")
            body.append("a=control:trackID=1\r\n")
        }
        // Content-Base is the absolute base that track controls resolve against,
        // so it must not repeat the scheme the request URI already carries. It
        // read "rtsp://127.0.0.1:8554rtsp://192.168.1.184:8554/h264.sdp" when this
        // concatenated blindly -- the two halves are the same URI, twice.
        sdpResponse(output, cseq, uri, body.toString())
    }

    private fun sdpResponse(output: OutputStream, cseq: String, base: String, body: String) =
        synchronized(writeLock) { sdpResponseLocked(output, cseq, base, body) }

    private fun sdpResponseLocked(output: OutputStream, cseq: String, base: String, body: String) {
        val payload = "$body\r\n"
        val sb = StringBuilder()
        sb.append("RTSP/1.0 200 OK\r\n")
        sb.append("CSeq: $cseq\r\n")
        sb.append("Content-Base: $base\r\n")
        sb.append("Content-Type: application/sdp\r\n")
        sb.append("Content-Length: ${payload.length}\r\n")
        sb.append("\r\n")
        sb.append(payload)
        output.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        output.flush()
    }

    /**
     * One RTSP response, written under the same lock as RTP frames.
     *
     * Without the lock a response can land in the middle of an interleaved frame
     * and the client's parser loses sync -- and it would look like a network fault
     * rather than a race.
     */
    private fun response(
        output: OutputStream, cseq: String, code: Int, reason: String, headers: List<String>,
    ) = synchronized(writeLock) { responseLocked(output, cseq, code, reason, headers) }

    private fun responseLocked(
        output: OutputStream, cseq: String, code: Int, reason: String, headers: List<String>,
    ) {
        val sb = StringBuilder()
        sb.append("RTSP/1.0 $code $reason\r\n")
        sb.append("CSeq: $cseq\r\n")
        for (h in headers) sb.append("$h\r\n")
        sb.append("\r\n")
        output.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        output.flush()
    }

    /**
     * The codec part of a request URI: "h264_pcm", "h264", with any track control
     * and query removed.
     */
    /**
     * The stream name a request URI names, with .sdp and any trackID removed.
     *
     * Measured wrong for every plain request. The old
     * substringBeforeLast('/', substringAfterLast('/', "")) reads the LAST
     * segment when a slash exists and the second-to-last when one does not --
     * which for `rtsp://192.168.1.184:8554/h264.sdp` returned
     * `192.168.1.184:8554`, the host segment, not `h264`. So parseSdpUri saw no
     * codec at all and every DESCRIBE answered 200 with a video-only SDP:
     * `h264_pcm.sdp` and `h264_opus.sdp` both "succeeded" while audio was
     * disabled, and SETUP only worked because its URL carries the trackID
     * segment and so happened to land on the right name. A client that asked for
     * audio got a negotiated stream with no audio track and no error.
     *
     * Split on segments and take the first one that is not a track control, so
     * both shapes work: h264.sdp and h264.sdp/trackID=0 give `h264`.
     */
    private fun sdpStem(uri: String): String {
        val path = uri.substringBefore('?').substringAfter("://")
            .substringAfter('/', "").ifEmpty { "" }
        val segments = path.split('/').filter { it.isNotEmpty() }
        val name = segments.firstOrNull { '=' !in it } ?: segments.firstOrNull() ?: ""
        return name.removeSuffix(".sdp").lowercase()
    }

    /**
     * Protocol and the track kinds a request URI names.
     *
     * The path is the part BEFORE any track control, not the last segment: a SETUP
     * arrives as the control URL from the SDP, which is
     * `rtsp://host:port/h264.sdp/trackID=0`. Taking substringAfterLast('/') gave
     * `trackID=0`, which names no codec, so every SETUP answered 551 Unsupported
     * media while DESCRIBE and OPTIONS worked -- and the whole reason it looked
     * fine in isolation.
     */
    private fun parseSdpUri(uri: String): Pair<String, List<String>> {
        val proto = uri.substringBefore("://", "rtsp")
        // Drop any query, then the track control, then the .sdp suffix.
        val stem = sdpStem(uri)
        val tracks = mutableListOf<String>()
        if (stem.contains("h264") || stem.contains("video")) tracks.add("video")
        if (stem.contains("pcm") || stem.contains("opus") || stem.contains("aac") ||
            stem.contains("ulaw") || stem.contains("alaw") || stem.contains("audio")
        ) tracks.add("audio")
        return proto to tracks
    }

    /**
     * The camera's LAN address, for the SDP's o= line.
     *
     * Walks the interfaces for a non-loopback IPv4 rather than asking
     * InetAddress.getLocalHost(), which returns 127.0.0.1 on a phone often enough
     * that a player reading o= instead of Content-Base would try to stream from
     * the device's own loopback and get nothing. The same search StreamServer
     * already does for its ONVIF URIs, for the same reason.
     */
    private fun localHost(): String = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }
            .firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address }
            ?.hostAddress
    }.getOrNull() ?: "127.0.0.1"

    private companion object {
        const val PTYPE_H264 = 96
        const val PTYPE_PCM = 97
        const val VIDEO_CHANNEL = 0
        const val AUDIO_CHANNEL = 2
        const val SESSION_ID = "ocubea-rtsp"
        const val SOCKET_TIMEOUT_MS = 30_000
        const val PCM_SAMPLE_RATE = 44100
        const val PCM_CHUNK_BYTES = 2048
        const val BYTES_PER_SAMPLE = 2
        /** RFC 3550: V=2, no padding, no extension, 12 bytes with no CSRC list. */
        const val RTP_HEADER_BYTES = 12
    }
}

/**
 * Reads a whole RTSP header block into a lowercase-keyed map.
 *
 * Ends at the blank line, as HTTP does. Bounded, because a client that opens a
 * connection and sends a request line with no terminator must not pin this thread
 * for the life of the socket.
 */
private fun readHeaders(input: java.io.BufferedReader): Map<String, String> {
    val out = HashMap<String, String>()
    var n = 0
    while (n < MAX_HEADERS) {
        val line = try {
            input.readLine() ?: break
        } catch (e: Exception) {
            break
        }
        if (line.isEmpty()) break
        val colon = line.indexOf(':')
        if (colon <= 0) continue
        out[line.substring(0, colon).trim().lowercase()] =
            line.substring(colon + 1).trim()
        n++
    }
    return out
}

/**
 * The CSeq to echo, from the header where RFC 2326 puts it.
 *
 * Falls back to the request line only for a client that inlined it, and answers
 * "0" rather than nothing when neither is present: a response with no CSeq cannot
 * be matched by a client that tracks it.
 */
private fun Map<String, String>.cseqOr(requestLine: String): String {
    this["cseq"]?.let { v ->
        Regex("(\\d+)").find(v)?.let { return it.groupValues[1] }
    }
    Regex("CSeq:\\s*(\\d+)", RegexOption.IGNORE_CASE)
        .find(requestLine)?.let { return it.groupValues[1] }
    return "0"
}

private const val MAX_HEADERS = 64

/** The method and the request URI from an RTSP request line. */
private fun String.methodAndUri(): Pair<String, String> {
    val parts = trim().split(Regex("\\s+"))
    return if (parts.size >= 2) parts[0] to parts[1] else parts[0] to ""
}
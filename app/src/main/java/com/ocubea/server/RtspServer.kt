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
    // Releasing the encoded-audio client is not implied by closing the stream.
    // AudioStreamManager.addEncodedClient() bumps a client count and registers a
    // sink on the shared fan-out; only removeEncodedClient() takes it back. The
    // HTTP path does this in its response's close(), and RTSP had no equivalent,
    // so every RTSP audio SETUP that was answered 200 leaked one client and one
    // encoder slot -- five probes in a row and `clients: 5` in /status.json.
    private val releaseAudio: () -> Unit,
    /** Installs (or with null clears) the per-frame tap on the encoder session. */
    private val setFrameTap: (((H264Encoder.Sample) -> Unit)?) -> Unit,
    /**
     * Asks the encoder to start, returning whether it is producing frames.
     *
     * RTSP is a separate switch from HLS, and HLS starts its encoder lazily on the
     * first playlist request. Without this, enabling RTSP alone left no encoder
     * running: DESCRIBE returned a correct SDP, SETUP returned interleaved
     * channels, PLAY returned 200, and not one RTP packet followed -- measured 0 in
     * 8 seconds. A client cannot distinguish that from a dead camera.
     */
    private val ensureEncoder: () -> Boolean,
    /**
     * The encoder's codec-config buffer (SPS/PPS), or null before it has one.
     *
     * Needed for sprop-parameter-sets. RTSP is the one consumer that has to hand
     * a client the parameter sets in advance: fMP4 carries them in an init segment
     * the client fetches before playback, and MJPEG has no codec to describe. RTSP
     * gets an SDP and then raw NALs, so anything not in that SDP has to be inferred
     * from the bitstream, and a client that infers wrongly plays nothing while
     * reporting a healthy connection.
     */
    private val encoderCodecConfig: () -> ByteArray?,
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
                            // Audio is refused here rather than promised and left
                            // silent. Answering 200 for a track this build will not
                            // deliver is the same lie as a silent video track: the
                            // client is told it is subscribed, and there is no
                            // RTSP signal that later tells it otherwise.
                            tracks.contains("audio") && audioChannel < 0 -> {
                                val source = openAudio()
                                if (source == null) {
                                    response(output, cseq, 551, "Unsupported media", emptyList())
                                } else {
                                    audioSource = source
                                    audioChannel = AUDIO_CHANNEL
                                    response(output, cseq, 200, "OK", listOf(
                                        "Transport: RTP/AVP/TCP;unicast;" +
                                            "interleaved=$audioChannel-${audioChannel + 1}",
                                        "Session: $SESSION_ID;timeout=60",
                                    ))
                                }
                            }
                            // A second SETUP for a track this session already
                            // holds is not a request for media that does not
                            // exist -- it is the same subscription. ffmpeg's
                            // RTSP demuxer does exactly this: after PLAY it
                            // re-SETUPs a track it already set up when it needs
                            // the payload type, and the old `else` answered 551,
                            // so ffprobe reported "method SETUP failed" against a
                            // server that was streaming correctly. Answer with the
                            // channel it already owns and take no second client.
                            tracks.contains("audio") && audioChannel >= 0 -> {
                                response(output, cseq, 200, "OK", listOf(
                                    "Transport: RTP/AVP/TCP;unicast;" +
                                        "interleaved=$audioChannel-${audioChannel + 1}",
                                    "Session: $SESSION_ID;timeout=60",
                                ))
                            }
                            tracks.contains("video") && videoChannel >= 0 -> {
                                response(output, cseq, 200, "OK", listOf(
                                    "Transport: RTP/AVP/TCP;unicast;" +
                                        "interleaved=$videoChannel-${videoChannel + 1}",
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
                        // Install the tap BEFORE asking whether the encoder is up.
                        // The availability check reads the tap, so asking first can
                        // only ever answer "not available" -- which is exactly what it
                        // did: startHls reported OK and isEncoding=true in the same
                        // millisecond, and this guard still refused.
                        if (videoChannel >= 0) setFrameTap(::offerSample)
                        val encoderReady = videoChannel < 0 || ensureEncoder()

                        if (!encoderReady) {
                            // Refuse rather than answer 200 and send nothing. Same
                            // reasoning as the SETUP 551 below: a client told
                            // "playing" with a silent stream reports the camera as
                            // broken, and nothing in RTSP lets it tell that apart
                            // from a camera that genuinely has nothing to send.
                            android.util.Log.w("OcuBeaRtsp", "PLAY refused: no encoder")
                            setFrameTap(null)
                            response(output, cseq, 503, "No encoder available", emptyList())
                            continue
                        }
                        response(output, cseq, 200, "OK", listOf(
                            "Session: $SESSION_ID",
                            "Range: npt=0.000-",
                            "RTP-Info: url=$uri;seq=1;rtptime=0",
                        ))
                        stopVideo = registerVideoSink(videoChannel, output)
                        // The ring was opened at SETUP, so a client that subscribes
                        // to audio and never sends PLAY still releases it in the
                        // finally.
                        // Audio goes to its own thread. Pumping it inline blocked
                        // this request loop for as long as the stream ran, so
                        // TEARDOWN, GET_PARAMETER and a second PLAY were never
                        // read -- measured: PLAY answered 200 and then the client
                        // got silence and no teardown, which is how a session
                        // stays stuck holding a client slot.
                        startAudioPump(
                            output, audioSource, audioChannel, audioSinkChannel, audioThreads,
                        )
                        android.util.Log.i(
                            "OcuBeaRtsp",
                            "PLAY ok videoChannel=$videoChannel audioChannel=$audioChannel",
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
            // Order matters: close the stream first so the reader stops, then
            // release the client that owns the encoder slot. Releasing first
            // would let the fan-out drop the sink under a live reader.
            runCatching { audioSource?.close() }
            if (audioSource != null) runCatching { releaseAudio() }
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
        // Per-session, so two clients cannot trip over each other's count.
        var keyframesSent = 0
        val sink: (H264Encoder.Sample) -> Unit = { sample ->
            // RFC 6184 packetization, not a raw copy of the access unit.
            //
            // SDP says packetization-mode=1, so every packet must carry a 1-byte
            // NAL header: for a small NAL the original header, for a large one an
            // FU-A indicator plus FU header. Writing the access unit straight into
            // the payload skipped that entirely, so a decoder read the middle of a
            // frame as a NAL header and found types 23, 21, 14 and 10 -- FU and
            // reserved values in the wrong place. ffprobe then failed every frame
            // with "non-existing PPS 0 referenced", while every protocol-level
            // check still passed: 200s, valid RTP headers, payloads full of H264
            // markers. A conformance probe written against this server could not
            // have caught it, because the bytes it looked for were there.
            // Every keyframe carries SPS/PPS in-band, the first one included.
            //
            // Skipping the first was the bug that left the captured stream starting
            // with a bare IDR: a client cannot rely on sprop-parameter-sets (measured
            // -- ffprobe still said "non-existing PPS 0" with them present), so the
            // very first access unit has to carry them or nothing that follows can
            // be decoded either. In that capture the SPS sat at offset 2782, after
            // 2 kB of picture.
            //
            // 27 bytes on a 1400-byte packet, per RFC 6184 section 8.2.
            //
            // Once per session, on the FIRST keyframe only.
            //
            // Prepending to every keyframe was a change I made on the strength of a
            // capture that appeared to show the parameter sets arriving 2782 bytes
            // in. That capture was broken -- it wrote one start code per access
            // unit, so the picture and the parameter sets looked merged. With the
            // capture fixed, repeating them on every keyframe only confuses a
            // decoder: ffmpeg reported `missing picture in access unit with size
            // 27` (27 = SPS 15 + PPS 4 + two 4-byte start codes), i.e. the
            // parameter sets arriving as a picture of their own, and 32 SPS for 15
            // keyframes. The frames decoded to a green band and a near-black field.
            //
            // RFC 6184 section 8.2 describes repeating them in a periodic refresh,
            // not on every keyframe, and a client's SDP already carries them.
            val withParams =
                if (sample.keyframe && keyframesSent == 0) prependParameterSets(sample.data)
                else sample.data
            if (sample.keyframe) keyframesSent++
            writeH264Rtp(output, channel, withParams)
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
        val aac = config.audioCodec.equals("aac", ignoreCase = true)
        // Bytes held back because they are the start of an access unit that has
        // not arrived whole. Only AAC needs this: an ADTS frame states its own
        // total length in the header, so the ring can be cut anywhere and the
        // pieces reassembled. L16 has no frames to find.
        var pending = ByteArray(AAC_PENDING_BYTES)
        var pendingLen = 0
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
            if (!aac) {
                writeRtp(output, channel, PTYPE_PCM, buf, 0, read)
                // L16 timestamps count samples at 44.1 kHz, not 90 kHz ticks.
                audioTs.addAndGet((read / BYTES_PER_SAMPLE).toLong())
                packetsSent.incrementAndGet()
                continue
            }

            // RFC 3640, mode=AAC-hbr: the payload starts with the AU-headers
            // section, not the access unit. A client reads the first bytes as the
            // AU-headers length and refuses the packet if that number is absurd --
            // shipping raw ADTS here made ffmpeg print `Error parsing AU headers`
            // for every frame while SETUP and PLAY both answered 200 OK, so the
            // probe gate stayed green over a stream nobody could decode.
            //
            // One AU per packet with sizeLength=16, indexLength=0 and
            // indexDeltaLength=0: a single 16-bit size field, big-endian.
            //
            // The AU must be WHOLE. Slicing the ring by however many bytes
            // happened to be available put a 9-byte stub on the wire whose ADTS
            // header claimed 184, and ffmpeg answered `First AU larger than
            // packet size`: the ring is a byte stream, not a frame queue, so its
            // reads land mid-frame.
            if (pendingLen + read > pending.size) {
                pending = ByteArray(pending.size * 2)
            }
            System.arraycopy(buf, 0, pending, pendingLen, read)
            pendingLen += read

            while (pendingLen >= ADTS_HEADER_BYTES && !sink.get()) {
                val frame = adtsFrameLength(pending, pendingLen)
                // 0 means no syncword here: the bytes held are not the start of an
                // ADTS frame, and resynchronising on the next one is the only way
                // out of a ring that was cut mid-frame.
                if (frame == 0) {
                    pendingLen = resyncAdts(pending, pendingLen)
                    break
                }
                if (pendingLen < frame) break
                writeRtp(output, channel, PTYPE_PCM,
                    byteArrayOf(
                        ((frame shr 8) and 0xFF).toByte(),
                        (frame and 0xFF).toByte(),
                    ), 0, AAC_AU_HEADER_BYTES,
                    // Marker set: one AU per packet means every packet ends an
                    // access unit. With it clear, ffmpeg kept the AU open, read
                    // the next packet's AU-header as audio, and answered
                    // `First AU larger than packet size` -- measured 130 packets
                    // with M=0 on the wire, all rejected. Timestamps advanced by
                    // exactly 1024 each, which is the proof that each packet was
                    // a whole frame and not a fragment.
                    marker = true,
                    samplesOverride = AAC_LC_FRAME_SAMPLES,
                    extraBody = pending, extraBodyLength = frame)
                // AAC-LC is 1024 samples per frame whatever the bitrate did to the
                // byte count, so this is a constant and not bytes/2. Deriving it
                // from the ADTS frame length gave 1416 for a frame holding 1024,
                // which paces a player at the wrong speed.
                audioTs.addAndGet(AAC_LC_FRAME_SAMPLES.toLong())
                packetsSent.incrementAndGet()
                pendingLen -= frame
                if (pendingLen > 0) {
                    System.arraycopy(pending, frame, pending, 0, pendingLen)
                }
            }
        }
    }

    /**
     * Total ADTS frame length from its 7-byte header, or 0 if [buf] does not
     * start with an ADTS syncword.
     *
     * `aac_frame_length` is 13 bits at offset 30: the low 2 bits of byte 3, all
     * of byte 4, and the high 3 bits of byte 5. The syncword is twelve 1 bits
     * followed by a layer id of 0, hence the 0xF6 mask.
     */
    private fun adtsFrameLength(buf: ByteArray, available: Int): Int {
        if (available < ADTS_HEADER_BYTES) return 0
        if ((buf[0].toInt() and 0xFF) != 0xFF) return 0
        if ((buf[1].toInt() and 0xF6) != 0xF0) return 0
        val frame = ((buf[3].toInt() and 0x03) shl 11) or
            ((buf[4].toInt() and 0xFF) shl 3) or
            ((buf[5].toInt() and 0xFF) shr 5)
        // A frame is never shorter than its own header, and anything past a few
        // seconds means the length field was misread.
        return if (frame in ADTS_HEADER_BYTES..MAX_AAC_FRAME_BYTES) frame else 0
    }

    /**
     * Drops bytes until a syncword is at the front, so a ring read that started
     * mid-frame does not wedge the stream forever.
     *
     * Without this the pump would hold the same broken bytes indefinitely, since
     * `frame == 0` also means "wait for more data". A malformed header must cost
     * at most one frame, not the rest of the session.
     */
    private fun resyncAdts(pending: ByteArray, pendingLen: Int): Int {
        var skip = 1
        while (skip + ADTS_HEADER_BYTES <= pendingLen) {
            if ((pending[skip].toInt() and 0xFF) == 0xFF &&
                (pending[skip + 1].toInt() and 0xF6) == 0xF0
            ) {
                break
            }
            skip++
        }
        if (skip + ADTS_HEADER_BYTES > pendingLen) {
            // No syncword at all in what is held, and the last ADTS_HEADER_BYTES-1
            // bytes could still be the start of one, so keep those.
            skip = pendingLen - (ADTS_HEADER_BYTES - 1)
            if (skip < 1) skip = pendingLen
        }
        val keep = pendingLen - skip
        if (keep > 0) System.arraycopy(pending, skip, pending, 0, keep)
        if (keep > 0) {
            android.util.Log.w("OcuBeaRtsp", "AAC resync: dropped $skip B, kept $keep B")
        }
        return keep
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
    /**
     * Sends one H264 access unit as RFC 6184 RTP payloads.
     *
     * The access unit is Annex-B: a sequence of start-code-delimited NAL units.
     * Each is sent on its own, split into MTU-sized fragments when it does not
     * fit:
     *
     *  - NAL of 1 byte or fewer, or within [singleNalMaxBytes]: sent whole, header
     *    included, marker bit set on the last fragment.
     *  - Larger NALs: FU-A. The indicator byte carries F=0, the original nal_unit_type
     *    and NRI; the FU header carries S/E flags and the type. Without the
     *    indicator a decoder cannot tell a fragment from a whole NAL, and the type
     *    it recovers is the FU type (28), not the real one.
     *
     * The marker bit goes on the final fragment of an access unit, not per NAL:
     * it means "a complete frame ends here", and a player uses it to decide when
     * to present a picture.
     */
    /**
     * The encoder's SPS/PPS as an Annex-B access unit, or an empty array when the
     * encoder has not produced any yet.
     *
     * Empty rather than null, so a caller can prepend unconditionally and the only
     * effect of "not ready yet" is that the frame goes out as it was.
     */
    /** The encoder's SPS/PPS as an Annex-B prefix, or empty before it has any. */
    private fun parameterSetsAnnextB(): ByteArray =
        com.ocubea.stream.H264Rtp.parameterSetsAnnextB(encoderCodecConfig() ?: ByteArray(0))

    /** [prefix] followed by [accessUnit], without copying more than needed. */
    private fun prependParameterSets(accessUnit: ByteArray): ByteArray {
        val prefix = parameterSetsAnnextB()
        if (prefix.isEmpty()) return accessUnit
        val out = ByteArray(prefix.size + accessUnit.size)
        System.arraycopy(prefix, 0, out, 0, prefix.size)
        System.arraycopy(accessUnit, 0, out, prefix.size, accessUnit.size)
        return out
    }

    /**
     * Sends one H264 access unit as RFC 6184 RTP payloads.
     *
     * The packetization itself lives in [com.ocubea.stream.H264Rtp], where it is
     * unit-tested against the bytes this device's encoder produces. It was inlined
     * here first, and being untestable is how it stayed wrong for hours: it built
     * every fragment as if it were a whole NAL, because the branch that decides
     * "fragment or not" tested the recovered NAL type for FU_A, and a fragment's
     * recovered type is never FU_A by construction.
     */
    private fun writeH264Rtp(output: OutputStream, channel: Int, accessUnit: ByteArray) {
        for (packet in com.ocubea.stream.H264Rtp.packetize(accessUnit, MTU_BYTES)) {
            val payload = packet.payload()
            writeRtp(
                output, channel, PTYPE_H264, payload, 0, payload.size, packet.marker,
            )
        }
    }

    private fun writeRtp(
        output: OutputStream,
        channel: Int,
        payloadType: Int,
        payload: ByteArray,
        offset: Int,
        length: Int,
        marker: Boolean = false,
        // For mode=AAC-hbr the RTP packet is AU-headers-section followed by the
        // access unit, so `payload` alone is not the body: it is the prefix.
        samplesOverride: Int? = null,
        extraBody: ByteArray? = null,
        extraBodyLength: Int = 0,
    ) = synchronized(writeLock) {
        writeRtpLocked(
            output, channel, payloadType, payload, offset, length, marker,
            samplesOverride, extraBody, extraBodyLength,
        )
    }

    private fun writeRtpLocked(
        output: OutputStream,
        channel: Int,
        payloadType: Int,
        payload: ByteArray,
        offset: Int,
        length: Int,
        marker: Boolean = false,
        samplesOverride: Int? = null,
        extraBody: ByteArray? = null,
        extraBodyLength: Int = 0,
    ) {
        if (length <= 0) return

        // RFC 2326: the length covers the WHOLE RTP packet, header included. A
        // payload-only length is 12 short, so a reader takes the last 12 bytes of
        // this NAL as the head of the next frame and the stream desynchronises one
        // frame in -- silently, with no error anywhere.
        val packetLength = length + extraBodyLength + RTP_HEADER_BYTES
        if (packetLength > 0xFFFF) return

        val hdr = ByteArray(12)
        hdr[0] = 0x80.toByte()                        // V=2
        // M marks the last packet of an access unit, not every packet of it. A
        // player presents a picture when it sees M, so M on every packet means a
        // frame appears once per fragment.
        hdr[1] = ((if (marker) 0x80 else 0x00) or (payloadType and 0x7F)).toByte()
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
        //
        // `length` is the prefix length here, so dividing it by 2 is only right
        // for L16 and only when the whole payload is PCM. For AAC the caller
        // passes the frame's real sample count, because the AU-headers prefix
        // is not audio at all and would drag the number down.
        val samples = samplesOverride
            ?: (if (payloadType == PTYPE_PCM) length / BYTES_PER_SAMPLE else 0)
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
        // The AU-headers prefix, then the access unit it describes. Order is the
        // whole point of RFC 3640 here, so it is not optional.
        if (extraBody != null && extraBodyLength > 0) {
            output.write(extraBody, 0, extraBodyLength)
        }
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

        // Make sure the encoder is up BEFORE building the SDP.
        //
        // sprop-parameter-sets carries the SPS/PPS, and those only exist once the
        // encoder has encoded its first frame. So without this, the very first
        // DESCRIBE of a session -- which is always the first, since no client
        // connects otherwise -- produced an SDP with no parameter sets, and the
        // client had no chance to get them from anywhere else. Measured: the SDP
        // came back with the fmtp line absent entirely and ffprobe failed every
        // frame with "non-existing PPS 0 referenced".
        //
        // Starting the encoder here rather than at PLAY is what makes it work: by
        // the time a client has read the SDP and sent SETUP and PLAY, frames exist
        // and the parameter sets could be attached -- but they cannot be attached
        // retroactively to an SDP already sent.
        if (tracks.contains("video")) {
            ensureEncoder()
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
        // sprop-parameter-sets carries the SPS and PPS, base64, in the SDP.
        //
        // Without it a client is told "H264" and nothing about how to decode it,
        // and it has to start guessing from NALs as they arrive. Measured with
        // ffprobe on this server: it connected, reported codec_name=h264, and
        // then failed every frame with "non-existing PPS 0 referenced" and
        // "no frame!". The encoder had been producing SPS/PPS all along in
        // codecConfig -- which this file never read, so they were encoded,
        // available, and still not sent.
        //
        // So a client can now be handed the parameter sets up front instead of
        // being made to scavenge them out of the first keyframe.
        // packetization-mode is unconditional; only the parameter sets are
        // conditional. Building the whole attribute inside the `if` once dropped
        // packetization-mode as well, so a client that had been told how to
        // packetize lost that on exactly the run where SPS/PPS were not ready --
        // a regression shipped inside the fix for a regression.
        val sprop = encoderCodecConfig()?.let {
            com.ocubea.stream.H264Rtp.spsPpsBase64(it)
        }
        val fmtp = buildString {
            append("packetization-mode=1")
            if (sprop != null) append(";sprop-parameter-sets=$sprop")
        }
        body.append("a=fmtp:96 $fmtp\r\n")
        body.append("a=control:trackID=0\r\n")
        if (wantsAudio && audioLive) {
            // The payload type must describe the track the server actually
            // sends. This hardcoded L16/44100/1 regardless of config.audioCodec,
            // so a session configured for aac, which is the default, advertised
            // raw PCM while delivering AAC: ffprobe then reported
            // codec_name=pcm_s16be for an AAC stream, and the client had no way
            // to notice, because RTSP has no signal saying the SDP payload type
            // was wrong.
            //
            // L16 stays the declaration for wav, the one codec whose ring really
            // does hold L16. The others name their own RTP encoding. Measured on
            // a Redmi Note 12 Pro running Android 16 with codec=aac: DESCRIBE
            // returned L16/44100/1 while logcat showed c2.android.aac.encoder
            // producing 216 frames per 5 s.
            val audioRtpmap = when (config.audioCodec) {
                "aac" -> "MPEG4-GENERIC/$AAC_RTP_SAMPLE_RATE/$AAC_RTP_CHANNELS"
                "opus" -> "OPUS/$PCM_SAMPLE_RATE/2"
                "amrnb" -> "AMR/$PCM_SAMPLE_RATE/1"
                "flac" -> "FLAC/$PCM_SAMPLE_RATE/1"
                else -> "L16/$PCM_SAMPLE_RATE/1"
            }
            body.append("m=audio 0 RTP/AVP 97\r\n")
            body.append("a=rtpmap:97 $audioRtpmap\r\n")
            // AAC over RTP needs the mode and profile that MP4 carries in esds, plus the
            // AU-header field widths. Without sizeLength a client falls back to
            // the 16-bit default for every AU, which happens to match here, but
            // it is not stated -- so a reader has to guess how many bytes of
            // header to skip, and getting that wrong is exactly the
            // `Error parsing AU headers` this server used to cause. sizelength=16
            // says out loud that each AU is preceded by one 16-bit size field.
            if (config.audioCodec == "aac") {
                body.append(
                    "a=fmtp:97 mode=AAC-hbr;profile-level-id=$AAC_PROFILE_LEVEL_ID;" +
                        "sizelength=16;indexlength=0;indexdeltalength=0;" +
                        "config=$AAC_CONFIG_HEX\r\n",
                )
            }
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
        // Audio is decided FIRST, and the combined stem is why.
        //
        // The audio aggregate is published as `h264_pcm.sdp`, so its stem is
        // "h264_pcm": it contains "h264" as well as "pcm". Testing video first put
        // a SETUP for the audio track down the video branch, which answered 200
        // with a video Transport even when audio was disabled and no source
        // existed -- the client then waited on a track the server never fed.
        //
        // So: a stem naming an audio codec is an audio track, whatever else is in
        // it. "h264_pcm" is audio, not video with a funny name.
        val audio = stem.contains("pcm") || stem.contains("opus") || stem.contains("aac") ||
            stem.contains("ulaw") || stem.contains("alaw") || stem.contains("audio")
        if (audio) tracks.add("audio")
        if (!audio && (stem.contains("h264") || stem.contains("video"))) tracks.add("video")

        // An explicit trackID overrides the stem, because the aggregate
        // `h264_pcm.sdp` is the URL every client is told to use and it carries
        // BOTH tracks. Without this, `h264_pcm.sdp/trackID=0` was read as audio
        // from the stem and the video branch never ran, so PLAY logged
        // `videoChannel=-1` while answering 200, ensureEncoder() was skipped by
        // `videoChannel < 0 ||`, startHls was never called, and the session
        // delivered audio only. Measured on both an API 23 and an API 36 phone:
        // ffmpeg wrote 287 B for twelve seconds of video, against 218 MJPEG
        // frames in twelve seconds over HTTP on the same phone at the same
        // moment.
        //
        // trackID 0 is video and 1 is audio because that is how describe()
        // numbers them: m=video gets a=control:trackID=0 and m=audio gets 1.
        val trackId = uri.substringAfterLast("trackID=", "")
            .takeWhile { it.isDigit() }
            .ifEmpty { null }
        if (trackId != null) {
            tracks.clear()
            tracks.add(if (trackId == "1") "audio" else "video")
        }
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
        /**
         * RFC 3640 AU-headers-section for one AAC AU: sizeLength=16 bits = 2
         * bytes, with indexLength=0 and indexDeltaLength=0 contributing none.
         *
         * This must equal the length of the byte array pumpAudio passes as the
         * prefix. It did not once: the constant said 4 while the array held 2
         * bytes, so `write(payload, 0, 4)` threw ArrayIndexOutOfBoundsException,
         * startAudioPump caught it as a generic Exception and closed the socket --
         * measured as SETUP 200, PLAY 200, then zero RTP packets forever, with
         * the encoder still producing (`packets_out` climbing, `empty_out` 0).
         */
        const val AAC_AU_HEADER_BYTES = 2
        const val ADTS_HEADER_BYTES = 7
        /**
         * What AAC-hbr packets on this wire actually are, read out of the ADTS
         * header rather than assumed.
         *
         * The SDP used to say 44100 and profile-level-id=1 (Main). Measured from
         * the bytes c2.android.aac.encoder produces: sampling_frequency_index=3,
         * which is 48000 Hz, and profile=1 in the ADTS `profile` field, which is
         * also Main but is a different field from profile-level-id. So the
         * declared rate was wrong by a factor that changes playback speed, and
         * nothing in the server noticed -- the RTP payload was well formed and
         * still came out of a player at the wrong pitch.
         *
         * Configured to match: AudioEncoder feeds the codec at 48 kHz and the
         * ADTS header on the wire says the same. If either side changes, this
         * constant and that configuration have to move together.
         */
        const val AAC_RTP_SAMPLE_RATE = 48000
        const val AAC_RTP_CHANNELS = 1
        /** AAC-LC, object type 2. */
        const val AAC_PROFILE_LEVEL_ID = 1
        /**
         * AudioSpecificConfig for AAC-LC at 48 kHz mono, as hex for a=fmtp.
         *
         * Bit layout: 5 bits object type (2 = LC), 4 bits sampling frequency
         * index (3 = 48000), 4 bits channel configuration (1 = mono), 3 bits
         * GASpecificConfig (0) -- 16 bits total, so 0001 0001 1000 1000.
         *
         * Without `config=` a client has no AudioSpecificConfig at all, and
         * ffmpeg's AAC-hbr demuxer rejects the stream with
         * `Error parsing AU headers`: it cannot tell an object type from the
         * ADTS header because under Aac-hbr the ADTS header is not required.
         * The RTP payload was already well formed -- 173 packets, M=1 on every
         * one, timestamps +1024, AU size matching the bytes that follow -- and
         * still nothing decoded.
         */
        const val AAC_CONFIG_HEX = "1188"
        /** AAC-LC, 1024 samples per frame at every bitrate and sample rate, so
         *  RTP's "samples" field is a constant here. Reading it out of the ADTS
         *  frame length gave 1416 for a frame holding 1024 samples, because the
         *  byte count grows with the bitrate while the frame does not. */
        const val AAC_LC_FRAME_SAMPLES = 1024
        /** Enough for two frames before the first grow, then it doubles as needed. */
        const val AAC_PENDING_BYTES = 4096
        /** Past this the 13-bit length field was misread; 64 kB is already absurd. */
        const val MAX_AAC_FRAME_BYTES = 65535
        /** RFC 3550: V=2, no padding, no extension, 12 bytes with no CSRC list. */
        const val RTP_HEADER_BYTES = 12

        /**
         * Whole-packet budget, under the 1500-byte Ethernet MTU.
         *
         * The transport is TCP-interleaved on the LAN, where oversizing a packet
         * IP-fragments it and then stalls on a retransmit: video that would merely
         * lose a frame over UDP instead stutters visibly over TCP. 1400 leaves
         * room for IP and TCP headers inside a single MTU.
         */
        const val MTU_BYTES = 1400

        /** 4-byte Annex-B start code. Every NAL unit needs its own. */
        val START_CODE = byteArrayOf(0, 0, 0, 1)
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

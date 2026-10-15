package com.ocubea.server

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Log

/**
 * Runtime probe for audio encoders, and the codec menu the settings screen and
 * `/status.json` are built from.
 *
 * This is the audio twin of the camera's CodecProbe, and it exists for the same
 * reason: the vendor codec XML is not an oracle. On the validation phone
 * (Redmi Note 10 Pro, MediaTek, API 33) reading `media_codecs_c2.xml` gives a
 * misleading picture -- it lists `c2.android.opus.encoder` and the AAC
 * encoders, but says nothing about whether they start, at what bitrate, or
 * whether a given device is old enough to have them at all. And the failure
 * mode is not cosmetic: an encoder that configures but never produces output
 * makes `/audio.aac` hang instead of fall back.
 *
 * So: ask MediaCodecList what exists, then confirm each candidate by actually
 * encoding a buffer. Only codecs that emit real bytes become menu entries, and
 * the menu degrades on its own when a device offers less -- an Android 6 phone
 * has no software Opus encoder at all, because `c2.android.opus.encoder`
 * landed in Android 11, so it must fall back to AAC without the user seeing a
 * broken option.
 *
 * MP3 is deliberately absent. No Android device ships an MP3 *encoder* in
 * MediaCodecList, software or hardware -- it is decode-only, and has been for
 * its whole life. A menu entry for it would be a lie.
 */
object AudioCodecProbe {

    private const val TAG = "OcuBeaAudio"

    @Volatile private var cached: ProbeResult? = null

    const val MIME_AAC = "audio/mp4a-latm"
    const val MIME_OPUS = "audio/opus"
    const val MIME_AMR_NB = "audio/3gpp"
    const val MIME_FLAC = "audio/flac"

    /** A codec the user can pick, with the bitrate the app will ask for. */
    data class Option(
        val id: String,
        val label: String,
        val mime: String,
        val bitrate: Int,
        val container: String,
        val note: String,
        /**
         * Whether a browser can play this stream through a plain progressive
         * `<audio src>` element -- the path the Web UI uses.
         *
         * This is a property of the CONTAINER the app serves, not of the
         * codec. It was measured, per container, in Chromium 148 against the
         * bytes this server actually emits (captured from the phone, served
         * paced and chunked with the same Content-Type, and required to move
         * an element's `currentTime`):
         *
         * | served as        | canPlayType | MSE isTypeSupported | element plays |
         * |------------------|-------------|---------------------|---------------|
         * | audio/wav        | maybe       | false               | yes, +3.01 s  |
         * | audio/aac (ADTS) | probably    | true                | yes, +3.01 s  |
         * | audio/flac       | probably    | false               | yes, +3.06 s  |
         * | audio/amr        | probably    | false               | not measured   |
         * | audio/ogg;opus   | probably    | false (webm: true)  | not measured   |
         *
         * So nothing in the current menu is unplayable, and hiding entries on
         * this flag would remove working choices. It is still carried because
         * the Web UI needs to know which paths are MSE-capable if it ever moves
         * audio onto the same SourceBuffer as the video: `audio/wav` and
         * `audio/flac` are not, so they cannot be appended to a SourceBuffer at
         * all and would need an fMP4 remux first.
         */
        val browserPlayable: Boolean = when (id) {
            "aac", "wav", "flac", "amrnb" -> true
            "opus" -> true // audio/ogg is progressive-playable; MSE wants audio/webm
            else -> false
        },
        /**
         * Whether the bytes can be handed to a MediaSource SourceBuffer.
         *
         * False for WAV and FLAC because MSE has no raw-PCM or FLAC source
         * buffer at all: `MediaSource.isTypeSupported('audio/wav')` and
         * `('audio/flac')` both return false in Chromium. Any plan to put
         * audio on the video SourceBuffer has to carry it as fMP4.
         */
        val mseCapable: Boolean = when (id) {
            "aac" -> true // audio/aac and audio/mp4; codecs="mp4a.40.2"
            else -> false
        }
    ) {
        /**
         * What the HTTP response says it is carrying.
         *
         * Not the same string as [mime]: the MediaCodec mime for Opus is
         * `audio/opus` but the byte stream is Ogg, and a browser handed
         * `audio/opus` will not play it. A client that trusts the content type
         * is being told the truth here, which is the whole reason the WAV-under-
         * an-AAC-name path was removed.
         */
        val contentTypeForHttp: String = when (id) {
            "aac" -> "audio/aac"
            "opus" -> "audio/ogg; codecs=opus"
            "amrnb" -> "audio/amr"
            "flac" -> "audio/flac"
            // `audio/x-wav` was what this returned before, and it is the
            // reason the WAV endpoint is served with a type no registry
            // carries. Chromium accepts it, but `audio/wav` is the registered
            // form and `audio/x-wav` is not in the IANA audio tree at all, so a
            // client doing a strict type check sees an unknown type and refuses
            // to play a stream whose bytes are perfectly valid. One string
            // cannot cost anything to get right.
            "wav" -> "audio/wav"
            else -> "application/octet-stream"
        }
    }

    /**
     * The menu, in the order the settings screen shows it: cheapest and best
     * first, WAV last as the always-available compatibility option.
     */
    fun options(probe: ProbeResult = probe()): List<Option> = buildList {
        if (probe.canOpus) {
            add(
                Option(
                    id = "opus",
                    label = "Opus 32 kbps",
                    mime = MIME_OPUS,
                    bitrate = 32_000,
                    container = "webm/opus",
                    note = "Najlepszy stosunek jakości do bajtów. Wymaga Androida 11+."
                )
            )
        }
        if (probe.canAac) {
            add(
                Option(
                    id = "aac",
                    label = "AAC 64 kbps",
                    mime = MIME_AAC,
                    bitrate = 64_000,
                    container = "aac",
                    note = "Działa na każdym Androidzie od 4.x. Domyślny wybór."
                )
            )
        }
        if (probe.canAmrNb) {
            add(
                Option(
                    id = "amrnb",
                    label = "AMR-NB 12 kbps",
                    mime = MIME_AMR_NB,
                    bitrate = 12_200,
                    container = "3gpp",
                    note = "Najmniej pasma, ale jakość wyraźnie gorsza."
                )
            )
        }
        add(
            Option(
                id = "wav",
                label = "WAV (surowy PCM)",
                mime = "audio/wav",
                bitrate = 706_000,
                container = "wav",
                note = "Bez kompresji, ~695 kbps na klienta. Zawsze dostępne."
            )
        )
        if (probe.canFlac) {
            add(
                Option(
                    id = "flac",
                    label = "FLAC",
                    mime = MIME_FLAC,
                    bitrate = 0, // lossless: the encoder picks
                    container = "flac",
                    note = "Bezstratny, ale większy niż WAV. Ciekawostka, nie oszczędność."
                )
            )
        }
    }

    /** What the device proved it can do, by encoding rather than by claiming. */
    data class ProbeResult(
        val canOpus: Boolean,
        val canAac: Boolean,
        val canAmrNb: Boolean,
        val canFlac: Boolean,
        val failed: Map<String, String>,
        /**
         * Codecs this device encodes but whose own decoder would not take the
         * bytes back. They stay in the menu, because a working encoder is worth
         * offering, but they are reported so an unplayable stream has a name
         * attached to it.
         *
         * On the validation phone this contains `audio/opus`:
         * `c2.android.opus.encoder` emits packets whose TOC byte is 0x78, which
         * is configuration 15 and forbidden by RFC 6716, so libopus drops them.
         */
        val undecodable: Set<String> = emptySet()
    ) {
        /**
         * The codec to serve when a client does not ask for one.
         *
         * AAC is the default, not Opus: AAC encoders exist on every Android
         * this app supports, while software Opus starts at Android 11. Choosing
         * the best codec available on the *newest* phone would make the stream
         * fail on the old e-waste phone that is the whole point of the app.
         */
        fun defaultId(): String = if (canAac) "aac" else "wav"
    }

    /**
     * Probes, in order, and caches the answer.
     *
     * The cache is deliberate: a request handler resolving "what can this
     * device do" must not spin up four MediaCodec instances per HTTP request,
     * and the answer only changes when the device does.
     */
    fun probe(): ProbeResult = cached ?: synchronized(this) {
        cached ?: probeUncached().also { cached = it }
    }

    /** Forgets the cached probe, for a settings screen that wants a fresh one. */
    fun invalidate() {
        synchronized(this) { cached = null }
    }

    fun probeUncached(): ProbeResult {
        val encoders = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { it.isEncoder }
        // Three separate questions, because they have three different answers
        // and conflating them is how a menu ends up lying:
        //
        //  - listed: MediaCodecList says the encoder exists
        //  - encoded: it actually produced bytes from real PCM
        //  - decoded: a decoder accepted those bytes back
        //
        // Only the first two decide what appears in the menu. The third one is
        // recorded and reported but deliberately does not gate availability,
        // because a decoder round trip is a much harder thing to get right than
        // an encoder is, and getting it wrong the other way is far more
        // damaging: it removed AAC from the menu on the validation phone, where
        // AAC demonstrably works and decodes to real PCM under ffmpeg. A codec
        // offered that a client cannot play is a user problem; a working codec
        // hidden from the menu is a bug in the app.
        //
        // So the rule is: no bytes, no option. Bytes that a local decoder
        // refuses are a warning in /status.json, not a hidden feature.
        val listed = mutableMapOf<String, Boolean>()
        val encoded = mutableMapOf<String, Boolean>()
        val decoded = mutableMapOf<String, Boolean>()
        fun check(mime: String): Boolean {
            val present = encoders.any { it.encodes(mime) }
            listed[mime] = present
            val out = if (present) tryEncode(mime) else null
            encoded[mime] = out != null
            val ok = out != null && tryDecode(mime, out, 2_000_000)
            decoded[mime] = ok
            return out != null
        }
        val aac = check(MIME_AAC)
        val opus = check(MIME_OPUS)
        val amr = check(MIME_AMR_NB)
        val flac = check(MIME_FLAC)
        Log.i(TAG, "probe listed=$listed encoded=$encoded decoded=$decoded")
        return ProbeResult(
            canOpus = opus,
            canAac = aac,
            canAmrNb = amr,
            canFlac = flac,
            undecodable = decoded.filterValues { !it }
                .keys
                .filter { encoded[it] == true }
                .toSet(),
            failed = buildMap {
                for ((mime, present) in listed) {
                    if (!present) continue
                    put(
                        mime,
                        if (encoded[mime] != true)
                            "listed but produced no output: $mime"
                        else if (decoded[mime] != true)
                            "encodes, but this device's decoder rejected it: $mime"
                        else
                            ""
                    )
                }
            }.filterValues { it.isNotEmpty() }
        )
    }

    private fun MediaCodecInfo.encodes(mime: String): Boolean = try {
        supportedTypes.any { it.equals(mime, ignoreCase = true) }
    } catch (_: Exception) {
        false
    }

    /**
     * Encodes real PCM and returns what the encoder produced, or null.
     *
     * Emitting bytes is not the same as working, so the caller hands the result
     * to a decoder -- see [tryDecode]. The payload is returned raw on purpose:
     * the probe must not wrap it in ADTS or Ogg the way the server does, since
     * a container bug is exactly the failure this check exists to rule out.
     */
    private fun tryEncode(mime: String, timeoutUs: Long = 2_000_000): List<ByteArray>? {
        var codec: MediaCodec? = null
        return try {
            codec = MediaCodec.createEncoderByType(mime)
            val format = MediaFormat.createAudioFormat(mime, 48_000, 1).apply {
                setInteger(
                    MediaFormat.KEY_BIT_RATE,
                    when (mime) {
                        MIME_OPUS -> 32_000
                        MIME_AMR_NB -> 12_200
                        MIME_FLAC -> 64_000
                        else -> 64_000
                    }
                )
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            // One second of audio is plenty for any encoder to emit
            // something, and small enough to stay instant on a slow phone.
            // Fed in small frames on purpose: an encoder's input buffer is
            // often much smaller than a second of 48 kHz audio -- pushing the
            // whole second at once throws BufferOverflowException, which is
            // exactly the failure this probe is supposed to catch rather than
            // mistake for "this device has no Opus".
            val outInfo = android.media.MediaCodec.BufferInfo()
            // nanoTime is nanoseconds and timeoutUs is microseconds: without
            // the *1000 the whole probe gets 8 ms instead of 8 s, which is
            // why the first version of this check declared AMR-NB and FLAC
            // broken on a phone that has both. Measured in milliseconds, once,
            // so the unit cannot drift again.
            val deadline = System.nanoTime() + timeoutUs * 1_000L * 4
            var queued = 0
            // 20 ms at 48 kHz, the frame size every encoder here accepts.
            val frameSamples = 960
            val pcm = ShortArray(frameSamples)
            // A tone, not silence: a decoder fed real content has to return
            // real content, which makes the comparison below meaningful.
            for (i in pcm.indices) {
                pcm[i] = (Math.sin(2.0 * Math.PI * 440.0 * i / 48_000.0) * 12_000).toInt().toShort()
            }
            // One entry per output buffer, never concatenated: the frame
            // boundaries are the encoder's, and they must survive to the decoder.
            val collected = mutableListOf<ByteArray>()
            // Draining after every single frame, not every few: measured on the
            // validation phone, AAC emitted its first bytes on the second frame
            // and Opus on the second as well, so checking output only every
            // eighth frame would have declared a working encoder broken.
            while (System.nanoTime() < deadline) {
                val inIdx = codec.dequeueInputBuffer(timeoutUs / 8)
                if (inIdx >= 0) {
                    val inBuf = codec.getInputBuffer(inIdx)
                    if (inBuf != null) {
                        inBuf.clear()
                        inBuf.asShortBuffer().put(pcm)
                        codec.queueInputBuffer(
                            inIdx, 0, frameSamples * 2, queued * 33_000_000L / 1000, 0
                        )
                        queued++
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(outInfo, timeoutUs / 8)
                if (outIdx >= 0) {
                    if (outInfo.size > 0) {
                        // A codec signal, not audio: Opus emits an
                        // AOPUSHD config blob that must never reach a client.
                        val buf = codec.getOutputBuffer(outIdx)
                        if (buf != null && !isCodecConfig(mime, buf, outInfo)) {
                            val slice = ByteArray(outInfo.size)
                            buf.position(outInfo.offset)
                            buf.get(slice)
                            collected.add(slice)
                        }
                    }
                    // Releasing every output, always. Forgetting this is how
                    // the live encoder stalled after two or three frames.
                    codec.releaseOutputBuffer(outIdx, false)
                    // Enough for a few frames to decode, but not so much that
                    // the probe is slow: a decoder needs a real payload, and
                    // 4 KB at 64 kbps is roughly half a second of audio.
                    if (collected.sumOf { it.size } > 8192) break
                }
            }
            val total = collected.sumOf { it.size }
            Log.i(TAG, "encode $mime: $queued frames in, ${collected.size} units, $total bytes out")
            if (collected.isEmpty()) null else collected
        } catch (e: Exception) {
            Log.w(TAG, "encoder $mime could not be confirmed", e)
            null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
        }
    }

    /**
     * True for the encoder's own init blob rather than audio.
     *
     * Opus signals its channel count and pre-skip in a packet starting
     * `AOPUSHD`; FLAC writes a `fLaC` signature. Both are configuration that
     * belongs in a header, not in the payload -- and a decoder that is handed
     * one as if it were audio may error out, which would make a working codec
     * look broken.
     */
    private fun isCodecConfig(mime: String, buf: java.nio.ByteBuffer, info: android.media.MediaCodec.BufferInfo): Boolean {
        if (info.size < 4) return false
        val head = ByteArray(4)
        buf.position(info.offset)
        buf.get(head)
        return when (mime) {
            MIME_OPUS -> String(head, Charsets.ISO_8859_1).startsWith("AOPUSHD")
            MIME_FLAC -> String(head, Charsets.ISO_8859_1) == "fLaC"
            else -> false
        }
    }

    /**
     * Feeds [payload] to a decoder and checks that audio comes back.
     *
     * A decoder that reports success but yields no output is treated as a
     * failure, because that is what libopus does with the validation phone's
     * `0x78` TOC: the frame parses, the packet is dropped.
     *
     * The payload is framed the way the server frames it before it leaves, which
     * is the whole point: a decoder fed bare AAC access units returns nothing at
     * all, because without an ADTS header it has no idea where a frame starts or
     * how long it is. Probing the unframed bytes would have reported AAC broken
     * on a phone where AAC works perfectly -- the probe has to test the thing the
     * client will actually be handed.
     */
    private fun tryDecode(mime: String, units: List<ByteArray>, timeoutUs: Long): Boolean {
        var codec: MediaCodec? = null
        return try {
            codec = MediaCodec.createDecoderByType(mime)
            val format = MediaFormat.createAudioFormat(mime, 48_000, 1)
            codec.configure(format, null, null, 0)
            codec.start()

            // AAC is checked framed, exactly as served. Opus is fed its bare
            // packets, which is what an Ogg page holds once demuxed.
            val framed = when (mime) {
                MIME_AAC -> wrapInAdts(units)
                else -> units.reduce { a, b -> a + b }
            }

            val info = android.media.MediaCodec.BufferInfo()
            val deadline = System.nanoTime() + timeoutUs * 1_000L * 4
            var samplesOut = 0
            // Fed repeatedly, like a live stream rather than one shot. An
            // audio decoder holds what it is given until it has enough to
            // decode, and codec priming means the first frames in a session
            // never produce output -- so a single queue leaves the decoder
            // silent and the probe would call a working codec broken. Feeding
            // the same chunk again is what the capture loop does with fresh
            // audio, and it is what gets a real answer out of the decoder.
            var fed = 0
            while (System.nanoTime() < deadline) {
                val inIdx = codec.dequeueInputBuffer(2_000)
                if (inIdx >= 0) {
                    val inBuf = codec.getInputBuffer(inIdx)
                    if (inBuf != null) {
                        inBuf.clear()
                        val n = minOf(framed.size, inBuf.capacity())
                        inBuf.put(framed, 0, n)
                        codec.queueInputBuffer(
                            inIdx, 0, n, fed * 33_000_000L / 1000, 0
                        )
                        fed++
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 5_000)
                if (outIdx >= 0) {
                    samplesOut += info.size
                    // Releasing every output, always, for the same reason the
                    // encoder loop does it.
                    codec.releaseOutputBuffer(outIdx, false)
                    if (samplesOut > 0) return true
                } else if (outIdx == android.media.MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // Format negotiation, not audio. Keep draining.
                    continue
                }
            }
            Log.w(
                TAG,
                "decoder $mime produced $samplesOut bytes from $fed feeds of " +
                    "${framed.size} bytes (${units.size} units)"
            )
            false
        } catch (e: Exception) {
            // An Opus decoder throwing on a TOC byte is the expected result
            // on the validation phone, not a surprise -- log it at info, or
            // every Android 6 phone looks like a crash in the log.
            Log.i(TAG, "decoder $mime rejected our own output: ${e.message}")
            false
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
        }
    }

    /**
     * Frames [parts] back to back, each with its own ADTS header.
     *
     * The encoder's output is not self-delimiting: each output buffer holds one
     * access unit and nothing in the bytes themselves says how long it is --
     * that length lives in MediaCodec.BufferInfo, and is gone once the buffers
     * are concatenated. So the sizes have to be captured while the encoder is
     * running rather than guessed afterwards.
     *
     * Guessing fails visibly. Splitting the concatenated bytes at a fixed 180 B
     * looks reasonable for 20 ms of 64 kbps AAC and produces frames that are
     * not aligned to any real frame boundary; the decoder then reports
     * ERROR_BAD_VALUE and substitutes silence, so a perfectly working codec
     * reads as broken.
     */
    private fun wrapInAdts(parts: List<ByteArray>): ByteArray {
        val rateIndex = AdtsFrame.indexFor(AudioEncoder.ENCODER_SAMPLE_RATE)
            ?: return parts.reduce { a, b -> a + b }
        val out = java.io.ByteArrayOutputStream()
        for (p in parts) {
            out.write(AdtsFrame.frame(p, AudioEncoder.ENCODER_SAMPLE_RATE, 1))
        }
        return out.toByteArray()
    }

    /** The codec to serve when a client does not ask for one. */
    fun defaultId(): String = probe().defaultId()

    /** One line for `/status.json`, so a remote reader knows what exists. */
    fun summary(p: ProbeResult = probe()): String =
        "aac=${p.canAac} opus=${p.canOpus} amrnb=${p.canAmrNb} flac=${p.canFlac} " +
            "wav=$canWav default=${p.defaultId()}"

    /**
     * Whether this device can serve the raw-PCM WAV endpoint.
     *
     * Always true, and that is the whole point of stating it: `wav` is the one
     * option that needs no MediaCodec, so it is available on every device
     * regardless of what the probe found.
     *
     * It was previously missing from [summary] entirely, and the WebUI reads
     * that string as a whitelist -- it builds the codec picker from the ids
     * that appear as `id=true` in it. With `wav` absent, the picker offered
     * only `none/aac/flac`, which is why the user reported that WAV "does not
     * appear in the UI" and therefore could not be tested. The endpoint served
     * 200 with a valid 44-byte header the whole time; it was invisible in the
     * menu that is supposed to point at it.
     *
     * Verified on the live page by replaying the page's own filter:
     *   available="aac=true opus=false amrnb=false flac=true default=aac"
     *     -> ['none','aac','flac']
     *   available="aac=true opus=false amrnb=false flac=true wav=true default=aac"
     *     -> ['none','aac','flac','wav']
     */
    const val canWav = true
}

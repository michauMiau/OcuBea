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
        val note: String
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
        val failed: Map<String, String>
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
        // Which of the wanted mimes merely exist, and which survived an actual
        // encode. Logging both separately is what makes a "false" here
        // diagnosable: without the split, a codec that is absent and a codec
        // that is present but silent look identical from outside.
        val listed = mutableMapOf<String, Boolean>()
        val worked = mutableMapOf<String, Boolean>()
        fun check(mime: String): Boolean {
            val present = encoders.any { it.encodes(mime) }
            listed[mime] = present
            val ok = present && tryEncode(mime)
            worked[mime] = ok
            return ok
        }
        val aac = check(MIME_AAC)
        val opus = check(MIME_OPUS)
        val amr = check(MIME_AMR_NB)
        val flac = check(MIME_FLAC)
        Log.i(TAG, "probe listed=$listed worked=$worked")
        return ProbeResult(
            canOpus = opus,
            canAac = aac,
            canAmrNb = amr,
            canFlac = flac,
            failed = listed.filterKeys { listed[it] == true && worked[it] != true }
                .mapValues { (mime, _) -> "listed but produced no output: $mime" }
        )
    }

    private fun MediaCodecInfo.encodes(mime: String): Boolean = try {
        supportedTypes.any { it.equals(mime, ignoreCase = true) }
    } catch (_: Exception) {
        false
    }

    /**
     * Confirms a codec by pushing real PCM through it and reading bytes back.
     *
     * `MediaCodec.createEncoderByType` plus a successful `configure` is not
     * enough: a codec that never emits output is exactly the failure that turns
     * a stream into a hang, so the loop below waits for the output buffer and
     * fails the probe if nothing arrives.
     */
    private fun tryEncode(mime: String, timeoutUs: Long = 2_000_000): Boolean {
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

            // One second of silence is plenty for any encoder to emit
            // something, and small enough to stay instant on a slow phone.
            // Fed in small frames on purpose: an encoder's input buffer is
            // often much smaller than a second of 48 kHz audio -- pushing the
            // whole second at once throws BufferOverflowException, which is
            // exactly the failure this probe is supposed to catch rather than
            // mistake for "this device has no Opus".
            val outInfo = android.media.MediaCodec.BufferInfo()
            val deadline = System.nanoTime() + timeoutUs * 4
            var queued = 0
            // 20 ms at 48 kHz, the frame size every encoder here accepts.
            val frameSamples = 960
            val silence = ShortArray(frameSamples)
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
                        inBuf.asShortBuffer().put(silence)
                        codec.queueInputBuffer(
                            inIdx, 0, frameSamples * 2, queued * 33_000_000L / 1000, 0
                        )
                        queued++
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(outInfo, timeoutUs / 8)
                if (outIdx >= 0 && outInfo.size > 0) return true
            }
            false
        } catch (e: Exception) {
            Log.w(TAG, "encoder $mime could not be confirmed", e)
            false
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
        }
    }

    /** The codec to serve when a client does not ask for one. */
    fun defaultId(): String = probe().defaultId()

    /** One line for `/status.json`, so a remote reader knows what exists. */
    fun summary(p: ProbeResult = probe()): String =
        "aac=${p.canAac} opus=${p.canOpus} amrnb=${p.canAmrNb} flac=${p.canFlac} default=${p.defaultId()}"
}

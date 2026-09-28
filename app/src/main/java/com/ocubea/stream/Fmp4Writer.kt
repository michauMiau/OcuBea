package com.ocubea.stream

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Minimal fragmented-MP4 writer for CMAF-style HLS video.
 *
 * Why fMP4 and not MPEG-TS: an H.264 elementary stream needs a container that
 * carries the SPS/PPS out-of-band (avcC) and lets a player join mid-stream
 * without waiting for the next program-start. CMAF fMP4 gives exactly that,
 * and hls.js plays it directly.
 *
 * Every segment begins with a random-access point, so a client that joins at
 * segment N never has to decode from the stream's beginning.
 */
class Fmp4Writer(
    requestedFps: Int = 30,
    /**
     * Target segment length, in milliseconds.
     *
     * This is what actually decides where segments are cut, so it lives here
     * rather than only in the playlist writer. A caller that advertises a
     * longer segment in #EXTINF without passing a matching value gets
     * one-frame segments described as long ones, and the player's clock
     * drifts away from the media timeline.
     */
    private val targetSegmentMs: Long = DEFAULT_SEGMENT_MS
) {

    data class Segment(val sequence: Int, val bytes: ByteArray, val durationMs: Long)

    private companion object {
        const val TIMESCALE = 1_000_000L          // microseconds, matches PTS directly
        // The movie AND media timescales must both be TIMESCALE, because every
        // timestamp written into the file — tfdt baseMediaDecodeTime and each
        // trun sample duration — is in microseconds straight from the encoder.
        //
        // Writing MOV_TIMESCALE (1000) here while feeding microsecond values made
        // a segment claim baseMediaDecodeTime = 16 977 125 223 / 1000 ≈ 16977125
        // seconds. A browser's MSE refuses to append such a buffer: hls.js
        // reports "bufferAppendError" and "internalException", the video element
        // never leaves readyState=1, and playback silently never starts. The
        // bytes are all individually valid, which is why ffprobe and ffmpeg read
        // the same segments without complaint while no browser would play them.
        const val MOV_TIMESCALE = TIMESCALE

        /**
         * 4 segments/sec: ~0.5s latency.
         *
         * This is a starting point, not a target the muxer always hits. A
         * segment is also cut whenever an IDR arrives, so with an IDR on every
         * frame segments are one frame long no matter what is asked for here.
         * The fix for a real target is a longer keyframe interval in the
         * encoder, not a number in this file.
         */
        const val DEFAULT_SEGMENT_MS = 250L
        const val SAMPLE_FLAGS_SYNC = 0x02000000
    }

    private var sequence = 0
    private var initSegment: ByteArray? = null
    private var pendingSamples = mutableListOf<H264Encoder.Sample>()
    private var segmentStartPtsUs = -1L
    private var lastPtsUs = -1L

    /** First sample's absolute PTS; subtracted from every sample to rebase to 0. */
    private var basePtsUs = 0L
    private var basePtsUsSet = false

    /** True when [pendingSamples] currently opens with an IDR. */
    private var segmentStartsWithKey = false

    /**
     * Frame interval used to stamp a segment's duration, in microseconds.
     *
     * Not the requested rate: the camera's ImageAnalysis regularly delivers
     * fewer frames than asked for, and a single-frame segment must claim exactly
     * the time that one frame really occupies. Too short and the segments leave
     * gaps; the decoder then never holds a continuous range and nothing paints.
     * Updated by [setMeasuredFps] as soon as the session has a real measurement.
     */
    @Volatile private var frameUs = 1_000_000L / 30

    fun setMeasuredFps(measured: Double) {
        if (measured <= 0.0) return
        val clamped = measured.coerceIn(0.5, 120.0)
        frameUs = (1_000_000.0 / clamped).toLong().coerceAtLeast(1_000L)
    }
    private var width = 0
    private var height = 0

    @Volatile var segmentsWritten: Long = 0L
        private set
    @Volatile var lastError: String = "none"
        private set

    /**
     * Builds the init segment once the encoder's SPS/PPS are known.
     *
     * Returns the ftyp+moov bytes, cached for every later segment.
     */
    fun initSegmentFor(width: Int, height: Int, rawConfig: ByteArray): ByteArray? {
        this.width = width
        this.height = height
        rawCodecConfig = rawConfig
        // The encoder hands us Annex-B SPS/PPS, not a ready-made avcC, so the
        // record has to be assembled before it can go into the avc1 sample entry.
        val full = buildInitSegment(width, height, rawConfig, null) ?: return null
        initSegment = full
        return full
    }

    val initSegmentOrNull: ByteArray? get() = initSegment

    /**
     * Rebuilds the init segment with a known total duration.
     *
     * Only meaningful for a file that is being closed: the returned ftyp+moov
     * has to replace what was written at the start of the file, so the caller
     * must be able to rewrite the first bytes. [newInit] is the same length as
     * the original — both are version-1 layouts, so the only field that differs
     * is the duration — which is what makes an in-place overwrite possible.
     *
     * Returns the replacement init, or null when the geometry is unknown or the
     * rebuild came out a different size than the original.
     */
    fun rebuildInitWithDuration(durationUs: Long): ByteArray? {
        val rawConfig = rawCodecConfig ?: return null
        if (width <= 0 || height <= 0) return null
        val original = initSegment ?: return null
        // A closed clip must carry a real length. Returning the live init
        // unchanged here would leave `duration = 0` in the file, which a
        // gallery reads as an incomplete recording — the exact symptom the
        // version-1 rewrite exists to fix. Refusing is honest; the caller keeps
        // the last known-good header.
        if (durationUs <= 0L) return null
        val rebuilt = buildInitSegment(width, height, rawConfig, durationUs) ?: return null
        // Both the live and the closed init are version-1 layouts, so the
        // only difference is the duration field itself. A size change here
        // would silently mean a different box layout and the in-place rewrite
        // would corrupt every offset after the header.
        if (rebuilt.size != original.size) return null
        return rebuilt
    }

    /** Remembers the encoder's Annex-B config so the moov can be rebuilt. */
    @Volatile private var rawCodecConfig: ByteArray? = null

    private fun buildInitSegment(w: Int, h: Int, rawConfig: ByteArray, durationUs: Long?): ByteArray? {
        val avcConfig = buildAvcC(rawConfig) ?: return null
        val ftyp = box("ftyp", ftypBody())
        // The version-1 layout is used unconditionally, with duration 0 while
        // the clip is still open. A version-0 box would be 8 bytes shorter, so
        // a live init and a closed init would not be interchangeable — and
        // "rewrite the header in place on close" depends on them matching
        // exactly. One layout, two meanings of the same field.
        val moov = box("moov", buildMoovBody(avcConfig, w, h, durationUs))
        return ftyp + moov
    }

    /**
     * The ftyp body: major brand, minor version, compatible brands.
     *
     * Found on a device, not in a spec: with only `iso6` and `cmfc` declared,
     * Android 13's Stagefright refused every clip with
     * `setDataSource failed: status = 0x80000000` — even though the file was
     * structurally valid, ffprobe read it without complaint, and the very same
     * bytes played back through the WebUI. MediaMetadataRetriever is the
     * narrowest decoder in the stack and it matches the declared brand against
     * what it actually implements, so the list has to include the classic
     * `isom`/`iso2` along with the fragmented-stream brands.
     *
     * `avc1` is included because this is an AVC track and some players key off
     * the sample entry brand rather than reading the codec configuration.
     * `iso5`/`iso6` cover the versions that use 64-bit box fields, which is
     * what mvhd/tkhd/mdhd here do.
     */
    private fun ftypBody(): ByteArray {
        val brands = listOf("isom", "iso2", "iso5", "iso6", "mp41", "avc1", "cmfc")
        val b = ByteArray(8 + brands.size * 4)
        // major_brand
        "isom".forEachIndexed { i, c -> b[i] = c.code.toByte() }
        var o = 4
        // minor_version 0x0200 as big-endian 00 00 02 00, as ISO/IEC 14496-12
        // requires for files that use 64-bit box fields.
        b[o++] = 0; b[o++] = 0; b[o++] = 2; b[o++] = 0
        for (brand in brands) {
            brand.forEachIndexed { i, c -> b[o + i] = c.code.toByte() }
            o += 4
        }
        return b
    }

    /**
     * Accumulates a sample; returns a segment when the target duration is hit.
     *
     * A keyframe forces an immediate cut so the segment always starts with a
     * random-access point, which is the hard requirement for CMAF.
     */
    fun append(sample: H264Encoder.Sample): Segment? {
        // A live stream must start its media timeline at zero, not at the
        // encoder's absolute PTS. The PTS comes from System.nanoTime()/1000, so
        // it is already ~17 million microseconds by the time anyone connects.
        // Subtracting the first sample's timestamp keeps the deltas exact while
        // making every tfdt and every segment start at a small, sane value —
        // which is also what a player expects from a live source, and what
        // keeps MSE from rejecting the first buffer as non-monotonic.
        if (!basePtsUsSet) {
            basePtsUs = sample.ptsUs
            basePtsUsSet = true
        }
        val rel = sample.copy(ptsUs = sample.ptsUs - basePtsUs)
        return appendRelative(rel)
    }

    private fun appendRelative(sample: H264Encoder.Sample): Segment? {
        if (pendingSamples.isEmpty()) {
            segmentStartPtsUs = sample.ptsUs
            // A segment that does not begin on an IDR is undecodable on its own:
            // every P-frame would reference macroblocks the client has never seen,
            // which is what produced "error while decoding MB" and a black
            // picture. When the encoder hands us a keyframe we start the segment
            // here so the keyframe is the FIRST sample, not merely present.
            segmentStartsWithKey = sample.keyframe
        } else if (sample.keyframe) {
            // Keyframe inside an open segment: close the open segment first, so
            // the keyframe opens the NEXT one.
            //
            // The previous version cleared pendingSamples and returned null,
            // throwing the partial segment away. With
            // MediaFormat.KEY_I_FRAME_INTERVAL = 0 every single frame is an IDR,
            // so EVERY append took this branch: each frame discarded the
            // previous one and nothing was ever flushed. The playlist froze on
            // the last segment before the session began growing, and a client
            // that had already read a higher sequence number from an earlier
            // playlist got a 404 for a segment that was dropped moments before
            // it asked. Closing here keeps the IDR as the first sample of the
            // next segment, which is the actual requirement.
            val closed = flush()
            segmentStartPtsUs = sample.ptsUs
            segmentStartsWithKey = true
            lastPtsUs = sample.ptsUs
            pendingSamples.add(sample)
            return closed
        }
        pendingSamples.add(sample)
        lastPtsUs = sample.ptsUs

        val elapsedMs = (sample.ptsUs - segmentStartPtsUs) / 1000
        // Only cut on time when the segment already opens with a keyframe;
        // otherwise keep buffering until one arrives.
        val mustCut = elapsedMs >= targetSegmentMs && segmentStartsWithKey
        if (!mustCut) return null
        return flush()
    }

    /** Forces a segment out regardless of duration (used on client joins/stop). */
    fun flush(): Segment? {
        if (pendingSamples.isEmpty() || initSegment == null) return null
        val samples = pendingSamples
        pendingSamples = mutableListOf()

        val startUs = segmentStartPtsUs
        val endUs = lastPtsUs
        // A one-sample segment has endUs == startUs, so the measured duration is
        // zero and the floor below supplies exactly one real frame interval.
        // A hardcoded 1000us instead of frameUs is what produced the gaps: at a
        // real 4fps a frame occupies ~250ms, so every segment claimed 1/250th
        // of the time it covered and SourceBuffer.buffered came out as a row of
        // disconnected 33ms islands.
        val durationUs = (endUs - startUs).coerceAtLeast(frameUs)

        val mdatPayload = buildFragment(samples, startUs, durationUs)
        // A CMAF media segment is moof+mdat ONLY. The ftyp+moov belongs to the
        // init segment the player fetches via #EXT-X-MAP, and must not be
        // repeated in every segment: appending the init to each one makes the
        // demuxer report "Found duplicated MOOV Atom" and "overread end of atom
        // 'dref'" once per segment, which is what a real client sees as a
        // stream that never gets past the first fragment.
        val segment = mdatPayload
        sequence++
        segmentsWritten++
        return Segment(sequence, segment, durationUs / 1000)
    }

    // ─── box construction ───────────────────────────────────────

    /**
     * Converts one Annex-B sample into AVCC (length-prefixed) form.
     *
     * MediaCodec emits NAL units separated by 00 00 01 start codes, but the
     * `avc1` sample entry declares AVCC framing, where each unit is prefixed by
     * its 4-byte length. Handing Annex-B bytes to an AVCC track makes the
     * decoder lose sync and report "non-existing PPS 0 referenced".
     *
     * Each NAL ends where the NEXT START CODE BEGINS, so both the 3-byte
     * (00 00 01) and 4-byte (00 00 00 01) forms are handled without guessing.
     * Inferring the code length from the preceding byte instead — and then
     * trimming a zero — silently deletes a payload byte, which shows up much
     * later as "error while decoding MB" rather than as a parse failure.
     *
     * Returns the input unchanged when no start code is present, so a device
     * that already hands back AVCC keeps working untouched.
     */
    private fun annexBToAvcc(data: ByteArray): ByteArray {
        val codeStarts = IntArray(64)
        val payloads = IntArray(64)
        var n = 0
        var i = 0
        while (i + 2 < data.size && n < codeStarts.size) {
            if ((data[i].toInt() and 0xFF) == 0 && (data[i + 1].toInt() and 0xFF) == 0 &&
                (data[i + 2].toInt() and 0xFF) == 1
            ) {
                val payload = i + 3
                val codeStart = if (payload >= 1 && (data[payload - 1].toInt() and 0xFF) == 0) {
                    payload - 1
                } else i
                codeStarts[n] = codeStart
                payloads[n] = payload
                n++
                i = payload
            } else {
                i++
            }
        }
        if (n == 0) {
            // MediaCodec may hand back a sample whose leading start code was
            // already stripped (the codec-config buffer is consumed once at
            // init). With no marker to anchor on, the whole buffer is one NAL.
            val out0 = ByteArray(data.size + 4)
            out0[0] = (data.size ushr 24).toByte()
            out0[1] = (data.size ushr 16).toByte()
            out0[2] = (data.size ushr 8).toByte()
            out0[3] = data.size.toByte()
            System.arraycopy(data, 0, out0, 4, data.size)
            return out0
        }

        val out = ByteArray(data.size + 4 * n)
        var o = 0
        for (k in 0 until n) {
            val from = payloads[k]
            val to = if (k == n - 1) data.size else codeStarts[k + 1]
            val len = to - from
            if (len <= 0) continue
            out[o] = (len ushr 24).toByte()
            out[o + 1] = (len ushr 16).toByte()
            out[o + 2] = (len ushr 8).toByte()
            out[o + 3] = len.toByte()
            System.arraycopy(data, from, out, o + 4, len)
            o += 4 + len
        }
        // Always return the AVCC form, including when the sizes happen to match.
        //
        // The old code had `if (o == data.size) return data`, reasoning that a
        // same-length result meant "nothing changed". It changes: Annex-B spends
        // 3 or 4 bytes on each start code and AVCC spends 4 on a length, so
        // equal totals prove nothing about the framing. On a single-NAL sample
        // whose leading start code the codec already stripped, the two are the
        // same length, the guard fired, and the raw Annex-B bytes were written
        // into mdat while trun advertised AVCC. Media Source Extensions then
        // got a buffer with no length-prefixed NAL at all: appendBuffer raised
        // an error, hls.js reported bufferAppendError, and no browser played a
        // frame — while ffprobe and ffmpeg, which are tolerant of the mismatch,
        // decoded the same segments without complaint.
        return out.copyOf(o)
    }

    /**
     * Builds an `avcC` decoder-configuration record from raw SPS/PPS NAL units.
     *
     * MediaCodec's BUFFER_FLAG_CODEC_CONFIG payload is NOT a valid avcC: on this
     * device it arrives as a 4-byte start code followed by the SPS, so storing it
     * verbatim produced configurationVersion=0, numOfSPS=4 and the decoder
     * error "sps_id 32 out of range". The record has to be assembled from the
     * individual parameter sets, each with its own 2-byte length prefix.
     *
     * Returns null when the payload has no usable SPS, so the caller can keep
     * waiting instead of caching a broken config that poisons every segment.
     */
    private fun buildAvcC(config: ByteArray): ByteArray? {
        val sps = ArrayList<ByteArray>()
        val pps = ArrayList<ByteArray>()
        var i = 0
        while (i + 2 < config.size) {
            if (!((config[i].toInt() and 0xFF) == 0 && (config[i + 1].toInt() and 0xFF) == 0 &&
                  (config[i + 2].toInt() and 0xFF) == 1)
            ) { i++; continue }

            val payload = i + 3

            // A NAL ends where the next START CODE BEGINS. The scan therefore
            // has to keep the index of that start code, not just the index where
            // the pattern was noticed: for 00 00 00 01 the code starts one byte
            // earlier than the 00 00 01 match, and using the match position
            // leaves the previous NAL one byte short and swallows a byte of the
            // next one.
            var nextCode = -1
            var j = payload
            while (j + 2 < config.size) {
                if ((config[j].toInt() and 0xFF) == 0 && (config[j + 1].toInt() and 0xFF) == 0 &&
                    (config[j + 2].toInt() and 0xFF) == 1
                ) {
                    nextCode = if (j >= 1 && (config[j - 1].toInt() and 0xFF) == 0) j - 1 else j
                    break
                }
                j++
            }
            val end = if (nextCode >= 0) nextCode else config.size

            val nal = config.copyOfRange(payload, end)
            when (nal[0].toInt() and 0x1F) {
                7 -> sps.add(nal)   // SPS
                8 -> pps.add(nal)   // PPS
            }
            if (nextCode >= 0) i = nextCode + 1 else break
        }
        if (sps.isEmpty()) return null

        val first = sps[0]
        val out = java.io.ByteArrayOutputStream(64)
        // configurationVersion is a SINGLE byte. Writing int(1) here emits the
        // four bytes 00 00 00 01, which shifts every following field by three
        // bytes — the record then parses as configurationVersion=0 with a
        // nonsensical SPS count, and the decoder rejects the whole stream.
        out.write(1)                        // configurationVersion = 1
        out.write(first[1].toInt())          // AVCProfileIndication
        out.write(first[2].toInt())          // profile_compatibility
        // AVCLevelIndication is overwritten with the level the actual frame size
        // requires, not the one the vendor encoder wrote into its SPS.
        //
        // This device's encoder reports level 0x0A (1.0) while producing
        // 1920x1080, which needs 4.0. A browser's Media Source Extensions
        // validates the codec string it built from this avcC against the stream
        // and refuses the media segment: MSE emits an error on appendBuffer,
        // hls.js surfaces "bufferAppendError" / "internalException", and the
        // video element stays stuck at readyState 1 with no picture. ffprobe and
        // ffmpeg never complain, which is why every byte-level test passed while
        // no browser would play a single frame.
        out.write(avcLevelFor(width, height))
        out.write(byteArrayOf(0xFF.toByte())) // 6 bits reserved + lengthSizeMinusOne = 3
        out.write(byteArrayOf(0xE1.toByte())) // 3 bits reserved + numOfSequenceParameterSets = 1
        for (s in sps) {
            out.write(byteArrayOf((s.size ushr 8).toByte(), s.size.toByte()))
            out.write(s, 0, s.size)
        }
        // The count is a single byte, so an explicit if/else: a Kotlin `if` here
        // would infer the common supertype Int and write() would not resolve.
        if (pps.isEmpty()) out.write(0) else out.write(pps.size)
        for (p in pps) {
            out.write(byteArrayOf((p.size ushr 8).toByte(), p.size.toByte()))
            out.write(p, 0, p.size)
        }
        return out.toByteArray()
    }

    /**
     * The AVC level byte written into avcC, chosen from the real frame size.
     *
     * Level 4.0 (0x28) admits 8192 macroblocks per frame, i.e. 2048x1536, so
     * 1920x1080 (8160 macroblocks) fits level 4.0 and not level 3.1. Levels
     * 4.1/5.0/5.1 raise the bitrate ceiling but not the macroblock count, so
     * there is nothing to gain by going past 4.0 here.
     *
     * The device's own encoder reports 0x0A (1.0) in its SPS while producing
     * 1080p, and MSE rejects the media segment when avcC disagrees with the
     * stream, so this value must come from the geometry we actually encode.
     */
    private fun avcLevelFor(w: Int, h: Int): Int {
        val macroblocks = ((w + 15) / 16).toLong() * ((h + 15) / 16)
        return when {
            macroblocks <= 3 * 3 -> 0x0A        // 1.0:  176x144
            macroblocks <= 11 * 11 -> 0x14     // 2.0:  352x288
            macroblocks <= 40 * 30 -> 0x1E     // 3.1:  1280x720
            macroblocks <= 120 * 68 -> 0x28    // 4.0:  2048x1536 (fits 1080p)
            macroblocks <= 368 * 368 -> 0x33   // 5.1:  4096x4096
            else -> 0x34                        // 5.2
        }
    }

    private fun buildFragment(samples: List<H264Encoder.Sample>, startUs: Long, durationUs: Long): ByteArray {
        // Re-frame every sample before muxing; sizes below must match what is
        // actually written, not the raw encoder output length.
        val avccSamples = samples.map { it.copy(data = annexBToAvcc(it.data)) }
        val totalSampleBytes = avccSamples.sumOf { it.data.size }

        val out = java.io.ByteArrayOutputStream(totalSampleBytes + 1024)

        // CMAF order is moof-then-mdat. trun's data_offset is resolved relative
        // to the start of the moof, so mdat must follow it — writing the mdat
        // header first (as an earlier revision did) makes every sample offset
        // wrong and players read the length prefix as payload.
        val moof = buildMoof(avccSamples, startUs, durationUs)
        out.putAll(moof)

        out.write(int(8 + totalSampleBytes))
        out.putAll(typeBytes("mdat"))
        for (s in avccSamples) out.putAll(s.data)
        return out.toByteArray()
    }

    private fun buildMoof(samples: List<H264Encoder.Sample>, startUs: Long, durationUs: Long): ByteArray {
        // mfhd sits directly in moof, but tfhd/tfdt/trun MUST be wrapped in a
        // traf box. ffmpeg's mov demuxer only looks for the track fragment
        // header inside traf, so a flat moof parses as a valid structure yet
        // fails with "trun track id unknown, no tfhd was found".
        val mfhd = java.io.ByteArrayOutputStream()
        mfhd.write(int(0))                                    // version + flags
        mfhd.write(int(sequence))
        val mfhdBox = box("mfhd", mfhd.toByteArray())

        val traf = java.io.ByteArrayOutputStream()

        // tfhd: track fragment header, default-base-is-moof
        val tfhd = java.io.ByteArrayOutputStream()
        tfhd.write(int(0x020000))                             // version 0, flags
        tfhd.write(int(1))                                    // track_ID
        traf.putAll(box("tfhd", tfhd.toByteArray()))

        // tfdt: base media decode time
        val tfdt = java.io.ByteArrayOutputStream()
        val b = startUs.toLong()
        // A FullBox is ONE version byte plus THREE flags bytes. The previous
        // code wrote byteArrayOf(0, 0, 0, 1) — a little-endian int 1 — which
        // put the version in the LAST byte and left the real version at 0 with
        // flags 0x000001. A version-0 tfdt is 8 bytes of payload, so the
        // demuxer read the first 4 bytes of the 64-bit timestamp as the decode
        // time: every segment claimed baseMediaDecodeTime=3 (microseconds in),
        // all eight sat on top of each other, and a player decoded exactly one
        // frame no matter how many segments it fetched.
        tfdt.write(byteArrayOf(1, 0, 0, 0))     // version 1, flags 0
        tfdt.write(byteArrayOf(
            ((b ushr 56) and 0xFF).toByte(), ((b ushr 48) and 0xFF).toByte(),
            ((b ushr 40) and 0xFF).toByte(), ((b ushr 32) and 0xFF).toByte(),
            ((b ushr 24) and 0xFF).toByte(), ((b ushr 16) and 0xFF).toByte(),
            ((b ushr 8) and 0xFF).toByte(), (b and 0xFF).toByte()
        ))
        traf.putAll(box("tfdt", tfdt.toByteArray()))

        // trun: one entry per sample. Duration is per-sample, not the whole
        // fragment, because players need it to pace playback.
        val trunBody = java.io.ByteArrayOutputStream()
        // flags 0x000701 = data-offset-present (0x0001) | sample-duration-present
        // (0x0100) | sample-size-present (0x0200) | sample-flags-present (0x0400).
        //
        // 0x0400 is sample-flags-present, NOT first-sample-flags-present — that
        // one is 0x0004, and setting it would insert an extra field that shifts
        // every sample entry. With 0x0400 each sample entry carries its own
        // flags, which is what marks the IDR at the head of the segment.
        trunBody.write(byteArrayOf(0, 0, 0x07, 0x01))  // version 0, flags 0x000701
        trunBody.write(int(samples.size))
        // data_offset is patched below, after the moof size is known.
        val dataOffsetPos = trunBody.size()
        trunBody.write(int(0))

        // Per-sample duration is the delta to the NEXT sample's pts, so players
        // pace playback correctly. Index-based: identity comparison on the last
        // element is unreliable and samples[indexOf(s)] is O(n^2).
        for (idx in samples.indices) {
            val cur = samples[idx]
            val nextPts = if (idx == samples.size - 1) startUs + durationUs else samples[idx + 1].ptsUs
            val durUs = (nextPts - cur.ptsUs).coerceIn(1L, 2_000_000L)
            trunBody.write(int(durUs.toInt()))
            trunBody.write(int(cur.data.size))
            trunBody.write(int(if (cur.keyframe) SAMPLE_FLAGS_SYNC else 0))
        }
        traf.putAll(box("trun", trunBody.toByteArray()))
        val trafBytes = box("traf", traf.toByteArray())
        val moofBody = java.io.ByteArrayOutputStream()
        moofBody.putAll(mfhdBox)
        moofBody.putAll(trafBytes)
        val moof = box("moof", moofBody.toByteArray())

        // Patch trun's data_offset: it is measured from the first byte of the
        // moof to the first byte of the mdat PAYLOAD, i.e. moof.length + 8.
        //
        // The offset of that field inside moof is found by searching for the
        // trun box, never by arithmetic. Hand-counting the prefix (8 for moof,
        // 8 for mfhd, 8 for traf) is easy to get wrong by one box, and a
        // mis-placed patch silently overwrites tfhd's track_ID — the moof then
        // still parses, but ffmpeg reads a nonsense data_offset and treats the
        // mdat header as sample data.
        val trunIdx = indexOfBox(moof, "trun")
        if (trunIdx >= 0) {
            // box header (8) + version/flags (4) + sample_count (4) = data_offset
            val patchAt = trunIdx + 16
            System.arraycopy(int(moof.size + 8), 0, moof, patchAt, 4)
        }

        return moof
    }

    /**
     * Byte offset of a box type inside a buffer, or -1.
     *
     * Used to patch already-serialised boxes. `data.copyOfRange` would copy the
     * whole moof just to find an offset; this scans in place.
     */
    private fun indexOfBox(buf: ByteArray, type: String): Int {
        val t = typeBytes(type)
        for (i in 0..buf.size - 4) {
            if ((buf[i].toInt() and 0xFF) == (t[0].toInt() and 0xFF) &&
                buf[i + 1] == t[1] && buf[i + 2] == t[2] && buf[i + 3] == t[3]
            ) return i - 4
        }
        return -1
    }

    /**
     * Builds the moov with an avcC sample description.
     *
     * Only the video track is described. There is no audio track, which is
     * valid for CMAF video-only HLS.
     */
    /**
     * Builds the moov.
     *
     * [knownDurationUs] is null for a live source, where the duration genuinely
     * is not known when the header is written, and set for a clip, whose length
     * is known by the time the file is closed. When it is set, mvhd and tkhd are
     * emitted at version 1 with real 64-bit durations: some players, the MIUI
     * gallery among them, read a version-0 duration of 0 as "incomplete file"
     * and show a zero-length clip even though every frame is present.
     *
     * Version 1 is not just a larger field — it also moves creation_time,
     * modification_time and duration to 64 bits, so the field layout changes
     * and writing the old offsets with a version-1 marker corrupts the box.
     */
    private fun buildMoovBody(avcConfig: ByteArray, w: Int, h: Int, knownDurationUs: Long? = null): ByteArray {
        val moov = java.io.ByteArrayOutputStream()
        val durationUs = knownDurationUs?.coerceAtLeast(0L) ?: 0L
        // Version 1 always. A 32-bit `duration` field is 4 bytes narrower, and
        // a live init segment that differs in size from a closed one makes an
        // in-place header rewrite impossible. One layout for both.

        // mvhd. Every inner box goes through box() so the size+type header is
        // always written; assembling the header by hand (write(int(0));
        // write(type)) yields a box with no size, which makes the whole moov
        // unparseable from that point on.
        val mvhd = java.io.ByteArrayOutputStream()
        mvhd.write(int(0x01000000.toInt()))                    // version 1 | flags 0
        mvhd.write(long(0)); mvhd.write(long(0))             // creation/modification
        mvhd.write(int(MOV_TIMESCALE.toInt()))
        mvhd.write(long(durationUs))                         // 64-bit duration
        mvhd.write(int(0x00010000))                          // rate 1.0
        mvhd.write(int(0x0100))                              // volume 1.0
        mvhd.write(int(0)); mvhd.write(int(0))               // reserved
        val matrix = intArrayOf(0x00010000, 0, 0, 0, 0x00010000, 0, 0, 0, 0x40000000.toInt())
        for (m in matrix) mvhd.write(int(m))
        for (i in 0 until 6) mvhd.write(int(0))              // pre_defined
        mvhd.write(int(2))                                   // next_track_ID
        moov.putAll(box("mvhd", mvhd.toByteArray()))

        moov.putAll(trackBox(w, h, avcConfig, durationUs, true))

        // mvex/trex is MANDATORY for a fragmented MP4: it declares that the
        // track is fragmented and supplies the default track_ID that tfhd/trun
        // reference. Without it ffmpeg's demuxer never associates the fragments
        // with the track and fails with "trun track id unknown, no tfhd was
        // found" — even when every box in the moof is perfectly well formed.
        val trex = java.io.ByteArrayOutputStream()
        trex.write(int(0))                                    // version + flags
        trex.write(int(1))                                    // track_ID
        trex.write(int(1))                                    // default_sample_description_index
        trex.write(int(0)); trex.write(int(0)); trex.write(int(0))  // duration, size, flags
        moov.putAll(box("mvex", box("trex", trex.toByteArray())))

        return moov.toByteArray()
    }

    /**
     * @param durationUs clip length, used only when [v1] is set
     * @param v1 emit version 1 headers with 64-bit timestamps and a real
     *   duration. A version-0 duration of 0 is legal for a live stream but
     *   reads as "incomplete file" to a gallery, so clips use version 1.
     */
    private fun trackBox(w: Int, h: Int, avcConfig: ByteArray, durationUs: Long = 0L, v1: Boolean = false): ByteArray {
        val t = java.io.ByteArrayOutputStream()

        // ── tkhd (flags 3 = track_enabled | track_in_movie)
        val tkhd = java.io.ByteArrayOutputStream()
        tkhd.write(int(0x01000003))                            // version 1 | flags 3
        tkhd.write(long(0)); tkhd.write(long(0))             // creation/modification
        tkhd.write(int(1))                                   // track_ID
        tkhd.write(int(0))                                   // reserved
        tkhd.write(long(durationUs))                         // 64-bit duration
        // Everything between the duration and the matrix is a run of reserved
        // and defaulted fields, and their WIDTH matters because the matrix has
        // to land on body offset 36. Writing the 8-byte reserved as int()
        // pairs and the four 2-byte fields as two more pairs made the run 24
        // bytes long instead of 16, so the matrix slid to 60, width/height to
        // 96, and the box came out 112 bytes instead of 104. Every field still
        // held a plausible value, which is why the duration and the codec
        // checks passed and nothing complained. A player that honours the
        // declared box size then reads the matrix where the pixel dimensions
        // should be.
        //   reserved 8 | layer 2 | alternate_group 2 | volume 2 | reserved 2
        tkhd.write(int(0)); tkhd.write(int(0))               // reserved 8
        tkhd.write(short(0)); tkhd.write(short(0))           // layer, alternate_group
        tkhd.write(short(0)); tkhd.write(short(0))           // volume, reserved
        val mtx = intArrayOf(0x00010000, 0, 0, 0, 0x00010000, 0, 0, 0, 0x40000000.toInt())
        for (m in mtx) tkhd.write(int(m))
        // 16.16 fixed point, so width/height are the PIXEL COUNTS SHIFTED LEFT
        // 16. Writing `w shl 16` truncates: 1280 shl 16 = 0x05000000 because Int
        // is 32-bit, and ffmpeg then reports width=0.
        tkhd.write(int(w * 65536))                           // width  16.16
        tkhd.write(int(h * 65536))                           // height 16.16
        t.putAll(box("tkhd", tkhd.toByteArray()))

        // ── mdia
        val mdia = java.io.ByteArrayOutputStream()
        // ── mdhd (version 1, matching mvhd/tkhd)
        val mdhd = java.io.ByteArrayOutputStream()
        mdhd.write(int(0x01000000.toInt()))                   // version 1 | flags 0
        mdhd.write(long(0)); mdhd.write(long(0))             // creation/modification
        mdhd.write(int(MOV_TIMESCALE.toInt()))
        mdhd.write(long(durationUs))                         // 64-bit duration
        mdhd.write(int(0x55C4))                              // 'und' language
        mdhd.write(int(0))                                   // pre_defined
        mdia.putAll(box("mdhd", mdhd.toByteArray()))

        // hdlr: 'vide'
        val hdlr = java.io.ByteArrayOutputStream()
        hdlr.write(int(0))                                   // version + flags
        hdlr.write(int(0))                                   // pre_defined
        hdlr.putAll("vide".toByteArray(Charsets.US_ASCII))
        hdlr.write(int(0)); hdlr.write(int(0)); hdlr.write(int(0))  // reserved[3]
        hdlr.putAll("OcuBea".toByteArray(Charsets.US_ASCII))
        hdlr.write(int(0))                                   // null terminator
        mdia.putAll(box("hdlr", hdlr.toByteArray()))

        // minf → vmhd + dinf + stbl
        val minf = java.io.ByteArrayOutputStream()
        val vmhd = java.io.ByteArrayOutputStream()
        vmhd.write(int(1))                                   // version 0, flags 1
        vmhd.write(int(0)); vmhd.write(int(0))               // graphicsmode, opcolor[3]
        minf.putAll(box("vmhd", vmhd.toByteArray()))

        minf.putAll(box("dinf", urlBox()))

        val stbl = java.io.ByteArrayOutputStream()
        // stsd → avc1 → avcC. The VisualSampleEntry header is 78 bytes and its
        // first fields are NOT int-aligned: 6 reserved BYTES + a 2-byte
        // data_ref_index, then 2 + 2 + 12 bytes. Writing them as 32-bit words
        // shifts the box by 16 bytes, which lands width/height on the wrong
        // field and makes ffmpeg report 0x0 for the whole stream.
        val avc1 = java.io.ByteArrayOutputStream()
        avc1.write(ByteArray(6))                        // reserved[6]
        avc1.write(byteArrayOf(0, 1))                   // data_ref_index = 1
        avc1.write(ByteArray(16))                       // pre_defined + reserved + pre_defined[3]
        avc1.write(byteArrayOf((w ushr 8).toByte(), w.toByte()))    // width  uint16
        avc1.write(byteArrayOf((h ushr 8).toByte(), h.toByte()))    // height uint16
        avc1.write(int(0x00480000)); avc1.write(int(0x00480000))    // 72dpi h/v
        avc1.write(int(0))                                   // reserved
        avc1.write(byteArrayOf(0, 1))                        // frame_count = 1
        avc1.write(ByteArray(32))                          // compressorname: 32 bytes
        avc1.write(byteArrayOf(0x00, 0x18))                // depth 0x0018
        avc1.write(byteArrayOf(0xFF.toByte(), 0xFF.toByte()))  // pre_defined = -1
        avc1.putAll(box("avcC", avcConfig))
        val stsd = java.io.ByteArrayOutputStream()
        stsd.write(int(0))                                   // version + flags
        stsd.write(int(1))                                   // entry_count
        stsd.putAll(box("avc1", avc1.toByteArray()))
        stbl.putAll(box("stsd", stsd.toByteArray()))

        // stts/stsc/stsz/stco are empty-but-present: every real value lives in
        // the fragments, and a zero-sample table is valid for fragmented MP4.
        for (name in arrayOf("stts", "stsc", "stco")) {
            val e = java.io.ByteArrayOutputStream()
            e.write(int(0))                                   // version + flags
            e.write(int(0))                                   // entry_count
            stbl.putAll(box(name, e.toByteArray()))
        }
        val stsz = java.io.ByteArrayOutputStream()
        stsz.write(int(0))                                    // version + flags
        stsz.write(int(0))                                    // sample_size 0 = in stz
        stsz.write(int(0))                                    // sample_count
        stbl.putAll(box("stsz", stsz.toByteArray()))

        minf.putAll(box("stbl", stbl.toByteArray()))
        mdia.putAll(box("minf", minf.toByteArray()))
        t.putAll(box("mdia", mdia.toByteArray()))

        return box("trak", t.toByteArray())
    }

    /**
     * Builds dinf → dref → one self-contained 'url ' entry.
     *
     * A dref entry is a FULL box: 4 bytes of size, 4 of type, then version and
     * flags — 12 bytes total. The previous version wrote version/flags and the
     * bare type with no size at all, so the demuxer read the entry's size as
     * 0x000000de (it ran into the following box) and reported "overread end of
     * atom 'dref' by 1970433052 bytes". The moov still parsed far enough to
     * report codec and dimensions, which is why ffprobe looked healthy while a
     * player could not use the stream.
     *
     * box() supplies the size and type, so the payload is only version+flags.
     */
    private fun urlBox(): ByteArray {
        val dref = java.io.ByteArrayOutputStream()
        dref.write(int(0))                                  // version + flags
        dref.write(int(1))                                  // entry_count = 1
        val entry = java.io.ByteArrayOutputStream()
        entry.write(int(1))                                 // version 0, flags 1 = self-contained
        dref.putAll(box("url ", entry.toByteArray()))        // 8 + 4 = 12 bytes
        return box("dref", dref.toByteArray())
    }

    // ─── primitives ────────────────────────────────────────────

    /**
     * Appends a byte array to a stream.
     *
     * Exists because ByteArrayOutputStream has no write(ByteArray) overload —
     * a bare write(arr) resolves to write(int) via Kotlin and stores the array
     * *reference* as 4 bytes, which silently corrupts the box structure. Every
     * payload write goes through here so that cannot happen again.
     */
    private fun ByteArrayOutputStream.putAll(bytes: ByteArray) = write(bytes, 0, bytes.size)

    private fun box(type: String, body: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(8 + body.size)
        out.write(int(8 + body.size))
        out.putAll(typeBytes(type))
        out.putAll(body)
        return out.toByteArray()
    }

    private fun int(v: Int): ByteArray = byteArrayOf(
        ((v ushr 24) and 0xFF).toByte(), ((v ushr 16) and 0xFF).toByte(),
        ((v ushr 8) and 0xFF).toByte(), (v and 0xFF).toByte()
    )

    /** Big-endian 64-bit field, needed by the version-1 movie/track headers. */
    private fun long(v: Long): ByteArray {
        val b = ByteArray(8)
        for (i in 0 until 8) b[i] = ((v ushr (56 - 8 * i)) and 0xFF).toByte()
        return b
    }

    /**
     * Big-endian 16-bit field.
     *
     * The tkhd interlude is a mix of 8- and 16-bit fields, so it cannot be
     * written as a run of int() calls: that produces a box of the right total
     * length only if the widths happen to add up, and they do not.
     */
    private fun short(v: Int): ByteArray = byteArrayOf(
        ((v ushr 8) and 0xFF).toByte(), (v and 0xFF).toByte()
    )

    /**
     * Four-character box type, ASCII, exactly four bytes.
     *
     * Cannot reuse int(): a box type is a string, and writing a char code as a
     * Kotlin Byte turns e.g. 'f' into 0xC3 0xBD under UTF-8, which shifts every
     * subsequent box by one byte and makes the whole file unparseable.
     */
    private fun typeBytes(type: String): ByteArray {
        val b = ByteArray(4)
        for (i in 0 until 4) b[i] = type[i].code.toByte()
        return b
    }
}

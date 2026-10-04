package com.ocubea.stream

/**
 * Annex-B parsing and RFC 6184 packetization for H264 over RTP.
 *
 * Extracted from RtspServer so it can be tested without a device, which is the
 * whole point: the RTP path was wrong for hours while every protocol-level check
 * passed, because those checks asked the server whether it was talking to itself
 * and never asked a decoder. These functions now run against the exact bytes a
 * MediaCodec produced on a real phone, in a unit test, in milliseconds.
 *
 * The bytes the tests use were read off a Sony F3311, not written by hand:
 *
 *     00 00 00 01 67 42 00 29 8d 8d 40 28 02 dd 00 f0 88 45 38
 *     00 00 00 01 68 ca 43 c8
 *
 * 15-byte SPS (type 7), 4-byte PPS (type 8), each introduced by a 4-byte start
 * code. Every assumption below that differs from this layout is a bug that a
 * synthetic round-trip would have hidden.
 */
object H264Rtp {

    /** 4-byte Annex-B start code. */
    val START_CODE: ByteArray = byteArrayOf(0, 0, 0, 1)

    /** One byte as a plain 0..255 int, so comparisons read as hex. */
    private fun byteAt(b: ByteArray, at: Int): Int = b[at].toInt() and 0xFF

    /** True where a 3- or 4-byte Annex-B start code begins at [at]. */
    fun isStartCode(b: ByteArray, at: Int): Boolean {
        if (at + 3 > b.size) return false
        if (byteAt(b, at) != 0x00) return false
        if (byteAt(b, at + 1) != 0x00) return false
        // 00 00 01 is a 3-byte code and 00 00 00 01 a 4-byte one. Both are legal,
        // and which one is present decides how far the caller steps -- so a code
        // matching only the first two bytes must not be mistaken for the long one.
        if (byteAt(b, at + 2) == 0x01) return true
        return at + 4 <= b.size && byteAt(b, at + 2) == 0x00 && byteAt(b, at + 3) == 0x01
    }

    /**
     * The length of the start code at [at], or 0 when there is none.
     *
     * Zero is a real answer and callers must check it. Scanning forward for a
     * start code while treating "nothing here" as a 3-byte code silently drops
     * three bytes of payload at every position it examines.
     */
    fun startCodeLength(b: ByteArray, at: Int): Int {
        if (!isStartCode(b, at)) return 0
        val four = at + 4 <= b.size &&
            byteAt(b, at + 2) == 0x00 && byteAt(b, at + 3) == 0x01
        return if (four) 4 else 3
    }

    /** The NAL ranges in an Annex-B buffer, each as `intArrayOf(start, end)`. */
    fun annexBNals(buf: ByteArray): List<IntArray> {
        val out = mutableListOf<IntArray>()
        var i = 0
        while (i < buf.size) {
            val sc = startCodeLength(buf, i)
            if (sc == 0) { i++; continue }
            i += sc
            val from = i
            // Runs to the next start code or the end of the buffer. The condition is
            // `<`, not `<= size - 3`: a final NAL shorter than that loses its tail,
            // and a truncated PPS is worse than none -- ffmpeg reports
            // "reference overflow (pps)" for it and still decodes nothing.
            while (i < buf.size && !isStartCode(buf, i)) i++
            if (i > from) out += intArrayOf(from, i)
        }
        return out
    }

    /** The SPS and PPS of a codec-config buffer, re-emitted as valid Annex-B. */
    fun parameterSetsAnnextB(cfg: ByteArray): ByteArray {
        if (cfg.isEmpty()) return ByteArray(0)
        val out = ByteArrayOutput()
        for (range in annexBNals(cfg)) {
            val type = byteAt(cfg, range[0]) and 0x1F
            if (type == 7 || type == 8) {
                // A start code before EACH unit.
                //
                // Without one, the last parameter set and the first NAL of the
                // access unit run together and parse as a single NAL whose type is
                // the first one's: `68ca43c8` + `65888000` became one 8-byte type-8
                // NAL, so the IDR was swallowed into the PPS and never sent.
                out.write(START_CODE)
                out.write(cfg, range[0], range[1] - range[0])
            }
        }
        return out.toByteArray()
    }

    /**
     * SPS and PPS base64 and comma separated, ready for sprop-parameter-sets.
     *
     * Null when the buffer has neither, which is a real answer: before the first
     * encoded frame the codec config can be absent, and SDP is built on a client's
     * DESCRIBE, which can arrive before that.
     */
    fun spsPpsBase64(cfg: ByteArray): String? {
        val parts = mutableListOf<String>()
        for (range in annexBNals(cfg)) {
            val type = byteAt(cfg, range[0]) and 0x1F
            if (type == 7 || type == 8) {
                parts += encodeBase64(cfg, range[0], range[1] - range[0])
            }
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(",")
    }

    /**
     * Base64 without line breaks.
     *
     * Encoded by hand rather than with either platform class, because both are
     * unusable here and each failure is silent:
     *
     *  - android.util.Base64 is a stub in the android.jar unit tests compile
     *    against: it exists to compile, not to run, and throws on a JVM. So the
     *    sprop line -- the one thing a decoder reads before any packet arrives --
     *    was the one thing no test could touch.
     *  - java.util.Base64 needs API 26. This app supports 23, and there is no
     *    coreLibraryDesugaring in the build, so calling it would throw
     *    NoClassDefFoundError on exactly the Android 6 device this app targets.
     *
     * Twenty lines of RFC 4648 is cheaper than either.
     */
    private fun encodeBase64(b: ByteArray, off: Int, len: Int): String {
        val sb = StringBuilder((len + 2) / 3 * 4)
        var i = off
        val end = off + len
        while (i + 3 <= end) {
            val n = ((b[i].toInt() and 0xFF) shl 16) or
                ((b[i + 1].toInt() and 0xFF) shl 8) or
                (b[i + 2].toInt() and 0xFF)
            sb.append(B64[(n shr 18) and 0x3F])
            sb.append(B64[(n shr 12) and 0x3F])
            sb.append(B64[(n shr 6) and 0x3F])
            sb.append(B64[n and 0x3F])
            i += 3
        }
        when (end - i) {
            1 -> {
                val n = (b[i].toInt() and 0xFF) shl 16
                sb.append(B64[(n shr 18) and 0x3F])
                sb.append(B64[(n shr 12) and 0x3F])
                sb.append("==")
            }
            2 -> {
                val n = ((b[i].toInt() and 0xFF) shl 16) or
                    ((b[i + 1].toInt() and 0xFF) shl 8)
                sb.append(B64[(n shr 18) and 0x3F])
                sb.append(B64[(n shr 12) and 0x3F])
                sb.append(B64[(n shr 6) and 0x3F])
                sb.append('=')
            }
        }
        return sb.toString()
    }

    private const val B64 =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    /** One assembled RTP packet for a fragment or whole NAL. */
    data class Packet(
        /** NRI bits plus the FU-A type, or the whole header for a single NAL. */
        val indicator: Int,
        /** The real NAL type, recovered from the FU header by a decoder. */
        val nalType: Int,
        /** True when this packet is an FU-A fragment rather than a whole NAL. */
        val fragment: Boolean,
        val start: Boolean,
        val end: Boolean,
        val body: ByteArray,
        val marker: Boolean,
    ) {
        /**
         * The RFC 6184 payload bytes: header, or FU-A indicator + FU header.
         *
         * `fragment`, not a comparison against FU_A_TYPE, decides this. For a
         * fragment the indicator's type field IS 28 by construction, so testing
         * nalType for 28 never matched, no branch ever ran, and the fragment went
         * out as a bare single NAL -- which is what produced payloads a decoder
         * rejected with "non-existing PPS 0 referenced". Measured on this build:
         * indicator 0x60 with FU header 0xab, where it must be 0x7c and 0x85.
         */
        fun payload(): ByteArray = if (fragment) {
            ByteArray(2 + body.size).also {
                it[0] = (indicator and 0xFF).toByte()
                it[1] = (
                    (if (start) 0x80 else 0x00) or
                        (if (end) 0x40 else 0x00) or (nalType and 0x1F)
                    ).toByte()
                System.arraycopy(body, 0, it, 2, body.size)
            }
        } else {
            ByteArray(1 + body.size).also {
                it[0] = (indicator and 0xFF).toByte()
                System.arraycopy(body, 0, it, 1, body.size)
            }
        }
    }

    /** The nal_unit_type of a FU-A indicator. */
    const val FU_A_TYPE = 28

    /**
     * Packetizes one Annex-B access unit into RTP payloads.
     *
     * Each NAL is sent on its own: whole when it fits the budget, split into
     * FU-A fragments when it does not. The marker bit goes on the final fragment
     * of the access unit, because it means "a complete picture ends here" and a
     * player uses it to decide when to display one.
     */
    fun packetize(
        accessUnit: ByteArray,
        mtuBytes: Int = 1400,
        rtpHeaderBytes: Int = 12,
    ): List<Packet> {
        if (accessUnit.isEmpty()) return emptyList()
        // Room for RTP's header, plus 2 bytes for FU-A indicator and FU header.
        val singleMax = mtuBytes - rtpHeaderBytes
        val fuMax = mtuBytes - rtpHeaderBytes - 2
        val nals = annexBNals(accessUnit)
        if (nals.isEmpty()) return emptyList()

        val out = mutableListOf<Packet>()
        for ((index, range) in nals.withIndex()) {
            val from = range[0]
            val to = range[1]
            val length = to - from
            val header = byteAt(accessUnit, from)
            val indicator = header and 0xFF
            val nri = header and 0xE0
            val type = header and 0x1F
            val isLast = index == nals.lastIndex

            // The NAL header byte goes in `indicator`, never in `body`.
            //
            // Payload() writes `indicator` itself and then appends `body`, so a
            // body that still carries its own header emits the header twice: an
            // SPS went out as `67 67 42 00 2d ...` and a PPS as `68 68 ca 43
            // c8`. ffmpeg read the second `67` as payload, so every access unit
            // lost a byte of shift and decoded as nal_unit_type 0 -- measured
            // on a Sony F3311: "illegal POC type 5" and "sps_id 1 out of range"
            // on all 75 frames, from a server that answered 200 OK and shipped
            // 2.4 MB of RTP. The same frames over HLS decoded cleanly, because
            // fMP4 takes its own length-prefixed NALs and never goes through
            // here.
            //
            // So `body` starts at from + 1 for both paths below.
            if (length <= singleMax) {
                out += Packet(
                    indicator = indicator,
                    nalType = type,
                    fragment = false,
                    start = true,
                    end = true,
                    body = accessUnit.copyOfRange(from + 1, to),
                    marker = isLast,
                )
                continue
            }
            var offset = from + 1
            var first = true
            while (offset < to) {
                val chunk = minOf(fuMax, to - offset)
                val lastFragment = offset + chunk >= to
                out += Packet(
                    // The indicator carries NRI and the FU-A type; the real type
                    // travels in the FU header, and the decoder puts it back.
                    indicator = nri or FU_A_TYPE,
                    nalType = type,
                    fragment = true,
                    start = first,
                    end = lastFragment,
                    body = accessUnit.copyOfRange(offset, offset + chunk),
                    marker = lastFragment && isLast,
                )
                offset += chunk
                first = false
            }
        }
        return out
    }

    /** Minimal growable byte buffer, so this file has no java.io dependency. */
    private class ByteArrayOutput {
        private var buf = ByteArray(256)
        private var len = 0

        fun write(b: ByteArray) {
            ensure(b.size)
            System.arraycopy(b, 0, buf, len, b.size)
            len += b.size
        }

        fun write(b: ByteArray, off: Int, count: Int) {
            ensure(count)
            System.arraycopy(b, off, buf, len, count)
            len += count
        }

        private fun ensure(extra: Int) {
            if (len + extra <= buf.size) return
            var next = buf.size * 2
            while (next < len + extra) next *= 2
            buf = buf.copyOf(next)
        }

        fun toByteArray(): ByteArray = buf.copyOf(len)
    }
}

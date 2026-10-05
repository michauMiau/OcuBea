package com.ocubea.server

/**
 * ADTS headers for AAC over a raw byte stream.
 *
 * A bare AAC access unit is self-delimiting for nothing: no length, no sample
 * rate, no channel count. A player handed one under `audio/aac` has no way to
 * find a frame boundary and answers "Error decoding AAC frame header" -- which
 * is exactly what ffmpeg did with this app's AAC endpoint before the frames
 * were wrapped. ADTS is the container that carries all three, seven bytes in
 * front of every frame, and `audio/aac` is the content type that means exactly
 * this framing.
 *
 * Layout, all multi-byte fields big-endian (unlike Ogg, which is
 * little-endian -- see [OggPage]):
 *
 * ```
 * 0        syncword             12 bits, always 0xFFF
 * 12       ID                   1 bit, 0 = MPEG-4, 1 = MPEG-2
 * 13       layer                2 bits, always 00
 * 15       protection absent    1 bit, 1 = no CRC
 * 16       profile              2 bits, 1 = AAC LC
 * 18       sampling freq index  4 bits
 * 22       private              1 bit
 * 23       channel config       3 bits
 * 26       originality/home    2 bits
 * 28       copyright id bit     1 bit
 * 29       copyright id start  1 bit
 * 30       frame length         13 bits, INCLUDING these seven header bytes
 * 43       buffer fullness      11 bits
 * 54       number of raw blocks 2 bits, 0 = one block
 * ```
 */
object AdtsFrame {

    /** MPEG-4, no CRC, AAC LC. */
    private const val ID = 0
    private const val LAYER = 0
    private const val PROTECTION_ABSENT = 1
    private const val PROFILE_AAC_LC = 1
    private const val HEADER_BYTES = 7

    /**
     * The 4-bit sample rate index. There are 13 defined values, so a rate that
     * is not on the list cannot be expressed here; [indexFor] returns null and
     * the caller sends the frame unwrapped rather than writing a wrong index.
     */
    fun indexFor(sampleRate: Int): Int? = when (sampleRate) {
        96000 -> 0
        88200 -> 1
        64000 -> 2
        48000 -> 3
        44100 -> 4
        32000 -> 5
        24000 -> 6
        22050 -> 7
        16000 -> 8
        12000 -> 9
        11025 -> 10
        8000 -> 11
        7350 -> 12
        else -> null
    }

    /** The rate table itself, so a reader can go from an index back to a rate. */
    val RATES: List<Int> = listOf(
        96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050,
        16000, 12000, 11025, 8000, 7350,
    )

    /**
     * The sampling-frequency index carried by an ADTS header, read back from
     * its bytes.
     *
     * The field is bits 18..21 of the 56-bit header, so it lives in byte 2,
     * bits 2..5 counted from that byte's top: `(h[2] and 0x3C) shr 2`. The
     * tempting `and 0x0C` reads the low two bits of byte 2 and the top two of
     * byte 3, which are the private bit and the first channel-configuration
     * bit -- it returns 49 for a header carrying index 3, which is not even a
     * table position. Checked against the header the phone actually sent,
     * `ff f1 4c 40 ...`, which this reads as 3 (48 kHz) and the wrong mask
     * reads as 49.
     */
    fun rateIndexOf(header: ByteArray): Int = (header[2].toInt() and 0x3C) shr 2

    /**
     * The channel configuration an ADTS header declares: bits 23..25, which
     * straddle bytes 2 and 3 -- the last bit of byte 2 and the top two of
     * byte 3.
     */
    fun channelConfigOf(header: ByteArray): Int =
        ((header[2].toInt() and 0x01) shl 2) or ((header[3].toInt() and 0xC0) shr 6)

    /** The frame length an ADTS header declares, header bytes included. */
    fun frameLengthOf(header: ByteArray): Int =
        ((header[3].toInt() and 0x03) shl 11) or
            ((header[4].toInt() and 0xFF) shl 3) or
            ((header[5].toInt() and 0xE0) shr 5)

    /**
     * True for [payload] when it is a bare AudioSpecificConfig rather than an
     * AAC access unit.
     *
     * An AAC encoder's first output buffer is not audio. On this device it is
     * the AudioSpecificConfig -- the two bytes `0x12 0x08`, meaning "AAC LC,
     * 44 100 Hz, mono" -- and it arrives on the same output queue as the audio
     * frames with nothing but its length to distinguish them. It is
     * configuration, not samples: it belongs in a container header, and raw
     * ADTS has no header slot for it, so it must not be framed as audio.
     *
     * Wrapping it anyway is what a 9-byte first ADTS frame is, and a 9-byte
     * ADTS frame is not decodable. ffmpeg on the downloaded bytes says
     * `Input buffer exhausted before END element found` and `Error submitting
     * packet to decoder: Invalid data found when processing input`, and both
     * messages disappear when those nine bytes are deleted from the capture.
     * In that capture it was the only frame under 16 bytes out of 282.
     *
     * ## Why length alone is not the test
     *
     * A real access unit can be short too, and the smallest frames an AAC
     * encoder emits on a quiet room are genuinely near that size. Guessing on
     * length would drop audio. What identifies the config is that it is
     * *structurally* an ASC: exactly two bytes, audioObjectType 2 (AAC LC) in
     * the top five bits, a rate index inside the table above, and the channel
     * configuration the encoder was configured with.
     *
     * It lives here rather than on `AudioEncoder` for the same reason the
     * header writer lives here: no Android imports, so the rule is assertable
     * on the JVM. The Opus equivalent is [OggPage]'s concern for the same
     * reason -- two codecs, one mistake.
     */
    fun isAudioSpecificConfig(payload: ByteArray, channels: Int): Boolean {
        // Two bytes is the whole encoding for AAC-LC at any of the four
        // channel configurations. Nothing else in the stream is this short.
        if (payload.size != 2) return false

        // audioObjectType, 5 bits. Values above 4 escape into a 6-bit field,
        // which changes the layout and makes the ASC longer than two bytes;
        // no Android AAC encoder writes anything but LC here.
        val objectType = (payload[0].toInt() and 0xF8) shr 3
        if (objectType !in 2..4) return false

        // samplingFrequencyIndex, 4 bits. 13..15 mean "explicit value in a
        // following field", which again implies more than two bytes.
        val freqIndex = ((payload[0].toInt() and 0x07) shl 1) or
            ((payload[1].toInt() and 0x80) shr 7)
        if (freqIndex >= RATES.size) return false

        // channelConfiguration, 4 bits, against what this stream was set up as.
        //
        // Values 8..15 are escape codes in the ASC: they mean "the real channel
        // configuration is carried in a further field", which by definition
        // makes the config longer than the two bytes checked above. A two-byte
        // payload carrying one is therefore not an ASC this encoder wrote --
        // accepting one would mean matching on a channel count that cannot
        // occur, and the function's whole job is to be narrow enough that it
        // never eats a real access unit.
        val channelConfig = (payload[1].toInt() and 0x78) shr 3
        return channelConfig in 1..7 && channelConfig == channels
    }

    /**
     * Prepends an ADTS header to one AAC access unit.
     *
     * Returns the payload unchanged when the frame cannot be described: an
     * over-long frame (> 8191 bytes) or a sample rate with no index. A wrong
     * header would be worse than none, because a wrong sync word desynchronises
     * the decoder for the rest of the stream.
     */
    fun frame(payload: ByteArray, sampleRate: Int, channels: Int): ByteArray {
        if (payload.isEmpty()) return payload
        val rateIndex = indexFor(sampleRate) ?: return payload
        val total = HEADER_BYTES + payload.size
        if (total > 0x1FFF) return payload

        val header = ByteArray(HEADER_BYTES)
        val bits = headerBits(rateIndex, channels, total)
        // Top-down: the first byte holds the top 8 bits of the 56-bit field.
        for (i in 0 until HEADER_BYTES) {
            header[i] = ((bits shr (8 * (HEADER_BYTES - 1 - i))) and 0xFF).toByte()
        }

        val out = ByteArray(total)
        System.arraycopy(header, 0, out, 0, HEADER_BYTES)
        System.arraycopy(payload, 0, out, HEADER_BYTES, payload.size)
        return out
    }

    /**
     * The seven header bytes as one integer, to be shifted out top-down.
     *
     * Every field is placed at its absolute bit offset measured from the start
     * of the 56-bit header, so there is no accumulation to get wrong. Writing
     * them as chained `or`s at hand-counted shifts is how the first version
     * produced sync=0xFFF with profile=0, rate index 1 and channel config 5 --
     * ffmpeg's "Reserved bit set" -- because `Byte.toInt()` sign-extends and one
     * misplaced shift corrupts every field after it.
     */
    private fun headerBits(rateIndex: Int, channels: Int, frameLength: Int): Long {
        var bits = 0L
        fun put(value: Int, count: Int, at: Int) {
            bits = bits or ((value.toLong() and ((1L shl count) - 1)) shl (56 - at - count))
        }
        put(0xFFF, 12, 0) // syncword
        put(ID, 1, 12) // MPEG-4
        put(LAYER, 2, 13) // always 0
        put(PROTECTION_ABSENT, 1, 15) // no CRC
        put(PROFILE_AAC_LC, 2, 16)
        put(rateIndex, 4, 18)
        put(0, 1, 22) // private
        put(channels, 3, 23)
        put(0, 2, 26) // originality/home
        put(0, 1, 28) // copyright id bit
        put(0, 1, 29) // copyright id start
        put(frameLength, 13, 30) // includes the seven header bytes
        put(0x7FF, 11, 43) // buffer fullness: VBR
        put(0, 2, 54) // one raw data block
        return bits
    }
}

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

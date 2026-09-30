package com.ocubea.server

/**
 * The `fLaC` signature and STREAMINFO block for a live FLAC stream.
 *
 * ## Why this exists
 *
 * Android's FLAC `MediaCodec` encoder emits **raw FLAC frames and nothing else**:
 * a bare sequence of frame headers and subframes, with no file signature and no
 * metadata. `MediaMuxer` normally supplies that outer layer, and this app does
 * not use `MediaMuxer` because it cannot express "a stream that ends when the
 * HTTP client disconnects" -- so the header this writes is the app's job.
 *
 * Without it the body is not a FLAC file. It is a headerless bitstream that a
 * decoder has to *guess* at, and the guess is unreliable by construction:
 *
 * ```
 * [flac @ ...] Format flac detected only with low score of 13,
 *              misdetection possible!
 * ```
 *
 * That warning is not cosmetic. Two things follow from having no STREAMINFO:
 *
 *  1. The decoder learns no authoritative sample rate, channel count or bits
 *     per sample, so it takes them from whatever the first frame happens to
 *     say. A frame whose sample-rate code is 0 ("get it from STREAMINFO") --
 *     which the spec explicitly allows -- becomes undecodable, because the
 *     STREAMINFO it refers to is not there.
 *  2. The demuxer cannot trust frame boundaries, so it hunts for the `0xFF 0xF8`
 *     sync word. Those two bytes occur **inside frame payloads** by chance: a
 *     600 KB capture measured here contained 51 of them, a 1.1 MB capture 306.
 *     Landing on one mid-subframe is what makes ffmpeg emit, verbatim:
 *
 *     ```
 *     [flac @ ...] invalid residual
 *     [flac @ ...] decode_frame() failed
 *     ```
 *
 *     which was reproduced on demand by deleting a few hundred bytes to force
 *     exactly that resync. Whether it fires depends on the audio, which is why
 *     the same endpoint could look clean for ten captures in a row and then
 *     report errors intermittently. Writing the header removes the whole
 *     failure class: with a signature and a STREAMINFO, the decoder is told the
 *     stream parameters up front and frames are located by walking the bitstream
 *     rather than by searching for a two-byte pattern.
 *
 * ## Layout
 *
 * ```text
 * "fLaC"                     32 bits   stream marker
 * last-metadata-block flag    1 bit    1: STREAMINFO is the last block
 * block type                  7 bits   0 = STREAMINFO
 * block length               24 bits   34
 *   minimum block size       16 bits
 *   maximum block size       16 bits
 *   minimum frame size       24 bits   0 = unknown
 *   maximum frame size       24 bits   0 = unknown
 *   sample rate              20 bits
 *   channels - 1              3 bits
 *   bits per sample - 1       5 bits
 *   total samples            36 bits   0 = unknown (a live stream never knows)
 *   MD5 signature           128 bits   0 = unknown
 * ```
 *
 * Every multi-byte field is big-endian, and the bit fields are packed
 * most-significant-bit first, which is what the format means and the opposite
 * of the Ogg page header in [OggPage].
 *
 * `last-metadata-block` MUST be 1. A stream that sets it to 0 is declaring that
 * more metadata blocks follow, and ffmpeg acts on that: it reads to the end of
 * the file waiting for them and returns an empty decode, with
 * `No filtered frames for output stream`. Measured, not assumed -- a headered
 * capture built both ways decoded 319 488 bytes of PCM with the flag set and 0
 * bytes with it clear.
 *
 * The unknown fields are honest zeros, not guesses. A live stream cannot know
 * its length or its MD5, and the spec defines 0 to mean exactly that.
 *
 * No Android imports, so the bit layout is assertable on the JVM.
 */
object FlacStreamHeader {

    /** `fLaC`, the stream marker every FLAC file starts with. */
    private val MAGIC = byteArrayOf(0x66, 0x4C, 0x61, 0x43) // "fLaC"

    /** STREAMINFO is always 34 bytes (RFC 9639 § 8.2). */
    const val STREAMINFO_BYTES = 34

    /** Block header: one flag bit, seven type bits, 24 length bits. */
    const val METADATA_HEADER_BYTES = 4

    /** `fLaC` + the STREAMINFO block header + STREAMINFO itself. */
    const val HEADER_BYTES = 4 + METADATA_HEADER_BYTES + STREAMINFO_BYTES

    /** STREAMINFO's block type in the metadata header. */
    private const val BLOCK_TYPE_STREAMINFO = 0

    /**
     * The block size the FLAC encoder on the validation phone emits: 4096
     * samples, measured by parsing the frame headers of real captures (423 of
     * 423 frames agreed).
     *
     * STREAMINFO's min/max block size tells a decoder how much to buffer and is
     * advisory for decoding: a frame whose own header says 4096 decodes
     * correctly whatever this field claims. It is stated accurately here rather
     * than left to a guess, and the frame headers remain the authority.
     */
    const val BLOCK_SIZE = 4096

    /** The PCM this app captures is `AudioStreamManager.AUDIO_FORMAT`, 16-bit. */
    const val BITS_PER_SAMPLE = 16

    /**
     * The stream header for a live [sampleRate]/[channels] FLAC stream, or an
     * empty array when the parameters cannot be expressed -- 20 bits of sample
     * rate, 3 bits of channels, 5 bits of depth. A header that lies about the
     * stream is worse than no header, so this refuses instead of truncating.
     */
    fun header(sampleRate: Int, channels: Int, bitsPerSample: Int = BITS_PER_SAMPLE): ByteArray {
        if (sampleRate !in 1..MAX_SAMPLE_RATE) return ByteArray(0)
        if (channels !in 1..MAX_CHANNELS) return ByteArray(0)
        if (bitsPerSample !in 1..MAX_BITS_PER_SAMPLE) return ByteArray(0)

        val out = ByteArray(HEADER_BYTES)
        MAGIC.copyInto(out, 0)

        // Metadata block header, big-endian: flag in bit 31, type in bits 30..24,
        // length in bits 23..0. `last` is 1 because a live stream has no further
        // metadata blocks and only audio frames after this one.
        val metaHeader = (1L shl 31) or
            (BLOCK_TYPE_STREAMINFO.toLong() shl 24) or
            STREAMINFO_BYTES.toLong()
        writeU32(out, MAGIC.size, metaHeader)

        // STREAMINFO body.
        writeU16(out, 4 + 4, BLOCK_SIZE)            // minimum block size
        writeU16(out, 4 + 6, BLOCK_SIZE)            // maximum block size
        // Minimum and maximum FRAME size are 24 bits each and a live stream
        // knows neither, so both are written as explicit zeros rather than
        // left to fall out of an over-wide write.
        writeU24(out, 4 + 8, 0)
        writeU24(out, 4 + 11, 0)
        writeStreamParams(out, 4 + 14, sampleRate, channels, bitsPerSample)
        // Bytes 4+22 .. 4+37 stay zero: the 36 bits of total samples are packed
        // by writeStreamParams and the 16-byte MD5 follows, both unknown for a
        // live stream.

        return out
    }

    private const val MAX_SAMPLE_RATE = (1 shl 20) - 1   // 20 bits
    private const val MAX_CHANNELS = (1 shl 3)           // 3 bits, value is ch-1
    private const val MAX_BITS_PER_SAMPLE = (1 shl 5)    // 5 bits, value is bps-1

    private fun writeU16(out: ByteArray, at: Int, v: Int) {
        out[at] = ((v shr 8) and 0xFF).toByte()
        out[at + 1] = (v and 0xFF).toByte()
    }

    private fun writeU24(out: ByteArray, at: Int, v: Long) {
        for (i in 0 until 3) out[at + i] = ((v shr (8 * (2 - i))) and 0xFF).toByte()
    }

    private fun writeU32(out: ByteArray, at: Int, v: Long) {
        for (i in 0 until 4) out[at + i] = ((v shr (8 * (3 - i))) and 0xFF).toByte()
    }

    private fun writeU64(out: ByteArray, at: Int, v: Long) {
        for (i in 0 until 8) out[at + i] = ((v shr (8 * (7 - i))) and 0xFF).toByte()
    }

    /**
     * Packs 20 + 3 + 5 bits into one 64-bit big-endian field: sample rate,
     * then channels - 1, then bits per sample - 1, with 36 bits of total
     * samples left at zero underneath them.
     *
     * Written through a running shift rather than by hand-placed bit offsets in
     * the stream, so the fields cannot drift out of order: each lands above the
     * ones already packed, which is the order the format defines.
     * `Byte.toInt()` sign-extends, so every value is masked to its field width
     * before shifting -- the failure mode that hand-shifting invites.
     */
    private fun writeStreamParams(out: ByteArray, at: Int, sampleRate: Int, channels: Int, bps: Int) {
        // 64 bits, read top-down as one value: 20 bits of sample rate, 3 of
        // channels-1, 5 of bps-1, then 36 bits of total samples.
        //
        // Built from the top down and then shifted LEFT by 36 to make room for
        // the sample count. Reserving that space by shifting is the part that
        // is easy to leave out, because the count is always 0 for a live stream
        // -- so a writer that ORs a zero in and stops emits only 28 bits, and
        // every field lands one byte low. A demuxer then reads sample rate 0 and
        // bits-per-sample 1 and rejects the stream with "invalid bps: 1", which
        // is exactly what the first version of this did. `writeU64` still
        // writes 8 bytes; the high 4 are zeros.
        var v = sampleRate.toLong() and 0xFFFFFL
        v = (v shl 3) or ((channels - 1).toLong() and 0x7L)
        v = (v shl 5) or ((bps - 1).toLong() and 0x1FL)
        v = (v shl 36) or (totalSamplesUnknown and 0xFFFFFFFFFL)
        writeU64(out, at, v)
    }

    /** A live stream has not ended, so it cannot know its total sample count. */
    private const val totalSamplesUnknown = 0L
}

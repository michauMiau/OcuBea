package com.ocubea.server

/**
 * Ogg page framing for Opus-over-HTTP, kept apart from [AudioEncoder] so it can be
 * tested on the JVM without a MediaCodec.
 *
 * Opus has no self-describing frame header the way ADTS does, so a bare Opus
 * byte stream is not decodable: it needs a container that says where the stream
 * starts and where it ends. Ogg is that container, and it is small enough to
 * write by hand -- which matters because MediaMuxer cannot express "this stream
 * ends when the client disconnects", which is the entire situation here.
 *
 * A live HTTP Opus stream therefore looks like:
 *
 *     page 0   BOS, OpusHead identification header
 *     page 1+  the packets
 *     page N   a zero-length page carrying the final granule position
 *
 * The final page is what makes a truncated stream still playable up to where it
 * was cut, and `stop` on a client disconnect is what writes it.
 *
 * This exists as its own object because an incorrect CRC here is not a visible
 * bug: the page still looks like an Ogg page, it just gets silently dropped by
 * every player. A JVM test that only checks the magic bytes would pass forever.
 */
object OggPage {

    /** Fixed serial: one Opus stream per process, so it need not vary. */
    const val SERIAL = 0x4F47_4241 // "OGBA"

    /**
     * CRC-32, polynomial 0x04c11db7, no bit reflection, no final xor.
     *
     * `toInt()` on the polynomial matters: 0x04C11DB7 does not fit in a signed
     * Int, and leaving it as a Long makes the xor below a Long operation that
     * silently truncates on every shift.
     */
    private val CRC_TABLE = IntArray(256) { i ->
        var c = i shl 24
        repeat(8) {
            // `toInt()` on both constants: a signed Int `shl` propagates the
            // sign bit instead of dropping it, so without these the table
            // entries above 0x7FFF_FFFF come out wrong.
            c = if (c and 0x8000_0000.toInt() != 0) {
                (c shl 1) xor 0x04C1_1DB7.toInt()
            } else {
                c shl 1
            } and 0xFFFF_FFFF.toInt()
        }
        c and 0xFFFF_FFFF.toInt()
    }

    /**
     * The 19-byte OpusHead a player requires on page 0.
     *
     * Layout is fixed by the Opus spec, and the byte count is the proof of it:
     *
     *     0..7   "OpusHead"
     *     8      version
     *     9      channel count
     *     10..11 pre-skip, little endian
     *     12..15 input sample rate, little endian
     *     16..17 output gain, little endian (0 == no gain)
     *     18     channel mapping family (0 == plain mono/stereo)
     *
     * That is 19 bytes exactly. Writing these through a running counter is how
     * the mapping family ended up at 17 in the first version of this: a header
     * that is one byte short, where the trailing field is silently dropped and
     * the player refuses the stream.
     */
    fun opusHead(channels: Int, preSkip: Int = 312): ByteArray {
        val head = ByteArray(19)
        "OpusHead".toByteArray(Charsets.US_ASCII).copyInto(head, 0)
        head[8] = 1 // version
        head[9] = channels.toByte()
        head[10] = (preSkip and 0xFF).toByte()
        head[11] = ((preSkip shr 8) and 0xFF).toByte()
        val rate = 48_000
        for (i in 0 until 4) head[12 + i] = ((rate shr (8 * i)) and 0xFF).toByte()
        head[16] = 0 // output gain low
        head[17] = 0 // output gain high
        head[18] = 0 // mapping family 0: no channel mapping table follows
        return head
    }

    /**
     * The comment header an Ogg Opus stream needs after `OpusHead`.
     *
     * Twelve bytes, not eight: the 8-byte `OpusTags` magic followed by a
     * 4-byte little-endian vendor comment length. An eight-byte version is a
     * silent truncation -- the demuxer reads four more bytes as the length,
     * takes them from the following page, and the whole header chain falls
     * apart. The first version of this function was exactly that, and the
     * symptom was ffmpeg reporting "Header processing failed" on a stream
     * whose CRCs were all valid.
     *
     * A zero length is the correct value, not a stub: this is a live
     * microphone, so there is no encoder name or version worth claiming, and
     * libopus writes zero tags for a bare stream too.
     */
    fun opusTags(): ByteArray {
        val out = ByteArray(12)
        "OpusTags".toByteArray(Charsets.US_ASCII).copyInto(out, 0)
        // Bytes 8..11 stay zero: vendor comment length, little endian, none.
        return out
    }

    /**
     * One Ogg page around [payload].
     *
     * [pageSeq] is the page counter and [granule] the sample position the page
     * ends at. The caller owns both, because the granule position has to
     * accumulate across pages and the BOS flag belongs to page 0 only.
     */
    fun page(
        payload: ByteArray,
        granule: Long,
        pageSeq: Int,
        flags: Int = if (pageSeq == 0) FLAG_BOS else 0
    ): ByteArray {
        require(payload.size <= 255) {
            "one Ogg page holds 255 payload bytes, got ${payload.size}; chunk the caller"
        }
        val lacing = payload.size
        val page = ByteArray(27 + 1 + payload.size)
        page[0] = 'O'.code.toByte(); page[1] = 'g'.code.toByte()
        page[2] = 'g'.code.toByte(); page[3] = 'S'.code.toByte()
        page[4] = 0 // stream structure version
        page[5] = flags.toByte()
        var i = 6
        // 0..7 and not 0..7 step 2: `step 2` writes only four bytes, which
        // shifts the serial, the sequence number and the CRC four bytes along
        // and produces a page that still starts with "OggS" and is still
        // silently dropped by every player.
        for (shift in 0..7) { // 64-bit little-endian granule position
            page[i++] = ((granule shr (8 * shift)) and 0xFF).toByte()
        }
        for (shift in 0..3) { // 32-bit little-endian serial
            page[i++] = ((SERIAL shr (8 * shift)) and 0xFF).toByte()
        }
        for (shift in 0..3) { // 32-bit little-endian page sequence
            page[i++] = ((pageSeq shr (8 * shift)) and 0xFF).toByte()
        }
        // 22..25 is the CRC, 26 the segment count, 27 the first lacing value.
        // These are written by absolute index, not through the running counter:
        // advancing the counter instead puts the segment count at 23, which
        // leaves a page that still begins with "OggS" and still gets dropped.
        page[22] = 0 // CRC placeholder, patched in below
        page[23] = 0
        page[24] = 0
        page[25] = 0
        page[26] = 1 // one segment table entry
        page[27] = lacing.toByte()
        payload.copyInto(page, 28)
        // The checksum field is four bytes at offset 22, zeroed while the
        // checksum is computed, then filled in. The Ogg spec stores it
        // LITTLE-endian, same as every other multi-byte field in the header.
        // An earlier version wrote it big-endian, which produced pages that
        // still began with "OggS", passed this file's own round-trip test --
        // because the test read the bytes back with the same wrong order --
        // and were rejected by every real player as "CRC mismatch!".
        val crc = crc(page, 0, page.size)
        for (shift in 0..3) {
            page[22 + shift] = ((crc shr (8 * shift)) and 0xFF).toByte()
        }
        return page
    }

    /** A zero-payload page carrying the final granule position. */
    fun endPage(granule: Long, pageSeq: Int): ByteArray = page(ByteArray(0), granule, pageSeq)

    /**
     * Ogg's CRC: shifted left, never reflected, no final xor. It is not the
     * zlib CRC-32 that ships with the JDK, so it cannot be borrowed.
     *
     * The `and 0xFFFF_FFFF.toInt()` on the table index and on the running value is the
     * whole trick. The spec describes a 32-bit register, but a Kotlin `Int` is
     * a signed 32-bit value: `shl` on it propagates the sign bit into bit 31
     * instead of dropping it, so a plain left shift leaves high bits that the
     * real register would have discarded. The stored value then disagrees with
     * any correct implementation, and the page is dropped without a word.
     */
    fun crc(data: ByteArray, offset: Int, length: Int): Int {
        var c = 0
        for (i in offset until offset + length) {
            val idx = (((c shr 24) and 0xFF) xor (data[i].toInt() and 0xFF))
            c = ((c shl 8) xor CRC_TABLE[idx]) and 0xFFFF_FFFF.toInt()
        }
        return c and 0xFFFF_FFFF.toInt()
    }

    const val FLAG_BOS = 0x02
    const val FLAG_EOS = 0x04
}

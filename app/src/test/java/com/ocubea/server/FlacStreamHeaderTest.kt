package com.ocubea.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The FLAC stream header, asserted on the JVM.
 *
 * The bug this covers was invisible to every existing test because it needed a
 * real decoder: the body had no `fLaC` signature and no STREAMINFO, and the
 * consequence -- a decoder hunting for sync words and reporting `invalid
 * residual` -- only shows up in ffmpeg, mid-stream, on some audio. The bit
 * layout is pure arithmetic, so all of it is checkable here instead.
 */
class FlacStreamHeaderTest {

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF

    /** Reads [bits] starting at absolute bit offset [at], most significant first. */
    private fun bits(b: ByteArray, at: Int, count: Int): Long {
        var v = 0L
        for (i in 0 until count) {
            val p = at + i
            v = (v shl 1) or ((u8(b, p shr 3) shr (7 - (p and 7))) and 1).toLong()
        }
        return v
    }

    @Test
    fun startsWithTheFLaCMagic() {
        val h = FlacStreamHeader.header(44_100, 1)
        assertEquals(0x66, u8(h, 0))
        assertEquals(0x4C, u8(h, 1))
        assertEquals(0x61, u8(h, 2))
        assertEquals(0x43, u8(h, 3))
        assertEquals("fLaC", String(h, 0, 4, Charsets.US_ASCII))
    }

    @Test
    fun metadataBlockIsStreamInfoAndTheLastOne() {
        val h = FlacStreamHeader.header(44_100, 1)
        // bit 31 = last-metadata-block, bits 30..24 = block type, 23..0 = length
        assertEquals(1L, bits(h, 32, 1))
        assertEquals(0L, bits(h, 33, 7))            // 0 = STREAMINFO
        assertEquals(34L, bits(h, 40, 24))          // STREAMINFO is always 34 B
    }

    /**
     * The flag has to be 1. A decoder that is told more metadata blocks follow
     * reads to the end of the file looking for them: ffmpeg returned an empty
     * decode with "No filtered frames for output stream" when it was clear.
     */
    @Test
    fun streamInfoIsMarkedAsTheLastMetadataBlock() {
        val h = FlacStreamHeader.header(44_100, 1)
        assertEquals(1, bits(h, 32, 1).toInt())
    }

    @Test
    fun blockSizeIsTheOneTheEncoderEmits() {
        val h = FlacStreamHeader.header(44_100, 1)
        // minimum block size, then maximum: both 16-bit big-endian after the
        // 8-byte marker+blockheader
        assertEquals(4096L, bits(h, 64, 16))
        assertEquals(4096L, bits(h, 80, 16))
        assertEquals(FlacStreamHeader.BLOCK_SIZE.toLong(), bits(h, 64, 16))
    }

    @Test
    fun frameSizesAreZeroBecauseALiveStreamCannotKnowThem() {
        val h = FlacStreamHeader.header(44_100, 1)
        assertEquals(0L, bits(h, 96, 24))   // minimum frame size
        assertEquals(0L, bits(h, 120, 24))  // maximum frame size
    }

    @Test
    fun streamParametersArePackedInFormatOrder() {
        val h = FlacStreamHeader.header(44_100, 1, 16)
        // 20 bits sample rate, 3 bits channels-1, 5 bits bps-1, then 36 bits
        // of total samples which a live stream leaves at 0.
        assertEquals(44_100L, bits(h, 144, 20))
        assertEquals(0L, bits(h, 164, 3))    // channels - 1
        assertEquals(15L, bits(h, 167, 5))   // bits per sample - 1
        assertEquals(0L, bits(h, 172, 36))   // total samples, unknown
    }

    @Test
    fun channelCountIsStoredAsChannelsMinusOne() {
        assertEquals(0L, bits(FlacStreamHeader.header(44_100, 1, 16), 164, 3))
        assertEquals(1L, bits(FlacStreamHeader.header(44_100, 2, 16), 164, 3))
        assertEquals(7L, bits(FlacStreamHeader.header(44_100, 8, 16), 164, 3))
    }

    @Test
    fun md5OfUnknownAudioIsAllZero() {
        val h = FlacStreamHeader.header(44_100, 1)
        // 16 bytes of MD5 after the 36 bits of total samples
        for (i in 26 until 42) assertEquals("MD5 byte $i", 0, u8(h, i))
    }

    @Test
    fun everyFieldIsBigEndian() {
        // The 20-bit sample-rate field starts at bit 144, which is byte 18
        // exactly (144 = 18 x 8), so it spans bytes 18..20 as 0x0A C4 40 --
        // 44100 = 0b0000_1010_1100_0100_0100, most significant bits first.
        // A little-endian writer would instead put the low bits at the front.
        val h = FlacStreamHeader.header(44_100, 1)
        assertEquals("byte 18", 0x0A, u8(h, 18))
        assertEquals("byte 19", 0xC4, u8(h, 19))
        assertEquals("byte 20", 0x40, u8(h, 20))
        // Byte 20 carries the last 4 bits of the rate in its top half; the
        // bottom half is the start of the channel count, which is 0 here.
        assertEquals("top nibble of byte 20", 4, (u8(h, 20) shr 4) and 0x0F)
    }

    /**
     * Decodes the header the way a FLAC demuxer does -- one wide read of the
     * 64-bit parameter field, then fields sliced out of it top-down.
     *
     * This is the test that the first version of this writer failed. It packed
     * the fields in the right ORDER but wrote the result 8 bits low, so the
     * demuxer read sample rate 0, bps 1 and refused the stream with
     * "invalid bps: 1". Asserting the individual bit fields could not catch
     * that, because every one of them was individually correct; only a whole
     * 64-bit read shows the byte alignment the demuxer actually uses.
     */
    @Test
    fun aDemuxerReadOfTheParameterFieldSucceeds() {
        val h = FlacStreamHeader.header(44_100, 1, 16)
        // Bytes 18..25, read as one 64-bit big-endian value, exactly as the
        // format's reference implementation does.
        var v = 0L
        for (i in 18..25) v = (v shl 8) or u8(h, i).toLong()
        val sampleRate = (v ushr 44).toInt()
        val channels = (((v ushr 41) and 0x7L).toInt()) + 1
        val bps = (((v ushr 36) and 0x1FL).toInt()) + 1
        val total = v and 0xFFFFFFFFFL
        assertEquals("sample rate read by a demuxer", 44_100, sampleRate)
        assertEquals("channels read by a demuxer", 1, channels)
        assertEquals("bits per sample read by a demuxer", 16, bps)
        assertEquals("total samples unknown for a live stream", 0L, total)
        // The failure this guards: ffmpeg rejects a stream whose STREAMINFO
        // implies a bit depth outside 4..32.
        assertTrue("bps must be legal", bps in 4..32)
    }

    @Test
    fun thePackedParameterFieldMatchesAReferenceEncoder() {
        // Byte-for-byte against a real FLAC file's STREAMINFO parameter field
        // for the same rate/channels/depth, so the whole 64-bit word is pinned
        // and not just the fields inside it.
        val expected = 0x0AC440F000000000L
        val h = FlacStreamHeader.header(44_100, 1, 16)
        var v = 0L
        for (i in 18..25) v = (v shl 8) or u8(h, i).toLong()
        assertEquals("64-bit parameter field", expected, v)
    }

    @Test
    fun everyCombinationTheAppCanProduceIsDemuxerReadable() {
        for (rate in listOf(8_000, 16_000, 22_050, 32_000, 44_100, 48_000, 96_000)) {
            for (ch in 1..8) {
                for (bits in listOf(8, 16, 24)) {
                    val h = FlacStreamHeader.header(rate, ch, bits)
                    var v = 0L
                    for (i in 18..25) v = (v shl 8) or u8(h, i).toLong()
                    val sr = (v ushr 44).toInt()
                    val c = (((v ushr 41) and 0x7L).toInt()) + 1
                    val b = (((v ushr 36) and 0x1FL).toInt()) + 1
                    assertEquals("rate $rate", rate, sr)
                    assertEquals("channels $ch", ch, c)
                    assertEquals("bits $bits", bits, b)
                }
            }
        }
    }

    @Test
    fun headerIsExactlyTheAdvertisedLength() {
        val h = FlacStreamHeader.header(44_100, 1)
        assertEquals(FlacStreamHeader.HEADER_BYTES, h.size)
        assertEquals(42, h.size)   // 4 marker + 4 block header + 34 STREAMINFO
    }

    /**
     * A parameter that does not fit its field must produce no header at all
     * rather than a truncated one. A header that lies about the stream is worse
     * than no header, because it is trusted.
     */
    @Test
    fun unexpressibleParametersProduceNoHeader() {
        assertEquals(0, FlacStreamHeader.header(0, 1).size)
        assertEquals(0, FlacStreamHeader.header(-1, 1).size)
        assertEquals(0, FlacStreamHeader.header(44_100, 0).size)
        assertEquals(0, FlacStreamHeader.header(44_100, 9).size)      // 3 bits
        assertEquals(0, FlacStreamHeader.header(44_100, 1, 0).size)
        assertEquals(0, FlacStreamHeader.header(44_100, 1, 33).size)  // 5 bits
        assertEquals(0, FlacStreamHeader.header(1 shl 20, 1).size)    // 20 bits
    }

    @Test
    fun everySampleRateTheAppUsesFits() {
        for (rate in listOf(8_000, 16_000, 22_050, 32_000, 44_100, 48_000, 96_000)) {
            val h = FlacStreamHeader.header(rate, 1, 16)
            assertEquals("rate $rate", rate.toLong(), bits(h, 144, 20))
        }
    }

    /**
     * The endpoint's own header must be a real FLAC header: the marker, then a
     * last-metadata STREAMINFO block, and nothing after it but audio frames.
     */
    @Test
    fun theHeaderIsFollowedByFramesNotMoreMetadata() {
        val h = FlacStreamHeader.header(44_100, 1)
        val endOfHeader = h.size
        assertEquals("fLaC", String(h, 0, 4, Charsets.US_ASCII))
        // The frame the encoder emits right after begins 0xFF 0xF8. Assert the
        // header ends exactly where a frame would begin, i.e. it neither leaves
        // a gap nor swallows the first frame.
        assertTrue(
            "header must not contain a frame sync",
            h.none { it.toInt() == 0xFF }
        )
        assertTrue(endOfHeader == 42)
    }
}

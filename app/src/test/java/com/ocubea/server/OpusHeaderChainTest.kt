package com.ocubea.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the Opus header chain.
 *
 * The bug these exist for: `OpusHead` alone is a legal identification header,
 * so the stream looked right -- valid CRCs, sequential page numbers, sane
 * granule positions -- and every check in this file passed. ffmpeg still
 * refused the whole file with "Header processing failed", because its ogg
 * demuxer walks the entire header chain before it opens a stream, ran off the
 * end of the first page, and gave up. Only a second header page makes the
 * difference, and only a decoder notices.
 *
 * So the tests below assert the whole chain, in order, and a decoder-shaped
 * invariant: every page of the header must carry the same serial, and the
 * sequence numbers must be consecutive from zero.
 */
class OpusHeaderChainTest {

    @Test
    fun `opusTags is the magic plus a zero-length vendor comment`() {
        val tags = OggPage.opusTags()
        // 12, not 8: magic is 8 bytes and the vendor comment length is 4 more.
        // An 8-byte version silently truncates and the demuxer reads its
        // length out of the next page.
        assertEquals(12, tags.size)
        assertEquals("OpusTags", String(tags, 0, 8, Charsets.US_ASCII))
        // Bytes 8..11 are the little-endian vendor comment length. Zero means
        // there is no vendor string, which is the correct answer for a live
        // microphone: there is no encoder name or version worth claiming.
        assertEquals(0, tags[8].toInt() and 0xFF)
        assertEquals(0, tags[9].toInt() and 0xFF)
        assertEquals(0, tags[10].toInt() and 0xFF)
        assertEquals(0, tags[11].toInt() and 0xFF)
    }

    @Test
    fun `opusHead is 19 bytes with a sane pre-skip`() {
        val head = OggPage.opusHead(channels = 1)
        assertEquals(19, head.size)
        assertEquals("OpusHead", String(head, 0, 8, Charsets.US_ASCII))
        assertEquals(1, head[8].toInt())      // version
        assertEquals(1, head[9].toInt())      // mono
        // Pre-skip little endian. Verified against a libopus-produced file:
        // the same 0x38 0x01 pair, so 312 is what a real encoder writes.
        assertEquals(312, (head[10].toInt() and 0xFF) or ((head[11].toInt() and 0xFF) shl 8))
        // Input sample rate 48000 little endian.
        assertEquals(48_000, (head[12].toInt() and 0xFF) or
            ((head[13].toInt() and 0xFF) shl 8) or
            ((head[14].toInt() and 0xFF) shl 16) or
            ((head[15].toInt() and 0xFF) shl 24))
        assertEquals(0, head[18].toInt())     // channel mapping family 0
    }

    @Test
    fun `a page carrying only OpusTags is a well formed Ogg page`() {
        val page = OggPage.page(OggPage.opusTags(), granule = 0, pageSeq = 1)
        assertEquals('O'.code.toByte(), page[0])
        assertEquals('g'.code.toByte(), page[1])
        assertEquals('g'.code.toByte(), page[2])
        assertEquals('S'.code.toByte(), page[3])
        assertEquals(0, page[4].toInt())      // stream structure version
        // Page 1 of the header chain is neither BOS nor EOS.
        assertEquals(0, page[5].toInt())
        assertEquals(1, page[18].toInt() or (page[19].toInt() shl 8) or
            (page[20].toInt() shl 16) or (page[21].toInt() shl 24))
        // One lacing value of 12, describing the 12-byte payload.
        assertEquals(1, page[26].toInt())
        assertEquals(12, page[27].toInt())
    }

    @Test
    fun `header pages share one serial and consecutive sequence numbers`() {
        // Built by hand the way AudioEncoder.streamHeader() does it, because
        // that pairing is the actual contract: BOS on 0, OpusTags on 1.
        val head = OggPage.page(OggPage.opusHead(1), granule = 0, pageSeq = 0)
        val tags = OggPage.page(OggPage.opusTags(), granule = 0, pageSeq = 1)

        fun serialOf(p: ByteArray): Long =
            (p[14].toLong() and 0xFF) or ((p[15].toLong() and 0xFF) shl 8) or
                ((p[16].toLong() and 0xFF) shl 16) or ((p[17].toLong() and 0xFF) shl 24)

        // A demuxer treats two streams with different serials as two logical
        // bitstreams, so a mismatch here is indistinguishable from garbage.
        assertEquals(serialOf(head), serialOf(tags))
        // A duplicate page number was the earlier duplicate-seq bug: the header
        // consumed 0 and then the first audio page claimed 0 as well.
        assertEquals(0, (head[18].toInt() and 0xFF) or (head[19].toInt() shl 8) or
            (head[20].toInt() shl 16) or (head[21].toInt() shl 24))
        assertEquals(1, (tags[18].toInt() and 0xFF) or (tags[19].toInt() shl 8) or
            (tags[20].toInt() shl 16) or (tags[21].toInt() shl 24))
    }

    @Test
    fun `the chain parses as two complete pages back to back`() {
        val head = OggPage.page(OggPage.opusHead(1), granule = 0, pageSeq = 0)
        val tags = OggPage.page(OggPage.opusTags(), granule = 0, pageSeq = 1)
        val stream = head + tags

        var off = 0
        var count = 0
        while (off + 27 <= stream.size) {
            assertEquals('O'.code.toByte(), stream[off])
            assertEquals('S'.code.toByte(), stream[off + 3])
            val nseg = stream[off + 26].toInt() and 0xFF
            var body = 0
            for (i in 0 until nseg) body += stream[off + 27 + i].toInt() and 0xFF
            off += 27 + nseg + body
            count++
        }
        // The demuxer needs to land exactly on the end of the chain. If it does
        // not, it reads into audio packets as if they were headers, which is
        // what produced "Header processing failed".
        assertEquals(2, count)
        assertEquals(stream.size, off)
        assertTrue("OpusTags must be findable in the chain",
            String(stream, Charsets.ISO_8859_1).contains("OpusTags"))
    }
}

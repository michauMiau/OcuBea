package com.ocubea.server

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ogg framing is the one part of Opus-over-HTTP that cannot be checked by looking
 * at the bytes: a wrong CRC still produces something that starts with "OggS", and
 * every player drops the page silently. So these tests verify the CRC against an
 * independent implementation, and verify the page against a hand-computed header.
 *
 * The reference CRC below is transcribed from the Ogg specification's own
 * pseudocode, written longhand rather than copied from [OggPage], so that a bug in
 * one of them does not quietly pass the other.
 */
class OggPageTest {

    /** Transcribed from the Ogg spec: left-shifting, non-reflected, no final xor. */
    private fun referenceCrc(bytes: ByteArray): Int {
        var crc = 0
        for (b in bytes) {
            crc = crc xor (b.toInt() shl 24)
            repeat(8) {
                crc = if (crc and 0x8000_0000.toInt() != 0) {
                    (crc shl 1) xor 0x04c1_1db7
                } else {
                    crc shl 1
                }
            }
        }
        return crc and 0xFFFF_FFFF.toInt()
    }

    @Test
    fun `crc matches the specification pseudocode`() {
        val cases = listOf(
            ByteArray(0),
            byteArrayOf(0),
            byteArrayOf(-1, -1, -1, -1),
            "OggS".toByteArray(),
            ByteArray(27) { it.toByte() },
            ByteArray(58) { (it * 7).toByte() },
        )
        for (c in cases) {
            assertEquals(
                "crc mismatch for ${c.size} bytes",
                referenceCrc(c),
                OggPage.crc(c, 0, c.size)
            )
        }
    }

    @Test
    fun `crc is not the jdk crc32`() {
        // If someone "simplifies" Ogg's CRC into java.util.zip.CRC32, this is
        // the test that catches it, because the two differ on the very first
        // non-empty input.
        val data = "OggS".toByteArray()
        val jdk = java.util.zip.CRC32().apply { update(data) }.value.toInt()
        assertNotEquals(jdk, OggPage.crc(data, 0, data.size))
    }

    @Test
    fun `page starts with OggS and a zero version`() {
        val p = OggPage.page(byteArrayOf(1, 2, 3), granule = 0, pageSeq = 0)
        assertEquals('O'.code.toByte(), p[0])
        assertEquals('g'.code.toByte(), p[1])
        assertEquals('g'.code.toByte(), p[2])
        assertEquals('S'.code.toByte(), p[3])
        assertEquals(0, p[4].toInt())
    }

    @Test
    fun `first page carries BOS and later pages do not`() {
        assertEquals(
            OggPage.FLAG_BOS,
            OggPage.page(byteArrayOf(9), 0, pageSeq = 0)[5].toInt()
        )
        assertEquals(0, OggPage.page(byteArrayOf(9), 0, pageSeq = 1)[5].toInt())
    }

    @Test
    fun `granule position is little endian across all eight bytes`() {
        val g = 0x0102_0304_0506_0708L
        val p = OggPage.page(byteArrayOf(0), g, pageSeq = 0)
        val actual = (0 until 8).fold(0L) { acc, i ->
            acc or ((p[6 + i].toLong() and 0xFF) shl (8 * i))
        }
        assertEquals(g, actual)
    }

    @Test
    fun `serial number is little endian and constant`() {
        val p = OggPage.page(byteArrayOf(0), 0, pageSeq = 0)
        val actual = (0 until 4).fold(0) { acc, i -> acc or ((p[14 + i].toInt() and 0xFF) shl (8 * i)) }
        assertEquals(OggPage.SERIAL, actual)
    }

    @Test
    fun `page sequence is little endian`() {
        val seq = 0x0A0B0C0D
        val p = OggPage.page(byteArrayOf(0), 0, pageSeq = seq)
        val actual = (0 until 4).fold(0) { acc, i -> acc or ((p[18 + i].toInt() and 0xFF) shl (8 * i)) }
        assertEquals(seq, actual)
    }

    @Test
    fun `segment count and lacing describe the payload`() {
        val p = OggPage.page(ByteArray(10) { 1 }, 0, pageSeq = 0)
        assertEquals(1, p[26].toInt()) // one segment-table entry
        assertEquals(10, p[27].toInt()) // lacing value == payload length
        assertEquals(28 + 10, p.size)
    }

    @Test
    fun `payload survives the round trip`() {
        val payload = ByteArray(200) { (it * 3 + 1).toByte() }
        val p = OggPage.page(payload, 123, pageSeq = 4)
        val got = p.copyOfRange(28, p.size)
        assertTrue(payload.contentEquals(got))
    }

    @Test
    fun `stored crc matches a recomputation over the page with the field zeroed`() {
        val p = OggPage.page(ByteArray(30) { 0x5A }, 999, pageSeq = 7)
        // The CRC is stored little-endian, so byte 22 is the LOW byte: fold
        // with the first byte as the least significant. Reading it the other
        // way round yields the bit-reversed value 0xCFD01AE4 instead of
        // 0xE41AD0CF, and the two look like a genuine mismatch.
        val stored = (0 until 4).fold(0) { acc, i ->
            acc or ((p[25 - i].toInt() and 0xFF) shl (8 * i))
        }
        val zeroed = p.copyOf()
        for (i in 22..25) zeroed[i] = 0
        assertEquals(OggPage.crc(zeroed, 0, zeroed.size), stored)
    }

    @Test
    fun `a single flipped payload bit invalidates the crc`() {
        // This is the property that makes the whole thing safe: if a mutation
        // bug corrupted payload bytes without touching the CRC field, players
        // would reject the page. So the CRC has to cover the payload.
        val p = OggPage.page(byteArrayOf(0x00, 0x7F, 0x11), 0, pageSeq = 0)
        val stored = (0 until 4).fold(0) { acc, i -> acc or ((p[22 + i].toInt() and 0xFF) shl (8 * i)) }
        val mutated = p.copyOf()
        mutated[28 + 1] = (mutated[28 + 1].toInt() xor 0x01).toByte()
        assertNotEquals(stored, OggPage.crc(mutated, 0, mutated.size))
    }

    @Test
    fun `end page carries the final granule and no payload`() {
        val p = OggPage.endPage(granule = 96_000, pageSeq = 12)
        assertEquals(28, p.size)
        assertEquals(0, p[27].toInt()) // zero lacing value == no payload
        val granule = (0 until 8).fold(0L) { acc, i -> acc or ((p[6 + i].toLong() and 0xFF) shl (8 * i)) }
        assertEquals(96_000L, granule)
    }

    @Test
    fun `opus head is the 19 byte header players require`() {
        val h = OggPage.opusHead(channels = 1)
        assertEquals(19, h.size)
        assertEquals("OpusHead", String(h, 0, 8, Charsets.US_ASCII))
        assertEquals(1, h[8].toInt()) // version
        assertEquals(1, h[9].toInt()) // channel count
        val preSkip = (h[10].toInt() and 0xFF) or ((h[11].toInt() and 0xFF) shl 8)
        assertEquals(312, preSkip)
        val rate = (0 until 4).fold(0) { acc, i -> acc or ((h[12 + i].toInt() and 0xFF) shl (8 * i)) }
        assertEquals(48_000, rate)
        // Every trailing byte is asserted, not just the last one. Asserting only
        // h[18] let a mutation that moved the mapping family from 18 to 17 pass
        // unnoticed, which is the same off-by-one that produced the one-byte-
        // short header in the first place. (message first, then expected and
        // actual -- assertEquals on the JVM has no (msg, Object, Object) form
        // for a three-argument Int comparison.)
        assertEquals("output gain low", 0, h[16].toInt())
        assertEquals("output gain high", 0, h[17].toInt())
        assertEquals("mapping family, the last byte", 0, h[18].toInt())
    }

    @Test
    fun `every byte of the opus head is the value the spec asks for`() {
        // Byte-exact, so a field moving anywhere in the header is caught. The
        // compact assertion above cannot do this: h[17] happens to be zero for
        // both "output gain high" and "mapping family", so a swap survives.
        val h = OggPage.opusHead(channels = 1, preSkip = 312)
        val magic = "OpusHead".toByteArray(Charsets.US_ASCII)
        val expected = ByteArray(19)
        magic.copyInto(expected, 0)
        expected[8] = 1
        expected[9] = 1
        expected[10] = 0x38 // pre-skip 312, low byte
        expected[11] = 0x01 // pre-skip, high byte
        expected[12] = 0x80.toByte() // 48000, low byte
        expected[13] = 0xBB.toByte()
        expected[14] = 0x00 // 48000, high bytes
        expected[15] = 0x00
        expected[16] = 0 // output gain, low
        expected[17] = 0 // output gain, high
        expected[18] = 0 // mapping family
        assertArrayEquals("OpusHead must match the spec byte for byte", expected, h)
    }

    @Test
    fun `opus head followed by packets forms a valid first page`() {
        val page = OggPage.page(OggPage.opusHead(1), granule = 0, pageSeq = 0)
        assertEquals(OggPage.FLAG_BOS, page[5].toInt())
        assertEquals(28 + 19, page.size)
        assertEquals("OpusHead", String(page, 28, 8, Charsets.US_ASCII))
    }

    @Test
    fun `a payload over one page is rejected rather than silently truncated`() {
        val tooBig = ByteArray(300)
        val failed = try {
            OggPage.page(tooBig, 0, pageSeq = 0)
            false
        } catch (e: IllegalArgumentException) {
            true
        }
        assertTrue("a 300 byte payload must not be silently truncated", failed)
    }
}

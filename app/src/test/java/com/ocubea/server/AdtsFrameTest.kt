package com.ocubea.server

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADTS is the container that makes `audio/aac` decodable, and every field in it
 * is a bit position rather than a byte. The tests below decode the produced
 * header the way a demuxer does and check the fields by name, so a wrong shift
 * shows up as a named field being wrong instead of a sync word that happens to
 * be right.
 */
class AdtsFrameTest {

    private fun field(h: ByteArray, fromBit: Int, count: Int): Int {
        var v = 0
        for (i in 0 until count) {
            val bit = fromBit + i
            val b = (h[bit / 8].toInt() shr (7 - (bit % 8))) and 1
            v = (v shl 1) or b
        }
        return v
    }

    @Test
    fun `syncword is twelve one bits`() {
        val h = AdtsFrame.frame(ByteArray(10) { 0x55 }, 48000, 1)
        assertEquals(0xFFF, field(h, 0, 12))
    }

    @Test
    fun `profile is aac lc`() {
        val h = AdtsFrame.frame(ByteArray(10) { 0x55 }, 48000, 1)
        // profile occupies bits 16-17, straight after the 12-bit syncword, the
        // MPEG-4 bit and the 2-bit layer field
        assertEquals(1, field(h, 16, 2))
    }

    @Test
    fun `layer is zero`() {
        val h = AdtsFrame.frame(ByteArray(10) { 0x55 }, 48000, 1)
        assertEquals(0, field(h, 13, 2))
    }

    @Test
    fun `protection absent so no crc follows`() {
        val h = AdtsFrame.frame(ByteArray(10) { 0x55 }, 48000, 1)
        assertEquals(1, field(h, 15, 1))
    }

    @Test
    fun `sample rate index matches the rate`() {
        val h = AdtsFrame.frame(ByteArray(10) { 0x55 }, 48000, 1)
        assertEquals(3, field(h, 18, 4))
    }

    @Test
    fun `channel configuration is written`() {
        assertEquals(1, field(AdtsFrame.frame(ByteArray(4), 48000, 1), 23, 3))
        assertEquals(2, field(AdtsFrame.frame(ByteArray(4), 48000, 2), 23, 3))
    }

    @Test
    fun `channel config uses all three bits for stereo plus layout`() {
        // 3 bits total, so a value above 4 must survive: the high bit of the
        // channel config sits between the private bit and the low two, which is
        // exactly where a sign-extending or() breaks it.
        assertEquals(6, field(AdtsFrame.frame(ByteArray(4), 48000, 6), 23, 3))
    }

    @Test
    fun `frame length includes the seven header bytes`() {
        val payload = ByteArray(100) { 0x11 }
        val h = AdtsFrame.frame(payload, 48000, 1)
        assertEquals(100 + 7, field(h, 30, 13))
        assertEquals(107, h.size)
    }

    @Test
    fun `frame length survives the 13 bit boundary`() {
        // 8191 is the largest encodable total; 8192 must be refused rather than
        // written truncated, because a wrong length desynchronises the decoder.
        val ok = AdtsFrame.frame(ByteArray(8191 - 7), 48000, 1)
        assertEquals(8191, field(ok, 30, 13))
        val tooBig = AdtsFrame.frame(ByteArray(8192 - 7), 48000, 1)
        assertEquals(8192 - 7, tooBig.size)
    }

    @Test
    fun `payload bytes are preserved exactly`() {
        val payload = ByteArray(64) { (it * 7).toByte() }
        val out = AdtsFrame.frame(payload, 48000, 1)
        assertArrayEquals(payload, out.copyOfRange(7, out.size))
    }

    @Test
    fun `an unexpressible sample rate is not written as a wrong index`() {
        val payload = ByteArray(20) { 0x33 }
        val out = AdtsFrame.frame(payload, 44101, 1)
        assertArrayEquals("no index for this rate, so no header", payload, out)
    }

    @Test
    fun `index lookup rejects unknown rates`() {
        assertEquals(3, AdtsFrame.indexFor(48000))
        assertEquals(4, AdtsFrame.indexFor(44100))
        assertEquals(null, AdtsFrame.indexFor(44101))
        assertEquals(null, AdtsFrame.indexFor(0))
    }

    @Test
    fun `every defined rate has a distinct index`() {
        val rates = listOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050,
            16000, 12000, 11025, 8000, 7350)
        val indices = rates.mapNotNull { AdtsFrame.indexFor(it) }
        assertEquals(rates.size, indices.size)
        assertEquals(rates.size, indices.toSet().size)
        assertTrue(indices.all { it in 0..12 })
    }
}

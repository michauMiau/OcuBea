package com.ocubea.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * H264 Annex-B parsing and RTP packetization, against a device's real bytes.
 *
 * Every fixture here was read off a Sony F3311 running this app. That matters:
 * the RTP path was wrong for hours with a plausible SDP, 200s on every request,
 * valid RTP headers and payloads full of H264-looking bytes -- and ffprobe
 * decoding nothing at all. Every check written against the server's own replies
 * passed, because none of them asked a decoder anything. These tests ask the
 * parsing and packetization questions directly, on the layout the encoder
 * actually produces.
 */
class H264RtpTest {

    /** The codec-config buffer this device emits: SPS then PPS, 4-byte start codes. */
    private val deviceCodecConfig = byteArrayOf(
        0x00, 0x00, 0x00, 0x01,
        0x67, 0x42, 0x00, 0x29, 0x8d.toByte(), 0x8d.toByte(), 0x40, 0x28,
        0x02, 0xdd.toByte(), 0x00, 0xf0.toByte(), 0x88.toByte(), 0x45, 0x38,
        0x00, 0x00, 0x00, 0x01,
        0x68, 0xca.toByte(), 0x43, 0xc8.toByte(),
    )

    private val spsHex = "674200298d8d402802dd00f0884538"
    private val ppsHex = "68ca43c8"

    private fun hex(b: ByteArray): String =
        b.joinToString("") { "%02x".format(it) }

    private fun decodeBase64(s: String): ByteArray {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val out = java.io.ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (ch in s) {
            if (ch == '=') break
            val digit = alphabet.indexOf(ch)
            check(digit >= 0) { "not base64: $ch" }
            // Accumulate the 6-bit value itself. Storing indexOf() into a
            // StringBuilder and then reading it back as a char turns 'B' into
            // '1' and yields -47 -- a broken decoder in the test that reports the
            // encoder as broken, which is the direction that wastes an afternoon.
            buffer = (buffer shl 6) or digit
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buffer shr bits) and 0xFF)
            }
        }
        return out.toByteArray()
    }

    private fun unhex(s: String): ByteArray {
        val out = ByteArray(s.length / 2)
        for (i in out.indices) {
            out[i] = s.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return out
    }

    @Test
    fun `finds both parameter sets in the device codec config`() {
        val nals = H264Rtp.annexBNals(deviceCodecConfig)
        assertEquals(2, nals.size)
        assertEquals(7, deviceCodecConfig[nals[0][0]].toInt() and 0x1F)
        assertEquals(8, deviceCodecConfig[nals[1][0]].toInt() and 0x1F)
        assertEquals(15, nals[0][1] - nals[0][0])
        // The PPS is 4 bytes. Truncating it to `68ca` produced a file ffmpeg
        // rejected with "reference overflow (pps)", which is worse than omitting
        // it: the parameter sets looked present and were unusable.
        assertEquals(4, nals[1][1] - nals[1][0])
    }

    @Test
    fun `sprop carries both parameter sets and decodes back to the device bytes`() {
        val sprop = H264Rtp.spsPpsBase64(deviceCodecConfig)
        assertNotNull(sprop)
        val parts = sprop!!.split(",")
        assertEquals(2, parts.size)
        // Decoded by hand, not with android.util.Base64: in the android.jar that
        // unit tests compile against it is a stub that throws on a JVM, so a test
        // using it reports a broken encoder when the encoder is fine.
        assertEquals(spsHex, hex(decodeBase64(parts[0])))
        assertEquals(ppsHex, hex(decodeBase64(parts[1])))
    }

    @Test
    fun `sprop is absent rather than empty when there are no parameter sets`() {
        // A codec config that exists but carries neither is a real state, and an
        // empty sprop attribute would be worse than none.
        assertNull(H264Rtp.spsPpsBase64(ByteArray(0)))
        val notParameterSets = unhex("000000010965888000200000")
        assertNull(H264Rtp.spsPpsBase64(notParameterSets))
    }

    @Test
    fun `parameter sets come back as valid annex-b with a start code each`() {
        val prefix = H264Rtp.parameterSetsAnnextB(deviceCodecConfig)
        // Two units, each introduced by its own 4-byte start code.
        assertEquals(
            "00000001674200298d8d402802dd00f08845380000000168ca43c8",
            hex(prefix),
        )
        val nals = H264Rtp.annexBNals(prefix)
        assertEquals(2, nals.size)
        assertEquals(7, prefix[nals[0][0]].toInt() and 0x1F)
        assertEquals(8, prefix[nals[1][0]].toInt() and 0x1F)
    }

    @Test
    fun `a prefix without its own start code swallows the next NAL`() {
        // The regression this guards: with no start code between the PPS and the
        // IDR, `68ca43c8` + `65888000` parses as one 8-byte type-8 NAL and the
        // IDR is never sent at all.
        // One start code in front, none between the units -- which is exactly what
        // the first implementation produced when it prepended four zero bytes
        // instead of 00 00 00 01.
        val broken = unhex("00000001" + spsHex + ppsHex + "6588800020000027")
        val nals = H264Rtp.annexBNals(broken)
        assertEquals(1, nals.size)
        assertEquals(7, broken[nals[0][0]].toInt() and 0x1F)
    }

    @Test
    fun `a small access unit is one packet carrying its own header`() {
        val au = unhex("0000000165888000" + "20000027")
        val packets = H264Rtp.packetize(au)
        assertEquals(1, packets.size)
        val p = packets[0]
        assertEquals(5, p.nalType)
        assertTrue(p.start)
        assertTrue(p.end)
        assertTrue(p.marker)
        // Not FU-A, so the payload starts with the original NAL header.
        val payload = p.payload()
        assertEquals(5, payload[0].toInt() and 0x1F)
    }

    @Test
    fun `a large NAL becomes FU-A fragments with start and end flags`() {
        // An IDR long enough to need splitting, which is every real one. The
        // header byte is 0x65 (type 5, IDR) and the body is filler.
        val body = ByteArray(60_000) { 0xAB.toByte() }
        body[0] = 0x65
        val au = H264Rtp.START_CODE + body
        val packets = H264Rtp.packetize(au)
        assertTrue("expected several fragments", packets.size > 3)

        // Every fragment announces FU-A. A fragment that carried the raw type
        // instead is what made a decoder read the middle of a picture as a NAL
        // header and fail with "non-existing PPS 0 referenced".
        for (p in packets) {
            val payload = p.payload()
            assertEquals(
                "fragment did not announce FU-A",
                H264Rtp.FU_A_TYPE,
                payload[0].toInt() and 0x1F,
            )
        }

        // The first sets S, the last sets E, and both recover the real type.
        assertTrue(packets.first().start)
        assertTrue(packets.first().payload()[1].toInt() and 0x80 != 0)
        assertTrue(packets.last().end)
        assertTrue(packets.last().payload()[1].toInt() and 0x40 != 0)
        assertEquals(5, packets.last().nalType)

        // Exactly one marker, on the last packet: it means a picture ends here.
        assertEquals(1, packets.count { it.marker })
        assertTrue(packets.last().marker)
    }

    @Test
    fun `fragments reassemble back into the original NAL`() {
        val original = ByteArray(50_000) { (it % 251).toByte() }
        val au = H264Rtp.START_CODE + original
        val packets = H264Rtp.packetize(au)

        // Undo FU-A the way a decoder does, then compare with the source.
        val rebuilt = java.io.ByteArrayOutputStream()
        for (p in packets) {
            val payload = p.payload()
            if ((payload[0].toInt() and 0x1F) == H264Rtp.FU_A_TYPE) {
                rebuilt.write(payload, 2, payload.size - 2)
            } else {
                rebuilt.write(payload, 1, payload.size - 1)
            }
        }
        assertEquals(original.size, rebuilt.size())
        val back = rebuilt.toByteArray()
        for (i in original.indices) {
            assertEquals("byte $i differs", original[i], back[i])
        }
    }

    @Test
    fun `every packet stays inside the mtu budget`() {
        val au = H264Rtp.START_CODE + ByteArray(60_000) { 0x5A }
        val packets = H264Rtp.packetize(au, mtuBytes = 1400, rtpHeaderBytes = 12)
        for (p in packets) {
            // payload + RTP header must fit, or the packet is IP-fragmented.
            assertTrue(
                "payload ${p.payload().size} + 12 exceeded 1400",
                p.payload().size + 12 <= 1400,
            )
        }
    }

    @Test
    fun `an access unit with several NALs marks only the last`() {
        val au = unhex(
            "00000001674200298d8d402802dd00f0884538" +
                "0000000168ca43c8" +
                "0000000165888000",
        )
        val packets = H264Rtp.packetize(au)
        assertEquals(3, packets.size)
        assertEquals(7, packets[0].nalType)
        assertEquals(8, packets[1].nalType)
        assertEquals(5, packets[2].nalType)
        // SPS and PPS are not the end of a picture; only the IDR is. This is the
        // property that matters, and it holds for the reason `&& isLast` is there:
        // the marker must not depend on being the last FRAGMENT, only on being
        // the last fragment of the last NAL.
        assertTrue(!packets[0].marker)
        assertTrue(!packets[1].marker)
        assertTrue(packets[2].marker)
        // And a fragment in the middle of a large NAL is never a picture boundary.
        val big = ByteArray(40_000) { 0x5A }
        big[0] = 0x65
        val fragmented = H264Rtp.packetize(H264Rtp.START_CODE + big)
        assertEquals(1, fragmented.count { it.marker })
        assertTrue(fragmented.last().marker)
        assertTrue(!fragmented.first().marker)
    }

    @Test
    fun `a three-byte start code is recognised as three`() {
        // Legal Annex-B, and this device does not emit it -- which is why the
        // first implementation assumed 4 and got the length wrong for it.
        val threeByte = unhex("00000167420029")
        assertEquals(3, H264Rtp.startCodeLength(threeByte, 0))
        val nals = H264Rtp.annexBNals(threeByte)
        assertEquals(1, nals.size)
        assertEquals(7, threeByte[nals[0][0]].toInt() and 0x1F)
    }

    @Test
    fun `a buffer with no start code yields no NALs instead of guessing`() {
        // Not annex-b at all. Guessing a start code here would silently drop
        // three bytes of payload at every position examined.
        val notAnnexB = ByteArray(64) { 0x11 }
        assertEquals(emptyList<IntArray>(), H264Rtp.annexBNals(notAnnexB))
        assertEquals(0, H264Rtp.startCodeLength(notAnnexB, 0))
    }

    @Test
    fun `a final short NAL keeps all of its bytes`() {
        // The loop condition that dropped this tail was `<= size - 3`. A PPS
        // shorter than that came out as `68ca` instead of `68ca43c8`, and ffmpeg
        // reported "reference overflow (pps)" while decoding nothing.
        val cfg = unhex("0000000168ca43c8")
        val nals = H264Rtp.annexBNals(cfg)
        assertEquals(1, nals.size)
        assertEquals(ppsHex, hex(cfg.copyOfRange(nals[0][0], nals[0][1])))
    }
}

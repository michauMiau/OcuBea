package com.ocubea.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the RTP packetizer against a header byte that is emitted twice.
 *
 * This exists because a passing test elsewhere did not catch it: the server
 * answered DESCRIBE/SETUP/PLAY with 200 OK and pushed 2.4 MB of RTP, and every
 * single frame failed to decode. The payload carried `67 67 42 00 ...` for the
 * SPS -- the NAL header twice -- because payload() writes `indicator` itself
 * and the body still held a copy. ffmpeg read the second byte as picture data,
 * so each access unit came out as nal_unit_type 0 and reported "illegal POC
 * type 5" and "sps_id 1 out of range".
 *
 * The HLS path was clean at the same moment, from the same encoder, so nothing
 * in the codec could have found this. Only the RTP framing was wrong.
 *
 * Every assertion below reads what a decoder would read: the reassembled
 * Annex-B stream, rebuilt from the payloads this class produces.
 */
class H264RtpHeaderOnceTest {

    private val startCode = byteArrayOf(0, 0, 0, 1)

    /** An SPS, a PPS and an IDR, as an encoder would emit them. */
    private fun accessUnit(): ByteArray {
        val sps = byteArrayOf(0x67, 0x42, 0x00, 0x29, 0x8d.toByte(), 0x8d.toByte(), 0x40, 0x28)
        val pps = byteArrayOf(0x68, 0xca.toByte(), 0x43, 0xc8.toByte())
        val idr = ByteArray(3000) { (it % 251).toByte() }
        idr[0] = 0x65
        return byteArrayOf(*startCode, *sps, *startCode, *pps, *startCode, *idr)
    }

    /** Reassembles RTP payloads back into Annex-B, the way a decoder would. */
    private fun reassemble(accessUnit: ByteArray, mtu: Int): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        for (packet in H264Rtp.packetize(accessUnit, mtuBytes = mtu)) {
            val payload = packet.payload()
            if (packet.fragment) {
                val fu = payload[1].toInt() and 0xFF
                val start = fu and 0x80 != 0
                val end = fu and 0x40 != 0
                val body = payload.copyOfRange(2, payload.size)
                if (start) out += payload.copyOfRange(0, 1) + body
                else if (out.isNotEmpty()) out[out.size - 1] += body
                else return emptyList()
                if (end && !start) { /* closed above */ }
            } else {
                out += payload
            }
        }
        return out
    }

    /** Splits an Annex-B stream into its NAL units, headers included. */
    private fun nals(stream: ByteArray): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        var i = 0
        while (i < stream.size) {
            val j = indexOfStartCode(stream, i)
            if (j < 0) break
            val k = indexOfStartCode(stream, j + 4)
            if (j + 4 < stream.size && (k < 0 || k > j + 4)) {
                out += stream.copyOfRange(j + 4, if (k < 0) stream.size else k)
            }
            i = j + 4
        }
        return out
    }

    private fun indexOfStartCode(stream: ByteArray, from: Int): Int {
        for (i in from..stream.size - 4) {
            if (stream[i] == 0.toByte() && stream[i + 1] == 0.toByte() &&
                stream[i + 2] == 0.toByte() && stream[i + 3] == 1.toByte()
            ) return i
        }
        return -1
    }

    @Test
    fun `SPS header is emitted once when the NAL fits in one packet`() {
        val nals = reassemble(accessUnit(), mtu = 8000)
        val sps = nals.first { (it[0].toInt() and 0x1F) == 7 }
        // 67 67 would be the bug: the header written twice.
        assertEquals(0x67, sps[0].toInt() and 0xFF)
        assertEquals(0x42, sps[1].toInt() and 0xFF)
        assertEquals(
            "SPS payload must follow its header immediately",
            byteArrayOf(0x67.toByte(), 0x42, 0x00, 0x29, 0x8d.toByte(), 0x8d.toByte(), 0x40, 0x28)
                .toList(), sps.take(8).toList()
        )
    }

    @Test
    fun `PPS header is emitted once`() {
        val nals = reassemble(accessUnit(), mtu = 8000)
        val pps = nals.first { (it[0].toInt() and 0x1F) == 8 }
        assertEquals(0x68, pps[0].toInt() and 0xFF)
        assertEquals(
            byteArrayOf(0x68.toByte(), 0xca.toByte(), 0x43, 0xc8.toByte()).toList(),
            pps.take(4).toList()
        )
    }

    @Test
    fun `fragmented IDR reassembles to the exact original bytes`() {
        val au = accessUnit()
        val packets = H264Rtp.packetize(au, mtuBytes = 600)
        val idrFragments = packets.count { it.fragment && it.nalType == 5 }
        assertTrue("IDR must actually be fragmented at this MTU", idrFragments > 1)

        // The FU indicator carries NRI + type 28; the real type 5 is in the
        // FU header, which is what a decoder restores the NAL byte from.
        val f = packets.first { it.fragment && it.nalType == 5 }
        assertEquals(28, f.payload()[0].toInt() and 0x1F)
        assertEquals(5, f.payload()[1].toInt() and 0x1F)

        // Reassembled IDR body, minus the 4-byte start code, must equal the
        // original IDR exactly. A duplicated header would shift this by one.
        val stream = buildStream(au, mtu = 600)
        val units = nals(stream)
        val idr = units.first { (it[0].toInt() and 0x1F) == 5 }
        val originalIdr = au.copyOfRange(au.size - 3000, au.size)
        assertEquals("fragmented IDR must survive packetize + reassemble intact",
            originalIdr.toList(), idr.toList())
    }

    @Test
    fun `single packet NAL keeps its payload byte for byte`() {
        val au = accessUnit()
        val stream = buildStream(au, mtu = 8000)
        val units = nals(stream)
        assertEquals("SPS, PPS and IDR must all come back", 3, units.size)
        val sps = units[0]
        assertEquals(0x67, sps[0].toInt() and 0xFF)
        assertEquals(8, sps.size)
        val idr = units[2]
        assertEquals(0x65, idr[0].toInt() and 0xFF)
        assertEquals(3000, idr.size)
    }

    @Test
    fun `every NAL type in the stream is a real H264 type`() {
        val stream = buildStream(accessUnit(), mtu = 1400)
        val types = nals(stream).map { it[0].toInt() and 0x1F }
        assertTrue(
            "nal_unit_type 0 is not an access unit; it means a shifted stream. Got $types",
            types.none { it == 0 }
        )
        assertEquals(listOf(7, 8, 5), types)
    }

    @Test
    fun `marker bit lands only on the final packet of the access unit`() {
        val au = accessUnit()
        val packets = H264Rtp.packetize(au, mtuBytes = 600)
        assertTrue(packets.last().marker)
        assertTrue(packets.dropLast(1).none { it.marker })
    }

    /** Reassembles payloads into one Annex-B stream. */
    private fun buildStream(au: ByteArray, mtu: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for (packet in H264Rtp.packetize(au, mtuBytes = mtu)) {
            val payload = packet.payload()
            if (!packet.fragment) {
                out.write(startCode)
                out.write(payload)
            } else {
                val fu = payload[1].toInt() and 0xFF
                if (fu and 0x80 != 0) {
                    // A decoder rebuilds the NAL byte from the FU header's type
                    // and the indicator's NRI. Writing a literal 0 here would
                    // be the bug under test, not the encoder.
                    val nri = payload[0].toInt() and 0xE0
                    out.write(startCode)
                    out.write(nri or (fu and 0x1F))
                    out.write(payload, 2, payload.size - 2)
                } else {
                    out.write(payload, 2, payload.size - 2)
                }
            }
        }
        return out.toByteArray()
    }
}

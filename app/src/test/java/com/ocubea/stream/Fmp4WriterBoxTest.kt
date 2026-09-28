package com.ocubea.stream

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the fMP4 muxer's box metadata.
 *
 * The device bug this guards against is not a decode failure — a wrong
 * `mvhd` still yields a stream most players accept. It is metadata that reads
 * as valid and tells the user the wrong thing: a finished clip reporting
 * `duration=0` shows up in a gallery as a zero-length file, and an `avcC`
 * level far below the real frame size makes a decoder refuse the track.
 *
 * These are JVM tests because `Fmp4Writer` imports nothing from Android, so
 * the box logic can be checked without a phone.
 */
class Fmp4WriterBoxTest {

    private val w = 1920
    private val h = 1080

    /**
     * The real Annex-B SPS/PPS pair this device's hardware encoder emits for
     * 1080p, lifted byte for byte out of a clip recorded on it. A hand-written
     * approximation would test the approximation: the level byte alone is
     * computed from geometry, but a malformed SPS can fail to parse and make
     * the whole muxer look broken when it is fine.
     *
     * `avcC` in the recorded file carries profile 0x64, compat 0x00,
     * level 0x28 — the very values this writer is expected to produce.
     */
    private val configAnnexB = byteArrayOf(
        0x00, 0x00, 0x00, 0x01,
        0x67, 0x64, 0x00, 0x0a, 0xac.toByte(), 0x1b, 0x1a, 0x80.toByte(),
        0x78, 0x02, 0x27, 0xe5.toByte(), 0x9a.toByte(), 0x81.toByte(), 0x01, 0x01,
        0x03, 0xc2.toByte(), 0x21, 0x1a, 0x80.toByte(),
        0x00, 0x00, 0x00, 0x01,
        0x68, 0xea.toByte(), 0x43, 0xcb.toByte(),
    )

    // ── Box helpers ────────────────────────────────────────────

    /**
     * Returns the **body** of the first box named [type] found in [buf], or
     * null.
     *
     * Scans for the literal type and validates that the 4 bytes in front of it
     * really are a plausible box length. Walking the container tree instead
     * needs every intermediate header modelled correctly, and each one has a
     * quirk of its own — `stsd` is a FullBox with an entry count, `avc1`
     * carries an 86-byte `VisualSampleEntry` — so a helper that models one
     * wrong returns null and the failure gets blamed on the muxer.
     *
     * The length check is what keeps this honest: a 4-byte run that happens to
     * spell "avcC" inside a SPS NAL payload cannot pass, because the bytes
     * before it would have to describe a box that fits inside the buffer.
     */
    private fun findBox(buf: ByteArray, type: String): ByteArray? {
        val want = type.toByteArray(Charsets.US_ASCII)
        for (i in 0..buf.size - 8) {
            var match = true
            for (k in want.indices) {
                if (buf[i + 4 + k] != want[k]) { match = false; break }
            }
            if (!match) continue
            // All FOUR length bytes. Reading only three shifts the value right
            // by eight bits, so a 44-byte box looks like 0 and the helper
            // rejects every box it is meant to find.
            var size = 0L
            for (k in 0 until 4) size = (size shl 8) or (buf[i + k].toLong() and 0xFF)
            if (size < 8 || i + size > buf.size) continue
            return buf.copyOfRange(i + 8, (i + size).toInt())
        }
        return null
    }
    /** Reads a 32-bit big-endian value at [off]. */
    private fun u32(buf: ByteArray, off: Int): Long {
        var v = 0L
        for (k in 0 until 4) v = (v shl 8) or (buf[off + k].toLong() and 0xFF)
        return v
    }

    /** Reads a 64-bit big-endian value at [off]. */
    private fun u64(buf: ByteArray, off: Int): Long {
        var v = 0L
        for (k in 0 until 8) v = (v shl 8) or (buf[off + k].toLong() and 0xFF)
        return v
    }

    // ── avcC level ─────────────────────────────────────────────

    @Test
    fun `avcC level matches 1080p rather than the smallest fitting one`() {
        val init = Fmp4Writer().initSegmentFor(w, h, configAnnexB)
        assertNotNull("init segment must build for a real SPS/PPS", init)
        val avcc = findBox(init!!, "avcC")
        assertNotNull("track must carry an avcC record", avcc)
        // avcC body: configurationVersion(1) profile(1) compat(1) level(1)
        val level = avcc!![3].toInt() and 0xFF
        // 1080p is 120x68 = 8160 macroblocks, which needs Level 4.0 (0x28).
        // The old threshold let 0x0a (Level 1.0-ish) through and decoders
        // rejected the track.
        assertEquals("avcC level for 1920x1080", 0x28, level)
    }

    @Test
    fun `avcC level rises with frame size`() {
        val sd = Fmp4Writer().initSegmentFor(640, 480, configAnnexB)
        val hd = Fmp4Writer().initSegmentFor(1920, 1080, configAnnexB)
        assertNotNull(sd); assertNotNull(hd)
        val sdBox = findBox(sd!!, "avcC")
        val hdBox = findBox(hd!!, "avcC")
        assertNotNull("480p needs an avcC too", sdBox)
        assertNotNull("1080p needs an avcC", hdBox)
        val sdLevel = sdBox!![3].toInt() and 0xFF
        val hdLevel = hdBox!![3].toInt() and 0xFF
        assertTrue("1080p level must not be below 480p", hdLevel >= sdLevel)
    }

    // ── mvhd duration ──────────────────────────────────────────

    @Test
    fun `an open clip reports version 1 with a zero duration`() {
        val init = Fmp4Writer().initSegmentFor(w, h, configAnnexB)!!
        val mvhd = findBox(init, "mvhd")
        assertNotNull(mvhd)
        // Version 1 from the start, not 0-then-upgraded. A version-0 box is
        // 8 bytes narrower, so a live and a closed init would not be
        // interchangeable and the in-place header rewrite would be impossible.
        assertEquals("one layout for open and closed clips", 1, mvhd!![0].toInt())
        // version 1: 4 flags, 8 creation, 8 modification, 4 timescale, 8 duration
        assertEquals(1_000_000L, u32(mvhd, 20))
        assertEquals("a clip that is still open has no length", 0L, u64(mvhd, 24))
    }

    @Test
    fun `rebuilt init segment reports a version 1 mvhd carrying the real duration`() {
        val writer = Fmp4Writer()
        writer.initSegmentFor(w, h, configAnnexB)
        val durationUs = 3_265_239L
        val rebuilt = writer.rebuildInitWithDuration(durationUs)
        assertNotNull("a closed clip must be able to carry a duration", rebuilt)
        val mvhd = findBox(rebuilt!!, "mvhd")!!
        assertEquals(1, mvhd[0].toInt())
        assertEquals(1_000_000L, u32(mvhd, 20))
        assertEquals(
            "duration must survive the 64-bit round trip exactly",
            durationUs, u64(mvhd, 24),
        )
    }

    @Test
    fun `mdhd carries the same duration as mvhd`() {
        val writer = Fmp4Writer()
        writer.initSegmentFor(w, h, configAnnexB)
        val durationUs = 3_265_239L
        val closed = writer.rebuildInitWithDuration(durationUs)!!
        val mdhd = findBox(closed, "mdhd")!!
        // A media header still reading 0 while the movie header reads the real
        // length is a file whose duration depends on which box a player trusts.
        assertEquals(1, mdhd[0].toInt())
        assertEquals(durationUs, u64(mdhd, 24))
    }

    @Test
    fun `rebuild does not change the init segment size so it can patch in place`() {
        val writer = Fmp4Writer()
        val live = writer.initSegmentFor(w, h, configAnnexB)!!
        val closed = writer.rebuildInitWithDuration(3_265_239L)!!
        // ClipWriter overwrites the head of the file with the rebuilt init.
        // A size change would leave a stale stco/box length behind and corrupt
        // every offset after it, so this equality is load-bearing, not cosmetic.
        assertEquals(
            "in-place init rewrite requires identical length",
            live.size, closed.size,
        )
    }

    /**
     * The tkhd body must be exactly 96 bytes for a version-1 box, with the
     * matrix on body offset 52 and the 16.16 display size right after it.
     *
     * Found on a real device recording, after ffprobe reported a correct
     * duration and a correct resolution. The reserved run between the duration
     * and the matrix had been written as int() pairs, 4 bytes wide where the
     * spec has an 8-byte reserved followed by four 2-byte fields, so the run
     * came out 24 bytes instead of 16: the matrix landed at 60 instead of 52,
     * the display size at 96 instead of 88, and the box was 112 bytes instead
     * of 104. ffprobe reads the size from the stsd sample entry, not from
     * tkhd, which is why the clip looked fine to a probe and was still wrong.
     *
     * The offsets are cross-checked against a file ffmpeg produced, where
     * tkhd is version 0 and the same matrix starts on body offset 40:
     * v0 is 4 bytes shorter in creation, modification and duration, so 52-12
     * = 40.
     */
    @Test
    fun `the tkhd layout puts the matrix and the display size where they belong`() {
        val writer = Fmp4Writer()
        writer.initSegmentFor(w, h, configAnnexB)
        val tkhd = findBox(writer.rebuildInitWithDuration(1_000_000L)!!, "tkhd")!!
        assertEquals("tkhd version 1 body is 96 bytes", 96, tkhd.size)
        // version 1: 4 vf, 8 cre, 8 mod, 4 track_ID, 4 reserved, 8 duration,
        // 8 reserved, 2 layer, 2 alternate_group, 2 volume, 2 reserved, 36
        // matrix, 4 width, 4 height.
        val words = (0 until 9).map { u32(tkhd, 52 + it * 4) }
        assertEquals(
            "identity matrix at body offset 52",
            listOf(0x00010000L, 0L, 0L, 0L, 0x00010000L, 0L, 0L, 0L, 0x40000000L),
            words,
        )
        assertEquals("16.16 width", w * 65536L, u32(tkhd, 88))
        assertEquals("16.16 height", h * 65536L, u32(tkhd, 92))
    }

    /**
     * The ftyp must declare `isom` and `iso2`.
     *
     * With only `iso6` and `cmfc` declared, every clip on a Redmi Note 10 Pro
     * running Android 13 was rejected by MediaMetadataRetriever with
     * `setDataSource failed: status = 0x80000000`. The file was structurally
     * valid — ffprobe read it, the WebUI played it — so nothing in the box tree
     * was wrong. The narrowest decoder in the stack simply did not recognise
     * the file from its declared brands, and threw the whole thing away.
     */
    @Test
    fun `ftyp declares the brands a phone decoder recognises`() {
        val init = Fmp4Writer().initSegmentFor(w, h, configAnnexB)!!
        val ftyp = findBox(init, "ftyp")!!
        val brands = (0 until ftyp.size / 4 - 1).map { i ->
            String(ftyp, i * 4, 4, Charsets.US_ASCII)
        }
        assertEquals("major brand", "isom", brands.first())
        for (required in listOf("isom", "iso2", "iso6", "avc1")) {
            assertTrue("ftyp must declare $required, got $brands", brands.contains(required))
        }
        // minor_version 0x0200 marks the file as using 64-bit box fields, which
        // is what mvhd/tkhd/mdhd here are.
        assertEquals(0x0200L, ((ftyp[4].toLong() and 0xFF) shl 24) or
            ((ftyp[5].toLong() and 0xFF) shl 16) or
            ((ftyp[6].toLong() and 0xFF) shl 8) or (ftyp[7].toLong() and 0xFF))
    }

    @Test
    fun `tkhd agrees with mvhd on the clip length`() {
        val writer = Fmp4Writer()
        writer.initSegmentFor(w, h, configAnnexB)
        val durationUs = 3_265_239L
        val closed = writer.rebuildInitWithDuration(durationUs)!!
        val tkhd = findBox(closed, "tkhd")!!
        assertEquals("tkhd must match the mvhd version", 1, tkhd[0].toInt())
        // version 1 tkhd: 4 version/flags, 8 creation, 8 modification,
        // 4 track_ID, 4 reserved, 8 duration.
        assertEquals(durationUs, u64(tkhd, 28))
    }

    @Test
    fun `a zero duration is refused rather than written into a closed clip`() {
        // The exact bug this whole rewrite exists to prevent: a finished file
        // whose mvhd reads 0, which a gallery shows as an empty recording.
        // Refusing is the honest answer — the caller keeps the live header.
        val writer = Fmp4Writer()
        writer.initSegmentFor(w, h, configAnnexB)
        assertNull(
            "a closed clip must never claim a duration of zero",
            writer.rebuildInitWithDuration(0L),
        )
    }

    // ── Annex-B → AVCC ─────────────────────────────────────────

    @Test
    fun `a segment is produced once the target duration is reached`() {
        val writer = Fmp4Writer(requestedFps = 30)
        writer.initSegmentFor(w, h, configAnnexB)
        // TARGET_SEGMENT_MS is 250, not one frame at 30fps: the cut is on
        // elapsed time, so three 33 ms frames stay inside one segment and the
        // fourth pushes past it.
        assertNull(
            "three short frames stay inside one segment",
            writer.append(H264Encoder.Sample(nal(0x65, 1), 0L, true)),
        )
        writer.append(H264Encoder.Sample(nal(0x41, 2), 33_333L, false))
        writer.append(H264Encoder.Sample(nal(0x41, 3), 66_666L, false))
        val seg = writer.append(H264Encoder.Sample(nal(0x41, 4), 260_000L, false))
        assertNotNull("past 250 ms the segment must be cut", seg)
        assertTrue("a fragment carries a moof and an mdat", seg!!.bytes.size > 16)
    }

    @Test
    fun `a keyframe closes the open segment instead of discarding it`() {
        val writer = Fmp4Writer(requestedFps = 30)
        writer.initSegmentFor(w, h, configAnnexB)
        writer.append(H264Encoder.Sample(nal(0x65, 1), 0L, true))
        writer.append(H264Encoder.Sample(nal(0x41, 2), 33_333L, false))
        // With KEY_I_FRAME_INTERVAL = 0 every frame is an IDR. The earlier code
        // cleared the pending samples and returned null here, so every append
        // threw the previous frame away and nothing was ever flushed.
        val closed = writer.append(H264Encoder.Sample(nal(0x65, 3), 66_666L, true))
        assertNotNull("an incoming keyframe must flush what came before", closed)
    }

    @Test
    fun `a sample with no start code is still accepted`() {
        val writer = Fmp4Writer(requestedFps = 30)
        writer.initSegmentFor(w, h, configAnnexB)
        // The old bug: a sample whose Annex-B conversion did not change the
        // total length was returned verbatim, so the payload kept its start
        // code instead of gaining a 4-byte length prefix and the decoder read
        // the NAL header as a size. The fix must not throw on this input.
        val noStartCode = byteArrayOf(0x65, 0x88.toByte(), 0x84.toByte(), 0x00, 0x33)
        writer.append(H264Encoder.Sample(noStartCode, 0L, true))
        writer.append(H264Encoder.Sample(nal(0x41, 2), 33_333L, false))
        val seg = writer.append(H264Encoder.Sample(nal(0x41, 3), 260_000L, false))
        assertNotNull(seg)
    }

    @Test
    fun `measured frame rate is accepted instead of the requested one`() {
        val writer = Fmp4Writer(requestedFps = 30)
        writer.initSegmentFor(w, h, configAnnexB)
        // This camera regularly delivers ~4 fps against a 30 fps target, so
        // consecutive samples are ~250 ms apart. The muxer must still cut on
        // the 250 ms target instead of assuming 33 ms frames.
        writer.setMeasuredFps(4.0)
        writer.append(H264Encoder.Sample(nal(0x65, 1), 0L, true))
        val seg = writer.append(H264Encoder.Sample(nal(0x41, 2), 250_000L, false))
        assertNotNull("a 250 ms gap must fill exactly one segment", seg)
    }

    @Test
    fun `flush yields whatever is pending`() {
        val writer = Fmp4Writer(requestedFps = 30)
        writer.initSegmentFor(w, h, configAnnexB)
        writer.append(H264Encoder.Sample(nal(0x65, 1), 0L, true))
        writer.append(H264Encoder.Sample(nal(0x41, 2), 33_333L, false))
        assertNotNull("closing a clip must not lose the last frames", writer.flush())
    }

    /** An Annex-B frame: 4-byte start code, one NAL header, a little payload. */
    private fun nal(header: Int, tag: Int) = byteArrayOf(
        0x00, 0x00, 0x00, 0x01, header.toByte(), tag.toByte(), 0x11, 0x22,
    )

    @Test
    fun `init segment is null when the codec config has no SPS`() {
        // Garbage instead of Annex-B: the muxer must refuse rather than write
        // an avcC that describes nothing.
        val init = Fmp4Writer().initSegmentFor(w, h, byteArrayOf(1, 2, 3, 4, 5))
        assertNull("no SPS means no usable avcC", init)
    }
}

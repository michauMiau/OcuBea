package com.ocubea.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * AviWriter is pure java.io - no Android imports - so it can be tested on the
 * JVM directly. That matters, because it was broken in a way no build and no
 * lint could see: buildHeader() emitted 224 bytes into a 200-byte placeholder,
 * and the size guard turned that into an exception on every close() that had a
 * frame in it. Motion recording went through this path, so every clip that
 * finished threw instead of being written.
 */
class AviWriterTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun jpeg(size: Int) = ByteArray(size) { 0xFF.toByte() }

    @Test
    fun `a writer with one frame closes and leaves a file behind`() {
        val out = File(tmp.newFolder(), "one.avi")
        AviWriter(out, 1920, 1080, 15).use { it.addFrame(jpeg(2048)) }
        assertTrue("the clip should exist after close()", out.exists())
        assertTrue("and should not be empty: $out", out.length() > 0L)
    }

    @Test
    fun `many frames still close`() {
        val out = File(tmp.newFolder(), "many.avi")
        AviWriter(out, 640, 480, 10).use { w -> repeat(120) { w.addFrame(jpeg(1000 + it)) } }
        assertTrue(out.length() > 0L)
    }

    @Test
    fun `an empty writer deletes its file instead of leaving a stub`() {
        val out = File(tmp.newFolder(), "empty.avi")
        AviWriter(out, 640, 480, 10).use { /* no frames */ }
        assertTrue("a clip with no frames is not a clip", !out.exists())
    }

    /**
     * The exact defect: the header did not fit the placeholder, so close()
     * threw "header overflow 224" for every recorded clip. One frame is enough
     * to build the header, and enough to make this fail.
     */
    @Test
    fun `the header fits the space reserved for it`() {
        val out = File(tmp.newFolder(), "fit.avi")
        AviWriter(out, 1920, 1080, 15).use { it.addFrame(jpeg(64)) }
        // No exception is the assertion. The size is checked structurally in
        // the two tests below rather than by re-deriving the writer's own
        // arithmetic, which is what made this file wrong in the first place.
        assertTrue(out.length() > 0L)
    }

    @Test
    fun `the file carries the four tags a demuxer looks for`() {
        val out = File(tmp.newFolder(), "tags.avi")
        AviWriter(out, 320, 240, 15).use { w ->
            w.addFrame(jpeg(128))
            w.addFrame(jpeg(128))
        }
        val s = String(out.readBytes(), Charsets.ISO_8859_1)
        for (tag in listOf("RIFF", "AVI ", "hdrl", "avih", "strl", "strh", "strf", "movi", "idx1")) {
            assertTrue("missing '$tag' in the written header", s.contains(tag))
        }
        assertTrue(
            "the frame region must come after the 'movi' fourcc",
            s.indexOf("movi") < s.indexOf("00dc"),
        )
        assertTrue(
            "the index must come after the frames",
            s.indexOf("00dc") < s.lastIndexOf("idx1"),
        )
    }

    @Test
    fun `a frame chunk declares its own length`() {
        val out = File(tmp.newFolder(), "len.avi")
        AviWriter(out, 64, 64, 15).use { it.addFrame(jpeg(200)) }
        val b = out.readBytes()
        val at = indexOf(b, "00dc".toByteArray(Charsets.US_ASCII))
        assertTrue("no frame chunk found", at > 0)
        assertEquals(
            "the chunk must declare the payload it carries",
            200L,
            u32le(b, at + 4),
        )
    }

    @Test
    fun `an odd-length frame is padded so the next chunk stays aligned`() {
        val out = File(tmp.newFolder(), "shift.avi")
        AviWriter(out, 64, 64, 15).use { w ->
            w.addFrame(jpeg(41)) // odd: needs a pad byte
            w.addFrame(jpeg(42)) // even: starts one byte later than it otherwise would
        }
        val b = out.readBytes()
        // '00dc' appears twice per frame - once in the stream, once in idx1 -
        // so only the occurrences before the index are real chunk headers.
        val s = String(b, Charsets.ISO_8859_1)
        val frames = countOf(s.substring(0, s.indexOf("idx1")), "00dc")
        assertEquals(
            "both frames should be findable after the odd one was padded",
            2,
            frames,
        )
        // What matters about the pad byte is alignment, not a byte total: a
        // demuxer reads chunk lengths in sequence, so chunk 2 has to start on
        // the same parity as chunk 1. Hand-derived file sizes are deliberately
        // not asserted here - the boundary between the 'movi' fourcc and the
        // first chunk is exactly what was miscounted when the header did not
        // fit, and re-deriving it in a test just repeats the mistake.
        val first = indexOf(b, "00dc".toByteArray(Charsets.US_ASCII))
        val second = indexOf(b, "00dc".toByteArray(Charsets.US_ASCII), from = first + 1)
        assertTrue("both chunk headers must be findable", first > 0 && second > first)
        assertEquals(
            "chunk 2 must be word-aligned with chunk 1, so the odd frame was padded",
            0,
            (second - first) % 2,
        )
    }

    @Test
    fun `dimensions and codec reach the header`() {
        val out = File(tmp.newFolder(), "dims.avi")
        AviWriter(out, 1280, 720, 15).use { w -> repeat(30) { w.addFrame(jpeg(256)) } }
        val b = out.readBytes()
        val s = String(b, Charsets.ISO_8859_1)
        // avih dwWidth / dwHeight are little-endian 32-bit.
        val wLe = String(byteArrayOf(0x00, 0x05, 0x00, 0x00), Charsets.ISO_8859_1) // 1280
        val hLe = String(byteArrayOf(0xD0.toByte(), 0x02, 0x00, 0x00), Charsets.ISO_8859_1) // 720
        assertTrue("1280x720 should appear in the header", s.contains(wLe) && s.contains(hLe))
        // biCompression used to be 0 (BI_RGB) while the frames were JPEG,
        // which is how a player ends up showing noise. It must be 'MJPG'.
        val mjpg = String(byteArrayOf(0x4D, 0x4A, 0x50, 0x47), Charsets.ISO_8859_1)
        assertTrue("biCompression should be the MJPG fourcc", s.contains(mjpg))
        // 30 chunk headers in the stream, plus 30 more in idx1.
        assertEquals(
            "all 30 frames should be present in the stream",
            30,
            countOf(s.substring(0, s.indexOf("idx1")), "00dc"),
        )
    }

    private fun u32le(b: ByteArray, at: Int): Long =
        (b[at].toLong() and 0xFF) or
            ((b[at + 1].toLong() and 0xFF) shl 8) or
            ((b[at + 2].toLong() and 0xFF) shl 16) or
            ((b[at + 3].toLong() and 0xFF) shl 24)

    private fun countOf(haystack: String, needle: String): Int {
        var n = 0
        var i = haystack.indexOf(needle)
        while (i >= 0) { n++; i = haystack.indexOf(needle, i + 1) }
        return n
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int = 0): Int {
        outer@ for (i in from..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}

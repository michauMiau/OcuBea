package com.ocubea.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The unit tests check OcuBea's own idea of the file. This one asks ffprobe -
 * something that knows nothing about this codebase - whether the result is a
 * readable MJPEG AVI with the dimensions and frame count that went in.
 *
 * A writer can satisfy every internal invariant and still produce a file no
 * player will open, and that is exactly the failure mode AviWriter had. Skips
 * when ffprobe is absent so CI without it is unaffected.
 */
class AviWriterFfprobeTest {

    @get:Rule val tmp = TemporaryFolder()

    private val ffmpeg: String? = sequenceOf("ffmpeg", "ffprobe")
        .map { c -> File("/usr/bin", c).takeIf { it.canExecute() } ?: File("/usr/local/bin", c).takeIf { it.canExecute() } }
        .firstOrNull()
        ?.absolutePath

    private val ffprobe: String? = sequenceOf("ffprobe", "ffmpeg")
        .map { candidate ->
            File("/usr/bin", candidate).takeIf { it.canExecute() }
                ?: File("/usr/local/bin", candidate).takeIf { it.canExecute() }
        }
        .firstOrNull()
        ?.absolutePath

    /**
     * A real 16x16 JPEG rather than random bytes, so ffprobe actually decodes
     * the frames instead of reporting a codec it cannot parse. The fixture is
     * a file, not a byte literal: a 220-byte array of escaped numbers in the
     * source would be unreviewable, and a regenerated-in-test JPEG would make
     * the test depend on an encoder being installed.
     */
    private fun jpegFrame(): ByteArray =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("frame16.jpg")) {
            "test fixture frame16.jpg is missing"
        }.use { it.readBytes() }

    @Test
    fun `ffprobe reads back the dimensions and the frame count`() {
        assumeTrue("ffprobe/ffmpeg not available", ffprobe != null)
        val out = File(tmp.newFolder(), "probe.avi")
        val frames = 12
        AviWriter(out, 16, 16, 10).use { w -> repeat(frames) { w.addFrame(jpegFrame()) } }

        val result = ProcessBuilder(ffprobe!!, "-v", "error", "-show_entries",
            "stream=codec_name,width,height,nb_read_frames", "-count_frames",
            "-of", "default=nw=1", out.absolutePath)
            .redirectErrorStream(true)
            .start()
        val text = result.inputStream.bufferedReader().readText()
        assertEquals("ffprobe should succeed on an OcuBea clip:\n$text", 0, result.waitFor())

        assertTrue(
            "the stream should be decoded as mjpeg, got:\n$text",
            text.lineSequence().any { it.startsWith("codec_name=mjpeg") },
        )
        assertTrue("width should be 16, got:\n$text", text.lineSequence().any { it.trim() == "width=16" })
        assertTrue("height should be 16, got:\n$text", text.lineSequence().any { it.trim() == "height=16" })
        assertTrue(
            "ffprobe should count $frames frames, got:\n$text",
            text.lineSequence().any { it.trim() == "nb_read_frames=$frames" },
        )
    }

    @Test
    fun `ffprobe decodes the stream without reporting errors`() {
        assumeTrue("ffprobe/ffmpeg not available", ffprobe != null)
        val out = File(tmp.newFolder(), "decode.avi")
        AviWriter(out, 16, 16, 10).use { w -> repeat(8) { w.addFrame(jpegFrame()) } }

        // ffprobe has no "-f null" output; decoding to /dev/null is ffmpeg's job.
        assumeTrue("ffmpeg not available", ffmpeg != null)
        val result = ProcessBuilder(ffmpeg!!, "-v", "error", "-i", out.absolutePath,
            "-f", "null", "-")
            .redirectErrorStream(false)
            .start()
        val errors = result.errorStream.bufferedReader().readText()
        result.waitFor()
        assertTrue(
            "decoding an OcuBea clip should not print errors, got:\n$errors",
            errors.isBlank(),
        )
    }
}

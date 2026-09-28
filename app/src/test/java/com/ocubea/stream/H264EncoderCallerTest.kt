package com.ocubea.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards a bug that shipped once: [H264Encoder.keyFrameIntervalSec] defaults to
 * 0, meaning an IDR on every frame, and the clip path used to build its encoder
 * with four arguments. Nothing failed, nothing warned - the default simply won,
 * and a comment in the encoder went on claiming the clip passed a longer
 * interval.
 *
 * The cost of that silence was measured, not guessed: at 12000 kbps and 1080p a
 * clip recorded 94.9 MB/min with GOP=0 and 61.0 MB/min with GOP=1, so 36% more
 * disk than the setting implied. An earlier experiment had already "measured"
 * that GOP made no difference - because it was never actually applied.
 *
 * This test reads the source instead of calling the constructor, because the
 * defect is precisely that a caller omits an argument. A behavioural test would
 * have passed for years.
 */
class H264EncoderCallerTest {

    private val sourceRoot: File = File("src/main/java/com/ocubea")

    private fun sources(): List<File> =
        sourceRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    /** Call sites of the H264Encoder constructor, as the source text. */
    private fun constructorCallSites(): List<Pair<String, String>> =
        sources().flatMap { file ->
            val text = file.readText()
            // "H264Encoder(" with no dot prefix - a fully-qualified call
            // (com.ocubea.stream.H264Encoder) also counts, so match the bare
            // name and let the caller decide.
            val name = "H264Encoder("
            var i = text.indexOf(name)
            val found = mutableListOf<String>()
            while (i >= 0) {
                // Skip the class declaration itself.
                val before = text.substring(maxOf(0, i - 40), i)
                if (!before.trimEnd().endsWith("class")) {
                    var depth = 1
                    var j = i + name.length
                    while (j < text.length && depth > 0) {
                        when (text[j]) {
                            '(' -> depth++
                            ')' -> depth--
                        }
                        j++
                    }
                    found += text.substring(i, j)
                }
                i = text.indexOf(name, i + 1)
            }
            found.map { file.path to it }
        }

    @Test
    fun `every H264Encoder call site is found, so a new one cannot slip past`() {
        val sites = constructorCallSites()
        assertTrue(
            "expected the HLS session and the clip path to build the encoder, found ${sites.size}",
            sites.size >= 2,
        )
    }

    @Test
    fun `no caller omits the keyframe interval`() {
        val offenders = constructorCallSites().filter { (_, call) ->
            // Five arguments expected: width, height, fps, bitrate, GOP.
            val argCount = call.removePrefix("H264Encoder(")
                .removeSuffix(")")
                .split(",")
                .count { it.isNotBlank() }
            argCount < 5
        }
        assertEquals(
            "these H264Encoder call sites pass fewer than 5 arguments, so " +
                "keyFrameIntervalSec silently falls back to 0 (an IDR per " +
                "frame) and the clip path writes 36% more data than the " +
                "bitrate setting implies: $offenders",
            emptyList<Pair<String, String>>(),
            offenders,
        )
    }

    @Test
    fun `the clip path passes the shared constant, not a literal`() {
        val clipSite = constructorCallSites().firstOrNull { (path, _) ->
            path.endsWith("CameraManager.kt")
        }
        assertTrue("expected CameraManager to build a clip encoder", clipSite != null)
        val call = clipSite!!.second
        assertTrue(
            "the clip encoder should take HlsProfile.CLIP_KEY_FRAME_INTERVAL_SEC, " +
                "so the value has one home; found: $call",
            call.contains("CLIP_KEY_FRAME_INTERVAL_SEC"),
        )
    }

    @Test
    fun `the clip constant is a positive interval, not zero`() {
        // Zero here would reintroduce the exact defect: a named constant that
        // documents a longer GOP while carrying the dense-GOP value.
        assertEquals(1, com.ocubea.model.HlsProfile.CLIP_KEY_FRAME_INTERVAL_SEC)
    }
}

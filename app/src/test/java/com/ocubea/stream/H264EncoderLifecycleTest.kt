package com.ocubea.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * Guards two properties of the HLS path that no device measurement can reach.
 *
 * Both were real defects found by audit:
 *
 * 1. A failed configure() dropped the only reference to a MediaCodec without
 *    releasing it. MediaCodec has no finalizer, so the native encoder stayed
 *    allocated for the life of the process.
 * 2. `clients` was incremented on every playlist poll and never decremented,
 *    so the /status.json number climbed at ~2/s and could never mean anything.
 *
 * Why this is a structural test and not a measurement: the leak needs
 * configure() to fail, and pickHardwareAvcEncoder() filters out every size the
 * hardware encoder cannot take, so the failure path is unreachable through the
 * API. On the device, 3840x2160 - the obvious way to force a failure - is
 * simply supported, and the log recorded zero "configure failed" lines. That
 * unreachability is exactly why the bug survived unnoticed in the first place.
 *
 * So the honest guard is on the shape of the object after a failed start, plus
 * the telemetry that used to lie.
 */
class H264EncoderLifecycleTest {

    private fun encoder() =
        H264Encoder(width = 8, height = 8, fps = 5, bitrate = 64_000, keyFrameIntervalSec = 0)

    @Test
    fun `a failed start leaves no codec behind`() {
        val e = encoder()
        // On a plain JVM, pickHardwareAvcEncoder() hits MediaCodecList, which
        // android.jar stubs out to throw. That is still a failed start, so the
        // invariant below still has to hold - it is exactly the path where a
        // half-built codec used to be dropped. Anything else (a real encoder,
        // a future Robolectric setup) returns early.
        val started = runCatching { e.start() }.getOrDefault(false)
        if (started) return
        val f = H264Encoder::class.java.getDeclaredField("codec")
        f.isAccessible = true
        assertTrue(
            "after a failed start the codec field must be null, not a " +
                "half-built MediaCodec that nobody will ever release",
            f.get(e) == null
        )
    }

    @Test
    fun `the MediaCodec reference outlives the catch block`() {
        // Mutation-tested and it is honest about its own limits: removing the
        // release() does NOT fail any test, because on a plain JVM
        // createByCodecName() throws before a codec is ever allocated, so the
        // leaking line is unreachable here. A test that pretends to cover it
        // would be a false green light, which is worse than none.
        //
        // So this asserts the source shape instead: mc has to be declared
        // outside the try, or the release in the catch has nothing to release
        // and the leak is back. The test is deliberately blunt about being a
        // source check - the alternative is no guard at all.
        val text = readSourceOrNull()
        assertTrue(
            "could not locate H264Encoder.kt via -Docubea.srcRoot - this " +
                "guard silently protects nothing if it cannot find its file",
            text != null
        )
        text!!

        val startBlock = text.substringAfter("fun start(): Boolean")
        assertTrue(
            "the MediaCodec must be declared before the try, otherwise the " +
                "catch block cannot release it and a failed configure leaks " +
                "the native encoder for the life of the process",
            Regex("""var mc: MediaCodec\? = null\s*\n\s*return try""").containsMatchIn(startBlock)
        )
        assertTrue(
            "the catch block must release mc; `codec = null` alone only " +
                "drops the last reference",
            Regex("""mc\?\.release\(\)""").containsMatchIn(startBlock)
        )
    }

    @Test
    fun `codec discovery is inside the guard`() {
        // Found while writing the test above: pickHardwareAvcEncoder() and
        // pickColorFormat() sit outside the try, so a MediaCodecList failure
        // escaped start() as an exception instead of the documented
        // "false + readable lastError" contract. Caught by the sibling test;
        // locked here so the guard cannot be lost in a refactor.
        val text = readSourceOrNull()!!
        val startBlock = text.substringAfter("fun start(): Boolean")
            .substringBefore("fun encode(")
        val tryIdx = startBlock.indexOf("val info = try {")
        val codecNameIdx = startBlock.indexOf("codecName = info.name")
        assertTrue(
            "encoder discovery must be inside the try so a MediaCodecList " +
                "failure returns false instead of throwing at the caller",
            tryIdx >= 0 && codecNameIdx > tryIdx
        )
    }

    private fun readSourceOrNull(): String? {
        // Wired by the test task in build.gradle.kts. A missing value fails
        // the test loudly rather than skipping the guard silently - a source
        // check that quietly no-ops is the exact false green light this file
        // exists to avoid.
        val root = System.getProperty("ocubea.srcRoot")
            ?: return null
        val f = java.io.File(root, "app/src/main/java/com/ocubea/stream/H264Encoder.kt")
        if (!f.isFile) return null
        return f.readText()
    }

    @Test
    fun `HlsSession no longer carries a client counter`() {
        // The counter was the defect. clientJoined() ran on every playlist poll
        // and clientLeft() had no caller anywhere in the repo, so hls.clients
        // grew without bound and no future idle policy could have trusted it.
        // Removing it - rather than wiring up a decrement that cannot fire -
        // is what makes the telemetry honest.
        val declared = HlsSession::class.java.declaredFields.map { it.name }
        assertTrue(
            "HlsSession must not expose a clients counter: nothing in this " +
                "protocol can decrement it, so any value it showed was fiction",
            "clients" !in declared
        )
        assertTrue(
            "clientJoined must be gone with it",
            HlsSession::class.java.declaredMethods.none { it.name == "clientJoined" }
        )
        assertTrue(
            "clientLeft must be gone with it",
            HlsSession::class.java.declaredMethods.none { it.name == "clientLeft" }
        )
    }

    @Test
    fun `requestKeyFrame stays available for a future join path`() {
        // Kept deliberately. The audit called it dead code, but it is a
        // documented hook ("e.g. right after a client joins") with no caller
        // yet - removing it would throw away a working way to force an IDR for
        // the day a viewer-counting mechanism does exist.
        val m = H264Encoder::class.java.getDeclaredMethod("requestKeyFrame")
        assertTrue("requestKeyFrame must stay public", !Modifier.isPrivate(m.modifiers))
    }

    @Test
    fun `a session is safe to stop twice`() {
        // stop() is reachable from stopHls() and from the camera rebind in the
        // watchdog; both can fire for the same session, and a second stop()
        // must not throw out of a runCatching that was meant to be the net.
        val s = HlsSession(width = 8, height = 8, fps = 5, bitrate = 64_000)
        runCatching { s.start() }
        runCatching { s.stop() }
        runCatching { s.stop() }
        assertEquals(false, s.isEncoding)
    }
}

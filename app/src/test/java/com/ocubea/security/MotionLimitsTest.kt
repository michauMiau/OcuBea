package com.ocubea.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pre-roll buffer is an ArrayDeque of full JPEGs, and its length was
 * `preRecordSeconds * fps` with neither value bounded. Any device on the LAN
 * could POST /settings/pre_record_seconds?set=2000000 and the camera would try
 * to hold 30 million frames - about 2.6 TB - before failing.
 *
 * A seconds-only limit is not enough, which is the part worth testing: the same
 * 30 seconds cost ~40MB at 1080p and far less at 720p, and the user can change
 * the resolution independently. So the byte ceiling has to hold at both.
 */
class MotionLimitsTest {

    /** One 1080p JPEG measured on the test phone. */
    private val jpeg1080p = 87 * 1024
    private val jpeg720p = 45 * 1024

    @Test
    fun `the default matches what the app used to ship`() {
        assertEquals(2, MotionLimits.DEFAULT_PRE_RECORD_SECONDS)
        assertEquals(300, MotionLimits.DEFAULT_MAX_CLIP_SECONDS)
    }

    @Test
    fun `an absurd request is held to the ceiling`() {
        // The original bug, mechanically: 2000000s at 15fps would be 30 million
        // frames.
        assertEquals(
            MotionLimits.MAX_PRE_RECORD_SECONDS,
            MotionLimits.effectivePreRecordFrames(2_000_000, 15, jpeg1080p),
        )
    }

    @Test
    fun `a negative or zero request cannot produce a negative buffer`() {
        for (bad in listOf(-1, -1000, Int.MIN_VALUE)) {
            val secs = MotionLimits.effectivePreRecordFrames(bad, 15, jpeg1080p)
            assertTrue("request $bad produced $secs", secs >= 0)
        }
    }

    @Test
    fun `the byte ceiling holds at 1080p`() {
        val fps = 15
        val secs = MotionLimits.effectivePreRecordFrames(
            MotionLimits.MAX_PRE_RECORD_SECONDS, fps, jpeg1080p
        )
        val bytes = secs * fps.toLong() * jpeg1080p
        assertTrue(
            "buffer would hold ${bytes / 1e6}MB at 1080p, over the " +
                "${MotionLimits.MAX_PRE_ROLL_BYTES / 1e6}MB ceiling",
            bytes <= MotionLimits.MAX_PRE_ROLL_BYTES,
        )
    }

    @Test
    fun `the byte ceiling holds at 720p, where more seconds fit`() {
        val fps = 15
        val secs = MotionLimits.effectivePreRecordFrames(
            MotionLimits.MAX_PRE_RECORD_SECONDS, fps, jpeg720p
        )
        val bytes = secs * fps.toLong() * jpeg720p
        assertTrue(
            "buffer would hold ${bytes / 1e6}MB at 720p",
            bytes <= MotionLimits.MAX_PRE_ROLL_BYTES,
        )
        // The point of having a byte ceiling at all: 720p frames are smaller, so
        // the seconds bound should be what limits it, not the bytes.
        assertEquals(MotionLimits.MAX_PRE_RECORD_SECONDS, secs)
    }

    @Test
    fun `a short history is never extended by a small frame size`() {
        assertEquals(2, MotionLimits.effectivePreRecordFrames(2, 15, 1 * 1024))
    }

    @Test
    fun `degenerate frame and rate values do not divide by zero`() {
        // fps 0 and a zero-length frame both reach here from a camera that
        // failed mid-frame, so they must not throw on the capture path.
        assertTrue(MotionLimits.effectivePreRecordFrames(5, 0, jpeg1080p) >= 0)
        assertTrue(MotionLimits.effectivePreRecordFrames(5, 15, 0) >= 0)
    }

    @Test
    fun `a huge frame clamps the history to nothing rather than to one frame`() {
        // One frame larger than the whole budget must not leave a 1-frame
        // history that then holds an oversized buffer anyway.
        val secs = MotionLimits.effectivePreRecordFrames(
            30, 15, MotionLimits.MAX_PRE_ROLL_BYTES.toInt() * 2
        )
        assertTrue("got $secs", secs >= 0)
        val bytes = secs * 15L * MotionLimits.MAX_PRE_ROLL_BYTES * 2
        assertTrue("would still hold ${bytes / 1e6}MB", bytes <= MotionLimits.MAX_PRE_ROLL_BYTES || secs == 0)
    }
}

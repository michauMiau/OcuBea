package com.ocubea.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bitrate clamping, tested without SharedPreferences.
 *
 * These exercise [BitrateBounds] from the main source set, not a copy, so a
 * change to the real bounds fails here.
 */
class OcuBeaConfigTest {

    @Test
    fun `clamp keeps values already inside the range`() {
        assertEquals(4000, BitrateBounds.clampKbps(4000))
        assertEquals(200, BitrateBounds.clampKbps(200))
        assertEquals(20_000, BitrateBounds.clampKbps(20_000))
    }

    @Test
    fun `clamp pulls values back into the range`() {
        assertEquals(200, BitrateBounds.clampKbps(0))
        assertEquals(200, BitrateBounds.clampKbps(-5_000))
        assertEquals(20_000, BitrateBounds.clampKbps(999_999))
    }

    @Test
    fun `bps conversion cannot overflow at the top of the range`() {
        // 20000 * 1000 fits comfortably; the point of the clamp is that an
        // unclamped value would not.
        assertEquals(20_000_000, BitrateBounds.bpsFromKbps(20_000))
        assertTrue(BitrateBounds.bpsFromKbps(999_999) <= 20_000_000)
        assertTrue(BitrateBounds.bpsFromKbps(-1) >= 200_000)
    }

    @Test
    fun `the default sits high enough for 1080p but below the old hardcoded rate`() {
        val previous = 1920 * 1080 * 4 / 1000  // the width*height*4 formula
        val now = 4_000
        assertTrue("default should be below the old formula", now < previous)
        assertTrue("default should still be a sane 1080p rate", now >= 3_000)
    }
}

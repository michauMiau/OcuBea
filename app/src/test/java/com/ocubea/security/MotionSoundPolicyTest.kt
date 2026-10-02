package com.ocubea.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The motion tone's gate, tested without an `AudioManager`.
 *
 * `sound`, `sound_event` and `sound_timeout` answered `okText("ok")` for every
 * value and never made a sound -- there was no `ToneGenerator` anywhere in the
 * main source set before this. A test that asserts "a tone is audible" cannot
 * exist: no JVM unit test has a speaker, and a fake that records
 * `startTone` called proves nothing about sound coming out.
 *
 * So what is tested here is the part that *was* the lie -- the policy. Whether
 * a tone may play at all, and the deadline arithmetic that decides "again",
 * "not yet" and "never". If that gate is wrong, the tone plays when it must not,
 * or a config change silently does nothing, and both are visible here.
 */
class MotionSoundPolicyTest {

    // ── toneAllowed ────────────────────────────────────────────────────────

    @Test
    fun `a tone plays only when both switches are on and the gap has passed`() {
        assertTrue(MotionSoundPolicy.toneAllowed(masterOn = true, eventOn = true, nowMs = 0, nextAllowedAtMs = 0))
        assertFalse(MotionSoundPolicy.toneAllowed(masterOn = false, eventOn = true, nowMs = 0, nextAllowedAtMs = 0))
        assertFalse(MotionSoundPolicy.toneAllowed(masterOn = true, eventOn = false, nowMs = 0, nextAllowedAtMs = 0))
        assertFalse(MotionSoundPolicy.toneAllowed(masterOn = true, eventOn = true, nowMs = 9_999, nextAllowedAtMs = 10_000))
    }

    /**
     * The exact boundary, and it is inclusive.
     *
     * `nowMs >= nextAllowedAtMs` is what makes a timeout of N seconds mean "at
     * least N seconds between tones". With `>` the real gap would be N+1ms and
     * a test asserting the documented interval would be off by a frame of
     * time -- harmless in practice, but it is the sort of off-by-one that later
     * gets "fixed" in the wrong direction by someone reading it as a bug.
     */
    @Test
    fun `the deadline itself is allowed, not only what is past it`() {
        assertTrue(
            "a tone at exactly the deadline must play",
            MotionSoundPolicy.toneAllowed(masterOn = true, eventOn = true, nowMs = 10_000, nextAllowedAtMs = 10_000),
        )
        assertFalse(
            "one millisecond before it must not",
            MotionSoundPolicy.toneAllowed(masterOn = true, eventOn = true, nowMs = 9_999, nextAllowedAtMs = 10_000),
        )
    }

    /**
     * The gate is AND, and this test is what says so.
     *
     * Written as a truth table rather than a couple of one-offs: an OR here
     * would let a tone through with the master off, which is the exact failure
     * where a user who muted motion sound still hears a room chirping.
     */
    @Test
    fun `the gate is an AND over every input`() {
        // The deadline is fixed at 0 and `nowMs` is varied, so `gap` below means
        // "how far past the deadline the event arrives". An earlier version of
        // this test varied nowMs with nextAllowedAtMs pinned at 0 too and then
        // expected gap=1000 to be refused -- which is wrong, and it went red:
        // with the deadline at 0, nowMs=1000 is a second *after* it and a tone
        // must play. The table is spelled out this way so "gap" cannot be
        // mistaken for a timeout.
        for (master in listOf(false, true)) {
            for (event in listOf(false, true)) {
                for (gap in listOf(0L, 1_000L)) {
                    val expected = master && event
                    assertEquals(
                        "master=$master event=$event gapPastDeadline=$gap",
                        expected,
                        MotionSoundPolicy.toneAllowed(master, event, nowMs = gap, nextAllowedAtMs = 0),
                    )
                }
            }
        }
    }

    // ── nextAllowedAt ──────────────────────────────────────────────────────

    /** A timeout of 0 must not push the deadline into the future at all. */
    @Test
    fun `zero means every event, so the deadline stays now`() {
        assertEquals(1_000_000L, MotionSoundPolicy.nextAllowedAt(nowMs = 1_000_000L, timeoutSeconds = 0))
        // Repeated, because "0" is the one value where an implementation that
        // multiplied first would give 0 and hold every tone back forever --
        // i.e. `sound_timeout=0` would mean "never beep", the opposite of the
        // documented meaning, and no test of the boolean gate would catch it.
        assertEquals(1_000_000L, MotionSoundPolicy.nextAllowedAt(1_000_000L, 0))
    }

    @Test
    fun `a timeout pushes the deadline out by exactly that many seconds`() {
        assertEquals(1_030_000L, MotionSoundPolicy.nextAllowedAt(nowMs = 1_000_000L, timeoutSeconds = 30))
        assertEquals(2_000_000L, MotionSoundPolicy.nextAllowedAt(nowMs = 1_000_000L, timeoutSeconds = 1_000))
    }

    @Test
    fun `the documented ceiling does not overflow`() {
        // 86400 * 1000 fits a Long easily; what this guards is the *Int* side:
        // an implementation doing `nowMs + timeoutSeconds * 1000` with Int
        // arithmetic would wrap at ~2.1e9 ms, i.e. just past 596 hours, and a
        // wrapped deadline is in the past -- so the timeout would silently
        // become "no timeout at all".
        val now = 1_700_000_000_000L
        val next = MotionSoundPolicy.nextAllowedAt(now, MotionSoundPolicy.MAX_TIMEOUT_SECONDS)
        assertEquals(now + 86_400_000L, next)
        assertTrue("deadline must be in the future", next > now)
    }

    /**
     * A negative timeout is refused by the parser, so the policy never sees one.
     *
     * If that ever changes, `<= 0` here would treat it as "every event", which
     * is the safe direction -- a notification is noisy, not a privacy setting.
     * This test pins which one it is so the choice is a decision and not an
     * accident of `if (timeoutSeconds <= 0)`.
     */
    @Test
    fun `a negative timeout is treated as the most permissive, not the strictest`() {
        val now = 5_000_000L
        assertEquals(now, MotionSoundPolicy.nextAllowedAt(now, -1))
        assertEquals(now, MotionSoundPolicy.nextAllowedAt(now, -999))
    }

    // ── round trip through the policy, which is where a real bug lives ─────

    /**
     * The sequence a real detector produces: an event, the gate holds, time
     * passes, the gate opens.
     *
     * Individual unit tests of `nextAllowedAt` and `toneAllowed` would both pass
     * with a unit mismatch between them (one in seconds, one in ms) -- which is
     * the failure that makes `sound_timeout=30` behave like 30ms or 30 minutes.
     */
    @Test
    fun `playing a tone at 30s holds back the next one and releases it on time`() {
        val now = 1_700_000_000_000L
        assertTrue(MotionSoundPolicy.toneAllowed(true, true, now, nextAllowedAtMs = 0))

        val deadline = MotionSoundPolicy.nextAllowedAt(now, 30)
        assertFalse(MotionSoundPolicy.toneAllowed(true, true, now + 29_999, deadline))
        assertTrue(MotionSoundPolicy.toneAllowed(true, true, now + 30_000, deadline))

        // And with timeout 0 there is no hold-back at all between two events.
        val openDeadline = MotionSoundPolicy.nextAllowedAt(now, 0)
        assertTrue(MotionSoundPolicy.toneAllowed(true, true, now, openDeadline))
        assertTrue(MotionSoundPolicy.toneAllowed(true, true, now, openDeadline))
    }
}
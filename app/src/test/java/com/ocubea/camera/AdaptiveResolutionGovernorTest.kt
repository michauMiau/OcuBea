package com.ocubea.camera

import com.ocubea.camera.AdaptiveResolutionGovernor.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The adaptive quality ladder, pinned.
 *
 * This is the policy that decides whether a person watching the stream sees
 * 720p at 7 fps or 360p at 15, and it runs on hardware that is impossible to
 * put in front of a test on demand. Everything here is therefore driven by an
 * injected clock and an injected measurement: the transitions that would need a
 * specific phone in a specific state are reproducible because nothing in the
 * class reads a real clock or a real camera.
 *
 * The properties asserted are the ones whose failure a viewer would notice --
 * a camera that rebinds forever, a ladder that degrades quality without ever
 * changing the frame rate, a status page that reports the request as though it
 * were the measurement. The tuning constants are not asserted, because they
 * are guesses about hardware and pinning them would only make them harder to
 * change honestly.
 */
class AdaptiveResolutionGovernorTest {

    private val logs = mutableListOf<String>()

    private fun governor(): AdaptiveResolutionGovernor =
        AdaptiveResolutionGovernor { logs += it }

    /**
     * Drives one closed measurement window.
     *
     * Windows are one second apart in production, so the tests step in the same
     * unit. [ms] is the timestamp of the window, not a frame.
     */
    private fun AdaptiveResolutionGovernor.window(
        ms: Long,
        measured: Double,
        requested: Int = 10,
    ): Decision = observe(ms, measured, requested)

    // ── Downgrade ───────────────────────────────────────────────

    @Test
    fun `a device far below the request eventually steps down`() {
        val g = governor()
        g.anchor(1280, 720)
        // 5 fps against a 10 fps ask. Measured on a Sony F3311 asked for 24 fps
        // and delivering 10.1: 42% of the request, and the shortfall this whole
        // class exists to respond to.
        //
        // Four windows, not three: the first only starts the dwell timer, and
        // downHoldMs is 3 s, so the earliest a decision can be made is 3 s after
        // the first bad reading.
        assertEquals(Decision.Action.NONE, g.window(1_000, 5.0).action)
        assertEquals(Decision.Action.NONE, g.window(2_000, 5.0).action)
        assertEquals(Decision.Action.NONE, g.window(3_000, 5.0).action)
        val d = g.window(4_000, 5.0)
        assertEquals(Decision.Action.RESOLUTION_DOWN, d.action)
        assertTrue("a downgrade must actually rebind", d.isRebind)
    }

    @Test
    fun `exactly the down threshold is not below it`() {
        val g = governor()
        g.anchor(1280, 720)
        // 6.0 of a 10 fps ask is exactly 60%, and the rule is a strict "less
        // than". This is the Sony F3311's worst measured number against a 10 fps
        // ask, so it is a boundary that will genuinely be straddled in the
        // field: the device must not degrade for hitting the line exactly.
        repeat(20) { i ->
            assertEquals(
                "degraded a device sitting exactly on the threshold (window $i)",
                Decision.Action.NONE,
                g.window(1_000L * (i + 1), 6.0).action,
            )
        }
        assertEquals("1280x720", g.rung().toString())
    }

    @Test
    fun `just under the down threshold does degrade`() {
        val g = governor()
        g.anchor(1280, 720)
        // 5.9 of 10 is one frame below the line, and must be treated as a
        // shortfall rather than as the dead band. A hysteresis band that
        // swallows the boundary is a band that will not react in the field.
        g.window(1_000, 5.9)
        g.window(2_000, 5.9)
        g.window(3_000, 5.9)
        assertEquals(
            "a reading one frame under the threshold was ignored",
            Decision.Action.RESOLUTION_DOWN, g.window(4_000, 5.9).action,
        )
    }

    @Test
    fun `the ladder descends one rung at a time and stops at the floor`() {
        val g = governor()
        g.anchor(1280, 720)
        var t = 0L
        val seen = mutableListOf<String>()
        // 200 windows of permanent shortfall: 40 changes allowed by the 10 s
        // cooldown, so the ladder has to bottom out and stop.
        repeat(200) {
            t += 1_000
            val d = g.window(t, 3.0)
            if (d.isRebind) {
                seen += d.rung.toString()
                t += g.settleMs   // the production caller rebinds; camera reopens
            }
        }
        assertTrue("ladder never moved: $seen", seen.isNotEmpty())
        assertEquals(
            "ladder must be strictly descending: $seen",
            seen.sortedByDescending { it.substringBefore('x').toInt() },
            seen,
        )
        assertEquals(
            "must stop at the floor, not keep degrading: $seen",
            AdaptiveResolutionGovernor.Rung(480, 270).toString(),
            seen.last(),
        )
    }

    @Test
    fun `below the floor the ladder asks for frames instead of shrinking further`() {
        val g = governor()
        g.anchor(1280, 720)
        var t = 0L
        var sawRelief = false
        var lowest: String? = null
        repeat(400) {
            t += 1_000
            val d = g.window(t, 3.0)
            if (d.action == Decision.Action.FPS_RELIEF) sawRelief = true
            if (d.isRebind) {
                lowest = d.rung.toString()
                t += g.settleMs
            }
            // Every decision, relief included, opens a settle window: the
            // relief pushes a new capture request and the camera needs a moment
            // before the first frame at the new rate is a measurement of
            // anything.
            if (d.action != Decision.Action.NONE) t += g.settleMs
        }
        assertTrue("never fell back to asking for more frames", sawRelief)
        assertEquals(
            "resolution went below the floor: $lowest",
            AdaptiveResolutionGovernor.Rung(480, 270).toString(),
            lowest,
        )
    }

    @Test
    fun `fps relief is capped so a slow phone is not asked for 60 fps`() {
        val g = governor()
        g.anchor(480, 270)
        var t = 0L
        repeat(300) {
            t += 1_000
            val d = g.window(t, 2.0, requested = 30)
            // Only a decision carries a frame rate; NONE means "no change", and
            // the 0 in it is not a frame rate anybody will be asked for.
            if (d.action == Decision.Action.FPS_RELIEF) {
                assertTrue(
                    "effective fps out of range: ${d.fps}",
                    d.fps in 1..30,
                )
            }
            t += g.settleMs
        }
        assertEquals(
            "relief must stop after maxReliefSteps",
            AdaptiveResolutionGovernor.maxReliefSteps,
            g.reliefSteps(),
        )
        assertTrue(
            "effective fps exceeded the camera ceiling: ${g.effectiveFps(30)}",
            g.effectiveFps(30) <= AdaptiveResolutionGovernor.MAX_EFFECTIVE_FPS,
        )
    }

    // ── Hysteresis: the reason this is not a comparator ─────────

    @Test
    fun `a shortfall shorter than the dwell time changes nothing`() {
        val g = governor()
        g.anchor(1280, 720)
        // Three bad windows, 1 s apart: downHoldMs is 3 s, and the first only
        // starts the timer, so this is the earliest a decision may be made. A
        // blip of one or two windows must change nothing.
        assertEquals(Decision.Action.NONE, g.window(1_000, 2.0).action)
        assertEquals(Decision.Action.NONE, g.window(2_000, 2.0).action)
        assertEquals(Decision.Action.NONE, g.window(3_000, 2.0).action)
        assertEquals(Decision.Action.RESOLUTION_DOWN, g.window(4_000, 2.0).action)
    }

    @Test
    fun `a measurement inside the dead band is neither credit nor blame`() {
        val g = governor()
        g.anchor(1280, 720)
        // 80% of a 10 fps ask. Too slow to call good, too fast to call bad, and
        // the normal state of a healthy phone asked for more than it needs.
        repeat(30) { i ->
            assertEquals(
                "dead band produced a decision at window $i",
                Decision.Action.NONE,
                g.window(1_000L * (i + 1), 8.0).action,
            )
        }
        assertEquals("dead band moved the ladder", "1280x720", g.rung().toString())
    }

    @Test
    fun `crossing the dead band resets the surplus timer instead of banking it`() {
        val g = governor()
        g.anchor(1280, 720)
        // Bank 9 good windows (upHoldMs is 10 s), then dip into the band.
        repeat(9) { i -> g.window(1_000L * (i + 1), 10.0) }
        g.window(10_000, 8.0)
        // The surplus must be gone: one more good window must not be enough to
        // trigger an upgrade on the strength of a streak that ended.
        g.window(11_000, 10.0)
        val d = g.window(12_000, 10.0)
        assertNotEquals(
            "banked surplus produced an upgrade one window after a dip",
            Decision.Action.RESOLUTION_UP, d.action,
        )
    }

    @Test
    fun `upgrading is slower than downgrading`() {
        val g = governor()
        assertTrue(
            "a surplus must wait longer than a shortfall",
            g.upHoldMs > g.downHoldMs,
        )
    }

    @Test
    fun `a device that hovers either side of a threshold does not rebind forever`() {
        val g = governor()
        g.anchor(1280, 720)
        var t = 0L
        var rebinds = 0
        // Alternate just under and just over the upgrade line, forever. A single
        // threshold would rebind on every window here.
        repeat(200) { i ->
            t += 1_000
            val measured = if (i % 2 == 0) 8.9 else 9.1
            if (g.window(t, measured).isRebind) {
                rebinds++
                t += g.settleMs
            }
        }
        // Every window in this run is inside the dead band on one side, so the
        // only way to get a rebind here at all is an unlucky alignment; what
        // matters is that it is bounded, not that it is zero.
        assertTrue("oscillated without bound: $rebinds rebinds", rebinds < 10)
    }

    // ── Cooldown and settle ─────────────────────────────────────

    @Test
    fun `the settle window swallows the measurements taken while the camera reopens`() {
        val g = governor()
        g.anchor(1280, 720)
        g.window(1_000, 2.0)
        g.window(2_000, 2.0)
        g.window(3_000, 2.0)
        val d = g.window(4_000, 2.0)
        assertEquals(Decision.Action.RESOLUTION_DOWN, d.action)
        // The rebind closes and reopens the camera. The frames in the next
        // window belong to a camera that is still opening and would report a
        // collapse; they must be discarded, not acted on.
        val reopened = 4_000L + g.settleMs
        assertEquals(
            "acted on a measurement from a camera that was still opening",
            Decision.Action.NONE, g.window(reopened + 500, 0.5).action,
        )
    }

    @Test
    fun `two changes cannot land closer together than the cooldown`() {
        val g = governor()
        g.anchor(1280, 720)
        var t = 0L
        var lastChangeAt = Long.MIN_VALUE
        repeat(300) {
            t += 1_000
            val d = g.window(t, 2.0)
            if (d.action != Decision.Action.NONE) {
                assertTrue(
                    "change at $t is only ${t - lastChangeAt}ms after the previous one",
                    lastChangeAt == Long.MIN_VALUE || t - lastChangeAt >= g.cooldownMs,
                )
                lastChangeAt = t
                t += g.settleMs
            }
        }
    }

    // ── Anchoring: the user's setting is the ceiling ────────────

    @Test
    fun `the ladder never climbs above the setting the user chose`() {
        val g = governor()
        g.anchor(1280, 720)
        var t = 0L
        repeat(200) {
            t += 1_000
            val d = g.window(t, 20.0)
            if (d.isRebind) t += g.settleMs
            assertTrue(
                "climbed to ${g.rung()} above the user's 1280x720",
                g.rung().pixels <= 1280L * 720L,
            )
        }
    }

    @Test
    fun `re-anchoring to the same size leaves a pending downgrade alone`() {
        val g = governor()
        g.anchor(1280, 720)
        g.window(1_000, 2.0)
        g.window(2_000, 2.0)
        g.window(3_000, 2.0)
        g.window(4_000, 2.0)
        val downgraded = g.rung()
        assertNotEquals("precondition: the ladder should have moved", "1280x720", downgraded.toString())
        // applyConfigToFields runs on every watchdog reopen. If anchor() reset on
        // an unchanged setting, every reopen would silently cancel the
        // downgrade and the phone would oscillate for the rest of the session.
        g.anchor(1280, 720)
        assertEquals("re-anchor cancelled the downgrade", downgraded, g.rung())
    }

    @Test
    fun `a genuine change of setting resets the ladder`() {
        val g = governor()
        g.anchor(1280, 720)
        g.window(1_000, 2.0)
        g.window(2_000, 2.0)
        g.window(3_000, 2.0)
        g.window(4_000, 2.0)
        assertNotEquals("precondition", "1280x720", g.rung().toString())
        // A user who picks a smaller size is not a device that failed at 720p.
        // 640x480 is not a rung, so it anchors to the largest rung at or below
        // it: binding above the request would make the problem worse.
        g.anchor(640, 480)
        assertEquals("640x360", g.rung().toString())
        assertEquals(0, g.reliefSteps())
    }

    @Test
    fun `a size that is not on the ladder anchors just under itself`() {
        val g = governor()
        // 800x600 is not a rung. It must not snap to 1920x1080, which would bind
        // above what was asked for, and it must not snap to the bottom either.
        g.anchor(800, 600)
        assertEquals("854x480", g.rung().toString())
    }

    @Test
    fun `anchoring above every rung goes to the top, not off the end`() {
        val g = governor()
        g.anchor(3840, 2160)
        assertEquals("1920x1080", g.rung().toString())
    }

    // ── The fps relief contract ─────────────────────────────────

    @Test
    fun `an explicit frame rate hands the relief straight back`() {
        val g = governor()
        g.anchor(480, 270)
        var t = 0L
        repeat(100) {
            t += 1_000
            g.window(t, 2.0, requested = 10)
            // Every decision opens a settle window, so the clock has to advance
            // past it for the next one to be heard.
            t += g.settleMs
        }
        val before = g.reliefSteps()
        assertTrue("precondition: expected relief to be banked, was $before", before > 0)
        // A user typing 10 fps means 10 fps. Raising it behind their back is the
        // app overruling the one thing they just asked for.
        g.clearRelief()
        assertEquals(0, g.reliefSteps())
        assertEquals(10, g.effectiveFps(10))
    }

    @Test
    fun `relief is added on top of the request and never above the camera ceiling`() {
        val g = governor()
        g.anchor(480, 270)
        var t = 0L
        repeat(200) {
            t += 1_000
            g.window(t, 2.0, requested = 28)
            t += g.settleMs
            assertTrue(
                "effective fps exceeded the ceiling: ${g.effectiveFps(28)}",
                g.effectiveFps(28) <= AdaptiveResolutionGovernor.MAX_EFFECTIVE_FPS,
            )
        }
        assertTrue(
            "28 fps plus relief should still be capped, was ${g.effectiveFps(28)}",
            g.effectiveFps(28) == AdaptiveResolutionGovernor.MAX_EFFECTIVE_FPS,
        )
    }

    @Test
    fun `fps relief never asks the camera to rebind`() {
        val g = governor()
        g.anchor(480, 270)
        var t = 0L
        repeat(200) {
            t += 1_000
            val d = g.window(t, 2.0)
            if (d.action == Decision.Action.FPS_RELIEF) {
                assertFalse(
                    "fps relief was treated as a rebind, which costs a black frame",
                    d.isRebind,
                )
            }
            t += g.settleMs
        }
    }

    // ── Degenerate input ────────────────────────────────────────

    @Test
    fun `a device that has stopped streaming is not degraded`() {
        val g = governor()
        g.anchor(1280, 720)
        var t = 0L
        // 0.2 fps is a broken camera, not a slow one. Shrinking the image makes
        // it slower, never faster.
        repeat(50) { i ->
            t += 1_000
            assertEquals(
                "degraded a camera that is not streaming (window $i)",
                Decision.Action.NONE, g.window(t, 0.2).action,
            )
        }
        assertEquals("1280x720", g.rung().toString())
    }

    @Test
    fun `a request of zero does not divide by zero`() {
        val g = governor()
        g.anchor(1280, 720)
        // requestedFps 0 coerced to 1: ratio is then enormous, so the only
        // requirement is that it returns rather than throwing.
        assertEquals(Decision.Action.NONE, g.window(1_000, 5.0, requested = 0).action)
    }

    @Test
    fun `a negative timestamp does not crash the dwell arithmetic`() {
        val g = governor()
        g.anchor(1280, 720)
        g.window(-1_000, 2.0)
        g.window(0, 2.0)
        g.window(1_000, 2.0)
        g.window(2_000, 2.0)
    }

    // ── Observability ───────────────────────────────────────────

    @Test
    fun `every change is logged so logcat can prove what happened`() {
        val g = governor()
        g.anchor(1280, 720)
        assertTrue("anchoring was not logged", logs.isNotEmpty())
        val before = logs.size
        g.window(1_000, 2.0)
        g.window(2_000, 2.0)
        g.window(3_000, 2.0)
        g.window(4_000, 2.0)
        assertTrue("a downgrade produced no log line", logs.size > before)
        assertTrue(
            "the log line does not name the direction: ${logs.last()}",
            logs.last().contains("DOWN"),
        )
    }

    @Test
    fun `telemetry reports the rung and the user setting separately`() {
        val g = governor()
        g.anchor(1280, 720)
        g.window(1_000, 2.0)
        g.window(2_000, 2.0)
        g.window(3_000, 2.0)
        g.window(4_000, 2.0)
        val t = g.telemetry()
        assertEquals("1280x720", t["user_rung"])
        assertNotEquals(
            "rung and user_rung are the same, so the ladder did not move",
            t["user_rung"], t["rung"],
        )
        // A downgraded stream must be visible as downgraded, not reported as
        // the resolution the user picked.
        assertTrue("rung did not report a size", (t["rung"] as String).contains("x"))
    }
}

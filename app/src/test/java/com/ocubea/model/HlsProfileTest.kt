package com.ocubea.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HlsProfile encodes a trade, so the tests check the shape of the trade rather
 * than just that the fields hold their defaults.
 */
class HlsProfileTest {

    /**
     * The invariant that matters: a segment can only start on an IDR, so a GOP
     * longer than the segment means most segments wait for a keyframe they
     * never get, the playlist advertises durations that do not arrive, and the
     * player drains. This is the one property that, if broken, produces a
     * stream that looks configured and plays nothing.
     */
    @Test
    fun `gop never exceeds the segment it has to fit in`() {
        val all = listOf(
            HlsProfile.DEFAULT,
            HlsProfile.LOW_LATENCY,
            HlsProfile.HIGH_QUALITY,
            HlsProfile.sanitized(HlsProfile.DEFAULT, 500, 10),
            HlsProfile.sanitized(HlsProfile.LOW_LATENCY, 3000, 0)
        )
        for (p in all) {
            val gopMs = if (p.keyFrameIntervalSec == 0) 0 else p.keyFrameIntervalSec * 1000
            assertTrue(
                "segment=${p.segmentMs} gop=${p.keyFrameIntervalSec} — segment krotszy niz GOP",
                gopMs <= p.segmentMs
            )
        }
    }

    /**
     * The point of the high-quality profile is a longer GOP than low latency
     * has. If sanitized() ever collapses them to the same value, the profile
     * exists but does nothing, and the user pays latency for no bitrate.
     */
    @Test
    fun `high quality has a sparser gop than low latency`() {
        assertTrue(
            "high quality GOP ${HlsProfile.HIGH_QUALITY.keyFrameIntervalSec}s " +
                "powinien byc dluzszy niz low latency " +
                "${HlsProfile.LOW_LATENCY.keyFrameIntervalSec}s",
            HlsProfile.HIGH_QUALITY.keyFrameIntervalSec >
                HlsProfile.LOW_LATENCY.keyFrameIntervalSec
        )
        assertTrue(
            "high quality powinno miec dluzsze segmenty",
            HlsProfile.HIGH_QUALITY.segmentMs > HlsProfile.LOW_LATENCY.segmentMs
        )
    }

    /**
     * Low latency has to mean lower latency, not just different numbers. hls.js
     * waits liveSyncDurationCount segments before playing, so a profile that
     * keeps the default count while shortening segments does not get the
     * player to the live edge any sooner.
     */
    @Test
    fun `low latency is actually more aggressive than default`() {
        assertTrue(
            HlsProfile.LOW_LATENCY.segmentMs < HlsProfile.DEFAULT.segmentMs
        )
        assertTrue(
            HlsProfile.LOW_LATENCY.liveSyncDurationCount <
                HlsProfile.DEFAULT.liveSyncDurationCount
        )
        assertTrue(
            HlsProfile.LOW_LATENCY.maxBufferLength < HlsProfile.DEFAULT.maxBufferLength
        )
    }

    /** Nonsensical input from the API is clamped, not accepted. */
    @Test
    fun `sanitized clamps a segment below one frame`() {
        val p = HlsProfile.sanitized(HlsProfile.DEFAULT, segmentMs = 1, keyFrameIntervalSec = 0)
        assertTrue("1ms to nie jest segment, tylko blad", p.segmentMs >= 100)
    }

    @Test
    fun `sanitized clamps an absurd segment`() {
        val p = HlsProfile.sanitized(HlsProfile.DEFAULT, segmentMs = 999_999, keyFrameIntervalSec = 5)
        assertTrue("6s to maksimum, wiecej to juz bufor", p.segmentMs <= 6000)
    }

    /** A GOP longer than the segment is the failure mode described above. */
    @Test
    fun `sanitized holds a gop that outruns the segment`() {
        val p = HlsProfile.sanitized(HlsProfile.DEFAULT, segmentMs = 400, keyFrameIntervalSec = 9)
        assertTrue(
            "GOP 9s w segmencie 400ms — segmenty nigdy nie dostana IDR",
            p.keyFrameIntervalSec * 1000 <= p.segmentMs
        )
    }

    /**
     * 0 is legal and means "keyframe on every frame", which is exactly what low
     * latency asks for. Clamping it away would silently turn a short-segment
     * stream into a slow one.
     */
    @Test
    fun `sanitized keeps a zero gop as zero`() {
        val p = HlsProfile.sanitized(HlsProfile.DEFAULT, segmentMs = 500, keyFrameIntervalSec = 0)
        assertEquals(0, p.keyFrameIntervalSec)
    }

    /** The player-facing numbers come from the base, not from a reset. */
    @Test
    fun `sanitized keeps the player settings of its base`() {
        val p = HlsProfile.sanitized(
            base = HlsProfile.LOW_LATENCY,
            segmentMs = 900,
            keyFrameIntervalSec = 0
        )
        assertEquals(
            "sanitized nie moze cofnac ustawien odtwarzacza",
            HlsProfile.LOW_LATENCY.liveSyncDurationCount, p.liveSyncDurationCount
        )
        assertEquals(HlsProfile.LOW_LATENCY.maxBufferLength, p.maxBufferLength)
    }

    /** The API takes one word, so of() must map exactly the two we accept. */
    @Test
    fun `of maps the toggle to a real profile`() {
        assertEquals(HlsProfile.LOW_LATENCY, HlsProfile.of(true))
        assertEquals(HlsProfile.DEFAULT, HlsProfile.of(false))
    }

    /**
     * A profile that survives a restart unchanged, so a client reading it back
     * does not see a value that differs from what was set.
     */
    @Test
    fun `a profile round trips through the api unchanged`() {
        for (p in listOf(HlsProfile.DEFAULT, HlsProfile.LOW_LATENCY, HlsProfile.HIGH_QUALITY)) {
            val again = HlsProfile.sanitized(
                base = p,
                segmentMs = p.segmentMs,
                keyFrameIntervalSec = p.keyFrameIntervalSec
            )
            assertEquals(p, again)
        }
    }

    // ── of(name) / name(): the API's own vocabulary ────────────
    //
    // `StreamServer.handleHlsProfile` answers with `HlsProfile.name(p)` and
    // accepts `HlsProfile.of(set)`. These two are the whole client-facing
    // contract, and they used to be duplicated in the HTTP handler's own
    // `when`, which is how a name and a model drifted apart unnoticed. Now
    // that the handler delegates, the pair itself is what has to hold.

    /**
     * The round trip the API depends on: a profile's name must parse back to
     * that exact profile. `GET /hls/profile` reports a name and
     * `GET /hls/profile?set=<name>` accepts one, so if `name()` ever emitted a
     * word `of()` does not know, a client that read the current profile and
     * echoed it back would be handed a 400 on a value the server itself
     * printed.
     */
    @Test
    fun `every profile answers to a name that parses back to it`() {
        for (p in listOf(HlsProfile.DEFAULT, HlsProfile.LOW_LATENCY, HlsProfile.HIGH_QUALITY)) {
            val name = HlsProfile.name(p)
            assertEquals(
                "of(name(p)) must return p; the API reports '" + name +
                    "' and accepts it back, so a mismatch makes the profile " +
                    "unsettable through its own reported name",
                p, HlsProfile.of(name),
            )
        }
    }

    /**
     * The names have to be distinct. Two profiles sharing a name would make
     * the round trip above pass while `of(name)` still picked the wrong one,
     * and the user could never select the second profile at all.
     */
    @Test
    fun `the profiles have distinct names`() {
        val names = listOf(
            HlsProfile.DEFAULT, HlsProfile.LOW_LATENCY, HlsProfile.HIGH_QUALITY,
        ).map { HlsProfile.name(it) }
        assertEquals("each profile needs its own name, got $names", names.size, names.toSet().size)
    }

    /**
     * The canonical name is what `name()` must return, and the aliases only
     * exist so a client can say `on` instead of `low`. Asserted through the
     * round trip rather than against a literal list, so a profile added later
     * does not need this test edited to stay correct.
     */
    @Test
    fun `every alias resolves to the profile that owns the name`() {
        val aliases = mapOf(
            "low" to HlsProfile.LOW_LATENCY,
            "on" to HlsProfile.LOW_LATENCY,
            "true" to HlsProfile.LOW_LATENCY,
            "high" to HlsProfile.HIGH_QUALITY,
            "quality" to HlsProfile.HIGH_QUALITY,
            "default" to HlsProfile.DEFAULT,
            "off" to HlsProfile.DEFAULT,
            "false" to HlsProfile.DEFAULT,
            "" to HlsProfile.DEFAULT,
        )
        for ((alias, expected) in aliases) {
            assertEquals(
                "of(\"$alias\") must resolve to ${HlsProfile.name(expected)}, because " +
                    "that is the name name() reports for it",
                expected, HlsProfile.of(alias),
            )
        }
    }

    /**
     * A client's casing and padding are not the server's problem, but a wrong
     * word is: the handler answers 400 on null, so `of()` returning null for
     * something it should have recognised shows up as "set must be low, high or
     * default" for a value the user can see is spelled correctly.
     */
    @Test
    fun `a name is matched after trimming and case folding`() {
        for (spelling in listOf("LOW", "low", "Low", "  low  ", "\tlow\n")) {
            assertEquals(
                "of(\"$spelling\") should match the same profile as of(\"low\")",
                HlsProfile.LOW_LATENCY, HlsProfile.of(spelling),
            )
        }
    }

    @Test
    fun `an unknown name is refused rather than silently defaulting`() {
        // The important half of the contract: of() must NOT fall back to
        // DEFAULT for a name it does not know, or `?set=lo` would quietly
        // change the stream to something the client did not ask for and report
        // success.
        for (bogus in listOf("bogus", "lo", "med", "low,high", "low high", "1", "null")) {
            assertNull(
                "an unrecognised profile name must yield null so the handler " +
                    "answers 400 instead of applying a profile the client did " +
                    "not ask for: [$bogus]",
                HlsProfile.of(bogus),
            )
        }
    }

    /**
     * `name()` matches on the WHOLE profile value, so a profile whose numbers
     * were changed by `sanitized()` matches no branch and falls to `else` —
     * it reports "default" whatever it was derived from.
     *
     * This is a real limitation of the API contract and it is pinned here so
     * it is a decision rather than a surprise. `sanitized()` is not on any
     * live path: `StreamServer.handleHlsProfile` assigns the profile from
     * `of(name)` straight to `cameraManager.hlsProfile`, so a running stream
     * always holds one of the three constants and the name is always right.
     * If `sanitized()` is ever wired into a handler, this test is the one that
     * says the label has to start tracking the adjusted values.
     */
    @Test
    fun `a sanitized profile reports default because name matches on the whole value`() {
        val tweaked = HlsProfile.sanitized(HlsProfile.LOW_LATENCY, segmentMs = 333, keyFrameIntervalSec = 0)
        assertEquals(
            "name() compares the whole data class, so an adjusted profile " +
                "matches no branch. If sanitized() is ever used on a live " +
                "path this stops being true and name() has to be taught to " +
                "report the base it was derived from.",
            "default", HlsProfile.name(tweaked),
        )
        assertTrue(
            "and the numbers really did change, so the name alone is not a " +
                "complete description of the stream",
            tweaked.segmentMs != HlsProfile.LOW_LATENCY.segmentMs,
        )
    }

    /**
     * The round trip therefore only holds for the three named profiles, which
     * is exactly the set the API can put into a live session. Stated
     * explicitly so the limitation above has a stated boundary: if `of()` ever
     * starts producing adjusted profiles, this stops holding and both the
     * round trip and the reported name need revisiting together.
     */
    @Test
    fun `the name round trip covers exactly the profiles the api can select`() {
        val selectable = listOf("low", "high", "default")
            .map { HlsProfile.of(it)!! }
        for (p in selectable) {
            assertEquals(
                "every profile the API can put into a running session must " +
                    "round trip through its reported name",
                p, HlsProfile.of(HlsProfile.name(p)),
            )
        }
    }

    /**
     * What the API actually does in one step: take a name, then push it
     * through the same sanitiser the settings path uses. A name that survives
     * `of()` but not `sanitized()` would come back from the settings screen
     * with different numbers than the profile it selected, and the stream
     * would not be the one that was asked for.
     */
    @Test
    fun `every selectable name survives the sanitiser unchanged`() {
        for (alias in listOf("low", "high", "default", "on", "off", "true", "false", "")) {
            val selected = HlsProfile.of(alias)!!
            val afterSettings = HlsProfile.sanitized(
                base = selected,
                segmentMs = selected.segmentMs,
                keyFrameIntervalSec = selected.keyFrameIntervalSec,
            )
            assertEquals(
                "selecting \"$alias\" and then reading its numbers back must " +
                    "not change them, or the profile the API named is not the " +
                    "profile the stream runs",
                selected, afterSettings,
            )
        }
    }
}

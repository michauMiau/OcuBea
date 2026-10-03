package com.ocubea.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The invariant the 2026-10-02 performance pass needed and did not have.
 *
 * That pass found the HAL delivering roughly twice what the app published, and
 * no counter covered the interval between a frame arriving at `analyzeFrame`
 * and `frameCounter++`. `Metrics.timer(FRAME_ANALYZE)` spans the whole method,
 * so it cannot separate "never arrived" from "arrived and died" either.
 *
 * The rule these tests pin is the accounting identity:
 *
 *     entered == published + returnedEarly + skippedNoConsumer + saturated
 *
 * Every entered frame must land in exactly one bucket. While that holds,
 * `unaccounted()` is 0 and a non-zero value means frames are dying somewhere
 * nobody counts. Without it the gap is invisible, which is precisely how the
 * earlier pass reached a confident wrong answer.
 *
 * These are behavioural tests on the extracted decision object, not source
 * scans. The previous version of this file read
 * `CameraManager.kt` and asserted that `counter++` appeared before
 * `if (!isStreaming)` — which passes even if the counters are never read by
 * anything, and is the same "assert the source looks right" shape this repo has
 * had to undo before. The wiring itself is pinned separately by
 * `arrivalIsWiredIntoAnalyzeFrame` below, which is the one thing a pure test
 * genuinely cannot reach.
 */
class FrameArrivalAccountTest {

    private fun account() = FrameArrivalAccount()

    /** One frame, end to end, must leave unaccounted() at zero. */
    @Test
    fun aSingleAcceptedFrameIsFullyAccounted() {
        val a = account()
        assertEquals(FrameArrivalAccount.Outcome.ACCEPTED, a.observe(true, 1_000L, 0L, 15))
        a.recordPublished()
        assertEquals(1L, a.entered)
        assertEquals(1L, a.published)
        assertEquals(0L, a.unaccounted())
    }

    @Test
    fun aFrameAcceptedButNeverPublishedIsVisibleAsUnaccounted() {
        val a = account()
        a.observe(true, 1_000L, 0L, 15)
        // No recordPublished(): the frame died between the limiter and the
        // encoder. This is the case the pass could not see.
        assertEquals("the gap must not be silently absorbed", 1L, a.unaccounted())
        assertEquals(1L, a.lost())
    }

    @Test
    fun theFpsLimiterIsCountedAsAnEarlyReturn() {
        val a = account()
        // The account reads lastAcceptedNanos == 0 as "no frame accepted yet"
        // and skips the limiter entirely, so the accepted frame needs a
        // non-zero timestamp or this test never reaches the branch it claims
        // to check. nanoTime() can legitimately return 0, which is why the
        // sentinel is the value and not a separate flag.
        val accepted = 1_000_000_000L
        assertEquals(FrameArrivalAccount.Outcome.ACCEPTED, a.observe(true, accepted, 0L, 15))
        // At 15 fps the interval is 66_666_666 ns. A frame 10 ms after that
        // accepted one is too soon.
        assertEquals(
            FrameArrivalAccount.Outcome.LIMITED_BY_FPS,
            a.observe(true, accepted + 10_000_000L, accepted, 15),
        )
        // The zero sentinel really does bypass the limiter: same elapsed time,
        // no accepted frame yet, so it passes.
        assertEquals(
            FrameArrivalAccount.Outcome.ACCEPTED,
            account().observe(true, accepted + 10_000_000L, 0L, 15),
        )
        assertEquals(2L, a.entered)
        assertEquals(1L, a.returnedEarly)
        assertEquals(0L, a.published)
        // The accepted frame has not been published, so it *is* currently
        // unaccounted — and that is correct. unaccounted() is not "a leak"; it
        // is the gap between "the analyzer accepted this frame" and "something
        // finished with it", which is non-zero for every frame that is still
        // in the encoder pool. It only becomes a finding when it keeps
        // growing at steady state with nothing publishing.
        assertEquals(
            "the accepted-but-unpublished frame is the one open item here",
            1L,
            a.unaccounted(),
        )
        // Once it publishes, the identity closes. This is the assertion that
        // matters: no frame may vanish between accepted and published.
        a.recordPublished()
        assertEquals(0L, a.unaccounted())
    }

    @Test
    fun theLimiterIntervalFollowsTheTargetRate() {
        // The limiter compares against the last *accepted* frame, so each arm
        // has to establish one first. 15 fps -> 66.6 ms, so 100 ms later passes.
        // Non-zero accepted timestamp for the reason above.
        val accepted = 1_000_000_000L
        val a = account()
        assertEquals(FrameArrivalAccount.Outcome.ACCEPTED, a.observe(true, accepted, 0L, 15))
        // 15 fps -> 66.6 ms, so 100 ms later is allowed through.
        assertEquals(
            FrameArrivalAccount.Outcome.ACCEPTED,
            a.observe(true, accepted + 100_000_000L, accepted, 15),
        )
        // 5 fps -> 200 ms, so the same 100 ms is refused.
        val b = account()
        b.observe(true, accepted, 0L, 5)
        assertEquals(
            FrameArrivalAccount.Outcome.LIMITED_BY_FPS,
            b.observe(true, accepted + 100_000_000L, accepted, 5),
        )
    }

    @Test
    fun aFrameArrivingWhileNotStreamingIsAnEarlyReturnNotALoss() {
        val a = account()
        assertEquals(
            FrameArrivalAccount.Outcome.NOT_STREAMING,
            a.observe(false, 1_000L, 0L, 15),
        )
        assertEquals(1L, a.entered)
        assertEquals(1L, a.returnedEarly)
        assertEquals("winding down is deliberate, not a loss", 0L, a.unaccounted())
    }

    /**
     * The bug that started this: `null_bitmaps` counted a deliberate skip and
     * was read as a `toBitmap()` failure. Both must stay in separate buckets,
     * or the same misreading returns.
     */
    @Test
    fun skippingWithNoConsumerIsNotCountedAsAFailure() {
        val a = account()
        // The skip happens on the path where jpegNeeded was false, which is
        // after the limiter accepted the frame. Recording it puts the frame in
        // the skip bucket rather than leaving it looking lost.
        a.observe(true, 0L, 0L, 15)
        a.recordSkippedNoConsumer()
        assertEquals(1L, a.skippedNoConsumer)
        assertEquals(1L, a.entered)
        assertEquals(0L, a.published)
        assertEquals("a skip is deliberate, so nothing is unaccounted", 0L, a.unaccounted())
    }

    @Test
    fun aSaturatedDropIsAccountedSeparatelyFromALimiterSkip() {
        val a = account()
        a.observe(true, 0L, 0L, 15)
        a.recordSaturated()
        assertEquals(1L, a.droppedSaturated)
        assertEquals(0L, a.returnedEarly)
        assertEquals(0L, a.unaccounted())
    }

    /** Many frames, mixed outcomes: the identity must hold in aggregate. */
    @Test
    fun theIdentityHoldsAcrossAMixedRun() {
        val a = account()
        // Non-zero, for the same reason: the limiter is skipped entirely
        // while lastAcceptedNanos is 0.
        var last = 1_000_000_000L
        var accepted = 0
        var limited = 0
        // 5 seconds at 15 fps from a 24 fps source.
        for (i in 1..120) {
            val now = last + i * 41_666_666L
            when (a.observe(true, now, last, 15)) {
                FrameArrivalAccount.Outcome.ACCEPTED -> {
                    last = now
                    accepted++
                }
                FrameArrivalAccount.Outcome.LIMITED_BY_FPS -> limited++
                FrameArrivalAccount.Outcome.NOT_STREAMING -> {}
            }
        }
        assertEquals(120L, a.entered)
        assertEquals(limited.toLong(), a.returnedEarly)
        assertTrue("the run must contain both outcomes", accepted > 0 && limited > 0)

        // Give every accepted frame a fate, without any frame getting two.
        // published = accepted / 2, skipped = accepted / 3, saturated = the
        // rest, chosen so the three counts sum to exactly `accepted`. Using
        // independent ratios like half-and-a-quarter left a residue that this
        // test then misread as a leak; the residue was just unrecorded frames.
        val willPublish = accepted / 2
        val willSkip = accepted / 3
        val willSaturate = accepted - willPublish - willSkip
        repeat(willPublish) { a.recordPublished() }
        repeat(willSkip) { a.recordSkippedNoConsumer() }
        repeat(willSaturate) { a.recordSaturated() }
        assertEquals("the three post-acceptance buckets must be disjoint",
            accepted, willPublish + willSkip + willSaturate)

        // The invariant: entered partitions into the frames refused up front and
        // the frames accepted, and every accepted frame reached exactly one
        // fate, so nothing is left unaccounted.
        assertEquals(
            "entered must equal refused + published + skipped + saturated + in-flight",
            a.entered,
            a.returnedEarly + a.published + a.skippedNoConsumer + a.droppedSaturated + a.unaccounted(),
        )
        assertEquals("every accepted frame was given a fate", 0L, a.unaccounted())
        // If the fates had overlapped -- the same frame published *and* skipped,
        // which is impossible -- unaccounted() would go negative instead of
        // failing. That is worth pinning, because it is the failure mode that
        // sent me chasing a non-existent bug in the formula.
        assertTrue("unaccounted() must never be negative", a.unaccounted() >= 0)
    }

    @Test
    fun resetClearsEveryBucket() {
        val a = account()
        a.observe(true, 0L, 0L, 15)
        a.recordPublished()
        a.observe(true, 1L, 0L, 15)
        a.reset()
        assertEquals(0L, a.entered)
        assertEquals(0L, a.published)
        assertEquals(0L, a.returnedEarly)
        assertEquals(0L, a.skippedNoConsumer)
        assertEquals(0L, a.droppedSaturated)
        assertEquals(0L, a.unaccounted())
    }

    /**
     * The one thing a pure object test cannot reach: that `analyzeFrame` really
     * routes its decision and its four accounting points through the account.
     *
     * The previous version asserted that certain substrings were *present* in
     * CameraManager.kt. Five mutations sailed through it — including replacing
     * `arrival.observe(...)` with a hardcoded ACCEPTED, which leaves the string
     * "arrival.observe(" on screen while counting nothing. Presence of a name
     * is not evidence that the name is called.
     *
     * So this reads the source and *evaluates* it: it extracts the telemetry
     * expressions by name and evaluates each one against a fresh account with
     * known counters. A wrong formula, a hardcoded 0, or a dropped increment
     * all produce different numbers, and the assertion is on the numbers.
     *
     * This is still a source test by necessity — `analyzeFrame` needs an
     * ImageProxy, a CameraX pipeline and a running HAL. But it checks values,
     * not words, which is what makes it able to fail.
     */
    @Test
    fun telemetryReportsTheAccountsRealCounters() {
        val text = java.io.File("src/main/java/com/ocubea/camera/CameraManager.kt").readText()

        // Build an account whose every counter is distinguishable, so any
        // formula that mixes them up lands on a different answer.
        //
        // Every entered frame must get exactly one fate. A frame cannot be
        // published AND skipped, so the four post-acceptance fates need four
        // separate accepted frames.
        //
        // Two earlier drafts of this test were wrong and each looked like it
        // had found a real defect: one handed three fates to a single accepted
        // frame, and one refused to record a fourth. Both showed up as
        // `unaccounted()` disagreeing with a hand-computed total. The lesson
        // worth keeping is that a counter test has to walk a physically
        // possible run, and the arithmetic has to come from the account rather
        // than from a literal in the assertion.
        val a = account()
        // Offsets are 100 ms apart: long enough to clear the 66.6 ms interval
        // at 15 fps, so each of these really is accepted. An earlier version
        // spaced them by 10 ns, which the limiter correctly refused, and the
        // test failed while looking like it had found a real defect.
        val STEP = 100_000_000L
        a.observe(false, ACC, 0L, 15)                  // not streaming -> returnedEarly
        a.observe(true, ACC + 10, ACC, 15)             // 10 ns late  -> limited by fps
        a.observe(true, ACC + STEP, ACC, 15)           // 100 ms late -> accepted
        a.observe(true, ACC + 2 * STEP, ACC, 15)       // accepted -> published
        a.observe(true, ACC + 3 * STEP, ACC, 15)       // accepted -> skipped
        a.observe(true, ACC + 4 * STEP, ACC, 15)       // accepted -> saturated
        a.observe(true, ACC + 5 * STEP, ACC, 15)       // accepted -> in flight
        a.recordSkippedNoConsumer()
        a.recordSaturated()
        a.recordPublished()

        assertEquals("7 observe() calls enter 7 frames", 7L, a.entered)
        // Two refusals, from two different checks: the winding-down gate runs
        // before the limiter, so a frame can only ever land in one of them.
        assertEquals("2 refused inside observe(), one per check", 2L, a.returnedEarly)
        assertEquals(1L, a.skippedNoConsumer)
        assertEquals(1L, a.droppedSaturated)
        assertEquals(1L, a.published)
        // The disjointness that makes the formula meaningful. Two refusals plus
        // three recorded fates account for five of the seven entered frames; the
        // other two accepted frames are still in flight.
        assertEquals(
            "refused + recorded fates must account for the rest",
            a.entered - 2L,
            a.published + a.returnedEarly + a.skippedNoConsumer + a.droppedSaturated,
        )
        assertEquals("two accepted frames are still in flight", 2L, a.unaccounted())

        for (field in listOf(
            "frames_entered", "frames_returned_early", "frames_published",
            "frames_lost", "frames_unaccounted",
        )) {
            assertTrue(
                "brak pola $field w telemetrii CameraManager — luka pozostaje niewidoczna",
                text.contains("\"$field\" to"),
            )
        }

        // The formula the production field must implement: entered minus
        // published. Both terms are read from the account so a change to the
        // scenario cannot silently invalidate the literal -- an earlier version
        // hardcoded 4 and went stale the moment a frame was added.
        assertEquals(
            "frames_lost must be entered - published",
            a.entered - a.published,
            a.lost(),
        )
        assertEquals("with 7 entered and 1 published, frames_lost is 6", 6L, a.lost())
        // 7 - 2 = 5 against 7 - 1 = 6, so the two formulas are distinguishable.
        assertTrue(
            "entered-returnedEarly must not coincide with entered-published here, " +
                "or the mutation that swaps the base could not be caught",
            (a.entered - a.returnedEarly) != a.lost(),
        )

        // unaccounted() must be the real accounting, never a constant 0. This
        // is what catches `"frames_unaccounted" to 0L`.
        //
        // Two frames are already in flight, so a third accepted frame takes the
        // count to three, and each record*() closes exactly one of them --
        // recording a fate for a frame closes that frame, not the whole gap.
        val before = a.unaccounted()
        a.observe(true, ACC + 6 * STEP, ACC, 15)
        assertEquals("the new frame was accepted, not refused", before + 1L, a.unaccounted())
        a.recordSkippedNoConsumer()
        assertEquals("one record closes one frame, not all of them", before, a.unaccounted())
        a.recordSaturated()
        assertEquals(before - 1L, a.unaccounted())
        a.recordPublished()
        assertEquals("nothing is in flight once every frame has a fate", 0L, a.unaccounted())
        assertTrue("unaccounted() must never go negative", a.unaccounted() >= 0)
    }

    /**
     * `analyzeFrame` must call the account at every accounting point, and call
     * it with the real `isStreaming`. A no-op decision or a hardcoded `true`
     * would hide wind-down frames; both were green under a presence test.
     */
    /**
     * The telemetry formulas themselves, evaluated numerically.
     *
     * Checking that `"frames_lost" to` appears in the source is not the same as
     * checking what it computes: two mutations changed the right side to
     * `framesEntered - framesReturnedEarly` and to a literal `0L`, both left the
     * field name in place, and both went green. A name present in the file is
     * not a claim about its value.
     *
     * So this pulls the right-hand side out of CameraManager.kt, maps the
     * Kotlin identifiers onto numbers taken from a real account, and evaluates
     * it. A swapped base or a hardcoded constant lands on a different value and
     * fails here. The substitution is deliberately narrow -- only the five
     * arrival identifiers and `0L`/`1L` literals are understood -- so a change
     * the evaluator cannot express fails loudly instead of silently passing.
     */
    @Test
    fun telemetryFormulasComputeTheRealValues() {
        val text = java.io.File("src/main/java/com/ocubea/camera/CameraManager.kt").readText()

        // A run with every counter at a distinct, non-zero value, so any
        // formula that mixes two of them up lands somewhere else entirely.
        val a = account()
        val STEP = 100_000_000L
        a.observe(false, ACC, 0L, 15)              // returnedEarly
        a.observe(true, ACC + 10, ACC, 15)         // returnedEarly (limiter)
        a.observe(true, ACC + STEP, ACC, 15)       // published
        a.observe(true, ACC + 2 * STEP, ACC, 15)   // skipped
        a.observe(true, ACC + 3 * STEP, ACC, 15)   // saturated
        a.observe(true, ACC + 4 * STEP, ACC, 15)   // still in flight
        a.recordSkippedNoConsumer()
        a.recordSaturated()
        a.recordPublished()
        // entered=6 returnedEarly=2 published=1 skipped=1 saturated=1 inFlight=1

        val expected = mapOf(
            "frames_entered" to a.entered,
            "frames_returned_early" to a.returnedEarly,
            "frames_published" to a.published,
            "frames_lost" to a.lost(),
            "frames_unaccounted" to a.unaccounted(),
        )
        // The wrong bases a mutation might install, for the "must differ" check.
        val wrongLostBase = a.entered - a.returnedEarly

        val values = mapOf(
            "framesEntered" to a.entered,
            "framesReturnedEarly" to a.returnedEarly,
            "frameCounter" to a.published,
            "arrival.unaccounted()" to a.unaccounted(),
        )

        // Sanity on the fixture: the two candidate formulas for frames_lost
        // have to give different numbers, otherwise nothing below can fail.
        assertEquals("fixture: entered and published", 6L, a.entered)
        assertEquals("fixture: two refused", 2L, a.returnedEarly)
        assertEquals("fixture: one published", 1L, a.published)
        assertTrue(
            "the two frames_lost formulas must differ on this fixture",
            expected["frames_lost"] != wrongLostBase,
        )
        assertEquals("fixture: one frame in flight", 1L, a.unaccounted())

        // Now evaluate each field's expression as written in production.
        for ((field, want) in expected) {
            val expr = extractRhs(text, field)
            val got = evaluateExpr(expr, values)
            assertEquals(
                "$field ma wartość $got, a konto daje $want — wzór w telemetrii " +
                    "nie odzwierciedla prawdziwego licznika",
                want,
                got,
            )
        }
    }

    /** The text after `"field" to` up to the comma that ends the entry. */
    private fun extractRhs(text: String, field: String): String {
        val marker = "\"$field\" to "
        val at = text.indexOf(marker)
        assertTrue("brak pola $field w telemetrii CameraManager", at >= 0)
        val start = at + marker.length
        val end = text.indexOf(',', start)
        assertTrue("nie znaleziono końca wyrażenia dla $field", end > start)
        return text.substring(start, end).trim()
    }

    /**
     * Evaluates the tiny arithmetic subset the telemetry uses.
     *
     * Only `-`, `+`, parentheses and the identifiers in [values] are accepted;
     * anything else throws, because a formula the evaluator cannot parse must
     * never be reported as verified. Implemented by hand rather than by
     * shelling out, so the test stays a plain JVM unit test with no external
     * process and no dependency on the machine's Python.
     */
    private fun evaluateExpr(expr: String, values: Map<String, Long>): Long {
        var work = expr
        // Longest identifier first, so framesEntered cannot shadow a longer
        // name and arrival.unaccounted() is replaced before "unaccounted".
        for ((name, value) in values.entries.sortedByDescending { it.key.length }) {
            work = work.replace(name, value.toString())
        }
        assertFalse(
            "nie potrafię wycenić '$expr' (po podstawieniu: '$work') — " +
                "test nie może przejść, nie rozumiejąc wzoru",
            Regex("[A-Za-z_]").containsMatchIn(work),
        )
        val tokens = work.filterNot { it.isWhitespace() }.replace("L", "").toCharArray()
        var pos = 0
        fun peek(): Char? = tokens.getOrNull(pos)
        fun expect(c: Char) {
            assertEquals("oczekiwano '$c' w '$work' na pozycji $pos", c, peek())
            pos++
        }

        // parseSum and parseAtom are mutually recursive, and a local fun in
        // Kotlin cannot call a local fun declared after it, so they are held in
        // lateinits and assigned below rather than declared inline.
        lateinit var parseSum: () -> Long
        lateinit var parseAtom: () -> Long

        parseAtom = {
            if (peek() == '(') {
                pos++
                val v = parseSum()
                expect(')')
                v
            } else {
                val digits = StringBuilder()
                while (peek()?.isDigit() == true) {
                    digits.append(tokens[pos]); pos++
                }
                assertTrue("oczekiwano liczby w '$work' na pozycji $pos", digits.isNotEmpty())
                digits.toString().toLong()
            }
        }
        parseSum = {
            var acc = parseAtom()
            while (peek() == '+' || peek() == '-') {
                val op = tokens[pos]; pos++
                val rhs = parseAtom()
                acc = if (op == '+') acc + rhs else acc - rhs
            }
            acc
        }

        val result = parseSum()
        assertEquals("nadmiarowy tekst po wyrażeniu: '$work'", tokens.size, pos)
        return result
    }

    @Test
    fun analyzeFrameGatesOnTheAccountForEveryOutcome() {
        val text = java.io.File("src/main/java/com/ocubea/camera/CameraManager.kt").readText()

        // Cut the analyzeFrame body out so the check cannot be satisfied by a
        // call site anywhere else in a 2500-line file. The delimiter is the
        // method's own closing brace at its indentation level, so a nested
        // block cannot end the slice early.
        val after = text.substringAfter("fun analyzeFrame(", "")
        assertTrue("nie znaleziono analyzeFrame", after.length < text.length)
        val body = after.substringBefore("\n    }\n")
        assertTrue("analyzeFrame miałby podejrzanie krótkie ciało", body.length > 400)

        // The decision must be delegated, not hardcoded.
        assertTrue(
            "analyzeFrame must delegate its accept/skip decision to " +
                "arrival.observe(isStreaming, ...) — a hardcoded outcome counts nothing",
            body.contains("arrival.observe(isStreaming,"),
        )
        assertTrue(
            "observe must receive the real isStreaming, not a literal",
            !body.contains("arrival.observe(true,"),
        )
        assertTrue(
            "a literal ACCEPTED branch would bypass the account entirely",
            !body.contains("if (true) FrameArrivalAccount.Outcome.ACCEPTED"),
        )

        // Every bucket must have a recording point inside analyzeFrame, and
        // the four Outcome arms must all be handled. Dropping any one of these
        // left the suite green.
        for (needle in listOf(
            "arrival.recordPublished()",
            "arrival.recordSkippedNoConsumer()",
            "arrival.recordSaturated()",
        )) {
            assertTrue(
                "analyzeFrame nie zlicza zdarzenia $needle — jego kubełek zostanie pusty, " +
                    "a unaccounted() pokaże zjawiskową lukę",
                body.contains(needle),
            )
        }
        for (outcome in FrameArrivalAccount.Outcome.entries) {
            assertTrue(
                "analyzeFrame nie obsługuje wariantu ${outcome.name}",
                body.contains("FrameArrivalAccount.Outcome.$outcome ->"),
            )
        }
        // lastFrameNanos may only be updated on the accepted path.
        assertTrue(
            "lastFrameNanos musi być ustawiane tylko dla klatki zaakceptowanej",
            body.contains("ACCEPTED -> lastFrameNanos = now"),
        )
        // Every refusal arm must actually leave analyzeFrame. A `when` on an
        // enum in Kotlin is NOT exhaustive as a statement, so deleting a `return`
        // compiles, stays silent, and lets a frame the account already counted
        // as returned-early fall through into the JPEG path -- where it gets
        // published, so returnedEarly and published both claim it. That mutation
        // was green through a substring check, and it is the kind of silent
        // fallthrough this test exists to prevent.
        for (outcome in listOf("NOT_STREAMING", "LIMITED_BY_FPS")) {
            val arm = Regex(
                "FrameArrivalAccount\\.Outcome\\.$outcome -> \\{[^}]*\\}",
            ).find(body)
            assertTrue("brak ramienia $outcome", arm != null)
            assertTrue(
                "ramie $outcome musi kończyć się return — bez niego klatka " +
                    "zaakceptowana przez konto spada dalej i jest liczona dwa razy",
                arm!!.value.contains("return"),
            )
        }
        // And the when must be exhaustive: no else is needed because all three
        // outcomes are handled, so an enum constant added later cannot silently
        // fall through. Asserting the set of handled outcomes is covered above;
        // what matters here is that nothing catches them and swallows the
        // refusal.
        assertFalse(
            "gałąź LIMITED_BY_FPS nie może być przechwycona przez else",
            Regex("LIMITED_BY_FPS[\\s\\S]{0,120}?else").containsMatchIn(body),
        )
    }
}

/**
 * Shared non-zero "a frame was accepted" timestamp. Zero is the account's
 * "no frame accepted yet" sentinel, so a test that wants to exercise the
 * limiter has to start the clock somewhere else.
 */
private const val ACC = 1_000_000_000L

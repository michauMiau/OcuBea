package com.ocubea.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The exposure / white-balance / antibanding mapping, tested without a camera.
 *
 * These five keys were literals in `applySetting`:
 *
 *     "exposure", "exposure_lock" -> okText("ok")
 *     "whitebalance", "whitebalance_lock" -> okText("auto")
 *     "antibanding" -> okText("auto")
 *
 * Measured on the phone: five keys, every value, HTTP 200 "Ok", and no hardware
 * touched. A test that only inspected responses would have passed for that code,
 * which is the same reason `focusmode` and `rotate` had to be removed twice. So
 * what is pinned here is the decision itself: which action a value produces, what
 * it says, and — the part that matters most — when it refuses.
 *
 * The capability argument is the load-bearing one. Every test drives a camera that
 * reports something specific, because "the camera supports it" is the claim being
 * made to a client and it has to be checked against something.
 */
class ImageControlsPlanTest {

    /** A camera with the full set of controls, as a modern phone reports it. */
    private val full = ImageControlCaps(
        exposureSupported = true,
        exposureIndexMin = -6,
        exposureIndexMax = 6,
        // Most phone sensors step in sixths of a stop, not whole stops.
        exposureEvStep = 1.0 / 6.0,
        awbModes = setOf(
            AwbModes.AUTO, AwbModes.INCANDESCENT, AwbModes.FLUORESCENT,
            AwbModes.DAYLIGHT, AwbModes.CLOUDY_DAYLIGHT, AwbModes.SHADE,
        ),
        antibandingModes = setOf(
            AntiBandingModes.OFF, AntiBandingModes.HZ50,
            AntiBandingModes.HZ60, AntiBandingModes.AUTO,
        ),
    )

    /** A camera with exposure compensation and no presets of any kind. */
    private val bare = ImageControlCaps(
        exposureSupported = true,
        exposureIndexMin = 0,
        exposureIndexMax = 0,
        exposureEvStep = 1.0,
        awbModes = emptySet(),
        antibandingModes = emptySet(),
    )

    private fun plan(key: String, value: String, caps: ImageControlCaps = full) =
        ImageControlsPlan.resolve(key, value, caps)

    // ── the keys are routed, and nothing else is ─────────────────

    @Test
    fun everyOwnedKeyResolves() {
        ImageControlsPlan.KEYS.forEach { k ->
            assertTrue("$k must resolve", plan(k, "auto") != null || plan(k, "0") != null)
        }
    }

    @Test
    fun aKeyThisFileDoesNotOwnResolvesToNull() {
        // null is "no opinion", which must stay distinct from a refusal, or
        // applySetting's own branch for the key would never run.
        listOf("zoom", "focusmode", "rotation", "", "exposurecompensation")
            .forEach { k ->
                assertNull("$k must not resolve", plan(k, "0"))
            }
    }

    // ── exposure: EV in, index out ────────────────────────────────

    /**
     * The API sends EV; the camera counts indices.
     *
     * On this camera one index is a sixth of a stop, so `exposure=1` must become
     * index 6 and NOT index 1. Treating the number as an index is the bug that
     * would answer "Ok" to a request for a whole stop while applying a sixth of
     * one — the image would change, so a response-only test would still pass.
     */
    @Test
    fun evIsConvertedToAnIndexUsingTheCamerasOwnStep() {
        // Wide enough that the conversion itself is what is under test; a narrow
        // range would clamp first and hide the step arithmetic.
        val caps = full.copy(exposureIndexMin = -12, exposureIndexMax = 12)
        val oneStop = plan("exposure", "1", caps)!!
        assertEquals(ImageControlAction.SET_EXPOSURE_COMPENSATION, oneStop.action)
        assertEquals(6, oneStop.index)
        assertEquals(-6, plan("exposure", "-1", caps)!!.index)
        assertEquals(0, plan("exposure", "0", caps)!!.index)
        assertEquals(12, plan("exposure", "2", caps)!!.index)
    }

    /**
     * The reply echoes EV *and* the index, so a client sees stops and can verify.
     *
     * Both numbers are wanted: EV is what the API spoke, and the index is what the
     * camera was actually given. A reply carrying only one of them forces the
     * client to guess which the server applied.
     */
    @Test
    fun theReplyNamesBothEvAndTheIndex() {
        val caps = full.copy(exposureIndexMin = -12, exposureIndexMax = 12)
        val reply = plan("exposure", "1", caps)!!.reply
        assertTrue("EV missing from: $reply", reply.contains("1 EV"))
        assertTrue("index missing from: $reply", reply.contains("index 6"))
    }

    /**
     * A fractional step must not round away in the reply.
     *
     * A third of a stop per index is a real phone setting: index 1 is 0.333 EV, not
     * 0 and not 1. Printing the index alone, or rounding EV to a whole stop, would
     * tell the client it got a third of a stop and show it a different number.
     */
    @Test
    fun aFractionalStepIsPrintedAsTheFractionItIs() {
        val caps = full.copy(
            exposureIndexMin = -12, exposureIndexMax = 12, exposureEvStep = 1.0 / 3.0
        )
        // 0.5 EV is not a whole number of third-stop steps, so it lands on index 2
        // and the reply must show the 0.667 EV that really was requested of the
        // camera -- not 0.5, not 1, and not the bare index.
        val p = plan("exposure", "0.5", caps)!!
        assertEquals(2, p.index)
        val reply = p.reply
        assertTrue("expected a fraction in: $reply", reply.contains("0.667 EV"))
        // and the approximation is stated, not hidden
        assertTrue(reply.contains("asked for 0.5"))
    }

    @Test
    fun aWholeStopStepMeansOneIndexPerEv() {
        val caps = full.copy(exposureEvStep = 1.0)
        assertEquals(2, plan("exposure", "2", caps)!!.index)
    }

    /** A driver that reported no step must not divide by zero. */
    @Test
    fun anUnreportedStepFallsBackToOneIndexPerEv() {
        val caps = full.copy(exposureEvStep = 0.0)
        val p = plan("exposure", "-1", caps)!!
        assertEquals(ImageControlAction.SET_EXPOSURE_COMPENSATION, p.action)
        assertEquals(-1, p.index)
    }

    /**
     * Out-of-range EV is clamped, and the reply admits it.
     *
     * A client asking for +6 EV on a ±1 EV camera means "as bright as you can".
     * Refusing would make the key unreachable for the clients that want it most,
     * so it clamps — but the reply has to name the clamping, or the client
     * believes it got 6 EV. This is the same rule FocusModePlan applies to
     * macro, and the same one the old unconditional "ok" broke.
     */
    @Test
    fun outOfRangeExposureIsClampedAndTheReplySaysSo() {
        // +/-\-1 EV, so 6 is genuinely past the end rather than exactly on it.
        val caps = full.copy(exposureIndexMin = -1, exposureIndexMax = 1, exposureEvStep = 1.0)
        val tooBright = plan("exposure", "6", caps)!!
        assertEquals(1, tooBright.index)
        assertTrue("clamping must be stated: ${tooBright.reply}", tooBright.reply.contains("cannot reach"))
        assertTrue(tooBright.reply.contains("asked for 6"))

        val tooDark = plan("exposure", "-6", caps)!!
        assertEquals(-1, tooDark.index)
        assertTrue(tooDark.reply.contains("cannot reach"))

        // Exactly on the limit is not clamping, and must not claim to be.
        val edge = plan("exposure", "1", caps)!!
        assertTrue(!edge.reply.contains("cannot reach"))
    }

    @Test
    fun anInRangeExposureDoesNotClaimClamping() {
        val p = plan("exposure", "0")!!
        assertTrue(!p.reply.contains("cannot reach"))
        assertTrue(p.reply.contains("exposure"))
    }

    /**
     * A non-numeric exposure is refused, naming the real units.
     *
     * TelemetryHandler advertises `exposure: [auto, normal, long, short]`, so those
     * four are values a client can legitimately be handed by this very app. They
     * are exposure *presets*, not EV offsets, and there is no AE-mode control here
     * to honour them — so accepting them would be the unconditional ok again. The
     * refusal says what the key does take instead.
     */
    @Test
    fun aPresetWordIsRefusedRatherThanTreatedAsEv() {
        listOf("auto", "normal", "long", "short", "banana", "").forEach { v ->
            val p = plan("exposure", v)!!
            assertEquals("$v must not act", ImageControlAction.UNSUPPORTED, p.action)
            assertTrue("$v must carry a reason", p.refusal != null)
        }
    }

    @Test
    fun theExposureRefusalNamesTheUnits() {
        assertTrue(plan("exposure", "long")!!.refusal!!.contains("EV"))
    }

    // ── a camera that cannot compensate refuses ───────────────────

    /**
     * Every EV, including 0, is refused on a camera with no compensation.
     *
     * Zero is the case that matters: index 0 looks like a no-op, so it is exactly
     * the value a handler could answer "Ok" for while nothing exists to adjust,
     * and the client would go on believing `exposure` works on this device.
     */
    @Test
    fun noExposureSupportRefusesEveryValueIncludingZero() {
        val caps = full.copy(exposureSupported = false)
        listOf("-2", "-1", "0", "1", "2").forEach { v ->
            val p = plan("exposure", v, caps)!!
            assertEquals("$v must not act", ImageControlAction.UNSUPPORTED, p.action)
            assertTrue("$v must carry a reason", p.refusal != null)
        }
    }

    @Test
    fun theNoExposureSupportRefusalNamesTheRealCause() {
        val caps = full.copy(exposureSupported = false)
        val refusal = plan("exposure", "1", caps)!!.refusal!!
        assertTrue(refusal.contains("no exposure compensation"))
        assertTrue(refusal.contains("isExposureCompensationSupported"))
    }

    /** A camera reporting nothing at all must refuse rather than assume. */
    @Test
    fun aCameraThatReportsNothingRefusesEverythingItCouldAct() {
        val none = ImageControlCaps.NONE
        assertEquals(ImageControlAction.UNSUPPORTED, plan("exposure", "1", none)!!.action)
        assertEquals(ImageControlAction.UNSUPPORTED, plan("whitebalance", "auto", none)!!.action)
        assertEquals(ImageControlAction.UNSUPPORTED, plan("antibanding", "50", none)!!.action)
    }

    // ── the two locks ────────────────────────────────────────────

    @Test
    fun onLocksAndOffUnlocks() {
        listOf("on", "true", "1", "yes", "lock").forEach { v ->
            assertEquals("$v", true, plan("exposure_lock", v)!!.enabled)
        }
        listOf("off", "false", "0", "no", "unlock").forEach { v ->
            assertEquals("$v", false, plan("exposure_lock", v)!!.enabled)
        }
    }

    @Test
    fun caseAndWhitespaceDoNotMatter() {
        assertEquals(true, plan("exposure_lock", "  ON  ")!!.enabled)
        assertEquals(true, plan("whitebalance_lock", " TRUE ")!!.enabled)
        assertEquals(6, plan("exposure", " 1 ")!!.index)
        assertEquals(AwbModes.INCANDESCENT, plan("whitebalance", " Incandescent ")!!.mode)
    }

    /**
     * Each lock wires to its own request key.
     *
     * They are different camera2 keys, so a plan that sent both to one of them
     * would look right in a response-only test and freeze the wrong loop.
     */
    @Test
    fun theTwoLocksAreDifferentActions() {
        assertEquals(
            ImageControlAction.SET_EXPOSURE_LOCK,
            plan("exposure_lock", "on")!!.action
        )
        assertEquals(
            ImageControlAction.SET_WHITE_BALANCE_LOCK,
            plan("whitebalance_lock", "on")!!.action
        )
    }

    /** A lock value that is neither spelling is a typo, and typos are not guessed. */
    @Test
    fun anUnknownLockValueIsRefused() {
        listOf("maybe", "2", "onoff", "", "enabled").forEach { v ->
            listOf("exposure_lock", "whitebalance_lock").forEach { k ->
                val p = plan(k, v)!!
                assertEquals("$k=$v must not act", ImageControlAction.UNSUPPORTED, p.action)
                assertTrue("$k=$v must carry a reason", p.refusal != null)
            }
        }
    }

    @Test
    fun theLockRefusalNamesTheKeyItWasAskedAbout() {
        val ew = plan("exposure_lock", "maybe")!!.refusal!!
        val ww = plan("whitebalance_lock", "maybe")!!.refusal!!
        assertTrue(ew.contains("exposure_lock"))
        assertTrue(ww.contains("whitebalance_lock"))
        // and the two must not be indistinguishable
        assertTrue(ew != ww)
    }

    // ── white balance ────────────────────────────────────────────

    @Test
    fun everyApiWhiteBalanceValueMapsToOneMode() {
        assertEquals(AwbModes.AUTO, plan("whitebalance", "auto")!!.mode)
        assertEquals(AwbModes.AUTO, plan("whitebalance", "on")!!.mode)
        assertEquals(AwbModes.OFF, plan("whitebalance", "off")!!.mode)
        assertEquals(AwbModes.FLUORESCENT, plan("whitebalance", "fluorescent")!!.mode)
        assertEquals(AwbModes.INCANDESCENT, plan("whitebalance", "incandescent")!!.mode)
        assertEquals(AwbModes.DAYLIGHT, plan("whitebalance", "daylight")!!.mode)
        assertEquals(AwbModes.DAYLIGHT, plan("whitebalance", "sunny")!!.mode)
        assertEquals(AwbModes.CLOUDY_DAYLIGHT, plan("whitebalance", "cloudy")!!.mode)
    }

    @Test
    fun everyVocabularyValueIsAcceptedBySomeCamera() {
        // A word listed in WHITE_BALANCE that no camera can ever accept would be
        // advertised to clients and then always refused, so the check runs against
        // a camera that lists every mode the mapping can produce -- including
        // OFF, which `full` deliberately omits because most sensors lack it.
        val caps = full.copy(
            awbModes = setOf(
                AwbModes.OFF, AwbModes.AUTO, AwbModes.INCANDESCENT, AwbModes.FLUORESCENT,
                AwbModes.DAYLIGHT, AwbModes.CLOUDY_DAYLIGHT, AwbModes.SHADE,
            )
        )
        ImageControlsPlan.WHITE_BALANCE.forEach { v ->
            val p = plan("whitebalance", v, caps)!!
            assertEquals("$v must have an action", ImageControlAction.SET_WHITE_BALANCE, p.action)
            assertNull("$v must carry no refusal", p.refusal)
        }
    }

    /** Most sensors have no AWB OFF; that is a capability, not a vocabulary error. */
    @Test
    fun offIsRefusedOnACameraWithoutIt() {
        assertTrue(AwbModes.OFF !in full.awbModes)
        val p = plan("whitebalance", "off")!!
        assertEquals(ImageControlAction.UNSUPPORTED, p.action)
        assertTrue(p.refusal!!.contains("CONTROL_AWB_AVAILABLE_MODES"))
    }

    /** "on" means "run white balance", which is camera2 auto — and it says so. */
    @Test
    fun onIsAutoAndAdmitsIt() {
        assertEquals(AwbModes.AUTO, plan("whitebalance", "on")!!.mode)
        assertTrue(plan("whitebalance", "on")!!.reply.contains("auto"))
        // plain auto must not claim to have been an alias
        assertTrue(!plan("whitebalance", "auto")!!.reply.contains("whitebalance=on"))
    }

    @Test
    fun anUnknownWhiteBalanceIsRefusedWithTheAcceptedSet() {
        val p = plan("whitebalance", "banana")!!
        assertEquals(ImageControlAction.UNSUPPORTED, p.action)
        assertTrue(p.refusal!!.contains("banana"))
        assertTrue(p.refusal!!.contains("fluorescent"))
    }

    /**
     * A preset the camera does not list is refused, not sent anyway.
     *
     * camera2 rejects an unsupported AWB mode by throwing inside the session and
     * leaving the previous mode in place. Sending it and answering "Ok" would be
     * the unconditional ok one layer down. The refusal also names what the camera
     * does offer, so the client can pick a real value instead of guessing.
     */
    @Test
    fun aPresetTheCameraDoesNotOfferIsRefused() {
        val caps = full.copy(awbModes = setOf(AwbModes.AUTO, AwbModes.DAYLIGHT))
        val p = plan("whitebalance", "fluorescent", caps)!!
        assertEquals(ImageControlAction.UNSUPPORTED, p.action)
        assertTrue(p.refusal!!.contains("CONTROL_AWB_AVAILABLE_MODES"))
        // The request is quoted back, so the offered list is what must be checked:
        // fluorescent may not appear among the modes the camera says it has.
        val offered = ImageControlsPlan.namedModes(caps.awbModes)
        assertEquals("auto, daylight", offered)
        assertTrue("refusal must offer a real value: ${p.refusal}", p.refusal!!.contains("daylight"))
        assertTrue(!offered.contains("fluorescent"))
    }

    @Test
    fun aCameraWithNoWhiteBalanceModesRefusesEveryPreset() {
        ImageControlsPlan.WHITE_BALANCE.forEach { v ->
            val p = plan("whitebalance", v, bare)!!
            assertEquals("$v must not act", ImageControlAction.UNSUPPORTED, p.action)
            assertTrue(p.refusal != null)
        }
    }

    @Test
    fun theRefusalNamesWhatTheCameraActuallyOffers() {
        val caps = full.copy(awbModes = setOf(AwbModes.AUTO, AwbModes.SHADE))
        val refusal = plan("whitebalance", "daylight", caps)!!.refusal!!
        assertTrue(refusal.contains("auto"))
        assertTrue(refusal.contains("shade"))
    }

    // ── antibanding ──────────────────────────────────────────────

    @Test
    fun everyAntibandingValueMapsToOneMode() {
        assertEquals(AntiBandingModes.HZ50, plan("antibanding", "50")!!.mode)
        assertEquals(AntiBandingModes.HZ50, plan("antibanding", "50hz")!!.mode)
        assertEquals(AntiBandingModes.HZ60, plan("antibanding", "60")!!.mode)
        assertEquals(AntiBandingModes.HZ60, plan("antibanding", "60hz")!!.mode)
        assertEquals(AntiBandingModes.AUTO, plan("antibanding", "auto")!!.mode)
        assertEquals(AntiBandingModes.OFF, plan("antibanding", "off")!!.mode)
    }

    @Test
    fun everyAntibandingVocabularyValueIsAcceptedBySomeCamera() {
        ImageControlsPlan.ANTIBANDING.forEach { v ->
            assertEquals(
                "$v must have an action",
                ImageControlAction.SET_ANTIBANDING,
                plan("antibanding", v)!!.action
            )
        }
    }

    @Test
    fun anUnknownAntibandingIsRefusedWithTheAcceptedSet() {
        val p = plan("antibanding", "55")!!
        assertEquals(ImageControlAction.UNSUPPORTED, p.action)
        assertTrue(p.refusal!!.contains("55"))
        assertTrue(p.refusal!!.contains("50"))
    }

    /** 60Hz mains is not something every camera exposes; the driver decides. */
    @Test
    fun anAntibandingModeTheCameraLacksIsRefused() {
        val caps = full.copy(antibandingModes = setOf(AntiBandingModes.OFF, AntiBandingModes.AUTO))
        val p = plan("antibanding", "60", caps)!!
        assertEquals(ImageControlAction.UNSUPPORTED, p.action)
        assertTrue(p.refusal!!.contains("CONTROL_AE_AVAILABLE_ANTIBANDING_MODES"))
        // The refusal quotes the request, so "60" is legitimately in the text.
        // What must not appear is 60 among the modes the camera SAYS it offers.
        val offered = ImageControlsPlan.namedAntibanding(caps.antibandingModes)
        assertEquals("auto, off", offered)
        assertTrue(p.refusal!!.contains("it offers $offered"))
    }

    // ── the shape every refusal shares ───────────────────────────

    /**
     * A refusal carries no reply.
     *
     * A plan that both refuses and answers would let the success path win, which is
     * precisely the failure mode of the code this replaces.
     */
    @Test
    fun everyRefusalCarriesNoReply() {
        val caps = ImageControlCaps.NONE
        val cases = listOf(
            "exposure" to "1", "exposure_lock" to "maybe",
            "whitebalance" to "auto", "whitebalance_lock" to "maybe",
            "antibanding" to "50",
        )
        cases.forEach { (k, v) ->
            val p = plan(k, v, caps)!!
            assertEquals("$k=$v must refuse", ImageControlAction.UNSUPPORTED, p.action)
            assertEquals("$k=$v must not also answer", "", p.reply)
        }
    }

    @Test
    fun everyAcceptedRequestHasNoRefusal() {
        val cases = listOf(
            "exposure" to "0", "exposure_lock" to "on",
            "whitebalance" to "auto", "whitebalance_lock" to "off",
            "antibanding" to "50",
        )
        cases.forEach { (k, v) ->
            val p = plan(k, v)!!
            assertNull("$k=$v must carry no refusal", p.refusal)
            assertTrue("$k=$v must answer", p.reply.isNotEmpty())
        }
    }

    /**
     * The plan is a function of its arguments alone.
     *
     * No shared mutable state may leak between requests: the bug this guards is a
     * latch that carries a mode from one caller into the next answer. Asking twice
     * must give the same plan, and asking about a different value must not be
     * influenced by the first.
     */
    @Test
    fun resolutionIsRepeatableAndOrderIndependent() {
        val caps = full.copy(exposureEvStep = 1.0 / 3.0)
        val first = plan("exposure", "1", caps)!!.index
        plan("exposure", "-2", caps)
        plan("whitebalance", "cloudy", caps)
        assertEquals(first, plan("exposure", "1", caps)!!.index)
        assertEquals(3, first)
    }

    /** Every action must be reachable, or one branch is dead code. */
    @Test
    fun everyActionIsReachable() {
        val seen = setOf(
            plan("exposure", "0")!!.action,
            plan("exposure_lock", "on")!!.action,
            plan("whitebalance", "auto")!!.action,
            plan("whitebalance_lock", "on")!!.action,
            plan("antibanding", "50")!!.action,
            plan("exposure", "1", ImageControlCaps.NONE)!!.action,
        )
        assertEquals(ImageControlAction.entries.toSet(), seen)
    }
}

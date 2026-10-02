package com.ocubea.server

import com.ocubea.security.MotionLimits
import com.ocubea.security.MotionSoundPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The nine keys that answered HTTP 200 and changed nothing, tested without a
 * camera.
 *
 * Measured on a Sony F3311 against the running app, before this file existed:
 *
 *     overlay=on|off                      -> "ok"
 *     awake=on|off                        -> "ok"
 *     idle=on|off                         -> "ok"
 *     sound|sound_event|sound_timeout=... -> "ok"
 *     motion_limit=<any number>           -> "ok"
 *     motion_event=on|off                 -> "ok"
 *     motion_active=on|off                -> "ok"
 *     gps_active=on                       -> "false"
 *
 * Eleven requests, eleven successes, zero effects. This handler has had the
 * same lie removed from it three times before (focus, focusmode, rotate), and
 * each time the fix was a *pure function* whose tests passed whatever the
 * handler did with the result. So the tests below are not only about the
 * values: the last one asserts the shape that made the difference -- a key in
 * the API's vocabulary has to produce more than one outcome, or a 200 for
 * everything is still reachable.
 *
 * Note what these tests deliberately do NOT check: they cannot prove the handler
 * calls [BehaviorSettingsPlan], because the handler is not a unit-testable
 * object. `IpWebcamSettingSurfaceTest` reads its source and `StreamServerSettingsTest`
 * drives it over a socket. This file owns the decision table; the route is
 * proved elsewhere, and a test that claims otherwise would be the coverage
 * illusion `prove-the-check-can-fail` warns about.
 */
class BehaviorSettingsPlanTest {

    /**
     * Keys that must refuse every value, and whose uniformity is therefore
     * required rather than suspicious.
     *
     * Kept as data so the two tests that care about them cannot disagree about
     * which ones they are: this one skips them, and
     * `a key the device cannot do is refused for every value` asserts them.
     */
    private companion object {
        val ALWAYS_REFUSED = setOf("gps_active", "motion_active")
    }

    // ── overlay ────────────────────────────────────────────────────────────

    @Test
    fun `overlay on is applied while the camera is producing frames`() {
        val plan = BehaviorSettingsPlan.overlay("on", painterReady = true)
        assertEquals(SettingAction.APPLY, plan.action)
        assertEquals(true, plan.value)
        assertNull(plan.refusal)
    }

    @Test
    fun `overlay off is applied too`() {
        val plan = BehaviorSettingsPlan.overlay("off", painterReady = true)
        assertEquals(SettingAction.APPLY, plan.action)
        assertEquals(false, plan.value)
    }

    /**
     * The refusal that the old unconditional "ok" made impossible.
     *
     * With no frames there is nothing to draw on, so an "Ok" would be a claim
     * about a picture nobody receives.
     */
    @Test
    fun `overlay is refused when the camera is not producing frames`() {
        val plan = BehaviorSettingsPlan.overlay("on", painterReady = false)
        assertTrue(plan.isRefusal)
        assertNotNull(plan.refusal)
        assertTrue(requireRefusal(plan).contains("not producing frames"))
        // The refusal has to tell the client what DID happen, or "400" reads as
        // "try again later" and the client retries forever.
        assertTrue(requireRefusal(plan).contains("stored"))
    }

    @Test
    fun `overlay refuses a value outside the on off vocabulary`() {
        for (v in listOf("banana", "yes please", "", "onn", "2")) {
            val plan = BehaviorSettingsPlan.overlay(v, painterReady = true)
            assertTrue("$v must be refused", plan.isRefusal)
            assertTrue(requireRefusal(plan).contains(v))
            assertNull(plan.value)
        }
    }

    /** The spellings a real client sends must all work. */
    @Test
    fun `overlay accepts every documented on and off spelling`() {
        listOf("on", "ON", " true ", "1", "yes", "enable", "enabled").forEach {
            assertEquals(it, true, BehaviorSettingsPlan.overlay(it, true).value)
        }
        listOf("off", "OFF", " false ", "0", "no", "disable", "disabled").forEach {
            assertEquals(it, false, BehaviorSettingsPlan.overlay(it, true).value)
        }
    }

    // ── awake ──────────────────────────────────────────────────────────────

    @Test
    fun `awake on is applied when a window is on screen`() {
        val plan = BehaviorSettingsPlan.awake("on", windowPresent = true)
        assertEquals(SettingAction.APPLY, plan.action)
        assertEquals(true, plan.value)
    }

    /**
     * The whole reason this key is not `config.keepScreenOn = value`.
     *
     * FLAG_KEEP_SCREEN_ON is a window attribute. A foreground service has no
     * window, so from a headless camera there is literally nothing to hold
     * awake -- and answering "Ok" there is the same confirmation of nothing that
     * this whole file exists to end.
     */
    @Test
    fun `awake is refused with no window, and says the request was stored`() {
        val plan = BehaviorSettingsPlan.awake("on", windowPresent = false)
        assertTrue(plan.isRefusal)
        assertTrue(requireRefusal(plan).contains("no OcuBea window"))
        // The parsed value survives a refusal, because the handler stores it
        // before returning the 400 -- that is what makes the next onResume apply
        // it. Dropping it here would mean the stored state could never be set
        // by a request that arrived while the screen was locked.
        assertEquals(true, plan.value)
    }

    @Test
    fun `awake off is refused with no window too`() {
        // Clearing a flag on a window that is not there has nothing to clear.
        // Answering 200 would claim a change that cannot exist, which is the
        // "false" body this key used to send for gps_active.
        assertTrue(BehaviorSettingsPlan.awake("off", windowPresent = false).isRefusal)
    }

    // ── idle ───────────────────────────────────────────────────────────────

    @Test
    fun `idle both directions are applied`() {
        assertEquals(SettingAction.APPLY, BehaviorSettingsPlan.idle("on").action)
        assertEquals(true, BehaviorSettingsPlan.idle("on").value)
        assertEquals(false, BehaviorSettingsPlan.idle("off").value)
    }

    @Test
    fun `idle refuses an unknown value`() {
        assertTrue(BehaviorSettingsPlan.idle("banana").isRefusal)
        assertTrue(BehaviorSettingsPlan.idle("").isRefusal)
    }

    // ── sound ──────────────────────────────────────────────────────────────

    @Test
    fun `sound on is applied on a device that can make a tone`() {
        val plan = BehaviorSettingsPlan.sound("on", deviceReady = true, eventOn = true)
        assertEquals(SettingAction.APPLY, plan.action)
        assertEquals(true, plan.value)
        assertTrue(plan.reply, plan.reply.contains("audible=yes"))
    }

    /**
     * A phone with no usable audio must not be told it is now beeping.
     *
     * The availability is a parameter because it comes from a real
     * ToneGenerator attempt, not from a constant in this file.
     */
    @Test
    fun `sound on is refused on a device with no audio output`() {
        val plan = BehaviorSettingsPlan.sound("on", deviceReady = false, eventOn = true)
        assertTrue(plan.isRefusal)
        assertTrue(requireRefusal(plan).contains("no audio output"))
        assertNull("a refusal must not carry a value to store", plan.value)
    }

    /** Turning it off is always possible -- that is the direction that needs no hardware. */
    @Test
    fun `sound off is applied even on a device with no audio output`() {
        val plan = BehaviorSettingsPlan.sound("off", deviceReady = false, eventOn = true)
        assertEquals(SettingAction.APPLY, plan.action)
        assertEquals(false, plan.value)
    }

    /**
     * Arming the trigger while the master is mute is a real change, and the
     * answer says so.
     *
     * Otherwise the client has `sound_event=on` stored and no way to tell from
     * the reply why nothing beeps -- which is exactly the mystery that made the
     * old bare "ok" useless.
     */
    @Test
    fun `sound_event on while the master is off is applied and says it is mute`() {
        val plan = BehaviorSettingsPlan.soundEvent("on", masterOn = false)
        assertEquals(SettingAction.APPLY, plan.action)
        assertEquals(true, plan.value)
        assertTrue(plan.reply, plan.reply.contains("sound is off"))
    }

    @Test
    fun `sound_event on while the master is on is audible`() {
        val plan = BehaviorSettingsPlan.soundEvent("on", masterOn = true)
        assertTrue(plan.reply, plan.reply.contains("audible=yes"))
    }

    @Test
    fun `sound_timeout accepts zero and the documented ceiling`() {
        assertEquals(0, BehaviorSettingsPlan.soundTimeout("0").seconds)
        assertEquals(
            MotionSoundPolicy.MAX_TIMEOUT_SECONDS,
            BehaviorSettingsPlan.soundTimeout(MotionSoundPolicy.MAX_TIMEOUT_SECONDS.toString()).seconds,
        )
        assertEquals(30, BehaviorSettingsPlan.soundTimeout("30").seconds)
    }

    /**
     * A negative timeout is refused rather than coerced to 0.
     *
     * Coercing would turn a typo into "a tone on every event", and a clamp would
     * be invisible in a bare "Ok" body -- so the range is enforced here instead.
     */
    @Test
    fun `sound_timeout outside the range is refused with the range named`() {
        for (bad in listOf("-1", "-86400", "999999", "100000")) {
            val plan = BehaviorSettingsPlan.soundTimeout(bad)
            assertTrue("$bad must be refused", plan.isRefusal)
            assertTrue(requireRefusal(plan).contains(MotionSoundPolicy.MAX_TIMEOUT_SECONDS.toString()))
        }
    }

    @Test
    fun `sound_timeout refuses a value that is not a number`() {
        for (bad in listOf("banana", "", "10s", "1.5")) {
            assertTrue("$bad must be refused", BehaviorSettingsPlan.soundTimeout(bad).isRefusal)
        }
        // "1.5" is the interesting one: the API is in whole seconds and toIntOrNull
        // refuses a decimal, so it is a refusal naming the unit rather than a
        // silent floor to 1.
        assertTrue(requireRefusal(BehaviorSettingsPlan.soundTimeout("1.5")).contains("seconds"))
    }

    // ── motion_limit ───────────────────────────────────────────────────────

    @Test
    fun `motion_limit accepts the range MotionLimits defines`() {
        assertEquals(
            MotionLimits.MIN_MAX_CLIP_SECONDS,
            BehaviorSettingsPlan.motionLimit(MotionLimits.MIN_MAX_CLIP_SECONDS.toString()).seconds,
        )
        assertEquals(
            MotionLimits.MAX_MAX_CLIP_SECONDS,
            BehaviorSettingsPlan.motionLimit(MotionLimits.MAX_MAX_CLIP_SECONDS.toString()).seconds,
        )
        assertEquals(60, BehaviorSettingsPlan.motionLimit("60").seconds)
    }

    /**
     * The bounds come from MotionLimits, not from a second set of numbers here.
     *
     * A copy would be free to drift, and it would drift silently: the recorder
     * would clamp on its own path and the reply would report a value the clip
     * never had.
     */
    @Test
    fun `motion_limit uses the same bounds the recorder clamps with`() {
        // Below MIN: the recorder's coerceIn would have raised it to MIN.
        assertTrue(BehaviorSettingsPlan.motionLimit("0").isRefusal)
        assertTrue(BehaviorSettingsPlan.motionLimit("-5").isRefusal)
        // Above MAX: a value the recorder would have pulled back, invisibly.
        val tooLong = MotionLimits.MAX_MAX_CLIP_SECONDS + 1
        assertTrue(BehaviorSettingsPlan.motionLimit(tooLong.toString()).isRefusal)
    }

    @Test
    fun `motion_limit refuses a value that is not a number`() {
        // "banana" answered "ok" before, and the clip length never changed.
        val plan = BehaviorSettingsPlan.motionLimit("banana")
        assertTrue(plan.isRefusal)
        assertTrue(requireRefusal(plan).contains("whole number"))
    }

    // ── motion_event / motion_active ───────────────────────────────────────

    @Test
    fun `motion_event both directions are applied`() {
        assertEquals(true, BehaviorSettingsPlan.motionEvent("on").value)
        assertEquals(SettingAction.APPLY, BehaviorSettingsPlan.motionEvent("on").action)
        assertEquals(false, BehaviorSettingsPlan.motionEvent("off").value)
    }

    /**
     * motion_active is a sensor, and no value of it can make motion appear.
     *
     * Every value is refused, including "on" and "off" -- and that is not a
     * limitation of this implementation but a property of the key: a 200 would
     * be a confirmation of a change that cannot exist. The refusal points at
     * motion_event, so the client is sent where it can go.
     */
    @Test
    fun `motion_active is refused for every value and names motion_event`() {
        for (v in listOf("on", "off", "true", "false", "1", "0")) {
            val plan = BehaviorSettingsPlan.motionActive(v)
            assertTrue("$v must be refused", plan.isRefusal)
            assertTrue(requireRefusal(plan).contains("motion_event"))
            assertNull("$v must not carry a value to store", plan.value)
        }
    }

    // ── gps_active ─────────────────────────────────────────────────────────

    /**
     * Both directions refused, because there is nothing to switch either way.
     *
     * `off` is refused too, deliberately. A 200 for `off` would be the same bare
     * "Ok" that the old "false" body was: a client that reads it as success
     * believes it disabled something that was never there.
     */
    @Test
    fun `gps_active is refused in both directions`() {
        for (v in listOf("on", "off")) {
            val plan = BehaviorSettingsPlan.gpsActive(v)
            assertTrue("$v must be refused", plan.isRefusal)
            assertTrue(requireRefusal(plan).contains("location permission"))
            assertTrue(requireRefusal(plan).contains("sensors.json"))
            assertNull("$v must not carry a value to store", plan.value)
        }
    }

    /**
     * The refusal must not depend on the value's direction.
     *
     * This is the assertion that pins the decision: `gps_active=on` and
     * `gps_active=off` take the same branch, so a future edit that lets one
     * through answers 200 and this goes red.
     */
    @Test
    fun `gps_active answers identically for on and off`() {
        val on = BehaviorSettingsPlan.gpsActive("on")
        val off = BehaviorSettingsPlan.gpsActive("off")
        assertEquals(on.action, off.action)
        assertEquals(on.action, SettingAction.REFUSE)
        assertEquals(on.refusal, off.refusal)
    }

    /**
     * There is no capability that makes this key answerable.
     *
     * This test used to assert the opposite -- that a device reporting a
     * location provider took an APPLY branch. That branch was the lie again, one
     * level down: the manifest declares no location permission and nothing in the
     * frame path reads a position, so an "Ok" there confirmed a change to
     * nothing. The signature no longer takes a capability, so the compiler is the
     * first line of defence; this is the second, in case someone adds the
     * parameter back with a default of true.
     */
    @Test
    fun `gps_active cannot be applied by any value`() {
        val vocabulary = listOf("on", "off", "true", "false", "1", "0", "yes", "no")
        for (v in vocabulary) {
            assertTrue(
                "gps_active=$v must be refused, not answered",
                BehaviorSettingsPlan.gpsActive(v).isRefusal,
            )
        }
    }

    /**
     * The 400 body and the /status.json field quote the same constant.
     *
     * TelemetryHandler builds `behaviors.gps_active.reason` from
     * [GPS_UNWRITABLE] too, and the refusal here embeds it. Asserting the
     * constant appears in the refusal is what stops the two from drifting: two
     * hand-written strings for one fact end up saying different things, and the
     * one a client reads at the moment it gets refused is the one that matters.
     */
    @Test
    fun `the gps refusal quotes the same constant telemetry reports`() {
        val refusal = BehaviorSettingsPlan.gpsActive("on").refusal.orEmpty()
        assertTrue(
            "the refusal must contain GPS_UNWRITABLE verbatim",
            refusal.contains(GPS_UNWRITABLE),
        )
    }

    @Test
    fun `gps_active still checks the vocabulary before refusing for the feature`() {
        // A nonsense value is a 400 about the *value*, not about GPS. If the
        // feature refusal swallowed it, a client with a typo would be told the
        // key is unavailable rather than that its value is wrong.
        val plan = BehaviorSettingsPlan.gpsActive("banana")
        assertTrue(plan.isRefusal)
        assertTrue(requireRefusal(plan).contains("banana"))
        assertTrue(
            "the value error must be the vocabulary error, not the GPS one",
            !requireRefusal(plan).contains("sensors.json"),
        )
    }

    // ── dispatcher ─────────────────────────────────────────────────────────

    @Test
    fun `resolve handles every key it claims to own`() {
        BehaviorSettingsPlan.KEYS.forEach { key ->
            // sound_timeout is the one key whose only legal value is numeric, so
            // a per-key probe value is passed rather than "on".
            val v = if (key == "sound_timeout" || key == "motion_limit") "10" else "on"
            val plan = BehaviorSettingsPlan.resolve(
                key, v,
                painterReady = true,
                windowPresent = true,
                soundDeviceReady = true,
                soundMasterOn = true,
                soundEventOn = true,
            )
            assertNotNull("$key must resolve", plan)
            assertTrue("$key must resolve to itself", plan!!.key == key)
        }
    }

    @Test
    fun `resolve returns null for a key it does not own`() {
        // Null means "not mine", which is different from a refusal. The handler
        // relies on the difference: null keeps the dispatch going, a refusal
        // stops it with a 400.
        assertNull(BehaviorSettingsPlan.resolve("quality", "50"))
        assertNull(BehaviorSettingsPlan.resolve("rotate", "portrait"))
        assertNull(BehaviorSettingsPlan.resolve("nonsense", "on"))
    }

    // ── the shape of the whole gap ─────────────────────────────────────────

    /**
     * No key in the vocabulary is answered identically for all values.
     *
     * This is the assertion that would have caught the original bug for all nine
     * keys at once. `overlay` answered 200 for on and off; `sound_timeout`
     * answered 200 for "0", "banana" and "999999"; `motion_limit` answered 200
     * for everything. A handler that maps every value of a key to one outcome is
     * exactly what those keys did.
     *
     * It is deliberately about *outcome diversity*, not about a specific value
     * being refused -- so it stays meaningful if the bounds move.
     */
    @Test
    fun `no key produces one single outcome for its whole value vocabulary`() {
        val vocabulary = listOf("on", "off", "banana", "", "10", "-1", "999999")
        for (key in BehaviorSettingsPlan.KEYS) {
            // Two keys legitimately refuse their entire vocabulary:
            //
            //   gps_active     -- there is no GPS overlay to switch at all
            //   motion_active  -- it is a sensor, not a setting
            //
            // Both are required to refuse everything, and both are asserted
            // directly in their own sections above. What this test forbids is
            // the opposite failure: a key that *answers* every value, which is
            // what the unconditional okText("ok") was. Uniform refusal is not
            // that -- it is a refusal.
            if (key in ALWAYS_REFUSED) continue
            val outcomes = vocabulary.map { v ->
                BehaviorSettingsPlan.resolve(
                    key, v,
                    painterReady = true,
                    windowPresent = true,
                    soundDeviceReady = true,
                    soundMasterOn = true,
                    soundEventOn = true,
                )?.action
            }.toSet()
            assertTrue(
                "$key produced only $outcomes for $vocabulary -- that is the " +
                    "unconditional okText(\"ok\") these keys used to return",
                outcomes.size > 1,
            )
        }
    }

    /**
     * A capability the device does not have must refuse EVERY value, not just
     * the ones that look like an enable.
     *
     * This is the capability check as distinct from the value check, and the
     * test above cannot see it: with every capability granted, the vocabulary
     * alone already produces two outcomes. Here each key is denied its
     * capability and the whole vocabulary has to collapse to REFUSE.
     *
     * Three keys, not four, and the omission is deliberate: `sound` is absent
     * because turning a sound OFF is always possible. Its master flag is a
     * stored boolean that MotionTonePlayer reads, so `sound=off` genuinely
     * disables a tone that could otherwise have been played -- a real change
     * that needs no hardware. Asserted separately below, because that asymmetry
     * is a decision and not an accident.
     */
    @Test
    fun `a key the device cannot do is refused for every value in the vocabulary`() {
        val vocabulary = listOf("on", "off", "true", "false", "1", "0")
        for (key in listOf("overlay", "awake", "gps_active")) {
            val outcomes = vocabulary.map { v ->
                BehaviorSettingsPlan.resolve(
                    key, v,
                    painterReady = false,
                    windowPresent = false,
                )?.action
            }
            assertTrue(
                "$key must be refused for every value when the device cannot do it, " +
                    "but produced $outcomes for $vocabulary",
                outcomes.none { it == SettingAction.APPLY },
            )
            assertTrue("$key produced no plan at all for $vocabulary", outcomes.all { it != null })
        }
    }

    /**
     * The counterpart, and the reason `sound` was left out above.
     *
     * A mute is always honoured, because there is nothing about it that needs
     * working audio. If this ever reads REFUSE for `sound=off`, a user on a
     * device with a broken speaker would have no way to turn the tone off --
     * which is the one direction they most need.
     */
    @Test
    fun `sound off is honoured even where sound on is not`() {
        val onSilent = BehaviorSettingsPlan.sound("on", deviceReady = false, eventOn = true)
        val offSilent = BehaviorSettingsPlan.sound("off", deviceReady = false, eventOn = true)
        assertEquals(SettingAction.REFUSE, onSilent.action)
        assertEquals(SettingAction.APPLY, offSilent.action)
        assertEquals(false, offSilent.value)
    }

    /** Every refusal has to carry a reason, or the 400 says nothing. */
    @Test
    fun `no refusal is ever returned without a reason`() {
        val probes = BehaviorSettingsPlan.KEYS.flatMap { key ->
            listOf("on", "off", "banana", "", "0", "-3", "abc", "999999").map { v ->
                BehaviorSettingsPlan.resolve(
                    key, v,
                    painterReady = false,
                    windowPresent = false,
                    soundDeviceReady = false,
                    soundMasterOn = false,
                    soundEventOn = false,
                )
            }
        }
        val refusals = probes.filter { it?.isRefusal == true }
        assertTrue("expected some refusals to inspect", refusals.isNotEmpty())
        refusals.forEach {
            assertNotNull("${it?.key} refused with no reason", it?.refusal)
            assertTrue(
                "${it?.key} refused with a blank reason",
                !it?.refusal.isNullOrBlank(),
            )
            assertFalse(
                "${it?.key} carries a value to store on a refusal",
                it?.value != null && it.key == "motion_active",
            )
        }
    }

    /** The vocabulary a refusal advertises must actually be accepted. */
    @Test
    fun `every spelling the refusal advertises is one the parser accepts`() {
        for (spelling in BehaviorSettingsPlan.ON_VALUES + BehaviorSettingsPlan.OFF_VALUES) {
            assertNotNull(
                "$spelling is advertised by the refusal message but parseBool rejects it",
                BehaviorSettingsPlan.parseBool(spelling),
            )
            assertEquals(
                spelling,
                spelling in BehaviorSettingsPlan.OFF_VALUES,
                !BehaviorSettingsPlan.parseBool(spelling)!!,
            )
        }
    }

    @Test
    fun `the two value sets are disjoint`() {
        // An overlap would make ON/OFF ambiguous, and whichever branch came
        // first would silently win for that spelling.
        assertTrue(
            "overlap: ${BehaviorSettingsPlan.ON_VALUES intersect BehaviorSettingsPlan.OFF_VALUES}",
            BehaviorSettingsPlan.ON_VALUES.intersect(BehaviorSettingsPlan.OFF_VALUES).isEmpty(),
        )
    }

    /**
     * The refusal text, failing the test when there is none.
     *
     * `plan.refusal.orEmpty()` was the wrong fix for the nullability warning:
     * it turns a missing refusal into an empty string, so `assertTrue("".contains(x))`
     * fails with a confusing message instead of saying "there was no reason".
     * This keeps the failure where the bug is.
     */
    private fun requireRefusal(plan: SettingPlan): String {
        val r = plan.refusal
        assertNotNull("${plan.key} refused with no reason to report", r)
        return r!!
    }
}

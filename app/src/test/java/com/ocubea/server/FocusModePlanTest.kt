package com.ocubea.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The `focusmode` mapping, tested without a camera.
 *
 * `focusmode`, `focus` and `focus_distance` used to answer `okText("auto")` to every
 * value — measured on the device as eight values in, eight "Ok" out, nothing behind
 * any of them. These tests pin the mapping that replaced it.
 *
 * The refusal cases matter most: a value that cannot be honestly approximated must
 * be refused, not silently turned into the nearest available action.
 */
class FocusModePlanTest {

    // --- the vocabulary is the API's, and a typo is not silently accepted

    @Test
    fun everyApiValueIsRecognised() {
        listOf("on", "auto", "macro", "off", "fixed", "infinity", "nofocus").forEach { v ->
            assertEquals("$v should be recognised", v, FocusModePlan.forValue(v, true)?.requested)
        }
    }

    @Test
    fun caseAndWhitespaceDoNotMatter() {
        assertEquals(
            FocusModeAction.LOCK,
            FocusModePlan.forValue("  OFF  ", true)?.action,
        )
    }

    @Test
    fun valuesOutsideTheApiAreRejected() {
        // smooth/aggressive belong to PFA cameras, not to this API. They used to be
        // accepted with an "Ok", which is how a client could believe a mode applied.
        listOf("smooth", "aggressive", "yes", "", "autofocus").forEach { v ->
            assertNull("$v must not resolve", FocusModePlan.forValue(v, true))
        }
    }

    // --- what each value actually does

    @Test
    fun onAndAutoRunContinuousAutofocus() {
        listOf("on", "auto").forEach { v ->
            assertEquals("$v", FocusModeAction.AUTOFOCUS, FocusModePlan.forValue(v, true)?.action)
        }
    }

    @Test
    fun offAndFixedLockTheFocus() {
        listOf("off", "fixed").forEach { v ->
            assertEquals("$v", FocusModeAction.LOCK, FocusModePlan.forValue(v, true)?.action)
        }
    }

    @Test
    fun nofocusReleasesALockedFocus() {
        assertEquals(FocusModeAction.RELEASE, FocusModePlan.forValue("nofocus", true)?.action)
    }

    /**
     * macro maps to autofocus, and says so.
     *
     * Mapping it is defensible: a close subject needs the lens still hunting, and
     * continuous autofocus is exactly that. The reply must therefore admit the
     * mapping, or the client is back to believing it got a macro mode.
     */
    @Test
    fun macroMapsToAutofocusAndAdmitsIt() {
        val plan = FocusModePlan.forValue("macro", true)!!
        assertEquals(FocusModeAction.AUTOFOCUS, plan.action)
        assertEquals(true, plan.reply.contains("macro is not a distinct mode"))
        // plain autofocus must NOT claim macro, so the two are distinguishable
        assertEquals(false, FocusModePlan.forValue("auto", true)!!.reply.contains("macro"))
    }

    /**
     * infinity is refused, even though locking is the nearest available action.
     *
     * This is the asymmetry with macro, and it is deliberate. LOCK focuses the
     * current subject; a client asking for infinity asked about a subject at
     * infinity. There is no reading of "locked at centre" that is near infinity, so
     * mapping it would answer about a different subject than the client asked for.
     */
    @Test
    fun infinityIsRefusedNotMapped() {
        val plan = FocusModePlan.forValue("infinity", true)!!
        assertEquals(FocusModeAction.UNSUPPORTED, plan.action)
        assertEquals(true, plan.refusal != null)
        assertEquals(true, plan.refusal!!.contains("infinity"))
        // and the refusal points at the honest alternative
        assertEquals(true, plan.refusal!!.contains("focusmode=off"))
    }

    // --- a camera with no focus hardware refuses everything

    @Test
    fun everyValueIsRefusedWhenTheCameraHasNoAutofocus() {
        FocusModePlan.ACCEPTED.forEach { v ->
            val plan = FocusModePlan.forValue(v, false)!!
            assertEquals("$v must not act on a camera with no focus", FocusModeAction.UNSUPPORTED, plan.action)
            assertEquals("$v must carry a reason", true, plan.refusal != null)
        }
    }

    @Test
    fun theNoAutofocusRefusalNamesTheRealCause() {
        val plan = FocusModePlan.forValue("macro", false)!!
        assertEquals(true, plan.refusal!!.contains("no autofocus"))
        assertEquals(true, plan.refusal!!.contains("isFocusMeteringSupported"))
    }

    /**
     * /nofocus must refuse on a camera with no focus hardware.
     *
     * cancelFocusAndMetering() succeeds there — there is no lock to cancel, so
     * nothing can fail — which made the HTTP /nofocus route answer 200 while
     * /focus answered 400 on the same device. Measured before this case existed.
     */
    @Test
    fun nofocusIsRefusedWhenTheCameraHasNoAutofocus() {
        val plan = FocusModePlan.forValue("nofocus", false)!!
        assertEquals(FocusModeAction.UNSUPPORTED, plan.action)
        assertEquals(true, plan.refusal!!.contains("no autofocus"))
    }

    @Test
    fun nofocusReleasesWhenTheCameraCanFocus() {
        val plan = FocusModePlan.forValue("nofocus", true)!!
        assertEquals(FocusModeAction.RELEASE, plan.action)
        assertEquals(null, plan.refusal)
    }

    @Test
    fun aRefusalCarriesNoReply() {
        // A plan that both refuses and answers would let the Ok path win.
        val plan = FocusModePlan.forValue("infinity", true)!!
        assertEquals("", plan.reply)
    }
}
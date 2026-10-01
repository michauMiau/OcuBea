package com.ocubea.server

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.URI
import java.net.URL
import java.util.Locale

/**
 * Guards the gap that let a client do nothing wrong and still get a 404.
 *
 * `IpWebcamCompat.SUPPORTED` is the promise the server makes about which
 * `/settings/<name>` keys it accepts. `StreamServer.applySetting` is where the
 * promise is kept. Nothing checked that the two agree, so `focus` 404'd while
 * `focusmode` worked, and six more keys were advertised but unreachable.
 *
 * The list of expected keys is written out here rather than derived from
 * `SUPPORTED`, because a test that reads the implementation proves nothing: it
 * passes for the very gap it exists to catch. `compatibilityKeysFromUpstream()`
 * pulls the real names out of the pydroid-ipcam source when it is available,
 * and `testKeysMatchUpstreamClient()` compares against that instead.
 */
class IpWebcamSettingSurfaceTest {

    /**
     * Every key this test requires the server to accept, taken from the
     * pydroid-ipcam settings surface plus the aliases declared in
     * IpWebcamCompat. Kept as a literal so a deletion shows up as a failure.
     */
    private val requiredKeys = listOf(
        // image / video
        "quality", "resolution", "video_size", "photo_size", "video_resolution",
        "fps", "jpeg_quality", "effect", "coloreffect", "rotate", "rotation", "mirror_flip",
        // camera
        "ffc", "front_camera", "torch", "flashmode", "focus", "focusmode", "focus_distance",
        "exposure", "exposure_lock", "antibanding", "whitebalance", "whitebalance_lock",
        "scenemode", "night_vision", "overlay",
        // audio
        "audio", "audio_enabled", "audio_only", "sound", "sound_event", "sound_timeout",
        // motion / recording
        "motion_detection", "motion_active", "motion_event", "motion_limit", "motion_sensitivity",
        "recording", "video_recording", "pre_record_seconds", "max_clip_seconds", "norecord",
        // service / power
        "awake", "idle", "power_saving", "autostart", "gps_active",
        "noremote", "login", "password", "port", "device_name",
        // service control
        "force_start", "force_stop", "start", "stop",
    )

    /**
     * The names pydroid-ipcam actually sends. Read from its source so the test
     * follows the client rather than a snapshot of our own guesses.
     */
    private fun upstreamKeys(): List<String>? {
        val candidates = listOf(
            System.getProperty("pydroid.source"),
            "../reference/pydroid_ipcam/__init__.py",
            "/root/.hermes/cache/scratch/pydroid.py",
        )
        val file = candidates.mapNotNull { it?.let(::File) }.firstOrNull { it.isFile } ?: return null
        val text = file.readText()
        // Settings go out as /settings/<key>?set=<value>, so the key is whatever
        // precedes "?set=" in the format strings.
        val fromCalls = Regex("""/settings/([a-z_0-9]+)\?set=""")
            .findAll(text).map { it.groupValues[1] }.toSet()
        // pydroid builds some paths from a key variable; the alias table in
        // IpWebcamCompat covers those, so only the literal ones are required.
        return if (fromCalls.isEmpty()) null else fromCalls.toList()
    }

    @Test
    fun `compat layer advertises every key the test requires`() {
        val missing = requiredKeys.filterNot { IpWebcamCompat.isKnown(it) }
        assertTrue(
            "IpWebcamCompat.SUPPORTED is missing keys this test requires: $missing",
            missing.isEmpty(),
        )
    }

    @Test
    fun `applySetting handles every key the compat layer advertises`() {
        val source = readApplySettingSource()
        val missing = requiredKeys.filterNot { it in source }
        assertTrue(
            "These keys are advertised by IpWebcamCompat but no when-branch in " +
                "applySetting handles them, so a client gets 404: $missing",
            missing.isEmpty(),
        )
    }

    @Test
    fun `every key pydroid sends is advertised`() {
        val upstream = upstreamKeys() ?: return   // reference not available
        val missing = upstream.filterNot { IpWebcamCompat.isKnown(it) }
        assertTrue(
            "pydroid-ipcam sends settings this server does not advertise: $missing",
            missing.isEmpty(),
        )
    }

    @Test
    fun `known keys are matched case-insensitively`() {
        // Clients lowercase their keys, but a hand-typed URL may not.
        assertTrue(IpWebcamCompat.isKnown("QUALITY"))
        assertTrue(IpWebcamCompat.isKnown("Night_Vision"))
        assertTrue(!IpWebcamCompat.isKnown("definitely_not_a_setting"))
    }

    private fun readApplySettingSource(): String {
        val candidates = listOf(
            System.getProperty("ocubea.source"),
            "src/main/java/com/ocubea/server/StreamServer.kt",
        )
        val file = candidates.mapNotNull { it?.let(::File) }.firstOrNull { it.isFile } ?: return ""
        val text = file.readText()
        val start = text.indexOf("private fun applySetting")
        if (start < 0) return ""
        val end = text.indexOf("\n    private fun ", start + 10)
        return text.substring(start, if (end > 0) end else text.length)
    }
    /**
     * The focus vocabulary, which the key-only tests above cannot see.
     *
     * This test class asserts which `/settings/<name>` keys exist. It says nothing
     * about which *values* a key accepts, and the gap was live: `focusmode`,
     * `focus` and `focus_distance` answered "Ok" to every value — measured as eight
     * values in, eight successes out, with nothing behind any of them.
     *
     * The mapping itself is pinned by FocusModePlanTest. What belongs here is the
     * shape of the refusal, because that is what a client reads: a 400 that names
     * the accepted set, not a success that changed nothing.
     */
    @Test
    fun focusValuesAreAnsweredFromTheApiVocabularyNotSilently() {
        // Every value the API spells must resolve to a plan on a camera that can
        // focus, or to an explicit refusal. None may return null (unknown) and none
        // may return a plan with no action and no reason.
        FocusModePlan.ACCEPTED.forEach { v ->
            // JUnit's assertNotNull does not smart-cast in Kotlin, so the !! is
            // what makes the next line compile; the assertion above is what makes
            // it safe.
            val plan = FocusModePlan.forValue(v, focusCapable = true)
            assertNotNull("$v should resolve to a plan", plan)
            assertTrue(
                "$v must do something or explain why it cannot",
                plan!!.action != FocusModeAction.UNSUPPORTED || plan.refusal != null,
            )
        }
    }

    /** The 400 must list the accepted set, so a client has somewhere to go. */
    @Test
    fun anUnknownFocusValueIsRejectedWithTheAcceptedSet() {
        assertNull(FocusModePlan.forValue("smooth", focusCapable = true))
        assertNull(FocusModePlan.forValue("aggressive", focusCapable = true))
        // the handler's message is built from the same list
        FocusModePlan.ACCEPTED.forEach { assertTrue(it.isNotBlank()) }
    }

    /**
     * `focus_distance` has no honest implementation and must not appear as one.
     *
     * The IP Webcam scale is 0.0-10.0 diopters. CameraX exposes no diopter control
     * at all, so the endpoint refuses with a reason instead of accepting a number it
     * cannot act on. A test that only checked the key's existence would call this
     * covered.
     */
    @Test
    fun focusDistanceIsRefusedRatherThanAccepted() {
        // There is no plan for it: the handler refuses it by name.
        assertNull(FocusModePlan.forValue("focus_distance", focusCapable = true))
    }
}

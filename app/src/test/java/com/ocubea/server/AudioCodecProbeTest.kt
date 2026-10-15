package com.ocubea.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The codec menu is the one part of this feature a user sees, and the one part
 * that has to be honest on hardware nobody in this repo owns. These tests
 * therefore drive the menu from a synthetic ProbeResult: an old e-waste phone
 * with no Opus and a modern one with everything.
 */
class AudioCodecProbeTest {

    private fun result(
        opus: Boolean = false,
        aac: Boolean = true,
        amr: Boolean = false,
        flac: Boolean = false
    ) = AudioCodecProbe.ProbeResult(opus, aac, amr, flac, emptyMap())

    @Test
    fun wavIsAlwaysOfferedBecauseItNeedsNoEncoder() {
        // Even a device with no encoders at all has to serve something.
        val none = result(aac = false, opus = false, amr = false, flac = false)
        val ids = AudioCodecProbe.options(none).map { it.id }
        assertTrue("WAV is the compatibility floor, not an option", "wav" in ids)
        assertEquals(1, ids.size)
    }

    @Test
    fun anAndroid6PhoneGetsNoOpusEntry() {
        // c2.android.opus.encoder landed in Android 11. Offering it on a
        // device that cannot encode is the bug this test exists for.
        val old = result(opus = false, aac = true)
        val ids = AudioCodecProbe.options(old).map { it.id }
        assertFalse("Opus must not appear without a working encoder", "opus" in ids)
    }

    @Test
    fun aModernPhoneGetsOpusFirst() {
        val modern = result(opus = true, aac = true, amr = true, flac = true)
        val ids = AudioCodecProbe.options(modern).map { it.id }
        assertEquals(listOf("opus", "aac", "amrnb", "wav", "flac"), ids)
    }

    @Test
    fun theDefaultIsAacNotTheBestAvailable() {
        // The app exists to run on old e-waste phones. Picking the newest
        // phone's best codec would make the default fail on the oldest one.
        assertEquals("aac", result(opus = true, aac = true).defaultId())
        assertEquals("wav", result(opus = false, aac = false).defaultId())
    }

    @Test
    fun thereIsNeverAnMp3Entry() {
        // No Android device ships an MP3 encoder; it is decode-only everywhere.
        val all = result(opus = true, aac = true, amr = true, flac = true)
        val labels = AudioCodecProbe.options(all).map { it.label.lowercase() }
        assertFalse("MP3 has no encoder on any Android device", labels.any { "mp3" in it })
    }

    @Test
    fun theMenuNeverDuplicatesWavWhenAnEncoderIsPresent() {
        val ids = AudioCodecProbe.options(result(opus = true, aac = true, flac = true)).map { it.id }
        assertEquals(ids.size, ids.distinct().size)
        assertEquals(1, ids.count { it == "wav" })
    }

    @Test
    fun everyOptionCarriesTheBitrateItWillActuallyAskFor() {
        val all = result(opus = true, aac = true, amr = true, flac = true)
        for (o in AudioCodecProbe.options(all)) {
            if (o.id == "flac") continue // lossless, the encoder picks
            assertTrue("${o.id} must have a real bitrate, was ${o.bitrate}", o.bitrate > 0)
        }
        // The point of the feature: every compressed option is far below the
        // 706 kbps WAV costs. Measured on the phone: 86 868 B/s.
        val wav = AudioCodecProbe.options(all).first { it.id == "wav" }
        for (o in AudioCodecProbe.options(all).filter { it.id != "wav" && it.id != "flac" }) {
            assertTrue(
                "${o.id} at ${o.bitrate} bps is not a saving over WAV at ${wav.bitrate}",
                o.bitrate * 10 < wav.bitrate
            )
        }
    }

    @Test
    fun theSummaryNamesEveryCapabilityAndTheDefault() {
        val s = AudioCodecProbe.summary(result(opus = true, aac = true, amr = false, flac = false))
        assertTrue(s, "aac=true" in s)
        assertTrue(s, "opus=true" in s)
        assertTrue(s, "amrnb=false" in s)
        assertTrue(s, "flac=false" in s)
        assertTrue(s, "default=aac" in s)
    }

    /**
     * `wav` has to be IN the summary, and it is the one capability that is
     * never false.
     *
     * This is the regression test for "wav nie wytestuję bo się nie pokazuje" --
     * WAV does not show up in the UI so it cannot be tested. The WebUI builds
     * its codec picker by treating this string as a whitelist of ids that
     * appear as `id=true`, so an id missing from here is a codec the user
     * cannot select, however healthy its endpoint is. `wav` was absent; the
     * picker offered none/aac/flac; the endpoint had been serving valid
     * bytes the entire time and was unreachable from the menu.
     *
     * Replayed in the browser against the shipped page, which is where the
     * filter itself comes from:
     *   without wav -> ['none','aac','flac']
     *   with wav    -> ['none','aac','flac','wav']
     */
    @Test
    fun theSummaryAdvertisesWavBecauseTheWebUiTreatsItAsAWhitelist() {
        val s = AudioCodecProbe.summary(result(opus = false, aac = true, amr = false, flac = true))
        assertTrue(
            "the WebUI shows only codecs named 'id=true' in this string, so a " +
                "missing wav makes the endpoint unreachable from the menu: $s",
            "wav=true" in s
        )
        // Not merely present: a client reading this string must be able to
        // serve it. WAV needs no MediaCodec, so it is never unavailable.
        assertTrue("wav must never be reported unavailable", AudioCodecProbe.canWav)
    }

    /**
     * Every id the WebUI can offer must also be servable, or the menu offers a
     * 501. The summary is the client's only view of the menu, so an id that is
     * in the menu but not servable is the defect this catches.
     */
    @Test
    fun everyOptionInTheMenuIsServableAndAppearsInTheSummary() {
        for (probe in listOf(
            result(opus = true, aac = true, amr = true, flac = true),
            result(opus = false, aac = true, amr = false, flac = true),
            result(opus = false, aac = false, amr = false, flac = false),
        )) {
            val s = AudioCodecProbe.summary(probe)
            val menu = AudioCodecProbe.options(probe)
            assertTrue("a menu must never be empty", menu.isNotEmpty())
            for (o in menu) {
                assertTrue(
                    "menu entry '${o.id}' is offered but the summary a client " +
                        "reads to build the same menu does not offer it: $s",
                    Regex("(^| )${o.id}=true( |\$)").containsMatchIn(s)
                )
            }
            // And the reverse: nothing in the summary may claim a codec the
            // menu omits, or a client picks from the string and gets a 501.
            for (id in listOf("aac", "opus", "amrnb", "flac", "wav")) {
                val claimed = Regex("(^| )$id=true( |\$)").containsMatchIn(s)
                assertEquals(
                    "'$id' is offered in the summary but not in the menu: $s",
                    claimed, menu.any { it.id == id }
                )
            }
        }
    }

    /**
     * The HTTP content type is what a client trusts before it has seen a byte,
     * so it has to be the registered form. `audio/x-wav` was being served and
     * is not an IANA audio type.
     */
    @Test
    fun everyOptionSaysWhatItActuallyIs() {
        val menu = AudioCodecProbe.options(result(opus = true, aac = true, amr = true, flac = true))
        val expected = mapOf(
            "wav" to "audio/wav",
            "flac" to "audio/flac",
            "aac" to "audio/aac",
            "opus" to "audio/ogg; codecs=opus",
            "amrnb" to "audio/amr",
        )
        for (o in menu) {
            assertEquals(
                "the Content-Type of /audio.${o.id} must state the container it " +
                    "actually carries",
                expected[o.id], o.contentTypeForHttp
            )
        }
    }
}

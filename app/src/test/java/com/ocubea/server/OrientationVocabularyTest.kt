package com.ocubea.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The orientation vocabulary, tested without a camera.
 *
 * The IP Webcam surface test only checks which `/settings/<name>` *keys* exist. It
 * proved nothing about which *values* those keys accept, and the gap was live:
 * `rotate` passed its value straight to `setDisplayOrientation(String)`, which takes
 * anything. Measured on the device — `rotate=banana`, `rotate=0`, `rotate=sideways`
 * and `rotate=90` each answered "Ok", the image did not rotate
 * (`rotationValueFor` falls through to ROTATION_0), and `/status.json` reported
 * `orientation: "banana"`, a state no canonical value could get back out of.
 *
 * So the rule this pins: an unknown orientation is refused, not applied.
 */
class OrientationVocabularyTest {

    @Test
    fun everyCanonicalNameResolvesToItself() {
        OrientationVocabulary.ORIENTATIONS.forEach { name ->
            assertEquals(name, name, OrientationVocabulary.resolve(name))
        }
    }

    @Test
    fun everyAliasResolvesToItsCanonicalName() {
        OrientationVocabulary.ALIASES.forEach { (alias, canonical) ->
            assertEquals(alias, canonical, OrientationVocabulary.resolve(alias))
        }
        assertEquals("upsidedown", OrientationVocabulary.resolve("reverse"))
        assertEquals("upsidedown", OrientationVocabulary.resolve("reverse_landscape"))
        assertEquals("upsidedown_portrait", OrientationVocabulary.resolve("reverse_portrait"))
    }

    @Test
    fun caseAndWhitespaceDoNotMatter() {
        assertEquals("portrait", OrientationVocabulary.resolve("  Portrait "))
        assertEquals("upsidedown", OrientationVocabulary.resolve("REVERSE"))
    }

    /**
     * Degrees are refused on purpose.
     *
     * "90" names sensor rotation, not the picture's orientation, and guessing which
     * of the four the caller meant would be the same silent wrong answer as before.
     * Measured as accepted by the old `rotate`, which is the bug.
     */
    @Test
    fun degreesAreNotAccepted() {
        listOf("90", "180", "270", "0", "45").forEach { v ->
            assertNull("$v must not resolve", OrientationVocabulary.resolve(v))
        }
    }

    @Test
    fun nonsenseIsNotAccepted() {
        listOf("banana", "", "  ", "portrait90", "landscape; rm -rf /", "sideways")
            .forEach { v ->
                assertNull("$v must not resolve", OrientationVocabulary.resolve(v))
            }
    }

    @Test
    fun everyAliasPointsAtARealOrientation() {
        // An alias to a name outside ORIENTATIONS would resolve to a value that no
        // setter accepts, which is the bug this file exists for.
        OrientationVocabulary.ALIASES.forEach { (alias, target) ->
            assertEquals("$alias", true, target in OrientationVocabulary.ORIENTATIONS)
        }
    }

    @Test
    fun theRefusalNamesWhatIsAccepted() {
        val msg = OrientationVocabulary.refusal("banana")
        assertEquals(true, msg.contains("banana"))
        assertEquals(true, msg.contains("portrait"))
        assertEquals(true, msg.contains("reverse_landscape"))
    }
}
package com.ocubea.server

/**
 * The accepted `orientation` / `rotate` vocabulary, and what each name means.
 *
 * Pulled out of `StreamServer` so it can be tested without a camera. `rotate` used
 * to skip both the alias map and the whitelist and pass its value straight to
 * `CameraManager.setDisplayOrientation(String)`, which accepts anything — measured
 * on the device: `rotate=banana`, `rotate=0`, `rotate=sideways` and `rotate=90`
 * each answered "Ok". `rotationValueFor()` has an `else -> ROTATION_0` branch, so
 * the image did not rotate at all while `/status.json` reported
 * `orientation: "banana"`, and because the string was persisted, `orientation` could
 * no longer reach a known state either.
 *
 * A rotation that accepts an unknown name is worse than one that rejects it: the
 * client is told the picture rotated, and it did not.
 */
internal object OrientationVocabulary {

    /** The four names the API spells, in the order IP Webcam documents them. */
    val ORIENTATIONS = listOf(
        "landscape", "portrait", "upsidedown", "upsidedown_portrait",
    )

    /** The same four under the other spelling some clients send. */
    val ALIASES = mapOf(
        "reverse_landscape" to "upsidedown",
        "reverse_portrait" to "upsidedown_portrait",
        "reverse" to "upsidedown",
    )

    /**
     * Resolves [value] to a canonical orientation, or null when it is not one.
     *
     * Degrees are deliberately not accepted. "90" and "270" name sensor rotation,
     * not the picture's orientation, and guessing which of the four the caller
     * meant would be the same silent wrong answer as before. A client that needs a
     * quarter turn can send `portrait`, which is unambiguous.
     */
    fun resolve(value: String): String? {
        val v = value.trim().lowercase()
        return ALIASES[v] ?: v.takeIf { it in ORIENTATIONS }
    }

    /** The message a rejected value gets, naming what is accepted. */
    fun refusal(value: String): String =
        "unknown orientation: $value (accepted: ${ORIENTATIONS.joinToString(", ")}, " +
            "plus aliases ${ALIASES.keys.joinToString(", ")})"
}

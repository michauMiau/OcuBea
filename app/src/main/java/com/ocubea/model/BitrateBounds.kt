package com.ocubea.model

/**
 * Bounds for the configured H.264 bitrate.
 *
 * The value is handed straight to MediaCodec as KEY_BIT_RATE, so a stray or
 * mistyped setting must not be able to ask for a rate the codec will either
 * refuse to configure with or silently round away. Kept as a top-level object
 * so it is testable on the JVM without SharedPreferences or a device.
 */
object BitrateBounds {

    /** Below this an encoder tends to fail to configure, or quantize badly. */
    const val MIN_KBPS = 200

    /** Above this buys nothing at 1080p and risks codec rejection. */
    const val MAX_KBPS = 20_000

    fun clampKbps(raw: Int): Int = raw.coerceIn(MIN_KBPS, MAX_KBPS)

    /** Converts the stored kbps into the bits-per-second the encoder wants. */
    fun bpsFromKbps(raw: Int): Int = clampKbps(raw) * 1000
}

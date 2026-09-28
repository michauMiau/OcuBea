package com.ocubea.model

/**
 * Maps the single quality slider onto both encoders.
 *
 * The slider is a human control with a 0..100 scale, while JPEG quality is
 * already 40..100 and bitrate is measured in kbps. Keeping the mapping in one
 * place stops the two from drifting apart when either range changes, and makes
 * it testable without SharedPreferences or a device.
 *
 * The bitrate curve is deliberately not linear. Measured on the Redmi: asking
 * for 1200 kbps still produced 4.25 Mbps, because the hardware encoder only
 * reaches ~6 fps of the 30 it is configured for, so the bits get spread over
 * fewer frames. That floor means the low half of the slider cannot buy much
 * resolution, while the top half pays for real detail. A straight line would
 * spend most of its travel in the range where nothing changes.
 */
object QualityScale {

    const val MIN_QUALITY = 40
    const val MAX_QUALITY = 100

    /** Bottom of the slider: visibly soft, but the smallest useful file. */
    const val MIN_BITRATE_KBPS = 800

    /** Top of the slider: above this the encoder gains nothing and stutters. */
    const val MAX_BITRATE_KBPS = 12_000

    fun jpegQualityFor(progress: Int): Int =
        MIN_QUALITY + progress.coerceIn(0, MAX_QUALITY - MIN_QUALITY)

    /** The slider's full travel, in SeekBar units. */
    const val MAX_PROGRESS = MAX_QUALITY - MIN_QUALITY

    /**
     * Bitrate for a slider position.
     *
     * Quadratic, normalised over the slider's real 0..MAX_PROGRESS range so
     * the top of the control really reaches [MAX_BITRATE_KBPS].
     *
     * The curve is not linear because the encoder compresses the low end much
     * harder than the top: measured on HLS, 800 kbps requested produced
     * 4.39 Mbps while 12000 kbps produced 12.82 Mbps. A straight line would
     * spend the bottom third of the slider on a difference too small to see
     * and leave the top third where detail actually shows.
     */
    fun bitrateKbpsFor(progress: Int): Int {
        val p = (progress.coerceIn(0, MAX_PROGRESS).toDouble() / MAX_PROGRESS)
        val shaped = p * p * 0.72 + p * 0.28
        val kbps = MIN_BITRATE_KBPS + (MAX_BITRATE_KBPS - MIN_BITRATE_KBPS) * shaped
        return kbps.toInt().coerceIn(
            BitrateBounds.MIN_KBPS,
            BitrateBounds.MAX_KBPS,
        )
    }
}

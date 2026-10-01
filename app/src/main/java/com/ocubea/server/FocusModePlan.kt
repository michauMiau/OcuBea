package com.ocubea.server

/**
 * What an IP Webcam `focusmode` value means on a camera that can actually focus.
 *
 * Split out from the HTTP handler so the mapping is unit testable. On the test
 * device `CameraManager.isFocusCapable()` is false, so the handler refuses every
 * value before the mapping is reached — a test that only checked responses would
 * pass whatever this said, which is exactly the gap that let `focusmode` answer
 * "Ok" to eight values with nothing behind them.
 *
 * The honesty rule here is narrow: a value may be mapped to the nearest action the
 * hardware can perform, but the reply must say it was mapped. A value that cannot
 * be approximated without claiming hardware that does not exist is refused.
 */
internal enum class FocusModeAction { AUTOFOCUS, LOCK, RELEASE, UNSUPPORTED }

/**
 * The result of interpreting one `focusmode` value: what to do, what to answer, and
 * — when the answer is a refusal — why.
 */
internal data class FocusModePlan(
    val requested: String,
    val action: FocusModeAction,
    val reply: String,
    val refusal: String? = null,
) {
    companion object {
        /** The API's own vocabulary, spelled out so the 400 can list it. */
        val ACCEPTED = listOf(
            "on", "auto", "macro", "off", "fixed", "infinity", "nofocus",
        )

        /**
         * Interprets one value, or null when the value is not in the vocabulary.
         *
         * [focusCapable] is the camera's own answer, never an assumption. A camera
         * without focus metering gets one refusal for every value, so no client is
         * told a focus mode changed on hardware that cannot change it.
         */
        fun forValue(value: String, focusCapable: Boolean): FocusModePlan? {
            val v = value.trim().lowercase()
            if (v !in ACCEPTED) return null

            if (!focusCapable) {
                return FocusModePlan(
                    requested = v,
                    action = FocusModeAction.UNSUPPORTED,
                    reply = "",
                    // Names the API the client can verify against, so a reader can
                    // tell this is a reported capability rather than a shrug.
                    refusal = "this camera reports no autofocus " +
                        "(isFocusMeteringSupported is false), so focusmode cannot be " +
                        "changed and /focus is unavailable too",
                )
            }

            return when (v) {
                // macro is the nearest real thing: a close subject needs the lens to
                // keep hunting rather than parked. Continuous autofocus is that, so
                // it is what happens — stated in the reply, not hidden.
                "on", "auto" -> FocusModePlan(
                    v, FocusModeAction.AUTOFOCUS, "auto",
                )
                "macro" -> FocusModePlan(
                    v,
                    FocusModeAction.AUTOFOCUS,
                    "auto; macro is not a distinct mode on this camera, so continuous " +
                        "autofocus was enabled instead",
                )
                // off and fixed are the same real thing: the lens drives to a point
                // and stays there (disableAutoCancel).
                "off", "fixed" -> FocusModePlan(
                    v, FocusModeAction.LOCK, "off; focus is now locked",
                )
                // nofocus releases a lock. It is also reachable as the HTTP
                // /nofocus route, and there it must refuse on a camera with no focus
                // hardware -- cancelFocusAndMetering() succeeds there (there is no
                // lock to cancel), so the handler would otherwise answer 200 while
                // /focus answered 400 on the same device.
                "nofocus" -> FocusModePlan(
                    v,
                    if (focusCapable) FocusModeAction.RELEASE else FocusModeAction.UNSUPPORTED,
                    if (focusCapable) "off; the locked focus was released" else "",
                    if (focusCapable) null
                    else "cannot release a focus lock: this camera reports no autofocus " +
                        "(isFocusMeteringSupported is false), so focus was never locked",
                )
                // infinity is deliberately NOT mapped to LOCK, even though locking is
                // the closest thing this camera can do.
                //
                // LOCK means "focus at the current subject, wherever that is". A
                // client asking for infinity means a subject at infinity. Locking on
                // whatever is in the middle of the frame and answering "off" would
                // name a different subject than the client asked for — and unlike
                // macro, there is no reading of "locked at centre" that is near
                // infinity. It is a refusal, not an approximation.
                //
                // This needs a fixed-focus lens driven by the camera itself, which a
                // phone does not expose.
                "infinity" -> FocusModePlan(
                    v,
                    FocusModeAction.UNSUPPORTED,
                    "",
                    "focusmode=infinity needs the lens driven to infinity, which this " +
                        "camera does not expose; use focusmode=off to lock focus where " +
                        "it lands",
                )
                else -> null
            }
        }
    }
}
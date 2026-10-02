package com.ocubea.server

/**
 * What `exposure`, `exposure_lock`, `whitebalance`, `whitebalance_lock` and
 * `antibanding` actually mean on this camera, decided without a camera in hand.
 *
 * All five used to be literals in `applySetting`:
 *
 *     "exposure", "exposure_lock" -> okText("ok")
 *     "whitebalance", "whitebalance_lock" -> okText("auto")
 *     "antibanding" -> okText("auto")
 *
 * Measured on the phone: five keys, every value, HTTP 200 "Ok" with nothing
 * behind any of them. This is the third time a key pair in this file has been
 * removed for that reason (focusmode, then rotate), so the reason it was
 * removable is worth naming: the decision lived in the HTTP handler, where no
 * test can reach it. This file is that decision, moved out.
 *
 * The capabilities are passed IN rather than read here, so the mapping is a pure
 * function of (key, value, what the camera reports) and a unit test can drive
 * every combination — including "camera reports nothing" — that a real device
 * would reach only by accident.
 */

/**
 * The camera's own answers, as reported by the driver.
 *
 * Deliberately plain data with no CameraX or camera2 types in it. Two reasons:
 * the unit tests must run on the JVM with no android.jar behaviour behind them,
 * and a capability that cannot be spelled in a test cannot be tested.
 *
 * [awbModes] and [antibandingModes] are the raw camera2 int sets, so "this
 * camera does not offer fluorescent" is answered from the driver rather than
 * from a guess about phones in general.
 */
internal data class ImageControlCaps(
    /** `ExposureState.isExposureCompensationSupported()`. */
    val exposureSupported: Boolean,
    /** `ExposureState.getExposureCompensationRange().lower` — the most negative. */
    val exposureIndexMin: Int,
    /** `ExposureState.getExposureCompensationRange().upper`. */
    val exposureIndexMax: Int,
    /** `ExposureState.getExposureCompensationStep()` as a double, e.g. 1/6 EV. */
    val exposureEvStep: Double,
    /** `CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES`. */
    val awbModes: Set<Int>,
    /** `CameraCharacteristics.CONTROL_AE_AVAILABLE_ANTIBANDING_MODES`. */
    val antibandingModes: Set<Int>,
) {
    companion object {
        /**
         * A camera that reports nothing usable.
         *
         * What the plan must refuse against. If a capability read fails and the
         * code falls back to "assume it works", that is the same lie the five
         * literals told, one level further down.
         */
        val NONE = ImageControlCaps(
            exposureSupported = false,
            exposureIndexMin = 0,
            exposureIndexMax = 0,
            exposureEvStep = 0.0,
            awbModes = emptySet(),
            antibandingModes = emptySet(),
        )
    }
}

/** camera2 `CONTROL_AWB_MODE_*` values. Spelled here so the mapping is testable. */
internal object AwbModes {
    const val OFF = 0
    const val AUTO = 1
    const val INCANDESCENT = 2
    const val FLUORESCENT = 3
    const val DAYLIGHT = 5
    const val CLOUDY_DAYLIGHT = 6
    const val SHADE = 8
}

/** camera2 `CONTROL_AE_ANTIBANDING_MODE_*` values. */
internal object AntiBandingModes {
    const val OFF = 0
    const val HZ50 = 1
    const val HZ60 = 2
    const val AUTO = 3
}

/** What the handler should actually do for one setting. */
internal enum class ImageControlAction {
    /** Push an absolute index to `CameraControl.setExposureCompensationIndex`. */
    SET_EXPOSURE_COMPENSATION,

    /** Set/clear `CONTROL_AE_LOCK`. */
    SET_EXPOSURE_LOCK,

    /** Set `CONTROL_AWB_MODE`. */
    SET_WHITE_BALANCE,

    /** Set/clear `CONTROL_AWB_LOCK`. */
    SET_WHITE_BALANCE_LOCK,

    /** Set `CONTROL_AE_ANTIBANDING_MODE`. */
    SET_ANTIBANDING,

    /** The camera cannot do it; [ImageControlPlan.refusal] says why. */
    UNSUPPORTED,
}

/**
 * The result of interpreting one image-control value: what to do, what to say,
 * and — when the answer is a refusal — why.
 */
internal data class ImageControlPlan(
    val key: String,
    val requested: String,
    val action: ImageControlAction,
    val reply: String,
    val refusal: String? = null,
    /** The camera2/AE index for SET_EXPOSURE_COMPENSATION. */
    val index: Int = 0,
    /** The camera2 mode for SET_WHITE_BALANCE / SET_ANTIBANDING. */
    val mode: Int = 0,
    /** The on/off state for the two lock keys. */
    val enabled: Boolean = false,
    /** The EV actually requested after clamping, for the reply to echo. */
    val ev: Double = 0.0,
)

internal object ImageControlsPlan {

    /** Every key this file owns, so the dispatcher can route them in one branch. */
    val KEYS = listOf(
        "exposure", "exposure_lock", "whitebalance", "whitebalance_lock", "antibanding",
    )

    /** IP Webcam's white balance vocabulary -> camera2 AWB mode. */
    val WHITE_BALANCE = listOf(
        "auto", "on", "off", "fluorescent", "incandescent", "daylight", "sunny", "cloudy", "shade",
    )

    /** IP Webcam's antibanding vocabulary -> camera2 antibanding mode. */
    val ANTIBANDING = listOf("auto", "off", "50", "60", "50hz", "60hz")

    /**
     * Booleans as the API spells them.
     *
     * An explicit list, not `toBooleanStrictOrNull`, for the same reason
     * OrientationVocabulary whitelists: anything not named here is a typo, and a
     * typo must not be guessed into a state.
     */
    val TRUE_VALUES = listOf("on", "true", "1", "yes", "lock", "locked")
    val FALSE_VALUES = listOf("off", "false", "0", "no", "unlock", "unlocked")

    /**
     * Interprets one request, or null when the KEY is not one of [KEYS].
     *
     * A null means "this object has no opinion about that setting", which is
     * different from a refusal: the caller falls through to its own handling.
     */
    fun resolve(key: String, value: String, caps: ImageControlCaps): ImageControlPlan? =
        when (key) {
            "exposure" -> exposure(value, caps)
            "exposure_lock" -> lock(key, value, caps, exposureLockRefusal = true)
            "whitebalance_lock" -> lock(key, value, caps, exposureLockRefusal = false)
            "whitebalance" -> whiteBalance(value, caps)
            "antibanding" -> antiBanding(value, caps)
            else -> null
        }

    // ── exposure ───────────────────────────────────────────────

    /**
     * `exposure` is an EV offset, and this camera's offsets are integers.
     *
     * The API sends a number ("-2", "0", "2"), while the camera counts steps:
     * `setExposureCompensationIndex` takes an index into the driver's own range
     * and CameraX calls that "index" rather than EV precisely because the step is
     * usually not 1 EV. Dividing by the reported step is what turns the number a
     * client sent into the number the camera wants — and using the reported step
     * rather than a guess is what makes -2 mean -2 EV instead of -2 steps.
     *
     * Out-of-range values are CLAMPED and the reply says so, rather than refused.
     * A client asking for +6 EV on a ±2 EV camera means "as bright as you can",
     * and clamping answers that; refusing would make the setting unreachable for
     * the clients that most want it. This is the same shape as the macro->autofocus
     * mapping in FocusModePlan: the approximation is defensible, so it is allowed
     * but never hidden.
     *
     * A camera with no exposure compensation at all refuses every value,
     * including 0.0. That is the point of passing `exposureSupported` in: index 0
     * looks like a no-op, so it is exactly the value that could be answered "ok"
     * while nothing exists to adjust — and a client that reads that as "exposure
     * works here" has been lied to about the whole key.
     */
    private fun exposure(value: String, caps: ImageControlCaps): ImageControlPlan? {
        val v = value.trim().lowercase()
        val ev = v.toDoubleOrNull()
            ?: return ImageControlPlan(
                key = "exposure",
                requested = v,
                action = ImageControlAction.UNSUPPORTED,
                reply = "",
                refusal = "exposure must be a number of EV steps " +
                    "(\"$v\" is not one, and this API has no auto/normal/long/short " +
                    "exposure mode to fall back on)",
            )
        if (!caps.exposureSupported) {
            return ImageControlPlan(
                key = "exposure",
                requested = v,
                action = ImageControlAction.UNSUPPORTED,
                reply = "",
                refusal = "this camera reports no exposure compensation " +
                    "(isExposureCompensationSupported is false), so exposure cannot " +
                    "be changed",
            )
        }
        val index = evToIndex(ev, caps)
        val clamped = indexToEv(index, caps)
        val clampedAway = kotlin.math.abs(clamped - ev) > 1e-6
        return ImageControlPlan(
            key = "exposure",
            requested = v,
            action = ImageControlAction.SET_EXPOSURE_COMPENSATION,
            reply = buildString {
                append("exposure ")
                append(formatEv(clamped))
                append(" EV (index ")
                append(index)
                append(')')
                if (clampedAway) {
                    // Honest about the approximation, or it is the same silent
                    // wrong answer the macro mapping would be without its note.
                    append("; asked for ")
                    append(formatEv(ev))
                    append(" EV, which this camera cannot reach")
                }
            },
            index = index,
            ev = clamped,
        )
    }

    /**
     * Converts EV to an exposure index using the camera's own step.
     *
     * A step of 0 (a driver that did not report one, or a camera with no
     * compensation at all) would divide by zero, so it is treated as "one index
     * per EV" — the only reading that does not invent precision.
     */
    private fun evToIndex(ev: Double, caps: ImageControlCaps): Int {
        val step = if (caps.exposureEvStep > 0.0) caps.exposureEvStep else 1.0
        // Math.round(Double) answers Long, and coerceIn over Int bounds needs an
        // Int here, so the conversion is explicit rather than a silent .toInt()
        // that could turn a huge value into a small one.
        val raw = Math.round(ev / step)
        if (raw > Int.MAX_VALUE.toLong()) return Int.MAX_VALUE
        if (raw < Int.MIN_VALUE.toLong()) return Int.MIN_VALUE
        val i = raw.toInt()
        return i.coerceIn(
            minOf(caps.exposureIndexMin, caps.exposureIndexMax),
            maxOf(caps.exposureIndexMin, caps.exposureIndexMax),
        )
    }

    /** The inverse of [evToIndex], so the reply can echo EV rather than an index. */
    private fun indexToEv(index: Int, caps: ImageControlCaps): Double {
        val step = if (caps.exposureEvStep > 0.0) caps.exposureEvStep else 1.0
        return index * step
    }

    private fun formatEv(ev: Double): String {
        val rounded = Math.round(ev * 1000.0) / 1000.0
        return if (rounded == Math.floor(rounded) && !rounded.isInfinite()) {
            rounded.toLong().toString()
        } else {
            rounded.toString()
        }
    }

    // ── the two locks ───────────────────────────────────────────

    /**
     * `exposure_lock` and `whitebalance_lock`: hold the current auto decision.
     *
     * Both are camera2 request keys (CONTROL_AE_LOCK, CONTROL_AWB_LOCK) with no
     * CameraX equivalent and no capability characteristic — camera2 does not
     * publish a "supports AE lock" flag, so the only honest question is whether
     * a live session accepted the request, which is what the handler checks.
     *
     * [exposureLockRefusal] only shapes the message. The two locks are wired to
     * different keys and must not share one refusal string: a client that sets
     * exposure_lock and gets back a sentence about white balance has learned
     * nothing about which of its two requests failed.
     */
    private fun lock(
        key: String,
        value: String,
        caps: ImageControlCaps,
        exposureLockRefusal: Boolean,
    ): ImageControlPlan? {
        val v = value.trim().lowercase()
        val enabled = when (v) {
            in TRUE_VALUES -> true
            in FALSE_VALUES -> false
            else -> return ImageControlPlan(
                key = key,
                requested = v,
                action = ImageControlAction.UNSUPPORTED,
                reply = "",
                refusal = "unknown $key value: $value (accepted: " +
                    "${(TRUE_VALUES + FALSE_VALUES).joinToString(", ")})",
            )
        }
        val action = if (exposureLockRefusal) {
            ImageControlAction.SET_EXPOSURE_LOCK
        } else {
            ImageControlAction.SET_WHITE_BALANCE_LOCK
        }
        return ImageControlPlan(
            key = key,
            requested = v,
            action = action,
            reply = if (enabled) {
                "$key on; the current decision is now held"
            } else {
                "$key off; the camera is deciding again"
            },
            enabled = enabled,
        )
    }

    // ── white balance ───────────────────────────────────────────

    /**
     * `whitebalance` names a preset, and each maps onto exactly one camera2 mode.
     *
     * "on" is the one alias worth an explanation: IP Webcam's "on" means "run
     * white balance", which is camera2's AUTO, so it is answered as auto and
     * says so. It is not a synonym invented to pad the list.
     *
     * A preset the camera does not list in CONTROL_AWB_AVAILABLE_MODES is
     * refused, not sent anyway. camera2 rejects an unsupported AWB mode by
     * throwing IllegalArgumentException deep in the session, and a request that
     * dies there leaves the previous mode in place — so "sent" would have been
     * the same Ok-with-nothing-behind-it, one layer deeper.
     */
    private fun whiteBalance(value: String, caps: ImageControlCaps): ImageControlPlan? {
        val v = value.trim().lowercase()
        if (v !in WHITE_BALANCE) {
            return ImageControlPlan(
                key = "whitebalance",
                requested = v,
                action = ImageControlAction.UNSUPPORTED,
                reply = "",
                refusal = "unknown whitebalance: $value (accepted: " +
                    "${WHITE_BALANCE.joinToString(", ")})",
            )
        }
        val mode = when (v) {
            "auto", "on" -> AwbModes.AUTO
            "off" -> AwbModes.OFF
            "fluorescent" -> AwbModes.FLUORESCENT
            "incandescent" -> AwbModes.INCANDESCENT
            "daylight", "sunny" -> AwbModes.DAYLIGHT
            "cloudy" -> AwbModes.CLOUDY_DAYLIGHT
            "shade" -> AwbModes.SHADE
            else -> return null
        }
        if (mode !in caps.awbModes) {
            return ImageControlPlan(
                key = "whitebalance",
                requested = v,
                action = ImageControlAction.UNSUPPORTED,
                reply = "",
                refusal = "whitebalance=$v is not offered by this camera " +
                    "(CONTROL_AWB_AVAILABLE_MODES does not list it); it offers " +
                    namedModes(caps.awbModes),
            )
        }
        return ImageControlPlan(
            key = "whitebalance",
            requested = v,
            action = ImageControlAction.SET_WHITE_BALANCE,
            reply = if (v == "on") {
                "auto; whitebalance=on is this camera's auto mode"
            } else {
                v
            },
            mode = mode,
        )
    }

    /**
     * `antibanding` is the mains frequency the camera should not fight.
     *
     * Same refusal shape as white balance: 50/60/auto/off each name one camera2
     * mode, and a mode the driver does not list is refused rather than sent into
     * a session that will throw on it.
     */
    private fun antiBanding(value: String, caps: ImageControlCaps): ImageControlPlan? {
        val v = value.trim().lowercase()
        if (v !in ANTIBANDING) {
            return ImageControlPlan(
                key = "antibanding",
                requested = v,
                action = ImageControlAction.UNSUPPORTED,
                reply = "",
                refusal = "unknown antibanding: $value (accepted: " +
                    "${ANTIBANDING.joinToString(", ")})",
            )
        }
        val mode = when (v) {
            "50", "50hz" -> AntiBandingModes.HZ50
            "60", "60hz" -> AntiBandingModes.HZ60
            "auto" -> AntiBandingModes.AUTO
            "off" -> AntiBandingModes.OFF
            else -> return null
        }
        if (mode !in caps.antibandingModes) {
            return ImageControlPlan(
                key = "antibanding",
                requested = v,
                action = ImageControlAction.UNSUPPORTED,
                reply = "",
                refusal = "antibanding=$v is not offered by this camera " +
                    "(CONTROL_AE_AVAILABLE_ANTIBANDING_MODES does not list it); it " +
                    "offers ${namedAntibanding(caps.antibandingModes)}",
            )
        }
        return ImageControlPlan(
            key = "antibanding",
            requested = v,
            action = ImageControlAction.SET_ANTIBANDING,
            reply = v,
            mode = mode,
        )
    }

    /** Names the AWB modes a camera offers, so a refusal is actionable. */
    fun namedModes(modes: Set<Int>): String =
        modes.mapNotNull { m ->
            when (m) {
                AwbModes.OFF -> "off"
                AwbModes.AUTO -> "auto"
                AwbModes.INCANDESCENT -> "incandescent"
                AwbModes.FLUORESCENT -> "fluorescent"
                AwbModes.DAYLIGHT -> "daylight"
                AwbModes.CLOUDY_DAYLIGHT -> "cloudy"
                AwbModes.SHADE -> "shade"
                else -> null
            }
        }.sorted().joinToString(", ").ifEmpty { "no white balance modes" }

    /** Names the antibanding modes a camera offers. */
    fun namedAntibanding(modes: Set<Int>): String =
        modes.mapNotNull { m ->
            when (m) {
                AntiBandingModes.OFF -> "off"
                AntiBandingModes.HZ50 -> "50"
                AntiBandingModes.HZ60 -> "60"
                AntiBandingModes.AUTO -> "auto"
                else -> null
            }
        }.sorted().joinToString(", ").ifEmpty { "no antibanding modes" }
}

package com.ocubea.server

import com.ocubea.security.MotionLimits

/**
 * What `overlay`, `awake`, `idle`, `sound*`, `motion_limit`, `motion_event`,
 * `motion_active` and `gps_active` actually mean on this phone, and what a
 * request for each has to answer.
 *
 * Nine of these keys answered HTTP 200 with a body that changed nothing.
 * Measured on a Sony F3311 against the running app:
 *
 *     overlay=on|off                      -> "ok"     image identical
 *     awake=on|off                        -> "ok"     screen behaved as before
 *     idle=on|off                         -> "ok"     nothing read the value
 *     sound|sound_event|sound_timeout=... -> "ok"     no tone, ever
 *     motion_limit=<any number>           -> "ok"     clip length unchanged
 *     motion_event=on|off                 -> "ok"     detector state unchanged
 *     motion_active=on|off                -> "ok"     same
 *     gps_active=on                       -> "false"  a body that is neither Ok
 *                                                            nor a refusal, and
 *                                                            names no state
 *
 * Every one of those is the same defect `focusmode` and `rotate` had, removed
 * from this handler twice before, so the rule here is narrow and mechanical: a
 * 200 is only allowed when the value reached something real, and a value that
 * cannot be honoured is a 400 carrying a reason a client can act on.
 *
 * This file is the decision layer, kept out of [StreamServer] for the reason
 * `FocusModePlan` is: `applySetting` is not unit-testable, and a pure function
 * that the handler may still route around was measured not to protect anything.
 * Every function here is total -- no camera, no socket, no Context.
 *
 * A null return means "not this key" (so [resolve] can keep looking), never
 * "refuse": a refusal always carries a [SettingPlan.refusal] the caller can put
 * in a 400 body.
 */
internal enum class SettingAction { APPLY, REFUSE }

/**
 * Why `gps_active` is not writable, in one place.
 *
 * Lives here rather than in TelemetryHandler because this is where the refusal
 * is composed; the /status.json `behaviors.gps_active.reason` field quotes this
 * constant so the 400 a client gets and the block it reads back afterwards say
 * the same thing. Two hand-written strings for one fact are how they drift
 * apart and both end up wrong.
 */
internal const val GPS_UNWRITABLE =
    "OcuBea declares no location permission and reads no position"

/**
 * One interpreted request: what to do with it, and — when the answer is a
 * refusal — why.
 *
 * [value] and [seconds] are the parsed, already-validated values. The handler
 * applies them; it does not re-parse, because a second parser is a second
 * opinion and the two eventually disagree.
 */
internal data class SettingPlan(
    val key: String,
    val requested: String,
    val action: SettingAction,
    /** Parsed boolean for an on/off key, null for the rest. */
    val value: Boolean? = null,
    /** Parsed integer for a numeric key, null for the rest. */
    val seconds: Int? = null,
    /** Extra text appended after the IP Webcam "Ok" body. */
    val reply: String = "",
    val refusal: String? = null,
) {
    val isRefusal: Boolean get() = action == SettingAction.REFUSE
}

/**
 * The boolean vocabulary, and the parsers for the nine keys above.
 *
 * The accepted spellings are the ones the API itself uses plus the handful of
 * spellings clients in the wild already send. Anything else is refused rather
 * than guessed at: `value !in OFF_VALUES` treats "banana" as ON, which is how a
 * typo turns into a silent state change reported as success.
 */
internal object BehaviorSettingsPlan {

    /** Values that mean ON. Everything else is a refusal, never an implicit ON. */
    val ON_VALUES = setOf("on", "true", "1", "yes", "enable", "enabled")

    /** Values that mean OFF. */
    val OFF_VALUES = setOf("off", "false", "0", "no", "disable", "disabled")

    /**
     * Parses an on/off value, or null when it is neither.
     *
     * Case and surrounding whitespace do not matter: a hand-typed URL and a
     * generated one differ there, and neither is a different intent.
     */
    fun parseBool(value: String): Boolean? {
        val v = value.trim().lowercase()
        return when {
            v in ON_VALUES -> true
            v in OFF_VALUES -> false
            else -> null
        }
    }

    /** The vocabulary a rejected boolean gets, spelled out so a client can fix it. */
    fun boolRefusal(key: String, value: String): String =
        "$key does not understand \"$value\": expected one of " +
            (ON_VALUES + OFF_VALUES).sorted().joinToString(", ")

    private fun refuse(key: String, value: String, why: String) =
        SettingPlan(key, value, SettingAction.REFUSE, refusal = why)

    /**
     * A refusal that still carries a value for the caller to store.
     *
     * For a setting whose *effect* is impossible right now but whose request is
     * worth keeping -- `awake` with no window on screen. The 400 says the
     * request was stored, so the store has to happen; a refusal with a null
     * [SettingPlan.value] would make the handler's `plan.value?.let { save(it) }`
     * a no-op and leave the message describing something that never occurred.
     *
     * That is the same defect as the unconditional ok, one level in: a body
     * making a claim the code does not honour. So the distinction is explicit
     * rather than implied by which constructor was used.
     */
    private fun refuseButStore(
        key: String,
        value: String,
        parsed: Boolean?,
        why: String,
    ) = SettingPlan(
        key, value, SettingAction.REFUSE,
        value = parsed,
        refusal = why,
    )

    private fun applyBool(key: String, value: String, on: Boolean, reply: String = "") =
        SettingPlan(key, value, SettingAction.APPLY, value = on, reply = reply)

    // ── overlay ────────────────────────────────────────────────────────────

    /**
     * `overlay` on/off: the time/date/signal text drawn onto each video frame.
     *
     * [painterReady] is the frame pipeline's own answer, not an assumption. With
     * it false there is no frame to draw on -- nothing is streaming, or the
     * camera has not opened -- so an "Ok" would be a claim about a picture
     * nobody is receiving. The flag itself is still stored, so the overlay
     * appears on the next frame; that is why the refusal says so.
     */
    fun overlay(value: String, painterReady: Boolean): SettingPlan {
        val on = parseBool(value) ?: return refuse("overlay", value, boolRefusal("overlay", value))
        return if (painterReady) {
            applyBool("overlay", value, on, "overlay=${if (on) "on" else "off"}")
        } else {
            // refuseButStore for the same reason as awake: the message says the
            // preference was stored, so the handler has to actually store it --
            // the overlay must appear on the first frame after the camera opens.
            refuseButStore(
                "overlay", value, on,
                "overlay cannot be applied right now: the camera is not producing " +
                    "frames, so there is nothing to draw the overlay on. The " +
                    "preference was stored and takes effect on the next frame.",
            )
        }
    }

    // ── awake ──────────────────────────────────────────────────────────────

    /**
     * `awake` on/off: FLAG_KEEP_SCREEN_ON on a real window.
     *
     * [windowPresent] is whether an activity window is actually on screen. The
     * flag only has meaning on a window, and a service that holds it with no
     * window has changed nothing -- so this is refused with the reason rather
     * than answered, exactly like `focus_distance` is refused for having no
     * primitive to drive.
     *
     * The request is still recorded either way: it is what gets applied to the
     * next window that appears, and `/status.json` reports `requested` and
     * `applied` as separate fields so the gap is visible rather than implied.
     */
    fun awake(value: String, windowPresent: Boolean): SettingPlan {
        val on = parseBool(value) ?: return refuse("awake", value, boolRefusal("awake", value))
        return if (windowPresent) {
            applyBool("awake", value, on, "keep_screen_on=${if (on) "on" else "off"}")
        } else {
            // refuseButStore, not refuse: the handler stores plan.value before it
            // looks at the refusal, and that store is what this message promises.
            refuseButStore(
                "awake", value, on,
                "awake cannot be applied: no OcuBea window is on screen, so there " +
                    "is no window to hold awake. The request was stored and will be " +
                    "applied to the next window that opens.",
            )
        }
    }

    // ── idle ───────────────────────────────────────────────────────────────

    /**
     * `idle` on/off: the power-saving policy, `config.powerSaving`.
     *
     * OcuBea has exactly one such control and it is what IP Webcam's idle
     * toggle means here -- stop spending battery on quality the camera cannot
     * deliver. Both directions are real, so neither is refused.
     */
    fun idle(value: String): SettingPlan {
        val on = parseBool(value) ?: return refuse("idle", value, boolRefusal("idle", value))
        return applyBool("idle", value, on, "power_saving=${if (on) "on" else "off"}")
    }

    // ── sound ──────────────────────────────────────────────────────────────

    /**
     * `sound` on/off: the audible notification on a motion event.
     *
     * [deviceReady] is the device's own answer -- whether a tone generator could
     * actually be opened. A phone that cannot make the sound must not be told
     * that it is now making it.
     *
     * `sound` is the master switch. `sound_event` arms the edge trigger; a
     * client that turns the trigger on while the master is off has still made a
     * real change, and the reply says which of the two is mute so the state is
     * readable from the answer alone.
     *
     * No `masterOn` parameter, and its absence is deliberate: this key *is* the
     * master, so the requested value supersedes the stored one and there is
     * nothing for a second boolean to contribute. It was here, unused, and the
     * compiler said so -- a reader would have taken it for a check that existed.
     */
    fun sound(value: String, deviceReady: Boolean, eventOn: Boolean): SettingPlan {
        val on = parseBool(value) ?: return refuse("sound", value, boolRefusal("sound", value))
        if (on && !deviceReady) {
            return refuse(
                "sound", value,
                "sound cannot be enabled: this device has no audio output the app " +
                    "can open a tone on, so the motion notification would be silent.",
            )
        }
        val effective = on && eventOn
        val why = if (effective) "" else if (!on) " (sound is off)" else " (sound_event is off)"
        return applyBool(
            "sound", value, on,
            "sound=${if (on) "on" else "off"} audible=${if (effective) "yes" else "no$why"}",
        )
    }

    /**
     * `sound_event` on/off: whether a motion *edge* emits a sound at all.
     *
     * Distinct from [sound] on purpose: with the event trigger off, motion is
     * still detected and clips are still written, but nothing is audible. The
     * reply carries the composite so "I armed it and still hear nothing" is a
     * state the answer explains rather than a mystery.
     */
    fun soundEvent(value: String, masterOn: Boolean): SettingPlan {
        val on = parseBool(value) ?: return refuse("sound_event", value, boolRefusal("sound_event", value))
        return applyBool(
            "sound_event", value, on,
            "sound_event=${if (on) "on" else "off"} audible=" +
                if (!on) "no" else if (masterOn) "yes" else "no (sound is off)",
        )
    }

    /**
     * `sound_timeout`: the shortest gap between two notifications, in seconds.
     *
     * 0 means "on every event". The ceiling exists because the buffer is a
     * deadline the user sets to keep a room from chirping all night -- a
     * multi-day "timeout" is a plausible typo (`999999`) and would disable the
     * feature instead of describing it, so it is refused with the range rather
     * than silently clamped. Clamping would have to be visible in the reply,
     * and the IP Webcam body is a bare "Ok", which it could not be.
     */
    fun soundTimeout(value: String): SettingPlan {
        val secs = value.trim().toIntOrNull()
            ?: return refuse(
                "sound_timeout", value,
                "sound_timeout must be a whole number of seconds, got \"$value\" " +
                    "(accepted: ${com.ocubea.security.MotionSoundPolicy.MIN_TIMEOUT_SECONDS}" +
                    "..${com.ocubea.security.MotionSoundPolicy.MAX_TIMEOUT_SECONDS}, " +
                    "0 = every event)",
            )
        if (secs < com.ocubea.security.MotionSoundPolicy.MIN_TIMEOUT_SECONDS ||
            secs > com.ocubea.security.MotionSoundPolicy.MAX_TIMEOUT_SECONDS
        ) {
            return refuse(
                "sound_timeout", value,
                "sound_timeout must be " +
                    "${com.ocubea.security.MotionSoundPolicy.MIN_TIMEOUT_SECONDS}.." +
                    "${com.ocubea.security.MotionSoundPolicy.MAX_TIMEOUT_SECONDS} seconds, " +
                    "got $secs (0 = a tone on every motion event)",
            )
        }
        return SettingPlan(
            "sound_timeout", value, SettingAction.APPLY, seconds = secs,
            reply = "sound_timeout=${secs}s",
        )
    }

    // ── motion_limit ───────────────────────────────────────────────────────

    /**
     * `motion_limit`: how long one motion clip may run, in seconds.
     *
     * This is the value `/sensors.json?sense=motion_limit` has always reported
     * (the recorder's max clip length), so the setting and the sensor describe
     * the same number and a client that reads it back sees its own write.
     *
     * The bounds come from [MotionLimits] rather than from a new constant,
     * because that object is where the clip lengths are already clamped and
     * tested; a second set of numbers here would be free to disagree.
     */
    fun motionLimit(value: String): SettingPlan {
        val secs = value.trim().toIntOrNull()
            ?: return refuse(
                "motion_limit", value,
                "motion_limit must be a whole number of seconds, got \"$value\" " +
                    "(accepted: ${MotionLimits.MIN_MAX_CLIP_SECONDS}" +
                    "..${MotionLimits.MAX_MAX_CLIP_SECONDS})",
            )
        if (secs < MotionLimits.MIN_MAX_CLIP_SECONDS || secs > MotionLimits.MAX_MAX_CLIP_SECONDS) {
            return refuse(
                "motion_limit", value,
                "motion_limit must be ${MotionLimits.MIN_MAX_CLIP_SECONDS}" +
                    "..${MotionLimits.MAX_MAX_CLIP_SECONDS} seconds, got $secs. A clip " +
                    "longer than ${MotionLimits.MAX_MAX_CLIP_SECONDS}s fills the storage " +
                    "this camera has, so the value is refused rather than clamped.",
            )
        }
        return SettingPlan(
            "motion_limit", value, SettingAction.APPLY, seconds = secs,
            reply = "motion_limit=${secs}s",
        )
    }

    // ── motion_event / motion_active ───────────────────────────────────────

    /**
     * `motion_event` on/off: arm the motion pipeline -- detection, and the
     * recorder when the user has motion recording switched on.
     *
     * Off closes any clip in progress, so this is a real stop and not a mute.
     */
    fun motionEvent(value: String): SettingPlan {
        val on = parseBool(value) ?: return refuse("motion_event", value, boolRefusal("motion_event", value))
        return applyBool(
            "motion_event", value, on,
            "motion_event=${if (on) "on" else "off"}",
        )
    }

    /**
     * `motion_active` is refused for every value, and that is the honest answer.
     *
     * It is a *sensor* in the IP Webcam API: "is motion happening right now".
     * Nothing a client writes to this key can make motion appear or stop, so a
     * 200 would be a confirmation of a change that cannot exist. The refusal
     * names the two keys that really do change something, so the client is sent
     * somewhere it can go rather than left with a dead endpoint.
     */
    fun motionActive(value: String): SettingPlan =
        refuse(
            "motion_active", value,
            "motion_active is a read-only sensor (is motion happening now), not a " +
                "setting, so no value of it can be applied. Use " +
                "motion_event=on|off to arm or disarm motion detection, or read " +
                "/sensors.json?sense=motion_active for the current state.",
        )

    // ── gps_active ─────────────────────────────────────────────────────────

    /**
     * `gps_active`: refused for every value, in both directions.
     *
     * OcuBea's AndroidManifest declares no ACCESS_FINE_LOCATION or
     * ACCESS_COARSE_LOCATION, so the permission can never be granted, and
     * nothing in the frame path reads a position. There is no coordinate to
     * stamp on a frame, which means there is nothing to switch on -- and, just
     * as importantly, nothing to switch off.
     *
     * The previous body was the bare string `"false"` for every value: not an
     * "Ok" a client reads as success, not a 400 a client can act on, and not a
     * state anything could have switched off. It reported a state of a feature
     * that does not exist.
     *
     * ## Why there is no capability parameter here
     *
     * An earlier version took `locationCapable` and had an APPLY branch for a
     * device that reported a provider. That branch was the defect again, one
     * level down: the app cannot stamp a coordinate even on a phone with
     * perfect location hardware, so an "Ok" from it would have confirmed a
     * change to nothing. Probing the device is not enough -- what has to exist
     * is the *implementation*, and there is none. So the refusal is
     * unconditional, and adding a real GPS overlay has to change this function
     * and this test, which is the point: the day someone implements it, the
     * test that says "no value of gps_active can be applied" goes red and makes
     * them read this.
     *
     * `off` is refused too, on purpose. Answering 200 for `off` would be the
     * same bare "Ok" the "false" body was, and a client reading it as success
     * would believe it had disabled something that was never there.
     *
     * The refusal is deliberately identical for on and off: a
     * direction-dependent wording ("...to switch off") would let a reader infer
     * that `off` is the meaningful direction and `on` the broken one.
     */
    fun gpsActive(value: String): SettingPlan {
        parseBool(value) ?: return refuse("gps_active", value, boolRefusal("gps_active", value))
        return refuse(
            "gps_active", value,
            "gps_active cannot be applied in either direction: $GPS_UNWRITABLE, so " +
                "there is no coordinate to stamp on the video and no GPS overlay to " +
                "switch on or off. /sensors.json reports gps_active=false because " +
                "nothing here can ever make it true.",
        )
    }

    // ── dispatcher ─────────────────────────────────────────────────────────

    /**
     * Interprets one request for one of the keys above, or returns null when
     * [key] is not one of them.
     *
     * The capabilities are passed in rather than read, so the decision table is
     * a pure function of "what the value says" and "what the device can do".
     */
    fun resolve(
        key: String,
        value: String,
        painterReady: Boolean = false,
        windowPresent: Boolean = false,
        soundDeviceReady: Boolean = true,
        soundMasterOn: Boolean = false,
        soundEventOn: Boolean = true,
    ): SettingPlan? = when (key) {
        "overlay" -> overlay(value, painterReady)
        "awake" -> awake(value, windowPresent)
        "idle" -> idle(value)
        "sound" -> sound(value, soundDeviceReady, soundEventOn)
        "sound_event" -> soundEvent(value, soundMasterOn)
        "sound_timeout" -> soundTimeout(value)
        "motion_limit" -> motionLimit(value)
        "motion_event" -> motionEvent(value)
        "motion_active" -> motionActive(value)
        "gps_active" -> gpsActive(value)
        else -> null
    }

    /** Every key this object owns, for the handler and for telemetry. */
    val KEYS = listOf(
        "overlay", "awake", "idle",
        "sound", "sound_event", "sound_timeout",
        "motion_limit", "motion_event", "motion_active", "gps_active",
    )
}

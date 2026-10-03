package com.ocubea.security

/**
 * The audible motion notification, and what it costs to run.
 *
 * `sound`, `sound_event` and `sound_timeout` all answered `okText("ok")` for
 * every value. There was no tone generator anywhere in the app, no
 * `ToneGenerator`, no `RingtoneManager`, no `SoundPool` -- grep over the whole
 * main source set finds nothing that makes a sound. So the setting was a
 * promise with no implementation, and a client that enabled it got a
 * confirmation and silence.
 *
 * This object is the policy the implementation obeys, kept pure so the bounds
 * can be tested without an `AudioManager`. The tone itself is played by
 * `StreamService` through the platform `ToneGenerator`, because a phone's audio
 * output cannot be faked in a unit test and pretending otherwise is how the
 * last three keys in this file ended up lying.
 */
object MotionSoundPolicy {

    /**
     * 0 means "a tone on every motion event".
     *
     * Anything negative is not a shorter gap, so it is refused rather than
     * coerced: the difference between "notify on every event" and "never
     * notify" is a whole feature, and a negative number that quietly became the
     * first would turn a typo into a mute.
     */
    const val MIN_TIMEOUT_SECONDS = 0

    /**
     * Ceiling on the gap, in seconds (24 hours).
     *
     * The bound is not about what is possible -- nothing stops a week-long
     * silence -- but about what can be meant. A timeout past a day is a typo
     * (`999999`) far more often than a policy, and a typo that silently
     * disables the only user-visible part of motion detection is worse than a
     * refusal that names the range.
     */
    const val MAX_TIMEOUT_SECONDS = 86_400

    /** One day, in milliseconds. Used by the deadline arithmetic. */
    const val MAX_TIMEOUT_MS = MAX_TIMEOUT_SECONDS * 1000L

    /**
     * Whether a tone may be played now, or null when it must not.
     *
     * Both halves of the gate live here so the caller cannot check one and
     * forget the other:
     *
     *  - [masterOn] is the `sound` master switch and [eventOn] is the
     *    `sound_event` trigger. Arming the trigger with the master off makes a
     *    real change to the stored state and is answered 200, but no tone is
     *    emitted -- and the reply says `audible=no` so the difference is visible.
     *  - [nextAllowedAtMs] is the deadline the previous tone set. A motion edge
     *    that arrives inside it is not an error: the request was a *trigger*,
     *    not a command to interrupt, so the edge is simply held back.
     *
     * Returning null rather than Boolean keeps "suppressed" and "played"
     * distinguishable from a caller that ignores the result, which is what
     * `sound_event=on` answering 200 while nothing happened looked like.
     */
    fun toneAllowed(
        masterOn: Boolean,
        eventOn: Boolean,
        nowMs: Long,
        nextAllowedAtMs: Long,
    ): Boolean = masterOn && eventOn && nowMs >= nextAllowedAtMs

    /**
     * The deadline the next tone may play at, after one played now.
     *
     * A timeout of 0 means every event, so the deadline is now -- the next edge
     * is not held back at all.
     */
    fun nextAllowedAt(nowMs: Long, timeoutSeconds: Int): Long =
        if (timeoutSeconds <= 0) nowMs else nowMs + timeoutSeconds * 1000L
}

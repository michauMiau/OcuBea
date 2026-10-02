package com.ocubea.security

import android.media.AudioManager
import android.media.ToneGenerator
import com.ocubea.model.OcuBeaConfig

/**
 * The audible motion notification, made audible.
 *
 * `sound`, `sound_event` and `sound_timeout` answered `okText("ok")` for every
 * value and nothing in the app could make a sound -- grep over the main source
 * set found no `ToneGenerator`, no `RingtoneManager`, no `SoundPool`. So the
 * three keys were a confirmation of nothing.
 *
 * This is the missing half: a real [ToneGenerator], opened lazily, gated by
 * [MotionSoundPolicy]. It lives here rather than in the HTTP handler because the
 * trigger is a motion *edge* in the frame pipeline, not a request -- a tone
 * played from the settings thread would fire once on the request instead of
 * once on the event, which is a different feature.
 *
 * Every failure is contained. A phone with no speaker, a muted stream or a
 * `ToneGenerator` the platform refuses is reported through [isAvailable] so
 * `/settings/sound?set=on` can answer 400 with that reason instead of promising
 * a sound it cannot make.
 */
class MotionTonePlayer(private val config: OcuBeaConfig) {

    /**
     * One shared generator.
     *
     * Constructed on first use rather than in the constructor: `ToneGenerator`
     * allocates a native audio resource, and a camera that has never had motion
     * should not hold one. Reused because a new one per event leaks native
     * handles on several Android 6 builds.
     */
    private var tone: ToneGenerator? = null

    /** Set once a construction attempt fails, so it is not retried every event. */
    @Volatile private var unavailableReason: String? = null

    /** Tones actually emitted. The counter /status.json reports. */
    @Volatile var played: Long = 0L
        private set

    /** Events the policy held back because the timeout had not elapsed. */
    @Volatile var suppressed: Long = 0L
        private set

    /** When the next tone may play, from the last one. */
    @Volatile private var nextAllowedAtMs: Long = 0L

    /** Volume 0..100, deliberately low: this is a notification, not an alarm. */
    private val volumePercent = 80

    /**
     * Whether a tone can be produced here, or the reason it cannot.
     *
     * Null means available. The probe is a real construction attempt, so a
     * device with no audio output is discovered rather than assumed, and the
     * refusal a client gets names the actual failure.
     */
    fun unavailableReason(): String? {
        unavailableReason?.let { return it }
        return try {
            ensureTone()
            unavailableReason
        } catch (e: Throwable) {
            val why = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
            "the platform refused to open an audio tone generator: $why".also {
                unavailableReason = it
            }
        }
    }

    /**
     * Emits a tone if the settings and the timeout allow one.
     *
     * Returns true when a tone was actually produced, so a caller can count a
     * suppressed edge as suppressed rather than as played.
     */
    @Synchronized
    fun onMotionEvent(nowMs: Long): Boolean {
        if (!MotionSoundPolicy.toneAllowed(
                masterOn = config.soundEnabled,
                eventOn = config.soundEventEnabled,
                nowMs = nowMs,
                nextAllowedAtMs = nextAllowedAtMs,
            )
        ) {
            suppressed++
            return false
        }
        val g = try {
            ensureTone()
        } catch (e: Throwable) {
            val why = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
            unavailableReason = "the platform refused to open an audio tone generator: $why"
            suppressed++
            return false
        }
        return try {
            // TONE_PROP_BEEP2 rather than TONE_PROP_PROMPT: the shorter tone is
            // less irritating when the timeout is 0 and a room is empty.
            g.startTone(ToneGenerator.TONE_PROP_BEEP2, TONE_MILLIS)
            played++
            nextAllowedAtMs = MotionSoundPolicy.nextAllowedAt(nowMs, config.soundTimeoutSeconds)
            true
        } catch (e: Throwable) {
            // The platform refused to make a sound. Recorded as a permanent
            // unavailability so the next /settings/sound?set=on answers 400 with
            // this reason instead of another optimistic Ok, and the half-open
            // handle is dropped so it cannot be reused.
            val why = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
            unavailableReason = "the platform could not play a motion tone: $why"
            runCatching { g.release() }
            tone = null
            suppressed++
            false
        }
    }

    /**
     * Opens the generator, or returns the one already open.
     *
     * Availability is NOT probed with `ToneGenerator.getState()`: that returns
     * a *cached* value and reads STATE_NO_RESOURCE_ERROR on a device that has
     * plenty of audio until the first startTone, so a capability check built on
     * it reports a working speaker as silent. What is checked instead is the
     * real startTone below, and a refusal happens only after the platform has
     * actually failed to make a sound.
     */
    private fun ensureTone(): ToneGenerator {
        tone?.let { return it }
        val g = ToneGenerator(AudioManager.STREAM_NOTIFICATION, volumePercent)
        tone = g
        return g
    }

    /** Releases the native handle. Called from the service teardown. */
    fun release() {
        synchronized(this) {
            runCatching { tone?.release() }
            tone = null
        }
    }

    /**
     * What /status.json reports.
     *
     * The four numbers are the whole story of the setting: the request, whether
     * the device can do it, what has been emitted, and what has been held back.
     * A `sound=on` with `played=0, suppressed=0` means nothing has moved yet
     * rather than hiding that.
     */
    fun status(): Map<String, Any> = mapOf(
        "enabled" to config.soundEnabled,
        "event" to config.soundEventEnabled,
        "timeout_s" to config.soundTimeoutSeconds,
        "played" to played,
        "suppressed" to suppressed,
        "available" to (unavailableReason() == null),
        "unavailable_reason" to (unavailableReason() ?: ""),
        // Armed means a tone WOULD be emitted on the next edge if one arrived.
        // It is false whenever the timeout still has time to run, so it is the
        // state a client polling status can act on.
        "armed" to MotionSoundPolicy.toneAllowed(
            config.soundEnabled, config.soundEventEnabled,
            System.currentTimeMillis(), nextAllowedAtMs,
        ),
    )

    companion object {
        /** 250 ms: long enough to hear, short enough not to startle a pet. */
        const val TONE_MILLIS = 250
    }
}
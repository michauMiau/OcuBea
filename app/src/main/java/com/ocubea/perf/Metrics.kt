package com.ocubea.perf

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * The single place a named measurement lives, so any subsystem can report
 * its own cost without passing a timer object through its constructors.
 *
 * Naming rather than passing is a deliberate trade. Passing a [RingTimer] into
 * the encode path would be cleaner to test and worse to use: every call site
 * would need the object, and the first caller to skip it would be invisible.
 * A process-wide registry means a hot path can say `Metrics.span("muxer")`
 * and the number shows up, whether or not anyone wired the plumbing.
 *
 * Two properties keep that from turning into a debuggable mess:
 *
 * - **Off by default.** [enabled] gates every store. A disabled registry costs
 *   one volatile read per call site, and a phone that is also running a camera
 *   should not pay for diagnostics nobody is looking at. Flipping it on is
 *   what the profiler button does.
 * - **Bounded.** At most [MAX_SPANS] names exist. A bug that calls
 *   `span(id.toString())` therefore allocates 64 timers and then stops
 *   recording, rather than growing a map per frame for the rest of the
 *   session. Recording the first N names and reporting the overflow is the
 *   only version of this that cannot itself be the leak it is meant to catch.
 */
object Metrics {
    const val MAX_SPANS = 64

    @Volatile
    var enabled: Boolean = false
        private set

    private val spans = ConcurrentHashMap<String, RingTimer>()
    private val rejected = AtomicLong(0)

    /**
     * Recording is off by default and this is the only way to turn it on, so
     * a caller cannot flip it from a request handler without going through the
     * UI that reports it.
     */
    fun setEnabled(on: Boolean) {
        enabled = on
        if (!on) {
            spans.clear()
            rejected.set(0)
        }
    }

    /** Names that hit the cap and were not recorded. Non-zero means a leak. */
    fun rejectedSpans(): Long = rejected.get()

    fun names(): Set<String> = spans.keys.toSet()

    /**
     * Returns the timer for [name], creating it on first use. Returns null
     * once [MAX_SPANS] distinct names exist, so a caller in a hot loop costs
     * one lookup and no allocation.
     */
    fun timer(name: String): RingTimer? {
        spans[name]?.let { return it }
        if (spans.size >= MAX_SPANS) {
            rejected.incrementAndGet()
            return null
        }
        return spans.getOrPut(name) { RingTimer() }
    }

    fun snapshot(): Map<String, RingTimer.Snapshot> =
        spans.entries.associate { (k, v) -> k to v.snapshot() }

    fun reset() {
        spans.values.forEach { it.reset() }
        rejected.set(0)
    }

    // ── Span names ──────────────────────────────────────────────
    //
    // Constants, not inline literals: a typo in a span name would create a
    // second timer that nobody ever reads, and the report would look complete
    // while missing the subsystem you meant.

    const val ENCODE = "encode"
    const val MUX = "mux"
    const val FRAME_ANALYZE = "analyze"
    const val JPEG = "jpeg"
    const val CLIP_WRITE = "clipWrite"
    const val HTTP = "http"
    const val POLL = "poll"
    const val MAIN = "main"
}

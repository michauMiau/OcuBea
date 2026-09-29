package com.ocubea.perf

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray

/**
 * A small fixed-width timing accumulator, so a hot path can be measured
 * without a clock read, a String, or an allocation.
 *
 * Why it exists: regressions in this app are almost never visible in a build
 * or a unit test. A frame that starts costing 2 ms more, a poll that starts
 * allocating 40 KB, a muxer that starts copying twice — all of those pass
 * 177 green tests. The only way to catch them is to measure on the device and
 * keep the number, so the next run can be compared against it.
 *
 * Design constraints, all of them learned the hard way here:
 *
 * - **No allocation per sample.** The hot path calls [begin]/[end] millions
 *   of times. Storing into a preallocated [AtomicLongArray] of ring slots and
 *   computing percentiles on read means a measurement costs a clock read and
 *   two array stores, and nothing else.
 * - **Percentiles from a ring, not a growing list.** A growing list would let
 *   a long session allocate without bound — the exact leak shape this app has
 *   to defend against elsewhere.
 * - **The ring truncates silently, and that is the design.** A long session
 *   keeps the newest `capacity` samples, not all of them. [Snapshot.window]
 *   reports how many samples the reported percentiles actually describe, so a
 *   window shorter than the run is visible instead of being read as "this is
 *   the whole picture". A dropped-sample counter that nothing increments is
 *   worse than no counter, so there is none — this field says the same thing
 *   and cannot lie.
 * - **Readers never reset.** [snapshot] is read from a background thread
 *   while the measured path keeps writing, so it must be a consistent-enough
 *   view, not a transaction. [reset] is explicit and for the test's benefit.
 *
 * Deliberately not a general profiler: it times spans this class is asked to
 * time, on threads the caller names, and reports what the caller recorded. It
 * does not sample stacks, walk the heap, or attribute time to a caller. See
 * [ThreadTime] for per-thread CPU share, which is a different question.
 */
class RingTimer(
    /** Ring size. Must be a power of two so the index wraps with a mask. */
    capacity: Int = 256,
) {
    init {
        require(capacity > 0 && capacity and (capacity - 1) == 0) {
            "capacity must be a power of two, was $capacity"
        }
    }

    private val mask = capacity - 1
    private val slots = AtomicLongArray(capacity)
    private val written = AtomicLong(0)
    private val totalNanos = AtomicLong(0)
    private val maxNanos = AtomicLong(0)

    /** Call immediately before the measured span. Returns the start token. */
    fun begin(): Long = System.nanoTime()

    /** Call immediately after the measured span, with the token from [begin]. */
    fun end(startNanos: Long) {
        val delta = System.nanoTime() - startNanos
        // A negative delta means the two nanoTime reads straddled a wrap or a
        // suspend; it is not a measurement, and averaging it in would poison
        // every percentile. Dropping it is the only honest option.
        if (delta < 0) return
        store(delta)
    }

    /** Cumulative sample count since the last [reset]. */
    fun count(): Long = written.get()

    /**
     * Records a known duration directly, bypassing the clock.
     *
     * This exists for tests and for callers that already measured the span
     * themselves. It is NOT a way to fake a measurement in production: a
     * number passed in here is taken on trust, which is exactly the property
     * a profiler must not have.
     *
     * It also solves a real testing problem. `end(begin() - x)` does not
     * record x — `end` measures its own `nanoTime` read and adds that cost to
     * the span, so the recorded value is x plus tens of nanoseconds that
     * depend on the machine. A test asserting equality on such a value is
     * flaky, and asserting on it with a margin is a test that silently
     * stopped testing the thing it names. Here the duration is exact.
     */
    fun record(durationNanos: Long) {
        if (durationNanos < 0L) return
        store(durationNanos)
    }

    /**
     * The single write path. Both [end] and [record] funnel here, so a fix to
     * how a sample is stored cannot be made in one and missed in the other.
     */
    private fun store(delta: Long) {
        val n = written.incrementAndGet()
        slots[((n - 1).toInt()) and mask] = delta
        totalNanos.addAndGet(delta)
        // A lock-free max: a lost race just means a later sample wins, which
        // is the same answer, so compareAndSet is correct here and cheaper
        // than a lock.
        var cur = maxNanos.get()
        while (delta > cur && !maxNanos.compareAndSet(cur, delta)) {
            cur = maxNanos.get()
        }
    }

    fun reset() {
        written.set(0)
        totalNanos.set(0)
        maxNanos.set(0)
        for (i in 0..mask) slots[i] = 0
    }

    /**
     * Reads the ring and reports the distribution. Allocates exactly one
     * array, so it is safe to call from a status handler and not from a hot
     * path.
     */
    fun snapshot(): Snapshot {
        val n = written.get()
        if (n == 0L) return Snapshot.EMPTY
        val window = minOf(n, (mask + 1).toLong()).toInt()
        val copy = LongArray(window)
        // Read the newest `window` slots. With a ring the newest write is at
        // (n-1) masked, so walk backwards from it rather than forwards from
        // zero, which on a wrapped ring would return the oldest samples.
        val newest = ((n - 1).toInt()) and mask
        for (i in 0 until window) {
            copy[i] = slots[(newest - i) and mask]
        }
        copy.sort()
        return Snapshot(
            totalSamples = n,
            window = window,
            meanNanos = totalNanos.get() / n,
            p50Nanos = percentile(copy, 0.50),
            p95Nanos = percentile(copy, 0.95),
            p99Nanos = percentile(copy, 0.99),
            maxNanos = maxNanos.get(),
        )
    }

    private fun percentile(sorted: LongArray, q: Double): Long {
        if (sorted.isEmpty()) return 0
        val idx = ((sorted.size - 1) * q).toInt().coerceIn(0, sorted.size - 1)
        return sorted[idx]
    }

    data class Snapshot(
        /** Every sample taken since the last reset, including evicted ones. */
        val totalSamples: Long,
        /** How many of those the percentiles and max actually describe. */
        val window: Int,
        val meanNanos: Long,
        val p50Nanos: Long,
        val p95Nanos: Long,
        val p99Nanos: Long,
        val maxNanos: Long,
    ) {
        val meanMillis: Double get() = meanNanos / 1_000_000.0
        val p50Millis: Double get() = p50Nanos / 1_000_000.0
        val p95Millis: Double get() = p95Nanos / 1_000_000.0
        val p99Millis: Double get() = p99Nanos / 1_000_000.0
        val maxMillis: Double get() = maxNanos / 1_000_000.0

        /**
         * True when the ring has already evicted samples, i.e. the
         * percentiles below describe only the most recent window and not the
         * whole run since the last reset.
         */
        val truncated: Boolean get() = totalSamples > window

        companion object {
            val EMPTY = Snapshot(0, 0, 0, 0, 0, 0, 0)
        }
    }
}

/**
 * Per-thread CPU share, answered by the OS rather than sampled by us.
 *
 * `Debug.threadCpuTimeNanos()` is a cheap read of a kernel counter. Sampling
 * it twice and dividing by the wall-clock delta gives the fraction of a core
 * the CALLING thread was using. That is the question that finds bottlenecks:
 * a thread at 90% is a subsystem to read, a thread at 3% is not worth
 * optimising.
 *
 * Two hard limits, both consequences of the API rather than choices:
 *
 * - It measures the calling thread only. There is no API-23 way to ask about
 *   an arbitrary thread id (`Process.getThreadCpuTimeNanos` is API 26+), so
 *   this object is per-thread by construction. For "how busy is thread X" for
 *   an X you are not running on, use `adb shell top -H`.
 * - It cannot tell you *which method* in a busy thread is responsible, only
 *   that the thread is. For that use `simpleperf` or Perfetto on the device.
 *
 * So this is a regression tripwire, not a profiler: a number you can diff
 * between two builds.
 */
class ThreadTime {

    private val lastCpuNanos = AtomicLong(0)
    private val lastWallNanos = AtomicLong(0)

    /**
     * False on a JVM unit test, where `android.os.Debug` is a stub. Callers
     * that must not report a wrong number check this rather than trusting a
     * zero, and the UI shows "unavailable" instead of "0% busy".
     */
    fun isAvailable(): Boolean = ProcessProbe.callerCpuNanos() != null

    /**
     * Marks a sample point on the calling thread. Returns the share observed
     * since the previous call here, or null when there is no usable previous
     * point: the first call, a clock that went backwards, or a pair that
     * straddles a suspension.
     */
    fun sample(wallNanos: Long = System.nanoTime()): Share? {
        val cpu = ProcessProbe.callerCpuNanos() ?: return null
        val prevCpu = lastCpuNanos.get()
        val prevWall = lastWallNanos.get()
        lastCpuNanos.set(cpu)
        lastWallNanos.set(wallNanos)
        val wallDelta = wallNanos - prevWall
        val cpuDelta = cpu - prevCpu
        if (prevCpu <= 0L || wallDelta <= 0L || cpuDelta < 0L) return null
        // A thread cannot use more than one core per unit of wall time, so a
        // larger cpuDelta means the pair straddles a suspend or a restart and
        // the ratio is meaningless. Report null rather than a 400% lie.
        if (cpuDelta > wallDelta) return null
        return Share(cpuDelta.toDouble() / wallDelta.toDouble())
    }

    data class Share(val fraction: Double) {
        val percent: Double get() = fraction * 100.0
    }
}

/**
 * Isolated so [ThreadTime] holds no `android.os.Process` reference in a
 * signature: that class is absent from the unit-test classpath, and a class
 * that cannot be loaded cannot be constructed to assert it degrades to null.
 */
private object ProcessProbe {
    fun callerCpuNanos(): Long? = try {
        val t = android.os.Debug.threadCpuTimeNanos()
        if (t > 0L) t else null
    } catch (_: Throwable) {
        null
    }
}

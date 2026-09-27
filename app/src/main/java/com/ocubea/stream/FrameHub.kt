package com.ocubea.stream

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * In-memory frame hub: holds the latest JPEG frame and fans it out to every
 * connected MJPEG viewer without touching the filesystem.
 *
 * ## Design: one-slot handoff, not a queue
 *
 * Each viewer keeps a **single slot** holding the newest frame, published
 * through an [AtomicReference]. Two reasons this is not a backlog queue:
 *
 * 1. **Latency.** A queue lets a slow client fall behind and then be served
 *    stale frames — the picture lags reality by however far behind it drifted.
 *    Overwriting the slot means a viewer that wakes up gets the *current*
 *    frame, so latency stays bounded by one encode no matter how slow the
 *    client is.
 * 2. **Correctness and cost.** The previous implementation mixed an
 *    unsynchronized `queue.addLast()` in publish() with a
 *    `synchronized(queue) { queue.removeFirst() }` in pollFrame() — a data race
 *    on a non-thread-safe ArrayDeque that can corrupt its internal arrays. It
 *    also allocated a queue node per frame per viewer. A single atomic slot
 *    has neither problem: no lock, no per-frame allocation, no race.
 */
class FrameHub {

    /** Latest complete JPEG frame. */
    private val latestFrame = AtomicReference<ByteArray?>(null)

    /** Monotonic sequence number of [latestFrame]; increments per produced frame. */
    val frameSeq = AtomicLong(0)

    @Volatile var fps: Int = 0
        private set

    private val viewers = ConcurrentLinkedQueue<Viewer>()

    /**
     * A registered consumer. [pending] is a one-slot mailbox: the producer
     * overwrites it, the consumer takes it.
     */
    class Viewer internal constructor(val id: Long) {
        val pending = AtomicReference<ByteArray?>(null)
        @Volatile var active = true
    }

    private val viewerCounter = AtomicLong(0)

    /** Called by the capture pipeline for every produced frame. */
    fun publish(frame: ByteArray) {
        if (frame.isEmpty()) return
        latestFrame.set(frame)
        frameSeq.incrementAndGet()

        // Rolling FPS estimate over ~1s.
        frameCountThisSecond++
        val now = System.nanoTime()
        if (now - lastFpsSample >= 1_000_000_000L) {
            fps = frameCountThisSecond
            frameCountThisSecond = 0
            lastFpsSample = now
        }

        // Fan out. Overwrite unconditionally: a viewer that cannot keep up
        // gets the newest frame instead of accumulating a backlog.
        for (v in viewers) {
            if (v.active) v.pending.set(frame)
        }
    }

    @Volatile private var lastFpsSample = System.nanoTime()
    @Volatile private var frameCountThisSecond = 0

    fun getLatest(): ByteArray? = latestFrame.get()

    fun hasFrame(): Boolean = latestFrame.get() != null

    /** Drop the current frame and all viewer slots — used when the camera rebinds. */
    fun reset() {
        latestFrame.set(null)
        frameSeq.set(0)
        frameCountThisSecond = 0
        for (v in viewers) v.pending.set(null)
    }

    /** Register a new MJPEG viewer. */
    fun addViewer(): Viewer = Viewer(viewerCounter.getAndIncrement()).also { viewers.add(it) }

    fun removeViewer(v: Viewer) {
        v.active = false
        v.pending.set(null)
        viewers.remove(v)
    }

    fun viewerCount(): Int = viewers.size

    /**
     * Waits up to [timeoutMs] for this viewer's next frame.
     *
     * Returns null when the viewer was removed or the timeout expired with
     * nothing available. A merely slow viewer gets the newest frame rather
     * than a stale one.
     */
    fun pollFrame(v: Viewer, timeoutMs: Long): ByteArray? {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (true) {
            v.pending.getAndSet(null)?.let { return it }
            if (!v.active) return null
            val left = deadline - System.nanoTime()
            if (left <= 0) return getLatest()
            // Park briefly rather than spin: the producer posts on every frame,
            // so a short wait costs no measurable latency.
            try {
                Thread.sleep(if (left > 2_000_000L) 2L else 1L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
        }
    }
}

package com.ocubea.stream

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
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
     * Live viewer count, kept separately because [ConcurrentLinkedQueue.size]
     * walks the whole queue. Also the cap's authority - see [addViewer].
     */
    private val viewerCount = AtomicInteger(0)

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

    companion object {
        /**
         * Concurrent MJPEG viewers before new ones are refused.
         *
         * Measured on the device: at 8 open `/video` connections the handler
         * pool is full, NanoHTTPD closes every new socket, and `/status.json`
         * and `/shot.jpg` stop answering in ~5ms while the streams themselves
         * keep running. Six viewers left the same requests at 24ms. Refusing
         * the seventh keeps the control surface alive, which is the part that
         * matters: a person who cannot load the page cannot turn the camera
         * off.
         */
        const val MAX_VIEWERS = 6
    }

    /**
     * Register a new MJPEG viewer, or return null when the hub is full.
     *
     * The limit exists because of a measured failure, not a guess. Every
     * `/video` connection occupies one of the server's handler threads for the
     * whole life of the stream - hours. With the thread pool full, NanoHTTPD
     * closes the *new* socket, and that includes `/status.json` and
     * `/shot.jpg`: at 8 concurrent viewers on the device the entire control
     * surface stopped answering in 5ms while video kept streaming, with
     * nothing in logcat. At 6 viewers the same requests answered in 24ms.
     *
     * Refusing here is the cheap half of the fix: the request is rejected
     * inside its own handler, so a surplus viewer never takes a thread at all.
     * `BoundedAsyncRunner` is the outer bound for everything else.
     */
    fun addViewer(): Viewer? {
        // Compare-and-set, not size(): ConcurrentLinkedQueue.size() is O(n) and
        // racy against a concurrent remove, which would let the cap be exceeded.
        while (true) {
            val n = viewerCount.get()
            if (n >= MAX_VIEWERS) return null
            if (viewerCount.compareAndSet(n, n + 1)) break
        }
        val v = Viewer(viewerCounter.getAndIncrement())
        viewers.add(v)
        return v
    }

    fun removeViewer(v: Viewer) {
        v.active = false
        v.pending.set(null)
        if (viewers.remove(v)) viewerCount.decrementAndGet()
    }

    fun viewerCount(): Int = viewerCount.get()

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

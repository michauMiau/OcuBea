package com.ocubea.stream

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference

/**
 * In-memory frame hub: holds the latest JPEG frame and fans out frames
 * to every connected MJPEG viewer without touching the filesystem.
 */
class FrameHub {

    /** Latest complete JPEG frame. */
    private val latestFrame = AtomicReference<ByteArray?>(null)

    /** Monotonic sequence number of [latestFrame]; increments per produced frame. */
    @Volatile var frameSeq: Long = 0
        private set

    @Volatile var fps: Int = 0
        private set

    private val viewers = ConcurrentLinkedQueue<Viewer>()

    class Viewer internal constructor(val id: Long) {
        val queue = ArrayDeque<ByteArray>()
        @Volatile var active = true
    }

    private var viewerCounter = 0L

    /** Called by the capture pipeline for every produced frame. */
    fun publish(frame: ByteArray) {
        if (frame.isEmpty()) return
        latestFrame.set(frame)
        frameSeq++
        // simple rolling FPS estimate over last second
        val now = System.nanoTime()
        if (now - lastFpsSample >= 1_000_000_000L) {
            fps = frameCountThisSecond
            frameCountThisSecond = 0
            lastFpsSample = now
        }
        frameCountThisSecond++
        for (v in viewers) {
            if (!v.active || v.queue.size > 2) { // drop slow viewers' backlog
                if (v.queue.isNotEmpty()) v.queue.clear()
                continue
            }
            v.queue.addLast(frame)
        }
    }

    private var lastFpsSample = System.nanoTime()
    private var frameCountThisSecond = 0

    fun getLatest(): ByteArray? = latestFrame.get()

    fun hasFrame(): Boolean = latestFrame.get() != null

    /** Register a new MJPEG viewer; returns null-safe handle used in removeViewer(). */
    fun addViewer(): Viewer {
        val v = Viewer(viewerCounter++)
        viewers.add(v)
        return v
    }

    fun removeViewer(v: Viewer) {
        v.active = false
        viewers.remove(v)
    }

    fun viewerCount(): Int = viewers.size

    /**
     * Blocking pull of the next frame for a viewer.
     * Returns the newest queued frame, or waits up to [timeoutMs] for one.
     */
    fun pollFrame(v: Viewer, timeoutMs: Long): ByteArray? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            synchronized(v.queue) {
                if (v.queue.isNotEmpty()) return v.queue.removeFirst()
            }
            if (!v.active) return null
            try { Thread.sleep(10) } catch (_: InterruptedException) { return null }
        }
        // timeout: fall back to latest frame so slow clients never stall forever
        return getLatest()
    }
}

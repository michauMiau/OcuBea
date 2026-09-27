package com.ocubea.security

import android.content.Context
import com.ocubea.stream.H264Encoder
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Writes clips as fragmented MP4 — the same muxing path HLS already uses.
 *
 * Replaces [AviWriter], which wrapped MJPEG frames in AVI: at 1920x1080 that is
 * roughly 2.4 MB per second of clip, so a 300s clip filled 700 MB. fMP4 from
 * the hardware H.264 encoder is around 200 kB/s, twelve times smaller, and the
 * resulting file seeks properly in a browser because it carries the same
 * moof/mdat structure a player already parses.
 *
 * The file is opened by [openWith] rather than by a start() call because the
 * init segment cannot exist before the encoder has produced its SPS/PPS: the
 * avcC record inside moov is built from them. Opening a file first would
 * produce a clip whose track header contradicts its samples, and the gallery
 * reports a zero-length file.
 *
 * Nothing here touches the camera thread. Samples are handed over through a
 * bounded queue and written on a dedicated thread: disk I/O on the
 * ImageAnalysis path would stall frame delivery, and the camera has priority
 * over recording.
 */
class ClipWriter(private val context: Context) {

    private companion object {
        /** 64 H.264 samples at 1080p is a few hundred KB — bounded on purpose. */
        const val QUEUE_CAPACITY = 64
        const val THREAD_NAME = "ocubea-clipwriter"
        const val PUMP_POLL_MS = 200L
    }

    private val lock = Any()
    private val running = AtomicBoolean(false)
    private val queue = LinkedBlockingQueue<H264Encoder.Sample>(QUEUE_CAPACITY)
    /** Set by the owner; muxes and writes the queued samples. */
    private var muxer: com.ocubea.stream.Fmp4Writer? = null

    private var thread: Thread? = null
    private var file: RandomAccessFile? = null
    private var activeName: String? = null

    @Volatile var recording = false
        private set
    @Volatile var bytesWritten = 0L
        private set
    @Volatile var lastError: String? = null
        private set
    @Volatile var framesWritten = 0
        private set
    @Volatile var framesDropped = 0
        private set

    val activeClip: String? get() = activeName

    /**
     * True while a file is open. The retention sweep must skip it — deleting
     * the open file leaves a corrupt clip and a handle pointing at nothing.
     */
    val hasOpenClip: Boolean get() = synchronized(lock) { file != null }

    // ── Lifecycle ──────────────────────────────────────────────────────────

    /**
     * Opens the clip. [init] is ftyp+moov and must already contain the avcC
     * record, so it is written before any media segment and the file is a valid
     * MP4 from byte zero even if recording stops a frame later.
     */
    fun openWith(init: ByteArray, fps: Int, mx: com.ocubea.stream.Fmp4Writer): Boolean =
        synchronized(lock) {
            if (file != null) return true
            val name = ClipStorage.newClipName()
            val target = ClipStorage.resolve(context, name) ?: run {
                lastError = "clip path rejected"
                return false
            }
            try {
                val raf = RandomAccessFile(target, "rw")
                raf.setLength(0)
                raf.write(init)
                file = raf
                activeName = name
                muxer = mx
                mx.setMeasuredFps(fps.toDouble())
                bytesWritten = init.size.toLong()
                framesWritten = 0
                framesDropped = 0
                lastError = null
                recording = true
                running.set(true)
                thread = Thread({ pump() }, "$THREAD_NAME-pump").apply {
                    priority = Thread.NORM_PRIORITY - 1
                    isDaemon = true
                    start()
                }
                return true
            } catch (e: IOException) {
                lastError = e.message ?: "cannot open clip"
                runCatching { file?.close() }
                file = null
                activeName = null
                return false
            }
        }

    /**
     * Queues one encoded sample. Never blocks: a full queue drops the frame and
     * counts it, because falling behind on recording must never turn into
     * falling behind on the camera.
     */
    fun offer(sample: H264Encoder.Sample) {
        if (!recording) return
        if (!queue.offer(sample)) framesDropped++
    }

    /** Stops the clip and flushes the file. Safe to call when not recording. */
    fun stop() {
        running.set(false)
        runCatching { thread?.join(3000) }
        thread = null
        synchronized(lock) { closeFile() }
        recording = false
    }

    /** Stops and deletes the partial file — used when the camera shuts down. */
    fun abort() {
        val name = activeName
        stop()
        if (name != null) runCatching { ClipStorage.resolve(context, name)?.delete() }
    }

    // ── Internals ──────────────────────────────────────────────────────────

    private fun pump() {
        while (running.get() || queue.isNotEmpty()) {
            val sample = try {
                queue.poll(PUMP_POLL_MS, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                null
            } ?: continue

            val bytes = runCatching { muxer?.append(sample) }.getOrNull()?.bytes
            if (bytes == null) continue

            val ok = synchronized(lock) {
                val raf = file
                if (raf == null) {
                    false
                } else {
                    try {
                        raf.write(bytes)
                        bytesWritten += bytes.size
                        framesWritten++
                        true
                    } catch (e: IOException) {
                        // A pulled SD card lands here.
                        lastError = e.message ?: "clip write failed"
                        false
                    }
                }
            }
            // Stop cleanly on a dead handle rather than spinning on it for the
            // rest of the session.
            if (!ok) running.set(false)
        }
    }

    private fun closeFile() {
        val raf = file
        file = null
        if (raf != null) {
            // A clip that dies without this is missing its mfra/mfro, and the
            // next startup sweep has to treat it as unfinished.
            runCatching { raf.fd.sync() }
            runCatching { raf.close() }
        }
        activeName = null
        muxer = null
        queue.clear()
    }
}

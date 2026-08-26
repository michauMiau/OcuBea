package com.ocubea.security

import android.graphics.Bitmap
import android.graphics.Color
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lightweight frame-difference motion detector.
 * Downsamples the frame to a 32x24 grayscale grid and compares to the previous
 * frame; configurable fraction of changed blocks above threshold = motion.
 */
class MotionDetector(
    sensitivity: Int = 50,
    private var minChangedFraction: Float = 0.02f
) {
    @Volatile var enabled = false

    private val gridW = 32
    private val gridH = 24
    private var previous: IntArray? = null
    @Volatile var lastMotionTime: Long = 0
        private set
    @Volatile var motionDetected: Boolean = false
        private set

    /** Fired on the rising edge of motion detection. */
    var onMotionStart: (() -> Unit)? = null
    var onMotionStop: (() -> Unit)? = null

    private var threshold: Int = computeThreshold(sensitivity)

    fun setSensitivity(s: Int) {
        threshold = computeThreshold(s.coerceIn(0, 100))
    }

    private fun computeThreshold(s: Int): Int = ((100 - s.coerceIn(0, 100)) * 40 / 100) + 2

    /** Analyze a frame; returns true while motion is considered active. */
    fun process(bitmap: Bitmap): Boolean {
        if (!enabled) {
            if (motionDetected) { motionDetected = false; onMotionStop?.invoke() }
            previous = null
            return false
        }
        val small = Bitmap.createScaledBitmap(bitmap, gridW, gridH, true)
        val cur = IntArray(gridW * gridH)
        small.getPixels(cur, 0, gridW, 0, 0, gridW, gridH)
        for (i in cur.indices) {
            val c = cur[i]
            cur[i] = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
        }
        if (small !== bitmap) small.recycle()

        val prev = previous ?: run { previous = cur; return motionDetected }
        previous = cur
        if (prev.size != cur.size) return motionDetected

        var changed = 0
        for (i in cur.indices) if (Math.abs(cur[i] - prev[i]) > threshold) changed++

        val now = System.currentTimeMillis()
        val detected = changed.toFloat() / cur.size >= minChangedFraction
        return if (detected) {
            lastMotionTime = now
            if (!motionDetected) { motionDetected = true; onMotionStart?.invoke() }
            true
        } else {
            // 2s grace before clearing — avoids flicker
            if (motionDetected && now - lastMotionTime > 2000) {
                motionDetected = false
                onMotionStop?.invoke()
            }
            motionDetected
        }
    }
}

/**
 * Records motion events as Motion-JPEG AVI files to device storage.
 */
class MotionRecorder(outputDir: File) {

    @Volatile var enabled = false

    @Volatile var preRecordSeconds: Int = 2
    @Volatile var maxClipSeconds: Int = 300
    @Volatile var postMotionFrames: Int = 75

    private var dir: File = outputDir.also { it.mkdirs() }
    private val preBuffer = ArrayDeque<ByteArray>()
    private val lock = Any()

    private var writer: AviWriter? = null
    private var idleFrames = 0
    private var clipFrames = 0
    private var currentFps = 15

    @Volatile var recording = false
        private set
    @Volatile var lastRecordingFile: String? = null
        private set
    @Volatile var recordingsCount: Int = 0
        private set

    fun setOutputDir(d: File) { dir = d.also { it.mkdirs() } }

    fun listRecordings(): List<File> =
        dir.listFiles { f -> f.name.endsWith(".avi") }?.sortedByDescending { it.lastModified() } ?: emptyList()

    fun deleteRecording(name: String): Boolean {
        if (!name.endsWith(".avi") || name.contains('/') || name.contains("..")) return false
        return File(dir, name).delete()
    }

    /**
     * Feed every produced frame here while security mode is armed.
     * [isMotion] is the detector state for this frame.
     */
    fun onFrame(jpeg: ByteArray, isMotion: Boolean, fps: Int) {
        if (!enabled) return stopAll()
        synchronized(lock) {
            currentFps = fps.coerceIn(1, 30)
            if (!recording && !isMotion) {
                preBuffer.addLast(jpeg)
                while (preBuffer.size > preRecordSeconds * currentFps) preBuffer.removeFirst()
                return
            }
            if (!recording) startClip()
            try {
                writer?.addFrame(jpeg) ?: return
            } catch (_: Exception) { stopAll(); return }
            idleFrames = if (isMotion) 0 else idleFrames + 1
            clipFrames++
            if (idleFrames >= postMotionFrames || clipFrames >= maxClipSeconds * currentFps) finishClip()
        }
    }

    private fun startClip() {
        val name = "motion_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.avi"
        val file = File(dir, name)
        writer = AviWriter(file, lastWidth, lastHeight, currentFps)
        for ((i, jpeg) in preBuffer.withIndex()) writer?.addFrame(jpeg)
        preBuffer.clear()
        recording = true
        idleFrames = 0
        clipFrames = 0
        lastRecordingFile = name
    }

    fun finishClip() {
        try { writer?.close() } catch (_: Exception) {}
        writer = null
        recording = false
        recordingsCount = listRecordings().size
    }

    fun stopAllIfIdle() {
        if (!enabled && !recording) synchronized(lock) { preBuffer.clear() }
    }

    private fun stopAll() {
        synchronized(lock) {
            if (recording) finishClip() else preBuffer.clear()
        }
    }

    companion object {
        // updated by StreamServer when frames flow through
        @JvmStatic var lastWidth = 640
        @JvmStatic var lastHeight = 480
    }
}

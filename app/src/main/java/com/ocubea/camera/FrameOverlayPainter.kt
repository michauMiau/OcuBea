package com.ocubea.camera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The video overlay: the time, the date and the camera name drawn onto every
 * frame, which is what `overlay=on` means in the IP Webcam API.
 *
 * `/settings/overlay` answered `okText("ok")` for every value and drew nothing.
 * There was no overlay code anywhere in the app: `grep -ri overlay` over the
 * main source set returned the handler line, the two `curvals` entries and the
 * sensor stub. So the setting was a claim about the picture with no picture
 * behind it, and `curvals.overlay` hardcoded `"on"` while the sensor answered
 * `"false"` -- the same number in two places, contradicting.
 *
 * ## Why the bitmap, and not a Canvas over the view
 *
 * The frames are JPEG bytes produced on an encode pool and fanned out to
 * viewers over a socket ([com.ocubea.stream.FrameHub]). There is no shared
 * Surface to draw on, so the overlay has to become part of the pixels. It is
 * drawn once per frame here, on the same bitmap the effects pass already owns,
 * so it costs one `Canvas` per frame and no extra copy.
 *
 * ## Why it is drawn before the effects rather than after
 *
 * `applyEffects` replaces the whole pixel array for mono/sepia/nightvision. An
 * overlay painted before it would come out of a night-vision frame as grey on
 * grey, which is both ugly and -- worse -- indistinguishable from the pixel
 * noise the effect exists to create. It is applied after, on the final bitmap.
 */
class FrameOverlayPainter {

    /**
     * Whether the overlay is painted. Read per frame by the capture path and
     * written from an HTTP thread, so it is volatile rather than a plain field.
     */
    @Volatile var enabled: Boolean = false

    /** Frames actually stamped. The counter the /status.json block reads. */
    @Volatile private var stamped = 0L

    /** The clock, rebuilt only when the second changes -- not per frame. */
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

    /** The date line under the clock. Same rule: rebuilt once a day. */
    private val dateLine = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    private var lastSecond: Int = -1
    private var lastDay: Int = -1
    private var cachedClock = ""
    private var cachedDate = ""

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        setShadowLayer(4f, 1f, 1f, Color.BLACK)
    }

    /**
     * A semi-opaque bar behind the text.
     *
     * White text on a bright window is unreadable, and a shadow alone does not
     * fix that -- it only smudges the edge. The bar is what makes the overlay
     * legible against a sky.
     */
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(96, 0, 0, 0)
    }

    /**
     * Stamps [source] and returns the bitmap that now carries the overlay.
     *
     * Returns [source] itself when there is nothing to draw, so the caller's
     * ownership rules stay exactly as they were for a frame with the overlay
     * off -- `applyEffects` already documents that it may return its input.
     *
     * Text size is a fraction of the frame height rather than a fixed value: an
     * overlay sized in pixels is legible at 1080p and invisible at 480x270,
     * and the resolution is not the caller's to know about.
     */
    fun paint(source: Bitmap, label: String, nowMs: Long): Bitmap {
        if (!enabled) return source
        val w = source.width
        val h = source.height
        if (w <= 0 || h <= 0) return source

        val sec = (nowMs / 1000L).toInt()
        val day = (nowMs / 86_400_000L).toInt()
        if (sec != lastSecond) {
            lastSecond = sec
            cachedClock = runCatching { clock.format(Date(nowMs)) }.getOrDefault("")
        }
        if (day != lastDay) {
            lastDay = day
            cachedDate = runCatching { dateLine.format(Date(nowMs)) }.getOrDefault("")
        }
        val date = cachedDate
        val time = cachedClock
        if (time.isEmpty()) return source

        return try {
            val textSize = (h * 0.045f).coerceIn(12f, 96f)
            textPaint.textSize = textSize
            val lines = if (date.isEmpty()) listOf(time) else listOf(time, date)
            // +1 for the inter-line gap. drawText's y is the baseline, so the
            // block is measured from the baseline of the first line upward.
            val lineHeight = textSize * 1.25f
            val blockHeight = lineHeight * lines.size
            val pad = textSize * 0.35f
            val barH = blockHeight + pad * 2

            val canvas = Canvas(source)
            canvas.drawRect(0f, 0f, w.toFloat(), barH, barPaint)
            var baseline = pad + textSize
            for (line in lines) {
                canvas.drawText(line, pad, baseline, textPaint)
                baseline += lineHeight
            }
            if (label.isNotBlank()) {
                textPaint.textSize = textSize * 0.7f
                canvas.drawText(label, pad, barH + textSize, textPaint)
            }
            stamped++
            source
        } catch (_: Throwable) {
            // A recycled bitmap or a hardware-backed one would throw here. The
            // overlay is a nicety; the frame is the product, so a frame that
            // cannot be stamped is published undecorated rather than dropped.
            source
        }
    }

    /** Frames stamped since the process started, for /status.json. */
    fun stampedFrames(): Long = stamped

    /** Called when the camera rebinds, so a reset counter has a visible cause. */
    fun resetCounters() {
        stamped = 0
    }
}
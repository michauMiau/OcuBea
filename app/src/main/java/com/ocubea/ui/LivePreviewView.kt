package com.ocubea.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout
import com.ocubea.stream.FrameHub
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Live in-app preview fed straight from [FrameHub].
 *
 * Deliberately does NOT open a second camera stream — the camera belongs to
 * StreamService and CameraX only allows one active capture session. Instead this
 * view registers as an extra FrameHub viewer, so the on-screen preview and
 * remote viewers see identical frames at zero extra camera cost, with no HTTP
 * polling in the loop.
 */
class LivePreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val surface = SurfaceView(context)
    private val paint = Paint().apply { isFilterBitmap = true; isAntiAlias = true }
    private val running = AtomicBoolean(false)
    private var viewer: FrameHub.Viewer? = null
    private var thread: Thread? = null

    /** Set by the activity; polled until the surface is ready. */
    var frameHub: FrameHub? = null

    private val surfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) { start() }

        /**
         * Was an empty override, which is where a rotation lands.
         *
         * The Activity declares `configChanges="orientation|screenSize"`, so the
         * platform does not recreate it: it resizes the SurfaceView and calls
         * this. With nothing here, nothing told the drawing code that the surface
         * geometry changed, so the next `drawFrame` fitted the JPEG into the
         * previous canvas size. The preview then sat letterboxed in the wrong
         * place until some other event happened to trigger another layout, which
         * is the "landscape looks broken" symptom: the picture is drawn to stale
         * dimensions and the controls float over the wrong part of it.
         *
         * `drawFrame` re-reads `canvas.width`/`canvas.height` on every frame, so
         * it needs no cached size to invalidate -- it needs one frame to be
         * scheduled after the resize. Without a viewer attached, nothing is
         * polling, so the surface would stay showing the last frame at the old
         * scale until the next JPEG arrived. Redrawing here makes the new
         * geometry take effect immediately.
         *
         * `holder.surface?.isValid` guards the case where the surface was
         * destroyed between the callback and this code; locking an invalid
         * canvas throws.
         */
        override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
            if (w <= 0 || h <= 0) return
            // Invalidate so the compositor redraws the surface at the new size
            // instead of stretching the last frame.
            runCatching { surface.holder.surface?.let { if (it.isValid) invalidate() } }
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) { stop() }
    }

    init {
        surface.holder.addCallback(surfaceCallback)
        addView(
            surface,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )
        // Keep the preview visible over the live SurfaceView content.
        setWillNotDraw(false)
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread = Thread({ renderLoop() }, "ocubea-preview").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running.set(false)
        frameHub?.let { hub -> viewer?.let { hub.removeViewer(it) } }
        viewer = null
        thread?.interrupt()
        thread = null
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    private fun renderLoop() {
        val hub = frameHub
        if (hub == null) {
            running.set(false)
            return
        }
        val v = hub.addViewer() ?: run {
            // The hub is at its cap because remote viewers are streaming. The
            // on-screen preview is the last thing to give up, and dropping it
            // would mean the user cannot see whether the camera is even
            // running, so the loop simply ends instead of failing the view.
            stop()
            return
        }
        viewer = v
        try {
            while (running.get() && v.active) {
                val frame = hub.pollFrame(v, 500) ?: continue
                if (frame.isEmpty()) continue
                drawFrame(frame)
            }
        } catch (_: InterruptedException) {
            // stopped by stop()
        } finally {
            hub.removeViewer(v)
        }
    }

    private fun drawFrame(jpeg: ByteArray) {
        val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return
        try {
            val canvas: Canvas = surface.holder.lockCanvas() ?: return
            try {
                canvas.drawColor(Color.BLACK)
                // Fit-center, preserving aspect ratio.
                val vw = canvas.width
                val vh = canvas.height
                if (vw <= 0 || vh <= 0) return
                val scale = minOf(vw.toFloat() / bmp.width, vh.toFloat() / bmp.height)
                val w = (bmp.width * scale).toInt()
                val h = (bmp.height * scale).toInt()
                val left = (vw - w) / 2
                val top = (vh - h) / 2
                val dst = Rect(left, top, left + w, top + h)
                canvas.drawBitmap(bmp, null, dst, paint)
            } finally {
                runCatching { surface.holder.unlockCanvasAndPost(canvas) }
            }
        } finally {
            bmp.recycle()
        }
    }
}

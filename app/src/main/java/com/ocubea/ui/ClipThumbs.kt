package com.ocubea.ui

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.Log
import android.util.LruCache
import com.ocubea.security.ClipStorage
import java.io.File
import java.util.concurrent.Executors

/**
 * Poster frames for the clip grid.
 *
 * The clips are read over HTTP through the app's own server, so a thumbnail
 * means handing a real URL to MediaMetadataRetriever. That opens a decoder per
 * call, and a grid of two columns shows four items at once, so the requests are
 * funnelled through a small pool and the results are cached: scrolling back to a
 * clip already seen must not decode it again.
 *
 * An LruCache sized from the heap rather than a fixed count, because a 1080p
 * poster is about 8 MB as an ARGB_8888 bitmap and a fixed "32 entries" would
 * be 256 MB on a phone.
 *
 * getFrameAtTime is asked for a frame near the middle rather than at 0: frame 0
 * is the IDR, and on a clip whose first keyframe lands on a dark or transitional
 * frame the poster reads as black.
 */
object ClipThumbs {

    private val pool = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "clip-thumbs").apply { priority = Thread.MIN_PRIORITY }
    }

    private val cache = object : LruCache<String, Bitmap>(
        // A third of this heap as decoded bitmaps, in KB.
        ((Runtime.getRuntime().maxMemory() / 1024) / 3).toInt().coerceAtLeast(4 * 1024)
    ) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }

    /** Cached poster, or null if it has not been decoded yet. */
    fun cached(ctx: Context, item: ClipItem): Bitmap? = cache.get(key(ctx, item))

    /**
     * Decodes a poster off the main thread and hands it to [onReady] on the
     * main thread. [onReady] is called with null when the clip cannot be
     * decoded, so the caller can fall back to the play glyph.
     *
     * The bitmap passed to the callback is the cached instance, so a caller must
     * not recycle it. Recycling a bitmap another row is drawing throws.
     */
    fun load(ctx: Context, item: ClipItem, onReady: (Bitmap?) -> Unit) {
        val k = key(ctx, item)
        cache.get(k)?.let { onReady(it); return }
        val app = ctx.applicationContext
        pool.execute {
            val bmp = runCatching { decode(app, item) }.getOrNull()
            if (bmp != null) cache.put(k, bmp)
            android.os.Handler(app.mainLooper).post { onReady(bmp) }
        }
    }

    private fun key(ctx: Context, item: ClipItem) =
        "${ClipAdapter.baseUrl(ctx)}/${item.name}/${item.sizeBytes}"

    private fun decode(ctx: Context, item: ClipItem): Bitmap? {
        // Direct file first, HTTP only as a fallback.
        //
        // MediaMetadataRetriever's URI overload goes through Stagefright's
        // HTTP data source, which refuses to open a loopback address: the
        // request never leaves the device, yet on Android 13 it comes back as
        // "could not access http://127.0.0.1:8080/...". The server itself is
        // reachable — curl on the same phone returns the clip list — so this is
        // the media stack's policy, not a networking problem. Reading the file
        // the app already wrote sidesteps the stack completely and skips a
        // round trip through our own socket.
        //
        // The HTTP path stays for the case where the clip directory is not
        // readable in this process, which would otherwise mean no thumbnails at
        // all rather than a slower load.
        val direct = runCatching {
            val f = File(ClipStorage.root(ctx), item.name)
            if (f.isFile && f.canRead()) decodeFile(f) else null
        }.getOrNull()
        if (direct != null) return direct
        return decodeHttp(ctx, item)
    }

    /**
     * One-shot report of what this process can actually see.
     *
     * A `Permission denied` from `run-as` on the shell side proves nothing:
     * run-as lands in a different SELinux domain from the app itself. The
     * question that matters is whether THIS process can open the file, so it
     * reads a few bytes and logs the outcome.
     */
    fun probe(ctx: Context) {
        val dir = ClipStorage.root(ctx)
        Log.i(TAG, "probe dir=$dir exists=${dir.isDirectory} canRead=${dir.canRead()}")
        val f = dir.listFiles()?.firstOrNull { it.isFile }
        if (f == null) {
            Log.w(TAG, "probe no clips found in $dir")
            return
        }
        Log.i(TAG, "probe file=${f.name} len=${f.length()} canRead=${f.canRead()}")
        val head = runCatching {
            f.inputStream().use { s -> ByteArray(16).also { s.read(it) } }
        }
        val hex = head.getOrNull()?.joinToString("") {
            it.toUByte().toString(16).padStart(2, '0')
        } ?: "brak"
        Log.i(TAG, "probe head=$hex sdk=${android.os.Build.VERSION.SDK_INT} " +
            "ext=${ClipStorage.isExternal(ctx)}")
    }

    private fun decodeFile(f: File): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(f.absolutePath)
            frameFrom(r, f.name)
        } catch (e: Exception) {
            Log.w(TAG, "decode ${f.name} failed: ${e.message}")
            null
        } finally {
            runCatching { r.release() }
        }
    }

    private fun decodeHttp(ctx: Context, item: ClipItem): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(ctx, ClipAdapter.uriFor(ctx, item))
            frameFrom(r, item.name)
        } catch (e: Exception) {
            Log.w(TAG, "decode ${item.name} over http failed: ${e.message}")
            null
        } finally {
            runCatching { r.release() }
        }
    }

    private fun frameFrom(r: MediaMetadataRetriever, label: String): Bitmap? {
        val durMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
        Log.i(TAG, "decode $label: duration=$durMs")
        // MICRO seconds. A quarter of the way in avoids the transition frame
        // right after the opening IDR.
        val mid = durMs?.toLongOrNull()?.div(4) ?: 0L
        val bmp = r.getFrameAtTime(
            mid * 1000,
            MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
        ) ?: r.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        Log.i(TAG, "decode $label: frame=${bmp?.width}x${bmp?.height}")
        return bmp
    }

    private const val TAG = "ClipThumbs"
}

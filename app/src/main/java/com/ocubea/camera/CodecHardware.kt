package com.ocubea.camera

import android.media.MediaCodecInfo
import android.os.Build

/**
 * One place that answers "is this codec hardware-accelerated?".
 *
 * `MediaCodecInfo.isHardwareAccelerated()` was added in **API 29**. On an older
 * device the call throws `NoSuchMethodError`, which is an `Error`, not an
 * `Exception` — so the `catch (_: Exception)` that used to guard it in two
 * places did not catch it, and the error escaped a `try` block and killed the
 * process on the way to starting the camera.
 *
 * Reached from the service start path (`StreamService.startEverything` ->
 * `startClipRecording` -> `H264Encoder.start` -> `pickHardwareAvcEncoder`), with
 * no try/catch of its own, so this was a hard crash rather than a fallback.
 *
 * The `runCatching` form is deliberate: it catches `Throwable`, which is what an
 * absent method actually throws. A `Version.SDK_INT` guard would also work, but
 * this way the three call sites cannot each get the catch wrong again.
 */
fun isHardwareAvcCapableOf(info: MediaCodecInfo): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        try {
            info.isHardwareAccelerated
        } catch (_: Throwable) {
            false
        }
    } else {
        // Pre-29 there is no way to ask. Codecs not in the software list are
        // hardware on every device this app has run on, and guessing here only
        // picks WHICH encoder to try - a wrong guess fails the MediaFormat
        // configure step and falls back to MJPEG, which is the same as before.
        !info.isSoftwareOnlyAvc()
    }

/** Pre-29 stand-in for `isSoftwareOnly`, which is equally API 29. */
private fun MediaCodecInfo.isSoftwareOnlyAvc(): Boolean = name.lowercase().let {
    it.startsWith("omx.google.") ||
        it.startsWith("c2.android.") ||
        it == "omx.ffmpeg.decoder" ||
        it.startsWith("c2.android.avc")
}

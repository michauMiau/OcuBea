package com.ocubea.security

import android.content.Context
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Runs [ClipRetention] on a timer so the limits actually apply.
 *
 * An endpoint the user has to remember to press is not a retention policy. The
 * sweep runs shortly after a clip closes — that is the moment the directory has
 * actually grown — and then on a slow interval as a backstop for the case
 * where the app is left running with no new clips.
 *
 * Everything happens on a single background thread: pruning deletes files, and
 * doing that on the camera thread would stall frame delivery.
 */
class ClipRetentionScheduler(
    private val context: Context,
    private val limits: () -> Limits,
) {
    private companion object {
        const val TAG = "ClipRetention"
        /** Long enough that a burst of clips does not cause a sweep per clip. */
        const val AFTER_CLOSE_DELAY_S = 20L
        const val PERIOD_MIN = 15L
    }

    data class Limits(
        val maxBytes: Long,
        val maxAgeMs: Long,
        val maxFiles: Int,
    )

    private val executor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "ocubea-clipretention").apply { isDaemon = true }
        }

    @Volatile private var running = false
    @Volatile private var protectedName: String? = null

    /** The clip currently being written, excluded from every sweep. */
    fun protect(name: String?) {
        protectedName = name
    }

    fun start() {
        if (running) return
        running = true
        // Catch-up sweep first: after a crash or a long stop there may be
        // hundreds of clips and no new close event to hang a sweep on.
        executor.execute { sweep("startup") }
        executor.scheduleWithFixedDelay(
            { sweep("period") },
            PERIOD_MIN, PERIOD_MIN, TimeUnit.MINUTES,
        )
    }

    fun stop() {
        running = false
        executor.shutdownNow()
    }

    /** Called right after a clip closes; the delay lets a burst settle. */
    fun scheduleAfterClose() {
        if (!running) return
        try {
            executor.schedule({ sweep("after-close") }, AFTER_CLOSE_DELAY_S, TimeUnit.SECONDS)
        } catch (_: Exception) {
            // RejectedExecutionException after stop() — nothing to do.
        }
    }

    private fun sweep(reason: String) {
        if (!running) return
        val cfg = runCatching { limits() }.getOrNull() ?: return
        val open = protectedName
        val result = runCatching {
            ClipRetention.prune(
                context,
                maxBytes = cfg.maxBytes,
                maxAgeMs = cfg.maxAgeMs,
                maxFiles = cfg.maxFiles,
                protectedNames = if (open != null) setOf(open) else emptySet(),
            )
        }.getOrNull() ?: return

        if (result.removed > 0) {
            Log.i(TAG, "prune($reason): removed=${result.removed} " +
                "freed=${result.freedBytes}B byAge=${result.byAge} " +
                "bySize=${result.bySize} byCount=${result.byCount}")
        }
    }
}

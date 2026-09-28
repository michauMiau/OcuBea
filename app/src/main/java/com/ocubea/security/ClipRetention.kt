package com.ocubea.security

import android.content.Context
import java.io.File

/**
 * The eviction sweep: age, total size and file count.
 *
 * The three limits are independent and any of them can fire — they are OR-ed,
 * not AND-ed. A clip is deleted as soon as ONE limit says it should go, oldest
 * first. AND-ing them would mean a directory that is 90% full and full of
 * hour-old clips keeping everything, which is the opposite of what a security
 * camera is for.
 *
 * [isOpen] reports whether a clip is currently being written; that file is
 * never a candidate. Deleting it would leave the writer appending to an
 * unlinked inode: the bytes go nowhere, the listing shows nothing, and the
 * space does not come back until the process dies.
 */
object ClipRetention {

    data class Result(
        val removed: Int,
        val freedBytes: Long,
        val byAge: Int,
        val bySize: Int,
        val byCount: Int,
    )

    fun prune(
        context: Context,
        maxBytes: Long,
        maxAgeMs: Long,
        maxFiles: Int,
        protectedNames: Set<String> = emptySet(),
        nowMs: Long = System.currentTimeMillis(),
    ): Result = plan(
        candidates = ClipStorage.list(context)
            .filter { it.name !in protectedNames },
        maxBytes = maxBytes,
        maxAgeMs = maxAgeMs,
        maxFiles = maxFiles,
        nowMs = nowMs,
    )

    /**
     * The eviction decision, free of Android.
     *
     * [candidates] must already exclude the clip being written. Oldest first.
     * Nothing is inspected for age, size or count beyond what the caller
     * passed in, so the rules below can be tested against plain files.
     */
    fun plan(
        candidates: List<File>,
        maxBytes: Long,
        maxAgeMs: Long,
        maxFiles: Int,
        nowMs: Long,
    ): Result {
        val files = candidates.sortedBy { it.lastModified() }

        var removed = 0
        var freed = 0L
        var byAge = 0
        var bySize = 0
        var byCount = 0

        val gone = HashSet<String>()
        fun drop(f: File, bucket: Int) {
            if (gone.contains(f.name)) return
            val len = f.length()
            if (f.delete()) {
                removed++
                freed += len
                gone.add(f.name)
                when (bucket) { 0 -> byAge++; 1 -> bySize++; else -> byCount++ }
            }
        }

        // 1. Age. Independent of anything else — a 30-day-old clip goes even if
        //    the directory is nearly empty and the user asked for a week.
        for (f in files) {
            if (nowMs - f.lastModified() > maxAgeMs) drop(f, 0)
        }

        // 2. Total size. Walk oldest-first until under the limit.
        var total = files.sumOf { if (gone.contains(it.name)) 0L else it.length() }
        for (f in files) {
            if (total <= maxBytes) break
            if (gone.contains(f.name)) continue
            val len = f.length()
            if (f.delete()) {
                removed++; freed += len; total -= len
                gone.add(f.name); bySize++
            }
        }

        // 3. Count. A directory of 5000 two-second clips is worse than useless
        //    even when it fits in the byte budget.
        var remaining = files.count { !gone.contains(it.name) }
        for (f in files) {
            if (remaining <= maxFiles) break
            if (gone.contains(f.name)) continue
            // The size has to be read BEFORE the delete. After an unlink the
            // File handle still points at the inode, so length() keeps returning
            // the old value on some kernels and 0 on others; relying on either
            // makes the freed-bytes total drift away from the truth.
            val len = f.length()
            if (f.delete()) {
                removed++; freed += len; gone.add(f.name); remaining--; byCount++
            }
        }

        return Result(removed, freed, byAge, bySize, byCount)
    }

    /**
     * Removes clips left behind by a crash.
     *
     * A clip that never got its final flush has no mfra, so the muxer cannot
     * know how long it is and a player shows a truncated file. Anything older
     * than an hour that is still being written to by nobody is one of these.
     *
     * NOT WIRED UP. Nothing calls this, and it must not be called as written:
     * a clip of any length sits untouched for an hour as long as nothing else
     * writes to it, and by then the age limit may not have caught it. Wiring
     * this in without a marker for "this file has a closed muxer" would delete
     * perfectly good long recordings. It needs that marker first — the
     * finished-file check belongs in ClipWriter, not in a sweep that guesses
     * from mtime.
     */
    fun sweepUnfinished(context: Context, olderThanMs: Long = 3600_000L): Int {
        val cutoff = System.currentTimeMillis() - olderThanMs
        var removed = 0
        for (f in ClipStorage.list(context)) {
            if (f.length() < 1024) continue        // too small to be a real clip
            if (f.lastModified() < cutoff) {
                // lastModified advances on every write, so an old mtime means
                // nobody has touched it for over an hour.
                if (f.delete()) removed++
            }
        }
        return removed
    }
}

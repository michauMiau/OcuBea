package com.ocubea.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The eviction rules, exercised against real files in a temp directory.
 *
 * The three limits are OR-ed on purpose, so each test drives one limit with
 * the other two wide open. A test that let them interact would pass even if
 * one of them were dead code.
 */
class ClipRetentionTest {

    private val dir: File = File(System.getProperty("java.io.tmpdir"), "clipret-${System.nanoTime()}")

    private val now = 1_700_000_000_000L

    /** Creates a clip of [bytes] with an mtime [ageMs] in the past. */
    private fun clip(name: String, bytes: Int, ageMs: Long): File {
        dir.mkdirs()
        val f = File(dir, name)
        f.writeBytes(ByteArray(bytes) { (it % 251).toByte() })
        f.setLastModified(now - ageMs)
        return f
    }

    private fun names() = dir.listFiles()?.map { it.name }?.sorted() ?: emptyList()

    private fun prune(
        maxBytes: Long = Long.MAX_VALUE,
        maxAgeMs: Long = Long.MAX_VALUE,
        maxFiles: Int = Int.MAX_VALUE,
    ) = ClipRetention.plan(
        candidates = dir.listFiles()?.toList() ?: emptyList(),
        maxBytes = maxBytes,
        maxAgeMs = maxAgeMs,
        maxFiles = maxFiles,
        nowMs = now,
    )

    @Test
    fun `nothing goes when every limit is slack`() {
        clip("klip_a.mp4", 100, 1_000)
        clip("klip_b.mp4", 100, 2_000)
        val r = prune()
        assertEquals(0, r.removed)
        assertEquals(0L, r.freedBytes)
        assertEquals(2, names().size)
    }

    @Test
    fun `the age limit alone evicts and keeps the newer clips`() {
        clip("klip_old1.mp4", 100, 40 * 86_400_000L)
        clip("klip_old2.mp4", 100, 30 * 86_400_000L)
        clip("klip_new.mp4", 100, 60_000L)
        val r = prune(maxAgeMs = 7 * 86_400_000L)
        assertEquals(2, r.byAge)
        assertEquals(2, r.removed)
        assertEquals(listOf("klip_new.mp4"), names())
    }

    @Test
    fun `the count limit alone evicts oldest first`() {
        clip("klip_1.mp4", 10, 30_000)
        clip("klip_2.mp4", 10, 20_000)
        clip("klip_3.mp4", 10, 10_000)
        clip("klip_4.mp4", 10, 1_000)
        val r = prune(maxFiles = 2)
        assertEquals(2, r.byCount)
        assertEquals(listOf("klip_3.mp4", "klip_4.mp4"), names())
    }

    @Test
    fun `the size limit walks oldest first until it fits`() {
        clip("klip_1.mp4", 1_000, 30_000)
        clip("klip_2.mp4", 1_000, 20_000)
        clip("klip_3.mp4", 1_000, 10_000)
        // Room for two, so the oldest has to go.
        val r = prune(maxBytes = 2_500)
        assertEquals(1, r.bySize)
        assertEquals(1_000L, r.freedBytes)
        assertEquals(listOf("klip_2.mp4", "klip_3.mp4"), names())
    }

    @Test
    fun `freed bytes are counted for every evicted clip`() {
        // The bug this pins: the count branch read length() after delete(),
        // so its share of the total silently went missing.
        clip("klip_1.mp4", 700, 40_000)
        clip("klip_2.mp4", 700, 30_000)
        clip("klip_3.mp4", 700, 20_000)
        // maxFiles = 1 out of three files means two evictions, not one.
        val r = prune(maxFiles = 1)
        assertEquals(2, r.byCount)
        assertEquals(2, r.removed)
        assertEquals(1_400L, r.freedBytes)
        assertEquals(listOf("klip_3.mp4"), names())
    }

    @Test
    fun `limits are OR-ed, not AND-ed`() {
        // A directory that is 95% full but full of hour-old clips must still
        // lose its oldest files. AND-ing the limits would keep everything,
        // which is the opposite of what a security camera is for.
        clip("klip_1.mp4", 1_000_000, 5_000)
        clip("klip_2.mp4", 1_000_000, 4_000)
        val r = prune(maxBytes = 1_500_000, maxAgeMs = Long.MAX_VALUE, maxFiles = Int.MAX_VALUE)
        assertEquals(1, r.bySize)
        assertEquals(1, r.removed)
    }

    @Test
    fun `a clip removed by age is not counted twice`() {
        // Age fires first, so the size branch must not see the same file again
        // and double the removed count or the freed total. maxBytes = 1 forces
        // the size branch to run, which is the whole point: it has to find the
        // age-evicted file already gone and leave the survivor alone.
        clip("klip_old.mp4", 5_000, 90 * 86_400_000L)
        clip("klip_new.mp4", 5_000, 1_000)
        val r = prune(maxBytes = 1, maxAgeMs = 7 * 86_400_000L)
        assertEquals(2, r.removed)
        assertEquals(10_000L, r.freedBytes)
        assertEquals(1, r.byAge)
        assertEquals(1, r.bySize)
    }

    @Test
    fun `a file below the byte budget is not touched even when over the count`() {
        // maxFiles = 0 must empty the directory, but maxBytes huge must not
        // stop it — the two limits are independent.
        clip("klip_1.mp4", 1, 1_000)
        clip("klip_2.mp4", 1, 900)
        val r = prune(maxFiles = 0)
        assertEquals(2, r.removed)
        assertTrue(names().isEmpty())
    }

    @Test
    fun `the plan survives an empty directory`() {
        dir.mkdirs()
        val r = prune(maxAgeMs = 0, maxBytes = 0, maxFiles = 0)
        assertEquals(0, r.removed)
    }
}

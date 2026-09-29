package com.ocubea.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/**
 * The previous-frame swap has to be indivisible.
 *
 * process() is called from the encode pool, 2-4 frames deep, so "read the
 * predecessor, compare, store the new one" let two threads read the same
 * predecessor and both store their own frame. A moving frame was then compared
 * against itself and reported nothing. These tests pin the atomicity, using a
 * plain IntArray so they run on the JVM with no Bitmap.
 */
class FrameDifferTest {

    private fun grid(value: Int, size: Int = 768) = IntArray(size) { value }

    @Test
    fun `the first frame has no predecessor`() {
        assertNull(FrameDiffer().swap(grid(10)))
    }

    @Test
    fun `each frame gets a different predecessor`() {
        val d = FrameDiffer()
        val first = grid(10)
        val second = grid(20)
        val third = grid(30)
        assertNull(d.swap(first))
        assertEquals(first, d.swap(second))
        assertEquals(second, d.swap(third))
    }

    @Test
    fun `an unchanged frame reports no motion`() {
        val d = FrameDiffer()
        d.swap(grid(100))
        assertTrue(!d.differs(grid(100), grid(100), threshold = 20))
    }

    @Test
    fun `a moved frame reports motion`() {
        val d = FrameDiffer()
        val base = IntArray(768) { 100 }
        val moved = base.copyOf().also { for (i in it.indices step 10) it[i] = 220 }
        d.swap(base)
        assertTrue(d.differs(moved, base, threshold = 20))
    }

    @Test
    fun `reset drops the baseline`() {
        val d = FrameDiffer()
        d.swap(grid(10))
        d.reset()
        assertNull(d.swap(grid(20)))
    }

    /**
     * The regression test for the race: N threads each hand in one frame and
     * collect the predecessor they displaced. Exactly one thread may see null
     * (the very first frame) and the rest must see N-1 distinct arrays. If the
     * swap were a plain read-then-write, two threads would come away with the
     * same predecessor - and a frame would never be handed to anyone, which is
     * exactly the frame that gets compared against itself and misses motion.
     */
    @Test
    fun `concurrent swaps never hand the same predecessor to two threads`() {
        repeat(25) { round ->
            val d = FrameDiffer()
            val threads = 4
            val perThread = 250
            val start = CountDownLatch(1)
            val seen = java.util.Collections.synchronizedList(mutableListOf<IntArray>())
            val firsts = AtomicInteger(0)
            val pool = java.util.concurrent.Executors.newFixedThreadPool(threads)
            val done = CountDownLatch(threads)

            repeat(threads) { t ->
                pool.execute {
                    start.await()
                    repeat(perThread) { i ->
                        // A distinct array per call: sharing one would hide a
                        // bug where the same object is compared against itself.
                        val prev = d.swap(IntArray(768) { (t * perThread + i) % 256 })
                        if (prev == null) firsts.incrementAndGet() else seen.add(prev)
                    }
                    done.countDown()
                }
            }
            start.countDown()
            done.await()
            pool.shutdown()

            val total = threads * perThread
            assertEquals("round $round: exactly one thread may see the first frame",
                1, firsts.get())
            assertEquals("round $round: every frame displaced exactly one predecessor",
                total - 1, seen.size)
            // Identity, not value: two different frames can legitimately carry
            // the same first pixel. What must never happen is the same array
            // instance being handed to two threads, which is what let a moving
            // frame be compared against itself.
            val distinct = seen.toSet().size
            assertEquals("round $round: a predecessor was handed out twice", seen.size, distinct)
        }
    }
}

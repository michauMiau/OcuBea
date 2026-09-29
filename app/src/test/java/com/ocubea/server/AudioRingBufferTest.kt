package com.ocubea.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AudioRingBufferTest {

    @Test
    fun bytesComeBackInTheOrderTheyWentIn() {
        val r = AudioRingBuffer(1024)
        r.offer(byteArrayOf(1, 2, 3, 4), 0, 4)
        val out = ByteArray(4)
        r.asInputStream().read(out, 0, 4)
        assertEquals(listOf<Byte>(1, 2, 3, 4), out.toList())
    }

    @Test
    fun theBufferWrapsAndStaysInOrder() {
        // A chunk that does not fit in one contiguous free span, so the write
        // must wrap and the second arraycopy has to carry the tail. Plain
        // 2-byte chunks never do this, which is why a mutation that copied
        // only `first` bytes of the tail survived an earlier version of this
        // test: with aligned tiny chunks first and len-first came out equal,
        // so copying the wrong span was invisible.
        //
        // Wrap needs the free space split, so count>0 AND head>0 AND
        // head+count<cap. capacity 12, fill 10, read 4 (head=4, count=6), then
        // offer 6: w=10, first=min(6, 12-10)=2, so the tail copy is 4 bytes
        // and the mutation would corrupt 2 of them.
        val r = AudioRingBuffer(12)
        r.offer(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10), 0, 10)
        val s = r.asInputStream()
        s.read(ByteArray(4), 0, 4)                     // head=4, count=6
        val big = byteArrayOf(20, 21, 22, 23, 24, 25)
        assertTrue("the wrapping offer must be accepted", r.offer(big, 0, 6))
        assertEquals(12, r.available())
        val out = ByteArray(12)
        var filled = 0
        while (filled < 12) {
            val n = s.read(out, filled, 12 - filled)
            if (n < 0) break
            filled += n
        }
        val expected = listOf<Byte>(5, 6, 7, 8, 9, 10, 20, 21, 22, 23, 24, 25)
        assertEquals("wrap copied the wrong span", expected, out.toList())
    }

    @Test
    fun aFullBufferDropsTheClientInsteadOfBlocking() {
        val r = AudioRingBuffer(16)
        assertTrue(r.offer(ByteArray(16), 0, 16))
        val start = System.nanoTime()
        assertFalse("offer must not wait for room", r.offer(ByteArray(8), 0, 8))
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue("offer blocked for ${ms}ms", ms < 50)
    }

    @Test
    fun aChunkBiggerThanTheWholeBufferIsRejected() {
        val r = AudioRingBuffer(16)
        assertFalse(r.offer(ByteArray(17), 0, 17))
    }

    @Test
    fun readingMakesRoomAgain() {
        val r = AudioRingBuffer(8)
        r.offer(ByteArray(8), 0, 8)
        assertFalse(r.offer(ByteArray(1), 0, 1))
        val s = r.asInputStream()
        s.read(ByteArray(8), 0, 8)
        assertTrue("space freed by a read must be reusable", r.offer(ByteArray(1), 0, 1))
    }

    @Test
    fun availableTracksWhatIsBuffered() {
        val r = AudioRingBuffer(64)
        assertEquals(0, r.available())
        r.offer(ByteArray(10), 0, 10)
        assertEquals(10, r.available())
        r.asInputStream().read(ByteArray(4), 0, 4)
        assertEquals(6, r.available())
    }

    @Test
    fun closeWakesABlockedReaderWithEndOfStream() {
        val r = AudioRingBuffer(64)
        val stream = r.asInputStream()
        val got = arrayOfNulls<Int>(1)
        val started = CountDownLatch(1)
        val t = Thread {
            started.countDown()
            got[0] = try {
                stream.read(ByteArray(64), 0, 64)
            } catch (_: IOException) {
                -99
            }
        }
        t.start()
        started.await()
        Thread.sleep(120) // let it park on the condition
        r.close()
        t.join(3000)
        assertFalse("reader stayed blocked after close", t.isAlive)
        assertEquals("end of stream expected", -1, got[0])
    }

    @Test
    fun aWriterIsNeverBlockedByASlowReader() {
        val r = AudioRingBuffer(1024)
        val done = CountDownLatch(1)
        var offered = 0L
        val t = Thread {
            // A client that never reads: the writer must run away from it.
            val chunk = ByteArray(256)
            repeat(200) {
                if (r.offer(chunk, 0, chunk.size)) offered++ else return@repeat
            }
            done.countDown()
        }
        t.start()
        val finished = done.await(3, TimeUnit.SECONDS)
        assertTrue("writer blocked behind a client that never reads", finished)
        t.join(1000)
        assertTrue("expected drops, offered=$offered", offered in 1..199)
    }

    @Test
    fun aSteadyReaderNeverGetsDropped() {
        val r = AudioRingBuffer(8192)
        val s = r.asInputStream()
        var drops = 0
        // The shape of a real client: read what is there, keep going.
        for (i in 0 until 200) {
            if (!r.offer(ByteArray(256), 0, 256)) drops++
            val out = ByteArray(256)
            var filled = 0
            while (filled < 256) {
                val n = s.read(out, filled, 256 - filled)
                if (n < 0) break
                filled += n
            }
            if (filled != 256) break
        }
        assertEquals("a reader keeping up must never be dropped", 0, drops)
    }

    @Test
    fun readReturnsMinusOneAfterCloseEvenWithDataLeft() {
        val r = AudioRingBuffer(32)
        r.offer(ByteArray(8), 0, 8)
        val s = r.asInputStream()
        s.read(ByteArray(8), 0, 8)
        r.close()
        assertEquals(-1, s.read(ByteArray(8), 0, 8))
        assertTrue(r.isClosed())
    }

    @Test
    fun singleByteReadWorks() {
        val r = AudioRingBuffer(16)
        r.offer(byteArrayOf(0x41, 0x42), 0, 2)
        val s: InputStream = r.asInputStream()
        assertEquals(0x41, s.read())
        assertEquals(0x42, s.read())
        r.close()
        assertEquals(-1, s.read())
    }
}

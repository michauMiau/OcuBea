package com.ocubea.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * NanoHTTPD's default AsyncRunner creates one Thread per accepted socket with
 * no ceiling, and a `/video` connection holds that thread for the whole life of
 * the stream - hours, on a live camera. The 5s socket read timeout does not
 * help: it applies to reading the request, not to a long-lived response.
 *
 * On a 512MB phone a handful of tabs was enough to exhaust the budget and kill
 * the camera, so the pool is bounded and the overflow is refused rather than
 * queued without limit.
 *
 * `NanoHTTPD.ClientHandler` cannot be constructed here - it is an inner class
 * of NanoHTTPD holding a reference to its server instance - so this tests
 * [BoundedAsyncRunner.admit], which is the part that decides admission. The
 * handler itself only ever runs the caller's code once admitted.
 */
class BoundedAsyncRunnerTest {

    @Test
    fun `work beyond the pool is refused, not queued without limit`() {
        val runner = BoundedAsyncRunner(maxThreads = 2, maxQueued = 1)
        val release = CountDownLatch(1)
        val running = CountDownLatch(2)
        try {
            // Each task blocks until the test releases it, so the two threads
            // are genuinely occupied. Waiting for the latch is what makes this
            // deterministic: without it the tasks can be admitted but not yet
            // started, the queue is still empty, and the fourth task is let in.
            repeat(2) {
                assertTrue("blocking task $it should be admitted", runner.admit {
                    running.countDown()
                    try {
                        release.await(5, TimeUnit.SECONDS)
                    } catch (_: InterruptedException) {
                    }
                })
            }
            assertTrue("both threads should be busy", running.await(3, TimeUnit.SECONDS))

            // One slot left in the queue.
            assertTrue("the queued task should be admitted", runner.admit { })

            assertTrue(
                "the next task must be refused once 2 threads and 1 queue slot are taken",
                !runner.admit { },
            )
            assertEquals("only the refused task may be counted", 1, runner.refusedConnections())

            release.countDown()
        } finally {
            runner.closeAll()
        }
    }

    @Test
    fun `the active count rises while work runs and returns to zero after`() {
        val runner = BoundedAsyncRunner(maxThreads = 2, maxQueued = 2)
        try {
            val inside = CountDownLatch(1)
            val release = CountDownLatch(1)
            runner.admit {
                inside.countDown()
                try {
                    release.await(3, TimeUnit.SECONDS)
                } catch (_: InterruptedException) {
                }
            }
            assertTrue("the task should have started", inside.await(3, TimeUnit.SECONDS))
            assertEquals(1, runner.activeConnections())
            release.countDown()

            val deadline = System.currentTimeMillis() + 3000
            while (runner.activeConnections() > 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(20)
            }
            assertEquals("the count must not drift after a task returns", 0, runner.activeConnections())
        } finally {
            runner.closeAll()
        }
    }

    @Test
    fun `a refusal does not count as active work`() {
        // maxQueued 0 means "no waiting room", which the queue implementation
        // rejects, so the runner holds it at 1 and this test uses a full thread
        // plus a full queue to reach the same refusal.
        val runner = BoundedAsyncRunner(maxThreads = 1, maxQueued = 1)
        val release = CountDownLatch(1)
        val running = CountDownLatch(1)
        try {
            assertTrue(runner.admit {
                running.countDown()
                try {
                    release.await(5, TimeUnit.SECONDS)
                } catch (_: InterruptedException) {
                }
            })
            assertTrue("the single thread should be busy", running.await(3, TimeUnit.SECONDS))
            assertTrue("the queued task should be admitted", runner.admit { })
            assertTrue("the next task must be refused", !runner.admit { })

            assertEquals(
                "a refused task never started, so it must not be counted active",
                1, runner.activeConnections(),
            )
            release.countDown()
        } finally {
            runner.closeAll()
        }
    }

    @Test
    fun `the default pool is small enough for a phone`() {
        // Sized for a phone, not a server. A regression here would mean the
        // camera starts spending memory on threads instead of on picture.
        assertTrue(
            "default max threads is ${BoundedAsyncRunner.DEFAULT_MAX_THREADS}, " +
                "which is a server-sized default for a 512MB device",
            BoundedAsyncRunner.DEFAULT_MAX_THREADS in 2..8,
        )
    }
}

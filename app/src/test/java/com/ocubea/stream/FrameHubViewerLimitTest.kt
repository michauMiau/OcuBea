package com.ocubea.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The MJPEG viewer cap.
 *
 * Measured on the device: with 8 concurrent `/video` connections the HTTP
 * handler pool was full, NanoHTTPD closed every newly accepted socket, and
 * `/status.json` and `/shot.jpg` stopped answering in ~5ms while the existing
 * streams kept running. Nothing in logcat said why. Six viewers left the same
 * requests at 24ms.
 *
 * These tests pin the cheap half of the fix: a surplus viewer is refused
 * inside its own handler, so it never takes a thread.
 */
class FrameHubViewerLimitTest {

    @Test
    fun `a viewer is accepted below the cap`() {
        val hub = FrameHub()
        val v = hub.addViewer()
        assertNotNull("the first viewer must be accepted", v)
        assertEquals(1, hub.viewerCount())
    }

    @Test
    fun `a viewer is refused at the cap instead of over-admitting`() {
        val hub = FrameHub()
        val accepted = (0 until FrameHub.MAX_VIEWERS).mapNotNull { hub.addViewer() }
        assertEquals(FrameHub.MAX_VIEWERS, accepted.size)

        assertNull("the viewer past the cap must be refused", hub.addViewer())
        assertEquals("the refused viewer must not be counted", FrameHub.MAX_VIEWERS, hub.viewerCount())
    }

    @Test
    fun `removing a viewer frees a slot`() {
        val hub = FrameHub()
        val first = (0 until FrameHub.MAX_VIEWERS).mapNotNull { hub.addViewer() }
        assertNull(hub.addViewer())

        hub.removeViewer(first.first())
        assertEquals(FrameHub.MAX_VIEWERS - 1, hub.viewerCount())

        val replacement = hub.addViewer()
        assertNotNull("a freed slot must be usable again", replacement)
        assertEquals(FrameHub.MAX_VIEWERS, hub.viewerCount())
    }

    @Test
    fun `removing the same viewer twice does not free two slots`() {
        val hub = FrameHub()
        val v = hub.addViewer()!!
        hub.removeViewer(v)
        hub.removeViewer(v)
        assertEquals("a double remove must not underflow the count", 0, hub.viewerCount())
        assertNotNull(hub.addViewer())
    }

    /**
     * The counter is the cap's authority, so concurrent adds must not both see
     * "one slot left" and both take it.
     */
    @Test
    fun `concurrent adds never exceed the cap`() {
        val hub = FrameHub()
        val attempts = FrameHub.MAX_VIEWERS * 6
        val accepted = AtomicInteger(0)
        val pool = Executors.newFixedThreadPool(8)
        val gate = CountDownLatch(1)
        val done = CountDownLatch(attempts)

        repeat(attempts) {
            pool.execute {
                try {
                    gate.await()
                    if (hub.addViewer() != null) accepted.incrementAndGet()
                } catch (_: InterruptedException) {
                } finally {
                    done.countDown()
                }
            }
        }
        gate.countDown()
        assertTrue("threads did not finish", done.await(10, TimeUnit.SECONDS))
        pool.shutdownNow()

        assertEquals(
            "exactly MAX_VIEWERS may be admitted, no more and no fewer",
            FrameHub.MAX_VIEWERS,
            accepted.get(),
        )
        assertEquals(FrameHub.MAX_VIEWERS, hub.viewerCount())
    }

    private fun assertTrue(message: String, condition: Boolean) =
        org.junit.Assert.assertTrue(message, condition)
}

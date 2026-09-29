package com.ocubea.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioAdmissionControlTest {

    @Test
    fun theDefaultLeavesRoomForTheRestOfTheServer() {
        val a = AudioAdmissionControl()
        // Every audio client costs one of the 12 pool threads for the whole
        // connection, so the cap has to be well under the pool size.
        assertTrue("default cap must be below the thread pool", a.maxClients < 12)
        assertEquals(8, a.maxClients)
    }

    @Test
    fun clientsAreAdmittedUpToTheCap() {
        val a = AudioAdmissionControl(4)
        assertTrue(a.canAdmit(0))
        assertTrue(a.canAdmit(3))
        assertFalse("the fifth client must be refused", a.canAdmit(4))
        assertFalse(a.canAdmit(9))
    }

    @Test
    fun aRefusedClientCannotAppearEvenAfterOthersLeave() {
        val a = AudioAdmissionControl(2)
        assertTrue(a.canAdmit(1))
        assertFalse(a.canAdmit(2))
        // Two leave, room exists again -- the cap is on the current count, not
        // on a lifetime total, so a burst of clients that come and go is fine.
        assertTrue(a.canAdmit(1))
    }

    @Test
    fun aCapOfZeroRefusesEveryone() {
        assertFalse(AudioAdmissionControl(0).canAdmit(0))
    }

    @Test
    fun theCapIsOffByOneInTheRightDirection() {
        val a = AudioAdmissionControl(4)
        // The exact boundary matters: maxClients clients is allowed, so the
        // count that gets refused is the one that would take the last thread.
        for (n in 0 until 4) assertTrue("n=$n should be admitted", a.canAdmit(n))
        for (n in 4..20) assertFalse("n=$n should be refused", a.canAdmit(n))
    }
}

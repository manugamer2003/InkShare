package com.manuelperez.inkshare
import org.junit.Assert.*
import org.junit.Test
class MatchTimerTest {
    @Test fun defaultIs55Minutes() { assertEquals(55 * 60_000L, MatchTimer().remaining(0)) }
    @Test fun pauseAndResumePreserveRemaining() {
        val timer = MatchTimer(60_000).resume(1000).pause(11000)
        assertEquals(50_000L, timer.remaining(99000))
        assertEquals(40_000L, timer.resume(99000).remaining(109000))
    }
    @Test fun elapsedWhileAwayAndExpiryAreClamped() {
        val timer = MatchTimer(60_000).resume(1000)
        assertEquals(0L, timer.remaining(70000))
        assertFalse(timer.pause(70000).resume(80000).running)
        assertEquals(60_000L, timer.remaining(0))
    }
}

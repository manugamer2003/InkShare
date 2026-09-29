package com.manuelperez.inkshare

import org.junit.Assert.*
import org.junit.Test

class LiveScanGateTest {
    @Test fun requiresTwoConsecutiveReadings() {
        val gate = LiveScanGate()
        assertFalse(gate.accept(1))
        assertFalse(gate.accept(2))
        assertTrue(gate.accept(2))
    }
    @Test fun missingReadingBreaksStability() {
        val gate = LiveScanGate()
        gate.accept(1)
        assertFalse(gate.accept(null))
        assertFalse(gate.accept(1))
        assertTrue(gate.accept(1))
    }
    @Test fun blocksRepeatedDetectionsUntilExplicitNext() {
        val gate = LiveScanGate()
        gate.accept(1)
        assertTrue(gate.accept(1))
        assertFalse(gate.accept(1))
        assertFalse(gate.accept(2))
        gate.next()
        assertFalse(gate.accept(2))
        assertTrue(gate.accept(2))
    }
    @Test fun restoredPauseDoesNotRecognizeAgain() {
        val gate = LiveScanGate(true)
        assertFalse(gate.accept(1))
        assertFalse(gate.accept(1))
    }
}

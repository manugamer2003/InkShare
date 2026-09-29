package com.manuelperez.inkshare
import org.junit.Assert.*
import org.junit.Test
class BatchScanGateTest {
    @Test fun repeatedCardAndBlurDoNotDuplicate() {
        val gate = BatchScanGate()
        assertFalse(gate.accept(1)); assertTrue(gate.accept(1))
        repeat(10) { assertFalse(gate.accept(1)) }
        gate.accept(null); gate.accept(null)
        assertFalse(gate.accept(1)); assertFalse(gate.accept(1))
    }
    @Test fun differentStableCardContinuesAutomatically() {
        val gate = BatchScanGate()
        gate.accept(1); gate.accept(1)
        assertFalse(gate.accept(2)); assertTrue(gate.accept(2))
        assertFalse(gate.accept(1)); assertTrue(gate.accept(1))
    }
    @Test fun explicitNextAllowsAnotherIdenticalCopy() {
        val gate = BatchScanGate()
        gate.accept(1); gate.accept(1); gate.next()
        assertFalse(gate.accept(1)); assertTrue(gate.accept(1))
    }
}

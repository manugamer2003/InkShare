package com.manuelperez.inkshare

import org.junit.Assert.*
import org.junit.Test

class LoreMatchTest {
    @Test fun modesHaveRequestedTargetsAndPlayerLimits() {
        assertEquals(listOf(15,20,25), LoreMode.entries.map { it.target })
        assertTrue(LoreMode.PACK_RUSH.allows(2))
        assertFalse(LoreMode.PACK_RUSH.allows(3)); assertFalse(LoreMode.PACK_RUSH.allows(4))
        for (mode in listOf(LoreMode.STANDARD, LoreMode.COCONUT)) {
            for (players in 2..4) assertEquals(players, LoreMatch.start(mode, players).scores.size)
            assertFalse(mode.allows(1)); assertFalse(mode.allows(5))
        }
    }
    @Test fun eachModeDeclaresWinnerExactlyAtItsTarget() {
        for (mode in LoreMode.entries) {
            var match = LoreMatch.start(mode, 2)
            repeat(mode.target-1) { match = match.change(1, 1) }
            assertNull(match.winner)
            match = match.change(1, 1)
            assertEquals(1, match.winner)
            assertEquals(match, match.change(0, 1))
            assertNull(match.undo().winner)
            assertNull(match.change(1, -1).winner)
        }
    }
    @Test fun cannotGoBelowZeroAndUndoRestoresCorrectPlayer() {
        val initial = LoreMatch.start(LoreMode.STANDARD, 4)
        assertEquals(initial, initial.change(0, -1))
        val match = initial.change(2, 1).change(3, 1)
        assertEquals(listOf(0,0,1,0), match.undo().scores)
        assertEquals(initial, match.undo().undo())
        assertEquals(initial, match.restart())
    }
    @Test(expected = IllegalArgumentException::class) fun packRushRejectsMoreThanTwoPlayers() { LoreMatch.start(LoreMode.PACK_RUSH, 3) }
}

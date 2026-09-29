package com.manuelperez.inkshare

import org.junit.Assert.*
import org.junit.Test

class CardCatalogTest {
    private val ariel = LorcanaCard(1, "Ariel - On Human Legs", 1, "1", "1/204 • EN • 1", "", true, true)
    private val reprint = ariel.copy(id = 400, set = "4", identifier = "1/204 • EN • 4")
    private val other = ariel.copy(id = 2, name = "Mickey Mouse - Brave Little Tailor", number = 2)
    @Test fun unrelatedTextReturnsNoCandidates() {
        assertTrue(CardCatalog.match(listOf(ariel), "blurry unreadable photo").isEmpty())
    }
    @Test fun nameAndNumberRankTheCorrectCard() {
        assertEquals(ariel, CardCatalog.match(listOf(other, ariel), "ARIEL\nOn Human Legs\n1 / 204 • EN • 1").first())
    }
    @Test fun reprintsRemainCandidatesForManualConfirmation() {
        assertEquals(2, CardCatalog.match(listOf(ariel, reprint), "Ariel On Human Legs 1/204").size)
    }
    @Test fun normalizationHandlesAccentsAndPunctuation() {
        assertEquals("ariel on human legs", normalize("ÁRIEL — On Human Legs"))
    }
}

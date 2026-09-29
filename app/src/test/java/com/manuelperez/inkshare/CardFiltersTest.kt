package com.manuelperez.inkshare

import org.junit.Assert.*
import org.junit.Test

class CardFiltersTest {
    private val card = LorcanaCard(1, "Example", 1, "7", "1/204", "", true, true,
        setOf("Amber", "Ruby"), "Character", "Archazia's Island")
    @Test fun noSelectionIncludesEveryCard() {
        assertTrue(CardFilters().matches(card))
        assertTrue(CardFilters().matches(card.copy(colors = emptySet(), type = "")))
    }
    @Test fun eitherColorMatchesMulticolorCards() {
        assertTrue(CardFilters(colors = setOf("Ruby", "Steel")).matches(card))
        assertFalse(CardFilters(colors = setOf("Steel")).matches(card))
    }
    @Test fun groupsIntersectWhileOptionsWithinGroupAreAlternatives() {
        val filters = CardFilters(setOf("Amber"), setOf("1", "7"), setOf("Character", "Action"))
        assertTrue(filters.matches(card))
        assertFalse(filters.matches(card.copy(set = "8")))
        assertFalse(filters.matches(card.copy(type = "Item")))
    }
    @Test fun colorlessCardsHaveAnExplicitOption() {
        assertTrue(CardFilters(colors = setOf("")).matches(card.copy(colors = emptySet())))
        assertFalse(CardFilters(colors = setOf("")).matches(card))
    }
}

package com.manuelperez.inkshare

import org.junit.Assert.*
import org.junit.Test

class LoanAvailabilityTest {
    private val normal = LoanItem(42, "Carta", "42/204", false, 4)
    private val foil = normal.copy(foil = true, quantity = 2)
    private fun loan(id: String, lender: String = "me", closed: Boolean = false,
        items: List<LoanItem> = listOf(normal), remaining: List<Int> = items.map { it.quantity }) =
        Loan(id, lender, "Nombre", "Amigo", items, closed, null, null, remaining)

    @Test fun totalsSeparateFinishesAndUsePartialReturns() {
        val result = LoanAvailability.outstanding("me", listOf(
            loan("a", items = listOf(normal, foil), remaining = listOf(1, 2)),
            loan("b", remaining = listOf(2))))
        assertEquals(3, result[normal.key]); assertEquals(2, result[foil.key])
    }
    @Test fun ignoresClosedAndBorrowedCards() {
        assertTrue(LoanAvailability.outstanding("me", listOf(
            loan("closed", closed = true), loan("borrowed", lender = "friend"),
            loan("returned", remaining = listOf(0)))).isEmpty())
    }
    @Test fun legacyLoansDefaultToOriginalQuantities() {
        val old = Loan("old", "me", "Nombre", "Amigo", listOf(normal), false, null, null)
        assertEquals(4, LoanAvailability.outstanding("me", listOf(old))[normal.key])
    }
    @Test fun unknownAvailabilityAndInconsistentTotalsAreNotHidden() {
        assertTrue(LoanAvailability.label(4, null).contains("sin confirmar"))
        assertTrue(LoanAvailability.label(1, 3).startsWith("0 disponibles"))
        assertTrue(LoanAvailability.label(1, 3).contains("Revisa"))
        assertTrue(LoanAvailability.label(4, 1).startsWith("3 disponibles"))
    }
}

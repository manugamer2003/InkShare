package com.manuelperez.inkshare

object LoanAvailability {
    fun outstanding(uid: String, loans: List<Loan>): Map<String, Int> {
        val result = mutableMapOf<String, Int>()
        loans.filter { it.lenderUid == uid && !it.closed }.forEach { loan ->
            require(loan.remaining.size == loan.items.size)
            loan.items.forEachIndexed { index, item ->
                val count = loan.remaining[index]
                require(count in 0..item.quantity)
                if (count > 0) result[item.key] = Math.addExact(result[item.key] ?: 0, count)
            }
        }
        return result
    }
    fun label(total: Int, lent: Int?): String = if (lent == null)
        "$total en total · disponibilidad sin confirmar"
    else "${(total - lent).coerceAtLeast(0)} disponibles · $lent prestadas · $total en total" +
        if (lent > total) " · Revisa el total de tu colección" else ""
}

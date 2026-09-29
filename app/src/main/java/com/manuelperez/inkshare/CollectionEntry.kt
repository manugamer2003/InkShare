package com.manuelperez.inkshare

data class CollectionEntry(val cardId: Int, val foil: Boolean, val quantity: Int) {
    val key: String get() = "${cardId}_${if (foil) "foil" else "normal"}"
    init { require(cardId > 0 && quantity in 0..9999) { "Cantidad o carta no válida" } }
    companion object {
        fun fromKey(key: String, quantity: Int): CollectionEntry {
            val match = Regex("([1-9][0-9]*)_(normal|foil)").matchEntire(key) ?: error("Identificador de carta no válido")
            return CollectionEntry(match.groupValues[1].toInt(), match.groupValues[2] == "foil", quantity)
        }
        fun changed(current: Int, delta: Int): Int {
            require(current in 0..9999 && delta in listOf(-1, 1))
            val result = current + delta
            require(result in 0..9999) { if (result < 0) "No quedan copias para quitar" else "Máximo de 9999 copias" }
            return result
        }
    }
}

package com.manuelperez.inkshare

data class CardFilters(
    val colors: Set<String> = emptySet(),
    val sets: Set<String> = emptySet(),
    val types: Set<String> = emptySet()
) {
    val count get() = colors.size + sets.size + types.size
    fun matches(card: LorcanaCard): Boolean =
        (colors.isEmpty() || card.colors.any { it in colors } || (card.colors.isEmpty() && "" in colors)) &&
        (sets.isEmpty() || card.set in sets) &&
        (types.isEmpty() || card.type in types)
}

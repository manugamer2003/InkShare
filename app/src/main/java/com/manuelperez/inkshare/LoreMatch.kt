package com.manuelperez.inkshare

enum class LoreMode(val label: String, val target: Int) {
    PACK_RUSH("Pack Rush", 15), STANDARD("Estándar", 20), COCONUT("Coconut", 25);
    fun allows(players: Int) = if (this == PACK_RUSH) players == 2 else players in 2..4
}
data class LoreMatch(val mode: LoreMode, val names: List<String>, val scores: List<Int>,
    val history: List<List<Int>> = emptyList()) {
    init {
        require(mode.allows(names.size) && scores.size == names.size)
        require(names.all { it.isNotBlank() && it.length <= 30 })
        require(scores.all { it in 0..mode.target })
        require(history.size <= 200 && history.all { row -> row.size == scores.size && row.all { it in 0..mode.target } })
    }
    val winner: Int? get() = scores.indexOfFirst { it >= mode.target }.takeIf { it >= 0 }
    fun change(player: Int, delta: Int): LoreMatch {
        require(player in scores.indices && delta in listOf(-1, 1))
        if (delta > 0 && winner != null) return this
        val next = (scores[player] + delta).coerceIn(0, mode.target)
        if (next == scores[player]) return this
        return copy(scores = scores.mapIndexed { index, score -> if (index == player) next else score },
            history = (history + listOf(scores)).takeLast(200))
    }
    fun undo(): LoreMatch = history.lastOrNull()?.let { copy(scores = it, history = history.dropLast(1)) } ?: this
    fun restart() = copy(scores = List(scores.size) { 0 }, history = emptyList())
    companion object {
        fun start(mode: LoreMode, players: Int): LoreMatch {
            require(mode.allows(players))
            return LoreMatch(mode, List(players) { "Jugador ${it + 1}" }, List(players) { 0 })
        }
    }
}

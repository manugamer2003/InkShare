package com.manuelperez.inkshare

data class MatchTimer(val durationMs: Long = 55 * 60_000L, val pausedMs: Long = durationMs, val deadlineMs: Long = 0) {
    val running get() = deadlineMs > 0
    fun remaining(now: Long): Long = if (running) (deadlineMs - now).coerceIn(0, durationMs) else pausedMs
    fun pause(now: Long) = copy(pausedMs = remaining(now), deadlineMs = 0)
    fun resume(now: Long): MatchTimer = if (running || pausedMs <= 0) this else copy(deadlineMs = now + pausedMs)
}

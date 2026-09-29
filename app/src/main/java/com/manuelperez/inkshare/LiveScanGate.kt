package com.manuelperez.inkshare

// Two consistent readings, then explicit rearming before another confirmation.
class LiveScanGate(var paused: Boolean = false) {
    private var previous: Int? = null
    fun accept(id: Int?): Boolean {
        if (paused) return false
        val stable = id != null && id == previous
        previous = id
        if (stable) paused = true
        return stable
    }
    fun next() { previous = null; paused = false }
}

package com.manuelperez.inkshare

// A stable different card rearms automatically. Blur never rearms the same card.
class BatchScanGate {
    private var previous: Int? = null
    private var lastAccepted: Int? = null
    fun accept(id: Int?): Boolean {
        val stable = id != null && id == previous && id != lastAccepted
        previous = id
        if (stable) lastAccepted = id
        return stable
    }
    fun next() { previous = null; lastAccepted = null }
}

package com.manuelperez.inkshare

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import org.json.JSONArray
import org.json.JSONObject

class CounterViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("lore_counter_${Session.uid()}", 0)
    val match = MutableLiveData<LoreMatch?>(load())
    var configuring = match.value == null
    var draftMode = match.value?.mode ?: LoreMode.PACK_RUSH
    var draftPlayers = match.value?.scores?.size ?: 2
    var draftMinutes = 55
    var draftTimerEnabled = true
    var timerEnabled = prefs.getBoolean("timerEnabled", false)
        private set
    var timer = MatchTimer(prefs.getLong("timerDuration", 55 * 60_000L),
        prefs.getLong("timerRemaining", 55 * 60_000L), prefs.getLong("timerDeadline", 0))
        private set
    private fun saveTimer() {
        prefs.edit().putBoolean("timerEnabled", timerEnabled).putLong("timerDuration", timer.durationMs)
            .putLong("timerRemaining", timer.pausedMs).putLong("timerDeadline", timer.deadlineMs).apply()
    }
    fun setTimer(minutes: Int, enabled: Boolean = true, autoStart: Boolean = true) {
        require(minutes in 1..999)
        timerEnabled = enabled
        timer = MatchTimer(minutes * 60_000L)
        if (enabled && autoStart && match.value?.winner == null) timer = timer.resume(System.currentTimeMillis())
        saveTimer()
    }
    fun toggleTimer() {
        val now = System.currentTimeMillis()
        timer = if (timer.running) timer.pause(now) else timer.resume(now)
        saveTimer()
    }
    fun expireTimer(): Boolean {
        if (!timerEnabled || !timer.running || timer.remaining(System.currentTimeMillis()) > 0) return false
        timer = timer.pause(System.currentTimeMillis()); saveTimer(); return true
    }
    private fun load(): LoreMatch? = try {
        prefs.getString("match", null)?.let { raw ->
            val json = JSONObject(raw)
            val names = json.getJSONArray("names")
            val scores = json.getJSONArray("scores")
            val history = json.optJSONArray("history") ?: JSONArray()
            LoreMatch(LoreMode.valueOf(json.getString("mode")), List(names.length()) { names.getString(it) },
                List(scores.length()) { scores.getInt(it) }, List(history.length()) { i ->
                    val row = history.getJSONArray(i)
                    List(row.length()) { row.getInt(it) }
                })
        }
    } catch (e: Exception) { null }
    private fun save(value: LoreMatch) {
        val json = JSONObject().put("mode", value.mode.name).put("names", JSONArray(value.names))
            .put("scores", JSONArray(value.scores)).put("history", JSONArray(value.history.map { JSONArray(it) }))
        prefs.edit().putString("match", json.toString()).apply()
        match.value = value
    }
    fun start() {
        setTimer(draftMinutes, draftTimerEnabled, autoStart = false)
        configuring = false
        save(LoreMatch.start(draftMode, draftPlayers))
    }
    fun change(player: Int, delta: Int): Int? {
        val old = match.value ?: return null
        val next = old.change(player, delta)
        if (next != old) save(next)
        if (next.winner != null && timer.running) { timer = timer.pause(System.currentTimeMillis()); saveTimer() }
        return next.winner?.takeIf { old.winner == null }
    }
    fun undo() { match.value?.let { save(it.undo()) } }
    fun restart() { match.value?.let { save(it.restart()) } }
    fun rename(player: Int, name: String) {
        val clean = name.trim()
        require(clean.isNotEmpty() && clean.length <= 30)
        match.value?.let { current -> save(current.copy(names = current.names.mapIndexed { i, previous -> if (i == player) clean else previous })) }
    }
}

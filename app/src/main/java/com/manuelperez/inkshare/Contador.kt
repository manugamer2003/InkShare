package com.manuelperez.inkshare

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputFilter
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.TextViewCompat

class Contador : AppCompatActivity() {
    private val model: CounterViewModel by viewModels()
    private val scoreViews = mutableListOf<TextView>()
    private val panelViews = mutableListOf<LinearLayout>()
    private val defaultBackgrounds = listOf("#4527A0", "#00695C", "#AD1457", "#154B80").map { Color.parseColor(it) }
    private val nameViews = mutableListOf<TextView>()
    private val plusButtons = mutableListOf<Button>()
    private val minusButtons = mutableListOf<Button>()
    private val backgroundColors = listOf(
        "Ámbar" to Color.parseColor("#FFE082"),
        "Amatista" to Color.parseColor("#E1BEE7"),
        "Esmeralda" to Color.parseColor("#B9F6CA"),
        "Rubí" to Color.parseColor("#FFABAB"),
        "Zafiro" to Color.parseColor("#B3E5FC"),
        "Acero" to Color.parseColor("#CFD8DC")
    )
    private val colorPreferences by lazy { getSharedPreferences("counter_colors_${Session.uid()}", MODE_PRIVATE) }
    private var boardSize = 0
    private val timerHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val timerTick = object : Runnable {
        override fun run() { renderTimer(); timerHandler.postDelayed(this, 250) }
    }
    private val winnerAudio by lazy { WinnerAudio(this) }
    private val timerAudio by lazy { WinnerAudio(this) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!requireProfile()) return
        // Replace white backgrounds saved by earlier versions.
        defaultBackgrounds.forEachIndexed { index, color ->
            if (colorPreferences.getInt("position_$index", color) == Color.WHITE)
                colorPreferences.edit().putInt("position_$index", color).apply()
        }
        enableEdgeToEdge()
        setContentView(R.layout.activity_contador)
        applyInsets()
        volumeControlStream = android.media.AudioManager.STREAM_MUSIC
        val modes = findViewById<Spinner>(R.id.counterMode)
        val players = findViewById<Spinner>(R.id.counterPlayers)
        modes.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            LoreMode.entries.map { "${it.label} · ${it.target} lore" })
        modes.setSelection(model.draftMode.ordinal)
        fun playerOptions() {
            val allowed = if (model.draftMode == LoreMode.PACK_RUSH) listOf(2) else listOf(2, 3, 4)
            if (model.draftPlayers !in allowed) model.draftPlayers = 2
            players.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, allowed.map { "$it jugadores" })
            players.setSelection(allowed.indexOf(model.draftPlayers))
            players.isEnabled = allowed.size > 1
            players.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { model.draftPlayers = allowed[position] }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
        playerOptions()
        modes.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                model.draftMode = LoreMode.entries[position]
                playerOptions()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        val timerEnabled = findViewById<CheckBox>(R.id.enableMatchTimer)
        val timerMinutes = findViewById<EditText>(R.id.matchMinutes)
        timerEnabled.isChecked = model.draftTimerEnabled
        timerMinutes.setText(model.draftMinutes.toString())
        timerMinutes.isEnabled = timerEnabled.isChecked
        timerEnabled.setOnCheckedChangeListener { _, checked -> model.draftTimerEnabled = checked; timerMinutes.isEnabled = checked }
        findViewById<Button>(R.id.timerPause).setOnClickListener { model.toggleTimer(); renderTimer() }
        findViewById<Button>(R.id.timerTime).setOnClickListener { editTimer() }
        findViewById<Button>(R.id.timerReset).setOnClickListener {
            model.setTimer((model.timer.durationMs / 60_000).toInt())
            renderTimer()
        }
        findViewById<Button>(R.id.startCounter).setOnClickListener {
            val minutes = timerMinutes.text.toString().toIntOrNull()
            if (timerEnabled.isChecked && (minutes == null || minutes !in 1..999)) {
                timerMinutes.error = "Introduce entre 1 y 999 minutos"; return@setOnClickListener
            }
            model.draftMinutes = minutes?.takeIf { it in 1..999 } ?: 55
            if (model.match.value == null) model.start()
            else AlertDialog.Builder(this).setTitle("¿Empezar otra partida?")
                .setMessage("La nueva partida sustituirá al contador guardado.")
                .setPositiveButton("Empezar") { _, _ -> boardSize = 0; model.start() }.setNegativeButton("Cancelar", null).show()
        }
        findViewById<Button>(R.id.resumeCounter).setOnClickListener { model.configuring = false; render() }
        findViewById<Button>(R.id.undoCounter).setOnClickListener { model.undo() }
        findViewById<Button>(R.id.resetCounter).setOnClickListener {
            AlertDialog.Builder(this).setTitle("¿Reiniciar los puntos?")
                .setMessage("Todos volverán a 0. Se conservarán el modo y los jugadores.")
                .setPositiveButton("Reiniciar") { _, _ -> model.restart() }.setNegativeButton("Cancelar", null).show()
        }
        findViewById<Button>(R.id.newCounter).setOnClickListener { model.configuring = true; render() }
        findViewById<Button>(R.id.exitCounter).setOnClickListener { finish() }
        model.match.observe(this) { render() }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun render() {
        renderTimer()
        val match = model.match.value
        val setup = model.configuring || match == null
        findViewById<View>(R.id.counterSetup).visibility = if (setup) View.VISIBLE else View.GONE
        findViewById<View>(R.id.counterGame).visibility = if (setup) View.GONE else View.VISIBLE
        findViewById<View>(R.id.resumeCounter).visibility = if (match != null) View.VISIBLE else View.GONE
        if (!setup && match?.winner == null) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (setup || match?.winner == null) winnerAudio.stop()
        if (setup) return
        if (boardSize != match.scores.size) buildBoard(match.scores.size)
        findViewById<TextView>(R.id.counterHeading).text = "${match.mode.label} · Objetivo: ${match.mode.target} lore"
        findViewById<TextView>(R.id.counterWinner).apply {
            visibility = if (match.winner != null) View.VISIBLE else View.GONE
            text = match.winner?.let { "¡Posición ${it + 1}: ${match.names[it]} gana! Puedes deshacer o restar para corregir." }.orEmpty()
        }
        findViewById<Button>(R.id.undoCounter).isEnabled = match.history.isNotEmpty()
        match.scores.forEachIndexed { i, score ->
            scoreViews[i].text = score.toString()
            scoreViews[i].setTextColor(when {
                score * 100 >= match.mode.target * 85 -> Color.parseColor("#FF5252")
                score * 100 >= match.mode.target * 60 -> Color.parseColor("#FFEB3B")
                else -> Color.WHITE
            })
            scoreViews[i].contentDescription = "${match.names[i]}: $score de ${match.mode.target} lore. Mantén pulsado para cambiar el color del fondo."
            nameViews[i].text = "Posición ${i + 1} · ${match.names[i]}${if (match.winner == i) " ★" else ""}"
            plusButtons[i].isEnabled = match.winner == null
            minusButtons[i].isEnabled = score > 0
            plusButtons[i].contentDescription = "Sumar un lore a ${match.names[i]}"
            minusButtons[i].contentDescription = "Restar un lore a ${match.names[i]}"
        }
    }
    private fun buildBoard(players: Int) {
        boardSize = players
        val board = findViewById<LinearLayout>(R.id.counterBoard)
        board.removeAllViews(); panelViews.clear(); scoreViews.clear(); nameViews.clear(); plusButtons.clear(); minusButtons.clear()
        val rows: List<List<Int?>> = if (players == 2) listOf(listOf(0, 1))
            else listOf(listOf(0, 1), listOf(2, if (players == 4) 3 else null))

        rows.forEach { indices ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false }
            board.addView(row, LinearLayout.LayoutParams(-1, 0, 1f))
            indices.forEachIndexed seat@ { column, index ->
                val slotParams = LinearLayout.LayoutParams(0, -1, 1f).apply {
                    setMargins(dp(4), dp(4), dp(4), dp(4))
                }
                if (index == null) {
                    row.addView(View(this).apply {
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    }, slotParams)
                    return@seat
                }
                val slot = SidewaysCounterSlot(this, if (column == 0) 90f else -90f)
                row.addView(slot, slotParams)
                val panel = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(dp(8), dp(4), dp(8), dp(4))
                    background = GradientDrawable().apply { setColor(colorPreferences.getInt("position_$index", defaultBackgrounds[index])); cornerRadius = dp(16).toFloat() }
                }
                slot.addView(panel, FrameLayout.LayoutParams(-1, -1))
                val name = TextView(this).apply {
                    gravity = Gravity.CENTER; textSize = 16f; setTextColor(Color.WHITE)
                    setTypeface(null, Typeface.BOLD); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                    contentDescription = "Cambiar nombre del jugador ${index + 1}"
                    setOnClickListener { rename(index) }
                }
                val score = OutlinedScoreView(this).apply {
                    gravity = Gravity.CENTER; setTextColor(Color.WHITE); setTypeface(null, Typeface.BOLD)
                    TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(this, 20, 72, 2, android.util.TypedValue.COMPLEX_UNIT_SP)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES

                    setShadowLayer(dp(2).toFloat(), 0f, dp(1).toFloat(), Color.BLACK)
                    setOnLongClickListener { chooseBackgroundColor(index); true }
                }
                panel.addView(name, LinearLayout.LayoutParams(-1, dp(32)))
                panel.addView(score, LinearLayout.LayoutParams(-1, 0, 1f))
                val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                fun button(label: String, delta: Int) = Button(this).apply {
                    text = label; textSize = 24f; minWidth = 0; minimumWidth = 0; setPadding(0, 0, 0, 0)
                    setTextColor(Color.WHITE)
                    setOnClickListener {
                        val winner = model.change(index, delta)
                        if (winner != null) {
                            winnerAudio.play(winner + 1)
                            AlertDialog.Builder(this@Contador)
                            .setTitle("¡Posición ${winner + 1}: ${model.match.value!!.names[winner]} gana!")
                            .setMessage("Ha alcanzado ${model.match.value!!.mode.target} lore.")
                            .setPositiveButton("Entendido", null).setNegativeButton("Deshacer punto") { _, _ -> model.undo() }.show()
                        }
                    }
                }
                val minus = button("−", -1); val plus = button("+", 1)
                controls.addView(minus, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginEnd = dp(6) })
                controls.addView(plus, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginStart = dp(6) })
                panel.addView(controls, LinearLayout.LayoutParams(-1, dp(52)))
                panelViews.add(panel); nameViews.add(name); scoreViews.add(score); minusButtons.add(minus); plusButtons.add(plus)
            }
        }
    }
    override fun onStart() {
        super.onStart()
        if (Session.ready()) { timerHandler.removeCallbacks(timerTick); timerHandler.post(timerTick) }
    }
    private fun renderTimer() {
        if (model.match.value == null) return
        if (model.expireTimer()) timerAudio.playTimeUp()
        else if (!model.timerEnabled || model.timer.remaining(System.currentTimeMillis()) > 0) timerAudio.stop()
        findViewById<View>(R.id.timerBar).visibility = if (model.timerEnabled) View.VISIBLE else View.GONE
        val seconds = (model.timer.remaining(System.currentTimeMillis()) + 999) / 1000
        findViewById<Button>(R.id.timerTime).apply {
            text = if (seconds == 0L) "Tiempo agotado" else String.format(java.util.Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60)
            contentDescription = "Temporizador: $text. Toca para cambiar la duración."
        }
        findViewById<Button>(R.id.timerPause).apply {
            text = if (model.timer.running) "Pausar" else if (model.timer.pausedMs == model.timer.durationMs) "Iniciar" else "Reanudar"
            isEnabled = seconds > 0
        }
    }
    private fun editTimer() {
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(3))
            setText((model.timer.durationMs / 60_000).toString()); selectAll()
        }
        val dialog = AlertDialog.Builder(this).setTitle("Duración en minutos")
            .setMessage("Se reiniciará el temporizador con la duración indicada. Los puntos se conservan.")
            .setView(input).setPositiveButton("Reiniciar tiempo", null).setNegativeButton("Cancelar", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val minutes = input.text.toString().toIntOrNull()
            if (minutes == null || minutes !in 1..999) input.error = "Entre 1 y 999 minutos"
            else { model.setTimer(minutes); renderTimer(); dialog.dismiss() }
        } }
        dialog.show()
    }
    override fun onStop() {
        timerHandler.removeCallbacks(timerTick)
        timerAudio.stop()
        winnerAudio.stop()
        super.onStop()
    }
    private fun chooseBackgroundColor(index: Int) {
        val current = model.match.value ?: return
        val selected = colorPreferences.getInt("position_$index", defaultBackgrounds[index])
        val labels = backgroundColors.map { (name, color) ->
            android.text.SpannableString("●  $name").apply {
                setSpan(android.text.style.ForegroundColorSpan(color), 0, 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Color del fondo · ${current.names[index]}")
            .setSingleChoiceItems(labels, backgroundColors.indexOfFirst { it.second == selected }) { dialog, choice ->
                val color = backgroundColors[choice].second
                colorPreferences.edit().putInt("position_$index", color).apply()
                (panelViews.getOrNull(index)?.background as? GradientDrawable)?.setColor(color)
                dialog.dismiss()
            }.setNegativeButton("Cancelar", null).show()
    }
    private fun rename(index: Int) {
        val current = model.match.value ?: return
        val input = EditText(this).apply { setText(current.names[index]); selectAll(); maxLines = 1; filters = arrayOf(InputFilter.LengthFilter(30)) }
        val dialog = AlertDialog.Builder(this).setTitle("Nombre del jugador").setView(input)
            .setPositiveButton("Guardar", null).setNegativeButton("Cancelar", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (input.text.toString().isBlank()) input.error = "Escribe un nombre"
            else { model.rename(index, input.text.toString()); dialog.dismiss() }
        } }
        dialog.show()
    }
}

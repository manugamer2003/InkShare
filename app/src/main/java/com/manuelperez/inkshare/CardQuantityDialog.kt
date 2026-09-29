package com.manuelperez.inkshare

import android.graphics.Color
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer

fun AppCompatActivity.cardQuantityDialog(
    card: LorcanaCard, model: CollectionViewModel, batch: BatchScanViewModel? = null,
    changeCard: (() -> Unit)? = null, dismissed: (() -> Unit)? = null, loans: LoansViewModel? = null
): AlertDialog {
    fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(8)) }
    val info = TextView(this).apply {
        text = if (batch == null) "Copias en tu colección. Cada cambio se guarda en Firebase." else "Ajusta las copias que añadirás al guardar el lote."
        setTextColor(Color.WHITE)
    }
    box.addView(info)
    data class Row(val foil: Boolean, val label: TextView, val count: TextView, val minus: Button, val plus: Button)
    val rows = mutableListOf<Row>()
    fun count(foil: Boolean) = if (batch == null) model.count(card, foil) else batch.quantities.value?.get(CollectionStore.key(card.id, foil)) ?: 0
    for (foil in listOf(false, true).filter { if (it) card.hasFoil else card.hasNormal }) {
        val label = TextView(this).apply { setTextColor(Color.WHITE); textSize = 17f }
        box.addView(label, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        val line = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val value = TextView(this).apply { gravity = Gravity.CENTER; textSize = 26f; setTextColor(Color.WHITE) }
        fun button(delta: Int) = Button(this).apply {
            text = if (delta > 0) "+" else "−"; textSize = 24f; setTextColor(Color.WHITE)
            contentDescription = "${if (delta > 0) "Añadir" else "Quitar"} una copia ${if (foil) "foil" else "normal"}"
            setOnClickListener {
                if (batch == null) model.change(card, foil, delta)
                else try { batch.setQuantity(card, foil, count(foil) + delta) }
                catch (e: IllegalArgumentException) { info.text = e.localizedMessage }
            }
        }
        val minus = button(-1); val plus = button(1)
        line.addView(minus, LinearLayout.LayoutParams(dp(64), dp(56)))
        line.addView(value, LinearLayout.LayoutParams(0, dp(56), 1f).apply { setMargins(dp(12), 0, dp(12), 0) })
        line.addView(plus, LinearLayout.LayoutParams(dp(64), dp(56)))
        box.addView(line)
        rows.add(Row(foil, label, value, minus, plus))
    }
    val status = TextView(this).apply { setTextColor(Color.WHITE); setPadding(0, dp(12), 0, 0) }
    box.addView(status)
    fun refresh() {
        val busy = if (batch == null) model.busy.value == true else batch.busy.value == true || batch.locked.value == true
        rows.forEach { row ->
            val quantity = count(row.foil)
            val finish = if (row.foil) "Foil" else "Normal"
            row.label.text = if (batch == null && loans != null) "$finish · ${loans.availability(card.id, row.foil, model.count(card, row.foil))}" else if (batch == null) finish else "$finish · tienes ${model.count(card, row.foil)} · en este lote:"
            row.count.text = quantity.toString()
            row.count.contentDescription = "$finish: $quantity copias"
            row.minus.isEnabled = !busy && quantity > 0
            row.plus.isEnabled = !busy && quantity < 9999
        }
        status.text = if (batch == null) { if (busy) "Guardando…" else model.message.value.orEmpty() } else batch.message.value.orEmpty()
    }
    val builder = AlertDialog.Builder(this).setTitle("${card.name}\n${card.identifier}")
        .setView(ScrollView(this).apply { addView(box) }).setPositiveButton("Hecho", null)
    if (changeCard != null) builder.setNeutralButton("Cambiar carta") { _, _ -> changeCard() }
    val dialog = builder.create()
    val removeObservers = mutableListOf<() -> Unit>()
    fun <T> watch(data: LiveData<T>) {
        val observer = Observer<T> { refresh() }
        data.observe(this, observer)
        removeObservers.add { data.removeObserver(observer) }
    }
    watch(model.counts); watch(model.busy); watch(model.message)
    if (loans != null) watch(loans.lentCounts)
    if (batch != null) { watch(batch.quantities); watch(batch.busy); watch(batch.locked); watch(batch.message) }
    dialog.setOnDismissListener { removeObservers.forEach { it() }; dismissed?.invoke() }
    refresh()
    dialog.show()
    return dialog
}

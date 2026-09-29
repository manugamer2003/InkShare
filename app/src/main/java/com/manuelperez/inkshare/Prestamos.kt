package com.manuelperez.inkshare

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.*
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.tabs.TabLayout
import java.text.DateFormat

class Prestamos : AppCompatActivity() {
    private val model: LoansViewModel by viewModels()
    private val friends: FriendsViewModel by viewModels()
    private val collection: CollectionViewModel by viewModels()
    private var visibleRequests: List<LoanRequest> = emptyList()
    private var visible: List<Loan> = emptyList()
    private lateinit var listAdapter: ArrayAdapter<String>
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!requireProfile()) return
        enableEdgeToEdge()
        setContentView(R.layout.activity_prestamos)
        applyInsets()
        if (savedInstanceState == null && intent.hasExtra("pushTab")) {
            model.tab = intent.getIntExtra("pushTab", 0).coerceIn(0, 3)
            intent.getStringExtra("pushBody")?.let { body ->
                AlertDialog.Builder(this).setTitle("InkShare").setMessage(body).setPositiveButton("Ver", null).show()
            }
        }
        model.history = model.tab == 1
        if (intent.hasExtra("collectionOwnerUid")) {
            model.requesting = true
            if (model.friend.value == null) model.friend.value = Friend(intent.getStringExtra("collectionOwnerUid")!!, intent.getStringExtra("collectionOwnerName").orEmpty())
            if (savedInstanceState == null) {
                model.composing = true
                val cardId = intent.getIntExtra("requestedCardId", 0)
                if (cardId > 0) model.put(LoanItem(cardId, intent.getStringExtra("requestedCardName").orEmpty(),
                    intent.getStringExtra("requestedCardIdentifier").orEmpty(), intent.getBooleanExtra("requestedFoil", false), 1))
            }
        }
        listAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
        findViewById<ListView>(R.id.loanList).apply {
            adapter = listAdapter
            setOnItemClickListener { _, _, position, _ -> if (model.tab >= 2) visibleRequests.getOrNull(position)?.let { requestDetails(it) } else visible.getOrNull(position)?.let { details(it) } }
        }
        findViewById<TabLayout>(R.id.loanTabs).apply {
            tabMode = TabLayout.MODE_SCROLLABLE
            addTab(newTab().setText("Activos")); addTab(newTab().setText("Históricos"))
            addTab(newTab().setText("Recibidas")); addTab(newTab().setText("Enviadas"))
            getTabAt(model.tab)?.select()
            addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab) { model.tab = tab.position; model.history = tab.position == 1; refresh() }
                override fun onTabUnselected(tab: TabLayout.Tab) {}
                override fun onTabReselected(tab: TabLayout.Tab) {}
            })
        }
        findViewById<Button>(R.id.newLoan).setOnClickListener { model.composing = true; if (model.requesting) model.friend.value = Friend(intent.getStringExtra("collectionOwnerUid")!!, intent.getStringExtra("collectionOwnerName").orEmpty()); refresh() }
        findViewById<Button>(R.id.pickFriend).setOnClickListener {
            val rows = friends.friends.value.orEmpty()
            if (rows.isEmpty()) toast("Añade un amigo en Mis amigos primero.")
            else AlertDialog.Builder(this).setTitle("¿A quién prestas las cartas?")
                .setItems(rows.map { "@${it.username}" }.toTypedArray()) { _, i -> model.friend.value = rows[i] }.show()
        }
        findViewById<Button>(R.id.addLoanCard).setOnClickListener { chooseCard() }
        findViewById<Button>(R.id.cancelLoan).setOnClickListener {
            AlertDialog.Builder(this).setMessage("¿Descartar este borrador?")
                .setPositiveButton("Descartar") { _, _ -> model.discard(); refresh() }.setNegativeButton("Seguir editando", null).show()
        }
        findViewById<Button>(R.id.saveLoan).setOnClickListener {
            val target = model.friend.value ?: return@setOnClickListener
            AlertDialog.Builder(this).setTitle(if (model.requesting) "Pedir cartas a @${target.username}" else "Crear préstamo a @${target.username}")
                .setMessage(model.draft.value.orEmpty().joinToString("\n\n") { it.label() })
                .setPositiveButton(if (model.requesting) "Enviar solicitud" else "Crear préstamo") { _, _ -> if (model.requesting) model.sendRequest() else model.create() }.setNegativeButton("Revisar", null).show()
        }
        model.requests.observe(this) { refresh() }
        model.loans.observe(this) { refresh() }
        model.lentCounts.observe(this) { refresh() }
        model.draft.observe(this) { refresh() }
        model.friend.observe(this) { refresh() }
        model.busy.observe(this) { refresh() }
        model.status.observe(this) { findViewById<TextView>(R.id.loanStatus).text = it; refresh() }
        collection.cards.observe(this) { refresh() }
        collection.counts.observe(this) { refresh() }
        friends.status.observe(this) { findViewById<TextView>(R.id.friendStatus).text = it }
    }
    private fun refresh() {
        val busy = model.busy.value == true
        if (!collection.readOnly) {
            val lent = model.lentCounts.value
            val counts = collection.counts.value.orEmpty()
            val inventory = if (lent == null) "Disponibilidad pendiente de confirmar" else {
                val available = counts.entries.sumOf { (key, total) -> (total - (lent[key] ?: 0)).coerceAtLeast(0) }
                "$available disponibles · ${lent.values.sum()} prestadas · ${counts.values.sum()} en total" +
                    if (model.availabilityCached) " (datos guardados)" else ""
            }
            findViewById<TextView>(R.id.loanStatus).text = model.status.value.orEmpty() + "\nTu colección: " + inventory
        }
        findViewById<Button>(R.id.newLoan).text = if (model.requesting) "Pedir otro préstamo" else "Crear préstamo"
        findViewById<Button>(R.id.saveLoan).text = if (model.requesting) "Revisar y enviar solicitud" else "Revisar y crear préstamo"
        findViewById<Button>(R.id.pickFriend).visibility = if (model.requesting) View.GONE else View.VISIBLE
        findViewById<TextView>(R.id.loanDraftHelp).text = if (model.requesting) "Pide hasta 10 cartas/acabados a @${intent.getStringExtra("collectionOwnerName")}. Tu amigo decidirá si acepta; la solicitud no reserva cartas." else "Elige hasta 10 cartas/acabados distintos. El total de tu colección incluye las copias prestadas. Disponibles = total menos pendientes de devolución."

        findViewById<View>(R.id.loanDraft).visibility = if (model.composing) View.VISIBLE else View.GONE
        findViewById<Button>(R.id.newLoan).visibility = if (model.composing) View.GONE else View.VISIBLE
        findViewById<Button>(R.id.pickFriend).text = model.friend.value?.let { "Amigo: @${it.username}" } ?: "Elegir amigo"
        listOf(R.id.pickFriend, R.id.cancelLoan, R.id.newLoan).forEach { findViewById<View>(it).isEnabled = !busy }
        findViewById<Button>(R.id.addLoanCard).isEnabled = !busy && !collection.cards.value.isNullOrEmpty()
        findViewById<Button>(R.id.saveLoan).isEnabled = !busy && model.friend.value != null && !model.draft.value.isNullOrEmpty()
        val items = findViewById<LinearLayout>(R.id.draftItems)
        items.removeAllViews()
        model.draft.value.orEmpty().forEach { item ->
            items.addView(Button(this).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    val gap = resources.getDimensionPixelSize(R.dimen.button_half_gap)
                    topMargin = gap
                    bottomMargin = gap
                }
                text = "${item.label()} · Quitar"; isEnabled = !busy
                setOnClickListener { model.remove(item) }
            })
        }
        visible = model.loans.value.orEmpty().filter { it.closed == model.history }
        listAdapter.clear()
        listAdapter.addAll(visible.map {
            "${if (it.lenderUid == model.uid) "Prestado a @${it.borrowerName}" else "Recibido de @${it.lenderName}"}\n" +
                "${if (it.closed) it.items.sumOf { item -> item.quantity } else it.remaining.sum()} ${if (it.closed) "cartas devueltas" else "cartas pendientes"} · ${date(if (it.closed) it.closedAt else it.createdAt)}"
        })
        if (model.tab >= 2) {
            visibleRequests = model.requests.value.orEmpty().filter { if (model.tab == 2) it.lenderUid == model.uid else it.borrowerUid == model.uid }
            listAdapter.clear()
            listAdapter.addAll(visibleRequests.map {
                "${if (model.tab == 2) "De @${it.borrowerName}" else "A @${it.lenderName}"} · ${requestStatus(it.status)}\n${it.items.sumOf { row -> row.quantity }} cartas"
            })
        }
        findViewById<TextView>(R.id.noLoans).apply {
            visibility = if (if (model.tab >= 2) visibleRequests.isEmpty() else visible.isEmpty()) View.VISIBLE else View.GONE
            text = if (model.tab >= 2) "No hay solicitudes en esta pestaña." else if (model.history) "Todavía no hay préstamos cerrados." else "No hay préstamos activos."
        }
    }
    private fun loanCardLabel(card: LorcanaCard): String = "${card.name} · ${card.identifier}" +
        if (model.requesting) "" else "\n" + listOf(false, true)
            .filter { if (it) card.hasFoil else card.hasNormal }.joinToString("\n") {
                "${if (it) "Foil" else "Normal"}: ${model.availability(card.id, it, collection.count(card, it))}"
            }
    private fun chooseCard() {
        val cards = collection.cards.value.orEmpty().filter { collection.count(it, false) + collection.count(it, true) > 0 }
        if (cards.isEmpty()) { toast("No hay cartas disponibles en esta colección. Comprueba el acceso y la conexión."); return }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 8, 24, 8) }
        val search = EditText(this).apply { hint = "Buscar carta o edición"; inputType = InputType.TYPE_CLASS_TEXT }
        val list = ListView(this)
        var rows = cards
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, rows.map { loanCardLabel(it) })
        list.adapter = adapter
        box.addView(search); box.addView(list, LinearLayout.LayoutParams(-1, (310 * resources.displayMetrics.density).toInt()))
        val dialog = AlertDialog.Builder(this).setTitle(if (model.requesting) "Cartas de tu amigo" else "Cartas de tu colección").setView(box).setNegativeButton("Cancelar", null).create()
        search.doAfterTextChanged { text ->
            val terms = normalize(text.toString()).split(" ")
            rows = cards.filter { card -> terms.all { it in card.search } }
            adapter.clear(); adapter.addAll(rows.map { loanCardLabel(it) })
        }
        list.setOnItemClickListener { _, _, position, _ -> dialog.dismiss(); quantity(rows[position]) }
        dialog.show()
    }
    private fun quantity(card: LorcanaCard) {
        val finishes = listOf(false, true).filter { collection.count(card, it) > 0 }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 8, 32, 8) }
        val finish = Spinner(this).apply { adapter = ArrayAdapter(this@Prestamos, android.R.layout.simple_spinner_dropdown_item,
            finishes.map { "${if (it) "Foil" else "Normal"} · ${if (model.requesting) "${collection.count(card, it)} en la colección" else model.availability(card.id, it, collection.count(card, it))}" }) }
        val amount = EditText(this).apply { hint = "Cantidad"; inputType = InputType.TYPE_CLASS_NUMBER; setText("1") }
        box.addView(finish); box.addView(amount)
        val dialog = AlertDialog.Builder(this).setTitle(card.name).setView(box).setPositiveButton("Añadir", null).setNegativeButton("Cancelar", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val foil = finishes.getOrNull(finish.selectedItemPosition) ?: return@setOnClickListener
                val qty = amount.text.toString().toIntOrNull()
                if (qty == null || qty !in 1..collection.count(card, foil)) { amount.error = "Indica una cantidad entre 1 y ${collection.count(card, foil)}"; return@setOnClickListener }
                val key = CollectionStore.key(card.id, foil)
                if (model.draft.value.orEmpty().size >= 10 && model.draft.value.orEmpty().none { it.key == key }) { toast("Máximo 10 cartas/acabados distintos por préstamo."); return@setOnClickListener }
                model.put(LoanItem(card.id, card.name, card.identifier, foil, qty)); dialog.dismiss()
            }
        }
        dialog.show()
    }
    override fun onStart() { super.onStart(); if (::listAdapter.isInitialized && Session.ready()) collection.openSharedView() }
    override fun onStop() { if (::listAdapter.isInitialized) collection.closeSharedView(); super.onStop() }
    private fun requestStatus(value: String) = when(value) {
        "pending" -> "Pendiente"; "accepted" -> "Aceptada"; "rejected" -> "Rechazada"; else -> "Cancelada"
    }
    private fun requestDetails(request: LoanRequest) {
        val builder = AlertDialog.Builder(this).setTitle("@${request.borrowerName} pide a @${request.lenderName}")
            .setMessage("${requestStatus(request.status)}\n\n" + request.items.joinToString("\n\n") { it.label() })
            .setNegativeButton("Volver", null)
        if (request.status == "pending" && model.busy.value != true) {
            if (request.lenderUid == model.uid) {
                builder.setPositiveButton("Aceptar") { _, _ ->
                    AlertDialog.Builder(this).setTitle("¿Aceptar y crear el préstamo?")
                        .setMessage("Se registrarán estas cartas como prestadas a @${request.borrowerName}. Las cantidades de la colección no cambian.")
                        .setPositiveButton("Aceptar") { _, _ -> model.respond(request, "accepted") }
                        .setNegativeButton("Volver", null).show()
                }.setNeutralButton("Rechazar") { _, _ -> model.respond(request, "rejected") }
            } else builder.setNeutralButton("Cancelar solicitud") { _, _ -> model.respond(request, "cancelled") }
        }
        builder.show()
    }
    private fun details(loan: Loan) {
        val builder = AlertDialog.Builder(this).setTitle("@${loan.lenderName} → @${loan.borrowerName}")
            .setMessage("Creado: ${date(loan.createdAt)}" + (if (loan.closed) "\nDevuelto: ${date(loan.closedAt)}" else "\nPendiente de devolución") +
                "\n\n" + loan.items.mapIndexed { i, item ->
                    "${item.name} · ${item.identifier} · ${if (item.foil) "Foil" else "Normal"}\nPrestadas: ${item.quantity} · Devueltas: ${item.quantity - loan.remaining[i]} · Pendientes: ${loan.remaining[i]}" +
                        if (loan.lenderUid == model.uid && !collection.readOnly)
                            "\nTu colección: ${model.availability(item.cardId, item.foil, collection.counts.value?.get(item.key) ?: 0)}" else ""
                }.joinToString("\n\n")).setNegativeButton("Volver", null)
        if (!loan.closed && loan.lenderUid == model.uid && model.busy.value != true) builder.setPositiveButton("Registrar devolución") { _, _ ->
            AlertDialog.Builder(this).setTitle("¿Has recibido todas las cartas?")
                .setMessage("El préstamo se cerrará y pasará a Históricos para ambos.")
                .setPositiveButton("Sí, cerrar préstamo") { _, _ -> model.close(loan) }.setNegativeButton("Cancelar", null).show()
        }
        if (!loan.closed && loan.lenderUid == model.uid && model.busy.value != true) {
            builder.setNeutralButton("Editar pendientes") { _, _ -> editRemaining(loan) }
        }
        builder.show()
    }
    private fun editRemaining(loan: Loan) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        box.addView(TextView(this).apply {
            text = "Indica cuántas copias siguen prestadas de cada carta. Pon 0 si se devolvieron todas. Puedes corregir cantidades hasta el total original mientras el préstamo siga activo."
        })
        val inputs = loan.items.mapIndexed { i, item ->
            box.addView(TextView(this).apply {
                text = "${item.name} · ${item.identifier} · ${if (item.foil) "Foil" else "Normal"}\nTotal prestado: ${item.quantity}"
                setPadding(0, (16 * resources.displayMetrics.density).toInt(), 0, 0)
            })
            EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                hint = "Copias pendientes"
                setText(loan.remaining[i].toString())
                box.addView(this)
            }
        }
        val scroll = ScrollView(this).apply { addView(box) }
        val dialog = AlertDialog.Builder(this).setTitle("Editar cartas pendientes")
            .setView(scroll).setPositiveButton("Revisar cambios", null).setNegativeButton("Cancelar", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                var valid = true
                val counts = inputs.mapIndexed { i, input ->
                    val number = input.text.toString().toIntOrNull()
                    if (number == null || number !in 0..loan.items[i].quantity) {
                        input.error = "Entre 0 y ${loan.items[i].quantity}"; valid = false
                    }
                    number ?: 0
                }
                if (!valid) return@setOnClickListener
                if (counts == loan.remaining) { dialog.dismiss(); return@setOnClickListener }
                AlertDialog.Builder(this).setTitle("Confirmar devolución")
                    .setMessage(loan.items.mapIndexed { i, item -> "${item.name}: ${counts[i]} pendientes de ${item.quantity}" }.joinToString("\n") +
                        if (counts.all { it == 0 }) "\n\nSe cerrará el préstamo y pasará a Históricos." else "\n\nAmbos veréis las cantidades actualizadas.")
                    .setPositiveButton("Guardar") { _, _ -> model.updateRemaining(loan, counts); dialog.dismiss() }
                    .setNegativeButton("Seguir editando", null).show()
            }
        }
        dialog.show()
    }
    private fun date(value: com.google.firebase.Timestamp?) = value?.toDate()?.let { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(it) } ?: "Pendiente de sincronizar"
    private fun toast(message: String) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
}

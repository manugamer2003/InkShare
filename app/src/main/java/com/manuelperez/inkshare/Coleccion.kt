package com.manuelperez.inkshare

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import coil.load

class Coleccion : AppCompatActivity() {
    private val model: CollectionViewModel by viewModels()
    private val loans: LoansViewModel by viewModels()
    private lateinit var adapter: CardsAdapter
    private var filters = CardFilters()
    private var filtersExpanded = false
    private var candidateDialog: AlertDialog? = null
    private val gallery = registerForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let(model::scan) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!requireProfile()) return
        enableEdgeToEdge()
        setContentView(R.layout.activity_coleccion)
        applyInsets()
        if (model.readOnly) {
            findViewById<TextView>(R.id.collectionTitle).text = "Colección de @${model.ownerName}"
            findViewById<View>(R.id.scanCamera).visibility = View.GONE
            findViewById<View>(R.id.scanGallery).visibility = View.GONE
            findViewById<CheckBox>(R.id.onlyOwned).apply { isChecked = true; visibility = View.GONE }
            findViewById<TextView>(R.id.empty).text = "No hay cartas para mostrar. Revisa los filtros y el estado del acceso."
        }
        filters = CardFilters(
            savedInstanceState?.getStringArrayList("filterColors")?.toSet().orEmpty(),
            savedInstanceState?.getStringArrayList("filterSets")?.toSet().orEmpty(),
            savedInstanceState?.getStringArrayList("filterTypes")?.toSet().orEmpty()
        )
        filtersExpanded = savedInstanceState?.getBoolean("filtersExpanded") ?: false
        adapter = CardsAdapter()
        findViewById<Button>(R.id.toggleFilters).setOnClickListener {
            filtersExpanded = !filtersExpanded
            updateFilterHeader()
        }
        findViewById<Button>(R.id.clearFilters).setOnClickListener {
            filters = CardFilters()
            buildFilterOptions()
            refresh()
        }
        updateFilterHeader()
        findViewById<ListView>(R.id.cardList).apply {
            adapter = this@Coleccion.adapter
            setOnItemClickListener { _, _, position, _ -> edit(this@Coleccion.adapter.rows[position]) }
            setOnItemLongClickListener { _, _, position, _ ->
                showLargeCard(this@Coleccion.adapter.rows[position])
                true
            }
        }
        findViewById<EditText>(R.id.search).doAfterTextChanged { refresh() }
        findViewById<CheckBox>(R.id.onlyOwned).setOnCheckedChangeListener { _, _ -> refresh() }
        findViewById<Button>(R.id.scanGallery).setOnClickListener { gallery.launch("image/*") }
        findViewById<Button>(R.id.scanCamera).setOnClickListener {
            startActivity(android.content.Intent(this, LiveScanner::class.java))
        }
        model.cards.observe(this) { buildFilterOptions(); refresh() }
        model.counts.observe(this) { refresh() }
        if (!model.readOnly) loans.lentCounts.observe(this) { refresh() }
        model.message.observe(this) { findViewById<TextView>(R.id.status).text = it }
        model.syncStatus.observe(this) { findViewById<TextView>(R.id.syncStatus).text = it }
        model.busy.observe(this) { busy ->
            findViewById<ProgressBar>(R.id.progress).visibility = if (busy) View.VISIBLE else View.GONE
            val enabled = !busy && !model.cards.value.isNullOrEmpty()
            findViewById<Button>(R.id.scanCamera).isEnabled = enabled
            findViewById<Button>(R.id.scanGallery).isEnabled = enabled
        }
        model.candidates.observe(this) { matches ->
            if (matches != null && candidateDialog == null) {
                candidateDialog = AlertDialog.Builder(this).setTitle("Confirma nombre y edición")
                    .setItems(matches.map { "${it.name}\n${it.identifier}" }.toTypedArray()) { _, which -> edit(matches[which]) }
                    .setNegativeButton("Cerrar", null).create().also { dialog ->
                        if (matches.isEmpty()) dialog.setMessage("No se encontraron coincidencias. Prueba otra fotografía o busca manualmente.")
                        dialog.setOnDismissListener { candidateDialog = null; if (!isChangingConfigurations) model.candidates.value = null }
                        dialog.show()
                    }
            }
        }
    }
    override fun onStart() {
        super.onStart()
        if (::adapter.isInitialized && Session.ready()) model.openSharedView()
    }
    override fun onStop() {
        if (::adapter.isInitialized) model.closeSharedView()
        super.onStop()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putStringArrayList("filterColors", ArrayList(filters.colors))
        outState.putStringArrayList("filterSets", ArrayList(filters.sets))
        outState.putStringArrayList("filterTypes", ArrayList(filters.types))
        outState.putBoolean("filtersExpanded", filtersExpanded)
        super.onSaveInstanceState(outState)
    }
    private fun updateFilterHeader() {
        findViewById<View>(R.id.filterPanel).visibility = if (filtersExpanded) View.VISIBLE else View.GONE
        findViewById<Button>(R.id.toggleFilters).text =
            "${if (filtersExpanded) "Ocultar filtros ▴" else "Filtros ▾"} (${filters.count})"
        findViewById<Button>(R.id.clearFilters).isEnabled = filters.count > 0
    }
    private fun buildFilterOptions() {
        val container = findViewById<LinearLayout>(R.id.filterOptions)
        container.removeAllViews()
        val cards = model.cards.value.orEmpty()
        fun group(title: String, options: List<Pair<String, String>>, selected: Set<String>, change: (Set<String>) -> Unit) {
            container.addView(TextView(this).apply {
                text = title
                textSize = 18f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(0, (12 * resources.displayMetrics.density).toInt(), 0, 0)
                androidx.core.view.ViewCompat.setAccessibilityHeading(this, true)
            })
            val selection = selected.toMutableSet()
            options.forEach { (key, label) ->
                container.addView(CheckBox(this).apply {
                    text = label
                    minHeight = (48 * resources.displayMetrics.density).toInt()
                    isChecked = key in selected
                    setOnCheckedChangeListener { _, checked ->
                        if (checked) selection.add(key) else selection.remove(key)
                        change(selection.toSet())
                        refresh()
                    }
                })
            }
        }
        val colorLabels = mapOf("Amber" to "Ámbar", "Amethyst" to "Amatista", "Emerald" to "Esmeralda",
            "Ruby" to "Rubí", "Sapphire" to "Zafiro", "Steel" to "Acero", "" to "Sin color")
        val colors = cards.flatMap { it.colors.ifEmpty { setOf("") } }.distinct()
        group("Colores", colors.sortedBy { colorLabels[it] ?: it }.map { it to (colorLabels[it] ?: it) }, filters.colors) {
            filters = filters.copy(colors = it)
        }
        val sets = cards.distinctBy { it.set }.sortedWith(compareBy<LorcanaCard> { it.set.toIntOrNull() ?: Int.MAX_VALUE }.thenBy { it.set })
        group("Colecciones", sets.map { it.set to "${it.set} · ${it.setName}" }, filters.sets) {
            filters = filters.copy(sets = it)
        }
        val typeLabels = mapOf("Character" to "Personaje", "Action" to "Acción", "Item" to "Objeto", "Location" to "Lugar", "" to "Sin tipo")
        group("Tipos de carta", cards.map { it.type }.distinct().sorted().map { it to (typeLabels[it] ?: it) }, filters.types) {
            filters = filters.copy(types = it)
        }
        updateFilterHeader()
    }
    private fun refresh() {
        if (!::adapter.isInitialized) return
        val query = normalize(findViewById<EditText>(R.id.search).text.toString())
        val onlyOwned = model.readOnly || findViewById<CheckBox>(R.id.onlyOwned).isChecked
        adapter.rows = model.cards.value.orEmpty().filter { card ->
            filters.matches(card) && query.split(" ").all { it in card.search } && (!onlyOwned || model.count(card, false) + model.count(card, true) > 0 || (!model.readOnly && listOf(false, true).any { (loans.lentCounts.value?.get(CollectionStore.key(card.id, it)) ?: 0) > 0 }))
        }
        adapter.notifyDataSetChanged()
        updateFilterHeader()
        findViewById<TextView>(R.id.summary).text = "${model.counts.value.orEmpty().values.sum()} copias · ${adapter.rows.size} cartas visibles"
        findViewById<View>(R.id.empty).visibility = if (adapter.rows.isEmpty() && model.busy.value != true) View.VISIBLE else View.GONE
    }
    private fun edit(card: LorcanaCard) {
        if (model.readOnly) {
            showLargeCard(card)
            return
        }
        if (model.busy.value == true) return
        cardQuantityDialog(card, model, loans = loans)
    }
    private fun showLargeCard(card: LorcanaCard) {
        val content = layoutInflater.inflate(R.layout.dialog_card_preview, null)
        val picture = content.findViewById<ImageView>(R.id.largeCardImage)
        val loading = content.findViewById<TextView>(R.id.largeCardStatus)
        content.findViewById<TextView>(R.id.largeCardDetails).text =
            "${card.identifier} · ${card.setName}\nPellizca para ampliar · Doble toque para acercar\nNormal: ${model.count(card, false)} · Foil: ${model.count(card, true)}"
        picture.contentDescription = "${card.name}, ${card.identifier}"
        // Bound the scroll area to the current window, including split-screen devices.
        val root = findViewById<View>(R.id.main)
        val availableHeight = root.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        content.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (availableHeight * 0.70f).toInt())
        val builder = AlertDialog.Builder(this).setTitle(card.name).setView(content)
            .setPositiveButton("Cerrar", null)
        if (model.readOnly) builder.setNeutralButton("Pedir préstamo") { _, _ ->
                startActivity(android.content.Intent(this, Prestamos::class.java)
                    .putExtra("collectionOwnerUid", intent.getStringExtra("collectionOwnerUid"))
                    .putExtra("collectionOwnerName", model.ownerName)
                    .putExtra("requestedCardId", card.id).putExtra("requestedCardName", card.name)
                    .putExtra("requestedCardIdentifier", card.identifier).putExtra("requestedFoil", model.count(card, false) == 0))
            }
        val dialog = builder.create()
        dialog.show()
        val availableWidth = root.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        dialog.window?.setLayout((availableWidth * 0.95f).toInt(), (availableHeight * 0.90f).toInt())
        val request = picture.load(card.fullImage) {
            size(coil.size.Size.ORIGINAL)
            crossfade(true)
            listener(
                onSuccess = { _, _ -> loading.visibility = View.GONE },
                onError = { _, _ -> loading.text = "No se pudo cargar la imagen. Comprueba tu conexión y vuelve a abrir la carta." }
            )
        }
        dialog.setOnDismissListener { request.dispose() }
    }
    private inner class CardsAdapter : BaseAdapter() {
        var rows: List<LorcanaCard> = emptyList()
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = rows[position].id.toLong()
        override fun hasStableIds() = true
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView ?: LayoutInflater.from(this@Coleccion).inflate(R.layout.row_card, parent, false)
            val card = rows[position]
            row.findViewById<TextView>(R.id.cardRowHint).text = if (model.readOnly) "Toca para ampliar la carta" else "Toca para editar · Mantén pulsado para ampliar"
            row.findViewById<TextView>(R.id.cardName).text = card.name
            row.findViewById<TextView>(R.id.cardIdentifier).text = card.identifier
            row.findViewById<TextView>(R.id.cardCounts).text = if (model.readOnly)
                "Normal: ${model.count(card, false)} · Foil: ${model.count(card, true)}"
            else listOf(false, true).filter { if (it) card.hasFoil else card.hasNormal }.joinToString("\n") {
                "${if (it) "Foil" else "Normal"}: ${loans.availability(card.id, it, model.count(card, it))}"
            }
            row.findViewById<ImageView>(R.id.cardImage).load(card.image) {
                crossfade(true)
                placeholder(R.drawable.home_collection)
                error(R.drawable.home_collection)
            }
            return row
        }
    }
}

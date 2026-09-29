package com.manuelperez.inkshare

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.UUID

class BatchScanViewModel(private val state: SavedStateHandle) : ViewModel() {
    val enabled = state.getLiveData("batchEnabled", false)
    val quantities = state.getLiveData<HashMap<String, Int>>("batchCounts", hashMapOf())
    val locked = state.getLiveData("batchLocked", false)
    val busy = MutableLiveData(false)
    val message = MutableLiveData("")
    val gate = BatchScanGate()
    private val owner = state.get<String>("batchOwner") ?: Session.uid().also { state["batchOwner"] = it }
    private var batchId: String
        get() = state.get<String>("batchId") ?: UUID.randomUUID().toString().also { state["batchId"] = it }
        set(value) { state["batchId"] = value }
    fun setQuantity(card: LorcanaCard, foil: Boolean, quantity: Int) {
        if (busy.value == true || locked.value == true) return
        require(quantity in 0..9999)
        require(if (foil) card.hasFoil else card.hasNormal)
        val key = CollectionEntry(card.id, foil, quantity).key
        val copy = HashMap(quantities.value.orEmpty())
        require(quantity == 0 || copy.containsKey(key) || copy.size < 100) { "Máximo de 100 cartas/acabados distintos por lote" }
        if (quantity == 0) copy.remove(key) else copy[key] = quantity
        quantities.value = copy
    }
    fun add(card: LorcanaCard, foil: Boolean) {
        try {
            val key = CollectionEntry(card.id, foil, 0).key
            setQuantity(card, foil, (quantities.value?.get(key) ?: 0) + 1)
            message.value = "${card.name} añadida al lote. Cambia la carta: el escaneo se reinicia automáticamente a los 2,5 segundos."
        } catch (e: Exception) { message.value = e.localizedMessage }
    }
    fun save(cards: List<LorcanaCard>) {
        if (busy.value == true || quantities.value.isNullOrEmpty()) return
        busy.value = true
        locked.value = true
        val values = quantities.value.orEmpty().toMap()
        val id = batchId
        viewModelScope.launch {
            try {
                check(Session.uid() == owner) { "La sesión ha cambiado" }
                CollectionRepository(owner).addScanBatch(id, values, cards)
                quantities.value = hashMapOf()
                batchId = UUID.randomUUID().toString()
                locked.value = false
                message.value = "Lote guardado en el móvil: ${values.values.sum()} cartas. Se sincronizará automáticamente."
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (e is IllegalArgumentException) {
                    locked.value = false
                    message.value = "No se guardó el lote: ${e.localizedMessage}. Ajusta las cantidades y vuelve a intentarlo."
                } else message.value = "No se pudo confirmar el guardado. Conservamos el lote: pulsa Revisar lote para reintentar sin duplicarlo. ${e.localizedMessage}"
            } finally { busy.value = false }
        }
    }
}

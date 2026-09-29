package com.manuelperez.inkshare

import android.app.Application
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.google.firebase.firestore.ListenerRegistration
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class CollectionViewModel(app: Application, state: SavedStateHandle) : AndroidViewModel(app) {
    private val ownerUid = state.get<String>("collectionOwnerUid")?.takeIf { it.isNotBlank() } ?: Session.uid()
    val readOnly = ownerUid != Session.uid()
    val ownerName = state.get<String>("collectionOwnerName").orEmpty()
    private var viewing = false
    val cards = MutableLiveData<List<LorcanaCard>>(emptyList())
    val counts = MutableLiveData<Map<String, Int>>(emptyMap())
    val message = MutableLiveData("Cargando catálogo…")
    val syncStatus = MutableLiveData("Conectando con tu colección en Firebase…")
    val busy = MutableLiveData(false)
    val candidates = MutableLiveData<List<LorcanaCard>?>(null)
    private val repository = CollectionRepository(ownerUid)
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private var listener: ListenerRegistration? = null
    init {
        viewModelScope.launch {
            busy.value = true
            try {
                cards.value = withContext(Dispatchers.IO) {
                    app.assets.open("allCardsLorcana.json").bufferedReader().use { CardCatalog.parse(it.readText()) }
                }
                message.value = "${cards.value!!.size} cartas · nombres en inglés"
                if (!readOnly || viewing) startListening()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { message.value = "Error al cargar: ${e.localizedMessage}" }
            finally { busy.value = false }
        }
    }
    private fun startListening() {
        if (listener != null) return
        listener = repository.listen({ values, cached ->
            if (readOnly && !viewing) return@listen
            // Never display cached private data without a fresh server authorization.
            counts.value = if (readOnly && cached) emptyMap() else values
            syncStatus.value = when {
                readOnly && cached -> "Conecta a Internet para comprobar que sois amigos mutuos."
                readOnly -> "Colección de @$ownerName · Solo lectura"
                cached -> "Vista guardada en caché. Se necesita conexión para cambiar cantidades."
                else -> "Colección sincronizada con Firebase"
            }
        }, { error ->
            if (readOnly) counts.value = emptyMap()
            syncStatus.value = if (readOnly) "No se puede ver esta colección. Debéis teneros añadidos mutuamente y estar conectados. Comprueba también las reglas de Firebase."
                else "Error al sincronizar: ${error.localizedMessage}. Comprueba la conexión y las reglas de Firestore."
        })
    }
    fun openSharedView() {
        if (!readOnly) return
        viewing = true
        if (!cards.value.isNullOrEmpty()) startListening()
    }
    fun closeSharedView() {
        if (!readOnly) return
        viewing = false
        listener?.remove(); listener = null
        counts.value = emptyMap()
    }
    fun count(card: LorcanaCard, foil: Boolean) = counts.value.orEmpty()[CollectionStore.key(card.id, foil)] ?: 0
    fun change(card: LorcanaCard, foil: Boolean, delta: Int) {
        if (readOnly || busy.value == true) return
        busy.value = true
        viewModelScope.launch {
            try {
                repository.change(card, foil, delta)
                message.value = "Cambio guardado en Firebase."
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { message.value = "No se pudo confirmar el cambio en Firebase: ${e.localizedMessage}. Revisa la conexión antes de reintentar." }
            finally { busy.value = false }
        }
    }
    fun scan(uri: Uri) {
        if (readOnly || busy.value == true || cards.value.isNullOrEmpty()) return
        busy.value = true
        message.value = "Leyendo la fotografía…"
        viewModelScope.launch {
            try {
                val image = withContext(Dispatchers.IO) { InputImage.fromFilePath(getApplication(), uri) }
                val text = recognizer.process(image).await().text
                val catalogue = cards.value.orEmpty()
                candidates.value = withContext(Dispatchers.Default) { CardCatalog.match(catalogue, text) }
                message.value = if (candidates.value.isNullOrEmpty()) "Sin coincidencias. Prueba otra foto o busca por nombre." else "Verifica la edición y el acabado antes de añadir."
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { message.value = "No se pudo leer la foto: ${e.localizedMessage}" }
            finally { busy.value = false }
        }
    }
    override fun onCleared() { listener?.remove(); recognizer.close(); super.onCleared() }
}

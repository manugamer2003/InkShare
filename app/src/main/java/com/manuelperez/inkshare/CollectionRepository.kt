package com.manuelperez.inkshare

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Source
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import java.util.UUID

class CollectionRepository(private val uid: String) {
    private val db = FirebaseFirestore.getInstance()
    private val collection = db.collection("users").document(uid).collection("collection")
    private val viewerUid = Session.uid()
    private fun checkOwner() { checkAccount(); check(uid == viewerUid) { "Esta colección es de solo lectura" } }
    private fun checkAccount() { check(FirebaseAuth.getInstance().currentUser?.uid == viewerUid) { "La sesión ha cambiado. Vuelve a entrar." } }
    private fun decode(doc: DocumentSnapshot): CollectionEntry {
        val id = requireNotNull(doc.getLong("cardId"))
        val quantity = requireNotNull(doc.getLong("quantity"))
        require(id in 1..Int.MAX_VALUE.toLong() && quantity in 0L..9999L) { "Datos de colección no válidos" }
        val entry = CollectionEntry(id.toInt(), requireNotNull(doc.getBoolean("foil")), quantity.toInt())
        require(doc.id == entry.key) { "Identificador de colección no válido" }
        return entry
    }
    private fun fields(entry: CollectionEntry) = mapOf("cardId" to entry.cardId, "foil" to entry.foil,
        "quantity" to entry.quantity, "updatedAt" to FieldValue.serverTimestamp())
    fun listen(onData: (Map<String, Int>, Boolean) -> Unit, onError: (Exception) -> Unit): ListenerRegistration {
        checkAccount()
        if (uid == viewerUid) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val store = CollectionSync.account(uid)
            scope.launch { store.load() }
            scope.launch {
                store.updates.collect { value ->
                    if (FirebaseAuth.getInstance().currentUser?.uid == uid) {
                        if (value.ready) onData(value.counts, value.pending > 0)
                        else if (!value.text.startsWith("Leyendo")) onError(IllegalStateException(value.text))
                    }
                }
            }
            return ListenerRegistration { scope.cancel() }
        }
        return collection.addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
            if (FirebaseAuth.getInstance().currentUser?.uid != viewerUid) return@addSnapshotListener
            if (error != null) onError(error)
            else if (snapshot != null) {
                try {
                    val values = snapshot.documents.map(::decode).filter { it.quantity > 0 }.associate { it.key to it.quantity }
                    onData(values, snapshot.metadata.isFromCache)
                } catch (e: Exception) { onError(e) }
            }
        }
    }
    suspend fun change(card: LorcanaCard, foil: Boolean, delta: Int) {
        checkOwner()
        require(if (foil) card.hasFoil else card.hasNormal) { "Acabado no disponible" }
        require(delta == -1 || delta == 1)
        CollectionSync.account(uid).enqueue("offline_" + UUID.randomUUID().toString(),
            mapOf(CollectionEntry(card.id, foil, 0).key to delta))
    }
    suspend fun addScanBatch(batchId: String, values: Map<String, Int>, cards: List<LorcanaCard>) {
        checkOwner()
        require(values.isNotEmpty() && values.size <= 100)
        val catalog = cards.associateBy { it.id }
        val entries = values.map { (key, count) ->
            CollectionEntry.fromKey(key, count).also {
                require(it.quantity > 0)
                val card = requireNotNull(catalog[it.cardId])
                require(if (it.foil) card.hasFoil else card.hasNormal)
            }
        }
        CollectionSync.account(uid).enqueue(batchId, entries.associate { it.key to it.quantity })
    }
    suspend fun serverCounts(): Map<String, Int> {
        checkAccount()
        if (uid == viewerUid) CollectionSync.account(uid).synchronize()
        val values = collection.get(Source.SERVER).await().documents.map(::decode).filter { it.quantity > 0 }.associate { it.key to it.quantity }
        if (uid == viewerUid) check(!CollectionSync.account(uid).hasPending()) {
            "Hay cambios nuevos pendientes. Espera a que termine la sincronización antes de crear la copia."
        }
        return values
    }
    // Each entry imports idempotently. A retry after a partial interruption cannot add duplicates.
    suspend fun mergePrevious(values: Map<String, Int>, cards: List<LorcanaCard>, progress: (Int, Int) -> Unit) {
        checkOwner()
        CollectionSync.account(uid).synchronize()
        val catalogue = cards.associateBy { it.id }
        val entries = values.map { (key, quantity) ->
            CollectionEntry.fromKey(key, quantity).also { entry ->
                val card = requireNotNull(catalogue[entry.cardId]) { "La copia contiene una carta desconocida" }
                require(if (entry.foil) card.hasFoil else card.hasNormal) { "La copia contiene un acabado no válido" }
            }
        }.filter { it.quantity > 0 }
        entries.forEachIndexed { index, incoming ->
            checkAccount()
            val ref = collection.document(incoming.key)
            db.runTransaction { tx ->
                val doc = tx.get(ref)
                val current = if (doc.exists()) decode(doc).quantity else 0
                if (incoming.quantity > current) tx.set(ref, fields(incoming))
            }.await()
            progress(index + 1, entries.size)
        }
    }
}

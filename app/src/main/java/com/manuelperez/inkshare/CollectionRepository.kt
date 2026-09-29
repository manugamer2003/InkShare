package com.manuelperez.inkshare

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Source
import kotlinx.coroutines.tasks.await

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
        val ref = collection.document(CollectionEntry(card.id, foil, 0).key)
        db.runTransaction { tx ->
            val document = tx.get(ref)
            val current = if (document.exists()) decode(document).quantity else 0
            val next = CollectionEntry.changed(current, delta)
            if (next == 0) tx.delete(ref) else tx.set(ref, fields(CollectionEntry(card.id, foil, next)))
        }.await()
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
        val receipt = db.document("users/$uid/scanBatches/$batchId")
        db.runTransaction { tx ->
            // Receipt makes retries safe even if the server committed but the reply was lost.
            if (!tx.get(receipt).exists()) {
                val updated = entries.map { entry ->
                    val doc = tx.get(collection.document(entry.key))
                    val current = if (doc.exists()) decode(doc).quantity else 0
                    require(current + entry.quantity <= 9999) { "Una carta supera el máximo de 9999 copias" }
                    entry.copy(quantity = current + entry.quantity)
                }
                updated.forEach { tx.set(collection.document(it.key), fields(it)) }
                tx.set(receipt, mapOf("createdAt" to FieldValue.serverTimestamp()))
            }
        }.await()
    }
    suspend fun serverCounts(): Map<String, Int> {
        checkAccount()
        return collection.get(Source.SERVER).await().documents.map(::decode).filter { it.quantity > 0 }.associate { it.key to it.quantity }
    }
    // Each entry imports idempotently. A retry after a partial interruption cannot add duplicates.
    suspend fun mergePrevious(values: Map<String, Int>, cards: List<LorcanaCard>, progress: (Int, Int) -> Unit) {
        checkOwner()
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

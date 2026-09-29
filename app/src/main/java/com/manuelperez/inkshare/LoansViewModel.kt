package com.manuelperez.inkshare

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.MetadataChanges
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

data class LoanItem(val cardId: Int, val name: String, val identifier: String, val foil: Boolean, val quantity: Int) {
    val key get() = CollectionStore.key(cardId, foil)
    fun fields() = mapOf("cardId" to cardId, "name" to name, "identifier" to identifier, "foil" to foil, "quantity" to quantity)
    fun label() = "$quantity × $name · $identifier · ${if (foil) "Foil" else "Normal"}"
}
data class Loan(val id: String, val lenderUid: String, val lenderName: String, val borrowerName: String,
    val items: List<LoanItem>, val closed: Boolean, val createdAt: Timestamp?, val closedAt: Timestamp?,
    val remaining: List<Int> = items.map { if (closed) 0 else it.quantity }, val revision: Long = 0)

data class LoanRequest(val id: String, val lenderUid: String, val lenderName: String,
    val borrowerUid: String, val borrowerName: String, val items: List<LoanItem>, val status: String)

class LoansViewModel : ViewModel() {
    val uid = Session.uid()
    private val db = FirebaseFirestore.getInstance()
    private val collection = db.collection("loans")
    val loans = MutableLiveData<List<Loan>>(emptyList())
    val lentCounts = MutableLiveData<Map<String, Int>?>(null)
    var availabilityCached = false
        private set
    fun availability(cardId: Int, foil: Boolean, total: Int): String =
        LoanAvailability.label(total, lentCounts.value?.get(CollectionStore.key(cardId, foil)) ?: if (lentCounts.value != null) 0 else null) +
            if (availabilityCached && lentCounts.value != null) " (datos guardados)" else ""

    val busy = MutableLiveData(false)
    val status = MutableLiveData("Cargando préstamos…")
    val draft = MutableLiveData<List<LoanItem>>(emptyList())
    val friend = MutableLiveData<Friend?>(null)
    var composing = false
    var history = false
    var tab = 0
    var requesting = false
    val requests = MutableLiveData<List<LoanRequest>>(emptyList())
    private val requestCollection = db.collection("loanRequests")
    private val requestListener = requestCollection.whereArrayContains("participants", uid).addSnapshotListener { snapshot, error ->
        if (error != null) { requests.value = emptyList(); status.value = "No se pueden cargar las solicitudes de préstamo. Publica las reglas actualizadas y comprueba la conexión." }
        else if (snapshot != null) {
            try {
                requests.value = snapshot.documents.sortedByDescending { it.getTimestamp("createdAt")?.seconds ?: 0 }.map { d ->
                    val items = (d.get("items") as List<*>).map { raw ->
                        val item = raw as Map<*, *>
                        LoanItem((item["cardId"] as Number).toInt(), item["name"] as String,
                            item["identifier"] as String, item["foil"] as Boolean, (item["quantity"] as Number).toInt())
                    }
                    LoanRequest(d.id, d.getString("lenderUid")!!, d.getString("lenderName")!!,
                        d.getString("borrowerUid")!!, d.getString("borrowerName")!!, items, d.getString("status")!!)
                }
            } catch (e: Exception) { requests.value = emptyList(); status.value = "Hay una solicitud con datos no válidos." }
        }
    }
    private var draftId = collection.document().id
    private val listener = collection.whereArrayContains("participants", uid)
        .addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
            if (error != null) { lentCounts.value = null; status.value = "No se pueden consultar los préstamos. Comprueba la conexión y publica las reglas de Firestore actualizadas." }
            else if (snapshot != null) {
                try {
                    loans.value = snapshot.documents.map { d ->
                        val items = (d.get("items") as List<*>).map { raw ->
                            val item = raw as Map<*, *>
                            LoanItem((item["cardId"] as Number).toInt(), item["name"] as String,
                                item["identifier"] as String, item["foil"] as Boolean, (item["quantity"] as Number).toInt())
                        }
                        Loan(d.id, d.getString("lenderUid")!!, d.getString("lenderName")!!,
                            d.getString("borrowerName")!!, items, d.getString("status") == "closed",
                            d.getTimestamp("createdAt"), d.getTimestamp("closedAt"),
                            if (d.getString("status") == "closed") items.map { 0 }
                            else (d.get("remaining") as? List<*>)?.map { (it as Number).toInt() } ?: items.map { it.quantity },
                            d.getLong("revision") ?: 0)
                    }.sortedByDescending { (if (it.closed) it.closedAt else it.createdAt)?.seconds ?: 0 }
                    availabilityCached = snapshot.metadata.isFromCache
                    lentCounts.value = LoanAvailability.outstanding(uid, loans.value.orEmpty())
                    status.value = if (snapshot.metadata.isFromCache) "Vista en caché. Necesitas conexión para crear o cerrar préstamos." else "Préstamos sincronizados con Firebase."
                } catch (e: Exception) { lentCounts.value = null; status.value = "Hay un préstamo con datos no válidos." }
            }
        }
    fun put(item: LoanItem) {
        if (busy.value == true) return
        val rows = draft.value.orEmpty().filterNot { it.key == item.key }
        require(rows.size < 10 && item.quantity in 1..9999)
        draft.value = rows + item
    }
    fun remove(item: LoanItem) { if (busy.value != true) draft.value = draft.value.orEmpty().filterNot { it.key == item.key } }
    fun discard() {
        if (busy.value == true) return
        draft.value = emptyList(); friend.value = null; composing = false; draftId = collection.document().id
    }
    private fun action(block: suspend () -> Unit) {
        if (busy.value == true) return
        busy.value = true
        viewModelScope.launch {
            try {
                check(Session.uid() == uid) { "La sesión ha cambiado" }
                block()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { status.value = "No se confirmó la operación: ${e.localizedMessage}. Revisa la conexión y las reglas; puedes reintentar." }
            finally { busy.value = false }
        }
    }
    fun create() = action {
        val target = requireNotNull(friend.value) { "Elige un amigo" }
        val items = draft.value.orEmpty().toList()
        require(target.uid != uid && items.isNotEmpty() && items.size <= 10)
        val ref = collection.document(draftId)
        db.runTransaction { tx ->
            // A retry with the same draft id cannot create a second loan.
            val existing = tx.get(ref)
            if (!existing.exists()) {
                val currentFriend = tx.get(db.document("users/$uid/friends/${target.uid}"))
                require(currentFriend.getString("username") == target.username) { "El usuario ya no está en tu lista de amigos" }
                val owned = items.map { tx.get(db.document("users/$uid/collection/${it.key}")) }
                items.zip(owned).forEach { (item, doc) ->
                    require(item.quantity in 1..9999 && (doc.getLong("quantity") ?: 0) >= item.quantity) { "No tienes suficientes copias de ${item.name}" }
                }
                tx.set(ref, mapOf("lenderUid" to uid, "lenderName" to Session.username,
                    "borrowerUid" to target.uid, "borrowerName" to target.username,
                    "participants" to listOf(uid, target.uid), "items" to items.map { it.fields() },
                    "status" to "active", "createdAt" to FieldValue.serverTimestamp(), "closedAt" to null))
            }
        }.await()
        draft.value = emptyList(); friend.value = null; composing = false; draftId = collection.document().id
        status.value = "Préstamo creado. Tú y @${target.username} podéis consultarlo."
    }
    fun sendRequest() = action {
        val target = requireNotNull(friend.value) { "Elige un amigo" }
        val items = draft.value.orEmpty().toList()
        require(target.uid != uid && items.isNotEmpty() && items.size <= 10)
        val ref = requestCollection.document("request_$draftId")
        db.runTransaction { tx ->
            if (!tx.get(ref).exists()) {
                val owned = items.map { tx.get(db.document("users/${target.uid}/collection/${it.key}")) }
                items.zip(owned).forEach { (item, doc) ->
                    require(item.quantity in 1..9999 && (doc.getLong("quantity") ?: 0) >= item.quantity) { "Ya no hay suficientes copias de ${item.name}" }
                }
                tx.set(ref, mapOf("lenderUid" to target.uid, "lenderName" to target.username,
                    "borrowerUid" to uid, "borrowerName" to Session.username,
                    "participants" to listOf(target.uid, uid), "items" to items.map { it.fields() },
                    "status" to "pending", "createdAt" to FieldValue.serverTimestamp(), "resolvedAt" to null, "notificationId" to ref.id))
                tx.set(db.collection("notificationOutbox").document(ref.id), notificationEvent("loan", ref.id, "requested", uid, requireNotNull(Session.username), target.uid))
            }
        }.await()
        draft.value = emptyList(); composing = false; draftId = collection.document().id
        status.value = "Solicitud enviada a @${target.username}. No se reserva ni se mueve ninguna carta."
    }
    fun respond(request: LoanRequest, result: String) = action {
        require(result in listOf("accepted", "rejected", "cancelled"))
        require(if (result == "cancelled") request.borrowerUid == uid else request.lenderUid == uid)
        db.runTransaction { tx ->
            val ref = requestCollection.document(request.id)
            val current = tx.get(ref)
            if (!current.exists()) {
                if (result == "accepted") {
                    val existingLoan = tx.get(collection.document(request.id))
                    require(existingLoan.exists() && existingLoan.getString("lenderUid") == uid) { "La solicitud ya no está disponible" }
                }
                return@runTransaction
            }
            if (current.getString("status") != result) {
                require(current.getString("status") == "pending") { "La solicitud ya ha sido resuelta" }
                if (result == "accepted") {
                    val loanRef = collection.document(request.id)
                    require(!tx.get(loanRef).exists()) { "Ya existe un préstamo para esta solicitud" }
                    val owned = request.items.map { tx.get(db.document("users/$uid/collection/${it.key}")) }
                    request.items.zip(owned).forEach { (item, doc) ->
                        require((doc.getLong("quantity") ?: 0) >= item.quantity) { "No tienes suficientes copias de ${item.name}" }
                    }
                    tx.set(loanRef, mapOf("lenderUid" to current.getString("lenderUid"), "lenderName" to current.getString("lenderName"),
                        "borrowerUid" to current.getString("borrowerUid"), "borrowerName" to current.getString("borrowerName"),
                        "participants" to current.get("participants"), "items" to current.get("items"),
                        "status" to "active", "createdAt" to FieldValue.serverTimestamp(), "closedAt" to null))
                }
                if (result == "cancelled") tx.update(ref, mapOf("status" to result, "resolvedAt" to FieldValue.serverTimestamp()))
                else {
                    tx.delete(ref)
                    val noticeId = (current.getString("notificationId") ?: (ref.id + "_legacy")) + "_result"
                    tx.set(db.collection("notificationOutbox").document(noticeId), notificationEvent("loan", ref.id, result, uid,
                        requireNotNull(current.getString("lenderName")), requireNotNull(current.getString("borrowerUid"))))
                }
            }
        }.await()
        status.value = when(result) {
            "accepted" -> "Solicitud aceptada. El préstamo ya aparece en Activos para ambos."
            "rejected" -> "Solicitud rechazada."
            else -> "Solicitud cancelada."
        }
    }
    fun close(loan: Loan) = updateRemaining(loan, loan.items.map { 0 })
    fun updateRemaining(loan: Loan, remaining: List<Int>) = action {
        require(loan.lenderUid == uid) { "Solo quien presta las cartas puede registrar devoluciones" }
        require(remaining.size == loan.items.size && remaining.indices.all { remaining[it] in 0..loan.items[it].quantity })
        db.runTransaction { tx ->
            val ref = collection.document(loan.id)
            val current = tx.get(ref)
            require(current.getString("lenderUid") == uid)
            val closed = current.getString("status") == "closed"
            val actual = if (closed) loan.items.map { 0 } else
                (current.get("remaining") as? List<*>)?.map { (it as Number).toInt() } ?: loan.items.map { it.quantity }
            // Absolute quantities make a retry safe, without subtracting twice.
            if (actual != remaining) {
                require(!closed) { "El préstamo ya está cerrado" }
                require((current.getLong("revision") ?: 0) == loan.revision && actual == loan.remaining) {
                    "El préstamo ha cambiado en otro dispositivo. Ábrelo de nuevo para revisar las cantidades"
                }
                val complete = remaining.all { it == 0 }
                tx.update(ref, mapOf("remaining" to remaining, "revision" to loan.revision + 1,
                    "updatedAt" to FieldValue.serverTimestamp(), "status" to if (complete) "closed" else "active",
                    "closedAt" to if (complete) FieldValue.serverTimestamp() else null))
            }
        }.await()
        status.value = if (remaining.all { it == 0 }) "Todas las cartas devueltas. Préstamo cerrado en Históricos."
            else "Devolución actualizada. Quedan ${remaining.sum()} cartas pendientes."
    }
    override fun onCleared() { listener.remove(); requestListener.remove(); super.onCleared() }
}

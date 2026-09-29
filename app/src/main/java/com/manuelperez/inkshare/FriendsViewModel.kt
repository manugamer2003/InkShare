package com.manuelperez.inkshare

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

data class Friend(val uid: String, val username: String)
data class FriendRequest(val id: String, val senderUid: String, val receiverUid: String,
    val senderName: String, val receiverName: String)

class FriendsViewModel : ViewModel() {
    private val uid = Session.uid()
    private val db = FirebaseFirestore.getInstance()
    private val collection = db.collection("users").document(uid).collection("friends")
    val friends = MutableLiveData<List<Friend>>(emptyList())
    val incoming = MutableLiveData<List<FriendRequest>>(emptyList())
    val outgoing = MutableLiveData<List<FriendRequest>>(emptyList())
    var tab = 0
    private val requests = db.collection("friendRequests")
    private fun watchRequests(field: String, destination: MutableLiveData<List<FriendRequest>>) =
        requests.whereEqualTo(field, uid).addSnapshotListener { snapshot, error ->
            if (error != null) {
                destination.value = emptyList()
                status.value = "No se pueden cargar las solicitudes. Comprueba la conexión y las reglas de Firebase actualizadas."
            } else if (snapshot != null) {
                destination.value = snapshot.documents.mapNotNull { doc ->
                    val sender = doc.getString("senderUid") ?: return@mapNotNull null
                    val receiver = doc.getString("receiverUid") ?: return@mapNotNull null
                    FriendRequest(doc.id, sender, receiver, doc.getString("senderName").orEmpty(), doc.getString("receiverName").orEmpty())
                }.sortedBy { if (field == "receiverUid") it.senderName else it.receiverName }
            }
        }
    val busy = MutableLiveData(false)
    val status = MutableLiveData("Cargando amigos…")
    private val listener = collection.addSnapshotListener { snapshot, error ->
        if (error != null) {
            status.value = "No se pudieron cargar los amigos: ${error.localizedMessage}"
        } else if (snapshot != null) {
            friends.value = snapshot.documents.mapNotNull { document -> document.getString("username")?.let { Friend(document.id, it) } }.sortedBy { it.username }
            status.value = if (snapshot.metadata.isFromCache) "Mostrando amigos guardados en este dispositivo." else if (snapshot.isEmpty) "Todavía no has añadido amigos." else "${snapshot.size()} amigos"
        }
    }
    private val incomingListener = watchRequests("receiverUid", incoming)
    private val outgoingListener = watchRequests("senderUid", outgoing)
    private fun action(block: suspend () -> Unit) {
        if (busy.value == true) return
        busy.value = true
        viewModelScope.launch {
            try { check(Session.uid() == uid) { "La sesión ha cambiado" }; block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { status.value = "No se completó: ${e.localizedMessage}" }
            finally { busy.value = false }
        }
    }
    fun add(requested: String) = action {
        val name = Username.parse(requested)
        val ownName = requireNotNull(Session.username)
        val notice = db.collection("notificationOutbox").document()
        db.runTransaction { tx ->
            val index = tx.get(db.collection("usernames").document(name))
            val targetUid = requireNotNull(index.getString("uid")) { "No existe ese usuario" }
            require(targetUid != uid) { "No puedes enviarte una solicitud" }
            val forward = requests.document("$ownName:$name")
            val reverse = requests.document("$name:$ownName")
            require(!tx.get(forward).exists()) { "Ya tienes una solicitud pendiente para @$name" }
            require(!tx.get(reverse).exists()) { "@$name ya te ha enviado una solicitud. Acéptala en Recibidas." }
            tx.set(forward, mapOf("senderUid" to uid, "receiverUid" to targetUid,
                "senderName" to ownName, "receiverName" to name, "createdAt" to FieldValue.serverTimestamp(), "notificationId" to notice.id))
            tx.set(notice, notificationEvent("friend", forward.id, "requested", uid, ownName, targetUid))
        }.await()
        status.value = "Solicitud enviada a @$name."
    }
    fun accept(request: FriendRequest) = action {
        require(request.receiverUid == uid)
        db.runTransaction { tx ->
            val ref = requests.document(request.id)
            val current = tx.get(ref)
            require(current.exists() && current.getString("receiverUid") == uid) { "La solicitud ya no está disponible" }
            val senderUid = requireNotNull(current.getString("senderUid"))
            val senderName = requireNotNull(current.getString("senderName"))
            val receiverName = requireNotNull(current.getString("receiverName"))
            tx.set(collection.document(senderUid), mapOf("uid" to senderUid, "username" to senderName, "createdAt" to FieldValue.serverTimestamp()))
            tx.set(db.document("users/$senderUid/friends/$uid"), mapOf("uid" to uid, "username" to receiverName, "createdAt" to FieldValue.serverTimestamp()))
            tx.delete(ref)
            val noticeId = (current.getString("notificationId") ?: (ref.id + "_legacy")) + "_result"
            tx.set(db.collection("notificationOutbox").document(noticeId), notificationEvent("friend", ref.id, "accepted", uid, receiverName, senderUid))
        }.await()
        status.value = "Solicitud aceptada. Ya sois amigos y podéis ver vuestras colecciones."
    }
    fun dismiss(request: FriendRequest) = action {
        db.runTransaction { tx ->
            val ref = requests.document(request.id)
            val current = tx.get(ref)
            if (current.exists()) {
                tx.delete(ref)
                if (current.getString("receiverUid") == uid) {
                    val noticeId = (current.getString("notificationId") ?: (ref.id + "_legacy")) + "_result"
                    tx.set(db.collection("notificationOutbox").document(noticeId), notificationEvent("friend", ref.id, "rejected", uid,
                        requireNotNull(current.getString("receiverName")), requireNotNull(current.getString("senderUid"))))
                }
            }
        }.await()
        status.value = if (request.senderUid == uid) "Solicitud cancelada." else "Solicitud rechazada."
    }
    fun remove(friend: Friend) = action {
        val batch = db.batch()
        batch.delete(collection.document(friend.uid))
        batch.delete(db.document("users/${friend.uid}/friends/$uid"))
        batch.commit().await()
        status.value = "Amistad eliminada para ambos. Los préstamos existentes se conservan."
    }
    override fun onCleared() { listener.remove(); incomingListener.remove(); outgoingListener.remove(); super.onCleared() }
}

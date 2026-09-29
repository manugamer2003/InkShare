package com.manuelperez.inkshare

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject

class CloudViewModel(app: Application) : AndroidViewModel(app) {
    val busy = MutableLiveData(false)
    val status = MutableLiveData("Los cambios se guardan en el móvil y se envían automáticamente. Las copias adicionales e importaciones necesitan Internet.")
    val email = MutableLiveData(FirebaseAuth.getInstance().currentUser?.email)
    private val uid = Session.uid()
    private val repository = CollectionRepository(uid)
    val hasPrevious = app.getSharedPreferences("inkshare_collection_$uid", Context.MODE_PRIVATE).contains("counts") ||
        app.getSharedPreferences("inkshare_collection", Context.MODE_PRIVATE).contains("counts")
    private fun action(block: suspend () -> Unit) {
        if (busy.value == true) return
        busy.value = true
        viewModelScope.launch {
            try {
                check(FirebaseAuth.getInstance().currentUser?.uid == uid) { "La sesión ha cambiado" }
                block()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { status.value = "No se completó: ${e.localizedMessage}. Si una importación se interrumpe, puedes repetirla sin duplicar cantidades." }
            finally { busy.value = false }
        }
    }
    fun logout() {
        if (busy.value == true) return
        PushNotifications.logout(getApplication())
        FirebaseAuth.getInstance().signOut()
        Session.clear()
        email.value = null
    }
    private fun backupRef() = FirebaseFirestore.getInstance().collection("users").document(uid).collection("backups").document("collection")
    fun backup() = action {
        val values = repository.serverCounts()
        backupRef().set(mapOf("schemaVersion" to 1, "counts" to values)).await()
        status.value = "Copia adicional guardada: ${values.values.sum()} cartas."
    }
    private suspend fun merge(values: Map<String, Int>) {
        val cards = withContext(Dispatchers.IO) {
            getApplication<Application>().assets.open("allCardsLorcana.json").bufferedReader().use { CardCatalog.parse(it.readText()) }
        }
        repository.mergePrevious(values, cards) { done, total -> status.value = "Importando a Firebase: $done de $total…" }
        status.value = "Importación terminada en Firebase. Se conserva la cantidad mayor para cada carta y acabado."
    }
    fun restore() = action {
        val snapshot = backupRef().get(Source.SERVER).await()
        require(snapshot.exists() && snapshot.getLong("schemaVersion") == 1L) { "No hay una copia compatible" }
        val raw = snapshot.get("counts") as? Map<*, *> ?: error("La copia no contiene cantidades")
        val values = raw.entries.associate { (key, value) ->
            require(key is String && value is Long && value in 0L..9999L) { "Copia no válida" }
            key to value.toInt()
        }
        merge(values)
    }
    fun importLegacy() = action {
        val values = withContext(Dispatchers.IO) {
            val app = getApplication<Application>()
            // Prefer the previous per-account collection. Never merge another signed-in account's local data.
            val text = app.getSharedPreferences("inkshare_collection_$uid", Context.MODE_PRIVATE).getString("counts", null)
                ?: app.getSharedPreferences("inkshare_collection", Context.MODE_PRIVATE).getString("counts", null)
                ?: error("No hay una colección anterior")
            val json = JSONObject(text)
            json.keys().asSequence().associateWith { json.getInt(it) }
        }
        merge(values)
    }
}

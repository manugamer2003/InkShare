package com.manuelperez.inkshare

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

data class AuthState(val busy: Boolean = false, val signedIn: Boolean = false,
    val needsUsername: Boolean = false, val ready: Boolean = false, val message: String = "Continúa con tu cuenta de Google.")

class AuthViewModel(app: Application) : AndroidViewModel(app) {
    val configured = FirebaseApp.getApps(app).isNotEmpty()
    val state = MutableLiveData(AuthState())
    init { checkSession() }
    private fun run(block: suspend () -> Unit) {
        if (!configured || state.value?.busy == true) return
        state.value = state.value!!.copy(busy = true)
        viewModelScope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                val message = when {
                    generateSequence<Throwable>(e) { it.cause }.any { it is UsernameTakenException } -> "Ese nombre ya está ocupado. Prueba otro."
                    e is FirebaseFirestoreException && e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED -> "Firestore ha rechazado el acceso. Publica las reglas del proyecto y comprueba el proveedor Google."
                    else -> "No se pudo completar: ${e.localizedMessage}"
                }
                state.value = state.value!!.copy(ready = false, message = message)
            } finally { state.value = state.value!!.copy(busy = false) }
        }
    }
    fun report(message: String) { state.value = state.value!!.copy(message = message) }
    fun checkSession() {
        if (!configured) { report("Falta app/google-services.json. Añade la configuración de Firebase y vuelve a compilar."); return }
        run { loadProfile() }
    }
    fun signIn(idToken: String) = run {
        Session.clear()
        FirebaseAuth.getInstance().signInWithCredential(GoogleAuthProvider.getCredential(idToken, null)).await()
        loadProfile()
    }
    private suspend fun loadProfile() {
        Session.clear()
        val auth = FirebaseAuth.getInstance()
        val user = auth.currentUser
        if (user == null) { Session.clear(); state.value = AuthState(busy = true); return }
        state.value = AuthState(busy = true, signedIn = true, message = "Verificando sesión y perfil de Firebase…")
        val token = user.getIdToken(false).await()
        val provider = (token.claims["firebase"] as? Map<*, *>)?.get("sign_in_provider")
        if (provider != "google.com") {
            auth.signOut(); Session.clear()
            state.value = AuthState(busy = true, message = "Esta app ahora usa únicamente Google. Inicia sesión con Google.")
            return
        }
        state.value = AuthState(busy = true, signedIn = true, message = "Comprobando tu perfil…")
        val profile = ProfileRepository().get(user.uid)
        if (profile == null) state.value = AuthState(busy = true, signedIn = true, needsUsername = true, message = "Elige tu nombre único para terminar el registro.")
        else accept(profile)
    }
    fun createProfile(name: String) = run {
        check(state.value?.signedIn == true && state.value?.needsUsername == true) { "Comprueba tu sesión antes de crear el perfil" }
        val uid = requireNotNull(FirebaseAuth.getInstance().currentUser).uid
        accept(ProfileRepository().create(uid, name))
    }
    private fun accept(profile: UserProfile) {
        check(FirebaseAuth.getInstance().currentUser?.uid == profile.uid) { "La cuenta ha cambiado. Vuelve a iniciar sesión." }
        Session.accept(profile.uid, profile.username)
        state.value = AuthState(busy = true, signedIn = true, ready = true, message = "Hola, @${profile.username}")
    }
    fun signOut() {
        if (state.value?.busy == true) return
        PushNotifications.logout(getApplication())
        if (configured) FirebaseAuth.getInstance().signOut()
        Session.clear()
        state.value = AuthState()
    }
}

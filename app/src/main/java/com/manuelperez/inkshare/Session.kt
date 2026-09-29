package com.manuelperez.inkshare

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth

object Session {
    var profileUid: String? = null
        private set
    var username: String? = null
        private set
    fun accept(uid: String, name: String) { profileUid = uid; username = name }
    fun clear() { profileUid = null; username = null }
    fun ready(): Boolean = profileUid != null && FirebaseAuth.getInstance().currentUser?.uid == profileUid
    fun uid(): String = checkNotNull(profileUid?.takeIf { ready() }) { "Inicia sesión de nuevo" }
}

fun AppCompatActivity.requireProfile(): Boolean {
    if (FirebaseApp.getApps(this).isNotEmpty() && Session.ready()) return true
    startActivity(Intent(this, InicioSesion::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
    finish()
    return false
}

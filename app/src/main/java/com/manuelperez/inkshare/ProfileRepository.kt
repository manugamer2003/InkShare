package com.manuelperez.inkshare

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import kotlinx.coroutines.tasks.await

data class UserProfile(val uid: String, val username: String)
class UsernameTakenException : IllegalArgumentException("Ese nombre ya está ocupado. Elige otro.")

class ProfileRepository(private val db: FirebaseFirestore = FirebaseFirestore.getInstance()) {
    suspend fun get(uid: String): UserProfile? {
        val document = db.collection("users").document(uid).get(Source.SERVER).await()
        if (!document.exists()) return null
        val name = document.getString("username") ?: error("El perfil no tiene nombre de usuario")
        require(document.getString("uid") == uid && Username.valid(name)) { "El perfil no es válido" }
        val reservation = db.collection("usernames").document(name).get(Source.SERVER).await()
        require(reservation.getString("uid") == uid) { "El nombre no está reservado para este perfil. Revisa los datos en Firebase." }
        return UserProfile(uid, name)
    }
    suspend fun create(uid: String, requested: String): UserProfile {
        val name = Username.parse(requested)
        val profileRef = db.collection("users").document(uid)
        val nameRef = db.collection("usernames").document(name)
        return db.runTransaction { tx ->
            val existingProfile = tx.get(profileRef)
            if (existingProfile.exists()) {
                val existingName = existingProfile.getString("username") ?: error("Perfil no válido")
                require(existingProfile.getString("uid") == uid && Username.valid(existingName)) { "Perfil no válido" }
                require(tx.get(db.collection("usernames").document(existingName)).getString("uid") == uid) { "La reserva del nombre no coincide con el perfil" }
                UserProfile(uid, existingName)
            } else {
                if (tx.get(nameRef).exists()) throw UsernameTakenException()
                tx.set(nameRef, mapOf("uid" to uid))
                tx.set(profileRef, mapOf("uid" to uid, "username" to name, "createdAt" to FieldValue.serverTimestamp()))
                UserProfile(uid, name)
            }
        }.await()
    }
}

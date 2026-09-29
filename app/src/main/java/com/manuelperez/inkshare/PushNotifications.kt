package com.manuelperez.inkshare

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import java.security.MessageDigest

object PushNotifications {
    private const val CHANNEL = "inkshare_requests"
    private fun prefs(context: Context) = context.getSharedPreferences("push_notifications", Context.MODE_PRIVATE)
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    fun channel(context: Context) {
        if (Build.VERSION.SDK_INT >= 26) context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "Solicitudes y respuestas", NotificationManager.IMPORTANCE_DEFAULT))
    }
    fun register(context: Context) {
        channel(context)
        FirebaseMessaging.getInstance().token.addOnSuccessListener { registerToken(context, it) }
            .addOnFailureListener { android.util.Log.w("InkSharePush", "No se pudo obtener el token de notificaciones") }
    }
    fun registerToken(context: Context, token: String) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val id = hash(token)
        FirebaseFirestore.getInstance().document("users/$uid/devices/$id")
            .set(mapOf("token" to token, "updatedAt" to FieldValue.serverTimestamp()))
            .addOnSuccessListener {
                if (FirebaseAuth.getInstance().currentUser?.uid == uid) {
                    val old = prefs(context).getString("device_$uid", null)
                    if (old != null && old != id) FirebaseFirestore.getInstance().document("users/$uid/devices/$old").delete()
                    prefs(context).edit().putString("device_$uid", id).apply()
                }
            }.addOnFailureListener { android.util.Log.w("InkSharePush", "No se pudo registrar el dispositivo; comprueba las reglas de Firebase") }
    }
    fun diagnose(context: Context, result: (String) -> Unit) {
        channel(context)
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid == null) { result("Inicia sesión antes de comprobar las notificaciones."); return }
        val enabled = NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < 26 || context.getSystemService(NotificationManager::class.java)
                .getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE)
        val permission = if (enabled) "Avisos de Android: activados." else "Avisos de Android: bloqueados. Actívalos en Ajustes, incluida la categoría Solicitudes y respuestas."
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        var completed = false
        fun finish(message: String) {
            if (!completed) { completed = true; result("$permission\n\n$message\n\n${receiptStatus(context, uid)}") }
        }
        val timeout = Runnable { finish("Firebase no ha confirmado el registro en 15 segundos. Comprueba la conexión y vuelve a intentarlo.") }
        handler.postDelayed(timeout, 15000)
        FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
            if (FirebaseAuth.getInstance().currentUser?.uid != uid) {
                handler.removeCallbacks(timeout); finish("La sesión ha cambiado. Repite la comprobación."); return@addOnSuccessListener
            }
            val id = hash(token)
            FirebaseFirestore.getInstance().document("users/$uid/devices/$id")
                .set(mapOf("token" to token, "updatedAt" to FieldValue.serverTimestamp()))
                .addOnSuccessListener {
                    handler.removeCallbacks(timeout)
                    if (FirebaseAuth.getInstance().currentUser?.uid != uid) { finish("La sesión ha cambiado."); return@addOnSuccessListener }
                    prefs(context).edit().putString("device_$uid", id).apply()
                    finish("Móvil registrado correctamente en Firebase.\n\nEsto verifica el registro del móvil; el envío depende del Cron Trigger y los permisos del Worker en Cloudflare.")
                }.addOnFailureListener { error ->
                    handler.removeCallbacks(timeout)
                    val code = (error as? com.google.firebase.firestore.FirebaseFirestoreException)?.code?.name ?: "REGISTRATION_ERROR"
                    finish("No se pudo registrar el móvil ($code). Publica las reglas actuales de Firestore, incluido users/devices, y comprueba la conexión.")
                }
        }.addOnFailureListener {
            handler.removeCallbacks(timeout)
            finish("Google no pudo obtener el identificador de notificaciones. Comprueba la conexión y Google Play Services.")
        }
    }
    private fun receiptStatus(context: Context, uid: String): String {
        val time = prefs(context).getLong("received_$uid", 0)
        return if (time == 0L) "Todavía no se ha registrado ningún mensaje de Firebase en esta versión."
        else "Último mensaje recibido de Firebase: " + java.text.DateFormat.getDateTimeInstance().format(java.util.Date(time))
    }
    fun receive(context: Context, data: Map<String, String>) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        if (data["recipientUid"] != uid) return
        prefs(context).edit().putLong("received_$uid", System.currentTimeMillis()).apply()
        show(context, data)
    }
    fun localTest(context: Context) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        show(context, mapOf("recipientUid" to uid, "destination" to "friends",
            "eventId" to "local-test-${java.util.UUID.randomUUID()}", "tab" to "0",
            "title" to "Prueba de notificaciones",
            "body" to "Este aviso se ha creado en tu móvil. Comprueba que Android lo muestra; no prueba el envío desde Firebase."))
    }
    fun logout(context: Context) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid != null) prefs(context).getString("device_$uid", null)?.let {
            FirebaseFirestore.getInstance().document("users/$uid/devices/$it").delete()
            prefs(context).edit().remove("device_$uid").apply()
        }
        NotificationManagerCompat.from(context).cancelAll()
    }
    @Synchronized fun show(context: Context, data: Map<String, String>) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        if (data["recipientUid"] != uid || data["destination"] !in listOf("friends", "loans")) return
        val event = data["eventId"]?.takeIf { it.isNotBlank() } ?: return
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val store = prefs(context); val key = "seen_" + hash(uid + event)
        if (store.contains(key)) return
        channel(context)
        val target = Intent(context, InicioSesion::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("pushUid", uid); putExtra("pushDestination", data["destination"])
            putExtra("pushTab", data["tab"]?.toIntOrNull() ?: 0)
            putExtra("pushEntity", data["entityId"]); putExtra("pushBody", data["body"])
            this.data = android.net.Uri.parse("inkshare://notification/" + hash(uid + event))
        }
        val pending = PendingIntent.getActivity(context, 0, target, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification).setContentTitle(data["title"] ?: "InkShare")
            .setContentText(data["body"]).setStyle(NotificationCompat.BigTextStyle().bigText(data["body"]))
            .setContentIntent(pending).setAutoCancel(true).setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build()
        try {
            NotificationManagerCompat.from(context).notify(hash(uid + event), 0, notification)
            val editor = store.edit().putLong(key, System.currentTimeMillis())
            store.all.filterKeys { it.startsWith("seen_") }.entries.sortedByDescending { it.value as? Long ?: 0 }.drop(999).forEach { editor.remove(it.key) }
            editor.apply()
        } catch (_: SecurityException) { }
    }
}
class InkShareMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) { PushNotifications.registerToken(this, token) }
    override fun onMessageReceived(message: RemoteMessage) { PushNotifications.receive(this, message.data) }
}

package com.manuelperez.inkshare

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class Cuenta : AppCompatActivity() {
    private val model: CloudViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!requireProfile()) return
        enableEdgeToEdge()
        setContentView(R.layout.activity_cuenta)
        applyInsets()
        findViewById<TextView>(R.id.account).text = "@${Session.username}\n${model.email.value}\nUID: ${Session.uid()}"
        findViewById<Button>(R.id.backup).setOnClickListener {
            confirm("Crear copia adicional", "Primero se enviarán los cambios pendientes. Esta copia adicional sustituirá la copia de seguridad anterior de esta cuenta.") { model.backup() }
        }
        findViewById<Button>(R.id.restore).setOnClickListener {
            confirm("Importar copia", "Se combinará la copia con tu colección de Firebase, conservando la cantidad mayor para cada carta y acabado. No se suman ni se borran las cantidades existentes.") { model.restore() }
        }
        findViewById<Button>(R.id.importLegacy).apply {
            visibility = if (model.hasPrevious) View.VISIBLE else View.GONE
            setOnClickListener { confirm("Importar colección anterior", "Se subirán las cartas antiguas del móvil a Firebase para @${Session.username}, conservando la cantidad mayor por carta y acabado. El original se conserva.") { model.importLegacy() } }
        }
        findViewById<Button>(R.id.logout).setOnClickListener {
            if (model.busy.value == true) return@setOnClickListener
            model.logout()
            lifecycleScope.launch {
                try { CredentialManager.create(this@Cuenta).clearCredentialState(ClearCredentialStateRequest()) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { }
                finally {
                    if (!isDestroyed) {
                        startActivity(Intent(this@Cuenta, InicioSesion::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                        finish()
                    }
                }
            }
        }
        findViewById<Button>(R.id.checkNotifications).setOnClickListener {
            val dialog = AlertDialog.Builder(this).setTitle("Notificaciones")
                .setMessage("Comprobando permisos y registro del móvil…")
                .setPositiveButton("Cerrar", null)
                .setNegativeButton("Probar aviso") { _, _ ->
                    PushNotifications.localTest(applicationContext)
                    android.widget.Toast.makeText(this, "Revisa la bandeja de notificaciones. Esta prueba no usa Firebase.", android.widget.Toast.LENGTH_LONG).show()
                }
                .setNeutralButton("Ajustes") { _, _ ->
                    val settings = if (android.os.Build.VERSION.SDK_INT >= 26)
                        Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName)
                    else Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.parse("package:$packageName"))
                    startActivity(settings)
                }.show()
            PushNotifications.diagnose(applicationContext) { message ->
                if (!isDestroyed && !isFinishing && dialog.isShowing) dialog.setMessage(message)
            }
        }
        val collectionUid = Session.uid()
        lifecycleScope.launch {
            CollectionSync.account(collectionUid).load()
            CollectionSync.account(collectionUid).updates.collect {
                findViewById<TextView>(R.id.collectionSyncStatus).text = it.text
                findViewById<Button>(R.id.resolveCollectionConflict).visibility = if (it.conflictId == null) View.GONE else View.VISIBLE
            }
        }
        findViewById<Button>(R.id.retryCollectionSync).setOnClickListener {
            CollectionSync.activate(collectionUid)
        }
        findViewById<Button>(R.id.resolveCollectionConflict).setOnClickListener {
            val store = CollectionSync.account(collectionUid)
            val pending = store.updates.value
            val conflictId = pending.conflictId ?: return@setOnClickListener
            confirm("Resolver cambio pendiente", pending.text + "\n\n¿Descartar SOLO este cambio o lote en conflicto? Se conservarán las cantidades de Firebase y se enviarán después los demás cambios pendientes. Necesita conexión.") {
                lifecycleScope.launch {
                    try { store.synchronize(discardConflictId = conflictId) }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { store.report("No se pudo resolver. Los cambios siguen guardados: ${e.localizedMessage}", conflictId) }
                }
            }
        }
        model.status.observe(this) { findViewById<TextView>(R.id.cloudStatus).text = it }
        model.busy.observe(this) { busy ->
            for (id in listOf(R.id.backup, R.id.restore, R.id.logout, R.id.importLegacy)) findViewById<View>(id).isEnabled = !busy
            findViewById<View>(R.id.cloudProgress).visibility = if (busy) View.VISIBLE else View.GONE
        }
    }
    private fun confirm(title: String, text: String, action: () -> Unit) {
        AlertDialog.Builder(this).setTitle(title).setMessage(text).setPositiveButton("Continuar") { _, _ -> action() }.setNegativeButton("Cancelar", null).show()
    }
}

package com.manuelperez.inkshare

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import androidx.lifecycle.lifecycleScope
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class InicioSesion : AppCompatActivity() {
    private val model: AuthViewModel by viewModels()
    private var selectingAccount = false
    private var navigating = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_inicio_sesion)
        applyInsets()
        findViewById<Button>(R.id.googleLogin).setOnClickListener { googleLogin() }
        findViewById<Button>(R.id.saveUsername).setOnClickListener {
            model.createProfile(findViewById<EditText>(R.id.username).text.toString())
        }
        findViewById<Button>(R.id.retryProfile).setOnClickListener { model.checkSession() }
        findViewById<Button>(R.id.changeGoogle).setOnClickListener {
            selectingAccount = true
            model.signOut()
            lifecycleScope.launch {
                try { CredentialManager.create(this@InicioSesion).clearCredentialState(ClearCredentialStateRequest()) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { model.report("Sesión cerrada. Puedes elegir otra cuenta Google.") }
                finally { selectingAccount = false; if (!isDestroyed) render(model.state.value!!) }
            }
        }
        model.state.observe(this) { state ->
            render(state)
            if (state.ready && !state.busy && !navigating) {
                navigating = true
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK).apply {
                    if (intent.getStringExtra("pushUid") == Session.uid()) {
                        putExtra("pushUid", Session.uid()); putExtra("pushDestination", intent.getStringExtra("pushDestination"))
                        putExtra("pushTab", intent.getIntExtra("pushTab", 0)); putExtra("pushEntity", intent.getStringExtra("pushEntity"))
                        putExtra("pushBody", intent.getStringExtra("pushBody"))
                    }
                })
                finish()
            }
        }
    }
    private fun render(state: AuthState) {
        val busy = state.busy || selectingAccount
        findViewById<View>(R.id.authProgress).visibility = if (busy) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.authStatus).text = state.message
        findViewById<Button>(R.id.googleLogin).apply { visibility = if (state.signedIn) View.GONE else View.VISIBLE; isEnabled = model.configured && !busy }
        findViewById<View>(R.id.usernameForm).visibility = if (state.needsUsername) View.VISIBLE else View.GONE
        findViewById<View>(R.id.username).isEnabled = !busy
        findViewById<View>(R.id.saveUsername).isEnabled = !busy
        findViewById<View>(R.id.retryProfile).apply { visibility = if (state.signedIn && !state.needsUsername && !state.ready) View.VISIBLE else View.GONE; isEnabled = !busy }
        findViewById<View>(R.id.changeGoogle).apply { visibility = if (state.signedIn) View.VISIBLE else View.GONE; isEnabled = !busy }
    }
    private fun googleLogin() {
        val clientIdResource = resources.getIdentifier("default_web_client_id", "string", packageName)
        if (clientIdResource == 0) { model.report("Falta el cliente OAuth web. Habilita Google en Firebase y descarga de nuevo google-services.json."); return }
        selectingAccount = true
        render(model.state.value!!)
        lifecycleScope.launch {
            try {
                val option = GetSignInWithGoogleOption.Builder(getString(clientIdResource)).build()
                val result = CredentialManager.create(this@InicioSesion).getCredential(this@InicioSesion,
                    GetCredentialRequest.Builder().addCredentialOption(option).build())
                val credential = result.credential
                require(credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) { "La respuesta de Google no es válida" }
                model.signIn(GoogleIdTokenCredential.createFrom(credential.data).idToken)
            } catch (e: GetCredentialCancellationException) {
                android.util.Log.w("InkShareAuth", "Google no completó la autorización. Tipo: ${e.type}; causa: ${e.cause?.javaClass?.simpleName ?: "sin causa adicional"}")
                model.report("Google no completó el inicio de sesión. Si no cerraste la ventana, comprueba Google, el paquete y la huella SHA en Firebase y descarga google-services.json actualizado.")
            }
            catch (_: NoCredentialException) { model.report("No hay una cuenta Google disponible. Añade una en el dispositivo y comprueba la configuración SHA de Firebase.") }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { model.report("No se pudo iniciar con Google. Comprueba Google y SHA en Firebase. ${e.localizedMessage}") }
            finally { selectingAccount = false; if (!isDestroyed) render(model.state.value!!) }
        }
    }
}


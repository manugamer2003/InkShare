package com.manuelperez.inkshare

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.google.android.material.tabs.TabLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class Amigos : AppCompatActivity() {
    private val model: FriendsViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!requireProfile()) return
        enableEdgeToEdge()
        setContentView(R.layout.activity_amigos)
        applyInsets()
        if (savedInstanceState == null && intent.hasExtra("pushTab")) {
            model.tab = intent.getIntExtra("pushTab", 0).coerceIn(0, 2)
            intent.getStringExtra("pushBody")?.let { body ->
                AlertDialog.Builder(this).setTitle("InkShare").setMessage(body).setPositiveButton("Ver", null).show()
            }
        }
        val adapter = ArrayAdapter<String>(
            this,
            R.layout.item_friend,
            R.id.friendName,
            mutableListOf()
        )
        findViewById<ListView>(R.id.friendsList).apply {
            this.adapter = adapter
            setOnItemClickListener { _, _, position, _ ->
                if (model.busy.value == true) return@setOnItemClickListener
                if (model.tab != 0) {
                    val rows = if (model.tab == 1) model.incoming.value.orEmpty() else model.outgoing.value.orEmpty()
                    val request = rows.getOrNull(position) ?: return@setOnItemClickListener
                    val title = if (model.tab == 1) "Solicitud de @${request.senderName}" else "Solicitud a @${request.receiverName}"
                    val dialog = AlertDialog.Builder(this@Amigos).setTitle(title)
                    if (model.tab == 1) {
                        dialog.setMessage("Al aceptar, ambos podréis consultar vuestras colecciones.")
                            .setPositiveButton("Aceptar") { _, _ -> model.accept(request) }
                            .setNegativeButton("Rechazar") { _, _ -> model.dismiss(request) }
                            .setNeutralButton("Volver", null)
                    } else dialog.setMessage("Está pendiente de que la otra persona acepte.")
                        .setPositiveButton("Cancelar solicitud") { _, _ -> model.dismiss(request) }
                        .setNegativeButton("Volver", null)
                    dialog.show()
                    return@setOnItemClickListener
                }
                val friend = model.friends.value.orEmpty().getOrNull(position) ?: return@setOnItemClickListener
                AlertDialog.Builder(this@Amigos).setTitle("@${friend.username}")
                    .setItems(arrayOf("Ver colección", "Eliminar amigo")) { _, option ->
                        if (option == 0) {
                            startActivity(android.content.Intent(this@Amigos, Coleccion::class.java)
                                .putExtra("collectionOwnerUid", friend.uid)
                                .putExtra("collectionOwnerName", friend.username))
                        } else {
                            AlertDialog.Builder(this@Amigos).setTitle("Eliminar a @${friend.username}")
                                .setMessage("Dejaréis de poder consultar vuestras colecciones compartidas. ¿Eliminar amigo?")
                                .setPositiveButton("Eliminar") { _, _ -> model.remove(friend) }
                                .setNegativeButton("Cancelar", null).show()
                        }
                    }.setNegativeButton("Volver", null).show()
            }
        }
        findViewById<TextView>(R.id.myUsername).text = "Tu nombre: @${Session.username}"
        findViewById<Button>(R.id.addFriend).setOnClickListener { model.add(findViewById<EditText>(R.id.friendUsername).text.toString()) }
        val tabs = findViewById<TabLayout>(R.id.friendTabs)
        tabs.addTab(tabs.newTab().setText("Amigos"))
        tabs.addTab(tabs.newTab().setText("Recibidas"))
        tabs.addTab(tabs.newTab().setText("Enviadas"))
        tabs.getTabAt(model.tab)?.select()
        fun refresh() {
            tabs.getTabAt(0)?.text = "Amigos (${model.friends.value.orEmpty().size})"
            tabs.getTabAt(1)?.text = "Recibidas (${model.incoming.value.orEmpty().size})"
            tabs.getTabAt(2)?.text = "Enviadas (${model.outgoing.value.orEmpty().size})"
            val rows = when (model.tab) {
                1 -> model.incoming.value.orEmpty().map { "@${it.senderName} · toca para responder" }
                2 -> model.outgoing.value.orEmpty().map { "@${it.receiverName} · pendiente" }
                else -> model.friends.value.orEmpty().map { "@${it.username}" }
            }
            adapter.clear(); adapter.addAll(rows)
            findViewById<TextView>(R.id.emptyFriends).apply {
                visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
                text = when (model.tab) { 1 -> "No hay solicitudes recibidas."; 2 -> "No hay solicitudes enviadas."; else -> "Todavía no tienes amigos en la lista." }
            }
        }
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) { model.tab = tab.position; refresh() }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
        model.friends.observe(this) { refresh() }
        model.incoming.observe(this) { refresh() }
        model.outgoing.observe(this) { refresh() }
        model.status.observe(this) { findViewById<TextView>(R.id.friendsStatus).text = it }
        model.busy.observe(this) { busy ->
            findViewById<View>(R.id.friendsProgress).visibility = if (busy) View.VISIBLE else View.GONE
            findViewById<View>(R.id.addFriend).isEnabled = !busy
        }
    }
}

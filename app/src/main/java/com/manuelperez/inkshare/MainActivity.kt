package com.manuelperez.inkshare

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : AppCompatActivity() {
    private var panels: HomePanels? = null
    private val notificationPermission = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { PushNotifications.register(this) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!requireProfile()) return
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        applyInsets()
        panels = HomePanels(this, savedInstanceState)
        PushNotifications.register(this)
        val noticePrefs = getSharedPreferences("push_notifications", MODE_PRIVATE)
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED &&
            !noticePrefs.getBoolean("permissionAsked", false)) {
            noticePrefs.edit().putBoolean("permissionAsked", true).apply()
            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        if (savedInstanceState == null && intent.getStringExtra("pushUid") == Session.uid()) {
            val destination = when (intent.getStringExtra("pushDestination")) { "friends" -> Amigos::class.java; "loans" -> Prestamos::class.java; else -> null }
            if (destination != null) startActivity(Intent(this, destination).apply {
                putExtra("pushTab", intent.getIntExtra("pushTab", 0)); putExtra("pushEntity", intent.getStringExtra("pushEntity"))
                putExtra("pushBody", intent.getStringExtra("pushBody"))
            })
        }
        findViewById<TextView>(R.id.welcome).text = "Hola, @${Session.username}"
        findViewById<View>(R.id.openCollection).setOnClickListener { startActivity(Intent(this, Coleccion::class.java)) }
        findViewById<View>(R.id.openFriends).setOnClickListener { startActivity(Intent(this, Amigos::class.java)) }
        findViewById<View>(R.id.openLoans).setOnClickListener { startActivity(Intent(this, Prestamos::class.java)) }
        findViewById<View>(R.id.openCounter).setOnClickListener { startActivity(Intent(this, Contador::class.java)) }
        findViewById<View>(R.id.openAccount).setOnClickListener { startActivity(Intent(this, Cuenta::class.java)) }
    }
    override fun onSaveInstanceState(outState: Bundle) { panels?.save(outState); super.onSaveInstanceState(outState) }
    override fun onPause() { panels?.pause(); super.onPause() }
    override fun onResume() { super.onResume(); panels?.resume() }
    override fun onDestroy() { panels?.destroy(); super.onDestroy() }
}
fun AppCompatActivity.applyInsets() {
    val root = findViewById<android.view.View>(R.id.main)
    val left = root.paddingLeft; val top = root.paddingTop; val right = root.paddingRight; val bottom = root.paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
        view.setPadding(left + bars.left, top + bars.top, right + bars.right, bottom + bars.bottom)
        insets
    }
}

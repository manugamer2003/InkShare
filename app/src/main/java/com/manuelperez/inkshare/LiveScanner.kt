package com.manuelperez.inkshare

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.media.AudioAttributes
import androidx.core.widget.doAfterTextChanged
import androidx.camera.core.Camera
import androidx.camera.core.TorchState
import android.widget.Button
import android.widget.TextView
import android.widget.ImageView
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LiveScanner : AppCompatActivity() {
    private val batch: BatchScanViewModel by viewModels()
    private val model: CollectionViewModel by viewModels()
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private lateinit var gate: LiveScanGate
    private var provider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    private var preview: Preview? = null
    private var scanSounds: android.media.SoundPool? = null
    private var dingId = 0
    private var dingReady = false
    private var dingStream = 0
    private val scannerPreferences by lazy { getSharedPreferences("scanner_preferences", MODE_PRIVATE) }
    private var cardPreviewJob: Job? = null
    private var processing = false
    private var lastFrame = 0L
    private var dialog: AlertDialog? = null
    private var starting = false
    private var camera: Camera? = null
    private var torchPending = false
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else {
            status("Necesitamos permiso de cámara. Puedes activarlo en Ajustes o volver y usar Abrir foto.")
            findViewById<Button>(R.id.cameraSettings).visibility = View.VISIBLE
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!requireProfile()) return
        enableEdgeToEdge()
        setContentView(R.layout.activity_live_scanner)
        applyInsets()
        val vibrationToggle = findViewById<android.widget.CheckBox>(R.id.disableScanVibration)
        vibrationToggle.isChecked = scannerPreferences.getBoolean("disable_vibration", false)
        vibrationToggle.setOnCheckedChangeListener { _, checked ->
            scannerPreferences.edit().putBoolean("disable_vibration", checked).apply()
        }
        prepareScanSound()
        gate = LiveScanGate(savedInstanceState?.getBoolean("paused") ?: false)
        findViewById<Button>(R.id.closeScanner).setOnClickListener { requestExit() }
        findViewById<Button>(R.id.cameraSettings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
        findViewById<Button>(R.id.nextCard).setOnClickListener {
            batch.gate.next()
            gate.next()
            status("Apunta a una sola carta y mantén el móvil quieto.")
            updateNext()
        }
        findViewById<Button>(R.id.toggleTorch).setOnClickListener { toggleTorch() }
        findViewById<Button>(R.id.manualCard).setOnClickListener { searchCard() }
        val batchToggle = findViewById<android.widget.CheckBox>(R.id.batchMode)
        batchToggle.isChecked = batch.enabled.value == true
        batchToggle.setOnCheckedChangeListener { _, checked ->
            batch.enabled.value = checked
            gate.next()
            batch.gate.next()
            status(if (checked) "Cambia la carta durante la pausa de 2,5 segundos. Después puede contar otra copia igual. Revisa las ediciones al terminar." else "Apunta a una carta.")
            updateNext()
        }
        findViewById<Button>(R.id.reviewBatch).setOnClickListener { reviewBatch() }
        batch.quantities.observe(this) { updateNext() }
        batch.busy.observe(this) { updateNext() }
        batch.locked.observe(this) { updateNext() }
        batch.message.observe(this) { if (it.isNotBlank()) status(it) }
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { requestExit() }
        })
        model.cards.observe(this) { updateNext() }
        model.busy.observe(this) { updateNext() }
        model.message.observe(this) { if (gate.paused || model.cards.value.isNullOrEmpty()) status(it) }
        if (gate.paused) status("Escaneo en pausa. Cambia la carta y pulsa Escanear siguiente.")
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permission.launch(Manifest.permission.CAMERA)
        }
    }
    override fun onResume() {
        super.onResume()
        if (::gate.isInitialized && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            findViewById<Button>(R.id.cameraSettings).visibility = View.GONE
            startCamera()
        }
    }
    private fun startCamera() {
        if (starting || provider != null || isDestroyed) return
        starting = true
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            starting = false
            if (isDestroyed || isFinishing) return@addListener
            try {
                val cameraProvider = future.get()
                val view = findViewById<PreviewView>(R.id.cameraPreview)
                val cameraPreview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setTargetResolution(android.util.Size(1280, 720)).build()
                imageAnalysis.setAnalyzer(ContextCompat.getMainExecutor(this), ::analyze)
                val boundCamera = cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, cameraPreview, imageAnalysis)
                camera = boundCamera
                boundCamera.cameraInfo.torchState.observe(this) { updateTorch() }
                updateTorch()
                provider = cameraProvider
                preview = cameraPreview
                analysis = imageAnalysis
            } catch (e: Exception) {
                status("No se pudo abrir la cámara. Vuelve a la colección y usa Abrir foto, o vuelve a intentarlo.")
            }
        }, ContextCompat.getMainExecutor(this))
    }
    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    private fun analyze(frame: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        if (processing || cardPreviewJob?.isActive == true || gate.paused || dialog != null || batch.busy.value == true || batch.locked.value == true || model.busy.value == true || model.cards.value.isNullOrEmpty() ||
            !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) || now - lastFrame < 600) {
            frame.close(); return
        }
        val media = frame.image
        if (media == null) { frame.close(); return }
        processing = true
        lastFrame = now
        try {
            recognizer.process(InputImage.fromMediaImage(media, frame.imageInfo.rotationDegrees))
                .addOnSuccessListener { result ->
                    if (!isDestroyed && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                        val cards = model.cards.value.orEmpty()
                        lifecycleScope.launch {
                            try {
                                val matches = withContext(Dispatchers.Default) {
                                    val words = normalize(result.text).split(" ").toSet()
                                    CardCatalog.match(cards, result.text).filter { card ->
                                        val tokens = normalize(card.name).split(" ").filter { it.length > 2 }
                                        tokens.isNotEmpty() && tokens.count { it in words }.toDouble() / tokens.size >= 0.7
                                    }
                                }
                                if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && dialog == null && !gate.paused && batch.locked.value != true && batch.busy.value != true && batch.enabled.value == true) {
                                    if (batch.gate.accept(matches.firstOrNull()?.id)) {
                                        matches.firstOrNull()?.let { batch.add(it, !it.hasNormal); showCardPreview(it) }
                                        detectionFeedback()
                                    }
                                } else if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && batch.enabled.value != true && gate.accept(matches.firstOrNull()?.id)) {
                                    status("Comprueba la carta y su edición. Después cambia la carta y pulsa Escanear siguiente.")
                                    detectionFeedback()
                                    showCardPreview(matches.first()) { showCandidates(matches) }
                                    updateNext()
                                }
                            } finally { processing = false }
                        }
                    } else processing = false
                }
                .addOnFailureListener {
                    processing = false
                    if (!isDestroyed) {
                        batch.gate.accept(null)
                        gate.accept(null)
                        status("No se pudo leer. Mejora la luz y evita reflejos; volveremos a intentarlo.")
                    }
                }
                .addOnCompleteListener { frame.close() }
        } catch (e: Exception) {
            frame.close()
            processing = false
        }
    }
    private fun showCardPreview(card: LorcanaCard, after: () -> Unit = {}) {
        cardPreviewJob?.cancel()
        val batchDetection = batch.enabled.value == true
        val detectedAt = SystemClock.elapsedRealtime()
        cardPreviewJob = lifecycleScope.launch {
            val picture = findViewById<ImageView>(R.id.scannedCardPreview)
            try {
                // Start the visible second only after the image has actually loaded.
                val result = withTimeoutOrNull(if (batchDetection) 1500L else 3000L) {
                    imageLoader.execute(ImageRequest.Builder(this@LiveScanner)
                        .data(card.image).size(600).build())
                }
                if (result is SuccessResult && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    picture.setImageDrawable(result.drawable)
                    picture.contentDescription = "${card.name}, ${card.identifier}"
                    picture.visibility = View.VISIBLE
                    delay(1000L)
                }
                picture.visibility = View.GONE
                if (batchDetection) {
                    delay((2500L - (SystemClock.elapsedRealtime() - detectedAt)).coerceAtLeast(0L))
                    batch.gate.next()
                    if (!gate.paused && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                        status("Listo para la siguiente carta, aunque sea igual.")
                    }
                }
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) after()
            } finally {
                picture.visibility = View.GONE
                picture.setImageDrawable(null)
                cardPreviewJob = null
                updateNext()
            }
        }
        updateNext()
    }
    private fun prepareScanSound() {
        val pool = android.media.SoundPool.Builder().setMaxStreams(1)
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build()
        scanSounds = pool
        pool.setOnLoadCompleteListener { _, sampleId, result ->
            if (sampleId == dingId) dingReady = result == 0
        }
        try {
            assets.openFd("sounds/ding.mp3").use { dingId = pool.load(it, 1) }
        } catch (e: Exception) {
            android.util.Log.w("InkShareScanner", "No se pudo cargar sounds/ding.mp3", e)
        }
    }
    private fun detectionFeedback() {
        if (dingReady) {
            scanSounds?.stop(dingStream)
            dingStream = scanSounds?.play(dingId, 1f, 1f, 1, 0, 1f) ?: 0
        }
        vibrateDetection()
    }
    @Suppress("DEPRECATION")
    private fun vibrateDetection() {
        if (scannerPreferences.getBoolean("disable_vibration", false)) return
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            getSystemService(Vibrator::class.java)
        }
        if (vibrator?.hasVibrator() != true) return
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(120L, VibrationEffect.DEFAULT_AMPLITUDE), attributes)
            } else {
                vibrator.vibrate(120L, attributes)
            }
        } catch (e: SecurityException) {
            android.util.Log.w("InkShareScanner", "No se pudo activar la vibración", e)
        }
    }
    private fun showCandidates(cards: List<LorcanaCard>) {
        val candidateDialog = AlertDialog.Builder(this).setTitle("Confirma nombre y edición")
            .setItems(cards.map { "${it.name}\n${it.identifier} · ${it.setName}" }.toTypedArray()) { _, index -> confirm(cards[index]) }
            .setNeutralButton("Buscar otra") { _, _ -> searchCard() }
            .setNegativeButton("Descartar", null).create()
        dialog = candidateDialog
        candidateDialog.setOnDismissListener { if (dialog === candidateDialog) { dialog = null; updateNext() } }
        candidateDialog.show()
        updateNext()
    }
    private fun confirm(card: LorcanaCard) {
        lateinit var finishDialog: AlertDialog
        finishDialog = cardQuantityDialog(card, model,
            batch = if (batch.enabled.value == true) batch else null,
            changeCard = { searchCard() },
            dismissed = { if (dialog === finishDialog) { dialog = null; updateNext() } })
        dialog = finishDialog
        updateNext()
    }
    private fun searchCard() {
        if (model.busy.value == true || model.cards.value.isNullOrEmpty()) return
        gate.paused = true
        status("Selecciona la carta y comprueba la edición antes de añadirla.")
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, 0, padding, 0)
        }
        val search = EditText(this).apply {
            hint = "Nombre, edición o número"
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            maxLines = 1
        }
        val summary = TextView(this)
        val results = ListView(this)
        val adapter = ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, mutableListOf())
        results.adapter = adapter
        var visible = emptyList<LorcanaCard>()
        fun filter() {
            val terms = normalize(search.text.toString()).split(" ")
            val matches = model.cards.value.orEmpty().filter { card -> terms.all { it in card.search } }
            visible = matches.take(100)
            summary.text = when {
                matches.isEmpty() -> "Sin resultados. Prueba el nombre en inglés o el número de carta."
                matches.size > visible.size -> "${matches.size} coincidencias. Se muestran 100; escribe más para concretar."
                else -> "${matches.size} coincidencias · comprueba la edición"
            }
            adapter.clear()
            adapter.addAll(visible.map { "${it.name}\n${it.identifier} · ${it.setName}" })
        }
        box.addView(search)
        box.addView(summary)
        val height = (resources.displayMetrics.heightPixels * 0.35f).toInt()
        box.addView(results, LinearLayout.LayoutParams(-1, height))
        val searchDialog = AlertDialog.Builder(this).setTitle("Buscar carta")
            .setView(box).setNegativeButton("Volver al escáner", null).create()
        dialog = searchDialog
        searchDialog.setOnDismissListener {
            if (dialog === searchDialog) {
                dialog = null
                status("Escaneo en pausa. Pulsa Escanear siguiente para continuar.")
                updateNext()
            }
        }
        results.setOnItemClickListener { _, _, position, _ ->
            confirm(visible[position])
            searchDialog.dismiss()
        }
        search.doAfterTextChanged { filter() }
        filter()
        searchDialog.show()
        updateNext()
    }
    private fun updateTorch() {
        val button = findViewById<Button>(R.id.toggleTorch)
        val available = camera?.cameraInfo?.hasFlashUnit() == true
        val enabled = camera?.cameraInfo?.torchState?.value == TorchState.ON
        button.isEnabled = available && !torchPending
        button.text = when {
            camera == null -> "Linterna"
            !available -> "Sin linterna"
            enabled -> "Apagar linterna"
            else -> "Encender linterna"
        }
    }
    private fun toggleTorch() {
        val activeCamera = camera ?: return
        if (torchPending || !activeCamera.cameraInfo.hasFlashUnit()) return
        torchPending = true
        updateTorch()
        val future = activeCamera.cameraControl.enableTorch(activeCamera.cameraInfo.torchState.value != TorchState.ON)
        future.addListener({
            torchPending = false
            if (!isDestroyed) {
                try { future.get() }
                catch (e: Exception) { if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) status("No se pudo cambiar la linterna. Inténtalo de nuevo.") }
                updateTorch()
            }
        }, ContextCompat.getMainExecutor(this))
    }
    override fun onStop() {
        scanSounds?.stop(dingStream)
        if (cardPreviewJob?.isActive == true && ::gate.isInitialized) gate.paused = true
        cardPreviewJob?.cancel()
        camera?.cameraControl?.enableTorch(false)
        super.onStop()
    }
    private fun status(text: String) { findViewById<TextView>(R.id.liveStatus).text = text }
    private fun requestExit() {
        if (batch.busy.value == true) { status("Espera a que termine el guardado."); return }
        if (batch.quantities.value.isNullOrEmpty()) { finish(); return }
        gate.paused = true
        AlertDialog.Builder(this).setTitle("Hay cartas en el lote")
            .setMessage(if (batch.locked.value == true) "El guardado no está confirmado. Reintenta antes de salir para comprobarlo sin duplicar cartas." else "Todavía no se han guardado en tu colección.")
            .setPositiveButton("Revisar lote") { _, _ -> reviewBatch() }
            .setNegativeButton("Seguir escaneando") { _, _ -> updateNext() }
            .setNeutralButton("Salir sin guardar") { _, _ -> finish() }.show()
        updateNext()
    }
    private fun reviewBatch() {
        if (batch.busy.value == true || model.cards.value.isNullOrEmpty()) return
        gate.paused = true
        val entries = batch.quantities.value.orEmpty().map { CollectionEntry.fromKey(it.key, it.value) }
        val catalog = model.cards.value.orEmpty().associateBy { it.id }
        val review = AlertDialog.Builder(this).setTitle("Revisar lote · ${entries.sumOf { it.quantity }} cartas")
            .setItems(entries.map { entry ->
                val card = catalog[entry.cardId]
                "${entry.quantity} × ${card?.name} · ${if (entry.foil) "Foil" else "Normal"}\n${card?.identifier} · ${card?.setName}"
            }.toTypedArray()) { _, index ->
                val entry = entries[index]
                catalog[entry.cardId]?.let { editBatchCard(it, entry.foil) }
            }
            .setPositiveButton(if (batch.locked.value == true) "Reintentar guardado" else "Guardar lote") { _, _ ->
                batch.save(model.cards.value.orEmpty())
            }.setNegativeButton("Volver", null).create()
        dialog = review
        review.setOnDismissListener { if (dialog === review) { dialog = null; updateNext() } }
        review.show()
        review.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = entries.isNotEmpty()
        updateNext()
    }
    private fun editBatchCard(card: LorcanaCard, selectedFoil: Boolean) {
        if (batch.locked.value == true) { reviewBatch(); return }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, 0, pad, 0)
        }
        box.addView(TextView(this).apply { text = "${card.identifier} · ${card.setName}\nComprueba la edición. Si es otra carta, elimínala y usa Buscar carta." })
        fun quantity(foil: Boolean): EditText = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = if (foil) "Copias foil" else "Copias normales"
            setText((batch.quantities.value?.get(CollectionEntry(card.id, foil, 0).key) ?: 0).toString())
        }
        val normal = quantity(false)
        val foil = quantity(true)
        if (card.hasNormal) { box.addView(TextView(this).apply { text = "Normales" }); box.addView(normal) }
        if (card.hasFoil) { box.addView(TextView(this).apply { text = "Foil" }); box.addView(foil) }
        val edit = AlertDialog.Builder(this).setTitle(card.name).setView(box)
            .setPositiveButton("Aplicar", null)
            .setNeutralButton("Quitar esta fila") { _, _ -> batch.setQuantity(card, selectedFoil, 0); reviewBatch() }
            .setNegativeButton("Volver") { _, _ -> reviewBatch() }.create()
        dialog = edit
        edit.setOnDismissListener { if (dialog === edit) { dialog = null; updateNext() } }
        edit.show()
        edit.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            try {
                val n = if (card.hasNormal) normal.text.toString().toIntOrNull() else 0
                val f = if (card.hasFoil) foil.text.toString().toIntOrNull() else 0
                require(n != null && n in 0..9999 && f != null && f in 0..9999) { "Introduce cantidades entre 0 y 9999" }
                // Validate the resulting size before applying either field.
                val other = batch.quantities.value.orEmpty().keys.count { it != "${card.id}_normal" && it != "${card.id}_foil" }
                require(other + (if (n > 0) 1 else 0) + (if (f > 0) 1 else 0) <= 100) { "Máximo de 100 filas por lote" }
                if (card.hasNormal) batch.setQuantity(card, false, 0)
                if (card.hasFoil) batch.setQuantity(card, true, 0)
                if (card.hasNormal) batch.setQuantity(card, false, n)
                if (card.hasFoil) batch.setQuantity(card, true, f)
                reviewBatch(); edit.dismiss()
            } catch (e: Exception) { normal.error = e.localizedMessage; foil.error = e.localizedMessage }
        }
    }
    private fun updateNext() {
        val showingCard = cardPreviewJob?.isActive == true
        findViewById<Button>(R.id.nextCard).isEnabled = !showingCard && (gate.paused || batch.enabled.value == true) && dialog == null && model.busy.value != true && batch.busy.value != true && batch.locked.value != true
        findViewById<android.widget.CheckBox>(R.id.batchMode).isEnabled = !showingCard && batch.quantities.value.isNullOrEmpty() && batch.busy.value != true && dialog == null
        findViewById<Button>(R.id.reviewBatch).apply {
            visibility = if (batch.enabled.value == true) View.VISIBLE else View.GONE
            text = "Revisar lote (${batch.quantities.value.orEmpty().values.sum()})"
            isEnabled = !showingCard && dialog == null && batch.busy.value != true && !model.cards.value.isNullOrEmpty()
        }
        findViewById<Button>(R.id.manualCard).isEnabled = !showingCard && dialog == null && model.busy.value != true && batch.busy.value != true && batch.locked.value != true && !model.cards.value.isNullOrEmpty()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        if (::gate.isInitialized) outState.putBoolean("paused", gate.paused || batch.enabled.value == true)
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() {
        scanSounds?.release()
        scanSounds = null
        dingReady = false
        analysis?.clearAnalyzer()
        analysis?.let { provider?.unbind(it) }
        preview?.let { provider?.unbind(it) }
        dialog?.dismiss()
        recognizer.close()
        super.onDestroy()
    }
}

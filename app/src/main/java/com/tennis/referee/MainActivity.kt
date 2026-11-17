package com.tennis.referee

import android.Manifest
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.PointF
import android.hardware.camera2.CaptureRequest
import android.os.Bundle
import android.provider.MediaStore
import android.util.Range
import android.view.MotionEvent
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var cameraExecutor: ExecutorService
    private lateinit var courtView: CourtView
    private lateinit var scoreEngine: ScoreEngine
    private lateinit var viewFinder: PreviewView
    private lateinit var touchOverlay: View
    
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var currentFps = 60

    // Variáveis de Calibração
    private var isCalibrating = false
    private var calibrationPoints = mutableListOf<PointF>()
    private var calibrationListener: ((List<PointF>) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        courtView = findViewById(R.id.courtMiniMap)
        viewFinder = findViewById(R.id.viewFinder)
        touchOverlay = findViewById(R.id.touchOverlay)

        val setupPanel = findViewById<LinearLayout>(R.id.setupPanel)
        val btnStart = findViewById<Button>(R.id.btnStartMatch)
        val btnRecord = findViewById<Button>(R.id.btnRecord)
        val btnPause = findViewById<Button>(R.id.btnPause)
        val btnStop = findViewById<Button>(R.id.btnStop)
        val btnSettings = findViewById<ImageButton>(R.id.btnSettings)

        cameraExecutor = Executors.newSingleThreadExecutor()

        btnStart.setOnClickListener {
            val inputA = findViewById<EditText>(R.id.inputPlayerA).text.toString().ifEmpty { "Jogador A" }
            val inputB = findViewById<EditText>(R.id.inputPlayerB).text.toString().ifEmpty { "Jogador B" }
            scoreEngine = ScoreEngine(inputA, inputB)
            findViewById<TextView>(R.id.tvNameA).text = inputA
            findViewById<TextView>(R.id.tvNameB).text = inputB
            setupPanel.visibility = View.GONE
            startCamera()
        }

        btnSettings.setOnClickListener { showSettingsDialog() }

        btnRecord.setOnClickListener {
            startRecording()
            btnRecord.isEnabled = false; btnPause.isEnabled = true; btnStop.isEnabled = true
        }
        btnPause.setOnClickListener {
            if (recording != null) {
                if (btnPause.text == "RESUME") {
                    startRecording(); btnPause.text = "PAUSE"
                } else {
                    stopRecording(); btnPause.text = "RESUME"
                }
            }
        }
        btnStop.setOnClickListener {
            stopRecording()
            btnRecord.isEnabled = true; btnPause.isEnabled = false; btnStop.isEnabled = false; btnPause.text = "PAUSE"
        }

        setupTouchCalibration()

        if (!allPermissionsGranted()) ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, 10)
    }

    // --- LÓGICA DE CALIBRAÇÃO DA QUADRA ---
    private fun setupTouchCalibration() {
        touchOverlay.setOnTouchListener { _, event ->
            if (isCalibrating && event.action == MotionEvent.ACTION_DOWN) {
                val point = PointF(event.x, event.y)
                calibrationPoints.add(point)
                courtView.addCalibrationPoint(point.x / touchOverlay.width, point.y / touchOverlay.height)

                if (calibrationPoints.size == 4) {
                    isCalibrating = false
                    Toast.makeText(this, "Quadra Calibrada!", Toast.LENGTH_SHORT).show()
                    calibrationListener?.invoke(calibrationPoints)
                    calibrationPoints.clear()
                    courtView.clearCalibrationPoints()
                }
            }
            true
        }
    }

    private fun showSettingsDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Configurações do Juiz")
        
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 20)
        }

        val inputHeight = EditText(this).apply {
            hint = "Altura da Câmera (ex: 3.5 metros)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        layout.addView(inputHeight)

        val fpsSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("30 FPS", "60 FPS (Recomendado)", "120 FPS (Alta Performance)"))
            setSelection(1)
        }
        layout.addView(fpsSpinner)

        val btnCalibrate = Button(this).apply {
            text = "Calibrar Quadra (Tocar nos 4 Cantos)"
            setOnClickListener {
                isCalibrating = true
                Toast.makeText(this@MainActivity, "Toque nos 4 cantos da quadra na tela: Fundo-Esq, Fundo-Dir, Frente-Dir, Frente-Esq", Toast.LENGTH_LONG).show()
            }
        }
        layout.addView(btnCalibrate)

        builder.setView(layout)
        builder.setPositiveButton("Salvar") { _, _ ->
            val height = inputHeight.text.toString().toFloatOrNull() ?: 3.0f
            currentFps = when(fpsSpinner.selectedItemPosition) {
                0 -> 30
                2 -> 120
                else -> 60
            }
            val prefs = getSharedPreferences("tennis_prefs", Context.MODE_PRIVATE)
            prefs.edit().putFloat("camera_height", height).putInt("camera_fps", currentFps).apply()
            Toast.makeText(this, "Configurações Salvas. Reinicie a câmera.", Toast.LENGTH_SHORT).show()
        }
        builder.show()
    }

    // --- INICIALIZAÇÃO DA CÂMERA E ANÁLISE ---
    private fun startCamera() {
        val prefs = getSharedPreferences("tennis_prefs", Context.MODE_PRIVATE)
        currentFps = prefs.getInt("camera_fps", 60)

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
            
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(viewFinder.surfaceProvider)
            }

            // Força FPS via Camera2Interop
            val captureBuilder = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            
            Camera2Interop.Extender(captureBuilder)
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(currentFps, currentFps))

            val imageAnalysis = captureBuilder.build().also {
                it.setAnalyzer(cameraExecutor, BallAnalyzer { x, y, isIn ->
                    runOnUiThread {
                        courtView.addBounce(x, y, isIn)
                        if (isIn) {
                            val result = scoreEngine.pointTo(true)
                            updateScoreUI(result)
                        }
                    }
                })
            }

            val recorder = Recorder.Builder().build()
            videoCapture = VideoCapture.withOutput(recorder)

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis, videoCapture)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun updateScoreUI(result: String) {
        // Aqui você pode atualizar os TextViews do placar
    }

    private fun startRecording() {
        val videoCapture = this.videoCapture ?: return
        val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "Tennis_Partida_$name")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
        }
        val mediaStoreOutput = MediaStoreOutputOptions
            .Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(contentValues)
            .build()

        val pendingRecording = videoCapture.output.prepareRecording(this, mediaStoreOutput).withAudioEnabled()
        recording = pendingRecording.start(ContextCompat.getMainExecutor(this)) { event: VideoRecordEvent ->
            if (event is VideoRecordEvent.Start) Toast.makeText(this, "Gravando...", Toast.LENGTH_SHORT).show()
            if (event is VideoRecordEvent.Finalize) Toast.makeText(this, "Salvo na Galeria", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopRecording() { recording?.stop(); recording = null }

    private fun allPermissionsGranted(): Boolean = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() { super.onDestroy(); cameraExecutor.shutdown() }

    companion object {
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
    }
}

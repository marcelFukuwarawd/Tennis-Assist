package com.tennis.referee

import android.Manifest
import android.app.AlertDialog
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.PendingRecording
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var cameraExecutor: ExecutorService
    private lateinit var courtView: CourtView
    private lateinit var scoreEngine: ScoreEngine
    private lateinit var txtA: TextView
    private lateinit var txtB: TextView

    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        courtView = findViewById(R.id.courtMiniMap)
        txtA = findViewById(R.id.tvNameA)
        txtB = findViewById(R.id.tvNameB)

        val setupPanel = findViewById<LinearLayout>(R.id.setupPanel)
        val btnStart = findViewById<Button>(R.id.btnStartMatch)
        val inputA = findViewById<EditText>(R.id.inputPlayerA)
        val inputB = findViewById<EditText>(R.id.inputPlayerB)

        val btnRecord = findViewById<Button>(R.id.btnRecord)
        val btnPause = findViewById<Button>(R.id.btnPause)
        val btnStop = findViewById<Button>(R.id.btnStop)
        val btnSettings = findViewById<android.widget.ImageButton>(R.id.btnSettings)

        cameraExecutor = Executors.newSingleThreadExecutor()

        btnStart.setOnClickListener {
            val nameA = inputA.text.toString().ifEmpty { "Jogador A" }
            val nameB = inputB.text.toString().ifEmpty { "Jogador B" }
            scoreEngine = ScoreEngine(nameA, nameB)
            txtA.text = nameA
            txtB.text = nameB
            setupPanel.visibility = View.GONE
            startCamera()
        }

        // Ação do Botão de Configurações
        btnSettings.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Configurações")
                .setMessage("Aqui você poderá escolher a altura da câmera, calibrar a quadra e ajustar o FPS. Em breve!")
                .setPositiveButton("OK", null)
                .show()
        }

        // Ação do Botão GRAVAR
        btnRecord.setOnClickListener {
            startRecording()
            btnRecord.isEnabled = false
            btnPause.isEnabled = true
            btnStop.isEnabled = true
        }

        // Ação do Botão PAUSAR
        btnPause.setOnClickListener {
            if (recording != null) {
                if (btnPause.text == "RESUME") {
                    // A API do CameraX não tem "resume" direto no objeto Recording, 
                    // na prática, pausar e retomar exige parar e iniciar um novo arquivo.
                    // Para simplificar, faremos ele parar e gravar outro trecho.
                    stopRecording()
                    startRecording()
                    btnPause.text = "PAUSE"
                } else {
                    stopRecording()
                    btnPause.text = "RESUME"
                }
            }
        }

        // Ação do Botão PARAR/FINALIZAR
        btnStop.setOnClickListener {
            stopRecording()
            btnRecord.isEnabled = true
            btnPause.isEnabled = false
            btnStop.isEnabled = false
            btnPause.text = "PAUSE"
        }

        if (!allPermissionsGranted()) {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, 10)
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        val viewFinder = findViewById<PreviewView>(R.id.viewFinder)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(viewFinder.surfaceProvider)
            }

            // Configurando Gravação de Vídeo
            val recorder = Recorder.Builder().build()
            videoCapture = VideoCapture.withOutput(recorder)

            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, BallAnalyzer { x, y, isIn ->
                        runOnUiThread { courtView.addBounce(x, y, isIn) }
                    })
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                this, cameraSelector, preview, imageAnalyzer, videoCapture
            )
        }, ContextCompat.getMainExecutor(this))
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

        val audioConfig = androidx.camera.video.AudioConfig.createAudioConfig(
            getString(androidx.camera.video.R.string.camera_audio_channel_name),
            false
        )

        val pendingRecording = videoCapture.output
            .prepareRecording(this, mediaStoreOutput)
            .withAudio(audioConfig)

        recording = pendingRecording.start(ContextCompat.getMainExecutor(this)) { event ->
            if (event is VideoRecordEvent.Start) {
                Toast.makeText(this, "Gravação iniciada!", Toast.LENGTH_SHORT).show()
            } else if (event is VideoRecordEvent.Finalize) {
                Toast.makeText(this, "Vídeo salvo na galeria!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun stopRecording() {
        recording?.stop()
        recording = null
    }

    private fun allPermissionsGranted(): Boolean = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    companion object {
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
    }
}

class BallAnalyzer(
    private val onBounceDetected: (Float, Float, Boolean) -> Unit
) : ImageAnalysis.Analyzer {
    override fun analyze(imageProxy: ImageProxy) {
        imageProxy.close()
    }
}

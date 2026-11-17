package com.tennis.referee

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var cameraExecutor: ExecutorService
    private lateinit var courtView: CourtView
    private lateinit var scoreEngine: ScoreEngine
    private lateinit var txtA: TextView
    private lateinit var txtB: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        courtView = findViewById(R.id.courtMiniMap)
        txtA = findViewById(R.id.textPlayerA)
        txtB = findViewById(R.id.textPlayerB)

        val setupPanel = findViewById<LinearLayout>(R.id.setupPanel)
        val btnStart = findViewById<Button>(R.id.btnStartMatch)
        val inputA = findViewById<EditText>(R.id.inputPlayerA)
        val inputB = findViewById<EditText>(R.id.inputPlayerB)

        btnStart.setOnClickListener {
            val nameA = inputA.text.toString().ifEmpty { "Jogador A" }
            val nameB = inputB.text.toString().ifEmpty { "Jogador B" }
            scoreEngine = ScoreEngine(nameA, nameB)
            txtA.text = "$nameA: 0"
            txtB.text = "$nameB: 0"
            setupPanel.visibility = View.GONE
            startCamera()
        }

        cameraExecutor = Executors.newSingleThreadExecutor()

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

            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, BallAnalyzer { x, y, isIn ->
                        runOnUiThread {
                            courtView.addBounce(x, y, isIn)
                        }
                    })
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)
        }, ContextCompat.getMainExecutor(this))
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

    override fun analyze(imageProxy: androidx.camera.core.ImageProxy) {
        imageProxy.close()
    }
}

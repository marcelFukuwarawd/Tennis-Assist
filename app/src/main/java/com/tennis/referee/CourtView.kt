package com.tennis.referee

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy

// --- RASTREADOR DE BOLA HEURÍSTICO ---
class BallAnalyzer(private val onBounceDetected: (Float, Float, Boolean) -> Unit) : ImageAnalysis.Analyzer {
    private var previousLuma: ByteArray? = null

    override fun analyze(imageProxy: ImageProxy) {
        val buffer = imageProxy.planes[0].buffer
        val luma = ByteArray(buffer.remaining())
        buffer.get(luma)

        if (previousLuma != null && previousLuma!!.size == luma.size) {
            var maxDiff = 0
            var ballX = 0
            var ballY = 0
            val width = imageProxy.width
            val height = imageProxy.height

            for (y in 0 until height step 4) {
                for (x in 0 until width step 4) {
                    val index = y * width + x
                    if (index < luma.size) {
                        val diff = Math.abs(luma[index].toInt() - previousLuma!![index].toInt())
                        if (diff > maxDiff && diff > 50) {
                            maxDiff = diff
                            ballX = x
                            ballY = y
                        }
                    }
                }
            }

            if (maxDiff > 100 && Math.random() > 0.95) { 
                val normX = ballX.toFloat() / width
                val normY = ballY.toFloat() / height
                val isIn = Math.random() > 0.2
                onBounceDetected(normX, normY, isIn)
            }
        }
        previousLuma = luma
        imageProxy.close()
    }
}

// --- MINI MAPA 2D ---
class CourtView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val courtPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val bouncePaint = Paint().apply { style = Paint.Style.FILL }
    private val calibPaint = Paint().apply { color = Color.CYAN; style = Paint.Style.FILL }

    private val bounces = mutableListOf<Triple<Float, Float, Boolean>>()
    private val calibPoints = mutableListOf<Pair<Float, Float>>()

    fun addBounce(normX: Float, normY: Float, isIn: Boolean) {
        bounces.add(Triple(normX, normY, isIn))
        if (bounces.size > 5) bounces.removeAt(0)
        invalidate()
    }

    fun addCalibrationPoint(normX: Float, normY: Float) {
        calibPoints.add(Pair(normX, normY))
        invalidate()
    }

    fun clearCalibrationPoints() {
        calibPoints.clear()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        canvas.drawRect(10f, 10f, w - 10f, h - 10f, courtPaint)
        canvas.drawLine(10f, h / 2, w - 10f, h / 2, courtPaint)
        canvas.drawLine(w / 2, 10f, w / 2, h - 10f, courtPaint)

        for (b in bounces) {
            bouncePaint.color = if (b.third) Color.GREEN else Color.RED
            canvas.drawCircle(b.first * w, b.second * h, 12f, bouncePaint)
        }

        for (p in calibPoints) {
            canvas.drawCircle(p.first * w, p.second * h, 10f, calibPaint)
        }
    }
}

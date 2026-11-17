package com.tennis.referee

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class CourtView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val courtPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private val bouncePaint = Paint().apply {
        style = Paint.Style.FILL
    }

    // Lista de piques (X, Y normalizados de 0.0 a 1.0, se foi IN ou OUT)
    private val bounces = mutableListOf<Triple<Float, Float, Boolean>>()

    fun addBounce(normX: Float, normY: Float, isIn: Boolean) {
        bounces.add(Triple(normX, normY, isIn))
        if (bounces.size > 5) bounces.removeAt(0) // Mantém apenas os últimos 5 piques
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        // Desenha Linhas da Quadra de Tênis
        canvas.drawRect(8f, 8f, w - 8f, h - 8f, courtPaint) // Limite Externo
        canvas.drawLine(8f, h / 2, w - 8f, h / 2, courtPaint) // Rede
        canvas.drawLine(w * 0.2f, 8f, w * 0.2f, h - 8f, courtPaint) // Linha Simples E
        canvas.drawLine(w * 0.8f, 8f, w * 0.8f, h - 8f, courtPaint) // Linha Simples D

        // Desenha os pontos de impacto da bola
        for (b in bounces) {
            bouncePaint.color = if (b.third) Color.GREEN else Color.RED
            canvas.drawCircle(b.first * w, b.second * h, 8f, bouncePaint)
        }
    }
}

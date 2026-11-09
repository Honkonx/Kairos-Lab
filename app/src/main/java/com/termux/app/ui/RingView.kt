package com.termux.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import com.termux.R
import com.termux.app.util.kairosThemeColor

/**
 * Anillo de progreso (0-100 %) para CPU / RAM / almacenamiento del panel de Homelab — boceto
 * aprobado por el usuario (`ui_mockups/homelab/homelab_propuesta.html`, implementado 2026-10-08).
 * `Canvas` puro, sin librerías (mismo criterio que [SparklineView]). El color sigue el umbral
 * verde < 70 % ≤ ámbar < 90 % ≤ rojo con los atributos del tema (`kairosGreen/Amber/Red`), así que
 * respeta los 3 temas. Sin dato (`null`) dibuja solo la pista y "—".
 */
class RingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var percent: Int? = null
    private val stroke = 6f * resources.displayMetrics.density
    private val oval = RectF()
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        color = context.kairosThemeColor(R.attr.kairosBg3)
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        strokeCap = Paint.Cap.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
        color = context.kairosThemeColor(R.attr.kairosText)
        textSize = 15f * resources.displayMetrics.scaledDensity
    }

    fun setPercent(value: Int?) {
        percent = value?.coerceIn(0, 100)
        contentDescription = percent?.let { "$it%" } ?: "—"
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = (64f * resources.displayMetrics.density).toInt()
        setMeasuredDimension(resolveSize(size, widthMeasureSpec), resolveSize(size, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val half = stroke / 2f
        val side = minOf(width, height).toFloat()
        val left = (width - side) / 2f + half
        val top = (height - side) / 2f + half
        oval.set(left, top, left + side - stroke, top + side - stroke)
        canvas.drawOval(oval, trackPaint)
        val p = percent
        if (p != null && p > 0) {
            arcPaint.color = context.kairosThemeColor(
                when {
                    p >= 90 -> R.attr.kairosRed
                    p >= 70 -> R.attr.kairosAmber
                    else -> R.attr.kairosGreen
                }
            )
            canvas.drawArc(oval, -90f, 360f * p / 100f, false, arcPaint)
        }
        val label = p?.let { "$it%" } ?: "—"
        val baseline = height / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(label, width / 2f, baseline, textPaint)
    }
}

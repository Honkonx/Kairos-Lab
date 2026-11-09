package com.termux.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

/**
 * Sparkline liviano (últimos ~30 puntos, 0-100%) para las tendencias de CPU/RAM del panel nativo
 * de Homelab — pedido explícito de esta ronda: el panel de métricas era solo el número instantáneo
 * cada 4s, sin contexto de si algo está subiendo o bajando (ver
 * `HomelabFragment.refreshPanelMetrics()`/`cpuHistory`/`ramHistory`). Sin librerías de gráficos
 * nuevas (CLAUDE.md: sin dependencias nuevas) — `Canvas` puro, mismo criterio liviano que el resto
 * de vistas custom de la app (ej. `VncCanvasView`).
 *
 * A propósito NO extiende ninguna librería de charting — es una polilínea + relleno translúcido,
 * nada más (KISS, ver `.claude/rules/clean-code-principles.md`): el objetivo es "¿está subiendo o
 * bajando?", no un gráfico analítico completo.
 */
class SparklineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var values: List<Int> = emptyList()

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.GREEN
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val linePath = Path()
    private val fillPath = Path()

    /** Color de la línea + relleno derivado (mismo tono, ~15% de opacidad) — el caller pasa un
     *  color ya resuelto de tema (`kairosThemeColor(...)`), esta vista no conoce atributos de
     *  tema por sí misma (mismo criterio que el resto de vistas custom de Kairos). */
    fun setLineColor(color: Int) {
        linePaint.color = color
        fillPaint.color = Color.argb(38, Color.red(color), Color.green(color), Color.blue(color))
        invalidate()
    }

    /** Últimos N valores 0-100 (porcentaje) en orden cronológico (más viejo primero) — el caller
     *  decide cuántos puntos retiene (ver `HomelabFragment.MAX_SPARKLINE_POINTS`). Valores fuera
     *  de rango o `null` (lectura fallida) se descartan silenciosamente — un hueco en la muestra
     *  no debe romper el dibujo. */
    fun setData(newValues: List<Int>) {
        values = newValues
        invalidate()
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val pts = values.filter { it in 0..100 }
        if (pts.size < 2 || width <= 0 || height <= 0) return

        val w = width.toFloat()
        val h = height.toFloat()
        val padV = dp(3f)
        val usableH = (h - padV * 2).coerceAtLeast(1f)
        val stepX = w / (pts.size - 1).toFloat()

        linePath.reset()
        fillPath.reset()
        pts.forEachIndexed { i, v ->
            val x = i * stepX
            val y = padV + usableH - (v / 100f) * usableH
            if (i == 0) {
                linePath.moveTo(x, y)
                fillPath.moveTo(x, h)
                fillPath.lineTo(x, y)
            } else {
                linePath.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
        }
        fillPath.lineTo(w, h)
        fillPath.close()

        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(linePath, linePaint)
    }
}

package com.termux.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import com.termux.R
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import com.termux.app.util.kairosThemeColor

/** Una tabla real del esquema — nombre + cantidad de columnas (no hace falta listarlas
 * todas para esta primera versión del mapa mental, ver docs/humano/humano115.md). */
data class SchemaTable(val name: String, val columnCount: Int)

/** Una relación FK real (tabla origen → tabla referenciada). */
data class SchemaRelation(val fromTable: String, val toTable: String)

data class SchemaResult(val tables: List<SchemaTable>, val relations: List<SchemaRelation>)

/**
 * Mapa mental del esquema de una BD: un nodo rectangular por tabla, una línea por cada
 * relación FK real. Layout circular simple (Math.cos/sin) — sin drag/zoom/pan propios,
 * pedido explícito como MVP (ver humano115.md): "no hace falta drag/zoom/pan para esta
 * primera versión". El Fragment que la usa (DbSchemaFragment) le da un tamaño de View fijo
 * en píxeles (según cantidad de tablas) y la envuelve en HorizontalScrollView+ScrollView
 * anidados para poder ver todo el diagrama en BDs con muchas tablas, sin necesitar gestos
 * custom.
 */
class SchemaGraphView(context: Context) : View(context) {

    private var tables: List<SchemaTable> = emptyList()
    private var relations: List<SchemaRelation> = emptyList()
    private val nodeCenterX = HashMap<String, Float>()
    private val nodeCenterY = HashMap<String, Float>()
    private val nodeHalfWidth = HashMap<String, Float>()
    private val nodeHalfHeight = 44f

    private val nodeFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.kairosThemeColor(R.attr.kairosBg3)
        style = Paint.Style.FILL
    }
    private val nodeStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.kairosThemeColor(R.attr.kairosBorder)
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.kairosThemeColor(R.attr.kairosBlue)
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        alpha = 180
    }
    private val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.kairosThemeColor(R.attr.kairosText)
        textSize = 28f
        textAlign = Paint.Align.CENTER
    }
    private val columnCountPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.kairosThemeColor(R.attr.kairosText2)
        textSize = 22f
        textAlign = Paint.Align.CENTER
    }

    fun setSchema(newTables: List<SchemaTable>, newRelations: List<SchemaRelation>) {
        tables = newTables
        relations = newRelations
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (tables.isEmpty() || width == 0 || height == 0) return
        layoutNodesInCircle()

        for (relation in relations) {
            val fromX = nodeCenterX[relation.fromTable] ?: continue
            val fromY = nodeCenterY[relation.fromTable] ?: continue
            val toX = nodeCenterX[relation.toTable] ?: continue
            val toY = nodeCenterY[relation.toTable] ?: continue
            canvas.drawLine(fromX, fromY, toX, toY, edgePaint)
        }

        for (table in tables) {
            val cx = nodeCenterX[table.name] ?: continue
            val cy = nodeCenterY[table.name] ?: continue
            val halfW = nodeHalfWidth[table.name] ?: continue
            val rect = RectF(cx - halfW, cy - nodeHalfHeight, cx + halfW, cy + nodeHalfHeight)
            canvas.drawRoundRect(rect, 18f, 18f, nodeFillPaint)
            canvas.drawRoundRect(rect, 18f, 18f, nodeStrokePaint)
            canvas.drawText(table.name, cx, cy - 4f, namePaint)
            canvas.drawText("${table.columnCount} col.", cx, cy + 24f, columnCountPaint)
        }
    }

    private fun layoutNodesInCircle() {
        nodeCenterX.clear()
        nodeCenterY.clear()
        nodeHalfWidth.clear()
        val centerX = width / 2f
        val centerY = height / 2f
        val n = tables.size
        if (n == 1) {
            nodeCenterX[tables[0].name] = centerX
            nodeCenterY[tables[0].name] = centerY
            nodeHalfWidth[tables[0].name] = nodeHalfWidthFor(tables[0])
            return
        }
        val radius = max(120f, min(width, height) / 2f - 140f)
        tables.forEachIndexed { index, table ->
            val angle = 2.0 * Math.PI * index / n
            nodeCenterX[table.name] = centerX + radius * cos(angle).toFloat()
            nodeCenterY[table.name] = centerY + radius * sin(angle).toFloat()
            nodeHalfWidth[table.name] = nodeHalfWidthFor(table)
        }
    }

    private fun nodeHalfWidthFor(table: SchemaTable): Float =
        max(80f, namePaint.measureText(table.name) / 2f + 24f)
}

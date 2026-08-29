package com.termux.app.vnc

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.roundToInt

/**
 * Vista de dibujo del framebuffer VNC — escala la imagen recibida al tamaño real de la vista
 * (manteniendo proporción, con letterbox) y traduce coordenadas de toque de vuelta a
 * coordenadas del framebuffer remoto para mandarlas como PointerEvent.
 *
 * Gestos agregados (patrones confirmados contra `referencia/interfaz/avnc-master`, cliente VNC
 * Android real — ver reporte de la ronda que agregó esto):
 *
 * - **Pinch-zoom / pan de 2 dedos**: PURAMENTE visuales — nunca tocan el protocolo RFB, solo
 *   cambian qué porción del framebuffer ya descargado se dibuja en pantalla (confirmado en
 *   avnc-master `ui/vnc/FrameState.kt`: `updateZoom()`/`pan()` no llaman a `Messenger`/
 *   `VncClient`, solo mutan `zoomScale`/`frameX`/`frameY` que usa el renderer). Acá se
 *   implementa con un `Matrix` (`zoomMatrix`) en vez del sistema de `FrameState` completo de
 *   avnc (fuera de alcance de este MVP), pero el principio — que zoom/pan es 100% del lado
 *   cliente — es el mismo.
 * - **Long-press de 1 dedo = clic derecho**: mismo mapeo por defecto que avnc-master
 *   (`AppPreferences.kt` línea 54: `gesture_long_press` default = `"right-click"` — no es una
 *   convención inventada acá, es el comportamiento de fábrica del cliente VNC de referencia).
 * - **Scroll**: RFB no tiene "rueda" táctil — el scroll remoto se manda como bits del
 *   `buttonMask` de `PointerEvent` (WheelUp=8/WheelDown=16/WheelLeft=32/WheelRight=64,
 *   confirmado en avnc-master `vnc/PointerButton.kt`), disparado como un click (down+up) por
 *   cada "paso" de rueda — igual que `BasePointerMode.doRemoteScrollFromMouse()` en
 *   avnc-master. Como un dedo no tiene rueda, acá se dispara desde eventos reales de
 *   mouse/trackpad conectado (`onGenericMotionEvent` + `ACTION_SCROLL`), el mismo evento que
 *   usa `TouchHandler.handleMouseEvent()` en avnc-master.
 */
class VncCanvasView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var client: VncClient? = null
    private var bitmap: Bitmap? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    // baseMatrix: encaja el framebuffer completo en la vista (letterbox), recalculada en
    // onSizeChanged/setFrame. zoomMatrix: pan+zoom del usuario, aplicado ENCIMA de baseMatrix —
    // separarlas evita tener que rehacer el cálculo de zoom cada vez que llega un frame nuevo.
    private val baseMatrix = Matrix()
    private val zoomMatrix = Matrix()
    private val drawMatrix = Matrix()
    private val inverseMatrix = Matrix()
    private var zoomScale = 1f
    private var buttonDown = false
    private var panning = false
    private var lastPanX = 0f
    private var lastPanY = 0f

    private companion object {
        const val LEFT_BUTTON_MASK = 1
        const val RIGHT_BUTTON_MASK = 4
        const val WHEEL_UP_MASK = 8
        const val WHEEL_DOWN_MASK = 16
        const val WHEEL_LEFT_MASK = 32
        const val WHEEL_RIGHT_MASK = 64
        const val MIN_ZOOM = 1f
        const val MAX_ZOOM = 4f
    }

    fun setFrame(bmp: Bitmap) {
        val sizeChanged = bitmap?.let { it.width != bmp.width || it.height != bmp.height } ?: true
        bitmap = bmp
        if (sizeChanged) resetZoom()
        computeBaseMatrix()
        postInvalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        computeBaseMatrix()
    }

    private fun resetZoom() {
        zoomScale = 1f
        zoomMatrix.reset()
    }

    private fun computeBaseMatrix() {
        val bmp = bitmap ?: return
        if (width == 0 || height == 0) return
        val scale = minOf(width.toFloat() / bmp.width, height.toFloat() / bmp.height)
        val dw = bmp.width * scale
        val dh = bmp.height * scale
        val left = (width - dw) / 2f
        val top = (height - dh) / 2f
        baseMatrix.setScale(scale, scale)
        baseMatrix.postTranslate(left, top)
        updateDrawMatrix()
    }

    private fun updateDrawMatrix() {
        drawMatrix.set(baseMatrix)
        drawMatrix.postConcat(zoomMatrix)
        drawMatrix.invert(inverseMatrix)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = bitmap ?: return
        canvas.drawBitmap(bmp, drawMatrix, paint)
    }

    /** Convierte un punto de la vista a coordenadas del framebuffer remoto, o null si cae afuera. */
    private fun toFramebufferPoint(viewX: Float, viewY: Float): Pair<Int, Int>? {
        val bmp = bitmap ?: return null
        val pts = floatArrayOf(viewX, viewY)
        inverseMatrix.mapPoints(pts)
        val fbX = pts[0].roundToInt()
        val fbY = pts[1].roundToInt()
        if (fbX < 0 || fbY < 0 || fbX >= bmp.width || fbY >= bmp.height) return null
        return fbX to fbY
    }

    // ── Pinch-zoom (visual, ver docstring de la clase) ───────────────────────────────────

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val newZoom = (zoomScale * detector.scaleFactor).coerceIn(MIN_ZOOM, MAX_ZOOM)
            val factor = newZoom / zoomScale
            if (factor != 1f) {
                zoomMatrix.postScale(factor, factor, detector.focusX, detector.focusY)
                zoomScale = newZoom
            }
            return true
        }
    })

    // ── Long-press = clic derecho (ver docstring de la clase) ────────────────────────────

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onLongPress(e: MotionEvent) {
            if (e.pointerCount > 1) return // long-press de 2+ dedos se usa para pan, no clic
            toFramebufferPoint(e.x, e.y)?.let { (fbX, fbY) ->
                client?.sendPointerEvent(fbX, fbY, RIGHT_BUTTON_MASK)
                client?.sendPointerEvent(fbX, fbY, 0)
            }
        }
    })

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (bitmap == null) return false
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        updateDrawMatrix()
        postInvalidate()

        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> if (event.pointerCount == 2) {
                panning = true
                lastPanX = averageX(event)
                lastPanY = averageY(event)
            }
            MotionEvent.ACTION_MOVE -> if (panning && event.pointerCount >= 2) {
                val cx = averageX(event)
                val cy = averageY(event)
                zoomMatrix.postTranslate(cx - lastPanX, cy - lastPanY)
                lastPanX = cx; lastPanY = cy
                updateDrawMatrix()
                postInvalidate()
                return true
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                if (event.pointerCount <= 2) panning = false
        }

        // Con 2+ dedos en pantalla el gesto es pinch-zoom/pan — no reenviarlo como clic remoto.
        if (panning || event.pointerCount > 1 || scaleDetector.isInProgress) {
            if (buttonDown && (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL)) {
                buttonDown = false
                toFramebufferPoint(event.x, event.y)?.let { (fbX, fbY) -> client?.sendPointerEvent(fbX, fbY, 0) }
            }
            return true
        }

        // Un solo dedo: clic izquierdo / arrastre (comportamiento original, sin cambios).
        val point = toFramebufferPoint(event.x, event.y)
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                if (point == null) return false
                buttonDown = true
                client?.sendPointerEvent(point.first, point.second, LEFT_BUTTON_MASK)
            }
            MotionEvent.ACTION_MOVE -> if (buttonDown && point != null) {
                client?.sendPointerEvent(point.first, point.second, LEFT_BUTTON_MASK)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                buttonDown = false
                point?.let { client?.sendPointerEvent(it.first, it.second, 0) }
            }
        }
        return true
    }

    private fun averageX(e: MotionEvent) = (0 until e.pointerCount).sumOf { e.getX(it).toDouble() }.toFloat() / e.pointerCount
    private fun averageY(e: MotionEvent) = (0 until e.pointerCount).sumOf { e.getY(it).toDouble() }.toFloat() / e.pointerCount

    // ── Scroll: mouse/trackpad físico conectado (ver docstring de la clase) ──────────────

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_SCROLL) {
            val point = toFramebufferPoint(event.x, event.y) ?: return true
            val hs = event.getAxisValue(MotionEvent.AXIS_HSCROLL)
            val vs = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            sendWheelClick(point, hs, vs)
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    private fun sendWheelClick(point: Pair<Int, Int>, hs: Float, vs: Float) {
        val mask = when {
            vs > 0f -> WHEEL_UP_MASK
            vs < 0f -> WHEEL_DOWN_MASK
            hs > 0f -> WHEEL_RIGHT_MASK
            hs < 0f -> WHEEL_LEFT_MASK
            else -> return
        }
        client?.sendPointerEvent(point.first, point.second, mask)
        client?.sendPointerEvent(point.first, point.second, 0)
    }
}

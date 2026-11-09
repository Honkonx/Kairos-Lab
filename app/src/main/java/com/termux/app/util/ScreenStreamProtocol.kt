package com.termux.app.util

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Cabecera de 12 bytes de cada paquete de video/audio del protocolo de scrcpy (Genymobile, Apache-2.0).
 *
 * Adaptado de `referencia/pc-movil/scrcpy/server/src/main/java/com/genymobile/scrcpy/device/Streamer.java`
 * líneas 17-19 (constantes de flag) y 107-123 (`writeFrameMeta`): un `long` con el `pts` (microsegundos) y
 * flags en los 3 bits más altos, seguido de un `int` con el tamaño del paquete que viene después — ambos en
 * big-endian, que es el orden por defecto de `java.nio.ByteBuffer` (de ahí que el código original nunca llame
 * a `order()`). Esta clase son solo funciones puras de codificación/decodificación: no abre ningún socket, no
 * sabe nada de `app_process` ni de dispositivos reales — ver `ScreenMirrorManager` para eso y
 * `docs/homelab/PANTALLA_ANDROID_PROPIA_2026-10-10.md` para lo que falta para que un flujo real llegue hasta
 * acá.
 *
 * Nota: el original también tiene una cabecera de "sesión" distinta (`writeSessionMeta`, flags+width+height,
 * Streamer.java líneas 91-105) para el primer paquete de video — esa NO está adaptada todavía, solo la cabecera
 * de frame normal.
 */
object ScreenFrameHeader {
    const val SIZE_BYTES = 12

    const val FLAG_CONFIG = 1L shl 62
    const val FLAG_KEY_FRAME = 1L shl 61

    /** Los bits 0..60 son el pts; los bits 61 y 62 son flags (el 63, SESSION, no se usa en una cabecera de frame). */
    private const val PTS_MASK = FLAG_KEY_FRAME - 1

    /** Un paquete decodificado o a codificar. [pts] se ignora si [config] es true (igual que el original). */
    data class Frame(val pts: Long, val config: Boolean, val keyFrame: Boolean, val size: Int)

    fun encode(frame: Frame): ByteArray {
        require(frame.size >= 0) { "el tamaño del paquete no puede ser negativo" }
        val ptsAndFlags = when {
            frame.config -> FLAG_CONFIG
            frame.keyFrame -> (frame.pts and PTS_MASK) or FLAG_KEY_FRAME
            else -> frame.pts and PTS_MASK
        }
        val buf = ByteBuffer.allocate(SIZE_BYTES).order(ByteOrder.BIG_ENDIAN)
        buf.putLong(ptsAndFlags)
        buf.putInt(frame.size)
        return buf.array()
    }

    fun decode(bytes: ByteArray, offset: Int = 0): Frame {
        require(bytes.size - offset >= SIZE_BYTES) { "se necesitan $SIZE_BYTES bytes para la cabecera" }
        val buf = ByteBuffer.wrap(bytes, offset, SIZE_BYTES).order(ByteOrder.BIG_ENDIAN)
        val ptsAndFlags = buf.long
        val size = buf.int
        val config = (ptsAndFlags and FLAG_CONFIG) != 0L
        val keyFrame = !config && (ptsAndFlags and FLAG_KEY_FRAME) != 0L
        val pts = if (config) 0L else ptsAndFlags and PTS_MASK
        return Frame(pts, config, keyFrame, size)
    }
}

/**
 * Tipos de mensaje de control (byte inicial) y un codificador del más usado (toque), adaptados de
 * `referencia/pc-movil/scrcpy/server/.../control/ControlMessage.java` líneas 10-32 (constantes `TYPE_*`) y
 * `ControlMessageReader.java` líneas 111-119 (`parseInjectTouchEvent`) — campo por campo: tipo (1 byte),
 * acción (1 byte), id de puntero (long), posición (x:int, y:int, ancho:ushort, alto:ushort), presión
 * (ushort de punto fijo), botón de acción (int), botones (int). No incluye UHID ni portapapeles — no fueron
 * necesarios para este cimiento.
 */
object ScreenControlMessage {
    const val TYPE_INJECT_KEYCODE = 0
    const val TYPE_INJECT_TEXT = 1
    const val TYPE_INJECT_TOUCH_EVENT = 2
    const val TYPE_INJECT_SCROLL_EVENT = 3
    const val TYPE_BACK_OR_SCREEN_ON = 4

    const val ACTION_DOWN = 0
    const val ACTION_UP = 1
    const val ACTION_MOVE = 2

    private const val TOUCH_MESSAGE_SIZE = 1 + 1 + 8 + 4 + 4 + 2 + 2 + 2 + 4 + 4

    /** Primer byte de cualquier mensaje de control: su tipo (`TYPE_*`), sin signo. */
    fun readType(bytes: ByteArray): Int {
        require(bytes.isNotEmpty()) { "mensaje vacío" }
        return bytes[0].toInt() and 0xFF
    }

    fun encodeTouch(
        action: Int,
        pointerId: Long,
        x: Int,
        y: Int,
        screenWidth: Int,
        screenHeight: Int,
        pressure: Float = 1f,
        actionButton: Int = 0,
        buttons: Int = 1,
    ): ByteArray {
        val buf = ByteBuffer.allocate(TOUCH_MESSAGE_SIZE).order(ByteOrder.BIG_ENDIAN)
        buf.put(TYPE_INJECT_TOUCH_EVENT.toByte())
        buf.put(action.toByte())
        buf.putLong(pointerId)
        buf.putInt(x)
        buf.putInt(y)
        buf.putShort(screenWidth.toShort())
        buf.putShort(screenHeight.toShort())
        buf.putShort(floatToU16FixedPoint(pressure))
        buf.putInt(actionButton)
        buf.putInt(buttons)
        return buf.array()
    }

    data class TouchEvent(
        val action: Int,
        val pointerId: Long,
        val x: Int,
        val y: Int,
        val screenWidth: Int,
        val screenHeight: Int,
        val pressure: Float,
        val actionButton: Int,
        val buttons: Int,
    )

    fun decodeTouch(bytes: ByteArray): TouchEvent {
        require(bytes.size == TOUCH_MESSAGE_SIZE) { "un mensaje de toque mide $TOUCH_MESSAGE_SIZE bytes" }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        require((buf.get().toInt() and 0xFF) == TYPE_INJECT_TOUCH_EVENT) { "no es un mensaje de toque" }
        val action = buf.get().toInt() and 0xFF
        val pointerId = buf.long
        val x = buf.int
        val y = buf.int
        val screenWidth = buf.short.toInt() and 0xFFFF
        val screenHeight = buf.short.toInt() and 0xFFFF
        val pressure = u16FixedPointToFloat(buf.short)
        val actionButton = buf.int
        val buttons = buf.int
        return TouchEvent(action, pointerId, x, y, screenWidth, screenHeight, pressure, actionButton, buttons)
    }

    /**
     * Punto fijo sin signo de 16 bits para presión (0..1), estimado a partir del uso en `ControlMessageReader`
     * (`Binary.u16FixedPointToFloat`, no auditado campo por campo — ver `docs/referencias/pc-movil/
     * REFERENCIA_scrcpy.md`, "Control: ... no se leyó campo por campo"). Round-trip propio, no verificado byte
     * a byte contra `Binary.java` real.
     */
    private fun floatToU16FixedPoint(value: Float): Short {
        val clamped = value.coerceIn(0f, 1f)
        return (clamped * 0xFFFF).toInt().coerceIn(0, 0xFFFF).toShort()
    }

    private fun u16FixedPointToFloat(raw: Short): Float {
        val unsigned = raw.toInt() and 0xFFFF
        return unsigned / 0xFFFF.toFloat()
    }
}

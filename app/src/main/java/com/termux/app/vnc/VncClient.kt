package com.termux.app.vnc

import android.graphics.Bitmap
import android.graphics.Canvas
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Cliente VNC (protocolo RFB 3.3/3.8) mínimo pero real — pensado específicamente para conectar
 * al `vncserver`/`tigervncserver` que ya arranca `EntornoNative.vncStart()` en
 * `127.0.0.1:5901` (ver `modulos/entorno.sh:vnc_start.sh`), no un cliente VNC genérico para
 * cualquier servidor de internet.
 *
 * Alcance deliberado (MVP real, no un stub): handshake completo (versión + autenticación VNC
 * con desafío DES, tal cual lo exige TigerVNC por defecto) + `SetPixelFormat` fijo a 32bpp
 * ARGB (para no tener que soportar todos los formatos de píxel posibles) + `SetEncodings` con
 * Raw (0, el único que todo servidor RFB soporta sí o sí) + CopyRect (1, redibujos baratos sin
 * tráfico de píxeles) + DesktopSize (-223, pseudo-encoding sin body, solo para no perder sync
 * si el servidor se resizea). Encodings comprimidos (Hextile/Tight/zlib) siguen sin
 * implementarse — más lento que ellos en redes reales, pero acá el servidor y el cliente
 * corren en el MISMO dispositivo (127.0.0.1), el ancho de banda no es el cuello de botella.
 * Quedan documentados como mejora futura (ver docs/x11/X11_EMBEBIDO.md).
 *
 * No hay librería de referencia local para adaptar (el único proyecto de VNC en `referencia/`
 * es `XoDos2-main/avnc_flutter`, Flutter — explícitamente descartado en este proyecto, que es
 * 100% Kotlin/Java nativo) — implementado desde la especificación real del protocolo RFB
 * (RFC 6143).
 */
class VncClient(
    private val host: String,
    private val port: Int,
    private val passwordProvider: () -> String?,
) {
    interface Listener {
        fun onConnected(width: Int, height: Int)
        fun onFramebufferUpdate(bitmap: Bitmap)
        fun onDisconnected(reason: String?)
        fun onAuthRequired()
        /**
         * ServerCutText (RFB, msg-type=3): el portapapeles del lado servidor (escritorio X11/
         * VNC) cambió. [text] ya viene decodificado — ver nota de codificación en [sendClientCutText].
         */
        fun onServerCutText(text: String)
        /**
         * Autenticación VNC rechazada por el servidor (contraseña incorrecta) — distinto de una
         * caída de red real. Con implementación por defecto que cae a [onDisconnected] para no
         * romper compatibilidad binaria de cualquier otro implementador futuro de [Listener];
         * [com.termux.app.ui.VncViewerActivity] la sobreescribe para volver a pedir la contraseña
         * en vez de disparar la reconexión automática con la misma contraseña ya sabida mala
         * (ver [AuthFailedSignal] — reintentar ciegamente con backoff acá sería un loop inútil).
         */
        fun onAuthFailed(reason: String?) {
            onDisconnected(reason)
        }
    }

    private companion object {
        const val TAG = "VncClient"
        const val ENCODING_RAW = 0
        const val ENCODING_COPY_RECT = 1
        const val ENCODING_DESKTOP_SIZE = -223 // pseudo-encoding, sin bytes de cuerpo
        // Límite defensivo para ServerCutText/ClientCutText — un portapapeles real nunca
        // necesita más que esto; evita un readInt() corrupto/malicioso pidiendo un ByteArray
        // gigante (mismo criterio que los otros bounds de lectura de este archivo, ej. nameLen).
        const val CLIPBOARD_MAX_BYTES = 1_000_000
    }

    /**
     * Cualquier encoding fuera de los que anunciamos en [setEncodings] es una violación del
     * protocolo del servidor — no podemos saber cuántos bytes ocupa su cuerpo sin decodificarlo,
     * así que no existe forma segura de "saltear" ese rect puntual sin perder la sincronización
     * de TODO el stream TCP que sigue. Lo más cerca de "no matar la sesión entera" que es
     * realmente seguro: cortar esta sesión con un mensaje claro y loggeado, en vez de un
     * `IllegalStateException` genérico (el bug original, ver `handleFramebufferUpdate`).
     */
    class VncUnsupportedEncodingException(encoding: Int) : IllegalStateException(
        "Encoding no soportado por este cliente: $encoding — cerrando esta sesión (no se puede " +
            "reasumir sync del stream sin decodificarlo)"
    )

    /**
     * Señal interna para "hace falta contraseña" — NO es un error real: [negotiateSecurity] ya
     * llamó a `listener.onAuthRequired()` (que en [com.termux.app.ui.VncViewerActivity] abre el
     * diálogo de contraseña) antes de lanzar esto. Bug real encontrado en la auditoría: como esa
     * excepción quedaba sin distinguir de un fallo real, subía hasta el catch de [start] y
     * disparaba TAMBIÉN `onDisconnected()` — la Activity mostraba un Toast de error ("VNC: Se
     * requiere contraseña") al mismo tiempo que el diálogo de contraseña recién se abría, con
     * un mensaje que suena a fallo cuando en realidad es el flujo normal de pedir credenciales.
     */
    private class AuthRequiredSignal : Exception()

    /**
     * Señal interna para "contraseña incorrecta" — igual que [AuthRequiredSignal], NO debe
     * reenviarse como [onDisconnected] genérico: [negotiateSecurity] ya llamó a
     * `listener.onAuthFailed(reason)` antes de lanzar esto. Necesaria para que la reconexión
     * automática (ver `VncViewerActivity.scheduleReconnect`) no confunda "contraseña mala" con
     * "caída de red" — si esto cayera en el mismo camino que una desconexión real, el cliente
     * reintentaría conectar en loop con la MISMA contraseña ya sabida incorrecta, gastando los
     * reintentos de backoff sin sentido en vez de volver a pedirle la contraseña al usuario.
     */
    private class AuthFailedSignal(reason: String) : Exception(reason)

    @Volatile private var running = false
    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null
    private var thread: Thread? = null

    var listener: Listener? = null

    var frameWidth = 0; private set
    var frameHeight = 0; private set

    // ── Doble buffer del framebuffer (fix real de auditoría, ver AUDITORIA_VNC_CODIGO_2026-08-19.md
    // sección "Actualización — auto-reconexión y doble buffer") ─────────────────────────────
    // Antes había un único Bitmap mutable: el hilo de sesión VNC lo escribía (setPixels en cada
    // rect de cada FramebufferUpdate) mientras el hilo de UI lo leía (canvas.drawBitmap) al
    // mismo tiempo, sin ninguna sincronización — race de datos real (tearing visual posible).
    // Ahora: [backBitmap] es SIEMPRE el que escribe el hilo de red; [frontBitmap] es SIEMPRE el
    // que se entrega a la UI vía el listener. Al completar un FramebufferUpdate con al menos un
    // rect, [publishFrame] intercambia las referencias bajo [bitmapLock] (swap de punteros, no
    // copia de píxeles — la sección crítica es mínima) y el bitmap que la UI ya tiene en pantalla
    // deja de ser tocado por el hilo de red desde ese instante. Como los rects entrantes pueden
    // ser incrementales (no todo el frame se reescribe cada vez, ej. CopyRect/rects parciales),
    // el nuevo backBitmap (la referencia vieja de frontBitmap, con contenido de 1 frame atrás) se
    // sincroniza con un blit nativo (`Canvas(back).drawBitmap(front, ...)`) FUERA del lock —
    // en ese momento back todavía no es visible para nadie más, solo lo toca este mismo hilo.
    private val bitmapLock = Object()
    private var backBitmap: Bitmap? = null
    private var frontBitmap: Bitmap? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread {
            try {
                runSession()
            } catch (e: AuthRequiredSignal) {
                // Ya notificado vía onAuthRequired() antes de lanzar esto — ver docstring de
                // AuthRequiredSignal. No hace falta (ni conviene) un segundo aviso de error.
            } catch (e: AuthFailedSignal) {
                // Ya notificado vía onAuthFailed() antes de lanzar esto — ver docstring de
                // AuthFailedSignal. No pasa por onDisconnected() para no disparar reconexión
                // automática con la misma contraseña incorrecta.
            } catch (e: Exception) {
                // Cualquier otra excepción real (IOException de red, protocolo desconocido,
                // encoding no soportado, servidor rechazó la conexión) SÍ es una desconexión
                // real — VncViewerActivity la usa como señal para programar un reintento con
                // backoff. Si [stop] ya puso `listener = null` (cierre intencional del usuario),
                // esta llamada es un no-op seguro y no dispara ninguna reconexión.
                listener?.onDisconnected(e.message ?: e.javaClass.simpleName)
            } finally {
                running = false
                closeQuietly()
            }
        }.apply {
            name = "vnc-client"
            start()
        }
    }

    fun stop() {
        // Limpiar el listener ANTES de cerrar el socket es lo que distingue un cierre
        // intencional (el usuario cerró VncViewerActivity, o canceló el diálogo de contraseña)
        // de una desconexión real: cerrar el socket desbloquea la lectura pendiente del hilo de
        // sesión con una IOException, que igual cae en el catch(e: Exception) de [start] — pero
        // con `listener == null` esa llamada a `onDisconnected()` es un no-op seguro, así que
        // VncViewerActivity nunca ve un evento de desconexión (ni dispara su reconexión
        // automática) para algo que el propio usuario pidió cerrar.
        listener = null
        running = false
        closeQuietly()
        thread?.interrupt()
    }

    private fun closeQuietly() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null; input = null; output = null
    }

    // ── Sesión completa: handshake + auth + init + loop de updates ──────────────

    private fun runSession() {
        val sock = Socket(host, port).apply { tcpNoDelay = true }
        socket = sock
        val inp = DataInputStream(BufferedInputStream(sock.getInputStream(), 64 * 1024))
        val out = DataOutputStream(BufferedOutputStream(sock.getOutputStream()))
        input = inp; output = out

        negotiateVersion(inp, out)
        negotiateSecurity(inp, out)
        // ClientInit — shared-flag=1 (compartir la sesión, no expulsar a otros clientes)
        out.writeByte(1)
        out.flush()

        // ServerInit
        val width = inp.readUnsignedShort()
        val height = inp.readUnsignedShort()
        inp.skipBytes(16) // pixel format del servidor — lo ignoramos, forzamos el nuestro abajo
        val nameLen = inp.readInt()
        if (nameLen in 0..65536) inp.skipBytes(nameLen)
        frameWidth = width; frameHeight = height
        // Dos instancias separadas desde el arranque (no una sola compartida) — ver docstring
        // de bitmapLock/backBitmap/frontBitmap más arriba.
        backBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        frontBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        setPixelFormat(out)
        setEncodings(out)
        requestFramebuffer(out, incremental = false)
        listener?.onConnected(width, height)

        while (running) {
            val msgType = inp.readUnsignedByte()
            when (msgType) {
                0 -> handleFramebufferUpdate(inp)
                1 -> { // SetColourMapEntries — no aplica con true-color, pero hay que consumirlo
                    inp.skipBytes(1)
                    val firstColour = inp.readUnsignedShort()
                    val numColours = inp.readUnsignedShort()
                    inp.skipBytes(numColours * 6)
                    @Suppress("UNUSED_EXPRESSION") firstColour
                }
                2 -> { /* Bell — ignorado */ }
                3 -> { // ServerCutText (portapapeles) — sincronizado hacia el clipboard de Android
                    inp.skipBytes(3)
                    val len = inp.readInt()
                    if (len in 0..CLIPBOARD_MAX_BYTES) {
                        val bytes = ByteArray(len)
                        inp.readFully(bytes)
                        // RFC 6143 §7.5.6: ServerCutText siempre viaja en Latin-1 (ISO 8859-1) —
                        // el protocolo RFB estándar no tiene forma de anunciar UTF-8 (algunos
                        // servidores lo mandan igual si el texto original era ASCII/Latin-1
                        // puro, pero unicode fuera de ese rango se pierde en el propio servidor,
                        // no en este cliente — limitación real del protocolo, no un bug local).
                        listener?.onServerCutText(String(bytes, Charsets.ISO_8859_1))
                    } else if (len > 0) {
                        inp.skipBytes(len)
                    }
                }
                else -> throw IllegalStateException("Mensaje de servidor desconocido: $msgType")
            }
        }
    }

    private fun negotiateVersion(inp: DataInputStream, out: DataOutputStream) {
        val versionBytes = ByteArray(12)
        inp.readFully(versionBytes)
        // Respondemos siempre 3.8 — todo servidor RFB real (incluido TigerVNC) acepta esto
        // aunque haya anunciado una versión menor, es el comportamiento estándar de cliente.
        out.write("RFB 003.008\n".toByteArray(Charsets.US_ASCII))
        out.flush()
    }

    private fun negotiateSecurity(inp: DataInputStream, out: DataOutputStream) {
        val numTypes = inp.readUnsignedByte()
        if (numTypes == 0) {
            // RFB 3.8: si numTypes==0, sigue un string de razón de fallo (u32 len + texto)
            val len = inp.readInt()
            val reason = if (len in 0..4096) String(ByteArray(len).also { inp.readFully(it) }) else "desconocida"
            throw IllegalStateException("Servidor rechazó la conexión: $reason")
        }
        val types = ByteArray(numTypes)
        inp.readFully(types)
        val chosen = when {
            types.contains(2.toByte()) -> 2 // VNC Authentication (desafío DES)
            types.contains(1.toByte()) -> 1 // None
            else -> throw IllegalStateException("El servidor no ofrece un método de autenticación soportado (${types.joinToString()})")
        }
        out.writeByte(chosen)
        out.flush()

        if (chosen == 2) {
            val challenge = ByteArray(16)
            inp.readFully(challenge)
            val password = passwordProvider() ?: run {
                listener?.onAuthRequired()
                throw AuthRequiredSignal()
            }
            val response = desEncryptChallenge(challenge, password)
            out.write(response)
            out.flush()
        }

        // SecurityResult — presente siempre en 3.8 (para "None" también, a diferencia de 3.3)
        val result = inp.readInt()
        if (result != 0) {
            val len = inp.readInt()
            val reason = if (len in 0..4096) String(ByteArray(len).also { inp.readFully(it) }) else "autenticación rechazada"
            val fullReason = "Autenticación VNC falló: $reason"
            // Solo esta rama (contraseña efectivamente rechazada por el servidor, no una caída
            // de red) usa onAuthFailed()/AuthFailedSignal — ver docstrings de ambos arriba.
            listener?.onAuthFailed(fullReason)
            throw AuthFailedSignal(fullReason)
        }
    }

    /**
     * DES del desafío VNC — particularidad real del protocolo (no es DES estándar con la
     * contraseña tal cual): cada byte de la clave (contraseña, truncada/rellenada a 8 bytes)
     * se usa con sus BITS EN ORDEN INVERSO antes de armar la SecretKeySpec, herencia histórica
     * del algoritmo original de RealVNC. Sin este detalle la autenticación falla silenciosamente
     * contra cualquier servidor real (confirmado contra la especificación RFB, RFC 6143 §7.2.2).
     */
    private fun desEncryptChallenge(challenge: ByteArray, password: String): ByteArray {
        val keyBytes = ByteArray(8)
        val pwBytes = password.toByteArray(Charsets.US_ASCII)
        for (i in 0 until 8) keyBytes[i] = if (i < pwBytes.size) reverseBits(pwBytes[i]) else 0
        val cipher = Cipher.getInstance("DES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "DES"))
        return cipher.doFinal(challenge)
    }

    private fun reverseBits(b: Byte): Byte {
        var v = b.toInt() and 0xFF
        var r = 0
        for (i in 0 until 8) {
            r = (r shl 1) or (v and 1)
            v = v shr 1
        }
        return r.toByte()
    }

    private fun setPixelFormat(out: DataOutputStream) {
        out.writeByte(0) // message-type SetPixelFormat
        out.write(ByteArray(3)) // padding
        out.writeByte(32) // bits-per-pixel
        out.writeByte(24) // depth
        out.writeByte(1) // big-endian-flag (network order — coincide con los shifts de abajo)
        out.writeByte(1) // true-colour-flag
        out.writeShort(255); out.writeShort(255); out.writeShort(255) // red/green/blue-max
        out.writeByte(16); out.writeByte(8); out.writeByte(0) // red/green/blue-shift
        out.write(ByteArray(3)) // padding
        out.flush()
    }

    private fun setEncodings(out: DataOutputStream) {
        out.writeByte(2) // message-type SetEncodings
        out.writeByte(0) // padding
        out.writeShort(3) // number-of-encodings
        out.writeInt(ENCODING_RAW)
        out.writeInt(ENCODING_COPY_RECT)
        out.writeInt(ENCODING_DESKTOP_SIZE)
        out.flush()
    }

    private fun requestFramebuffer(out: DataOutputStream, incremental: Boolean) {
        out.writeByte(3) // message-type FramebufferUpdateRequest
        out.writeByte(if (incremental) 1 else 0)
        out.writeShort(0); out.writeShort(0)
        out.writeShort(frameWidth); out.writeShort(frameHeight)
        out.flush()
    }

    private fun handleFramebufferUpdate(inp: DataInputStream) {
        inp.skipBytes(1) // padding
        val numRects = inp.readUnsignedShort()
        val bmp = backBitmap ?: return
        val row = IntArray(frameWidth)
        repeat(numRects) {
            val x = inp.readUnsignedShort()
            val y = inp.readUnsignedShort()
            val w = inp.readUnsignedShort()
            val h = inp.readUnsignedShort()
            when (val encoding = inp.readInt()) {
                ENCODING_RAW -> readRawRect(inp, bmp, row, x, y, w, h)
                ENCODING_COPY_RECT -> copyRect(inp, bmp, x, y, w, h)
                ENCODING_DESKTOP_SIZE ->
                    // Pseudo-encoding sin bytes de cuerpo: el servidor avisa que cambió de
                    // tamaño (p.ej. resize de la ventana X11). Redimensionar el bitmap en vivo
                    // queda fuera de este MVP, pero como no trae payload no desincroniza el
                    // stream — solo lo registramos y seguimos con el resto de la actualización.
                    android.util.Log.w(TAG, "Servidor pidió DesktopSize (no soportado todavía): ${w}x$h")
                else -> {
                    // Bug real corregido acá (ver auditoría): antes esto era un throw crudo que
                    // mataba la sesión ENTERA por cualquier rect inesperado, incluso perdiendo
                    // los rects ya procesados en esta misma actualización sin loggear nada útil.
                    // Seguimos sin poder "saltear" el rect con seguridad (no conocemos el tamaño
                    // de su cuerpo sin decodificarlo — ver VncUnsupportedEncodingException) pero
                    // ahora queda loggeado con contexto real antes de cerrar la sesión.
                    android.util.Log.w(TAG, "Rect con encoding no soportado en ($x,$y ${w}x$h): $encoding")
                    throw VncUnsupportedEncodingException(encoding)
                }
            }
        }
        // Con numRects==0 no hubo ninguna escritura real en backBitmap — no hace falta swap ni
        // aviso a la UI, el frame que ya está en pantalla sigue siendo válido tal cual.
        if (numRects > 0) {
            listener?.onFramebufferUpdate(publishFrame())
        }
        if (running) requestFramebuffer(output ?: return, incremental = true)
    }

    /**
     * Intercambia [backBitmap] (recién escrito por este mismo hilo, contenido completo y
     * consistente del frame actual) con [frontBitmap] (lo que la UI venía mostrando) y devuelve
     * la nueva referencia "front" para entregarle al listener. La sección bajo [bitmapLock] es
     * solo el intercambio de 2 referencias — O(1), no una copia de píxeles — para no introducir
     * contención real con el hilo de red en cada FramebufferUpdate.
     *
     * Después del swap, el nuevo [backBitmap] (la referencia vieja de frontBitmap) tiene
     * contenido de un frame atrás — como los rects entrantes pueden ser parciales/incrementales
     * (CopyRect referencia regiones ya presentes en el framebuffer, rects Raw pueden cubrir solo
     * parte de la pantalla), hace falta sincronizarlo con el contenido actual ANTES de que el
     * próximo FramebufferUpdate empiece a escribir sobre él — si no, las regiones no tocadas por
     * el próximo update quedarían con píxeles de 2 frames atrás en vez del último frame real.
     * Este blit corre fuera del lock: en este punto [backBitmap] todavía no es visible para
     * ningún otro hilo (solo se publica recién en el PRÓXIMO swap), así que no hace falta
     * protegerlo — y leer [frontBitmap] concurrentemente desde el hilo de UI (para dibujarlo) es
     * seguro porque ninguna escritura está ocurriendo sobre esa misma instancia en este momento.
     */
    private fun publishFrame(): Bitmap {
        synchronized(bitmapLock) {
            val old = frontBitmap!!
            frontBitmap = backBitmap!!
            backBitmap = old
        }
        val back = backBitmap!!
        val front = frontBitmap!!
        Canvas(back).drawBitmap(front, 0f, 0f, null)
        return front
    }

    private fun readRawRect(inp: DataInputStream, bmp: Bitmap, row: IntArray, x: Int, y: Int, w: Int, h: Int) {
        for (ry in 0 until h) {
            for (rx in 0 until w) {
                // Con big-endian=1 + shifts red=16/green=8/blue=0, el servidor manda los
                // 4 bytes de cada píxel en orden [X, R, G, B] (MSB primero) — armamos
                // directo un int 0xAARRGGBB forzando alpha=0xFF (X del servidor se descarta,
                // no representa transparencia real en una sesión de escritorio X11/VNC).
                val b0 = inp.readUnsignedByte()
                val r = inp.readUnsignedByte()
                val g = inp.readUnsignedByte()
                val b = inp.readUnsignedByte()
                @Suppress("UNUSED_EXPRESSION") b0
                row[rx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            if (x + w <= frameWidth && y + ry < frameHeight) {
                bmp.setPixels(row, 0, frameWidth, x, y + ry, w, 1)
            }
        }
    }

    /**
     * CopyRect (encoding=1) — el servidor no manda píxeles nuevos: el rectángulo destino
     * (x,y,w,h) es una copia de otra región YA presente en el framebuffer local, identificada
     * por (srcX,srcY) — RFC 6143 §7.7.2. avnc-master soporta este mismo encoding (vía su
     * pipeline GL en `ui/vnc/gl/`); acá lo hacemos con `getPixels`/`setPixels` sobre el Bitmap
     * porque este cliente no usa GL.
     */
    private fun copyRect(inp: DataInputStream, bmp: Bitmap, x: Int, y: Int, w: Int, h: Int) {
        val srcX = inp.readUnsignedShort()
        val srcY = inp.readUnsignedShort()
        if (w <= 0 || h <= 0) return
        if (srcX + w > frameWidth || srcY + h > frameHeight || x + w > frameWidth || y + h > frameHeight) return
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, srcX, srcY, w, h)
        bmp.setPixels(pixels, 0, w, x, y, w, h)
    }

    // ── Entrada del usuario → eventos RFB ────────────────────────────────────

    /**
     * buttonMask: bit0=izquierdo(1), bit1=medio(2), bit2=derecho(4), bit3=WheelUp(8),
     * bit4=WheelDown(16), bit5=WheelLeft(32), bit6=WheelRight(64) — estándar RFB PointerEvent,
     * confirmado contra `PointerButton.kt` de avnc-master (mismos valores de bitMask).
     */
    @Synchronized
    fun sendPointerEvent(x: Int, y: Int, buttonMask: Int) {
        val out = output ?: return
        try {
            out.writeByte(5)
            out.writeByte(buttonMask)
            out.writeShort(x.coerceIn(0, frameWidth - 1))
            out.writeShort(y.coerceIn(0, frameHeight - 1))
            out.flush()
        } catch (_: Exception) {}
    }

    /**
     * ClientCutText (RFB, msg-type=6): sincroniza el portapapeles de Android hacia el servidor
     * (dirección opuesta a [Listener.onServerCutText]). Mismo límite de codificación que el
     * lado de recepción — Latin-1/ISO 8859-1 (RFC 6143 §7.5.6), el propio protocolo RFB
     * estándar no soporta UTF-8 acá. Caracteres fuera de ese rango (emoji, CJK, etc.) se
     * reemplazan por '?' en vez de fallar silenciosamente o cortar la sesión — limitación real
     * del protocolo, documentada, no un intento fallido de "hacerlo bien".
     */
    @Synchronized
    fun sendClientCutText(text: String) {
        val out = output ?: return
        if (text.isEmpty()) return
        try {
            val encoder = Charsets.ISO_8859_1.newEncoder().apply {
                onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPLACE)
                onMalformedInput(java.nio.charset.CodingErrorAction.REPLACE)
                replaceWith(byteArrayOf('?'.code.toByte()))
            }
            val bytes = encoder.encode(java.nio.CharBuffer.wrap(text)).let {
                ByteArray(it.remaining()).also { arr -> it.get(arr) }
            }
            if (bytes.size > CLIPBOARD_MAX_BYTES) return
            out.writeByte(6) // message-type ClientCutText
            out.write(ByteArray(3)) // padding
            out.writeInt(bytes.size)
            out.write(bytes)
            out.flush()
        } catch (_: Exception) {
            // No corta la sesión por un fallo de portapapeles — solo esa sincronización puntual
            // se pierde, el resto de la sesión VNC sigue funcionando.
        }
    }

    @Synchronized
    fun sendKeyEvent(keysym: Int, down: Boolean) {
        val out = output ?: return
        try {
            out.writeByte(4)
            out.writeByte(if (down) 1 else 0)
            out.writeShort(0)
            out.writeInt(keysym)
            out.flush()
        } catch (_: Exception) {}
    }
}

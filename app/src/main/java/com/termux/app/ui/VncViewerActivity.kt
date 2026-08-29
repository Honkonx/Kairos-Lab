package com.termux.app.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.termux.R
import com.termux.app.vnc.VncCanvasView
import com.termux.app.vnc.VncClient

/**
 * Visor VNC embebido — pedido explícito del usuario (ver `docs/humano/humano136.md`): en vez
 * de depender de una app VNC externa para conectarse al `vncserver` que ya arranca
 * `EntornoNative.vncStart()` en `127.0.0.1:5901`, esta Activity conecta directo con
 * `VncClient` (implementación propia del protocolo RFB, ver ese archivo) y dibuja el
 * framebuffer en vivo con `VncCanvasView`.
 *
 * Se abre desde `EntornoFragment` ("🖵 Abrir visor VNC", fusión 2026-08-25 — antes
 * `X11Fragment.openVnc()`) — asume que el llamador ya se aseguró de que el servidor VNC esté
 * instalado/corriendo (mismo patrón que "Entrar en X11" asume que X11Service ya está arrancado).
 */
class VncViewerActivity : Activity() {

    companion object {
        /**
         * Puerto VNC a conectar — extra opcional, default 5901 (TigerVNC de Mini PC/Entorno,
         * ver `EntornoNative.vncStart()`). Agregado 2026-08-26 para reusar este mismo visor
         * desde el módulo QEMU (`QemuFragment.bootVm()` en modo VNC, servidor QEMU en el
         * display :2 → puerto 5902, ver `modulos/qemu.sh` `run_vm.sh`) sin escribir un cliente
         * VNC nuevo — ambos servidores hablan el mismo protocolo RFB que ya implementa
         * `VncClient`.
         */
        const val EXTRA_PORT = "vnc_port"

        // Backoff pedido explícitamente: 2s, 4s, 8s — tope de reintentos antes de rendirse y
        // mostrar el error real (no reintentar para siempre en silencio).
        private val RECONNECT_DELAYS_MS = longArrayOf(2000L, 4000L, 8000L, 8000L, 8000L)

        // Keysyms X11 de teclas de control reusados tanto por el puente de teclado físico
        // (onKeyDown/onKeyUp, ver androidKeyToX11Keysym) como por el puente de teclado software
        // (ver setupSoftKeyboardBridge) — una sola fuente de verdad, DRY.
        private const val KEYSYM_ENTER = 0xFF0D
        private const val KEYSYM_BACKSPACE = 0xFF08

        // Placeholder que se mantiene siempre presente en [keyboardInput] para poder distinguir
        // "el usuario borró un carácter" (texto queda vacío) de "todavía no escribió nada" — ver
        // docstring de setupSoftKeyboardBridge() para el porqué.
        private const val KEYBOARD_PLACEHOLDER = " "
    }

    private lateinit var canvas: VncCanvasView
    private lateinit var statusText: TextView
    private lateinit var keyboardInput: EditText
    // Guarda de reentrancia: setupSoftKeyboardBridge() edita [keyboardInput] a mano dentro de su
    // propio TextWatcher (para reponer el placeholder) — sin esto, ese mismo replace() dispara
    // afterTextChanged() de nuevo de forma recursiva sobre un texto que ya procesamos.
    private var isSyncingKeyboardInput = false
    private var client: VncClient? = null
    private val port: Int by lazy { intent.getIntExtra(EXTRA_PORT, 5901) }
    @Volatile private var pendingPassword: String? = null
    private val passwordLock = Object()

    // ── Reconexión automática con backoff (fix real de auditoría, ver
    // AUDITORIA_VNC_CODIGO_2026-08-19.md sección "Actualización — auto-reconexión y doble
    // buffer") ─────────────────────────────────────────────────────────────────────────────
    // Antes: cualquier caída de conexión (red inestable, vncserver se reinicia del lado proot)
    // solo mostraba un Toast + "Desconectado: <motivo>" y el usuario tenía que cerrar y volver a
    // abrir el visor a mano. [manuallyClosing] es lo que distingue un cierre real (el usuario
    // tocó "Cerrar" o "Cancelar" en el diálogo de contraseña, o la Activity se está destruyendo)
    // de uno inesperado — VncClient.stop() ya limpia su propio listener antes de cerrar el
    // socket (ver docstring ahí), así que un cierre intencional NUNCA llega a onDisconnected();
    // cualquier onDisconnected() que SÍ se dispare es, por construcción, una desconexión real.
    private val reconnectHandler = Handler(Looper.getMainLooper())
    private var reconnectRunnable: Runnable? = null
    private var reconnectAttempt = 0
    private var manuallyClosing = false

    // Sincronización de portapapeles Android ↔ servidor VNC (ver VncClient.sendClientCutText/
    // Listener.onServerCutText). [applyingServerClip] evita el eco: cuando escribimos al
    // portapapeles de Android PORQUE el servidor lo mandó, ClipboardManager dispara
    // onPrimaryClipChanged() igual que si lo hubiera tocado el usuario — sin este guard,
    // reenviaríamos ese mismo texto de vuelta al servidor en un loop inútil (no infinito, pero
    // sí tráfico y trabajo de más en cada cambio real).
    private var clipboardManager: ClipboardManager? = null
    @Volatile private var applyingServerClip = false
    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        if (applyingServerClip) return@OnPrimaryClipChangedListener
        val text = clipboardManager?.primaryClip?.let { clip ->
            if (clip.itemCount > 0) clip.getItemAt(0).coerceToText(this)?.toString() else null
        }
        if (!text.isNullOrEmpty()) client?.sendClientCutText(text)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_vnc_viewer)

        canvas = findViewById(R.id.vnc_canvas)
        statusText = findViewById(R.id.vnc_status_text)
        keyboardInput = findViewById(R.id.vnc_keyboard_input)
        findViewById<TextView>(R.id.btn_vnc_close).setOnClickListener {
            manuallyClosing = true
            cancelReconnect()
            finish()
        }
        findViewById<TextView>(R.id.btn_vnc_keyboard).setOnClickListener { openSoftKeyboard() }
        setupSoftKeyboardBridge()

        clipboardManager = (getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager)?.also {
            it.addPrimaryClipChangedListener(clipboardListener)
        }

        connect()
    }

    private fun connect() {
        statusText.text = getString(R.string.vnc_viewer_connecting, port)
        val c = VncClient(
            host = "127.0.0.1",
            port = port,
            passwordProvider = { waitForPassword() },
        )
        c.listener = object : VncClient.Listener {
            override fun onConnected(width: Int, height: Int) {
                runOnUiThread {
                    statusText.text = ""
                    // Conexión (re)establecida con éxito — se perdona cualquier racha previa de
                    // reintentos fallidos, el próximo corte empieza su propio backoff desde cero.
                    reconnectAttempt = 0
                }
            }
            override fun onFramebufferUpdate(bitmap: android.graphics.Bitmap) {
                runOnUiThread { canvas.setFrame(bitmap) }
            }
            override fun onDisconnected(reason: String?) {
                runOnUiThread { scheduleReconnect(reason) }
            }
            override fun onAuthRequired() {
                runOnUiThread { promptPassword() }
            }
            override fun onAuthFailed(reason: String?) {
                // Contraseña rechazada por el servidor — NO es una caída de red, reintentar con
                // backoff repetiría la misma contraseña mala en loop. Se vuelve a pedir en vez
                // de tocar reconnectAttempt/scheduleReconnect.
                runOnUiThread {
                    synchronized(passwordLock) { pendingPassword = null }
                    Toast.makeText(this@VncViewerActivity, getString(R.string.vnc_viewer_wrong_password), Toast.LENGTH_LONG).show()
                    promptPassword()
                }
            }
            override fun onServerCutText(text: String) {
                runOnUiThread {
                    val cm = clipboardManager ?: return@runOnUiThread
                    applyingServerClip = true
                    cm.setPrimaryClip(ClipData.newPlainText("VNC", text))
                    // setPrimaryClip() dispara OnPrimaryClipChangedListener por un callback
                    // Binder que llega al hilo principal, no necesariamente de forma síncrona
                    // dentro de esta misma llamada — despachar el reset del guard en un post()
                    // (mismo hilo, siguiente vuelta del message loop) en vez de limpiarlo acá
                    // mismo asegura que ya haya llegado antes de bajar la guarda.
                    canvas.post { applyingServerClip = false }
                }
            }
        }
        canvas.client = c
        client = c
        c.start()
    }

    /**
     * Se llama SOLO desde `onDisconnected()` — por construcción, cualquier llamada acá es una
     * desconexión real (ver docstring de [manuallyClosing]/[VncClient.stop] arriba), nunca un
     * cierre intencional del usuario. `canvas.setFrame()` no se toca acá a propósito: el último
     * frame recibido se queda congelado en pantalla mientras se reintenta, en vez de dejar al
     * usuario con una vista en blanco sin explicación — el `statusText` es el feedback real.
     */
    private fun scheduleReconnect(reason: String?) {
        if (manuallyClosing || isFinishing) return
        if (reconnectAttempt >= RECONNECT_DELAYS_MS.size) {
            statusText.text = if (reason != null) {
                getString(R.string.vnc_viewer_status_disconnected_with_reason, reason)
            } else {
                getString(R.string.vnc_viewer_status_disconnected_no_reason)
            }
            Toast.makeText(this, getString(R.string.vnc_viewer_toast_reconnect_failed, reason ?: getString(R.string.vnc_viewer_reason_connection_lost)), Toast.LENGTH_LONG).show()
            return
        }
        val delayMs = RECONNECT_DELAYS_MS[reconnectAttempt]
        reconnectAttempt++
        val seconds = delayMs / 1000
        statusText.text = if (reason != null) {
            getString(R.string.vnc_viewer_status_reconnecting_with_reason, reason, seconds, reconnectAttempt, RECONNECT_DELAYS_MS.size)
        } else {
            getString(R.string.vnc_viewer_status_reconnecting_no_reason, seconds, reconnectAttempt, RECONNECT_DELAYS_MS.size)
        }
        val runnable = Runnable {
            reconnectRunnable = null
            if (!manuallyClosing && !isFinishing) connect()
        }
        reconnectRunnable = runnable
        reconnectHandler.postDelayed(runnable, delayMs)
    }

    private fun cancelReconnect() {
        reconnectRunnable?.let { reconnectHandler.removeCallbacks(it) }
        reconnectRunnable = null
    }

    // No bloquea — devuelve la contraseña ya conocida (si el usuario ya la tipeó en un intento
    // anterior) o null. VncClient trata null como "pedila" (onAuthRequired()) y corta esa
    // sesión; connect() arma una sesión NUEVA una vez que el diálogo la setea (ver
    // promptPassword()), en vez de dejar el hilo viejo esperando indefinidamente.
    private fun waitForPassword(): String? = synchronized(passwordLock) { pendingPassword }

    private fun promptPassword() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = getString(R.string.vnc_viewer_password_hint)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.vnc_viewer_password_title))
            .setView(input)
            .setPositiveButton(getString(R.string.vnc_viewer_btn_connect)) { _, _ ->
                synchronized(passwordLock) { pendingPassword = input.text.toString() }
                connect() // reintenta la sesión completa con la contraseña ya disponible
            }
            .setNegativeButton(getString(R.string.vnc_viewer_btn_cancel)) { _, _ ->
                manuallyClosing = true
                cancelReconnect()
                finish()
            }
            .setCancelable(false)
            .show()
    }

    // ── Puente de teclado software (botón "⌨") ───────────────────────────────
    //
    // Por qué existe además de onKeyDown/onKeyUp: la mayoría de los teclados software de Android
    // NO generan KeyEvent reales para caracteres alfanuméricos — los entregan vía
    // InputConnection.commitText() sobre la vista con foco (ver InputConnectionWrapper/
    // BaseInputConnection). Sin una vista de texto real con foco, no hay forma de interceptar
    // eso — por eso KairosX11MainActivity (mismo problema, resuelto distinto porque ahí sí existe
    // un View con foco propio dentro del visor X11 embebido) no alcanza acá: VncCanvasView no es
    // una vista de texto, así que se agrega [keyboardInput] (EditText oculto de 1x1dp, ver
    // activity_vnc_viewer.xml) solo para poder recibir ese commitText() y traducirlo a eventos
    // RFB reales via VncClient.sendKeyEvent() — el mismo mecanismo que ya usa onKeyDown/onKeyUp
    // para teclado físico, solo que alimentado desde otra fuente.
    private fun openSoftKeyboard() {
        // isFocusable/isFocusableInTouchMode se activan acá y SOLO acá (ver comentario en
        // activity_vnc_viewer.xml sobre por qué el EditText arranca no-focusable) — habilitarlos
        // justo antes de pedir foco, en vez de dejarlos siempre en true, es lo que evita que
        // Android le dé foco de ventana automático a este EditText al abrir la Activity y le robe
        // los KeyEvent físicos a onKeyDown()/onKeyUp() antes de que el usuario toque el ⌨.
        keyboardInput.isFocusable = true
        keyboardInput.isFocusableInTouchMode = true
        keyboardInput.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(keyboardInput, InputMethodManager.SHOW_IMPLICIT)
    }

    /**
     * Contraparte de [openSoftKeyboard]: vuelve a dejar [keyboardInput] no-focusable apenas
     * pierde el foco (teclado software cerrado, o el usuario toca la superficie VNC) para que el
     * teclado físico vuelva a llegar a onKeyDown()/onKeyUp() de la Activity en vez de quedar
     * atrapado ahí de nuevo en el siguiente onWindowFocusChanged().
     */
    private fun releaseKeyboardInputFocus() {
        keyboardInput.isFocusable = false
        keyboardInput.isFocusableInTouchMode = false
    }

    /**
     * [keyboardInput] siempre contiene [KEYBOARD_PLACEHOLDER] (nunca queda realmente vacío) —
     * es lo que permite distinguir "el usuario borró un carácter" (el placeholder desaparece,
     * texto queda vacío) de "todavía no hay nada escrito" (que con un EditText real vacío no se
     * podría distinguir de una eliminación, porque afterTextChanged simplemente no dispara sobre
     * un campo que ya estaba vacío al presionar Backspace de nuevo).
     */
    private fun setupSoftKeyboardBridge() {
        keyboardInput.setText(KEYBOARD_PLACEHOLDER)
        keyboardInput.setSelection(keyboardInput.length())
        keyboardInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable) {
                if (isSyncingKeyboardInput) return
                isSyncingKeyboardInput = true
                when {
                    s.length > 1 -> {
                        // Caracteres nuevos tras el placeholder (puede ser más de uno con
                        // autocompletado/pegado) — se envían todos y se repone el placeholder.
                        s.substring(1).forEach { sendPrintableChar(it) }
                        s.replace(0, s.length, KEYBOARD_PLACEHOLDER)
                    }
                    s.isEmpty() -> {
                        // El propio placeholder fue borrado — Backspace real del usuario.
                        sendSyntheticKeyPress(KEYSYM_BACKSPACE)
                        s.replace(0, 0, KEYBOARD_PLACEHOLDER)
                    }
                    // s.length == 1: sigue siendo el placeholder tal cual, nada que hacer.
                }
                isSyncingKeyboardInput = false
            }
        })
        keyboardInput.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) releaseKeyboardInputFocus() }
        keyboardInput.setOnEditorActionListener { _, actionId, event ->
            val isEnter = actionId == EditorInfo.IME_ACTION_DONE ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (isEnter) {
                sendSyntheticKeyPress(KEYSYM_ENTER)
                true
            } else {
                false
            }
        }
    }

    private fun sendPrintableChar(ch: Char) {
        // Mismo criterio que androidKeyToX11Keysym: para ASCII imprimible el keysym X11
        // coincide con el propio código del carácter. Fuera de ese rango (acentos, emoji, CJK)
        // se descarta en silencio — mismo alcance MVP documentado ahí, no cubierto todavía.
        val code = ch.code
        if (code in 0x20..0x7E) sendSyntheticKeyPress(code)
    }

    private fun sendSyntheticKeyPress(keysym: Int) {
        client?.sendKeyEvent(keysym, true)
        client?.sendKeyEvent(keysym, false)
    }

    // Teclado físico/software básico — mapeo ASCII directo a keysym X11 (los keysyms de
    // caracteres imprimibles coinciden 1:1 con su código ASCII, ver X11/keysymdef.h) + las
    // teclas de control más comunes. No cubre teclas muertas/acentos ni layouts no-QWERTY —
    // alcance MVP, documentado como pendiente.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val keysym = androidKeyToX11Keysym(keyCode, event) ?: return super.onKeyDown(keyCode, event)
        client?.sendKeyEvent(keysym, true)
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val keysym = androidKeyToX11Keysym(keyCode, event) ?: return super.onKeyUp(keyCode, event)
        client?.sendKeyEvent(keysym, false)
        return true
    }

    private fun androidKeyToX11Keysym(keyCode: Int, event: KeyEvent): Int? {
        val unicode = event.unicodeChar
        return when {
            keyCode == KeyEvent.KEYCODE_ENTER -> KEYSYM_ENTER
            keyCode == KeyEvent.KEYCODE_DEL -> KEYSYM_BACKSPACE
            keyCode == KeyEvent.KEYCODE_FORWARD_DEL -> 0xFFFF // Delete
            keyCode == KeyEvent.KEYCODE_TAB -> 0xFF09
            keyCode == KeyEvent.KEYCODE_ESCAPE -> 0xFF1B
            keyCode == KeyEvent.KEYCODE_DPAD_LEFT -> 0xFF51
            keyCode == KeyEvent.KEYCODE_DPAD_RIGHT -> 0xFF53
            keyCode == KeyEvent.KEYCODE_DPAD_UP -> 0xFF52
            keyCode == KeyEvent.KEYCODE_DPAD_DOWN -> 0xFF54
            keyCode == KeyEvent.KEYCODE_SPACE -> 0x0020
            unicode in 0x20..0x7E -> unicode // ASCII imprimible = mismo valor que su keysym X11
            else -> null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        manuallyClosing = true
        cancelReconnect()
        clipboardManager?.removePrimaryClipChangedListener(clipboardListener)
        client?.stop()
    }
}

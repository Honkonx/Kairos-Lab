package com.termux.app

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.termux.R
import com.termux.app.util.AdbPairingRunner
import kotlin.math.abs

/**
 * Ventana flotante mínima para escribir el puerto/código/puerto de conexión del emparejamiento
 * ADB inalámbrico SIN tener que volver a Kairos — pedido explícito del usuario (ver
 *): "la configuracion via adb [...] con la pantalla de notificaciones y
 * flotante para poner el codigo". El código de 6 dígitos y los puertos los muestra Android en
 * Ajustes → Sistema → Opciones de desarrollador → Depuración inalámbrica — esta ventana queda
 * flotando ARRIBA de esa pantalla de Ajustes para poder leer y escribir al mismo tiempo, sin
 * cambiar de app. Mismo patrón de overlay que [FloatingWidgetService]/[AgentAvatarService]
 * (`hasOverlayPermission`/`overlayType`), la lógica real de pair+connect es
 * [AdbPairingRunner.pairAndConnect] — la misma que usa el diálogo normal de
 * `ConfigFragment.showAdbPairingStep2()`, para no duplicarla.
 */
class AdbPairingOverlayService : Service() {

    companion object {
        const val EXTRA_PAIRING_PORT = "pairing_port"
        const val EXTRA_CONNECT_PORT = "connect_port"
    }

    private lateinit var windowManager: WindowManager
    private var panelView: View? = null
    private var statusView: TextView? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (!hasOverlayPermission()) {
            Toast.makeText(this, getString(R.string.config_permission_adb_floating_needs_overlay), Toast.LENGTH_LONG).show()
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (panelView == null) {
            addPanel(
                intent?.getStringExtra(EXTRA_PAIRING_PORT).orEmpty(),
                intent?.getStringExtra(EXTRA_CONNECT_PORT).orEmpty()
            )
        }
        return START_NOT_STICKY
    }

    private fun hasOverlayPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || android.provider.Settings.canDrawOverlays(this)

    @Suppress("DEPRECATION")
    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_PHONE

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun addPanel(prefillPairingPort: String, prefillConnectPort: String) {
        val pairingPortInput = EditText(this).apply {
            hint = getString(R.string.monitor_hint_pairing_port)
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(prefillPairingPort)
        }
        val codeInput = EditText(this).apply {
            hint = getString(R.string.monitor_hint_code)
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        // Opcional (2026-10-31): "salen 3 casillas cuando debería ser dos" —
        // AdbPairingRunner ahora detecta este puerto solo vía `adb mdns services`, mismo
        // criterio que Shizuku en Android 15+ (activar depuración + un botón, sin leer/tipear
        // puertos a mano). Queda como escape hatch con su propio hint "opcional" para cuando
        // mDNS no encuentre nada — nunca obligatorio.
        val connectPortInput = EditText(this).apply {
            hint = getString(R.string.monitor_hint_connect_port_optional)
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(prefillConnectPort)
        }
        val status = TextView(this).apply {
            text = getString(R.string.config_permission_adb_floating_hint)
            textSize = 12f
            setTextColor(Color.WHITE)
            setPadding(0, dp(4), 0, dp(8))
        }
        statusView = status

        val pairButton = Button(this).apply { text = getString(R.string.config_permission_pair) }
        val closeButton = Button(this).apply { text = getString(R.string.config_btn_close) }
        val buttonsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(pairButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(closeButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#E6202020"))
            setPadding(dp(16), dp(12), dp(16), dp(12))
            addView(TextView(this@AdbPairingOverlayService).apply {
                text = getString(R.string.config_permission_adb_step2_title)
                setTextColor(Color.WHITE)
                textSize = 14f
                setPadding(0, 0, 0, dp(8))
            })
            addView(status)
            addView(pairingPortInput)
            addView(codeInput)
            addView(connectPortInput)
            addView(buttonsRow)
        }

        // Bug real reportado por el usuario (2026-10-31): el teclado nunca
        // abría al tocar los campos de esta ventana flotante. Causa raíz confirmada:
        // FLAG_NOT_FOCUSABLE le prohíbe a Android darle foco de teclado a CUALQUIER ventana con
        // ese flag — sin foco, el IME nunca se asocia. El comentario original (ver cabecera de
        // la clase) pensaba evitar robarle el foco a la pantalla de Ajustes de atrás, pero eso
        // hacía que jamás se pudiera escribir acá — contradicción real del diseño. Sin el flag,
        // la ventana puede recibir foco (y el usuario la usa de forma secuencial: lee el código
        // en Ajustes, toca esta ventana, escribe — nunca simultáneo, no hace falta "no robar
        // foco nunca").
        //
        // Segundo bug real reportado (2026-10-31): con flags=0, esta ventana
        // (focusable, sin FLAG_NOT_TOUCH_MODAL) se vuelve "touch modal" — Android le manda TODO
        // el touch de la pantalla a ella, incluso fuera de sus 280dp de ancho, dejando Ajustes
        // de atrás completamente sin respuesta al tacto (justo lo que el comentario de arriba
        // pensaba evitar, pero el flag que realmente lo resuelve no es FLAG_NOT_FOCUSABLE —
        // es FLAG_NOT_TOUCH_MODAL, que deja pasar los toques FUERA del rectángulo de esta
        // ventana hacia la ventana de atrás, sin afectar el foco de teclado para nada).
        val params = WindowManager.LayoutParams(
            dp(280), WindowManager.LayoutParams.WRAP_CONTENT, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(24); y = dp(120)
        }

        // Arrastre por el título (igual patrón que FloatingWidgetService.moveBubbleNearAvatar):
        // mover la ventana entera, nunca ningún EditText adentro pierde foco por el drag porque
        // el listener vive en el contenedor, no en los campos.
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        container.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> { downX = event.rawX; downY = event.rawY; startX = params.x; startY = params.y; false }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(event.rawX - downX) > 8 || abs(event.rawY - downY) > 8) {
                        params.x = startX + (event.rawX - downX).toInt()
                        params.y = startY + (event.rawY - downY).toInt()
                        windowManager.updateViewLayout(container, params)
                        true
                    } else false
                }
                else -> false
            }
        }

        pairButton.setOnClickListener {
            val pairingPort = pairingPortInput.text.toString().trim()
            val code = codeInput.text.toString().trim()
            val connectPort = connectPortInput.text.toString().trim()
            if (!com.termux.app.util.PermissionsValidation.isValidPairingSession(pairingPort, code, connectPort)) {
                status.text = getString(R.string.monitor_fill_2_fields)
                return@setOnClickListener
            }
            pairButton.isEnabled = false
            status.text = getString(R.string.config_permission_adb_progress_pairing)
            AdbPairingRunner.pairAndConnect(applicationContext, pairingPort, code, connectPort) { result ->
                handler.post {
                    pairButton.isEnabled = true
                    status.text = if (result.ok) getString(R.string.config_permission_adb_success) else result.message
                    if (result.ok) {
                        com.termux.app.util.AdbShellBridge.activateAfterPairing(applicationContext)
                        handler.postDelayed({ stopSelf() }, 2500)
                    }
                }
            }
        }
        closeButton.setOnClickListener { stopSelf() }

        windowManager.addView(container, params)
        panelView = container
    }

    override fun onDestroy() {
        super.onDestroy()
        panelView?.let { runCatching { windowManager.removeView(it) } }
        panelView = null
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

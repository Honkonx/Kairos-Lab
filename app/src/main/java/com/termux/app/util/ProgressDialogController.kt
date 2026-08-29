package com.termux.app.util

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog

/**
 * Máquina de estados de progreso reusable para acciones largas (instalar, descargar, backup)
 * — reemplaza el patrón repetido "AlertDialog + TextView con un string de progreso" que se
 * reimplementaba suelto en varios lugares de Kairos. Patrón adaptado de `ProgressDisplay.kt`
 * de Linbox-WinEmu (Compose original; acá reimplementado en Views nativas, Kairos no usa
 * Compose), ver docs/referencias/REFERENCIA_LINBOX.md. Detalle técnico colapsable opcional al terminar —
 * mismo patrón "Ver detalles" que ya usan `LlmErrorMapper`/`ChatFragment` en esta sesión.
 *
 * Estados: NOT_STARTED (antes de `show()`) -> PROCESSING (spinner + mensaje, se puede
 * actualizar con `update()`) -> DONE_SUCCESS / DONE_FAILURE (mensaje final, botón OK
 * habilitado, detalle técnico si se pasó uno).
 *
 * Fix real (docs/humano247.md, pedido explícito del usuario — CLAUDE.md § filosofía de
 * producto: "no bloquear, no dejar hacer más nada"): antes el diálogo se creaba SIEMPRE con
 * `setCancelable(false)` y sin ningún botón salvo el OK final — un `AlertDialog` modal bloquea
 * toda la pantalla (bottom nav incluido) mientras la operación corre, y no había forma de
 * volver a navegar hasta que terminara. Ahora `show()` acepta `allowBackground = true` para
 * agregar un botón "Enviar a 2do plano" que oculta el diálogo SIN cancelar el `Thread`
 * subyacente (que ya corría independiente del diálogo) — el caller debe chequear
 * `isBackgrounded` en su callback de éxito/error para avisar por notificación (
 * `ModuleEventBridge.notifyDirect`) en vez de depender de que el diálogo siga visible.
 */
class ProgressDialogController(private val context: Context) {

    enum class State { NOT_STARTED, PROCESSING, DONE_SUCCESS, DONE_FAILURE }

    var state = State.NOT_STARTED
        private set

    /** true si el usuario tocó "Enviar a 2do plano" — el caller debe notificar al terminar en vez de solo actualizar el diálogo (ya oculto). */
    var isBackgrounded = false
        private set

    private var dialog: AlertDialog? = null
    private lateinit var spinner: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var toggleDetail: TextView
    private lateinit var detailText: TextView

    fun show(title: String, initialMessage: String = "Iniciando…", allowBackground: Boolean = false) {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(4))
        }

        val spinnerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        // Estilo horizontal (no el spinner circular default) — permite mostrar tanto progreso
        // indeterminado (barra en movimiento) como determinado (% real) con el mismo widget,
        // en vez de necesitar dos ProgressBar distintos según si hay % parseable o no.
        spinner = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            layoutParams = LinearLayout.LayoutParams(dp(48), LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        statusText = TextView(context).apply {
            text = initialMessage
            textSize = 14f
            setPadding(dp(12), 0, 0, 0)
        }
        spinnerRow.addView(spinner)
        spinnerRow.addView(statusText)
        root.addView(spinnerRow)

        toggleDetail = TextView(context).apply {
            text = "Ver detalles"
            textSize = 12f
            visibility = View.GONE
            setPadding(0, dp(10), 0, 0)
            setOnClickListener {
                val expanding = detailText.visibility != View.VISIBLE
                detailText.visibility = if (expanding) View.VISIBLE else View.GONE
                text = if (expanding) "Ocultar detalles" else "Ver detalles"
            }
        }
        root.addView(toggleDetail)

        detailText = TextView(context).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
            visibility = View.GONE
            setPadding(0, dp(6), 0, dp(4))
        }
        root.addView(detailText)

        val builder = AlertDialog.Builder(context)
            .setTitle(title)
            .setView(root)
            .setCancelable(false)
            .setPositiveButton("OK", null)
        if (allowBackground) {
            builder.setNeutralButton("Enviar a 2do plano") { _, _ -> background() }
        }
        dialog = builder.create()
        dialog?.show()
        // El botón OK solo tiene sentido una vez terminado (éxito o error) — se oculta
        // mientras el estado sigue en PROCESSING.
        dialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.visibility = View.GONE
        state = State.PROCESSING
    }

    /** Actualiza el mensaje mientras sigue en PROCESSING — no-op en cualquier otro estado. */
    fun update(message: String) {
        if (state != State.PROCESSING) return
        statusText.text = message
    }

    /**
     * Progreso real parseable (%, ej. descarga de un modelo Ollama o una imagen QEMU) —
     * cambia la barra a modo determinado en vez del indeterminado por defecto. `percent`
     * fuera de 0..100 (ej. -1, fase sin tamaño conocido todavía) deja/vuelve al modo
     * indeterminado — no-op fuera de PROCESSING.
     */
    fun updateProgress(percent: Int, message: String) {
        if (state != State.PROCESSING) return
        if (percent in 0..100) {
            spinner.isIndeterminate = false
            spinner.max = 100
            spinner.progress = percent
        } else {
            spinner.isIndeterminate = true
        }
        statusText.text = message
    }

    fun success(message: String, detail: String? = null) = finish(State.DONE_SUCCESS, "✓ $message", detail)

    fun failure(message: String, detail: String? = null) = finish(State.DONE_FAILURE, "✗ $message", detail)

    /** Oculta el diálogo sin tocar el Thread de fondo — ver nota de clase. No-op fuera de PROCESSING. */
    fun background() {
        if (state != State.PROCESSING) return
        isBackgrounded = true
        dialog?.dismiss()
        dialog = null
    }

    private fun finish(newState: State, message: String, detail: String?) {
        state = newState
        spinner.visibility = View.GONE
        statusText.text = message
        if (!detail.isNullOrBlank()) {
            toggleDetail.visibility = View.VISIBLE
            detailText.text = detail
        }
        dialog?.setCancelable(true)
        dialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.visibility = View.VISIBLE
    }

    fun dismiss() {
        dialog?.dismiss()
        dialog = null
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}

package com.termux.app.ui.studio.keyboard

import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import com.termux.R

/**
 * Barra fija de teclas virtuales (Ctrl/Alt/Shift/Tab/Esc) para escribir sin teclado físico —
 * patrón "sticky modifiers" calcado de `referencia/interfaz/avnc-master` (`VirtualKeys.kt`):
 * tap sobre Ctrl/Alt/Shift = se aplican una sola vez al próximo evento de tecla real (del
 * teclado del sistema), long-press = quedan fijos hasta volver a tocarlos. Tab/Esc son botones
 * de acción directa que ya llevan los modificadores activos aplicados.
 *
 * No reimplementa interpretación de atajos (Ctrl+S, Ctrl+F, ...) — eso es responsabilidad de
 * [KeyboardShortcutHandler] (atajos de teclado físico/Bluetooth). Esta clase solo AUMENTA el
 * evento de tecla que va a llegar (agregándole metaState de Ctrl/Alt/Shift) antes de que
 * cualquier otro listener lo vea — por eso intercepta a nivel de [View] (el editor), no a nivel
 * de Activity: así el evento aumentado sigue el mismo camino normal (`View.dispatchKeyEvent`)
 * que seguiría un Ctrl físico sostenido, y cualquier atajo que dependa de `event.isCtrlPressed`
 * sigue funcionando sin cambios en otras clases.
 */
class VirtualKeyboardBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private val state = StickyModifierState()
    private var target: View? = null
    private var isDispatchingSynthetic = false

    private lateinit var ctrlButton: Button
    private lateinit var altButton: Button
    private lateinit var shiftButton: Button

    init {
        orientation = HORIZONTAL
        LayoutInflater.from(context).inflate(R.layout.studio_view_virtual_keyboard_bar, this, true)

        ctrlButton = findViewById(R.id.vk_key_ctrl)
        altButton = findViewById(R.id.vk_key_alt)
        shiftButton = findViewById(R.id.vk_key_shift)
        val tabButton = findViewById<Button>(R.id.vk_key_tab)
        val escButton = findViewById<Button>(R.id.vk_key_esc)

        setUpModifierButton(ctrlButton, ModifierKey.CTRL)
        setUpModifierButton(altButton, ModifierKey.ALT)
        setUpModifierButton(shiftButton, ModifierKey.SHIFT)
        tabButton.setOnClickListener { sendDirectKey(KeyEvent.KEYCODE_TAB) }
        escButton.setOnClickListener { sendDirectKey(KeyEvent.KEYCODE_ESCAPE) }
    }

    /** Conecta la barra a la vista que recibe el texto (el [io.github.rosemoe.sora.widget.CodeEditor]
     * del editor) — a partir de acá, los taps sobre Ctrl/Alt/Shift/Tab/Esc actúan sobre [view]. */
    fun attachTo(view: View) {
        target = view
        view.setOnKeyListener { v, _, event -> interceptPhysicalKeyEvent(v, event) }
    }

    /** Libera todos los modificadores sticky activos, incluidos los `LOCKED` por long-press —
     * sin esto, un modificador lockeado podía sobrevivir indefinidamente a un cambio de pantalla
     * o de proyecto dentro del mismo [StudioFragment][com.termux.app.ui.studio.StudioFragment]
     * (auditoría `docs/ide/AUDITORIA_ESTUDIO_PROFUNDA_2026-08-19.md` sección 2.2: no
     * había ningún punto del ciclo de vida que llamara [StickyModifierState.releaseAll]). Se
     * invoca al pasar a background (`onStop`) y al abrir un proyecto nuevo — ambos puntos donde
     * "empezar de cero" ya es la semántica esperada del resto del editor. */
    fun releaseAllModifiers() {
        state.releaseAll()
        refreshModifierVisuals()
    }

    private fun setUpModifierButton(button: Button, key: ModifierKey) {
        button.setOnClickListener {
            state.onTap(key)
            refreshModifierVisuals()
        }
        button.setOnLongClickListener {
            state.onLongPress(key)
            refreshModifierVisuals()
            true
        }
    }

    /** Aplica el metaState sticky activo sobre un evento físico real y lo reinyecta en
     * [target] — devuelve `true` para consumir el evento original y evitar que sora-editor lo
     * procese dos veces (una vez con el meta original, otra con el aumentado). */
    private fun interceptPhysicalKeyEvent(view: View, event: KeyEvent): Boolean {
        if (isDispatchingSynthetic) return false
        if (!state.hasActiveModifiers()) return false
        if (isModifierKeyCode(event.keyCode)) return false

        val augmented = KeyEvent(
            event.downTime, event.eventTime, event.action, event.keyCode, event.repeatCount,
            event.metaState or state.currentMetaState(), event.deviceId, event.scanCode,
            event.flags, event.source
        )
        isDispatchingSynthetic = true
        view.dispatchKeyEvent(augmented)
        isDispatchingSynthetic = false

        if (event.action == KeyEvent.ACTION_UP) {
            state.consumeOneShot()
            refreshModifierVisuals()
        }
        return true
    }

    /** Envío directo de Tab/Esc — no dependen de que llegue un evento físico, se sintetizan acá
     * mismo (down + up) con los modificadores sticky activos aplicados. */
    private fun sendDirectKey(keyCode: Int) {
        val view = target ?: return
        val metaState = state.currentMetaState()
        val now = android.os.SystemClock.uptimeMillis()
        isDispatchingSynthetic = true
        view.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, metaState))
        view.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, metaState))
        isDispatchingSynthetic = false
        state.consumeOneShot()
        refreshModifierVisuals()
    }

    private fun isModifierKeyCode(keyCode: Int): Boolean =
        ModifierKey.entries.any { it.keyCode == keyCode }

    private fun refreshModifierVisuals() {
        applyModifierVisual(ctrlButton, ModifierKey.CTRL)
        applyModifierVisual(altButton, ModifierKey.ALT)
        applyModifierVisual(shiftButton, ModifierKey.SHIFT)
    }

    private fun applyModifierVisual(button: Button, key: ModifierKey) {
        val colorRes = when (state.modeOf(key)) {
            ModifierMode.OFF -> R.color.ide_surface
            ModifierMode.PENDING -> R.color.ide_accent_dim
            ModifierMode.LOCKED -> R.color.ide_accent
        }
        val textColorRes = if (state.isActive(key)) R.color.ide_background else R.color.ide_text_secondary
        button.backgroundTintList = ContextCompat.getColorStateList(context, colorRes)
        button.setTextColor(ContextCompat.getColor(context, textColorRes))
    }
}

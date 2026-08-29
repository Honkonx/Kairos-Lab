package com.termux.app.ui.studio.keyboard

import android.view.KeyEvent

/**
 * Modificador soportado por [VirtualKeyboardBar]. `metaOn` es el flag de [KeyEvent] a combinar
 * sobre el próximo evento físico; `keyCode` es el keycode físico equivalente (usado para no
 * interceptar el propio evento del modificador cuando el usuario también tiene teclado físico).
 */
enum class ModifierKey(val metaOn: Int, val keyCode: Int) {
    CTRL(KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON, KeyEvent.KEYCODE_CTRL_LEFT),
    ALT(KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON, KeyEvent.KEYCODE_ALT_LEFT),
    SHIFT(KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON, KeyEvent.KEYCODE_SHIFT_LEFT)
}

/** Estado de un [ModifierKey] individual. */
enum class ModifierMode { OFF, PENDING, LOCKED }

/**
 * Modificadores "sticky" para teclas virtuales, patrón calcado de
 * `referencia/interfaz/avnc-master` (`VirtualKeys.kt`: `toggleKeys` + `lockedToggleKeys`) pero
 * reimplementado acá sin depender de [android.widget.ToggleButton] — [VirtualKeyboardBar] decide
 * cómo pintar cada estado.
 *
 * - Tap sobre un modificador inactivo → [ModifierMode.PENDING] (se aplica una sola vez al
 *   próximo evento de tecla real y se libera solo).
 * - Long-press → [ModifierMode.LOCKED] (queda fijo hasta que se lo vuelve a tocar).
 * - Tap sobre un modificador ya activo (pending o locked) → lo apaga.
 */
class StickyModifierState {

    private val modes = mutableMapOf(
        ModifierKey.CTRL to ModifierMode.OFF,
        ModifierKey.ALT to ModifierMode.OFF,
        ModifierKey.SHIFT to ModifierMode.OFF
    )

    fun modeOf(key: ModifierKey): ModifierMode = modes.getValue(key)

    fun isActive(key: ModifierKey): Boolean = modes.getValue(key) != ModifierMode.OFF

    fun hasActiveModifiers(): Boolean = modes.values.any { it != ModifierMode.OFF }

    /** Tap normal — alterna entre OFF y PENDING; apaga si ya estaba PENDING o LOCKED. */
    fun onTap(key: ModifierKey): ModifierMode {
        modes[key] = if (modes.getValue(key) == ModifierMode.OFF) ModifierMode.PENDING else ModifierMode.OFF
        return modes.getValue(key)
    }

    /** Long-press — fija el modificador en LOCKED hasta que se lo vuelva a tocar. */
    fun onLongPress(key: ModifierKey): ModifierMode {
        modes[key] = ModifierMode.LOCKED
        return ModifierMode.LOCKED
    }

    /** Combina los flags meta de todos los modificadores activos (pending o locked). */
    fun currentMetaState(): Int {
        var meta = 0
        modes.forEach { (key, mode) -> if (mode != ModifierMode.OFF) meta = meta or key.metaOn }
        return meta
    }

    /** Se llama después de aplicar el estado a un evento de tecla real — apaga los
     * modificadores PENDING (un solo carácter), deja los LOCKED como están. */
    fun consumeOneShot() {
        modes.keys.forEach { key ->
            if (modes.getValue(key) == ModifierMode.PENDING) modes[key] = ModifierMode.OFF
        }
    }

    fun releaseAll() {
        modes.keys.forEach { modes[it] = ModifierMode.OFF }
    }
}

package com.termux.app.ui.studio.keyboard

import android.view.KeyEvent

/**
 * Intérprete puro de atajos de teclado físico/Bluetooth para el editor — no toca UI, solo mapea
 * un [KeyEvent] a una [KeyboardShortcutAction] (o `null` si no matchea nada). [MainActivity] hace
 * `override fun dispatchKeyEvent` y le pasa cada evento; separado en su propia clase para no
 * inflar más `MainActivity.kt` (ya es el archivo más grande del proyecto).
 *
 * ## Investigación previa (docs/ide/IDE_EXTERNO.md Sección 7, docs/humano/humano155.md)
 *
 * Se leyeron 2 referencias reales antes de escribir esto (no se asumió su contenido de memoria):
 *
 * - `referencia/interfaz/avnc-master/.../input/KeyHandler.kt`: implementa síntesis de
 *   Shift/Ctrl/Alt "down" (`generateFakeShifts`/`generateFakeCtrl`/`generateFakeAlt`) porque
 *   **Gboard** (teclado de software) manda secuencias de Shift rotas al escribir mayúsculas
 *   (Shift DOWN, Shift UP, y RECIÉN DESPUÉS el evento del carácter con `metaState` marcando
 *   Shift). Ese archivo lo dice explícito en su comentario: "That's what Gboard does" — es un
 *   workaround de teclado de software, no de teclado físico/Bluetooth.
 * - `referencia/interfaz/Haven-main/.../KeyEventInterceptor.kt`: patrón de hook a nivel Activity
 *   (`dispatchKeyEvent`) con un `object` que expone un callback registrable — usa
 *   `KeyEvent.getUnicodeChar()` para resolver el layout real del teclado (evita hardcodear
 *   QWERTY US). Confirma que interceptar en el nivel de Activity (antes que la vista con foco)
 *   es el patrón correcto para no depender de qué View tiene el foco en un momento dado.
 *
 * **Conclusión honesta, no asumida:** no se confirmó en este repo que un teclado físico/Bluetooth
 * real mande `KeyEvent.getMetaState()` con `META_CTRL_ON` tarde o incompleto — el bug de Gboard
 * documentado en avnc es específico de teclado de software (mapeo de Shift para generar
 * mayúsculas/símbolos), no del caso "tecla Ctrl física sostenida + otra tecla física" que es lo
 * que este handler necesita. Un teclado Bluetooth reporta el estado real de sus teclas modificadoras
 * al sistema (no hay síntesis de "carácter compuesto" de por medio como con Shift+letra en
 * software). Por eso NO se replicó el patrón de síntesis de `generateFakeCtrl`/`generateFakeShift`
 * acá — se usa directo `event.isCtrlPressed`/`event.isShiftPressed` (que a su vez leen
 * `getMetaState()`). Si en dispositivo real con un teclado Bluetooth específico se confirma que
 * esto falla (Ctrl no llega marcado), ahí sí correspondería adoptar el patrón de avnc — no antes,
 * para no agregar complejidad sin un bug confirmado (YAGNI).
 *
 * No probado en dispositivo real con teclado físico/Bluetooth — solo revisado por lectura de API
 * de Android (`KeyEvent.isCtrlPressed`, `isShiftPressed`, `ACTION_DOWN`).
 */
enum class KeyboardShortcutAction {
    /** Ctrl+S — guardar el archivo activo. */
    SAVE_FILE,

    /** Ctrl+F — abrir/enfocar la barra de búsqueda del archivo activo. */
    SEARCH_IN_FILE,

    /** Ctrl+Shift+F — abrir búsqueda en todo el proyecto ([com.termux.app.ui.studio.search.ProjectSearchActivity]). */
    SEARCH_IN_PROJECT,

    /** Ctrl+W — cerrar la pestaña activa. */
    CLOSE_TAB,

    /** Ctrl+Tab — ir a la pestaña siguiente. */
    NEXT_TAB,

    /** Ctrl+Shift+Tab — ir a la pestaña anterior. */
    PREVIOUS_TAB,

    /** Ctrl+N — crear un archivo nuevo en la raíz del proyecto abierto. */
    NEW_FILE,

    /** Ctrl+O — abrir un archivo suelto (picker `ACTION_OPEN_DOCUMENT`). Se mapeó a "archivo" y
     * no a "carpeta" porque son dos flujos distintos en el menú existente
     * (`action_open_file`/`action_open_folder`) y un solo atajo no puede cubrir ambos sin un
     * diálogo intermedio — abrir carpeta de proyecto sigue siendo solo por el menú/drawer. */
    OPEN_FILE,

    /** Ctrl+P — abrir la paleta de comandos ([com.termux.app.ui.studio.palette.CommandPaletteDialog]).
     * Preexistente de otra ronda paralela ([com.termux.app.ui.studio.MainActivity.onKeyShortcut]) —
     * absorbido acá para que haya un único punto de resolución de atajos Ctrl+<tecla>, en vez de
     * dos `when` separados compitiendo por el mismo `onKeyShortcut`. */
    COMMAND_PALETTE
}

object KeyboardShortcutHandler {

    /**
     * Devuelve la acción que corresponde a [event], o `null` si no matchea ningún atajo
     * soportado. Solo procesa `ACTION_DOWN` (evita disparar dos veces por combinación, una en
     * down y otra en up) y exige Ctrl presionado — ninguno de los atajos de esta ronda usa
     * Alt/Meta solos, así que no hace falta discriminar por esos.
     */
    fun resolve(event: KeyEvent): KeyboardShortcutAction? {
        if (event.action != KeyEvent.ACTION_DOWN) return null
        if (!event.isCtrlPressed) return null

        return when (event.keyCode) {
            KeyEvent.KEYCODE_S -> KeyboardShortcutAction.SAVE_FILE
            KeyEvent.KEYCODE_F ->
                if (event.isShiftPressed) KeyboardShortcutAction.SEARCH_IN_PROJECT
                else KeyboardShortcutAction.SEARCH_IN_FILE
            KeyEvent.KEYCODE_W -> KeyboardShortcutAction.CLOSE_TAB
            KeyEvent.KEYCODE_TAB ->
                if (event.isShiftPressed) KeyboardShortcutAction.PREVIOUS_TAB
                else KeyboardShortcutAction.NEXT_TAB
            KeyEvent.KEYCODE_N -> KeyboardShortcutAction.NEW_FILE
            KeyEvent.KEYCODE_O -> KeyboardShortcutAction.OPEN_FILE
            KeyEvent.KEYCODE_P -> KeyboardShortcutAction.COMMAND_PALETTE
            else -> null
        }
    }
}

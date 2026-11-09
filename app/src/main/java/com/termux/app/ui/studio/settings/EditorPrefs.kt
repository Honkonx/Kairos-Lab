package com.termux.app.ui.studio.settings

import android.content.Context
import android.content.SharedPreferences

/**
 * Persistencia de las preferencias del editor de código (tamaño de fuente, tabulación, ajuste
 * de línea, números de línea, resaltado de línea actual). SharedPreferences planas, sin cifrar
 * — a diferencia de [com.termux.app.ui.studio.ai.AiProviderPrefs] (que guarda API keys), acá no hay ningún
 * dato sensible, así que EncryptedSharedPreferences sería complejidad sin beneficio real.
 *
 * Los valores por defecto coinciden con lo que ya aplicaba [com.termux.app.ui.studio.editor.EditorSchemeSetup]
 * antes de que existiera esta pantalla de configuración (tamaño 14sp, sin wordwrap), más los
 * defaults razonables de sora-editor para el resto (confirmados contra las clases reales del AAR
 * `editor-0.24.4`, no supuestos): tabWidth=4, lineNumberEnabled=true, highlightCurrentLine=true.
 *
 * [editorTheme] guarda el nombre del theme TextMate elegido (ver
 * [com.termux.app.ui.studio.editor.SyntaxHighlighter.AVAILABLE_THEMES]) — se valida contra esa lista recién
 * al aplicarlo (acá se guarda el string tal cual), para no crear una dependencia circular entre
 * `settings` y `editor` en tiempo de compilación por un simple default constant.
 */
class EditorPrefs(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var fontSize: Float
        get() = prefs.getFloat(KEY_FONT_SIZE, DEFAULT_FONT_SIZE)
        set(value) = prefs.edit().putFloat(KEY_FONT_SIZE, value).apply()

    var tabSize: Int
        get() = prefs.getInt(KEY_TAB_SIZE, DEFAULT_TAB_SIZE)
        set(value) = prefs.edit().putInt(KEY_TAB_SIZE, value).apply()

    var wordWrap: Boolean
        get() = prefs.getBoolean(KEY_WORD_WRAP, DEFAULT_WORD_WRAP)
        set(value) = prefs.edit().putBoolean(KEY_WORD_WRAP, value).apply()

    var showLineNumbers: Boolean
        get() = prefs.getBoolean(KEY_SHOW_LINE_NUMBERS, DEFAULT_SHOW_LINE_NUMBERS)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_LINE_NUMBERS, value).apply()

    var highlightCurrentLine: Boolean
        get() = prefs.getBoolean(KEY_HIGHLIGHT_CURRENT_LINE, DEFAULT_HIGHLIGHT_CURRENT_LINE)
        set(value) = prefs.edit().putBoolean(KEY_HIGHLIGHT_CURRENT_LINE, value).apply()

    var editorTheme: String
        get() = prefs.getString(KEY_EDITOR_THEME, DEFAULT_EDITOR_THEME) ?: DEFAULT_EDITOR_THEME
        set(value) = prefs.edit().putString(KEY_EDITOR_THEME, value).apply()

    // Opciones agregadas 2026-11-02 (hallazgo real,
    // docs/ide/PLAN_REDISENO_ESTUDIO_IDE_2026-08-22.md § quick-wins): sora-editor 0.24.4 ya
    // expone estas 6 opciones (confirmado inspeccionando el .aar real,
    // io.github.rosemoe.sora.widget.CodeEditor/DirectAccessProps), Estudio no las usaba.

    /** sora-editor DirectAccessProps.stickyScroll — fija el encabezado de la función/bloque actual arriba al hacer scroll. */
    var stickyScroll: Boolean
        get() = prefs.getBoolean(KEY_STICKY_SCROLL, DEFAULT_STICKY_SCROLL)
        set(value) = prefs.edit().putBoolean(KEY_STICKY_SCROLL, value).apply()

    /** CodeEditor.setScalable — pinch-to-zoom para cambiar el tamaño de fuente con 2 dedos. */
    var pinchZoom: Boolean
        get() = prefs.getBoolean(KEY_PINCH_ZOOM, DEFAULT_PINCH_ZOOM)
        set(value) = prefs.edit().putBoolean(KEY_PINCH_ZOOM, value).apply()

    /** CodeEditor.setPinLineNumber — fija la columna de números de línea al hacer scroll horizontal. */
    var pinLineNumber: Boolean
        get() = prefs.getBoolean(KEY_PIN_LINE_NUMBER, DEFAULT_PIN_LINE_NUMBER)
        set(value) = prefs.edit().putBoolean(KEY_PIN_LINE_NUMBER, value).apply()

    /** CodeEditor.setCursorAnimationEnabled — animación suave del cursor al moverse. */
    var cursorAnimation: Boolean
        get() = prefs.getBoolean(KEY_CURSOR_ANIMATION, DEFAULT_CURSOR_ANIMATION)
        set(value) = prefs.edit().putBoolean(KEY_CURSOR_ANIMATION, value).apply()

    /** CodeEditor FLAG_DRAW_WHITESPACE_* — dibuja espacios/tabs invisibles como puntos/flechas. */
    var showInvisibleChars: Boolean
        get() = prefs.getBoolean(KEY_SHOW_INVISIBLE_CHARS, DEFAULT_SHOW_INVISIBLE_CHARS)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_INVISIBLE_CHARS, value).apply()

    /** sora-editor DirectAccessProps.symbolPairAutoCompletion — auto-cierre de ( [ { " ' al escribir el de apertura. */
    var autoCloseSymbols: Boolean
        get() = prefs.getBoolean(KEY_AUTO_CLOSE_SYMBOLS, DEFAULT_AUTO_CLOSE_SYMBOLS)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_CLOSE_SYMBOLS, value).apply()

    companion object {
        private const val PREFS_NAME = "kairos_ide_editor_prefs"

        private const val KEY_FONT_SIZE = "font_size"
        private const val KEY_TAB_SIZE = "tab_size"
        private const val KEY_WORD_WRAP = "word_wrap"
        private const val KEY_SHOW_LINE_NUMBERS = "show_line_numbers"
        private const val KEY_HIGHLIGHT_CURRENT_LINE = "highlight_current_line"
        private const val KEY_EDITOR_THEME = "editor_theme"
        private const val KEY_STICKY_SCROLL = "sticky_scroll"
        private const val KEY_PINCH_ZOOM = "pinch_zoom"
        private const val KEY_PIN_LINE_NUMBER = "pin_line_number"
        private const val KEY_CURSOR_ANIMATION = "cursor_animation"
        private const val KEY_SHOW_INVISIBLE_CHARS = "show_invisible_chars"
        private const val KEY_AUTO_CLOSE_SYMBOLS = "auto_close_symbols"

        const val DEFAULT_FONT_SIZE = 14f
        const val DEFAULT_TAB_SIZE = 4
        const val DEFAULT_WORD_WRAP = false
        const val DEFAULT_SHOW_LINE_NUMBERS = true
        const val DEFAULT_HIGHLIGHT_CURRENT_LINE = true
        // Defaults elegidos para no cambiar el comportamiento visible de nadie que ya usaba
        // Estudio: pinchZoom/cursorAnimation/autoCloseSymbols ON porque son mejoras sin
        // contrapartida (no cambian el contenido, solo UX); stickyScroll/pinLineNumber/
        // showInvisibleChars OFF porque cambian cómo se ve la pantalla de entrada.
        const val DEFAULT_STICKY_SCROLL = false
        const val DEFAULT_PINCH_ZOOM = true
        const val DEFAULT_PIN_LINE_NUMBER = false
        const val DEFAULT_CURSOR_ANIMATION = true
        const val DEFAULT_SHOW_INVISIBLE_CHARS = false
        const val DEFAULT_AUTO_CLOSE_SYMBOLS = true

        /** Duplicado a propósito de [com.termux.app.ui.studio.editor.SyntaxHighlighter.DEFAULT_THEME] — un
         * `const val` no puede referenciar otro módulo sin crear una dependencia circular
         * `settings` <-> `editor`; ambos valores deben mantenerse en sync ("kairos-ink") si se
         * renombra el theme por defecto. */
        const val DEFAULT_EDITOR_THEME = "kairos-ink"

        const val MIN_FONT_SIZE = 8f
        const val MAX_FONT_SIZE = 32f
        const val MIN_TAB_SIZE = 1
        const val MAX_TAB_SIZE = 8
    }
}

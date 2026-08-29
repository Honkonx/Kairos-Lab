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

    companion object {
        private const val PREFS_NAME = "kairos_ide_editor_prefs"

        private const val KEY_FONT_SIZE = "font_size"
        private const val KEY_TAB_SIZE = "tab_size"
        private const val KEY_WORD_WRAP = "word_wrap"
        private const val KEY_SHOW_LINE_NUMBERS = "show_line_numbers"
        private const val KEY_HIGHLIGHT_CURRENT_LINE = "highlight_current_line"
        private const val KEY_EDITOR_THEME = "editor_theme"

        const val DEFAULT_FONT_SIZE = 14f
        const val DEFAULT_TAB_SIZE = 4
        const val DEFAULT_WORD_WRAP = false
        const val DEFAULT_SHOW_LINE_NUMBERS = true
        const val DEFAULT_HIGHLIGHT_CURRENT_LINE = true

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

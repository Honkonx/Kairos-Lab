package com.termux.app.ui.studio.editor

import com.termux.app.ui.studio.settings.EditorPrefs
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.schemes.SchemeDarcula

/**
 * Configuración del editor sora-editor (mismo BOM 0.24.4 que ya usa Kairos en el tab Archivos).
 * Esquema de color de arranque (antes de abrir cualquier archivo) + preferencias reales de
 * [EditorPrefs] (tamaño de fuente, tabulación, wordwrap, números de línea, resaltado de línea
 * actual, theme de color). El resaltado de sintaxis real por lenguaje (grammars TextMate + theme
 * elegido) se aplica por archivo/tab en [SyntaxHighlighter.apply], que sobreescribe el
 * `colorScheme` de acá con el de TextMate en cuanto se abre el primer archivo — ver esa clase
 * para la lógica de detección de lenguaje y carga de grammars/themes.
 */
object EditorSchemeSetup {

    fun apply(editor: CodeEditor) {
        editor.colorScheme = SchemeDarcula()
        applyPrefs(editor, EditorPrefs(editor.context))
        editor.getComponent(io.github.rosemoe.sora.widget.component.EditorAutoCompletion::class.java)
            .isEnabled = true
    }

    /** Reaplica solo las preferencias configurables desde [com.termux.app.ui.studio.settings.EditorSettingsActivity]
     *  — métodos confirmados contra las clases reales del AAR `editor-0.24.4` (no supuestos).
     *  [SyntaxHighlighter.setTheme] es no-op visual si [prefs.editorTheme] ya es el theme activo
     *  (recrea el `colorScheme` igual, pero es barato — son ~20 entradas de color, no un parseo
     *  pesado), así que se puede llamar sin trackear si cambió. */
    fun applyPrefs(editor: CodeEditor, prefs: EditorPrefs) {
        editor.setTextSize(prefs.fontSize)
        editor.setTabWidth(prefs.tabSize)
        editor.isWordwrap = prefs.wordWrap
        editor.isLineNumberEnabled = prefs.showLineNumbers
        editor.isHighlightCurrentLine = prefs.highlightCurrentLine
        SyntaxHighlighter.setTheme(editor, prefs.editorTheme)

        // 6 opciones agregadas 2026-11-02 (hallazgo real,
        // docs/ide/PLAN_REDISENO_ESTUDIO_IDE_2026-08-22.md § quick-wins) — todas confirmadas
        // contra las clases reales del AAR editor-0.24.4, no supuestas.
        editor.props.stickyScroll = prefs.stickyScroll
        editor.isScalable = prefs.pinchZoom
        editor.setPinLineNumber(prefs.pinLineNumber)
        editor.isCursorAnimationEnabled = prefs.cursorAnimation
        editor.nonPrintablePaintingFlags = if (prefs.showInvisibleChars) {
            CodeEditor.FLAG_DRAW_WHITESPACE_LEADING or
                CodeEditor.FLAG_DRAW_WHITESPACE_INNER or
                CodeEditor.FLAG_DRAW_WHITESPACE_TRAILING or
                CodeEditor.FLAG_DRAW_LINE_SEPARATOR
        } else {
            0
        }
        editor.props.symbolPairAutoCompletion = prefs.autoCloseSymbols
    }
}

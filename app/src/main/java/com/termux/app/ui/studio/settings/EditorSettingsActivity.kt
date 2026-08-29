package com.termux.app.ui.studio.settings

import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import com.termux.databinding.StudioActivityEditorSettingsBinding
import com.termux.app.ui.studio.editor.SyntaxHighlighter

/**
 * Pantalla de configuración del editor — opciones reales aplicadas a [io.github.rosemoe.sora.widget.CodeEditor]
 * vía [com.termux.app.ui.studio.editor.EditorSchemeSetup.applyPrefs], no un mockup: tema de color, tamaño
 * de fuente, tamaño de tabulación, ajuste de línea (word wrap), números de línea y resaltado de
 * línea actual. Se guarda inmediatamente en [EditorPrefs] al tocar cada control — MainActivity
 * reaplica las preferencias en `onResume()` cuando vuelve de esta pantalla.
 */
class EditorSettingsActivity : AppCompatActivity() {

    private lateinit var binding: StudioActivityEditorSettingsBinding
    private lateinit var prefs: EditorPrefs

    /** Mismo orden que [SyntaxHighlighter.AVAILABLE_THEMES] — el índice elegido en el spinner
     * mapea 1:1 a esa lista, así que agregar un theme nuevo solo requiere sumar su label acá. */
    private val themeLabelRes = mapOf(
        "kairos-ink" to com.termux.R.string.editor_theme_kairos_ink,
        "kairos-paper" to com.termux.R.string.editor_theme_kairos_paper,
        "kairos-contrast" to com.termux.R.string.editor_theme_kairos_contrast
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = StudioActivityEditorSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        prefs = EditorPrefs(this)
        setUpThemeControl()
        setUpFontSizeControl()
        setUpTabSizeControl()
        setUpSwitches()
    }

    /** El cambio de theme se guarda al tocar el spinner — MainActivity lo aplica en runtime al
     * volver a foco (`onResume` -> [com.termux.app.ui.studio.editor.EditorSchemeSetup.applyPrefs] ->
     * [SyntaxHighlighter.setTheme]), sin reiniciar la app. */
    private fun setUpThemeControl() {
        val themes = SyntaxHighlighter.AVAILABLE_THEMES
        val labels = themes.map { getString(themeLabelRes[it] ?: com.termux.R.string.editor_theme_kairos_ink) }
        binding.editorThemeSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            labels
        )

        val currentIndex = themes.indexOf(prefs.editorTheme).let { if (it >= 0) it else 0 }
        binding.editorThemeSpinner.setSelection(currentIndex)

        binding.editorThemeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                prefs.editorTheme = themes[position]
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun setUpFontSizeControl() {
        val range = EditorPrefs.MAX_FONT_SIZE - EditorPrefs.MIN_FONT_SIZE
        binding.fontSizeSeekbar.max = range.toInt()
        binding.fontSizeSeekbar.progress = (prefs.fontSize - EditorPrefs.MIN_FONT_SIZE).toInt()
        updateFontSizeLabel(prefs.fontSize)
        binding.fontSizeSeekbar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val newSize = EditorPrefs.MIN_FONT_SIZE + progress
                updateFontSizeLabel(newSize)
                if (fromUser) prefs.fontSize = newSize
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
    }

    private fun updateFontSizeLabel(size: Float) {
        binding.fontSizeValueLabel.text = "${size.toInt()}sp"
    }

    private fun setUpTabSizeControl() {
        val range = EditorPrefs.MAX_TAB_SIZE - EditorPrefs.MIN_TAB_SIZE
        binding.tabSizeSeekbar.max = range
        binding.tabSizeSeekbar.progress = prefs.tabSize - EditorPrefs.MIN_TAB_SIZE
        updateTabSizeLabel(prefs.tabSize)
        binding.tabSizeSeekbar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val newSize = EditorPrefs.MIN_TAB_SIZE + progress
                updateTabSizeLabel(newSize)
                if (fromUser) prefs.tabSize = newSize
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
    }

    private fun updateTabSizeLabel(size: Int) {
        binding.tabSizeValueLabel.text = size.toString()
    }

    private fun setUpSwitches() {
        binding.wordWrapSwitch.isChecked = prefs.wordWrap
        binding.wordWrapSwitch.setOnCheckedChangeListener { _, checked -> prefs.wordWrap = checked }

        binding.lineNumbersSwitch.isChecked = prefs.showLineNumbers
        binding.lineNumbersSwitch.setOnCheckedChangeListener { _, checked -> prefs.showLineNumbers = checked }

        binding.highlightCurrentLineSwitch.isChecked = prefs.highlightCurrentLine
        binding.highlightCurrentLineSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.highlightCurrentLine = checked
        }
    }
}

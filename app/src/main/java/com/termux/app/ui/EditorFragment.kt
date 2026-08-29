package com.termux.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.termux.R
import io.github.rosemoe.sora.widget.CodeEditor
import java.io.File

/**
 * Editor de texto simple para el file manager — usa sora-editor (LGPL-2.1,
 * dependencia sin modificar, ver app/build.gradle) en vez de copiar código de
 * proyectos GPL como Xed-Editor. Sin archivos binarios ni mayores a 5MB.
 *
 * Resaltado de sintaxis (fix real 2026-08-01, ver docs/referencias/REFERENCIA_XED_EDITOR.md): el
 * código de wiring (`setupSyntaxHighlighting`/`ensureTextMateInitialized`) ya estaba
 * implementado desde una ronda anterior, pero sin efecto real — sora-editor no trae
 * bundleados los archivos de gramática (.tmLanguage.json) ni de tema, y bajarlos vía
 * WebFetch arriesgaba corromper el JSON silenciosamente (sin forma de probarlo antes
 * de un build real). Los assets de `app/src/main/assets/textmate/` ahora son una copia
 * DIRECTA (sin pasar por WebFetch) de `referencia/ides/Xed-Editor-main/core/main/src/main/
 * assets/textmate/` — mismo proyecto que valida en producción (Xed-Editor está
 * publicado en F-Droid/IzzyOnDroid) las mismas gramáticas TextMate contra la misma
 * librería sora-editor que ya usa Kairos. Xed-Editor es GPLv3 — copiar estos archivos
 * de DATOS (gramáticas/config, no código de la librería en sí) es compatible, ver
 * `referencia/ides/Xed-Editor-main/LICENSE` y el aviso de atribución en
 * `app/src/main/assets/textmate/ATTRIBUTION.md`. Solo se copiaron los 12 lenguajes que
 * `EXTENSION_TO_SCOPE` (abajo) realmente mapea — no las ~48 gramáticas completas del
 * proyecto de origen, para no inflar el APK con lenguajes que Kairos nunca abre.
 */
class EditorFragment : Fragment() {

    private lateinit var editor: CodeEditor
    private lateinit var fileNameText: TextView
    private var filePath: String = ""
    private var originalText: String = ""

    companion object {
        // Inicialización de FileProviderRegistry/GrammarRegistry es global al proceso,
        // no por instancia de fragment — solo hace falta una vez.
        private var textMateInitialized = false
        private var themeLoaded = false

        private const val THEME_ASSET_PATH = "textmate/darcula.json"
        private const val THEME_NAME = "darcula"

        // Mapeo extensión → scope TextMate — mismos scopes que usa el propio demo de
        // sora-editor (ver comentario de clase). Extensiones sin gramática conocida
        // (properties/conf/cfg/ini/toml/csv/log/bashrc/profile/gradle) caen a texto
        // plano a propósito — no existe una gramática estándar confiable para todas
        // y es mejor texto plano que un mapeo adivinado.
        private val EXTENSION_TO_SCOPE = mapOf(
            "java" to "source.java",
            "kt" to "source.kotlin",
            "py" to "source.python",
            "xml" to "text.xml",
            "html" to "text.html.basic",
            "js" to "source.js",
            "ts" to "source.ts",
            "md" to "text.html.markdown",
            "json" to "source.json",
            "yaml" to "source.yaml",
            "yml" to "source.yaml",
            "sh" to "source.shell",
            "css" to "source.css"
        )

        fun newInstance(path: String): EditorFragment {
            return EditorFragment().apply {
                arguments = Bundle().apply { putString("file_path", path) }
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, c: ViewGroup?, b: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_editor, c, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        filePath = arguments?.getString("file_path") ?: ""

        fileNameText = view.findViewById(R.id.file_name_text)
        fileNameText.text = File(filePath).name

        editor = CodeEditor(requireContext())
        view.findViewById<FrameLayout>(R.id.editor_container).addView(
            editor,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )

        view.findViewById<View>(R.id.back_btn).setOnClickListener { confirmBackIfDirty() }
        view.findViewById<View>(R.id.btn_save).setOnClickListener { save() }

        loadFile()
    }

    private fun loadFile() {
        val file = File(filePath)
        if (!file.exists()) {
            toast(getString(R.string.editor_file_not_found))
            parentFragmentManager.popBackStack()
            return
        }
        if (file.length() > 5L * 1024 * 1024) {
            toast(getString(R.string.editor_file_too_large))
            parentFragmentManager.popBackStack()
            return
        }
        try {
            originalText = file.readText()
            editor.setText(originalText)
            setupSyntaxHighlighting(file.extension.lowercase())
        } catch (e: Exception) {
            toast(getString(R.string.editor_read_error, e.message ?: "null"))
            parentFragmentManager.popBackStack()
        }
    }

    /**
     * Configura resaltado TextMate si los assets de gramática/tema están presentes en
     * app/src/main/assets/textmate/ (ver comentario de clase — hoy no lo están, así que
     * esto siempre cae al modo texto plano sin ningún cambio de comportamiento visible).
     * Nunca lanza — cualquier fallo (asset faltante, JSON malformado si algún día se
     * agregan a mano sin validar) deja el editor en texto plano, no lo rompe.
     */
    private fun setupSyntaxHighlighting(extension: String) {
        val scopeName = EXTENSION_TO_SCOPE[extension] ?: return // sin gramática mapeada: texto plano
        if (!textmateAssetAvailable("textmate/languages.json")) return // assets no bundleados: texto plano
        try {
            ensureTextMateInitialized()
            val language = io.github.rosemoe.sora.langs.textmate.TextMateLanguage.create(scopeName, true)
            editor.setEditorLanguage(language)
            if (themeLoaded) {
                editor.colorScheme = io.github.rosemoe.sora.langs.textmate.TextMateColorScheme.create(
                    io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry.getInstance()
                )
            }
        } catch (e: Exception) {
            // No interrumpe la edición — solo se queda en texto plano.
            android.util.Log.w("EditorFragment", "No se pudo activar resaltado de sintaxis: ${e.message}")
        }
    }

    private fun textmateAssetAvailable(path: String): Boolean {
        return try {
            requireContext().assets.open(path).close()
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun ensureTextMateInitialized() {
        if (textMateInitialized) return
        val fileProviderRegistry = io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry.getInstance()
        fileProviderRegistry.addFileProvider(
            io.github.rosemoe.sora.langs.textmate.registry.provider.AssetsFileResolver(requireContext().assets)
        )
        io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry.getInstance()
            .loadGrammars("textmate/languages.json")

        // El tema es un asset separado (ej. textmate/quietlight.json) — igual de
        // opcional/ausente hoy que las gramáticas, se activa solo si aparece.
        if (textmateAssetAvailable(THEME_ASSET_PATH)) {
            try {
                val themeRegistry = io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry.getInstance()
                requireContext().assets.open(THEME_ASSET_PATH).use { input ->
                    themeRegistry.loadTheme(
                        io.github.rosemoe.sora.langs.textmate.registry.model.ThemeModel(
                            org.eclipse.tm4e.core.registry.IThemeSource.fromInputStream(input, THEME_ASSET_PATH, null),
                            THEME_NAME
                        )
                    )
                }
                themeRegistry.setTheme(THEME_NAME)
                themeLoaded = true
            } catch (e: Exception) {
                android.util.Log.w("EditorFragment", "No se pudo cargar el tema TextMate: ${e.message}")
            }
        }
        textMateInitialized = true
    }

    private fun isDirty(): Boolean = editor.text.toString() != originalText

    private fun confirmBackIfDirty() {
        if (!isDirty()) {
            parentFragmentManager.popBackStack()
            return
        }
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.editor_unsaved_changes_title))
            .setMessage(getString(R.string.editor_unsaved_changes_message))
            .setPositiveButton(getString(R.string.editor_exit_without_saving)) { _, _ -> parentFragmentManager.popBackStack() }
            .setNegativeButton(getString(R.string.editor_action_cancel), null)
            .show()
    }

    private fun save() {
        try {
            File(filePath).writeText(editor.text.toString())
            originalText = editor.text.toString()
            toast(getString(R.string.editor_saved))
        } catch (e: Exception) {
            toast(getString(R.string.editor_save_error, e.message ?: "null"))
        }
    }

    private fun toast(msg: String) = Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()

    override fun onDestroyView() {
        // sora-editor recomienda liberar el editor explícitamente al destruirse
        // (ver docs.getting-started) — evita fugas de recursos nativos.
        editor.release()
        super.onDestroyView()
    }
}

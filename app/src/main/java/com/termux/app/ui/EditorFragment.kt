package com.termux.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.termux.R
import com.termux.app.ui.studio.editor.EditorSchemeSetup
import com.termux.app.ui.studio.editor.SyntaxHighlighter
import io.github.rosemoe.sora.widget.CodeEditor
import io.noties.markwon.Markwon
import java.io.File

/**
 * Editor de texto simple para el file manager — usa sora-editor (LGPL-2.1,
 * dependencia sin modificar, ver app/build.gradle) en vez de copiar código de
 * proyectos GPL como Xed-Editor. Sin archivos binarios ni mayores a 5MB.
 *
 * Resaltado de sintaxis (fix real 2026-08-01, ver docs/referencias/ides/REFERENCIA_XED_EDITOR.md):
 * los assets de `app/src/main/assets/textmate/` son una copia DIRECTA de
 * `referencia/ides/Xed-Editor-main/core/main/src/main/assets/textmate/` — mismo proyecto que
 * valida en producción (Xed-Editor está publicado en F-Droid/IzzyOnDroid) las mismas gramáticas
 * TextMate contra la misma librería sora-editor que ya usa Kairos. Xed-Editor es GPLv3 — copiar
 * estos archivos de DATOS (gramáticas/config, no código de la librería en sí) es compatible, ver
 * `referencia/ides/Xed-Editor-main/LICENSE` y el aviso de atribución en
 * `app/src/main/assets/textmate/ATTRIBUTION.md`.
 *
 * Reusa `SyntaxHighlighter`/`EditorSchemeSetup` (hallazgo real 2026-08-31, auditoría fresca de
 * `referencia/ides/CodeAssist-main`/`Xed-Editor-main`, ver `docs/referencias/ides/`): este
 * fragment tenía su PROPIO wiring de TextMate duplicado (12 lenguajes, tema `darcula.json`
 * suelto) en vez de reusar el wiring ya construido para Estudio
 * (`com.termux.app.ui.studio.editor.SyntaxHighlighter`, 20 lenguajes incluyendo css/html/yaml/
 * groovy/jsx/tsx, temas Kairos `kairos-ink`/`kairos-paper`/`kairos-contrast`) — dos
 * implementaciones de la misma pieza, una estrictamente más completa que la otra. Migrado a
 * llamar `SyntaxHighlighter.apply()` directo: mismo resultado visual para los 12 lenguajes que
 * ya cubría, más soporte real para el resto sin duplicar código (principio DRY: no mantener dos
 * implementaciones de la misma pieza).
 *
 * Vista previa Markdown (hallazgo real 2026-08-31, ver
 * docs/referencias/interfaz/REFERENCIA_FLET.md seccion "Profundizacion 2026-08-24" -
 * markdown_viewer_app de proyectos_flet-main mostro que Kairos no renderiza Markdown en
 * ningun lado, solo lo resalta como texto plano/sintaxis): io.noties.markwon ya estaba
 * declarado en app/build.gradle (core/ext-strikethrough/linkify/recycler) sin ningun uso
 * real en el codigo -- quedaba como dependencia muerta. El boton "Vista previa" (visible
 * solo para archivos .md) usa esa dependencia ya presente para renderizar el Markdown
 * actual del editor en un TextView dentro de un ScrollView separado
 * (markdown_preview_container en fragment_editor.xml), sin agregar ninguna libreria nueva
 * ni tocar build.gradle.
 */
class EditorFragment : Fragment() {

    private lateinit var editor: CodeEditor
    private lateinit var fileNameText: TextView
    private lateinit var btnPreview: TextView
    private lateinit var editorContainer: FrameLayout
    private lateinit var markdownPreviewContainer: ScrollView
    private lateinit var markdownPreviewText: TextView
    private var filePath: String = ""
    private var originalText: String = ""
    private var isMarkdownFile: Boolean = false
    private var isPreviewMode: Boolean = false

    companion object {
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

        editorContainer = view.findViewById(R.id.editor_container)
        editor = CodeEditor(requireContext())
        editorContainer.addView(
            editor,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )
        // Mismas preferencias (tamaño de fuente, tab, wordwrap, línea actual, tema Kairos) y
        // autocompletado que ya usa Estudio — antes este editor quedaba con los defaults crudos
        // de sora-editor, sin ninguna de las preferencias que el usuario configura en Ajustes.
        // EditorSchemeSetup.apply() ya habilita EditorAutoCompletion internamente.
        EditorSchemeSetup.apply(editor)

        btnPreview = view.findViewById(R.id.btn_preview)
        markdownPreviewContainer = view.findViewById(R.id.markdown_preview_container)
        markdownPreviewText = view.findViewById(R.id.markdown_preview_text)

        view.findViewById<View>(R.id.back_btn).setOnClickListener { confirmBackIfDirty() }
        view.findViewById<View>(R.id.btn_save).setOnClickListener { save() }
        btnPreview.setOnClickListener { togglePreview() }

        loadFile()
    }

    /**
     * Alterna entre editar el Markdown y ver su render (Markwon). Guard `isAdded` porque
     * `Markwon.create(requireContext())` puede correr después de que el usuario ya salió
     * del Fragment si el archivo es grande.
     */
    private fun togglePreview() {
        if (!isAdded) return
        isPreviewMode = !isPreviewMode
        if (isPreviewMode) {
            val markwon = Markwon.create(requireContext())
            markwon.setMarkdown(markdownPreviewText, editor.text.toString())
            editorContainer.visibility = View.GONE
            markdownPreviewContainer.visibility = View.VISIBLE
            btnPreview.text = getString(R.string.editor_preview_edit)
        } else {
            markdownPreviewContainer.visibility = View.GONE
            editorContainer.visibility = View.VISIBLE
            btnPreview.text = getString(R.string.editor_preview)
        }
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
            // Delega en el mismo wiring de TextMate que usa Estudio — ver comentario de clase.
            // SyntaxHighlighter.apply() nunca lanza (cae a EmptyLanguage si la extensión no
            // tiene grammar bundleado), mismo contrato de "nunca romper la edición" que tenía
            // el código duplicado que reemplaza.
            SyntaxHighlighter.apply(editor, file.name)
            val extension = file.extension.lowercase()
            isMarkdownFile = extension == "md"
            btnPreview.visibility = if (isMarkdownFile) View.VISIBLE else View.GONE
        } catch (e: Exception) {
            toast(getString(R.string.editor_read_error, e.message ?: "null"))
            parentFragmentManager.popBackStack()
        }
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

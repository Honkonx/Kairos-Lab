package com.termux.app.ui.studio.tabs

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.google.android.material.tabs.TabLayout
import com.termux.R
import com.termux.app.ui.studio.editor.SyntaxHighlighter
import com.termux.app.ui.studio.filetree.FileIconProvider
import io.github.rosemoe.sora.widget.CodeEditor
import android.widget.TextView
import java.io.OutputStreamWriter

/**
 * Sistema de pestañas de archivos abiertos: un [TabLayout] con vistas custom
 * (`item_file_tab.xml`) + un único [CodeEditor] compartido cuyo contenido se intercambia al
 * cambiar de pestaña. El estado "modificado" (punto en la pestaña) se calcula por comparación
 * de texto al salir de la pestaña o al guardar — no hay listener de cada tecla porque la API de
 * eventos de sora-editor (`subscribeEvent`) no se pudo confirmar contra una referencia real en
 * este repo dentro del tiempo de esta ronda; comparar en los puntos de transición (cambio de
 * pestaña / guardado) es suficiente para la señal visual que pide la ronda.
 *
 * [activateFile] también dispara [SyntaxHighlighter.apply] — es el único punto por el que pasa
 * tanto abrir un archivo nuevo ([openOrSelect]) como cambiar a una pestaña ya abierta (selección
 * de tab), así que resaltar sintaxis ahí cubre ambos casos sin duplicar la llamada en MainActivity.
 */
class EditorTabsController(
    private val tabLayout: TabLayout,
    private val codeEditor: CodeEditor,
    private val emptyStateLabel: View,
    private val statusFileLabel: TextView,
    private val onActiveFileChanged: (OpenFile?) -> Unit
) {

    private val openFiles = mutableListOf<OpenFile>()
    private var activeIndex = -1

    init {
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                activateFile(tab.position)
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {
                syncActiveFileDirtyState()
            }

            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
    }

    fun openOrSelect(uri: Uri, name: String, content: String) {
        val existingIndex = openFiles.indexOfFirst { it.uri == uri }
        if (existingIndex >= 0) {
            tabLayout.getTabAt(existingIndex)?.select()
            return
        }

        val file = OpenFile(uri = uri, name = name, content = content)
        openFiles.add(file)
        tabLayout.visibility = View.VISIBLE
        val tab = tabLayout.newTab().setCustomView(buildTabView(file))
        tabLayout.addTab(tab, openFiles.size - 1, true)
    }

    fun activeFile(): OpenFile? = openFiles.getOrNull(activeIndex)

    /** Copia inmutable de las pestañas abiertas — usada para persistir la sesión (ver
     * [com.termux.app.ui.studio.project.SessionStateManager]), nunca para mutar el estado interno. */
    fun openFilesList(): List<OpenFile> = openFiles.toList()

    /** Selecciona la pestaña de [uri] si ya está abierta — no-op si no existe. Usado al
     * restaurar sesión, después de reabrir todas las pestañas guardadas, para dejar activa la
     * que era activa antes de cerrar la app. */
    fun selectByUri(uri: Uri) {
        val index = openFiles.indexOfFirst { it.uri == uri }
        if (index >= 0) tabLayout.getTabAt(index)?.select()
    }

    /** Compara el texto actual del editor contra el último contenido guardado en memoria y
     * actualiza `isDirty` + el punto de la pestaña — se llama antes de perder la pestaña activa
     * (cambio de pestaña, guardado, cierre). */
    fun syncActiveFileDirtyState() {
        val index = activeIndex
        val file = openFiles.getOrNull(index) ?: return
        val currentText = codeEditor.text.toString()
        val isNowDirty = currentText != file.content
        if (isNowDirty != file.isDirty) {
            file.isDirty = isNowDirty
            updateTabDirtyIndicator(index)
        }
    }

    /** Persiste el texto actual del editor en el [OpenFile] activo, sin tocar `isDirty` — lo
     * usa quien escribe a disco (MainActivity) para saber qué contenido guardar. */
    fun captureActiveEditorText() {
        val file = openFiles.getOrNull(activeIndex) ?: return
        file.content = codeEditor.text.toString()
    }

    fun markActiveFileSaved() {
        val file = openFiles.getOrNull(activeIndex) ?: return
        file.content = codeEditor.text.toString()
        file.isDirty = false
        updateTabDirtyIndicator(activeIndex)
    }

    /** Cierra la pestaña activa — usada por el atajo de teclado Ctrl+W
     * ([com.termux.app.ui.studio.keyboard.KeyboardShortcutHandler]), no-op si no hay ninguna abierta. */
    fun closeActiveTab() {
        val file = openFiles.getOrNull(activeIndex) ?: return
        closeTab(file)
    }

    /** Selecciona la siguiente pestaña, cíclico (de la última vuelve a la primera) — usada por
     * el atajo Ctrl+Tab. No-op si no hay pestañas abiertas. */
    fun selectNextTab() {
        if (openFiles.isEmpty()) return
        val nextIndex = (activeIndex + 1).mod(openFiles.size)
        tabLayout.getTabAt(nextIndex)?.select()
    }

    /** Selecciona la pestaña anterior, cíclico (de la primera vuelve a la última) — usada por
     * el atajo Ctrl+Shift+Tab. No-op si no hay pestañas abiertas. */
    fun selectPreviousTab() {
        if (openFiles.isEmpty()) return
        val previousIndex = (activeIndex - 1).mod(openFiles.size)
        tabLayout.getTabAt(previousIndex)?.select()
    }

    private fun activateFile(position: Int) {
        val file = openFiles.getOrNull(position) ?: return
        activeIndex = position
        SyntaxHighlighter.apply(codeEditor, file.name)
        codeEditor.setText(file.content)
        codeEditor.visibility = View.VISIBLE
        emptyStateLabel.visibility = View.GONE
        statusFileLabel.text = file.name
        onActiveFileChanged(file)
    }

    private fun buildTabView(file: OpenFile): View {
        val view = LayoutInflater.from(tabLayout.context)
            .inflate(R.layout.studio_item_file_tab, tabLayout, false)
        view.findViewById<TextView>(R.id.tab_file_name).text = file.name
        view.findViewById<View>(R.id.tab_close_button).setOnClickListener { closeTab(file) }
        // Acento por tipo de archivo — misma categorización que FileIconProvider usa para el
        // árbol de archivos, ver comentario en item_file_tab.xml.
        val accentColorRes = FileIconProvider.iconFor(file.name, isDirectory = false, isExpanded = false).colorRes
        view.findViewById<View>(R.id.tab_accent).setBackgroundColor(
            ContextCompat.getColor(tabLayout.context, accentColorRes)
        )
        return view
    }

    private fun updateTabDirtyIndicator(index: Int) {
        val tabView = tabLayout.getTabAt(index)?.customView ?: return
        val file = openFiles.getOrNull(index) ?: return
        tabView.findViewById<View>(R.id.tab_dirty_dot).visibility =
            if (file.isDirty) View.VISIBLE else View.GONE
    }

    /** Cierra [file] (botón "X" de la pestaña o [closeActiveTab] vía Ctrl+W) — antes de este fix
     * (auditoría `docs/ide/AUDITORIA_ESTUDIO_PROFUNDA_2026-08-19.md` sección 2.1, el
     * hallazgo más serio de esa ronda) esto descartaba cambios sin guardar sin ningún aviso, y
     * además el chequeo de `isDirty` de la pestaña activa podía estar desactualizado porque solo
     * se recalculaba en [syncActiveFileDirtyState] (llamada desde `onTabUnselected`, nunca desde
     * acá). Ahora: si [file] es la pestaña activa, se sincroniza su `isDirty` primero para que el
     * chequeo sea confiable; si queda dirty, se confirma con el usuario antes de perder el
     * contenido en memoria. */
    private fun closeTab(file: OpenFile) {
        val index = openFiles.indexOf(file)
        if (index == -1) return

        if (index == activeIndex) syncActiveFileDirtyState()

        if (file.isDirty) {
            confirmCloseDirtyTab(file)
        } else {
            removeTab(file)
        }
    }

    private fun confirmCloseDirtyTab(file: OpenFile) {
        val context = tabLayout.context
        AlertDialog.Builder(context)
            .setTitle(R.string.tabs_unsaved_changes_title)
            .setMessage(context.getString(R.string.tabs_unsaved_changes_message, file.name))
            .setPositiveButton(R.string.tabs_save_and_close) { _, _ ->
                if (saveFileToDisk(file)) removeTab(file)
            }
            .setNegativeButton(R.string.tabs_discard_and_close) { _, _ -> removeTab(file) }
            .setNeutralButton(R.string.tabs_cancel_close, null)
            .show()
    }

    /** Escribe [file.content] a disco para "Guardar y cerrar" — si [file] es la pestaña activa,
     * primero captura el texto vivo del editor (puede haber cambios más recientes que la última
     * sincronización). Devuelve `false` si falla, para no cerrar una pestaña cuyo guardado no se
     * pudo confirmar (mismo criterio que [com.termux.app.ui.studio.StudioFragment.saveCurrentFile],
     * reimplementado acá porque ese método solo conoce `currentFileUri`, no [file] en general —
     * por ejemplo, al cerrar con la "X" una pestaña inactiva distinta a la que se está editando). */
    private fun saveFileToDisk(file: OpenFile): Boolean {
        val index = openFiles.indexOf(file)
        if (index == activeIndex) file.content = codeEditor.text.toString()
        val context = tabLayout.context
        return try {
            context.contentResolver.openOutputStream(file.uri, "wt")?.use { stream ->
                OutputStreamWriter(stream).use { it.write(file.content) }
            }
            file.isDirty = false
            if (index == activeIndex) updateTabDirtyIndicator(index)
            true
        } catch (error: Exception) {
            Toast.makeText(context, "No se pudo guardar: ${error.message}", Toast.LENGTH_LONG).show()
            false
        }
    }

    /** Todas las pestañas abiertas quedan con su contenido en disco (`isDirty == false`) tras
     * esta llamada — se usa antes de cambiar de sesión de proyecto
     * ([com.termux.app.ui.studio.StudioFragment.switchToSession]/`openProjectAsSession`) para no
     * perder cambios sin guardar sin tener que interrumpir con un diálogo de confirmación por
     * cada pestaña dirty (a diferencia de [closeActiveTab]/cerrar con la "X", que sí pregunta —
     * cambiar de proyecto abierto es una acción de navegación, no de descarte intencional). */
    fun saveAllDirtyTabs() {
        syncActiveFileDirtyState()
        openFiles.filter { it.isDirty }.forEach { saveFileToDisk(it) }
    }

    /** Cierra TODAS las pestañas sin preguntar ni volver a guardar — llamar SIEMPRE después de
     * [saveAllDirtyTabs] (que ya persistió cualquier cambio pendiente), nunca como reemplazo de
     * ese guardado. Usado al cambiar/cerrar una sesión de proyecto completa; la acción directa
     * del usuario de cerrar UNA pestaña sigue pasando por [closeTab]/[closeActiveTab]. */
    fun closeAllTabsSilently() {
        openFiles.toList().forEach { removeTab(it) }
    }

    private fun removeTab(file: OpenFile) {
        val index = openFiles.indexOf(file)
        if (index == -1) return

        openFiles.removeAt(index)
        tabLayout.removeTabAt(index)

        if (openFiles.isEmpty()) {
            activeIndex = -1
            codeEditor.setText("")
            codeEditor.visibility = View.GONE
            emptyStateLabel.visibility = View.VISIBLE
            tabLayout.visibility = View.GONE
            statusFileLabel.text = ""
            onActiveFileChanged(null)
        }
        // Si quedan pestañas, TabLayout.removeTabAt ya dispara onTabSelected sobre la pestaña
        // vecina automáticamente — activateFile() se encarga de refrescar el editor.
    }
}

package com.termux.app.ui

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.tabs.TabLayout
import com.termux.R
import com.termux.app.util.kairosThemeColor
import com.termux.shared.termux.TermuxConstants
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private val TEXT_EXTENSIONS = setOf(
    "txt", "md", "sh", "py", "js", "ts", "json", "xml", "kt", "java",
    "gradle", "properties", "yaml", "yml", "conf", "cfg", "log", "ini",
    "html", "css", "csv", "toml", "bashrc", "profile"
)
private const val MAX_EDITABLE_SIZE = 5L * 1024 * 1024 // 5MB
private const val PREFS_NAME = "kairos_file_manager"
private const val PREF_BOOKMARKS = "bookmarks"
private const val MAX_HISTORY = 50

/**
 * Explorador de archivos — dos raíces fijas (Termux $HOME / almacenamiento interno del
 * teléfono), navegación in/out con historial atrás/adelante tipo browser, marcadores de
 * carpetas favoritas, selección múltiple con operaciones batch (copiar/cortar/eliminar/
 * comprimir), toggle de archivos ocultos y "abrir con" para archivos no editables. Toque
 * largo en un ítem activa/alterna el modo de selección; el botón "⋮" por fila abre el menú
 * de un solo archivo (copiar/cortar/renombrar/eliminar/abrir con/seleccionar). Tocar un
 * archivo de texto lo abre en EditorFragment. MANAGE_EXTERNAL_STORAGE ya se pide en el
 * wizard, así que java.io.File directo alcanza para ambas raíces sin Storage Access
 * Framework. Ver docs/arquitectura/INVESTIGACION_GESTOR_ARCHIVOS_2026-09-01.md (esfuerzo
 * bajo #1/#2/#3) para el contexto de esta ronda.
 */
class FileManagerFragment : Fragment() {

    companion object {
        private const val ARG_START_PATH = "start_path"

        /** Abre el gestor de archivos ya posicionado en [startPath] — usado por ApkFragment
         *  tras un decode ("Abrir carpeta en Archivos"). Si [startPath] no cae bajo el $HOME
         *  real de Termux, arranca en $HOME normal en vez de confiar en la ruta recibida. */
        fun newInstance(startPath: String): FileManagerFragment {
            return FileManagerFragment().apply {
                arguments = Bundle().apply { putString(ARG_START_PATH, startPath) }
            }
        }
    }

    private lateinit var pathText: TextView
    private lateinit var upButton: TextView
    private lateinit var backButton: TextView
    private lateinit var forwardButton: TextView
    private lateinit var toggleHiddenButton: TextView
    private lateinit var bookmarkButton: TextView
    private lateinit var bookmarksListButton: TextView
    private lateinit var tabLayout: TabLayout
    private lateinit var recycler: RecyclerView
    private lateinit var emptyState: TextView
    private lateinit var selectionBar: View
    private lateinit var selectionCountText: TextView
    private lateinit var clipboardBar: View
    private lateinit var clipboardBarLabel: TextView
    private lateinit var bookmarksPrefs: SharedPreferences

    private var currentRoot: File = File(TermuxConstants.TERMUX_HOME_DIR_PATH)
    private var currentDir: File = currentRoot

    // Portapapeles batch: lista de archivos/carpetas, copiar o cortar — antes admitía un
    // solo ítem (clipboardFile: File?), ahora respalda la selección múltiple del punto 1.
    private var clipboardFiles: List<File> = emptyList()
    private var clipboardCut: Boolean = false

    // Historial de navegación tipo browser (atrás/adelante) — en memoria, no persistido
    // entre sesiones (esfuerzo bajo #3 de la investigación no lo exige).
    private val backStack = ArrayDeque<File>()
    private val forwardStack = ArrayDeque<File>()
    private var suppressTabReset = false

    // Modo de selección múltiple — long-press en un ítem lo activa/alterna.
    private var selectionMode = false
    private val selectedPaths = mutableSetOf<String>()

    // Listado crudo (sin filtrar) de la carpeta actual, cacheado para poder re-filtrar por
    // "mostrar ocultos" o re-renderizar tras un cambio de selección sin volver a golpear disco.
    private var currentEntriesRaw: List<File> = emptyList()
    private var showHidden = false

    override fun onCreateView(inflater: LayoutInflater, c: ViewGroup?, b: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_file_manager, c, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        pathText = view.findViewById(R.id.path_text)
        upButton = view.findViewById(R.id.btn_up)
        backButton = view.findViewById(R.id.btn_back)
        forwardButton = view.findViewById(R.id.btn_forward)
        toggleHiddenButton = view.findViewById(R.id.btn_toggle_hidden)
        bookmarkButton = view.findViewById(R.id.btn_bookmark)
        bookmarksListButton = view.findViewById(R.id.btn_bookmarks_list)
        tabLayout = view.findViewById(R.id.tab_roots)
        recycler = view.findViewById(R.id.files_recycler)
        emptyState = view.findViewById(R.id.empty_state)
        selectionBar = view.findViewById(R.id.selection_bar)
        selectionCountText = view.findViewById(R.id.selection_count)
        clipboardBar = view.findViewById(R.id.clipboard_bar)
        clipboardBarLabel = view.findViewById(R.id.clipboard_bar_label)
        bookmarksPrefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        recycler.layoutManager = LinearLayoutManager(requireContext())

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                // navigateToBookmark() selecciona la tab programáticamente cuando un
                // marcador vive en la otra raíz — en ese caso no queremos resetear
                // currentDir a currentRoot, la navegación real la maneja navigateToDir().
                if (suppressTabReset) { suppressTabReset = false; return }
                currentRoot = if (tab.position == 0)
                    File(TermuxConstants.TERMUX_HOME_DIR_PATH)
                else
                    Environment.getExternalStorageDirectory()
                currentDir = currentRoot
                backStack.clear()
                forwardStack.clear()
                exitSelectionMode()
                refresh()
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        upButton.setOnClickListener {
            val parent = currentDir.parentFile
            if (parent != null && currentDir.path != currentRoot.path) navigateToDir(parent)
        }
        backButton.setOnClickListener {
            if (backStack.isEmpty()) return@setOnClickListener
            forwardStack.addLast(currentDir)
            currentDir = backStack.removeLast()
            exitSelectionMode()
            refresh()
        }
        forwardButton.setOnClickListener {
            if (forwardStack.isEmpty()) return@setOnClickListener
            backStack.addLast(currentDir)
            currentDir = forwardStack.removeLast()
            exitSelectionMode()
            refresh()
        }
        toggleHiddenButton.setOnClickListener {
            showHidden = !showHidden
            toggleHiddenButton.text = if (showHidden) "🙈" else "👁"
            applyDisplayList()
        }
        bookmarkButton.setOnClickListener { toggleBookmark(currentDir) }
        bookmarksListButton.setOnClickListener { showBookmarksDialog() }

        view.findViewById<TextView>(R.id.btn_sel_copy).setOnClickListener {
            setClipboardBatch(selectedFiles(), cut = false); exitSelectionMode()
        }
        view.findViewById<TextView>(R.id.btn_sel_cut).setOnClickListener {
            setClipboardBatch(selectedFiles(), cut = true); exitSelectionMode()
        }
        view.findViewById<TextView>(R.id.btn_sel_compress).setOnClickListener {
            compressBatch(selectedFiles())
        }
        view.findViewById<TextView>(R.id.btn_sel_delete).setOnClickListener {
            confirmDeleteBatch(selectedFiles())
        }
        view.findViewById<TextView>(R.id.btn_sel_cancel).setOnClickListener {
            exitSelectionMode()
        }
        view.findViewById<TextView>(R.id.btn_clipboard_paste).setOnClickListener {
            confirmPasteInto(currentDir)
        }
        view.findViewById<TextView>(R.id.btn_clipboard_cancel).setOnClickListener {
            clipboardFiles = emptyList()
            updateClipboardBar()
        }

        arguments?.getString(ARG_START_PATH)?.let { startPath ->
            val target = File(startPath)
            val homeRoot = File(TermuxConstants.TERMUX_HOME_DIR_PATH)
            if (target.canonicalPath.startsWith(homeRoot.canonicalPath) && target.isDirectory) {
                currentRoot = homeRoot
                currentDir = target
            }
        }

        refresh()
    }

    // Bug real (auditoría 2026-08-13, ver docs/viejo/AUDITORIA_CODIGO_2026-08-13.md
    // §1.12): listFiles() corría directo en el hilo de UI — I/O local, normalmente rápido,
    // pero sin la misma cautela (Thread{} + resultado posteado) que usa el resto de la app
    // para I/O de disco. Se captura currentDir/currentRoot ANTES del Thread (por si el
    // usuario navega a otra carpeta mientras el listado corre) y se descarta el resultado si
    // ya no corresponde a la carpeta actual al volver al hilo de UI.
    private fun refresh() {
        pathText.text = currentDir.path
        val atRoot = currentDir.path == currentRoot.path
        upButton.alpha = if (atRoot) 0.3f else 1f
        upButton.isEnabled = !atRoot
        updateNavButtons()
        updateBookmarkButton()

        val dir = currentDir
        val root = currentRoot
        Thread {
            val listed = dir.listFiles()
            val entries = listed?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase(Locale.getDefault()) }))
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded || currentDir.path != dir.path) return@runOnUiThread
                if (listed == null) {
                    currentEntriesRaw = emptyList()
                    showEmpty(
                        if (!Environment.isExternalStorageManager() && root.path != TermuxConstants.TERMUX_HOME_DIR_PATH)
                            getString(R.string.file_manager_error_no_storage_permission)
                        else getString(R.string.file_manager_error_cannot_read_folder)
                    )
                    return@runOnUiThread
                }
                if (entries!!.isEmpty()) {
                    currentEntriesRaw = emptyList()
                    showEmpty(getString(R.string.file_manager_empty_folder))
                    return@runOnUiThread
                }
                currentEntriesRaw = entries
                applyDisplayList()
            }
        }.start()
    }

    // Re-filtra/re-renderiza sin volver a listar disco — usado por el toggle de ocultos y
    // por cualquier cambio del modo de selección (marcar/desmarcar un ítem).
    private fun applyDisplayList() {
        val displayed = if (showHidden) currentEntriesRaw else currentEntriesRaw.filter { !it.name.startsWith(".") }
        if (displayed.isEmpty()) {
            showEmpty(getString(R.string.file_manager_empty_folder))
            return
        }
        emptyState.visibility = View.GONE
        recycler.visibility = View.VISIBLE
        recycler.adapter = FileAdapter(
            displayed,
            selectionMode,
            selectedPaths.toSet(),
            onClick = { file ->
                if (selectionMode) {
                    toggleSelection(file)
                } else if (file.isDirectory) {
                    navigateToDir(file)
                } else if (isEditable(file)) {
                    navigateTo(EditorFragment.newInstance(file.absolutePath))
                } else {
                    openFileWithFallback(file)
                }
            },
            onLongClick = { file -> toggleSelection(file) },
            onMenuClick = { file -> showFileMenu(file) }
        )
    }

    private fun isEditable(file: File): Boolean {
        if (file.length() > MAX_EDITABLE_SIZE) return false
        val ext = file.extension.lowercase(Locale.getDefault())
        return ext in TEXT_EXTENSIONS || (ext.isEmpty() && file.length() < 256 * 1024)
    }

    private fun navigateTo(fragment: Fragment) {
        parentFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .addToBackStack(null)
            .commit()
    }

    // ── Navegación (subir / historial atrás-adelante) ──────────────────────

    private fun navigateToDir(target: File) {
        if (currentDir.path != target.path) {
            backStack.addLast(currentDir)
            if (backStack.size > MAX_HISTORY) backStack.removeFirst()
            forwardStack.clear()
        }
        currentDir = target
        exitSelectionMode()
        refresh()
    }

    private fun updateNavButtons() {
        backButton.isEnabled = backStack.isNotEmpty()
        backButton.alpha = if (backStack.isNotEmpty()) 1f else 0.3f
        forwardButton.isEnabled = forwardStack.isNotEmpty()
        forwardButton.alpha = if (forwardStack.isNotEmpty()) 1f else 0.3f
    }

    // ── Marcadores ───────────────────────────────────────────────────────

    private fun getBookmarks(): List<String> =
        bookmarksPrefs.getStringSet(PREF_BOOKMARKS, emptySet())?.toList()?.sorted() ?: emptyList()

    private fun toggleBookmark(dir: File) {
        val set = bookmarksPrefs.getStringSet(PREF_BOOKMARKS, emptySet())!!.toMutableSet()
        val label = dir.name.ifEmpty { dir.path }
        if (set.contains(dir.path)) {
            set.remove(dir.path)
            bookmarksPrefs.edit().putStringSet(PREF_BOOKMARKS, set).apply()
            toast(getString(R.string.file_manager_bookmark_removed, label))
        } else {
            set.add(dir.path)
            bookmarksPrefs.edit().putStringSet(PREF_BOOKMARKS, set).apply()
            toast(getString(R.string.file_manager_bookmark_added, label))
        }
        updateBookmarkButton()
    }

    private fun removeBookmark(path: String) {
        val set = bookmarksPrefs.getStringSet(PREF_BOOKMARKS, emptySet())!!.toMutableSet()
        set.remove(path)
        bookmarksPrefs.edit().putStringSet(PREF_BOOKMARKS, set).apply()
    }

    private fun updateBookmarkButton() {
        val bookmarked = getBookmarks().contains(currentDir.path)
        bookmarkButton.text = if (bookmarked) "★" else "☆"
        bookmarkButton.setTextColor(
            if (bookmarked) requireContext().kairosThemeColor(R.attr.kairosGreen)
            else requireContext().kairosThemeColor(R.attr.kairosText)
        )
    }

    private fun showBookmarksDialog() {
        val bookmarks = getBookmarks()
        if (bookmarks.isEmpty()) {
            toast(getString(R.string.file_manager_bookmarks_empty))
            return
        }
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.file_manager_bookmarks_title))
            .setItems(bookmarks.toTypedArray()) { _, which -> navigateToBookmark(bookmarks[which]) }
            .setNegativeButton(getString(R.string.file_manager_btn_cancel), null)
            .show()
    }

    private fun navigateToBookmark(path: String) {
        val target = File(path)
        if (!target.isDirectory) {
            toast(getString(R.string.file_manager_bookmark_gone, path))
            removeBookmark(path)
            return
        }
        val homeRoot = File(TermuxConstants.TERMUX_HOME_DIR_PATH)
        val isHome = try {
            target.canonicalPath.startsWith(homeRoot.canonicalPath)
        } catch (e: Exception) {
            target.path.startsWith(homeRoot.path)
        }
        val newRoot = if (isHome) homeRoot else Environment.getExternalStorageDirectory()
        if (newRoot.path != currentRoot.path) {
            currentRoot = newRoot
            suppressTabReset = true
            tabLayout.getTabAt(if (isHome) 0 else 1)?.select()
        }
        navigateToDir(target)
    }

    // ── Selección múltiple + operaciones batch ──────────────────────────

    private fun selectedFiles(): List<File> =
        currentEntriesRaw.filter { selectedPaths.contains(it.path) }

    private fun toggleSelection(file: File) {
        if (!selectedPaths.remove(file.path)) selectedPaths.add(file.path)
        selectionMode = selectedPaths.isNotEmpty()
        updateSelectionBar()
        applyDisplayList()
    }

    private fun exitSelectionMode() {
        if (!selectionMode && selectedPaths.isEmpty()) return
        selectionMode = false
        selectedPaths.clear()
        updateSelectionBar()
        if (view != null) applyDisplayList()
    }

    private fun updateSelectionBar() {
        selectionBar.visibility = if (selectionMode) View.VISIBLE else View.GONE
        if (selectionMode) selectionCountText.text = getString(R.string.file_manager_selection_count, selectedPaths.size)
    }

    private fun setClipboardBatch(files: List<File>, cut: Boolean) {
        if (files.isEmpty()) return
        clipboardFiles = files
        clipboardCut = cut
        updateClipboardBar()
    }

    // Fix UX 2026-09-14 (docs/humano334.md): antes la única señal de "hay algo en el
    // portapapeles" era un Snackbar.LENGTH_LONG (~2.75s) que desaparecía solo — si el usuario
    // no tocaba "Pegar aquí" a tiempo, la única forma de volver a pegar era el menú de tres
    // puntos, sin ningún indicador visual de que el portapapeles seguía teniendo algo. Ahora es
    // una barra persistente (misma visibilidad que selection_bar) mientras clipboardFiles no
    // esté vacío.
    private fun updateClipboardBar() {
        clipboardBar.visibility = if (clipboardFiles.isNotEmpty()) View.VISIBLE else View.GONE
        if (clipboardFiles.isNotEmpty()) {
            val verb = if (clipboardCut) getString(R.string.file_manager_verb_cut) else getString(R.string.file_manager_verb_copied)
            val label = if (clipboardFiles.size == 1) clipboardFiles[0].name
                else getString(R.string.file_manager_clipboard_multi, clipboardFiles.size)
            clipboardBarLabel.text = getString(R.string.file_manager_snackbar_clipboard_format, verb, label)
        }
    }

    // Fix UX 2026-09-14 (docs/humano334.md): antes pasteInto() se ejecutaba directo, sin
    // confirmación — a diferencia de confirmDeleteBatch()/showDeleteConfirm() en este mismo
    // archivo, que sí usan AlertDialog antes de una acción con impacto real. Mismo patrón acá.
    private fun confirmPasteInto(targetDir: File) {
        val srcs = clipboardFiles
        if (srcs.isEmpty()) return
        val label = if (srcs.size == 1) srcs[0].name else getString(R.string.file_manager_clipboard_multi, srcs.size)
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.file_manager_action_paste_here))
            .setMessage(getString(R.string.file_manager_dialog_msg_paste, label))
            .setPositiveButton(getString(R.string.file_manager_action_paste_here)) { _, _ -> pasteInto(targetDir) }
            .setNegativeButton(getString(R.string.file_manager_clipboard_bar_cancel), null)
            .show()
    }

    private fun confirmDeleteBatch(files: List<File>) {
        if (files.isEmpty()) return
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.file_manager_action_delete))
            .setMessage(getString(R.string.file_manager_dialog_msg_delete_batch, files.size))
            .setPositiveButton(getString(R.string.file_manager_action_delete)) { _, _ ->
                var okCount = 0
                for (f in files) {
                    try {
                        val ok = if (f.isDirectory) f.deleteRecursively() else f.delete()
                        if (ok) okCount++
                    } catch (e: Exception) { /* se cuenta como fallo abajo, no interrumpe el resto del batch */ }
                }
                exitSelectionMode()
                refresh()
                toast(getString(R.string.file_manager_toast_batch_result, okCount, files.size - okCount))
            }
            .setNegativeButton(getString(R.string.file_manager_btn_cancel), null)
            .show()
    }

    private fun compressBatch(files: List<File>) {
        if (files.isEmpty()) return
        val targetDir = currentDir
        exitSelectionMode()
        Thread {
            val zipFile = File(targetDir, "archivos_${System.currentTimeMillis()}.zip")
            try {
                ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zos ->
                    for (f in files) addToZip(zos, f, f.name)
                }
                if (!isAdded) return@Thread
                requireActivity().runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    refresh()
                    toast(getString(R.string.file_manager_toast_compress_done, zipFile.name))
                }
            } catch (e: Exception) {
                zipFile.delete()
                if (!isAdded) return@Thread
                requireActivity().runOnUiThread {
                    if (isAdded) toast(getString(R.string.file_manager_error_compress_failed))
                }
            }
        }.start()
    }

    private fun addToZip(zos: ZipOutputStream, file: File, entryName: String) {
        if (file.isDirectory) {
            val children = file.listFiles()
            if (children.isNullOrEmpty()) {
                zos.putNextEntry(ZipEntry("$entryName/"))
                zos.closeEntry()
                return
            }
            for (child in children) addToZip(zos, child, "$entryName/${child.name}")
        } else {
            zos.putNextEntry(ZipEntry(entryName))
            file.inputStream().use { it.copyTo(zos) }
            zos.closeEntry()
        }
    }

    // ── Menú de un solo archivo (copiar/cortar/renombrar/eliminar/abrir con/seleccionar) ──

    private fun showFileMenu(file: File) {
        val optPaste = getString(R.string.file_manager_action_paste_here)
        val optSelect = getString(R.string.file_manager_action_select)
        val optOpenWith = getString(R.string.file_manager_action_open_with)
        val optCopy = getString(R.string.file_manager_action_copy)
        val optCut = getString(R.string.file_manager_action_cut)
        val optRename = getString(R.string.file_manager_action_rename)
        val optDelete = getString(R.string.file_manager_action_delete)
        val options = mutableListOf(optSelect, optCopy, optCut, optRename, optDelete)
        if (!file.isDirectory) options.add(1, optOpenWith)
        if (clipboardFiles.isNotEmpty()) options.add(0, optPaste)
        AlertDialog.Builder(requireContext())
            .setTitle(file.name)
            .setItems(options.toTypedArray()) { _, which ->
                when (options[which]) {
                    optPaste -> confirmPasteInto(currentDir)
                    optSelect -> toggleSelection(file)
                    optOpenWith -> openFileWithFallback(file)
                    optCopy -> setClipboardBatch(listOf(file), cut = false)
                    optCut -> setClipboardBatch(listOf(file), cut = true)
                    optRename -> showRenameDialog(file)
                    optDelete -> showDeleteConfirm(file)
                }
            }
            .show()
    }

    // "Abrir con" (esfuerzo bajo #3) — content:// vía el FileProvider dedicado "filemanager"
    // (ver AndroidManifest.xml + file_manager_paths.xml). Si no hay ninguna app que resuelva
    // el Intent (o el archivo cae fuera de las rutas declaradas por el FileProvider), cae al
    // toast informativo de nombre+tamaño que ya mostraba el comportamiento anterior.
    private fun openFileWithFallback(file: File) {
        try {
            val uri = FileProvider.getUriForFile(requireContext(), "${requireContext().packageName}.filemanager", file)
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase(Locale.getDefault())) ?: "*/*"
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.file_manager_action_open_with)))
        } catch (e: Exception) {
            toast(getString(R.string.file_manager_toast_file_info, file.name, humanSize(file.length())))
        }
    }

    private fun pasteInto(targetDir: File) {
        val srcs = clipboardFiles
        if (srcs.isEmpty()) return
        var successCount = 0
        var failCount = 0
        for (src in srcs) {
            // Auditoría 2026-07-27: sin este chequeo, cortar /foo y pegarlo dentro de
            // /foo/bar hace que copyRecursively() recorra /foo mientras escribe una copia
            // de sí misma dentro de su propio subárbol — corrupción/recursión sin fin
            // práctico. src.name también cubre "pegar en la misma carpeta de origen".
            if (src.isDirectory) {
                val srcCanon = src.canonicalPath
                val targetCanon = targetDir.canonicalPath
                if (targetCanon == srcCanon || targetCanon.startsWith(srcCanon + File.separator)) {
                    failCount++
                    continue
                }
            }
            val dest = File(targetDir, src.name)
            if (dest.exists()) {
                failCount++
                continue
            }
            try {
                val ok = if (src.isDirectory) src.copyRecursively(dest, overwrite = false) else {
                    src.copyTo(dest, overwrite = false); true
                }
                if (!ok) { failCount++; continue }
                if (clipboardCut) {
                    if (src.isDirectory) src.deleteRecursively() else src.delete()
                }
                successCount++
            } catch (e: Exception) {
                failCount++
            }
        }
        // Simplificación deliberada: tras un corte (aunque sea parcial) se limpia el
        // portapapeles completo en vez de trackear qué ítems puntuales sí se movieron —
        // mismo comportamiento que el clipboard de un solo archivo antes de esta ronda.
        if (clipboardCut) clipboardFiles = emptyList()
        updateClipboardBar()
        refresh()
        if (failCount == 0) {
            val label = if (srcs.size == 1) srcs[0].name else getString(R.string.file_manager_clipboard_multi, srcs.size)
            toast(getString(R.string.file_manager_toast_done, label))
        } else {
            toast(getString(R.string.file_manager_toast_batch_result, successCount, failCount))
        }
    }

    private fun showRenameDialog(file: File) {
        val edit = EditText(requireContext()).apply { setText(file.name) }
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.file_manager_action_rename))
            .setView(edit)
            .setPositiveButton(getString(R.string.file_manager_action_rename)) { _, _ ->
                val newName = edit.text.toString().trim()
                if (newName.isEmpty()) { toast(getString(R.string.file_manager_error_empty_name)); return@setPositiveButton }
                val dest = File(file.parentFile, newName)
                if (dest.exists()) { toast(getString(R.string.file_manager_error_name_exists, newName)); return@setPositiveButton }
                if (file.renameTo(dest)) { refresh() } else { toast(getString(R.string.file_manager_error_rename_failed)) }
            }
            .setNegativeButton(getString(R.string.file_manager_btn_cancel), null)
            .show()
    }

    private fun showDeleteConfirm(file: File) {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.file_manager_action_delete))
            .setMessage(getString(R.string.file_manager_dialog_msg_delete, file.name))
            .setPositiveButton(getString(R.string.file_manager_action_delete)) { _, _ ->
                try {
                    val ok = if (file.isDirectory) file.deleteRecursively() else file.delete()
                    if (ok) { refresh() } else { toast(getString(R.string.file_manager_error_delete_failed)) }
                } catch (e: Exception) {
                    toast(getString(R.string.file_manager_error_generic, e.message))
                }
            }
            .setNegativeButton(getString(R.string.file_manager_btn_cancel), null)
            .show()
    }

    private fun toast(msg: String) = Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()

    private fun showEmpty(message: String) {
        emptyState.text = message
        emptyState.visibility = View.VISIBLE
        recycler.visibility = View.GONE
        recycler.adapter = null
    }

    private fun humanSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "%.1f KB".format(kb)
        val mb = kb / 1024.0
        if (mb < 1024) return "%.1f MB".format(mb)
        return "%.1f GB".format(mb / 1024.0)
    }

    private inner class FileAdapter(
        private val files: List<File>,
        private val selectionMode: Boolean,
        private val selectedPaths: Set<String>,
        private val onClick: (File) -> Unit,
        private val onLongClick: (File) -> Unit,
        private val onMenuClick: (File) -> Unit
    ) : RecyclerView.Adapter<FileAdapter.VH>() {

        private val dateFmt = SimpleDateFormat("dd/MM/yy HH:mm", Locale.getDefault())

        inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val card: MaterialCardView = itemView.findViewById(R.id.file_row_card)
            val icon: TextView = itemView.findViewById(R.id.file_icon)
            val name: TextView = itemView.findViewById(R.id.file_name)
            val meta: TextView = itemView.findViewById(R.id.file_meta)
            val menuBtn: TextView = itemView.findViewById(R.id.file_menu_btn)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_file_row, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val f = files[position]
            val ctx = holder.itemView.context
            val selected = selectedPaths.contains(f.path)
            holder.name.text = f.name
            holder.meta.text = if (f.isDirectory) getString(R.string.file_manager_label_folder) else "${humanSize(f.length())} · ${dateFmt.format(Date(f.lastModified()))}"
            if (selectionMode) {
                holder.icon.text = if (selected) "✅" else "⬜"
                holder.card.strokeColor = ctx.kairosThemeColor(if (selected) R.attr.kairosGreen else R.attr.kairosBorder)
            } else {
                holder.icon.text = if (f.isDirectory) "📁" else "📄"
                holder.card.strokeColor = ctx.kairosThemeColor(R.attr.kairosBorder)
            }
            holder.card.setOnClickListener { onClick(f) }
            holder.card.setOnLongClickListener { onLongClick(f); true }
            holder.menuBtn.setOnClickListener { onMenuClick(f) }
        }

        override fun getItemCount() = files.size
    }
}

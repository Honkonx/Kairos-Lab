package com.termux.app.ui

import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import com.termux.R
import com.termux.shared.termux.TermuxConstants
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val TEXT_EXTENSIONS = setOf(
    "txt", "md", "sh", "py", "js", "ts", "json", "xml", "kt", "java",
    "gradle", "properties", "yaml", "yml", "conf", "cfg", "log", "ini",
    "html", "css", "csv", "toml", "bashrc", "profile"
)
private const val MAX_EDITABLE_SIZE = 5L * 1024 * 1024 // 5MB

/**
 * Explorador de archivos — dos raíces fijas (Termux $HOME / almacenamiento interno del
 * teléfono), navegación in/out. Toque largo abre copiar/cortar/pegar/renombrar/eliminar
 * (mover = cortar + pegar). Tocar un archivo de texto lo abre en EditorFragment; el resto
 * muestra nombre + tamaño. MANAGE_EXTERNAL_STORAGE ya se pide en el wizard, así que
 * java.io.File directo alcanza para ambas raíces sin Storage Access Framework.
 */
class FileManagerFragment : Fragment() {

    private lateinit var pathText: TextView
    private lateinit var upButton: TextView
    private lateinit var recycler: RecyclerView
    private lateinit var emptyState: TextView

    private var currentRoot: File = File(TermuxConstants.TERMUX_HOME_DIR_PATH)
    private var currentDir: File = currentRoot

    // Portapapeles simple: un solo archivo/carpeta a la vez, copiar o cortar.
    private var clipboardFile: File? = null
    private var clipboardCut: Boolean = false

    override fun onCreateView(inflater: LayoutInflater, c: ViewGroup?, b: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_file_manager, c, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        pathText = view.findViewById(R.id.path_text)
        upButton = view.findViewById(R.id.btn_up)
        recycler = view.findViewById(R.id.files_recycler)
        emptyState = view.findViewById(R.id.empty_state)
        recycler.layoutManager = LinearLayoutManager(requireContext())

        view.findViewById<TabLayout>(R.id.tab_roots).addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                currentRoot = if (tab.position == 0)
                    File(TermuxConstants.TERMUX_HOME_DIR_PATH)
                else
                    Environment.getExternalStorageDirectory()
                currentDir = currentRoot
                refresh()
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        upButton.setOnClickListener {
            val parent = currentDir.parentFile
            if (parent != null && currentDir.path != currentRoot.path) {
                currentDir = parent
                refresh()
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

        val dir = currentDir
        val root = currentRoot
        Thread {
            val listed = dir.listFiles()
            val entries = listed?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase(Locale.getDefault()) }))
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded || currentDir.path != dir.path) return@runOnUiThread
                if (listed == null) {
                    showEmpty(
                        if (!Environment.isExternalStorageManager() && root.path != TermuxConstants.TERMUX_HOME_DIR_PATH)
                            getString(R.string.file_manager_error_no_storage_permission)
                        else getString(R.string.file_manager_error_cannot_read_folder)
                    )
                    return@runOnUiThread
                }
                if (entries!!.isEmpty()) {
                    showEmpty(getString(R.string.file_manager_empty_folder))
                    return@runOnUiThread
                }
                emptyState.visibility = View.GONE
                recycler.visibility = View.VISIBLE
                recycler.adapter = FileAdapter(
                    entries,
                    onClick = { file ->
                        if (file.isDirectory) {
                            currentDir = file
                            refresh()
                        } else if (isEditable(file)) {
                            navigateTo(EditorFragment.newInstance(file.absolutePath))
                        } else {
                            Toast.makeText(requireContext(), getString(R.string.file_manager_toast_file_info, file.name, humanSize(file.length())), Toast.LENGTH_SHORT).show()
                        }
                    },
                    onLongClick = { file -> showFileMenu(file) }
                )
            }
        }.start()
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

    // ── Menú de archivo (copiar/cortar/eliminar/renombrar) ────────────────

    private fun showFileMenu(file: File) {
        val optCopy = getString(R.string.file_manager_action_copy)
        val optCut = getString(R.string.file_manager_action_cut)
        val optRename = getString(R.string.file_manager_action_rename)
        val optDelete = getString(R.string.file_manager_action_delete)
        val optPaste = getString(R.string.file_manager_action_paste_here)
        val options = mutableListOf(optCopy, optCut, optRename, optDelete)
        if (clipboardFile != null) options.add(0, optPaste)
        AlertDialog.Builder(requireContext())
            .setTitle(file.name)
            .setItems(options.toTypedArray()) { _, which ->
                when (options[which]) {
                    optPaste -> pasteInto(currentDir)
                    optCopy -> setClipboard(file, cut = false)
                    optCut -> setClipboard(file, cut = true)
                    optRename -> showRenameDialog(file)
                    optDelete -> showDeleteConfirm(file)
                }
            }
            .show()
    }

    private fun setClipboard(file: File, cut: Boolean) {
        clipboardFile = file
        clipboardCut = cut
        val view = view ?: return
        val verb = if (cut) getString(R.string.file_manager_verb_cut) else getString(R.string.file_manager_verb_copied)
        Snackbar.make(view, getString(R.string.file_manager_snackbar_clipboard_format, verb, file.name), Snackbar.LENGTH_LONG)
            .setAction(getString(R.string.file_manager_action_paste_here)) { pasteInto(currentDir) }
            .show()
    }

    private fun pasteInto(targetDir: File) {
        val src = clipboardFile ?: return
        // Auditoría 2026-07-27: sin este chequeo, cortar /foo y pegarlo dentro de
        // /foo/bar hace que copyRecursively() recorra /foo mientras escribe una copia
        // de sí misma dentro de su propio subárbol — corrupción/recursión sin fin
        // práctico. src.name también cubre "pegar en la misma carpeta de origen".
        if (src.isDirectory) {
            val srcCanon = src.canonicalPath
            val targetCanon = targetDir.canonicalPath
            if (targetCanon == srcCanon || targetCanon.startsWith(srcCanon + File.separator)) {
                toast(getString(R.string.file_manager_error_paste_into_self, src.name))
                return
            }
        }
        val dest = File(targetDir, src.name)
        if (dest.exists()) {
            toast(getString(R.string.file_manager_error_already_exists, src.name))
            return
        }
        try {
            val ok = if (src.isDirectory) src.copyRecursively(dest, overwrite = false) else {
                src.copyTo(dest, overwrite = false); true
            }
            if (!ok) { toast(getString(R.string.file_manager_error_copy_failed)); return }
            if (clipboardCut) {
                if (src.isDirectory) src.deleteRecursively() else src.delete()
                clipboardFile = null
            }
            refresh()
            toast(getString(R.string.file_manager_toast_done, src.name))
        } catch (e: Exception) {
            toast(getString(R.string.file_manager_error_generic, e.message))
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
        private val onClick: (File) -> Unit,
        private val onLongClick: (File) -> Unit
    ) : RecyclerView.Adapter<FileAdapter.VH>() {

        private val dateFmt = SimpleDateFormat("dd/MM/yy HH:mm", Locale.getDefault())

        inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val card: View = itemView.findViewById(R.id.file_row_card)
            val icon: TextView = itemView.findViewById(R.id.file_icon)
            val name: TextView = itemView.findViewById(R.id.file_name)
            val meta: TextView = itemView.findViewById(R.id.file_meta)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_file_row, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val f = files[position]
            holder.name.text = f.name
            holder.icon.text = if (f.isDirectory) "📁" else "📄"
            holder.meta.text = if (f.isDirectory) getString(R.string.file_manager_label_folder) else "${humanSize(f.length())} · ${dateFmt.format(Date(f.lastModified()))}"
            holder.card.setOnClickListener { onClick(f) }
            holder.card.setOnLongClickListener { onLongClick(f); true }
        }

        override fun getItemCount() = files.size
    }
}

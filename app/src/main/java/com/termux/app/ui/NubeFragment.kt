package com.termux.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import com.termux.R
import com.termux.app.util.ManagerNativeUtils
import com.termux.app.util.NubeServer
import com.termux.app.util.TunnelManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pantalla "Nube" — pedido explícito del usuario (docs/humano*.md): convertir el
 * teléfono en una nube de almacenamiento mínima tipo Drive/Mediafire, acotada a UNA
 * carpeta fija ($HOME/nube, ver NubeServer.nubeRoot) y accesible desde cualquier
 * navegador, no solo desde la app. Pantalla propia (no una 3ra pestaña de
 * FileManagerFragment) porque necesita controles de servidor/túnel que ese fragment
 * no tiene — mismo criterio que separó TunnelFragment del resto.
 *
 * Reusa dos piezas ya existentes tal cual, sin reinventar nada:
 * - NubeServer: servidor HTTP embebido a mano (ver ese archivo para el detalle del
 *   protocolo, el gateo por token y la validación de path traversal).
 * - TunnelManager: la misma lógica de cloudflared/ngrok que ya usa TunnelFragment,
 *   apuntada al puerto de NubeServer en vez de a un módulo (Ollama/n8n/...).
 *
 * La lista de archivos de abajo usa el mismo mecanismo in/out que FileManagerFragment
 * (comparar currentDir.path contra la raíz para bloquear salir de ella) pero acotado a
 * nubeRoot en vez de $HOME completo / almacenamiento interno — así el usuario tampoco
 * puede navegar fuera de "nube" desde la app nativa, ni ver el resto del teléfono.
 */
class NubeFragment : Fragment() {

    private val nubeRoot: File get() = NubeServer.nubeRoot
    private var currentDir: File = File(NubeServer.nubeRoot.path)

    private lateinit var statusLabel: TextView
    private lateinit var localUrlText: TextView
    private lateinit var publicUrlRow: View
    private lateinit var publicUrlText: TextView
    private lateinit var startBtn: TextView
    private lateinit var stopBtn: TextView
    private lateinit var cloudflareBtn: TextView
    private lateinit var ngrokBtn: TextView
    private lateinit var stopTunnelBtn: TextView
    private lateinit var pathText: TextView
    private lateinit var upButton: TextView
    private lateinit var recycler: RecyclerView
    private lateinit var emptyState: TextView

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreateView(inflater: LayoutInflater, c: ViewGroup?, b: Bundle?): View? =
        inflater.inflate(R.layout.fragment_nube, c, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // NubeServer.nubeRoot es un `by lazy` que crea $HOME/nube si no existía todavía
        // (primer acceso a esta pantalla) — cumple el pedido "crear la carpeta la
        // primera vez que se abre la pantalla".
        currentDir = nubeRoot

        statusLabel = view.findViewById(R.id.nube_status_label)
        localUrlText = view.findViewById(R.id.nube_local_url)
        publicUrlRow = view.findViewById(R.id.nube_public_url_row)
        publicUrlText = view.findViewById(R.id.nube_public_url)
        startBtn = view.findViewById(R.id.btn_nube_start)
        stopBtn = view.findViewById(R.id.btn_nube_stop)
        cloudflareBtn = view.findViewById(R.id.btn_nube_cloudflare)
        ngrokBtn = view.findViewById(R.id.btn_nube_ngrok)
        stopTunnelBtn = view.findViewById(R.id.btn_nube_stop_tunnel)
        pathText = view.findViewById(R.id.nube_path_text)
        upButton = view.findViewById(R.id.btn_nube_up)
        recycler = view.findViewById(R.id.nube_files_recycler)
        emptyState = view.findViewById(R.id.nube_empty_state)
        recycler.layoutManager = LinearLayoutManager(requireContext())

        startBtn.setOnClickListener { startServer() }
        stopBtn.setOnClickListener { stopServer() }
        cloudflareBtn.setOnClickListener { startTunnel("cloudflared") }
        ngrokBtn.setOnClickListener { startTunnel("ngrok") }
        stopTunnelBtn.setOnClickListener { stopTunnel() }
        view.findViewById<TextView>(R.id.btn_nube_copy).setOnClickListener { copyUrl() }
        view.findViewById<TextView>(R.id.btn_nube_share).setOnClickListener { shareUrl() }

        upButton.setOnClickListener {
            val parent = currentDir.parentFile
            if (parent != null && currentDir.path != nubeRoot.path) {
                currentDir = parent
                refreshFiles()
            }
        }

        refreshServerStatus()
        refreshFiles()
    }

    override fun onDestroyView() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroyView()
    }

    // ── Servidor ─────────────────────────────────────────────────────────

    private fun startServer() {
        runInBackground({ NubeServer.start() }) { ok ->
            toast(if (ok) getString(R.string.nube_msg_servidor_iniciado) else getString(R.string.nube_msg_no_se_pudo_iniciar_puerto, NubeServer.port))
            refreshServerStatus()
        }
    }

    private fun stopServer() {
        runInBackground({ NubeServer.stop() }) {
            toast(getString(R.string.nube_msg_servidor_detenido))
            publicUrlRow.visibility = View.GONE
            refreshServerStatus()
        }
    }

    private fun refreshServerStatus() {
        if (!isAdded) return
        val running = NubeServer.isRunning
        statusLabel.text = if (running) getString(R.string.nube_status_activo_puerto, NubeServer.port) else getString(R.string.nube_status_detenido)
        setChipEnabled(startBtn, !running)
        setChipEnabled(stopBtn, running)
        setChipEnabled(cloudflareBtn, running)
        setChipEnabled(ngrokBtn, running)

        if (running) {
            val ip = NubeServer.localIpAddress() ?: "127.0.0.1"
            localUrlText.text = "http://$ip:${NubeServer.port}/?token=${NubeServer.token}"
            pollTunnelStatus()
        } else {
            localUrlText.text = "—"
            publicUrlRow.visibility = View.GONE
        }
    }

    private fun setChipEnabled(chip: TextView, enabled: Boolean) {
        chip.isEnabled = enabled
        chip.alpha = if (enabled) 1f else 0.4f
    }

    // ── Túnel — reusa TunnelManager.start/stop/status tal cual, mismo patrón que
    //    TunnelFragment pero apuntado al puerto de NubeServer en vez de un módulo. ──

    private fun startTunnel(provider: String) {
        toast(getString(R.string.nube_msg_iniciando_tunel, provider))
        val nativeLibDir = requireContext().applicationInfo.nativeLibraryDir
        runInBackground({ TunnelManager.start(NubeServer.port, provider, null, nativeLibDir = nativeLibDir) }) { result ->
            if (result.ok) pollTunnelStatus(attempt = 1) else toast(getString(R.string.nube_error_fmt, result.error))
        }
    }

    private fun stopTunnel() {
        runInBackground({ TunnelManager.stop(NubeServer.port) }) { result ->
            toast(if (result.ok) getString(R.string.nube_msg_tunel_detenido) else getString(R.string.nube_error_fmt, result.error))
            publicUrlRow.visibility = View.GONE
        }
    }

    private fun pollTunnelStatus(attempt: Int = 0) {
        if (!isAdded) return
        if (attempt > 7) {
            // Mismo bug que TunnelFragment.pollStatus (auditoría 2026-08-19, ver
            // docs/arquitectura/AUDITORIA_NUBE_CODIGO_2026-08-19.md): sin este aviso, si el
            // túnel tardaba más de ~14s en imprimir la URL, la fila pública se quedaba oculta
            // para siempre sin ningún indicio de que el poll se había rendido.
            toast(getString(R.string.nube_msg_tunel_sin_url))
            return
        }
        runInBackground({ TunnelManager.status(NubeServer.port) }) { status ->
            if (!isAdded) return@runInBackground
            if (status.running && status.url.isNotEmpty()) {
                publicUrlRow.visibility = View.VISIBLE
                // Mismo patrón de "token en la URL" que OpenClawNative.gatewayUrl() —
                // así el link que se comparte ya funciona solo, sin pasos extra.
                publicUrlText.text = "${status.url}?token=${NubeServer.token}"
            } else if (status.running) {
                handler.postDelayed({ pollTunnelStatus(attempt + 1) }, 2000)
            } else {
                publicUrlRow.visibility = View.GONE
            }
        }
    }

    // ── Compartir / copiar ──────────────────────────────────────────────

    private fun currentShareUrl(): String =
        if (publicUrlRow.visibility == View.VISIBLE) publicUrlText.text.toString() else localUrlText.text.toString()

    private fun copyUrl() {
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("nube_url", currentShareUrl()))
        toast(getString(R.string.nube_msg_url_copiada))
    }

    private fun shareUrl() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, currentShareUrl())
        }
        startActivity(Intent.createChooser(intent, getString(R.string.nube_share_chooser_title)))
    }

    // ── Lista de archivos — mismo patrón in/out que FileManagerFragment.kt, acotado
    //    a nubeRoot en vez de $HOME / almacenamiento interno. ─────────────────────

    // Bug real (auditoría 2026-08-13, ver docs/viejo/AUDITORIA_CODIGO_2026-08-13.md
    // §1.12): listFiles() corría directo en el hilo de UI. Reusa el mismo runInBackground()
    // que ya tiene este archivo (Thread{} + resultado posteado). Se captura currentDir ANTES
    // del Thread — si el usuario navega mientras el listado corre, el resultado se descarta.
    private fun refreshFiles() {
        pathText.text = currentDir.path
        val atRoot = currentDir.path == nubeRoot.path
        upButton.alpha = if (atRoot) 0.3f else 1f
        upButton.isEnabled = !atRoot

        val dir = currentDir
        runInBackground({
            dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase(Locale.getDefault()) }))
        }) { entries ->
            if (currentDir.path != dir.path) return@runInBackground
            if (entries == null) {
                showEmpty(getString(R.string.nube_msg_no_se_pudo_leer_carpeta))
                return@runInBackground
            }
            if (entries.isEmpty()) {
                showEmpty(getString(R.string.nube_msg_carpeta_vacia))
                return@runInBackground
            }
            emptyState.visibility = View.GONE
            recycler.visibility = View.VISIBLE
            recycler.adapter = NubeFileAdapter(
                entries,
                onClick = { file ->
                    if (file.isDirectory) {
                        currentDir = file
                        refreshFiles()
                    } else {
                        toast(getString(R.string.nube_file_click_info, file.name, ManagerNativeUtils.humanSize(file.length())))
                    }
                },
                onLongClick = { file -> showFileMenu(file) }
            )
        }
    }

    private fun showFileMenu(file: File) {
        val options = arrayOf(getString(R.string.nube_file_menu_renombrar), getString(R.string.nube_file_menu_eliminar))
        AlertDialog.Builder(requireContext())
            .setTitle(file.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showRenameDialog(file)
                    1 -> showDeleteConfirm(file)
                }
            }
            .show()
    }

    private fun showRenameDialog(file: File) {
        val edit = EditText(requireContext()).apply { setText(file.name) }
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.nube_dialog_title_renombrar))
            .setView(edit)
            .setPositiveButton(getString(R.string.nube_btn_renombrar)) { _, _ ->
                val newName = edit.text.toString().trim()
                if (newName.isEmpty()) { toast(getString(R.string.nube_msg_nombre_vacio)); return@setPositiveButton }
                val dest = File(file.parentFile, newName)
                if (dest.exists()) { toast(getString(R.string.nube_msg_ya_existe, newName)); return@setPositiveButton }
                if (file.renameTo(dest)) refreshFiles() else toast(getString(R.string.nube_msg_no_se_pudo_renombrar))
            }
            .setNegativeButton(getString(R.string.nube_btn_cancelar), null)
            .show()
    }

    private fun showDeleteConfirm(file: File) {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.nube_dialog_title_eliminar))
            .setMessage(getString(R.string.nube_dialog_msg_eliminar, file.name))
            .setPositiveButton(getString(R.string.nube_btn_eliminar)) { _, _ ->
                val ok = if (file.isDirectory) file.deleteRecursively() else file.delete()
                if (ok) refreshFiles() else toast(getString(R.string.nube_msg_no_se_pudo_eliminar))
            }
            .setNegativeButton(getString(R.string.nube_btn_cancelar), null)
            .show()
    }

    private fun showEmpty(message: String) {
        emptyState.text = message
        emptyState.visibility = View.VISIBLE
        recycler.visibility = View.GONE
        recycler.adapter = null
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun <T> runInBackground(work: () -> T, onResult: (T) -> Unit) {
        Thread {
            val result = work()
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread { if (isAdded) onResult(result) }
        }.start()
    }

    private fun toast(msg: String) {
        if (isAdded) Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
    }

    private inner class NubeFileAdapter(
        private val files: List<File>,
        private val onClick: (File) -> Unit,
        private val onLongClick: (File) -> Unit
    ) : RecyclerView.Adapter<NubeFileAdapter.VH>() {

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
            holder.meta.text = if (f.isDirectory) getString(R.string.nube_file_meta_carpeta) else getString(R.string.nube_file_meta_size_date, ManagerNativeUtils.humanSize(f.length()), dateFmt.format(Date(f.lastModified())))
            holder.card.setOnClickListener { onClick(f) }
            holder.card.setOnLongClickListener { onLongClick(f); true }
        }

        override fun getItemCount() = files.size
    }
}

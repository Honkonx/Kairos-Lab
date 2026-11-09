package com.termux.app.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.termux.R
import com.termux.app.util.ManagerNativeUtils
import com.termux.app.util.NubeServer
import com.termux.app.util.ProgressDialogController
import com.termux.app.util.TunnelManager
import com.termux.app.util.kairosThemeColor
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.webkit.MimeTypeMap

/**
 * Pantalla "Nube" — pedido explícito del usuario: convertir el
 * teléfono en una nube de almacenamiento mínima tipo Drive/Mediafire, acotada a UNA
 * carpeta fija ($HOME/nube, ver NubeServer.nubeRoot) y accesible desde cualquier
 * navegador, no solo desde la app. Pantalla propia (no una 3ra pestaña de
 * FileManagerFragment) porque necesita controles de servidor/túnel que ese fragment
 * no tiene — mismo criterio que separó TunnelFragment del resto.
 *
 * Rediseño 2026-09-16 (ver de esta ronda: "nube sigue igual de horrible,
 * entiende algo, analiza los html que estan en ver/nube [...] adapta la interfaz de esos
 * html"): la organización visual (header con icono de marca, tarjeta de almacenamiento con
 * barra de uso real, tarjeta de servidor/túnel, buscador + chips de categoría, grilla de
 * tarjetas de archivo) se adaptó 1:1 de ver/nube/nube_personal_web.html y
 * nube_personal_interfaz_web.html (mockups HTML+Tailwind del propio usuario). Toda la lógica
 * real (servidor, túnel, cifrado, notificaciones) sigue viviendo intacta en NubeServer/
 * TunnelManager — este archivo solo cambió cómo se presenta.
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

    private val addFilesLauncher = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty() && isAdded) importFilesIntoCurrentFolder(uris)
    }

    private val nubeRoot: File get() = NubeServer.nubeRoot
    private var currentDir: File = File(NubeServer.nubeRoot.path)

    private lateinit var statusPill: View
    private lateinit var statusDot: View
    private lateinit var statusLabel: TextView
    private lateinit var localUrlText: TextView
    private lateinit var publicUrlRow: View
    private lateinit var publicUrlText: TextView
    private lateinit var startBtn: TextView
    private lateinit var stopBtn: TextView
    private lateinit var cloudflareBtn: TextView
    private lateinit var ngrokBtn: TextView
    private lateinit var stopTunnelBtn: TextView
    private lateinit var openBrowserBtn: TextView
    private lateinit var pathText: TextView
    private lateinit var upButton: TextView
    private lateinit var recycler: RecyclerView
    private lateinit var emptyState: TextView
    private lateinit var searchInput: EditText

    private lateinit var diskUsageValue: TextView
    private lateinit var diskFreeLabel: TextView
    private lateinit var diskSegments: ViewGroup
    private lateinit var legendImagenes: TextView
    private lateinit var legendArchivos: TextView
    private lateinit var legendOtros: TextView

    private lateinit var filterTodos: TextView
    private lateinit var filterImagenes: TextView
    private lateinit var filterDocumentos: TextView
    private lateinit var filterCifrados: TextView
    private lateinit var filterChips: List<TextView>

    private val handler = Handler(Looper.getMainLooper())
    private var dotPulseAnimator: ValueAnimator? = null

    // Lista sin filtrar de la carpeta actual — el buscador/chips de categoría filtran esta
    // copia en memoria en vez de volver a leer disco por cada tecla (refreshFiles() sigue
    // siendo la única función que efectivamente lee el filesystem).
    private var allEntries: List<File> = emptyList()
    private var activeFilter = NubeFilter.TODOS

    private enum class NubeFilter { TODOS, IMAGENES, DOCUMENTOS, CIFRADOS }

    private val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "svg")

    override fun onCreateView(inflater: LayoutInflater, c: ViewGroup?, b: Bundle?): View? =
        inflater.inflate(R.layout.fragment_nube, c, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Anti-tapjacking (auditoría referencia/ia/*, 2026-08-31): esta pantalla muestra el
        // token de acceso de NubeServer en la URL — ver .claude/rules/kairos-secrets-never-revealed.md.
        view.filterTouchesWhenObscured = true
        // NubeServer.nubeRoot es un `by lazy` que crea $HOME/nube si no existía todavía
        // (primer acceso a esta pantalla) — cumple el pedido "crear la carpeta la
        // primera vez que se abre la pantalla".
        currentDir = nubeRoot

        statusPill = view.findViewById(R.id.nube_status_pill)
        statusDot = view.findViewById(R.id.nube_status_dot)
        statusLabel = view.findViewById(R.id.nube_status_label)
        localUrlText = view.findViewById(R.id.nube_local_url)
        publicUrlRow = view.findViewById(R.id.nube_public_url_row)
        publicUrlText = view.findViewById(R.id.nube_public_url)
        startBtn = view.findViewById(R.id.btn_nube_start)
        stopBtn = view.findViewById(R.id.btn_nube_stop)
        cloudflareBtn = view.findViewById(R.id.btn_nube_cloudflare)
        ngrokBtn = view.findViewById(R.id.btn_nube_ngrok)
        stopTunnelBtn = view.findViewById(R.id.btn_nube_stop_tunnel)
        openBrowserBtn = view.findViewById(R.id.btn_nube_open_browser)
        pathText = view.findViewById(R.id.nube_path_text)
        upButton = view.findViewById(R.id.btn_nube_up)
        recycler = view.findViewById(R.id.nube_files_recycler)
        emptyState = view.findViewById(R.id.nube_empty_state)
        searchInput = view.findViewById(R.id.nube_search_input)

        diskUsageValue = view.findViewById(R.id.nube_disk_usage_value)
        diskFreeLabel = view.findViewById(R.id.nube_disk_free_label)
        diskSegments = view.findViewById(R.id.nube_disk_usage_segments)
        legendImagenes = view.findViewById(R.id.nube_legend_imagenes)
        legendArchivos = view.findViewById(R.id.nube_legend_archivos)
        legendOtros = view.findViewById(R.id.nube_legend_otros)

        filterTodos = view.findViewById(R.id.nube_filter_todos)
        filterImagenes = view.findViewById(R.id.nube_filter_imagenes)
        filterDocumentos = view.findViewById(R.id.nube_filter_documentos)
        filterCifrados = view.findViewById(R.id.nube_filter_cifrados)
        filterChips = listOf(filterTodos, filterImagenes, filterDocumentos, filterCifrados)

        view.findViewById<TextView>(R.id.btn_nube_add_files).setOnClickListener {
            addFilesLauncher.launch(arrayOf("*/*"))
        }
        view.findViewById<TextView>(R.id.btn_nube_new_folder).setOnClickListener { showCreateFolderDialog() }

        recycler.layoutManager = GridLayoutManager(requireContext(), 2)

        startBtn.setOnClickListener { startServer() }
        stopBtn.setOnClickListener { stopServer() }
        cloudflareBtn.setOnClickListener { startTunnel("cloudflared") }
        ngrokBtn.setOnClickListener { startTunnel("ngrok") }
        stopTunnelBtn.setOnClickListener { stopTunnel() }
        openBrowserBtn.setOnClickListener { openInBrowser() }
        view.findViewById<TextView>(R.id.btn_nube_copy).setOnClickListener { copyUrl() }
        view.findViewById<TextView>(R.id.btn_nube_share).setOnClickListener { shareUrl() }

        filterTodos.setOnClickListener { selectFilter(NubeFilter.TODOS) }
        filterImagenes.setOnClickListener { selectFilter(NubeFilter.IMAGENES) }
        filterDocumentos.setOnClickListener { selectFilter(NubeFilter.DOCUMENTOS) }
        filterCifrados.setOnClickListener { selectFilter(NubeFilter.CIFRADOS) }

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { applyFilter() }
        })

        upButton.setOnClickListener {
            val parent = currentDir.parentFile
            if (parent != null && currentDir.path != nubeRoot.path) {
                currentDir = parent
                refreshFiles()
            }
        }

        refreshServerStatus()
        refreshFiles()
        refreshDiskUsage()
    }

    override fun onDestroyView() {
        handler.removeCallbacksAndMessages(null)
        dotPulseAnimator?.cancel()
        dotPulseAnimator = null
        super.onDestroyView()
    }

    // ── Servidor ─────────────────────────────────────────────────────────

    private fun startServer() {
        // Se pasa applicationContext (no requireContext() — el server vive más allá de esta
        // vista) para que NubeServer pueda notificar subidas recibidas mientras la app está en
        // segundo plano (ver comentario de NubeServer.appContext).
        val appContext = requireContext().applicationContext
        runInBackground({ NubeServer.start(context = appContext) }) { ok ->
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
        statusPill.setBackgroundResource(if (running) R.drawable.bg_nube_status_on else R.drawable.bg_nube_status_off)
        statusDot.setBackgroundResource(if (running) R.drawable.circle_dot_green else R.drawable.circle_dot_gray)
        setChipEnabled(startBtn, !running)
        setChipEnabled(stopBtn, running)
        setChipEnabled(cloudflareBtn, running)
        setChipEnabled(ngrokBtn, running)
        setChipEnabled(openBrowserBtn, running)
        updateDotPulse(running)

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

    /** Punto "●" animado del pill de estado — equivalente del `animate-ping` CSS del mockup
     * (ver bg_nube_status_on.xml), resuelto acá porque un shape XML no puede animarse solo. */
    private fun updateDotPulse(running: Boolean) {
        dotPulseAnimator?.cancel()
        dotPulseAnimator = null
        if (!running) {
            statusDot.alpha = 1f
            return
        }
        dotPulseAnimator = ObjectAnimator.ofFloat(statusDot, View.ALPHA, 1f, 0.35f, 1f).apply {
            duration = 1600
            repeatCount = ValueAnimator.INFINITE
            start()
        }
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

    // ── Compartir / copiar / abrir ──────────────────────────────────────

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

    /** Botón principal "Abrir en el navegador" del mockup — la subida real de archivos
     * (con contraseña/zip opcional) vive en la página que sirve NubeServer, no hay upload
     * nativo en esta pantalla (ver comentario del layout) — este botón es el camino real
     * para llegar a ella sin copiar/pegar la URL a mano. */
    private fun openInBrowser() {
        if (!NubeServer.isRunning) { toast(getString(R.string.nube_msg_iniciar_servidor_primero)); return }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(currentShareUrl())))
        } catch (_: Exception) {
            toast(getString(R.string.nube_msg_no_hay_navegador))
        }
    }

    private fun showCreateFolderDialog() {
        val input = EditText(requireContext()).apply {
            hint = getString(R.string.nube_folder_name_hint)
            setSingleLine(true)
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.nube_new_folder_title)
            .setView(input)
            .setPositiveButton(R.string.nube_new_folder_create) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty() || name == "." || name == ".." || name.contains('/') || name.contains('\\')) {
                    toast(getString(R.string.nube_folder_invalid_name))
                    return@setPositiveButton
                }
                val parent = currentDir.canonicalFile
                if (!isInsideNubeRoot(parent)) {
                    currentDir = nubeRoot
                    refreshFiles()
                    return@setPositiveButton
                }
                val folder = File(parent, name)
                if (folder.exists()) toast(getString(R.string.nube_folder_already_exists, name))
                else if (folder.mkdir()) {
                    refreshFiles()
                    refreshDiskUsage()
                    toast(getString(R.string.nube_folder_created, name))
                } else toast(getString(R.string.nube_folder_create_failed))
            }
            .setNegativeButton(R.string.nube_btn_cancelar, null)
            .show()
    }

    private data class ImportSummary(val copied: Int, val failed: Int)

    /** Copia seleccionada vía SAF a la carpeta de nube abierta, sin reemplazar archivos previos. */
    private fun importFilesIntoCurrentFolder(uris: List<Uri>) {
        val destination = currentDir.canonicalFile
        val appContext = requireContext().applicationContext
        if (!isInsideNubeRoot(destination)) {
            currentDir = nubeRoot
            refreshFiles()
            toast(getString(R.string.nube_folder_outside_root))
            return
        }
        val progress = ProgressDialogController(requireContext())
        progress.show(getString(R.string.nube_import_title), getString(R.string.nube_import_started), allowBackground = true)
        runInBackground({
            var copied = 0
            var failed = 0
            for (uri in uris) {
                try {
                    val name = queryDisplayName(appContext, uri)
                    val target = uniqueImportTarget(destination, name)
                    val input = appContext.contentResolver.openInputStream(uri)
                        ?: throw java.io.IOException("No se pudo abrir el archivo")
                    input.use { source -> target.outputStream().buffered().use { source.copyTo(it) } }
                    copied++
                } catch (_: Exception) {
                    failed++
                }
            }
            ImportSummary(copied, failed)
        }) { result ->
            refreshFiles()
            refreshDiskUsage()
            val summary = getString(R.string.nube_import_result, result.copied, result.failed)
            if (progress.isBackgrounded) {
                com.termux.app.util.ModuleEventBridge.notifyDirect(appContext, "nube", "files_imported", summary)
            }
            if (result.failed == 0) progress.success(summary) else progress.failure(summary)
        }
    }

    private fun queryDisplayName(context: Context, uri: Uri): String {
        val name = try {
            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
        } catch (_: Exception) { null }
        return File(name.orEmpty().ifBlank { uri.lastPathSegment.orEmpty() }).name
            .takeIf { it.isNotBlank() && it != "." && it != ".." }
            ?: "archivo-${System.currentTimeMillis()}"
    }

    private fun uniqueImportTarget(parent: File, rawName: String): File {
        val name = File(rawName).name.ifBlank { "archivo" }
        var candidate = File(parent, name)
        if (!candidate.exists()) return candidate
        val extension = name.substringAfterLast('.', "").takeIf { it.isNotEmpty() }
        val stem = if (extension == null) name else name.removeSuffix(".$extension")
        var suffix = 2
        do {
            candidate = File(parent, if (extension == null) "$stem ($suffix)" else "$stem ($suffix).$extension")
            suffix++
        } while (candidate.exists())
        return candidate
    }

    private fun isInsideNubeRoot(file: File): Boolean {
        val root = nubeRoot.canonicalFile.path
        val path = file.canonicalFile.path
        return path == root || path.startsWith(root + File.separator)
    }

    // ── Almacenamiento — barra de uso real de disco + desglose por categoría, adaptado
    //    del "Storage Details Card" del hero de nube_personal_web.html. ─────────────────

    private fun refreshDiskUsage() {
        val root = nubeRoot
        runInBackground({
            val total = root.totalSpace
            val free = root.usableSpace
            val used = (total - free).coerceAtLeast(0)
            val imagenesBytes = folderBytes(File(root, "imagenes"))
            val archivosBytes = folderBytes(File(root, "archivos"))
            val otrosBytes = (used - imagenesBytes - archivosBytes).coerceAtLeast(0)
            DiskUsageSnapshot(total, free, used, imagenesBytes, archivosBytes, otrosBytes)
        }) { snapshot -> renderDiskUsage(snapshot) }
    }

    private fun folderBytes(dir: File): Long {
        if (!dir.isDirectory) return 0L
        return try {
            dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        } catch (_: Exception) { 0L }
    }

    private data class DiskUsageSnapshot(
        val total: Long, val free: Long, val used: Long,
        val imagenes: Long, val archivos: Long, val otros: Long
    )

    private fun renderDiskUsage(s: DiskUsageSnapshot) {
        if (!isAdded || s.total <= 0) return
        diskUsageValue.text = getString(R.string.nube_disk_usage_value_fmt, ManagerNativeUtils.humanSize(s.used), ManagerNativeUtils.humanSize(s.total))
        diskFreeLabel.text = getString(R.string.nube_disk_free_fmt, ManagerNativeUtils.humanSize(s.free))
        legendImagenes.text = getString(R.string.nube_legend_fmt, getString(R.string.nube_legend_imagenes_label), ManagerNativeUtils.humanSize(s.imagenes))
        legendArchivos.text = getString(R.string.nube_legend_fmt, getString(R.string.nube_legend_archivos_label), ManagerNativeUtils.humanSize(s.archivos))
        legendOtros.text = getString(R.string.nube_legend_fmt, getString(R.string.nube_legend_otros_label), ManagerNativeUtils.humanSize(s.otros))

        diskSegments.removeAllViews()
        val ctx = requireContext()
        val totalF = s.total.toFloat()
        addUsageSegment(ctx, s.imagenes / totalF, ctx.kairosThemeColor(R.attr.kairosBlue))
        addUsageSegment(ctx, s.archivos / totalF, ctx.kairosThemeColor(R.attr.kairosPurple))
        addUsageSegment(ctx, s.otros / totalF, ctx.kairosThemeColor(R.attr.kairosAmber))
        // Relleno transparente que ocupa el resto de la barra (espacio libre) — mismo efecto
        // visual que las "slivers" finas del mockup sobre una barra de fondo neutro.
        addUsageSegment(ctx, s.free.coerceAtLeast(0) / totalF, android.graphics.Color.TRANSPARENT)
    }

    /** Agrega un tramo de color a la barra de uso, proporcional a `weight` (0..1 del total del
     * disco) — LinearLayout.LayoutParams.weight hace el reparto, sin cálculo de ancho a mano. */
    private fun addUsageSegment(ctx: Context, weight: Float, color: Int) {
        if (weight <= 0f) return
        val v = View(ctx)
        v.setBackgroundColor(color)
        val params = android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight)
        diskSegments.addView(v, params)
    }

    // ── Lista de archivos — mismo patrón in/out que FileManagerFragment.kt, acotado
    //    a nubeRoot en vez de $HOME / almacenamiento interno. ─────────────────────

    // Bug real (auditoría 2026-08-13, ver docs/viejo/AUDITORIA_CODIGO_2026-08-13.md
    // §1.12): listFiles() corría directo en el hilo de UI. Reusa el mismo runInBackground()
    // que ya tiene este archivo (Thread{} + resultado posteado). Se captura currentDir ANTES
    // del Thread — si el usuario navega mientras el listado corre, el resultado se descarta.
    private fun refreshFiles() {
        if (!isInsideNubeRoot(currentDir)) currentDir = nubeRoot
        pathText.text = currentDir.path
        val atRoot = currentDir.path == nubeRoot.path
        upButton.alpha = if (atRoot) 0.3f else 1f
        upButton.isEnabled = !atRoot

        val dir = currentDir
        runInBackground({
            dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase(Locale.getDefault()) }))
        }) { entries ->
            if (currentDir.path != dir.path) return@runInBackground
            allEntries = entries ?: emptyList()
            applyFilter()
        }
    }

    /** Filtra `allEntries` por chip de categoría + texto del buscador — sin volver a leer
     * disco, adaptando el buscador/chips del mockup (`filterFiles()` en el HTML original)
     * a esta lista ya cargada en memoria. */
    private fun applyFilter() {
        if (!isAdded) return
        val query = searchInput.text?.toString()?.trim()?.lowercase(Locale.getDefault()).orEmpty()
        val visible = allEntries.filter { file ->
            val matchesQuery = query.isEmpty() || file.name.lowercase(Locale.getDefault()).contains(query)
            val matchesFilter = when (activeFilter) {
                NubeFilter.TODOS -> true
                NubeFilter.IMAGENES -> file.isDirectory || isImageFile(file)
                NubeFilter.DOCUMENTOS -> file.isDirectory || (!isImageFile(file) && !isEncryptedFile(file))
                NubeFilter.CIFRADOS -> file.isDirectory || isEncryptedFile(file)
            }
            matchesQuery && matchesFilter
        }

        if (allEntries.isEmpty()) {
            showEmpty(getString(R.string.nube_msg_carpeta_vacia))
            return
        }
        if (visible.isEmpty()) {
            showEmpty(getString(R.string.nube_msg_filtro_sin_resultados))
            return
        }
        emptyState.visibility = View.GONE
        recycler.visibility = View.VISIBLE
        recycler.adapter = NubeFileAdapter(
            visible,
            onClick = { file ->
                if (file.isDirectory) {
                    if (isInsideNubeRoot(file)) {
                        currentDir = file
                        refreshFiles()
                    } else {
                        toast(getString(R.string.nube_folder_outside_root))
                    }
                } else {
                    openCloudFile(file)
                }
            },
            onLongClick = { file -> showFileMenu(file) },
            onDelete = { file -> showDeleteConfirm(file) }
        )
    }

    private fun selectFilter(filter: NubeFilter) {
        activeFilter = filter
        val selectedChip = chipFor(filter)
        filterChips.forEach { chip ->
            val selected = chip === selectedChip
            chip.background = androidx.core.content.ContextCompat.getDrawable(
                requireContext(),
                if (selected) R.drawable.bg_nube_chip_selected else R.drawable.bg_nube_chip_unselected
            )
            chip.setTextColor(
                if (selected) android.graphics.Color.WHITE else requireContext().kairosThemeColor(R.attr.kairosText2)
            )
        }
        applyFilter()
    }

    private fun chipFor(filter: NubeFilter): TextView = when (filter) {
        NubeFilter.TODOS -> filterTodos
        NubeFilter.IMAGENES -> filterImagenes
        NubeFilter.DOCUMENTOS -> filterDocumentos
        NubeFilter.CIFRADOS -> filterCifrados
    }

    private fun isImageFile(f: File): Boolean =
        !f.isDirectory && baseExtension(f).lowercase(Locale.getDefault()) in imageExtensions

    private fun isEncryptedFile(f: File): Boolean = !f.isDirectory && f.name.endsWith(".kenc", ignoreCase = true)

    /** Extensión "real" de un archivo, ignorando el sufijo `.kenc` que agrega NubeServer a
     * las subidas cifradas (ver NubeServer.kt destFile) — así `foto.png.kenc` sigue
     * mostrándose como imagen con el badge de cifrado aparte, en vez de como un ".kenc" opaco. */
    private fun baseExtension(f: File): String {
        val name = if (isEncryptedFile(f)) f.name.removeSuffix(".kenc").removeSuffix(".KENC") else f.name
        return name.substringAfterLast('.', "")
    }

    private fun showFileMenu(file: File) {
        val options = arrayOf(
            getString(R.string.nube_file_menu_open),
            getString(R.string.nube_file_menu_share),
            getString(R.string.nube_file_menu_renombrar),
            getString(R.string.nube_file_menu_eliminar)
        )
        AlertDialog.Builder(requireContext())
            .setTitle(file.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> openCloudFile(file)
                    1 -> shareCloudFile(file)
                    2 -> showRenameDialog(file)
                    3 -> showDeleteConfirm(file)
                }
            }
            .show()
    }

    private fun openCloudFile(file: File) {
        try {
            val uri = FileProvider.getUriForFile(requireContext(), "${requireContext().packageName}.filemanager", file)
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase(Locale.getDefault())) ?: "*/*"
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.nube_file_menu_open)))
        } catch (_: Exception) {
            toast(getString(R.string.nube_file_click_info, file.name, ManagerNativeUtils.humanSize(file.length())))
        }
    }

    private fun shareCloudFile(file: File) {
        try {
            val uri = FileProvider.getUriForFile(requireContext(), "${requireContext().packageName}.filemanager", file)
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase(Locale.getDefault())) ?: "*/*"
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newUri(requireContext().contentResolver, file.name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.nube_file_menu_share)))
        } catch (_: Exception) {
            toast(getString(R.string.nube_file_click_info, file.name, ManagerNativeUtils.humanSize(file.length())))
        }
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
                if (ok) { refreshFiles(); refreshDiskUsage() } else toast(getString(R.string.nube_msg_no_se_pudo_eliminar))
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

    /** Tarjeta de archivo del grid (item_nube_file_card.xml) — reemplaza la fila de lista
     * plana que usaba item_file_row.xml (compartido con FileManagerFragment, sin tocar). */
    private inner class NubeFileAdapter(
        private val files: List<File>,
        private val onClick: (File) -> Unit,
        private val onLongClick: (File) -> Unit,
        private val onDelete: (File) -> Unit
    ) : RecyclerView.Adapter<NubeFileAdapter.VH>() {

        private val dateFmt = SimpleDateFormat("dd/MM/yy HH:mm", Locale.getDefault())

        inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val card: View = itemView.findViewById(R.id.nube_file_card)
            val icon: TextView = itemView.findViewById(R.id.nube_card_icon)
            val badge: TextView = itemView.findViewById(R.id.nube_card_badge)
            val name: TextView = itemView.findViewById(R.id.nube_card_name)
            val meta: TextView = itemView.findViewById(R.id.nube_card_meta)
            val ext: TextView = itemView.findViewById(R.id.nube_card_ext)
            val menuBtn: TextView = itemView.findViewById(R.id.nube_card_menu_btn)
            val deleteBtn: TextView = itemView.findViewById(R.id.nube_card_delete_btn)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_nube_file_card, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val f = files[position]
            val encrypted = isEncryptedFile(f)
            val ext = if (f.isDirectory) "" else baseExtension(f).uppercase(Locale.getDefault())

            holder.name.text = f.name
            holder.meta.text = if (f.isDirectory) getString(R.string.nube_file_meta_carpeta) else getString(R.string.nube_file_meta_size_date, ManagerNativeUtils.humanSize(f.length()), dateFmt.format(Date(f.lastModified())))
            holder.ext.text = if (f.isDirectory) getString(R.string.nube_file_meta_carpeta) else ext.ifEmpty { "—" }

            holder.icon.text = when {
                f.isDirectory -> "📁"
                isImageFile(f) -> "🖼"
                ext == "ZIP" || ext == "RAR" || ext == "7Z" -> "🗜"
                else -> "📄"
            }
            holder.icon.setTextColor(
                requireContext().kairosThemeColor(
                    when {
                        f.isDirectory -> R.attr.kairosText
                        isImageFile(f) -> R.attr.kairosBlue
                        ext == "ZIP" || ext == "RAR" || ext == "7Z" -> R.attr.kairosAmber
                        else -> R.attr.kairosPurple
                    }
                )
            )

            if (encrypted) {
                holder.badge.visibility = View.VISIBLE
                holder.badge.text = getString(R.string.nube_badge_cifrado)
            } else {
                holder.badge.visibility = View.GONE
            }

            holder.card.setOnClickListener { onClick(f) }
            holder.card.setOnLongClickListener { onLongClick(f); true }
            holder.menuBtn.setOnClickListener { onLongClick(f) }
            holder.deleteBtn.setOnClickListener { onDelete(f) }
        }

        override fun getItemCount() = files.size
    }
}

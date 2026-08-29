package com.termux.app.ui

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcelable
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.snackbar.Snackbar
import com.termux.R
import com.termux.app.ModuleController
import com.termux.app.TermuxActivity
import com.termux.app.data.ModuleCatalog
import com.termux.app.data.ModuleInstalled
import com.termux.app.data.ModuleRegistry
import com.termux.app.model.ModuleInfo
import com.termux.app.model.ModuleInfo.Status
import com.termux.app.util.ProjectsManager
import com.termux.app.util.applyTermuxEnv
import com.termux.app.util.friendlyProcessErrorMessage
import com.termux.shared.module.ModuleManager
import com.termux.shared.termux.TermuxConstants
import java.io.BufferedReader
import java.io.FileReader

class ModulesFragment : Fragment(), ModuleManager.ModuleListener {

    private lateinit var recyclerView: RecyclerView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var adapter: ModuleListAdapter
    private lateinit var searchInput: EditText
    private lateinit var statsInstalled: TextView
    private lateinit var statsActive: TextView
    private lateinit var statsRam: TextView
    private lateinit var statsRefresh: TextView
    private lateinit var sectionLabel: TextView
    private lateinit var emptyState: View
    private lateinit var emptyGoPlugins: View

    private val moduleDefinitions = mutableListOf<ModuleInfo>()
    private val handler = Handler(Looper.getMainLooper())
    private val pollRunnable = object : Runnable {
        override fun run() {
            pollStatus()
            handler.removeCallbacks(this)
            handler.postDelayed(this, 5000)
        }
    }

    private var moduleManager: ModuleManager? = null
    private var registry: ModuleRegistry? = null

    // 2026-08-10 (rediseño de pantallas): la pantalla Módulos muestra SOLO lo instalado.
    // pollStatus() calcula el subconjunto visible y lo submitListea al adapter solo cuando
    // cambia (evita reinvocar DiffUtil + rebind de todas las filas en cada poll de 5s).
    private var lastVisibleIds: Set<String> = emptySet()

    // Buscador (2026-08-26) — filtra el subconjunto visible (lastVisibleIds) por
    // nombre/id/descripción/categoría, mismo patrón que PluginsFragment.query/applyFilter().
    private var query: String = ""

    // Causa raíz del "bug de navegación" (ver MEJORAS_PENDIENTES.md): ModuleDetailNavigator
    // navega al detalle de un módulo con replace()+addToBackStack — al volver atrás, esta
    // vista (RecyclerView incluido) se DESTRUYE y se RECREA de cero (onDestroyView +
    // onViewCreated), perdiendo el scroll y el estado del adapter. moduleDefinitions/
    // lastVisibleIds ya sobrevivían eso (son campos del Fragment, no de la vista), pero
    // loadModuleDefinitions() igual vaciaba el adapter y esperaba a que pollStatus() (spawnea
    // un proceso tmux/pgrep POR módulo, nada instantáneo) lo repoblara — de ahí el "recarga
    // por completo... lento" reportado. lastStatuses/lastVersions cachean el último resultado
    // conocido de pollStatus() para repoblar el adapter nuevo SINCRÓNICAMENTE (sin esperar al
    // poll) apenas se recrea la vista; savedLayoutState guarda/restaura la posición de scroll
    // del LayoutManager (que también es una instancia nueva en cada recreación).
    private var lastStatuses: Map<String, Status> = emptyMap()
    private var lastVersions: Map<String, String> = emptyMap()
    private var savedLayoutState: Parcelable? = null

    // Ciclo anterior de pollStatus() — permite detectar transiciones RUNNING ->
    // INSTALLED_STOPPED (un módulo que se cae/se detiene solo) para las
    // notificaciones de "módulos caídos" (Ajustes). null en el primer ciclo,
    // a propósito, para no disparar falsos positivos comparando contra nada.
    private var previousStatuses: Map<String, Status>? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_modules, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        statsInstalled = view.findViewById(R.id.stats_installed)
        statsActive = view.findViewById(R.id.stats_active)
        statsRam = view.findViewById(R.id.stats_ram)
        statsRefresh = view.findViewById(R.id.stats_refresh)
        statsRefresh.setOnClickListener { onRefreshClick() }
        sectionLabel = view.findViewById(R.id.modules_section_label)
        emptyState = view.findViewById(R.id.modules_empty)
        emptyGoPlugins = view.findViewById(R.id.modules_empty_go_plugins)
        emptyGoPlugins.setOnClickListener {
            // La Tienda de plugins vive en TermuxActivity (switchFragment), no hay una
            // API pública de navegación entre fragments — se expone openPlugins().
            (activity as? TermuxActivity)?.openPlugins()
        }

        // "Voz → Agente" (ver VoiceToAgentDialog): dicta un pedido y lo manda como prompt
        // inicial a cualquiera de los CLIs de IA que Kairos ya soporta. childFragmentManager
        // (no parentFragmentManager) porque el diálogo es propio de este Fragment, no de la
        // navegación de pantallas completas de TermuxActivity.
        view.findViewById<View>(R.id.btn_voice_to_agent).setOnClickListener {
            VoiceToAgentDialog().show(childFragmentManager, "voice_to_agent")
        }

        // Bug real (2026-08-06, ver docs/humano/humano77.md): fragment_modules.xml
        // define este SwipeRefreshLayout pero nunca se conectaba desde Kotlin — el
        // widget nativo arranca su animación al deslizar hacia abajo sin necesitar
        // ningún listener (es comportamiento propio de Android), pero sin
        // setOnRefreshListener()/setRefreshing(false) en ningún lado, el círculo
        // blanco quedaba pegado para siempre. Mismo alcance que la opción "R" del
        // menú TUI de termux-ai-stack-dev (menu.sh): solo relee estado (pollStatus,
        // registry + tmux en vivo), sin red ni git pull — eso es el botón "↻
        // Actualizar" aparte (onRefreshClick(), hace git pull).
        swipeRefresh = view.findViewById(R.id.swipe_refresh)
        swipeRefresh.setOnRefreshListener { pollStatus() }

        searchInput = view.findViewById(R.id.modules_search)
        searchInput.setText(query)
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString()?.trim().orEmpty()
                submitVisible()
            }
        })

        recyclerView = view.findViewById(R.id.modules_recycler)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        // Bug #27 real confirmado en dispositivo 3 veces (ver docs/humano/humano189.md/humano190.md,
        // "tocar la fila N abre la fila N-1"): el DiffCallback del adapter ya compara por ID
        // estable (no por posición), así que no era un bug de identidad de datos — el
        // candidato real era el ItemAnimator por defecto de RecyclerView. Con el poll de
        // estado cada 5s (pollStatus() más abajo) llamando a submitList() periódicamente, cada
        // actualización dispara animaciones de cambio/movimiento de fila; si el usuario toca
        // justo mientras una fila está animando su posición en pantalla, el MotionEvent puede
        // despacharse contra la vista que está animando HACIA esa posición en vez de la que el
        // dedo tocó realmente — patrón documentado de RecyclerView+ListAdapter con actualizaciones
        // periódicas. Sin animator, submitList() aplica el diff al instante, sin ventana de
        // desfase entre posición visual y posición real durante el toque.
        recyclerView.itemAnimator = null
        // Restaura la posición de scroll guardada en onDestroyView (si la vista se está
        // recreando al volver de un submódulo) — LinearLayoutManager aplica un
        // onRestoreInstanceState() pendiente en su próximo layout pass, sin importar que el
        // adapter todavía no tenga items en este punto.
        savedLayoutState?.let { recyclerView.layoutManager?.onRestoreInstanceState(it) }

        adapter = ModuleListAdapter(
            onItemClick = { module -> onModuleClick(module) },
            onToggle = { module, start -> onModuleToggle(module, start) }
        )
        recyclerView.adapter = adapter

        moduleManager = ModuleManager.getInstance(requireContext())
        moduleManager?.addListener(this)

        registry = ModuleRegistry(requireContext())

        loadModuleDefinitions()
        pollStatus()
    }

    override fun onResume() {
        super.onResume()
        // Bug real confirmado en dispositivo (2026-08-27, ver docs/humano264.md — "pantalla
        // principal dice 3 instalados" tras instalar 50 módulos por script fuera de la app):
        // esto SOLO agendaba pollRunnable con postDelayed(5000) — el primer re-chequeo real al
        // volver a esta pantalla (ej. la Activity vuelve de background tras pausarse con la
        // pantalla apagada, que sí dispara onPause/onResume incluso con el patrón hide()/show()
        // de TermuxActivity) tardaba hasta 5s en llegar. PluginsFragment.onResume() ya llamaba
        // pollStatus() de inmediato (línea ~155) — este Fragment quedaba asimétrico. Mismo
        // criterio ahora: re-leer el estado real (registry + binario en vivo) apenas la pantalla
        // vuelve a mostrarse, sin esperar al primer tick del poll periódico.
        handler.removeCallbacks(pollRunnable)
        pollStatus()
        handler.postDelayed(pollRunnable, 5000)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(pollRunnable)
    }

    override fun onDestroyView() {
        // Guardar ANTES de super.onDestroyView(): recyclerView sigue siendo la instancia
        // vieja acá — se lee su estado de scroll para restaurarlo si onViewCreated() se
        // vuelve a llamar (ver comentario de savedLayoutState arriba).
        if (::recyclerView.isInitialized) {
            savedLayoutState = recyclerView.layoutManager?.onSaveInstanceState()
        }
        super.onDestroyView()
        moduleManager?.removeListener(this)
        // Auditoría 2026-08-19 (docs/viejo/AUDITORIA_MONITOR_PROCESOS_2026-08-19.md):
        // mismo patrón defensivo que MonitorFragment.onDestroyView() — removeCallbacks(pollRunnable)
        // solo cancela ESE runnable puntual, no cualquier otro callback que haya quedado
        // encolado en `handler` (ej. un post() disparado por un callback async en curso al
        // momento de destruir la vista). removeCallbacksAndMessages(null) cancela todo lo
        // pendiente de este `handler`, cerrando la misma referencia colgante desde el Looper
        // principal que ya se corrigió en MonitorFragment/X11Fragment/TunnelFragment/NubeFragment.
        handler.removeCallbacksAndMessages(null)
    }

    private fun loadModuleDefinitions() {
        try {
            // Fase B (2026-08-10): el parseo del JSON ahora vive en ModuleCatalog.parse()
            // (data/ModuleCatalog.kt), compartido con la Tienda de plugins (PluginsFragment).
            // Antes ModulesFragment tenía su propia copia del parseo campo a campo — si el
            // catálogo ganaba un campo nuevo, había que acordarse de actualizar los DOS sitios.
            // Precedencia: cache del refresco remoto → bundled del APK (fallback offline).
            // internal=true (ver ModuleInfo.kt): módulos consolidados dentro de otro (ej. los
            // 16 lenguajes/paquetes npm dentro de "languages"/"packages") no aparecen como
            // entrada propia acá — evita el duplicado de que un lenguaje instalado desde
            // LanguagesFragment también aparezca suelto en Módulos.
            // hideFromCatalog=true (ver ModuleInfo.kt): módulos como "entorno" (Mini PC) que
            // siguen siendo navegables directo desde otras pantallas pero no deben listarse
            // como una card más del catálogo — 2026-08-26.
            val freshDefs = ModuleCatalog.load(requireContext())
                .filterNot { it.internal || it.hideFromCatalog }
            // Bug de navegación (ver MEJORAS_PENDIENTES.md): antes acá SIEMPRE se vaciaba el
            // adapter y se esperaba a pollStatus() (lento, spawnea procesos por módulo) para
            // repoblarlo — visible como "recarga completa + scroll a 0" cada vez que se volvía
            // de un detalle de módulo. Si el catálogo no cambió Y ya conocíamos qué módulos
            // estaban visibles (típico al volver de un backstack, que recrea esta vista/adapter
            // pero NO destruye el Fragment ni sus campos), repoblamos sincrónicamente con el
            // último estado conocido — sin flash, sin esperar al poll.
            val catalogChanged = freshDefs != moduleDefinitions
            moduleDefinitions.clear()
            moduleDefinitions.addAll(freshDefs)
            android.util.Log.i("ModulesFragment", "loadModuleDefinitions(): ${moduleDefinitions.size} módulos parseados en catálogo")
            if (catalogChanged || lastVisibleIds.isEmpty()) {
                // Primera carga real o el catálogo cambió de verdad (ej. tras "↻ Actualizar"):
                // no hay nada bueno para reusar todavía. El submitList real lo hace
                // pollStatus() (que conoce el estado por módulo) — acá se deja la lista vacía
                // y el estado vacío visible hasta el primer poll, evitando el flash de "todos
                // los módulos con — No instalado".
                lastVisibleIds = emptySet()
                sectionLabel.text = getString(R.string.modules_section_label)
                adapter.submitList(emptyList())
            } else {
                // Repoblado instantáneo desde el caché — mismo criterio que la Tienda de
                // plugins: usar lo último conocido en vez de recalcular todo de cero.
                adapter.updateStatuses(lastStatuses, lastVersions)
                submitVisible()
                emptyState.visibility = View.GONE
            }
        } catch (e: Exception) {
            // 2026-07-28: catch-all silencioso — si algo rompe acá (modules.json
            // corrupto, un campo con tipo inesperado, etc.) la lista quedaba vacía
            // SIN NINGÚN rastro visible, ni en pantalla ni en logcat. Reportado por
            // el usuario como "ningún módulo abre ni instala" — con esto, si vuelve
            // a pasar, al menos se ve el error real en vez de fallar en silencio.
            android.util.Log.e("ModulesFragment", "loadModuleDefinitions() falló", e)
            if (isAdded) {
                Snackbar.make(requireView(), getString(R.string.modules_error_loading, e.message.orEmpty()), Snackbar.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Recalcula la lista visible real (moduleDefinitions ∩ lastVisibleIds), filtrada por el
     * buscador (nombre/id/descripción/categoría, mismo criterio que PluginsFragment.applyFilter())
     * y ordenada alfabéticamente por nombre — mismo patrón que PluginsFragment.sortCatalog()
     * (2026-08-26, pedido del usuario: "Antigravity CLI" debe quedar primero, antes de eso el
     * orden era el crudo de modules.json). Se llama desde loadModuleDefinitions() (repoblado
     * sincrónico), pollStatus() (nuevo resultado real) y el TextWatcher del buscador — un solo
     * lugar que decide qué se ve, en vez de repetir el filtro+sort en cada call-site.
     */
    private fun submitVisible() {
        if (!::adapter.isInitialized) return
        val q = query.lowercase()
        val filtered = moduleDefinitions
            .filter { it.id in lastVisibleIds }
            .filter { m ->
                q.isEmpty() ||
                    m.name.lowercase().contains(q) ||
                    m.id.lowercase().contains(q) ||
                    m.description.lowercase().contains(q) ||
                    m.category.lowercase().contains(q)
            }
            .sortedBy { it.name.lowercase() }
        adapter.submitList(filtered)
        sectionLabel.text = if (lastVisibleIds.isEmpty()) getString(R.string.modules_section_label)
            else getString(R.string.modules_section_label_count, filtered.size)
    }

    private fun pollStatus(forceModuleId: String? = null) {
        // Snapshot inmutable ANTES de lanzar el hilo de fondo: moduleDefinitions es una
        // MutableList que loadModuleDefinitions() (hilo principal) limpia y repuebla —
        // si ese método corre de nuevo (ej. al volver de la pantalla de detalle de un
        // módulo, replace()+backstack recrea la vista y dispara onViewCreated otra vez)
        // MIENTRAS este hilo de fondo todavía está iterando la lista vieja, revienta con
        // ConcurrentModificationException. La copia acá evita la carrera de raíz, en vez
        // de solo capturarla (ver el try/catch de abajo, que igual queda como red de
        // seguridad para cualquier otra excepción).
        val defsSnapshot = moduleDefinitions.toList()
        val ctx = requireContext()
        Thread {
            try {
                // Fuente única de verdad: el registry real ($HOME/.android_server_registry,
                // escrito por los scripts bash) + un chequeo en vivo de tmux para saber si
                // el proceso está corriendo AHORA (el registry solo sabe si se instaló alguna
                // vez, no si sigue vivo). ModuleManager (Java, termux-shared) NO se usa acá
                // a propósito — corre en un sandbox separado que ningún script real escribe,
                // así que su estado siempre queda en el valor por defecto y antes tapaba este
                // fallback (ver docs/ kairos-app: pollStatus fix 2026-07-24).
                val reg = registry?.load()?.getModules() ?: emptyMap()

                val installed = mutableMapOf<String, Status>()
                val versions = mutableMapOf<String, String>()
                var installedCount = 0
                var activeCount = 0

                for (def in defsSnapshot) {
                    val mid = def.id
                    // 2026-08-10: isInstalled unificado (registry + fallback binario con cache).
                    // Antes esto era solo reg["$mid.installed"] == "true" — python instalado por
                    // el wizard no aparecía (kairos.sh no escribía python.installed). Ahora se
                    // centraliza el mismo criterio que PythonFragment/OllamaFragment.
                    val isInstalled = ModuleInstalled.isInstalled(ctx, mid)
                    val status = when {
                        isInstalled && ModuleController.isRunning(mid) -> {
                            activeCount++
                            installedCount++
                            Status.RUNNING
                        }
                        isInstalled -> {
                            installedCount++
                            Status.INSTALLED_STOPPED
                        }
                        else -> Status.NOT_INSTALLED
                    }
                    installed[mid] = status
                    reg["$mid.version"]?.let { if (it.isNotEmpty()) versions[mid] = it }
                }

                checkForCrashedModules(installed, defsSnapshot)
                previousStatuses = installed.toMap()

                // Subconjunto visible: solo los instalados (RUNNING o INSTALLED_STOPPED).
                // 2026-08-11 (humano97 punto 4): los módulos ocultos desde la Tienda
                // ("Desactivar" = ocultar del home SIN desinstalar, registry <id>.hidden=true)
                // se restan del subconjunto visible — el módulo sigue instalado y reactivable
                // desde la Tienda (ver PluginsFragment.manageModule).
                val hiddenIds = ProjectsManager.hiddenModuleIds()
                val visibleIds = installed
                    .filterValues { it != Status.NOT_INSTALLED }
                    .keys
                    .filterNot { it in hiddenIds }
                    .toSet()

                handler.post {
                    if (!isAdded) return@post
                    adapter.updateStatuses(installed, versions, forceIds = setOfNotNull(forceModuleId))
                    // Cachear el último resultado real conocido — lo usa loadModuleDefinitions()
                    // para repoblar sincrónicamente un adapter nuevo tras recrearse la vista
                    // (ver comentario de lastStatuses/lastVersions arriba).
                    lastStatuses = installed
                    lastVersions = versions
                    if (visibleIds != lastVisibleIds) {
                        lastVisibleIds = visibleIds
                        submitVisible()
                        emptyState.visibility = if (visibleIds.isEmpty()) View.VISIBLE else View.GONE
                    }
                    if (::swipeRefresh.isInitialized) swipeRefresh.isRefreshing = false
                    updateStats(installedCount, activeCount)
                }
            } catch (e: Exception) {
                // 2026-07-28: este Thread no tenía NINGÚN try/catch — si algo tiraba acá
                // (registry.load(), ModuleController.isRunning() para algún módulo nuevo,
                // lo que sea), el hilo moría en silencio y adapter.updateStatuses() NUNCA
                // se llamaba — todos los módulos quedaban viendo "No instalado" para
                // siempre (el fallback de ModuleListAdapter.getStatus()), sin ningún
                // rastro visible. Esto explica exactamente el reporte del usuario:
                // "ningún módulo abre ni instala" — ni el estado real de un módulo ya
                // instalado se reflejaba nunca. Con esto, al menos queda un log real.
                android.util.Log.e("ModulesFragment", "pollStatus() falló", e)
                handler.post { if (::swipeRefresh.isInitialized) swipeRefresh.isRefreshing = false }
            }
        }.start()
    }

    // ────────────────────────────────────────────────────────────
    // Notificaciones de "módulos caídos" — compara el ciclo anterior contra
    // el actual, dispara una notificación local por cada módulo que pasó de
    // RUNNING a INSTALLED_STOPPED (se cayó/se detuvo solo) entre polls.
    // Gateado por la preferencia "pref_notify_modules" de Ajustes.
    // ────────────────────────────────────────────────────────────

    private fun checkForCrashedModules(current: Map<String, Status>, defs: List<ModuleInfo>) {
        val prev = previousStatuses ?: return // primer ciclo: nada que comparar todavía
        val ctx = context ?: return
        val notifyEnabled = ctx.getSharedPreferences("kairos_prefs", 0)
            .getBoolean("pref_notify_modules", true)
        if (!notifyEnabled) return

        for ((moduleId, prevStatus) in prev) {
            val newStatus = current[moduleId] ?: continue
            if (prevStatus == Status.RUNNING && newStatus == Status.INSTALLED_STOPPED) {
                val name = defs.find { it.id == moduleId }?.name ?: moduleId
                notifyModuleStopped(ctx, name)
            }
        }
    }

    private fun notifyModuleStopped(ctx: Context, moduleName: String) {
        val channelId = "kairos_module_crashes"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(channelId) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(channelId, ctx.getString(R.string.modules_notification_channel_crashed), NotificationManager.IMPORTANCE_DEFAULT)
                )
            }
        }
        if (ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return // sin permiso (usuario lo rechazó) — no intentar notificar
        val notification = NotificationCompat.Builder(ctx, channelId)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(ctx.getString(R.string.modules_notification_stopped_title, moduleName))
            .setContentText(ctx.getString(R.string.modules_notification_stopped_text))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(ctx).notify(moduleName.hashCode(), notification)
    }

    private fun updateStats(installed: Int, active: Int) {
        statsInstalled.text = installed.toString()
        statsActive.text = active.toString()
        readRamInfo()
    }

    private fun readRamInfo() {
        try {
            BufferedReader(FileReader("/proc/meminfo")).use { reader ->
                var totalKb = 0L
                var availableKb = 0L
                reader.forEachLine { line ->
                    when {
                        line.startsWith("MemTotal:") -> totalKb = parseKb(line)
                        line.startsWith("MemAvailable:") -> availableKb = parseKb(line)
                    }
                }
                if (totalKb > 0) {
                    val usedGb = (totalKb - availableKb) / (1024f * 1024f)
                    val totalGb = totalKb / (1024f * 1024f)
                    statsRam.text = getString(R.string.modules_ram_format, usedGb, totalGb)
                }
            }
        } catch (_: Exception) {
            statsRam.text = getString(R.string.modules_ram_unavailable)
        }
    }

    private fun parseKb(line: String): Long {
        val numeric = line.replace(Regex("[^0-9]"), "")
        return if (numeric.isEmpty()) 0L else numeric.toLong()
    }

    private fun onRefreshClick() {
        val view = view ?: return
        Snackbar.make(view, getString(R.string.modules_updating_repo), Snackbar.LENGTH_LONG).show()
        Thread {
            val result = try {
                val home = TermuxConstants.TERMUX_HOME_DIR_PATH
                // Runtime.exec(["git",...]) usaba el PATH del proceso de la app (Android),
                // no el de Termux — "git" nunca se encontraba (Cannot run program "git":
                // error=2). git vive en $PREFIX/bin. Usar el mismo helper compartido que el
                // resto de la app (antes seteaba HOME/PATH a mano, sin PREFIX/LD_LIBRARY_PATH
                // que git también puede necesitar para resolver sus propias libs dinámicas).
                val pb = ProcessBuilder("git", "-C", "$home/termuxapp", "pull")
                pb.applyTermuxEnv()
                pb.redirectErrorStream(true)
                val proc = pb.start()
                val output = proc.inputStream.bufferedReader().readText().trim()
                val exit = proc.waitFor()
                if (exit == 0) "✓ $output" else "✗ $output"
            } catch (e: Exception) {
                "✗ ${friendlyProcessErrorMessage(e, "git")}"
            }
            handler.post {
                // Bug real confirmado (ver docs/humano/humano63.md): `view` se capturó ANTES
                // de arrancar el Thread — si el usuario navega fuera mientras el `git pull`
                // sigue en curso, ese `view` puede haber quedado obsoleto (Fragment sin vista
                // activa). Mismo patrón de guard ya establecido en el resto de la app.
                if (!isAdded) return@post
                Snackbar.make(view, result, Snackbar.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun onModuleClick(module: ModuleInfo) {
        val status = adapter.getStatus(module.id)
        when (status) {
            Status.NOT_INSTALLED, Status.ERROR -> {
                showInstallSheet(module)
            }
            Status.INSTALLED_STOPPED, Status.RUNNING -> {
                navigateToModuleDetail(module)
            }
            Status.INSTALLING -> { /* ignore during install */ }
        }
    }

    private fun navigateToModuleDetail(module: ModuleInfo) {
        // Fase B (2026-08-10): el mapeo id → fragment ahora vive en ModuleDetailNavigator
        // (compartido con la Tienda de plugins). Mismo comportamiento que antes: los 14
        // módulos con UI específica real usan su fragment dedicado, y cualquier módulo
        // NUEVO del catálogo cae en GenericModuleFragment (Fase A) sin necesitar clase
        // Kotlin nueva.
        ModuleDetailNavigator.navigate(parentFragmentManager, module)
    }

    private fun showInstallSheet(module: ModuleInfo) {
        val sheet = BottomSheetInstalacion.newInstance(
            id = module.id,
            name = module.name,
            icon = module.icon,
            iconBg = module.iconBg,
            description = module.description,
            port = module.port,
            size = module.size,
            type = module.type,
            requiresProot = module.requiresProot,
            hasVariants = module.hasVariants,
            estimate = module.estimate
        )
        sheet.show(parentFragmentManager, "install")
    }

    private fun onModuleToggle(module: ModuleInfo, start: Boolean) {
        val v = view ?: return
        if (start) {
            Snackbar.make(v, getString(R.string.modules_starting, module.name), Snackbar.LENGTH_SHORT).show()
            ModuleController.startModule(module.id, requireContext().applicationContext) { success, output ->
                // Auditoría 2026-07-27: si el usuario navega fuera mientras start/stop
                // sigue en vuelo, requireActivity() revienta con "Fragment not attached" —
                // mismo guard que ya usan onStatusChanged/onError/runManagerAction.
                if (!isAdded) return@startModule
                requireActivity().runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    // Libera el guard "pendiente" (gap real reportado en auditoría 2026-08-27,
                    // docs/humano273.md) — sin esto, un start/stop que tarda más de 5s (el poll
                    // periódico) reactivaba el switch antes de que la operación terminara de
                    // verdad, permitiendo un doble-tap real.
                    adapter.setPending(module.id, false)
                    if (!success) {
                        Snackbar.make(v, getString(R.string.modules_error_output, output.take(60)), Snackbar.LENGTH_LONG).show()
                    }
                    pollStatus(forceModuleId = module.id)
                }
            }
        } else {
            Snackbar.make(v, getString(R.string.modules_stopping, module.name), Snackbar.LENGTH_SHORT).show()
            ModuleController.stopModule(module.id, requireContext().applicationContext) { success ->
                if (!isAdded) return@stopModule
                requireActivity().runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    adapter.setPending(module.id, false)
                    // Antes fallaba en silencio — asimétrico con el path de start, que sí avisa.
                    if (!success) {
                        Snackbar.make(v, getString(R.string.modules_stop_failed, module.name), Snackbar.LENGTH_LONG).show()
                    }
                    pollStatus(forceModuleId = module.id)
                }
            }
        }
    }

    override fun onStatusChanged(moduleId: String, status: String) {
        if (!isAdded) return
        requireActivity().runOnUiThread { pollStatus() }
    }

    override fun onLog(moduleId: String, line: String) {}
    override fun onError(moduleId: String, error: String) {
        if (!isAdded) return
        requireActivity().runOnUiThread { pollStatus() }
    }
}

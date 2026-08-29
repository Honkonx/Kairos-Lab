package com.termux.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.google.android.material.card.MaterialCardView
import com.google.android.material.snackbar.Snackbar
import com.termux.R
import com.termux.app.ModuleController
import com.termux.app.util.EntornoNative
import com.termux.app.util.ManagerNativeUtils
import com.termux.app.util.PermissionManager
import com.termux.app.util.PhantomProcessKillerHelper
import com.termux.app.util.ProgressDialogController
import com.termux.app.util.TERMUX_BASH_PATH
import com.termux.app.util.TERMUX_PKG_PATH
import com.termux.app.util.TERMUX_PREFIX_PATH
import com.termux.app.util.TERMUX_PYTHON3_PATH
import com.termux.app.util.applyTermuxEnv
import com.termux.app.util.friendlyProcessErrorMessage
import com.termux.app.util.isTermuxBinaryAvailable
import com.termux.shared.termux.TermuxConstants
import org.json.JSONArray
import org.json.JSONObject
import com.termux.app.util.kairosThemeColor
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Tab de Monitor/Diagnóstico — dashboard único de la app tras la fusión con el ex-tab
 * "Sistema" (2026-08-26, pedido explícito del usuario: "que sea solo Monitor" — ver
 * docs/humano/humano225.md y siguientes). Muestra: RAM/almacenamiento/info de dispositivo
 * (sección DISPOSITIVO, ex-SystemFragment.kt, eliminado — su lista estática de "servicios" con
 * puertos hardcodeados y sus botones backup/terminal NO se portaron por ser redundantes con
 * MÓDULOS EN EJECUCIÓN de abajo y con Ajustes/el FAB de terminal respectivamente), ¿módulos con
 * proceso corriendo?, procesos pm2, conectividad de red, paquetes de Termux instalados,
 * paquetes de Python instalados. Ver docs/humano/humano.md / INVESTIGACION_FASE4.md.
 *
 * Fusionado también con la ex-pantalla "Procesos" (2026-08-12, ver docs/humano/humano98.md) — el
 * usuario las veía redundantes ("parecidas... ahorramos espacio"), ambas mostraban "qué está
 * corriendo" (módulos vía tmux acá, servicios pm2 en la otra). La sección "PROCESOS (pm2)"
 * de abajo es el contenido de `ProcesosFragment.kt` (ya eliminado) portado tal cual, solo
 * renombrando sus campos para no chocar con los de Monitor.
 */
class MonitorFragment : Fragment() {

    private lateinit var container: LinearLayout
    private lateinit var ramCanvas: TextView
    private lateinit var ramAvailable: TextView
    private lateinit var ramTotal: TextView
    private lateinit var ramUsed: TextView
    private lateinit var storageCanvas: TextView
    private lateinit var storageAvailable: TextView
    private lateinit var storageTotal: TextView
    private lateinit var storageUsed: TextView
    private lateinit var storageCardRow: LinearLayout
    private lateinit var cpuCanvas: TextView
    private lateinit var cpuLine1: TextView
    private lateinit var cpuLine2: TextView
    private lateinit var cpuLine3: TextView
    private lateinit var deviceInfoContainer: LinearLayout
    private lateinit var deviceIpValue: TextView
    private lateinit var deviceUptimeValue: TextView
    private lateinit var deviceGpuValue: TextView
    private lateinit var deviceNetworkValue: TextView
    private lateinit var deviceProcessesValue: TextView
    private lateinit var modulesContainer: LinearLayout
    private lateinit var aiEnginesContainer: LinearLayout
    private lateinit var cliToolsContainer: LinearLayout
    private lateinit var dbContainer: LinearLayout
    private lateinit var entornoContainer: LinearLayout
    private lateinit var procesosContainer: LinearLayout
    private lateinit var procesosEmptyState: TextView
    private lateinit var networkContainer: LinearLayout
    private lateinit var pkgSummary: TextView
    private lateinit var pipSummary: TextView
    private lateinit var diagnosticContainer: LinearLayout

    private val handler = Handler(Looper.getMainLooper())
    private var polling = false

    // Estado acumulado entre ticks de polling para calcular deltas reales (no snapshots
    // instantáneos que no dicen nada): CPU% real necesita 2 lecturas de /proc/stat
    // (jiffies acumulados desde el boot, no hay "% de CPU ahora mismo" de un solo read);
    // la tasa de red real necesita 2 lecturas de TrafficStats con el tiempo transcurrido
    // entre medio. lastCpuTotal=0L es el valor "todavía sin primera muestra" — la
    // primera pasada de readCpuInfo() solo siembra la base, no dibuja %.
    private var lastCpuTotal: Long = 0L
    private var lastCpuIdle: Long = 0L
    private var lastRxBytes: Long = -1L
    private var lastTxBytes: Long = -1L
    private var lastNetSampleTime: Long = 0L

    // GPU se consulta una sola vez (glxinfo/vulkaninfo/pkg list-installed son comandos
    // reales que spawnean procesos — repetirlos cada 5s en el ciclo de polling sería
    // costoso sin aportar nada, el renderer/tipo de GPU no cambia en caliente).
    private var gpuLoaded = false

    // Polling propio de 2s (ex-SystemFragment.kt) mientras falta el permiso de
    // almacenamiento — más agresivo que el ciclo general de 5s porque el usuario puede
    // conceder el permiso en cualquier momento desde Ajustes del sistema y queremos
    // reflejarlo rápido. Usa el mismo `handler` que el resto de Monitor — onDestroyView()
    // ya cancela TODO lo pendiente en ese handler (removeCallbacksAndMessages(null)), no
    // hace falta un guard aparte.
    private val storagePollRunnable = object : Runnable {
        override fun run() {
            readStorageInfo()
            handler.removeCallbacks(this)
            handler.postDelayed(this, 2000)
        }
    }

    // Módulos con proceso real (hasSwitch:true en modules.json) — misma fuente de
    // verdad que ModulesFragment/ModuleController, no un mecanismo paralelo.
    private fun processModules() = listOf(
        "ollama" to getString(R.string.monitor_module_ollama),
        "n8n" to getString(R.string.monitor_module_n8n),
        "openclaw" to getString(R.string.monitor_module_openclaw),
        "opencode" to getString(R.string.monitor_module_opencode),
        "remote" to getString(R.string.monitor_module_remote)
    )

    // Motores de IA — mismo criterio que processModules de arriba (misma fuente de
    // verdad, ModuleController.isRunning), separados en su propia sección porque el
    // pedido explícito de esta ronda es un dashboard de homelab con los motores de IA
    // agrupados aparte de los módulos generales. El puerto se lee de modules.json en
    // vez de hardcodearlo de nuevo acá (ModuleController.getModulePort() es privado) —
    // una sola fuente de verdad para el número de puerto de cada módulo.
    private fun aiEngineModules() = listOf(
        "ollama" to getString(R.string.monitor_module_ollama),
        "llamaserver" to getString(R.string.monitor_engine_llamaserver)
    )

    // Bug real confirmado 2026-08-25 (docs/arquitectura/AUDITORIA_GAPS_NAVEGACION_2026-08-25.md):
    // Hermes y Engram no tenían NINGÚN camino real de navegación en la UI — mismo bug que ya
    // tenía Remote (SSH), arreglado acá mismo agregando su fila a processModules. Hermes/Engram
    // NO encajan ahí: hasSwitch:false en modules.json (son CLIs sin proceso servidor
    // persistente, ModuleController.isRunning() no aplica de forma significativa) — sección
    // propia, solo lectura de "instalado sí/no" en vez de estado corriendo/detenido.
    private fun cliToolModules() = listOf(
        "hermes" to getString(R.string.monitor_cli_hermes),
        "engram" to getString(R.string.monitor_cli_engram)
    )

    override fun onCreateView(inflater: LayoutInflater, c: ViewGroup?, b: Bundle?): View? {
        val ctx = requireContext()
        val scroll = ScrollView(ctx).apply {
            setBackgroundColor(ctx.kairosThemeColor(R.attr.kairosBg))
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(container)

        sectionTitle(getString(R.string.monitor_section_device))
        val ramCard = buildMetricCard(R.attr.kairosGreen)
        ramCanvas = ramCard.canvas
        ramAvailable = ramCard.line1
        ramTotal = ramCard.line2
        ramUsed = ramCard.line3
        val storageMetricCard = buildMetricCard(R.attr.kairosBlue)
        storageCanvas = storageMetricCard.canvas
        storageAvailable = storageMetricCard.line1
        storageTotal = storageMetricCard.line2
        storageUsed = storageMetricCard.line3
        storageCardRow = storageMetricCard.row
        val cpuMetricCard = buildMetricCard(R.attr.kairosAmber)
        cpuCanvas = cpuMetricCard.canvas
        cpuLine1 = cpuMetricCard.line1
        cpuLine2 = cpuMetricCard.line2
        cpuLine3 = cpuMetricCard.line3
        deviceInfoContainer = cardContainer()
        deviceIpValue = labeledRow(deviceInfoContainer, getString(R.string.monitor_device_ip), "—", R.attr.kairosText)
        deviceUptimeValue = labeledRow(deviceInfoContainer, getString(R.string.monitor_device_uptime), "—", R.attr.kairosText)
        labeledRow(deviceInfoContainer, getString(R.string.monitor_device_android), getString(R.string.monitor_device_android_value, Build.VERSION.SDK_INT), R.attr.kairosText)
        labeledRow(deviceInfoContainer, getString(R.string.monitor_device_arch), Build.SUPPORTED_ABIS[0], R.attr.kairosText)
        deviceGpuValue = labeledRow(deviceInfoContainer, getString(R.string.monitor_device_gpu), getString(R.string.monitor_loading), R.attr.kairosText)
        deviceNetworkValue = labeledRow(deviceInfoContainer, getString(R.string.monitor_device_network_rate), getString(R.string.monitor_network_rate_measuring), R.attr.kairosText)
        deviceProcessesValue = labeledRow(deviceInfoContainer, getString(R.string.monitor_device_processes), getString(R.string.monitor_loading), R.attr.kairosText)

        sectionTitle(getString(R.string.monitor_section_modules))
        modulesContainer = cardContainer()

        sectionTitle(getString(R.string.monitor_section_ai_engines))
        aiEnginesContainer = cardContainer()

        sectionTitle(getString(R.string.monitor_section_cli_tools))
        cliToolsContainer = cardContainer()

        sectionTitle(getString(R.string.monitor_section_databases))
        dbContainer = cardContainer()

        sectionTitle(getString(R.string.monitor_section_desktop))
        entornoContainer = cardContainer()

        sectionTitle(getString(R.string.monitor_section_processes))
        procesosContainer = cardContainer()
        procesosEmptyState = TextView(ctx).apply {
            text = getString(R.string.monitor_loading)
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(context.kairosThemeColor(R.attr.kairosText2))
            setPadding(dp(14), dp(24), dp(14), dp(24))
        }
        procesosContainer.addView(procesosEmptyState)

        sectionTitle(getString(R.string.monitor_section_network))
        networkContainer = cardContainer()

        sectionTitle(getString(R.string.monitor_section_termux_packages))
        pkgSummary = infoOnlyCard(getString(R.string.monitor_loading))

        sectionTitle(getString(R.string.monitor_section_pip_packages))
        pipSummary = infoOnlyCard(getString(R.string.monitor_loading))

        sectionTitle(getString(R.string.monitor_section_diagnostics))
        diagnosticContainer = cardContainer()

        return scroll
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        readRamInfo()
        readStorageInfo()
        readCpuInfo()
        refreshDeviceLiveInfo()
        refreshGpuInfo()
        refreshProcessCount()
        refreshModules()
        refreshAiEngines()
        refreshCliTools()
        refreshDatabases()
        refreshEntornoDesktop()
        refreshProcesos()
        refreshNetwork()
        refreshPkgList()
        refreshPipList()
        buildDiagnosticRows()
        detectSystemRestrictions()
        startPolling()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        polling = false
        // Auditoría 2026-08-19 (docs/viejo/AUDITORIA_MONITOR_PROCESOS_2026-08-19.md):
        // el flag `polling` ya evita que el ciclo se re-agende, pero no cancela el
        // callback YA encolado en el MessageQueue — con TermuxActivity usando
        // add()+hide()/show() (no replace()) esto no se manifestó nunca como leak real
        // en uso normal, pero deja al Fragment referenciado desde el Looper principal
        // hasta que ese último tick se ejecuta. Mismo patrón defensivo que ya usan
        // X11Fragment/TunnelFragment/NubeFragment/los fragments del wizard — se agrega
        // acá para que MonitorFragment no sea la excepción.
        handler.removeCallbacksAndMessages(null)
    }

    private fun startPolling() {
        polling = true
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (!polling || !isAdded) return
                readRamInfo()
                readStorageInfo()
                readCpuInfo()
                refreshDeviceLiveInfo()
                refreshProcessCount()
                refreshModules()
                refreshAiEngines()
                refreshDatabases()
                refreshEntornoDesktop()
                refreshProcesos()
                refreshNetwork()
                handler.postDelayed(this, 5000)
            }
        }, 5000)
    }

    // ── Módulos ──────────────────────────────────────────────────────────

    private fun refreshModules() {
        val targets = processModules()
        Thread {
            val statuses = HashMap<String, Boolean>()
            for ((id, _) in targets) {
                statuses[id] = try {
                    ModuleController.isRunning(id)
                } catch (_: Exception) {
                    false
                }
            }
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                modulesContainer.removeAllViews()
                for ((id, name) in targets) {
                    val running = statuses[id] ?: false
                    modulesContainer.addView(statusRow(name, if (running) getString(R.string.monitor_status_running) else getString(R.string.monitor_status_stopped),
                        if (running) R.attr.kairosGreen else R.attr.kairosText3, id,
                        if (running) R.drawable.ic_start else R.drawable.ic_stop))
                }
            }
        }.start()
    }

    // ── Motores de IA ────────────────────────────────────────────────────
    // Pedido explícito de esta ronda (dashboard de homelab): ¿Ollama corriendo?
    // ¿llama-server corriendo? — misma fuente de verdad que refreshModules()
    // (ModuleController.isRunning), agregando el puerto real de modules.json cuando el
    // motor está corriendo.

    private fun refreshAiEngines() {
        val targets = aiEngineModules()
        Thread {
            val rows = targets.map { (id, name) ->
                val running = try { ModuleController.isRunning(id) } catch (_: Exception) { false }
                val port = if (running) readModulePort(id) else null
                Triple(id, name, Pair(running, port))
            }
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                aiEnginesContainer.removeAllViews()
                for ((id, name, runningAndPort) in rows) {
                    val (running, port) = runningAndPort
                    val value = when {
                        running && !port.isNullOrBlank() -> getString(R.string.monitor_status_running_port, port)
                        running -> getString(R.string.monitor_status_running)
                        else -> getString(R.string.monitor_status_stopped)
                    }
                    aiEnginesContainer.addView(statusRow(name, value, if (running) R.attr.kairosGreen else R.attr.kairosText3, id,
                        if (running) R.drawable.ic_start else R.drawable.ic_stop))
                }
            }
        }.start()
    }

    // ── Herramientas IA sin proceso persistente (Hermes/Engram) ─────────────────────────
    // Solo lectura de "instalado" (no hay estado corriendo/detenido real que trackear, son
    // CLIs invocados a demanda) — igual se re-lee cada vez que se entra a la pantalla, no
    // hace falta re-pollear cada 5s como processModules/aiEngineModules.
    private fun refreshCliTools() {
        val targets = cliToolModules()
        val ctx = requireContext().applicationContext
        Thread {
            val installed = targets.map { (id, _) ->
                id to try {
                    com.termux.app.data.ModuleInstalled.isInstalled(ctx, id)
                } catch (_: Exception) {
                    false
                }
            }.toMap()
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                cliToolsContainer.removeAllViews()
                for ((id, name) in targets) {
                    val isInstalled = installed[id] ?: false
                    cliToolsContainer.addView(statusRow(name, if (isInstalled) getString(R.string.monitor_installed_check) else getString(R.string.monitor_not_installed_dash),
                        if (isInstalled) R.attr.kairosGreen else R.attr.kairosText3, id,
                        if (isInstalled) R.drawable.ic_success else null))
                }
            }
        }.start()
    }

    /** Lee el puerto real de un módulo desde modules.json (misma fuente de verdad que
     * ModuleController.getModulePort(), que es privado — no se duplica el valor a mano
     * acá, se lee del mismo JSON que usa el resto de la app). */
    private fun readModulePort(moduleId: String): String? {
        return try {
            val json = requireContext().assets.open("modules.json").bufferedReader().use { it.readText() }
            val modules = JSONArray(json)
            for (i in 0 until modules.length()) {
                val m = modules.getJSONObject(i)
                if (m.optString("id") == moduleId) {
                    return m.optString("port").ifBlank { null }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    // ── Bases de datos ───────────────────────────────────────────────────
    // Pedido explícito de esta ronda: estado real de los motores de BD que soporta
    // Kairos (modulos/db.sh) y del último stack instalado (modulos/stacks.sh). MySQL y
    // PostgreSQL corren como daemons propios (mysqld/postgres) — mismo chequeo
    // "pgrep -x" que ya hace db.sh en su modo --status, vía el helper compartido
    // ManagerNativeUtils.pgrepX() (mismo patrón que ModuleController.isProcessAlive()).
    // SQLite no tiene daemon (CLI embebida, se abre por archivo) — se informa
    // disponibilidad del binario, no "corriendo". Los presets de stacks.sh (php -S,
    // npm run dev, python -m http.server) tampoco tienen un proceso gestionado por
    // Kairos que se pueda verificar de forma honesta — no hay tmux/pgrep propio, el
    // usuario los arranca a mano desde la terminal — así que acá solo se informa el
    // último preset INSTALADO (registry "stacks.last_preset"/"stacks.last_target"), sin
    // inventar un estado "corriendo" que no se puede verificar de verdad.

    private fun refreshDatabases() {
        Thread {
            val mysqlRunning = try { ManagerNativeUtils.pgrepX("mysqld") } catch (_: Exception) { false }
            val postgresRunning = try { ManagerNativeUtils.pgrepX("postgres") } catch (_: Exception) { false }
            val sqliteAvailable = try { isTermuxBinaryAvailable("sqlite3") } catch (_: Exception) { false }
            val reg = try { ManagerNativeUtils.readRegistry() } catch (_: Exception) { emptyMap() }
            val stacksInstalled = reg["stacks.installed"] == "true"
            val lastPreset = reg["stacks.last_preset"]?.takeIf { it.isNotBlank() }
            val lastTarget = reg["stacks.last_target"]?.takeIf { it.isNotBlank() }
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                dbContainer.removeAllViews()
                dbContainer.addView(statusRow(
                    getString(R.string.monitor_db_mysql),
                    if (mysqlRunning) getString(R.string.monitor_status_running) else getString(R.string.monitor_status_stopped),
                    if (mysqlRunning) R.attr.kairosGreen else R.attr.kairosText3
                ))
                dbContainer.addView(statusRow(
                    getString(R.string.monitor_db_postgres),
                    if (postgresRunning) getString(R.string.monitor_status_running) else getString(R.string.monitor_status_stopped),
                    if (postgresRunning) R.attr.kairosGreen else R.attr.kairosText3
                ))
                dbContainer.addView(statusRow(
                    getString(R.string.monitor_db_sqlite),
                    if (sqliteAvailable) getString(R.string.monitor_db_available) else getString(R.string.monitor_db_not_installed),
                    if (sqliteAvailable) R.attr.kairosGreen else R.attr.kairosText3
                ))
                val stacksValue = if (stacksInstalled && lastPreset != null) {
                    if (lastTarget != null) getString(R.string.monitor_db_stack_target_format, lastPreset, lastTarget) else lastPreset
                } else {
                    getString(R.string.monitor_db_no_presets)
                }
                dbContainer.addView(statusRow(
                    getString(R.string.monitor_db_last_stack),
                    stacksValue,
                    if (stacksInstalled && lastPreset != null) R.attr.kairosText else R.attr.kairosText3
                ))
            }
        }.start()
    }

    // ── Entorno — Escritorio ─────────────────────────────────────────────
    // Pedido explícito de esta ronda: ¿hay un escritorio activo corriendo ahora mismo?
    // Investigado en EntornoNative.kt (no se asumió el patrón): el escritorio nativo de
    // Kairos (XFCE4/LXQt/MATE) corre sobre el HOME real de Termux, montado sobre el
    // servidor X11 EMBEBIDO del propio APK (X11Service, proceso ":xserver") — no dentro
    // de una distro proot aislada (eso quedó reemplazado 2026-08-12, ver comentario de
    // EntornoNative.startDesktop()). No existe un archivo de estado "qué distro tiene
    // escritorio activo" porque el escritorio no vive dentro de una distro — el patrón
    // real usado por EntornoNative.status() (ya público, reusado tal cual acá) es
    // "pgrep -f :xserver" para saber si el servidor X11 está vivo ahora mismo, más la
    // lista de escritorios instalados (verificados por binario: startxfce4/startlxqt/
    // mate-session).
    private fun refreshEntornoDesktop() {
        Thread {
            val status = try { EntornoNative.status() } catch (_: Exception) { null }
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                entornoContainer.removeAllViews()
                if (status == null || !status.optBoolean("ok", false)) {
                    entornoContainer.addView(statusRow(getString(R.string.monitor_desktop_x11_server), getString(R.string.monitor_desktop_x11_unavailable), R.attr.kairosText3))
                    return@runOnUiThread
                }
                val x11Running = status.optBoolean("x11_running", false)
                entornoContainer.addView(statusRow(
                    getString(R.string.monitor_desktop_x11_server),
                    if (x11Running) getString(R.string.monitor_status_running) else getString(R.string.monitor_status_stopped),
                    if (x11Running) R.attr.kairosGreen else R.attr.kairosText3
                ))
                val desktopsArray = status.optJSONArray("installed_desktops")
                val desktops = (0 until (desktopsArray?.length() ?: 0)).mapNotNull { desktopsArray?.optString(it) }
                val desktopsValue = if (desktops.isEmpty()) {
                    getString(R.string.monitor_desktop_none_installed)
                } else {
                    desktops.joinToString(", ") { EntornoNative.desktopLabel(it) }
                }
                entornoContainer.addView(statusRow(
                    getString(R.string.monitor_desktop_installed),
                    desktopsValue,
                    if (desktops.isEmpty()) R.attr.kairosText3 else R.attr.kairosText
                ))
            }
        }.start()
    }

    // ── Procesos (pm2) — ex-ProcesosFragment.kt, fusionado acá (ver docs/humano/humano98.md) ──
    // pm2 ya se instala como parte del wizard (kairos.sh PASO 8, npm globales). Corre
    // `pm2 jlist` directo por ProcessBuilder (sin python3/kairos_manager.py de por medio).

    private fun refreshProcesos() {
        runPm2("jlist") { ok, output ->
            if (!isAdded) return@runPm2
            procesosContainer.removeAllViews()
            if (!ok) {
                showPm2Unavailable(output)
                return@runPm2
            }
            val procs = try {
                JSONArray(extractJsonArray(output))
            } catch (e: Exception) {
                procesosEmptyState.text = getString(R.string.monitor_pm2_invalid_json, e.message ?: "null")
                procesosContainer.addView(procesosEmptyState)
                return@runPm2
            }
            if (procs.length() == 0) {
                procesosEmptyState.text = getString(R.string.monitor_pm2_no_processes)
                procesosContainer.addView(procesosEmptyState)
                return@runPm2
            }
            for (i in 0 until procs.length()) {
                procesosContainer.addView(procesoRow(normalizePm2Process(procs.getJSONObject(i))))
            }
        }
    }

    /**
     * pm2 no es un módulo de modules.json (no tiene pantalla propia con botón
     * "Instalar") — se instala como parte de kairos.sh PASO 8 (npm globales), junto al
     * resto del bootstrap inicial. Si `pm2 jlist` falla, distinguimos:
     *  - pm2 realmente no está en PATH (isTermuxBinaryAvailable devuelve false): mensaje
     *    claro + botón para reinstalarlo ahí mismo, sin tener que ir a Ajustes ni
     *    reinstalar la app entera — mismo patrón ya usado en PythonFragment.
     *  - pm2 SÍ está disponible pero el comando falló por otra razón (pm2 daemon
     *    corrupto, permisos, etc.): se muestra el error real (ya traducido si es el
     *    "Cannot run program" crudo de la JVM).
     */
    private fun showPm2Unavailable(rawOutput: String) {
        if (isTermuxBinaryAvailable("pm2")) {
            procesosEmptyState.text = getString(R.string.monitor_error_generic, rawOutput)
            procesosContainer.addView(procesosEmptyState)
            return
        }
        procesosEmptyState.text = getString(R.string.monitor_pm2_unavailable)
        procesosContainer.addView(procesosEmptyState)
        procesosContainer.addView(TextView(requireContext()).apply {
            text = getString(R.string.monitor_pm2_reinstall_btn)
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(context.kairosThemeColor(R.attr.kairosGreen))
            setPadding(dp(14), dp(10), dp(14), dp(14))
            setOnClickListener { reinstallPm2() }
        })
    }

    private fun reinstallPm2() {
        procesosEmptyState.text = getString(R.string.monitor_pm2_reinstalling)
        procesosContainer.removeAllViews()
        procesosContainer.addView(procesosEmptyState)
        Thread {
            val (ok, output) = try {
                val pb = ProcessBuilder("$TERMUX_PREFIX_PATH/bin/npm", "install", "-g", "pm2")
                pb.applyTermuxEnv()
                pb.redirectErrorStream(true)
                val process = pb.start()
                val out = process.inputStream.bufferedReader().readText()
                (process.waitFor() == 0) to out
            } catch (e: Exception) {
                false to friendlyProcessErrorMessage(e, "npm")
            }
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                val msg = if (ok && isTermuxBinaryAvailable("pm2")) {
                    getString(R.string.monitor_pm2_reinstall_ok)
                } else {
                    getString(R.string.monitor_pm2_reinstall_fail, output.takeLast(200))
                }
                Snackbar.make(requireView(), msg, Snackbar.LENGTH_LONG).show()
                refreshProcesos()
            }
        }.start()
    }

    /**
     * Causa real confirmada del bug "en monitor da error pm2" (auditoría 2026-08-20, ver
     * docs/viejo/AUDITORIA_BUGS_MONITOR_VERIFICAR_2026-08-19.md): la PRIMERA vez que se
     * corre `pm2 jlist` tras el arranque del dispositivo (o si el daemon de pm2 se cayó), pm2
     * no tiene el daemon corriendo todavía — lo spawnea solo, e imprime líneas informativas
     * ("[PM2] Spawning PM2 daemon with pm2_home=...", "[PM2] PM2 Successfully daemonized") a
     * STDOUT (no a stderr) ANTES del array JSON real. Como runPm2() para "jlist" separa
     * stdout/stderr a propósito (necesita JSON puro), esas líneas de spawn quedaban mezcladas
     * con el JSON y JSONArray(output) fallaba con "Respuesta de pm2 no es JSON válido" — el pm2
     * SÍ estaba disponible y SÍ funcionaba, el parseo simplemente no toleraba el ruido de la
     * primera corrida. Se extrae el substring entre el primer '[' y el último ']' antes de
     * parsear — tolera cualquier prefijo/sufijo no-JSON sin importar su contenido exacto.
     */
    private fun extractJsonArray(raw: String): String {
        val start = raw.indexOf('[')
        val end = raw.lastIndexOf(']')
        if (start == -1 || end == -1 || end < start) return raw
        return raw.substring(start, end + 1)
    }

    /** Traduce el JSON crudo de "pm2 jlist" (pm2_env/monit anidados) a campos planos. */
    private fun normalizePm2Process(raw: JSONObject): JSONObject {
        val env = raw.optJSONObject("pm2_env") ?: JSONObject()
        val monit = raw.optJSONObject("monit") ?: JSONObject()
        return JSONObject().apply {
            put("name", raw.optString("name", "?"))
            put("pid", raw.optInt("pid", 0))
            put("status", env.optString("status", "unknown"))
            put("restarts", env.optInt("restart_time", 0))
            put("cpu", monit.optInt("cpu", 0))
            put("mem", humanSize(monit.optLong("memory", 0)))
        }
    }

    private fun humanSize(bytes: Long): String {
        var b = bytes.toDouble()
        for (unit in listOf("B", "KB", "MB", "GB")) {
            if (b < 1024) return "%.1f%s".format(b, unit)
            b /= 1024
        }
        return "%.1fTB".format(b)
    }

    private fun procesoRow(p: JSONObject): View {
        val ctx = requireContext()
        val name = p.optString("name", "?")
        val status = p.optString("status", "unknown")
        val running = status == "online"
        val statusColor = when (status) {
            "online" -> R.attr.kairosGreen
            "stopped" -> R.attr.kairosText3
            else -> R.attr.kairosRed
        }
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        val top = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(TextView(ctx).apply {
            text = name
            textSize = 14f
            setTextColor(context.kairosThemeColor(R.attr.kairosText))
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        })
        top.addView(TextView(ctx).apply {
            text = if (running) "● $status" else "○ $status"
            textSize = 11f
            setTypeface(android.graphics.Typeface.MONOSPACE)
            setTextColor(context.kairosThemeColor(statusColor))
        })
        card.addView(top)

        card.addView(TextView(ctx).apply {
            text = getString(R.string.monitor_pm2_proc_detail, p.optInt("pid", 0), p.optInt("cpu", 0), p.optString("mem", "?"), p.optInt("restarts", 0))
            textSize = 11f
            setTextColor(context.kairosThemeColor(R.attr.kairosText2))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).also { it.topMargin = dp(2) }
        })

        val actions = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).also { it.topMargin = dp(8) }
        }
        // Auditoría 2026-08-19 (docs/viejo/AUDITORIA_MONITOR_PROCESOS_2026-08-19.md):
        // antes había un botón fijo extra "↻ Reiniciar" acá, siempre presente además del
        // primer botón — cuando el proceso estaba detenido, ambos hacían exactamente lo
        // mismo (pm2Action("restart", name)), duplicado real heredado del port de
        // ProcesosFragment.kt. Solo corriendo tenía sentido (Detener vs Reiniciar son
        // acciones distintas ahí) — se deja solo el botón que ya alterna según el estado.
        actions.addView(procesoActionLabel(if (running) getString(R.string.monitor_pm2_action_stop) else getString(R.string.monitor_pm2_action_restart),
            if (running) R.drawable.ic_stop else R.drawable.ic_start) {
            pm2Action(if (running) "stop" else "restart", name)
        })
        if (running) {
            // "↻ Reiniciar" es un reinicio real (distinto de arrancar/detener) — ninguno
            // de los 9 íconos nuevos representa "reiniciar", se deja sin ícono en vez de
            // forzar ic_start/ic_stop, que serían engañosos acá.
            actions.addView(procesoActionLabel(getString(R.string.monitor_pm2_action_restart_icon)) { pm2Action("restart", name) })
        }
        actions.addView(procesoActionLabel(getString(R.string.monitor_action_delete), R.drawable.ic_uninstall) { confirmDeleteProceso(name) })
        card.addView(actions)

        return card
    }

    // iconRes opcional (pulido visual 2026-08-25, wireo de íconos vectoriales — ver
    // docs/estructura/ESTILO_VISUAL_2026-08-25.md): compound drawable a la izquierda del
    // label, no un ImageView aparte — este botón ya es un solo TextView centrado (gravity
    // CENTER), agregar un LinearLayout horizontal acá rompería ese centrado.
    private fun procesoActionLabel(text: String, iconRes: Int? = null, onClick: () -> Unit): TextView {
        return TextView(requireContext()).apply {
            this.text = text
            textSize = 12f
            setTextColor(context.kairosThemeColor(R.attr.kairosText2))
            setPadding(dp(8), dp(6), dp(8), dp(6))
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            if (iconRes != null) {
                compoundDrawablePadding = dp(6)
                val icon = androidx.core.content.ContextCompat.getDrawable(context, iconRes)?.mutate()
                icon?.setTint(context.kairosThemeColor(R.attr.kairosText2))
                setCompoundDrawablesWithIntrinsicBounds(icon, null, null, null)
            }
            // Ripple táctil (pulido visual 2026-08-25, docs/estructura/ESTILO_VISUAL_2026-08-25.md)
            // — mismo patrón ya usado más abajo en este archivo para las filas de módulo.
            val outValue = android.util.TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
            if (outValue.resourceId != 0) setBackgroundResource(outValue.resourceId)
            setOnClickListener { onClick() }
        }
    }

    private fun confirmDeleteProceso(name: String) {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.monitor_pm2_delete_title, name))
            .setMessage(getString(R.string.monitor_pm2_delete_message))
            .setPositiveButton(getString(R.string.monitor_action_delete)) { _, _ -> pm2Action("delete", name) }
            .setNegativeButton(getString(R.string.monitor_action_cancel), null)
            .show()
    }

    private fun pm2Action(action: String, name: String) {
        runPm2(action, name) { ok, output ->
            if (!isAdded) return@runPm2
            val msg = if (ok) getString(R.string.monitor_pm2_action_result, name, action) else getString(R.string.monitor_error_generic, output)
            Snackbar.make(requireView(), msg, Snackbar.LENGTH_SHORT).show()
            refreshProcesos()
        }
    }

    /**
     * Corre `pm2 <args...>` directo. `onResult` recibe (exitCode == 0, salida). Para
     * "jlist" el stdout se mantiene separado de stderr (se necesita JSON puro para
     * parsear); para el resto (stop/restart/delete) se combinan.
     */
    private fun runPm2(vararg args: String, onResult: (Boolean, String) -> Unit) {
        val mergeStderr = args.firstOrNull() != "jlist"
        Thread {
            val (ok, output) = try {
                val cmd = mutableListOf("$TERMUX_PREFIX_PATH/bin/pm2")
                cmd.addAll(args)
                val pb = ProcessBuilder(cmd)
                pb.applyTermuxEnv()
                pb.redirectErrorStream(mergeStderr)
                val process = pb.start()
                val out = process.inputStream.bufferedReader().readText()
                val err = if (mergeStderr) "" else process.errorStream.bufferedReader().readText()
                val exitOk = process.waitFor() == 0
                exitOk to (if (exitOk || mergeStderr) out else (err.ifBlank { out }))
            } catch (e: Exception) {
                false to friendlyProcessErrorMessage(e, "pm2")
            }
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread { if (isAdded) onResult(ok, output) }
        }.start()
    }

    // ── Red ──────────────────────────────────────────────────────────────

    private fun refreshNetwork() {
        networkContainer.removeAllViews()
        val ctx = requireContext()
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val network = cm?.activeNetwork
        val caps = network?.let { cm.getNetworkCapabilities(it) }
        if (caps == null) {
            networkContainer.addView(statusRow(getString(R.string.monitor_network_label_connection), getString(R.string.monitor_network_no_connection), R.attr.kairosRed))
            return
        }
        val type = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> getString(R.string.monitor_network_wifi)
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> getString(R.string.monitor_network_cellular)
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> getString(R.string.monitor_network_ethernet)
            else -> getString(R.string.monitor_network_other)
        }
        val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        networkContainer.addView(statusRow(getString(R.string.monitor_network_label_type), type, R.attr.kairosText))
        networkContainer.addView(statusRow(getString(R.string.monitor_network_label_internet),
            if (validated) getString(R.string.monitor_network_validated) else getString(R.string.monitor_network_no_internet),
            if (validated) R.attr.kairosGreen else R.attr.kairosAmber))
    }

    // ── Paquetes de Termux ──────────────────────────────────────────────

    /** name/version de una línea de `pkg list-installed` — mismo criterio de parseo
     * que tenía cmd_pkg en kairos_manager.py (ver docs/humano*.md, migración a Kotlin). */
    private data class TermuxPackageInfo(val name: String, val version: String)

    private fun refreshPkgList() {
        Thread {
            val text = try {
                val count = listInstalledTermuxPackages().size
                getString(R.string.monitor_pkg_count, count)
            } catch (e: Exception) {
                pkgUnavailableMessage(e)
            }
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread { pkgSummary.text = text }
        }.start()
    }

    /**
     * `pkg` es parte del bootstrap base de Termux (paquete termux-tools, extraído por
     * TermuxInstaller al primer arranque) — a diferencia de pm2/python3, NINGÚN script
     * de kairos.sh lo "instala": kairos.sh directamente LO USA para instalar todo lo
     * demás (ver PASO 2, `pkg update && pkg upgrade`). Si acá `pkg` no está disponible,
     * es señal de que el bootstrap en sí quedó incompleto o se corrompió en este
     * dispositivo puntual — no un módulo que se pueda reinstalar desde la UI.
     *
     * Investigación confirmada (2026-08-01): ni "Re-ejecutar setup" ni "Reinstalar
     * stack" (ConfigFragment) arreglan esto — TermuxInstaller.setupBootstrapIfNeeded()
     * se salta la re-extracción del bootstrap zip apenas $PREFIX ya existe y no está
     * vacío (ver TermuxInstaller.java, chequeo de directoryFileExists/
     * isTermuxPrefixDirectoryEmpty), sin verificar que los binarios de adentro sigan
     * intactos — y ninguno de los dos flujos de "reparar" de ConfigFragment borra
     * $PREFIX (solo tocan $HOME/scripts, el registry y .kairos_ready). Repararlo de
     * verdad requeriría un flujo nuevo que borre $PREFIX y fuerce la re-extracción del
     * zip de bootstrap — no existe todavía (fuera de alcance de este fix puntual, ver
     * hallazgo documentado en el reporte de la ronda 2026-08-01).
     */
    private fun pkgUnavailableMessage(e: Exception): String {
        if (!isTermuxBinaryAvailable("pkg")) {
            return getString(R.string.monitor_pkg_unavailable)
        }
        return getString(R.string.monitor_error_generic, friendlyProcessErrorMessage(e, "pkg"))
    }

    /** Corre `pkg list-installed` nativo (sin pasar por python3/kairos_manager.py) —
     * formato de línea típico: "nombre/stable,now 1.2.3 aarch64 [installed]". */
    private fun listInstalledTermuxPackages(): List<TermuxPackageInfo> {
        val pb = ProcessBuilder(TERMUX_PKG_PATH, "list-installed")
        pb.applyTermuxEnv()
        val process = pb.start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()
        return output.lineSequence()
            .filter { it.contains("/") }
            .mapNotNull { line ->
                val parts = line.trim().split(Regex("\\s+"))
                val name = parts.getOrNull(0)?.substringBefore("/")?.trim() ?: return@mapNotNull null
                if (name.isEmpty()) return@mapNotNull null
                TermuxPackageInfo(name, parts.getOrElse(1) { "" })
            }
            .toList()
    }

    /** Corre `python3 -m pip list` directo (sin pasar por kairos_manager.py, migrado la
     * ronda 2026-07-31 junto al resto del archivo — ver docs/humano/humano27.md) — mismo criterio
     * de fallback que tenía cmd_python's pip-list: intenta `--format=json` primero (más
     * confiable de parsear), y si pip no soporta ese flag o no devuelve JSON válido, cae al
     * formato de texto plano de `pip list` (2 líneas de cabecera, luego "nombre  versión"). */
    private fun refreshPipList() {
        Thread {
            val text = try {
                val count = listInstalledPipPackages().size
                getString(R.string.monitor_pip_count, count)
            } catch (e: Exception) {
                pipUnavailableMessage(e)
            }
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread { pipSummary.text = text }
        }.start()
    }

    /**
     * A diferencia de pkg (bootstrap base, ver pkgUnavailableMessage), python3/pip SÍ
     * los instala un script de la app (python.sh) — así que acá SÍ se puede distinguir
     * "nunca se instaló" (registry sin python.installed=true) de "se instaló pero se
     * rompió después" (registry dice true, binario no responde ahora).
     *
     * Root cause del segundo caso confirmado y arreglado en esta misma ronda: python.sh
     * marcaba el checkpoint "python_install" como hecho para siempre, sin re-verificar
     * en corridas futuras que python3 siguiera respondiendo (ver modulos/python.sh
     * PASO 3) — con el fix, reinstalar desde Módulos > Python ahora sí vuelve a
     * instalar python3 de verdad en vez de saltarse el paso por el checkpoint viejo.
     */
    private fun pipUnavailableMessage(e: Exception): String {
        if (!isTermuxBinaryAvailable("python3")) {
            val everInstalled = ManagerNativeUtils.readRegistry()["python.installed"] == "true"
            return if (everInstalled) {
                getString(R.string.monitor_pip_broken)
            } else {
                getString(R.string.monitor_pip_never_installed)
            }
        }
        return getString(R.string.monitor_error_generic, friendlyProcessErrorMessage(e, "pip"))
    }

    private data class PipPackageInfo(val name: String, val version: String)

    private fun listInstalledPipPackages(): List<PipPackageInfo> {
        val jsonPb = ProcessBuilder(TERMUX_PYTHON3_PATH, "-m", "pip", "list", "--format=json")
        jsonPb.applyTermuxEnv()
        val jsonProcess = jsonPb.start()
        val jsonOutput = jsonProcess.inputStream.bufferedReader().readText()
        if (jsonProcess.waitFor() == 0 && jsonOutput.isNotBlank()) {
            try {
                val array = org.json.JSONArray(jsonOutput)
                return (0 until array.length()).map {
                    val obj = array.getJSONObject(it)
                    PipPackageInfo(obj.optString("name"), obj.optString("version"))
                }
            } catch (_: Exception) {
                // pip viejo sin --format=json o salida no-JSON — cae al parseo de texto plano.
            }
        }
        val plainPb = ProcessBuilder(TERMUX_PYTHON3_PATH, "-m", "pip", "list")
        plainPb.applyTermuxEnv()
        val plainProcess = plainPb.start()
        val plainOutput = plainProcess.inputStream.bufferedReader().readText()
        plainProcess.waitFor()
        return plainOutput.lineSequence()
            .drop(2) // 2 líneas de cabecera ("Package  Version" + "-------  -------")
            .mapNotNull { line ->
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size < 2) return@mapNotNull null
                PipPackageInfo(parts[0], parts[1])
            }
            .toList()
    }

    // ── Diagnóstico avanzado ──────────────────────────────────────────────
    // Pieza adoptada de termux-desktop-main (ver docs/referencias/REFERENCIA_TERMUX_DESKTOP.md,
    // hallazgo 2026-08-01): el "phantom process killer" de Android 12+ mata procesos en
    // segundo plano cuando una app supera ~32 procesos "phantom" simultáneos o usa CPU
    // excesiva. Kairos corre exactamente el patrón que lo dispara — cada módulo activo
    // (Ollama serve, n8n, OpenClaw, cada sesión de terminal por CLI vía tmux) es un
    // proceso hijo de larga duración; con varios activos a la vez es plausible pasar el
    // umbral. Ya se sospechaba que esto explicaba un SIGKILL de OpenClaw en una ronda
    // anterior sin confirmar la causa (ver también decodeExitSignal() en
    // ModuleController.startModule/installModule — un SIGKILL ahí ahora avisa esto
    // explícitamente en el log).
    //
    // El fix real (`settings put global settings_enable_monitor_phantom_procs false`)
    // requiere el permiso WRITE_SECURE_SETTINGS, que ninguna app normal tiene por
    // defecto. Primer intento, silencioso: `su` (solo funciona si el dispositivo está
    // rooteado). Si eso falla, ronda 51 (ver docs/humano/humano43.md/humano44.md) agregó una
    // segunda vía real sin PC: emparejar ADB por "Depuración inalámbrica" desde el propio
    // dispositivo (PhantomProcessKillerHelper.kt) — y como último recurso, el tutorial
    // manual con los comandos para que el usuario los corra él mismo. Nunca un fix
    // silencioso que finja haber funcionado sin evidencia — cada camino verifica el
    // resultado real antes de reportar éxito.
    private val phantomKillerCommand =
        "settings put global ${PhantomProcessKillerHelper.PHANTOM_SETTING_KEY} false"

    // true una vez que runGuidedFix() (o el intento root) confirmó el valor en "false" en
    // esta sesión — solo afecta el texto/color de la fila, el botón sigue disponible
    // siempre porque algunos dispositivos necesitan repetir el fix tras reiniciar (ver
    // nota de referencia/emuladores/XoDos2-main/phantom.md en PhantomProcessKillerHelper.kt).
    private var phantomKillerFixedThisSession = false

    // Estado REAL detectado (pedido 2026-08-13, ver humano101): no asumir el valor del
    // setting, leerlo de verdad al abrir la pantalla y avisar al usuario qué le conviene
    // desactivar para evitar errores (phantom killer ON / optimización de batería activa).
    private var phantomKillerActive = true
    private var batteryOptimized = true
    private var restrictionsChecked = false

    // MVP de modo root (2026-08-25, ver `docs/arquitectura/INVESTIGACION_MODO_ROOT_2026-08-25.md`)
    // — sin root, Android (hidepid, desde Android 7) restringe /proc a los procesos propios de
    // Kairos; con root, se puede listar el conteo real de procesos de TODO el sistema. Acceso
    // cross-app (leer /data/data/<otraApp>) queda deliberadamente FUERA de este MVP.
    private var rootChecked = false
    private var rootSandboxProcessCount: Int? = null
    private var rootFullProcessCount: Int? = null

    private fun buildDiagnosticRows() {
        diagnosticContainer.removeAllViews()

        // Fila phantom killer — subtítulo según el estado REAL detectado del setting.
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        row.addView(LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            addView(TextView(requireContext()).apply {
                text = getString(R.string.monitor_diag_phantom_label)
                textSize = 13f
                setTextColor(context.kairosThemeColor(R.attr.kairosText))
            })
            addView(TextView(requireContext()).apply {
                text = if (phantomKillerFixedThisSession)
                    getString(R.string.monitor_diag_phantom_fixed)
                else if (!restrictionsChecked)
                    getString(R.string.monitor_diag_checking_real_state)
                else if (!phantomKillerActive)
                    getString(R.string.monitor_diag_phantom_off)
                else
                    getString(R.string.monitor_diag_phantom_on)
                textSize = 11f
                setTextColor(context.kairosThemeColor(
                    when {
                        phantomKillerFixedThisSession || (restrictionsChecked && !phantomKillerActive) -> R.attr.kairosGreen
                        restrictionsChecked && phantomKillerActive -> R.attr.kairosAmber
                        else -> R.attr.kairosText3
                    }
                ))
            })
        })
        row.addView(TextView(requireContext()).apply {
            text = if (phantomKillerFixedThisSession) getString(R.string.monitor_diag_reapply) else getString(R.string.monitor_diag_disable)
            textSize = 12f
            setTextColor(context.kairosThemeColor(R.attr.kairosBlue))
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener { disablePhantomProcessKiller() }
        })
        diagnosticContainer.addView(row)

        // Fila optimización de batería — detecta con el PowerManager real si Kairos está
        // exento de las restricciones de batería del sistema (ver BatteryRestrictionHelper).
        val batteryRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        batteryRow.addView(LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            addView(TextView(requireContext()).apply {
                text = getString(R.string.monitor_diag_battery_label)
                textSize = 13f
                setTextColor(context.kairosThemeColor(R.attr.kairosText))
            })
            addView(TextView(requireContext()).apply {
                text = if (!restrictionsChecked)
                    getString(R.string.monitor_diag_checking_real_state)
                else if (!batteryOptimized)
                    getString(R.string.monitor_diag_battery_off)
                else
                    getString(R.string.monitor_diag_battery_on)
                textSize = 11f
                setTextColor(context.kairosThemeColor(
                    when {
                        !restrictionsChecked -> R.attr.kairosText3
                        !batteryOptimized -> R.attr.kairosGreen
                        else -> R.attr.kairosAmber
                    }
                ))
            })
        })
        batteryRow.addView(TextView(requireContext()).apply {
            text = getString(R.string.monitor_diag_disable)
            textSize = 12f
            setTextColor(context.kairosThemeColor(R.attr.kairosBlue))
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener {
                (activity as? android.app.Activity)?.let {
                    com.termux.app.util.BatteryRestrictionHelper.requestDisableBatteryRestrictions(it)
                }
            }
        })
        diagnosticContainer.addView(batteryRow)

        // Fila modo root — solo informativa acá (más acciones concretas de root viven en
        // Ciberseguridad/nmap, no en Monitor). Sin acceso cross-app deliberadamente.
        val rootRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        rootRow.addView(LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            addView(TextView(requireContext()).apply {
                text = getString(R.string.monitor_diag_root_label)
                textSize = 13f
                setTextColor(context.kairosThemeColor(R.attr.kairosText))
            })
            addView(TextView(requireContext()).apply {
                text = if (!rootChecked)
                    getString(R.string.monitor_diag_checking)
                else if (rootFullProcessCount == null)
                    getString(R.string.monitor_diag_root_not_detected)
                else
                    getString(R.string.monitor_diag_root_detected, rootFullProcessCount ?: 0, (rootSandboxProcessCount ?: "?").toString())
                textSize = 11f
                setTextColor(context.kairosThemeColor(
                    if (rootChecked && rootFullProcessCount != null) R.attr.kairosGreen else R.attr.kairosText3
                ))
            })
        })
        diagnosticContainer.addView(rootRow)
    }

    // Detección real (pedido 2026-08-13): lee el setting global del phantom killer con
    // `settings get` (lectura no requiere permisos especiales) y el PowerManager para la
    // batería. Si algo está en el estado problemático, avisa con un Toast que recomienda
    // desactivarlo para evitar errores. Corre al abrir la pantalla.
    private fun detectSystemRestrictions() {
        Thread {
            val phantomOn = try {
                val pb = ProcessBuilder("settings", "get", "global", PhantomProcessKillerHelper.PHANTOM_SETTING_KEY)
                pb.redirectErrorStream(true)
                val p = pb.start()
                val out = p.inputStream.bufferedReader().readText().trim()
                p.waitFor()
                // "1" o "null" (no seteado = comportamiento por defecto activo) → killer activo.
                out == "1" || out == "null" || out.isEmpty()
            } catch (_: Exception) {
                true
            }
            val batteryOn = try {
                val pm = requireContext().getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
                if (pm == null) true else !pm.isIgnoringBatteryOptimizations(requireContext().packageName)
            } catch (_: Exception) {
                true
            }
            val sandboxProcCount = try {
                val p = ProcessBuilder("ps", "-A").start()
                val lines = p.inputStream.bufferedReader().readText().lines().filter { it.isNotBlank() }
                p.waitFor()
                (lines.size - 1).coerceAtLeast(0) // -1 por la fila de encabezado
            } catch (_: Exception) {
                null
            }
            val fullProcCount = if (com.termux.app.util.RootAccess.hasRoot()) {
                val r = com.termux.app.util.RootAccess.runAsRoot("ps -A")
                if (r.ok) r.stdout.lines().filter { it.isNotBlank() }.size.let { (it - 1).coerceAtLeast(0) } else null
            } else null
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                phantomKillerActive = phantomOn
                batteryOptimized = batteryOn
                restrictionsChecked = true
                rootChecked = true
                rootSandboxProcessCount = sandboxProcCount
                rootFullProcessCount = fullProcCount
                buildDiagnosticRows()
                val problems = buildList {
                    if (phantomKillerActive) add(getString(R.string.monitor_diag_problem_phantom))
                    if (batteryOptimized) add(getString(R.string.monitor_diag_problem_battery))
                }
                if (problems.isNotEmpty()) {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.monitor_diag_recommend_toast, problems.joinToString(" y ")),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    // (a) Primer intento, silencioso: solo funciona con root. Si el dispositivo está
    // rooteado, el usuario nunca ve ningún diálogo — mismo comportamiento que antes de
    // esta ronda.
    private fun disablePhantomProcessKiller() {
        Thread {
            val (ok, detail) = try {
                // Sin applyTermuxEnv() a propósito: "su" (si existe) vive fuera de
                // $PREFIX/bin, en el PATH del sistema/root manager (Magisk, etc.), no en
                // el de Termux — restringir el PATH acá lo dejaría sin encontrar.
                val pb = ProcessBuilder("su", "-c", phantomKillerCommand)
                val process = pb.start()
                val out = process.inputStream.bufferedReader().readText()
                val err = process.errorStream.bufferedReader().readText()
                val success = process.waitFor() == 0
                success to (if (success) "" else err.ifBlank { out })
            } catch (e: Exception) {
                false to (e.message ?: "sin acceso root")
            }
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                if (ok) {
                    phantomKillerFixedThisSession = true
                    Toast.makeText(requireContext(), getString(R.string.monitor_phantom_root_success), Toast.LENGTH_LONG).show()
                    buildDiagnosticRows()
                } else {
                    showPhantomKillerFixOptionsDialog(detail)
                }
            }
        }.start()
    }

    // Root falló (o no hay root) — ofrece las 3 vías reales que no necesitan PC: el
    // diálogo guiado de 3 pasos (b), la vía beta con auto-detección de puerto (b2), o el
    // tutorial 100% manual (c). AlertDialog no admite 4 acciones (3 botones + mensaje), así
    // que se arma una lista de opciones con vista custom en vez de setMessage+3 botones.
    private fun showPhantomKillerFixOptionsDialog(rootErrorDetail: String) {
        val ctx = requireContext()
        lateinit var dialog: AlertDialog
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), dp(4))
            addView(TextView(ctx).apply {
                text = getString(R.string.monitor_phantom_dialog_no_root) +
                    (if (rootErrorDetail.isNotBlank()) getString(R.string.monitor_phantom_dialog_detail_suffix, rootErrorDetail) else "")
                textSize = 13f
                setTextColor(ctx.kairosThemeColor(R.attr.kairosText2))
                setPadding(0, 0, 0, dp(14))
            })
            addView(phantomFixOptionRow(
                getString(R.string.monitor_phantom_option_auto_title),
                getString(R.string.monitor_phantom_option_auto_subtitle)
            ) { dialog.dismiss(); startGuidedFix() })
            addView(phantomFixOptionRow(
                getString(R.string.monitor_phantom_option_beta_title),
                getString(R.string.monitor_phantom_option_beta_subtitle)
            ) { dialog.dismiss(); startAutoDetectFix() })
            addView(phantomFixOptionRow(
                getString(R.string.monitor_phantom_option_manual_title),
                getString(R.string.monitor_phantom_option_manual_subtitle)
            ) { dialog.dismiss(); showManualTutorialDialog() })
        }
        dialog = AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.monitor_phantom_dialog_title_no_root))
            .setView(content)
            .setNegativeButton(getString(R.string.monitor_action_close), null)
            .create()
        dialog.show()
    }

    private fun phantomFixOptionRow(title: String, subtitle: String, onClick: () -> Unit): View {
        val ctx = requireContext()
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            isFocusable = true
            // Ripple táctil (pulido visual 2026-08-25, docs/estructura/ESTILO_VISUAL_2026-08-25.md).
            val outValue = android.util.TypedValue()
            ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
            if (outValue.resourceId != 0) setBackgroundResource(outValue.resourceId)
            setPadding(dp(4), dp(12), dp(4), dp(12))
            addView(TextView(ctx).apply {
                text = title
                textSize = 14f
                setTextColor(ctx.kairosThemeColor(R.attr.kairosBlue))
            })
            addView(TextView(ctx).apply {
                text = subtitle
                textSize = 11f
                setTextColor(ctx.kairosThemeColor(R.attr.kairosText3))
                setPadding(0, dp(2), 0, 0)
            })
            setOnClickListener { onClick() }
        }
    }

    // Compartido por la vía guiada (b) y la vía beta (b2) — delega en
    // PhantomProcessKillerHelper.ensureDeveloperOptionsThen(), promovido ahí cuando
    // WizardPhantomProcessFragment pasó a necesitar el mismo gate (ver docs/humano53.md).
    private fun ensureDeveloperOptionsThen(onReady: () -> Unit) {
        PhantomProcessKillerHelper.ensureDeveloperOptionsThen(requireActivity(), onReady)
    }

    // (b) Diálogo guiado — punto de entrada.
    private fun startGuidedFix() {
        ensureDeveloperOptionsThen { showGuidedFixStep1() }
    }

    private fun showGuidedFixStep1() {
        val ctx = requireContext()
        AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.monitor_guided_step1_title))
            .setMessage(getString(R.string.monitor_guided_step1_message))
            .setNeutralButton(getString(R.string.monitor_open_dev_options)) { _, _ ->
                PhantomProcessKillerHelper.openDeveloperOptions(requireActivity())
            }
            .setNegativeButton(getString(R.string.monitor_action_cancel), null)
            .setPositiveButton(getString(R.string.monitor_continue)) { _, _ -> showGuidedFixStep2() }
            .show()
    }

    private fun showGuidedFixStep2() {
        val ctx = requireContext()
        val pairingPortInput = EditText(ctx).apply {
            hint = getString(R.string.monitor_hint_pairing_port)
            inputType = InputType.TYPE_CLASS_NUMBER
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText))
        }
        val codeInput = EditText(ctx).apply {
            hint = getString(R.string.monitor_hint_code)
            inputType = InputType.TYPE_CLASS_NUMBER
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText))
        }
        val connectPortInput = EditText(ctx).apply {
            hint = getString(R.string.monitor_hint_connect_port)
            inputType = InputType.TYPE_CLASS_NUMBER
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText))
        }
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(0))
            addView(TextView(ctx).apply {
                text = getString(R.string.monitor_guided_step2_message)
                textSize = 12f
                setTextColor(ctx.kairosThemeColor(R.attr.kairosText3))
                setPadding(0, 0, 0, dp(12))
            })
            addView(pairingPortInput)
            addView(codeInput)
            addView(connectPortInput)
        }
        AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.monitor_guided_step2_title))
            .setView(container)
            .setNegativeButton(getString(R.string.monitor_action_cancel), null)
            .setPositiveButton(getString(R.string.monitor_apply_fix)) { _, _ ->
                val pairingPort = pairingPortInput.text.toString().trim()
                val code = codeInput.text.toString().trim()
                val connectPort = connectPortInput.text.toString().trim()
                val digitsOnly = Regex("^\\d+$")
                if (!digitsOnly.matches(pairingPort) || !digitsOnly.matches(code) || !digitsOnly.matches(connectPort)) {
                    Toast.makeText(ctx, getString(R.string.monitor_fill_3_fields), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                runGuidedFix(pairingPort, code, connectPort)
            }
            .show()
    }

    // (b) Paso 3 — corre PhantomProcessKillerHelper.runFix() mostrando el output en vivo
    // en un ProgressDialogController (mismo patrón que instalaciones de módulos).
    private fun runGuidedFix(pairingPort: String, code: String, connectPort: String) {
        val ctx = requireContext()
        val progress = ProgressDialogController(ctx)
        progress.show(getString(R.string.monitor_guided_step3_title), getString(R.string.monitor_running_adb))
        val log = StringBuilder()
        PhantomProcessKillerHelper.runFix(
            pairingPort, code, connectPort,
            onOutput = { line ->
                log.append(line).append('\n')
                if (!isAdded) return@runFix
                requireActivity().runOnUiThread {
                    if (isAdded) progress.update(line)
                }
            },
            onDone = { success ->
                if (!isAdded) return@runFix
                requireActivity().runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    if (success) {
                        phantomKillerFixedThisSession = true
                        progress.success(getString(R.string.monitor_phantom_fix_success), log.toString())
                        buildDiagnosticRows()
                    } else {
                        progress.failure(getString(R.string.monitor_phantom_fix_fail), log.toString())
                    }
                }
            }
        )
    }

    // (b2) Vía BETA — pieza adoptada de referencia/ciberseguridad/i-Haklab-master/phantom-ps (ver
    // docs/referencias/REFERENCIA_IHAKLAB.md): en vez de pedir el puerto de conexión a mano, lo detecta
    // con nmap. Punto de entrada: si nmap no está instalado, lo instala PRIMERO (mostrando
    // "instalando paquete necesario" antes de siquiera ofrecer abrir Depuración inalámbrica)
    // — pedido explícito del usuario, para que quede claro por qué tarda antes del paso 1.
    private fun startAutoDetectFix() {
        val ctx = requireContext()
        if (PhantomProcessKillerHelper.isNmapAvailable()) {
            ensureDeveloperOptionsThen { showAutoDetectStep1() }
            return
        }
        val progress = ProgressDialogController(ctx)
        progress.show(getString(R.string.monitor_autodetect_preparing_title), getString(R.string.monitor_autodetect_preparing_message))
        Thread {
            val ok = PhantomProcessKillerHelper.installNmap { line ->
                if (!isAdded) return@installNmap
                requireActivity().runOnUiThread { if (isAdded) progress.update(line) }
            }
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                if (ok) {
                    progress.dismiss()
                    ensureDeveloperOptionsThen { showAutoDetectStep1() }
                } else {
                    progress.failure(
                        getString(R.string.monitor_nmap_install_fail_title),
                        getString(R.string.monitor_nmap_install_fail_detail)
                    )
                }
            }
        }.start()
    }

    private fun showAutoDetectStep1() {
        val ctx = requireContext()
        AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.monitor_autodetect_step1_title))
            .setMessage(getString(R.string.monitor_autodetect_step1_message))
            .setNeutralButton(getString(R.string.monitor_open_dev_options)) { _, _ ->
                PhantomProcessKillerHelper.openDeveloperOptions(requireActivity())
            }
            .setNegativeButton(getString(R.string.monitor_action_cancel), null)
            .setPositiveButton(getString(R.string.monitor_continue)) { _, _ -> showAutoDetectStep2() }
            .show()
    }

    private fun showAutoDetectStep2() {
        val ctx = requireContext()
        val pairingPortInput = EditText(ctx).apply {
            hint = getString(R.string.monitor_hint_pairing_port)
            inputType = InputType.TYPE_CLASS_NUMBER
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText))
        }
        val codeInput = EditText(ctx).apply {
            hint = getString(R.string.monitor_hint_code)
            inputType = InputType.TYPE_CLASS_NUMBER
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText))
        }
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(0))
            addView(TextView(ctx).apply {
                text = getString(R.string.monitor_autodetect_step2_message)
                textSize = 12f
                setTextColor(ctx.kairosThemeColor(R.attr.kairosText3))
                setPadding(0, 0, 0, dp(12))
            })
            addView(pairingPortInput)
            addView(codeInput)
        }
        AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.monitor_guided_step2_title))
            .setView(container)
            .setNegativeButton(getString(R.string.monitor_action_cancel), null)
            .setPositiveButton(getString(R.string.monitor_apply_fix)) { _, _ ->
                val pairingPort = pairingPortInput.text.toString().trim()
                val code = codeInput.text.toString().trim()
                val digitsOnly = Regex("^\\d+$")
                if (!digitsOnly.matches(pairingPort) || !digitsOnly.matches(code)) {
                    Toast.makeText(ctx, getString(R.string.monitor_fill_2_fields), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                runAutoDetectFixFlow(pairingPort, code)
            }
            .show()
    }

    // (b2) Paso 3 — corre PhantomProcessKillerHelper.runAutoDetectFix(), mismo patrón de
    // ProgressDialogController que la vía guiada normal.
    private fun runAutoDetectFixFlow(pairingPort: String, code: String) {
        val ctx = requireContext()
        val progress = ProgressDialogController(ctx)
        progress.show(getString(R.string.monitor_autodetect_step3_title), getString(R.string.monitor_running_adb))
        val log = StringBuilder()
        PhantomProcessKillerHelper.runAutoDetectFix(
            pairingPort, code,
            onOutput = { line ->
                log.append(line).append('\n')
                if (!isAdded) return@runAutoDetectFix
                requireActivity().runOnUiThread {
                    if (isAdded) progress.update(line)
                }
            },
            onDone = { success ->
                if (!isAdded) return@runAutoDetectFix
                requireActivity().runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    if (success) {
                        phantomKillerFixedThisSession = true
                        progress.success(getString(R.string.monitor_phantom_fix_success), log.toString())
                        buildDiagnosticRows()
                    } else {
                        progress.failure(getString(R.string.monitor_phantom_fix_fail), log.toString())
                    }
                }
            }
        )
    }

    // (c) Tutorial manual — delega en PhantomProcessKillerHelper.showManualTutorialDialog(),
    // compartido con WizardPhantomProcessFragment (antes duplicado acá, ver docs/humano/humano56.md
    // — el tutorial ahora prioriza el toggle nativo "Desactivar restricciones de procesos
    // secundarios" sobre los comandos ADB).
    private fun showManualTutorialDialog() {
        PhantomProcessKillerHelper.showManualTutorialDialog(requireActivity())
    }

    // ── Dispositivo: RAM / almacenamiento / info (ex-SystemFragment.kt, fusionado acá
    // 2026-08-26 — ver docs/humano/humano225.md y siguientes, "que sea solo Monitor") ──

    /** Vistas de una card de anillo (RAM o almacenamiento) — misma estructura visual para
     * ambas, factorizada en `buildMetricCard()` para no duplicar el layout dos veces. */
    private data class MetricCardViews(
        val row: LinearLayout,
        val canvas: TextView,
        val line1: TextView,
        val line2: TextView,
        val line3: TextView
    )

    private fun buildMetricCard(accentColorAttr: Int): MetricCardViews {
        val ctx = requireContext()
        val holder = cardContainer()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        val canvas = TextView(ctx).apply {
            gravity = Gravity.CENTER
            text = "—"
            textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(ctx.kairosThemeColor(accentColorAttr))
            layoutParams = LinearLayout.LayoutParams(dp(100), dp(100))
        }
        val textColumn = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).also { it.marginStart = dp(16) }
        }
        val line1 = TextView(ctx).apply {
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText))
        }
        val line2 = TextView(ctx).apply {
            textSize = 12f
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText2))
            setPadding(0, dp(2), 0, 0)
        }
        val line3 = TextView(ctx).apply {
            textSize = 12f
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText3))
            setPadding(0, dp(2), 0, 0)
        }
        textColumn.addView(line1)
        textColumn.addView(line2)
        textColumn.addView(line3)
        row.addView(canvas)
        row.addView(textColumn)
        holder.addView(row)
        return MetricCardViews(row, canvas, line1, line2, line3)
    }

    /** Dibuja el anillo de progreso (arco de fondo + arco de porcentaje) sobre un bitmap y
     * lo pone de background del TextView central — compartido por RAM y almacenamiento. */
    private fun drawMetricArc(target: TextView, percent: Float, accentColorAttr: Int) {
        val ctx = requireContext()
        val bmp = android.graphics.Bitmap.createBitmap(dp(100), dp(100), android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val rect = RectF(dp(8).toFloat(), dp(8).toFloat(), dp(92).toFloat(), dp(92).toFloat())

        paint.color = ctx.kairosThemeColor(R.attr.kairosBg3)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(6).toFloat()
        canvas.drawArc(rect, -90f, 360f, false, paint)

        paint.color = ctx.kairosThemeColor(accentColorAttr)
        canvas.drawArc(rect, -90f, percent * 360f, false, paint)

        target.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        target.background = android.graphics.drawable.BitmapDrawable(resources, bmp)
    }

    private fun parseKb(line: String): Long {
        val numeric = line.replace(Regex("[^0-9]"), "")
        return if (numeric.isEmpty()) 0L else numeric.toLong()
    }

    private fun readRamInfo() {
        if (!isAdded) return
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
                    val usedKb = totalKb - availableKb
                    val percent = usedKb.toFloat() / totalKb
                    val usedMb = usedKb / 1024
                    val availMb = availableKb / 1024
                    val totalMb = totalKb / 1024
                    val pct = (percent * 100).toInt()

                    ramCanvas.text = "$pct%"
                    ramAvailable.text = getString(R.string.monitor_ram_available, availMb)
                    ramTotal.text = getString(R.string.monitor_ram_total, totalMb)
                    ramUsed.text = getString(R.string.monitor_ram_used, usedMb, pct)
                    drawMetricArc(ramCanvas, percent, R.attr.kairosGreen)
                }
            }
        } catch (_: Exception) {
            ramCanvas.text = "—"
        }
    }

    private fun readStorageInfo() {
        if (!isAdded) return
        if (!PermissionManager.hasStorage()) {
            storageCanvas.text = "—"
            storageAvailable.text = getString(R.string.monitor_storage_no_permission)
            storageTotal.text = getString(R.string.monitor_storage_tap_grant)
            storageUsed.visibility = View.GONE
            storageCardRow.setOnClickListener { runStorageSetup() }
            handler.removeCallbacks(storagePollRunnable)
            handler.postDelayed(storagePollRunnable, 2000)
            return
        }
        handler.removeCallbacks(storagePollRunnable)
        storageCardRow.setOnClickListener(null)
        storageUsed.visibility = View.VISIBLE
        try {
            val stat = StatFs(Environment.getDataDirectory().path)
            val total = stat.blockCountLong * stat.blockSizeLong
            val free = stat.availableBlocksLong * stat.blockSizeLong
            val used = total - free
            val pct = (used * 100 / total).toInt()
            val totalGb = total / (1024f * 1024f * 1024f)
            val freeGb = free / (1024f * 1024f * 1024f)
            val usedGb = used / (1024f * 1024f * 1024f)

            storageCanvas.text = "${pct}%"
            storageAvailable.text = getString(R.string.monitor_storage_available_gb, "%.1f".format(freeGb))
            storageTotal.text = getString(R.string.monitor_storage_total_used, "%.1f".format(totalGb), "%.1f".format(usedGb), pct)
            drawMetricArc(storageCanvas, used.toFloat() / total, R.attr.kairosBlue)
        } catch (_: Exception) {
            storageCanvas.text = "—"
            storageAvailable.text = getString(R.string.monitor_storage_read_error)
            storageTotal.text = "—"
            storageUsed.visibility = View.GONE
        }
    }

    // Bug real confirmado (ver docs/humano/humano63.md, auditoría de ProcessBuilder): esta
    // función nunca llamaba a applyTermuxEnv() — a diferencia del resto de la app, ni siquiera
    // intentaba setear PATH/PREFIX, así que "bash" (nombre relativo) fallaba SIEMPRE, no de
    // forma intermitente. Corregido para usar la ruta absoluta + el helper compartido, mismo
    // patrón que el resto de la app. Portado de SystemFragment.kt (eliminado 2026-08-26).
    private fun runStorageSetup() {
        val v = view ?: return
        Snackbar.make(v, getString(R.string.monitor_storage_setting_up), Snackbar.LENGTH_LONG).show()
        Thread {
            val result = try {
                val home = TermuxConstants.TERMUX_HOME_DIR_PATH
                val pb = ProcessBuilder(TERMUX_BASH_PATH, "-c", "$home/scripts/kairos.sh --setup storage")
                    .directory(File(home))
                pb.applyTermuxEnv()
                val proc = pb.start()
                val exitCode = proc.waitFor()
                val output = proc.inputStream.bufferedReader().readText()
                if (exitCode == 0) getString(R.string.monitor_storage_setup_ok) else getString(R.string.monitor_storage_setup_error, output)
            } catch (e: Exception) {
                getString(R.string.monitor_storage_setup_exception, e.message ?: "null")
            }
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                Snackbar.make(v, result, Snackbar.LENGTH_LONG).show()
                readStorageInfo()
            }
        }.start()
    }

    /**
     * IP/uptime/tasa de red — actualizados en CADA tick del polling de 5s (todas
     * llamadas síncronas y baratas: enumerar interfaces de red, leer /proc/uptime,
     * TrafficStats — ninguna spawnea un proceso externo, mismo criterio que
     * readRamInfo()/readStorageInfo() ya corriendo síncrono en el hilo principal).
     */
    private fun refreshDeviceLiveInfo() {
        if (!isAdded) return
        deviceIpValue.text = getLocalIp()
        deviceUptimeValue.text = readUptime()
        deviceNetworkValue.text = readNetworkRate()
    }

    /**
     * android.net.TrafficStats.getTotalRxBytes()/getTotalTxBytes() — contadores
     * acumulados desde el boot del dispositivo (no requieren permiso especial para el
     * total del dispositivo, a diferencia de UsageStatsManager/NetworkStatsManager por
     * uid). UNSUPPORTED indica que el kernel no expone /proc/net/xt_qtaguid ni la vía
     * moderna — se informa honestamente en vez de mostrar 0/0 como si fuera una tasa real.
     */
    private fun readNetworkRate(): String {
        val rx = TrafficStats.getTotalRxBytes()
        val tx = TrafficStats.getTotalTxBytes()
        if (rx == TrafficStats.UNSUPPORTED.toLong() || tx == TrafficStats.UNSUPPORTED.toLong()) {
            lastRxBytes = -1L
            return getString(R.string.monitor_network_rate_unavailable)
        }
        val now = System.currentTimeMillis()
        val result = if (lastNetSampleTime == 0L || lastRxBytes < 0L) {
            getString(R.string.monitor_network_rate_measuring)
        } else {
            val elapsedSec = ((now - lastNetSampleTime) / 1000.0).coerceAtLeast(0.5)
            val rxRate = ((rx - lastRxBytes) / elapsedSec).coerceAtLeast(0.0)
            val txRate = ((tx - lastTxBytes) / elapsedSec).coerceAtLeast(0.0)
            getString(R.string.monitor_network_rate_value, humanRate(rxRate), humanRate(txRate))
        }
        lastRxBytes = rx
        lastTxBytes = tx
        lastNetSampleTime = now
        return result
    }

    private fun humanRate(bytesPerSec: Double): String {
        var v = bytesPerSec
        for (unit in listOf("B/s", "KB/s", "MB/s")) {
            if (v < 1024) return "%.1f %s".format(v, unit)
            v /= 1024
        }
        return "%.1f GB/s".format(v)
    }

    /**
     * % de CPU real — delta de jiffies acumulados entre 2 lecturas de /proc/stat (línea
     * "cpu " agregada, todos los núcleos). No hay forma de leer "% de CPU ahora mismo"
     * de una sola muestra: los contadores son acumulados desde el boot, por eso la
     * primera llamada solo siembra la base (lastCpuTotal queda en 0L hasta entonces) y
     * recién la segunda (siguiente tick de polling, 5s después) puede mostrar un % real.
     */
    private fun readCpuInfo() {
        if (!isAdded) return
        try {
            BufferedReader(FileReader("/proc/stat")).use { reader ->
                val line = reader.readLine() ?: return
                if (!line.startsWith("cpu ")) return
                val fields = line.trim().split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
                if (fields.size < 4) return
                val idle = fields[3] + (fields.getOrNull(4) ?: 0L) // idle + iowait
                val total = fields.sum()
                if (lastCpuTotal > 0L) {
                    val totalDelta = total - lastCpuTotal
                    val idleDelta = idle - lastCpuIdle
                    if (totalDelta > 0) {
                        val usedFraction = ((totalDelta - idleDelta).toFloat() / totalDelta).coerceIn(0f, 1f)
                        val pct = (usedFraction * 100).toInt()
                        cpuCanvas.text = "$pct%"
                        cpuLine1.text = getString(R.string.monitor_cpu_usage, pct)
                        cpuLine2.text = getString(R.string.monitor_cpu_cores, Runtime.getRuntime().availableProcessors())
                        cpuLine3.text = readLoadAverage()
                        drawMetricArc(cpuCanvas, usedFraction, R.attr.kairosAmber)
                    }
                } else {
                    cpuLine1.text = getString(R.string.monitor_cpu_measuring)
                    cpuLine2.text = getString(R.string.monitor_cpu_cores, Runtime.getRuntime().availableProcessors())
                }
                lastCpuTotal = total
                lastCpuIdle = idle
            }
        } catch (_: Exception) {
            // Bug real confirmado por ADB (run-as com.termux cat /proc/stat -> "Permission
            // denied" en un Samsung SM-A566E/Android 16) — /proc/stat (y /proc/loadavg) no son
            // legibles por la propia app en este dispositivo, a diferencia de /proc/meminfo
            // (RAM, sí legible) — no es un bug intermitente, siempre falla acá. Antes este catch
            // reusaba por error el string de almacenamiento (monitor_storage_read_error, copy-
            // paste) mostrando "Error al leer almacenamiento" en la card de CPU — se corrige con
            // un string propio y honesto, mismo criterio ya usado por readNetworkRate() cuando
            // TrafficStats no está soportado.
            cpuCanvas.text = "—"
            cpuLine1.text = getString(R.string.monitor_cpu_unavailable)
        }
    }

    private fun readLoadAverage(): String = try {
        File("/proc/loadavg").readText().trim().split(" ").take(3).joinToString(" ")
    } catch (_: Exception) {
        ""
    }

    /**
     * Reusa EntornoNative.gpuDiagnostic() (ya usado por el diagnóstico GPU de Entorno) en
     * vez de duplicar getprop/glxinfo/vulkaninfo acá — una sola fuente de verdad para
     * "qué GPU tiene este dispositivo y qué renderer está activo". Se consulta una sola
     * vez al abrir la pantalla (ver comentario de gpuLoaded arriba), no en cada tick de
     * polling.
     *
     * Bug real confirmado por ADB (2026-08-29): gpuDiagnostic().renderer depende de
     * `glxinfo` (paquete `mesa-utils`, herramienta de X11/OpenGL de escritorio) — tiene
     * sentido para el diagnóstico de aceleración GPU de Entorno (ahí SÍ importa si hay un
     * renderer OpenGL corriendo dentro de Termux/proot), pero Monitor solo quiere mostrar
     * "qué chip GPU tiene este teléfono", un dato de HARDWARE que no depende de tener X11
     * instalado — la gran mayoría de instalaciones de Kairos nunca instalan mesa-utils, así
     * que este dato literal de "glxinfo no instalado (pkg install mesa-utils)" terminaba
     * siempre en pantalla, como si fuera un error, en vez de dato de dispositivo (ver
     * kairos-product-philosophy.md: no hace falta terminal/paquetes para info básica).
     * `gpu_type` (adreno/mali/xclipse, vía getprop — ver detectGpuType()) no depende de
     * ningún paquete de Termux, así que Monitor lo traduce a un nombre legible y solo suma
     * el renderer real de glxinfo cuando de verdad está disponible.
     */
    private fun refreshGpuInfo() {
        Thread {
            val gpuInfo = try { EntornoNative.gpuDiagnostic() } catch (_: Exception) { null }
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                gpuLoaded = true
                deviceGpuValue.text = if (gpuInfo != null && gpuInfo.optBoolean("ok", false)) {
                    val type = gpuInfo.optString("gpu_type", "unknown")
                    val label = when (type) {
                        "adreno" -> "Qualcomm Adreno"
                        "mali" -> "ARM Mali"
                        "xclipse" -> "Samsung Xclipse"
                        else -> null
                    }
                    val renderer = gpuInfo.optString("renderer", "")
                    val hasRealRenderer = renderer.isNotBlank() &&
                        !renderer.contains("no instalado") && renderer != "no detectado"
                    when {
                        label != null && hasRealRenderer -> getString(R.string.monitor_device_gpu_value, label, renderer)
                        label != null -> label
                        hasRealRenderer -> renderer
                        else -> getString(R.string.monitor_device_gpu_unavailable)
                    }
                } else {
                    getString(R.string.monitor_device_gpu_unavailable)
                }
            }
        }.start()
    }

    /**
     * Conteo de procesos VISIBLES para Kairos vía `ps -A` — desde Android 7 (hidepid),
     * una app sin root solo ve sus propios procesos en /proc, no los de todo el sistema
     * (mismo límite ya documentado en rootSandboxProcessCount/detectSystemRestrictions()
     * más abajo en este archivo). Se informa honesto como "procesos visibles", nunca como
     * "procesos del sistema" — no hay forma de dar el conteo real sin root.
     */
    private fun refreshProcessCount() {
        Thread {
            val count = try {
                val p = ProcessBuilder("ps", "-A").start()
                val lines = p.inputStream.bufferedReader().readText().lines().filter { it.isNotBlank() }
                p.waitFor()
                (lines.size - 1).coerceAtLeast(0) // -1 por la fila de encabezado
            } catch (_: Exception) {
                null
            }
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                deviceProcessesValue.text = count?.let { getString(R.string.monitor_device_processes_value, it) } ?: "—"
            }
        }.start()
    }

    private fun getLocalIp(): String {
        try {
            val interfaces = java.util.Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                val addrs = java.util.Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) return addr.hostAddress ?: "—"
                }
            }
        } catch (_: Exception) {}
        return "127.0.0.1"
    }

    private fun readUptime(): String {
        try {
            BufferedReader(FileReader("/proc/uptime")).use { reader ->
                val line = reader.readLine() ?: return "—"
                val seconds = line.split(" ").first().toDouble().toLong()
                val days = seconds / 86400
                val hours = (seconds % 86400) / 3600
                val minutes = (seconds % 3600) / 60
                return if (days > 0) "${days}d ${hours}h ${minutes}m"
                else "${hours}h ${minutes}m"
            }
        } catch (_: Exception) { return "—" }
    }

    // ── Helpers de UI (mismo lenguaje visual que ConfigFragment/BaseModuleFragment) ──

    private fun sectionTitle(text: String) {
        container.addView(TextView(requireContext()).apply {
            this.text = text
            textSize = 10f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            letterSpacing = 0.12f
            setTextColor(context.kairosThemeColor(R.attr.kairosText3))
            setPadding(dp(4), dp(16), dp(4), dp(8))
        })
    }

    private fun cardContainer(): LinearLayout {
        val ctx = requireContext()
        val card = MaterialCardView(ctx).apply {
            setCardBackgroundColor(ctx.kairosThemeColor(R.attr.kairosBg2))
            radius = dp(14).toFloat()
            strokeColor = ctx.kairosThemeColor(R.attr.kairosBorder)
            strokeWidth = dp(1)
            cardElevation = 0f
            setContentPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }
        val inner = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        card.addView(inner)
        container.addView(card)
        return inner
    }

    /**
     * Fila label+valor donde el valor se puede actualizar in-place (a diferencia de
     * statusRow(), que devuelve solo la View completa — acá hace falta el TextView del
     * valor por separado porque IP/uptime/red/GPU/procesos se refrescan cada tick de
     * polling sin reconstruir todo deviceInfoContainer). Se agrega directo al `holder`
     * pasado por parámetro y devuelve el TextView del valor.
     */
    private fun labeledRow(holder: LinearLayout, label: String, initialValue: String, valueColorAttr: Int): TextView {
        val ctx = requireContext()
        val valueView = TextView(ctx).apply {
            text = initialValue
            textSize = 12f
            gravity = Gravity.END
            setTypeface(android.graphics.Typeface.MONOSPACE)
            setTextColor(ctx.kairosThemeColor(valueColorAttr))
        }
        holder.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            addView(TextView(ctx).apply {
                text = label
                textSize = 13f
                setTextColor(ctx.kairosThemeColor(R.attr.kairosText))
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            })
            addView(valueView)
        })
        return valueView
    }

    private fun infoOnlyCard(initialText: String): TextView {
        val tv = TextView(requireContext()).apply {
            text = initialText
            textSize = 13f
            setTextColor(context.kairosThemeColor(R.attr.kairosText2))
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        val holder = cardContainer()
        holder.addView(tv)
        return tv
    }

    // iconRes opcional (pulido visual 2026-08-25, wireo de íconos vectoriales — ver
    // docs/estructura/ESTILO_VISUAL_2026-08-25.md): antepone un ImageView de 18dp al
    // label cuando la fila representa un estado real corriendo/detenido/instalado, sin
    // forzarlo en filas sin moduleId (sin ripple, fuera de alcance de esta ronda).
    private fun statusRow(label: String, value: String, valueColorRes: Int, moduleId: String? = null, iconRes: Int? = null): View {
        val ctx = requireContext()
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            if (iconRes != null) {
                addView(android.widget.ImageView(ctx).apply {
                    setImageResource(iconRes)
                    setColorFilter(ctx.kairosThemeColor(valueColorRes))
                    layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).also { it.marginEnd = dp(10) }
                })
            }
            addView(TextView(ctx).apply {
                text = label
                textSize = 13f
                setTextColor(ctx.kairosThemeColor(R.attr.kairosText))
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            })
            addView(TextView(ctx).apply {
                text = value
                textSize = 12f
                gravity = Gravity.END
                setTypeface(android.graphics.Typeface.MONOSPACE)
                setTextColor(ctx.kairosThemeColor(valueColorRes))
            })
            // Bug real encontrado por ADB (2026-08-25, ver docs/humano225.md y siguientes):
            // ninguna fila de esta lista era clickeable — "Remote (SSH)" en particular no tenía
            // NINGÚN camino real en toda la app hacia RemoteFragment.kt (ni la Tienda, que solo
            // abre el diálogo genérico Desactivar/Reinstalar/Desinstalar). Se agrega navegación
            // real a las filas con id conocido, reusando ModuleDetailNavigator (mismo mecanismo
            // que Módulos/Tienda) en vez de inventar un camino paralelo.
            if (moduleId != null) {
                isClickable = true
                isFocusable = true
                val outValue = android.util.TypedValue()
                ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
                if (outValue.resourceId != 0) setBackgroundResource(outValue.resourceId)
                setOnClickListener { openModuleDetail(moduleId) }
            }
        }
    }

    private fun openModuleDetail(moduleId: String) {
        Thread {
            val module = try {
                com.termux.app.data.ModuleCatalog.load(requireContext().applicationContext)
                    .firstOrNull { it.id == moduleId }
            } catch (_: Exception) {
                null
            }
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                if (module != null) {
                    ModuleDetailNavigator.navigate(parentFragmentManager, module)
                } else {
                    android.widget.Toast.makeText(requireContext(), getString(R.string.monitor_module_not_found), android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun dp(d: Int) = (d * resources.displayMetrics.density).toInt()
}

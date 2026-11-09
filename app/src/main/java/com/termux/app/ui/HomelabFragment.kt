package com.termux.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.cardview.widget.CardView
import androidx.fragment.app.Fragment
import com.google.android.material.card.MaterialCardView
import com.google.android.material.tabs.TabLayout
import com.termux.R
import com.termux.app.ModuleController
import com.termux.app.util.AddonEngine
import com.termux.app.util.HomelabAddons
import com.termux.app.util.HomelabManager
import com.termux.app.util.HomelabManager.HomelabService
import com.termux.app.util.HomelabManager.ServiceType
import com.termux.app.util.HomelabPanelPrefs
import com.termux.app.util.GatewayAccess
import com.termux.app.util.GatewayManager
import com.termux.app.util.HomelabServer
import com.termux.app.util.SystemMetrics
import com.termux.app.util.GatewayLink
import com.termux.app.util.RemoteManager
import com.termux.app.util.TERMUX_PREFIX_PATH
import com.termux.app.util.TunnelManager
import com.termux.app.util.applyTermuxEnv
import com.termux.app.util.kairosThemeColor
import com.termux.app.util.kairosThemeColorAlpha
import com.termux.app.util.setScreenSecure
import org.json.JSONArray

/**
 * Pantalla "Homelab" — panel de control + monitoreo real del dispositivo. Rediseño explícito del
 * usuario (2026-09-16, ver `docs/modulos/HOMELAB.md`): la versión anterior de esta pantalla era
 * solo una lista de accesos rápidos (indistinguible de Remote/SSH a simple vista) y
 * "Automatizaciones" vivía como un tile/tab propio y redundante dentro de "Más". Esta versión
 * agrega 3 pestañas:
 *
 * - **Panel**: métricas reales del sistema (CPU/RAM/almacenamiento/temperatura/batería/red/IP),
 *   módulos con proceso corriendo, y el panel web embebido ([HomelabServer]) para controlar el
 *   teléfono como un VPS/homelab desde cualquier navegador (local o por túnel Cloudflare).
 * - **Servicios**: el contenido que ya tenía esta pantalla — accesos a Remote/módulos con HTTP
 *   corriendo en el propio dispositivo, + servicios self-hosted EXTERNOS agregados a mano
 *   (Docker/Portainer, Pi-hole, Proxmox, etc.).
 * - **Automatizaciones**: [AutomationsFragment] embebido tal cual vía `childFragmentManager` —
 *   su motor real (`AutomationManager`/`AutomationScheduler`/`BroadcastReceiver`s) NO cambió,
 *   solo se dejó de exponer como destino de navegación de primer nivel (el usuario la consideró
 *   redundante ahí: "automatizar puede ir dentro de homelab").
 *
 * Diferenciación real de Remote/SSH (pedido explícito del usuario, para que no se sientan "lo
 * mismo"): Remote es acceso por TERMINAL (SSH entrante/saliente); Homelab es panel de CONTROL Y
 * MONITOREO (qué está pasando ahora mismo + prender/apagar cosas desde una UI, nativa o web) —
 * ver el subtítulo de esta pantalla y `homelab_device_remote` en la pestaña Servicios, que solo
 * ofrece un ACCESO RÁPIDO a Remote, no duplica su funcionalidad.
 *
 * 100% Kotlin nativo, sin bash. UI 100% programática (mismo criterio que el resto de pantallas de
 * gestión de la app: `MoreBottomSheetFragment`/partes de `RemoteFragment`).
 */
/** Cuántas muestras retienen [HomelabFragment.cpuHistory]/[HomelabFragment.ramHistory] —
 *  a 4s por muestra (ver [HomelabFragment.startPolling]), 30 puntos son ~2 minutos de tendencia,
 *  suficiente para ver una subida/bajada real sin acumular historial sin límite. */
private const val MAX_SPARKLINE_POINTS = 30

class HomelabFragment : Fragment() {

    companion object {
        /** Mismo orden en el spinner de agregar/editar y en el agrupado por categoría de la
         *  lista (ver refreshServices()) -- una sola fuente de verdad para no desincronizar. */
        private val SERVICE_TYPE_ORDER = listOf(
            ServiceType.DOCKER, ServiceType.PORTAINER, ServiceType.PIHOLE, ServiceType.ADGUARD,
            ServiceType.PROXMOX, ServiceType.GITEA, ServiceType.PLEX, ServiceType.BESZEL,
            ServiceType.HTTP, ServiceType.OTHER
        )
    }

    private lateinit var root: ScrollView
    private lateinit var rootFrame: FrameLayout
    private lateinit var addFab: TextView
    private val segmentViews = mutableListOf<TextView>()
    private lateinit var ringCpu: RingView
    private lateinit var sshStatus: TextView
    private lateinit var sshCommand: TextView
    private lateinit var sshToggleBtn: TextView
    private lateinit var sshCopyBtn: TextView
    private lateinit var ringRam: RingView
    private lateinit var ringStorage: RingView
    private lateinit var panelTabContent: LinearLayout
    private lateinit var gatewayTabContent: LinearLayout
    private lateinit var modulesTabContent: LinearLayout
    private lateinit var serviciosTabContent: LinearLayout
    private lateinit var automationsContainer: FrameLayout

    // ── Pestaña "Principal" — info de dispositivo + módulos categorizados (2026-10-30,
    //: "lo que esa abajo como ip, uptime, android, gpu, vulkan velocidad
    //    de red etc igual en homelab" + "lo que sten modulos, adaptado a homelab igual en su
    //    pestaña modulos" — MonitorFragment.kt se elimina, este es su reemplazo real). ───────
    private lateinit var deviceInfoIp: TextView
    private lateinit var deviceInfoUptime: TextView
    private lateinit var deviceInfoAndroid: TextView
    private lateinit var deviceInfoGpu: TextView
    private lateinit var deviceInfoVulkan: TextView
    private lateinit var deviceInfoNetRate: TextView
    private var gpuInfoLoaded = false
    private var lastRxBytes: Long = -1L
    private var lastTxBytes: Long = -1L
    private var lastNetSampleTime: Long = 0L

    private lateinit var modCategoriesContainer: LinearLayout
    private lateinit var gatewayQuickStatus: TextView

    private lateinit var deviceContainer: LinearLayout
    private lateinit var servicesContainer: LinearLayout
    private lateinit var servicesEmptyState: TextView

    // ── Pestaña "Panel" — métricas ───────────────────────────────────────────────────────────
    private lateinit var metricCpu: TextView
    private lateinit var metricRam: TextView
    private lateinit var metricStorage: TextView
    private lateinit var metricTemp: TextView
    private lateinit var metricBattery: TextView
    private lateinit var metricNetwork: TextView
    private lateinit var metricIp: TextView
    private lateinit var modulesRunningContainer: LinearLayout
    private lateinit var modulesRunningEmpty: TextView

    // ── Pestaña "Panel" — tendencia CPU/RAM (sparkline, mejora de esta ronda) ────────────────
    private lateinit var sparklineCpu: SparklineView
    private lateinit var sparklineRam: SparklineView
    private val cpuHistory = ArrayDeque<Int>()
    private val ramHistory = ArrayDeque<Int>()

    // ── Pestaña "Panel" — panel web (HomelabServer) ──────────────────────────────────────────
    private lateinit var webPanelStatus: TextView
    private lateinit var webPanelLocalRow: LinearLayout
    private lateinit var webPanelLocalUrl: TextView
    private lateinit var webPanelPublicRow: LinearLayout
    private lateinit var webPanelPublicUrl: TextView
    private lateinit var webPanelStartBtn: TextView
    private lateinit var webPanelStopBtn: TextView
    private lateinit var webPanelTunnelBtn: TextView
    private lateinit var webPanelStopTunnelBtn: TextView
    private lateinit var webPanelCopyBtn: TextView

    // ── Pestaña "Panel" — gateway (GatewayManager) ───────────────────────────────
    private lateinit var gatewayStatus: TextView
    private lateinit var gatewayStartBtn: TextView
    private lateinit var gatewayStopBtn: TextView
    private lateinit var gatewayPermsBtn: TextView
    private lateinit var gatewayLocalBtn: TextView
    private lateinit var gatewayLanBtn: TextView
    private lateinit var gatewayLanWarning: TextView
    private lateinit var gatewayLinkText: TextView
    private lateinit var gatewayOpenBtn: TextView
    private lateinit var gatewayCopyBtn: TextView
    private lateinit var gatewayTunnelBtn: TextView
    private var gatewayTunnelUrl = ""

    private val handler = Handler(Looper.getMainLooper())
    private var polling = false
    private var lastCpuSample: SystemMetrics.CpuSample? = null

    // Id generado en runtime (no un recurso de ids.xml — no existía ninguno en el proyecto y no
    // vale la pena crear el archivo por un solo id) para el contenedor donde se embebe
    // AutomationsFragment vía childFragmentManager (ver mountAutomationsChildFragment()).
    private val automationsContainerId = View.generateViewId()

    override fun onCreateView(inflater: LayoutInflater, c: ViewGroup?, b: Bundle?): View {
        val ctx = requireContext()
        root = ScrollView(ctx).apply {
            setBackgroundColor(ctx.kairosThemeColor(R.attr.kairosBg))
            isFillViewport = true
        }
        val screen = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(96))
        }
        root.addView(screen)

        screen.addView(sectionTitle(getString(R.string.homelab_title)))
        screen.addView(TextView(ctx).apply {
            text = getString(R.string.homelab_subtitle)
            textSize = 12f
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText3))
            setPadding(dp(4), 0, dp(4), dp(12))
        })

        // Control segmentado (boceto aprobado 2026-10-08, ui_mockups/homelab/homelab_propuesta.html) en
        // lugar del TabLayout: mismas 3 secciones (Panel / Servicios / Automatizaciones), más legible.
        val segmented = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            background = pill(ctx.kairosThemeColor(R.attr.kairosBg2), dp(12), ctx.kairosThemeColor(R.attr.kairosBorder))
            setPadding(dp(3), dp(3), dp(3), dp(3))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).also { it.bottomMargin = dp(10) }
        }
        listOf(R.string.homelab_tab_principal, R.string.homelab_tab_gateway, R.string.homelab_tab_modulos, R.string.homelab_tab_servicios, R.string.homelab_tab_automatizaciones)
            .forEachIndexed { index, labelRes ->
                val seg = TextView(ctx).apply {
                    text = getString(labelRes)
                    textSize = 13f
                    gravity = Gravity.CENTER
                    maxLines = 1
                    setPadding(dp(4), dp(9), dp(4), dp(9))
                    layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                    setOnClickListener { selectHomelabTab(index) }
                }
                segmentViews.add(seg)
                segmented.addView(seg)
            }
        screen.addView(segmented)

        panelTabContent = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        gatewayTabContent = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        modulesTabContent = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        serviciosTabContent = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        automationsContainer = FrameLayout(ctx).apply {
            id = automationsContainerId
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            visibility = View.GONE
        }
        screen.addView(panelTabContent)
        screen.addView(gatewayTabContent)
        screen.addView(modulesTabContent)
        screen.addView(serviciosTabContent)
        screen.addView(automationsContainer)

        // Botón flotante "+ Agregar servicio" (boceto aprobado): solo en la sección Servicios.
        addFab = TextView(ctx).apply {
            text = getString(R.string.homelab_fab_agregar)
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = pill(ctx.kairosThemeColor(R.attr.kairosBlue), dp(26), null)
            elevation = dp(6).toFloat()
            setPadding(dp(18), dp(14), dp(18), dp(14))
            visibility = View.GONE
            setOnClickListener { showAddServiceDialog() }
        }

        buildPanelSection()
        buildGatewaySection()
        buildDeviceSection()
        buildAddonsSection()
        buildServicesSection()
        selectHomelabTab(0)

        rootFrame = FrameLayout(ctx).apply {
            addView(root, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(addFab, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).also {
                it.setMargins(dp(16), dp(16), dp(16), dp(20))
            })
        }
        return rootFrame
    }

    /** Muestra la sección [position] (0 Principal, 1 Gateway, 2 Módulos, 3 Servicios,
     *  4 Automatizaciones) y resalta su segmento. */
    private fun selectHomelabTab(position: Int) {
        val ctx = requireContext()
        panelTabContent.visibility = if (position == 0) View.VISIBLE else View.GONE
        gatewayTabContent.visibility = if (position == 1) View.VISIBLE else View.GONE
        modulesTabContent.visibility = if (position == 2) View.VISIBLE else View.GONE
        serviciosTabContent.visibility = if (position == 3) View.VISIBLE else View.GONE
        automationsContainer.visibility = if (position == 4) View.VISIBLE else View.GONE
        addFab.visibility = if (position == 3) View.VISIBLE else View.GONE
        segmentViews.forEachIndexed { i, seg ->
            val selected = i == position
            seg.background = if (selected) pill(ctx.kairosThemeColorAlpha(R.attr.kairosGreen, 46), dp(10), null) else null
            seg.setTextColor(ctx.kairosThemeColor(if (selected) R.attr.kairosGreen else R.attr.kairosText3))
            seg.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    private fun pill(fill: Int, radiusPx: Int, stroke: Int?) = android.graphics.drawable.GradientDrawable().apply {
        setColor(fill)
        cornerRadius = radiusPx.toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }

    // FLAG_SECURE (hallazgo de auditoría de referencia, 2026-09-15 — ver
    // com.termux.app.util.setScreenSecure para el porqué es por-pantalla y no global): esta
    // pantalla tiene diálogos que tipean tokens de servicios (showReplaceSecretDialog más abajo)
    // y muestra el token del panel web en texto — bloquea screenshots/grabación de pantalla
    // mientras Homelab está en foreground. onResume/onPause también controlan el polling de
    // métricas (pedido explícito: "cada 3-5s mientras la pantalla está visible, parar en
    // onPause()") — TermuxActivity usa add()+hide()/show() para los tabs, así que onPause()
    // real solo dispara cuando la Activity entera pasa a background, no al cambiar de tab.
    override fun onResume() {
        super.onResume()
        setScreenSecure(true)
        startPolling()
        refreshSsh()
    }

    override fun onPause() {
        super.onPause()
        setScreenSecure(false)
        polling = false
        handler.removeCallbacksAndMessages(null)
    }

    // Bug real encontrado 2026-11-02 (ver reporte del usuario "sigue sin
    // dejar tirar capture"): el comentario de arriba YA documentaba que hide()/show() no
    // dispara onPause() real al cambiar de tab — pero esa nota solo se aplicó al polling, no
    // a setScreenSecure(false). Consecuencia real: visitar Homelab UNA sola vez dejaba
    // FLAG_SECURE prendido para TODA la app (es un flag de Window, no de Fragment) hasta que
    // la Activity entera pasara a background — bloqueando capturas/grabación en cualquier
    // otra pantalla, indefinidamente. onHiddenChanged() SÍ dispara con hide()/show() (a
    // diferencia de onResume/onPause) — acá se replica el mismo par set/clear.
    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) {
            setScreenSecure(false)
            polling = false
            handler.removeCallbacksAndMessages(null)
        } else if (isResumed) {
            setScreenSecure(true)
            startPolling()
            refreshSsh()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Anti-tapjacking (mismo criterio que NubeFragment/RemoteFragment, ver
        // .claude/rules/kairos-secrets-never-revealed.md): esta pantalla puede guardar tokens de
        // servicios agregados por el usuario y muestra el token del panel web.
        view.filterTouchesWhenObscured = true

        mountAutomationsChildFragment()

        refreshDevice()
        refreshAddons()
        refreshServices()
        refreshPanelMetrics()
        refreshWebPanelStatus()
        refreshGatewayStatus()
        refreshDeviceInfoSection()
        refreshGatewayQuickStatus()
        refreshModuleCategories()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        polling = false
        handler.removeCallbacksAndMessages(null)
    }

    private fun startPolling() {
        if (polling) return
        polling = true
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (!polling || !isAdded) return
                refreshPanelMetrics()
                refreshDeviceInfoSection()
                refreshGatewayQuickStatus()
                refreshModuleCategories()
                handler.postDelayed(this, 4000)
            }
        }, 4000)
    }

    // ── Pestaña "Automatizaciones" — AutomationsFragment embebido tal cual ──────────────────

    private fun mountAutomationsChildFragment() {
        if (childFragmentManager.findFragmentByTag("automations_child") == null) {
            childFragmentManager.beginTransaction()
                .add(automationsContainerId, AutomationsFragment(), "automations_child")
                .commit()
        }
    }

    // ── Pestaña "Panel" — métricas del sistema ───────────────────────────────────────────────

    private fun buildPanelSection() {
        val ctx = requireContext()

        // Grilla de métricas — 2026-09-16, bug real confirmado en dispositivo (uiautomator dump
        // tras QA de esta ronda): android.widget.GridLayout con columnSpec de peso
        // (GridLayout.spec(UNDEFINED, 1f)) sin alignment=FILL explícito distribuye el ancho mal
        // en la práctica — una columna colapsaba a ~1px y la fila entera se estiraba para llenar
        // todo el alto restante del ScrollView, tapando CPU/Batería/Red/IP y las secciones
        // "Módulos corriendo"/"Panel web" enteras (nunca llegaban a estar en pantalla, aunque el
        // código sí las agregaba). Reemplazado por filas de LinearLayout con layout_weight=1 —
        // mismo patrón robusto que el resto del archivo ya usa (buttonsRow/tunnelRow más abajo),
        // sin depender de la distribución de peso de GridLayout.
        val metricsContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).also { it.bottomMargin = dp(12) }
        }
        // Anillos de progreso para CPU / RAM / almacenamiento y chips para temperatura, batería, red e IP
        // (boceto aprobado 2026-10-08). Los TextView metricX siguen siendo los que refreshPanelMetrics() rellena.
        val rings = metricRow(ctx)
        metricCpu = addRingTile(rings, getString(R.string.homelab_panel_cpu)) { ringCpu = it }
        metricRam = addRingTile(rings, getString(R.string.homelab_panel_ram)) { ringRam = it }
        metricStorage = addRingTile(rings, getString(R.string.homelab_panel_storage)) { ringStorage = it }
        metricsContainer.addView(rings)

        val chips1 = metricRow(ctx)
        metricTemp = addChip(chips1, getString(R.string.homelab_panel_temp))
        metricBattery = addChip(chips1, getString(R.string.homelab_panel_battery))
        metricsContainer.addView(chips1)
        val chips2 = metricRow(ctx)
        metricNetwork = addChip(chips2, getString(R.string.homelab_panel_network))
        metricIp = addChip(chips2, getString(R.string.homelab_panel_ip))
        metricsContainer.addView(chips2)
        panelTabContent.addView(metricsContainer)

        buildTrendSection()
        buildDeviceInfoSection()
        buildGatewayQuickToggleSection()

        panelTabContent.addView(sectionLabel(getString(R.string.homelab_panel_section_modulos)))
        val modulesCard = cardOf()
        modulesRunningContainer = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        modulesRunningEmpty = TextView(ctx).apply {
            text = getString(R.string.homelab_panel_modulos_vacio)
            textSize = 12f
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText3))
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        modulesCard.addView(modulesRunningContainer)
        panelTabContent.addView(modulesCard)

        buildSshSection()
        buildWebPanelSection()
        buildModuleCategoriesSection()
    }

    /** Info de dispositivo (IP/red ya están arriba como chips) — uptime, versión de Android, GPU,
     *  Vulkan y velocidad de red real, portado desde MonitorFragment.kt (pantalla eliminada
     * 2026-10-30,: "lo que esa abajo como ip, uptime, android, gpu,
     *  vulkan velocidad de red etc igual en homelab"). */
    private fun buildDeviceInfoSection() {
        val ctx = requireContext()
        panelTabContent.addView(sectionLabel(getString(R.string.homelab_panel_section_dispositivo)))
        val card = cardOf()
        val holder = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        deviceInfoUptime = deviceInfoRow(holder, getString(R.string.monitor_device_uptime))
        deviceInfoAndroid = deviceInfoRow(holder, getString(R.string.monitor_device_android))
        deviceInfoAndroid.text = getString(R.string.monitor_device_android_value, Build.VERSION.SDK_INT)
        deviceInfoGpu = deviceInfoRow(holder, getString(R.string.monitor_device_gpu))
        deviceInfoVulkan = deviceInfoRow(holder, getString(R.string.monitor_device_vulkan))
        deviceInfoVulkan.text = readVulkanSupport()
        deviceInfoNetRate = deviceInfoRow(holder, getString(R.string.monitor_device_network_rate))
        card.addView(holder)
        panelTabContent.addView(card)
    }

    private fun deviceInfoRow(holder: LinearLayout, label: String): TextView {
        val ctx = requireContext()
        val value = TextView(ctx).apply {
            text = getString(R.string.monitor_loading)
            textSize = 12f
            gravity = Gravity.END
            typeface = Typeface.MONOSPACE
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText))
        }
        holder.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            addView(TextView(ctx).apply {
                text = label; textSize = 13f; setTextColor(ctx.kairosThemeColor(R.attr.kairosText3))
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            })
            addView(value)
        })
        return value
    }

    /** Soporte/versión real de Vulkan vía PackageManager — copia exacta de
     *  `MonitorFragment.readVulkanSupport()` (dato de hardware, independiente de Termux). */
    private fun readVulkanSupport(): String {
        val pm = requireContext().packageManager
        val supported = pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL)
        if (!supported) return getString(R.string.monitor_device_vulkan_unsupported)
        val rawVersion = pm.systemAvailableFeatures.firstOrNull { it.name == "android.hardware.vulkan.version" }?.version ?: 0
        if (rawVersion <= 0) return getString(R.string.monitor_device_vulkan_supported)
        val major = (rawVersion shr 22) and 0x3FF
        val minor = (rawVersion shr 12) and 0x3FF
        val patch = rawVersion and 0xFFF
        return getString(R.string.monitor_device_vulkan_version, major, minor, patch)
    }

    private fun refreshDeviceInfoSection() {
        if (!isAdded) return
        deviceInfoUptime.text = readUptime()
        deviceInfoNetRate.text = readNetworkRate()
        if (!gpuInfoLoaded) {
            gpuInfoLoaded = true
            Thread {
                val gpuInfo = try { com.termux.app.util.EntornoNative.gpuDiagnostic() } catch (_: Exception) { null }
                if (!isAdded) return@Thread
                requireActivity().runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    deviceInfoGpu.text = if (gpuInfo != null && gpuInfo.optBoolean("ok", false)) {
                        val type = gpuInfo.optString("gpu_type", "unknown")
                        val label = when (type) { "adreno" -> "Qualcomm Adreno"; "mali" -> "ARM Mali"; "xclipse" -> "Samsung Xclipse"; else -> null }
                        val renderer = gpuInfo.optString("renderer", "")
                        val hasRealRenderer = renderer.isNotBlank() && !renderer.contains("no instalado") && renderer != "no detectado"
                        when {
                            label != null && hasRealRenderer -> getString(R.string.monitor_device_gpu_value, label, renderer)
                            label != null -> label
                            hasRealRenderer -> renderer
                            else -> getString(R.string.monitor_device_gpu_unavailable)
                        }
                    } else getString(R.string.monitor_device_gpu_unavailable)
                }
            }.start()
        }
    }

    /** Mismo formato que tenía MonitorFragment.readUptime() ("Nd Nh Nm"), pero vía
     *  SystemClock.elapsedRealtime() (incluye deep sleep, no requiere leer /proc/uptime —
     *  mismo tipo de restricción que ya rompió la lectura de /proc/stat en algunos
     *  dispositivos, ver readCpuInfo() de la pantalla eliminada). */
    private fun readUptime(): String {
        val millis = android.os.SystemClock.elapsedRealtime()
        val totalMinutes = millis / 60_000
        val days = totalMinutes / 1440
        val hours = (totalMinutes % 1440) / 60
        val minutes = totalMinutes % 60
        return if (days > 0) "${days}d ${hours}h ${minutes}m" else "${hours}h ${minutes}m"
    }

    /** Copia exacta de `MonitorFragment.readNetworkRate()` (delta de TrafficStats entre 2 muestras). */
    private fun readNetworkRate(): String {
        val rx = android.net.TrafficStats.getTotalRxBytes()
        val tx = android.net.TrafficStats.getTotalTxBytes()
        if (rx == android.net.TrafficStats.UNSUPPORTED.toLong() || tx == android.net.TrafficStats.UNSUPPORTED.toLong()) {
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
        lastRxBytes = rx; lastTxBytes = tx; lastNetSampleTime = now
        return result
    }

    private fun humanRate(bytesPerSec: Double): String {
        var v = bytesPerSec
        for (unit in listOf("B/s", "KB/s", "MB/s")) { if (v < 1024) return "%.1f %s".format(v, unit); v /= 1024 }
        return "%.1f GB/s".format(v)
    }

    /** Switch rápido de encendido/apagado del Gateway en "Principal" — la configuración
     *  completa (token, red, túnel) vive en la pestaña "Gateway" (buildGatewaySection()).
     *  Pedido explícito del usuario: "en la principal estar el boton para encender o apaar
     *  gateway pero que en la pestaña gateway se configure el token, red etc". */
    private fun buildGatewayQuickToggleSection() {
        val ctx = requireContext()
        panelTabContent.addView(sectionLabel(getString(R.string.homelab_gateway_quick_label)))
        val card = cardOf()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        gatewayQuickStatus = TextView(ctx).apply {
            text = getString(R.string.monitor_loading)
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText))
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        }
        row.addView(gatewayQuickStatus)
        row.addView(smallButton(getString(R.string.homelab_gateway_btn_start), true) { startGateway() })
        row.addView(smallButton(getString(R.string.homelab_gateway_btn_stop), true) { stopGateway() })
        card.addView(row)
        panelTabContent.addView(card)
    }

    private fun refreshGatewayQuickStatus() {
        runInBackground({ GatewayManager.isHealthy() }) { running ->
            if (!::gatewayQuickStatus.isInitialized) return@runInBackground
            gatewayQuickStatus.text = getString(if (running) R.string.homelab_gateway_status_running_short else R.string.homelab_gateway_status_stopped)
            gatewayQuickStatus.setTextColor(requireContext().kairosThemeColor(if (running) R.attr.kairosGreen else R.attr.kairosText3))
        }
    }

    // ── Pestaña "Panel" — acceso SSH para controlar Termux desde otro equipo ─────────────────
    // Pedido explícito del usuario: "desde homelab debe sacar ssh para controlar
    // termux" (y más adelante, librerías del SDK para controlar Android por completo por SSH — eso NO
    // está hecho). Reusa el servidor SSH que ya existe: arrancar/detener pasa por
    // ModuleController ("remote", mismo camino que su pantalla) y el estado/comando por
    // RemoteManager.info(). Claves, contraseña y puerto se gestionan en la pantalla Remote ("Opciones").

    private fun buildSshSection() {
        val ctx = requireContext()
        panelTabContent.addView(sectionLabel(getString(R.string.homelab_ssh_section)))
        val card = cardOf()
        val inner = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        inner.addView(TextView(ctx).apply {
            text = getString(R.string.homelab_ssh_desc)
            textSize = 12f
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText2))
        })
        sshStatus = TextView(ctx).apply {
            text = getString(R.string.monitor_loading)
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText3))
            setPadding(0, dp(8), 0, dp(4))
        }
        inner.addView(sshStatus)
        sshCommand = TextView(ctx).apply {
            textSize = 12.5f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText))
            background = pill(ctx.kairosThemeColor(R.attr.kairosBg3), dp(8), null)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            visibility = View.GONE
        }
        inner.addView(sshCommand)
        val buttons = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }
        sshToggleBtn = smallButton(getString(R.string.homelab_ssh_btn_start), true) { toggleSsh() }
        sshCopyBtn = smallButton(getString(R.string.homelab_ssh_btn_copy), true) { copySshCommand() }
        val optionsBtn = smallButton(getString(R.string.homelab_ssh_btn_options), true) { navigateTo(RemoteFragment()) }
        listOf(sshToggleBtn, sshCopyBtn, optionsBtn).forEachIndexed { i, b ->
            b.layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).also { if (i > 0) it.marginStart = dp(8) }
            buttons.addView(b)
        }
        inner.addView(buttons)
        card.addView(inner)
        panelTabContent.addView(card)
    }

    private var sshRunning = false

    private fun refreshSsh() {
        if (!isAdded) return
        val appContext = requireContext().applicationContext
        Thread {
            val info = try { com.termux.app.util.RemoteManager.info(appContext) } catch (_: Exception) { null }
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                val running = info?.sshRunning == true
                sshRunning = running
                sshStatus.text = if (running) getString(R.string.homelab_ssh_status_on, info?.sshPort ?: 8022, info?.connections ?: 0)
                else getString(R.string.homelab_ssh_status_off)
                sshStatus.setTextColor(requireContext().kairosThemeColor(if (running) R.attr.kairosGreen else R.attr.kairosText3))
                sshCommand.text = info?.connectCmd.orEmpty()
                sshCommand.visibility = if (running && !info?.connectCmd.isNullOrBlank()) View.VISIBLE else View.GONE
                sshToggleBtn.text = getString(if (running) R.string.homelab_ssh_btn_stop else R.string.homelab_ssh_btn_start)
                setChipEnabled(sshCopyBtn, running)
            }
        }.start()
    }

    private fun toggleSsh() {
        val appContext = requireContext().applicationContext
        if (sshRunning) {
            ModuleController.stopModule("remote", appContext) { ok ->
                toast(getString(if (ok) R.string.homelab_ssh_toast_stopped else R.string.homelab_ssh_toast_stop_failed))
                refreshSsh()
            }
        } else {
            toast(getString(R.string.homelab_ssh_toast_starting))
            ModuleController.startModule("remote", appContext) { ok, message ->
                toast(if (ok) getString(R.string.homelab_ssh_toast_started) else getString(R.string.homelab_ssh_toast_start_failed, message))
                refreshSsh()
            }
        }
    }

    private fun copySshCommand() {
        val text = sshCommand.text.toString()
        if (text.isBlank()) return
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("ssh", text))
        toast(getString(R.string.homelab_ssh_toast_copied))
    }

    /** Fila horizontal para 2 tiles de métrica lado a lado (ver comentario en buildPanelSection
     *  de por qué esto reemplazó a GridLayout). */
    private fun metricRow(ctx: android.content.Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
    }

    /** Tile con anillo de progreso arriba, etiqueta y detalle abajo. Devuelve el TextView del detalle. */
    private fun addRingTile(parent: LinearLayout, label: String, onRing: (RingView) -> Unit): TextView {
        val ctx = requireContext()
        val card = MaterialCardView(ctx).apply {
            setCardBackgroundColor(ctx.kairosThemeColor(R.attr.kairosBg2))
            radius = resources.getDimension(R.dimen.kairos_stroke_default)
            strokeColor = ctx.kairosThemeColor(R.attr.kairosBorder)
            strokeWidth = dp(1)
            cardElevation = 0f
        }
        val inner = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(8), dp(12), dp(8), dp(10))
        }
        val ring = RingView(ctx).also(onRing)
        inner.addView(ring, LinearLayout.LayoutParams(dp(64), dp(64)))
        inner.addView(TextView(ctx).apply {
            text = label
            textSize = 10.5f
            gravity = Gravity.CENTER
            letterSpacing = 0.05f
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText3))
            setPadding(0, dp(6), 0, 0)
        })
        val detail = TextView(ctx).apply {
            text = getString(R.string.monitor_loading)
            textSize = 10.5f
            gravity = Gravity.CENTER
            minLines = 2
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText2))
            setPadding(0, dp(2), 0, 0)
        }
        inner.addView(detail)
        card.addView(inner)
        parent.addView(card, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).also { it.setMargins(dp(4), dp(4), dp(4), dp(4)) })
        return detail
    }

    /** Chip "etiqueta  valor" (temperatura, batería, red, IP). Devuelve el TextView del valor. */
    private fun addChip(parent: LinearLayout, label: String): TextView {
        val ctx = requireContext()
        val chip = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = pill(ctx.kairosThemeColor(R.attr.kairosBg2), dp(20), ctx.kairosThemeColor(R.attr.kairosBorder))
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        chip.addView(TextView(ctx).apply {
            text = label
            textSize = 10.5f
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText3))
        })
        val value = TextView(ctx).apply {
            text = getString(R.string.monitor_loading)
            textSize = 12.5f
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText))
            setPadding(dp(8), 0, 0, 0)
        }
        chip.addView(value)
        parent.addView(chip, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).also { it.setMargins(dp(4), dp(4), dp(4), dp(4)) })
        return value
    }

    private fun addMetricTile(parent: LinearLayout, label: String, fullWidth: Boolean = false): TextView {
        val ctx = requireContext()
        val card = MaterialCardView(ctx).apply {
            setCardBackgroundColor(ctx.kairosThemeColor(R.attr.kairosBg2))
            radius = resources.getDimension(R.dimen.kairos_stroke_default)
            strokeColor = ctx.kairosThemeColor(R.attr.kairosBorder)
            strokeWidth = dp(1)
            cardElevation = 0f
        }
        val params = if (fullWidth) {
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).also { it.setMargins(dp(4), dp(4), dp(4), dp(4)) }
        } else {
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).also { it.setMargins(dp(4), dp(4), dp(4), dp(4)) }
        }
        val inner = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        inner.addView(TextView(ctx).apply {
            text = label
            textSize = 10.5f
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText3))
            letterSpacing = 0.05f
        })
        val valueView = TextView(ctx).apply {
            text = getString(R.string.monitor_loading)
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText))
            setPadding(0, dp(4), 0, 0)
        }
        inner.addView(valueView)
        card.addView(inner)
        parent.addView(card, params)
        return valueView
    }

    /** Tarjeta "Tendencia" — 2 sparklines (CPU/RAM) debajo del grid de métricas instantáneas,
     *  mejora explícita de esta ronda (ver comentario de [MAX_SPARKLINE_POINTS]). */
    private fun buildTrendSection() {
        val ctx = requireContext()
        panelTabContent.addView(sectionLabel(getString(R.string.homelab_panel_section_tendencia)))
        val card = cardOf()
        val inner = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(6))
        }
        sparklineCpu = addSparklineRow(inner, getString(R.string.homelab_panel_cpu), ctx.kairosThemeColor(R.attr.kairosBlue))
        sparklineRam = addSparklineRow(inner, getString(R.string.homelab_panel_ram), ctx.kairosThemeColor(R.attr.kairosGreen))
        card.addView(inner)
        panelTabContent.addView(card)
    }

    private fun addSparklineRow(container: LinearLayout, label: String, color: Int): SparklineView {
        val ctx = requireContext()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(10))
        }
        row.addView(TextView(ctx).apply {
            text = label
            textSize = 10.5f
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText3))
            letterSpacing = 0.05f
        })
        val spark = SparklineView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(36)).also { it.topMargin = dp(4) }
            setLineColor(color)
        }
        row.addView(spark)
        container.addView(row)
        return spark
    }

    /** Empuja una muestra nueva al final de [history] y descarta la más vieja una vez superado
     *  [MAX_SPARKLINE_POINTS] — `null` (lectura fallida, ej. sin permiso de almacenamiento para
     *  RAM en algún dispositivo) se ignora en vez de graficar un 0 falso que arruinaría la escala. */
    private fun pushSample(history: ArrayDeque<Int>, value: Int?) {
        if (value == null) return
        history.addLast(value)
        while (history.size > MAX_SPARKLINE_POINTS) history.removeFirst()
    }

    private fun refreshPanelMetrics() {
        if (!isAdded) return
        val ctx = requireContext().applicationContext
        Thread {
            val ram = try { SystemMetrics.readRam() } catch (_: Exception) { null }
            val storage = try { SystemMetrics.readStorage() } catch (_: Exception) { null }
            val sample = try { SystemMetrics.sampleCpu() } catch (_: Exception) { null }
            val cpuPct = SystemMetrics.cpuPercent(lastCpuSample, sample)
            lastCpuSample = sample
            val tempC = try { SystemMetrics.batteryTemperatureC(ctx) } catch (_: Exception) { null }
            val batteryPct = try { SystemMetrics.batteryLevel(ctx) } catch (_: Exception) { null }
            val net = try { SystemMetrics.networkInfo(ctx) } catch (_: Exception) { null }
            val ip = try { SystemMetrics.localIpAddress() } catch (_: Exception) { null }
            val modules = try { SystemMetrics.moduleStates(ctx) } catch (_: Exception) { emptyList() }
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                ringCpu.setPercent(cpuPct)
                // Android no deja leer /proc/stat a las apps normales (el % de CPU puede salir "—"): el
                // anillo lo dice con honestidad y debajo va un dato real, el número de núcleos.
                metricCpu.text = getString(R.string.homelab_panel_cpu_cores, Runtime.getRuntime().availableProcessors())
                ringRam.setPercent(ram?.percent)
                ringStorage.setPercent(storage?.takeIf { it.hasPermission }?.percent)
                metricRam.text = ram?.let { getString(R.string.homelab_panel_ram_value, it.usedMb.toInt(), it.totalMb.toInt(), it.percent) } ?: "—"
                metricStorage.text = storage?.takeIf { it.hasPermission }
                    ?.let { getString(R.string.homelab_panel_storage_value, it.usedGb, it.totalGb, it.percent) } ?: "—"
                metricTemp.text = tempC?.let { getString(R.string.homelab_panel_temp_value, it) } ?: "—"
                metricBattery.text = batteryPct?.let { getString(R.string.homelab_panel_battery_value, it) } ?: "—"
                metricNetwork.text = networkLabel(net)
                metricIp.text = ip ?: "—"

                pushSample(cpuHistory, cpuPct)
                pushSample(ramHistory, ram?.percent)
                sparklineCpu.setData(cpuHistory.toList())
                sparklineRam.setData(ramHistory.toList())

                modulesRunningContainer.removeAllViews()
                val running = modules.filter { it.running }
                if (running.isEmpty()) {
                    modulesRunningContainer.addView(modulesRunningEmpty)
                } else {
                    running.forEach { m -> modulesRunningContainer.addView(moduleRunningRow(m.id, m.name)) }
                }
            }
        }.start()
    }

    /** Fila de solo lectura para "Módulos corriendo" — a diferencia de [deviceRow] (pestaña
     *  Servicios, con botón "Abrir") esta no navega a ningún lado: abrir el módulo real vive en
     *  Módulos/Monitor, este panel es solo un vistazo (mismo criterio que la sección "Módulos
     *  corriendo" de MonitorFragment, que tampoco navega desde la fila). */
    private fun moduleRunningRow(id: String, name: String): View {
        val ctx = requireContext()
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            addView(View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).also { it.marginEnd = dp(10) }
                setBackgroundColor(ctx.kairosThemeColor(R.attr.kairosGreen))
            })
            addView(TextView(ctx).apply {
                text = name
                textSize = 13f
                setTextColor(ctx.kairosThemeColor(R.attr.kairosText))
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            })
            addView(TextView(ctx).apply {
                text = getString(R.string.homelab_status_activo)
                textSize = 11f
                setTextColor(ctx.kairosThemeColor(R.attr.kairosGreen))
                layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).also { it.marginEnd = dp(10) }
            })
            addView(smallButton(getString(R.string.homelab_btn_detener), true) {
                confirmStopRunningModule(id, name)
            })
        }
    }

    private fun confirmStopRunningModule(id: String, name: String) {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.homelab_stop_module_title, name))
            .setMessage(R.string.homelab_stop_module_message)
            .setPositiveButton(R.string.homelab_btn_detener) { _, _ ->
                val appContext = requireContext().applicationContext
                ModuleController.stopModule(id, appContext) { stopped ->
                    handler.post {
                        if (!isAdded) return@post
                        toast(if (stopped) getString(R.string.homelab_stop_module_done, name) else getString(R.string.homelab_stop_module_failed, name))
                        refreshPanelMetrics()
                    }
                }
            }
            .setNegativeButton(R.string.homelab_btn_cancelar, null)
            .show()
    }

    private fun networkLabel(net: SystemMetrics.NetworkInfo?): String {
        if (net == null) return "—"
        val typeLabel = when (net.type) {
            "wifi" -> getString(R.string.homelab_panel_network_wifi)
            "datos_moviles" -> getString(R.string.homelab_panel_network_datos_moviles)
            "ethernet" -> getString(R.string.homelab_panel_network_ethernet)
            "sin_conexion" -> return getString(R.string.homelab_panel_network_sin_conexion)
            else -> getString(R.string.homelab_panel_network_otro)
        }
        val suffix = if (net.internetOk) getString(R.string.homelab_panel_network_con_internet) else getString(R.string.homelab_panel_network_sin_internet)
        return typeLabel + suffix
    }

    // ── Pestaña "Panel" — panel web (HomelabServer) ──────────────────────────────────────────

    private fun buildWebPanelSection() {
        val ctx = requireContext()
        panelTabContent.addView(sectionLabel(getString(R.string.homelab_section_panel_web)))
        val card = cardOf()
        val inner = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(14))
        }
        inner.addView(TextView(ctx).apply {
            text = getString(R.string.homelab_panel_web_desc)
            textSize = 11.5f
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText3))
            setPadding(0, 0, 0, dp(10))
        })
        webPanelStatus = TextView(ctx).apply {
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText))
        }
        inner.addView(webPanelStatus)

        webPanelLocalRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, dp(6), 0, 0)
        }
        webPanelLocalRow.addView(TextView(ctx).apply {
            text = getString(R.string.homelab_webpanel_url_local)
            textSize = 10.5f
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText3))
        })
        webPanelLocalUrl = TextView(ctx).apply {
            textSize = 12f
            setTypeface(Typeface.MONOSPACE)
            setTextColor(ctx.kairosThemeColor(R.attr.kairosBlue))
            setTextIsSelectable(true)
        }
        webPanelLocalRow.addView(webPanelLocalUrl)
        inner.addView(webPanelLocalRow)

        webPanelPublicRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, dp(6), 0, 0)
        }
        webPanelPublicRow.addView(TextView(ctx).apply {
            text = getString(R.string.homelab_webpanel_url_publica)
            textSize = 10.5f
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText3))
        })
        webPanelPublicUrl = TextView(ctx).apply {
            textSize = 12f
            setTypeface(Typeface.MONOSPACE)
            setTextColor(ctx.kairosThemeColor(R.attr.kairosGreen))
            setTextIsSelectable(true)
        }
        webPanelPublicRow.addView(webPanelPublicUrl)
        inner.addView(webPanelPublicRow)

        val buttonsRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }
        webPanelStartBtn = smallButton(getString(R.string.homelab_webpanel_btn_iniciar), true) { startWebPanel() }
        webPanelStopBtn = smallButton(getString(R.string.homelab_webpanel_btn_detener), true) { stopWebPanel() }
        webPanelCopyBtn = smallButton(getString(R.string.homelab_webpanel_btn_copiar), true) { copyWebPanelUrl() }
        buttonsRow.addView(webPanelStartBtn)
        buttonsRow.addView(webPanelStopBtn)
        buttonsRow.addView(webPanelCopyBtn)
        inner.addView(buttonsRow)

        val tunnelRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        webPanelTunnelBtn = smallButton(getString(R.string.homelab_webpanel_btn_cloudflare), true) { startTunnel() }
        webPanelStopTunnelBtn = smallButton(getString(R.string.homelab_webpanel_btn_detener_tunel), true) { stopTunnel() }
        tunnelRow.addView(webPanelTunnelBtn)
        tunnelRow.addView(webPanelStopTunnelBtn)
        inner.addView(tunnelRow)

        // Switch "iniciar al arrancar" — ítem #6 de docs/referencias/homelab/AUDITORIA_homelab_2026-10-09.md
        // (idea de Sshd4a-dev, código propio — ver HomelabBootReceiver.kt/HomelabPanelPrefs.kt).
        val autoStartRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, 0)
        }
        autoStartRow.addView(TextView(ctx).apply {
            text = getString(R.string.homelab_webpanel_autostart_label)
            textSize = 12f
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText2))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        autoStartRow.addView(androidx.appcompat.widget.SwitchCompat(ctx).apply {
            isChecked = HomelabPanelPrefs.isAutoStartEnabled(ctx)
            setOnCheckedChangeListener { toggle, checked ->
                if (!toggle.isPressed) return@setOnCheckedChangeListener
                HomelabPanelPrefs.setAutoStartEnabled(ctx, checked)
            }
        })
        inner.addView(autoStartRow)

        card.addView(inner)
        panelTabContent.addView(card)
    }

    private fun startWebPanel() {
        val appContext = requireContext().applicationContext
        runInBackground({ HomelabServer.start(context = appContext) }) { ok ->
            toast(if (ok) getString(R.string.homelab_msg_servidor_iniciado) else getString(R.string.homelab_msg_no_se_pudo_iniciar_puerto, HomelabServer.port))
            refreshWebPanelStatus()
        }
    }

    private fun stopWebPanel() {
        runInBackground({ HomelabServer.stop() }) {
            toast(getString(R.string.homelab_msg_servidor_detenido))
            webPanelPublicRow.visibility = View.GONE
            refreshWebPanelStatus()
        }
    }

    private fun refreshWebPanelStatus() {
        if (!isAdded) return
        val running = HomelabServer.isRunning
        webPanelStatus.text = if (running) getString(R.string.homelab_webpanel_status_activo, HomelabServer.port) else getString(R.string.homelab_webpanel_status_detenido)
        setChipEnabled(webPanelStartBtn, !running)
        setChipEnabled(webPanelStopBtn, running)
        setChipEnabled(webPanelTunnelBtn, running)
        setChipEnabled(webPanelCopyBtn, running)
        if (running) {
            val ip = HomelabServer.localIpAddress() ?: "127.0.0.1"
            webPanelLocalRow.visibility = View.VISIBLE
            webPanelLocalUrl.text = "http://$ip:${HomelabServer.port}/?token=${HomelabServer.token}"
        } else {
            webPanelLocalRow.visibility = View.GONE
            webPanelPublicRow.visibility = View.GONE
        }
    }

    // ── Módulos por categoría — motores IA, CLI sin proceso, bases de datos, escritorio,
    //    procesos pm2. Portado desde la pestaña "Módulos" de MonitorFragment.kt (pantalla
    // eliminada 2026-10-30,) — misma fuente de verdad
    //    (ModuleController.isRunning), estilo visual propio de Homelab en vez de los
    //    statusRow() con ícono que tenía Monitor. ───────────────────────────────────────────

    private lateinit var aiEnginesCard: LinearLayout
    private lateinit var cliToolsCard: LinearLayout
    private lateinit var dbCard: LinearLayout
    private lateinit var desktopCard: LinearLayout
    private lateinit var pm2Card: LinearLayout

    // Pestaña propia "Módulos" (2026-10-31,: "en homelab la panalla princpial
    // quedo mu cargda, tca quitar eso q mta las ases de daos, herramientas, moteres ia et eso
    // debe ir en su propia pesaña") — antes vivía dentro de "Principal", ahora en
    // [modulesTabContent] para que Principal quede liviana.
    private fun buildModuleCategoriesSection() {
        val ctx = requireContext()
        modulesTabContent.addView(sectionLabel(getString(R.string.homelab_section_ai_engines)))
        aiEnginesCard = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        cardOf().also { it.addView(aiEnginesCard); modulesTabContent.addView(it) }

        modulesTabContent.addView(sectionLabel(getString(R.string.homelab_section_cli_tools)))
        cliToolsCard = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        cardOf().also { it.addView(cliToolsCard); modulesTabContent.addView(it) }

        modulesTabContent.addView(sectionLabel(getString(R.string.homelab_section_databases)))
        dbCard = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        cardOf().also { it.addView(dbCard); modulesTabContent.addView(it) }

        modulesTabContent.addView(sectionLabel(getString(R.string.homelab_section_desktop)))
        desktopCard = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        cardOf().also { it.addView(desktopCard); modulesTabContent.addView(it) }

        modulesTabContent.addView(sectionLabel(getString(R.string.homelab_section_pm2)))
        pm2Card = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        cardOf().also { it.addView(pm2Card); modulesTabContent.addView(it) }
    }

    private fun categoryRow(label: String, value: String, colorAttr: Int): View {
        val ctx = requireContext()
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            addView(TextView(ctx).apply {
                text = label; textSize = 13f; setTextColor(ctx.kairosThemeColor(R.attr.kairosText))
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            })
            addView(TextView(ctx).apply {
                text = value; textSize = 12f; typeface = Typeface.MONOSPACE
                setTextColor(ctx.kairosThemeColor(colorAttr))
            })
        }
    }

    private fun refreshModuleCategories() {
        if (!isAdded) return
        val ctx = requireContext().applicationContext
        Thread {
            // Motores IA — misma fuente de verdad que la fila "módulos corriendo" de arriba
            // (ModuleController.isRunning), agrupados aparte por ser el foco real de un panel
            // de homelab (ver comentario equivalente, ex-MonitorFragment.kt).
            val aiEngines = listOf("ollama" to getString(R.string.monitor_module_ollama), "llamaserver" to getString(R.string.monitor_engine_llamaserver))
                .map { (id, name) -> Triple(id, name, try { ModuleController.isRunning(id) } catch (_: Exception) { false }) }
            val cliTools = listOf("hermes" to getString(R.string.monitor_cli_hermes), "engram" to getString(R.string.monitor_cli_engram))
                .map { (id, name) -> Triple(id, name, try { com.termux.app.data.ModuleInstalled.isInstalled(ctx, id) } catch (_: Exception) { false }) }
            val mysqlRunning = try { com.termux.app.util.ManagerNativeUtils.checkPort(3306) } catch (_: Exception) { false }
            val postgresRunning = try { com.termux.app.util.ManagerNativeUtils.checkPort(5432) } catch (_: Exception) { false }
            val sqliteAvailable = try { com.termux.app.util.isTermuxBinaryAvailable("sqlite3") } catch (_: Exception) { false }
            val entornoStatus = try { com.termux.app.util.EntornoNative.status() } catch (_: Exception) { null }
            val pm2Procs = try { readPm2ProcessesSync() } catch (_: Exception) { null }
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                aiEnginesCard.removeAllViews()
                aiEngines.forEach { (_, name, running) ->
                    aiEnginesCard.addView(categoryRow(name,
                        getString(if (running) R.string.monitor_status_running else R.string.monitor_status_stopped),
                        if (running) R.attr.kairosGreen else R.attr.kairosText3))
                }
                cliToolsCard.removeAllViews()
                cliTools.forEach { (_, name, installed) ->
                    cliToolsCard.addView(categoryRow(name,
                        getString(if (installed) R.string.monitor_installed_check else R.string.monitor_not_installed_dash),
                        if (installed) R.attr.kairosGreen else R.attr.kairosText3))
                }
                dbCard.removeAllViews()
                dbCard.addView(categoryRow(getString(R.string.monitor_db_mysql), getString(if (mysqlRunning) R.string.monitor_status_running else R.string.monitor_status_stopped), if (mysqlRunning) R.attr.kairosGreen else R.attr.kairosText3))
                dbCard.addView(categoryRow(getString(R.string.monitor_db_postgres), getString(if (postgresRunning) R.string.monitor_status_running else R.string.monitor_status_stopped), if (postgresRunning) R.attr.kairosGreen else R.attr.kairosText3))
                dbCard.addView(categoryRow(getString(R.string.monitor_db_sqlite), getString(if (sqliteAvailable) R.string.monitor_db_available else R.string.monitor_db_not_installed), if (sqliteAvailable) R.attr.kairosGreen else R.attr.kairosText3))
                desktopCard.removeAllViews()
                if (entornoStatus != null && entornoStatus.optBoolean("ok", false)) {
                    val x11Running = entornoStatus.optBoolean("x11_running", false)
                    desktopCard.addView(categoryRow(getString(R.string.monitor_desktop_x11_server),
                        getString(if (x11Running) R.string.monitor_status_running else R.string.monitor_status_stopped),
                        if (x11Running) R.attr.kairosGreen else R.attr.kairosText3))
                } else {
                    desktopCard.addView(categoryRow(getString(R.string.monitor_desktop_x11_server), getString(R.string.monitor_desktop_x11_unavailable), R.attr.kairosText3))
                }
                pm2Card.removeAllViews()
                if (pm2Procs.isNullOrEmpty()) {
                    pm2Card.addView(categoryRow(getString(R.string.monitor_pm2_no_processes), "", R.attr.kairosText3))
                } else {
                    pm2Procs.forEach { (name, status) ->
                        val running = status == "online"
                        pm2Card.addView(categoryRow(name, status, if (running) R.attr.kairosGreen else R.attr.kairosText3))
                    }
                }
            }
        }.start()
    }

    /** `pm2 jlist` síncrono (corre en el Thread de [refreshModuleCategories], no en el hilo
     *  principal) — mismo extractJsonArray()/parseo tolerante que tenía MonitorFragment, pero
     *  solo lectura (acciones de stop/restart/delete quedan en la terminal/pm2 directo, fuera
     *  de alcance de este panel de resumen). */
    private fun readPm2ProcessesSync(): List<Pair<String, String>>? {
        val pb = ProcessBuilder("$TERMUX_PREFIX_PATH/bin/pm2", "jlist")
        pb.applyTermuxEnv()
        val process = pb.start()
        val out = process.inputStream.bufferedReader().readText()
        process.waitFor()
        val start = out.indexOf('['); val end = out.lastIndexOf(']')
        if (start == -1 || end == -1 || end < start) return null
        val arr = JSONArray(out.substring(start, end + 1))
        return (0 until arr.length()).map { i ->
            val raw = arr.getJSONObject(i)
            val env = raw.optJSONObject("pm2_env") ?: org.json.JSONObject()
            raw.optString("name", "?") to env.optString("status", "unknown")
        }
    }

    // ── Gateway — puerta con permisos de Homelab (native/gateway, ver docs/homelab/GATEWAY_GO.md) ──

    private fun buildGatewaySection() {
        val ctx = requireContext()
        gatewayTabContent.addView(sectionLabel(getString(R.string.homelab_gateway_label)))
        val card = cardOf()
        val inner = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(14))
        }
        inner.addView(TextView(ctx).apply {
            text = getString(R.string.homelab_gateway_desc)
            textSize = 11.5f
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText3))
            setPadding(0, 0, 0, dp(10))
        })
        gatewayStatus = TextView(ctx).apply {
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText))
        }
        inner.addView(gatewayStatus)

        val buttons = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, 0) }
        gatewayStartBtn = smallButton(getString(R.string.homelab_gateway_btn_start), true) { startGateway() }
        gatewayStopBtn = smallButton(getString(R.string.homelab_gateway_btn_stop), true) { stopGateway() }
        gatewayPermsBtn = smallButton(getString(R.string.homelab_gateway_btn_permissions), true) {
            GatewayPermissionsDialog.show(requireActivity())
        }
        buttons.addView(gatewayStartBtn)
        buttons.addView(gatewayStopBtn)
        buttons.addView(gatewayPermsBtn)
        inner.addView(buttons)

        val access = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        gatewayLocalBtn = smallButton(getString(R.string.homelab_gateway_access_localhost), true) { changeGatewayAccess(GatewayAccess.LOCALHOST) }
        gatewayLanBtn = smallButton(getString(R.string.homelab_gateway_access_lan), true) { changeGatewayAccess(GatewayAccess.LAN) }
        access.addView(gatewayLocalBtn)
        access.addView(gatewayLanBtn)
        inner.addView(access)
        gatewayLanWarning = TextView(ctx).apply {
            text = getString(R.string.homelab_gateway_lan_warning)
            textSize = 11f
            setTextColor(ctx.kairosThemeColor(R.attr.kairosAmber))
            setPadding(0, dp(6), 0, 0)
            visibility = View.GONE
        }
        inner.addView(gatewayLanWarning)

        gatewayLinkText = TextView(ctx).apply {
            textSize = 12f
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText2))
            setPadding(0, dp(12), 0, dp(2))
            setTextIsSelectable(true)
        }
        inner.addView(gatewayLinkText)
        val link = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        gatewayOpenBtn = smallButton(getString(R.string.homelab_gateway_btn_open), true) { openGatewayInBrowser() }
        gatewayCopyBtn = smallButton(getString(R.string.homelab_gateway_btn_copy_link), true) { copyGatewayLink() }
        link.addView(gatewayOpenBtn)
        link.addView(gatewayCopyBtn)
        inner.addView(link)
        val reach = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        gatewayTunnelBtn = smallButton(getString(R.string.homelab_gateway_btn_tunnel), true) { toggleGatewayTunnel() }
        reach.addView(gatewayTunnelBtn)
        reach.addView(smallButton(getString(R.string.homelab_gateway_btn_address), true) { askGatewayAddress() })
        inner.addView(reach)
        card.addView(inner)
        gatewayTabContent.addView(card)
    }

    private fun gatewayPrefs() = requireContext().getSharedPreferences("kairos_gateway_prefs", Context.MODE_PRIVATE)

    /** Enlace vigente (dirección manual > túnel > red local > este teléfono). Nunca lleva token. */
    private fun gatewayLink(): String {
        val lan = GatewayManager.files.access == GatewayAccess.LAN
        val ip = if (lan) RemoteManager.getLocalIp(requireContext()) else ""
        return GatewayLink.resolve(gatewayPrefs().getString("link_custom", "").orEmpty(), gatewayTunnelUrl, lan, ip, GatewayManager.PORT)
    }

    private fun openGatewayInBrowser() {
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(gatewayLink()))
        try { startActivity(intent) } catch (_: Exception) { toast(getString(R.string.homelab_gateway_no_browser)) }
    }

    private fun copyGatewayLink() {
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("kairos_gateway_url", gatewayLink()))
        toast(getString(R.string.homelab_msg_url_copiada))
    }

    /** IP o dominio propio (por ejemplo el de un túnel que ya tienes). Vacío = volver a la automática. */
    private fun askGatewayAddress() {
        val input = EditText(requireContext()).apply {
            hint = getString(R.string.homelab_gateway_address_hint)
            setSingleLine()
            setText(gatewayPrefs().getString("link_custom", "").orEmpty())
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.homelab_gateway_btn_address)
            .setView(input)
            .setPositiveButton(R.string.gateway_save) { _, _ ->
                gatewayPrefs().edit().putString("link_custom", GatewayLink.normalizeCustom(input.text.toString(), GatewayManager.PORT)).apply()
                refreshGatewayStatus()
            }
            .setNegativeButton(R.string.gateway_close, null)
            .show()
    }

    /** Túnel hacia el puerto del gateway. Con el túnel activo, la administración sigue bloqueada (cabeceras de reenvío). */
    private fun toggleGatewayTunnel() {
        val port = GatewayManager.PORT
        runInBackground({ TunnelManager.status(port).running }) { running ->
            if (running) {
                runInBackground({ TunnelManager.stop(port) }) {
                    gatewayTunnelUrl = ""
                    toast(getString(R.string.homelab_msg_tunel_detenido))
                    refreshGatewayStatus()
                }
            } else {
                chooseTunnelProvider { provider -> startGatewayTunnel(provider) }
            }
        }
    }

    private fun chooseTunnelProvider(onChosen: (String) -> Unit) {
        val providers = listOf("cloudflared", "ngrok")
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.homelab_gateway_btn_tunnel)
            .setItems(arrayOf("Cloudflare", "ngrok")) { _, which -> onChosen(providers[which]) }
            .show()
    }

    private fun startGatewayTunnel(provider: String) {
        val config = TunnelManager.getConfig(provider, "")
        if (provider == "ngrok" && config.token.isBlank()) {
            toast(getString(R.string.homelab_gateway_ngrok_needs_token))
            return
        }
        toast(getString(R.string.homelab_msg_iniciando_tunel, provider))
        val nativeLibDir = requireContext().applicationInfo.nativeLibraryDir
        val token = config.token.ifBlank { null }
        val domain = config.domain.ifBlank { null }
        runInBackground({ TunnelManager.start(GatewayManager.PORT, provider, token, domain, nativeLibDir = nativeLibDir) }) { result ->
            if (result.ok) pollGatewayTunnel(1) else toast(getString(R.string.homelab_error_fmt, result.error))
        }
    }

    private fun pollGatewayTunnel(attempt: Int) {
        if (!isAdded) return
        if (attempt > 8) { toast(getString(R.string.homelab_msg_tunel_sin_url)); return }
        runInBackground({ TunnelManager.status(GatewayManager.PORT) }) { status ->
            if (!isAdded) return@runInBackground
            when {
                status.running && status.url.isNotEmpty() -> { gatewayTunnelUrl = status.url; refreshGatewayStatus() }
                status.running -> handler.postDelayed({ pollGatewayTunnel(attempt + 1) }, 2000)
            }
        }
    }

    // GatewayForegroundService (2026-10-16, ver meta 7: "que Kairos quede
    // corriendo en segundo plano siempre") protege el proceso del gateway con una notificación
    // foreground — sin tocar GatewayManager.start()/stop() en sí (siguen siendo la lógica real),
    // solo envuelve su ciclo de vida para que Android no mate el proceso en background. Arrancar
    // primero vía GatewayManager (acá) da el resultado real inmediato para el toast; el Service
    // luego confirma con isHealthy() antes de reintentar arrancar (es idempotente, no lo duplica).
    private fun startGateway() {
        val appContext = requireContext().applicationContext
        runInBackground({ GatewayManager.start(appContext) }) { result ->
            if (result.ok) com.termux.app.GatewayForegroundService.start(appContext)
            toast(if (result.ok) getString(R.string.homelab_gateway_toast_started) else getString(R.string.homelab_gateway_toast_failed, result.message))
            refreshGatewayStatus()
        }
    }

    private fun stopGateway() {
        com.termux.app.GatewayForegroundService.stop(requireContext().applicationContext)
        runInBackground({ GatewayManager.stop() }) {
            toast(getString(R.string.homelab_gateway_toast_stopped))
            refreshGatewayStatus()
        }
    }

    private fun changeGatewayAccess(access: GatewayAccess) {
        val appContext = requireContext().applicationContext
        runInBackground({ GatewayManager.restartWithAccess(appContext, access) }) { result ->
            if (!result.ok) toast(getString(R.string.homelab_gateway_toast_failed, result.message))
            refreshGatewayStatus()
        }
    }

    /** Lee el estado real (salud por HTTP) en segundo plano y actualiza la tarjeta. */
    private fun refreshGatewayStatus() {
        runInBackground({ GatewayManager.isHealthy() }) { running ->
            val access = GatewayManager.files.access
            gatewayStatus.text = if (running) {
                val where = getString(if (access == GatewayAccess.LAN) R.string.homelab_gateway_access_lan else R.string.homelab_gateway_access_localhost)
                getString(R.string.homelab_gateway_status_running, GatewayManager.PORT, where)
            } else getString(R.string.homelab_gateway_status_stopped)
            setChipEnabled(gatewayStartBtn, !running)
            setChipEnabled(gatewayStopBtn, running)
            setChipEnabled(gatewayPermsBtn, running)
            gatewayLocalBtn.alpha = if (access == GatewayAccess.LOCALHOST) 1f else 0.5f
            gatewayLanBtn.alpha = if (access == GatewayAccess.LAN) 1f else 0.5f
            gatewayLanWarning.visibility = if (access == GatewayAccess.LAN) View.VISIBLE else View.GONE
            gatewayLinkText.text = getString(R.string.homelab_gateway_link, gatewayLink())
            setChipEnabled(gatewayOpenBtn, running)
            setChipEnabled(gatewayCopyBtn, running)
        }
    }

    private fun setChipEnabled(chip: TextView, enabled: Boolean) {
        chip.isEnabled = enabled
        chip.alpha = if (enabled) 1f else 0.4f
    }

    // ── Túnel — reusa TunnelManager tal cual (mismo patrón que NubeFragment), apuntado al
    //    puerto de HomelabServer en vez de un módulo. ────────────────────────────────────────

    private fun startTunnel() {
        toast(getString(R.string.homelab_msg_iniciando_tunel, "cloudflared"))
        val nativeLibDir = requireContext().applicationInfo.nativeLibraryDir
        runInBackground({ TunnelManager.start(HomelabServer.port, "cloudflared", null, nativeLibDir = nativeLibDir) }) { result ->
            if (result.ok) pollTunnelStatus(attempt = 1) else toast(getString(R.string.homelab_error_fmt, result.error))
        }
    }

    private fun stopTunnel() {
        runInBackground({ TunnelManager.stop(HomelabServer.port) }) { result ->
            toast(if (result.ok) getString(R.string.homelab_msg_tunel_detenido) else getString(R.string.homelab_error_fmt, result.error))
            webPanelPublicRow.visibility = View.GONE
        }
    }

    private fun pollTunnelStatus(attempt: Int = 0) {
        if (!isAdded) return
        if (attempt > 7) {
            toast(getString(R.string.homelab_msg_tunel_sin_url))
            return
        }
        runInBackground({ TunnelManager.status(HomelabServer.port) }) { status ->
            if (!isAdded) return@runInBackground
            if (status.running && status.url.isNotEmpty()) {
                webPanelPublicRow.visibility = View.VISIBLE
                webPanelPublicUrl.text = "${status.url}?token=${HomelabServer.token}"
            } else if (status.running) {
                handler.postDelayed({ pollTunnelStatus(attempt + 1) }, 2000)
            } else {
                webPanelPublicRow.visibility = View.GONE
            }
        }
    }

    private fun copyWebPanelUrl() {
        if (!HomelabServer.isRunning) return
        val url = if (webPanelPublicRow.visibility == View.VISIBLE) webPanelPublicUrl.text.toString() else webPanelLocalUrl.text.toString()
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("homelab_url", url))
        toast(getString(R.string.homelab_msg_url_copiada))
    }

    // ── Pestaña "Servicios" — "Este dispositivo" — Remote/SSH + módulos con servicio HTTP
    //    corriendo ahora ────────────────────────────────────────────────────────────────────

    private fun buildDeviceSection() {
        serviciosTabContent.addView(sectionLabel(getString(R.string.homelab_section_este_dispositivo)))
        val card = MaterialCardView(requireContext()).apply {
            setCardBackgroundColor(requireContext().kairosThemeColor(R.attr.kairosBg2))
            radius = resources.getDimension(R.dimen.kairos_stroke_default)
            strokeColor = requireContext().kairosThemeColor(R.attr.kairosBorder)
            strokeWidth = dp(1)
            cardElevation = 0f
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).also { it.bottomMargin = dp(16) }
        }
        deviceContainer = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        card.addView(deviceContainer)
        serviciosTabContent.addView(card)
    }

    private fun refreshDevice() {
        if (!isAdded) return
        Thread {
            val remoteRunning = try { ModuleController.isRunning("remote") } catch (_: Exception) { false }
            val candidates = loadHttpModuleCandidates()
            val runningModules = candidates.filter {
                try { ModuleController.isRunning(it.id) } catch (_: Exception) { false }
            }
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                deviceContainer.removeAllViews()
                deviceContainer.addView(deviceRow(
                    name = getString(R.string.homelab_device_remote),
                    statusText = if (remoteRunning) getString(R.string.remote_status_corriendo_8022) else getString(R.string.remote_status_detenido),
                    running = remoteRunning,
                    onOpen = { navigateTo(RemoteFragment()) }
                ))
                if (runningModules.isEmpty()) {
                    deviceContainer.addView(TextView(requireContext()).apply {
                        text = getString(R.string.homelab_device_sin_modulos_http)
                        textSize = 12f
                        setTextColor(requireContext().kairosThemeColor(R.attr.kairosText3))
                        setPadding(dp(14), dp(2), dp(14), dp(12))
                    })
                } else {
                    runningModules.forEach { m ->
                        deviceContainer.addView(deviceRow(
                            name = "${m.name} (:${m.port})",
                            statusText = getString(R.string.homelab_status_activo),
                            running = true,
                            onOpen = { navigateTo(ModuleWebViewFragment.newInstance("http://127.0.0.1:${m.port}", m.name)) }
                        ))
                    }
                }
            }
        }.start()
    }

    private data class HttpModuleCandidate(val id: String, val name: String, val port: String)

    /** Lee modules.json y devuelve todo módulo con un `port` real declarado — sin lista
     *  hardcodeada de ids, así cualquier módulo nuevo con puerto aparece acá solo (mismo
     *  criterio "adaptable/escalable" de CLAUDE.md, ver readModulePort() en MonitorFragment.kt
     *  para el mismo patrón de lectura puntual de modules.json). */
    private fun loadHttpModuleCandidates(): List<HttpModuleCandidate> {
        return try {
            val json = requireContext().assets.open("modules.json").bufferedReader().use { it.readText() }
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val port = o.optString("port")
                val id = o.optString("id")
                if (port.isBlank() || id.isBlank() || id == "remote") null
                else HttpModuleCandidate(id, o.optString("name", id), port)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun deviceRow(name: String, statusText: String, running: Boolean, onOpen: () -> Unit): View {
        val ctx = requireContext()
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            addView(View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).also { it.marginEnd = dp(10) }
                setBackgroundColor(
                    if (running) ctx.kairosThemeColor(R.attr.kairosGreen) else ctx.kairosThemeColor(R.attr.kairosText3)
                )
            })
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                addView(TextView(ctx).apply {
                    text = name
                    textSize = 13f
                    setTextColor(ctx.kairosThemeColor(R.attr.kairosText))
                })
                addView(TextView(ctx).apply {
                    text = statusText
                    textSize = 11f
                    setTextColor(ctx.kairosThemeColor(R.attr.kairosText3))
                })
            })
            addView(smallButton(getString(R.string.homelab_btn_abrir), running, onOpen))
        }
    }

    // ── Pestaña "Servicios" — complementos (Syncthing, Restic) ───────────────────────────────
    // Antes módulos propios del catálogo; ahora complementos de Homelab (orden explícita del
    // usuario 2026-10-08, mensaje 6) sobre AddonEngine/HomelabAddons
    // docs/arquitectura/PLAN_COMPLEMENTOS_2026-10-08.md.

    private lateinit var addonsContainer: LinearLayout

    private class AddonEntry(val spec: AddonEngine.Spec, val nameRes: Int, val descRes: Int)

    private val addonEntries by lazy {
        listOf(
            AddonEntry(HomelabAddons.SYNCTHING, R.string.homelab_addon_syncthing_name, R.string.homelab_addon_syncthing_desc),
            AddonEntry(HomelabAddons.RESTIC, R.string.homelab_addon_restic_name, R.string.homelab_addon_restic_desc)
        )
    }

    private fun buildAddonsSection() {
        serviciosTabContent.addView(sectionLabel(getString(R.string.homelab_section_complementos)))
        val card = cardOf()
        addonsContainer = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        addonsContainer.addView(TextView(requireContext()).apply {
            text = getString(R.string.homelab_addon_status_loading)
            textSize = 12f
            setTextColor(requireContext().kairosThemeColor(R.attr.kairosText3))
            setPadding(dp(14), dp(12), dp(14), dp(12))
        })
        card.addView(addonsContainer)
        serviciosTabContent.addView(card)
    }

    private fun refreshAddons() {
        if (!isAdded) return
        Thread {
            val statuses = addonEntries.map { it to AddonEngine.status(it.spec) }
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                addonsContainer.removeAllViews()
                statuses.forEach { (entry, status) -> addonsContainer.addView(addonRow(entry, status)) }
            }
        }.start()
    }

    private fun addonRow(entry: AddonEntry, status: AddonEngine.Status): View {
        val ctx = requireContext()
        val name = getString(entry.nameRes)
        val isService = entry.spec.service != null
        val statusText = when {
            !status.installed -> getString(R.string.homelab_addon_status_not_installed)
            status.running -> getString(R.string.homelab_addon_status_running, status.version ?: "")
            status.version != null -> getString(R.string.homelab_addon_status_installed_version, status.version)
            else -> getString(R.string.homelab_addon_status_installed)
        }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            addView(TextView(ctx).apply {
                text = name
                textSize = 13f
                setTextColor(ctx.kairosThemeColor(R.attr.kairosText))
            })
            addView(TextView(ctx).apply {
                text = getString(entry.descRes)
                textSize = 11f
                setTextColor(ctx.kairosThemeColor(R.attr.kairosText3))
            })
            addView(TextView(ctx).apply {
                text = statusText
                textSize = 11f
                setTextColor(
                    if (status.running) ctx.kairosThemeColor(R.attr.kairosGreen) else ctx.kairosThemeColor(R.attr.kairosText2)
                )
                setPadding(0, dp(4), 0, dp(6))
            })
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                val buttons = mutableListOf<TextView>()
                when {
                    !status.installed -> buttons += smallButton(getString(R.string.homelab_addon_btn_install), true) {
                        runAddonAction(name, R.string.homelab_addon_toast_installing, R.string.homelab_addon_toast_install_ok, R.string.homelab_addon_toast_install_failed) {
                            AddonEngine.install(entry.spec)
                        }
                    }
                    isService && status.running -> {
                        buttons += smallButton(getString(R.string.homelab_addon_btn_stop), true) {
                            runAddonAction(name, null, R.string.homelab_addon_toast_stop_ok, R.string.homelab_addon_toast_stop_failed) { AddonEngine.stop(entry.spec) }
                        }
                        // Solo Syncthing tiene interfaz web propia (127.0.0.1:8384).
                        if (entry.spec.id == HomelabAddons.SYNCTHING.id) {
                            buttons += smallButton(getString(R.string.homelab_btn_abrir), true) {
                                navigateTo(ModuleWebViewFragment.newInstance(HomelabAddons.SYNCTHING_URL, name))
                            }
                        }
                    }
                    // Usuario/contraseña de la interfaz (Syncthing no los trae): disponible con el
                    // complemento instalado, corriendo o no.
                    isService -> buttons += smallButton(getString(R.string.homelab_addon_btn_start), true) {
                        runAddonAction(name, null, R.string.homelab_addon_toast_start_ok, R.string.homelab_addon_toast_start_failed) { AddonEngine.start(entry.spec) }
                    }
                    entry.spec.id == HomelabAddons.RESTIC.id -> {
                        buttons += smallButton(getString(R.string.homelab_addon_btn_backup), true) { showResticDialog(backup = true) }
                        buttons += smallButton(getString(R.string.homelab_addon_btn_snapshots), true) { showResticDialog(backup = false) }
                    }
                }
                if (status.installed && entry.spec.id == HomelabAddons.SYNCTHING.id) {
                    buttons += smallButton(getString(R.string.homelab_syncthing_btn_credentials), true) { showSyncthingCredentialsDialog() }
                }
                buttons.forEachIndexed { i, b ->
                    b.layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).also { if (i > 0) it.marginStart = dp(8) }
                    addView(b)
                }
            })
        }
    }

    /** Corre [action] fuera del hilo principal, avisa con toasts y refresca la sección. */
    private fun runAddonAction(name: String, startingRes: Int?, okRes: Int, failedRes: Int, action: () -> AddonEngine.Result) {
        startingRes?.let { toast(getString(it, name)) }
        runInBackground({
            try { action() } catch (e: Exception) { AddonEngine.Result(false, e.message ?: "error") }
        }) { result ->
            toast(if (result.ok) getString(okRes, name) else getString(failedRes, name, result.message))
            refreshAddons()
        }
    }

    /**
     * Diálogo de usuario y contraseña de la interfaz web de Syncthing. El campo de contraseña arranca
     * SIEMPRE vacío y nunca se muestra la guardada (no se guarda); solo se ve el nombre de usuario.
     */
    private fun showSyncthingCredentialsDialog() {
        val ctx = requireContext()
        val current = HomelabAddons.syncthingGuiUser()
        val form = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val user = EditText(ctx).apply {
            hint = getString(R.string.homelab_syncthing_hint_user)
            setText(current ?: "")
            inputType = InputType.TYPE_CLASS_TEXT
            form.addView(this)
        }
        val password = EditText(ctx).apply {
            hint = getString(if (current != null) R.string.homelab_syncthing_hint_password_replace else R.string.homelab_syncthing_hint_password)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            form.addView(this)
        }
        AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.homelab_syncthing_dialog_title))
            .setMessage(getString(if (current != null) R.string.homelab_syncthing_dialog_message_set else R.string.homelab_syncthing_dialog_message_unset))
            .setView(wrapDialogContent(form))
            .setPositiveButton(getString(R.string.homelab_syncthing_btn_save)) { _, _ ->
                val u = user.text.toString().trim()
                val p = password.text.toString()
                runInBackground({
                    try { HomelabAddons.syncthingSetCredentials(u, p) } catch (e: Exception) { AddonEngine.Result(false, e.message ?: "error") }
                }) { result ->
                    toast(if (result.ok) getString(R.string.homelab_syncthing_toast_ok) else getString(R.string.homelab_syncthing_toast_failed, result.message))
                    refreshAddons()
                }
            }
            .setNegativeButton(getString(R.string.homelab_btn_cancelar), null)
            .show()
    }

    /** Diálogo de Restic. La contraseña vive solo en este diálogo y en el entorno del proceso. */
    private fun showResticDialog(backup: Boolean) {
        val ctx = requireContext()
        val home = com.termux.shared.termux.TermuxConstants.TERMUX_HOME_DIR_PATH
        val form = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        fun field(hintRes: Int, initial: String = "", password: Boolean = false): EditText = EditText(ctx).apply {
            hint = getString(hintRes)
            setText(initial)
            inputType = if (password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT
            form.addView(this)
        }
        val folder = if (backup) field(R.string.homelab_restic_hint_folder, "/storage/emulated/0/Documents") else null
        val repo = field(R.string.homelab_restic_hint_repo, "$home/restic_repo")
        val password = field(R.string.homelab_restic_hint_password, password = true)
        AlertDialog.Builder(ctx)
            .setTitle(getString(if (backup) R.string.homelab_restic_dialog_title_backup else R.string.homelab_restic_dialog_title_snapshots))
            .setView(wrapDialogContent(form))
            .setPositiveButton(getString(R.string.homelab_restic_btn_run)) { _, _ ->
                val repoPath = repo.text.toString().trim()
                val pw = password.text.toString()
                val folderPath = folder?.text?.toString()?.trim().orEmpty()
                toast(getString(R.string.homelab_restic_toast_running))
                runInBackground({
                    try {
                        if (backup) HomelabAddons.resticBackup(repoPath, folderPath, pw) else HomelabAddons.resticSnapshots(repoPath, pw)
                    } catch (e: Exception) {
                        AddonEngine.Result(false, e.message ?: "error")
                    }
                }) { result ->
                    AlertDialog.Builder(requireContext())
                        .setTitle(getString(if (result.ok) R.string.homelab_restic_result_title else R.string.homelab_restic_error_title))
                        .setMessage(result.message)
                        .setPositiveButton(getString(R.string.homelab_restic_btn_close), null)
                        .show()
                }
            }
            .setNegativeButton(getString(R.string.homelab_btn_cancelar), null)
            .show()
    }

    // ── Pestaña "Servicios" — servicios externos agregados por el usuario ───────────────────

    private fun buildServicesSection() {
        val header = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(sectionLabel(getString(R.string.homelab_section_servicios)).apply {
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        })
        serviciosTabContent.addView(header)

        servicesEmptyState = TextView(requireContext()).apply {
            text = getString(R.string.homelab_servicios_vacio)
            textSize = 12f
            setTextColor(requireContext().kairosThemeColor(R.attr.kairosText3))
            setPadding(dp(4), dp(8), dp(4), dp(8))
        }
        servicesContainer = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        serviciosTabContent.addView(servicesEmptyState)
        serviciosTabContent.addView(servicesContainer)
    }

    private fun refreshServices() {
        if (!isAdded) return
        Thread {
            val services = try { HomelabManager.listServices() } catch (_: Exception) { emptyList() }
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                servicesContainer.removeAllViews()
                servicesEmptyState.visibility = if (services.isEmpty()) View.VISIBLE else View.GONE
                // Agrupados por tipo (hallazgo real: "categorías de servicios en Homelab -- hoy
                // lista plana, sin agrupar") -- mismo orden que el spinner de agregar/editar
                // (SERVICE_TYPE_ORDER), un sectionTitle() por grupo no vacío. Con un solo tipo
                // en uso (lo más común al principio) no vale la pena el encabezado -- queda la
                // lista simple de siempre.
                val byType = services.groupBy { it.type }
                if (byType.size <= 1) {
                    services.forEach { s -> servicesContainer.addView(serviceCard(s)) }
                } else {
                    for (type in SERVICE_TYPE_ORDER) {
                        val group = byType[type] ?: continue
                        servicesContainer.addView(sectionTitle(typeLabel(type)))
                        group.forEach { s -> servicesContainer.addView(serviceCard(s)) }
                    }
                }
            }
            // Ping en un segundo pase — no bloquea el primer render de la lista con la latencia
            // de red de cada probe (podría ser varios servicios en LANs lentas).
            if (services.isNotEmpty()) pingServicesAndUpdate(services)
        }.start()
    }

    private fun pingServicesAndUpdate(services: List<HomelabService>) {
        services.forEach { s ->
            val reachable = try { HomelabManager.probe(s.url) } catch (_: Exception) { false }
            if (!isAdded) return
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                (servicesContainer.findViewWithTag<View>("dot_${s.id}"))?.setBackgroundColor(
                    if (reachable) requireContext().kairosThemeColor(R.attr.kairosGreen)
                    else requireContext().kairosThemeColor(R.attr.kairosRed)
                )
                servicesContainer.findViewWithTag<TextView>("status_${s.id}")?.apply {
                    text = getString(if (reachable) R.string.homelab_service_status_online else R.string.homelab_service_status_offline)
                    setTextColor(
                        if (reachable) requireContext().kairosThemeColor(R.attr.kairosGreen)
                        else requireContext().kairosThemeColor(R.attr.kairosRed)
                    )
                }
            }
        }
    }

    private fun serviceCard(service: HomelabService): View {
        val ctx = requireContext()
        val card = MaterialCardView(ctx).apply {
            setCardBackgroundColor(ctx.kairosThemeColor(R.attr.kairosBg2))
            radius = resources.getDimension(R.dimen.kairos_stroke_default)
            strokeColor = ctx.kairosThemeColor(R.attr.kairosBorder)
            strokeWidth = dp(1)
            cardElevation = 0f
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).also { it.bottomMargin = dp(8) }
            setOnLongClickListener { showServiceMenu(service); true }
        }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        row.addView(View(ctx).apply {
            tag = "dot_${service.id}"
            layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).also { it.marginEnd = dp(10) }
            // Gris mientras no llegó el primer probe (pingServicesAndUpdate lo pinta verde/rojo
            // apenas responde) — nunca se asume "activo" por default.
            setBackgroundColor(ctx.kairosThemeColor(R.attr.kairosText3))
        })
        row.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            addView(TextView(ctx).apply {
                text = service.name
                textSize = 13f
                setTextColor(ctx.kairosThemeColor(R.attr.kairosText))
            })
            addView(TextView(ctx).apply {
                text = "${typeLabel(service.type)} · ${service.url}"
                textSize = 11f
                setTextColor(ctx.kairosThemeColor(R.attr.kairosText3))
            })
            // Estado legible además del punto de color (rediseño aprobado 2026-10-08, boceto de
            // Homelab: "estado en texto, no solo punto de color"). Lo actualiza pingServicesAndUpdate().
            addView(TextView(ctx).apply {
                tag = "status_${service.id}"
                text = getString(R.string.homelab_service_status_checking)
                textSize = 11f
                setTextColor(ctx.kairosThemeColor(R.attr.kairosText3))
            })
        })
        row.addView(smallButton(getString(R.string.homelab_btn_abrir), true) { openService(service) })
        // ⋮ visible: antes Editar/Token/Eliminar solo salían con pulsación larga (poco descubrible).
        row.addView(TextView(ctx).apply {
            text = "⋮"
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText2))
            contentDescription = getString(R.string.a11y_more_options)
            minimumWidth = dp(40)
            minimumHeight = dp(40)
            setOnClickListener { showServiceMenu(service) }
        })
        card.addView(row)
        return card
    }

    private fun openService(service: HomelabService) {
        navigateTo(ModuleWebViewFragment.newInstance(service.url, service.name))
    }

    private fun showServiceMenu(service: HomelabService) {
        val options = mutableListOf(getString(R.string.homelab_menu_editar))
        options.add(if (service.hasSecret) getString(R.string.homelab_menu_reemplazar_token) else getString(R.string.homelab_menu_agregar_token))
        if (service.hasSecret) options.add(getString(R.string.homelab_menu_borrar_token))
        options.add(getString(R.string.homelab_menu_eliminar))
        AlertDialog.Builder(requireContext())
            .setTitle(service.name)
            .setItems(options.toTypedArray()) { _, which ->
                when (options[which]) {
                    getString(R.string.homelab_menu_editar) -> showAddServiceDialog(service)
                    getString(R.string.homelab_menu_reemplazar_token), getString(R.string.homelab_menu_agregar_token) -> showReplaceSecretDialog(service)
                    getString(R.string.homelab_menu_borrar_token) -> confirmClearSecret(service)
                    getString(R.string.homelab_menu_eliminar) -> confirmDeleteService(service)
                }
            }
            .show()
    }

    private fun confirmClearSecret(service: HomelabService) {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.homelab_menu_borrar_token))
            .setMessage(getString(R.string.homelab_dialog_msg_borrar_token, service.name))
            .setPositiveButton(getString(R.string.homelab_btn_eliminar)) { _, _ ->
                runInBackground({ HomelabManager.clearSecret(service.id) }) { result ->
                    toast(if (result.ok) result.message else result.error)
                    refreshServices()
                }
            }
            .setNegativeButton(getString(R.string.homelab_btn_cancelar), null)
            .show()
    }

    private fun confirmDeleteService(service: HomelabService) {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.homelab_dialog_title_eliminar_servicio))
            .setMessage(getString(R.string.homelab_dialog_msg_eliminar_servicio, service.name))
            .setPositiveButton(getString(R.string.homelab_btn_eliminar)) { _, _ ->
                runInBackground({ HomelabManager.deleteService(service.id) }) { result ->
                    toast(if (result.ok) result.message else result.error)
                    refreshServices()
                }
            }
            .setNegativeButton(getString(R.string.homelab_btn_cancelar), null)
            .show()
    }

    /** Diálogo "Reemplazar token" — arranca SIEMPRE vacío, nunca pre-cargado con el valor
     *  anterior (regla dura, ver docstring de [HomelabManager.replaceSecret]). */
    private fun showReplaceSecretDialog(service: HomelabService) {
        val ctx = requireContext()
        val edit = EditText(ctx).apply {
            hint = getString(R.string.homelab_hint_token)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.homelab_menu_reemplazar_token))
            .setView(wrapDialogContent(edit))
            .setPositiveButton(getString(R.string.homelab_btn_guardar)) { _, _ ->
                val value = edit.text.toString()
                if (value.isBlank()) { toast(getString(R.string.homelab_msg_token_vacio)); return@setPositiveButton }
                runInBackground({ HomelabManager.replaceSecret(service.id, value) }) { result ->
                    toast(if (result.ok) result.message else result.error)
                    refreshServices()
                }
            }
            .setNegativeButton(getString(R.string.homelab_btn_cancelar), null)
            .show()
    }

    /** Diálogo "Agregar/Editar servicio" — [existing] null crea uno nuevo (con campo opcional de
     *  token), no-null edita nombre/tipo/URL/usuario de uno ya guardado (el token se maneja
     *  siempre por separado, ver [showReplaceSecretDialog], nunca mezclado en este diálogo para
     *  no tener que pre-cargarlo). */
    private fun showAddServiceDialog(existing: HomelabService? = null) {
        val ctx = requireContext()
        val view = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val nameEdit = EditText(ctx).apply {
            hint = getString(R.string.homelab_hint_nombre)
            setText(existing?.name ?: "")
        }
        val typeLabels = arrayOf(
            getString(R.string.homelab_type_docker),
            getString(R.string.homelab_type_pihole),
            getString(R.string.homelab_type_proxmox),
            getString(R.string.homelab_type_http),
            getString(R.string.homelab_type_otro)
        )
        val typeOrder = SERVICE_TYPE_ORDER
        val typeSpinner = Spinner(ctx).apply {
            adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, typeLabels)
            setSelection(existing?.let { typeOrder.indexOf(it.type) }?.takeIf { it >= 0 } ?: typeOrder.indexOf(ServiceType.HTTP))
        }
        val urlEdit = EditText(ctx).apply {
            hint = getString(R.string.homelab_hint_url)
            setText(existing?.url ?: "")
        }
        val userEdit = EditText(ctx).apply {
            hint = getString(R.string.homelab_hint_usuario_opcional)
            setText(existing?.username ?: "")
        }
        view.addView(nameEdit)
        view.addView(typeSpinner)
        view.addView(urlEdit)
        view.addView(userEdit)
        var tokenEdit: EditText? = null
        if (existing == null) {
            tokenEdit = EditText(ctx).apply {
                hint = getString(R.string.homelab_hint_token_opcional)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            view.addView(tokenEdit)
        }

        AlertDialog.Builder(ctx)
            .setTitle(getString(if (existing == null) R.string.homelab_dialog_title_agregar else R.string.homelab_dialog_title_editar))
            .setView(wrapDialogContent(view))
            .setPositiveButton(getString(R.string.homelab_btn_guardar)) { _, _ ->
                val name = nameEdit.text.toString().trim()
                val url = urlEdit.text.toString().trim()
                val user = userEdit.text.toString().trim()
                val type = typeOrder[typeSpinner.selectedItemPosition]
                if (url.isBlank()) { toast(getString(R.string.homelab_msg_url_vacia)); return@setPositiveButton }
                if (existing == null) {
                    val token = tokenEdit?.text?.toString()?.trim()
                    runInBackground({ HomelabManager.addService(name, type, url, user, token) }) { result ->
                        toast(if (result.ok) result.message else result.error)
                        refreshServices()
                    }
                } else {
                    runInBackground({ HomelabManager.updateService(existing.id, name, type, url, user) }) { result ->
                        toast(if (result.ok) result.message else result.error)
                        refreshServices()
                    }
                }
            }
            .setNegativeButton(getString(R.string.homelab_btn_cancelar), null)
            .show()
    }

    private fun typeLabel(type: ServiceType): String = when (type) {
        ServiceType.DOCKER -> getString(R.string.homelab_type_docker)
        ServiceType.PIHOLE -> getString(R.string.homelab_type_pihole)
        ServiceType.PROXMOX -> getString(R.string.homelab_type_proxmox)
        ServiceType.PORTAINER -> getString(R.string.homelab_type_portainer)
        ServiceType.ADGUARD -> getString(R.string.homelab_type_adguard)
        ServiceType.GITEA -> getString(R.string.homelab_type_gitea)
        ServiceType.PLEX -> getString(R.string.homelab_type_plex)
        ServiceType.BESZEL -> getString(R.string.homelab_type_beszel)
        ServiceType.HTTP -> getString(R.string.homelab_type_http)
        ServiceType.OTHER -> getString(R.string.homelab_type_otro)
    }

    // ── Helpers de estilo/layout — versión reducida y autónoma de los helpers equivalentes de
    //    BaseModuleFragment (addCard/infoRow/pill/dp) — HomelabFragment no extiende
    //    BaseModuleFragment (no es un módulo con lifecycle de proceso, ver KDoc de la clase). ──

    private fun cardOf(): MaterialCardView = MaterialCardView(requireContext()).apply {
        setCardBackgroundColor(requireContext().kairosThemeColor(R.attr.kairosBg2))
        radius = resources.getDimension(R.dimen.kairos_stroke_default)
        strokeColor = requireContext().kairosThemeColor(R.attr.kairosBorder)
        strokeWidth = dp(1)
        cardElevation = 0f
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).also { it.bottomMargin = dp(16) }
    }

    private fun sectionTitle(text: String): View = TextView(requireContext()).apply {
        this.text = text
        textSize = 18f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(requireContext().kairosThemeColor(R.attr.kairosText))
        setPadding(dp(4), dp(4), dp(4), dp(2))
    }

    private fun sectionLabel(text: String): TextView = TextView(requireContext()).apply {
        this.text = text
        textSize = 11f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(requireContext().kairosThemeColor(R.attr.kairosText3))
        letterSpacing = 0.08f
        setPadding(dp(4), dp(12), dp(4), dp(6))
    }

    private fun smallButton(text: String, enabled: Boolean, onClick: () -> Unit): TextView {
        val ctx = requireContext()
        return TextView(ctx).apply {
            this.text = text
            textSize = 12f
            setTextColor(ctx.kairosThemeColor(R.attr.kairosBlue))
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            alpha = if (enabled) 1f else 0.4f
            isEnabled = enabled
            // Auditoría de temas 2026-09-15: era Color.argb(28, 0, 122, 255) fijo (azul iOS,
            // no kairosBlue) — roto en Señal/Claro, ver kairosThemeColorAlpha().
            setBackgroundColor(ctx.kairosThemeColorAlpha(R.attr.kairosBlue, 28))
            setOnClickListener { if (enabled) onClick() }
        }
    }

    private fun wrapDialogContent(content: View): View {
        return CardView(requireContext()).apply {
            radius = 0f
            cardElevation = 0f
            setContentPadding(dp(20), dp(8), dp(20), dp(0))
            setCardBackgroundColor(Color.TRANSPARENT)
            addView(content)
        }
    }

    private fun dp(d: Int): Int = (d * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) {
        if (isAdded) Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
    }

    // Mismo patrón de navegación que BaseModuleFragment.navigateTo() — HomelabFragment vive
    // como tab raíz (agregado directo a R.id.fragment_container por TermuxActivity, igual que
    // Monitor/Nube/Entorno), así que parentFragmentManager acá ES el FragmentManager de la
    // Activity, mismo contrato que el resto de módulos que empujan un detalle con backstack.
    private fun navigateTo(fragment: Fragment) {
        // Defensa contra FLAG_SECURE pegado (ver com.termux.app.util.ScreenSecurity, bug real
        // "sigue sin dejar tirar capture") -- mismo motivo que BaseModuleFragment.navigateTo().
        activity?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        parentFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .addToBackStack(null)
            .commit()
    }

    private fun <T> runInBackground(work: () -> T, onResult: (T) -> Unit) {
        Thread {
            val result = work()
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread { if (isAdded) onResult(result) }
        }.start()
    }
}

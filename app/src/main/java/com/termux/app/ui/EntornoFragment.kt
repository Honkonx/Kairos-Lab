package com.termux.app.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.webkit.MimeTypeMap
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import com.termux.R
import com.termux.app.X11Service
import com.termux.app.util.EntornoNative
import com.termux.app.x11.KairosX11MainActivity
import org.json.JSONObject
import com.termux.app.util.kairosThemeColor
import java.io.File

/**
 * Entorno — "mini PC portátil": proot-distro + X11 embebido (Xlorie) + GPU, instalado por
 * entorno.sh (infra base solamente). Puerto nativo (100% Kotlin, vía EntornoNative.kt)
 * de las opciones de mayor valor de los 3 submenús de menu_entorno.sh (termux-ai-stack,
 * solo lectura — no se reimplementan las ~25 completas, ver EntornoNative.kt para el
 * detalle de qué quedó afuera y por qué). "Login a distro" abre una consola interactiva
 * real dentro del proot vía el overlay de terminal (mismo patrón que el resto de módulos
 * con CLI), no un placeholder.
 *
 * **Refactor de separación por pestaña (2026-09-07):** este archivo era un monolito de
 * ~2600 líneas con las 5 pestañas del grid (Nativo/X11/Distros/VNC/Sistema) mezcladas —
 * riesgo real de que un cambio en una pestaña rompiera otra sin querer. Ahora es un
 * "cascarón": ciclo de vida del Fragment, las 3 cards de arriba (ESTADO/INSTALADO/SESIONES
 * DE DISTRO), el TabLayout, y todo lo GENUINAMENTE compartido por 2+ pestañas (refreshStatus(),
 * runEntornoAction(), el guard beginOp()/endOp(), errorDetail(), showConflictDialog(),
 * launchX11(), el picker de wallpaper). Cada pestaña vive en su propia clase/archivo
 * (EntornoNativoTab.kt, EntornoX11Tab.kt, EntornoDistrosTab.kt, EntornoVncTab.kt,
 * EntornoSistemaTab.kt), instanciada una vez y reusada en cada render. Los helpers de UI
 * puramente presentacionales sin estado (tileGrid/actionTile/TileAction/sectionLabel/
 * distroIconView/inventorySubLabel) viven en EntornoTabUi.kt, top-level, para no obligar a
 * las clases de pestaña (que NO heredan de Fragment) a depender de miembros protegidos de
 * BaseModuleFragment. Reorganización pura de código ya existente — CERO cambios de
 * comportamiento visible; ver docs/mini-pc/ para el historial de diseño de cada pestaña en
 * sí (no repetido acá).
 */
class EntornoFragment : BaseModuleFragment() {
    override fun getModuleId() = "entorno"
    override fun getModuleName() = getString(R.string.entorno_module_name)

    // Desde 2026-08-25 este Fragment vive como tab raíz "Mini PC" del BottomNavigationView
    // (TermuxActivity.mEntornoFragment) además de seguir accesible como card en el catálogo de
    // Módulos (ModuleDetailNavigator, "entorno" -> EntornoFragment()) — mismo patrón de doble
    // vía que otros módulos promovidos a tab. Como tab raíz nunca se agrega vía
    // addToBackStack(), así que la flecha "←" heredada de BaseModuleFragment no tiene a dónde
    // volver — se desactiva acá. Cuando se abre desde el catálogo (backstack real), no hay
    // pérdida real de navegación: TermuxActivity ya expone el tab "Mini PC" como salida.
    override val showBackButton = false

    private var gpuValue: TextView? = null
    private var methodValue: TextView? = null
    private var x11Value: TextView? = null
    private var vncValue: TextView? = null
    private var pulseValue: TextView? = null
    private var desktopsValue: TextView? = null
    private var inventoryDistrosContainer: LinearLayout? = null

    // Selector visual multi-sesión de terminal por distro (2026-09-01, auditoría profunda de
    // Mini PC — ver docs/mini-pc/AUDITORIA_MINIPC_PROFUNDA_2026-09-01.md). Antes de la ronda
    // 2026-09-01 esto quedó documentado como "requiere más diseño de estado" (REFERENCIA_TRIERARCH.md,
    // hallazgo #4 sobre TerminalSessionController.kt); con getActiveModuleSessionNames()/
    // ForegroundProcessDetector.kt/watchTerminalSessions() ya construidos HOY para el chip global
    // de ModulesFragment (docs/arquitectura/DISENO_SELECTOR_SESIONES_TERMINAL_2026-09-01.md), el
    // costo real bajó a "filtrar esa misma lista por prefijo" — cada login a distro
    // (promptDistroLogin()) ya abre una sesión NOMBRADA distinta ("Entorno: <distro>", ver
    // entorno_titulo_sesion_terminal), así que TermuxService ya sostiene N sesiones vivas en
    // paralelo sin ningún cambio de motor — solo faltaba una superficie de UI para verlas/
    // cambiar entre ellas SIN pasar por el selector genérico (mezclado con CLIs de IA) de
    // Módulos.
    private var distroSessionsContainer: LinearLayout? = null
    private var distroSessionsHolder: FrameLayout? = null

    /** Prefijo real de sessionName que arma promptDistroLogin() (en EntornoDistrosTab) — "Entorno: "
     *  (con el espacio final del formato %1$s vacío). Único identificador estable para filtrar,
     *  dentro de la lista GLOBAL de sesiones nombradas, cuáles son sesiones de distro de Mini PC. */
    private val distroSessionPrefix: String
        get() = getString(R.string.entorno_titulo_sesion_terminal, "")

    // Estado real (EntornoNative.status(), misma fuente que las cards "ESTADO" de arriba) usado
    // para pintar el punto verde de "corriendo" en las tiles del grid — leído por EntornoX11Tab/
    // EntornoVncTab/EntornoSistemaTab (running = { fragment.x11Running } etc). Solo se badgea lo
    // que status() expone como señal directa y confirmada (x11/vnc/pulse) — no se inventa un
    // estado "escritorio nativo corriendo" que EntornoNative no puede confirmar de forma
    // inequívoca (ver regla empirical-verification-before-fix: no fabricar una post-condición
    // sin evidencia real). `internal` (no `private`): leídas desde las clases de pestaña, que
    // viven en archivos separados dentro del mismo módulo.
    internal var x11Running = false
    internal var vncRunning = false
    internal var pulseRunning = false

    /**
     * Guard real contra instalaciones apt/pkg concurrentes sobre el MISMO target (auditoría
     * Mini PC 2026-09-04). Confirmado leyendo `modulos/entorno.sh` (`distro_setup_gui.sh`,
     * protegido — solo lectura): cada instalación de escritorio dentro de una distro empieza
     * con `pkill -9 -f "proot-distro login $DISTRO"` (limpieza de procesos huérfanos de una
     * corrida anterior interrumpida) — si dos instalaciones para LA MISMA distro corren
     * solapadas (posible desde que `ProgressDialogController` ofrece "Enviar a 2do plano",
     * ver docs/humano247.md: el diálogo desaparece pero el Thread sigue corriendo, nada impide
     * volver a tocar el mismo botón u otro que toque el mismo proot-distro login/dpkg), la
     * segunda mataría a la primera en pleno `apt-get install` sin avisar — el usuario ve
     * "instalando en 2do plano" y en realidad la instalación murió silenciosamente. Lo mismo
     * aplica sin distro (instalar XFCE4 nativo + TigerVNC comparten el mismo dpkg de Termux).
     * No se puede arreglar del lado del script (protegido) — se previene que la UI dispare la
     * segunda operación mientras la primera sigue en curso.
     *
     * `internal` (no `private`): usado desde EntornoNativoTab, EntornoDistrosTab y
     * EntornoVncTab (las 3 pestañas que disparan instalaciones largas sobre el mismo target).
     */
    // ConcurrentHashMap.newKeySet() (no un mutableSetOf() plano) — beginOp() se llama siempre
    // desde el hilo de UI (onClick), pero endOp() se llama desde el Thread de background que
    // corre la instalación real; un HashSet plano mutado desde 2 hilos sin sincronizar sería
    // en sí mismo el mismo tipo de bug de concurrencia que este guard intenta prevenir.
    private val opsInProgress: java.util.concurrent.ConcurrentHashMap.KeySetView<String, Boolean> =
        java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** true y reserva `key` si no había ninguna operación en curso con esa clave; si ya había una, avisa y no reserva nada. */
    internal fun beginOp(key: String): Boolean {
        if (!opsInProgress.add(key)) {
            toast(getString(R.string.entorno_toast_operacion_en_curso))
            return false
        }
        return true
    }

    internal fun endOp(key: String) {
        opsInProgress.remove(key)
    }

    /** Clave compartida por instalar XFCE4 nativo / instalar un escritorio genérico nativo / instalar TigerVNC — las 3 usan `pkg install` sobre el MISMO dpkg de Termux. */
    internal val nativePkgInstallKey = "native-pkg-install"

    // Holder de registro para refreshStatus() en watchTerminalSessions() (ver comentario en
    // buildContent()) — nunca se agrega al árbol de vistas, solo sirve para reusar el poll de
    // 3s de BaseModuleFragment (mismo patrón ya usado por distroSessionsHolder arriba).
    private var statusWatchHolder: FrameLayout? = null

    /** Pestaña activa del grid (Nativo/X11/Distros/VNC/Sistema) — usado por renderTab() al reconstruir tras un refresh de estado. */
    private var tabContentContainer: LinearLayout? = null
    private var activeTabIndex = 0

    // Instancias únicas de cada pestaña — creadas una vez (no en cada renderTab()) para no
    // perder ningún estado propio que llegaran a acumular a futuro; hoy son sin estado propio
    // (delegan todo lo compartido a este Fragment), pero mantenerlas como instancias estables
    // evita GC churn de recrear 5 objetos en cada cambio de pestaña/refreshStatus().
    private val nativoTab by lazy { EntornoNativoTab(this) }
    private val x11Tab by lazy { EntornoX11Tab(this) }
    private val distrosTab by lazy { EntornoDistrosTab(this) }
    private val vncTab by lazy { EntornoVncTab(this) }
    private val sistemaTab by lazy { EntornoSistemaTab(this) }

    // ── Fondo de pantalla — pedido explícito del usuario ("incluso poder cambiar la imagen de
    // fondo etc", docs/humano249.md). Mismo patrón que mPickImageLauncher (ChatFragment.kt) /
    // mPickImportFileLauncher (CactusFragment.kt): registro como campo de instancia (requisito
    // de ciclo de vida de ActivityResultLauncher), no dentro de un onClick — por eso el
    // mecanismo entero (target sealed class, launcher, pickWallpaper()) se queda en el
    // Fragment aunque EntornoNativoTab/EntornoDistrosTab sean quienes lo disparan.
    // pendingWallpaperTarget guarda a dónde aplicar el resultado (nativo o distro+DE) entre el
    // momento en que se abre el picker y el momento en que vuelve el callback — no hay forma de
    // pasarle un parámetro extra a ActivityResultContracts.GetContent().
    internal sealed class WallpaperTarget {
        data class Native(val de: String) : WallpaperTarget()
        data class Distro(val distro: String, val de: String) : WallpaperTarget()
    }

    private var pendingWallpaperTarget: WallpaperTarget? = null

    private val mPickWallpaperLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri?.let { applyPickedWallpaper(it) }
        }

    /** Punto de entrada único para EntornoNativoTab/EntornoDistrosTab: guarda el destino y abre el picker de imagen. */
    internal fun pickWallpaper(target: WallpaperTarget) {
        pendingWallpaperTarget = target
        mPickWallpaperLauncher.launch("image/*")
    }

    override fun buildContent() {
        // Fix real (2026-08-16, quejas de uso en dispositivo, ver mensaje del usuario del
        // día): antes esta pantalla solo mostraba "Volvé al listado y tocá Instalar" sin
        // ninguna acción propia — y ese botón "Instalar" del listado quedaba bloqueado por
        // el gate de requiresProot (ver BottomSheetInstalacion.kt), que decía "Instalar
        // proot primero" aunque el módulo NO depende de proot para su camino principal
        // (XFCE4 nativo sobre el X11 embebido, sin distro). Ahora se ofrece instalar en
        // segundo plano directo desde acá, mismo patrón que N8nFragment.
        if (!isModuleInstalled()) {
            showNotInstalled(getModuleName()) { installEntornoSilently() }
            return
        }

        addCard(getString(R.string.entorno_card_estado)) {
            addView(infoRowWithIcon(R.drawable.ic_gpu, getString(R.string.entorno_label_gpu), "—").also { gpuValue = it.valueTextView() })
            addView(infoRowWithIcon(R.drawable.ic_settings, getString(R.string.entorno_label_metodo), "—").also { methodValue = it.valueTextView() })
            addView(infoRowWithIcon(R.drawable.ic_x11, getString(R.string.entorno_label_x11), "—").also { x11Value = it.valueTextView() })
            addView(infoRowWithIcon(R.drawable.ic_vnc, getString(R.string.entorno_label_vnc), "—").also { vncValue = it.valueTextView() })
            addView(infoRowWithIcon(R.drawable.ic_audio, getString(R.string.entorno_label_pulseaudio), "—").also { pulseValue = it.valueTextView() })
            addView(infoRowWithIcon(R.drawable.ic_desktop, getString(R.string.entorno_label_escritorios), "—").also { desktopsValue = it.valueTextView() })
        }
        // Bug real confirmado (ya documentado en docs/adb/AUDITORIA_ADB_DISPOSITIVO_REAL_2026-08-21.md
        // y MEJORAS_PENDIENTES.md "Estado 2026-08-21 (11)": "el estado de X11 nativo no se
        // refresca en vivo" — antes refreshStatus() solo corría una vez acá y después de cada
        // acción del propio usuario, pero NUNCA mientras la pantalla sigue abierta sin que el
        // usuario toque nada. Si X11/VNC/PulseAudio mueren solos (crash del proceso Mesa/
        // Gallium ya confirmado en dispositivo real, o el usuario los mata desde otra app/
        // Ajustes>Apps), la card "ESTADO" seguía mostrando "Corriendo" indefinidamente — el
        // mismo patrón de "estado que miente" ya corregido para el badge del FAB de terminal
        // (TermuxActivity.resolveFabBadgeSessionName()). Fix: mismo mecanismo de poll de 3s
        // (mientras el Fragment está resumed) ya usado para las sesiones de distro — sin
        // Handler nuevo, reusa watchTerminalSessions()/terminalStatusHandler de
        // BaseModuleFragment.
        statusWatchHolder = FrameLayout(requireContext())
        watchTerminalSessions(statusWatchHolder!!) { refreshStatus() }

        // Inventario de solo-lectura (gap real de paridad con menu_entorno.sh
        // submenu_terminal [3] — "Listar distros instaladas"): antes solo se podía ACTUAR
        // sobre distros vía los diálogos de EntornoDistrosTab (instalar/login/eliminar) — no
        // había forma de simplemente VER "esto es lo que tenés instalado ahora" sin abrir un
        // diálogo de acción primero. Contenedores udocker: sacados de acá (2026-08-18, pedido
        // explícito del usuario) — ya tienen pantalla propia completa en el módulo "udocker"
        // (UdockerFragment.kt), esta card duplicaba esa vista sin agregar nada.
        addCard(getString(R.string.entorno_card_instalado)) {
            addView(inventorySubLabel(requireContext(), getString(R.string.entorno_sublabel_distros)))
            inventoryDistrosContainer = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
            addView(inventoryDistrosContainer)
        }
        refreshInventory()

        // Card "SESIONES DE DISTRO" — selector visual multi-sesión (ver comentario del campo
        // distroSessionsContainer arriba). Solo lectura del estado ya en memoria de
        // TermuxService (mismo mecanismo que ModulesFragment.refreshTerminalSessionsIndicator(),
        // sin ProcessBuilder) — watchTerminalSessions() la refresca sola cada 3s mientras la
        // pantalla está visible, así que se actualiza si una sesión se cierra desde OTRO lado
        // (ej. `exit` dentro de la propia TUI de la distro).
        addCard(getString(R.string.entorno_card_sesiones_distro)) {
            distroSessionsContainer = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
            addView(distroSessionsContainer)
        }
        distroSessionsHolder = FrameLayout(requireContext())
        watchTerminalSessions(distroSessionsHolder!!) { refreshDistroSessions() }

        // Rediseño visual 2026-08-26 (mockup "Grid Launcher" aprobado por el usuario, ver
        // docs/mini-pc/MINIPC_TAB_2026-08-25.md sección "Ronda 4"): TabLayout de categorías
        // (Nativo/X11/Distros/VNC/Sistema) con grids de tiles ícono+texto. Ambos caminos de
        // escritorio (NATIVO sin distro vs CON DISTRO/proot-distro) comparten el mismo
        // servidor X11 embebido (:1) — solo uno activo a la vez, enforzado en
        // EntornoNative.startDesktop()/startDistroDesktop() (desktopModeConflict()) — si el
        // usuario arranca el otro camino con uno ya activo, showConflictDialog() ofrece
        // detener el actual primero, nunca se dejan 2 sesiones simultáneas.
        buildTabsSection()

        // Gap real (auditoría de consistencia de menús 2026-08-19): Entorno era una pantalla
        // propia sin NINGÚN botón de Actualizar/Desinstalar — GenericModuleFragment ya lo da
        // gratis a cualquier módulo sin pantalla propia, este Fragment lo había perdido al
        // reemplazar esa pantalla genérica. Ver BaseModuleFragment.addMaintenanceCard().
        addMaintenanceCard()
    }

    /**
     * Fix real (humano181, bug 1 "da error al instalar entorno gráfico en la distro"). Varios
     * `progress.failure(...)` de las pestañas solo mostraban `json.error` (mensaje corto) y
     * descartaban `json.output` (la salida real de apt-get/pkg dentro del proot — por qué
     * falló de verdad) sin mostrarlo NI loguearlo — mismo tipo de gap de diagnosticabilidad
     * ya identificado y corregido en runEntornoAction() (ver docs/humano65.md/humano66.md).
     * `internal`: llamado desde EntornoNativoTab y EntornoDistrosTab.
     */
    internal fun errorDetail(json: JSONObject): String {
        val error = json.optString("error", getString(R.string.entorno_error_desconocido_texto))
        val output = json.optString("output", "").takeLast(300)
        return if (output.isNotBlank()) "$error — $output" else error
    }

    /** Delegado público a BaseModuleFragment.toast() (protected) para que las clases de pestaña (que no heredan de Fragment) puedan mostrar un Toast. */
    internal fun toastMsg(message: String) = toast(message)

    /** Delegado público a BaseModuleFragment.launchTerminalCommand() (protected), usado por EntornoDistrosTab.promptDistroLogin(). */
    internal fun launchTerminal(command: String, sessionName: String?) = launchTerminalCommand(command, sessionName)

    /** Instala la infra base de entorno.sh en segundo plano — mismo patrón que N8nFragment.installModuleInBackground(). */
    private fun installEntornoSilently() {
        installModuleInBackground(null) { ok ->
            if (ok) {
                toast(getString(R.string.entorno_toast_instalado))
                refreshView()
            } else {
                toast(getString(R.string.entorno_toast_fallo_instalacion))
            }
        }
    }

    private fun refreshView() {
        container.removeAllViews()
        buildContent()
    }

    /**
     * Variante de infoRow() con un ícono chico antes de la etiqueta — pulido visual 2026-08-27
     * (ver docs/humano259.md, "hacer la interfaz bonita tipo app del 2026") para la card
     * "ESTADO", que antes era texto plano puro. Mantiene la MISMA estructura de 2 hijos que
     * infoRow() (contenedor de etiqueta, TextView de valor) para que valueTextView() — que
     * asume `getChildAt(1)` == el TextView de valor — siga funcionando sin cambios; el ícono
     * va DENTRO del primer hijo (un LinearLayout ícono+texto), no como hijo propio.
     */
    private fun infoRowWithIcon(iconRes: Int, key: String, value: String): View {
        val ctx = requireContext()
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            addView(
                LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(ImageView(ctx).apply {
                        setImageResource(iconRes)
                        imageTintList = ColorStateList.valueOf(ctx.kairosThemeColor(R.attr.kairosText3))
                        layoutParams = LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginEnd = dp(8) }
                    })
                    addView(TextView(ctx).apply {
                        text = key
                        textSize = 13f
                        setTextColor(ctx.kairosThemeColor(R.attr.kairosText2))
                    })
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.5f)
            )
            addView(TextView(ctx).apply {
                text = value
                textSize = 12f
                setTypeface(android.graphics.Typeface.MONOSPACE)
                gravity = Gravity.END
                setTextColor(ctx.kairosThemeColor(R.attr.kairosText))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.5f)
            })
        }
    }

    private fun emptyInventoryRow(text: String): View {
        val ctx = requireContext()
        return TextView(ctx).apply {
            this.text = text
            textSize = 12f
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText3))
            setPadding(dp(14), dp(8), dp(14), dp(10))
        }
    }

    /**
     * Fila del inventario "📋 INSTALADO" con el logo real de la distro (reemplaza el 🐧
     * genérico fijo que antes se mostraba para CUALQUIER distro instalada — pedido explícito
     * del usuario, ver docs/humano259.md) — mismo layout de 2 columnas que infoRow() pero con
     * un ícono de identidad antes del nombre.
     */
    private fun distroInventoryRow(name: String): View {
        val ctx = requireContext()
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            addView(
                distroIconView(ctx, name, 22),
                LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(10) }
            )
            addView(TextView(ctx).apply {
                text = name
                textSize = 13f
                setTextColor(ctx.kairosThemeColor(R.attr.kairosText2))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(ctx).apply {
                text = getString(R.string.entorno_estado_instalada)
                textSize = 12f
                setTypeface(android.graphics.Typeface.MONOSPACE)
                gravity = Gravity.END
                setTextColor(ctx.kairosThemeColor(R.attr.kairosText))
            })
        }
    }

    /**
     * Nombres de sesión de terminal ACTIVAS ahora mismo que corresponden a un login de distro
     * ("Entorno: <distro>", ver EntornoDistrosTab.promptDistroLogin()) — filtro por prefijo
     * sobre la lista GLOBAL de sesiones nombradas (TermuxActivity.getActiveModuleSessionNames()
     * no distingue por módulo, mezcla CLIs de IA y cualquier otra sesión con nombre). Devuelve
     * el par (nombre completo de sesión, nombre de distro sin el prefijo) para poder mostrar el
     * ícono real de la distro (distroIconView()) en cada fila.
     */
    private fun activeDistroSessions(): List<Pair<String, String>> {
        val act = activity as? com.termux.app.TermuxActivity ?: return emptyList()
        val prefix = distroSessionPrefix
        return act.getActiveModuleSessionNames()
            .filter { it.startsWith(prefix) }
            .map { it to it.removePrefix(prefix) }
    }

    /**
     * Fila de una sesión de distro activa: ícono real de la distro + nombre, tocar la fila
     * reabre esa sesión exacta (mismo mecanismo de reuso-por-nombre que
     * TermuxActivity.openTerminalWithCommand ya usa para "TUI en terminal" de cualquier
     * módulo — nunca duplica sesión), botón "✕" cierra SOLO esa terminal (deja la distro
     * corriendo, mismo criterio que BaseModuleFragment.closeTerminalSession()).
     */
    private fun distroSessionRow(sessionName: String, distroName: String): View {
        val ctx = requireContext()
        val act = activity as? com.termux.app.TermuxActivity
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            isClickable = true
            isFocusable = true
            setOnClickListener { act?.openTerminalWithCommand(null, sessionName) }
            addView(
                distroIconView(ctx, distroName, 22),
                LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(10) }
            )
            addView(TextView(ctx).apply {
                text = distroName
                textSize = 13f
                setTextColor(ctx.kairosThemeColor(R.attr.kairosText2))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(ctx).apply {
                text = getString(R.string.entorno_sesion_cerrar)
                textSize = 11f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(ctx.kairosThemeColor(R.attr.kairosRed))
                setPadding(dp(8), dp(4), dp(8), dp(4))
                setOnClickListener {
                    act?.stopSessionByName(sessionName)
                    toast(getString(R.string.base_module_terminal_closed, sessionName))
                    refreshDistroSessions()
                }
            })
        }
    }

    /** Repuebla distroSessionsContainer con las sesiones de distro activas ahora mismo — colgado
     *  de watchTerminalSessions() (poll de 3s mientras la pantalla está visible, ver buildContent()). */
    private fun refreshDistroSessions() {
        val listContainer = distroSessionsContainer ?: return
        listContainer.removeAllViews()
        val sessions = activeDistroSessions()
        if (sessions.isEmpty()) {
            listContainer.addView(emptyInventoryRow(getString(R.string.entorno_sesiones_distro_ninguna)))
            return
        }
        for ((sessionName, distroName) in sessions) {
            listContainer.addView(distroSessionRow(sessionName, distroName))
        }
    }

    /**
     * Refresca el inventario de solo-lectura (distros proot-distro) — llamado al entrar a
     * la pantalla (buildContent()) y también desde runEntornoAction() tras cualquier acción,
     * mismo criterio que refreshStatus() (instalar/eliminar una distro es una de las
     * acciones posibles ahí). Contenedores udocker: sacados de acá 2026-08-18 — su
     * inventario en vivo ya vive en UdockerFragment.kt (módulo propio), esta pantalla no
     * necesita duplicarlo.
     */
    private fun refreshInventory() {
        Thread {
            val distros = EntornoNative.distroList()
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                renderDistroInventory(distros)
            }
        }.start()
    }

    private fun renderDistroInventory(json: JSONObject) {
        val listContainer = inventoryDistrosContainer ?: return
        listContainer.removeAllViews()
        val installed = json.optJSONArray("installed")
        if (!json.optBoolean("ok", false) || installed == null || installed.length() == 0) {
            listContainer.addView(emptyInventoryRow(getString(R.string.entorno_ninguna_distro)))
            return
        }
        for (i in 0 until installed.length()) {
            listContainer.addView(distroInventoryRow(installed.optString(i)))
        }
    }

    /** `internal`: llamado desde las 5 clases de pestaña tras cualquier acción que cambie el estado (instalar/detener/etc). */
    internal fun refreshStatus() {
        Thread {
            val json = EntornoNative.status()
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (json.optBoolean("ok", false)) {
                    gpuValue?.text = json.optString("gpu", "?")
                    methodValue?.text = json.optString("gpu_method", "?")
                    x11Running = json.optBoolean("x11_running", false)
                    x11Value?.text = if (x11Running) getString(R.string.entorno_x11_corriendo) else getString(R.string.entorno_detenido)
                    x11Value?.setTextColor(
                        requireContext().kairosThemeColor(
                            if (x11Running) R.attr.kairosGreen else R.attr.kairosText2
                        )
                    )
                    vncRunning = json.optBoolean("vnc_running", false)
                    vncValue?.text = when {
                        vncRunning -> getString(R.string.entorno_corriendo)
                        json.optBoolean("vnc_installed", false) -> getString(R.string.entorno_detenido)
                        else -> getString(R.string.entorno_no_instalado)
                    }
                    vncValue?.setTextColor(
                        requireContext().kairosThemeColor(if (vncRunning) R.attr.kairosGreen else R.attr.kairosText2)
                    )
                    pulseRunning = json.optBoolean("pulse_running", false)
                    pulseValue?.text = if (pulseRunning) getString(R.string.entorno_activo) else getString(R.string.entorno_detenido)
                    pulseValue?.setTextColor(
                        requireContext().kairosThemeColor(if (pulseRunning) R.attr.kairosGreen else R.attr.kairosText2)
                    )
                    val desktops = json.optJSONArray("installed_desktops")
                    desktopsValue?.text = if (desktops == null || desktops.length() == 0) {
                        getString(R.string.entorno_ninguno)
                    } else {
                        (0 until desktops.length()).joinToString(", ") { EntornoNative.desktopLabel(desktops.optString(it)) }
                    }
                } else {
                    gpuValue?.text = getString(R.string.entorno_status_error)
                    methodValue?.text = "—"
                    x11Value?.text = "—"
                    vncValue?.text = "—"
                    pulseValue?.text = "—"
                    desktopsValue?.text = "—"
                }
                // Los badges verdes de las tiles del grid dependen de x11Running/vncRunning/
                // pulseRunning — re-renderizar la pestaña activa para reflejar el estado nuevo
                // (mismo criterio que refreshInventory(), llamado tras cada acción real).
                renderTab(activeTabIndex)
            }
        }.start()
    }

    /**
     * `opKey` opcional (ver opsInProgress arriba) — solo se usa hoy para "distro-install"/
     * "distro-remove", las 2 acciones de este helper que tardan minutos reales y comparten
     * target (proot-distro login) con las instalaciones de escritorio/apps que ya tienen su
     * propio guard. El resto de acciones de este helper (pulse-toggle, gpu-method, vnc-start/
     * stop, backup, bridge-mount) son rápidas o no colisionan con nada — no necesitan guard.
     * `internal`: llamado desde las 5 clases de pestaña (acción genérica sobre EntornoNative).
     */
    internal fun runEntornoAction(action: String, vararg args: String, opKey: String? = null) {
        if (opKey != null && !beginOp(opKey)) return
        toast("$action…")
        Thread {
          try {
            val arg0 = args.getOrElse(0) { "" }
            val json = when (action) {
                "distro-install" -> EntornoNative.distroInstall(arg0)
                "distro-remove" -> EntornoNative.distroRemove(arg0)
                "distro-backup" -> EntornoNative.distroBackup(arg0)
                "distro-restore" -> EntornoNative.distroRestore(arg0, args.getOrElse(1) { "" })
                "distro-reset" -> EntornoNative.distroReset(arg0)
                "bridge-mount" -> EntornoNative.mountProjectBridge(arg0)
                "desktop-install" -> EntornoNative.installDesktop(arg0)
                "desktop-launchers" -> EntornoNative.generateDesktopLaunchers()
                "distro-app-remove" -> EntornoNative.distroAppRemove(arg0, args.getOrElse(1) { "" })
                "vnc-install" -> EntornoNative.vncInstall()
                "vnc-start" -> EntornoNative.vncStart()
                "vnc-stop" -> EntornoNative.vncStop()
                "pulse-toggle" -> EntornoNative.pulseToggle()
                "gpu-method" -> EntornoNative.setGpuMethod(arg0)
                else -> JSONObject().put("ok", false).put("error", getString(R.string.entorno_accion_desconocida, action))
            }
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                val ok = json.optBoolean("ok", false)
                // Antes solo se mostraba json.error (mensaje genérico) — el detalle real
                // (json.output, la salida real de proot-distro/pkg) se descartaba sin mostrar
                // ni loguear en ningún lado. Bug de diagnosticabilidad real (auditoría
                // 2026-08-05, ver docs/humano65.md/humano66.md): el usuario no tenía forma de
                // saber POR QUÉ fallaba una distro/escritorio/X11, y nosotros tampoco
                // teníamos ningún log para depurarlo después. Ahora se muestra el detalle real
                // (si vino) además del mensaje corto, y EntornoNative ya lo deja también en
                // ~/kairos_logs/wizard_debug.log.
                val msg = if (ok) {
                    json.optString("message", getString(R.string.entorno_ok))
                } else {
                    val detail = json.optString("output", "").takeLast(200)
                    val base = getString(R.string.entorno_error_prefix, json.optString("error", getString(R.string.entorno_desconocido)))
                    if (detail.isNotBlank()) "$base — $detail" else base
                }
                Snackbar.make(requireView(), msg, Snackbar.LENGTH_LONG).show()
                refreshStatus()
                refreshInventory()
            }
          } finally {
            if (opKey != null) endOp(opKey)
          }
        }.start()
    }

    /**
     * TabLayout de 5 categorías (Nativo/X11/Distros/VNC/Sistema) arriba del grid — mismo
     * estilo (`?attr/kairosGreen` de indicador, `?attr/kairosText`/`kairosText3` de texto) que
     * ya usa `fragment_file_manager.xml`, aplicado programáticamente acá porque este Fragment
     * arma todo su contenido en Kotlin (BaseModuleFragment.container), no desde un layout XML
     * propio. `tabContentContainer` se limpia y reconstruye en cada cambio de pestaña — mismo
     * patrón que `refreshView()` (limpiar + reconstruir), no ViewPager: el contenido igual
     * vive dentro del ScrollView único de `fragment_module_detail.xml`.
     */
    private fun buildTabsSection() {
        val ctx = requireContext()
        val tabLayout = TabLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, dp(8), 0, dp(4))
            }
            tabMode = TabLayout.MODE_SCROLLABLE
            setSelectedTabIndicatorColor(ctx.kairosThemeColor(R.attr.kairosGreen))
            setTabTextColors(ctx.kairosThemeColor(R.attr.kairosText3), ctx.kairosThemeColor(R.attr.kairosText))
            setBackgroundColor(ctx.kairosThemeColor(R.attr.kairosBg2))
        }
        listOf(
            getString(R.string.entorno_tab_nativo),
            getString(R.string.entorno_tab_x11),
            getString(R.string.entorno_tab_distros),
            getString(R.string.entorno_tab_vnc),
            getString(R.string.entorno_tab_sistema)
        ).forEach { tabLayout.addTab(tabLayout.newTab().setText(it)) }
        container.addView(tabLayout)

        val content = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        container.addView(content)
        tabContentContainer = content

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) { renderTab(tab.position) }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
        tabLayout.getTabAt(activeTabIndex)?.select()
        renderTab(activeTabIndex)
    }

    private fun renderTab(index: Int) {
        activeTabIndex = index
        val content = tabContentContainer ?: return
        content.removeAllViews()
        when (index) {
            0 -> nativoTab.render(content)
            1 -> x11Tab.render(content)
            2 -> distrosTab.render(content)
            3 -> vncTab.render(content)
            else -> sistemaTab.render(content)
        }
    }

    /**
     * Diálogo mostrado cuando EntornoNative bloquea un arranque de escritorio porque el OTRO
     * camino (nativo/distro) ya está activo — pedido explícito del usuario: "no tener las dos
     * abiertas al mismo tiempo". Ofrece detener la sesión actual (EntornoNative.
     * stopDesktopSession(), mantiene el servidor X11 arriba) y reintentar la acción original.
     * `internal`: compartido por EntornoNativoTab.startDesktopOnEmbeddedX11() y
     * EntornoDistrosTab.startDistroDesktopOnEmbeddedX11().
     */
    internal fun showConflictDialog(json: JSONObject, retry: () -> Unit) {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.entorno_dialog_conflicto_titulo))
            .setMessage(json.optString("error", getString(R.string.entorno_conflicto_mensaje_default)))
            .setPositiveButton(getString(R.string.entorno_boton_detener_actual_continuar)) { _, _ ->
                toast(getString(R.string.entorno_toast_deteniendo_escritorio_actual))
                Thread {
                    EntornoNative.stopDesktopSession()
                    if (!isAdded) return@Thread
                    requireActivity().runOnUiThread { retry() }
                }.start()
            }
            .setNegativeButton(getString(R.string.entorno_cancelar), null)
            .show()
    }

    /**
     * "Entrar en X11 (reabrir visor)" — portado de X11Fragment.launchX11() (fusión 2026-08-25).
     * Arranca X11Service + abre KairosX11MainActivity con FLAG_ACTIVITY_NEW_TASK: MainActivity
     * (visor X11) tiene su propio taskAffinity (ver AndroidManifest.xml, com.termux.x11.
     * MainActivity) separado del de TermuxActivity — sin este flag, launchMode="singleTask" con
     * un taskAffinity distinto al de la task que llama a startActivity() no crea la task nueva
     * de forma confiable en todas las versiones de Android. Con la task separada, el diálogo
     * "Salir de X11" (Minimizar/Cerrar) del visor solo afecta a esa task, no a la de Kairos
     * (bug real 2026-08-18). `internal`: compartido por EntornoX11Tab (tile "Entrar en X11"),
     * EntornoNativoTab (Snackbar "Abrir X11" tras iniciar el escritorio) y EntornoDistrosTab
     * (mismo Snackbar, camino con distro).
     */
    internal fun launchX11() {
        try {
            X11Service.start(requireContext())
            startActivity(
                Intent(requireContext(), KairosX11MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            android.util.Log.e("EntornoFragment", "launchX11() falló", e)
            if (!isAdded) return
            AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.entorno_dialog_no_pudo_abrir_x11_titulo))
                .setMessage(e.message ?: getString(R.string.entorno_error_desconocido))
                .setPositiveButton(getString(R.string.entorno_ok), null)
                .show()
        }
    }

    /** Copia la imagen elegida (content:// URI) a un archivo real de ~/Pictures — ni xfconf-query ni un proceso dentro de proot pueden leer un content:// URI directo (EntornoNative solo trabaja con rutas de filesystem reales). */
    private fun copyUriToWallpaperFile(uri: Uri): File? = try {
        val resolver = requireContext().contentResolver
        val mime = resolver.getType(uri)
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)?.takeIf { it.isNotBlank() } ?: "png"
        val destDir = File(com.termux.shared.termux.TermuxConstants.TERMUX_HOME_DIR_PATH, "Pictures").apply { mkdirs() }
        val dest = File(destDir, "kairos_wallpaper.$ext")
        resolver.openInputStream(uri)?.use { input -> dest.outputStream().use { output -> input.copyTo(output) } }
        if (dest.exists() && dest.length() > 0) dest else null
    } catch (_: Exception) {
        null
    }

    private fun applyPickedWallpaper(uri: Uri) {
        val target = pendingWallpaperTarget ?: return
        pendingWallpaperTarget = null
        Thread {
            val localFile = copyUriToWallpaperFile(uri)
            if (localFile == null) {
                if (!isAdded) return@Thread
                requireActivity().runOnUiThread { toast(getString(R.string.entorno_error_no_pudo_leer_imagen)) }
                return@Thread
            }
            val json = when (target) {
                is WallpaperTarget.Native -> EntornoNative.setWallpaperNative(target.de, localFile.absolutePath)
                is WallpaperTarget.Distro -> EntornoNative.setWallpaperDistro(target.distro, target.de, localFile.absolutePath)
            }
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                val ok = json.optBoolean("ok", false)
                val msg = if (ok) json.optString("message", getString(R.string.entorno_fondo_cambiado)) else errorDetail(json)
                Snackbar.make(requireView(), msg, Snackbar.LENGTH_LONG).show()
            }
        }.start()
    }
}

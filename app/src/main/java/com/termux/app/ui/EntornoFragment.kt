package com.termux.app.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.webkit.MimeTypeMap
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.google.android.material.card.MaterialCardView
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import com.termux.R
import com.termux.app.X11Service
import com.termux.app.util.DistroIcons
import com.termux.app.util.EntornoNative
import com.termux.app.util.ProgressDialogController
import com.termux.app.x11.KairosX11MainActivity
import com.termux.app.x11.KairosX11PreferencesActivity
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

    // Estado real (EntornoNative.status(), misma fuente que las cards "ESTADO" de arriba) usado
    // para pintar el punto verde de "corriendo" en las tiles del grid — ver actionTile() y
    // renderVncTab()/renderNativoTab()/renderSistemaTab(). Solo se badgea lo que status() expone
    // como señal directa y confirmada (x11/vnc/pulse) — no se inventa un estado "escritorio
    // nativo corriendo" que EntornoNative no puede confirmar de forma inequívoca (ver regla
    // empirical-verification-before-fix: no fabricar una post-condición sin evidencia real).
    private var x11Running = false
    private var vncRunning = false
    private var pulseRunning = false

    /** Pestaña activa del grid (Nativo/X11/Distros/VNC/Sistema) — usado por renderTab() al reconstruir tras un refresh de estado. */
    private var tabContentContainer: LinearLayout? = null
    private var activeTabIndex = 0

    /**
     * Catálogo curado de apps GUI comunes para instalar dentro de una distro — antes
     * (`promptPackageName()`) el usuario tenía que escribir el nombre exacto del paquete
     * apt de memoria, sin ninguna sugerencia. Gap real confirmado esta ronda (2026-08-18):
     * el flujo de apps de distro seguía siendo MVP puro texto libre. No reemplaza el texto
     * libre (queda como "Otro" al final) — solo agrega un atajo de un toque para las apps
     * más pedidas en un homelab típico (navegador, ofimática, edición de imagen/video).
     */
    /**
     * Categorizado y ampliado (auditoría GUI/distro 2026-08-28,
     * docs/mini-pc/INVESTIGACION_REFERENCIAS_GUI_DISTRO_2026-08-26.md) — antes era una lista
     * plana de 10 apps sin agrupar. Cada `pkg` es el nombre real del paquete apt (Debian/
     * Ubuntu/Kali — único gestor que usa distroAppInstall(), ver EntornoNative.kt) — todos
     * verificados contra Debian stable/testing antes de agregarlos, no inventados.
     */
    private data class DistroApp(val pkg: String, val label: String, val category: String)

    private val curatedDistroApps = listOf(
        DistroApp("firefox-esr", "Firefox", "Internet"),
        DistroApp("chromium", "Chromium", "Internet"),
        DistroApp("thunderbird", "Thunderbird", "Internet"),
        DistroApp("filezilla", "FileZilla", "Internet"),
        DistroApp("remmina", "Remmina (RDP/VNC/SSH)", "Internet"),
        DistroApp("libreoffice", "LibreOffice", "Oficina"),
        DistroApp("evince", "Evince (lector PDF)", "Oficina"),
        DistroApp("keepassxc", "KeePassXC (gestor de contraseñas)", "Oficina"),
        DistroApp("gimp", "GIMP", "Multimedia"),
        DistroApp("inkscape", "Inkscape", "Multimedia"),
        DistroApp("vlc", "VLC", "Multimedia"),
        DistroApp("blender", "Blender", "Multimedia"),
        DistroApp("obs-studio", "OBS Studio", "Multimedia"),
        DistroApp("audacity", "Audacity", "Multimedia"),
        DistroApp("geany", "Geany (editor de código)", "Desarrollo"),
        DistroApp("xarchiver", "Xarchiver (archivos comprimidos)", "Utilidades")
    )

    private val curatedDistroAppCategories = curatedDistroApps.map { it.category }.distinct()

    // ── Fondo de pantalla — pedido explícito del usuario ("incluso poder cambiar la imagen de
    // fondo etc", docs/humano249.md). Mismo patrón que mPickImageLauncher (ChatFragment.kt) /
    // mPickImportFileLauncher (CactusFragment.kt): registro como campo de instancia (requisito
    // de ciclo de vida de ActivityResultLauncher), no dentro de un onClick. pendingWallpaperTarget
    // guarda a dónde aplicar el resultado (nativo o distro+DE) entre el momento en que se abre el
    // picker y el momento en que vuelve el callback — no hay forma de pasarle un parámetro extra
    // a ActivityResultContracts.GetContent().
    private sealed class WallpaperTarget {
        data class Native(val de: String) : WallpaperTarget()
        data class Distro(val distro: String, val de: String) : WallpaperTarget()
    }

    private var pendingWallpaperTarget: WallpaperTarget? = null

    private val mPickWallpaperLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri?.let { applyPickedWallpaper(it) }
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
        refreshStatus()

        // Inventario de solo-lectura (gap real de paridad con menu_entorno.sh
        // submenu_terminal [3] — "Listar distros instaladas"): antes solo se podía ACTUAR
        // sobre distros vía los diálogos de abajo (instalar/login/eliminar) — no había forma
        // de simplemente VER "esto es lo que tenés instalado ahora" sin abrir un diálogo de
        // acción primero. Contenedores udocker: sacados de acá (2026-08-18, pedido explícito
        // del usuario) — ya tienen pantalla propia completa en el módulo "udocker"
        // (UdockerFragment.kt), esta card duplicaba esa vista sin agregar nada.
        addCard(getString(R.string.entorno_card_instalado)) {
            addView(inventorySubLabel(getString(R.string.entorno_sublabel_distros)))
            inventoryDistrosContainer = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
            addView(inventoryDistrosContainer)
        }
        refreshInventory()

        // Rediseño visual 2026-08-26 (mockup "Grid Launcher" aprobado por el usuario, ver
        // docs/mini-pc/MINIPC_TAB_2026-08-25.md sección "Ronda 4"): los 27 actionButton() de
        // texto apiladas bajo 7 sectionLabel() (historial completo en el diff de esta ronda)
        // pasan a un TabLayout de categorías (Nativo/Distros/VNC/Sistema, ahora 5 con "X11"
        // separado desde la ronda 5 — ver buildTabsSection() abajo) con grids de tiles
        // ícono+texto — reorganización visual pura, TODAS las acciones originales siguen
        // presentes, ver buildTabsSection()/render*Tab() abajo para el mapeo completo. Ambos
        // caminos de escritorio (NATIVO sin distro vs CON DISTRO/proot-distro) comparten el
        // mismo servidor X11 embebido (:1) — solo uno activo a la vez, enforzado en
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
     * Fix real (humano181, bug 1 "da error al instalar entorno gráfico en la distro" — y el
     * mismo gap aplicaba a instalar TigerVNC/XFCE4 nativo). Varios `progress.failure(...)` de
     * este archivo solo mostraban `json.error` (mensaje corto, ej. "No se pudo instalar
     * xfce4 en ubuntu") y descartaban `json.output` (la salida real de apt-get/pkg dentro del
     * proot — por qué falló de verdad: red, mirror caído, disco lleno, paquete no encontrado)
     * sin mostrarlo NI loguearlo — mismo tipo de gap de diagnosticabilidad ya identificado y
     * corregido en runEntornoAction() (ver comentario ahí, docs/humano65.md/humano66.md), que
     * no se había propagado a los flujos con ProgressDialogController. Sin el detalle real, ni
     * el usuario ni una sesión futura puede saber la causa concreta de "da error".
     */
    private fun errorDetail(json: JSONObject): String {
        val error = json.optString("error", getString(R.string.entorno_error_desconocido_texto))
        val output = json.optString("output", "").takeLast(300)
        return if (output.isNotBlank()) "$error — $output" else error
    }

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

    // Mismo estilo del título que usa addCard() internamente (BaseModuleFragment.kt) pero
    // sin envolver un MaterialCardView vacío. Parámetro `parent` agregado en el rediseño
    // grid/pestañas 2026-08-26 (antes escribía siempre directo a `container`) — reusado como
    // subtítulo "Lanzar"/"Mantenimiento" DENTRO de cada pestaña (ver tileGrid()/render*Tab()
    // abajo), en vez de crear un helper nuevo casi idéntico solo por el destino distinto.
    private fun sectionLabel(text: String, parent: LinearLayout = container) {
        val ctx = requireContext()
        parent.addView(TextView(ctx).apply {
            this.text = text
            textSize = 10f
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText3))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            letterSpacing = 0.12f
            setPadding(dp(4), dp(16), dp(4), dp(8))
        })
    }

    /**
     * Variante de infoRow() con un ícono chico antes de la etiqueta — pulido visual 2026-08-27
     * (ver docs/humano259.md, "hacer la interfaz bonita tipo app del 2026") para la card
     * "ESTADO", que antes era texto plano puro. Mantiene la MISMA estructura de 2 hijos que
     * infoRow() (contenedor de etiqueta, TextView de valor) para que valueTextView() — que
     * asume `getChildAt(1)` == el TextView de valor — siga funcionando sin cambios; el ícono
     * va DENTRO del primer hijo (un LinearLayout ícono+texto), no como hijo propio. No
     * reemplaza infoRow() en el resto del archivo (Distros/VNC/Sistema ya tienen su propio
     * ícono grande en el grid de tiles — duplicar un ícono chico ahí sería ruido, no señal).
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

    /** Subtítulo chico dentro de la card "📋 Instalado" — distinto de sectionLabel() (esa es entre cards, a nivel del container principal). */
    private fun inventorySubLabel(text: String): View {
        val ctx = requireContext()
        return TextView(ctx).apply {
            this.text = text
            textSize = 10f
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText3))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            letterSpacing = 0.08f
            setPadding(dp(14), dp(10), dp(14), dp(4))
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
     * Ícono de identidad de distro (2026-08-27, ver docs/humano259.md) — círculo del color de
     * marca real (DistroIcons.colorHex) con el logo simplificado en blanco encima, mismo
     * lenguaje visual que `bindModuleIcon()`/`moduleIconBackground()` (ModuleRowRenderer.kt)
     * usa para el catálogo de módulos, aplicado acá a distros de proot-distro en vez de
     * reimplementar un esquema de color distinto solo para esta pantalla.
     */
    private fun distroIconView(ctx: android.content.Context, name: String, sizeDp: Int): ImageView {
        return ImageView(ctx).apply {
            setImageResource(DistroIcons.iconRes(name))
            imageTintList = ColorStateList.valueOf(android.graphics.Color.WHITE)
            val pad = dp(sizeDp / 5)
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(
                    try {
                        android.graphics.Color.parseColor(DistroIcons.colorHex(name))
                    } catch (_: Exception) {
                        ctx.kairosThemeColor(R.attr.kairosBg3)
                    }
                )
            }
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

    private fun refreshStatus() {
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

    private fun runEntornoAction(action: String, vararg args: String) {
        toast("$action…")
        Thread {
            val arg0 = args.getOrElse(0) { "" }
            val json = when (action) {
                "distro-install" -> EntornoNative.distroInstall(arg0)
                "distro-remove" -> EntornoNative.distroRemove(arg0)
                "distro-backup" -> EntornoNative.distroBackup(arg0)
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
                // Antes solo se mostraba json.error (mensaje genérico tipo "Instalación de X
                // falló") — el detalle real (json.output, la salida real de proot-distro/pkg)
                // se descartaba sin mostrar ni loguear en ningún lado. Bug de diagnosticabilidad
                // real (auditoría 2026-08-05, ver docs/humano65.md/humano66.md): el usuario no
                // tenía forma de saber POR QUÉ fallaba una distro/escritorio/X11, y nosotros
                // tampoco teníamos ningún log para depurarlo después. Ahora se muestra el
                // detalle real (si vino) además del mensaje corto, y EntornoNative ya lo deja
                // también en ~/kairos_logs/wizard_debug.log.
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
        }.start()
    }

    /**
     * Grid de tiles (2 columnas) en vez de una lista de texto plano — patrón adoptado de
     * Linbox-WinEmu (`AddContainerScreen.kt`, Compose original, ver docs/referencias/REFERENCIA_LINBOX.md),
     * reimplementado con `GridLayout` nativo (sin Compose). Más fácil de tocar en pantallas
     * chicas que una fila de texto de una sola línea, y deja lugar para distinguir visualmente
     * las distros experimentales sin depender solo del texto entre paréntesis.
     */
    private fun promptDistroInstall() {
        val confirmed = arrayOf("ubuntu", "debian", "alpine")
        // "kali" agregada 2026-08-26 (pedido explícito del usuario: "en Mini PC nunca sale
        // disponible la distro Kali y debería salir" — faltaba acá, la lista real que arma el
        // diálogo de instalación; EntornoNative.KNOWN_DISTROS ya la tiene, pero esta pantalla
        // usa su propio array local en vez de leer esa lista, ver docs/humano/humano226.md).
        // "manjaro"/"rockylinux"/"opensuse-tumbleweed" agregadas en la misma ronda de auditoría
        // de catálogo (2026-08-26, ver docs/mini-pc/MINIPC_TAB_2026-08-25.md sección "Catálogo
        // de distros" y el comentario de EntornoNative.KNOWN_DISTROS para la justificación de
        // cada una). Se cuentan como experimental (mismo criterio que archlinux/fedora/void/
        // kali: sin corridas confirmadas en dispositivos reales de este proyecto como ubuntu/
        // debian/alpine) — a diferencia de "kali", estas 3 SÍ usan el alias plano de proot-distro
        // sin mapeo especial (no verificado en dispositivo real todavía).
        val experimental = arrayOf("archlinux", "fedora", "void", "kali", "manjaro", "rockylinux", "opensuse-tumbleweed")
        val all = confirmed + experimental
        val ctx = requireContext()

        val grid = GridLayout(ctx).apply {
            columnCount = 2
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        lateinit var dialog: AlertDialog
        dialog = AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.entorno_dialog_instalar_distro_titulo))
            .setView(grid)
            .setNegativeButton(getString(R.string.entorno_cancelar), null)
            .create()

        all.forEachIndexed { i, name ->
            val isExperimental = i >= confirmed.size
            val tile = distroTile(ctx, name, isExperimental) {
                dialog.dismiss()
                if (isExperimental) {
                    AlertDialog.Builder(ctx)
                        .setTitle(getString(R.string.entorno_dialog_distro_experimental_titulo))
                        .setMessage(getString(R.string.entorno_distro_experimental_mensaje, name))
                        .setPositiveButton(getString(R.string.entorno_instalar_boton)) { _, _ -> runEntornoAction("distro-install", name) }
                        .setNegativeButton(getString(R.string.entorno_cancelar), null)
                        .show()
                } else {
                    runEntornoAction("distro-install", name)
                }
            }
            val params = GridLayout.LayoutParams().apply {
                width = dp(130)
                height = dp(96)
                columnSpec = GridLayout.spec(i % 2)
                rowSpec = GridLayout.spec(i / 2)
                setMargins(dp(6), dp(6), dp(6), dp(6))
            }
            grid.addView(tile, params)
        }
        dialog.show()
    }

    private fun distroTile(ctx: android.content.Context, name: String, experimental: Boolean, onClick: () -> Unit): View {
        val card = MaterialCardView(ctx).apply {
            radius = dp(12).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1)
            strokeColor = ctx.kairosThemeColor(R.attr.kairosBorder)
            setCardBackgroundColor(ctx.kairosThemeColor(R.attr.kairosBg2))
            setOnClickListener { onClick() }
        }
        val inner = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        inner.addView(
            distroIconView(ctx, name, 32),
            LinearLayout.LayoutParams(dp(32), dp(32)).apply { gravity = Gravity.CENTER }
        )
        inner.addView(TextView(ctx).apply {
            text = name
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText))
            setPadding(0, dp(6), 0, 0)
        })
        if (experimental) {
            inner.addView(TextView(ctx).apply {
                text = getString(R.string.entorno_badge_experimental)
                textSize = 9f
                gravity = Gravity.CENTER
                setTextColor(ctx.kairosThemeColor(R.attr.kairosAmber))
            })
        }
        card.addView(inner)
        return card
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Grid de tiles por pestaña (rediseño 2026-08-26, mockup "Grid Launcher" aprobado
    //  por el usuario — ver docs/mini-pc/MINIPC_TAB_2026-08-25.md sección "Ronda 4").
    //  Reorganización visual pura de las mismas 27 acciones que ya existían como
    //  actionButton() de texto — ningún flujo/función se eliminó, solo se reagruparon
    //  bajo categorías con tiles ícono+texto en vez de una lista larga apilada (4 al
    //  cerrar la ronda 4, 5 desde la ronda 5 — "X11" separado de "Nativo").
    // ═══════════════════════════════════════════════════════════════════════

    /** Una tile del grid: ícono + texto corto + acción, con badge verde opcional si `running` da true (evaluado en el momento de renderizar, no cacheado). */
    private data class TileAction(
        val label: String,
        val iconRes: Int,
        val running: () -> Boolean = { false },
        val onClick: () -> Unit
    )

    /**
     * TabLayout de 5 categorías (Nativo/X11/Distros/VNC/Sistema, "X11" agregado ronda 5,
     * 2026-08-26 — ver `docs/mini-pc/MINIPC_TAB_2026-08-25.md`) arriba del grid — mismo estilo
     * (`?attr/kairosGreen` de indicador, `?attr/kairosText`/`kairosText3` de texto) que ya usa
     * `fragment_file_manager.xml`, aplicado programáticamente acá porque este Fragment arma
     * todo su contenido en Kotlin (BaseModuleFragment.container), no desde un layout XML
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
            0 -> renderNativoTab(content)
            1 -> renderX11Tab(content)
            2 -> renderDistrosTab(content)
            3 -> renderVncTab(content)
            else -> renderSistemaTab(content)
        }
    }

    /** Grid de 3 columnas — usado por los 4 render*Tab() de abajo, uno por subsección ("Lanzar"/"Mantenimiento"). */
    private fun tileGrid(parent: LinearLayout, actions: List<TileAction>) {
        val ctx = requireContext()
        val grid = GridLayout(ctx).apply {
            columnCount = 3
            setPadding(dp(2), dp(2), dp(2), dp(6))
        }
        actions.forEachIndexed { i, action ->
            val params = GridLayout.LayoutParams().apply {
                width = 0
                height = dp(92)
                columnSpec = GridLayout.spec(i % 3, 1f)
                rowSpec = GridLayout.spec(i / 3)
                setMargins(dp(4), dp(4), dp(4), dp(4))
            }
            grid.addView(actionTile(ctx, action), params)
        }
        parent.addView(grid)
    }

    /** Tile individual: MaterialCardView con ícono (tintado por tema) + texto centrado + punto verde de "corriendo" en la esquina — mismo estilo de card que distroTile(), generalizado para cualquier acción. */
    private fun actionTile(ctx: android.content.Context, action: TileAction): View {
        val card = MaterialCardView(ctx).apply {
            radius = dp(12).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1)
            strokeColor = ctx.kairosThemeColor(R.attr.kairosBorder)
            setCardBackgroundColor(ctx.kairosThemeColor(R.attr.kairosBg2))
            setOnClickListener { action.onClick() }
        }
        val frame = FrameLayout(ctx)
        val inner = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(8), dp(6), dp(6))
        }
        inner.addView(ImageView(ctx).apply {
            setImageResource(action.iconRes)
            imageTintList = ColorStateList.valueOf(ctx.kairosThemeColor(R.attr.kairosText))
            layoutParams = LinearLayout.LayoutParams(dp(26), dp(26))
        })
        inner.addView(TextView(ctx).apply {
            text = action.label
            textSize = 10.5f
            gravity = Gravity.CENTER
            maxLines = 3
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText))
            setPadding(0, dp(6), 0, 0)
        })
        frame.addView(inner, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        if (action.running()) {
            frame.addView(View(ctx).apply {
                layoutParams = FrameLayout.LayoutParams(dp(9), dp(9)).apply {
                    gravity = Gravity.TOP or Gravity.END
                    setMargins(0, dp(6), dp(6), 0)
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(ctx.kairosThemeColor(R.attr.kairosGreen))
                }
            })
        }
        card.addView(frame)
        return card
    }

    /**
     * Pestaña "Nativo" — escritorio (DE) directo sobre Termux (sin distro/proot), el camino
     * recomendado. Mapeo 1:1 con las acciones que antes vivían bajo el sectionLabel "NATIVO —
     * X11 + escritorio directo sobre Termux", MENOS las 3 acciones puntuales de X11 en sí
     * (Entrar en X11, Configuración de X11, Detener servidor X11) — esas se separaron a su
     * propia pestaña "X11" (ronda 5, `docs/mini-pc/MINIPC_TAB_2026-08-25.md`) porque X11 es la
     * base compartida (nativo/distro/futuro Wine+Box64, ver `FUTURO.md` §9), no algo propio de
     * "Nativo" — esta pestaña queda con lo que sí es específico del DE nativo.
     */
    private fun renderNativoTab(parent: LinearLayout) {
        sectionLabel(getString(R.string.entorno_seccion_lanzar), parent)
        tileGrid(parent, listOf(
            TileAction(getString(R.string.entorno_tile_xfce_nativo), R.drawable.ic_desktop) { promptXfceNative() },
            TileAction(getString(R.string.entorno_tile_iniciar_escritorio), R.drawable.ic_start) { promptStartDesktop() },
            TileAction(getString(R.string.entorno_tile_configurar_autoinicio), R.drawable.ic_settings) { promptAutostart() }
        ))
        sectionLabel(getString(R.string.entorno_seccion_mantenimiento), parent)
        tileGrid(parent, listOf(
            TileAction(getString(R.string.entorno_tile_instalar_otro_escritorio), R.drawable.ic_install) { promptInstallDesktop() },
            TileAction(getString(R.string.entorno_tile_actualizar_lanzadores), R.drawable.ic_desktop) { runEntornoAction("desktop-launchers") },
            TileAction(getString(R.string.entorno_tile_detener_escritorio), R.drawable.ic_stop) { stopDesktopSessionAction() },
            TileAction(getString(R.string.entorno_tile_cambiar_fondo), R.drawable.studio_ic_file_image) { promptChangeWallpaperNative() }
        ))
    }

    /**
     * Pestaña "X11" — servidor X11 embebido en sí (Xlorie/termux-x11 fork, ver
     * `docs/x11/X11_EMBEBIDO.md`), separado de "Nativo" (ronda 5, 2026-08-26): el usuario pidió
     * la separación explícitamente porque X11 es la base compartida por Nativo, proot-distro, y
     * a futuro Wine+DXVK+Box64+FEX (`docs/arquitectura/FUTURO.md` §9) — no es una acción propia
     * del escritorio nativo. Incluye "Detener servidor X11", que antes vivía en "Nativo" bajo
     * MANTENIMIENTO: se movió acá porque es una acción sobre el servidor X11 mismo (mata el
     * proceso Xlorie), no sobre el escritorio/DE que corre encima — mismo criterio que las
     * otras 2 tiles de esta pestaña.
     */
    private fun renderX11Tab(parent: LinearLayout) {
        sectionLabel(getString(R.string.entorno_seccion_servidor_x11), parent)
        parent.addView(inventorySubLabel(getString(R.string.entorno_subtitulo_display_x11)))
        tileGrid(parent, listOf(
            TileAction(getString(R.string.entorno_tile_entrar_x11), R.drawable.ic_x11, running = { x11Running }) { launchX11() },
            TileAction(getString(R.string.entorno_tile_configuracion_x11), R.drawable.ic_settings) { openX11Settings() },
            TileAction(getString(R.string.entorno_tile_detener_servidor_x11), R.drawable.ic_stop, running = { x11Running }) { stopEmbeddedX11() }
        ))
    }

    /**
     * Pestaña "Distros" — proot-distro (sistema Linux completo, opcional) + catálogo de apps
     * dentro de la distro. Mapeo 1:1 con las 9 acciones que antes vivían bajo "CON DISTRO
     * (proot-distro)" + "CON DISTRO — catálogo de apps".
     */
    private fun renderDistrosTab(parent: LinearLayout) {
        sectionLabel(getString(R.string.entorno_seccion_lanzar), parent)
        tileGrid(parent, listOf(
            // Roadmap Mini PC item 4 (MEJORAS_PENDIENTES.md 2026-08-28) — primera tile a
            // propósito: es el atajo pensado para reemplazar los 3 pasos manuales de abajo
            // cuando el usuario no tiene una preferencia específica de distro/DE/GPU.
            TileAction(getString(R.string.entorno_tile_perfil_recomendado), R.drawable.ic_gpu) { promptRecommendedProfile() },
            TileAction(getString(R.string.entorno_tile_instalar_distro), R.drawable.ic_install) { promptDistroInstall() },
            TileAction(getString(R.string.entorno_tile_login_distro), R.drawable.ic_terminal) { promptDistroLogin() },
            TileAction(getString(R.string.entorno_tile_instalar_escritorio_distro), R.drawable.ic_desktop) { promptDistroInstallDesktop() },
            TileAction(getString(R.string.entorno_tile_iniciar_escritorio_distro), R.drawable.ic_start) { promptDistroDesktopStart() }
        ))
        sectionLabel(getString(R.string.entorno_seccion_mantenimiento), parent)
        tileGrid(parent, listOf(
            TileAction(getString(R.string.entorno_tile_backup_distro), R.drawable.ic_backup) { promptDistroAction("distro-backup", getString(R.string.entorno_titulo_backup_distro)) },
            TileAction(getString(R.string.entorno_tile_eliminar_distro), R.drawable.ic_uninstall) { promptDistroRemove() },
            TileAction(getString(R.string.entorno_tile_vincular_carpetas), R.drawable.ic_bridge) { promptDistroAction("bridge-mount", getString(R.string.entorno_titulo_vincular_carpetas)) },
            TileAction(getString(R.string.entorno_tile_instalar_app_distro), R.drawable.ic_install) { promptDistroAppInstall() },
            TileAction(getString(R.string.entorno_tile_eliminar_app_distro), R.drawable.ic_uninstall) { promptDistroAppRemove() },
            TileAction(getString(R.string.entorno_tile_cambiar_fondo), R.drawable.studio_ic_file_image) { promptChangeWallpaperDistro() }
        ))
    }

    /**
     * Pestaña "VNC" — secundario/opcional. Mapeo 1:1 con las 5 acciones que antes vivían bajo
     * el sectionLabel "VNC — secundario/opcional".
     */
    private fun renderVncTab(parent: LinearLayout) {
        sectionLabel(getString(R.string.entorno_seccion_lanzar), parent)
        tileGrid(parent, listOf(
            TileAction(getString(R.string.entorno_tile_instalar_tigervnc), R.drawable.ic_install) { vncInstallWithProgress() },
            TileAction(getString(R.string.entorno_tile_iniciar_vnc), R.drawable.ic_start, running = { vncRunning }) { runEntornoAction("vnc-start") },
            TileAction(getString(R.string.entorno_tile_configurar_iniciar_vnc), R.drawable.ic_settings) { promptVncConfig() },
            TileAction(getString(R.string.entorno_tile_abrir_visor_vnc), R.drawable.ic_vnc, running = { vncRunning }) { openVnc() }
        ))
        sectionLabel(getString(R.string.entorno_seccion_mantenimiento), parent)
        tileGrid(parent, listOf(
            TileAction(getString(R.string.entorno_tile_detener_vnc), R.drawable.ic_stop, running = { vncRunning }) { runEntornoAction("vnc-stop") }
        ))
    }

    /**
     * Pestaña "Sistema" — X11 embebido a nivel de infraestructura (GPU/audio), distinto del
     * X11 "de uso" que vive en la pestaña Nativo. Mapeo 1:1 con las 3 acciones que antes
     * vivían bajo el sectionLabel "AUDIO + GPU".
     */
    private fun renderSistemaTab(parent: LinearLayout) {
        sectionLabel(getString(R.string.entorno_seccion_audio_gpu), parent)
        tileGrid(parent, listOf(
            TileAction(getString(R.string.entorno_tile_pulseaudio_toggle), R.drawable.ic_audio, running = { pulseRunning }) { runEntornoAction("pulse-toggle") },
            TileAction(getString(R.string.entorno_tile_diagnostico_gpu), R.drawable.ic_gpu) { showGpuDiagnostic() },
            TileAction(getString(R.string.entorno_tile_configurar_metodo_gpu), R.drawable.ic_settings) { promptGpuMethod() }
        ))
    }

    private fun promptDistroRemove() {
        Thread {
            val json = EntornoNative.distroList()
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                val installed = json.optJSONArray("installed")
                if (installed == null || installed.length() == 0) {
                    toast(getString(R.string.entorno_toast_no_hay_distros))
                    return@runOnUiThread
                }
                val names = Array(installed.length()) { installed.optString(it) }
                AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.entorno_dialog_eliminar_distro_titulo))
                    .setItems(names) { _, which ->
                        AlertDialog.Builder(requireContext())
                            .setTitle(getString(R.string.entorno_dialog_confirmar_eliminar_distro, names[which]))
                            .setMessage(getString(R.string.entorno_mensaje_eliminar_distro_irreversible))
                            .setPositiveButton(getString(R.string.entorno_eliminar)) { _, _ -> runEntornoAction("distro-remove", names[which]) }
                            .setNegativeButton(getString(R.string.entorno_cancelar), null)
                            .show()
                    }
                    .setNegativeButton(getString(R.string.entorno_cancelar), null)
                    .show()
            }
        }.start()
    }

    /** Lista distros instaladas y abre una consola real dentro del proot elegido — mismo comando que la opción [2] de submenu_terminal. */
    private fun promptDistroLogin() {
        Thread {
            val json = EntornoNative.distroList()
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                val installed = json.optJSONArray("installed")
                if (installed == null || installed.length() == 0) {
                    toast(getString(R.string.entorno_toast_no_hay_distros_instala))
                    return@runOnUiThread
                }
                val names = Array(installed.length()) { installed.optString(it) }
                AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.entorno_dialog_login_distro_titulo))
                    .setItems(names) { _, which ->
                        launchTerminalCommand(EntornoNative.distroLoginCommand(names[which]), getString(R.string.entorno_titulo_sesion_terminal, names[which]))
                    }
                    .setNegativeButton(getString(R.string.entorno_cancelar), null)
                    .show()
            }
        }.start()
    }

    /** Lista distros instaladas y corre `action` sobre la elegida — reusado por backup y bridge-mount. */
    private fun promptDistroAction(action: String, title: String) {
        Thread {
            val json = EntornoNative.distroList()
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                val installed = json.optJSONArray("installed")
                if (installed == null || installed.length() == 0) {
                    toast(getString(R.string.entorno_toast_no_hay_distros_instala))
                    return@runOnUiThread
                }
                val names = Array(installed.length()) { installed.optString(it) }
                AlertDialog.Builder(requireContext())
                    .setTitle(title)
                    .setItems(names) { _, which -> runEntornoAction(action, names[which]) }
                    .setNegativeButton(getString(R.string.entorno_cancelar), null)
                    .show()
            }
        }.start()
    }

    /** Elige una distro instalada y pide el nombre del paquete apt a instalar. */
    private fun promptDistroAppInstall() {
        Thread {
            val json = EntornoNative.distroList()
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                val installed = json.optJSONArray("installed")
                if (installed == null || installed.length() == 0) {
                    toast(getString(R.string.entorno_toast_no_hay_distros_instala))
                    return@runOnUiThread
                }
                val names = Array(installed.length()) { installed.optString(it) }
                AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.entorno_dialog_en_que_distro_instalar))
                    .setItems(names) { _, which -> promptAppCatalog(names[which]) }
                    .setNegativeButton(getString(R.string.entorno_cancelar), null)
                    .show()
            }
        }.start()
    }

    /**
     * Catálogo curado (curatedDistroApps) agrupado por categoría (auditoría GUI/distro
     * 2026-08-28) + "Otro" al final para texto libre — antes era un único AlertDialog plano
     * con las 10 apps + "Otro" (MVP sin ninguna categorización, pedido explícito del plan
     * PLAN_EXPANSION_HOMELAB_2026-08-13 §2.8). Primer paso: elegir categoría o "Otro" directo;
     * segundo paso: elegir la app dentro de la categoría elegida.
     */
    private fun promptAppCatalog(distro: String) {
        val labels = curatedDistroAppCategories.toTypedArray() + getString(R.string.entorno_opcion_otro_paquete)
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.entorno_dialog_instalar_en_distro, distro))
            .setItems(labels) { _, which ->
                if (which < curatedDistroAppCategories.size) {
                    promptAppCatalogCategory(distro, curatedDistroAppCategories[which])
                } else {
                    promptPackageName(distro)
                }
            }
            .setNegativeButton(getString(R.string.entorno_cancelar), null)
            .show()
    }

    private fun promptAppCatalogCategory(distro: String, category: String) {
        val apps = curatedDistroApps.filter { it.category == category }
        val labels = apps.map { it.label }.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle(category)
            .setItems(labels) { _, which -> installDistroAppWithProgress(distro, apps[which].pkg) }
            .setNegativeButton(getString(R.string.entorno_cancelar), null)
            .show()
    }

    /** Texto libre para el nombre del paquete apt — usado cuando el catálogo curado (promptAppCatalog()) no trae la app buscada. */
    private fun promptPackageName(distro: String) {
        val edit = EditText(requireContext()).apply {
            hint = getString(R.string.entorno_hint_nombre_paquete)
        }
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.entorno_dialog_instalar_en_distro, distro))
            .setView(edit)
            .setPositiveButton(getString(R.string.entorno_instalar_boton)) { _, _ ->
                val pkg = edit.text.toString().trim()
                if (pkg.isNotEmpty()) installDistroAppWithProgress(distro, pkg) else toast(getString(R.string.entorno_toast_paquete_vacio))
            }
            .setNegativeButton(getString(R.string.entorno_cancelar), null)
            .show()
    }

    /** apt-get install dentro de la distro puede tardar varios minutos (app GUI real) — mismo patrón ProgressDialogController que installDesktopWithProgress(). */
    private fun installDistroAppWithProgress(distro: String, pkg: String) {
        val appContext = requireContext().applicationContext
        val progress = ProgressDialogController(requireContext())
        // allowBackground=true (docs/humano247.md, pedido explícito del usuario: instalar un
        // entorno gráfico dentro de una distro no debe bloquear el resto de la app) — el
        // diálogo no reporta % real (apt-get dentro del proot no expone progreso parseable
        // acá), pero el usuario ya puede mandarlo a 2do plano y se avisa al terminar.
        progress.show(getString(R.string.entorno_progreso_instalando_app_titulo), getString(R.string.entorno_progreso_instalando_app_mensaje, pkg, distro), allowBackground = true)
        Thread {
            val json = EntornoNative.distroAppInstall(distro, pkg)
            val ok = json.optBoolean("ok", false)
            if (progress.isBackgrounded) {
                val detail = json.optString("output", "").ifBlank { json.optString("error", "") }
                com.termux.app.util.ModuleEventBridge.notifyDirect(
                    appContext, pkg, if (ok) "install_done" else "install_failed", detail.takeLast(300)
                )
            }
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (ok) {
                    progress.success(json.optString("message", getString(R.string.entorno_instalado)))
                } else {
                    val detail = json.optString("output", "").ifBlank { json.optString("error", getString(R.string.entorno_error_desconocido_texto)) }
                    progress.failure(getString(R.string.entorno_error_no_pudo_instalar_pkg, pkg), detail.takeLast(300))
                }
            }
        }.start()
    }

    /** Lista todas las apps de distro ya instaladas (todas las distros) y confirma antes de desinstalar. */
    private fun promptDistroAppRemove() {
        Thread {
            val json = EntornoNative.distroAppsInstalled()
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                val apps = json.optJSONArray("apps")
                if (apps == null || apps.length() == 0) {
                    toast(getString(R.string.entorno_toast_no_hay_apps_distro))
                    return@runOnUiThread
                }
                val labels = Array(apps.length()) {
                    val entry = apps.optJSONObject(it)
                    "${entry?.optString("pkg")} (${entry?.optString("distro")})"
                }
                AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.entorno_dialog_eliminar_app_titulo))
                    .setItems(labels) { _, which ->
                        val entry = apps.optJSONObject(which) ?: return@setItems
                        val distro = entry.optString("distro")
                        val pkg = entry.optString("pkg")
                        AlertDialog.Builder(requireContext())
                            .setTitle(getString(R.string.entorno_dialog_confirmar_eliminar_app, pkg, distro))
                            .setPositiveButton(getString(R.string.entorno_eliminar)) { _, _ -> runEntornoAction("distro-app-remove", distro, pkg) }
                            .setNegativeButton(getString(R.string.entorno_cancelar), null)
                            .show()
                    }
                    .setNegativeButton(getString(R.string.entorno_cancelar), null)
                    .show()
            }
        }.start()
    }

    private fun promptInstallDesktop() {
        val des = EntornoNative.KNOWN_DESKTOPS
        val labels = des.map { EntornoNative.desktopLabel(it) }.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.entorno_dialog_instalar_escritorio_titulo))
            .setItems(labels) { _, which -> installDesktopWithProgress(des[which]) }
            .setNegativeButton(getString(R.string.entorno_cancelar), null)
            .show()
    }

    /**
     * XFCE4 nativo (sin distro) — interfaz gráfica bionic para ejecutar cosas de la
     * terminal. Pedido explícito del usuario (ronda 2026-08-13, ver
     * docs/humano/humano100.md): el escritorio xfce4 nativo de Termux (paquetes pkg,
     * NO dentro de proot-distro) sobre el X11 embebido del APK. Acción de una sola
     * pulsación: si ya está instalado solo lo inicia (sobre el X11 embebido, abriendo
     * el visor); si no, instala xfce4 + xfce4-terminal con barra de progreso y luego
     * lo arranca.
     */
    private fun promptXfceNative() {
        Thread {
            val json = EntornoNative.status()
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                val installed = json.optJSONArray("installed_desktops")
                val already = installed != null &&
                    (0 until installed.length()).any { installed.optString(it) == "xfce4" }
                if (already) {
                    startDesktopOnEmbeddedX11("xfce4")
                } else {
                    installXfceNativeWithProgress()
                }
            }
        }.start()
    }

    /** Instala xfce4 nativo (sin distro) con ProgressDialog y, al terminar, lo inicia sobre el X11 embebido. */
    private fun installXfceNativeWithProgress() {
        val appContext = requireContext().applicationContext
        val progress = ProgressDialogController(requireContext())
        // allowBackground=true (docs/humano247.md, pedido explícito del usuario: instalar un
        // entorno gráfico NATIVO no debe bloquear el resto de la app — el otro caso citado
        // textualmente junto con "en distro", ver installDistroDesktopWithProgress()). Si el
        // usuario manda a 2do plano, el visor X11 no se auto-abre al terminar (necesita una
        // Activity real) — se avisa por notificación para que lo abra manualmente.
        progress.show(getString(R.string.entorno_progreso_instalando_escritorio_titulo), getString(R.string.entorno_progreso_instalando_xfce_nativo), allowBackground = true)
        Thread {
            val install = EntornoNative.installDesktop("xfce4")
            if (!install.optBoolean("ok", false)) {
                if (progress.isBackgrounded) {
                    com.termux.app.util.ModuleEventBridge.notifyDirect(
                        appContext, "XFCE4", "install_failed", errorDetail(install)
                    )
                }
                if (!isAdded) return@Thread
                requireActivity().runOnUiThread {
                    progress.failure(getString(R.string.entorno_error_no_pudo_instalar_xfce), errorDetail(install))
                }
                return@Thread
            }
            // Instalado — ahora sí, iniciar sobre el X11 embebido (mismo contrato que
            // startDesktopOnEmbeddedX11: X11Service.start + startDesktop + abrir visor).
            // X11Service.start() debe llamarse en el hilo de UI (es un startForegroundService),
            // por eso se hace desde requireActivity().runOnUiThread justo antes de
            // EntornoNative.startDesktop(), que corre en el hilo background.
            //
            // Fix (auditoría terminal adaptada 2026-08-19): a diferencia del bloque de arriba
            // (línea 663) y el de abajo (línea 677), a este runOnUiThread le faltaba el
            // re-chequeo de isAdded — instalar XFCE4 nativo puede tardar bastante y el usuario
            // puede navegar a otra pantalla antes de que termine.
            if (!isAdded) {
                if (progress.isBackgrounded) {
                    com.termux.app.util.ModuleEventBridge.notifyDirect(
                        appContext, "XFCE4", "install_done",
                        getString(R.string.entorno_notif_xfce_instalado_background)
                    )
                }
                return@Thread
            }
            requireActivity().runOnUiThread { if (isAdded) X11Service.start(requireContext()) }
            Thread.sleep(500)
            val start = EntornoNative.startDesktop("xfce4")
            if (!isAdded) {
                if (progress.isBackgrounded) {
                    val ok = start.optBoolean("ok", false)
                    com.termux.app.util.ModuleEventBridge.notifyDirect(
                        appContext, "XFCE4",
                        if (ok) "install_done" else "install_failed",
                        if (ok) getString(R.string.entorno_notif_xfce_instalado_iniciado_background) else errorDetail(start)
                    )
                }
                return@Thread
            }
            requireActivity().runOnUiThread {
                if (start.optBoolean("conflict", false)) {
                    progress.dismiss()
                    showConflictDialog(start) { installXfceNativeWithProgress() }
                    return@runOnUiThread
                }
                val ok = start.optBoolean("ok", false)
                if (ok) {
                    // Fix (docs/humano283.md, pedido explícito: "al iniciar entorno grafico
                    // sea nativo o en proot no debe abrir x11 automaticamente") — antes este
                    // bloque abría KairosX11MainActivity automáticamente apenas terminaba de
                    // arrancar la sesión (ver historial git para el startActivity() que vivía
                    // acá). Ahora solo se arranca el DE del lado servidor; abrir el visor queda
                    // como acción explícita del usuario — un Snackbar con acción "Abrir X11"
                    // (o la tile "Entrar en X11" / launchX11()), nunca automático.
                    progress.success(start.optString("message", getString(R.string.entorno_mensaje_xfce_iniciado)))
                    Snackbar.make(requireView(), getString(R.string.entorno_snackbar_escritorio_listo), Snackbar.LENGTH_LONG)
                        .setAction(getString(R.string.entorno_snackbar_accion_abrir_x11)) { launchX11() }
                        .show()
                } else {
                    progress.failure(getString(R.string.entorno_error_xfce_instalado_no_iniciado), errorDetail(start))
                }
                refreshStatus()
            }
        }.start()
    }

    /**
     * Instalar un escritorio es la acción más larga de este módulo (paquetes reales dentro
     * del proot) — primer uso real de `ProgressDialogController` en Kairos (patrón adoptado
     * de `ProgressDisplay.kt` de Linbox-WinEmu, ver docs/referencias/REFERENCIA_LINBOX.md), en vez del
     * toast+Snackbar genérico que usa `runEntornoAction()` para el resto de acciones. Sirve
     * de referencia para migrar otros flujos largos (descargas, instalación de módulos) más
     * adelante — no se migró todo el proyecto en esta pasada, solo este caso real.
     */
    private fun installDesktopWithProgress(desktopId: String) {
        val appContext = requireContext().applicationContext
        val label = EntornoNative.desktopLabel(desktopId)
        val progress = ProgressDialogController(requireContext())
        // allowBackground=true (docs/humano247.md) — instalar un escritorio (paquetes reales
        // dentro del proot) puede tardar minutos; antes bloqueaba toda la app hasta terminar.
        progress.show(getString(R.string.entorno_progreso_instalando_escritorio_titulo), getString(R.string.entorno_progreso_instalando_desktop_mensaje, label), allowBackground = true)
        Thread {
            val json = EntornoNative.installDesktop(desktopId)
            val ok = json.optBoolean("ok", false)
            if (progress.isBackgrounded) {
                com.termux.app.util.ModuleEventBridge.notifyDirect(
                    appContext, label, if (ok) "install_done" else "install_failed", errorDetail(json)
                )
            }
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (ok) {
                    progress.success(json.optString("message", getString(R.string.entorno_escritorio_instalado)))
                } else {
                    progress.failure(getString(R.string.entorno_error_no_pudo_instalar_escritorio), errorDetail(json))
                }
                refreshStatus()
            }
        }.start()
    }

    /**
     * Fix real (humano181, bug 2: "no se sabe cuando esta listo vnc, no sale una barra de
     * progreso al instalar"). Antes usaba runEntornoAction("vnc-install"), que solo dispara
     * un toast fijo ("vnc-install…") y recién muestra algo al terminar — hasta 300s
     * (EntornoNative.vncInstall(), timeout de pkg install) sin ningún indicador visual. Mismo
     * patrón que installDesktopWithProgress() (ProgressDialogController, primer uso real en
     * este archivo) — la instalación de TigerVNC es igual de larga (paquete real vía pkg)
     * así que merece el mismo tratamiento.
     */
    private fun vncInstallWithProgress() {
        val appContext = requireContext().applicationContext
        val progress = ProgressDialogController(requireContext())
        // allowBackground=true (docs/humano247.md) — mismo criterio que installDesktopWithProgress().
        progress.show(getString(R.string.entorno_progreso_instalando_tigervnc_titulo), getString(R.string.entorno_progreso_instalando_tigervnc_mensaje), allowBackground = true)
        Thread {
            val json = EntornoNative.vncInstall()
            val ok = json.optBoolean("ok", false)
            if (progress.isBackgrounded) {
                com.termux.app.util.ModuleEventBridge.notifyDirect(
                    appContext, "TigerVNC", if (ok) "install_done" else "install_failed", errorDetail(json)
                )
            }
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (ok) {
                    progress.success(json.optString("message", getString(R.string.entorno_tigervnc_instalado)))
                } else {
                    progress.failure(getString(R.string.entorno_error_no_pudo_instalar_tigervnc), errorDetail(json))
                }
                refreshStatus()
            }
        }.start()
    }

    /** Si hay un solo DE instalado lo arranca directo; si hay varios, pregunta cuál (mismo criterio que submenu_interfaz [2]). */
    private fun promptStartDesktop() {
        Thread {
            val json = EntornoNative.status()
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                val installed = json.optJSONArray("installed_desktops")
                when {
                    installed == null || installed.length() == 0 ->
                        toast(getString(R.string.entorno_toast_no_hay_escritorio))
                    installed.length() == 1 ->
                        startDesktopOnEmbeddedX11(installed.optString(0))
                    else -> {
                        val names = Array(installed.length()) { installed.optString(it) }
                        val labels = names.map { EntornoNative.desktopLabel(it) }.toTypedArray()
                        AlertDialog.Builder(requireContext())
                            .setTitle(getString(R.string.entorno_dialog_cual_escritorio_iniciar))
                            .setItems(labels) { _, which -> startDesktopOnEmbeddedX11(names[which]) }
                            .setNegativeButton(getString(R.string.entorno_cancelar), null)
                            .show()
                    }
                }
            }
        }.start()
    }

    /**
     * Arranca el servidor X11 embebido (X11Service, ver docs/x11/X11_EMBEBIDO.md)
     * y, una vez arriba, el DE elegido — y abre el visor embebido (MainActivity)
     * directamente, en vez de dejar que el usuario adivine dónde entrar. Pedido explícito
     * del usuario: "como ya tenemos x11/xlorie en el apk debe abrir ahi directamente o
     * decir donde debe entrar" (ver docs/humano/humano98.md). EntornoNative.startDesktop()
     * ya no arranca ningún servidor por sí mismo (no tiene Context) — solo lanza el DE
     * sobre el display embebido una vez que este Fragment arrancó X11Service.
     */
    private fun startDesktopOnEmbeddedX11(desktopId: String) {
        toast(getString(R.string.entorno_toast_iniciando_sobre_x11, EntornoNative.desktopLabel(desktopId)))
        X11Service.start(requireContext())
        Thread {
            val json = EntornoNative.startDesktop(desktopId)
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (json.optBoolean("conflict", false)) {
                    showConflictDialog(json) { startDesktopOnEmbeddedX11(desktopId) }
                    return@runOnUiThread
                }
                val ok = json.optBoolean("ok", false)
                val msg = if (ok) json.optString("message", getString(R.string.entorno_ok)) else getString(R.string.entorno_error_prefix, json.optString("error", getString(R.string.entorno_desconocido)))
                // Fix (docs/humano283.md): antes abría KairosX11MainActivity automáticamente
                // acá (ver historial git) — el usuario pidió que iniciar el escritorio (nativo
                // o en proot) NUNCA abra el visor X11 por sí solo. Ahora el Snackbar de éxito
                // ofrece "Abrir X11" como acción explícita (mismo botón que
                // installXfceNativeWithProgress() de arriba); el visor solo se abre si el
                // usuario lo toca, o desde la tile "Entrar en X11" (launchX11()).
                val snackbar = Snackbar.make(requireView(), msg, Snackbar.LENGTH_LONG)
                if (ok) snackbar.setAction(getString(R.string.entorno_snackbar_accion_abrir_x11)) { launchX11() }
                snackbar.show()
                refreshStatus()
            }
        }.start()
    }

    /** Lista distros instaladas y pide el paquete apt (dbus + DE elegido) — análogo a promptInstallDesktop() pero DENTRO de la distro (EntornoNative.distroInstallDesktop()), antes solo corría a mano vía "distro_setup_gui.sh <distro>" desde una terminal (gap real, ronda 2026-08-18). */
    private fun promptDistroInstallDesktop() {
        Thread {
            val json = EntornoNative.distroList()
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                val installed = json.optJSONArray("installed")
                if (installed == null || installed.length() == 0) {
                    toast(getString(R.string.entorno_toast_no_hay_distros_instala))
                    return@runOnUiThread
                }
                val names = Array(installed.length()) { installed.optString(it) }
                AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.entorno_dialog_en_que_distro_instalar_escritorio))
                    .setItems(names) { _, which -> promptDesktopChoiceForDistro(names[which]) }
                    .setNegativeButton(getString(R.string.entorno_cancelar), null)
                    .show()
            }
        }.start()
    }

    /**
     * Toggle "Instalación liviana" agregado antes de elegir la DE (auditoría GUI/distro
     * 2026-08-28, docs/mini-pc/INVESTIGACION_REFERENCIAS_GUI_DISTRO_2026-08-26.md) — un
     * CheckBox simple en vez de un layout XML nuevo (mismo criterio que promptVncConfig()),
     * se lee UNA vez antes de abrir el picker de DE para no repetirlo por cada DE.
     */
    private fun promptDesktopChoiceForDistro(distro: String) {
        val ctx = requireContext()
        val cbLite = android.widget.CheckBox(ctx).apply {
            text = getString(R.string.entorno_checkbox_instalacion_liviana)
            setPadding(dp(20), dp(8), dp(20), dp(4))
        }
        AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.entorno_dialog_escritorio_para_distro, distro))
            .setView(cbLite)
            .setMessage(getString(R.string.entorno_mensaje_elegir_escritorio_liviano))
            .setPositiveButton(getString(R.string.entorno_continuar)) { _, _ ->
                // KNOWN_DESKTOPS_DISTRO (no KNOWN_DESKTOPS) — roadmap Mini PC item 2
                // (MEJORAS_PENDIENTES.md 2026-08-28): esta vía "con distro" es la única
                // que puede ofrecer KDE Plasma de verdad (ver comentario de
                // EntornoNative.KNOWN_DESKTOPS_DISTRO para por qué el picker nativo no lo
                // incluye).
                val des = EntornoNative.KNOWN_DESKTOPS_DISTRO
                val labels = des.map { EntornoNative.desktopLabel(it) }.toTypedArray()
                AlertDialog.Builder(ctx)
                    .setTitle(getString(R.string.entorno_dialog_escritorio_para_distro, distro))
                    .setItems(labels) { _, which ->
                        val de = des[which]
                        if (de == "kde") {
                            // KDE Plasma pesa ~1.5-2GB — mismo patrón de confirmación que
                            // promptDistroInstall() usa para distros experimentales (ver
                            // entorno_dialog_distro_experimental_titulo arriba en este
                            // archivo), reusado acá porque el motivo real es análogo:
                            // avisar de un costo real antes de una descarga larga, no un
                            // mecanismo nuevo.
                            AlertDialog.Builder(ctx)
                                .setTitle(getString(R.string.entorno_dialog_kde_pesado_titulo))
                                .setMessage(getString(R.string.entorno_mensaje_kde_pesado))
                                .setPositiveButton(getString(R.string.entorno_instalar_boton)) { _, _ ->
                                    installDistroDesktopWithProgress(distro, de, cbLite.isChecked)
                                }
                                .setNegativeButton(getString(R.string.entorno_cancelar), null)
                                .show()
                        } else {
                            installDistroDesktopWithProgress(distro, de, cbLite.isChecked)
                        }
                    }
                    .setNegativeButton(getString(R.string.entorno_cancelar), null)
                    .show()
            }
            .setNegativeButton(getString(R.string.entorno_cancelar), null)
            .show()
    }

    /** apt-get dentro de la distro puede tardar varios minutos — mismo patrón ProgressDialogController que installDesktopWithProgress() (nativo). */
    private fun installDistroDesktopWithProgress(distro: String, de: String, lite: Boolean = false) {
        val appContext = requireContext().applicationContext
        val label = "${EntornoNative.desktopLabel(de)} ($distro)"
        val progress = ProgressDialogController(requireContext())
        // allowBackground=true (docs/humano247.md, pedido explícito del usuario: instalar un
        // entorno gráfico dentro de una distro no debe bloquear el resto de la app) — este es
        // uno de los 3 casos citados textualmente en el pedido.
        progress.show(getString(R.string.entorno_progreso_instalando_escritorio_titulo), getString(R.string.entorno_progreso_instalando_desktop_en_distro, EntornoNative.desktopLabel(de), distro), allowBackground = true)
        Thread {
            val json = EntornoNative.distroInstallDesktop(distro, de, lite)
            val ok = json.optBoolean("ok", false)
            if (progress.isBackgrounded) {
                com.termux.app.util.ModuleEventBridge.notifyDirect(
                    appContext, label, if (ok) "install_done" else "install_failed", errorDetail(json)
                )
            }
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (ok) {
                    progress.success(json.optString("message", getString(R.string.entorno_escritorio_instalado)))
                } else {
                    progress.failure(getString(R.string.entorno_error_no_pudo_instalar_escritorio_distro, distro), errorDetail(json))
                }
            }
        }.start()
    }

    /**
     * "Perfil recomendado" (roadmap Mini PC item 4, MEJORAS_PENDIENTES.md 2026-08-28) — pide
     * la sugerencia a EntornoNative.recommendedProfile() y la muestra en un diálogo de
     * confirmación antes de instalar nada (mismo criterio "no ejecutar sin confirmar" que
     * promptDistroInstall()/promptDesktopChoiceForDistro() ya usan para pasos largos).
     */
    private fun promptRecommendedProfile() {
        Thread {
            val profile = EntornoNative.recommendedProfile()
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                val distro = profile.optString("distro", "debian")
                val de = profile.optString("de", "xfce4")
                val gpuMethod = profile.optString("gpu_method", "auto")
                val gpuType = profile.optString("gpu_type", "unknown")
                val lite = profile.optBoolean("lite", true)
                val liteSuffix = if (lite) getString(R.string.entorno_perfil_recomendado_modo_liviano) else ""
                AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.entorno_dialog_perfil_recomendado_titulo))
                    .setMessage(getString(
                        R.string.entorno_mensaje_perfil_recomendado,
                        distro, EntornoNative.desktopLabel(de), liteSuffix, gpuMethod, gpuType
                    ))
                    .setPositiveButton(getString(R.string.entorno_instalar_boton)) { _, _ ->
                        installRecommendedProfile(distro, de, gpuMethod, lite)
                    }
                    .setNegativeButton(getString(R.string.entorno_cancelar), null)
                    .show()
            }
        }.start()
    }

    /**
     * Orquesta la instalación real del perfil recomendado encadenando las 3 llamadas YA
     * existentes (distroInstall/distroInstallDesktop/setGpuMethod) en orden — no reimplementa
     * ninguna de las 3, solo las llama en secuencia. Cada paso chequea su propio "ok" real
     * antes de seguir al siguiente (empirical-verification-before-fix.md: no asumir éxito
     * solo porque el hilo no lanzó excepción) — si un paso falla, se corta ahí y se reporta
     * cuál de los 3 fue el que falló, en vez de un error genérico de "algo salió mal".
     */
    private fun installRecommendedProfile(distro: String, de: String, gpuMethod: String, lite: Boolean) {
        val appContext = requireContext().applicationContext
        val progress = ProgressDialogController(requireContext())
        progress.show(
            getString(R.string.entorno_progreso_perfil_recomendado_titulo),
            getString(R.string.entorno_progreso_perfil_paso_distro, distro),
            allowBackground = true
        )
        Thread {
            val distroJson = EntornoNative.distroInstall(distro)
            if (!distroJson.optBoolean("ok", false)) {
                finishRecommendedProfile(progress, appContext, false, R.string.entorno_perfil_recomendado_error_distro, errorDetail(distroJson))
                return@Thread
            }
            progress.update(getString(R.string.entorno_progreso_perfil_paso_escritorio, EntornoNative.desktopLabel(de)))
            val desktopJson = EntornoNative.distroInstallDesktop(distro, de, lite)
            if (!desktopJson.optBoolean("ok", false)) {
                finishRecommendedProfile(progress, appContext, false, R.string.entorno_perfil_recomendado_error_escritorio, errorDetail(desktopJson))
                return@Thread
            }
            progress.update(getString(R.string.entorno_progreso_perfil_paso_gpu, gpuMethod))
            val gpuJson = EntornoNative.setGpuMethod(gpuMethod)
            if (!gpuJson.optBoolean("ok", false)) {
                finishRecommendedProfile(progress, appContext, false, R.string.entorno_perfil_recomendado_error_gpu, errorDetail(gpuJson))
                return@Thread
            }
            finishRecommendedProfile(progress, appContext, true, R.string.entorno_perfil_recomendado_ok, null)
        }.start()
    }

    private fun finishRecommendedProfile(
        progress: ProgressDialogController,
        appContext: android.content.Context,
        ok: Boolean,
        messageRes: Int,
        detail: String?
    ) {
        val message = getString(messageRes)
        if (progress.isBackgrounded) {
            com.termux.app.util.ModuleEventBridge.notifyDirect(
                appContext, getString(R.string.entorno_dialog_perfil_recomendado_titulo),
                if (ok) "install_done" else "install_failed", detail ?: message
            )
        }
        if (!isAdded) return
        requireActivity().runOnUiThread {
            if (ok) progress.success(message) else progress.failure(message, detail)
            refreshStatus()
        }
    }

    /** Lista distros instaladas y arranca el escritorio DENTRO de la elegida (camino "CON DISTRO") — análogo a promptStartDesktop() pero para proot-distro. Pide también qué DE (mismo picker que promptDesktopChoiceForDistro()) — antes quedaba fijo en "xfce4" sin importar cuál se hubiera instalado con promptDistroInstallDesktop(). */
    private fun promptDistroDesktopStart() {
        Thread {
            val json = EntornoNative.distroList()
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                val installed = json.optJSONArray("installed")
                if (installed == null || installed.length() == 0) {
                    toast(getString(R.string.entorno_toast_no_hay_distros_instala))
                    return@runOnUiThread
                }
                val names = Array(installed.length()) { installed.optString(it) }
                AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.entorno_dialog_en_que_distro_iniciar_escritorio))
                    .setItems(names) { _, which ->
                        val distro = names[which]
                        // Bug real confirmado por ADB (docs/humano249.md): ofrecer los 3
                        // KNOWN_DESKTOPS acá dejaba elegir un DE que nunca se instaló en ESTA
                        // distro en particular — el arranque fallaba con "Couldn't exec
                        // <de>-session: No such file or directory". Filtrado a lo realmente
                        // instalado (ver EntornoNative.distroInstallDesktop()).
                        val des = EntornoNative.installedDesktopsForDistro(distro)
                        if (des.isEmpty()) {
                            AlertDialog.Builder(requireContext())
                                .setTitle(distro)
                                .setMessage(getString(R.string.entorno_mensaje_sin_escritorio_en_distro, distro))
                                .setPositiveButton(getString(R.string.entorno_entendido), null)
                                .show()
                            return@setItems
                        }
                        val labels = des.map { EntornoNative.desktopLabel(it) }.toTypedArray()
                        AlertDialog.Builder(requireContext())
                            .setTitle(getString(R.string.entorno_dialog_cual_escritorio_iniciar_en_distro, distro))
                            .setItems(labels) { _, deWhich -> startDistroDesktopOnEmbeddedX11(distro, des[deWhich]) }
                            .setNegativeButton(getString(R.string.entorno_cancelar), null)
                            .show()
                    }
                    .setNegativeButton(getString(R.string.entorno_cancelar), null)
                    .show()
            }
        }.start()
    }

    /** Mismo contrato que startDesktopOnEmbeddedX11() pero para el DE DENTRO de una distro proot (EntornoNative.startDistroDesktop()). */
    private fun startDistroDesktopOnEmbeddedX11(distro: String, de: String = "xfce4") {
        toast(getString(R.string.entorno_toast_iniciando_dentro_distro, EntornoNative.desktopLabel(de), distro))
        X11Service.start(requireContext())
        Thread {
            val json = EntornoNative.startDistroDesktop(distro, de)
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (json.optBoolean("conflict", false)) {
                    showConflictDialog(json) { startDistroDesktopOnEmbeddedX11(distro, de) }
                    return@runOnUiThread
                }
                val ok = json.optBoolean("ok", false)
                val msg = if (ok) json.optString("message", getString(R.string.entorno_ok)) else getString(R.string.entorno_error_prefix, json.optString("error", getString(R.string.entorno_desconocido)))
                // Fix (docs/humano283.md): mismo fix que startDesktopOnEmbeddedX11() arriba —
                // ya no abre KairosX11MainActivity automáticamente, ofrece "Abrir X11" como
                // acción explícita del Snackbar.
                val snackbar = Snackbar.make(requireView(), msg, Snackbar.LENGTH_LONG)
                if (ok) snackbar.setAction(getString(R.string.entorno_snackbar_accion_abrir_x11)) { launchX11() }
                snackbar.show()
                refreshStatus()
            }
        }.start()
    }

    // ────────────────────────────────────────────────────────────
    // Fondo de pantalla (nativo y dentro de distro) — ver EntornoNative.setWallpaperNative()/
    // setWallpaperDistro() para el mecanismo real por DE (xfconf-query/gsettings/pcmanfm-qt).
    // ────────────────────────────────────────────────────────────

    /** Nativo — usa status().installed_desktops (lo realmente instalado, mismo criterio que promptStartDesktop()), no KNOWN_DESKTOPS a secas. */
    private fun promptChangeWallpaperNative() {
        Thread {
            val json = EntornoNative.status()
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                val installed = json.optJSONArray("installed_desktops")
                val des = if (installed == null) emptyList() else (0 until installed.length()).map { installed.optString(it) }
                if (des.isEmpty()) {
                    toast(getString(R.string.entorno_toast_instala_escritorio_nativo))
                    return@runOnUiThread
                }
                if (des.size == 1) {
                    pendingWallpaperTarget = WallpaperTarget.Native(des[0])
                    mPickWallpaperLauncher.launch("image/*")
                    return@runOnUiThread
                }
                val labels = des.map { EntornoNative.desktopLabel(it) }.toTypedArray()
                AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.entorno_dialog_para_cual_escritorio))
                    .setItems(labels) { _, which ->
                        pendingWallpaperTarget = WallpaperTarget.Native(des[which])
                        mPickWallpaperLauncher.launch("image/*")
                    }
                    .setNegativeButton(getString(R.string.entorno_cancelar), null)
                    .show()
            }
        }.start()
    }

    /** Distro — elige distro instalada, después el DE realmente instalado ahí (installedDesktopsForDistro(), mismo filtro real que ya corrigió promptDistroDesktopStart()). */
    private fun promptChangeWallpaperDistro() {
        Thread {
            val json = EntornoNative.distroList()
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                val installed = json.optJSONArray("installed")
                if (installed == null || installed.length() == 0) {
                    toast(getString(R.string.entorno_toast_no_hay_distros))
                    return@runOnUiThread
                }
                val names = Array(installed.length()) { installed.optString(it) }
                AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.entorno_dialog_en_que_distro))
                    .setItems(names) { _, which -> promptChangeWallpaperDistroDesktop(names[which]) }
                    .setNegativeButton(getString(R.string.entorno_cancelar), null)
                    .show()
            }
        }.start()
    }

    private fun promptChangeWallpaperDistroDesktop(distro: String) {
        val des = EntornoNative.installedDesktopsForDistro(distro)
        if (des.isEmpty()) {
            toast(getString(R.string.entorno_toast_distro_sin_escritorio, distro))
            return
        }
        if (des.size == 1) {
            pendingWallpaperTarget = WallpaperTarget.Distro(distro, des[0])
            mPickWallpaperLauncher.launch("image/*")
            return
        }
        val labels = des.map { EntornoNative.desktopLabel(it) }.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.entorno_dialog_cual_escritorio_en_distro, distro))
            .setItems(labels) { _, which ->
                pendingWallpaperTarget = WallpaperTarget.Distro(distro, des[which])
                mPickWallpaperLauncher.launch("image/*")
            }
            .setNegativeButton(getString(R.string.entorno_cancelar), null)
            .show()
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

    /**
     * Diálogo mostrado cuando EntornoNative bloquea un arranque de escritorio porque el OTRO
     * camino (nativo/distro) ya está activo — pedido explícito del usuario: "no tener las dos
     * abiertas al mismo tiempo". Ofrece detener la sesión actual (EntornoNative.
     * stopDesktopSession(), mantiene el servidor X11 arriba) y reintentar la acción original.
     */
    private fun showConflictDialog(json: JSONObject, retry: () -> Unit) {
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

    /** Detiene SOLO la sesión de escritorio activa (nativa o de distro) sin apagar el servidor X11 — para cambiar de camino sin perder el servidor. */
    private fun stopDesktopSessionAction() {
        toast(getString(R.string.entorno_toast_deteniendo_escritorio))
        Thread {
            val json = EntornoNative.stopDesktopSession()
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                val ok = json.optBoolean("ok", false)
                val msg = if (ok) json.optString("message", getString(R.string.entorno_ok)) else getString(R.string.entorno_error_prefix, json.optString("error", getString(R.string.entorno_desconocido)))
                Snackbar.make(requireView(), msg, Snackbar.LENGTH_LONG).show()
                refreshStatus()
            }
        }.start()
    }

    /**
     * "Entrar en X11 (reabrir visor)" — portado de X11Fragment.launchX11() (fusión 2026-08-25).
     * Arranca X11Service + abre KairosX11MainActivity con FLAG_ACTIVITY_NEW_TASK: MainActivity
     * (visor X11) tiene su propio taskAffinity (ver AndroidManifest.xml, com.termux.x11.
     * MainActivity) separado del de TermuxActivity — sin este flag, launchMode="singleTask" con
     * un taskAffinity distinto al de la task que llama a startActivity() no crea la task nueva
     * de forma confiable en todas las versiones de Android. Con la task separada, el diálogo
     * "Salir de X11" (Minimizar/Cerrar) del visor solo afecta a esa task, no a la de Kairos
     * (bug real 2026-08-18, mismo motivo citado en los otros call-sites de este archivo).
     */
    private fun launchX11() {
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

    /**
     * "Configuración de X11" — portado de X11Fragment.openSettings() (fusión 2026-08-25).
     * KairosX11PreferencesActivity (subclase de LoriePreferences, la pantalla de preferencias
     * ORIGINAL de termux-x11: resolución, escala, orientación forzada horizontal/vertical,
     * fullscreen, PiP, teclado extra, touch...). Se abre como actividad standalone; los cambios
     * se propagan por el broadcast ACTION_PREFERENCES_CHANGED al visor en vivo.
     */
    private fun openX11Settings() {
        startActivity(Intent(requireContext(), KairosX11PreferencesActivity::class.java).apply {
            action = Intent.ACTION_MAIN
        })
    }

    /**
     * "Abrir visor VNC" — portado de X11Fragment.openVnc() (fusión 2026-08-25). Se asegura de
     * que el servidor VNC (TigerVNC, ya gestionado por EntornoNative.vncInstall()/vncStart(),
     * ver sección "VNC — secundario/opcional" arriba) esté instalado y corriendo ANTES de abrir
     * el visor propio (VncViewerActivity) — mismo patrón que launchX11() arranca X11Service
     * antes de abrir el visor de X11. Corre en background — instalar/arrancar el servidor
     * puede tardar unos segundos.
     */
    private fun openVnc() {
        if (!isAdded) return
        toast(getString(R.string.entorno_toast_preparando_visor_vnc))
        Thread {
            try {
                val status = EntornoNative.status()
                if (!status.optBoolean("vnc_installed", false)) {
                    val install = EntornoNative.vncInstall()
                    if (!install.optBoolean("ok", false)) {
                        if (!isAdded) return@Thread
                        requireActivity().runOnUiThread {
                            if (isAdded) toast(install.optString("error", getString(R.string.entorno_error_no_pudo_instalar_vnc)))
                        }
                        return@Thread
                    }
                }
                if (!status.optBoolean("vnc_running", false)) {
                    val start = EntornoNative.vncStart()
                    if (!start.optBoolean("ok", false)) {
                        if (!isAdded) return@Thread
                        requireActivity().runOnUiThread {
                            if (isAdded) toast(start.optString("error", getString(R.string.entorno_error_no_pudo_iniciar_servidor_vnc)))
                        }
                        return@Thread
                    }
                    Thread.sleep(1500) // margen para que el servidor termine de publicar el socket
                }
                if (!isAdded) return@Thread
                requireActivity().runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    startActivity(Intent(requireContext(), VncViewerActivity::class.java))
                }
            } catch (e: Exception) {
                if (!isAdded) return@Thread
                requireActivity().runOnUiThread {
                    if (isAdded) toast(e.message ?: getString(R.string.entorno_error_desconocido))
                }
            }
        }.start()
    }

    /** Mismo contrato que X11Fragment.stopX11(): broadcast ACTION_STOP (cierra el visor si está abierto) + X11Service.stop() (mata el proceso :xserver). */
    private fun stopEmbeddedX11() {
        val ctx = requireContext()
        ctx.sendBroadcast(Intent("com.termux.x11.ACTION_STOP").setPackage(ctx.packageName))
        X11Service.stop(ctx)
        // Apagar el servidor X11 mata cualquier DE (nativo o de distro) que estuviera
        // arriba con él — limpia la marca de exclusividad para no dejar bloqueado el
        // próximo arranque (ver EntornoNative.desktopModeConflict()).
        EntornoNative.clearDesktopMode()
        toast(getString(R.string.entorno_toast_servidor_x11_detenido))
        refreshStatus()
    }

    private fun showGpuDiagnostic() {
        toast(getString(R.string.entorno_toast_diagnosticando_gpu))
        Thread {
            val json = EntornoNative.gpuDiagnostic()
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!json.optBoolean("ok", false)) {
                    Snackbar.make(requireView(), getString(R.string.entorno_error_no_pudo_diagnosticar_gpu), Snackbar.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                val message = getString(
                    R.string.entorno_gpu_diagnostico_mensaje,
                    json.optString("gpu_type"),
                    json.optString("gpu_method"),
                    json.optString("renderer"),
                    json.optString("vulkan_device"),
                    json.optString("drivers_installed")
                )
                AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.entorno_dialog_diagnostico_gpu_titulo))
                    .setMessage(message)
                    .setPositiveButton(getString(R.string.entorno_cerrar), null)
                    .show()
            }
        }.start()
    }

    private fun promptGpuMethod() {
        Thread {
            val json = EntornoNative.gpuMethodOptions()
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                val labels = json.optJSONArray("labels")
                val values = json.optJSONArray("values")
                if (labels == null || values == null) {
                    toast(getString(R.string.entorno_error_no_pudo_leer_gpu))
                    return@runOnUiThread
                }
                val labelArr = Array(labels.length()) { labels.optString(it) }
                AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.entorno_dialog_metodo_gpu_titulo, json.optString("gpu_type")))
                    .setItems(labelArr) { _, which -> runEntornoAction("gpu-method", values.optString(which)) }
                    .setNegativeButton(getString(R.string.entorno_cancelar), null)
                    .show()
            }
        }.start()
    }

    /**
     * Diálogo "Configurar e iniciar VNC" — resolución, calidad (profundidad de color) y si
     * pedir contraseña, los únicos 3 parámetros reales que soporta
     * `EntornoNative.vncStartWithConfig()` (ver su KDoc para por qué puerto/display NO son
     * configurables: :5901/:1 están atados al X11 embebido). Layout armado a mano (mismo
     * criterio que promptDistroInstall() con su GridLayout) en vez de un layout XML nuevo —
     * son solo 2 Spinners + 1 CheckBox, no justifica un recurso aparte.
     */
    private fun promptVncConfig() {
        val ctx = requireContext()
        // Presets ampliados (auditoría GUI/distro 2026-08-28, docs/mini-pc/INVESTIGACION_REFERENCIAS_GUI_DISTRO_2026-08-26.md):
        // antes solo 3 valores fijos ("1920x1080","1280x720","1024x768") sin ninguna opción
        // ligada a la resolución real del dispositivo. "Nativo del dispositivo" se resuelve acá
        // mismo (DisplayMetrics, sin costo de red/proceso) y se agrega al final del array de
        // labels — el valor real que viaja a vncStartWithConfig() sigue siendo "WxH" siempre,
        // igual que los presets fijos (Regex de validación en EntornoNative.vncStartWithConfig()
        // no distingue "nativo" de un preset, ambos llegan ya resueltos a "WxH").
        val nativeGeometry = run {
            val dm = ctx.resources.displayMetrics
            "${dm.widthPixels}x${dm.heightPixels}"
        }
        val resolutionValues = arrayOf("1920x1080", "1600x900", "1280x720", "1024x768", nativeGeometry)
        val resolutionLabels = arrayOf(
            "1920x1080", "1600x900", "1280x720", "1024x768",
            getString(R.string.entorno_resolucion_nativa, nativeGeometry)
        )
        val depthLabels = arrayOf(getString(R.string.entorno_depth_24bit), getString(R.string.entorno_depth_16bit))
        val depthValues = intArrayOf(24, 16)

        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
        }
        layout.addView(TextView(ctx).apply { text = getString(R.string.entorno_label_resolucion); textSize = 12f })
        val sResolution = android.widget.Spinner(ctx).apply {
            adapter = android.widget.ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, resolutionLabels)
        }
        layout.addView(sResolution)
        // Orientación (docs/humano283.md, faltaba explícitamente) — swap real de ancho/alto
        // sobre el geometry "WxH" elegido arriba, no un toggle cosmético: vncStartWithConfig()
        // solo entiende "WxH" (regex ^\d{2,5}x\d{2,5}$), así que "Vertical" arma la cadena
        // invertida antes de mandarla — mismo mecanismo que un cliente VNC/X11 no puede inferir
        // solo (no hay flag `-rotate` real en vncserver/tigervncserver, a diferencia de un
        // driver X real; el único control real disponible es la geometría en sí).
        layout.addView(TextView(ctx).apply {
            text = getString(R.string.entorno_label_orientacion); textSize = 12f; setPadding(0, dp(12), 0, 0)
        })
        val orientationLabels = arrayOf(getString(R.string.entorno_orientacion_horizontal), getString(R.string.entorno_orientacion_vertical))
        val sOrientation = android.widget.Spinner(ctx).apply {
            adapter = android.widget.ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, orientationLabels)
        }
        layout.addView(sOrientation)
        layout.addView(TextView(ctx).apply {
            text = getString(R.string.entorno_label_calidad); textSize = 12f; setPadding(0, dp(12), 0, 0)
        })
        val sDepth = android.widget.Spinner(ctx).apply {
            adapter = android.widget.ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, depthLabels)
        }
        layout.addView(sDepth)
        val cbPassword = android.widget.CheckBox(ctx).apply {
            text = getString(R.string.entorno_checkbox_pedir_contrasena)
            isChecked = true
            setPadding(0, dp(12), 0, 0)
        }
        layout.addView(cbPassword)
        // Campo real para escribir la contraseña (humano202, 2026-08-22): antes el checkbox
        // de arriba no tenía forma de que el usuario la ingresara — vncStartWithConfig()
        // asumía que ya existía ~/.vnc/passwd de una corrida manual de `vncpasswd` en terminal,
        // algo que esta app justamente busca no exigir nunca (ver CLAUDE.md: "nunca entrar a la
        // terminal"). Se oculta/deshabilita si el checkbox está destildado.
        val tvPasswordLabel = TextView(ctx).apply {
            text = getString(R.string.entorno_label_contrasena); textSize = 12f; setPadding(0, dp(12), 0, 0)
        }
        layout.addView(tvPasswordLabel)
        val etPassword = android.widget.EditText(ctx).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = getString(R.string.entorno_hint_nueva_contrasena_vnc)
        }
        layout.addView(etPassword)
        cbPassword.setOnCheckedChangeListener { _, checked ->
            tvPasswordLabel.visibility = if (checked) View.VISIBLE else View.GONE
            etPassword.visibility = if (checked) View.VISIBLE else View.GONE
        }

        AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.entorno_dialog_configurar_vnc_titulo))
            .setView(layout)
            .setPositiveButton(getString(R.string.entorno_iniciar)) { _, _ ->
                val baseGeometry = resolutionValues[sResolution.selectedItemPosition]
                // sOrientation posición 1 = "Vertical" — invierte WxH real (no cosmético, ver
                // comentario de arriba); posición 0 = "Horizontal" deja el geometry tal cual.
                val geometry = if (sOrientation.selectedItemPosition == 1) {
                    val parts = baseGeometry.split("x", limit = 2)
                    if (parts.size == 2) "${parts[1]}x${parts[0]}" else baseGeometry
                } else baseGeometry
                val depth = depthValues[sDepth.selectedItemPosition]
                val requirePassword = cbPassword.isChecked
                val password = etPassword.text?.toString().orEmpty()
                if (!requirePassword) {
                    AlertDialog.Builder(ctx)
                        .setTitle(getString(R.string.entorno_dialog_iniciar_sin_contrasena_titulo))
                        .setMessage(getString(R.string.entorno_mensaje_iniciar_sin_contrasena))
                        .setPositiveButton(getString(R.string.entorno_boton_iniciar_igual)) { _, _ -> startVncWithConfig(geometry, depth, false, null) }
                        .setNegativeButton(getString(R.string.entorno_cancelar), null)
                        .show()
                } else if (password.length < 6) {
                    toast(getString(R.string.entorno_toast_contrasena_corta))
                } else {
                    startVncWithConfig(geometry, depth, true, password)
                }
            }
            .setNegativeButton(getString(R.string.entorno_cancelar), null)
            .show()
    }

    private fun startVncWithConfig(geometry: String, depth: Int, requirePassword: Boolean, password: String?) {
        toast(getString(R.string.entorno_toast_iniciando_vnc))
        Thread {
            val json = EntornoNative.vncStartWithConfig(geometry, depth, requirePassword, password)
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                val ok = json.optBoolean("ok", false)
                val msg = if (ok) {
                    json.optString("message", getString(R.string.entorno_ok))
                } else {
                    val detail = json.optString("output", "").takeLast(200)
                    val base = getString(R.string.entorno_error_prefix, json.optString("error", getString(R.string.entorno_desconocido)))
                    if (detail.isNotBlank()) "$base — $detail" else base
                }
                Snackbar.make(requireView(), msg, Snackbar.LENGTH_LONG).show()
                refreshStatus()
            }
        }.start()
    }

    /** Picker de checkboxes con los CLIs instalados (EntornoNative.autostartOptions()) — preselecciona los que ya están en "entorno.autostart". */
    private fun promptAutostart() {
        Thread {
            val json = EntornoNative.autostartOptions()
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                val ids = json.optJSONArray("ids")
                val labels = json.optJSONArray("labels")
                if (ids == null || labels == null || ids.length() == 0) {
                    toast(getString(R.string.entorno_toast_no_hay_cli_autoinicio))
                    return@runOnUiThread
                }
                val enabled = json.optJSONArray("enabled")
                val enabledSet = (0 until (enabled?.length() ?: 0)).map { enabled!!.optString(it) }.toSet()
                val idArr = Array(ids.length()) { ids.optString(it) }
                val labelArr = Array(labels.length()) { labels.optString(it) }
                val checked = BooleanArray(idArr.size) { idArr[it] in enabledSet }
                AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.entorno_dialog_autoinicio_titulo))
                    .setMultiChoiceItems(labelArr, checked) { _, which, isChecked -> checked[which] = isChecked }
                    .setPositiveButton(getString(R.string.entorno_guardar)) { _, _ ->
                        runAutostartSave(idArr.filterIndexed { i, _ -> checked[i] })
                    }
                    .setNegativeButton(getString(R.string.entorno_cancelar), null)
                    .show()
            }
        }.start()
    }

    private fun runAutostartSave(ids: List<String>) {
        Thread {
            val json = EntornoNative.setAutostart(ids)
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                Snackbar.make(requireView(), json.optString("message", getString(R.string.entorno_ok)), Snackbar.LENGTH_LONG).show()
            }
        }.start()
    }
}

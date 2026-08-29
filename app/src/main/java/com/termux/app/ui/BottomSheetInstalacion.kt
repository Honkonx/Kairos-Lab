package com.termux.app.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import android.widget.ProgressBar
import androidx.appcompat.widget.SwitchCompat
import androidx.fragment.app.DialogFragment
import com.termux.R
import com.google.android.material.snackbar.Snackbar
import com.termux.app.ModuleController
import com.termux.app.util.kairosThemeColor

class BottomSheetInstalacion : DialogFragment() {

    private var moduleId: String = ""
    private var moduleName: String = ""
    private var moduleIcon: String = ""
    private var moduleIconBg: String = ""
    private var moduleDescription: String = ""
    private var modulePort: String = ""
    private var moduleSize: String = ""
    private var moduleType: String = ""
    private var requiresProot: Boolean = false
    private var hasVariants: Boolean = false
    private var estimate: String = ""

    private var selectedVariant = 0
    private var installBtn: TextView? = null
    // Pedido explícito del usuario (ver cita real en
    // docs/viejo/AUDITORIA_UX_INSTALACION_2026-08-19.md): "en la ventana de instalar
    // debe salir la opcion o un switch para la instalacion silenciosa que al tocar se empieze
    // a instalar pero se cierre la ventana y el usuario pueda seguir utilizando el apk,
    // ademas cuando termine debe avisar". false por default — el comportamiento visible
    // (progreso bloqueante + Snackbar) sigue siendo el default, este switch es opt-in.
    private var silentInstall = false
    // 2026-08-10: force=true se usa desde la Tienda de plugins ("Cambiar método") — el
    // script de instalación re-ejecuta con --force para reinstalar sobre el método ya
    // elegido (ver ModuleController.installModule: sin --force es casi siempre un no-op).
    private var forceReinstall: Boolean = false
    // Origen de instalación (2026-08-23, pedido explícito del usuario, ver docs/humano206.md):
    // "Limpia" es el flujo de siempre (ModuleController.installModule, corre el script completo
    // del módulo). "GitHub" es nuevo — busca un .deb pre-armado en GitHub Releases
    // (ModuleDebInstaller) y lo instala vía moduledeb extract/verify/apply, sin correr el
    // instalador completo. Deliberadamente NO se agrega una 3ra opción "paquete local" acá — esa
    // sigue viviendo donde ya estaba, en la pantalla de Plugins ("📦 Paquete local").
    private var useGithubSource = false
    private var installOriginGroup: LinearLayout? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Pedido explícito: solo el botón "Cancelar" debe cerrar esta pantalla —
        // un tap afuera o el botón atrás no deben poder cerrarla por accidente,
        // ya que el usuario no tiene forma de saber si la instalación (que sigue
        // corriendo en background vía ModuleController.installModule()) continúa
        // o no después de un cierre accidental.
        isCancelable = false
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(MATCH_PARENT, WRAP_CONTENT)
    }

    override fun onCreateView(inflater: LayoutInflater, c: ViewGroup?, b: Bundle?): View? {
        val density = resources.displayMetrics.density
        fun dp(d: Int) = (d * density).toInt()
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(20))
            setBackgroundColor(context.kairosThemeColor(R.attr.kairosBg2))
            minimumHeight = dp(400)

            // Icon (64dp radius 14dp)
            addView(android.widget.ImageView(context).apply {
                setImageResource(com.termux.app.util.ModuleIcons.forModule(moduleId))
                setColorFilter(Color.WHITE)
                scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
                val pad = dp(16)
                setPadding(pad, pad, pad, pad)
                try {
                    val bg = GradientDrawable().apply {
                        setColor(Color.parseColor(moduleIconBg))
                        cornerRadius = dp(14).toFloat()
                    }
                    background = bg
                } catch (_: Exception) {}
                layoutParams = LinearLayout.LayoutParams(dp(64), dp(64)).also {
                    it.gravity = Gravity.CENTER
                }
            })

            // Name: 22sp bold
            addView(TextView(context).apply {
                text = moduleName
                textSize = 22f
                gravity = Gravity.CENTER
                setTextColor(context.kairosThemeColor(R.attr.kairosText))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).also {
                    it.topMargin = dp(12)
                }
            })

            // Description: 14sp
            addView(TextView(context).apply {
                text = moduleDescription
                textSize = 14f
                gravity = Gravity.CENTER
                maxLines = 2
                setTextColor(context.kairosThemeColor(R.attr.kairosText2))
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).also {
                    it.topMargin = dp(4)
                }
            })

            // Chips: min 80dp wide, 32dp tall, 12sp, bg3 + border
            val chips = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).also {
                    it.topMargin = dp(16)
                }
            }
            for (chip in listOfNotNull(
                moduleSize.takeIf { it.isNotEmpty() },
                moduleType.takeIf { it.isNotEmpty() }?.let { if (it == "proot") getString(R.string.install_sheet_chip_proot) else getString(R.string.install_sheet_chip_type_format, it) },
                modulePort.takeIf { it.isNotEmpty() }?.let { getString(R.string.install_sheet_chip_port_format, it) },
                estimate.takeIf { it.isNotEmpty() }?.let { getString(R.string.install_sheet_chip_estimate_format, it) }
            )) {
                chips.addView(TextView(context).apply {
                    text = chip
                    textSize = 12f
                    setTextColor(context.kairosThemeColor(R.attr.kairosText2))
                    setPadding(dp(8), dp(4), dp(8), dp(4))
                    setBackgroundColor(context.kairosThemeColor(R.attr.kairosBg3))
                    layoutParams = LinearLayout.LayoutParams(dp(80), dp(32)).also {
                        it.rightMargin = dp(6)
                        it.gravity = Gravity.CENTER
                    }
                })
            }
            addView(chips)

            // Variant selector
            if (hasVariants) {
                val variantGroup = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).also {
                        it.topMargin = dp(16)
                    }
                }
                val variants = getVariants()
                for (i in variants.indices) {
                    val (title, desc) = variants[i]
                    val isSelected = i == selectedVariant
                    val card = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(14), dp(12), dp(14), dp(12))
                        setOnClickListener {
                            selectedVariant = i
                            updateVariantGroup(variantGroup)
                            updateInstallButton(variants)
                        }
                        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).also {
                            it.bottomMargin = dp(8)
                        }
                    }
                    // Set card background/border via GradientDrawable
                    val gd = GradientDrawable().apply {
                        cornerRadius = dp(8).toFloat()
                        if (isSelected) {
                            setColor(Color.parseColor("#0A22C55E"))
                            setStroke(dp(1), Color.parseColor("#22C55E"))
                        } else {
                            setColor(context.kairosThemeColor(R.attr.kairosBg3))
                            setStroke(dp(1), context.kairosThemeColor(R.attr.kairosBorder))
                        }
                    }
                    card.background = gd

                    // Top row: radio + title + badge
                    val topRow = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                    }
                    topRow.addView(TextView(context).apply {
                        text = if (isSelected) "◉" else "○"
                        textSize = 16f
                        setTextColor(if (isSelected) context.kairosThemeColor(R.attr.kairosGreen) else context.kairosThemeColor(R.attr.kairosText2))
                        layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).also {
                            it.rightMargin = dp(8)
                        }
                    })
                    topRow.addView(TextView(context).apply {
                        text = title
                        textSize = 13f
                        setTextColor(context.kairosThemeColor(R.attr.kairosText))
                        layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                    })
                    if (i == 0) {
                        topRow.addView(TextView(context).apply {
                            text = getString(R.string.install_sheet_badge_recommended)
                            textSize = 10f
                            tag = "badge_${i}"
                            setTypeface(typeface, android.graphics.Typeface.BOLD)
                            setTextColor(context.kairosThemeColor(R.attr.kairosGreen))
                            setPadding(dp(8), dp(2), dp(8), dp(2))
                            val badgeBg = GradientDrawable().apply {
                                cornerRadius = dp(10).toFloat()
                                setColor(Color.parseColor("#1A22C55E"))
                            }
                            background = badgeBg
                        })
                    }
                    card.addView(topRow)

                    // Description row
                    if (desc.isNotEmpty()) {
                        card.addView(TextView(context).apply {
                            text = desc
                            textSize = 12f
                            setTextColor(context.kairosThemeColor(R.attr.kairosText2))
                            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).also {
                                it.topMargin = dp(4)
                                it.leftMargin = dp(28)
                            }
                        })
                    }
                    variantGroup.addView(card)
                }
                addView(variantGroup)
            }

            // Selector de origen: Limpia (de siempre) vs GitHub (paquete .deb pre-armado, ver
            // ModuleDebInstaller) — 2026-08-23, pedido explícito del usuario. Mismo estilo visual
            // que los "chips" de arriba, simple (2 opciones, sin necesitar el patrón completo de
            // variantGroup con radios/descripciones largas que sí necesitan las variantes reales).
            val originGroup = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).also {
                    it.topMargin = dp(16)
                }
            }
            fun originChip(label: String, isGithub: Boolean): TextView = TextView(context).apply {
                text = label
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(10), dp(8), dp(10))
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).also {
                    if (!isGithub) it.rightMargin = dp(6) else it.leftMargin = dp(6)
                }
                setOnClickListener {
                    useGithubSource = isGithub
                    updateOriginGroup(originGroup)
                }
            }
            originGroup.addView(originChip(getString(R.string.install_sheet_origin_clean), false))
            originGroup.addView(originChip(getString(R.string.install_sheet_origin_github), true))
            installOriginGroup = originGroup
            addView(originGroup)
            updateOriginGroup(originGroup)

            // Switch de instalación silenciosa (2026-08-20) — vive en esta hoja compartida
            // (BottomSheetInstalacion), no en cada Fragment: se abre para TODOS los módulos
            // desde ModulesFragment/PluginsFragment, así que un solo switch acá cubre
            // n8n/hermes/ciberseguridad (los 3 módulos citados por el usuario) y el resto sin
            // duplicar nada por módulo.
            //
            // Límite de instalaciones simultáneas (pedido explícito del usuario, ver
            // InstallQueueManager/docs/arquitectura/COLA_INSTALACION_MODULOS.md): con 4+
            // módulos instalando ahora mismo, activar este switch solo llevaría a que
            // installModule() encole la instalación igual (el límite real es incondicional,
            // no depende del switch) — se deshabilita visualmente para que el usuario no
            // active algo que no va a arrancar de inmediato sin que quede claro por qué.
            val queueAtCapacity = com.termux.app.util.InstallQueueManager.isAtCapacity()
            if (queueAtCapacity) silentInstall = false
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).also {
                    it.topMargin = dp(16)
                }
                addView(TextView(context).apply {
                    text = getString(R.string.install_sheet_label_silent_install)
                    textSize = 13f
                    setTextColor(context.kairosThemeColor(R.attr.kairosText2))
                    layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                })
                addView(SwitchCompat(context).apply {
                    thumbTintList = ContextCompat.getColorStateList(context, R.color.switch_thumb_color)
                    trackTintList = ContextCompat.getColorStateList(context, R.color.switch_track_color)
                    isChecked = silentInstall
                    isEnabled = !queueAtCapacity
                    setOnCheckedChangeListener { _, checked -> silentInstall = checked }
                })
            })
            if (queueAtCapacity) {
                addView(TextView(context).apply {
                    text = getString(R.string.install_sheet_queue_capacity_warning, com.termux.app.util.InstallQueueManager.MAX_CONCURRENT_INSTALLS)
                    textSize = 12f
                    setTextColor(context.kairosThemeColor(R.attr.kairosAmber))
                    layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).also {
                        it.topMargin = dp(4)
                    }
                })
            }

            // Install button with installation handling
            val needProot = requiresProot && !isProotInstalled()
            val btn = TextView(context).apply {
                text = buildButtonText()
                textSize = 14f
                gravity = Gravity.CENTER
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                if (needProot) {
                    setBackgroundColor(context.kairosThemeColor(R.attr.kairosAmber))
                    setTextColor(Color.BLACK)
                } else {
                    setBackgroundColor(context.kairosThemeColor(R.attr.kairosGreen))
                    setTextColor(Color.BLACK)
                }
                setPadding(dp(16), dp(12), dp(16), dp(12))
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).also {
                    it.topMargin = dp(24)
                }
                setOnClickListener {
                    if (needProot) return@setOnClickListener
                    if (!isAdded) return@setOnClickListener
                    val variant = if (hasVariants) getVariantId(selectedVariant) else null
                    if (useGithubSource) {
                        startGithubInstall()
                        return@setOnClickListener
                    }
                    if (silentInstall) {
                        startSilentInstall(variant)
                        return@setOnClickListener
                    }
                    isEnabled = false
                    visibility = View.GONE
                    // Sin output crudo de pkg/apt/npm en pantalla — solo spinner + estado
                    // corto. El log completo se guarda en ~/kairos_logs/install_<id>.log
                    // (ModuleController.installLogFile) para diagnosticar si algo falla.
                    val progressBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                        isIndeterminate = true
                        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).also { it.topMargin = dp(8) }
                    }
                    addView(progressBar)
                    val statusView = TextView(context).apply {
                        text = if (forceReinstall) getString(R.string.install_sheet_status_changing_method) else getString(R.string.install_sheet_status_installing)
                        textSize = 13f
                        gravity = Gravity.CENTER
                        setTextColor(context.kairosThemeColor(R.attr.kairosText2))
                        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).also { it.topMargin = dp(8) }
                    }
                    addView(statusView)

                    // Bug real: antes se derivaba el --variant del TEXTO del título
                    // (ej. "⚡ Termux GPU" -> substringAfter(" ") -> "termux gpu", con
                    // espacio incluido). Los scripts (ollama.sh/claude.sh) esperan un id
                    // exacto de una sola palabra ("gpu"/"standard", "native"/"legacy") —
                    // un string con espacio no matchea ningún case del script, así que
                    // el case no hacía NADA pero igual se llamaba mark_done "..._install"
                    // después: quedaba un checkpoint de "instalado" sin instalar nada, y
                    // el registry terminaba con la versión del repo APT (pkg show, que
                    // devuelve datos aunque el paquete no esté instalado) — exactamente
                    // el síntoma reportado: "v0.31.1" en la UI con el switch en Inactivo.
                    // n8n no lo sufría porque sus dos variantes ("proot"/"udocker") son
                    // de una sola palabra y coincidían por accidente.
                    // (variant ya se calculó arriba, antes del branch de silentInstall.)

                    com.termux.app.ModuleController.installModule(moduleId, requireContext(), variant, forceReinstall, { _ -> }) { success ->
                        if (!isAdded) return@installModule
                        activity?.runOnUiThread {
                            // isAdded solo confirma que el fragment sigue adjunto, no que la
                            // FragmentManager admite commits (ver dismissAllowingStateLoss abajo) —
                            // este callback puede llegar minutos después (n8n ~5min, OpenClaw ~2min),
                            // cuando el usuario ya navegó a otra pantalla y la Activity guardó su estado.
                            if (!isAdded) return@runOnUiThread
                            val v = view ?: return@runOnUiThread
                            removeView(progressBar)
                            removeView(statusView)
                            if (success) {
                                Snackbar.make(v, getString(R.string.install_sheet_snackbar_success), Snackbar.LENGTH_LONG).show()
                                dismissAllowingStateLoss()
                            } else {
                                Snackbar.make(v, getString(R.string.install_sheet_snackbar_failed, moduleId), Snackbar.LENGTH_LONG).show()
                                visibility = View.VISIBLE
                                isEnabled = true
                            }
                        }
                    }
                }
            }
            installBtn = btn
            addView(btn)

            // Cancel
            addView(TextView(context).apply {
                text = getString(R.string.install_sheet_btn_cancel)
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(context.kairosThemeColor(R.attr.kairosText2))
                setPadding(dp(16), dp(12), dp(16), dp(12))
                setOnClickListener {
                    // Bug real, pedido explícito del usuario (ver cita real en
                    // docs/viejo/AUDITORIA_UX_INSTALACION_2026-08-19.md): "en muchos
                    // modulos al dar cancelar mientras se instala se sigue instalando en
                    // segundo plano, al dar cancelar deberia matar esa terminal y proceso de
                    // instalacion en backend". Antes esto solo cerraba la hoja — el Process real
                    // (ModuleController.installModule) seguía corriendo sin que nadie lo matara.
                    // cancelInstall() es no-op silencioso si todavía no se tocó "Instalar" para
                    // este módulo.
                    com.termux.app.ModuleController.cancelInstall(moduleId)
                    dismissAllowingStateLoss()
                }
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            })
        }
    }

    /**
     * Instalación silenciosa (2026-08-20) — pedido explícito del usuario (ver cita real en el
     * comentario de [silentInstall]): arranca la instalación real (mismo
     * ModuleController.installModule() de siempre) y cierra la hoja DE INMEDIATO, sin esperar a
     * que termine — el usuario sigue navegando la app con normalidad mientras corre en
     * background. [onComplete] llega minutos después potencialmente con este DialogFragment ya
     * destruido, así que el aviso de fin NO puede ser un Toast/Snackbar atado a esta vista —
     * usa una notificación Android real (ModuleEventBridge, mismo canal que el resto de eventos
     * de módulo) para que llegue igual sin importar en qué pantalla esté el usuario.
     *
     * "install_failed" se dispara siempre desde acá (éxito y fallo) porque el lado bash
     * (`notify_event ... install_done`, ver modulos/lib.sh) solo cubre el camino de ÉXITO — un
     * fallo silencioso no tenía NINGÚN aviso proactivo antes de este fix.
     */
    private fun startSilentInstall(variant: String?) {
        val appContext = requireContext().applicationContext
        val id = moduleId
        val name = moduleName
        // Mismo criterio que BaseModuleFragment.installModuleInBackground() — con 4+ módulos
        // instalando ahora mismo, installModule() encola esta en vez de arrancarla; avisar del
        // lado del toast en vez de dejar que el usuario piense que ya arrancó.
        val queuedText = com.termux.app.ModuleController.INSTALL_QUEUED_MESSAGE
        Toast.makeText(
            appContext,
            if (com.termux.app.util.InstallQueueManager.isAtCapacity()) queuedText else getString(R.string.install_sheet_toast_silent_installing, name),
            Toast.LENGTH_SHORT
        ).show()
        com.termux.app.ModuleController.installModule(id, requireContext(), variant, forceReinstall, { _ -> }) { success ->
            com.termux.app.util.ModuleEventBridge.notifyDirect(
                appContext,
                id,
                if (success) "install_done" else "install_failed",
                if (success) "" else appContext.getString(R.string.install_sheet_notify_check_log, id)
            )
        }
        dismissAllowingStateLoss()
    }

    /**
     * Instalación desde GitHub (2026-08-23, ver ModuleDebInstaller.kt) — descarga+instala en
     * background y cierra la hoja de inmediato, mismo criterio que [startSilentInstall]: la
     * descarga+verificación+aplicación puede tardar (red + posibles dependencias a instalar), no
     * tiene sentido bloquear al usuario en esta pantalla mientras tanto. El aviso de fin llega
     * por notificación real (ModuleEventBridge), no por Snackbar atado a esta vista que puede ya
     * no existir para cuando termine.
     */
    private fun startGithubInstall() {
        val appContext = requireContext().applicationContext
        val id = moduleId
        val name = moduleName
        Toast.makeText(appContext, getString(R.string.install_sheet_toast_github_installing, name), Toast.LENGTH_SHORT).show()
        Thread {
            val result = try {
                com.termux.app.util.ModuleDebInstaller.installFromGithub(id) { }
            } catch (e: Exception) {
                com.termux.app.util.ModuleDebInstaller.InstallResult(false, e.message ?: appContext.getString(R.string.install_sheet_error_unknown))
            }
            com.termux.app.util.ModuleEventBridge.notifyDirect(
                appContext,
                id,
                if (result.ok) "install_done" else "install_failed",
                result.message
            )
        }.start()
        dismissAllowingStateLoss()
    }

    private fun updateOriginGroup(group: LinearLayout) {
        val ctx = context ?: return
        for (i in 0 until group.childCount) {
            val chip = group.getChildAt(i) as? TextView ?: continue
            val isSelected = (i == 1) == useGithubSource
            val gd = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                if (isSelected) {
                    setColor(Color.parseColor("#0A22C55E"))
                    setStroke(dp(1), Color.parseColor("#22C55E"))
                } else {
                    setColor(ctx.kairosThemeColor(R.attr.kairosBg3))
                    setStroke(dp(1), ctx.kairosThemeColor(R.attr.kairosBorder))
                }
            }
            chip.background = gd
            chip.setTextColor(if (isSelected) ctx.kairosThemeColor(R.attr.kairosGreen) else ctx.kairosThemeColor(R.attr.kairosText2))
        }
    }

    private fun updateVariantGroup(group: LinearLayout) {
        val variants = getVariants()
        val ctx = context ?: return
        for (i in 0 until group.childCount) {
            val card = group.getChildAt(i) as? LinearLayout ?: continue
            val isSelected = i == selectedVariant
            val gd = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                if (isSelected) {
                    setColor(Color.parseColor("#0A22C55E"))
                    setStroke(dp(1), Color.parseColor("#22C55E"))
                } else {
                    setColor(ctx.kairosThemeColor(R.attr.kairosBg3))
                    setStroke(dp(1), ctx.kairosThemeColor(R.attr.kairosBorder))
                }
            }
            card.background = gd
            // Update radio button
            val topRow = card.getChildAt(0) as? LinearLayout
            val radio = topRow?.getChildAt(0) as? TextView
            radio?.text = if (isSelected) "◉" else "○"
            radio?.setTextColor(if (isSelected) ctx.kairosThemeColor(R.attr.kairosGreen) else ctx.kairosThemeColor(R.attr.kairosText2))
            // Show/hide badge only on selected
            val badge = topRow?.findViewWithTag<TextView>("badge_${i}")
            if (i == 0) {
                badge?.visibility = if (isSelected) View.VISIBLE else View.INVISIBLE
            }
        }
    }

    private fun updateInstallButton(variants: List<Pair<String, String>>) {
        installBtn?.text = buildButtonText()
    }

    private fun buildButtonText(): String {
        val needProot = requiresProot && !isProotInstalled()
        if (needProot) return getString(R.string.install_sheet_btn_install_proot_first)
        val variantSuffix = if (hasVariants) {
            val variants = getVariants()
            if (selectedVariant < variants.size) {
                val title = variants[selectedVariant].first
                " ${title.substringAfter(" ")}"
            } else ""
        } else ""
        // 2026-08-10: con force=true (cambiar método desde la Tienda) el botón dice
        // "Cambiar método" en vez de "Instalar" para que quede claro que REinstala.
        val action = if (forceReinstall) getString(R.string.install_sheet_btn_change_method) else getString(R.string.install_sheet_btn_install)
        return "$action$variantSuffix"
    }

    private fun getVariants(): List<Pair<String, String>> {
        return when (moduleId) {
            "ollama" -> listOf(
                getString(R.string.install_sheet_variant_ollama_gpu_title) to getString(R.string.install_sheet_variant_ollama_gpu_desc),
                getString(R.string.install_sheet_variant_ollama_standard_title) to getString(R.string.install_sheet_variant_ollama_standard_desc)
            )
            "claude" -> listOf(
                getString(R.string.install_sheet_variant_claude_native_title) to "",
                getString(R.string.install_sheet_variant_claude_legacy_title) to getString(R.string.install_sheet_variant_claude_legacy_desc)
            )
            // openclaw y opencode: la variante proot se removió 2026-07-24
            // (glibc-repo de Termux alcanza, no hace falta proot completo —
            // ver docs/viejo/PROPUESTA_SCRIPTS_MODULOS.md). Sin selector — un solo camino.
            "n8n" -> listOf(
                // Orden invertido 2026-08-06 (ver docs/humano/humano77.md, pedido explícito
                // del usuario): udocker pasa a ser la recomendada — vive en $HOME (Termux
                // nativo puede verla/gestionarla), cloudflared corre nativo sin necesitar
                // proot-distro (aislado, más dependencias, peor compatibilidad en algunos
                // dispositivos). El badge "RECOMENDADA" se asigna por posición (índice 0).
                getString(R.string.install_sheet_variant_n8n_udocker_title) to getString(R.string.install_sheet_variant_n8n_udocker_desc),
                getString(R.string.install_sheet_variant_n8n_proot_title) to getString(R.string.install_sheet_variant_n8n_proot_desc)
            )
            // codex: canal "native" agregado 2026-07-28 (binario ARM64 prebuilt de
            // codex_android, sin Node.js) pero nunca se wireó acá — modules.json no
            // tenía hasVariants:true y este when no tenía el caso "codex", así que la
            // hoja de instalación nunca mostraba el selector y --variant nunca se
            // pasaba: SIEMPRE se instalaba por npm (~50MB, requiere Node.js), aunque
            // el usuario pidiera lo contrario. Nativa primero (RECOMENDADA) porque es
            // más liviana y no depende de npm/registry.
            "codex" -> listOf(
                getString(R.string.install_sheet_variant_codex_native_title) to getString(R.string.install_sheet_variant_codex_native_desc),
                getString(R.string.install_sheet_variant_codex_termux_title) to getString(R.string.install_sheet_variant_codex_termux_desc)
            )
            // ciberseguridad: básico (nativo bionic, igual que siempre) vs pro (+ contenedor
            // Kali Linux completo vía proot-distro, con o sin interfaz gráfica). RECOMENDADA
            // (badge índice 0) es básico — más liviano, sin proot, cubre la mayoría de casos.
            "ciberseguridad" -> listOf(
                getString(R.string.install_sheet_variant_ciber_basico_title) to getString(R.string.install_sheet_variant_ciber_basico_desc),
                getString(R.string.install_sheet_variant_ciber_pro_headless_title) to getString(R.string.install_sheet_variant_ciber_pro_headless_desc),
                getString(R.string.install_sheet_variant_ciber_pro_gui_title) to getString(R.string.install_sheet_variant_ciber_pro_gui_desc)
            )
            else -> emptyList()
        }
    }

    // Ids exactos que cada script normaliza en su propio `case "$INSTALL_MODE"`/
    // `case "$VARIANT"` (ver --describe de cada uno) — deben ser una sola palabra,
    // en el mismo orden que getVariants() para el mismo moduleId.
    private fun getVariantId(index: Int): String? {
        val ids = when (moduleId) {
            "ollama" -> listOf("gpu", "standard")
            "claude" -> listOf("native", "legacy")
            "n8n" -> listOf("udocker", "proot")
            "codex" -> listOf("native", "termux")
            "ciberseguridad" -> listOf("basico", "pro-headless", "pro-gui")
            else -> emptyList()
        }
        return ids.getOrNull(index)
    }

    private fun isProotInstalled(): Boolean {
        return java.io.File(requireContext().filesDir, "usr/bin/proot").exists()
    }

    private fun dp(d: Int) = (d * resources.displayMetrics.density).toInt()

    companion object {
        fun newInstance(
            id: String, name: String, icon: String, iconBg: String,
            description: String, port: String, size: String = "",
            type: String = "", requiresProot: Boolean = false,
            hasVariants: Boolean = false, estimate: String = "",
            force: Boolean = false
        ): BottomSheetInstalacion {
            return BottomSheetInstalacion().apply {
                moduleId = id
                moduleName = name
                moduleIcon = icon
                moduleIconBg = iconBg
                moduleDescription = description
                modulePort = port
                moduleSize = size
                moduleType = type
                this.requiresProot = requiresProot
                this.hasVariants = hasVariants
                this.estimate = estimate
                forceReinstall = force
            }
        }
    }

}

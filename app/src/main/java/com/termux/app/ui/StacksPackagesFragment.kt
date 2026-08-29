package com.termux.app.ui

import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.termux.R
import com.termux.app.util.EntornoNative
import com.termux.app.util.ManagerNativeUtils
import com.termux.app.util.ProgressDialogController
import com.termux.app.util.TERMUX_BASH_PATH
import com.termux.app.util.kairosThemeColor
import com.termux.shared.termux.TermuxConstants
import java.io.File

/**
 * "📦 Paquetes necesarios" — pantalla propia (reorganización 2026-08-23, ver docs/humano208.md,
 * pedido explícito del usuario: "no es quitar opciones es reorganizarlas"). Antes esto vivía
 * como 4 cards con 8 botones sueltos directo en la pantalla principal de StacksFragment — MISMA
 * lógica exacta (mismos presets, mismo mecanismo `stacks.sh --preset <id> [--distro <nombre>]
 * --silent`, sin cambios de comportamiento), solo relocalizada a una pantalla propia accesible
 * desde el detalle de un proyecto (StacksFragment, estado "detalle").
 *
 * [hintTags] (opcional) — tags detectados del proyecto que llevó al usuario acá
 * (`detectProjectStack()` en StacksFragment) — solo se usa para resaltar visualmente el preset
 * recomendado, no cambia qué presets existen ni qué instalan.
 *
 * Migración a strings.xml (2026-08-28, soporte español/inglés) — [presets] es un `val` con
 * evaluación inmediata (corre al construir el objeto, ANTES de que el Fragment esté adjunto a
 * un Context), así que `getString()` ahí directo revienta con `IllegalStateException: Fragment
 * ... not attached`. Se envuelve en `by lazy {}` para diferir la evaluación al primer acceso
 * real (dentro de `buildContent()`, ya con Fragment adjunto) — mismo patrón que
 * PackagesFragment.items.
 */
class StacksPackagesFragment : BaseModuleFragment() {

    override fun getModuleId() = "stacks"
    override fun getModuleName() = getString(R.string.stacks_packages_module_name)

    /** Tags detectados del proyecto de origen (opcional) — set por quien navega acá, antes de
     * `navigateTo()`. Solo afecta qué preset se marca como recomendado. */
    var hintTags: List<String> = emptyList()

    private data class Preset(val id: String, val title: String, val summary: String, val matchTags: List<String>)

    // go/rust/java/dotnet agregados 2026-08-25 (pedido explícito: "toca agregar mas lenguajes,
    // paquetes... tipo .net, modulos etc en entorno de prueba") — mismo mecanismo real que
    // modulos/stacks.sh (native_package_for_preset()/distro_packages_for_preset()), paquetes
    // confirmados reales (golang/rust nativos de Termux; golang-go/rustc+cargo/default-jdk en
    // distro Debian/Ubuntu). dotnet es un caso aparte: el SDK de .NET no tiene paquete nativo de
    // Termux (confirmado real, solo se empaqueta para Ubuntu/Debian vía apt) — su fila SIEMPRE
    // abre el selector de distro en vez de instalar nativo, ver DOTNET_ALWAYS_DISTRO más abajo.
    private val presets by lazy {
        listOf(
            Preset(
                "python-postgres", getString(R.string.stacks_packages_preset_python_postgres_title),
                getString(R.string.stacks_packages_preset_python_postgres_summary), listOf("python")
            ),
            Preset(
                "php-mysql", getString(R.string.stacks_packages_preset_php_mysql_title),
                getString(R.string.stacks_packages_preset_php_mysql_summary), listOf("php")
            ),
            Preset(
                "react-vite", getString(R.string.stacks_packages_preset_react_vite_title),
                getString(R.string.stacks_packages_preset_react_vite_summary), listOf("node")
            ),
            Preset(
                "html", getString(R.string.stacks_packages_preset_html_title),
                getString(R.string.stacks_packages_preset_html_summary), listOf("html")
            ),
            Preset(
                "go", getString(R.string.stacks_packages_preset_go_title),
                getString(R.string.stacks_packages_preset_go_summary), listOf("go", "golang")
            ),
            Preset(
                "rust", getString(R.string.stacks_packages_preset_rust_title),
                getString(R.string.stacks_packages_preset_rust_summary), listOf("rust", "cargo")
            ),
            Preset(
                "java", getString(R.string.stacks_packages_preset_java_title),
                getString(R.string.stacks_packages_preset_java_summary), listOf("java", "kotlin")
            ),
            Preset(
                "dotnet", getString(R.string.stacks_packages_preset_dotnet_title),
                getString(R.string.stacks_packages_preset_dotnet_summary), emptyList()
            )
        )
    }

    private val DOTNET_ALWAYS_DISTRO = setOf("dotnet")

    private val distroRows = mutableListOf<android.view.View>()

    // Catálogo de frameworks/librerías extra por preset (2026-08-23, ver docs/humano209.md,
    // pedido explícito: "en entorno faltan muchas configuracion o lenjuages tipo fash api de
    // python [...] investiga y agregalos") — mismo catálogo que extra_packages_catalog() en
    // modulos/stacks.sh, no una lista inventada acá. Nombres de librerías/frameworks (fastapi,
    // django, laravel, etc.): identificadores técnicos de paquetes reales, no texto descriptivo
    // — se dejan sin traducir, mismo criterio que el resto de nombres de paquete de este archivo
    // (ver informe de la ronda de migración a strings.xml).
    private fun extrasFor(presetId: String): List<String> = when (presetId) {
        "python-postgres" -> listOf("fastapi", "django", "flask", "uvicorn", "sqlalchemy", "requests", "numpy", "pandas")
        "react-vite" -> listOf("express", "next", "vue", "axios")
        "php-mysql" -> listOf("laravel")
        else -> emptyList()
    }

    private val selectedExtras = mutableMapOf<String, MutableSet<String>>()

    // Ícono por preset (2026-08-23, rediseño visual real — ver docs/humano209.md, el usuario
    // rechazó la ronda anterior por ser solo reordenamiento sin diseño real).
    private fun iconFor(preset: Preset): String = when (preset.id) {
        "python-postgres" -> "🐍"
        "php-mysql" -> "🐘"
        "react-vite" -> "⚛️"
        "go" -> "🐹"
        "rust" -> "🦀"
        "java" -> "☕"
        "dotnet" -> "🔷"
        else -> "🌐"
    }

    override fun buildContent() {
        val recommended = presets.firstOrNull { it.matchTags.any { t -> t in hintTags } }
        addCard(getString(R.string.stacks_packages_card_title)) {
            presets.forEachIndexed { i, preset ->
                addView(buildPresetRow(preset, isRecommended = preset == recommended))
                if (i < presets.size - 1) divider()
            }
        }
        refreshDistroAvailability()
    }

    private fun buildPresetRow(preset: Preset, isRecommended: Boolean): android.view.View {
        val requiresDistro = preset.id in DOTNET_ALWAYS_DISTRO
        return modelRow(
            icon = iconFor(preset),
            name = preset.title,
            subtitle = preset.summary,
            trailing = {
                if (isRecommended) {
                    val recommendedPill = pill(getString(R.string.stacks_packages_recommended_pill), true)
                    (recommendedPill.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.marginEnd = dp(8)
                    addView(recommendedPill)
                }
                if (extrasFor(preset.id).isNotEmpty()) {
                    val extrasBtn = TextView(context).apply {
                        text = "➕"
                        textSize = 15f
                        setPadding(dp(8), dp(4), dp(8), dp(4))
                        setOnClickListener { promptExtras(preset) }
                    }
                    addView(extrasBtn)
                }
                // dotnet no tiene instalación nativa — la fila entera abre el selector de
                // distro (mismo flujo que el botón 📦 de los demás presets), no hace falta un
                // botón aparte redundante.
                if (!requiresDistro) {
                    val distroBtn = TextView(context).apply {
                        text = "📦"
                        textSize = 15f
                        setPadding(dp(8), dp(4), dp(8), dp(4))
                        isEnabled = false
                        alpha = 0.4f
                        setOnClickListener { promptDistroAndRun(preset) }
                    }
                    distroRows.add(distroBtn)
                    addView(distroBtn)
                } else {
                    addView(TextView(context).apply {
                        text = getString(R.string.stacks_packages_needs_distro)
                        textSize = 10f
                        setTextColor(context.kairosThemeColor(R.attr.kairosText3))
                        setPadding(dp(8), dp(4), dp(8), dp(4))
                    })
                }
            },
            onClick = { if (requiresDistro) promptDistroAndRun(preset) else confirmAndRun(preset, distro = null) },
        )
    }

    /** Diálogo de selección múltiple de frameworks/librerías extra (fastapi, laravel, etc.) —
     * la selección queda guardada por preset y se aplica en la próxima instalación (nativa o en
     * distro) de ese preset vía `--extra <lista,separada,por,comas>`. */
    private fun promptExtras(preset: Preset) {
        val options = extrasFor(preset.id)
        val current = selectedExtras.getOrPut(preset.id) { mutableSetOf() }
        val checked = BooleanArray(options.size) { options[it] in current }
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.stacks_packages_extras_title, preset.title))
            .setMultiChoiceItems(options.toTypedArray(), checked) { _, which, isChecked ->
                if (isChecked) current.add(options[which]) else current.remove(options[which])
            }
            .setPositiveButton(getString(R.string.stacks_packages_button_done)) { _, _ ->
                toast(
                    if (current.isEmpty()) getString(R.string.stacks_packages_no_extras_selected, preset.title)
                    else getString(R.string.stacks_packages_extras_selected, preset.title, current.joinToString(", "))
                )
            }
            .setNegativeButton(getString(R.string.stacks_packages_button_cancel), null)
            .show()
    }

    /** El botón "Instalar en distro" solo tiene sentido si hay al menos una distro proot ya
     * instalada — mismo criterio que la versión anterior de esta pantalla (antes en StacksFragment). */
    private fun refreshDistroAvailability() {
        Thread {
            val json = EntornoNative.distroList()
            val installed = json.optJSONArray("installed")
            val hasDistro = installed != null && installed.length() > 0
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                distroRows.forEach { btn ->
                    btn.isEnabled = hasDistro
                    btn.alpha = if (hasDistro) 1f else 0.5f
                }
                if (!hasDistro) toast(getString(R.string.stacks_packages_no_distros_enable))
            }
        }.start()
    }

    private fun promptDistroAndRun(preset: Preset) {
        Thread {
            val json = EntornoNative.distroList()
            val installed = json.optJSONArray("installed")
            val names = if (installed != null) (0 until installed.length()).map { installed.optString(it) } else emptyList()
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                if (names.isEmpty()) {
                    toast(getString(R.string.stacks_packages_no_distros_install_first))
                    return@runOnUiThread
                }
                if (names.size == 1) {
                    confirmAndRun(preset, names[0])
                    return@runOnUiThread
                }
                AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.stacks_packages_choose_distro_title))
                    .setItems(names.toTypedArray()) { _, which -> confirmAndRun(preset, names[which]) }
                    .setNegativeButton(getString(R.string.stacks_packages_button_cancel), null)
                    .show()
            }
        }.start()
    }

    private fun confirmAndRun(preset: Preset, distro: String?) {
        val target = if (distro != null) getString(R.string.stacks_packages_target_distro, distro) else getString(R.string.stacks_packages_target_native)
        val extras = selectedExtras[preset.id].orEmpty()
        val extrasNote = if (extras.isNotEmpty()) getString(R.string.stacks_packages_extras_note, extras.joinToString(", ")) else ""
        AlertDialog.Builder(requireContext())
            .setTitle(preset.title)
            .setMessage(getString(R.string.stacks_packages_install_confirm_message, preset.title, target, extrasNote))
            .setPositiveButton(getString(R.string.stacks_packages_button_install)) { _, _ -> runPreset(preset, distro) }
            .setNegativeButton(getString(R.string.stacks_packages_button_cancel), null)
            .show()
    }

    // Timeout generoso (15 min) — sin cambios respecto a la versión anterior de esta pantalla.
    private fun runPreset(preset: Preset, distro: String?) {
        val progress = ProgressDialogController(requireContext())
        progress.show(preset.title, getString(R.string.stacks_packages_installing_message, preset.title))
        Thread {
            val script = File(TermuxConstants.TERMUX_HOME_DIR_PATH, "scripts/install/stacks.sh").absolutePath
            val args = mutableListOf(TERMUX_BASH_PATH, script, "--preset", preset.id, "--silent")
            if (distro != null) args.addAll(listOf("--distro", distro))
            val extras = selectedExtras[preset.id].orEmpty()
            if (extras.isNotEmpty()) args.addAll(listOf("--extra", extras.joinToString(",")))
            val (exitCode, out, err) = ManagerNativeUtils.runExec(args, timeoutSeconds = 900)
            val output = out.ifEmpty { err }
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                val summary = output.lines()
                    .filter { it.trim().startsWith("[RESUMEN]") }
                    .joinToString("\n") { it.trim().removePrefix("[RESUMEN]").trim() }
                if (exitCode == 0 && summary.isNotBlank()) {
                    progress.success(getString(R.string.stacks_packages_ready_title, preset.title), summary)
                } else {
                    val errorLine = output.lines().lastOrNull { it.trim().startsWith("[ERROR]") }?.trim()
                        ?: getString(R.string.stacks_packages_script_failed_fallback, exitCode)
                    progress.failure(getString(R.string.stacks_packages_install_failed_title, preset.title), "$errorLine\n\n$output")
                }
            }
        }.start()
    }
}

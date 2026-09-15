package com.termux.app.ui

import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.termux.app.ui.BaseModuleFragment.ButtonStyle.GHOST
import com.termux.app.ui.BaseModuleFragment.ButtonStyle.PRIMARY
import com.termux.app.util.EntornoNative
import com.termux.app.util.ManagerNativeUtils
import com.termux.app.util.ProgressDialogController
import com.termux.app.util.ProjectsManager
import com.termux.app.util.TERMUX_BASH_PATH
import com.termux.app.util.TunnelManager
import com.termux.app.util.kairosThemeColor
import com.termux.app.util.runProjectsAction
import com.termux.R
import com.termux.shared.termux.TermuxConstants
import java.io.File
import java.security.MessageDigest

/**
 * Entornos de Prueba — reorganizado 2026-08-23 (ver docs/humano208.md, pedido explícito del
 * usuario: "no es quitar opciones es reorganizarlas", confirmado sobre un mockup visual antes de
 * implementar). Antes eran 2 pantallas: esta (4 presets fijos con 8 botones sueltos + un acceso
 * a "Proyecto real") y `StacksProjectFragment` (lista de carpetas + detalle). Ahora es UNA sola
 * pantalla con 2 estados:
 *
 * - **Estado LISTA** (`activePath == null`, default): lista de proyectos reales del usuario
 *   (nombre + stack detectado + estado activo/detenido), más "+ Agregar carpeta de proyecto"
 *   (que ahora corre la detección automática y la muestra como confirmación ANTES de crear el
 *   proyecto, en vez de detectar en silencio recién dentro del detalle).
 * - **Estado DETALLE** (`activePath != null`): exactamente las mismas cards que tenía
 *   `StacksProjectFragment` (DETECCIÓN, DESTINO DE EJECUCIÓN, DEPENDENCIAS, EJECUCIÓN Y
 *   MONITOREO, EXPONER), MÁS una fila nueva "📦 Paquetes necesarios" que navega a
 *   `StacksPackagesFragment` — los 4 presets que antes vivían acá se relocalizaron ahí, sin
 *   cambiar qué instalan ni cómo (ver ese archivo).
 *
 * Se fusionan las 2 clases en una (antes StacksFragment + StacksProjectFragment) en vez de
 * pasarse el path por argumento entre 2 Fragments — evita duplicar el estado mutable
 * (projectPaths/projectStates) entre 2 clases y sincronizarlo a mano; los 2 estados de esta
 * misma clase ya comparten esos campos de forma natural.
 */
class StacksFragment : BaseModuleFragment() {

    override fun getModuleId() = "stacks"
    override fun getModuleName() = getString(R.string.stacks_module_name)

    companion object {
        private const val PREFS = "kairos_stacks_prefs"
        private const val KEY_PATHS = "project_paths"
        private const val KEY_STATES = "project_states"
    }

    /** Estado propio de cada carpeta de proyecto elegida — no se comparte entre carpetas
     * (idéntico a como funcionaba en el StacksProjectFragment original).
     * [udockerMode] agregado 2026-09-03 (pedido explícito del usuario): "simple" (default,
     * imagen angosta por stack detectado, comportamiento de siempre) o "full" (debian:bookworm
     * con el set completo "esencial para programar" — ver _install_essentials() en
     * modulos/stacks.sh). Solo tiene efecto cuando target=="udocker". */
    private class ProjectState(
        var target: String = "native",
        var selectedDistro: String? = null,
        var runCmdText: String = "",
        var udockerMode: String = "simple"
    )

    private val projectPaths = mutableListOf<String>()
    private val projectStates = mutableMapOf<String, ProjectState>()
    private var activePath: String? = null
    private var detectedTags: List<String> = emptyList()
    private var projectsLoaded = false

    private val activeState: ProjectState
        get() = projectStates.getOrPut(activePath ?: "") { ProjectState() }
    private var target: String
        get() = activeState.target
        set(value) { activeState.target = value; if (isAdded) saveProjects() }
    private var selectedDistro: String?
        get() = activeState.selectedDistro
        set(value) { activeState.selectedDistro = value; if (isAdded) saveProjects() }
    private var runCmdText: String
        get() = activeState.runCmdText
        set(value) { activeState.runCmdText = value; if (isAdded) saveProjects() }
    private var udockerMode: String
        get() = activeState.udockerMode
        set(value) { activeState.udockerMode = value; if (isAdded) saveProjects() }

    private lateinit var cmdEdit: EditText
    private lateinit var runSwitch: SwitchRow

    override fun buildContent() {
        if (!projectsLoaded) { loadProjects(); projectsLoaded = true }
        val path = activePath
        if (path == null) buildProjectListState() else buildProjectDetailState(path)
    }

    // ═══════════════════════════════════════════════════════════
    //  Persistencia real de la lista de proyectos (2026-08-24, ver docs/humano210.md).
    //  Bug real reportado: "las carpetas en entorno de prueba las carpetas no se quedan guardadas
    //  al salir se quitan" — `projectPaths`/`projectStates` eran solo `mutableListOf`/`mutableMapOf`
    //  en memoria, sin ningún archivo/SharedPreferences detrás, así que se perdían apenas el
    //  Fragment se recreaba (salir y volver a entrar, rotar pantalla, matar la app en background).
    //  SharedPreferences propia de esta pantalla (no el registry compartido de los módulos —
    //  esta lista es puramente de UI, no la necesita leer ningún script bash).
    // ═══════════════════════════════════════════════════════════

    private fun loadProjects() {
        val prefs = requireContext().getSharedPreferences(PREFS, 0)
        try {
            prefs.getString(KEY_PATHS, null)?.let { json ->
                val arr = org.json.JSONArray(json)
                for (i in 0 until arr.length()) {
                    val p = arr.getString(i)
                    // Solo se restauran carpetas que todavía existen — evita filas rotas si el
                    // usuario borró/movió la carpeta desde fuera de la app.
                    if (File(p).isDirectory) projectPaths.add(p)
                }
            }
        } catch (_: Exception) { }
        try {
            prefs.getString(KEY_STATES, null)?.let { json ->
                val obj = org.json.JSONObject(json)
                obj.keys().forEach { path ->
                    val s = obj.getJSONObject(path)
                    projectStates[path] = ProjectState(
                        target = s.optString("target", "native"),
                        selectedDistro = s.optString("selectedDistro").ifBlank { null },
                        runCmdText = s.optString("runCmdText", ""),
                        udockerMode = s.optString("udockerMode", "simple"),
                    )
                }
            }
        } catch (_: Exception) { }
    }

    private fun saveProjects() {
        val prefs = requireContext().getSharedPreferences(PREFS, 0)
        val pathsArr = org.json.JSONArray()
        projectPaths.forEach { pathsArr.put(it) }
        val statesObj = org.json.JSONObject()
        projectStates.forEach { (path, state) ->
            statesObj.put(
                path,
                org.json.JSONObject()
                    .put("target", state.target)
                    .put("selectedDistro", state.selectedDistro ?: "")
                    .put("runCmdText", state.runCmdText)
                    .put("udockerMode", state.udockerMode)
            )
        }
        prefs.edit().putString(KEY_PATHS, pathsArr.toString()).putString(KEY_STATES, statesObj.toString()).apply()
    }

    private fun refreshView() {
        container.removeAllViews()
        buildContent()
    }

    // ═══════════════════════════════════════════════════════════
    //  Estado LISTA — pantalla principal del módulo
    // ═══════════════════════════════════════════════════════════

    private fun buildProjectListState() {
        if (projectPaths.isEmpty()) {
            addCard {
                addView(TextView(requireContext()).apply {
                    text = getString(R.string.stacks_empty_state)
                    textSize = 12f
                    setTextColor(requireContext().kairosThemeColor(R.attr.kairosText2))
                    setPadding(dp(14), dp(14), dp(14), dp(14))
                })
            }
        } else {
            addCard(getString(R.string.stacks_card_projects)) {
                projectPaths.forEachIndexed { i, p ->
                    addView(buildProjectRow(p))
                    if (i < projectPaths.size - 1) divider()
                }
            }
        }
        showFab("＋") { pickProjectFolder() }
    }

    /** Ícono por stack detectado — mismo `detectProjectStack()` de siempre, solo se le agrega
     * una representación visual (2026-08-23, rediseño visual real tras el rechazo del usuario a
     * la reorganización "copy-paste" anterior — ver docs/humano209.md). */
    private fun iconForTags(tags: List<String>): String = when {
        "python" in tags -> "🐍"
        "php" in tags -> "🐘"
        "node" in tags -> "⚛️"
        "html" in tags -> "🌐"
        else -> "📁"
    }

    private fun buildProjectRow(path: String): View {
        val tags = detectProjectStack(File(path))
        return modelRow(
            icon = iconForTags(tags),
            name = File(path).name,
            subtitle = if (tags.isEmpty()) getString(R.string.stacks_subtitle_unrecognized) else tags.joinToString(" + "),
            trailing = {
                addView(TextView(context).apply {
                    text = "✕"
                    textSize = 14f
                    setTextColor(context.kairosThemeColor(R.attr.kairosText2))
                    setPadding(dp(10), dp(4), dp(2), dp(4))
                    setOnClickListener { removeProjectFolder(path) }
                })
            },
            onClick = { openProject(path) },
        )
    }

    private fun openProject(path: String) {
        activePath = path
        detectedTags = detectProjectStack(File(path))
        refreshView()
    }

    // ═══════════════════════════════════════════════════════════
    //  Estado DETALLE — mismo contenido que el StacksProjectFragment original, solo con
    //  "← Volver a proyectos" nuevo (vuelve al estado LISTA) y "📦 Paquetes necesarios" nuevo
    //  (antes eran los presets sueltos de la pantalla principal, ver StacksPackagesFragment).
    // ═══════════════════════════════════════════════════════════

    private fun buildProjectDetailState(path: String) {
        hideFab()
        actionButton(getString(R.string.stacks_back_button, File(path).name), GHOST) { activePath = null; refreshView() }

        addCard {
            addView(compactStatusRow(
                statusText = if (detectedTags.isEmpty()) getString(R.string.stacks_status_unrecognized) else detectedTags.joinToString(" + "),
                isActive = detectedTags.isNotEmpty(),
            ))
            addView(createActionButton(getString(R.string.stacks_packages_needed), PRIMARY) {
                navigateTo(StacksPackagesFragment().apply { hintTags = detectedTags })
            })
        }

        addCard(getString(R.string.stacks_card_destination)) {
            val options = listOf(
                getString(R.string.stacks_target_native),
                getString(R.string.stacks_target_distro),
                getString(R.string.stacks_target_udocker)
            )
            val initialIndex = when (target) { "distro" -> 1; "udocker" -> 2; else -> 0 }
            addView(dropdownRow(getString(R.string.stacks_label_destino), options, initialIndex) { index ->
                when (index) {
                    0 -> { target = "native"; refreshView() }
                    1 -> pickDistroTarget()
                    2 -> { target = "udocker"; selectedDistro = null; refreshView() }
                }
            }.root)
            // Pedido 2026-09-03: solo aplica a target=="udocker" — elegir entre la imagen
            // angosta de siempre por stack detectado ("simple") o debian:bookworm con el set
            // completo "esencial para programar" ("full", ver _install_essentials() en
            // modulos/stacks.sh). No se muestra para native/distro (no aplica).
            if (target == "udocker") {
                val modeOptions = listOf(
                    getString(R.string.stacks_udocker_mode_simple),
                    getString(R.string.stacks_udocker_mode_full)
                )
                val modeIndex = if (udockerMode == "full") 1 else 0
                addView(dropdownRow(getString(R.string.stacks_label_udocker_mode), modeOptions, modeIndex) { index ->
                    udockerMode = if (index == 1) "full" else "simple"
                    refreshView()
                }.root)
            }
            addView(infoRow(getString(R.string.stacks_label_elegido), targetLabel()))
        }

        addCard(getString(R.string.stacks_card_dependencies)) {
            addView(infoRow(getString(R.string.stacks_label_instalar), dependenciesDescription()))
            addView(createActionButton(getString(R.string.stacks_btn_install_deps), PRIMARY) { confirmInstall(path) })
        }

        addCard(getString(R.string.stacks_card_execution)) {
            cmdEdit = EditText(requireContext()).apply {
                hint = getString(R.string.stacks_hint_command)
                setText(runCmdText.ifEmpty { suggestedRunCommand(detectedTags) })
                addTextChangedListener(object : TextWatcher {
                    override fun afterTextChanged(s: Editable?) { runCmdText = s?.toString().orEmpty() }
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                })
            }
            addView(cmdEdit)
            runSwitch = switchRow(getString(R.string.stacks_switch_background)) { on ->
                if (on) startProject(path) else stopProject(path)
            }
            addView(runSwitch.root)
            addView(createActionButton(getString(R.string.stacks_btn_view_logs), GHOST) { viewLiveLogs(path) })
        }

        addCard(getString(R.string.stacks_card_expose)) {
            addView(createActionButton(getString(R.string.stacks_btn_expose_cloudflare), GHOST) { promptTunnel() })
        }

        refreshStatus(path)
    }

    // ═══════════════════════════════════════════════════════════
    //  Detector de stack — sin cambios de lógica respecto al StacksProjectFragment original.
    // ═══════════════════════════════════════════════════════════
    private fun detectProjectStack(dir: File): List<String> {
        val tags = mutableListOf<String>()
        val hasPackageJson = File(dir, "package.json").isFile
        if (hasPackageJson) tags += "node"
        val pyFiles = dir.listFiles { f -> f.isFile && f.name.endsWith(".py") } ?: emptyArray()
        if (File(dir, "requirements.txt").isFile || pyFiles.isNotEmpty()) tags += "python"
        val dbFiles = dir.listFiles { f ->
            f.isFile && (f.name.endsWith(".db") || f.name.endsWith(".sqlite") || f.name.endsWith(".sqlite3"))
        } ?: emptyArray()
        if (dbFiles.isNotEmpty()) tags += "sqlite"
        val phpFiles = dir.listFiles { f -> f.isFile && f.name.endsWith(".php") } ?: emptyArray()
        if (File(dir, "composer.json").isFile || phpFiles.isNotEmpty()) tags += "php"
        if (File(dir, "index.html").isFile && !hasPackageJson) tags += "html"
        return tags
    }

    private fun recommendTarget(tags: List<String>): String = when {
        tags.isEmpty() -> getString(R.string.stacks_recommend_none)
        "php" in tags -> getString(R.string.stacks_recommend_php)
        else -> getString(R.string.stacks_recommend_native, tags.joinToString("+"))
    }

    private fun targetLabel(): String = when (target) {
        "distro" -> getString(R.string.stacks_target_label_distro, selectedDistro ?: getString(R.string.stacks_no_selection))
        "udocker" -> getString(R.string.stacks_target_label_udocker, udockerImageLabel())
        else -> getString(R.string.stacks_target_label_native)
    }

    /** Imagen udocker real según el modo elegido (2026-09-03) — "full" siempre usa
     * debian:bookworm (fija, no depende del stack detectado), "simple" sigue usando
     * [udockerImageForTags] como siempre. */
    private fun udockerImageLabel(): String = if (udockerMode == "full") {
        "debian:bookworm (${getString(R.string.stacks_udocker_mode_full)})"
    } else {
        udockerImageForTags(detectedTags) ?: getString(R.string.stacks_no_stack_recognized)
    }

    private fun udockerImageForTags(tags: List<String>): String? = when {
        "node" in tags -> "node:20"
        "python" in tags -> "python:3.12"
        "php" in tags -> "php:8.3-cli"
        "html" in tags -> "python:3.12"
        else -> null
    }

    private fun dependenciesDescription(): String = when {
        target == "udocker" && udockerMode == "full" -> getString(R.string.stacks_deps_udocker_full)
        target == "udocker" -> getString(R.string.stacks_deps_udocker)
        target == "distro" -> getString(R.string.stacks_deps_distro)
        else -> getString(R.string.stacks_deps_native)
    }

    private fun suggestedRunCommand(tags: List<String>): String = when {
        "node" in tags -> "npm run dev -- --host"
        "python" in tags -> "python3 app.py"
        "php" in tags -> "php -S 0.0.0.0:8080 -t ."
        "html" in tags -> "python3 -m http.server 8080"
        else -> ""
    }

    // ═══════════════════════════════════════════════════════════
    //  Navegador de carpetas — sin cambios de lógica respecto al original, salvo que
    //  [onProjectFolderPicked] ahora muestra la detección como confirmación (pedido explícito
    //  del usuario: "al seleccionar la carpeta... una especie de encuesta... automática") en vez
    //  de crear el proyecto directo y detectar recién al entrar al detalle.
    // ═══════════════════════════════════════════════════════════
    private fun pickProjectFolder() {
        val options = arrayOf(
            getString(R.string.stacks_option_home, TermuxConstants.TERMUX_HOME_DIR_PATH),
            getString(R.string.stacks_option_external)
        )
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.stacks_dialog_pick_folder_title))
            .setItems(options) { _, which ->
                val root = if (which == 0) TermuxConstants.TERMUX_HOME_DIR_PATH else ProjectsManager.EXTERNAL_STORAGE_ROOT
                pickStorageFolderLocal(root, root)
            }
            .setNegativeButton(getString(R.string.stacks_cancel), null)
            .show()
    }

    private fun pickStorageFolderLocal(path: String, floor: String) {
        runProjectsAction({ ProjectsManager.projectsStorageDirs(path) }) { json ->
            if (!json.optBoolean("ok", false)) {
                toast(getString(R.string.stacks_error_generic, json.optString("error", getString(R.string.stacks_unknown))))
                return@runProjectsAction
            }
            val dirs = json.optJSONArray("dirs")
            val count = dirs?.length() ?: 0
            val names = (0 until count).map { dirs!!.getJSONObject(it).optString("name") }
            val paths = (0 until count).map { dirs!!.getJSONObject(it).optString("path") }
            val canGoUp = path != floor && File(path).parent != null
            val items = mutableListOf(getString(R.string.stacks_use_this_folder))
            if (canGoUp) items.add(getString(R.string.stacks_go_up))
            items.addAll(names.map { getString(R.string.stacks_folder_item, it) })
            AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.stacks_dialog_pick_folder_path, path))
                .setItems(items.toTypedArray()) { _, which ->
                    when {
                        which == 0 -> onProjectFolderPicked(path)
                        canGoUp && which == 1 -> pickStorageFolderLocal(File(path).parent ?: path, floor)
                        else -> {
                            val idx = which - (if (canGoUp) 2 else 1)
                            pickStorageFolderLocal(paths[idx], floor)
                        }
                    }
                }
                .setNegativeButton(getString(R.string.stacks_cancel), null)
                .show()
        }
    }

    /** Confirmación de detección automática (nuevo, pedido explícito del usuario) — corre
     * [detectProjectStack] apenas se elige la carpeta y la muestra ANTES de agregar el proyecto
     * a la lista, en vez de detectar en silencio recién al entrar al detalle. */
    private fun onProjectFolderPicked(path: String) {
        val tags = detectProjectStack(File(path))
        val detected = if (tags.isEmpty()) getString(R.string.stacks_no_stack_detected) else getString(R.string.stacks_detected_stack, tags.joinToString(" + "))
        AlertDialog.Builder(requireContext())
            .setTitle(File(path).name)
            .setMessage(getString(R.string.stacks_confirm_add_project, detected))
            .setPositiveButton(getString(R.string.stacks_add)) { _, _ ->
                if (path !in projectPaths) projectPaths.add(path)
                saveProjects()
                refreshView()
            }
            .setNegativeButton(getString(R.string.stacks_cancel), null)
            .show()
    }

    private fun removeProjectFolder(path: String) {
        projectPaths.remove(path)
        projectStates.remove(path)
        saveProjects()
        refreshView()
    }

    private fun pickDistroTarget() {
        Thread {
            val json = EntornoNative.distroList()
            val installed = json.optJSONArray("installed")
            val names = if (installed != null) (0 until installed.length()).map { installed.optString(it) } else emptyList()
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                if (names.isEmpty()) {
                    offerInstallDebian()
                    return@runOnUiThread
                }
                AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.stacks_dialog_pick_distro_title))
                    .setItems(names.toTypedArray()) { _, which ->
                        target = "distro"; selectedDistro = names[which]; refreshView()
                    }
                    .setNegativeButton(getString(R.string.stacks_cancel), null)
                    .show()
            }
        }.start()
    }

    /** Pedido 2026-09-03: si no hay NINGUNA distro instalada, ofrecer instalar Debian
     * automáticamente en vez de solo avisar que no hay ninguna (antes bloqueaba acá — el
     * usuario tenía que ir al módulo Entorno primero). Debian confirmado por el usuario como
     * default ("la más liviana y completa"). Reusa [EntornoNative.distroInstall], el mismo
     * mecanismo que ya usa EntornoFragment para instalar distros — no reinventa la llamada a
     * proot-distro acá. */
    private fun offerInstallDebian() {
        if (!isAdded) return
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.stacks_no_distros))
            .setMessage(getString(R.string.stacks_install_debian_message))
            .setPositiveButton(getString(R.string.stacks_install_debian_confirm)) { _, _ -> installDebianAndUse() }
            .setNegativeButton(getString(R.string.stacks_cancel), null)
            .show()
    }

    private fun installDebianAndUse() {
        val progress = ProgressDialogController(requireContext())
        progress.show(getString(R.string.stacks_install_debian_progress_title), getString(R.string.stacks_install_debian_progress_message))
        Thread {
            val result = EntornoNative.distroInstall("debian")
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                if (result.optBoolean("ok", false)) {
                    progress.success(getString(R.string.stacks_install_debian_success_title), getString(R.string.stacks_install_debian_success_message))
                    target = "distro"; selectedDistro = "debian"; refreshView()
                } else {
                    progress.failure(getString(R.string.stacks_install_debian_failure_title), result.optString("error", getString(R.string.stacks_unknown)))
                }
            }
        }.start()
    }

    // ═══════════════════════════════════════════════════════════
    //  Instalar / iniciar / detener / estado — sin cambios de lógica respecto al original.
    // ═══════════════════════════════════════════════════════════
    private fun projectScriptArgs(action: String, path: String, extra: List<String> = emptyList()): List<String> {
        val script = File(TermuxConstants.TERMUX_HOME_DIR_PATH, "scripts/install/stacks.sh").absolutePath
        val args = mutableListOf(
            TERMUX_BASH_PATH, script,
            "--project-path", path,
            "--project-action", action,
            "--project-target", target,
            "--silent"
        )
        if (target == "distro" && selectedDistro != null) args += listOf("--project-distro", selectedDistro!!)
        if (target == "udocker") args += listOf("--project-udocker-mode", udockerMode)
        args += extra
        return args
    }

    private fun confirmInstall(path: String) {
        // selectedDistro==null ya no bloquea la instalación (2026-09-03): si el usuario no
        // eligió ninguna, stacks.sh instala Debian automáticamente — solo pickDistroTarget()
        // sigue pidiendo elegir explícitamente cuando SÍ hay distros instaladas.
        // udockerMode=="full" tampoco depende del stack detectado (imagen fija debian:bookworm)
        // — el chequeo de stack reconocido solo aplica al modo "simple".
        if (target == "udocker" && udockerMode == "simple" && udockerImageForTags(detectedTags) == null) {
            toast(getString(R.string.stacks_no_udocker_stack)); return
        }
        val destino = when (target) {
            "distro" -> getString(R.string.stacks_destino_distro, selectedDistro ?: "debian")
            "udocker" -> getString(R.string.stacks_destino_udocker, udockerImageLabel())
            else -> getString(R.string.stacks_destino_native)
        }
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.stacks_dialog_install_title))
            .setMessage(getString(R.string.stacks_dialog_install_message, destino))
            .setPositiveButton(getString(R.string.stacks_label_instalar)) { _, _ -> runInstall(path) }
            .setNegativeButton(getString(R.string.stacks_cancel), null)
            .show()
    }

    private fun runInstall(path: String) {
        val progress = ProgressDialogController(requireContext())
        progress.show(getString(R.string.stacks_progress_install_title), getString(R.string.stacks_progress_install_message))
        Thread {
            val (exitCode, out, err) = ManagerNativeUtils.runExec(projectScriptArgs("install", path), timeoutSeconds = 900)
            val output = out.ifEmpty { err }
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                if (exitCode == 0) {
                    val resumen = output.lines().filter { it.trim().startsWith("[RESUMEN]") }
                        .joinToString("\n") { it.trim().removePrefix("[RESUMEN]").trim() }
                    progress.success(getString(R.string.stacks_install_success_title), if (resumen.isNotBlank()) resumen else output)
                } else {
                    val errorLine = output.lines().lastOrNull { it.trim().startsWith("[ERROR]") }?.trim()
                        ?: getString(R.string.stacks_script_error_generic, exitCode)
                    progress.failure(getString(R.string.stacks_install_failure_title), "$errorLine\n\n$output")
                }
            }
        }.start()
    }

    private fun startProject(path: String) {
        val cmd = cmdEdit.text.toString().trim()
        if (cmd.isEmpty()) { toast(getString(R.string.stacks_toast_write_command)); runSwitch.setSwitchState(false); return }
        if (target == "distro" && selectedDistro == null) { toast(getString(R.string.stacks_pick_distro_first)); runSwitch.setSwitchState(false); return }
        toast(getString(R.string.stacks_toast_starting))
        Thread {
            val (exitCode, out, err) = ManagerNativeUtils.runExec(
                projectScriptArgs("start", path, listOf("--project-cmd", cmd)), timeoutSeconds = 30
            )
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                toast(if (exitCode == 0) getString(R.string.stacks_toast_process_started) else getString(R.string.stacks_error_generic, err.ifEmpty { out }.takeLast(200)))
                refreshStatus(path)
            }
        }.start()
    }

    private fun stopProject(path: String) {
        Thread {
            val (exitCode, out, err) = ManagerNativeUtils.runExec(projectScriptArgs("stop", path), timeoutSeconds = 15)
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                toast(if (exitCode == 0) getString(R.string.stacks_toast_process_stopped) else getString(R.string.stacks_error_generic, err.ifEmpty { out }.takeLast(200)))
                refreshStatus(path)
            }
        }.start()
    }

    private fun refreshStatus(path: String) {
        Thread {
            val (_, out, _) = ManagerNativeUtils.runExec(projectScriptArgs("status", path), timeoutSeconds = 15)
            val running = out.contains("running=true")
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded || !::runSwitch.isInitialized) return@runOnUiThread
                runSwitch.setSwitchState(running)
            }
        }.start()
    }

    private fun projectLogPath(path: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(path.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }.take(10)
        return File(TermuxConstants.TERMUX_HOME_DIR_PATH, "kairos_logs/stacks_project_$hex.log").absolutePath
    }

    private fun viewLiveLogs(path: String) {
        if (!isAdded) return
        val log = projectLogPath(path)
        val ctx = requireContext()
        val body = TextView(ctx).apply {
            text = getString(R.string.stacks_loading)
            textSize = 11f
            setTypeface(android.graphics.Typeface.MONOSPACE)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(ctx).apply { addView(body) }
        val handler = Handler(Looper.getMainLooper())
        var polling = true
        val pollIntervalMs = 2000L
        lateinit var poll: Runnable
        poll = Runnable {
            if (!polling || !isAdded) return@Runnable
            Thread {
                val (_, out, _) = ManagerNativeUtils.runShell("tail -n 150 '$log' 2>/dev/null", 5)
                if (!polling || !isAdded) return@Thread
                requireActivity().runOnUiThread {
                    if (!polling || !isAdded) return@runOnUiThread
                    body.text = out.ifBlank { getString(R.string.stacks_no_logs_yet) }
                    scroll.post { scroll.fullScroll(android.view.View.FOCUS_DOWN) }
                    if (polling) handler.postDelayed(poll, pollIntervalMs)
                }
            }.start()
        }
        val dialog = AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.stacks_dialog_logs_title, File(path).name))
            .setView(scroll)
            .setPositiveButton(getString(R.string.stacks_close), null)
            .setNeutralButton(getString(R.string.stacks_terminal_full)) { _, _ ->
                launchTerminalCommand(
                    "tail -n 100 -f '$log' 2>/dev/null || echo '${getString(R.string.stacks_no_logs_yet)}'",
                    sessionName = getString(R.string.stacks_session_project_prefix, File(path).name)
                )
            }
            .create()
        dialog.setOnDismissListener {
            polling = false
            handler.removeCallbacks(poll)
        }
        dialog.show()
        poll.run()
    }

    // ═══════════════════════════════════════════════════════════
    //  Cloudflare — sin cambios de lógica respecto al original.
    // ═══════════════════════════════════════════════════════════
    private fun promptTunnel() {
        val edit = EditText(requireContext()).apply {
            hint = getString(R.string.stacks_hint_port)
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.stacks_dialog_expose_title))
            .setMessage(getString(R.string.stacks_dialog_expose_message))
            .setView(edit)
            .setPositiveButton(getString(R.string.stacks_btn_start_tunnel)) { _, _ ->
                val port = edit.text.toString().trim().toIntOrNull()
                if (port == null) toast(getString(R.string.stacks_toast_invalid_port)) else startTunnel(port)
            }
            .setNegativeButton(getString(R.string.stacks_cancel), null)
            .show()
    }

    private fun startTunnel(port: Int) {
        toast(getString(R.string.stacks_toast_starting_tunnel, port))
        val nativeLibDir = requireContext().applicationInfo.nativeLibraryDir
        Thread {
            val result = TunnelManager.start(port, "cloudflared", null, nativeLibDir = nativeLibDir)
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                toast(if (result.ok) result.message else getString(R.string.stacks_error_generic, result.error))
            }
        }.start()
    }
}

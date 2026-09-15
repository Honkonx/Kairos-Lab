package com.termux.app.ui

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import com.termux.R
import com.termux.app.ui.BaseModuleFragment.ButtonStyle.DANGER
import com.termux.app.ui.BaseModuleFragment.ButtonStyle.GHOST
import com.termux.app.ui.BaseModuleFragment.ButtonStyle.PRIMARY
import com.termux.app.util.ManagerNativeUtils
import com.termux.app.util.ProjectsManager
import com.termux.app.util.TERMUX_BASH_PATH
import com.termux.app.util.applyTermuxEnv
import com.termux.app.util.runProjectsAction
import com.termux.app.util.shellQuote
import com.termux.app.util.showProjectsMenu
import com.termux.shared.termux.TermuxConstants
import java.io.File
import java.io.FileOutputStream

/**
 * Fragment de detalle DEDICADO para el módulo "apk" (modulos/apk.sh — compilador de APK en
 * el dispositivo, puerta de entrada `compil-apk-termux`). Antes caía en GenericModuleFragment
 * (solo "Abrir en terminal" + "Gestionar proyectos") porque no tiene `terminalCommand` en
 * modules.json — acá se expone el pipeline real (aapt2 → javac → d8 → zipalign → apksigner,
 * ver cmd_build() en el wrapper que instala apk.sh) con progreso paso a paso en vez de una
 * caja de texto de terminal cruda.
 *
 * CONFIRMADO leyendo modulos/apk.sh (wrapper `compil-apk-termux`, PASO 3):
 *  - Subcomando real: `compil-apk-termux build <proyecto>` — pipeline todo-o-nada, sin paso
 *    intermedio invocable por separado (no existe "solo compilar sin firmar"; el `set -e` del
 *    wrapper corta en el primer error de cualquiera de los pasos).
 *  - Salida real: `<proyecto>/build/apk/final.apk` (firmado v1/v2/v3) — el comentario del
 *    header de apk.sh dice "build/final.apk" pero cmd_build() usa `$BUILD_DIR/apk/final.apk`
 *    de verdad (BUILD_DIR="$PROJECT/build"), confirmado en la función real, no en el comentario.
 *  - Cada paso imprime un marcador `=== [n/N] <descripción> ===` (aapt2 compile/link,
 *    [kotlinc — solo si el proyecto tiene .kt,] javac, d8, empaquetado, zipalign, apksigner) —
 *    se usa acá para actualizar [com.termux.app.util.ProgressDialogController] paso a paso en
 *    vez de un spinner genérico. N es 7 para proyectos solo-Java, 8 si hay fuentes Kotlin (ver
 *    docs/arquitectura/PROPUESTA_APK_MULTILENGUAJE_2026-08-26.md — el paso kotlinc es
 *    condicional, no altera el conteo de un proyecto Java puro).
 *  - Otros subcomandos del wrapper: `merge` sigue solo disponible a mano en terminal (fusión de
 *    split APKs, caso de uso poco frecuente). `info` y `decode` (2026-09-01, ver
 *    docs/referencias/herramientas/AUDITORIA_CATEGORIA_HERRAMIENTAS.md +
 *    docs/arquitectura/INVESTIGACION_GESTOR_ARCHIVOS_2026-09-01.md — mismo gap señalado por
 *    ambas auditorías) SÍ tienen UI real acá abajo (card "HERRAMIENTAS"), sobre CUALQUIER APK
 *    del dispositivo (no solo los que este módulo compiló) — ver [apkPicker]/[onApkPicked] y
 *    [runInfo]/[runDecode]. La UI solo invoca el binario `compil-apk-termux` vía ProcessBuilder
 *    y parsea su output; no reimplementa la lógica de `cmd_info()`/`cmd_decode()` de apk.sh.
 *
 * FileProvider dedicado (`${TERMUX_PACKAGE_NAME}.apkbuilder`, ver AndroidManifest.xml +
 * res/xml/apk_builder_paths.xml): el provider `.files` que ya existe en el manifest
 * (TermuxOpenReceiver.ContentProvider) exige la policy "allow-external-apps" en su
 * openFile() — pensada para plugins de terceros que llaman a la API de Termux, no para un
 * artefacto que la propia app genera y quiere entregarle al instalador de paquetes real. Se
 * agregó un provider nuevo, con alcance mínimo (solo `~/proyectos`), en vez de forzar el
 * existente contra una policy que no aplica a este caso de uso.
 */
class ApkFragment : BaseModuleFragment() {

    private var selectedProjectName: String? = null
    private var selectedProjectPath: String? = null
    private var selectedProjectValueText: TextView? = null
    private var apksCardBody: LinearLayout? = null

    // ── Selección de APK arbitrario para info/decode — distinto de selectedProjectPath (que
    //    es un PROYECTO fuente en ~/proyectos, no un .apk ya compilado). ──
    private var selectedApkName: String? = null
    private var selectedApkPath: String? = null
    private var selectedApkValueText: TextView? = null

    // ── Instrumentación sin root (fuente VictorH028/no-root-logger, ver docs/humano331.md) —
    //    decompila con apktool el APK elegido arriba (reusa selectedApkPath/selectedApkName,
    //    no hace falta elegir el APK dos veces), permite buscar una clase Smali, elegir un
    //    método e inyectar un hook que manda logs a un servidor local (127.0.0.1:9999).
    //    Pensado para depurar apps PROPIAS o AUTORIZADAS — ver disclaimer en la propia card. ──
    private var instrumentWorkDir: String? = null
    private var instrumentWorkDirValueText: TextView? = null
    private var instrumentSmaliClass: String? = null
    private var instrumentClassValueText: TextView? = null

    /**
     * `ACTION_OPEN_DOCUMENT` vía Storage Access Framework (mismo patrón que
     * `PluginsFragment.localPackagePicker`) — el `content://` que devuelve no es un path real
     * de filesystem, así que [onApkPicked] lo copia primero a `$HOME/tmp/apk_tools/` antes de
     * poder pasárselo a `compil-apk-termux` por `ProcessBuilder` (que corre `bash`, no entiende
     * URIs de content provider).
     */
    private val apkPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) onApkPicked(uri)
        }

    override fun getModuleId(): String = "apk"
    override fun getModuleName(): String = getString(R.string.apk_module_name)

    override fun buildContent() {
        if (!isModuleInstalled()) {
            showNotInstalled(getModuleName()) {
                installModuleInBackground { ok ->
                    toast(if (ok) getString(R.string.apk_toast_installed) else getString(R.string.apk_toast_install_failed))
                }
            }
            return
        }

        addCard(getString(R.string.apk_card_project)) {
            val (row, valueText) = valueRow(getString(R.string.apk_status_selected), selectedProjectName ?: getString(R.string.apk_status_none))
            addView(row)
            selectedProjectValueText = valueText
        }
        actionButton(getString(R.string.apk_btn_choose_project), GHOST) { pickProjectToBuild() }
        actionButton(getString(R.string.apk_btn_manage_projects), GHOST) {
            showProjectsMenu(onToast = { toast(it) })
        }

        addCard(getString(R.string.apk_card_build)) {
            addView(infoRow(getString(R.string.apk_info_pipeline_label), getString(R.string.apk_info_pipeline_value)))
            addView(infoRow(getString(R.string.apk_info_languages_label), getString(R.string.apk_info_languages_value)))
        }
        actionButton(getString(R.string.apk_btn_build), PRIMARY) { runBuild() }

        addCard(getString(R.string.apk_card_generated)) {
            apksCardBody = this
            renderApkRows()
        }

        addCard(getString(R.string.apk_card_tools)) {
            addView(infoRow(getString(R.string.apk_info_tools_desc_label), getString(R.string.apk_info_tools_desc_value)))
            val (row, valueText) = valueRow(getString(R.string.apk_status_apk_selected), selectedApkName ?: getString(R.string.apk_status_none))
            addView(row)
            selectedApkValueText = valueText
        }
        actionButton(getString(R.string.apk_btn_choose_apk), GHOST) { apkPicker.launch(arrayOf("application/vnd.android.package-archive")) }
        actionButton(getString(R.string.apk_btn_info), GHOST) { runInfo() }
        actionButton(getString(R.string.apk_btn_decode), GHOST) { runDecode() }

        addCard(getString(R.string.apk_card_instrument)) {
            addView(infoRow(getString(R.string.apk_instrument_disclaimer_label), getString(R.string.apk_instrument_disclaimer_value)))
            val (row1, valueText1) = valueRow(getString(R.string.apk_instrument_workdir_label), instrumentWorkDir?.let { File(it).name } ?: getString(R.string.apk_status_none))
            addView(row1)
            instrumentWorkDirValueText = valueText1
            val (row2, valueText2) = valueRow(getString(R.string.apk_instrument_class_label), instrumentSmaliClass?.let { File(it).name } ?: getString(R.string.apk_status_none))
            addView(row2)
            instrumentClassValueText = valueText2
        }
        actionButton(getString(R.string.apk_btn_instrument_decode), GHOST) { runInstrumentDecode() }
        actionButton(getString(R.string.apk_btn_instrument_pick_class), GHOST) { pickSmaliClass() }
        actionButton(getString(R.string.apk_btn_instrument_inject), PRIMARY) { pickMethodAndInject() }

        addCard(getString(R.string.apk_card_instrument_log)) {
            addView(infoRow(getString(R.string.apk_instrument_log_desc_label), getString(R.string.apk_instrument_log_desc_value)))
        }
        actionButton(getString(R.string.apk_btn_log_start), GHOST) { runLogControl("logstart", getString(R.string.apk_toast_log_started)) }
        actionButton(getString(R.string.apk_btn_log_stop), GHOST) { runLogControl("logstop", getString(R.string.apk_toast_log_stopped)) }
        actionButton(getString(R.string.apk_btn_log_view), PRIMARY) { showLogViewer() }

        addCard(getString(R.string.apk_card_maintenance)) {
            addView(infoRow(getString(R.string.apk_info_script_label), getString(R.string.apk_info_script_value)))
        }
        actionButton(getString(R.string.apk_btn_update_tools), GHOST) {
            toast(getString(R.string.apk_toast_updating, getModuleName()))
            updateModuleService { ok ->
                toast(if (ok) getString(R.string.apk_toast_updated, getModuleName()) else getString(R.string.apk_toast_update_failed))
            }
        }
        actionButton(getString(R.string.apk_btn_uninstall), DANGER) { confirmUninstall() }
    }

    // ── Selección de proyecto — reusa ProjectsManager (misma fuente que ProjectActions.kt::
    //    pickProjectToOpen) para listar ~/proyectos, sin duplicar su lógica de symlink/copiar. ──

    private fun pickProjectToBuild() {
        runProjectsAction({ ProjectsManager.projectsList() }) { json ->
            if (!json.optBoolean("ok", false)) {
                toast(getString(R.string.apk_toast_error_generic, json.optString("error", getString(R.string.apk_error_unknown))))
                return@runProjectsAction
            }
            val projects = json.optJSONArray("projects")
            if (projects == null || projects.length() == 0) {
                toast(getString(R.string.apk_toast_no_projects))
                return@runProjectsAction
            }
            val names = (0 until projects.length()).map { projects.getJSONObject(it).optString("name") }
            val paths = (0 until projects.length()).map { projects.getJSONObject(it).optString("path") }
            AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.apk_title_choose_project))
                .setItems(names.toTypedArray()) { _, which ->
                    selectedProjectName = names[which]
                    selectedProjectPath = paths[which]
                    selectedProjectValueText?.text = names[which]
                }
                .setNegativeButton(getString(R.string.apk_btn_cancel), null)
                .show()
        }
    }

    // ── Compilación real — corre `compil-apk-termux build <proyecto>` (pipeline todo-o-nada
    //    confirmado leyendo modulos/apk.sh) y traduce sus marcadores "=== [n/N] ... ===" a
    //    actualizaciones de ProgressDialogController en vez de un spinner genérico. ──

    private fun runBuild() {
        val projectPath = selectedProjectPath
        if (projectPath.isNullOrBlank()) {
            toast(getString(R.string.apk_toast_choose_project_first))
            return
        }
        if (!File(projectPath).isDirectory) {
            toast(getString(R.string.apk_toast_project_missing, projectPath))
            return
        }

        val appContext = requireContext().applicationContext
        val progress = com.termux.app.util.ProgressDialogController(requireContext())
        // allowBackground=true: compilar un APK (aapt2 → javac/kotlinc → d8 → zipalign →
        // apksigner) puede tardar varios minutos — mismo tratamiento que las descargas de
        // modelos/imágenes (docs/humano247.md), el usuario puede seguir navegando mientras
        // corre y se avisa por notificación al terminar.
        progress.show(getString(R.string.apk_progress_title), getString(R.string.apk_progress_starting), allowBackground = true)
        val fullLog = StringBuilder()

        Thread {
            var success = false
            try {
                val pb = ProcessBuilder(TERMUX_BASH_PATH, "-c", "compil-apk-termux build ${shellQuote(projectPath)}")
                pb.applyTermuxEnv()
                pb.redirectErrorStream(true)
                val process = pb.start()
                process.inputStream.bufferedReader().forEachLine { line ->
                    fullLog.appendLine(line)
                    val stepLabel = when {
                        line.startsWith("=== ") -> line.removePrefix("=== ").removeSuffix(" ===").trim()
                        line.startsWith("  [ERROR]") -> line.trim()
                        else -> null
                    }
                    if (stepLabel != null && isAdded) {
                        activity?.runOnUiThread { if (isAdded) progress.update(stepLabel) }
                    }
                }
                success = process.waitFor() == 0
            } catch (e: Exception) {
                fullLog.appendLine(getString(R.string.apk_exception_prefix, e.message))
            }
            val detail = fullLog.toString().takeLast(4000)
            if (progress.isBackgrounded) {
                com.termux.app.util.ModuleEventBridge.notifyDirect(
                    appContext, "apk",
                    if (success) "install_done" else "install_failed",
                    if (success) appContext.getString(R.string.apk_notify_build_done, selectedProjectName) else detail
                )
            }
            if (!isAdded) return@Thread
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                if (success) {
                    progress.success(getString(R.string.apk_progress_success, selectedProjectName), detail)
                    renderApkRows()
                } else {
                    progress.failure(getString(R.string.apk_progress_failure), detail)
                }
            }
        }.start()
    }

    // ── Herramientas info/decode — sobre CUALQUIER APK elegido por el usuario (SAF), no solo
    //    los que este módulo compiló. Reusan compil-apk-termux vía ProcessBuilder, igual que
    //    runBuild() arriba — la UI solo invoca el binario y parsea/muestra su output. ──

    private fun onApkPicked(uri: Uri) {
        val ctx = requireContext().applicationContext
        val name = queryDisplayName(uri) ?: "apk_${System.currentTimeMillis()}.apk"
        val safeName = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val toolsDir = File(TermuxConstants.TERMUX_HOME_DIR_PATH, "tmp/apk_tools")
        val dest = File(toolsDir, safeName)
        Thread {
            var ok = false
            var err: String? = null
            try {
                toolsDir.mkdirs()
                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(dest).use { output -> input.copyTo(output) }
                    ok = true
                } ?: run { err = getString(R.string.apk_error_unknown) }
            } catch (e: Exception) {
                err = e.message
            }
            if (!isAdded) return@Thread
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                if (ok) {
                    selectedApkName = safeName
                    selectedApkPath = dest.absolutePath
                    selectedApkValueText?.text = safeName
                } else {
                    toast(getString(R.string.apk_toast_copy_failed, err ?: getString(R.string.apk_error_unknown)))
                }
            }
        }.start()
    }

    /** `content://` no trae el nombre real en el propio URI — hay que consultar `DISPLAY_NAME` vía el ContentResolver (patrón estándar de SAF, mismo que usa LocalPluginManager para paquetes .deb/.tar.gz). */
    private fun queryDisplayName(uri: Uri): String? {
        return try {
            requireContext().contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun runInfo() {
        val apkPath = selectedApkPath
        if (apkPath.isNullOrBlank() || !File(apkPath).isFile) {
            toast(getString(R.string.apk_toast_choose_apk_first))
            return
        }
        val appContext = requireContext().applicationContext
        val progress = com.termux.app.util.ProgressDialogController(requireContext())
        progress.show(getString(R.string.apk_progress_info_title), getString(R.string.apk_progress_info_starting), allowBackground = true)
        val apkName = selectedApkName ?: apkPath

        Thread {
            var success = false
            val output = StringBuilder()
            try {
                val pb = ProcessBuilder(TERMUX_BASH_PATH, "-c", "compil-apk-termux info ${shellQuote(apkPath)}")
                pb.applyTermuxEnv()
                pb.redirectErrorStream(true)
                val process = pb.start()
                process.inputStream.bufferedReader().forEachLine { output.appendLine(it) }
                success = process.waitFor() == 0
            } catch (e: Exception) {
                output.appendLine(getString(R.string.apk_exception_prefix, e.message))
            }
            val detail = output.toString().trim()
            if (progress.isBackgrounded) {
                com.termux.app.util.ModuleEventBridge.notifyDirect(
                    appContext, "apk",
                    if (success) "install_done" else "install_failed",
                    if (success) appContext.getString(R.string.apk_notify_info_done, apkName) else detail
                )
            }
            if (!isAdded) return@Thread
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                if (success) {
                    // dismiss() en vez de success(): el detalle real (paquete/versión/permisos/
                    // firma) va en un diálogo dedicado de texto seleccionable (showTextDialog),
                    // no en la sección colapsable "Ver detalles" del progress dialog — acá SÍ es
                    // el entregable principal, no un log secundario.
                    progress.dismiss()
                    showTextDialog(getString(R.string.apk_title_info_result, apkName), detail)
                } else {
                    progress.failure(getString(R.string.apk_progress_info_failure), detail)
                }
            }
        }.start()
    }

    private fun runDecode() {
        val apkPath = selectedApkPath
        if (apkPath.isNullOrBlank() || !File(apkPath).isFile) {
            toast(getString(R.string.apk_toast_choose_apk_first))
            return
        }
        val apkName = selectedApkName ?: File(apkPath).name
        val baseName = apkName.removeSuffix(".apk").ifBlank { "apk" }
        val outDir = File(TermuxConstants.TERMUX_HOME_DIR_PATH, "apk_decoded/${baseName}_${System.currentTimeMillis()}")

        val appContext = requireContext().applicationContext
        val progress = com.termux.app.util.ProgressDialogController(requireContext())
        progress.show(getString(R.string.apk_progress_decode_title), getString(R.string.apk_progress_decode_starting), allowBackground = true)
        val fullLog = StringBuilder()

        Thread {
            var success = false
            try {
                val cmd = "compil-apk-termux decode ${shellQuote(apkPath)} ${shellQuote(outDir.absolutePath)}"
                val pb = ProcessBuilder(TERMUX_BASH_PATH, "-c", cmd)
                pb.applyTermuxEnv()
                pb.redirectErrorStream(true)
                val process = pb.start()
                process.inputStream.bufferedReader().forEachLine { line ->
                    fullLog.appendLine(line)
                    val stepLabel = when {
                        line.startsWith("=== ") -> line.removePrefix("=== ").removeSuffix(" ===").trim()
                        line.startsWith("──") -> line.trim()
                        else -> null
                    }
                    if (stepLabel != null && isAdded) {
                        activity?.runOnUiThread { if (isAdded) progress.update(stepLabel) }
                    }
                }
                success = process.waitFor() == 0
            } catch (e: Exception) {
                fullLog.appendLine(getString(R.string.apk_exception_prefix, e.message))
            }
            val detail = fullLog.toString().takeLast(4000)
            if (progress.isBackgrounded) {
                com.termux.app.util.ModuleEventBridge.notifyDirect(
                    appContext, "apk",
                    if (success) "install_done" else "install_failed",
                    if (success) appContext.getString(R.string.apk_notify_decode_done, apkName) else detail
                )
            }
            if (!isAdded) return@Thread
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                if (success) {
                    progress.success(getString(R.string.apk_progress_decode_success, outDir.absolutePath), detail)
                    offerOpenDecodedFolder(outDir)
                } else {
                    progress.failure(getString(R.string.apk_progress_decode_failure), detail)
                }
            }
        }.start()
    }

    private fun offerOpenDecodedFolder(outDir: File) {
        if (!isAdded) return
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.apk_progress_decode_success, outDir.name))
            .setPositiveButton(getString(R.string.apk_btn_open_in_files)) { _, _ ->
                navigateTo(FileManagerFragment.newInstance(outDir.absolutePath))
            }
            .setNegativeButton(getString(R.string.apk_btn_cancel), null)
            .show()
    }

    // ── Instrumentación sin root — ver KDoc del campo instrumentWorkDir arriba. Cada función
    //    invoca `compil-apk-termux instrument-*`/`log*` vía ProcessBuilder, igual patrón que
    //    runBuild()/runInfo()/runDecode() de arriba: la UI solo corre el binario y parsea su
    //    output, no reimplementa la lógica de apk.sh. ──

    private fun runInstrumentDecode() {
        val apkPath = selectedApkPath
        if (apkPath.isNullOrBlank() || !File(apkPath).isFile) {
            toast(getString(R.string.apk_toast_choose_apk_first))
            return
        }
        val appContext = requireContext().applicationContext
        val progress = com.termux.app.util.ProgressDialogController(requireContext())
        progress.show(getString(R.string.apk_progress_instrument_decode_title), getString(R.string.apk_progress_instrument_decode_starting), allowBackground = true)
        val fullLog = StringBuilder()
        var workDir: String? = null
        val apkName = selectedApkName ?: apkPath

        Thread {
            var success = false
            try {
                val pb = ProcessBuilder(TERMUX_BASH_PATH, "-c", "compil-apk-termux instrument-decode ${shellQuote(apkPath)}")
                pb.applyTermuxEnv()
                pb.redirectErrorStream(true)
                val process = pb.start()
                process.inputStream.bufferedReader().forEachLine { line ->
                    fullLog.appendLine(line)
                    when {
                        line.startsWith("WORKDIR:") -> workDir = line.removePrefix("WORKDIR:").trim()
                        line.startsWith("=== ") && isAdded -> {
                            val stepLabel = line.removePrefix("=== ").removeSuffix(" ===").trim()
                            activity?.runOnUiThread { if (isAdded) progress.update(stepLabel) }
                        }
                    }
                }
                success = process.waitFor() == 0 && workDir != null
            } catch (e: Exception) {
                fullLog.appendLine(getString(R.string.apk_exception_prefix, e.message))
            }
            val detail = fullLog.toString().takeLast(4000)
            val finalWorkDir = workDir
            if (progress.isBackgrounded) {
                com.termux.app.util.ModuleEventBridge.notifyDirect(
                    appContext, "apk",
                    if (success) "install_done" else "install_failed",
                    if (success) appContext.getString(R.string.apk_notify_instrument_decode_done, apkName) else detail
                )
            }
            if (!isAdded) return@Thread
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                if (success && finalWorkDir != null) {
                    instrumentWorkDir = finalWorkDir
                    instrumentSmaliClass = null
                    instrumentWorkDirValueText?.text = File(finalWorkDir).name
                    instrumentClassValueText?.text = getString(R.string.apk_status_none)
                    progress.success(getString(R.string.apk_progress_instrument_decode_success), detail)
                } else {
                    progress.failure(getString(R.string.apk_progress_instrument_decode_failure), detail)
                }
            }
        }.start()
    }

    private fun pickSmaliClass() {
        val workDir = instrumentWorkDir
        if (workDir.isNullOrBlank()) {
            toast(getString(R.string.apk_toast_instrument_decode_first))
            return
        }
        val input = EditText(requireContext()).apply { hint = getString(R.string.apk_hint_search_class) }
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.apk_title_search_class))
            .setView(input)
            .setPositiveButton(getString(R.string.apk_btn_search)) { _, _ ->
                val query = input.text.toString().trim()
                if (query.isNotEmpty()) runSmaliSearch(workDir, query)
            }
            .setNegativeButton(getString(R.string.apk_btn_cancel), null)
            .show()
    }

    private fun runSmaliSearch(workDir: String, query: String) {
        Thread {
            val results = mutableListOf<String>()
            try {
                val pb = ProcessBuilder(TERMUX_BASH_PATH, "-c", "compil-apk-termux instrument-search ${shellQuote(workDir)} ${shellQuote(query)}")
                pb.applyTermuxEnv()
                pb.redirectErrorStream(true)
                val process = pb.start()
                process.inputStream.bufferedReader().forEachLine { if (it.isNotBlank()) results.add(it) }
                process.waitFor()
            } catch (_: Exception) {
            }
            if (!isAdded) return@Thread
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                if (results.isEmpty()) {
                    toast(getString(R.string.apk_toast_no_classes_found))
                    return@runOnUiThread
                }
                // Máximo 100 resultados mostrados — una app grande puede tener miles de .smali
                // coincidiendo con una búsqueda amplia (ej. "a"); el usuario puede refinar la
                // búsqueda en vez de scrollear una lista gigante.
                val shown = results.take(100)
                AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.apk_title_choose_class))
                    .setItems(shown.toTypedArray()) { _, which ->
                        instrumentSmaliClass = shown[which]
                        instrumentClassValueText?.text = File(shown[which]).name
                    }
                    .setNegativeButton(getString(R.string.apk_btn_cancel), null)
                    .show()
            }
        }.start()
    }

    private fun pickMethodAndInject() {
        val workDir = instrumentWorkDir
        val smaliClass = instrumentSmaliClass
        if (workDir.isNullOrBlank() || smaliClass.isNullOrBlank()) {
            toast(getString(R.string.apk_toast_instrument_pick_class_first))
            return
        }
        Thread {
            val methodNames = mutableListOf<String>()
            try {
                val pb = ProcessBuilder(TERMUX_BASH_PATH, "-c", "compil-apk-termux instrument-methods ${shellQuote(workDir)} ${shellQuote(smaliClass)}")
                pb.applyTermuxEnv()
                pb.redirectErrorStream(true)
                val process = pb.start()
                val out = process.inputStream.bufferedReader().readText()
                process.waitFor()
                val arr = org.json.JSONArray(out.trim())
                for (i in 0 until arr.length()) {
                    methodNames.add(arr.getJSONObject(i).optString("name"))
                }
            } catch (_: Exception) {
            }
            if (!isAdded) return@Thread
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                if (methodNames.isEmpty()) {
                    toast(getString(R.string.apk_toast_no_methods_found))
                    return@runOnUiThread
                }
                AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.apk_title_choose_method))
                    .setItems(methodNames.toTypedArray()) { _, which -> showActionDialog(workDir, smaliClass, methodNames[which]) }
                    .setNegativeButton(getString(R.string.apk_btn_cancel), null)
                    .show()
            }
        }.start()
    }

    private fun showActionDialog(workDir: String, smaliClass: String, method: String) {
        if (!isAdded) return
        val actions = arrayOf(
            getString(R.string.apk_action_enter),
            getString(R.string.apk_action_exit),
            getString(R.string.apk_action_both),
            getString(R.string.apk_action_log),
        )
        val actionValues = arrayOf("enter", "exit", "both", "d")
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.apk_title_choose_action, method))
            .setItems(actions) { _, which ->
                val action = actionValues[which]
                if (action == "d") {
                    showLogMessageDialog(workDir, smaliClass, method, action)
                } else {
                    runInstrumentApply(workDir, smaliClass, method, action, null, null)
                }
            }
            .setNegativeButton(getString(R.string.apk_btn_cancel), null)
            .show()
    }

    private fun showLogMessageDialog(workDir: String, smaliClass: String, method: String, action: String) {
        val ctx = requireContext()
        val tagInput = EditText(ctx).apply { hint = getString(R.string.apk_hint_tag) }
        val msgInput = EditText(ctx).apply { hint = getString(R.string.apk_hint_message) }
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), 0)
            addView(tagInput)
            addView(msgInput)
        }
        AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.apk_title_log_message))
            .setView(layout)
            .setPositiveButton(getString(R.string.apk_btn_inject)) { _, _ ->
                val tag = tagInput.text.toString().trim().ifEmpty { "LOG" }
                val message = msgInput.text.toString().trim().ifEmpty { getString(R.string.apk_default_log_message) }
                runInstrumentApply(workDir, smaliClass, method, action, tag, message)
            }
            .setNegativeButton(getString(R.string.apk_btn_cancel), null)
            .show()
    }

    private fun runInstrumentApply(workDir: String, smaliClass: String, method: String, action: String, tag: String?, message: String?) {
        val appContext = requireContext().applicationContext
        val progress = com.termux.app.util.ProgressDialogController(requireContext())
        progress.show(getString(R.string.apk_progress_instrument_apply_title), getString(R.string.apk_progress_instrument_apply_starting), allowBackground = true)
        val fullLog = StringBuilder()
        var instrumentedApk: String? = null
        val methodLabel = method

        Thread {
            var success = false
            try {
                val cmdParts = mutableListOf(
                    "compil-apk-termux", "instrument-apply",
                    shellQuote(workDir), shellQuote(smaliClass), shellQuote(method), shellQuote(action)
                )
                if (tag != null) cmdParts.add(shellQuote(tag))
                if (message != null) cmdParts.add(shellQuote(message))
                val pb = ProcessBuilder(TERMUX_BASH_PATH, "-c", cmdParts.joinToString(" "))
                pb.applyTermuxEnv()
                pb.redirectErrorStream(true)
                val process = pb.start()
                process.inputStream.bufferedReader().forEachLine { line ->
                    fullLog.appendLine(line)
                    when {
                        line.startsWith("✅ APK instrumentado: ") -> instrumentedApk = line.removePrefix("✅ APK instrumentado: ").trim()
                        line.startsWith("=== ") && isAdded -> {
                            val stepLabel = line.removePrefix("=== ").removeSuffix(" ===").trim()
                            activity?.runOnUiThread { if (isAdded) progress.update(stepLabel) }
                        }
                    }
                }
                success = process.waitFor() == 0 && instrumentedApk != null
            } catch (e: Exception) {
                fullLog.appendLine(getString(R.string.apk_exception_prefix, e.message))
            }
            val detail = fullLog.toString().takeLast(4000)
            val finalApk = instrumentedApk
            if (progress.isBackgrounded) {
                com.termux.app.util.ModuleEventBridge.notifyDirect(
                    appContext, "apk",
                    if (success) "install_done" else "install_failed",
                    if (success) appContext.getString(R.string.apk_notify_instrument_apply_done, methodLabel) else detail
                )
            }
            if (!isAdded) return@Thread
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                if (success && finalApk != null) {
                    progress.success(getString(R.string.apk_progress_instrument_apply_success), detail)
                    offerInstallInstrumentedApk(File(finalApk))
                } else {
                    progress.failure(getString(R.string.apk_progress_instrument_apply_failure), detail)
                }
            }
        }.start()
    }

    private fun offerInstallInstrumentedApk(apkFile: File) {
        if (!isAdded || !apkFile.isFile) return
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.apk_title_instrument_install))
            .setMessage(getString(R.string.apk_msg_instrument_install))
            .setPositiveButton(getString(R.string.apk_install_option)) { _, _ ->
                val uri = try {
                    FileProvider.getUriForFile(requireContext(), "${requireContext().packageName}.apkbuilder", apkFile)
                } catch (e: Exception) {
                    toast(getString(R.string.apk_toast_prepare_failed, e.message))
                    return@setPositiveButton
                }
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try {
                    startActivity(intent)
                } catch (e: Exception) {
                    toast(getString(R.string.apk_toast_installer_open_failed, e.message))
                }
            }
            .setNegativeButton(getString(R.string.apk_btn_cancel), null)
            .show()
    }

    // ── Servidor de logs de la instrumentación (127.0.0.1:9999, ver modulos/apk.sh
    //    logstart/logstop/logstatus/logtail) — corre en una sesión tmux detached, mismo
    //    patrón que otros módulos con servidor propio (n8n, ollama). ──

    private fun runLogControl(subcommand: String, doneMessage: String) {
        Thread {
            var out = ""
            try {
                val pb = ProcessBuilder(TERMUX_BASH_PATH, "-c", "compil-apk-termux $subcommand")
                pb.applyTermuxEnv()
                pb.redirectErrorStream(true)
                val process = pb.start()
                out = process.inputStream.bufferedReader().readText().trim()
                process.waitFor()
            } catch (e: Exception) {
                out = getString(R.string.apk_exception_prefix, e.message)
            }
            if (!isAdded) return@Thread
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                toast(if (out.startsWith("[ERROR]")) out else doneMessage)
            }
        }.start()
    }

    private fun showLogViewer() {
        val ctx = requireContext()
        val text = TextView(ctx).apply {
            textSize = 11f
            setTypeface(Typeface.MONOSPACE)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setTextIsSelectable(true)
            text = getString(R.string.apk_log_loading)
        }
        val dialog = AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.apk_title_log_viewer))
            .setView(ScrollView(ctx).apply { addView(text) })
            .setPositiveButton(getString(R.string.apk_btn_refresh_log), null)
            .setNegativeButton(getString(R.string.apk_btn_cancel), null)
            .show()

        fun refresh() {
            Thread {
                var out: String
                try {
                    val pb = ProcessBuilder(TERMUX_BASH_PATH, "-c", "compil-apk-termux logtail 200")
                    pb.applyTermuxEnv()
                    pb.redirectErrorStream(true)
                    val process = pb.start()
                    out = process.inputStream.bufferedReader().readText()
                    process.waitFor()
                } catch (e: Exception) {
                    out = getString(R.string.apk_exception_prefix, e.message)
                }
                if (!isAdded) return@Thread
                activity?.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    text.text = out.ifBlank { getString(R.string.apk_log_empty) }
                }
            }.start()
        }
        // setOnClickListener explícito en vez de la lambda de setPositiveButton — así el
        // diálogo NO se cierra al tocar "Actualizar" y se puede refrescar varias veces
        // seguidas mientras el usuario dispara hooks en la app instrumentada.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { refresh() }
        refresh()
    }

    private fun showTextDialog(title: String, body: String) {
        val ctx = requireContext()
        val text = TextView(ctx).apply {
            text = body
            textSize = 12f
            setTypeface(Typeface.MONOSPACE)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setTextIsSelectable(true)
        }
        AlertDialog.Builder(ctx)
            .setTitle(title)
            .setView(ScrollView(ctx).apply { addView(text) })
            .setPositiveButton(getString(R.string.apk_btn_cancel), null)
            .show()
    }

    // ── Listado de APKs generados — escanea <proyecto>/build/apk/final.apk bajo ~/proyectos
    //    (ruta real confirmada en cmd_build() del wrapper, no la del comentario del header). ──

    private fun scanGeneratedApks(): List<Pair<String, File>> {
        val root = ProjectsManager.PROJECTS_DIR
        if (!root.isDirectory) return emptyList()
        return root.listFiles { f -> f.isDirectory }
            ?.mapNotNull { dir ->
                val apk = File(dir, "build/apk/final.apk")
                if (apk.isFile) dir.name to apk else null
            }
            ?.sortedByDescending { it.second.lastModified() }
            ?: emptyList()
    }

    private fun renderApkRows() {
        val body = apksCardBody ?: return
        body.removeAllViews()
        val apks = scanGeneratedApks()
        if (apks.isEmpty()) {
            body.addView(infoRow(getString(R.string.apk_status_label), getString(R.string.apk_status_no_builds)))
            return
        }
        apks.forEach { (projectName, apkFile) ->
            val row = infoRow(projectName, ManagerNativeUtils.humanSize(apkFile.length()))
            row.isClickable = true
            row.isFocusable = true
            row.setOnClickListener { shareOrInstallApk(projectName, apkFile) }
            body.addView(row)
        }
    }

    // ── Compartir / instalar — content:// vía el FileProvider dedicado "apkbuilder" (ver
    //    AndroidManifest.xml + res/xml/apk_builder_paths.xml), no el ".files" existente
    //    (bloqueado por la policy allow-external-apps, ver KDoc de la clase). ──

    private fun shareOrInstallApk(projectName: String, apkFile: File) {
        val options = arrayOf(getString(R.string.apk_share_option), getString(R.string.apk_install_option))
        AlertDialog.Builder(requireContext())
            .setTitle(projectName)
            .setItems(options) { _, which ->
                val uri = try {
                    FileProvider.getUriForFile(requireContext(), "${requireContext().packageName}.apkbuilder", apkFile)
                } catch (e: Exception) {
                    toast(getString(R.string.apk_toast_prepare_failed, e.message))
                    return@setItems
                }
                when (which) {
                    0 -> {
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "application/vnd.android.package-archive"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        startActivity(Intent.createChooser(intent, getString(R.string.apk_share_chooser_title)))
                    }
                    1 -> {
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, "application/vnd.android.package-archive")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        try {
                            startActivity(intent)
                        } catch (e: Exception) {
                            toast(getString(R.string.apk_toast_installer_open_failed, e.message))
                        }
                    }
                }
            }
            .setNegativeButton(getString(R.string.apk_btn_cancel), null)
            .show()
    }

    private fun confirmUninstall() {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.apk_title_confirm_uninstall, getModuleName()))
            .setMessage(getString(R.string.apk_msg_confirm_uninstall))
            .setPositiveButton(getString(R.string.apk_btn_uninstall_confirm)) { _, _ ->
                com.termux.app.ModuleController.uninstallModule(getModuleId()) { ok ->
                    if (!isAdded) return@uninstallModule
                    requireActivity().runOnUiThread {
                        if (!isAdded) return@runOnUiThread
                        if (ok) {
                            toast(getString(R.string.apk_toast_uninstalled, getModuleName()))
                            parentFragmentManager.popBackStack()
                        } else {
                            toast(getString(R.string.apk_toast_uninstall_failed, getModuleName()))
                        }
                    }
                }
            }
            .setNegativeButton(getString(R.string.apk_btn_cancel), null)
            .show()
    }
}

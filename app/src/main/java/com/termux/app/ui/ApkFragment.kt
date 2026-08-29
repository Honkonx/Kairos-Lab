package com.termux.app.ui

import android.content.Intent
import android.widget.LinearLayout
import android.widget.TextView
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
import java.io.File

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
 *  - Otros subcomandos del wrapper (`info`, `merge`, `decode`) no forman parte del pipeline de
 *    build — quedan fuera de esta pantalla (siguen disponibles a mano en terminal).
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

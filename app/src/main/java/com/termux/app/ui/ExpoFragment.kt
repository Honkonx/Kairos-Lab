package com.termux.app.ui

import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import com.google.android.material.snackbar.Snackbar
import com.termux.R
import com.termux.app.ui.BaseModuleFragment.ButtonStyle.GHOST
import com.termux.app.ui.BaseModuleFragment.ButtonStyle.PRIMARY
import com.termux.app.util.TERMUX_BASH_PATH
import com.termux.app.util.TERMUX_PREFIX_PATH
import com.termux.app.util.applyTermuxEnv
import com.termux.app.util.friendlyProcessErrorMessage
import com.termux.app.util.shellQuote
import com.termux.shared.termux.TermuxConstants
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class ExpoFragment : BaseModuleFragment() {
    override fun getModuleId() = "expo"
    override fun getModuleName() = getString(R.string.expo_module_name)

    // Holds latest expo info for UI updates
    private var expoInfo: JSONObject? = null

    private val easProjectFile get() = File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".eas_active_project")

    private var easVersionValue: android.widget.TextView? = null
    private var nodeVersionValue: android.widget.TextView? = null
    private var userValue: android.widget.TextView? = null
    private var projectValue: android.widget.TextView? = null

    override fun buildContent() {
        if (!isModuleInstalled()) { showNotInstalled(getModuleName()); return }
        // Info card
        // Bug real (2026-08-07, ver docs/humano/humano91.md): las 4 filas quedaban en "—"
        // para siempre — buildInfoJson() SÍ trae los datos reales pero runExpoAction()
        // nunca los volvía a pintar acá, solo guardaba expoInfo sin usarlo (mismo bug que
        // PythonFragment). Ahora se cargan solos al abrir la pantalla.
        addCard(getString(R.string.expo_card_estado)) {
            addView(infoRow(getString(R.string.expo_label_eas_version), getString(R.string.expo_placeholder_dash)).also { easVersionValue = it.valueTextView() })
            addView(infoRow(getString(R.string.expo_label_node_version), getString(R.string.expo_placeholder_dash)).also { nodeVersionValue = it.valueTextView() })
            addView(infoRow(getString(R.string.expo_label_expo_user), getString(R.string.expo_placeholder_dash)).also { userValue = it.valueTextView() })
            addView(infoRow(getString(R.string.expo_label_active_project), getString(R.string.expo_placeholder_dash)).also { projectValue = it.valueTextView() })
        }
        actionButton(getString(R.string.expo_btn_build_preview), PRIMARY) { runExpoAction("build", "preview") }
        actionButton(getString(R.string.expo_btn_build_production), GHOST) { runExpoAction("build", "production") }
        actionButton(getString(R.string.expo_btn_view_builds), GHOST) { runExpoAction("builds-list") }
        // docs.expo.dev/eas/json/ — "build" define perfiles nombrados (development/preview/
        // production/lo que sea) con distribution/android.buildType/env propios. Antes esta
        // pantalla solo ofrecía 2 botones hardcodeados (preview/production, via
        // buildBuildJson()) — cualquier perfil custom del eas.json real del proyecto (ej. uno
        // con --profile "internal-qa") no tenía forma de lanzarse desde la app.
        actionButton(getString(R.string.expo_btn_build_profiles), GHOST) { runExpoAction("build-profiles") }
        // docs.expo.dev/develop/expo-doctor/ — diagnóstico oficial de "expo-doctor" (antes
        // "expo doctor"), no tenía ningún botón en la app; el único diagnóstico disponible
        // era la card ESTADO (versiones + usuario). Salida acotada (no interactivo, exit
        // code + texto) — encaja en un diálogo nativo, no necesita terminal.
        actionButton(getString(R.string.expo_btn_doctor), GHOST) { runExpoAction("doctor") }
        // "eas update" (OTA — Over The Air) publica cambios de JS/assets sin pasar por un
        // build nativo nuevo ni por revisión de tienda — la mitad del propósito real de EAS
        // que faltaba acá (solo estaba cubierto "eas build", la otra mitad del flujo real de
        // Expo). Soporta --non-interactive con --branch + --message, a diferencia de
        // "eas submit" (ver de abajo) que necesita más contexto interactivo en la mayoría de
        // los casos (selección de build/credenciales), por eso ese va a terminal.
        actionButton(getString(R.string.expo_btn_publish_ota), GHOST) { showEasUpdateDialog() }
        actionButton(getString(R.string.expo_btn_submit), GHOST) {
            launchTerminalCommand("eas submit --platform android")
        }
        actionButton(getString(R.string.expo_btn_login), GHOST) { openLoginSession() }
        actionButton(getString(R.string.expo_btn_logout), GHOST) { launchTerminalCommand("eas logout") }
        actionButton(getString(R.string.expo_btn_info), GHOST) { runExpoAction("info") }
        actionButton(getString(R.string.expo_btn_select_project), GHOST) { selectProject() }
        actionButton(getString(R.string.expo_btn_git_push), GHOST) { promptAndRun("git-push") }
        // submenu_expo() de termux-ai-stack (menu_nativo.sh) tiene la opción [u] "Actualizar
        // EAS CLI", que corre exactamente "npm install -g eas-cli@latest" — cmd_expo no
        // tiene una acción equivalente, pero es un único comando npm sin interacción real,
        // así que no hace falta agregar nada: alcanza con lanzarlo en la terminal (mismo
        // patrón que openLoginSession()) para que el usuario vea el output.
        actionButton(getString(R.string.expo_btn_update_cli), GHOST) { launchTerminalCommand("npm install -g eas-cli@latest") }
        // Consistencia con Db/Entorno/Qemu/Remote/Ciberseguridad (auditoría de menús
        // 2026-08-19, ver docs/viejo/AUDITORIA_CONSISTENCIA_MENUS_IA_2026-08-19.md):
        // a diferencia de "Actualizar EAS CLI" de arriba (npm suelto, no pasa por
        // ModuleController), Expo tampoco tenía ni el flujo estándar de actualización
        // (expo.sh --silent, mismo mecanismo que el resto de los módulos) ni forma de
        // desinstalarse desde su propia pantalla — único de los 8 módulos auditados sin
        // NINGUNA de las dos, así que acá sí se usa addMaintenanceCard() completo en vez de
        // solo el botón de Desinstalar.
        addMaintenanceCard()
        runExpoAction("info")
    }

    // Antes esto pasaba por kairos_manager.py ("python3 kairos_manager.py expo <action>"),
    // que a su vez armaba comandos de shell a mano con shlex.quote() (ver comentarios de
    // "Auditoría 2026-07-27" que quedan en modulos/kairos_manager.py explicando bugs reales
    // de shell-injection ya arreglados ahí: un mensaje de commit con un apóstrofe rompía el
    // comando). Acá Kotlin llama eas/node/git directo por ProcessBuilder con cada argumento
    // como elemento separado de la lista — no hay shell de por medio, así que no hace falta
    // escapar nada (ni shlex.quote ni su equivalente Kotlin).
    private fun runExpoAction(vararg args: String) {
        if (args.isEmpty()) return
        val action = args[0]
        val extra = args.drop(1)
        Thread {
            val json = try {
                when (action) {
                    "info" -> buildInfoJson()
                    "set-project" -> buildSetProjectJson(extra.getOrNull(0))
                    "get-project" -> buildGetProjectJson()
                    "build" -> buildBuildJson(extra.getOrNull(0))
                    "builds-list" -> buildBuildsListJson()
                    "build-profiles" -> buildBuildProfilesJson()
                    "doctor" -> buildDoctorJson()
                    "update" -> buildUpdateJson(extra.getOrNull(0), extra.getOrNull(1))
                    "git-push" -> buildGitPushJson(extra.getOrNull(0))
                    else -> JSONObject().put("ok", false).put("error", getString(R.string.expo_error_unknown_action, action))
                }
            } catch (e: Exception) {
                JSONObject().put("ok", false).put("error", friendlyProcessErrorMessage(e, "Expo (eas/node)"))
            }
            val ok = json.optBoolean("ok", false)
            if (ok && action == "info") expoInfo = json
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                if (ok && action == "info") {
                    easVersionValue?.text = if (json.isNull("eas")) getString(R.string.expo_placeholder_dash) else json.optString("eas", getString(R.string.expo_placeholder_dash))
                    nodeVersionValue?.text = if (json.isNull("node")) getString(R.string.expo_placeholder_dash) else json.optString("node", getString(R.string.expo_placeholder_dash))
                    userValue?.text = if (json.isNull("user")) getString(R.string.expo_placeholder_dash) else json.optString("user", getString(R.string.expo_placeholder_dash))
                    projectValue?.text = if (json.isNull("project_name")) getString(R.string.expo_placeholder_dash) else json.optString("project_name", getString(R.string.expo_placeholder_dash))
                    return@runOnUiThread
                }
                if (ok && action == "builds-list") { showBuildsListDialog(json); return@runOnUiThread }
                if (ok && action == "build-profiles") { showBuildProfilesDialog(json); return@runOnUiThread }
                if (ok && action == "doctor") { showDoctorDialog(json); return@runOnUiThread }
                val msg = if (ok) getString(R.string.expo_msg_ok) else getString(R.string.expo_error_with_reason, json.optString("error", getString(R.string.expo_error_unknown)))
                Snackbar.make(requireView(), msg, Snackbar.LENGTH_SHORT).show()
            }
        }.start()
    }

    private fun buildInfoJson(): JSONObject {
        val easRes = runCommand(listOf("eas", "--version"), 10)
        val nodeRes = runCommand(listOf("node", "--version"), 5)
        val userRes = runCommand(listOf("eas", "whoami"), 10)
        val easVersion = easRes.stdout.lineSequence().firstOrNull()?.takeIf { easRes.exitCode == 0 && it.isNotBlank() }
        val nodeVersion = nodeRes.stdout.lineSequence().firstOrNull()?.takeIf { nodeRes.exitCode == 0 && it.isNotBlank() }
        val userLine = userRes.stdout.lineSequence().firstOrNull()
        val user = userLine?.takeIf { it.isNotBlank() && !it.lowercase().contains("not logged") }
        val proj = if (easProjectFile.exists()) easProjectFile.readText().trim().takeIf { it.isNotBlank() } else null
        return JSONObject()
            .put("ok", true)
            .put("eas", easVersion ?: JSONObject.NULL)
            .put("node", nodeVersion ?: JSONObject.NULL)
            .put("user", user ?: JSONObject.NULL)
            .put("project", proj ?: JSONObject.NULL)
            .put("project_name", proj?.let { File(it).name } ?: JSONObject.NULL)
    }

    private fun buildSetProjectJson(path: String?): JSONObject {
        if (path.isNullOrBlank()) return JSONObject().put("ok", false).put("error", getString(R.string.expo_error_missing_path))
        val real = File(path).canonicalFile
        if (!real.isDirectory) return JSONObject().put("ok", false).put("error", getString(R.string.expo_error_path_not_found, path))
        easProjectFile.writeText(path)
        return JSONObject().put("ok", true).put("message", getString(R.string.expo_msg_project_set, path))
    }

    private fun buildGetProjectJson(): JSONObject {
        if (!easProjectFile.exists()) return JSONObject().put("ok", true).put("project", JSONObject.NULL)
        val proj = easProjectFile.readText().trim()
        val valid = proj.isNotBlank() && File(proj).isDirectory
        return JSONObject().put("ok", true).put("project", if (valid) proj else JSONObject.NULL)
    }

    // list-projects reusaba proj_list()/_handle_project_actions() en kairos_manager.py (el
    // mismo registry de symlinks "origin" que usan Claude/OpenCode) — Expo no necesita esa
    // capa: acá alcanza con listar carpetas de ~/proyectos directo con File.listFiles().
    private fun listProjectDirs(): List<String> {
        val dir = File(TermuxConstants.TERMUX_HOME_DIR_PATH, "proyectos")
        val files = dir.listFiles() ?: return emptyList()
        return files.filter { it.isDirectory }.map { it.name }.sorted()
    }

    private fun buildBuildJson(profile: String?): JSONObject {
        val prof = profile ?: "preview"
        if (!easProjectFile.exists()) return JSONObject().put("ok", false).put("error", getString(R.string.expo_error_no_active_project))
        val proj = easProjectFile.readText().trim()
        val real = File(proj).canonicalFile
        if (!real.isDirectory) return JSONObject().put("ok", false).put("error", getString(R.string.expo_error_not_found))
        // Bug real documentado en la ronda de auditoría 2026-08-19 (ver
        // docs/arquitectura/AUDITORIA_MODULOS_IA_DEV_VS_OFICIAL_2026-08-19.md): esta función
        // corría "eas build --non-interactive" directo, sin el fallback automático que sí tiene
        // el script de referencia `eas_build.sh` (modulos/expo.sh líneas 258-261: si falta
        // eas.json, corre "eas build:configure" antes) — un primer build en un proyecto sin
        // eas.json fallaba bajo --non-interactive en vez de generarlo. "eas build:configure"
        // no tiene su propio "--non-interactive" (confirmado contra docs.expo.dev/eas/cli/,
        // solo acepta "-p/--platform"), así que se corre con timeout acotado (60s): en la
        // mayoría de los proyectos ya logueados (chequeado por buildInfoJson()/openLoginSession()
        // antes de llegar acá) solo escribe eas.json sin más preguntas; si igual necesitara
        // input interactivo se corta por timeout y el build real de abajo falla con un error
        // claro en vez de quedar la app esperando una sesión que nunca va a recibir.
        val easJson = File(real, "eas.json")
        if (!easJson.isFile) {
            runCommand(listOf("eas", "build:configure", "-p", "android"), 60, workDir = real)
        }
        val r = runCommand(
            listOf("eas", "build", "--platform", "android", "--profile", prof, "--non-interactive"),
            600,
            workDir = real,
            extraEnv = mapOf("EAS_SKIP_AUTO_FINGERPRINT" to "1")
        )
        return if (r.exitCode == 0) {
            JSONObject().put("ok", true).put("message", getString(R.string.expo_msg_build_started, prof)).put("output", r.stdout.takeLast(1000))
        } else {
            JSONObject().put("ok", false).put("error", getString(R.string.expo_error_failed)).put("output", r.stderr.ifBlank { r.stdout }.takeLast(1000))
        }
    }

    private fun buildUpdateJson(branch: String?, message: String?): JSONObject {
        if (branch.isNullOrBlank()) return JSONObject().put("ok", false).put("error", getString(R.string.expo_error_missing_branch))
        if (!easProjectFile.exists()) return JSONObject().put("ok", false).put("error", getString(R.string.expo_error_no_active_project))
        val proj = easProjectFile.readText().trim()
        val real = File(proj).canonicalFile
        if (!real.isDirectory) return JSONObject().put("ok", false).put("error", getString(R.string.expo_error_not_found))
        val msg = message?.takeIf { it.isNotBlank() } ?: getString(R.string.expo_default_commit_message)
        val r = runCommand(
            listOf("eas", "update", "--branch", branch, "--message", msg, "--non-interactive"),
            300,
            workDir = real
        )
        return if (r.exitCode == 0) {
            JSONObject().put("ok", true).put("message", getString(R.string.expo_msg_ota_published, branch)).put("output", r.stdout.takeLast(1000))
        } else {
            JSONObject().put("ok", false).put("error", getString(R.string.expo_error_failed)).put("output", r.stderr.ifBlank { r.stdout }.takeLast(1000))
        }
    }

    private fun buildBuildsListJson(): JSONObject {
        val r = runCommand(listOf("eas", "build:list", "--platform", "android", "--limit", "5", "--json"), 30)
        if (r.exitCode == 0 && r.stdout.isNotBlank()) {
            try {
                val arr = JSONArray(r.stdout)
                return JSONObject().put("ok", true).put("builds", arr)
            } catch (_: Exception) {
                // fallback abajo al texto plano
            }
        }
        val r2 = runCommand(listOf("eas", "build:list", "--platform", "android", "--limit", "5"), 30)
        return JSONObject().put("ok", true).put("builds_raw", r2.stdout.ifBlank { getString(R.string.expo_msg_no_builds) })
    }

    // Render nativo de "builds-list" — buildBuildsListJson() ya traía el JSON real de
    // "eas build:list --json" pero runExpoAction() lo descartaba en un Snackbar "OK" sin
    // mostrar ni un build (mismo patrón de bug ya arreglado en PythonFragment.pip-list).
    private fun showBuildsListDialog(json: JSONObject) {
        val arr = json.optJSONArray("builds")
        if (arr != null && arr.length() > 0) {
            val labels = ArrayList<String>()
            for (i in 0 until arr.length()) {
                val b = arr.getJSONObject(i)
                val status = b.optString("status", "?")
                val platform = b.optString("platform", "?")
                val id = b.optString("id", "").take(8)
                val createdAt = b.optString("createdAt", "")
                labels.add("[$status] $platform · $id\n$createdAt")
            }
            AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.expo_title_builds_recent_count, arr.length()))
                .setItems(labels.toTypedArray()) { _, which ->
                    val b = arr.getJSONObject(which)
                    Snackbar.make(
                        requireView(),
                        "${b.optString("status", "?")} · ${b.optString("platform", "?")} · ${b.optString("id", "")}",
                        Snackbar.LENGTH_LONG
                    ).show()
                }
                .setNegativeButton(getString(R.string.expo_btn_close), null)
                .show()
            return
        }
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.expo_title_builds_recent))
            .setMessage(json.optString("builds_raw", getString(R.string.expo_msg_no_builds)))
            .setNegativeButton(getString(R.string.expo_btn_close), null)
            .show()
    }

    // docs.expo.dev/eas/json/ — eas.json["build"] es un mapa {nombre_perfil: config}. Antes
    // la app solo ofrecía 2 perfiles hardcodeados (preview/production) sin leer los reales
    // del proyecto — cualquier perfil custom quedaba inalcanzable desde la UI. Tocar un
    // perfil acá dispara el mismo buildBuildJson() que ya usan los botones fijos.
    private fun buildBuildProfilesJson(): JSONObject {
        if (!easProjectFile.exists()) return JSONObject().put("ok", false).put("error", getString(R.string.expo_error_no_active_project))
        val proj = easProjectFile.readText().trim()
        val real = File(proj).canonicalFile
        val easJson = File(real, "eas.json")
        if (!easJson.isFile) {
            return JSONObject().put("ok", false).put("error", getString(R.string.expo_error_no_eas_json, proj))
        }
        return try {
            val root = JSONObject(easJson.readText())
            JSONObject().put("ok", true).put("profiles", root.optJSONObject("build") ?: JSONObject())
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", getString(R.string.expo_error_invalid_eas_json, e.message))
        }
    }

    private fun showBuildProfilesDialog(json: JSONObject) {
        val profiles = json.optJSONObject("profiles") ?: JSONObject()
        val names = profiles.keys().asSequence().toList()
        if (names.isEmpty()) {
            Snackbar.make(requireView(), getString(R.string.expo_msg_no_build_profiles), Snackbar.LENGTH_SHORT).show()
            return
        }
        val labels = names.map { name ->
            val p = profiles.optJSONObject(name)
            val dist = p?.optString("distribution", "store") ?: "store"
            val buildType = p?.optJSONObject("android")?.optString("buildType", "-") ?: "-"
            "$name  ·  distribution=$dist  ·  android.buildType=$buildType"
        }
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.expo_title_build_profiles))
            .setItems(labels.toTypedArray()) { _, which ->
                toast(getString(R.string.expo_toast_build_with_profile, names[which]))
                runExpoAction("build", names[which])
            }
            .setNegativeButton(getString(R.string.expo_btn_close), null)
            .show()
    }

    // docs.expo.dev/develop/expo-doctor/ — "npx expo-doctor" es un chequeo puntual (exit
    // code + reporte de texto), no un proceso interactivo ni de logs largos en vivo — encaja
    // como diálogo nativo igual que el resto de las acciones de "info".
    private fun buildDoctorJson(): JSONObject {
        if (!easProjectFile.exists()) return JSONObject().put("ok", false).put("error", getString(R.string.expo_error_no_active_project))
        val proj = easProjectFile.readText().trim()
        val real = File(proj).canonicalFile
        if (!real.isDirectory) return JSONObject().put("ok", false).put("error", getString(R.string.expo_error_path_not_found, proj))
        val r = runCommand(listOf("npx", "expo-doctor"), 90, workDir = real)
        return JSONObject().put("ok", true).put("exit_code", r.exitCode)
            .put("output", r.stdout.ifBlank { r.stderr }.takeLast(3000))
    }

    private fun showDoctorDialog(json: JSONObject) {
        val exitCode = json.optInt("exit_code", -1)
        AlertDialog.Builder(requireContext())
            .setTitle(if (exitCode == 0) getString(R.string.expo_doctor_no_issues) else getString(R.string.expo_doctor_check))
            .setMessage(json.optString("output", getString(R.string.expo_msg_no_output)))
            .setNegativeButton(getString(R.string.expo_btn_close), null)
            .show()
    }

    private fun buildGitPushJson(msg: String?): JSONObject {
        if (!easProjectFile.exists()) return JSONObject().put("ok", false).put("error", getString(R.string.expo_error_no_project))
        val proj = easProjectFile.readText().trim()
        val real = File(proj).canonicalFile
        if (!File(real, ".git").isDirectory) return JSONObject().put("ok", false).put("error", getString(R.string.expo_error_not_git_repo))
        val commitMsg = msg?.takeIf { it.isNotBlank() } ?: getString(R.string.expo_default_commit_message)
        runCommand(listOf("git", "add", "."), 30, workDir = real)
        val status = runCommand(listOf("git", "status", "--short"), 10, workDir = real)
        runCommand(listOf("git", "commit", "-m", commitMsg), 30, workDir = real)
        val push = runCommand(listOf("git", "push"), 60, workDir = real)
        return if (push.exitCode == 0) {
            JSONObject().put("ok", true).put("message", getString(R.string.expo_msg_push_ok)).put("commit_msg", commitMsg).put("status", status.stdout)
        } else {
            JSONObject().put("ok", false).put("error", getString(R.string.expo_error_push_failed)).put("detail", push.stderr.ifBlank { push.stdout })
        }
    }

    private fun openLoginSession() {
        // El intent ACTION_VIEW con extra "command" no funcionaba — TermuxActivity nunca
        // lee ese extra (mismo bug que en PythonFragment.openRepl()).
        launchTerminalCommand("eas login")
    }

    private fun selectProject() {
        Thread {
            val names = try {
                listProjectDirs()
            } catch (e: Exception) {
                emptyList()
            }
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (names.isEmpty()) {
                    Snackbar.make(requireView(), getString(R.string.expo_msg_no_projects), Snackbar.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.expo_title_select_project))
                    .setItems(names.toTypedArray()) { _, which ->
                        val fullPath = File(TermuxConstants.TERMUX_HOME_DIR_PATH, "proyectos/${names[which]}").absolutePath
                        runExpoAction("set-project", fullPath)
                    }
                    .setNegativeButton(getString(R.string.expo_btn_cancel), null)
                    .show()
            }
        }.start()
    }

    // eas update necesita rama + mensaje (2 campos) — promptAndRun() de abajo solo maneja un
    // único EditText genérico, así que este diálogo se arma aparte con un LinearLayout
    // vertical de 2 campos, mismo patrón simple que el resto del Fragment.
    private fun showEasUpdateDialog() {
        val ctx = requireContext()
        val branchEdit = EditText(ctx).apply { hint = getString(R.string.expo_hint_branch); setText("production") }
        val messageEdit = EditText(ctx).apply { hint = getString(R.string.expo_hint_update_message) }
        val layout = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(0))
            addView(branchEdit)
            addView(messageEdit)
        }
        AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.expo_title_publish_ota))
            .setView(layout)
            .setPositiveButton(getString(R.string.expo_btn_publish)) { _, _ ->
                val branch = branchEdit.text.toString().trim()
                val message = messageEdit.text.toString().trim()
                if (branch.isNotBlank()) runExpoAction("update", branch, message)
            }
            .setNegativeButton(getString(R.string.expo_btn_cancel), null)
            .show()
    }

    private fun promptAndRun(action: String) {
        val ctx = requireContext()
        val edit = EditText(ctx)
        edit.hint = getString(R.string.expo_hint_value)
        AlertDialog.Builder(ctx)
            .setTitle(action)
            .setView(edit)
            .setPositiveButton(getString(R.string.expo_msg_ok)) { _, _ ->
                val value = edit.text.toString()
                if (value.isNotBlank()) runExpoAction(action, value)
            }
            .setNegativeButton(getString(R.string.expo_btn_cancel), null)
            .show()
    }

    private data class CmdResult(val exitCode: Int, val stdout: String, val stderr: String)

    // Corre un binario de Termux directo (eas/node/git), sin pasar por kairos_manager.py.
    // Lee stdout y stderr en threads separados (evita el deadlock clásico de leer un pipe
    // en secuencia mientras el otro se llena — relevante acá porque "eas build" puede
    // producir bastante output en ambos).
    // Resuelve el primer elemento (eas/node/git) a su ruta absoluta bajo $PREFIX/bin en vez
    // de dejarlo como nombre relativo — bug real confirmado esta sesión (ver
    // docs/humano/humano63.md); se resuelve una sola vez acá en vez de en cada uno de los 9
    // call sites de este archivo.
    private fun runCommand(
        cmd: List<String>,
        timeoutSec: Long,
        workDir: File? = null,
        extraEnv: Map<String, String> = emptyMap()
    ): CmdResult {
        return try {
            // Bug real confirmado 2026-08-24 (ver docs/humano216.md, pruebas funcionales reales
            // por ADB): "eas" NO es un binario nativo en $TERMUX_PREFIX_PATH/bin/ (ese hardcode
            // nunca resolvía nada ahí) — es un shim npm real instalado en
            // $HOME/.npm-global/bin/eas (mutación global de prefix npm, mismo root cause que el
            // fix de PATH en applyTermuxEnv()) con shebang "#!/usr/bin/env node". Invocado
            // directo por ProcessBuilder revienta con "/usr/bin/env: bad interpreter" — el
            // resolver de shebang del kernel necesita termux-exec (LD_PRELOAD) activo ANTES del
            // spawn del proceso, confirmado empíricamente en dispositivo (exportarlo DESPUÉS de
            // que un bash ya arrancó no sirve, el linker ya resolvió símbolos en el arranque).
            // Se invoca vía bash -c (deja que el PATH ya extendido de applyTermuxEnv() resuelva
            // "eas" solo) + LD_PRELOAD explícito, scopeado a este comando (mismo patrón que
            // EntornoNative.kt para clientes X11 — no se agrega a applyTermuxEnv() global para
            // no arriesgar a los binarios glibc-patcheados de otros módulos, que no lo usan).
            val shellCmd = cmd.joinToString(" ") { shellQuote(it) }
            val pb = ProcessBuilder(TERMUX_BASH_PATH, "-c", shellCmd)
            pb.applyTermuxEnv()
            pb.environment()["LD_PRELOAD"] = "$TERMUX_PREFIX_PATH/lib/libtermux-exec-ld-preload.so"
            extraEnv.forEach { (k, v) -> pb.environment()[k] = v }
            if (workDir != null) pb.directory(workDir)
            val process = pb.start()
            val stdout = StringBuilder()
            val stderr = StringBuilder()
            // Bug real confirmado por ADB (2026-08-24, ver docs/humano222.md): sin try/catch
            // acá, destroyForcibly() de abajo cierra los streams mientras este Thread está
            // bloqueado en readText() — la excepción sin capturar mata TODO el proceso de la
            // app (mismo patrón real confirmado en ModuleController.startModule()).
            val tOut = Thread { try { stdout.append(process.inputStream.bufferedReader().readText()) } catch (_: Exception) {} }
            val tErr = Thread { try { stderr.append(process.errorStream.bufferedReader().readText()) } catch (_: Exception) {} }
            tOut.start(); tErr.start()
            val finished = process.waitFor(timeoutSec, TimeUnit.SECONDS)
            if (!finished) process.destroyForcibly()
            tOut.join(2000); tErr.join(2000)
            CmdResult(if (finished) process.exitValue() else -1, stdout.toString().trim(), stderr.toString().trim())
        } catch (e: Exception) {
            CmdResult(-1, "", e.message ?: "error")
        }
    }
}

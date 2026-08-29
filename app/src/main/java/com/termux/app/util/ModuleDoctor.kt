package com.termux.app.util

import android.content.Context
import com.termux.app.data.ModuleCatalog
import com.termux.app.data.ModuleInstalled
import com.termux.app.data.ModuleRegistry
import com.termux.app.model.ModuleInfo
import com.termux.shared.termux.TermuxConstants
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Diagnóstico de salud por módulo — 2026-08-15 (verificación en vivo + ModuleDoctor).
 * Complementa a modulos/verificar.sh (el lado bash del mismo contrato): este objeto corre
 * del lado Kotlin para que la app pueda reportar "qué está roto" sin abrir la terminal.
 *
 * Por cada módulo del catálogo:
 *  - Verifica instalación con ModuleInstalled.isInstalledRobust() (registry O binario real
 *    O estrategia en vivo de LIVE_FALLBACK, todos cacheados).
 *  - Si el registry dice installed=true pero la verificación en vivo falla → el módulo está
 *    ROTO (el caso que verificar.sh busca: "instalado en el papel pero binario ausente").
 *  - Verifica el script de instalación (~/scripts/install/<id>.sh): que exista y que
 *    `bash -n` no encuentre errores de sintaxis.
 *
 * Escribe el resultado completo en ~/kairos_logs/module_doctor.json (mismo directorio que
 * usage_state.json de UsageStateManager e install_<modulo>.log de ModuleController) y
 * emite notifySessionEvent(ERROR, "module_doctor", ...) si hay módulos rotos (respetando el
 * cooldown del bridge). Corre en el hilo que lo llame (los callers usan background thread).
 */
object ModuleDoctor {

    private const val TAG = "ModuleDoctor"

    private val home get() = TermuxConstants.TERMUX_HOME_DIR_PATH
    private val logsDir get() = File(home, "kairos_logs")
    private val doctorFile get() = File(logsDir, "module_doctor.json")

    @JvmStatic
    fun runDiagnostics(context: Context, modules: List<ModuleInfo>): String {
        val registry = ModuleRegistry(context).load().getModules()
        val results = JSONArray()
        val broken = mutableListOf<String>()
        var ok = 0
        var notInstalled = 0

        for (m in modules) {
            val id = m.id
            val registryInstalled = registry["$id.installed"] == "true"
            val liveInstalled = ModuleInstalled.isInstalledRobust(context, id)
            val script = checkInstallScript(id)

            val status = when {
                (registryInstalled && !liveInstalled) ||
                    (registryInstalled && !script.exists) ||
                    (script.exists && !script.syntaxOk) -> {
                    broken.add(id)
                    "roto"
                }
                registryInstalled || liveInstalled -> {
                    ok++
                    "ok"
                }
                else -> {
                    notInstalled++
                    "no_instalado"
                }
            }

            val o = JSONObject()
                .put("id", id)
                .put("name", m.name)
                .put("registry_installed", registryInstalled)
                .put("live_installed", liveInstalled)
                .put("script_exists", script.exists)
                .put("script_syntax_ok", script.syntaxOk)
                .put("status", status)
            results.put(o)
        }

        val root = JSONObject()
            .put("generated_at", java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US).format(java.util.Date()))
            .put("total", modules.size)
            .put("ok", ok)
            .put("no_instalado", notInstalled)
            .put("roto", broken.size)
            .put("broken_ids", JSONArray(broken))
            .put("modules", results)

        try {
            logsDir.mkdirs()
            doctorFile.writeText(root.toString(2))
        } catch (e: Exception) {
            android.util.Log.e(TAG, "no se pudo escribir module_doctor.json", e)
        }

        if (broken.isNotEmpty()) {
            ModuleEventBridge.notifySessionEvent(
                context.applicationContext,
                "module_doctor",
                ModuleEventBridge.SessionEvent.ERROR,
                "${broken.size} módulos rotos: ${broken.joinToString(", ")}"
            )
        }

        return buildSummary(broken, ok, notInstalled, modules.size)
    }

    /** Diagnóstico de TODO el catálogo — acceso directo desde UI (BaseModuleFragment). */
    @JvmStatic
    fun runDiagnosticsForAll(context: Context): String =
        runDiagnostics(context, ModuleCatalog.load(context))

    private class ScriptCheck(val exists: Boolean, val syntaxOk: Boolean)

    // Misma convención de nombres que ModuleController.installScriptFile() (la única
    // excepción es "remote" cuyo script real es ssh.sh).
    private fun checkInstallScript(moduleId: String): ScriptCheck {
        val fileName = if (moduleId == "remote") "ssh.sh" else "$moduleId.sh"
        val file = File(home, "scripts/install/$fileName")
        if (!file.exists()) return ScriptCheck(false, false)
        return ScriptCheck(true, runBash(TERMUX_BASH_PATH, "-n", file.absolutePath))
    }

    private fun runBash(vararg args: String): Boolean {
        return try {
            val pb = ProcessBuilder(*args)
            pb.applyTermuxEnv()
            val process = pb.start()
            process.inputStream.bufferedReader().readText()
            process.errorStream.bufferedReader().readText()
            val finished = process.waitFor(8, TimeUnit.SECONDS)
            if (!finished) process.destroyForcibly()
            finished && process.exitValue() == 0
        } catch (_: Exception) {
            false
        }
    }

    private fun buildSummary(broken: List<String>, ok: Int, notInstalled: Int, total: Int): String = buildString {
        appendLine("\uD83E\uDE7A Diagnóstico de módulos")
        appendLine("• $total verificados · $ok OK · $notInstalled no instalados · ${broken.size} rotos")
        if (broken.isNotEmpty()) appendLine("• Rotos: ${broken.joinToString(", ")}")
        appendLine("• Reporte: $doctorFile")
    }
}
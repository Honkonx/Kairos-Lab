package com.termux.app.util

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.io.File

/**
 * Empaqueta evidencia real de dispositivo para reportar bugs — pedido explícito del usuario:
 * este proyecto acumula reportes que solo se pueden confirmar con logs reales (los mismos
 * `~/kairos_logs/install_<modulo>.log` que ya lee TermuxActivity.showModuleLogDialog(), más
 * `wizard_debug.log` de WizardDebugLog.kt) y datos básicos del dispositivo (SDK, ABI, RAM,
 * versión de la app) — hoy el usuario tiene que compartir esos archivos manualmente uno por
 * uno. Complementa (NO reemplaza) a BackupManager.create() (que respalda TODO el estado
 * funcional de los módulos) y a ConfigExportManager (config/ajustes) — este export es solo
 * diagnóstico de lectura, sin nada sensible/redactable de por medio (logs de instalación no
 * llevan tokens, y los datos de dispositivo son públicos).
 *
 * Mismo patrón que BackupManager: invoca el `tar` real de Termux vía
 * ManagerNativeUtils.runExec() en vez de reimplementar TAR+gzip a mano en Kotlin. A diferencia
 * de BackupManager (que empaqueta directo desde HOME con -C), acá se arma primero una carpeta
 * de staging en cacheDir — necesaria para poder sumar `device-info.txt` (generado en runtime,
 * no vive en ~/kairos_logs/) al mismo tar sin tocar los logs reales ni depender de que GNU tar
 * soporte múltiples flags -C intercalados (BusyBox tar no lo soporta parejo).
 */
object DiagnosticExportManager {

    private val home get() = ManagerNativeUtils.home
    private val logsDir get() = File(home, "kairos_logs")
    private val diagnosticsDir = File("/storage/emulated/0/Download/KairosDiagnostics")

    // Mismo criterio de nombres que ModuleController.installLogFile()/WizardDebugLog —
    // instal_<modulo>.log (todas las variantes, incluye install_<id>_<variante>.log) +
    // wizard_debug.log. Cualquier otro archivo que termine apareciendo en kairos_logs/ en el
    // futuro también matchea "install_*.log" o se ignora explícitamente por nombre.
    private fun isDiagnosticLog(name: String): Boolean =
        (name.startsWith("install_") && name.endsWith(".log")) || name == "wizard_debug.log"

    fun exportDiagnostics(context: Context): JSONObject {
        val staging = File(context.cacheDir, "kairos_diag_staging")
        return try {
            if (staging.exists()) staging.deleteRecursively()
            staging.mkdirs()

            val logFiles = logsDir.listFiles { f -> f.isFile && isDiagnosticLog(f.name) }?.toList() ?: emptyList()
            logFiles.forEach { it.copyTo(File(staging, it.name), overwrite = true) }

            // 2026-08-14 (humano123 C2): el snapshot de estado (usage_state.json) se genera
            // FRESCO en cada export (no se acumula en disco) — así el diagnóstico siempre lleva
            // el estado real de "qué instalado, qué versión, qué corriendo" al momento exacto
            // en que el usuario lo compartió, no uno viejo. La función de filtro de arriba
            // NO lo incluye (no matchea install_*.log/wizard_debug.log), por eso se agrega
            // explícitamente acá después de generarlo.
            UsageStateManager.generate(context)
            val usageState = File(logsDir, "usage_state.json")
            if (usageState.exists()) usageState.copyTo(File(staging, "usage_state.json"), overwrite = true)

            File(staging, "device-info.txt").writeText(buildDeviceInfoText(context))

            if (!diagnosticsDir.exists()) diagnosticsDir.mkdirs()
            val timestamp = System.currentTimeMillis().toString()
            val dest = File(diagnosticsDir, "kairos-diag-$timestamp.tar.gz")
            val (rc, out, err) = ManagerNativeUtils.runExec(
                listOf("tar", "-czf", dest.absolutePath, "-C", staging.absolutePath, "."),
                120
            )
            if (rc != 0) {
                dest.delete()
                return JSONObject().put("ok", false).put("error", err.ifEmpty { out }.takeLast(500))
            }

            JSONObject().apply {
                put("ok", true)
                put("path", dest.absolutePath)
                put("size", dest.length())
                put("size_human", ManagerNativeUtils.humanSize(dest.length()))
                put("log_count", logFiles.size)
            }
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message ?: "Error desconocido al exportar diagnóstico")
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun buildDeviceInfoText(context: Context): String {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)

        val appVersion = try {
            com.termux.BuildConfig.VERSION_NAME
        } catch (e: Exception) {
            try {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "desconocida"
            } catch (e2: Exception) {
                "desconocida"
            }
        }

        return buildString {
            appendLine("Kairos — diagnóstico de dispositivo")
            appendLine("Generado: ${java.util.Date()}")
            appendLine()
            appendLine("app_version=$appVersion")
            appendLine("android_sdk_int=${Build.VERSION.SDK_INT}")
            appendLine("android_release=${Build.VERSION.RELEASE}")
            appendLine("supported_abis=${Build.SUPPORTED_ABIS.joinToString(",")}")
            appendLine("manufacturer=${Build.MANUFACTURER}")
            appendLine("model=${Build.MODEL}")
            appendLine("ram_total=${ManagerNativeUtils.humanSize(memoryInfo.totalMem)}")
            appendLine("ram_available=${ManagerNativeUtils.humanSize(memoryInfo.availMem)}")
            appendLine("ram_low_memory=${memoryInfo.lowMemory}")
        }
    }
}

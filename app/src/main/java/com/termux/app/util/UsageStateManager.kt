package com.termux.app.util

import android.content.Context
import android.os.Build
import com.termux.app.ModuleController
import com.termux.app.data.ModuleRegistry
import com.termux.shared.termux.TermuxConstants
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Snapshot JSON del ESTADO REAL del sistema de módulos en el dispositivo — pedido en la ronda
 * 2026-08-14 (humano123, C2). Escribe `~/kairos_logs/usage_state.json` (el mismo directorio que
 * los install_<modulo>.log) con:
 *
 * - **metadata** — timestamp, versión de la app, Android SDK/ABI (contexto de dispositivo).
 * - **módulos** — por cada módulo del catálogo bundled/local: estado en registry
 *   (instalado/versión/canal/fecha), si está CORRIENDO de verdad (ModuleController.isRunning()
 *   sobre tmux/pgrep reales, no una conjetura de la UI) y si está oculto.
 * - **registry** — las claves del registry real (~/.android_server_registry) que no matchean
 *   el patrón <modulo>.algo (estado compartido/global del sistema).
 *
 * NO es un respaldo (eso es BackupManager) ni una exportación de config (ConfigExportManager):
 * es una foto de lectura de "qué hay instalado, qué versión, qué está corriendo" — el primer
 * archivo que mira el usuario (o quien le ayuda) cuando algo no anda, sin tener que abrir la
 * terminal y leer el registry a mano.
 *
 * Se regenera sobre el archivo existente (nunca se acumula) y corre en el hilo que lo llame
 * (los callers usan background thread). Devuelve JSONObject con ok/error para que la UI pueda
 * informar el resultado.
 */
object UsageStateManager {

    private const val TAG = "UsageStateManager"

    private val home get() = TermuxConstants.TERMUX_HOME_DIR_PATH
    private val logsDir get() = File(home, "kairos_logs")
    private val usageStateFile get() = File(logsDir, "usage_state.json")

    @JvmStatic
    fun generate(context: Context): JSONObject {
        return try {
            val reg = ModuleRegistry(context).load().getModules()
            val catalog = com.termux.app.data.ModuleCatalog.load(context)

            val root = JSONObject()
                .put("generated_at", java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US).format(java.util.Date()))
                .put("app_version", appVersion(context))
                .put("android_sdk", Build.VERSION.SDK_INT)
                .put("android_release", Build.VERSION.RELEASE)
                .put("abi", Build.SUPPORTED_ABIS.firstOrNull() ?: "desconocido")

            val modulesArr = JSONArray()
            for (m in catalog) {
                val mid = m.id
                val installed = reg["$mid.installed"] == "true"
                val running = try {
                    installed && ModuleController.isRunning(mid)
                } catch (e: Exception) {
                    false
                }
                val o = JSONObject()
                    .put("id", mid)
                    .put("name", m.name)
                    .put("installed", installed)
                    .put("running", running)
                    .put("hidden", reg["$mid.hidden"] == "true")
                reg["$mid.version"]?.takeIf { it.isNotEmpty() }?.let { o.put("version", it) }
                reg["$mid.channel"]?.takeIf { it.isNotEmpty() }?.let { o.put("channel", it) }
                reg["$mid.method"]?.takeIf { it.isNotEmpty() }?.let { o.put("method", it) }
                reg["$mid.install_date"]?.takeIf { it.isNotEmpty() }?.let { o.put("install_date", it) }
                reg["$mid.location"]?.takeIf { it.isNotEmpty() }?.let { o.put("location", it) }
                modulesArr.put(o)
            }
            root.put("modules", modulesArr)

            // Estado compartido/global del registry (lo que no es <modulo>.<clave>).
            val globalArr = JSONArray()
            for ((k, v) in reg) {
                if (!k.contains('.')) globalArr.put(JSONObject().put(k, v))
            }
            root.put("registry_global", globalArr)

            logsDir.mkdirs()
            usageStateFile.writeText(root.toString(2))

            JSONObject().put("ok", true).put("path", usageStateFile.absolutePath)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "generate() falló", e)
            JSONObject().put("ok", false).put("error", e.message ?: "Error desconocido al generar usage_state.json")
        }
    }

    private fun appVersion(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "desconocida"
    } catch (e: Exception) {
        "desconocida"
    }
}

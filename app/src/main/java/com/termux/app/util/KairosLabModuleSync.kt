package com.termux.app.util

import android.content.Context
import com.termux.shared.termux.TermuxConstants
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * "Verificar actualizaciones de módulos" contra el repo público Kairos-Lab
 * (https://github.com/Honkonx/Kairos-Lab), que desde 2026-08-29 hospeda una copia real de
 * `modulos/` (ver `.claude/rules/kairos-lab-batching.md` — se actualiza por lotes, no en cada
 * commit de kairos-dev, así que es una fuente "periódicamente refrescada", no bleeding-edge).
 *
 * Bug real que este archivo resuelve (confirmado esta sesión, ver docs/humanoN.md de la ronda):
 * los scripts de `modulos/<id>.sh` se copian UNA sola vez de los assets del APK al HOME real de
 * Termux (`~/scripts/install/<id>.sh`, ver [KairosBootstrap.doExtract]) — la re-extracción solo
 * se dispara si `versionCode` cambió (`KairosBootstrap.isAlreadyExtracted`), así que un rebuild
 * local sin bump de versión (el caso normal de un build debug de desarrollo) NUNCA vuelve a
 * copiar los scripts nuevos al dispositivo, aunque el APK se reinstale con `adb install -r`. Este
 * mecanismo da una vía manual, explícita, para sincronizar un módulo puntual (o todos) contra la
 * versión publicada en Kairos-Lab sin depender de un bump de versión ni de reinstalar desde cero.
 *
 * Mecanismo: la GitHub Contents API (https://docs.github.com/en/rest/repos/contents) devuelve,
 * para cada archivo de un directorio, su `sha` real de git blob — un identificador de contenido
 * liviano (no hace falta descargar el archivo completo para saber si cambió). Se guarda el sha
 * "último sincronizado" por módulo en SharedPreferences (mismo patrón que [KairosThemePrefs]) y
 * se compara contra el sha remoto en cada chequeo.
 *
 * Primera vez que se ve un módulo (sin sha guardado todavía): se toma el sha remoto actual como
 * baseline SIN marcarlo como "actualización disponible" — el script ya en el dispositivo es,
 * por definición, el que se instaló con el APK actual; no hay forma de saber si coincide con el
 * contenido exacto de Kairos-Lab sin descargarlo, y forzar una descarga silenciosa en el primer
 * chequeo de cada módulo sería sorprendente. El usuario puede forzar una comparación real
 * bajando el script de todos modos, si quiere confirmar.
 */
object KairosLabModuleSync {

    private const val REPO = "Honkonx/Kairos-Lab"
    private const val BRANCH = "main"
    private const val CONTENTS_API_URL = "https://api.github.com/repos/$REPO/contents/modulos?ref=$BRANCH"
    private const val RAW_BASE_URL = "https://raw.githubusercontent.com/$REPO/$BRANCH/modulos"

    private const val PREFS_NAME = "kairos_lab_module_sync_prefs"
    private const val KEY_SHA_PREFIX = "sha_"

    private val HOME get() = TermuxConstants.TERMUX_HOME_DIR_PATH
    private fun installDir() = File(HOME, "scripts/install").apply { mkdirs() }

    data class RemoteScript(val moduleId: String, val fileName: String, val sha: String)

    data class CheckResult(
        val ok: Boolean,
        val error: String? = null,
        /** Módulos con un sha remoto distinto al último sincronizado — actualización real disponible. */
        val updatable: List<RemoteScript> = emptyList(),
        /** Módulos ya al día (sha remoto == último sincronizado). */
        val upToDate: List<RemoteScript> = emptyList(),
        /** Primera vez que se ve este módulo — se tomó como baseline, no se marca como pendiente. */
        val newlyTracked: List<RemoteScript> = emptyList(),
    )

    data class ApplyResult(val moduleId: String, val ok: Boolean, val error: String? = null)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun lastKnownSha(context: Context, moduleId: String): String? =
        prefs(context).getString(KEY_SHA_PREFIX + moduleId, null)

    private fun storeSha(context: Context, moduleId: String, sha: String) {
        prefs(context).edit().putString(KEY_SHA_PREFIX + moduleId, sha).apply()
    }

    /** Consulta la GitHub Contents API una sola vez (una llamada HTTP, liviana — solo metadata,
     * sin bajar contenido) y clasifica cada script `.sh` de `modulos/` contra el sha guardado
     * localmente. Bloqueante — llamar desde un background thread (mismo criterio que
     * [ModuleVersionChecker]). */
    fun checkForUpdates(context: Context): CheckResult {
        val json = httpGet(CONTENTS_API_URL)
            ?: return CheckResult(false, error = "No se pudo consultar Kairos-Lab (¿sin conexión, o rate-limit de GitHub?)")
        val entries = try {
            JSONArray(json)
        } catch (e: Exception) {
            return CheckResult(false, error = "Respuesta inesperada de GitHub (${e.message})")
        }

        val updatable = mutableListOf<RemoteScript>()
        val upToDate = mutableListOf<RemoteScript>()
        val newlyTracked = mutableListOf<RemoteScript>()

        for (i in 0 until entries.length()) {
            val entry = entries.getJSONObject(i)
            val fileName = entry.optString("name")
            if (!fileName.endsWith(".sh")) continue
            val sha = entry.optString("sha").takeIf { it.isNotBlank() } ?: continue
            val moduleId = fileName.removeSuffix(".sh")
            val remote = RemoteScript(moduleId, fileName, sha)
            val known = lastKnownSha(context, moduleId)
            when {
                known == null -> {
                    storeSha(context, moduleId, sha)
                    newlyTracked.add(remote)
                }
                known != sha -> updatable.add(remote)
                else -> upToDate.add(remote)
            }
        }
        return CheckResult(true, updatable = updatable, upToDate = upToDate, newlyTracked = newlyTracked)
    }

    /** Descarga el script real desde `raw.githubusercontent.com`, lo escribe en la misma ruta
     * que usa el resto de la app para ejecutar módulos (`~/scripts/install/<id>.sh` — ver
     * [ModuleController.installModule], que arma la ruta con este mismo directorio), lo marca
     * ejecutable y guarda el sha nuevo como "último sincronizado". Bloqueante. */
    fun applyUpdate(context: Context, remote: RemoteScript): ApplyResult {
        val raw = httpGet("$RAW_BASE_URL/${remote.fileName}", acceptJson = false)
            ?: return ApplyResult(remote.moduleId, false, "No se pudo descargar ${remote.fileName}")
        return try {
            val dest = File(installDir(), remote.fileName)
            dest.writeText(raw)
            // chmod +x vía shell (mismo patrón que TunnelManager.kt/RemoteManager.kt para
            // marcar binarios/scripts descargados como ejecutables) en vez de
            // File.setExecutable() — consistente con el resto de escrituras de archivos
            // ejecutables descargados de la red en este proyecto.
            val (rc, _, err) = ManagerNativeUtils.runShell("chmod +x '${dest.absolutePath}'", 10)
            if (rc != 0) {
                return ApplyResult(remote.moduleId, false, "Descargado pero chmod +x falló: $err")
            }
            storeSha(context, remote.moduleId, remote.sha)
            ApplyResult(remote.moduleId, true)
        } catch (e: Exception) {
            ApplyResult(remote.moduleId, false, e.message ?: "error desconocido")
        }
    }

    private fun httpGet(url: String, acceptJson: Boolean = true, timeoutMs: Int = 15_000): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            if (acceptJson) conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "kairos-app/module-sync (+https://github.com/Honkonx/Kairos-Lab)")
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }
}

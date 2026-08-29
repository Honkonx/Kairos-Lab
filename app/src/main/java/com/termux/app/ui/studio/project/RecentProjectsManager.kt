package com.termux.app.ui.studio.project

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/**
 * Lista de las últimas carpetas de proyecto abiertas (URI SAF de árbol + nombre visible + fecha
 * de último acceso), para la pantalla/diálogo "Proyectos recientes".
 *
 * Guardado en [SharedPreferences] planas, sin cifrar a propósito — a diferencia de
 * [com.termux.app.ui.studio.ai.AiProviderPrefs] (que guarda API keys y sí usa
 * `EncryptedSharedPreferences`), acá solo se persisten URIs `content://` y nombres de carpeta,
 * que no son secretos; usar cifrado agregaría costo sin beneficio real.
 *
 * El permiso real de acceso a cada URI entre reinicios NO lo maneja esta clase — eso lo otorga
 * `ContentResolver.takePersistableUriPermission()`, llamado por quien abre la carpeta
 * (`MainActivity.openFolder`) antes de registrarla acá. Ese es el mecanismo estándar de Android
 * para persistir permisos SAF más allá del ciclo de vida del proceso (documentado en
 * `ContentResolver#takePersistableUriPermission`, requiere que el URI haya llegado con
 * `FLAG_GRANT_*_URI_PERMISSION` desde `ACTION_OPEN_DOCUMENT_TREE`/`ACTION_OPEN_DOCUMENT`, que es
 * el caso de `openFolderLauncher`/`openFileLauncher` en `MainActivity`). Sin ese paso previo, un
 * URI guardado acá puede fallar con `SecurityException` al reabrirlo tras un reinicio — por eso
 * cada punto de lectura (`RecentProjectsActivity`/diálogo, restauración de sesión) debe manejar
 * esa excepción y, si ocurre, lo razonable es quitar la entrada con [remove].
 */
class RecentProjectsManager(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    data class RecentProject(
        val uri: Uri,
        val name: String,
        val lastAccessedMillis: Long
    )

    /** Agrega (o mueve al principio, si ya existía) una carpeta de proyecto recién abierta. */
    fun recordOpened(uri: Uri, name: String) {
        val updated = readAll()
            .filterNot { it.uri == uri }
            .plus(RecentProject(uri, name, System.currentTimeMillis()))
            .sortedByDescending { it.lastAccessedMillis }
            .take(MAX_RECENT_PROJECTS)
        writeAll(updated)
    }

    fun getRecentProjects(): List<RecentProject> =
        readAll().sortedByDescending { it.lastAccessedMillis }

    fun remove(uri: Uri) {
        writeAll(readAll().filterNot { it.uri == uri })
    }

    private fun readAll(): List<RecentProject> {
        val raw = prefs.getString(KEY_PROJECTS, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index -> parseEntry(array.optJSONObject(index)) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseEntry(entry: JSONObject?): RecentProject? {
        if (entry == null) return null
        val uriString = entry.optString(FIELD_URI, "")
        if (uriString.isEmpty()) return null
        return RecentProject(
            uri = Uri.parse(uriString),
            name = entry.optString(FIELD_NAME, uriString),
            lastAccessedMillis = entry.optLong(FIELD_LAST_ACCESSED, 0L)
        )
    }

    private fun writeAll(projects: List<RecentProject>) {
        val array = JSONArray()
        projects.forEach { project ->
            array.put(
                JSONObject()
                    .put(FIELD_URI, project.uri.toString())
                    .put(FIELD_NAME, project.name)
                    .put(FIELD_LAST_ACCESSED, project.lastAccessedMillis)
            )
        }
        prefs.edit().putString(KEY_PROJECTS, array.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "kairos_ide_recent_projects"
        private const val KEY_PROJECTS = "projects_json"
        private const val FIELD_URI = "uri"
        private const val FIELD_NAME = "name"
        private const val FIELD_LAST_ACCESSED = "last_accessed"
        const val MAX_RECENT_PROJECTS = 10
    }
}

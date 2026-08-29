package com.termux.app.ui.studio.project

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persiste la LISTA de sesiones de proyecto abiertas en Estudio — multi-proyecto (ver
 * `docs/ide/PLAN_REDISENO_ESTUDIO_IDE_2026-08-22.md` §2, implementado 2026-08-26). Antes
 * (`docs/humano/humano202.md` en adelante) solo guardaba una única carpeta+pestañas; ahora
 * guarda cada [StudioSession] completa (URI de árbol, pestañas abiertas, pestaña activa) más
 * cuál era la sesión activa, para restaurar el estado multi-proyecto completo al reabrir la app
 * (ver `StudioFragment.restoreSessionIfAny`/`onStop`).
 *
 * Ruptura de formato deliberada respecto a la versión de sesión única (claves de
 * SharedPreferences nuevas, `sessions_json` en vez de `open_tabs_json`) — el peor caso de una
 * instalación con datos viejos es simplemente no restaurar la última sesión una vez, no hay
 * migración porque no vale la pena la complejidad para un dato de conveniencia, no crítico.
 *
 * Solo guarda identidad (URIs/nombres), nunca contenido de archivo: al restaurar, el contenido
 * se vuelve a leer desde el URI SAF vía `ContentResolver.openInputStream`. Cualquier edición sin
 * guardar al momento de que la app pase a background se pierde — limitación conocida y aceptada
 * (guardar contenido no persistido requeriría un mecanismo de autosave/borrador separado, fuera
 * de alcance acá).
 */
class SessionStateManager(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    data class TabState(val uri: Uri, val name: String)

    fun saveSessions(sessions: List<StudioSession>, activeIndex: Int) {
        val sessionsArray = JSONArray()
        sessions.forEach { session ->
            val tabsArray = JSONArray()
            session.openTabs.forEach { tab ->
                tabsArray.put(
                    JSONObject()
                        .put(FIELD_URI, tab.uri.toString())
                        .put(FIELD_NAME, tab.name)
                )
            }
            sessionsArray.put(
                JSONObject()
                    .put(FIELD_ID, session.id)
                    .put(FIELD_TREE_URI, session.treeUri.toString())
                    .put(FIELD_PROJECT_PATH, session.projectPath ?: "")
                    .put(FIELD_DISPLAY_NAME, session.displayName)
                    .put(FIELD_TABS, tabsArray)
                    .put(FIELD_ACTIVE_TAB_URI, session.activeTabUri?.toString() ?: "")
            )
        }
        prefs.edit()
            .putString(KEY_SESSIONS, sessionsArray.toString())
            .putInt(KEY_ACTIVE_SESSION_INDEX, activeIndex)
            .apply()
    }

    /** [Pair.second] es el índice de la sesión que estaba activa — puede quedar fuera de rango
     * si la lista guardada estaba vacía o corrupta, `StudioFragment` lo clampea antes de usarlo. */
    fun loadSessions(): Pair<List<StudioSession>, Int> {
        val sessions = try {
            val raw = prefs.getString(KEY_SESSIONS, null)
            if (raw.isNullOrEmpty()) {
                emptyList()
            } else {
                val array = JSONArray(raw)
                (0 until array.length()).mapNotNull { index -> parseSession(array.optJSONObject(index)) }
            }
        } catch (_: Exception) {
            emptyList()
        }
        val activeIndex = prefs.getInt(KEY_ACTIVE_SESSION_INDEX, 0)
        return sessions to activeIndex
    }

    fun clearSession() {
        prefs.edit().clear().apply()
    }

    private fun parseSession(entry: JSONObject?): StudioSession? {
        if (entry == null) return null
        val treeUriString = entry.optString(FIELD_TREE_URI, "")
        if (treeUriString.isEmpty()) return null
        val tabsArray = entry.optJSONArray(FIELD_TABS)
        val tabs = mutableListOf<TabState>()
        if (tabsArray != null) {
            for (index in 0 until tabsArray.length()) {
                parseTab(tabsArray.optJSONObject(index))?.let { tabs.add(it) }
            }
        }
        val activeTabUriString = entry.optString(FIELD_ACTIVE_TAB_URI, "")
        return StudioSession(
            id = entry.optString(FIELD_ID, treeUriString),
            treeUri = Uri.parse(treeUriString),
            projectPath = entry.optString(FIELD_PROJECT_PATH, "").ifEmpty { null },
            displayName = entry.optString(FIELD_DISPLAY_NAME, treeUriString),
            openTabs = tabs,
            activeTabUri = if (activeTabUriString.isEmpty()) null else Uri.parse(activeTabUriString)
        )
    }

    private fun parseTab(entry: JSONObject?): TabState? {
        if (entry == null) return null
        val uriString = entry.optString(FIELD_URI, "")
        if (uriString.isEmpty()) return null
        return TabState(Uri.parse(uriString), entry.optString(FIELD_NAME, uriString))
    }

    companion object {
        private const val PREFS_NAME = "kairos_ide_session"
        private const val KEY_SESSIONS = "sessions_json"
        private const val KEY_ACTIVE_SESSION_INDEX = "active_session_index"
        private const val FIELD_ID = "id"
        private const val FIELD_TREE_URI = "tree_uri"
        private const val FIELD_PROJECT_PATH = "project_path"
        private const val FIELD_DISPLAY_NAME = "display_name"
        private const val FIELD_TABS = "tabs"
        private const val FIELD_ACTIVE_TAB_URI = "active_tab_uri"
        private const val FIELD_URI = "uri"
        private const val FIELD_NAME = "name"
    }
}

package com.termux.app.util

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Paridad de módulo real para el gateway (2026-10-16, ver meta 4-5) — "stacks"
 * NO está en la lista de módulos protegidos (confirmado antes de escribir esto). Solo LECTURA: el
 * estado real vive en las MISMAS `SharedPreferences` que ya usa `StacksFragment`
 * (`kairos_stacks_prefs`/`project_paths`/`project_states`/`project_subdirs`, mismos nombres
 * exactos leídos ahí) — se lee tal cual, nunca se duplica en un archivo aparte que podría
 * desincronizarse. Instalar dependencias/correr un proyecto ya es posible con los genéricos
 * `distro.run`/`udocker.run`/`sandbox.run` según el `target` de cada proyecto — no hace falta
 * reimplementar esa lógica acá.
 */
object GatewayStacksOps {
    private const val PREFS = "kairos_stacks_prefs"
    private const val KEY_PATHS = "project_paths"
    private const val KEY_STATES = "project_states"
    private const val KEY_SUBDIRS = "project_subdirs"

    fun projects(context: Context): JSONObject {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val paths = runCatching { JSONArray(prefs.getString(KEY_PATHS, null) ?: "[]") }.getOrElse { JSONArray() }
        val states = runCatching { JSONObject(prefs.getString(KEY_STATES, null) ?: "{}") }.getOrElse { JSONObject() }
        val subdirs = runCatching { JSONObject(prefs.getString(KEY_SUBDIRS, null) ?: "{}") }.getOrElse { JSONObject() }
        val list = JSONArray()
        for (i in 0 until paths.length()) {
            val path = paths.optString(i)
            val state = states.optJSONObject(path)
            list.put(
                JSONObject()
                    .put("path", path)
                    .put("target", state?.optString("target", "native") ?: "native")
                    .put("selected_distro", state?.optString("selectedDistro").takeUnless { it.isNullOrBlank() } ?: JSONObject.NULL)
                    .put("udocker_mode", state?.optString("udockerMode", "simple") ?: "simple")
                    .put("sub_projects", subdirs.optJSONArray(path) ?: JSONArray())
            )
        }
        return JSONObject().put("ok", true).put("projects", list)
    }
}

package com.termux.app.util

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Historial de comandos reales enviados a cada sesión de terminal, para hacerlos buscables
 * desde el filtro del sidebar adaptado (ver TermuxActivity.applyAdaptedDrawerFilter()/
 * addMatchingHistoryRows()) — hallazgo 1 de
 * docs/estructura/INVESTIGACION_TERMINAL_PERSONALIZACION_2026-09-01.md.
 *
 * Alcance real (simplificación deliberada, no un gap sin documentar): captura solo los
 * comandos que pasan por el mecanismo de escritura MEDIADO por la app
 * (TermuxActivity.writeCommandOnceSessionReady()/runCustomCommand() — "Reiniciar módulo",
 * atajos MCP, comandos personalizados del sidebar, "TUI en terminal" desde un módulo) — NO
 * cada tecla que el usuario tipea directo en la terminal. Esas pulsaciones van de
 * TerminalView (terminal-view/, protegido — ver CLAUDE.md § Protected Files) directo a
 * TerminalSession.write() (terminal-emulator/, también protegido) sin pasar nunca por
 * TermuxActivity, así que interceptarlas exigiría tocar código protegido — fuera de alcance
 * sin permiso explícito del usuario (mismo criterio de "no tocar sin permiso explícito"
 * que ya aplica a modulos/, por analogía).
 *
 * Persistencia por sesión (clave = TerminalSession.mSessionName, o "default" para sesiones
 * sin nombre) en SharedPreferences con un único JSON {sessionKey: [cmd, ...]} — mismo patrón
 * sin cifrar que TerminalCustomCommandsManager (no son secretos, distinto del caso de
 * credenciales — eso no aplica acá).
 */
class TerminalCommandHistoryManager(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Agrega [command] al historial de [sessionKey], recortando a [MAX_PER_SESSION] (más
     *  reciente al final). No repite si es idéntico al último comando registrado — evita que
     *  reabrir el mismo TUI varias veces infle el historial con la misma línea. */
    @Synchronized
    fun record(sessionKey: String, command: String) {
        if (sessionKey.isBlank() || command.isBlank()) return
        val all = readAll()
        val current = (all[sessionKey] ?: emptyList()).toMutableList()
        if (current.lastOrNull() != command) {
            current.add(command)
            if (current.size > MAX_PER_SESSION) {
                current.subList(0, current.size - MAX_PER_SESSION).clear()
            }
            all[sessionKey] = current
            writeAll(all)
        }
    }

    /** Historial de [sessionKey], más reciente primero. */
    fun forSession(sessionKey: String): List<String> =
        readAll()[sessionKey]?.asReversed() ?: emptyList()

    /** Comandos de [sessionKey] que contienen [query] (sin distinguir mayúsculas), más
     *  reciente primero, recortado a [limit]. */
    fun search(sessionKey: String, query: String, limit: Int = 6): List<String> {
        if (query.isBlank()) return emptyList()
        val lower = query.trim().lowercase()
        return forSession(sessionKey).filter { it.lowercase().contains(lower) }.take(limit)
    }

    private fun readAll(): MutableMap<String, List<String>> {
        val raw = prefs.getString(KEY_HISTORY, null) ?: return mutableMapOf()
        return try {
            val obj = JSONObject(raw)
            val result = mutableMapOf<String, List<String>>()
            obj.keys().forEach { key ->
                val arr = obj.optJSONArray(key) ?: JSONArray()
                result[key] = (0 until arr.length()).map { arr.optString(it) }
            }
            result
        } catch (_: Exception) {
            mutableMapOf()
        }
    }

    private fun writeAll(all: Map<String, List<String>>) {
        val obj = JSONObject()
        all.forEach { (key, commands) -> obj.put(key, JSONArray(commands)) }
        prefs.edit().putString(KEY_HISTORY, obj.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "kairos_terminal_command_history"
        private const val KEY_HISTORY = "history_json"
        const val MAX_PER_SESSION = 50
    }
}

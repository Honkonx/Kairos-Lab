package com.termux.app.util

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Comandos personalizados del sidebar de la terminal normal (`left_drawer_normal_content` en
 * `activity_termux.xml`) — botones que el usuario define con un nombre corto (label) y el
 * comando real que se escribe en la sesión activa al tocarlos, para no tener que re-escribir
 * comandos largos/frecuentes a mano cada vez.
 *
 * Idea adoptada de `referencia/termux/NewTermux-main` ("Custom command buttons" del left drawer,
 * ver `docs/referencias/termux/REFERENCIA_NEWTERMUX.md`) — acá simplificada: en vez de abrir una
 * sesión nueva nombrada por botón (como el original), cada botón escribe el comando en la sesión
 * de terminal ACTIVA (mismo mecanismo que ya usa el resto de `TermuxActivity`, ver
 * `TerminalSession.write(command + "\n")`), que es el camino de menor riesgo dado que no toca el
 * manejo de sesiones múltiples heredado de termux-app.
 *
 * Persistencia en [SharedPreferences] planas con un JSON array — mismo patrón que
 * `RecentProjectsManager` (`app/src/main/java/com/termux/app/ui/studio/project/RecentProjectsManager.kt`):
 * sin cifrar a propósito, los comandos de terminal no son secretos (a diferencia de
 * credenciales, que nunca se vuelven a mostrar — eso no aplica acá).
 */
class TerminalCustomCommandsManager(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    data class CustomCommand(
        val id: String,
        val label: String,
        val command: String
    )

    fun getAll(): List<CustomCommand> = readAll()

    /** Agrega un comando nuevo, con tope de [MAX_CUSTOM_COMMANDS] para no desbordar el sidebar. */
    fun add(label: String, command: String): Boolean {
        val current = readAll()
        if (current.size >= MAX_CUSTOM_COMMANDS) return false
        val updated = current + CustomCommand(UUID.randomUUID().toString(), label.trim(), command.trim())
        writeAll(updated)
        return true
    }

    fun update(id: String, label: String, command: String) {
        val updated = readAll().map {
            if (it.id == id) it.copy(label = label.trim(), command = command.trim()) else it
        }
        writeAll(updated)
    }

    fun remove(id: String) {
        writeAll(readAll().filterNot { it.id == id })
    }

    private fun readAll(): List<CustomCommand> {
        val raw = prefs.getString(KEY_COMMANDS, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index -> parseEntry(array.optJSONObject(index)) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseEntry(entry: JSONObject?): CustomCommand? {
        if (entry == null) return null
        val id = entry.optString(FIELD_ID, "")
        val label = entry.optString(FIELD_LABEL, "")
        val command = entry.optString(FIELD_COMMAND, "")
        if (id.isEmpty() || label.isEmpty() || command.isEmpty()) return null
        return CustomCommand(id, label, command)
    }

    private fun writeAll(commands: List<CustomCommand>) {
        val array = JSONArray()
        commands.forEach { entry ->
            array.put(
                JSONObject()
                    .put(FIELD_ID, entry.id)
                    .put(FIELD_LABEL, entry.label)
                    .put(FIELD_COMMAND, entry.command)
            )
        }
        prefs.edit().putString(KEY_COMMANDS, array.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "kairos_terminal_custom_commands"
        private const val KEY_COMMANDS = "commands_json"
        private const val FIELD_ID = "id"
        private const val FIELD_LABEL = "label"
        private const val FIELD_COMMAND = "command"
        const val MAX_CUSTOM_COMMANDS = 20
    }
}

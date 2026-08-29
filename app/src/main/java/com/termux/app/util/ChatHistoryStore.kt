package com.termux.app.util

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persistencia mínima del historial de chat (ChatFragment) — una sola
 * conversación activa, en un JSON plano en el storage privado de la app
 * (`context.filesDir/chat_history.json`, mismo criterio que
 * LocalModelManager: nada de esto toca $HOME/Termux).
 *
 * A propósito NO es una arquitectura multi-conversación con Room/SQL — el
 * feedback del usuario pedía "administrar mensajes en disco y en memoria",
 * no un historial de conversaciones separadas. Si eso se pide después, se
 * arma explícito en ese momento (YAGNI).
 */
object ChatHistoryStore {

    data class StoredMessage(
        val id: String,
        val role: String,
        val content: String,
        val ts: Long,
        val model: String?,
        // Imagen adjunta (base64 crudo, solo mensajes "user" con Ollama remoto) — ver
        // ChatFragment.ChatMessage.imageBase64. Su crecimiento en disco queda acotado por
        // el límite de historial configurable (ChatFragment.enforceHistoryLimit), así que
        // no hace falta un archivo/cache de imágenes aparte de este JSON plano.
        val imageBase64: String? = null,
    )

    private const val FILE_NAME = "chat_history.json"

    @JvmStatic
    private fun historyFile(context: Context): File = File(context.filesDir, FILE_NAME)

    @JvmStatic
    fun load(context: Context): List<StoredMessage> {
        val file = historyFile(context)
        if (!file.exists()) return emptyList()
        return try {
            val array = JSONArray(file.readText(Charsets.UTF_8))
            val result = ArrayList<StoredMessage>(array.length())
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                result.add(
                    StoredMessage(
                        id = obj.getString("id"),
                        role = obj.getString("role"),
                        content = obj.getString("content"),
                        ts = obj.getLong("ts"),
                        model = if (obj.isNull("model")) null else obj.getString("model"),
                        // isNull() da true tanto si la clave no existe (historial guardado
                        // antes de este campo) como si es JSONObject.NULL — compatible hacia
                        // atrás sin necesidad de migrar archivos viejos.
                        imageBase64 = if (obj.isNull("image")) null else obj.getString("image"),
                    )
                )
            }
            result
        } catch (_: Exception) {
            // Archivo corrupto o formato viejo — se descarta en vez de romper el chat al abrir.
            emptyList()
        }
    }

    @JvmStatic
    fun save(context: Context, messages: List<StoredMessage>) {
        val array = JSONArray()
        for (msg in messages) {
            array.put(JSONObject().apply {
                put("id", msg.id)
                put("role", msg.role)
                put("content", msg.content)
                put("ts", msg.ts)
                put("model", msg.model)
                put("image", msg.imageBase64)
            })
        }
        try {
            historyFile(context).writeText(array.toString(), Charsets.UTF_8)
        } catch (_: Exception) {
            // Falla de escritura (disco lleno, etc.) no debe tumbar el chat — el historial
            // en memoria sigue funcionando aunque no se pueda persistir esta vez.
        }
    }

    @JvmStatic
    fun clear(context: Context) {
        val file = historyFile(context)
        if (file.exists()) file.delete()
    }

    /** Tamaño real en disco del historial persistido — usado por la pantalla de
     *  estadísticas de OllamaConfigFragment ("Historial de chat"), paridad con el "Tamaño
     *  BD" que muestra `_ollama_config_sql()` en menu_nativo.sh (ahí es un .db SQLite, acá
     *  es este mismo JSON plano). */
    @JvmStatic
    fun sizeBytes(context: Context): Long {
        val file = historyFile(context)
        return if (file.exists()) file.length() else 0L
    }
}

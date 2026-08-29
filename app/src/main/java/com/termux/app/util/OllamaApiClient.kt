package com.termux.app.util

import com.termux.shared.termux.TermuxConstants
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Habla directo con la API REST de Ollama (127.0.0.1:11434) — mismo patrón HTTP que
 * ChatFragment.makeOllamaRequest() (HttpURLConnection + org.json), para las acciones de
 * gestión de modelos que antes pasaban por kairos_manager.py cmd_ollama
 * (models-list/models-pull/models-delete/models-info). Ese código Python era subprocess +
 * urllib puro sin lógica propia — la API HTTP es la misma que ya habla Ollama, saltar a
 * python3 en el medio solo agregaba una dependencia (y un punto de falla, ver
 * "Cannot run program python3" en docs/humano*.md 2026-07-31) sin aportar nada.
 *
 * Todas las funciones de red son bloqueantes (sin threading propio) — igual que
 * ProcesosFragment.runPm2(), el Fragment que llama es responsable de correrlas en un
 * Thread y volver al hilo principal para tocar la UI.
 */
object OllamaApiClient {

    private const val BASE_URL = "http://127.0.0.1:11434"

    data class ModelSummary(val name: String, val sizeHuman: String, val family: String)
    // capabilities: campo real de /api/show en versiones recientes de Ollama (ej.
    // ["completion", "vision", "tools"]) — vacío en versiones viejas que no lo exponen
    // todavía, ver ChatFragment.refreshVisionCapability() para cómo se interpreta esa
    // ambigüedad (nunca se asume "no soporta" solo por falta de dato).
    data class ModelDetail(val parameterSize: String, val family: String, val capabilities: List<String> = emptyList())
    // Modelo cargado en memoria (VRAM/RAM) ahora mismo — dato real de /api/ps que la UI
    // nunca consultaba: OllamaFragment mostraba "Ninguno" hardcodeado en MODELO ACTIVO sin
    // importar si había un modelo realmente cargado tras un chat reciente.
    data class RunningModel(val name: String, val sizeVramHuman: String, val expiresAt: String)

    // ── Modelos (HTTP) ──────────────────────────────────────────────────

    @Throws(Exception::class)
    fun listModels(): List<ModelSummary> {
        val json = httpGetJson("$BASE_URL/api/tags", connectMs = 5000, readMs = 8000)
        val models = json.optJSONArray("models") ?: return emptyList()
        return (0 until models.length()).map { i ->
            val m = models.getJSONObject(i)
            val details = m.optJSONObject("details")
            ModelSummary(
                name = m.optString("name"),
                sizeHuman = humanSize(m.optLong("size", 0)),
                family = details?.optString("family", "") ?: ""
            )
        }
    }

    // Descargar un modelo puede tardar varios minutos (llega a GB) — mismo timeout
    // largo que usaba el lado Python (http_post_json(..., timeout=600)).
    //
    // Velocidad/ETA real (patrón adoptado de MiceWine, ver docs/referencias/REFERENCIA_MICEWINE.md
    // "Pieza #1"): antes esto era una sola llamada bloqueante con "stream": false, sin
    // ningún progreso hasta que terminaba (el usuario solo veía "Descargando…" fijo,
    // sin saber si iba a tardar 1 minuto o 20). La API real de Ollama soporta streaming
    // (`"stream": true`) — cada línea de la respuesta es un JSON con `completed`/`total`
    // en bytes; se parsea línea por línea (mismo patrón que ChatFragment.makeOllamaRequest())
    // y se reporta % + velocidad + ETA real, con el mismo throttle de 500ms que
    // LocalModelManager.downloadModel() usa para modelos GGUF locales.
    /**
     * `onProgress` recibe (percent, mensaje) — percent en 0..100 cuando Ollama ya reportó
     * `total` (para que la UI muestre una barra determinada real, ver ProgressDialogController
     * .updateProgress()), o -1 en fases sin tamaño conocido todavía ("verifying sha256 digest",
     * "writing manifest") — ahí solo hay texto de estado, no % que calcular. Antes esto era un
     * único `String` ya formateado (docs/humano247.md, pedido explícito del usuario: la barra
     * de progreso de descargas debe ser real, no solo texto dentro de un spinner indeterminado).
     */
    @Throws(Exception::class)
    fun pullModel(name: String, onProgress: ((percent: Int, message: String) -> Unit)? = null) {
        val conn = URL("$BASE_URL/api/pull").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = 10000
            conn.readTimeout = 600_000
            val body = JSONObject().put("name", name).put("stream", true)
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            if (code !in 200..299) {
                val text = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                throw IOException("HTTP $code: ${extractOllamaError(text) ?: text}")
            }

            var lastUpdateTime = System.currentTimeMillis()
            var lastCompletedAtUpdate = 0L
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val raw = line?.trim() ?: continue
                    if (raw.isEmpty()) continue
                    val json = try { JSONObject(raw) } catch (_: Exception) { continue }
                    json.optString("error").takeIf { it.isNotBlank() }?.let { throw IOException(it) }
                    if (onProgress == null) continue

                    val total = json.optLong("total", 0)
                    val completed = json.optLong("completed", 0)
                    val now = System.currentTimeMillis()
                    if (total > 0 && now - lastUpdateTime >= 500) {
                        val deltaSeconds = (now - lastUpdateTime) / 1000.0
                        val speedBps = (completed - lastCompletedAtUpdate) / deltaSeconds
                        lastUpdateTime = now
                        lastCompletedAtUpdate = completed
                        val pct = (completed * 100 / total).toInt()
                        onProgress(pct, formatPullProgress(name, completed, total, speedBps))
                    } else if (total == 0L) {
                        // Fases sin tamaño conocido (ej. "verifying sha256 digest",
                        // "writing manifest") — se muestra el status crudo de Ollama, no hay
                        // % que calcular todavía.
                        onProgress(-1, "$name — ${json.optString("status", "…")}")
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun formatPullProgress(name: String, completed: Long, total: Long, speedBps: Double): String {
        val pct = (completed * 100 / total).toInt()
        val speedInfo = if (speedBps > 0) " · ${humanSize(speedBps.toLong())}/s" else ""
        val etaInfo = if (speedBps > 0) " · ETA ${humanDuration(((total - completed) / speedBps).toInt())}" else ""
        return "$name — $pct% · ${humanSize(completed)}/${humanSize(total)}$speedInfo$etaInfo"
    }

    private fun humanDuration(seconds: Int): String {
        val s = seconds.coerceAtLeast(0)
        if (s < 60) return "${s}s"
        return "${s / 60}m ${s % 60}s"
    }

    // /api/ps — modelos cargados AHORA MISMO en memoria (proceso "ollama serve" vivo, no
    // requiere que haya un chat activo en la app: cualquier request reciente, incluso de
    // otra herramienta apuntando al mismo :11434, lo deja cargado hasta que expire).
    @Throws(Exception::class)
    fun psModels(): List<RunningModel> {
        val json = httpGetJson("$BASE_URL/api/ps", connectMs = 3000, readMs = 5000)
        val models = json.optJSONArray("models") ?: return emptyList()
        return (0 until models.length()).map { i ->
            val m = models.getJSONObject(i)
            RunningModel(
                name = m.optString("name"),
                sizeVramHuman = humanSize(m.optLong("size_vram", 0)),
                expiresAt = m.optString("expires_at", "")
            )
        }
    }

    // Descarga un modelo de memoria (VRAM/RAM) SIN borrarlo del disco — feature real y
    // documentada de la API de Ollama (docs/faq.md "How do I keep a model loaded in memory
    // or make it unload immediately?": un POST a /api/generate con "keep_alive": 0 y sin
    // "prompt" fuerza la descarga inmediata, en vez de esperar los 5 minutos default de
    // OLLAMA_KEEP_ALIVE). Antes esta acción no existía en ninguna parte de Kairos — el
    // único control real sobre modelos cargados era esperar el timeout o matar el proceso
    // "ollama serve" entero (perdiendo TODOS los modelos cargados, no solo uno).
    @Throws(Exception::class)
    fun unloadModel(name: String) {
        val body = JSONObject().put("model", name).put("keep_alive", 0)
        httpPostJson("$BASE_URL/api/generate", body, connectMs = 5000, readMs = 10000)
    }

    @Throws(Exception::class)
    fun deleteModel(name: String) {
        val conn = URL("$BASE_URL/api/delete").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "DELETE"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = 5000
            conn.readTimeout = 10000
            conn.outputStream.use { it.write(JSONObject().put("name", name).toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val text = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                throw IOException("HTTP $code: ${extractOllamaError(text) ?: text}")
            }
        } finally {
            conn.disconnect()
        }
    }

    @Throws(Exception::class)
    fun modelInfo(name: String): ModelDetail {
        val json = httpPostJson("$BASE_URL/api/show", JSONObject().put("name", name), connectMs = 5000, readMs = 15000)
        val details = json.optJSONObject("details")
        val capsArray = json.optJSONArray("capabilities")
        val caps = if (capsArray != null) (0 until capsArray.length()).map { capsArray.optString(it) } else emptyList()
        return ModelDetail(
            parameterSize = details?.optString("parameter_size", "—") ?: "—",
            family = details?.optString("family", "—") ?: "—",
            capabilities = caps
        )
    }

    // ── Config (~/.ollama_user_config) ─────────────────────────────────
    // Formato "OLLAMA_CLAVE=valor" por línea, mismos defaults que
    // _ollama_read_config()/_ollama_write_config() en kairos_manager.py — lectura/escritura
    // de texto simple, no hace falta pasar por un proceso para esto.

    private val configFile: File
        get() = File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".ollama_user_config")

    private val CONFIG_DEFAULTS = linkedMapOf(
        "OLLAMA_TEMP" to "0.7",
        "OLLAMA_TOP_P" to "0.9",
        "OLLAMA_TOP_K" to "40",
        "OLLAMA_REP_PENALTY" to "1.1",
        "OLLAMA_NUM_CTX" to "2048",
        "OLLAMA_NUM_PREDICT" to "2048",
        // 0 = solo localhost (default, seguro) · 1 = expone la API a la red local
        // (OLLAMA_HOST=0.0.0.0) — leída por ollama_start.sh en cada arranque de
        // Ollama, ver modulos/ollama.sh. No tiene efecto en caliente: si Ollama ya
        // está corriendo hay que reiniciar el servicio para que tome el nuevo valor.
        "OLLAMA_LAN" to "0",
        // Concurrencia/memoria del servidor (ronda de continuación 2026-08-19, ver
        // AUDITORIA_MODULOS_IA_DEV_VS_OFICIAL_2026-08-19.md § Actualización) — confirmados
        // reales contra github.com/ollama/ollama/envconfig/config.go + docs.ollama.com/faq.
        // Vacío/0 = usar el default real del binario (ver comentario en ollama_start.sh),
        // no se fuerza ningún valor si el usuario no tocó el campo.
        "OLLAMA_KEEP_ALIVE" to "",
        "OLLAMA_NUM_PARALLEL" to "0",
        "OLLAMA_MAX_LOADED_MODELS" to "0",
        // Paridad con LlamaServerConfigFragment's threads/-ngl (auditoría de paridad de
        // opciones, 2026-08-28) — a diferencia de OLLAMA_NUM_PARALLEL/MAX_LOADED_MODELS (env
        // vars leídas por ollama_start.sh al arrancar el binario), num_thread/num_gpu son
        // "options" reales del body de /api/chat (ver ChatFragment.makeOllamaRequest) —
        // confirmados contra github.com/ollama/ollama/api/types.go's Options struct. "0" acá
        // es el mismo sentinel "sin tocar" que el resto de esta config — makeOllamaRequest()
        // solo agrega la key al JSON si el valor guardado es > 0, nunca fuerza num_gpu=0
        // (que en la API real de Ollama significa "todo en CPU", no "auto").
        "OLLAMA_NUM_THREAD" to "0",
        "OLLAMA_NUM_GPU" to "0",
        "OLLAMA_SYSTEM_PROMPT" to "Eres un asistente técnico. Responde en español. Sé directo y conciso."
    )

    fun readConfig(): Map<String, String> {
        val cfg = LinkedHashMap(CONFIG_DEFAULTS)
        val file = configFile
        if (!file.exists()) return cfg
        try {
            file.forEachLine { raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#") || "=" !in line) return@forEachLine
                val key = line.substringBefore("=").trim()
                val value = line.substringAfter("=").trim().trim('"').trim('\'')
                cfg[key] = unescapeConfigValue(value)
            }
        } catch (_: Exception) { /* config corrupta o ilegible: se queda en los defaults */ }
        return cfg
    }

    // ManagerNativeUtils.upsertKeyValueLine() — consolidación 2026-08-13 (ver auditoría de
    // código): mismo algoritmo que HermesNative.configSetProvider()/
    // EntornoNative.updateRegistryValue() reimplementaban cada uno por su lado.
    //
    // Bug real (auditoría IA 2026-08-19): OllamaConfigFragment.systemPromptInput es multilinea
    // (minLines=4) pero el archivo es "CLAVE=valor" por línea — un system prompt con saltos de
    // línea reales rompía el formato: solo la primera línea quedaba asociada a
    // OLLAMA_SYSTEM_PROMPT= y el resto quedaba como líneas huérfanas sin "=" que readConfig()
    // descarta en silencio (el prompt guardado quedaba truncado a su primera línea). Se escapan
    // los \n (y \\) antes de escribir, se desescapan al leer.
    fun writeConfigValue(key: String, value: String) {
        val fullKey = if (key.startsWith("OLLAMA_")) key else "OLLAMA_${key.uppercase()}"
        ManagerNativeUtils.upsertKeyValueLine(configFile, fullKey, escapeConfigValue(value))
    }

    private fun escapeConfigValue(value: String): String = buildString {
        for (c in value) {
            when (c) {
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> { /* CRLF del teclado — se descarta, \n alcanza para reconstruir */ }
                else -> append(c)
            }
        }
    }

    private fun unescapeConfigValue(value: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '\\' && i + 1 < value.length) {
                when (value[i + 1]) {
                    'n' -> { sb.append('\n'); i += 2 }
                    '\\' -> { sb.append('\\'); i += 2 }
                    else -> { sb.append(c); i += 1 }
                }
            } else {
                sb.append(c); i += 1
            }
        }
        return sb.toString()
    }

    fun resetConfig() {
        val sb = StringBuilder("# ~/.ollama_user_config — restaurado\n")
        CONFIG_DEFAULTS.forEach { (k, v) -> sb.append(k).append('=').append(v).append('\n') }
        configFile.writeText(sb.toString())
    }

    // ── HTTP helpers ─────────────────────────────────────────────────

    // Bug real confirmado (reporte del usuario, 2026-07-31 — ver docs/humano/humano33.md): las 2
    // funciones de abajo tiraban el cuerpo real de la respuesta de error de Ollama al
    // lanzar la excepción (o ni lo leían), dejando solo "HTTP 404"/"HTTP 400" sin ninguna
    // pista de qué salió mal — Ollama manda `{"error": "..."}` con el motivo real
    // (ej. "model 'x' not found, try pulling it first"). Ahora ese texto viaja siempre en
    // el mensaje de la excepción — quien llama (ChatFragment, ModelsFragment) decide si
    // mostrarlo crudo o traducirlo (ver LlmErrorMapper.mapOllamaHttp).
    private fun httpGetJson(urlStr: String, connectMs: Int, readMs: Int): JSONObject {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = connectMs
            conn.readTimeout = readMs
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IOException("HTTP $code: ${extractOllamaError(text) ?: text}")
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }

    private fun httpPostJson(urlStr: String, body: JSONObject, connectMs: Int, readMs: Int): JSONObject {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = connectMs
            conn.readTimeout = readMs
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IOException("HTTP $code: ${extractOllamaError(text) ?: text}")
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }

    private fun extractOllamaError(body: String): String? =
        try { JSONObject(body).optString("error").takeIf { it.isNotBlank() } } catch (_: Exception) { null }

    private fun humanSize(bytes: Long): String {
        var b = bytes.toDouble()
        for (unit in listOf("B", "KB", "MB", "GB")) {
            if (b < 1024) return "%.1f%s".format(b, unit)
            b /= 1024
        }
        return "%.1fTB".format(b)
    }
}

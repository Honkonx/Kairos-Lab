package com.termux.app.util

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Export/import de configuración "liviana" de Kairos — complementa (NO reemplaza) a
 * BackupManager.create(), que empaqueta TODO (scripts/ completo + registry + .bashrc +
 * configs de módulo) en un .tar.gz binario pensado como respaldo completo. Acá se arma un
 * único JSON legible con: el registry completo (~/.android_server_registry), las
 * preferencias relevantes de "kairos_prefs" (las mismas que ConfigFragment.kt ya lee/
 * escribe: toggles generales + terminal + Telegram), y los archivos de config chicos por
 * módulo (flag de modo local de n8n, config de inferencia de Ollama, .env de Hermes, config
 * de OpenClaw, última URL de túnel) — pensado para migrar ajustes entre instalaciones o
 * revisar/diffear config a mano, no para mover proyectos/binarios completos.
 *
 * ── Redacción de secretos (decisión de esta ronda) ──────────────────────────────────────
 * Un JSON plano en Download/ es mucho más fácil de abrir "sin querer" (cualquier visor de
 * texto/gestor de archivos lo previsualiza tal cual) que un .tar.gz, que ya requiere
 * descomprimir para leer — aunque ninguno de los dos formatos está cifrado, la fricción de
 * exposición accidental es distinta. Por eso, en vez de tratar el token de Telegram como
 * caso especial, se aplica una regla genérica y pareja a TODO el contenido exportado:
 * cualquier clave (del registry, de prefs, o cualquier "CLAVE=valor" dentro de un archivo de
 * config de módulo, o cualquier campo de un JSON de módulo) cuyo NOMBRE contenga "token",
 * "apikey", "api_key", "secret" o "password" (sin importar mayúsculas) se exporta con el
 * centinela [REDACTED_VALUE] en vez del valor real. Esto cubre parejo: el bot token de
 * Telegram, tunnel.cloudflare.token / tunnel.ngrok.token del registry (ver TunnelManager.kt),
 * el gateway.auth.token y los apiKey de providers de ~/.openclaw/openclaw.json (ver
 * OpenClawNative.kt), y los *_API_KEY de ~/.hermes/.env (ver HermesNative.kt) — no solo
 * Telegram. Los chat_id, flags booleanos, URLs de túnel efímeras y parámetros de inferencia
 * de Ollama (temperatura, tono, LAN on/off) SÍ viajan reales: no son credenciales, y sin
 * ellos el export pierde la mitad de su utilidad práctica.
 * importConfig() nunca sobreescribe una clave/valor existente con el centinela — si lo
 * encuentra, salta esa entrada puntual y la reporta en "skipped" para que el usuario sepa
 * qué tiene que volver a configurar a mano (ej. re-pegar el token de Telegram).
 */
object ConfigExportManager {

    const val REDACTED_VALUE = "[REDACTED]"
    private val SENSITIVE_NAME_PATTERN = Regex("token|apikey|api_key|secret|password", RegexOption.IGNORE_CASE)

    private const val PREFS_NAME = "kairos_prefs"
    private const val SCHEMA_MARKER = "kairos_config_export"
    private const val SCHEMA_VERSION = 1

    private val home get() = ManagerNativeUtils.home
    private val configDir = File("/storage/emulated/0/Download/KairosConfig")
    private val registryLockFile get() = ManagerNativeUtils.registryLockFile

    // Prefs booleanas de kairos_prefs consideradas "config" (no estado transitorio de UI) —
    // mismo set que ConfigFragment.kt lee/escribe con addToggleRow().
    private val BOOLEAN_PREF_KEYS = listOf(
        "pref_auto_start",
        "pref_notify_modules",
        "pref_floating_widget",
        "pref_classic_terminal",
    )

    // Prefs de texto — el chat id no es secreto (solo identifica un chat de Telegram), el
    // bot token sí (ver SENSITIVE_NAME_PATTERN, se redacta solo por el nombre de la clave).
    private val STRING_PREF_KEYS = listOf(
        TelegramNotifier.PREF_BOT_TOKEN,
        TelegramNotifier.PREF_CHAT_ID,
    )

    // Archivos de config por-módulo tipo "CLAVE=valor" (con comentarios permitidos) —
    // relativos a HOME. No incluye ~/.hermes/config.yaml ni ~/.hermes/SOUL.md (contenido/
    // personalidad más grande, ya cubierto por el .tar.gz de BackupManager, no son "config"
    // en el sentido de ajustes/toggles).
    private val KV_CONFIG_FILES = listOf(
        ".ollama_user_config",
        ".hermes/.env",
    )

    // Archivo JSON de config de módulo — se redacta recursivamente por nombre de campo
    // (gateway.auth.token, providers[].apiKey, ver OpenClawNative.kt).
    private const val OPENCLAW_CONFIG_FILE = ".openclaw/openclaw.json"

    // Texto plano de una sola línea, no sensible (URL de túnel efímera).
    private const val LAST_CF_URL_FILE = ".last_cf_url"

    // Flag de existencia (sin contenido relevante) — ver N8nFragment.localOnlyFlagFile().
    private const val N8N_LOCAL_ONLY_FILE = ".n8n_local_only"

    // ────────────────────────────────────────────────────────────
    // Export
    // ────────────────────────────────────────────────────────────

    fun exportConfig(context: Context): JSONObject {
        val redactedKeys = JSONArray()

        val registryJson = JSONObject()
        ManagerNativeUtils.readRegistry().forEach { (k, v) ->
            if (isSensitiveKey(k)) {
                registryJson.put(k, REDACTED_VALUE)
                redactedKeys.put("registry.$k")
            } else {
                registryJson.put(k, v)
            }
        }

        val prefs = context.getSharedPreferences(PREFS_NAME, 0)
        val prefsJson = JSONObject()
        BOOLEAN_PREF_KEYS.forEach { key ->
            if (prefs.contains(key)) prefsJson.put(key, prefs.getBoolean(key, false))
        }
        STRING_PREF_KEYS.forEach { key ->
            val value = prefs.getString(key, null) ?: return@forEach
            if (value.isBlank()) return@forEach
            if (isSensitiveKey(key)) {
                prefsJson.put(key, REDACTED_VALUE)
                redactedKeys.put("prefs.$key")
            } else {
                prefsJson.put(key, value)
            }
        }

        val moduleFiles = JSONObject()
        KV_CONFIG_FILES.forEach { rel ->
            val file = File(home, rel)
            if (!file.exists()) return@forEach
            val (redactedText, redactedFileKeys) = redactKvText(file.readText())
            redactedFileKeys.forEach { redactedKeys.put("module_files.$rel:$it") }
            moduleFiles.put(rel, JSONObject().apply {
                put("type", "kv")
                put("content", redactedText)
            })
        }
        File(home, OPENCLAW_CONFIG_FILE).let { file ->
            if (!file.exists()) return@let
            val parsed = try { JSONObject(file.readText()) } catch (e: Exception) { null }
            if (parsed == null) return@let
            val fileRedacted = mutableListOf<String>()
            val redacted = redactJsonValue(parsed, "", fileRedacted) as JSONObject
            fileRedacted.forEach { redactedKeys.put("module_files.$OPENCLAW_CONFIG_FILE:$it") }
            moduleFiles.put(OPENCLAW_CONFIG_FILE, JSONObject().apply {
                put("type", "json")
                put("content", redacted)
            })
        }
        File(home, LAST_CF_URL_FILE).let { file ->
            if (!file.exists()) return@let
            moduleFiles.put(LAST_CF_URL_FILE, JSONObject().apply {
                put("type", "text")
                put("content", file.readText().trim())
            })
        }

        val flags = JSONObject().apply {
            put(N8N_LOCAL_ONLY_FILE, File(home, N8N_LOCAL_ONLY_FILE).exists())
        }

        val export = JSONObject().apply {
            put(SCHEMA_MARKER, true)
            put("version", SCHEMA_VERSION)
            put("created_at", System.currentTimeMillis())
            put("registry", registryJson)
            put("prefs", prefsJson)
            put("module_files", moduleFiles)
            put("flags", flags)
            put("redacted_keys", redactedKeys)
        }

        return try {
            if (!configDir.exists()) configDir.mkdirs()
            val timestamp = System.currentTimeMillis().toString()
            val dest = File(configDir, "kairos-config-$timestamp.json")
            dest.writeText(export.toString(2))
            JSONObject()
                .put("ok", true)
                .put("path", dest.absolutePath)
                .put("size", dest.length())
                .put("size_human", ManagerNativeUtils.humanSize(dest.length()))
                .put("redacted_count", redactedKeys.length())
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message ?: "Error desconocido al exportar")
        }
    }

    // ────────────────────────────────────────────────────────────
    // Import — conservador: cada entrada se aplica de forma independiente (try/catch por
    // ítem), nunca se detiene en el primer error y siempre reporta qué se aplicó, qué se
    // saltó (por estar redactado) y qué falló — nunca un estado a medias sin explicación.
    // ────────────────────────────────────────────────────────────

    fun importConfig(context: Context, file: File): JSONObject {
        val applied = JSONArray()
        val skipped = JSONArray()
        val errors = JSONArray()

        val root = try {
            JSONObject(file.readText())
        } catch (e: Exception) {
            return JSONObject().put("ok", false).put("error", "Archivo inválido o corrupto: ${e.message}")
        }
        if (!root.optBoolean(SCHEMA_MARKER, false)) {
            return JSONObject().put("ok", false).put("error", "No es un archivo de configuración de Kairos válido")
        }

        root.optJSONObject("registry")?.let { registryJson ->
            try {
                importRegistry(registryJson, applied, skipped, errors)
            } catch (e: Exception) {
                errors.put("registry: ${e.message}")
            }
        }

        root.optJSONObject("prefs")?.let { prefsJson ->
            val prefs = context.getSharedPreferences(PREFS_NAME, 0)
            val editor = prefs.edit()
            var changed = false
            for (key in prefsJson.keys()) {
                try {
                    val value = prefsJson.get(key)
                    if (value is String && value == REDACTED_VALUE) {
                        skipped.put("prefs.$key (redactado en el export)")
                        continue
                    }
                    when (value) {
                        is Boolean -> editor.putBoolean(key, value)
                        else -> editor.putString(key, value.toString())
                    }
                    changed = true
                    applied.put("prefs.$key")
                } catch (e: Exception) {
                    errors.put("prefs.$key: ${e.message}")
                }
            }
            if (changed) editor.apply()
        }

        root.optJSONObject("module_files")?.let { moduleFiles ->
            for (rel in moduleFiles.keys()) {
                try {
                    importModuleFile(rel, moduleFiles.getJSONObject(rel), applied, skipped)
                } catch (e: Exception) {
                    errors.put("module_files.$rel: ${e.message}")
                }
            }
        }

        root.optJSONObject("flags")?.let { flagsJson ->
            try {
                val n8nLocalOnly = flagsJson.optBoolean(N8N_LOCAL_ONLY_FILE, false)
                val flagFile = File(home, N8N_LOCAL_ONLY_FILE)
                if (n8nLocalOnly) {
                    if (!flagFile.exists()) flagFile.createNewFile()
                } else {
                    flagFile.delete()
                }
                applied.put("flags.$N8N_LOCAL_ONLY_FILE")
            } catch (e: Exception) {
                errors.put("flags.$N8N_LOCAL_ONLY_FILE: ${e.message}")
            }
        }

        return JSONObject().apply {
            put("ok", errors.length() == 0)
            put("applied", applied)
            put("skipped", skipped)
            put("errors", errors)
        }
    }

    private fun importRegistry(registryJson: JSONObject, applied: JSONArray, skipped: JSONArray, errors: JSONArray) {
        val toApply = mutableMapOf<String, String>()
        for (key in registryJson.keys()) {
            try {
                val value = registryJson.getString(key)
                if (value == REDACTED_VALUE) {
                    skipped.put("registry.$key (redactado en el export)")
                } else {
                    toApply[key] = value
                    applied.put("registry.$key")
                }
            } catch (e: Exception) {
                errors.put("registry.$key: ${e.message}")
            }
        }
        if (toApply.isEmpty()) return
        RegistryLock.withLock(registryLockFile) {
            val registryFile = File(home, ".android_server_registry")
            val current = ManagerNativeUtils.readRegistry().toMutableMap()
            current.putAll(toApply)
            registryFile.writeText(current.entries.joinToString("\n") { "${it.key}=${it.value}" } + "\n")
        }
    }

    private fun importModuleFile(rel: String, entry: JSONObject, applied: JSONArray, skipped: JSONArray) {
        val file = File(home, rel)
        file.parentFile?.mkdirs()
        when (entry.optString("type")) {
            "kv" -> {
                val content = entry.optString("content", "")
                var appliedAny = false
                content.lines().forEach { line ->
                    val t = line.trim()
                    if (t.isEmpty() || t.startsWith("#")) return@forEach
                    val eq = t.indexOf('=')
                    if (eq <= 0) return@forEach
                    val key = t.substring(0, eq).trim()
                    val value = t.substring(eq + 1).trim()
                    if (value == REDACTED_VALUE) {
                        skipped.put("module_files.$rel:$key (redactado en el export)")
                        return@forEach
                    }
                    ManagerNativeUtils.upsertKeyValueLine(file, key, value)
                    appliedAny = true
                }
                if (appliedAny) applied.put("module_files.$rel")
            }
            "json" -> {
                val existing = if (file.exists()) {
                    try { JSONObject(file.readText()) } catch (e: Exception) { JSONObject() }
                } else JSONObject()
                val imported = entry.optJSONObject("content") ?: JSONObject()
                val fileSkipped = mutableListOf<String>()
                val merged = mergeJsonPreservingRedacted(existing, imported, fileSkipped) as JSONObject
                fileSkipped.forEach { skipped.put("module_files.$rel:$it (redactado en el export)") }
                file.writeText(merged.toString(2))
                applied.put("module_files.$rel")
            }
            "text" -> {
                val content = entry.optString("content", "")
                file.writeText(content)
                applied.put("module_files.$rel")
            }
        }
    }

    // ────────────────────────────────────────────────────────────
    // Redacción — helpers compartidos por exportConfig()
    // ────────────────────────────────────────────────────────────

    private fun isSensitiveKey(key: String): Boolean = SENSITIVE_NAME_PATTERN.containsMatchIn(key)

    /** Redacta líneas "CLAVE=valor" cuyo nombre de clave matchea SENSITIVE_NAME_PATTERN — preserva
     *  comentarios y el resto de líneas tal cual. Devuelve el texto redactado + las claves tocadas. */
    private fun redactKvText(rawText: String): Pair<String, List<String>> {
        val redacted = mutableListOf<String>()
        val lines = rawText.lines().map { line ->
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#")) return@map line
            val eq = t.indexOf('=')
            if (eq <= 0) return@map line
            val key = t.substring(0, eq).trim()
            if (isSensitiveKey(key)) {
                redacted.add(key)
                "$key=$REDACTED_VALUE"
            } else line
        }
        return lines.joinToString("\n") to redacted
    }

    /** Recorre un JSONObject/JSONArray redactando recursivamente cualquier campo String cuyo
     *  nombre de clave matchea SENSITIVE_NAME_PATTERN (ej. gateway.auth.token, providers[].apiKey). */
    private fun redactJsonValue(value: Any?, path: String, redactedKeys: MutableList<String>): Any? = when (value) {
        is JSONObject -> {
            val out = JSONObject()
            for (k in value.keys()) {
                val childPath = if (path.isEmpty()) k else "$path.$k"
                val v = value.get(k)
                if (isSensitiveKey(k) && v is String) {
                    redactedKeys.add(childPath)
                    out.put(k, REDACTED_VALUE)
                } else {
                    out.put(k, redactJsonValue(v, childPath, redactedKeys))
                }
            }
            out
        }
        is JSONArray -> {
            val out = JSONArray()
            for (i in 0 until value.length()) out.put(redactJsonValue(value.get(i), "$path[$i]", redactedKeys))
            out
        }
        else -> value
    }

    /** Fusiona "imported" sobre "existing": campos redactados ([REDACTED_VALUE]) del import
     *  NUNCA pisan un valor existente — se saltan y se reportan en "skipped". */
    private fun mergeJsonPreservingRedacted(existing: Any?, imported: Any?, skipped: MutableList<String>, path: String = ""): Any? {
        if (imported is JSONObject) {
            val base = if (existing is JSONObject) existing else JSONObject()
            for (k in imported.keys()) {
                val childPath = if (path.isEmpty()) k else "$path.$k"
                val importedValue = imported.get(k)
                if (importedValue is String && importedValue == REDACTED_VALUE) {
                    skipped.add(childPath)
                    // no se toca base[k] — conserva lo que ya había, si algo había
                } else {
                    base.put(k, mergeJsonPreservingRedacted(base.opt(k), importedValue, skipped, childPath))
                }
            }
            return base
        }
        return imported
    }
}

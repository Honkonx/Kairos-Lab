package com.termux.app.util

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Migración nativa de cmd_openclaw (kairos_manager.py) — SOLO la parte que no es
 * proyectos/workspace (esa sigue en Python, ver _handle_project_actions sobre
 * ~/.openclaw/workspace/, prefijo openclaw_workspace — mismo alcance/riesgo que
 * Claude/OpenCode, se migra aparte). Estas acciones eran wrappers finos sobre
 * archivos/tmux/HTTP que no necesitaban un intérprete Python en el medio.
 */
object OpenClawNative {

    private const val PORT = 18789
    private val home get() = ManagerNativeUtils.home

    fun info(): JSONObject {
        val reg = ManagerNativeUtils.readRegistry()
        val running = ManagerNativeUtils.tmuxHas("openclaw") || ManagerNativeUtils.checkPort(PORT)
        return JSONObject().apply {
            put("ok", true)
            put("installed", reg["openclaw.installed"] == "true")
            put("running", running)
            put("port", PORT)
            put("version", reg["openclaw.version"] ?: "")
            put("location", reg["openclaw.location"] ?: "")
        }
    }

    // Fix real 2026-08-25 (ver docs/modulos/OPENCLAW.md sección 12, causa raíz confirmada
    // contra referencia/interfaz/openclaw-termux-main/): "openclaw gateway" solo necesita
    // "gateway.mode"=="local" en el config para arrancar y auto-generar su propio
    // "gateway.auth.token" — exigir que el usuario complete el wizard interactivo entero
    // ("openclaw onboard", que además elige proveedor de IA) antes de dejarlo arrancar es
    // más estricto de lo necesario. Idempotente (merge, nunca pisa un config existente) —
    // refuerza en runtime el mismo pre-sembrado que ya hace modulos/openclaw.sh al instalar,
    // cubriendo el caso de un config que ya existía de antes de este fix o que se recreó
    // sin ese campo.
    //
    // Bug real #2 confirmado por ADB en dispositivo real (2026-08-28, docs/humano278.md/279.md):
    // "gateway.mode=local" solo no alcanza — sin "gateway.auth.mode=token" explícito, el propio
    // gateway genera un token EFÍMERO en cada arranque (confirmado en runtime.log real: "auth
    // token was missing. Generated a runtime token for this startup without changing config;
    // restart will generate a different token. Persist one with `openclaw config set
    // gateway.auth.mode token` and `openclaw config set gateway.auth.token <token>`") — ese
    // token efímero NUNCA se escribe en openclaw.json ni se imprime en ningún log que Kairos
    // pueda leer (confirmado: "grep -i token" sobre el log de sesión real no encuentra nada),
    // así que gatewayUrl() (abajo) siempre encontraba el campo vacío pese a que el gateway
    // arrancaba "ready" sin errores — exactamente el síntoma reportado ("dice iniciado pero no
    // sale nada"). Fix real: pre-sembrar nosotros mismos "gateway.auth.mode=token" +
    // "gateway.auth.token=<hex aleatorio>" ANTES de arrancar, como indica el propio hint del
    // log — así el gateway usa y persiste ESE token en vez de generar uno nuevo por sesión.
    fun ensureGatewayModeLocal(): Boolean {
        val cfgFile = configFile
        return try {
            val cfg = if (cfgFile.exists()) JSONObject(cfgFile.readText()) else JSONObject()
            val gateway = cfg.optJSONObject("gateway") ?: JSONObject().also { cfg.put("gateway", it) }
            var changed = !cfgFile.exists()
            if (gateway.optString("mode") != "local") {
                gateway.put("mode", "local")
                changed = true
            }
            val auth = gateway.optJSONObject("auth") ?: JSONObject().also { gateway.put("auth", it) }
            if (auth.optString("mode") != "token") {
                auth.put("mode", "token")
                changed = true
            }
            if (auth.optString("token").isBlank()) {
                auth.put("token", java.security.SecureRandom().let { rnd ->
                    ByteArray(24).also { rnd.nextBytes(it) }.joinToString("") { "%02x".format(it) }
                })
                changed = true
            }
            if (changed) {
                cfgFile.parentFile?.mkdirs()
                val tmp = File(cfgFile.parentFile, "openclaw.json.tmp")
                tmp.writeText(cfg.toString(2))
                tmp.renameTo(cfgFile)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    fun gatewayStart(): JSONObject {
        ensureGatewayModeLocal()
        val script = File(home, "scripts/openclaw/openclaw_start.sh")
        if (!script.exists()) {
            return JSONObject().put("ok", false).put("error", "Script start no encontrado")
        }
        // Bug real (2026-08-06, ver docs/humano/humano86.md): el health-check propio del
        // script (ver openclaw.sh) tenía una ventana insuficiente para el primer arranque
        // real de Node — ya subida a ~45s ahí. Pero acá además había un bug de fiabilidad
        // aparte: se ignoraba el exit code real del script (rc descartado con "_") y se
        // reportaba éxito con tmuxHas("openclaw") como fallback — la sesión tmux persiste
        // aunque el proceso interno haya crasheado (tmux corre un shell, no el comando
        // directamente), así que ese fallback podía dar un falso positivo. El timeout de
        // Kotlin (60s) también se sube para dar margen real a la ventana de 45s del script
        // + overhead de arranque de tmux/pkill/sleep.
        val timeout = 90L
        val (rc, out, _) = ManagerNativeUtils.runExec(listOf(TERMUX_BASH_PATH, script.absolutePath), timeout)
        return if (rc == 0 || ManagerNativeUtils.checkPort(PORT)) {
            JSONObject().put("ok", true).put("message", "Gateway iniciado").put("port", PORT)
        } else {
            JSONObject().put("ok", false).put("error", "Falló").put("output", out)
        }
    }

    fun gatewayStop(): JSONObject {
        val script = File(home, "scripts/openclaw/openclaw_stop.sh")
        if (script.exists()) {
            ManagerNativeUtils.runExec(listOf(TERMUX_BASH_PATH, script.absolutePath), 10)
        } else {
            ManagerNativeUtils.runExec(listOf("pkill", "-9", "-f", "openclaw"), 5)
            ManagerNativeUtils.runExec(listOf("tmux", "kill-session", "-t", "openclaw"), 5)
        }
        return JSONObject().put("ok", true).put("message", "Gateway detenido")
    }

    fun gatewayUrl(): JSONObject {
        // Bug real (2026-08-07, ver docs/humano/humano88.md): el token NUNCA se imprime en
        // "openclaw gateway run" (runtime.log, lo que este método leía) — se genera y
        // guarda una sola vez durante "openclaw onboard" (una sesión de terminal separada
        // que Kairos no captura a ningún log), y vive en el config real bajo
        // "gateway.auth.token" (confirmado leyendo GatewayConfig.swift del proyecto real en
        // referencia/interfaz/openclaw-android-assistant-main/). Por eso la URL nunca traía token —
        // el regex sobre el log jamás encontraba nada que buscar. Ahora se lee del config;
        // el log queda como fallback por si alguna versión vieja sí llegó a loguearlo.
        var token = ""
        val cfgFile = File(home, ".openclaw/openclaw.json")
        if (cfgFile.exists()) {
            try {
                val cfg = JSONObject(cfgFile.readText())
                token = cfg.optJSONObject("gateway")?.optJSONObject("auth")?.optString("token", "") ?: ""
            } catch (_: Exception) { }
        }
        if (token.isEmpty()) {
            val log = File(home, "openclaw-logs/runtime.log")
            if (log.exists()) {
                // Equivalente a `grep -o 'token=[a-f0-9]*' log | tail -1 | cut -d= -f2`:
                // se queda con la ÚLTIMA coincidencia del archivo.
                val regex = Regex("""token=([a-f0-9]+)""")
                try {
                    log.forEachLine { line -> regex.find(line)?.let { token = it.groupValues[1] } }
                } catch (_: Exception) { }
            }
        }
        var url = "http://localhost:$PORT/"
        if (token.isNotEmpty()) url += "#token=$token"
        return JSONObject().apply {
            put("ok", true)
            put("url", url)
            put("token", token)
            put("running", ManagerNativeUtils.checkPort(PORT))
        }
    }

    // Ruta real del config de OpenClaw (confirmada leyendo OpenClawPaths.swift del proyecto
    // real en referencia/interfaz/openclaw-android-assistant-main/, ver docs/humano/humano88.md):
    // "~/.openclaw/openclaw.json", NO "config.json" — este bug hacía que la app nunca
    // encontrara el config real aunque el usuario ya hubiera completado el onboarding. Las
    // rutas viejas quedan como fallback por si alguna versión anterior de openclaw las usó.
    fun providersList(): JSONObject {
        val paths = listOf(
            File(home, ".openclaw/openclaw.json"),
            File(home, ".openclaw/config.json"),
            File(home, ".config/openclaw/config.json"),
        )
        for (cp in paths) {
            if (cp.exists()) {
                try {
                    val cfg = JSONObject(cp.readText())
                    return JSONObject().put("ok", true).put("config", cfg)
                } catch (_: Exception) {
                    // Config corrupto/ilegible — intenta la siguiente ruta, igual que el original.
                }
            }
        }
        return JSONObject().put("ok", true).put("config", JSONObject.NULL)
            .put("message", "Sin config — ejecuta onboard primero")
    }

    fun ollamaModels(): JSONObject {
        return try {
            val url = URL("http://127.0.0.1:11434/api/tags")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            val text = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            val data = JSONObject(text)
            val modelsArr = data.optJSONArray("models") ?: JSONArray()
            val out = JSONArray()
            for (i in 0 until modelsArr.length()) {
                val m = modelsArr.getJSONObject(i)
                out.put(JSONObject().put("name", m.optString("name")).put("size", m.optLong("size", 0)))
            }
            JSONObject().put("ok", true).put("models", out)
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", "Ollama no disponible: ${e.message}")
        }
    }

    // ── Proveedores IA (2026-08-07, ver docs/humano/humano91.md) ──────────────────────
    // Bug real: "Proveedor IA / Modelo" era de solo lectura (showProviders() solo mostraba
    // el JSON) — el TUI real (_submenu_cl_proveedor, termux-ai-stack-dev/scripts/
    // menu_nativo.sh:4431-4739) tiene 3 acciones que escriben el config de verdad
    // ("Configurar Ollama local", "Proveedor personalizado", "Restaurar backup") sin
    // ningún equivalente en la app — sin terminal cruda no había forma de cambiar de
    // proveedor. Portado 1:1 a Kotlin nativo (misma estructura JSON exacta que el Python
    // embebido del TUI: models.providers.<name>, agents.defaults.models/model.primary),
    // con backup automático antes de escribir (mismo prefijo "openclaw.json.pre-<label>-
    // <timestamp>" que ya usa _cl_cfg_backup() del TUI, para que "Restaurar backup" real
    // los pueda ver).

    private val configFile get() = File(home, ".openclaw/openclaw.json")

    private fun backupConfigFile(label: String) {
        val cfg = configFile
        if (!cfg.exists()) return
        try {
            val ts = System.currentTimeMillis() / 1000
            File(cfg.parentFile, "openclaw.json.pre-$label-$ts").also { cfg.copyTo(it, overwrite = true) }
        } catch (_: Exception) { }
    }

    private fun isoTimestampNow(): String {
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
        fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return fmt.format(java.util.Date())
    }

    // Mismo criterio de contexto por familia de modelo que el TUI (case "$_OL_MOD" in
    // qwen3*|qwen2.5*|llama3*|gemma3*|phi*) 131072 ;; deepseek*) 65536 ;; default 32768).
    private fun contextWindowFor(modelId: String): Int {
        val m = modelId.lowercase()
        return when {
            m.startsWith("qwen3") || m.startsWith("qwen2.5") || m.startsWith("llama3") ||
                m.startsWith("gemma3") || m.startsWith("phi") -> 131072
            m.startsWith("deepseek") -> 65536
            else -> 32768
        }
    }

    private fun writeProviderEntry(
        providerName: String, baseUrl: String, apiKey: String,
        modelId: String, contextWindow: Int, makePrimary: Boolean, backupLabel: String
    ): JSONObject {
        if (!configFile.exists()) {
            return JSONObject().put("ok", false).put("error", "Config no encontrado — completá el onboarding primero (openclaw onboard)")
        }
        return try {
            val json = JSONObject(configFile.readText())
            backupConfigFile(backupLabel)

            val modelEntry = JSONObject()
                .put("id", modelId).put("name", modelId).put("reasoning", false)
                .put("input", JSONArray().put("text"))
                .put("cost", JSONObject().put("input", 0).put("output", 0).put("cacheRead", 0).put("cacheWrite", 0))
                .put("contextWindow", contextWindow).put("maxTokens", 8192)
            val providerEntry = JSONObject()
                .put("baseUrl", baseUrl).put("api", "openai-completions").put("apiKey", apiKey)
                .put("models", JSONArray().put(modelEntry))

            val models = json.optJSONObject("models") ?: JSONObject().also { json.put("models", it) }
            val providers = models.optJSONObject("providers") ?: JSONObject().also { models.put("providers", it) }
            providers.put(providerName, providerEntry)

            val modelFull = "$providerName/$modelId"
            val agents = json.optJSONObject("agents") ?: JSONObject().also { json.put("agents", it) }
            val defaults = agents.optJSONObject("defaults") ?: JSONObject().also { agents.put("defaults", it) }
            val whitelist = defaults.optJSONObject("models") ?: JSONObject().also { defaults.put("models", it) }
            whitelist.put(modelFull, JSONObject())
            if (makePrimary) {
                val modelBlock = defaults.optJSONObject("model") ?: JSONObject().also { defaults.put("model", it) }
                modelBlock.put("primary", modelFull)
            }
            (json.optJSONObject("meta") ?: JSONObject().also { json.put("meta", it) }).put("lastTouchedAt", isoTimestampNow())

            val tmp = File(configFile.parentFile, "openclaw.json.tmp")
            tmp.writeText(json.toString(2))
            if (!tmp.renameTo(configFile)) throw java.io.IOException("No se pudo reemplazar el config")

            JSONObject().put("ok", true).put("message",
                "$providerName/$modelId configurado" + if (makePrimary) " (modelo principal)" else "")
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message ?: "error desconocido")
        }
    }

    fun providersConfigureOllama(modelId: String, makePrimary: Boolean): JSONObject =
        writeProviderEntry("ollama", "http://127.0.0.1:11434/v1", "ollama-local", modelId, contextWindowFor(modelId), makePrimary, "ollama")

    fun providersConfigureCustom(
        providerName: String, baseUrl: String, apiKey: String, modelId: String, contextWindow: Int, makePrimary: Boolean
    ): JSONObject = writeProviderEntry(providerName, baseUrl, apiKey, modelId, contextWindow, makePrimary, "custom-$providerName")

    // ── Canales de mensajería (channels.<provider>, ver docs/modulos/OPENCLAW.md sección 8,
    // ronda 2026-08-25) — Discord/Telegram/WhatsApp/Slack confirmados reales contra
    // docs.openclaw.ai/gateway/config-channels; SMS NO es un canal real (la descripción vieja
    // de modules.json estaba mal, no se agrega acá). Mismo patrón de lectura/escritura que
    // writeProviderEntry() de arriba (backup automático antes de escribir, escritura atómica
    // vía archivo .tmp + rename) — schema mínimo real por canal según esa doc es
    // {"enabled": bool, "token": "..."} (token del bot/API del proveedor); si un proveedor
    // puntual necesita campos extra (multi-cuenta, mention gating, control de acceso) hay
    // que editarlos a mano en el JSON — este editor cubre el caso común de "activar un canal
    // con su token", no el schema avanzado completo de cada proveedor.
    fun channelsList(): JSONObject {
        val cfg = configFile
        if (!cfg.exists()) return JSONObject().put("ok", false).put("error", "Config no encontrado — completá el onboarding primero (openclaw onboard)")
        return try {
            val json = JSONObject(cfg.readText())
            val channels = json.optJSONObject("channels") ?: JSONObject()
            JSONObject().put("ok", true).put("channels", channels)
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message ?: "error desconocido")
        }
    }

    fun writeChannelEntry(providerName: String, token: String, enabled: Boolean): JSONObject {
        if (!configFile.exists()) {
            return JSONObject().put("ok", false).put("error", "Config no encontrado — completá el onboarding primero (openclaw onboard)")
        }
        return try {
            val json = JSONObject(configFile.readText())
            backupConfigFile("channel-$providerName")

            val channels = json.optJSONObject("channels") ?: JSONObject().also { json.put("channels", it) }
            val entry = channels.optJSONObject(providerName) ?: JSONObject()
            entry.put("enabled", enabled)
            if (token.isNotBlank()) entry.put("token", token)
            channels.put(providerName, entry)
            (json.optJSONObject("meta") ?: JSONObject().also { json.put("meta", it) }).put("lastTouchedAt", isoTimestampNow())

            val tmp = File(configFile.parentFile, "openclaw.json.tmp")
            tmp.writeText(json.toString(2))
            if (!tmp.renameTo(configFile)) throw java.io.IOException("No se pudo reemplazar el config")

            JSONObject().put("ok", true).put("message", "Canal $providerName ${if (enabled) "activado" else "desactivado"}")
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message ?: "error desconocido")
        }
    }

    fun channelDelete(providerName: String): JSONObject {
        if (!configFile.exists()) {
            return JSONObject().put("ok", false).put("error", "Config no encontrado")
        }
        return try {
            val json = JSONObject(configFile.readText())
            val channels = json.optJSONObject("channels")
            if (channels == null || !channels.has(providerName)) {
                return JSONObject().put("ok", true).put("message", "El canal ya no estaba configurado")
            }
            backupConfigFile("channel-$providerName-delete")
            channels.remove(providerName)

            val tmp = File(configFile.parentFile, "openclaw.json.tmp")
            tmp.writeText(json.toString(2))
            if (!tmp.renameTo(configFile)) throw java.io.IOException("No se pudo reemplazar el config")

            JSONObject().put("ok", true).put("message", "Canal $providerName eliminado")
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message ?: "error desconocido")
        }
    }

    fun providersListBackups(): JSONObject {
        val dir = configFile.parentFile ?: return JSONObject().put("ok", true).put("backups", JSONArray())
        val files = dir.listFiles { f -> f.name.startsWith("openclaw.json.pre-") || f.name == "openclaw.json.bak" }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
        val arr = JSONArray()
        for (f in files.take(10)) arr.put(JSONObject().put("name", f.name).put("path", f.absolutePath).put("size", f.length()))
        return JSONObject().put("ok", true).put("backups", arr)
    }

    // ── Memoria persistente de Engram vía MCP real (2026-08-17) ────────────────────────
    // Engram (modulos/engram.sh) no reconoce "openclaw" como slug de `engram setup <agent>`
    // (ver comentario de BaseModuleFragment.engramSetupButton()) — pero a diferencia de
    // Hermes, OpenClaw SÍ es un cliente MCP real y documentado: docs.openclaw.ai/cli/mcp
    // confirma un comando genérico `openclaw mcp add <name> --command <exe> --arg <arg>...`
    // que registra cualquier servidor MCP stdio en `mcp.servers` dentro de
    // ~/.openclaw/openclaw.json — no depende de que Engram conozca el nombre "openclaw" de
    // antemano. El subcomando real que expone Engram como servidor MCP es `engram mcp`
    // (confirmado en el mismo comentario de BaseModuleFragment: "el CLI lanza `engram mcp`
    // solo, como subproceso stdio corto en cada sesión"), así que el comando exacto es
    // `openclaw mcp add engram --command engram --arg mcp`. A diferencia de `engram setup`,
    // esto no requiere que el gateway esté corriendo, pero un gateway YA corriendo necesita
    // reiniciarse para levantar el nuevo servidor (confirmado en la doc: "reload" solo
    // afecta al proceso CLI actual, no a un gateway ya vivo en otro proceso).
    fun mcpConnectEngram(): JSONObject {
        val (rc, out, err) = ManagerNativeUtils.runExec(
            listOf("openclaw", "mcp", "add", "engram", "--command", "engram", "--arg", "mcp"), 20
        )
        if (rc != 0) {
            return JSONObject().put("ok", false).put("error", err.ifBlank { out.ifBlank { "openclaw mcp add falló (rc=$rc)" } })
        }
        return JSONObject().put("ok", true)
            .put("message", "Engram conectado vía MCP")
            .put("gateway_running", ManagerNativeUtils.tmuxHas("openclaw") || ManagerNativeUtils.checkPort(PORT))
    }

    fun providersRestoreBackup(backupPath: String): JSONObject {
        val backup = File(backupPath)
        if (!backup.exists()) return JSONObject().put("ok", false).put("error", "Backup no encontrado")
        return try {
            // Mismo chequeo que el TUI: validar que el backup es JSON válido antes de
            // restaurar, para no dejar el config real roto si el backup está corrupto.
            JSONObject(backup.readText())
            if (configFile.exists()) configFile.copyTo(File(configFile.parentFile, "openclaw.json.bak"), overwrite = true)
            backup.copyTo(configFile, overwrite = true)
            JSONObject().put("ok", true).put("message", "Config restaurado desde ${backup.name}")
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", "Backup inválido o error al restaurar: ${e.message}")
        }
    }
}

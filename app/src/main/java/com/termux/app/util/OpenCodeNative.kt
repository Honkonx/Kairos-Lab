package com.termux.app.util

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Migración nativa de cmd_opencode (kairos_manager.py) — SOLO la parte que no es
 * proyectos (esa sigue en Python, ver _handle_project_actions sobre ~/proyectos/,
 * prefijo project_origin — mismo alcance/riesgo que Claude/OpenClaw, se migra
 * aparte). Estas acciones eran wrappers finos sobre tmux/archivos/HTTP (versión,
 * servidor web vía tmux con reintentos, config JSON de Ollama, listado de modelos)
 * que no necesitaban un intérprete Python en el medio — mismo criterio ya aplicado
 * a cmd_openclaw (ver OpenClawNative.kt).
 */
object OpenCodeNative {

    private val home get() = ManagerNativeUtils.home
    private val webLog get() = File(home, ".opencode_web.log")
    private val configFile get() = File(home, ".config/opencode/opencode.json")

    // Equivalente a `grep -o 'http://127\.0\.0\.1:[0-9]*/[^ ]*' log | head -1`: se lee el
    // archivo directo en vez de invocar grep por subproceso, mismo resultado sin el fork extra.
    private val URL_REGEX = Regex("""http://127\.0\.0\.1:\d+/\S*""")

    // Bug real (ver docs/humano* de esta ronda): el botón "Servidor web :4096" usaba la
    // MISMA sesión tmux fija "opencode" que la que abre el botón ":3000" (ModuleController +
    // scripts/opencode/opencode_start.sh, ver ModuleController.getTmuxSession("opencode")).
    // Resultado: iniciar el de :4096 hacía que ModuleController.isRunning("opencode")
    // devolviera true para el de :3000 (mismo nombre de sesión) aunque nada estuviera
    // escuchando ahí — el botón ":3000" se pintaba "activo" y navegaba a una URL muerta.
    // Cada puerto ahora tiene su propia sesión tmux y su propio log, así un puerto nunca
    // puede hacerse pasar por el otro. Puerto 3000 mantiene el nombre "opencode" a secas
    // por compatibilidad con ModuleController (que sigue siendo el dueño de ese puerto).
    private fun sessionFor(port: Int): String = if (port == 3000) "opencode" else "opencode-$port"

    private fun logFor(port: Int): File =
        if (port == 3000) webLog else File(home, ".opencode_web_$port.log")

    private fun extractUrlFromLog(log: File = webLog): String? {
        if (!log.exists()) return null
        return try {
            URL_REGEX.find(log.readText())?.value
        } catch (_: Exception) {
            null
        }
    }

    fun info(): JSONObject {
        val reg = ManagerNativeUtils.readRegistry()
        var version = reg["opencode.version"] ?: ""
        if (version.isBlank()) {
            // Bug real confirmado por ADB (2026-08-24, ver docs/humano222.md): nombre relativo,
            // mismo patrón que Hermes/vncserver/udocker — ruta absoluta confirmada instalando
            // opencode.sh de verdad en el dispositivo (ver TERMUX_OPENCODE_PATH).
            val (rc, out, _) = ManagerNativeUtils.runExec(listOf(TERMUX_OPENCODE_PATH, "--version"), 10)
            if (rc == 0) version = Regex("""\d+\.\d+\.\d+""").find(out)?.value ?: ""
        }
        val webOn = ManagerNativeUtils.tmuxHas("opencode")
        val webUrl = if (webOn) extractUrlFromLog() ?: "" else ""
        return JSONObject().apply {
            put("ok", true)
            put("version", version)
            put("installed", version.isNotBlank() || reg["opencode.installed"] == "true")
            put("web_running", webOn)
            put("web_url", webUrl)
        }
    }

    /**
     * Arranca `opencode web --port <port>` en una sesión tmux "opencode" con log a
     * ~/.opencode_web.log, esperando hasta 16 intentos de 0.5s a que la URL aparezca en
     * el log — mismo loop que el original en Python (time.sleep(0.5) x16).
     *
     * [cwd] (2026-08-26, mockup de "Abrir TUI en carpeta" aprobado por el usuario — ver
     * docs/estructura/ABRIR_TUI_EN_CARPETA_2026-08-26.md): ruta absoluta opcional, antepone
     * "cd '<cwd>' &&" al comando de tmux. Parámetro con default `null` a propósito — no rompe
     * el único call-site existente (OpenCodeFragment.onWebServerSwitchToggled), que sigue
     * pudiendo llamar webStart(port) sin cwd para el comportamiento de siempre (arranca en ~).
     */
    fun webStart(port: Int = 3000, cwd: String? = null): JSONObject {
        val session = sessionFor(port)
        val log = logFor(port)
        if (ManagerNativeUtils.tmuxHas(session)) {
            val url = extractUrlFromLog(log) ?: ""
            return JSONObject().put("ok", true).put("message", "Ya corriendo").put("url", url).put("port", port)
        }
        // Solo mata el proceso/sesión de ESTE puerto — pkill por patrón exacto de flag
        // (antes "opencode web" a secas mataba también instancias corriendo en otros
        // puertos al arrancar cualquiera de los dos botones).
        ManagerNativeUtils.runShell("pkill -f \"opencode web --port $port \" 2>/dev/null", 5)
        ManagerNativeUtils.runShell("tmux kill-session -t $session 2>/dev/null", 5)
        log.delete()
        val cdPrefix = if (cwd.isNullOrBlank()) "" else "cd ${shellQuote(cwd)} && "
        ManagerNativeUtils.runShell(
            "tmux new-session -d -s $session \"${cdPrefix}opencode web --port $port --hostname 127.0.0.1 2>&1 | tee '${log.absolutePath}'\"",
            10
        )
        var url = ""
        var attempts = 0
        while (url.isBlank() && attempts < 16) {
            Thread.sleep(500)
            url = extractUrlFromLog(log) ?: ""
            attempts++
        }
        return if (ManagerNativeUtils.tmuxHas(session)) {
            JSONObject().put("ok", true).put("message", "Iniciado").put("url", url).put("port", port)
        } else {
            JSONObject().put("ok", false).put("error", "Falló")
        }
    }

    fun webStop(port: Int = 3000): JSONObject {
        val session = sessionFor(port)
        ManagerNativeUtils.runShell("tmux kill-session -t $session 2>/dev/null", 5)
        ManagerNativeUtils.runShell("pkill -f \"opencode web --port $port \" 2>/dev/null", 5)
        return JSONObject().put("ok", true).put("message", "Detenido")
    }

    /**
     * Bug real: "Detener servidor" (DANGER, único botón de stop del submenú) solo mataba la
     * sesión tmux "opencode" (puerto 3000, vía ModuleController/opencode_stop.sh) — si el
     * usuario había iniciado el servidor en :4096, esa sesión ("opencode-4096") quedaba
     * corriendo para siempre sin ninguna forma de detenerla desde la UI. Enumera TODAS las
     * sesiones tmux que empiecen con "opencode" (cualquier puerto) y las mata, más un pkill
     * genérico como red de seguridad para procesos `opencode web` huérfanos de una sesión ya
     * cerrada. Usado por OpenCodeFragment además de (no en vez de) ModuleController.stopModule
     * — cubre dispositivos con un opencode_stop.sh viejo ya instalado que no lo hace.
     */
    fun stopAll(): JSONObject {
        val (_, sessionsOut, _) = ManagerNativeUtils.runExec(
            listOf("tmux", "list-sessions", "-F", "#{session_name}"), 5
        )
        val killed = mutableListOf<String>()
        sessionsOut.lineSequence()
            .map { it.trim() }
            .filter { it == "opencode" || it.startsWith("opencode-") }
            .forEach { session ->
                ManagerNativeUtils.runExec(listOf("tmux", "kill-session", "-t", session), 5)
                killed.add(session)
            }
        ManagerNativeUtils.runShell("pkill -f 'opencode web' 2>/dev/null", 5)
        return JSONObject().put("ok", true).put("message", "Detenido").put("sessions", JSONArray(killed))
    }

    fun webUrl(port: Int = 3000): JSONObject {
        val log = logFor(port)
        if (!log.exists()) {
            return JSONObject().put("ok", true).put("url", JSONObject.NULL).put("running", false)
        }
        val url = extractUrlFromLog(log)
        return JSONObject().put("ok", true)
            .put("url", url ?: JSONObject.NULL)
            .put("running", ManagerNativeUtils.tmuxHas(sessionFor(port)))
    }

    /** path: ruta absoluta opcional — equivalente a `p[0] if p else HOME` en cmd_opencode tui-cmd. */
    fun tuiCmd(path: String? = null): JSONObject =
        JSONObject().put("ok", true).put("command", "cd ${shellQuote(path ?: home)} && opencode .")

    fun ollamaConfig(model: String): JSONObject {
        return try {
            configFile.parentFile?.mkdirs()
            val provider = JSONObject()
                .put("npm", "@ai-sdk/openai-compatible")
                .put("name", "Ollama (local)")
                .put("options", JSONObject().put("baseURL", "http://127.0.0.1:11434/v1").put("apiKey", "ollama"))
                .put("models", JSONObject().put(model, JSONObject().put("name", model)))
            val cfg = JSONObject()
                .put("\$schema", "https://opencode.ai/config.json")
                .put("model", "ollama/$model")
                .put("provider", JSONObject().put("ollama", provider))
            configFile.writeText(cfg.toString(2) + "\n")
            JSONObject().put("ok", true).put("message", "Ollama: $model")
                .put("ollama_running", ManagerNativeUtils.checkPort(11434))
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message ?: "error desconocido")
        }
    }

    fun ollamaRemoveConfig(): JSONObject {
        if (configFile.exists()) configFile.delete()
        return JSONObject().put("ok", true).put("message", "Config eliminado")
    }

    /**
     * Equivalente a [ollamaConfig] pero apuntando al llama-server local en vez de Ollama —
     * rama "llama-server-and-terminal-ux" (2026-08-05, ver docs/humano/humano71.md). El
     * mecanismo baseURL genérico compatible OpenAI es el MISMO, solo cambia el puerto/nombre
     * — confirmado que OpenCode no está atado a Ollama específicamente. `modelLabel` es
     * cosmético (OpenCode no valida el nombre contra el servidor real, solo lo etiqueta).
     */
    fun llamaServerConfig(modelLabel: String): JSONObject {
        return try {
            configFile.parentFile?.mkdirs()
            val provider = JSONObject()
                .put("npm", "@ai-sdk/openai-compatible")
                .put("name", "llama-server (local)")
                .put("options", JSONObject().put("baseURL", "http://127.0.0.1:8085/v1").put("apiKey", "llamaserver"))
                .put("models", JSONObject().put(modelLabel, JSONObject().put("name", modelLabel)))
            val cfg = JSONObject()
                .put("\$schema", "https://opencode.ai/config.json")
                .put("model", "llamaserver/$modelLabel")
                .put("provider", JSONObject().put("llamaserver", provider))
            configFile.writeText(cfg.toString(2) + "\n")
            JSONObject().put("ok", true).put("message", "llama-server: $modelLabel")
                .put("llamaserver_running", ManagerNativeUtils.checkPort(8085))
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message ?: "error desconocido")
        }
    }

    /**
     * Lee los servidores MCP configurados directo de `~/.config/opencode/opencode.json` —
     * reemplaza abrir la terminal solo para VER una lista (pedido explícito del usuario,
     * ver docs/arquitectura/AUDITORIA_PANEL_MCP_UI_2026-08-19.md). Schema confirmado
     * contra opencode.ai/docs/mcp-servers (2026-08-19): clave top-level "mcp", objeto
     * nombre → { "type": "local"|"remote", "command": [...] (local) | "url": "..."
     * (remote), "enabled": bool }. A diferencia de Claude Code, acá SÍ se expone
     * habilitar/deshabilitar (ver [setMcpServerEnabled]) — el campo "enabled" es un bool
     * plano documentado del propio schema, no una suposición sobre un formato ajeno.
     */
    fun mcpServers(): JSONObject {
        return try {
            if (!configFile.isFile) {
                return JSONObject().put("ok", true).put("servers", JSONArray())
                    .put("configPath", configFile.absolutePath).put("configExists", false)
            }
            val root = JSONObject(configFile.readText())
            val servers = JSONArray()
            root.optJSONObject("mcp")?.let { mcp ->
                mcp.names()?.let { names ->
                    for (i in 0 until names.length()) {
                        val name = names.getString(i)
                        val cfg = mcp.optJSONObject(name) ?: JSONObject()
                        val type = cfg.optString("type", "local")
                        val transport = if (type == "remote") {
                            cfg.optString("url")
                        } else {
                            val cmd = cfg.optJSONArray("command")
                            if (cmd != null) (0 until cmd.length()).joinToString(" ") { cmd.optString(it) } else "—"
                        }
                        servers.put(
                            JSONObject().put("name", name).put("type", type).put("transport", transport)
                                .put("enabled", cfg.optBoolean("enabled", true))
                        )
                    }
                }
            }
            JSONObject().put("ok", true).put("servers", servers)
                .put("configPath", configFile.absolutePath).put("configExists", true)
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message ?: "error desconocido")
                .put("configPath", configFile.absolutePath)
        }
    }

    /**
     * Detalle completo de un servidor MCP puntual — comando, args por separado y variables
     * de entorno (clave "environment" del schema real, confirmado contra
     * opencode.ai/docs/mcp-servers 2026-08-19: `{"type": "local", "command": [...],
     * "environment": {"KEY": "value"}, "enabled": bool}` para local; `{"type": "remote",
     * "url": "...", "headers": {...}, "oauth": bool, "enabled": bool}` para remote).
     * [mcpServers] ya devuelve un resumen (comando+args aplanado en "transport") — este
     * método existe porque el panel de listado no tenía forma de mostrar env vars sin
     * abrir el archivo entero en el editor de texto; ronda de continuación de
     * AUDITORIA_MODULOS_IA_DEV_VS_OFICIAL_2026-08-19.md ("panel de CONFIGURACIÓN de
     * servidores MCP [...] ver detalle completo de cada uno: comando, args, env vars").
     */
    fun mcpServerDetail(name: String): JSONObject {
        return try {
            if (!configFile.isFile) return JSONObject().put("ok", false).put("error", "opencode.json no existe todavía")
            val root = JSONObject(configFile.readText())
            val mcp = root.optJSONObject("mcp")
                ?: return JSONObject().put("ok", false).put("error", "No hay servidores MCP configurados")
            val cfg = mcp.optJSONObject(name)
                ?: return JSONObject().put("ok", false).put("error", "Servidor '$name' no encontrado")
            val type = cfg.optString("type", "local")
            val result = JSONObject().put("ok", true).put("name", name).put("type", type)
                .put("enabled", cfg.optBoolean("enabled", true))
            if (type == "remote") {
                result.put("url", cfg.optString("url", "—"))
                result.put("oauth", cfg.optBoolean("oauth", false))
                cfg.optJSONObject("headers")?.let { result.put("headers", it) }
            } else {
                val cmdArr = cfg.optJSONArray("command")
                val cmdList = JSONArray()
                if (cmdArr != null) for (i in 0 until cmdArr.length()) cmdList.put(cmdArr.optString(i))
                result.put("command", cmdList)
                cfg.optJSONObject("environment")?.let { result.put("environment", it) }
            }
            result
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message ?: "error desconocido")
        }
    }

    /**
     * Flip del campo "enabled" de un servidor MCP ya configurado — única acción de
     * escritura del panel nativo de MCP (ver BaseModuleFragment.showMcpPanel). No crea
     * servidores nuevos ni cambia su comando/URL, solo el bool de habilitado/deshabilitado
     * que el propio schema de OpenCode ya soporta — alcance acotado a propósito.
     */
    fun setMcpServerEnabled(name: String, enabled: Boolean): JSONObject {
        return try {
            if (!configFile.isFile) return JSONObject().put("ok", false).put("error", "opencode.json no existe todavía")
            val root = JSONObject(configFile.readText())
            val mcp = root.optJSONObject("mcp")
                ?: return JSONObject().put("ok", false).put("error", "No hay servidores MCP configurados")
            val cfg = mcp.optJSONObject(name)
                ?: return JSONObject().put("ok", false).put("error", "Servidor '$name' no encontrado")
            cfg.put("enabled", enabled)
            mcp.put(name, cfg)
            root.put("mcp", mcp)
            configFile.writeText(root.toString(2) + "\n")
            JSONObject().put("ok", true).put("message", if (enabled) "Habilitado" else "Deshabilitado")
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message ?: "error desconocido")
        }
    }

    // Reusa OllamaApiClient (mismo cliente HTTP que ChatFragment/OllamaFragment) en vez de
    // duplicar la llamada a /api/tags — el pedido explícito del usuario fue "por que si es
    // ajustar un json" para ollama-config; esto extiende ese mismo razonamiento al listado.
    fun ollamaModels(): JSONObject {
        return try {
            val models = OllamaApiClient.listModels()
            val arr = JSONArray()
            models.forEach { arr.put(JSONObject().put("name", it.name)) }
            JSONObject().put("ok", true).put("models", arr)
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", "Ollama no disponible: ${e.message}")
        }
    }
}

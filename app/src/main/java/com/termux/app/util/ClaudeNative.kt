package com.termux.app.util

import org.json.JSONObject
import java.io.File

/**
 * Migración nativa de cmd_claude (kairos_manager.py) — SOLO detección de método de
 * instalación (native/legacy/broken/none) y armado del comando de apertura en
 * terminal. Las acciones projects-* siguen en Python vía _handle_project_actions
 * (symlinks + file-locking + registry, compartido con OpenCode/OpenClaw) — mismo
 * criterio ya aplicado a cmd_openclaw (ver OpenClawNative.kt): esta parte era
 * detección de archivos + regex sobre la salida de --version, sin necesidad real
 * de un intérprete Python en el medio.
 */
object ClaudeNative {

    private const val PREFIX = "/data/data/com.termux/files/usr"
    private val home get() = ManagerNativeUtils.home

    private val nativeBin get() = File(home, ".local/share/claude-code/claude")
    private val nativeWrap get() = File(home, ".local/bin/claude")
    private val legacyWrap get() = File(PREFIX, "bin/claude")

    // CLAUDE_CODE_OAUTH_TOKEN (2026-08-25, plan aprobado en docs/modulos/CLAUDE_CODE.md) —
    // token OAuth de larga duración generado a mano por el usuario con "claude setup-token"
    // (requiere Pro/Max/Team/Enterprise). Confirmado contra code.claude.com/docs/en/authentication:
    // cuando esta variable está seteada, Claude Code la usa en silencio en vez de las
    // credenciales de sesión (~/.claude/.credentials.json) — así el CLI siempre abre con la
    // cuenta de ESE token, no la del login interactivo. Mismo patrón de archivo que
    // N8nFragment.writeCfToken()/RemoteManager.CF_SSH_TOKEN_FILE (chmod 600, borrar = volver al
    // comportamiento normal).
    private val oauthTokenFile get() = File(home, ".claude_oauth_token")
    private const val OAUTH_BASHRC_MARKER = "# Claude Code OAuth token fijo (Kairos)"

    fun hasOAuthToken(): Boolean = oauthTokenFile.isFile && oauthTokenFile.length() > 0

    fun writeOAuthToken(token: String): Boolean {
        return try {
            oauthTokenFile.writeText(token.trim())
            ManagerNativeUtils.runShell("chmod 600 '${oauthTokenFile.absolutePath}'", 5)
            val bashrc = File(home, ".bashrc")
            val existing = if (bashrc.isFile) bashrc.readText() else ""
            if (!existing.contains(OAUTH_BASHRC_MARKER)) {
                bashrc.appendText(
                    "\n$OAUTH_BASHRC_MARKER\n" +
                        "export CLAUDE_CODE_OAUTH_TOKEN=\"\$(cat ~/.claude_oauth_token 2>/dev/null)\"\n"
                )
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    fun clearOAuthToken(): Boolean {
        return try {
            oauthTokenFile.delete()
            val bashrc = File(home, ".bashrc")
            if (bashrc.isFile) {
                val kept = bashrc.readLines().filterNot {
                    it.contains(OAUTH_BASHRC_MARKER) || it.contains("CLAUDE_CODE_OAUTH_TOKEN")
                }
                bashrc.writeText(kept.joinToString("\n") + "\n")
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    // Antepuesto a TODO comando armado por openCmd() (terminal y captura en background por
    // igual) — así cubre los 2 caminos reales documentados en el plan: terminal interactiva
    // (que además ya lo tiene vía .bashrc, esto es refuerzo) y las llamadas en background de
    // ManagerNativeUtils.runShell() (bash -c no interactivo, NO sourcea .bashrc — mismo gotcha
    // ya confirmado hoy con DISABLE_AUTOUPDATER/DISABLE_UPDATES un poco más abajo).
    private fun oauthTokenPrefix(): String =
        if (hasOAuthToken()) "export CLAUDE_CODE_OAUTH_TOKEN=\"\$(cat '${oauthTokenFile.absolutePath}')\"; " else ""

    private fun npmRootGlobal(): String? {
        val (rc, out, _) = ManagerNativeUtils.runShell("npm root -g 2>/dev/null", 5)
        return if (rc == 0 && out.isNotBlank()) out else null
    }

    private fun detectMethod(): String {
        if (nativeBin.isFile && nativeBin.canExecute()) return "native"
        val npmRoot = npmRootGlobal()
        if (npmRoot != null && File(npmRoot, "@anthropic-ai/claude-code/cli.js").isFile) return "legacy"
        if (legacyWrap.isFile) return "broken"
        return "none"
    }

    private fun findCli(): String? {
        val candidates = mutableListOf<String>()
        npmRootGlobal()?.let { candidates.add("$it/@anthropic-ai/claude-code/cli.js") }
        candidates.add("$PREFIX/lib/node_modules/@anthropic-ai/claude-code/cli.js")
        candidates.add("$home/.npm-global/lib/node_modules/@anthropic-ai/claude-code/cli.js")
        return candidates.firstOrNull { File(it).isFile }
    }

    /**
     * project: ruta absoluta opcional — equivalente a `p[0]` en cmd_claude open-cmd.
     * extraArgs (2026-08-17): argv adicional ya armado y escapado por el caller (ej.
     * `-p 'prompt' --model sonnet`, `--continue`, `--resume`, `mcp list`) — confirmado contra
     * code.claude.com/docs/en/cli-reference (headless `-p`/`--print`, `--continue`/`-c`,
     * `--resume`/`-r`, `--model`, subcomando `claude mcp list`). Se concatena DESPUÉS del
     * binario/cli.js y ANTES del `cd` — así el mismo comando final funciona para native y
     * legacy sin que ClaudeFragment tenga que conocer cuál de los dos está instalado.
     */
    fun openCmd(project: String? = null, extraArgs: String? = null): JSONObject {
        return when (val method = detectMethod()) {
            "native" -> {
                // Bug real reportado 2026-08-25 ("claude doctor" falla desde el panel — ver
                // docs/adb/AUDITORIA_MODULO_POR_MODULO_2026-08-24.md, ronda "validación real del
                // usuario"): a diferencia de la rama "legacy" de más abajo, esta rama no exportaba
                // DISABLE_AUTOUPDATER/DISABLE_UPDATES. modulos/claude.sh sí las deja en .bashrc
                // (líneas ~589-590) para uso interactivo en terminal, pero ManagerNativeUtils.runShell()
                // corre "bash -c <cmd>" no-interactivo — no sourcea .bashrc, así que ese export nunca
                // llegaba a un comando "doctor"/"auth status" lanzado desde el panel (sí llegaba
                // cuando el usuario abría Claude en una terminal real, de ahí que "funciona bien" en
                // terminal pero falle desde el panel). Se agrega inline, igual que ya hace la rama
                // legacy, para no depender de que el shell sea interactivo.
                var cmd = "${oauthTokenPrefix()}export DISABLE_AUTOUPDATER=1 DISABLE_UPDATES=1; unset LD_PRELOAD; \"${nativeWrap.absolutePath}\""
                if (!extraArgs.isNullOrBlank()) cmd += " $extraArgs"
                if (!project.isNullOrBlank()) cmd = "cd \"$project\" && $cmd"
                JSONObject().put("ok", true).put("command", cmd).put("method", "native")
            }
            "legacy" -> {
                val cli = findCli()
                if (cli == null) {
                    JSONObject().put("ok", false).put("error", "cli.js no encontrado")
                } else {
                    var cmd = "${oauthTokenPrefix()}DISABLE_AUTOUPDATER=1 DISABLE_UPDATES=1 node \"$cli\""
                    if (!extraArgs.isNullOrBlank()) cmd += " $extraArgs"
                    if (!project.isNullOrBlank()) cmd = "cd \"$project\" && $cmd"
                    JSONObject().put("ok", true).put("command", cmd).put("method", "legacy")
                }
            }
            else -> JSONObject().put("ok", false).put("error", "No instalado").put("method", method)
        }
    }

    private val claudeConfigFile get() = File(home, ".claude.json")

    /**
     * Lee los servidores MCP configurados directo del JSON real de Claude Code —
     * reemplaza el botón viejo "Ver servidores MCP" que abría la terminal para correr
     * `claude mcp list` solo para MOSTRAR una lista (pedido explícito del usuario, ver
     * docs/arquitectura/AUDITORIA_PANEL_MCP_UI_2026-08-19.md: "esa es la premisa del app,
     * poder hacer todo desde el apk de la interfaz [...] sin necesidad de abrir la
     * terminal"). Confirmado contra code.claude.com/docs/en/mcp (2026-08-19): `claude mcp
     * add` con scope por defecto ("user") escribe en `~/.claude.json` bajo la clave
     * top-level "mcpServers"; el scope "local" (el otro no-project) escribe bajo
     * `projects.<ruta absoluta>.mcpServers` del mismo archivo — acá se lee esa entrada
     * solo para el proyecto "~" (home), que es el que abre el botón "Abrir en directorio
     * raíz" de ClaudeFragment. El scope "project" (`.mcp.json` en la raíz de cada
     * proyecto individual) NO se escanea acá — limitación real y documentada: requeriría
     * iterar todos los proyectos de ProjectsManager, fuera de alcance de esta ronda
     * (YAGNI, ver clean-code-principles.md) — el botón "Copiar config"/"Abrir en editor"
     * sigue disponible para inspeccionar cualquier `.mcp.json` manualmente.
     */
    fun mcpServers(): JSONObject {
        return try {
            if (!claudeConfigFile.isFile) {
                return JSONObject().put("ok", true).put("servers", org.json.JSONArray())
                    .put("configPath", claudeConfigFile.absolutePath).put("configExists", false)
            }
            val root = JSONObject(claudeConfigFile.readText())
            val servers = org.json.JSONArray()
            root.optJSONObject("mcpServers")?.let { userScope ->
                userScope.names()?.let { names ->
                    for (i in 0 until names.length()) {
                        val name = names.getString(i)
                        servers.put(mcpServerEntry(name, userScope.optJSONObject(name) ?: JSONObject(), "usuario"))
                    }
                }
            }
            root.optJSONObject("projects")?.optJSONObject(home)?.optJSONObject("mcpServers")?.let { localScope ->
                localScope.names()?.let { names ->
                    for (i in 0 until names.length()) {
                        val name = names.getString(i)
                        servers.put(mcpServerEntry(name, localScope.optJSONObject(name) ?: JSONObject(), "local (~)"))
                    }
                }
            }
            JSONObject().put("ok", true).put("servers", servers)
                .put("configPath", claudeConfigFile.absolutePath).put("configExists", true)
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message ?: "error desconocido")
                .put("configPath", claudeConfigFile.absolutePath)
        }
    }

    private fun mcpServerEntry(name: String, cfg: JSONObject, scope: String): JSONObject {
        val type = cfg.optString("type", if (cfg.has("url")) "http" else "stdio")
        val transport = when {
            cfg.has("url") -> cfg.optString("url")
            cfg.has("command") -> {
                val args = cfg.optJSONArray("args")
                val argsText = if (args != null) (0 until args.length()).joinToString(" ") { args.optString(it) } else ""
                (cfg.optString("command") + " " + argsText).trim()
            }
            else -> "—"
        }
        return JSONObject().put("name", name).put("type", type).put("transport", transport).put("scope", scope)
    }
}

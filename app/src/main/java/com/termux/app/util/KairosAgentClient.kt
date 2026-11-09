package com.termux.app.util

import com.termux.shared.termux.TermuxConstants
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.Normalizer

/**
 * Cliente nativo del motor del Agente Kairos (`~/.kairos_agent/kairos_agent_engine.py`, instalado por
 * `modulos/kairos_agent.sh`). El agente ya NO es un módulo del catálogo ni ofrece "Abrir en terminal"
 * (orden explícita del usuario): es una pantalla propia en "Más" que conversa y
 * ejecuta acciones. El motor sigue siendo el mismo: un catálogo FIJO de intents (refrescar, procesos,
 * estado, instalar, iniciar, detener, historial); nunca ejecuta comandos libres.
 *
 * Esta clase solo habla con su CLI (`kairos-agent <intent> [args]`, una línea JSON por llamada) y
 * entiende pedidos simples en español sin necesitar IA; si el pedido no encaja se lo deja al router
 * con IA del propio motor (`kairos-agent interpretar`, llama-server/Ollama local).
 */
object KairosAgentClient {

    private val wrapper get() = File("$TERMUX_PREFIX_PATH/bin/kairos-agent")
    private val installScript get() = File(TermuxConstants.TERMUX_HOME_DIR_PATH, "scripts/install/kairos_agent.sh")

    /** Intent ya decodificada: `args` va tal cual al CLI. [mutates] = cambia el estado del dispositivo. */
    data class Parsed(val intent: String, val args: List<String>, val mutates: Boolean, val moduleName: String? = null)

    fun isInstalled(): Boolean = wrapper.exists()

    /** Instala el motor (Python stdlib + SQLite, ~1 MB). Devuelve (ok, cola de la salida). */
    fun installEngine(): Pair<Boolean, String> {
        if (!installScript.exists()) return false to "No se encontró ${installScript.absolutePath} (el APK no extrajo los scripts)"
        val (rc, out, err) = ManagerNativeUtils.runExec(listOf(TERMUX_BASH_PATH, installScript.absolutePath, "--silent"), 300)
        return (rc == 0 && isInstalled()) to (out.ifBlank { err }).takeLast(400)
    }

    /** Una llamada al CLI. Devuelve el JSON de la última línea no vacía, o null si no hubo JSON. */
    fun run(args: List<String>, timeoutSeconds: Long = 60): JSONObject? {
        val (_, out, err) = ManagerNativeUtils.runExec(listOf(wrapper.absolutePath) + args, timeoutSeconds)
        val line = (out.ifBlank { err }).lines().lastOrNull { it.trim().startsWith("{") } ?: return null
        return try { JSONObject(line) } catch (_: Exception) { null }
    }

    /**
     * `chat-directo` del motor (ver `chat_directo()` en `modulos/kairos_agent.sh`) — conversación cruda con
     * el LLM configurado, SIN el catálogo de intents de [parse]/[run]: nunca ejecuta una acción del dispositivo,
     * solo reenvía el mensaje y devuelve la respuesta tal cual. Es el "chat con IA" directo (gateway `ai.chat`),
     * distinto de "chat con el Agente" (`agent.ask`, acotado a instalar/iniciar/detener/estado/...). [history]
     * son los turnos previos de la conversación (rol `user`/`assistant`) — se mandan por stdin, nunca por argv
     * (mismo motivo que la key de IA externa en [configureAi]: argv queda visible en `/proc/<pid>/cmdline`).
     */
    fun chatDirecto(message: String, history: List<Pair<String, String>> = emptyList(), timeoutSeconds: Long = 45): JSONObject? {
        val args = listOf("chat-directo", message)
        val (_, out, err) = if (history.isEmpty()) {
            ManagerNativeUtils.runExec(listOf(wrapper.absolutePath) + args, timeoutSeconds)
        } else {
            val stdin = JSONArray(history.map { (role, content) -> JSONObject().put("role", role).put("content", content) }).toString()
            ManagerNativeUtils.runExecWithStdin(listOf(wrapper.absolutePath) + args + "--historial-stdin", stdin, timeoutSeconds)
        }
        val line = (out.ifBlank { err }).lines().lastOrNull { it.trim().startsWith("{") } ?: return null
        return try { JSONObject(line) } catch (_: Exception) { null }
    }

    // ── Configuración de IA externa (gancho opcional de interpretar) ────────
    // La key NUNCA se vuelve a leer desde la app una vez guardada (ver
    // .claude/rules/kairos-secrets-never-revealed.md, mismo criterio que
    // RemoteManager.kt para claves SSH) — solo "guardar"/"quitar", nunca "ver".
    // El motor la persiste en ~/.kairos_agent/ai_config.json con chmod 600.

    /**
     * Guarda url/modelo/key en el motor (`configurar-ia`). Un campo vacío no se envía/sobrescribe.
     *
     * Revisión de seguridad real: la key NUNCA viaja como argumento del proceso
     * (`--key <valor>`) — un argv queda visible para otros procesos del mismo usuario mientras
     * corre (`ps`/`/proc/<pid>/cmdline`, todos los procesos de Termux comparten UID) y, si alguien
     * escribe el mismo comando a mano en una terminal, queda además en `.bash_history`. Se manda
     * por `stdin` con `--key-stdin` en su lugar; el motor la lee de ahí y nunca la vuelve a
     * imprimir (igual que antes).
     */
    fun configureAi(url: String, model: String, key: String): Boolean {
        val args = mutableListOf("configurar-ia")
        if (url.isNotBlank()) { args += "--url"; args += url }
        if (model.isNotBlank()) { args += "--modelo"; args += model }
        if (key.isNotBlank()) args += "--key-stdin"
        if (args.size == 1) return false
        val (_, out, err) = if (key.isNotBlank()) {
            ManagerNativeUtils.runExecWithStdin(listOf(wrapper.absolutePath) + args, key, 30)
        } else {
            ManagerNativeUtils.runExec(listOf(wrapper.absolutePath) + args, 30)
        }
        val line = (out.ifBlank { err }).lines().lastOrNull { it.trim().startsWith("{") } ?: return false
        return try { JSONObject(line).optBoolean("ok") } catch (_: Exception) { false }
    }

    /** Quita solo la key guardada; url/modelo quedan como estaban. */
    fun clearAiKey(): Boolean = run(listOf("configurar-ia", "--quitar"), 15)?.optBoolean("ok") == true

    // ── Entender el pedido (sin IA) ─────────────────────────────────────────

    private fun normalize(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

    /**
     * Pedido en español → intent. [modules] = pares (id, nombre) del catálogo para reconocer de qué
     * módulo se habla. Devuelve null si no se entiende (entonces decide el router con IA, si hay).
     */
    fun parse(text: String, modules: List<Pair<String, String>>, kb: KairosAgentKnowledge? = null): Parsed? {
        val t = normalize(text).trim()
        if (t.isEmpty()) return null
        val words = t.split(Regex("[^a-z0-9_+.-]+")).filter { it.isNotEmpty() }
        fun has(vararg keys: String) = keys.any { k -> words.any { it == k || it.startsWith(k) } }

        val module = findModule(t, words, modules)
        val first = words.firstOrNull().orEmpty()

        val base = when {
            has("historial") || t.contains("que hiciste") -> Parsed("historial", listOf("--limit", "10"), false)
            has("proceso") -> Parsed("procesos", emptyList(), false)
            has("refresc") || t.contains("actualiza el catalogo") -> Parsed("refrescar", emptyList(), false)
            first.startsWith("instal") || has("instalame") ->
                module?.let { Parsed("instalar", listOf(it.first), true, it.second) }
            first.startsWith("inici") || first.startsWith("arranc") || first.startsWith("enciend") || first.startsWith("levant") || first == "start" ->
                module?.let { Parsed("iniciar", listOf(it.first), true, it.second) }
            first.startsWith("deten") || first.startsWith("apag") || first.startsWith("cierra") || first == "para" || first == "stop" ->
                module?.let { Parsed("detener", listOf(it.first), true, it.second) }
            has("estado", "status", "corriendo", "instalado") || t.contains("como esta") ->
                Parsed("estado", module?.let { listOf(it.first) } ?: emptyList(), false, module?.second)
            else -> null
        }
        return base ?: kb?.let { parseWithKnowledge(it, t, words, module, modules) }
    }

    /** Intents que cambian el dispositivo: siempre piden confirmación, sin importar lo que diga el JSON. */
    private val MUTATING = setOf("instalar", "iniciar", "detener")
    private val KNOWN = MUTATING + setOf("estado", "procesos", "historial", "refrescar")

    /** Segunda pasada con la jerga del JSON; solo acepta intents conocidos y fija `mutates` por código. */
    private fun parseWithKnowledge(
        kb: KairosAgentKnowledge, t: String, words: List<String>,
        module: Pair<String, String>?, modules: List<Pair<String, String>>,
    ): Parsed? {
        val rule = kb.intentFor(t, words)?.takeIf { it.intent in KNOWN } ?: return null
        val target = module ?: kb.moduleByAlias(t, words)?.let { id -> modules.firstOrNull { it.first == id } }
        val needsModule = rule.intent in MUTATING
        if (needsModule && target == null) return null
        val args = when (rule.intent) {
            "historial" -> listOf("--limit", "10")
            "estado" -> target?.let { listOf(it.first) }.orEmpty()
            "procesos", "refrescar" -> emptyList()
            else -> listOf(target!!.first)
        }
        return Parsed(rule.intent, args, rule.intent in MUTATING, target?.second)
    }

    /** El módulo cuyo id o nombre aparece en el pedido; si hay varios gana el nombre más largo. */
    private fun findModule(t: String, words: List<String>, modules: List<Pair<String, String>>): Pair<String, String>? =
        modules
            .filter { (id, name) ->
                val n = normalize(name)
                id.lowercase() in words || (n.length >= 3 && Regex("(^|[^a-z0-9])${Regex.escape(n)}([^a-z0-9]|$)").containsMatchIn(t))
            }
            .maxByOrNull { (id, name) -> maxOf(id.length, name.length) }

    // ── Mostrar la respuesta ────────────────────────────────────────────────

    /** Respuesta del motor → texto para la burbuja del agente. */
    fun format(intent: String, json: JSONObject): String {
        if (!json.optBoolean("ok", true) && json.has("error")) return "No pude: ${json.optString("error")}"
        return when (intent) {
            "estado" -> json.optJSONObject("modulo")?.let { m ->
                val estado = buildList {
                    add(if (m.optInt("installed") == 1) "instalado" else "no instalado")
                    if (m.optInt("installed") == 1) add(if (m.optInt("running") == 1) "corriendo" else "detenido")
                    m.optString("version").takeIf { it.isNotBlank() && it != "null" }?.let { add("versión $it") }
                    m.optString("port").takeIf { it.isNotBlank() && it != "null" }?.let { add("puerto $it") }
                }
                "${m.optString("name")}: ${estado.joinToString(", ")}."
            } ?: run {
                val arr = json.optJSONArray("modulos")
                val rows = (0 until (arr?.length() ?: 0)).mapNotNull { arr?.optJSONObject(it) }
                val running = rows.filter { it.optInt("running") == 1 }.map { it.optString("name") }
                val installed = rows.count { it.optInt("installed") == 1 }
                "Hay ${rows.size} módulos en el catálogo, $installed instalados." +
                    (if (running.isEmpty()) " Ninguno está corriendo." else " Corriendo: ${running.joinToString(", ")}.")
            }
            "procesos" -> {
                val arr = json.optJSONArray("procesos")
                val top = (0 until minOf(arr?.length() ?: 0, 8)).mapNotNull { arr?.optJSONObject(it)?.optString("cmd")?.take(60) }
                "${json.optInt("count")} procesos en marcha." + (if (top.isEmpty()) "" else "\n" + top.joinToString("\n") { "• $it" })
            }
            "historial" -> {
                val arr = json.optJSONArray("historial")
                val rows = (0 until (arr?.length() ?: 0)).mapNotNull { arr?.optJSONObject(it) }
                if (rows.isEmpty()) "Todavía no ejecuté ninguna acción." else
                    rows.joinToString("\n") { r ->
                        val mark = if (r.optInt("success") == 1) "✔" else "✖"
                        "$mark ${r.optString("intent")} ${r.optString("module_id")}".trim() + " · " + r.optString("ts").take(16).replace('T', ' ')
                    }
            }
            "refrescar" -> "Catálogo actualizado: ${json.optInt("modules_ingested")} módulos (${json.optString("source")})."
            "instalar", "iniciar", "detener" ->
                if (json.optBoolean("ok")) "Hecho." else "No se pudo completar. ${json.optString("output_tail").takeLast(300)}".trim()
            else -> json.toString().take(400)
        }
    }
}

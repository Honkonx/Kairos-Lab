package com.termux.app.util

import org.json.JSONObject
import java.io.File

/**
 * Migración nativa de cmd_hermes (kairos_manager.py) — todo el módulo es
 * lectura/escritura de archivos simples (~/.hermes/.env formato KEY=value,
 * ~/.hermes/config.yaml, ~/.hermes/SOUL.md) + tmux/pgrep para el estado del
 * gateway, sin ninguna lógica que realmente necesitara Python de por medio.
 */
object HermesNative {

    private const val GATEWAY_SESSION = "hermes-gw"
    private const val GATEWAY_PATTERN = "hermes.*gateway"

    private val hermesHome get() = File(ManagerNativeUtils.home, ".hermes")

    fun info(): JSONObject {
        val reg = ManagerNativeUtils.readRegistry()
        val envFile = File(hermesHome, ".env")
        var provider = ""
        if (envFile.exists()) {
            try {
                envFile.forEachLine { line ->
                    val t = line.trim()
                    if (t.startsWith("HERMES_PROVIDER=") || t.startsWith("AI_PROVIDER=")) {
                        provider = t.substringAfter("=").trim().trim('"').trim('\'')
                    }
                }
            } catch (_: Exception) { }
        }
        return JSONObject().apply {
            put("ok", true)
            put("installed", reg["hermes.installed"] == "true")
            put("version", reg["hermes.version"] ?: "")
            put("running", ManagerNativeUtils.pgrepF(GATEWAY_PATTERN))
            put("has_config", envFile.exists())
            put("provider", provider)
            put("install_dir", reg["hermes.install_dir"] ?: "")
        }
    }

    fun gatewayStart(): JSONObject {
        if (ManagerNativeUtils.pgrepF(GATEWAY_PATTERN)) {
            return JSONObject().put("ok", true).put("message", "Gateway ya corriendo")
        }
        ManagerNativeUtils.runExec(
            listOf(TERMUX_TMUX_PATH, "new-session", "-d", "-s", GATEWAY_SESSION, "hermes gateway"), 10
        )
        Thread.sleep(2000)
        return if (ManagerNativeUtils.pgrepF(GATEWAY_PATTERN) || ManagerNativeUtils.tmuxHas(GATEWAY_SESSION)) {
            JSONObject().put("ok", true).put("message", "Gateway iniciado")
        } else {
            JSONObject().put("ok", false).put("error", "Falló")
        }
    }

    fun gatewayStop(): JSONObject {
        ManagerNativeUtils.runExec(listOf(TERMUX_TMUX_PATH, "kill-session", "-t", GATEWAY_SESSION), 5)
        ManagerNativeUtils.runExec(listOf(TERMUX_PKILL_PATH, "-f", GATEWAY_PATTERN), 5)
        return JSONObject().put("ok", true).put("message", "Gateway detenido")
    }

    fun configGet(): JSONObject {
        val envFile = File(hermesHome, ".env")
        val cfgFile = File(hermesHome, "config.yaml")
        val envData = JSONObject()
        var cfgData = ""
        if (envFile.exists()) {
            try {
                envFile.forEachLine { raw ->
                    val l = raw.trim()
                    if (l.isNotEmpty() && !l.startsWith("#") && l.contains("=")) {
                        val k = l.substringBefore("=").trim()
                        val v = l.substringAfter("=").trim().trim('"').trim('\'')
                        envData.put(k, v)
                    }
                }
            } catch (_: Exception) { }
        }
        if (cfgFile.exists()) {
            try {
                cfgData = cfgFile.readText().take(2000)
            } catch (_: Exception) { }
        }
        return JSONObject().apply {
            put("ok", true)
            put("env", envData)
            put("config_yaml", cfgData)
            put("has_soul", File(hermesHome, "SOUL.md").exists())
        }
    }

    // ManagerNativeUtils.upsertKeyValueLine() — consolidación 2026-08-13 (ver auditoría de
    // código): mismo algoritmo "leer KEY=VALUE, buscar prefijo, reemplazar o agregar" que
    // OllamaApiClient.writeConfigValue()/EntornoNative.updateRegistryValue() reimplementaban
    // cada uno por su lado. configSetModel() (abajo) queda SIN migrar a propósito: busca
    // por 2 prefijos posibles (AI_MODEL=/HERMES_MODEL=) pero siempre escribe uno solo —
    // semántica distinta a "upsert de una única clave" que este helper no cubre sin
    // arriesgar duplicar la clave en vez de normalizarla.
    fun configSetProvider(provider: String, apiKey: String): JSONObject {
        hermesHome.mkdirs()
        val envFile = File(hermesHome, ".env")
        val keysToSet = linkedMapOf("AI_PROVIDER" to provider, "${provider.uppercase()}_API_KEY" to apiKey)
        keysToSet.forEach { (key, value) -> ManagerNativeUtils.upsertKeyValueLine(envFile, key, value) }
        chmod600(envFile)
        return JSONObject().put("ok", true).put("message", "Provider: $provider")
    }

    fun configSetModel(model: String): JSONObject {
        hermesHome.mkdirs()
        val envFile = File(hermesHome, ".env")
        val lines = if (envFile.exists()) envFile.readLines().toMutableList() else mutableListOf()
        val idx = lines.indexOfFirst { it.trim().startsWith("AI_MODEL=") || it.trim().startsWith("HERMES_MODEL=") }
        if (idx >= 0) lines[idx] = "AI_MODEL=$model" else lines.add("AI_MODEL=$model")
        envFile.writeText(lines.joinToString("\n") + "\n")
        return JSONObject().put("ok", true).put("message", "Modelo: $model")
    }

    // Bug real (2026-08-07, ver docs/humano/humano91.md): "en hermes no sale las opciones
    // bien de ia local" — configSetProvider()/configSetModel() de arriba solo escriben
    // ~/.hermes/.env (AI_PROVIDER=X, X_API_KEY=Y) — el formato correcto para un provider
    // CLOUD (OpenRouter/Anthropic/etc, confirmado en docs/modulos/HERMES.md § Variables de
    // entorno). Para un provider LOCAL (Ollama/llama-server, cualquier endpoint OpenAI-
    // compatible), Hermes v0.16+ requiere el bloque anidado "model:" de
    // ~/.hermes/config.yaml (provider: custom, base_url, default) — un formato YAML
    // COMPLETAMENTE DISTINTO que estas funciones nunca escribían, así que "Usar Ollama
    // local" aparentaba funcionar (el toast decía éxito) sin que Hermes realmente quedara
    // configurado. En vez de escribir YAML a mano (riesgo real de indentación/escape, ya
    // documentado como "error común" en HERMES.md), se usan los 3 comandos reales del CLI
    // (confirmados en docs/modulos/HERMES.md § Comandos de Referencia Rápida:
    // "hermes config set model.provider custom", "model.base_url", "model.default") — el
    // propio binario es quien escribe el YAML, con el schema que su versión instalada
    // realmente espera.
    fun configSetLocalProvider(baseUrl: String, model: String): JSONObject {
        val steps = listOf(
            "model.provider" to "custom",
            "model.base_url" to baseUrl,
            "model.default" to model
        )
        for ((key, value) in steps) {
            val (rc, _, err) = ManagerNativeUtils.runExec(listOf(TERMUX_HERMES_PATH, "config", "set", key, value), 15)
            if (rc != 0) {
                return JSONObject().put("ok", false)
                    .put("error", err.ifEmpty { "\"hermes config set $key\" falló (rc=$rc)" })
            }
        }
        return JSONObject().put("ok", true).put("message", "Proveedor local configurado: $model")
    }

    // Mismo patrón que OpenCodeNative.ollamaModels() — reusa OllamaApiClient (cliente HTTP
    // compartido con ChatFragment/OllamaFragment) en vez de invocar el CLI de Ollama.
    fun ollamaModels(): JSONObject {
        return try {
            val models = OllamaApiClient.listModels()
            val arr = org.json.JSONArray()
            models.forEach { arr.put(JSONObject().put("name", it.name)) }
            JSONObject().put("ok", true).put("models", arr)
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", "Ollama no disponible: ${e.message}")
        }
    }

    fun soulGet(): JSONObject {
        val soulFile = File(hermesHome, "SOUL.md")
        val content = if (soulFile.exists()) soulFile.readText() else ""
        return JSONObject().put("ok", true).put("content", content)
    }

    fun soulSet(content: String): JSONObject {
        hermesHome.mkdirs()
        File(hermesHome, "SOUL.md").writeText(content)
        return JSONObject().put("ok", true).put("message", "SOUL.md actualizado")
    }

    // ── Memoria persistente de Engram vía Skill (2026-08-17) ────────────────────────────
    // Hermes no es un cliente MCP reconocido por Engram (ver comentario de
    // BaseModuleFragment.engramSetupButton()) ni tiene un mecanismo de tool-calling custom
    // como OpenClaw (ver OpenClawNative.mcpConnectEngram()) — pero SÍ tiene un sistema de
    // Skills real y documentado (hermes-agent.nousresearch.com/docs/user-guide/features/
    // skills, website/docs/developer-guide/creating-skills.md del repo real): cualquier
    // carpeta con un SKILL.md dentro de "~/.hermes/skills/" (confirmado como "la fuente de
    // verdad", auto-detectado sin sync manual) se carga en el system prompt cuando el
    // agente decide que aplica, y su procedimiento puede ser instrucciones + shell commands
    // + herramientas existentes (el agente ya tiene una tool de terminal). No es una tool
    // registrada por function-calling nativo (a diferencia del MCP real de OpenClaw), pero
    // SÍ es automático una vez cargado: el agente decide solo cuándo invocar `engram save`/
    // `engram search` sin que el usuario tenga que copiar/pegar nada — mejor que el botón
    // manual "consultar memoria" que se había planteado como piso mínimo.
    private val engramSkillDir get() = File(hermesHome, "skills/custom/engram-memory")

    fun installEngramSkill(): JSONObject {
        return try {
            val dir = engramSkillDir
            dir.mkdirs()
            File(dir, "SKILL.md").writeText(
                """
                ---
                name: engram-memory
                description: Guarda y busca memorias persistentes del usuario usando el binario Engram (SQLite local, compartido entre agentes IA de Kairos).
                version: 1.0.0
                ---

                # Memoria persistente (Engram)

                ## Cuándo usar
                Usa esta skill cuando el usuario pida recordar algo para el futuro, o cuando
                necesites buscar contexto de conversaciones/tareas anteriores antes de responder.

                ## Procedimiento
                Ejecuta estos comandos con la herramienta de terminal (ambos ya están instalados
                y en PATH):

                - Guardar una memoria nueva:
                  `engram save "<título corto>" "<contenido a recordar>"`
                - Buscar memorias existentes por texto:
                  `engram search "<consulta>"`

                ## Notas
                - No inventes el contenido de una búsqueda — si `engram search` no devuelve
                  resultados relevantes, decilo en vez de inventar una memoria.
                - El título de `engram save` debe ser corto y descriptivo (sirve para
                  encontrar la memoria después con `engram search`).
                """.trimIndent()
            )
            JSONObject().put("ok", true).put("message", "Skill de Engram instalada en ~/.hermes/skills/custom/engram-memory")
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message ?: "error desconocido")
        }
    }

    // Equivalente a os.chmod(env_file, 0o600) — el .env puede tener API keys en texto
    // plano, no debería quedar legible/escribible para nadie más que el propio proceso.
    private fun chmod600(file: File) {
        file.setReadable(false, false)
        file.setWritable(false, false)
        file.setReadable(true, true)
        file.setWritable(true, true)
    }
}

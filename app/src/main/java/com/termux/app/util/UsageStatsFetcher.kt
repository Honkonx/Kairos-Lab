package com.termux.app.util

import com.termux.shared.termux.TermuxConstants
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * Línea de "uso/token/costo/plan" para la barra lateral de la terminal adaptada — pedido
 * explícito del usuario: los 3 CLIs de agentes IA más usados que Kairos ya instala (Claude
 * Code, Codex, OpenCode). Best-effort SIEMPRE: si falta un archivo/credencial, o el request
 * de red falla, devuelve null y el caller simplemente no muestra la línea (ver
 * TermuxActivity.refreshAdaptedDrawerUsage()) — nunca un mensaje de error visible.
 *
 * Fuentes reales, investigadas en docs/arquitectura/INVESTIGACION_TERMINAL_AGENTES_IA_2026-09-01.md
 * y docs/referencias/token-uso/ (auditoría de 7 proyectos de referencia, 2026-09-01) + ronda de
 * paridad Codex/OpenCode del mismo día:
 * - Claude: `GET https://api.anthropic.com/api/oauth/usage` con el `accessToken` OAuth que
 *   Claude Code ya guarda en `~/.claude/.credentials.json` — confirmado en producción real por
 *   `usage-monitor` (Kotlin) Y `token-monitor` (Electron), ambos proyectos MIT auditados de
 *   forma independiente. Endpoint NO documentado públicamente por Anthropic — puede cambiar de
 *   formato sin aviso, por eso todo el
 *   parseo de abajo usa alias defensivos (snake_case/camelCase) en vez de asumir un único nombre
 *   de campo, mismo patrón que `token-monitor/src/shared/limitCollector.js` (valueFromAliases).
 *   Tokens/costo de la SESIÓN activa: Claude Code persiste cada sesión como JSONL en
 *   `~/.claude/projects/(proyecto)/(sesion).jsonl` (`message.usage`), se lee el archivo modificado más
 *   recientemente y se dedupea por `message.id` (un mismo mensaje puede aparecer repetido en el
 *   JSONL — confirmado por `usage-monitor`'s `ClaudeTranscriptDto.kt` y `claude-monitor`).
 * - Codex: sin endpoint REST confirmado (CLI puro) — se lee el JSONL más reciente de
 *   `~/.codex/sessions/`. Schema EXACTO confirmado por 2 implementaciones independientes
 *   (`tokenmeter/internal/collector/codex.go` y `Codex-Token-Monitor/src/extension.js`,
 *   `parseTokenEvent`): cada línea es `{"timestamp":"...","type":"event_msg"|"session_meta"|
 *   "response_item","payload":{...}}`; el evento de tokens tiene `payload.type == "token_count"`
 *   y `payload.info.total_token_usage` (contador ACUMULADO de toda la sesión, no un delta — el
 *   último evento del archivo ya trae el total final, no hace falta sumar entre eventos).
 *   `info.model`/`model_id`/`modelId` (alias, ver `findFirstValue` de ambos proyectos de
 *   referencia) da el modelo real usado, que se cruza contra `CODEX_PRICE_TABLE` (tabla estática
 *   embebida en `Codex-Token-Monitor/src/extension.js` línea 13-20, precios reales publicados de
 *   la familia `gpt-5-codex`) para estimar el costo — sin llamar a ningún endpoint de precios ni
 *   inventar un número: si el modelo no está en la tabla, el costo simplemente no se muestra,
 *   igual que Claude no muestra
 *   ventanas de rate-limit que el endpoint no trae.
 * - OpenCode: es un CLI cliente-servidor propio (`sst/opencode`) — `opencode serve`/`opencode
 *   web` levantan un servidor HTTP+WebSocket real (puerto default `4096`, o `3000` vía el script
 *   `opencode_start.sh` que ya usa `OpenCodeFragment.kt`) con endpoints confirmados por el
 *   OpenAPI generado del propio proyecto (`packages/sdk/js/src/gen/types.gen.ts`, investigado
 *   2026-09-01 vía WebFetch de GitHub/opencode.ai/docs, sin código descargado a `referencia/`):
 *   `GET /session` (lista, cada uno con `time.updated`), `GET /session/:id/message` (mensajes de
 *   esa sesión, cada uno `{ info, parts }`). El objeto `AssistantMessage.info` trae `modelID`,
 *   `providerID`, `cost` (USD) y `tokens.{input,output,reasoning,cache.{read,write}}` REALES por
 *   mensaje — no hay que parsear ningún archivo, se suma sobre los mensajes `role == "assistant"`
 *   de la sesión más reciente. **Limitación real confirmada, no hipotética**: ese servidor HTTP
 *   NO corre nunca durante una sesión TUI interactiva normal (`opencode` sin subcomando, la
 *   forma en que `OpenCodeFragment.kt` abre la terminal vía `launchTerminalCommand`) — el propio
 *   comentario de `OpenCodeFragment.kt` línea ~44 lo dice explícito: "la TUI queda totalmente
 *   aparte, sin relación con este switch [Servidor web]". La documentación pública de OpenCode
 *   tampoco confirma que la TUI arranque un servidor interno propio sin que el usuario lo pida.
 *   Por eso `fetchOpenCodeSummary()` intenta los 2 puertos con timeout corto y best-effort: si el
 *   usuario activó el switch "Servidor web" (o corrió `opencode serve`/`opencode web` a mano), la
 *   línea aparece con datos reales; si no, no hay nada que consultar y la línea simplemente no se
 *   muestra — igual que cualquier otro dato ausente en este archivo, nunca un error visible.
 */
object UsageStatsFetcher {

    // TTL corto para no pegarle a la red (Claude)/disco (Codex, JSONL potencialmente grande) en
    // cada apertura del drawer — mismo criterio de costo/beneficio que
    // TermuxActivity.ADAPTED_METRICS_INTERVAL_MS, pero sin loop propio: el caller solo pide un
    // valor fresco cuando el usuario realmente abre/refresca el sidebar.
    private const val CACHE_TTL_MS = 60_000L

    private data class CacheEntry(val timestamp: Long, val summary: String?)

    private val cache = HashMap<String, CacheEntry>()

    /**
     * Punto de entrada único — bloqueante, DEBE llamarse desde un hilo de background (nunca el
     * hilo principal: hace HTTP real para "claude" y lee archivos potencialmente grandes para
     * "codex"). Devuelve null si no hay nada útil que mostrar, nunca lanza.
     */
    @Synchronized
    fun getUsageSummary(moduleId: String): String? {
        val now = System.currentTimeMillis()
        cache[moduleId]?.let { entry ->
            if (now - entry.timestamp < CACHE_TTL_MS) return entry.summary
        }
        val summary = try {
            when (moduleId) {
                "claude" -> fetchClaudeSummary()
                "codex" -> fetchCodexSummary()
                "opencode" -> fetchOpenCodeSummary()
                else -> null
            }
        } catch (_: Exception) {
            null
        }
        cache[moduleId] = CacheEntry(now, summary)
        return summary
    }

    // ── Claude Code ─────────────────────────────────────────────────────

    private fun fetchClaudeSummary(): String? {
        val accessToken = readClaudeAccessToken() ?: return null

        val quotaText = try {
            fetchClaudeQuotaText(accessToken)
        } catch (_: Exception) {
            null // Endpoint no documentado — cualquier fallo (401, formato cambiado, sin red) se ignora en silencio.
        }

        val sessionText = try {
            fetchClaudeSessionTokensText()
        } catch (_: Exception) {
            null
        }

        val parts = listOfNotNull(quotaText, sessionText)
        if (parts.isEmpty()) return null
        return "Claude: " + parts.joinToString(" · ")
    }

    private fun readClaudeAccessToken(): String? {
        val file = File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".claude/.credentials.json")
        if (!file.isFile) return null
        val root = try {
            JSONObject(file.readText(Charsets.UTF_8))
        } catch (_: Exception) {
            return null
        }
        // Forma real observada por 2 proyectos de referencia independientes (usage-monitor,
        // token-monitor): { "claudeAiOauth": { "accessToken": "..." } } — con fallback a
        // "oauth" o el propio root, por si una versión distinta de Claude Code cambia el
        // envoltorio sin cambiar el nombre del campo del token en sí.
        val oauth = root.optJSONObject("claudeAiOauth")
            ?: root.optJSONObject("oauth")
            ?: root
        val token = oauth.optString("accessToken", oauth.optString("access_token", ""))
        return token.takeIf { it.isNotBlank() }
    }

    private fun fetchClaudeQuotaText(accessToken: String): String? {
        val conn = URL("https://api.anthropic.com/api/oauth/usage").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.setRequestProperty("Authorization", "Bearer $accessToken")
            conn.setRequestProperty("User-Agent", "claude-code/1.0.0")
            conn.setRequestProperty("anthropic-beta", "oauth-2025-04-20")
            conn.connectTimeout = 4000
            conn.readTimeout = 6000
            val code = conn.responseCode
            if (code !in 200..299) return null
            val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            if (text.isBlank()) return null
            val json = JSONObject(text)

            val fiveHour = json.optJSONObject("five_hour") ?: json.optJSONObject("fiveHour")
            val sevenDay = json.optJSONObject("seven_day") ?: json.optJSONObject("sevenDay")

            val windows = mutableListOf<String>()
            windowPercent(fiveHour)?.let { pct -> windows.add("${pct}% uso 5h${windowResetSuffix(fiveHour)}") }
            windowPercent(sevenDay)?.let { pct -> windows.add("${pct}% uso 7d${windowResetSuffix(sevenDay)}") }
            return if (windows.isEmpty()) null else windows.joinToString(" · ")
        } finally {
            conn.disconnect()
        }
    }

    // Alias defensivos (ver comentario de cabecera del archivo) — endpoint no documentado.
    private fun windowPercent(window: JSONObject?): Int? {
        if (window == null) return null
        val raw = firstDouble(window, "usedPercent", "used_percent", "utilization", "percent") ?: return null
        // Heurística: algunas variantes de este tipo de endpoint reportan fracción 0..1, otras
        // ya en 0..100 — si el valor es <= 1.0 se asume fracción (0% real coincide en ambos
        // casos, no genera ambigüedad práctica).
        val pct = if (raw <= 1.0) raw * 100.0 else raw
        return pct.coerceIn(0.0, 100.0).toInt()
    }

    private fun windowResetSuffix(window: JSONObject?): String {
        if (window == null) return ""
        val resetsAt = firstString(window, "resetsAt", "resets_at", "resetAt", "reset_at") ?: return ""
        val relative = formatRelativeReset(resetsAt) ?: return ""
        return " (resetea $relative)"
    }

    private fun formatRelativeReset(iso: String): String? {
        return try {
            val instant = Instant.parse(iso)
            val duration = Duration.between(Instant.now(), instant)
            if (duration.isNegative) return null
            // toMinutesPart()/toHoursPart() son de Java 9 — minSdk=26 no garantiza esas
            // variantes "part" del backport de java.time, así que se calcula a mano con
            // toMinutes() (disponible desde API 26) en vez de arriesgar un NoSuchMethodError.
            val totalMinutes = duration.toMinutes()
            val hours = totalMinutes / 60
            val minutes = totalMinutes % 60
            if (hours > 0) "en ${hours}h ${minutes}m" else "en ${minutes}m"
        } catch (_: DateTimeParseException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    // Tokens de la sesión JSONL modificada más recientemente en ~/.claude/projects/ — dedup por
    // message.id (ver comentario de cabecera: usage-monitor/claude-monitor confirman que el
    // mismo mensaje puede repetirse en el JSONL si Claude Code reescribe/continúa la sesión).
    private fun fetchClaudeSessionTokensText(): String? {
        val projectsDir = File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".claude/projects")
        if (!projectsDir.isDirectory) return null
        val newest = projectsDir.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".jsonl") }
            .maxByOrNull { it.lastModified() } ?: return null

        var inputTokens = 0L
        var outputTokens = 0L
        val seenMessageIds = HashSet<String>()

        newest.bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue
                val entry = try { JSONObject(trimmed) } catch (_: Exception) { continue }
                if (entry.optString("type") != "assistant") continue
                val message = entry.optJSONObject("message") ?: continue
                val usage = message.optJSONObject("usage") ?: continue
                val messageId = message.optString("id")
                if (messageId.isNotBlank() && !seenMessageIds.add(messageId)) continue
                inputTokens += usage.optLong("input_tokens", 0)
                outputTokens += usage.optLong("output_tokens", 0)
            }
        }

        val total = inputTokens + outputTokens
        if (total <= 0) return null
        return "${humanTokenCount(total)} tok sesión"
    }

    // ── Codex CLI ────────────────────────────────────────────────────────

    // Precios reales $/1M tokens de la familia gpt-5-codex — tabla estática embebida en
    // `Codex-Token-Monitor/src/extension.js` (línea 13-20, ver comentario de cabecera). Sin
    // llamada a ningún endpoint de precios: si el modelo real de la sesión no está acá, el costo
    // no se muestra (nunca se inventa un número — empirical-verification-before-fix.md).
    private val CODEX_PRICE_TABLE = mapOf(
        "gpt-5.5" to Triple(5.0, 0.5, 30.0),
        "gpt-5-codex" to Triple(1.25, 0.125, 10.0),
        "gpt-5.1-codex" to Triple(1.25, 0.125, 10.0),
        "gpt-5.1-codex-max" to Triple(1.25, 0.125, 10.0),
        "gpt-5.2-codex" to Triple(1.75, 0.175, 14.0),
        "gpt-5.3-codex" to Triple(1.75, 0.175, 14.0)
    )

    private fun fetchCodexSummary(): String? {
        val sessionsDir = File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".codex/sessions")
        if (!sessionsDir.isDirectory) return null
        val newest = sessionsDir.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".jsonl") }
            .maxByOrNull { it.lastModified() } ?: return null

        // total_token_usage es un contador ACUMULADO de toda la sesión (confirmado por
        // tokenmeter/Codex-Token-Monitor) — cada evento nuevo sobreescribe al anterior, no se
        // suma entre eventos; el último evento del archivo ya trae el total final.
        var inputTokens = 0L
        var cachedInputTokens = 0L
        var outputTokens = 0L
        var reasoningTokens = 0L
        var model: String? = null
        var sawUsage = false

        newest.bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.isEmpty() || !trimmed.contains("\"token_count\"")) continue
                val entry = try { JSONObject(trimmed) } catch (_: Exception) { continue }
                if (entry.optString("type") != "event_msg") continue
                val payload = entry.optJSONObject("payload") ?: continue
                if (payload.optString("type") != "token_count") continue
                val info = payload.optJSONObject("info") ?: continue
                val total = info.optJSONObject("total_token_usage") ?: continue
                sawUsage = true
                inputTokens = total.optLong("input_tokens", 0)
                cachedInputTokens = total.optLong("cached_input_tokens", 0)
                outputTokens = total.optLong("output_tokens", 0)
                // Alias "reasoning_tokens" confirmado por tokenmeter (normalizeCodexTokenUsage,
                // ver comentario de cabecera) — versiones de Codex más viejas usaban ese nombre.
                reasoningTokens = total.optLong("reasoning_output_tokens", total.optLong("reasoning_tokens", 0))
                model = firstString(info, "model", "model_id", "modelId") ?: model
            }
        }

        if (!sawUsage) return null
        val total = inputTokens + outputTokens + reasoningTokens
        if (total <= 0) return null
        val cacheInfo = if (cachedInputTokens > 0) " (${humanTokenCount(cachedInputTokens)} cache)" else ""
        val costInfo = computeCodexCost(model, inputTokens, cachedInputTokens, outputTokens + reasoningTokens)
            ?.let { " · $%.2f".format(it) } ?: ""
        return "Codex: ${humanTokenCount(total)} tok sesión$cacheInfo$costInfo"
    }

    private fun computeCodexCost(model: String?, inputTokens: Long, cachedTokens: Long, outputTokens: Long): Double? {
        val price = CODEX_PRICE_TABLE[model] ?: return null
        val (inputPricePerM, cachedPricePerM, outputPricePerM) = price
        val uncachedInput = (inputTokens - cachedTokens).coerceAtLeast(0)
        val cost = uncachedInput / 1_000_000.0 * inputPricePerM +
            cachedTokens / 1_000_000.0 * cachedPricePerM +
            outputTokens / 1_000_000.0 * outputPricePerM
        return cost.takeIf { it > 0.0 }
    }

    // ── OpenCode ─────────────────────────────────────────────────────────

    // Mismos 2 puertos que ya ofrece OpenCodeFragment.kt (dropdown "Servidor web") — ver
    // comentario de cabecera: el servidor solo corre si el usuario lo activó explícitamente,
    // nunca durante la sesión TUI normal, así que se prueban ambos con timeout corto y se sigue
    // en silencio si ninguno responde.
    private val OPENCODE_PORTS = listOf(4096, 3000)
    private const val OPENCODE_CONNECT_TIMEOUT_MS = 400
    private const val OPENCODE_READ_TIMEOUT_MS = 1200

    private fun fetchOpenCodeSummary(): String? {
        for (port in OPENCODE_PORTS) {
            val summary = try {
                fetchOpenCodeSummaryFromPort(port)
            } catch (_: Exception) {
                null
            }
            if (summary != null) return summary
        }
        return null
    }

    private fun fetchOpenCodeSummaryFromPort(port: Int): String? {
        val sessionsText = openCodeHttpGet("http://127.0.0.1:$port/session") ?: return null
        val sessions = try { org.json.JSONArray(sessionsText) } catch (_: Exception) { return null }
        if (sessions.length() == 0) return null

        // Sesión más reciente por time.updated (mismo campo que expone GET /session, ver
        // comentario de cabecera — Session.time.updated, epoch ms).
        var newestId: String? = null
        var newestUpdated = -1L
        for (i in 0 until sessions.length()) {
            val session = sessions.optJSONObject(i) ?: continue
            val updated = session.optJSONObject("time")?.optLong("updated", -1) ?: -1
            if (updated > newestUpdated) {
                newestUpdated = updated
                newestId = session.optString("id").takeIf { it.isNotBlank() }
            }
        }
        val sessionId = newestId ?: return null

        val messagesText = openCodeHttpGet("http://127.0.0.1:$port/session/$sessionId/message") ?: return null
        val messages = try { org.json.JSONArray(messagesText) } catch (_: Exception) { return null }

        var inputTokens = 0L
        var outputTokens = 0L
        var reasoningTokens = 0L
        var cacheReadTokens = 0L
        var totalCost = 0.0
        var model: String? = null
        var sawAssistantMessage = false

        for (i in 0 until messages.length()) {
            val entry = messages.optJSONObject(i) ?: continue
            val info = entry.optJSONObject("info") ?: continue
            if (info.optString("role") != "assistant") continue
            sawAssistantMessage = true
            val tokens = info.optJSONObject("tokens")
            if (tokens != null) {
                inputTokens += tokens.optLong("input", 0)
                outputTokens += tokens.optLong("output", 0)
                reasoningTokens += tokens.optLong("reasoning", 0)
                cacheReadTokens += tokens.optJSONObject("cache")?.optLong("read", 0) ?: 0
            }
            totalCost += info.optDouble("cost", 0.0)
            model = info.optString("modelID").takeIf { it.isNotBlank() } ?: model
        }

        if (!sawAssistantMessage) return null
        val total = inputTokens + outputTokens + reasoningTokens
        if (total <= 0 && totalCost <= 0.0) return null

        val parts = mutableListOf<String>()
        if (total > 0) {
            val cacheInfo = if (cacheReadTokens > 0) " (${humanTokenCount(cacheReadTokens)} cache)" else ""
            parts.add("${humanTokenCount(total)} tok sesión$cacheInfo")
        }
        if (totalCost > 0.0) parts.add("$%.2f".format(totalCost))
        if (!model.isNullOrBlank()) parts.add(model)
        if (parts.isEmpty()) return null
        return "OpenCode: " + parts.joinToString(" · ")
    }

    private fun openCodeHttpGet(url: String): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = OPENCODE_CONNECT_TIMEOUT_MS
            conn.readTimeout = OPENCODE_READ_TIMEOUT_MS
            val code = conn.responseCode
            if (code !in 200..299) return null
            val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            return text.takeIf { it.isNotBlank() }
        } finally {
            conn.disconnect()
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun firstDouble(obj: JSONObject, vararg keys: String): Double? {
        for (key in keys) {
            if (obj.has(key) && !obj.isNull(key)) {
                val value = obj.optDouble(key, Double.NaN)
                if (!value.isNaN()) return value
            }
        }
        return null
    }

    private fun firstString(obj: JSONObject, vararg keys: String): String? {
        for (key in keys) {
            val value = obj.optString(key, "")
            if (value.isNotBlank()) return value
        }
        return null
    }

    private fun humanTokenCount(count: Long): String {
        return when {
            count >= 1_000_000 -> "%.1fM".format(count / 1_000_000.0)
            count >= 1_000 -> "%.1fk".format(count / 1_000.0)
            else -> count.toString()
        }
    }
}

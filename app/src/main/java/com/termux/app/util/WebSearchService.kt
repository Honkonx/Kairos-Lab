package com.termux.app.util

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLDecoder

/**
 * Búsqueda web SIN API key — scraping del endpoint HTML de DuckDuckGo
 * (`https://html.duckduckgo.com/html/`, la versión sin JS que se usa normalmente para esto,
 * confirmado por lectura directa de su markup real, no asumido) vía Jsoup. Complementa (no
 * reemplaza) la Web Search API de Ollama que ya usa ChatFragment.performWebSearch() — esa
 * requiere una API key de ollama.com; este servicio no requiere ninguna clave.
 *
 * Patrón adoptado de `search/.../DuckDuckGoSearchService.kt` de RikkaHub Agent (ver
 * docs/referencias/agentes/REFERENCIA_RIKKAHUB_AGENT.md sección 8) — mismas 3 piezas de diseño:
 * 1) headers de navegador real (sin esto, DuckDuckGo bloquea de entrada),
 * 2) clasificación HONESTA de la respuesta en 3 casos (nunca confundir "bloqueado" con "sin
 *    resultados" — un shape de respuesta no reconocido cae a [SearchOutcome.Blocked] por
 *    default, no a [SearchOutcome.Empty]: un falso positivo reintentable es más seguro que
 *    decirle al usuario/modelo que la web no tiene nada cuando el markup simplemente cambió),
 * 3) circuit breaker propio (para no seguir golpeando un endpoint que ya nos está bloqueando).
 */
object WebSearchService {

    private const val SEARCH_URL = "https://html.duckduckgo.com/html/"

    // User-Agent de navegador real (Chrome desktop reciente) — imprescindible: sin esto,
    // DuckDuckGo devuelve la página de challenge anti-bot en vez de resultados reales.
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private const val TIMEOUT_MS = 12000
    private const val MAX_RESULTS = 6

    // Circuit breaker (ver registerFailure()/registerSuccess()): tras N fallos SEGUIDOS
    // (bloqueo detectado o excepción de red), deja de intentar por un cooldown en vez de
    // seguir reintentando y empeorar el bloqueo del lado de DuckDuckGo.
    private const val FAILURE_THRESHOLD = 3
    private const val COOLDOWN_MS = 5 * 60 * 1000L // 5 minutos

    @Volatile private var consecutiveFailures = 0
    @Volatile private var circuitOpenUntilMs = 0L
    private val stateLock = Any()

    data class SearchResultItem(val title: String, val url: String, val snippet: String)

    sealed class SearchOutcome {
        /** Resultados reales — DuckDuckGo respondió de verdad y hay contenido. */
        data class Results(val items: List<SearchResultItem>) : SearchOutcome()
        /** SERP genuinamente vacío — la propia página lo dice explícitamente, no es un guess. */
        object Empty : SearchOutcome()
        /** Challenge anti-bot/CAPTCHA detectado, o HTTP 202/403/429/5xx — NUNCA se reporta como "sin resultados". */
        data class Blocked(val reason: String) : SearchOutcome()
        /** El circuit breaker está abierto (demasiados fallos recientes) — ni se intentó la request. */
        data class CircuitOpen(val retryAfterMs: Long) : SearchOutcome()
        /** Excepción de red/parseo no clasificable como bloqueo (timeout, DNS, etc.). */
        data class Error(val message: String) : SearchOutcome()
    }

    // Marcadores reales de la página de challenge anti-bot de DuckDuckGo (capturados de una
    // respuesta real, mismo criterio que la referencia) — cualquiera de estos en el HTML de
    // respuesta significa "bloqueado", no "sin resultados".
    private val BLOCKED_BODY_MARKERS = listOf(
        "anomaly-modal",
        "select all squares",
        "unusual traffic",
        "are you a robot",
        "captcha",
        "detected an automated request"
    )

    private val EMPTY_BODY_MARKERS = listOf(
        "no results.",
        "no  results.",
        "did not match any documents"
    )

    private val BLOCKED_STATUS_CODES = setOf(202, 403, 429)

    fun search(query: String): SearchOutcome {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return SearchOutcome.Empty

        synchronized(stateLock) {
            val now = System.currentTimeMillis()
            if (now < circuitOpenUntilMs) {
                return SearchOutcome.CircuitOpen(circuitOpenUntilMs - now)
            }
        }

        return try {
            val response = Jsoup.connect(SEARCH_URL)
                .userAgent(USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "es-ES,es;q=0.9,en-US;q=0.8,en;q=0.7")
                .header("Referer", "https://duckduckgo.com/")
                .data("q", trimmed)
                .timeout(TIMEOUT_MS)
                .followRedirects(true)
                .ignoreHttpErrors(true) // clasificamos el status code nosotros, no dejamos que Jsoup lo convierta en excepción
                .execute()

            val statusCode = response.statusCode()
            if (statusCode in BLOCKED_STATUS_CODES || statusCode >= 500) {
                onFailure()
                return SearchOutcome.Blocked("http_$statusCode")
            }

            val outcome = classify(response.parse())
            if (outcome is SearchOutcome.Blocked) onFailure() else onSuccess()
            outcome
        } catch (e: Exception) {
            onFailure()
            SearchOutcome.Error(e.message ?: e.javaClass.simpleName)
        }
    }

    /** Clasificación honesta de la respuesta — ver la documentación de clase para el criterio. */
    private fun classify(doc: Document): SearchOutcome {
        val bodyLower = doc.body()?.text()?.lowercase().orEmpty()
        if (BLOCKED_BODY_MARKERS.any { bodyLower.contains(it) }) {
            return SearchOutcome.Blocked("anti_bot_challenge_detected")
        }

        val resultNodes = doc.select("div.result, div.web-result")
        if (resultNodes.isNotEmpty()) {
            val items = resultNodes.mapNotNull { node -> parseResultNode(node) }.take(MAX_RESULTS)
            // Los nodos existían pero ninguno parseó (título/href faltante) — markup cambió de
            // forma que no reconocemos, no es lo mismo que "0 resultados reales".
            return if (items.isNotEmpty()) SearchOutcome.Results(items) else SearchOutcome.Blocked("unrecognized_result_markup")
        }

        if (EMPTY_BODY_MARKERS.any { bodyLower.contains(it) }) {
            return SearchOutcome.Empty
        }

        // Shape no reconocido (ni resultados, ni marcador de bloqueo, ni marcador de vacío
        // genuino) — un falso positivo reintentable (Blocked) es más seguro que decirle al
        // usuario/modelo que la web no tiene nada cuando el markup simplemente cambió.
        return SearchOutcome.Blocked("unrecognized_response_shape")
    }

    private fun parseResultNode(node: org.jsoup.nodes.Element): SearchResultItem? {
        val titleEl = node.selectFirst("a.result__a") ?: return null
        val title = titleEl.text().trim()
        val rawHref = titleEl.attr("href")
        if (title.isEmpty() || rawHref.isEmpty()) return null
        val url = resolveRealUrl(rawHref)
        // Protección SSRF (ver SearchEgressGuard) — un resultado cuya URL cae en un rango
        // privado/local (poco probable en DuckDuckGo real, pero el guard no confía en la
        // fuente) se descarta antes de mostrarse, no solo antes de "seguirse".
        if (!SearchEgressGuard.isUrlSafeToFetch(url)) return null
        val snippet = node.selectFirst(".result__snippet")?.text()?.trim().orEmpty()
        return SearchResultItem(title = title, url = url, snippet = snippet)
    }

    /**
     * DuckDuckGo envuelve cada resultado en un link de redirección propio
     * (`//duckduckgo.com/l/?uddg=<url-encoded>&rut=...`) — esto decodifica la URL real del
     * parámetro `uddg`. Si el href ya es una URL directa (no matchea el patrón de redirect),
     * se devuelve tal cual.
     */
    private fun resolveRealUrl(href: String): String {
        val normalized = if (href.startsWith("//")) "https:$href" else href
        return try {
            val uri = java.net.URI(normalized)
            val query = uri.rawQuery ?: return normalized
            val uddgParam = query.split("&").firstOrNull { it.startsWith("uddg=") } ?: return normalized
            URLDecoder.decode(uddgParam.removePrefix("uddg="), "UTF-8")
        } catch (e: Exception) {
            normalized
        }
    }

    private fun onFailure() {
        synchronized(stateLock) {
            consecutiveFailures++
            if (consecutiveFailures >= FAILURE_THRESHOLD) {
                circuitOpenUntilMs = System.currentTimeMillis() + COOLDOWN_MS
            }
        }
    }

    private fun onSuccess() {
        synchronized(stateLock) {
            consecutiveFailures = 0
            circuitOpenUntilMs = 0L
        }
    }
}

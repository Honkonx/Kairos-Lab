package com.termux.app.util

import android.content.Context
import com.termux.app.ModuleController
import com.termux.shared.termux.TermuxConstants
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Servidor HTTP embebido de "Homelab" — panel de control real del teléfono como VPS/homelab
 * desde CUALQUIER navegador, pedido explícito del usuario ("crear un panel web sea por tunnel o
 * ip para controlar el movil como vps/homelab, pero bien"). Mismo patrón crudo de
 * `ServerSocket`/`Socket` que [NubeServer] (sin librerías nuevas, ver cabecera de ese archivo
 * para el razonamiento completo de por qué HTTP a mano en vez de NanoHTTPD/`com.sun.net.httpserver`)
 * — gateo por token igual que Nube (`$HOME/.homelab_token`), nunca sin protección (ver
 * `.claude/rules/kairos-secrets-never-revealed.md`).
 *
 * Rutas:
 * - `GET /?token=...` — página HTML con métricas del sistema ([SystemMetrics]) + lista de
 *   módulos con botón iniciar/detener + lista de automatizaciones (solo lectura).
 * - `POST /module?token=...&id=...&action=start|stop` — inicia/detiene un módulo real vía
 *   [com.termux.app.ModuleController], mismo control que la pantalla nativa de Módulos.
 * - `GET /status?token=...` — JSON crudo de [buildMetricsJson] (para un futuro cliente que
 *   quiera pollear sin recargar la página completa; no usado por la página HTML de esta ronda,
 *   que hace un refresh HTML simple — YAGNI, no se agrega JS/fetch sin necesidad real todavía).
 *
 * A diferencia de Nube, esta página SÍ necesita JS mínimo para que los botones de
 * iniciar/detener no recarguen la página entera en cada click — se relaja el CSP de
 * `script-src 'none'` a `script-src 'unsafe-inline'` SOLO en esta página (el único script es estático y embebido; auditoría 2026-10-09 corrigió este comentario, la cabecera real ya emitía unsafe-inline), con un
 * único script embebido que hace `fetch()` con el mismo token ya presente en la URL.
 */
object HomelabServer {

    private const val DEFAULT_PORT = 8972
    private const val MAX_CONCURRENT_CONNECTIONS = 6
    private const val SOCKET_TIMEOUT_MS = 15_000
    private const val ACTION_WAIT_SECONDS = 8L
    private const val SSE_INTERVAL_MS = 4_000L
    private const val SSE_MAX_DURATION_MS = 30 * 60 * 1000L
    private const val TEXT_PLAIN = "text/plain; charset=utf-8"
    private const val TEXT_HTML = "text/html; charset=utf-8"
    private const val APP_JSON = "application/json; charset=utf-8"

    private val HOME = TermuxConstants.TERMUX_HOME_DIR_PATH
    private val tokenFile: File by lazy { File(HOME, ".homelab_token") }
    val token: String by lazy { loadOrCreateToken() }

    var port: Int = DEFAULT_PORT
        private set

    private var serverSocket: ServerSocket? = null
    private var executor: ExecutorService? = null
    private val activeConnections = AtomicInteger(0)
    @Volatile private var appContext: Context? = null

    // Estado propio para el delta de CPU% (ver SystemMetrics.cpuPercent) — una sola muestra
    // "anterior" compartida entre requests HTTP consecutivos, mismo criterio que
    // MonitorFragment guarda lastCpuTotal/lastCpuIdle entre ticks de su propio polling.
    @Volatile private var lastCpuSample: SystemMetrics.CpuSample? = null

    val isRunning: Boolean get() = serverSocket?.isClosed == false

    private fun loadOrCreateToken(): String {
        try {
            if (tokenFile.exists()) {
                val existing = tokenFile.readText().trim()
                if (existing.isNotEmpty()) return existing
            }
        } catch (_: Exception) { }
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        val generated = bytes.joinToString("") { "%02x".format(it) }
        try { tokenFile.writeText(generated) } catch (_: Exception) { }
        return generated
    }

    @Synchronized
    fun start(preferredPort: Int = DEFAULT_PORT, context: Context? = null): Boolean {
        if (isRunning) return true
        context?.let { appContext = it.applicationContext }
        return try {
            val socket = ServerSocket()
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(preferredPort))
            serverSocket = socket
            port = preferredPort
            val pool = Executors.newFixedThreadPool(MAX_CONCURRENT_CONNECTIONS)
            executor = pool
            Thread { acceptLoop(socket, pool) }.apply { isDaemon = true; start() }
            true
        } catch (_: Exception) {
            serverSocket = null
            false
        }
    }

    @Synchronized
    fun stop() {
        try { serverSocket?.close() } catch (_: Exception) { }
        serverSocket = null
        executor?.shutdownNow()
        executor = null
    }

    private fun acceptLoop(socket: ServerSocket, pool: ExecutorService) {
        while (!socket.isClosed) {
            val client = try { socket.accept() } catch (_: Exception) { break }
            if (activeConnections.get() >= MAX_CONCURRENT_CONNECTIONS) {
                try { client.close() } catch (_: Exception) { }
                continue
            }
            pool.submit {
                activeConnections.incrementAndGet()
                try {
                    client.soTimeout = SOCKET_TIMEOUT_MS
                    handleClient(client)
                } catch (_: Exception) {
                } finally {
                    activeConnections.decrementAndGet()
                    try { client.close() } catch (_: Exception) { }
                }
            }
        }
    }

    private fun handleClient(client: Socket) {
        val input = client.getInputStream()
        val output = client.getOutputStream()

        val requestLine = readLine(input) ?: return
        val parts = requestLine.split(" ")
        if (parts.size < 2) { writeText(output, 400, TEXT_PLAIN, "400 Bad Request"); return }
        val method = parts[0]

        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0) headers[line.substring(0, idx).trim().lowercase(Locale.US)] = line.substring(idx + 1).trim()
        }
        // Body (solo se usa para POST /module, formulario corto — nunca multipart acá).
        val bodyText = if (method == "POST") {
            val len = headers["content-length"]?.toIntOrNull() ?: 0
            readExact(input, len)
        } else ""

        val (path, query) = splitPathQuery(parts[1])
        val params = parseQuery(query) + parseQuery(bodyText)

        val providedToken = params["token"].orEmpty()
        if (!MessageDigest.isEqual(providedToken.toByteArray(Charsets.UTF_8), token.toByteArray(Charsets.UTF_8))) {
            writeText(output, 403, TEXT_PLAIN, "403 Forbidden — token inválido o ausente")
            return
        }

        val ctx = appContext
        when {
            method == "GET" && path == "/" -> serveIndex(output, ctx)
            method == "GET" && path == "/status" -> serveStatusJson(output, ctx)
            method == "GET" && path == "/events" -> serveEvents(output, ctx, client)
            method == "POST" && path == "/module" -> handleModuleAction(output, ctx, params["id"].orEmpty(), params["action"].orEmpty())
            else -> writeText(output, 404, TEXT_PLAIN, "404 Not Found")
        }
    }

    /**
     * `GET /events?token=...` — Server-Sent Events: reemplaza el `<meta http-equiv="refresh">`
     * de 10s de la página HTML (recarga completa, parpadeo, pierde el estado de scroll) por
     * actualizaciones en vivo sin recargar. Pedido indirecto del usuario ("gateway, homelab,
     * dashboard" como prioridad —) y hallazgo #1 por valor/costo de la
     * investigación de Homelab de esta ronda (docs/arquitectura/BACKLOG_DIFERIDO_2026-10-26.md
     * cita SSE de `referencia/homelab/web-appliance-dashboard-main`, MIT (corregido 2026-11-02, decía Apache-2.0), como patrón —
     * esta implementación es propia, sobre el mismo `ServerSocket`/`Socket` crudo que ya usa
     * todo este archivo, no copiada).
     *
     * [client] se necesita para desactivar `soTimeout` (los 15s de [SOCKET_TIMEOUT_MS] son para
     * LEER el pedido inicial, no para mantener la conexión de escritura — SSE no vuelve a leer
     * nada) y para que `client.isClosed`/la excepción de escritura corten el bucle cuando el
     * navegador cierra la pestaña. Cada conexión SSE ocupa un hilo del pool
     * ([MAX_CONCURRENT_CONNECTIONS]=6) mientras dura — aceptable para un panel de un solo
     * usuario, no pensado para muchos visores simultáneos.
     */
    private fun serveEvents(output: OutputStream, ctx: Context?, client: Socket) {
        client.soTimeout = 0
        val headers = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream; charset=utf-8\r\n" +
            "Cache-Control: no-store\r\nConnection: keep-alive\r\nX-Content-Type-Options: nosniff\r\n\r\n"
        try {
            output.write(headers.toByteArray(Charsets.UTF_8))
            output.flush()
            val deadline = System.currentTimeMillis() + SSE_MAX_DURATION_MS
            while (!client.isClosed && System.currentTimeMillis() < deadline) {
                val payload = "data: ${buildMetricsJson(ctx)}\n\n"
                output.write(payload.toByteArray(Charsets.UTF_8))
                output.flush()
                Thread.sleep(SSE_INTERVAL_MS)
            }
        } catch (_: Exception) {
            // Pestaña cerrada / red cortada — nada que limpiar, el `finally` de acceptLoop cierra el socket.
        }
    }

    // ── Métricas — reusa SystemMetrics tal cual, mismo objeto que HomelabFragment ───────────

    private fun buildMetricsJson(ctx: Context?): org.json.JSONObject {
        val ram = SystemMetrics.readRam()
        val storage = SystemMetrics.readStorage()
        val sample = SystemMetrics.sampleCpu()
        val cpuPct = SystemMetrics.cpuPercent(lastCpuSample, sample)
        lastCpuSample = sample
        val net = ctx?.let { SystemMetrics.networkInfo(it) }
        val tempC = ctx?.let { SystemMetrics.batteryTemperatureC(it) }
        val batteryPct = ctx?.let { SystemMetrics.batteryLevel(it) }
        val modules = ctx?.let { SystemMetrics.moduleStates(it) } ?: emptyList()
        return org.json.JSONObject().apply {
            put("ram_used_mb", ram?.usedMb ?: -1)
            put("ram_total_mb", ram?.totalMb ?: -1)
            put("ram_percent", ram?.percent ?: -1)
            put("storage_used_gb", storage.usedGb)
            put("storage_total_gb", storage.totalGb)
            put("storage_percent", storage.percent)
            put("cpu_percent", cpuPct ?: -1)
            put("battery_temp_c", tempC ?: -1)
            put("battery_percent", batteryPct ?: -1)
            put("network_type", net?.type ?: "desconocido")
            put("internet_ok", net?.internetOk ?: false)
            put("local_ip", SystemMetrics.localIpAddress() ?: "—")
            // Lista completa (hallazgo real: antes solo se exponía la primera IP, descartando en
            // silencio cualquier otra interfaz activa -- ej. WiFi + hotspot a la vez). "local_ip"
            // (singular) se conserva tal cual para no romper a nadie que ya lo lea.
            put("local_ips", org.json.JSONArray(SystemMetrics.localIpAddresses()))
            put("modules", org.json.JSONArray().apply {
                modules.forEach { m ->
                    put(org.json.JSONObject().apply {
                        put("id", m.id); put("name", m.name); put("running", m.running)
                    })
                }
            })
            put("automations", org.json.JSONArray().apply {
                try {
                    AutomationManager.listAutomations().forEach { a ->
                        put(org.json.JSONObject().apply {
                            put("name", a.name)
                            put("enabled", a.enabled)
                            put("trigger", a.triggerType.name)
                            put("action", "${a.actionType.name} ${a.moduleId}")
                        })
                    }
                } catch (_: Exception) { }
            })
        }
    }

    private fun serveStatusJson(output: OutputStream, ctx: Context?) {
        writeText(output, 200, APP_JSON, buildMetricsJson(ctx).toString())
    }

    /**
     * Mejora de esta ronda (antes devolvía siempre `{"ok":true}` sin mirar el resultado real —
     * el usuario nunca se enteraba de un fallo real de arranque, ej. puerto que no responde,
     * salvo recargando y comparando el estado a mano). ModuleController.startModule/stopModule
     * ya corren en su propio hilo de fondo (ver ModuleController.kt) — acá se espera hasta
     * [ACTION_WAIT_SECONDS] a que terminen; si terminan dentro de esa ventana se reporta el
     * ok/mensaje REAL, si no (módulos lentos, ej. n8n en proot) se marca `pending:true` — no es
     * un fallo, sigue corriendo en segundo plano, el próximo `GET /status` reflejará el
     * resultado final (mismo criterio "acción disparada, resultado async" que ya usa la UI
     * nativa vía BaseModuleFragment).
     */
    private fun handleModuleAction(output: OutputStream, ctx: Context?, moduleId: String, action: String) {
        if (moduleId.isBlank() || (action != "start" && action != "stop")) {
            writeText(output, 400, TEXT_PLAIN, "400 Bad Request — falta id/action válidos")
            return
        }
        // Sin @Volatile a propósito: CountDownLatch.countDown()/await() ya establecen la
        // relación happens-before necesaria (java.util.concurrent garantiza visibilidad entre
        // el hilo que cuenta y el que espera) — un var local normal alcanza.
        val latch = java.util.concurrent.CountDownLatch(1)
        var actionOk = false
        var actionMessage = ""
        if (action == "start") {
            ModuleController.startModule(moduleId, ctx) { ok, msg -> actionOk = ok; actionMessage = msg; latch.countDown() }
        } else {
            ModuleController.stopModule(moduleId, ctx) { ok -> actionOk = ok; latch.countDown() }
        }
        val completed = try { latch.await(ACTION_WAIT_SECONDS, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) { false }
        val json = org.json.JSONObject().apply {
            put("ok", if (completed) actionOk else true)
            put("pending", !completed)
            put("message", if (completed && !actionOk) actionMessage.take(400) else "")
        }
        writeText(output, 200, APP_JSON, json.toString())
    }

    // ── Página HTML ──────────────────────────────────────────────────────────────────────────

    private fun serveIndex(output: OutputStream, ctx: Context?) {
        val metrics = buildMetricsJson(ctx)
        writeText(output, 200, TEXT_HTML, buildIndexHtml(metrics))
    }

    private fun pct(v: Int): String = if (v < 0) "—" else "$v%"

    // Rediseño 2026-10-08 (boceto ui_mockups/web/homelab_web_propuesta.html): el usuario
    // pidió una página "bonita" con color en lugar de un formulario plano. Mismas funciones que antes
    // (métricas, Iniciar/Detener módulos, automatizaciones, refresco cada 10 s, mismo endpoint /module);
    // cambia solo la presentación: anillos para CPU/RAM/almacenamiento, barras, chips de estado, tarjetas
    // con degradado y tema claro/oscuro según el sistema (prefers-color-scheme). Paleta hardcodeada igual
    // que NubeServer: página HTML standalone servida por socket crudo, sin AAPT, no puede usar ?attr/kairos*.

    /** Anillo SVG con el porcentaje en el centro (circunferencia r=26 → 163.4). */
    private fun ringHtml(label: String, pct: Int, detail: String): String {
        val p = pct.coerceIn(0, 100)
        val color = when { pct < 0 -> "var(--text3)"; p >= 90 -> "var(--red)"; p >= 70 -> "var(--amber)"; else -> "var(--green)" }
        val dash = "%.1f".format(java.util.Locale.US, p * 1.634)
        val shown = if (pct < 0) "—" else "$pct%"
        return "<div class=\"metric ring\"><svg viewBox=\"0 0 64 64\" class=\"rg\">" +
            "<circle cx=\"32\" cy=\"32\" r=\"26\" fill=\"none\" stroke=\"var(--bg3)\" stroke-width=\"7\"/>" +
            "<circle cx=\"32\" cy=\"32\" r=\"26\" fill=\"none\" stroke=\"$color\" stroke-width=\"7\" stroke-linecap=\"round\" stroke-dasharray=\"$dash 163.4\" transform=\"rotate(-90 32 32)\"/>" +
            "<text x=\"32\" y=\"36\" text-anchor=\"middle\" font-size=\"14\" font-weight=\"700\" fill=\"var(--text)\">$shown</text></svg>" +
            "<div><div class=\"ml\">$label</div><div class=\"mv\">$detail</div></div></div>"
    }

    /** Métrica con barra (temperatura, batería, red). [fill] 0..100. */
    private fun barHtml(label: String, value: String, fill: Int, color: String, small: Boolean = false): String =
        "<div class=\"metric\"><div class=\"ml\">$label</div><div class=\"mv\"${if (small) " style=\"font-size:12.5px\"" else ""}>$value</div>" +
            "<div class=\"bar\"><i style=\"width:${fill.coerceIn(0, 100)}%;background:$color\"></i></div></div>"

    private fun buildIndexHtml(m: org.json.JSONObject): String {
        val modulesArr = m.optJSONArray("modules")
        val modulesHtml = StringBuilder()
        for (i in 0 until (modulesArr?.length() ?: 0)) {
            val mod = modulesArr!!.getJSONObject(i)
            val running = mod.optBoolean("running", false)
            val id = mod.optString("id")
            val name = escapeHtml(mod.optString("name"))
            modulesHtml.append(
                "<div class=\"row\"><span class=\"dot ${if (running) "on" else "off"}\"></span>" +
                    "<span class=\"rn\">$name</span>" +
                    "<span class=\"st ${if (running) "on" else ""}\">${if (running) "En línea" else "Detenido"}</span>" +
                    "<button class=\"btn ${if (running) "stop" else "go"}\" onclick=\"toggleModule('${escapeJs(id)}','${if (running) "stop" else "start"}',this)\">" +
                    (if (running) "Detener" else "Iniciar") + "</button></div>"
            )
        }
        val automationsArr = m.optJSONArray("automations")
        val automationsHtml = StringBuilder()
        for (i in 0 until (automationsArr?.length() ?: 0)) {
            val a = automationsArr!!.getJSONObject(i)
            val enabled = a.optBoolean("enabled", false)
            automationsHtml.append(
                "<div class=\"row\"><span class=\"dot ${if (enabled) "on" else "off"}\"></span>" +
                    "<span class=\"rn\">${escapeHtml(a.optString("name"))}</span>" +
                    "<span class=\"rs\">${escapeHtml(a.optString("action"))}</span></div>"
            )
        }
        val ramDetail = "${m.optLong("ram_used_mb")} / ${m.optLong("ram_total_mb")} MB"
        val storageDetail = "%.1f / %.1f GB".format(java.util.Locale.US, m.optDouble("storage_used_gb"), m.optDouble("storage_total_gb"))
        val tempC = m.optDouble("battery_temp_c", -1.0)
        val temp = if (tempC < 0) "—" else "%.1f°C".format(java.util.Locale.US, tempC)
        val batt = m.optInt("battery_percent", -1)
        val battText = if (batt < 0) "—" else "$batt%"
        val netOk = m.optBoolean("internet_ok")
        val net = escapeHtml(m.optString("network_type")) + if (netOk) " · con internet" else " · sin internet"
        val ip = escapeHtml(m.optString("local_ip"))
        val tempFill = if (tempC < 0) 0 else ((tempC / 60.0) * 100).toInt()
        val tempColor = if (tempC >= 45) "var(--red)" else if (tempC >= 38) "var(--amber)" else "var(--green)"
        val battColor = if (batt in 0..15) "var(--red)" else if (batt in 16..30) "var(--amber)" else "var(--green)"

        return """
        <!doctype html><html lang="es"><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <title>Homelab — Kairos</title>
        <style>
          :root{--bg:#050505;--bg2:#0a0a0a;--bg3:#111111;--text:#e8e8e8;--text2:#8a8a8a;--text3:#5a5a5a;
            --green:#22c55e;--red:#ef4444;--amber:#f59e0b;--blue:#3b82f6;--purple:#6366f1;--border:#1f1f1f;
            --glow:rgba(34,197,94,.10);--chip-on:rgba(34,197,94,.14);--chip-off:rgba(120,120,120,.14);--btn-text:#04130a}
          @media (prefers-color-scheme:light){:root{--bg:#f7f6f3;--bg2:#ffffff;--bg3:#efede8;--text:#1a1a1c;--text2:#5b5b60;--text3:#8c8c90;
            --green:#16a34a;--red:#dc2626;--amber:#d97706;--blue:#2563eb;--purple:#4f46e5;--border:#e4e2dc;
            --glow:rgba(22,163,74,.10);--chip-on:rgba(22,163,74,.12);--chip-off:rgba(120,120,120,.12);--btn-text:#fff}}
          *{box-sizing:border-box}
          body{font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;margin:0;padding:20px 16px 48px;color:var(--text);
            background:radial-gradient(900px 360px at 50% -140px,var(--glow),transparent 62%),var(--bg)}
          .page{max-width:760px;margin:0 auto}
          .hd{display:flex;align-items:center;justify-content:space-between;gap:12px;flex-wrap:wrap;margin-bottom:6px}
          .brand{display:flex;align-items:center;gap:12px}
          .logo{width:40px;height:40px;border-radius:12px;display:grid;place-items:center;background:linear-gradient(135deg,var(--green),#0e7a3a);color:#fff}
          .logo svg{width:22px;height:22px}
          h1{font-size:20px;margin:0;letter-spacing:.2px}
          .sub{color:var(--text2);font-size:12px;margin:2px 0 0}
          .pill{display:inline-flex;align-items:center;gap:7px;font-size:11.5px;padding:4px 12px;border-radius:999px;border:1px solid rgba(34,197,94,.28);background:var(--chip-on);color:var(--green)}
          .dot{width:8px;height:8px;border-radius:50%;background:var(--text3);flex:0 0 auto}
          .dot.on{background:var(--green);animation:pulse 2.2s ease infinite}
          @keyframes pulse{0%{box-shadow:0 0 0 0 rgba(34,197,94,.45)}70%{box-shadow:0 0 0 6px rgba(34,197,94,0)}100%{box-shadow:0 0 0 0 rgba(34,197,94,0)}}
          @keyframes up{from{opacity:0;transform:translateY(8px)}to{opacity:1;transform:none}}
          .grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(210px,1fr));gap:10px;margin:16px 0 14px}
          .metric{background:linear-gradient(160deg,var(--bg2),var(--bg3));border:1px solid var(--border);border-radius:14px;padding:12px;animation:up .5s ease both}
          .metric.ring{display:flex;align-items:center;gap:10px}
          .rg{width:58px;height:58px;flex:0 0 auto}
          .ml{font-size:10.5px;color:var(--text3);text-transform:uppercase;letter-spacing:.6px}
          .mv{font-size:14px;font-weight:700;margin-top:3px}
          .bar{height:6px;background:var(--bg3);border:1px solid var(--border);border-radius:999px;margin-top:9px;overflow:hidden}
          .bar i{display:block;height:100%;border-radius:999px}
          .card{background:linear-gradient(180deg,var(--bg2),var(--bg));border:1px solid var(--border);border-radius:16px;padding:16px;margin-bottom:14px;animation:up .5s ease both}
          .card h2{font-size:12px;text-transform:uppercase;letter-spacing:.7px;color:var(--text2);margin:0 0 10px}
          .row{display:flex;align-items:center;gap:10px;padding:10px 0;border-top:1px solid var(--border)}
          .row:first-of-type{border-top:none}
          .rn{flex:1;font-size:13.5px;font-weight:600;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
          .rs{font-size:11px;color:var(--text3);font-family:ui-monospace,Menlo,monospace}
          .st{font-size:10.5px;padding:2px 9px;border-radius:999px;background:var(--chip-off);color:var(--text2)}
          .st.on{background:var(--chip-on);color:var(--green)}
          .btn{font:inherit;font-size:12px;font-weight:700;border-radius:9px;padding:7px 14px;cursor:pointer;border:1px solid transparent}
          .btn:active{transform:scale(.96)}
          .btn:disabled{opacity:.7;cursor:default}
          .btn.go{background:var(--green);color:var(--btn-text)}
          .btn.stop{background:rgba(239,68,68,.10);color:var(--red);border-color:rgba(239,68,68,.35)}
          .empty{color:var(--text3);font-size:12.5px;padding:10px 0;text-align:center}
          .toast{display:none;margin-top:14px;border:1px solid rgba(239,68,68,.35);background:rgba(239,68,68,.08);color:var(--red);border-radius:10px;padding:9px 12px;font-size:12px}
        </style>
        </head><body>
        <div class="page">
          <div class="hd">
            <div class="brand"><div class="logo"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M3 11l9-8 9 8v9a2 2 0 0 1-2 2h-4v-6H9v6H5a2 2 0 0 1-2-2z"/></svg></div>
              <div><h1>Homelab — Kairos</h1><p class="sub" id="liveSub">Panel de control del dispositivo · en vivo</p></div></div>
            <span class="pill"><span class="dot on" id="liveDot"></span><span id="ipPill">$ip</span></span>
          </div>
          <div class="grid" id="metricsGrid">
            ${ringHtml("CPU", m.optInt("cpu_percent", -1), pct(m.optInt("cpu_percent", -1)))}
            ${ringHtml("RAM", m.optInt("ram_percent", -1), ramDetail)}
            ${ringHtml("Almacenamiento", m.optInt("storage_percent", -1), storageDetail)}
            ${barHtml("Temperatura", temp, tempFill, tempColor)}
            ${barHtml("Batería", battText, if (batt < 0) 0 else batt, battColor)}
            ${barHtml("Red", net, 100, if (netOk) "var(--green)" else "var(--red)", small = true)}
          </div>
          <div class="card">
            <h2>Módulos</h2>
            ${if (modulesHtml.isEmpty()) "<p class=\"empty\">Sin módulos con proceso propio.</p>" else modulesHtml}
          </div>
          <div class="card">
            <h2>Automatizaciones</h2>
            ${if (automationsHtml.isEmpty()) "<p class=\"empty\">No hay automatizaciones configuradas.</p>" else automationsHtml}
          </div>
          <div class="toast" id="toast"></div>
        </div>
        <script>
        // Vivo por SSE — antes era <meta http-equiv="refresh" content="10">,
        // recarga completa de la página cada 10s. Mismas funciones ringHtml/barHtml de Kotlin,
        // reescritas en JS porque la metrica llega como JSON por /events, no como HTML armado.
        function pctTxt(v){ return v < 0 ? '—' : v + '%'; }
        function ringCard(label, p, detail){
          p = Math.max(0, Math.min(100, p));
          var color = p < 0 ? 'var(--text3)' : (p >= 90 ? 'var(--red)' : (p >= 70 ? 'var(--amber)' : 'var(--green)'));
          var dash = (p * 1.634).toFixed(1);
          return '<div class="metric ring"><svg viewBox="0 0 64 64" class="rg">' +
            '<circle cx="32" cy="32" r="26" fill="none" stroke="var(--bg3)" stroke-width="7"/>' +
            '<circle cx="32" cy="32" r="26" fill="none" stroke="' + color + '" stroke-width="7" stroke-linecap="round" stroke-dasharray="' + dash + ' 163.4" transform="rotate(-90 32 32)"/>' +
            '<text x="32" y="36" text-anchor="middle" font-size="14" font-weight="700" fill="var(--text)">' + pctTxt(p) + '</text></svg>' +
            '<div><div class="ml">' + label + '</div><div class="mv">' + detail + '</div></div></div>';
        }
        function barCard(label, value, fill, color, small){
          return '<div class="metric"><div class="ml">' + label + '</div><div class="mv"' + (small ? ' style="font-size:12.5px"' : '') + '>' + value + '</div>' +
            '<div class="bar"><i style="width:' + Math.max(0, Math.min(100, fill)) + '%;background:' + color + '"></i></div></div>';
        }
        function applyMetrics(m){
          var ramDetail = m.ram_used_mb + ' / ' + m.ram_total_mb + ' MB';
          var storageDetail = m.storage_used_gb.toFixed(1) + ' / ' + m.storage_total_gb.toFixed(1) + ' GB';
          var tempC = m.battery_temp_c;
          var temp = tempC < 0 ? '—' : tempC.toFixed(1) + '°C';
          var tempFill = tempC < 0 ? 0 : Math.round((tempC / 60.0) * 100);
          var tempColor = tempC >= 45 ? 'var(--red)' : (tempC >= 38 ? 'var(--amber)' : 'var(--green)');
          var batt = m.battery_percent;
          var battText = batt < 0 ? '—' : batt + '%';
          var battColor = (batt >= 0 && batt <= 15) ? 'var(--red)' : ((batt > 15 && batt <= 30) ? 'var(--amber)' : 'var(--green)');
          var net = m.network_type + (m.internet_ok ? ' · con internet' : ' · sin internet');
          document.getElementById('metricsGrid').innerHTML =
            ringCard('CPU', m.cpu_percent, pctTxt(m.cpu_percent)) +
            ringCard('RAM', m.ram_percent, ramDetail) +
            ringCard('Almacenamiento', m.storage_percent, storageDetail) +
            barCard('Temperatura', temp, tempFill, tempColor) +
            barCard('Batería', battText, batt < 0 ? 0 : batt, battColor) +
            barCard('Red', net, 100, m.internet_ok ? 'var(--green)' : 'var(--red)', true);
          document.getElementById('ipPill').textContent = m.local_ip;
        }
        if (window.EventSource) {
          var token0 = new URLSearchParams(window.location.search).get('token');
          var es = new EventSource('/events?token=' + encodeURIComponent(token0));
          es.onmessage = function(ev){ try { applyMetrics(JSON.parse(ev.data)); } catch (e) {} };
          es.onerror = function(){
            var d = document.getElementById('liveDot'); if (d) d.classList.remove('on');
            var s = document.getElementById('liveSub'); if (s) s.textContent = 'Panel de control del dispositivo · reconectando…';
          };
        } else {
          document.getElementById('liveSub').textContent = 'Panel de control del dispositivo · tu navegador no soporta actualización en vivo (SSE)';
        }
        function showError(msg) {
          var t = document.getElementById('toast');
          t.textContent = msg; t.style.display = 'block';
        }
        function toggleModule(id, action, btn) {
          btn.disabled = true; btn.textContent = '...';
          var token = new URLSearchParams(window.location.search).get('token');
          fetch('/module?token=' + encodeURIComponent(token), {
            method: 'POST',
            headers: {'Content-Type': 'application/x-www-form-urlencoded'},
            body: 'id=' + encodeURIComponent(id) + '&action=' + encodeURIComponent(action) + '&token=' + encodeURIComponent(token)
          }).then(function(r){ return r.json(); }).then(function(data){
            // Feedback real (ver HomelabServer.handleModuleAction() para qué significa cada campo).
            if (!data.ok) {
              showError((action === 'start' ? 'No se pudo iniciar: ' : 'No se pudo detener: ') + (data.message || 'error desconocido'));
              btn.disabled = false; btn.textContent = action === 'start' ? 'Iniciar' : 'Detener';
              return;
            } else if (data.pending) {
              btn.textContent = 'En curso…';
            }
            setTimeout(function(){ location.reload(); }, data.pending ? 2500 : 1000);
          }).catch(function(){
            showError('No se pudo contactar al panel web — revisa la conexión.');
            btn.disabled = false;
          });
        }
        </script>
        </body></html>
        """.trimIndent()
    }

    // ── HTTP helpers (mismo patrón crudo que NubeServer) ────────────────────────────────────

    private fun readLine(input: InputStream): String? {
        val buffer = ByteArrayOutputStream()
        var readAny = false
        while (true) {
            val b = input.read()
            if (b == -1) return if (readAny) buffer.toString("ISO-8859-1") else null
            readAny = true
            if (b == '\n'.code) {
                val bytes = buffer.toByteArray()
                val len = if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) bytes.size - 1 else bytes.size
                return String(bytes, 0, len, Charsets.ISO_8859_1)
            }
            buffer.write(b)
        }
    }

    private fun readExact(input: InputStream, len: Int): String {
        if (len <= 0) return ""
        val buf = ByteArray(len)
        var read = 0
        while (read < len) {
            val n = input.read(buf, read, len - read)
            if (n <= 0) break
            read += n
        }
        return String(buf, 0, read, Charsets.UTF_8)
    }

    private fun splitPathQuery(raw: String): Pair<String, String> {
        val idx = raw.indexOf('?')
        return if (idx < 0) raw to "" else raw.substring(0, idx) to raw.substring(idx + 1)
    }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        return query.split("&").mapNotNull { pair ->
            if (pair.isBlank()) return@mapNotNull null
            val idx = pair.indexOf('=')
            if (idx < 0) urlDecode(pair) to "" else urlDecode(pair.substring(0, idx)) to urlDecode(pair.substring(idx + 1))
        }.toMap()
    }

    private fun urlDecode(s: String): String = try { URLDecoder.decode(s, "UTF-8") } catch (_: Exception) { s }

    private fun escapeHtml(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&#39;")

    private fun escapeJs(s: String): String = s.replace("\\", "\\\\").replace("'", "\\'")

    private fun statusText(code: Int): String = when (code) {
        200 -> "OK"; 400 -> "Bad Request"; 403 -> "Forbidden"; 404 -> "Not Found"
        else -> "Unknown"
    }

    private fun writeText(output: OutputStream, status: Int, contentType: String, body: String) {
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        // script-src 'unsafe-inline' (la cabecera real, ver más abajo): el único <script> de esta página es inline,
        // pero Android WebView/navegadores modernos exigen 'unsafe-inline' o un nonce para que
        // un <script> inline corra bajo CSP — como este panel es HTML servido crudo (sin build
        // step para generar un nonce por request de forma barata), se documenta la excepción acá
        // en vez de forzar 'unsafe-inline' silenciosamente: el bloque toggleModule() es estático,
        // sin interpolar ningún dato de usuario dentro del <script> en sí (los valores dinámicos
        // viajan por atributos onclick ya escapados con escapeJs(), no por el cuerpo del script).
        // CAUSA del reporte "fondo blanco con botones" (2026-10-08): sin style-src,
        // default-src 'self' bloquea el <style> y los style="" inline y la página se ve sin CSS.
        // Solo se permiten ESTILOS inline; los scripts siguen como estaban.
        val cspHeader = if (contentType.startsWith("text/html")) {
            "Content-Security-Policy: default-src 'self'; style-src 'unsafe-inline'; img-src 'self' data:; script-src 'unsafe-inline'; frame-ancestors 'none'\r\n"
        } else ""
        val headers = "HTTP/1.1 $status ${statusText(status)}\r\n" +
            "Content-Type: $contentType\r\n" +
            "Content-Length: ${bodyBytes.size}\r\n" +
            cspHeader +
            "Connection: close\r\n\r\n"
        output.write(headers.toByteArray(Charsets.US_ASCII))
        output.write(bodyBytes)
        output.flush()
    }

    fun localIpAddress(): String? = SystemMetrics.localIpAddress()
}

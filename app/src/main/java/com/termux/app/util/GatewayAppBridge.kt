package com.termux.app.util

import android.content.Context
import com.termux.app.ModuleController
import com.termux.app.data.ModuleCatalog
import com.termux.app.data.ModuleInstalled
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class ModuleSummary(val id: String, val name: String, val installed: Boolean, val running: Boolean, val port: Int? = null)

/**
 * Configuración del espejo de pantalla que `screen.config` guarda y `screen.start` usa. No vive
 * dentro de [ScreenMirrorManager] porque [GatewayBridgeCore.screenMirror] crea una instancia NUEVA en cada
 * llamada (no hay estado entre `screen.config` y el `screen.start` siguiente) — este `var` vive en
 * [GatewayBridgeCore], que sí persiste mientras el gateway esté arriba. Los nombres de campo copian 1:1 los
 * argumentos reales de `Options.parse` (ver [ScreenMirrorManager.start]) para que no haga falta traducir nada.
 */
data class ScreenStartConfig(
    val maxSize: Int = 1280,
    val bitRate: Int = 4_000_000,
    val maxFps: Int = 15,
    val audio: Boolean = false,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val orientation: String? = null,
)

/** Resultado de una operación sobre un módulo; [pending] = sigue en segundo plano (no es un fallo). */
data class ModuleOpResult(val ok: Boolean, val pending: Boolean = false, val message: String = "")

/** Lo que el gateway puede pedirle a la app sobre los módulos. Se separa para poder probar el puente sin Android. */
interface GatewayModuleOps {
    fun list(): List<ModuleSummary>
    fun status(id: String): ModuleSummary?
    fun start(id: String): ModuleOpResult
    fun stop(id: String): ModuleOpResult
    fun install(id: String): ModuleOpResult
    /** Desinstalación "simple" (no "profunda"), misma vía que [PluginsFragment]'s
     *  "Desinstalar" sin tildar "borrado profundo" -- ver ModuleController.uninstallModule(). */
    fun uninstall(id: String): ModuleOpResult
}

/** `system.metrics` — una lectura puntual de CPU/RAM/almacenamiento/batería. Se separa de [SystemMetrics] (sin
 *  estado propio) para poder probar el puente con una implementación falsa, igual que [GatewayModuleOps]. */
interface GatewayMetricsOps {
    fun read(): JSONObject
}

/** Implementación real: reusa [SystemMetrics] (el mismo /proc-reading que ya usa `MonitorFragment`/`HomelabServer`
 *  — ver cabecera de ese archivo). El % de CPU exige 2 muestras separadas en el tiempo; se toman las dos acá mismo,
 *  con una espera corta, porque el gateway no mantiene un estado de "muestra anterior" entre llamadas. */
class AppMetricsOps(private val context: Context) : GatewayMetricsOps {
    override fun read(): JSONObject {
        val first = SystemMetrics.sampleCpu()
        Thread.sleep(CPU_SAMPLE_GAP_MS)
        val cpu = SystemMetrics.cpuPercent(first, SystemMetrics.sampleCpu())
        val ram = SystemMetrics.readRam()
        val storage = SystemMetrics.readStorage()
        val battery = SystemMetrics.batteryLevel(context)
        return JSONObject()
            .put("cpu_percent", cpu?.let { it } ?: JSONObject.NULL)
            .put("ram_used_mb", ram?.usedMb ?: JSONObject.NULL)
            .put("ram_total_mb", ram?.totalMb ?: JSONObject.NULL)
            .put("storage_used_gb", storage.usedGb)
            .put("storage_total_gb", storage.totalGb)
            .put("storage_has_permission", storage.hasPermission)
            .put("battery_percent", battery?.let { it } ?: JSONObject.NULL)
    }

    private companion object {
        /** Ventana entre las 2 muestras de `/proc/stat` — suficiente para un delta de jiffies
         *  medible sin alargar demasiado la respuesta del panel. */
        const val CPU_SAMPLE_GAP_MS = 200L
    }
}

/** Resultado de `cloud.start`/`cloud.stop` — el propio servidor Nube ([NubeServer]) ya corre en la app, esto solo
 *  lo arranca/para desde el gateway reusando el método existente que ya usa `NubeFragment.kt`. */
data class CloudOpResult(val ok: Boolean, val message: String = "")

/** Resultado de `cloud.share_link` — [url] ya trae el token embebido (mismo patrón que
 *  `NubeFragment.refreshServerStatus()`, que también muestra `http://ip:puerto/?token=...` en la UI nativa) —
 *  no es un secreto guardado por el usuario (`.claude/rules/kairos-secrets-never-revealed.md` no aplica, ver
 *  ese mismo patrón ya expuesto en pantalla), es la URL pensada para compartirse. */
data class CloudShareResult(val ok: Boolean, val url: String = "", val message: String = "")

/** `cloud.*` — estado/arranque/parada/enlace de Nube ([NubeServer]) para el gateway. `status()` nunca expone el
 *  token (`.claude/rules/kairos-secrets-never-revealed.md`); los archivos de `$HOME/nube` ya son accesibles vía
 *  `fs.*` (`HomeFiles` no excluye esa carpeta). `shareLink()` sí lo incluye, a propósito, porque es la URL que
 *  la persona va a copiar/compartir (ver [CloudShareResult]). */
interface GatewayCloudOps {
    fun status(): JSONObject
    fun start(): CloudOpResult
    fun stop(): CloudOpResult
    fun shareLink(path: String): CloudShareResult
}

/** Implementación real: delega en [NubeServer], el mismo objeto que ya usa `NubeFragment.kt` — no duplica
 *  lógica de arranque/parada, conteo de archivos ([NubeServer.fileCount]) ni armado de URL ([NubeServer.shareLinkFor]). */
class AppCloudOps(private val context: Context) : GatewayCloudOps {
    override fun status(): JSONObject {
        val running = NubeServer.isRunning
        val json = JSONObject().put("running", running).put("port", NubeServer.port)
        if (running) json.put("file_count", NubeServer.fileCount())
        return json
    }

    override fun start(): CloudOpResult {
        if (NubeServer.isRunning) return CloudOpResult(true, "ya estaba en marcha")
        val ok = NubeServer.start(context = context.applicationContext)
        return CloudOpResult(ok, if (ok) "" else "no se pudo iniciar en el puerto ${NubeServer.port}")
    }

    override fun stop(): CloudOpResult {
        NubeServer.stop()
        return CloudOpResult(true)
    }

    override fun shareLink(path: String): CloudShareResult {
        if (!NubeServer.isRunning) return CloudShareResult(false, message = "Nube no está en marcha")
        val url = NubeServer.shareLinkFor(path) ?: return CloudShareResult(false, message = "ruta inválida o fuera de la carpeta nube")
        return CloudShareResult(true, url)
    }
}

/** Resultado de `agent.ask` — [ok]=false cuando el motor no está instalado, no respondió, o no entendió el pedido. */
data class AgentAskResult(val ok: Boolean, val reply: String)

/**
 * `agent.ask` — el Agente conversacional de Kairos para el gateway. Reusa el mismo cliente/motor que la pantalla
 * nativa ([com.termux.app.ui.KairosAgentFragment]): [KairosAgentClient.parse] (jerga sin IA) → si no entiende,
 * la IA del motor (`interpretar`) → [KairosAgentClient.run] + [KairosAgentClient.format]. Nunca ejecuta comandos
 * libres: solo el catálogo fijo de intents del motor (refrescar, procesos, estado, instalar, iniciar, detener,
 * historial).
 */
interface GatewayAgentOps {
    fun ask(text: String): AgentAskResult
}

/**
 * Implementación real. Un pedido en texto libre puede resolver en una intent de solo lectura ("estado") o en una
 * que muta el dispositivo (instalar/iniciar/detener) — cuál de las dos no se sabe ANTES de parsear el texto, así
 * que esta acción entera pide el nivel más conservador ("total") en el manifiesto (`default_manifest.json`) en
 * vez de intentar distinguir el riesgo por adelantado.
 */
class AppAgentOps(private val context: Context) : GatewayAgentOps {
    override fun ask(text: String): AgentAskResult {
        if (!KairosAgentClient.isInstalled()) {
            return AgentAskResult(false, "El Agente no está instalado todavía; instálalo desde la app (Más → Agente).")
        }
        if (text.isBlank()) return AgentAskResult(false, "pedido vacío")
        val modules = ModuleCatalog.load(context).map { it.id to it.name }
        val kb = KairosAgentKnowledge.load(context)
        val parsed = KairosAgentClient.parse(text, modules, kb)
        if (parsed != null) return runParsed(parsed)
        kb?.answer(text)?.let { return AgentAskResult(true, it) }
        return askRouter(text)
    }

    private fun runParsed(p: KairosAgentClient.Parsed): AgentAskResult {
        // KairosAgentFragment espera hasta 1800 s para "instalar" porque ahí corre en un Thread propio de la
        // UI sin más límite. Acá el gateway (default_manifest.json "agent.ask") tiene un timeout_sec=600 —
        // el máximo que acepta Registry.validate() — así que un timeout más largo solo dejaría el hilo de
        // GatewayAppBridge (pool de 4) colgado después de que el gateway ya respondió con error por su cuenta.
        val timeout = if (p.intent == "instalar") INSTALL_TIMEOUT_SEC else DEFAULT_TIMEOUT_SEC
        val json = KairosAgentClient.run(listOf(p.intent) + p.args, timeout)
            ?: return AgentAskResult(false, "El motor del Agente no respondió.")
        return AgentAskResult(true, KairosAgentClient.format(p.intent, json))
    }

    private companion object {
        const val DEFAULT_TIMEOUT_SEC = 200L
        const val INSTALL_TIMEOUT_SEC = 580L
    }

    /** Pedido que no encaja con ninguna intent conocida → router con IA del motor, mismo flujo que `askRouter()` en
     *  [com.termux.app.ui.KairosAgentFragment] (sin el diálogo de confirmación: el gateway ya exigió nivel "total"
     *  para toda la acción `agent.ask`, así que cubre también lo que el router elija ejecutar). */
    private fun askRouter(text: String): AgentAskResult {
        val json = KairosAgentClient.run(listOf("interpretar", text), 60)
            ?: return AgentAskResult(false, "No entendí el pedido.")
        if (json.has("error") && !json.optBoolean("ok", true)) return AgentAskResult(false, "No entendí el pedido.")
        if (json.optBoolean("pending_confirmation")) {
            val chosen = json.optString("intent")
            val args = json.optJSONObject("args")?.optString("id")?.takeIf { it.isNotBlank() }?.let { listOf(it) }.orEmpty()
            return runParsed(KairosAgentClient.Parsed(chosen, args, true, args.firstOrNull()))
        }
        val result = json.optJSONObject("resultado")?.let { KairosAgentClient.format(json.optString("intent_elegida"), it) }
        return result?.let { AgentAskResult(true, it) } ?: AgentAskResult(false, "No entendí el pedido.")
    }
}

/** Resultado de `ai.chat` — [ok]=false cuando el motor no está instalado o ningún endpoint de IA respondió. */
data class AiChatResult(val ok: Boolean, val reply: String)

/**
 * `ai.chat` — conversación cruda con el modelo de IA local/externo configurado (llama-server :8085, Ollama
 * :11434, o la API externa de `configurar-ia`), SIN el filtro de intents fijas del Agente. Distinta de
 * `agent.ask` ([GatewayAgentOps]: estado/instalar/iniciar/detener un módulo) y del futuro "bot Kairos" de
 * Telegram (todavía sin empezar, ver `docs/arquitectura/PLAN_MAESTRO_GATEWAY_HOMELAB_AGENTE_2026-10-10.md`).
 * Nunca ejecuta nada sobre el dispositivo — reusa el mismo binario `kairos-agent` (subcomando `chat-directo`,
 * ver `modulos/kairos_agent.sh` función `chat_directo()`) solo porque ya sabe resolver los endpoints de IA;
 * no pasa por [KairosAgentClient.parse]/[KairosAgentClient.run] ni por el catálogo `INTENTS` del motor.
 */
interface GatewayAiChatOps {
    fun chat(text: String, history: List<Pair<String, String>>): AiChatResult
}

/** Implementación real: [KairosAgentClient.chatDirecto]. Riesgo "read" en el manifiesto (ver
 *  `default_manifest.json`) porque, a diferencia de `agent.ask`, esta acción nunca puede mutar el dispositivo
 *  — en el peor caso devuelve texto generado por un LLM, nunca instala/inicia/detiene nada. */
class AppAiChatOps : GatewayAiChatOps {
    override fun chat(text: String, history: List<Pair<String, String>>): AiChatResult {
        if (!KairosAgentClient.isInstalled()) {
            return AiChatResult(false, "El Agente no está instalado todavía; instálalo desde la app (Más → Agente).")
        }
        if (text.isBlank()) return AiChatResult(false, "mensaje vacío")
        val json = KairosAgentClient.chatDirecto(text, history)
            ?: return AiChatResult(false, "Ningún modelo de IA (local o configurado) respondió.")
        if (!json.optBoolean("ok", true)) return AiChatResult(false, json.optString("error").ifBlank { "no se pudo completar" })
        return AiChatResult(true, json.optString("reply").ifBlank { "(sin respuesta)" })
    }
}

/**
 * `minipc.vnc_status` (ver `.claude/rules/kairos-gateway-paridad-total.md` y
 * `docs/arquitectura/briefs_2026-10-10/BRIEF_minipc_vnc_x11_gateway.md`) — estado real del servidor VNC
 * (TigerVNC) de Mini PC (`EntornoVncTab.kt`), de solo lectura. Nunca arranca/detiene nada desde el gateway
 * todavía — ver `.claude/rules/kairos-vnc-scope.md`: VNC sigue acotado a QEMU y Mini PC, esto NO se extiende
 * a Ciberseguridad/Kali.
 */
interface GatewayMinipcOps {
    fun vncStatus(): JSONObject

    /**
     * Arranca el escritorio gráfico — nativo en Termux (sin [distro]) o dentro de una distro ya
     * preparada (con [distro]) — sobre el MISMO servidor X11 embebido que ya usa `EntornoFragment`.
     * No es VNC nuevo (`.claude/rules/kairos-nunca-vnc-nuevo.md`): es lo mismo que la app ya hace,
     * ahora también desde el gateway (ver meta 5 — "ejecutar entorno gráfico
     * normal nativo en termux o en distro... en su tab en el gateway"). Para VERLO desde el panel
     * no hace falta nada nuevo: `screen.*` (el relay de pantalla) ya captura la pantalla completa
     * del dispositivo, incluido lo que X11Service dibuje — basta con `screen.start` + "Pantalla".
     */
    fun desktopStart(de: String, distro: String?): JSONObject

    /** Detiene el escritorio actual (nativo o de distro), sin apagar el servidor X11 embebido. */
    fun desktopStop(): JSONObject

    /** Estado real del escritorio gráfico (modo activo, GPU, etc. — `EntornoNative.status()`). */
    fun desktopStatus(): JSONObject
}

/** Implementación real: delega en [EntornoNative] — las mismas funciones que ya usa `EntornoFragment`. */
class AppMinipcOps : GatewayMinipcOps {
    override fun vncStatus(): JSONObject = EntornoNative.vncGatewayStatus()
    override fun desktopStart(de: String, distro: String?): JSONObject =
        if (distro.isNullOrBlank()) EntornoNative.startDesktop(de) else EntornoNative.startDistroDesktop(distro, de)
    override fun desktopStop(): JSONObject = EntornoNative.stopDesktopSession()
    override fun desktopStatus(): JSONObject = EntornoNative.status()
}

/**
 * Núcleo del endpoint "inapp" del gateway (`POST /internal/action`, ver `native/gateway/internal/router.InAppExecutor`):
 * recibe `{"target": "...", "params": {...}}` con el secreto en `X-Kairos-App-Secret` y devuelve (código HTTP, JSON).
 * El gateway ya validó permisos y parámetros; aquí se repite la comprobación del secreto y del id porque este
 * puerto también es alcanzable por cualquier app del teléfono (loopback es compartido).
 */
class GatewayBridgeCore(
    private val secret: String,
    private val ops: GatewayModuleOps,
    private val privilege: PrivilegeRegistry? = null,
    private val control: GatewayControl? = null,
    private val metrics: GatewayMetricsOps? = null,
    private val cloud: GatewayCloudOps? = null,
    private val agent: GatewayAgentOps? = null,
    private val aiChat: GatewayAiChatOps? = null,
    private val minipc: GatewayMinipcOps? = null,
    private val storage: GatewayStorageOps? = null,
    private val ftp: GatewayFtpOps? = null,
    private val ollama: GatewayOllamaOps? = null,
    private val openclaw: GatewayOpenClawOps? = null,
    private val tunnel: GatewayTunnelOps? = null,
    private val hermes: GatewayHermesOps? = null,
    private val remote: GatewayRemoteOps? = null,
    private val addons: GatewayAddonsOps? = null,
    private val restic: GatewayResticOps? = null,
    private val qemuRam: GatewayQemuRamOps? = null,
    private val lanScan: GatewayLanScanOps? = null,
    private val stacks: (() -> JSONObject)? = null,
    private val automations: GatewayAutomationsOps? = null,
    private val ide: GatewayIdeOps? = null,
    /** Elegibilidad real de Dhizuku/Device Owner ( meta 1) -- mismo chequeo de SOLO LECTURA que
     *  `ConfigFragment.hasNoAccountsConfigured()` (AccountManager.accounts.isEmpty(), nunca activa nada). */
    private val dhizukuEligible: (() -> Boolean)? = null,
    /** Carpeta de estado del gateway Go (`<dir>/screen.scid`) — null si no se sabe (p. ej. en pruebas). */
    private val screenDir: java.io.File? = null,
    private val screenJarReady: () -> Boolean = { false },
) {
    // Estado de configuración del espejo de pantalla — ver [ScreenStartConfig]. Mutable a propósito:
    // "screen.config" lo actualiza, "screen.start" lo lee. Vive mientras este GatewayBridgeCore esté vivo (se
    // reconstruye solo si la app vuelve a llamar a GatewayAppBridge.start(), ej. tras reiniciar el gateway).
    @Volatile private var screenConfig = ScreenStartConfig()

    fun handle(secretHeader: String?, body: String): Pair<Int, String> {
        if (secret.isEmpty() || secretHeader == null ||
            !MessageDigest.isEqual(secret.toByteArray(Charsets.UTF_8), secretHeader.toByteArray(Charsets.UTF_8))
        ) return 403 to error("secreto inválido")
        return dispatch(body)
    }

    /**
     * Mismo dispatch que [handle], sin el chequeo de secreto -- para callers DENTRO del mismo
     * proceso Android (ej. [AutomationManager], que corre una acción real del gateway desde un
     * `BroadcastReceiver` de arranque/horario, sin pasar por el socket HTTP del gateway Go). El
     * secreto existe para autenticar al proceso Go, que habla por un socket TCP real -- un
     * caller Kotlin dentro del mismo proceso ya está, por definición, en el mismo nivel de
     * confianza que el resto de la app (si pudiera invocar código Kotlin arbitrario, ya habría
     * ganado más que lo que esta función expone). Hallazgo real: "automatizaciones que ejecuten
     * acciones del gateway, hoy solo start/stop de módulos" (investigación 2026-11-05).
     */
    fun handleInternal(body: String): Pair<Int, String> = dispatch(body)

    private fun dispatch(body: String): Pair<Int, String> {
        val req = try { JSONObject(body) } catch (_: Exception) { return 400 to error("cuerpo JSON inválido") }
        val target = req.optString("target")
        val params = req.optJSONObject("params") ?: JSONObject()
        if (target == "privilege.status" || target == "privilege.run") return handlePrivilege(target, params)
        if (target == "privilege.dhizuku_status") {
            val check = dhizukuEligible ?: return 404 to error("el chequeo de Dhizuku no está activo")
            return 200 to JSONObject().put("ok", true).put("eligible", check()).toString()
        }
        if (target == "screen.status") return handleScreenStatus()
        if (target == "screen.start" || target == "screen.stop") return handleScreenLifecycle(target)
        if (target == "screen.config") return handleScreenConfig(params)
        if (target == "screen.codecs") return handleScreenCodecs()
        if (target == "screen.input") return handleScreenInput(params)
        if (target == "system.metrics") return handleMetrics()
        if (target == "cloud.status" || target == "cloud.start" || target == "cloud.stop" || target == "cloud.share_link") {
            return handleCloud(target, params)
        }
        if (target == "agent.ask") return handleAgent(params)
        if (target == "ai.chat") return handleAiChat(params)
        if (target.startsWith("minipc.")) return handleMinipc(target, params)
        if (target.startsWith("ollama.")) return handleOllama(target, params)
        if (target == "openclaw.status") return handleOpenClaw()
        if (target.startsWith("tunnel.")) return handleTunnel(target, params)
        if (target.startsWith("hermes.")) return handleHermes(target)
        if (target.startsWith("remote.")) return handleRemote(target)
        if (target.startsWith("plugins.")) return handlePlugins(target)
        if (target.startsWith("restic.")) return handleRestic(target, params)
        if (target.startsWith("db.")) return handleDb(target, params)
        if (target.startsWith("qemu.")) return handleQemu(target)
        if (target == "ciberseguridad.lan_scan") return handleLanScan()
        if (target == "stacks.projects") return (stacks?.invoke() ?: JSONObject().put("ok", false).put("error", "Stacks no está activo en este puente")).let {
            (if (it.optBoolean("ok")) 200 else 404) to it.toString()
        }
        if (target.startsWith("automations.")) return handleAutomations(target, params)
        if (target == "ide.project_info") return (ide?.projectInfo() ?: JSONObject().put("ok", false).put("error", "Estudio no está activo en este puente")).let {
            (if (it.optBoolean("ok")) 200 else 404) to it.toString()
        }
        if (target == "ide.tree" || target == "ide.read" || target == "ide.write") {
            val a = ide ?: return 404 to error("Estudio no está activo en este puente")
            val projectId = params.optString("project_id")
            val path = params.optString("path")
            val result = when (target) {
                "ide.tree" -> a.tree(projectId, path)
                "ide.read" -> a.read(projectId, path)
                else -> a.write(projectId, path, params.optString("content"))
            }
            return (if (result.optBoolean("ok")) 200 else 400) to result.toString()
        }
        if (target == "media.download") return execResponse(
            MediaDownloaderManager.download(
                params.optString("url"),
                params.optString("format").ifBlank { "video" },
                params.optString("max_height").toIntOrNull() ?: 1440,
            )
        )
        if (target == "storage.volumes" || target == "storage.list_dir") return handleStorage(target, params)
        if (target.startsWith("ftp.")) return handleFtp(target, params)
        if (CONTROL_PREFIXES.any { target.startsWith(it) }) return handleControl(target, params)
        val id = params.optString("id")
        val needsId = target in ID_TARGETS
        if (needsId && !ID_PATTERN.matches(id)) return 400 to error("id de módulo inválido")

        return when (target) {
            "module.list" -> 200 to JSONObject().put("modules", JSONArray(ops.list().map(::summaryJson))).toString()
            "module.status" -> ops.status(id)?.let { 200 to summaryJson(it).toString() } ?: (404 to error("módulo no encontrado"))
            "module.start" -> opResponse(ops.start(id))
            "module.stop" -> opResponse(ops.stop(id))
            "module.install" -> opResponse(ops.install(id))
            "module.uninstall" -> {
                if (params.optString("confirm") != "true") return 400 to error("hace falta confirm=true explícito para desinstalar un módulo")
                opResponse(ops.uninstall(id))
            }
            else -> 404 to error("acción no soportada por la app: $target")
        }
    }

    /** Proveedores de privilegio (`privilege.status` / `privilege.run`). El gateway ya aplicó el nivel de permiso. */
    private fun handlePrivilege(target: String, params: JSONObject): Pair<Int, String> {
        val registry = privilege ?: return 404 to error("los proveedores de privilegio no están activos")
        if (target == "privilege.status") {
            val list = registry.status().map { JSONObject().put("id", it.id).put("title", it.title).put("available", it.available) }
            return 200 to JSONObject().put("providers", JSONArray(list)).toString()
        }
        val provider = params.optString("provider")
        val cmd = params.optString("cmd")
        if (!PROVIDER_PATTERN.matches(provider) || cmd.isBlank() || cmd.length > MAX_CMD) return 400 to error("proveedor o comando inválido")
        val r = registry.run(provider, cmd, RUN_TIMEOUT_SEC)
        val json = JSONObject().put("ok", r.ok).put("exit_code", r.exitCode).put("output", r.output)
        return (if (r.ok) 200 else if (r.exitCode == 404) 404 else if (r.exitCode == 503) 503 else 500) to json.toString()
    }

    /**
     * `screen.status` — cimiento del espejo/control de pantalla propio (ver [ScreenMirrorManager]). Solo dice si
     * HOY hay un proveedor con identidad shell disponible; nunca arranca nada ni afirma que el espejo funcione.
     */
    private fun handleScreenStatus(): Pair<Int, String> {
        val mirror = screenMirror() ?: return 404 to error("los proveedores de privilegio no están activos")
        val status = mirror.status()
        val json = JSONObject().put("ready", status.ready).put("reason", status.reason)
        status.provider?.let { json.put("provider", it) }
        return 200 to json.toString()
    }

    private fun screenMirror(): ScreenMirrorManager? {
        val registry = privilege ?: return null
        val dir = screenDir ?: return null
        return ScreenMirrorManager(registry, dir, jarExists = screenJarReady)
    }

    /**
     * `screen.start`/`screen.stop` — arranca/corta el servidor real (ver [ScreenMirrorManager.start]/[stop]).
     * Máximo riesgo del gateway: controlar la pantalla es controlar el dispositivo entero.
     */
    private fun handleScreenLifecycle(target: String): Pair<Int, String> {
        val mirror = screenMirror() ?: return 404 to error("los proveedores de privilegio no están activos")
        val r = if (target == "screen.start") {
            val c = screenConfig
            mirror.start(c.maxSize, c.bitRate, c.maxFps, c.audio, c.videoCodec, c.audioCodec, c.orientation)
        } else {
            mirror.stop()
        }
        val json = JSONObject().put("ok", r.ok).put("exit_code", r.exitCode).put("output", r.output.take(400))
        return (if (r.ok) 200 else if (r.exitCode == 404) 404 else if (r.exitCode == 503) 503 else 500) to json.toString()
    }

    /**
     * `screen.config` — guarda (y devuelve) la configuración del próximo `screen.start` (:
     * resolución/bitrate/fps/audio/códec/orientación). Sin parámetros en el cuerpo, solo devuelve la actual —
     * así el panel puede precargar los controles sin tener que adivinar los valores por defecto de
     * [ScreenMirrorManager.start]. La validación real (nombres de códec/orientación soportados) queda en
     * [ScreenMirrorManager.start] — acá solo se guarda el valor crudo, para no duplicar esa lista en dos sitios.
     */
    private fun handleScreenConfig(params: JSONObject): Pair<Int, String> {
        var c = screenConfig
        if (params.has("max_size")) c = c.copy(maxSize = params.optInt("max_size", c.maxSize))
        if (params.has("video_bit_rate")) c = c.copy(bitRate = params.optInt("video_bit_rate", c.bitRate))
        if (params.has("max_fps")) c = c.copy(maxFps = params.optInt("max_fps", c.maxFps))
        if (params.has("audio")) c = c.copy(audio = params.optBoolean("audio", c.audio))
        if (params.has("video_codec")) c = c.copy(videoCodec = params.optString("video_codec").ifBlank { null })
        if (params.has("audio_codec")) c = c.copy(audioCodec = params.optString("audio_codec").ifBlank { null })
        if (params.has("orientation")) c = c.copy(orientation = params.optString("orientation").ifBlank { null })
        screenConfig = c
        val json = JSONObject().put("ok", true)
            .put("max_size", c.maxSize).put("video_bit_rate", c.bitRate).put("max_fps", c.maxFps)
            .put("audio", c.audio).put("video_codec", c.videoCodec ?: "").put("audio_codec", c.audioCodec ?: "")
            .put("orientation", c.orientation ?: "")
        return 200 to json.toString()
    }

    /**
     * `screen.codecs` — códecs de video/audio y pantallas reales del dispositivo (ver
     * [ScreenMirrorManager.detectCodecs]). Corre el servidor real en primer plano un momento (no arranca el
     * espejo) — puede tardar unos segundos en un dispositivo con muchos encoders.
     */
    private fun handleScreenCodecs(): Pair<Int, String> {
        val mirror = screenMirror() ?: return 404 to error("los proveedores de privilegio no están activos")
        val info = mirror.detectCodecs()
        fun encoderArray(list: List<ScreenMirrorManager.CodecEncoder>) = JSONArray(list.map {
            JSONObject().put("codec", it.codec).put("encoder", it.encoder).put("type", it.hwType)
        })
        // "error" se repite junto a "reason" a propósito: el panel (y cualquier otro cliente genérico del
        // gateway) solo mira "error"/"message" en una respuesta no-ok (mismo contrato que [error] abajo) —
        // "reason" es el nombre más descriptivo para esta acción puntual, pero no romper ese contrato genérico.
        val json = JSONObject().put("ok", info.ok).put("reason", info.reason).put("error", info.reason)
            .put("video_encoders", encoderArray(info.videoEncoders))
            .put("audio_encoders", encoderArray(info.audioEncoders))
            .put("displays", JSONArray(info.displays.map { JSONObject().put("id", it.id).put("size", it.size) }))
        return (if (info.ok) 200 else 404) to json.toString()
    }

    /**
     * `screen.input` — toque/gesto/tecla vía `adb shell input` (ver [AdbInputController]). Camino independiente
     * del transporte de video (no toca [ScreenMirrorManager]/`ScreenStreamProtocol.kt`): necesita el mismo nivel
     * shell que `screen.status` (adb inalámbrico, Shizuku, root) — "app" (uid de la propia Kairos) no alcanza.
     */
    private fun handleScreenInput(params: JSONObject): Pair<Int, String> {
        val registry = privilege ?: return 404 to error("los proveedores de privilegio no están activos")
        val gesture = AdbInputController.parse(params) ?: return 400 to error("gesto inválido (type: tap|swipe|key)")
        val r = AdbInputController(registry).send(gesture)
        val json = JSONObject().put("ok", r.ok).put("exit_code", r.exitCode).put("output", r.output)
        return (if (r.ok) 200 else if (r.exitCode == 503) 503 else 500) to json.toString()
    }

    /** `system.metrics` — CPU/RAM/almacenamiento/batería en vivo, ver [GatewayMetricsOps]. */
    private fun handleMetrics(): Pair<Int, String> {
        val m = metrics ?: return 404 to error("las métricas no están activas")
        return 200 to m.read().toString()
    }

    /** `cloud.status`/`cloud.start`/`cloud.stop`/`cloud.share_link` — ver [GatewayCloudOps]. */
    private fun handleCloud(target: String, params: JSONObject): Pair<Int, String> {
        val c = cloud ?: return 404 to error("Nube no está activa")
        return when (target) {
            "cloud.status" -> 200 to c.status().toString()
            "cloud.start" -> opToResponse(c.start())
            "cloud.stop" -> opToResponse(c.stop())
            "cloud.share_link" -> shareLinkResponse(c.shareLink(params.optString("path")))
            else -> 404 to error("acción no soportada por la app: $target")
        }
    }

    private fun opToResponse(r: CloudOpResult) =
        (if (r.ok) 200 else 500) to JSONObject().put("ok", r.ok).put("message", r.message).toString()

    private fun shareLinkResponse(r: CloudShareResult) =
        (if (r.ok) 200 else 400) to JSONObject().put("ok", r.ok).put("url", r.url).put("message", r.message).toString()

    /** `agent.ask` — ver [GatewayAgentOps]. */
    private fun handleAgent(params: JSONObject): Pair<Int, String> {
        val a = agent ?: return 404 to error("el Agente no está activo")
        val text = params.optString("text")
        if (text.isBlank() || text.length > MAX_AGENT_TEXT) return 400 to error("pedido inválido")
        val r = a.ask(text)
        return (if (r.ok) 200 else 500) to JSONObject().put("ok", r.ok).put("reply", r.reply).toString()
    }

    /**
     * `ai.chat` — ver [GatewayAiChatOps]. `history` es opcional: los turnos previos de la conversación (la
     * propia pantalla del gateway/panel la mantiene — el puente no guarda estado entre llamadas). El gateway Go
     * real solo transporta parámetros como texto (`map[string]string`, ver `router.Action.Params`), así que
     * `history` viaja como un STRING con un JSON `[{"role":"user"|"assistant","content":"..."}]` adentro, no
     * como un array nativo — se acepta igual un `JSONArray` directo (lo que mandan los tests de este archivo,
     * que construyen el cuerpo a mano) para no depender de ese detalle de transporte en las pruebas.
     */
    private fun handleAiChat(params: JSONObject): Pair<Int, String> {
        val a = aiChat ?: return 404 to error("el chat con IA no está activo")
        val text = params.optString("text")
        if (text.isBlank() || text.length > MAX_AI_CHAT_TEXT) return 400 to error("mensaje inválido")
        val historyArr = params.optJSONArray("history")
            ?: params.optString("history").takeIf { it.isNotBlank() }?.let { raw ->
                try { JSONArray(raw) } catch (_: Exception) { null }
            }
        if (historyArr != null && historyArr.length() > MAX_AI_CHAT_HISTORY) return 400 to error("historial demasiado largo")
        val history = (0 until (historyArr?.length() ?: 0)).mapNotNull { i ->
            val turn = historyArr?.optJSONObject(i) ?: return@mapNotNull null
            val role = turn.optString("role")
            val content = turn.optString("content").take(MAX_AI_CHAT_TEXT)
            if ((role == "user" || role == "assistant") && content.isNotBlank()) role to content else null
        }
        val r = aiChat.chat(text, history)
        return (if (r.ok) 200 else 500) to JSONObject().put("ok", r.ok).put("reply", r.reply).toString()
    }

    /** `minipc.vnc_status` — ver [GatewayMinipcOps]. */
    private fun handleMinipc(target: String, params: JSONObject): Pair<Int, String> {
        val m = minipc ?: return 404 to error("Mini PC no está activo en este puente")
        return when (target) {
            "minipc.vnc_status" -> 200 to m.vncStatus().toString()
            "minipc.desktop_status" -> 200 to m.desktopStatus().toString()
            "minipc.desktop_start" -> {
                val de = params.optString("de").ifBlank { "xfce4" }
                if (!DESKTOP_ENV_PATTERN.matches(de)) return 400 to error("entorno de escritorio inválido: $de")
                val distro = params.optString("distro").takeIf { it.isNotBlank() }
                if (distro != null && !DistroManager.ALIAS.matches(distro)) return 400 to error("distro inválida: $distro")
                val r = m.desktopStart(de, distro)
                (if (r.optBoolean("ok")) 200 else 500) to r.toString()
            }
            "minipc.desktop_stop" -> {
                val r = m.desktopStop()
                (if (r.optBoolean("ok")) 200 else 500) to r.toString()
            }
            else -> 404 to error("acción no soportada por la app: $target")
        }
    }

    private fun handleOllama(target: String, params: JSONObject): Pair<Int, String> {
        val o = ollama ?: return 404 to error("Ollama no está activo en este puente")
        fun modelName(): String? {
            val name = params.optString("name")
            return if (OLLAMA_MODEL_PATTERN.matches(name)) name else null
        }
        return when (target) {
            "ollama.models" -> 200 to o.models().toString()
            "ollama.running" -> 200 to o.running().toString()
            "ollama.pull" -> {
                val name = modelName() ?: return 400 to error("nombre de modelo inválido")
                val r = o.pull(name)
                (if (r.optBoolean("ok")) 200 else 500) to r.toString()
            }
            "ollama.delete" -> {
                val name = modelName() ?: return 400 to error("nombre de modelo inválido")
                val r = o.delete(name)
                (if (r.optBoolean("ok")) 200 else 500) to r.toString()
            }
            "ollama.unload" -> {
                val name = modelName() ?: return 400 to error("nombre de modelo inválido")
                val r = o.unload(name)
                (if (r.optBoolean("ok")) 200 else 500) to r.toString()
            }
            else -> 404 to error("acción no soportada por la app: $target")
        }
    }

    private fun handleOpenClaw(): Pair<Int, String> {
        val oc = openclaw ?: return 404 to error("OpenClaw no está activo en este puente")
        return 200 to oc.status().toString()
    }

    private fun handleTunnel(target: String, params: JSONObject): Pair<Int, String> {
        val t = tunnel ?: return 404 to error("el túnel no está activo en este puente")
        val port = params.optString("port").toIntOrNull()
        if (port == null || port !in 1..65535) return 400 to error("puerto inválido")
        return when (target) {
            "tunnel.status" -> 200 to t.status(port).toString()
            "tunnel.start" -> {
                val provider = params.optString("provider")
                if (provider != "cloudflared" && provider != "ngrok") return 400 to error("proveedor inválido: $provider")
                val r = t.start(port, provider, params.optString("token").takeIf { it.isNotBlank() }, params.optString("domain").takeIf { it.isNotBlank() })
                (if (r.optBoolean("ok")) 200 else 500) to r.toString()
            }
            "tunnel.stop" -> {
                val r = t.stop(port)
                (if (r.optBoolean("ok")) 200 else 500) to r.toString()
            }
            else -> 404 to error("acción no soportada por la app: $target")
        }
    }

    private fun handleHermes(target: String): Pair<Int, String> {
        val h = hermes ?: return 404 to error("Hermes no está activo en este puente")
        return when (target) {
            "hermes.info" -> 200 to h.info().toString()
            "hermes.gateway_start" -> {
                val r = h.gatewayStart()
                (if (r.optBoolean("ok")) 200 else 500) to r.toString()
            }
            "hermes.gateway_stop" -> {
                val r = h.gatewayStop()
                (if (r.optBoolean("ok")) 200 else 500) to r.toString()
            }
            else -> 404 to error("acción no soportada por la app: $target")
        }
    }

    private fun handleRemote(target: String): Pair<Int, String> {
        val r = remote ?: return 404 to error("Remote no está activo en este puente")
        return when (target) {
            "remote.info" -> 200 to r.info().toString()
            "remote.ssh_connections" -> 200 to r.sshConnections().toString()
            "remote.ssh_security" -> 200 to r.sshSecurity().toString()
            "remote.cf_info" -> 200 to r.cfInfo().toString()
            "remote.cf_start" -> {
                val x = r.cfStart()
                (if (x.optBoolean("ok")) 200 else 500) to x.toString()
            }
            "remote.cf_stop" -> {
                val x = r.cfStop()
                (if (x.optBoolean("ok")) 200 else 500) to x.toString()
            }
            else -> 404 to error("acción no soportada por la app: $target")
        }
    }

    private fun handlePlugins(target: String): Pair<Int, String> {
        val a = addons ?: return 404 to error("los complementos no están activos en este puente")
        fun result(ok: Boolean, json: JSONObject) = (if (ok) 200 else 500) to json.toString()
        return when (target) {
            "plugins.tor_status" -> 200 to a.torStatus().toString()
            "plugins.tor_start" -> a.torStart().let { result(it.optBoolean("ok"), it) }
            "plugins.tor_stop" -> a.torStop().let { result(it.optBoolean("ok"), it) }
            "plugins.syncthing_status" -> 200 to a.syncthingStatus().toString()
            "plugins.syncthing_start" -> a.syncthingStart().let { result(it.optBoolean("ok"), it) }
            "plugins.syncthing_stop" -> a.syncthingStop().let { result(it.optBoolean("ok"), it) }
            else -> 404 to error("acción no soportada por la app: $target")
        }
    }

    private fun handleDb(target: String, params: JSONObject): Pair<Int, String> {
        val engine = params.optString("engine")
        if (target == "db.status") {
            val s = DbManager.status()
            val json = JSONObject(s as Map<*, *>).put("ok", true)
            return 200 to json.toString()
        }
        if (engine != "mysql" && engine != "postgres") return 400 to error("motor inválido: $engine (mysql|postgres)")
        val name = params.optString("name")
        return when (target) {
            "db.create" -> execResponse(DbManager.create(engine, name))
            "db.drop" -> execResponse(DbManager.drop(engine, name, params.optString("confirm") == "true"))
            "db.backup" -> execResponse(DbManager.backup(engine, name))
            "db.restore" -> execResponse(DbManager.restore(engine, name, params.optString("backup_file")))
            else -> 404 to error("acción no soportada por la app: $target")
        }
    }

    private fun handleRestic(target: String, params: JSONObject): Pair<Int, String> {
        val rst = restic ?: return 404 to error("Restic no está activo en este puente")
        val repo = params.optString("repo")
        val password = params.optString("password")
        return when (target) {
            "restic.backup" -> {
                val r = rst.backup(repo, params.optString("folder"), password)
                (if (r.optBoolean("ok")) 200 else 500) to r.toString()
            }
            "restic.snapshots" -> {
                val r = rst.snapshots(repo, password)
                (if (r.optBoolean("ok")) 200 else 500) to r.toString()
            }
            else -> 404 to error("acción no soportada por la app: $target")
        }
    }

    private fun handleLanScan(): Pair<Int, String> {
        val ls = lanScan ?: return 404 to error("Ciberseguridad no está activo en este puente")
        val r = ls.scan()
        return (if (r.optBoolean("ok")) 200 else 500) to r.toString()
    }

    private fun handleAutomations(target: String, params: JSONObject): Pair<Int, String> {
        val a = automations ?: return 404 to error("Automatizaciones no está activo en este puente")
        return when (target) {
            "automations.list" -> 200 to a.list().toString()
            "automations.add" -> {
                val r = a.add(
                    params.optString("name"), params.optString("trigger"), params.optString("schedule_time"),
                    params.optString("action"), params.optString("module_id"),
                    params.optString("battery_threshold").toIntOrNull() ?: 20,
                    params.optString("battery_direction").ifBlank { "BELOW" },
                )
                (if (r.optBoolean("ok")) 200 else 400) to r.toString()
            }
            "automations.delete" -> {
                val r = a.delete(params.optString("id"))
                (if (r.optBoolean("ok")) 200 else 400) to r.toString()
            }
            "automations.set_enabled" -> {
                val r = a.setEnabled(params.optString("id"), params.optString("enabled") == "true")
                (if (r.optBoolean("ok")) 200 else 400) to r.toString()
            }
            else -> 404 to error("acción no soportada por la app: $target")
        }
    }

    private fun handleQemu(target: String): Pair<Int, String> = when (target) {
        "qemu.status" -> 200 to JSONObject(QemuManager.status() as Map<*, *>).put("ok", true).toString()
        "qemu.images" -> 200 to JSONObject().put("ok", true).put("images", JSONArray(QemuManager.images().map { JSONObject(it) })).toString()
        "qemu.ram_available" -> (qemuRam?.available() ?: JSONObject().put("ok", false).put("error", "QEMU no está activo en este puente")).let {
            (if (it.optBoolean("ok")) 200 else 404) to it.toString()
        }
        else -> 404 to error("acción no soportada por la app: $target")
    }

    /** `storage.volumes`/`storage.list_dir` — ver [GatewayStorageOps]. Lectura de volúmenes REALES del
     *  dispositivo (interno/microSD/USB), más allá del HOME de Termux que ya cubre `fs.*`. */
    private fun handleStorage(target: String, params: JSONObject): Pair<Int, String> {
        val s = storage ?: return 404 to error("el almacenamiento no está activo en este puente")
        return when (target) {
            "storage.volumes" -> 200 to JSONObject().put("volumes", JSONArray(s.volumes().map {
                JSONObject().put("id", it.id).put("description", it.description).put("primary", it.primary)
                    .put("removable", it.removable).put("state", it.state)
                    .put("path", it.path ?: JSONObject.NULL).put("accessible", it.accessible)
            })).toString()
            "storage.list_dir" -> {
                val volumeId = params.optString("volume_id")
                if (!VOLUME_ID_PATTERN.matches(volumeId)) return 400 to error("volume_id inválido")
                s.listDir(volumeId, params.optString("path")).fold(
                    { list -> 200 to JSONObject().put("entries", JSONArray(list.map {
                        JSONObject().put("name", it.name).put("dir", it.dir).put("size", it.size)
                    })).toString() },
                    { 400 to error(it.message ?: "error") },
                )
            }
            else -> 404 to error("acción no soportada por la app: $target")
        }
    }

    /** `ftp.status`/`ftp.start`/`ftp.stop`/`ftp.set_credentials` — ver [GatewayFtpOps]. */
    private fun handleFtp(target: String, params: JSONObject): Pair<Int, String> {
        val f = ftp ?: return 404 to error("FTP no está activo en este puente")
        return when (target) {
            "ftp.status" -> {
                val st = f.status()
                200 to JSONObject().put("running", st.running).put("port", st.port)
                    .put("configured", st.configured).put("username", st.username ?: JSONObject.NULL).toString()
            }
            "ftp.start" -> opToFtpResponse(f.start())
            "ftp.stop" -> opToFtpResponse(f.stop())
            "ftp.set_credentials" -> {
                val user = params.optString("username")
                val pass = params.optString("password")
                if (user.isBlank() || pass.isBlank()) return 400 to error("usuario y contraseña requeridos")
                opToFtpResponse(f.setCredentials(user, pass))
            }
            else -> 404 to error("acción no soportada por la app: $target")
        }
    }

    private fun opToFtpResponse(r: FtpOpResult) =
        (if (r.ok) 200 else 500) to JSONObject().put("ok", r.ok).put("message", r.message).toString()

    /** Sesiones de terminal, archivos del HOME y ajustes. El gateway ya aplicó el nivel de permiso de cada acción. */
    private fun handleControl(target: String, params: JSONObject): Pair<Int, String> {
        val c = control ?: return 404 to error("el control de la app no está activo")
        val name = params.optString("name")
        val path = params.optString("path")
        return when (target) {
            "session.list" -> 200 to JSONObject().put("sessions", JSONArray(c.sessions.list().map {
                JSONObject().put("name", it.name).put("windows", it.windows).put("attached", it.attached)
            })).toString()
            "session.open" -> sessionResponse(c.sessions.open(name, params.optString("command")))
            "session.open_module" -> {
                val id = params.optString("id")
                if (!ID_PATTERN.matches(id)) return 400 to error("id de módulo inválido")
                val cmd = c.terminalCommandOf(id)?.takeIf { it.isNotBlank() } ?: return 404 to error("el módulo no tiene comando de terminal")
                sessionResponse(c.sessions.open(id.take(24), cmd))
            }
            "session.send" -> sessionResponse(c.sessions.send(name, params.optString("text"), params.optString("enter") != "false"))
            "session.read" -> {
                val r = c.sessions.read(name, params.optString("lines").toIntOrNull() ?: 40)
                if (r.ok) 200 to JSONObject().put("screen", r.output).toString() else sessionFailure(r)
            }
            "session.kill" -> sessionResponse(c.sessions.kill(name))
            "fs.list" -> c.files.list(path).fold(
                { list -> 200 to JSONObject().put("entries", JSONArray(list.map {
                    JSONObject().put("name", it.name).put("dir", it.dir).put("size", it.size)
                })).toString() },
                { 400 to error(it.message ?: "error") },
            )
            "fs.read" -> if (params.optString("encoding") == "base64") {
                c.files.readBytes(path).fold(
                    { 200 to JSONObject().put("content", Base64.getEncoder().encodeToString(it)).put("encoding", "base64").toString() },
                    { 400 to error(it.message ?: "error") },
                )
            } else {
                c.files.read(path).fold({ 200 to JSONObject().put("content", it).toString() }, { 400 to error(it.message ?: "error") })
            }
            "fs.write" -> if (params.optString("encoding") == "base64") {
                val bytes = try { Base64.getDecoder().decode(params.optString("content")) } catch (_: IllegalArgumentException) { null }
                if (bytes == null) 400 to error("contenido base64 inválido")
                else c.files.writeBytes(path, bytes).fold({ 200 to JSONObject().put("ok", true).toString() }, { 400 to error(it.message ?: "error") })
            } else {
                c.files.write(path, params.optString("content")).fold({ 200 to JSONObject().put("ok", true).toString() }, { 400 to error(it.message ?: "error") })
            }
            "fs.mkdir" -> c.files.mkdir(path).fold({ 200 to JSONObject().put("ok", true).toString() }, { 400 to error(it.message ?: "error") })
            "fs.delete" -> c.files.delete(path).fold({ 200 to JSONObject().put("ok", true).toString() }, { 400 to error(it.message ?: "error") })
            "fs.rename" -> c.files.rename(path, params.optString("to")).fold({ 200 to JSONObject().put("ok", true).toString() }, { 400 to error(it.message ?: "error") })
            "apk.info" -> {
                val apkFile = c.files.resolve(path) ?: return 400 to error("ruta no permitida")
                execResponse(ApkToolsManager.info(apkFile, RUN_TIMEOUT_SEC))
            }
            "apk.decode" -> {
                val apkFile = c.files.resolve(path) ?: return 400 to error("ruta no permitida")
                execResponse(ApkToolsManager.decode(apkFile, 290))
            }
            // fs.compress/fs.extract ( lote 23) -- mismo motor que usa FileManagerFragment
            // (ArchiveManager, formatos zip/7z/tar.zst, ya con sus propios límites de zip-bomb y
            // path-traversal) -- acá solo se traducen rutas relativas via HomeFiles.resolve() antes
            // de llamarlo, nunca se reimplementa la lógica de compresión/extracción.
            "fs.compress" -> {
                val relativePaths = params.optString("files").split("\n").map { it.trim() }.filter { it.isNotBlank() }
                if (relativePaths.isEmpty()) return 400 to error("no se indicó ningún archivo/carpeta para comprimir")
                val resolvedFiles = mutableListOf<File>()
                for (rel in relativePaths) resolvedFiles.add(c.files.resolve(rel) ?: return 400 to error("ruta no permitida: $rel"))
                val outputFile = c.files.resolve(path) ?: return 400 to error("ruta de salida no permitida")
                val format = when (params.optString("format")) {
                    "zip" -> ArchiveFormat.ZIP
                    "7z" -> ArchiveFormat.SEVEN_Z
                    "tar.zst" -> ArchiveFormat.TAR_ZSTD
                    else -> return 400 to error("formato inválido (zip|7z|tar.zst)")
                }
                runCatching { ArchiveManager.compress(resolvedFiles, outputFile, format) }.fold(
                    { 200 to JSONObject().put("ok", true).put("output", path).toString() },
                    { 400 to error(it.message ?: "no se pudo comprimir") },
                )
            }
            "fs.extract" -> {
                val archiveFile = c.files.resolve(path) ?: return 400 to error("ruta de archivo no permitida")
                val destFile = c.files.resolve(params.optString("destination")) ?: return 400 to error("ruta de destino no permitida")
                runCatching { ArchiveManager.extractAll(archiveFile, destFile) }.fold(
                    { 200 to JSONObject().put("ok", true).toString() },
                    { 400 to error(it.message ?: "no se pudo extraer") },
                )
            }
            "distro.list" -> 200 to JSONObject().put("distros", JSONArray(c.distros.list().map {
                JSONObject().put("alias", it.alias).put("name", it.name).put("installed", it.installed)
            })).toString()
            "distro.open" -> sessionResponse(c.distros.open(params.optString("alias")))
            "distro.install" -> sessionResponse(c.distros.install(params.optString("alias")))
            "udocker.images" -> 200 to JSONObject().put("images", JSONArray(c.distros.images().map { JSONObject().put("name", it.name) })).toString()
            "udocker.open" -> sessionResponse(c.distros.openImage(params.optString("image")))
            "distro.run" -> execResponse(c.distros.run(params.optString("alias"), params.optString("command"), RUN_TIMEOUT_SEC, sandbox = params.optString("sandbox") == "true"))
            "udocker.run" -> execResponse(c.distros.runImage(params.optString("image"), params.optString("command"), RUN_TIMEOUT_SEC))
            "udocker.containers" -> 200 to UdockerContainerManager.list().toString()
            "udocker.container_run" -> execResponse(UdockerContainerManager.run(params.optString("container"), params.optString("command"), RUN_TIMEOUT_SEC))
            "udocker.container_inspect" -> execResponse(UdockerContainerManager.inspect(params.optString("container")))
            "udocker.container_remove" -> execResponse(UdockerContainerManager.remove(params.optString("container"), params.optString("confirm") == "true"))
            "sandbox.run" -> execResponse(c.sandbox.run(params.optString("command"), RUN_TIMEOUT_SEC, keep = params.optString("keep") == "true"))
            "engram.run" -> execResponse(c.engram.run(params.optString("args"), RUN_TIMEOUT_SEC))
            "config.get" -> {
                val key = params.optString("key")
                if (key !in c.settings.keys) return 400 to error("ajuste no permitido")
                200 to JSONObject().put("key", key).put("value", c.settings.get(key) ?: JSONObject.NULL).toString()
            }
            "config.set" -> {
                val key = params.optString("key")
                if (key !in c.settings.keys) return 400 to error("ajuste no permitido")
                if (c.settings.set(key, params.optString("value"))) 200 to JSONObject().put("ok", true).toString() else 400 to error("valor no válido")
            }
            else -> 404 to error("acción no soportada por la app: $target")
        }
    }

    private fun sessionResponse(r: PrivilegeResult) = if (r.ok) 200 to JSONObject().put("ok", true).toString() else sessionFailure(r)

    private fun sessionFailure(r: PrivilegeResult) = (if (r.exitCode == 400) 400 else 500) to error(r.output.ifBlank { "no se pudo completar" })

    // Respuesta completa (no solo ok/error) para acciones que corren un comando de una sin abrir
    // sesión de terminal (distro.run/udocker.run, 2026-10-16,: "instalar
    // distro y poner ahí programa o sistema y verificarlo") — quien llama necesita la salida real
    // (p. ej. "python3 --version") para verificar, no solo si el comando devolvió éxito.
    private fun execResponse(r: PrivilegeResult): Pair<Int, String> {
        val json = JSONObject().put("ok", r.ok).put("exit_code", r.exitCode).put("output", r.output.take(4000))
        return (if (r.exitCode == 400) 400 else 200) to json.toString()
    }

    private fun opResponse(r: ModuleOpResult): Pair<Int, String> {
        val json = JSONObject().put("ok", r.ok).put("pending", r.pending).put("message", r.message.take(400))
        return (if (r.ok) (if (r.pending) 202 else 200) else 500) to json.toString()
    }

    private fun summaryJson(m: ModuleSummary) =
        JSONObject().put("id", m.id).put("name", m.name).put("installed", m.installed).put("running", m.running)
            .put("port", m.port ?: JSONObject.NULL)

    private fun error(msg: String) = JSONObject().put("error", msg).toString()

    companion object {
        private val CONTROL_PREFIXES = listOf("session.", "fs.", "config.", "distro.", "udocker.", "sandbox.", "engram.", "apk.")
        private val ID_TARGETS = setOf("module.status", "module.start", "module.stop", "module.install", "module.uninstall")
        private val ID_PATTERN = Regex("[a-z0-9_-]{1,40}")
        private val PROVIDER_PATTERN = Regex("[a-z]{2,16}")
        // Mismos 4 valores que EntornoNative.desktopLabel() — xfce4/lxqt/mate soportados por
        // desktopExecCmd()/desktopBinaryExists() en el camino nativo, kde solo en distro.
        private val DESKTOP_ENV_PATTERN = Regex("xfce4|lxqt|mate|kde")
        // Mismo patrón que DistroManager.IMAGE (GatewayDistroOps.kt) — los nombres de modelo de
        // Ollama tienen la misma forma general que una imagen de contenedor (ns/nombre:tag).
        private val OLLAMA_MODEL_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._/:-]{0,79}")
        private const val MAX_CMD = 500
        private const val RUN_TIMEOUT_SEC = 50L
        private const val MAX_AGENT_TEXT = 500
        private const val MAX_AI_CHAT_TEXT = 2000
        private const val MAX_AI_CHAT_HISTORY = 20
        private val VOLUME_ID_PATTERN = Regex("[A-Za-z0-9_.:-]{1,64}")
    }
}

/** Implementación real: catálogo + [ModuleController]. start/stop esperan unos segundos; instalar siempre queda en segundo plano. */
class AppModuleOps(private val context: Context) : GatewayModuleOps {
    private fun catalog() = ModuleCatalog.load(context)

    private fun summary(id: String, name: String) =
        ModuleSummary(id, name, ModuleInstalled.isInstalledRobust(context, id), ModuleController.isRunning(id), ModuleController.getModulePort(id))

    override fun list() = catalog().map { summary(it.id, it.name) }

    override fun status(id: String) = catalog().firstOrNull { it.id == id }?.let { summary(it.id, it.name) }

    private fun exists(id: String) = catalog().any { it.id == id }

    override fun start(id: String): ModuleOpResult {
        if (!exists(id)) return ModuleOpResult(false, message = "módulo no encontrado")
        val latch = CountDownLatch(1)
        var ok = false
        var msg = ""
        ModuleController.startModule(id, context) { success, m -> ok = success; msg = m; latch.countDown() }
        val done = latch.await(WAIT_SECONDS, TimeUnit.SECONDS)
        return if (done) ModuleOpResult(ok, message = if (ok) "" else msg) else ModuleOpResult(true, pending = true)
    }

    override fun stop(id: String): ModuleOpResult {
        if (!exists(id)) return ModuleOpResult(false, message = "módulo no encontrado")
        val latch = CountDownLatch(1)
        var ok = false
        ModuleController.stopModule(id, context) { success -> ok = success; latch.countDown() }
        val done = latch.await(WAIT_SECONDS, TimeUnit.SECONDS)
        return if (done) ModuleOpResult(ok) else ModuleOpResult(true, pending = true)
    }

    override fun install(id: String): ModuleOpResult {
        if (!exists(id)) return ModuleOpResult(false, message = "módulo no encontrado")
        ModuleController.installModule(id, context, null, false, { }, { })
        return ModuleOpResult(true, pending = true, message = "instalación iniciada; consulta module.status")
    }

    override fun uninstall(id: String): ModuleOpResult {
        if (!exists(id)) return ModuleOpResult(false, message = "módulo no encontrado")
        val latch = CountDownLatch(1)
        var ok = false
        ModuleController.uninstallModule(id) { success -> ok = success; latch.countDown() }
        val done = latch.await(WAIT_SECONDS, TimeUnit.SECONDS)
        return if (done) ModuleOpResult(ok, message = if (ok) "" else "no se pudo desinstalar") else ModuleOpResult(true, pending = true)
    }

    private companion object { const val WAIT_SECONDS = 8L }
}

/**
 * Servidor HTTP mínimo SOLO en 127.0.0.1 para el endpoint "inapp" del gateway. Distinto de [HomelabServer] (que escucha
 * en todas las interfaces para el panel) a propósito: este puerto da control sobre los módulos y no debe verse desde la red.
 */
object GatewayAppBridge {
    const val DEFAULT_PORT = 8971
    // 16 KB alcanzaba mientras todo el cuerpo era texto corto; un archivo binario en base64 de hasta 2 MB
    // (ver HomeFiles.MAX_WRITE_BINARY) pesa ~2.7 MB codificado — el límite sube para que fs.write con
    // encoding=base64 no se corte a mitad de archivo.
    private const val MAX_BODY = 3 * 1024 * 1024
    private const val SOCKET_TIMEOUT_MS = 15_000

    private var server: ServerSocket? = null
    @Volatile private var core: GatewayBridgeCore? = null

    val isRunning get() = server?.isClosed == false
    val port get() = server?.localPort ?: 0

    /** Enlaza en loopback (puerto [preferred] y, si está ocupado, uno libre). Devuelve el puerto o 0. */
    @Synchronized
    fun start(
        secret: String,
        ops: GatewayModuleOps,
        preferred: Int = DEFAULT_PORT,
        privilege: PrivilegeRegistry? = null,
        control: GatewayControl? = null,
        metrics: GatewayMetricsOps? = null,
        cloud: GatewayCloudOps? = null,
        minipc: GatewayMinipcOps? = null,
        agent: GatewayAgentOps? = null,
        aiChat: GatewayAiChatOps? = null,
        storage: GatewayStorageOps? = null,
        ftp: GatewayFtpOps? = null,
        ollama: GatewayOllamaOps? = null,
        openclaw: GatewayOpenClawOps? = null,
        tunnel: GatewayTunnelOps? = null,
        hermes: GatewayHermesOps? = null,
        remote: GatewayRemoteOps? = null,
        addons: GatewayAddonsOps? = null,
        restic: GatewayResticOps? = null,
        qemuRam: GatewayQemuRamOps? = null,
        lanScan: GatewayLanScanOps? = null,
        stacks: (() -> JSONObject)? = null,
        automations: GatewayAutomationsOps? = null,
        ide: GatewayIdeOps? = null,
        dhizukuEligible: (() -> Boolean)? = null,
        screenDir: java.io.File? = null,
        screenJarReady: () -> Boolean = { false },
    ): Int {
        if (isRunning) {
            core = GatewayBridgeCore(secret, ops, privilege, control, metrics, cloud, minipc = minipc, agent = agent, aiChat = aiChat, storage = storage, ftp = ftp, ollama = ollama, openclaw = openclaw, tunnel = tunnel, hermes = hermes, remote = remote, addons = addons, restic = restic, qemuRam = qemuRam, lanScan = lanScan, stacks = stacks, automations = automations, ide = ide, dhizukuEligible = dhizukuEligible, screenDir = screenDir, screenJarReady = screenJarReady)
            return port
        }
        val loopback = InetAddress.getByName("127.0.0.1")
        val socket = try { ServerSocket(preferred, 8, loopback) } catch (_: Exception) {
            try { ServerSocket(0, 8, loopback) } catch (_: Exception) { return 0 }
        }
        server = socket
        core = GatewayBridgeCore(secret, ops, privilege, control, metrics, cloud, minipc = minipc, agent = agent, aiChat = aiChat, storage = storage, ftp = ftp, ollama = ollama, openclaw = openclaw, tunnel = tunnel, hermes = hermes, remote = remote, addons = addons, restic = restic, qemuRam = qemuRam, lanScan = lanScan, stacks = stacks, automations = automations, ide = ide, dhizukuEligible = dhizukuEligible, screenDir = screenDir, screenJarReady = screenJarReady)
        val pool = Executors.newFixedThreadPool(4)
        Thread {
            while (!socket.isClosed) {
                val client = try { socket.accept() } catch (_: Exception) { break }
                pool.submit { try { client.use { serve(it) } } catch (_: Exception) { } }
            }
            pool.shutdownNow()
        }.apply { isDaemon = true; name = "gateway-app-bridge"; start() }
        return socket.localPort
    }

    @Synchronized
    fun stop() {
        try { server?.close() } catch (_: Exception) { }
        server = null
        core = null
    }

    /** Pasarela a [GatewayBridgeCore.handleInternal] para callers DENTRO del proceso (ver KDoc
     *  ahí) -- null si el gateway no está corriendo (no hay [core] activo), igual que cualquier
     *  otra acción del gateway necesitaría que esté arriba para ejecutarse. */
    fun dispatchInternal(target: String, params: JSONObject): Pair<Int, String>? =
        core?.handleInternal(JSONObject().put("target", target).put("params", params).toString())

    private fun serve(client: Socket) {
        client.soTimeout = SOCKET_TIMEOUT_MS
        val input = client.getInputStream()
        val requestLine = readLine(input) ?: return
        val parts = requestLine.split(" ")
        var secretHeader: String? = null
        var length = 0
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i <= 0) continue
            val name = line.substring(0, i).trim()
            val value = line.substring(i + 1).trim()
            if (name.equals("X-Kairos-App-Secret", ignoreCase = true)) secretHeader = value
            if (name.equals("Content-Length", ignoreCase = true)) length = value.toIntOrNull() ?: 0
        }
        val (code, body) = when {
            parts.size < 2 || parts[0] != "POST" || parts[1] != "/internal/action" -> 404 to """{"error":"no encontrado"}"""
            length !in 0..MAX_BODY -> 413 to """{"error":"cuerpo demasiado grande"}"""
            else -> core?.handle(secretHeader, readBody(input, length)) ?: (503 to """{"error":"puente apagado"}""")
        }
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $code X\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        client.getOutputStream().apply { write(head.toByteArray(Charsets.UTF_8)); write(bytes); flush() }
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (sb.length < 8192) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
        }
        return null
    }

    private fun readBody(input: InputStream, length: Int): String {
        val buf = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(buf, read, length - read)
            if (n < 0) break
            read += n
        }
        return String(buf, 0, read, Charsets.UTF_8)
    }
}

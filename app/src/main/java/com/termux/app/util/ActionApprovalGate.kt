package com.termux.app.util

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Capa de POLÍTICA de aprobación para acciones potencialmente riesgosas iniciadas por algo
 * AUTOMATIZADO — no un tap directo del usuario sobre un botón de la propia UI de Kairos. El
 * primer consumidor real es el bot de Telegram con comandos entrantes que otro agente está
 * implementando en paralelo (`app/src/main/java/com/termux/app/TelegramBotService.kt` — todavía
 * no existe a la fecha de este archivo, ver nota de integración pendiente más abajo); a futuro,
 * cualquier acción de un "Agente Kairos" (`docs/arquitectura/FUTURO.md` Sección 8).
 *
 * Patrones adoptados de 2 auditorías de referencia externa de esta sesión (leer ambas fichas
 * completas antes de tocar este archivo):
 *  - **`ConsentBroker`** (`agent-nexus2.0`, ver
 *    `docs/referencias/agentes/REFERENCIA_AGENT_NEXUS.md` sección 2) — cola de aprobación
 *    pendiente (`call_id → evento`) + timeout + resolución externa por un endpoint/callback. Acá
 *    es un `CompletableDeferred<Boolean>` por `call_id`, en vez de un `asyncio.Event`.
 *  - **`ToolApprovalDefaults` + `HardlineCommandGuard`** (`rikkahub-agent`, ver
 *    `docs/referencias/agentes/REFERENCIA_RIKKAHUB_AGENT.md` sección 1) — una sola fuente de
 *    verdad de qué acciones requieren aprobación (acá: `ActionType.policy`), un subset que NUNCA
 *    acepta "recordar mi elección" (`ApprovalPolicy.ALWAYS_ASK`), y un piso de bloqueo
 *    incondicional para comandos shell arbitrarios que ni la aprobación del usuario puede
 *    saltear (ver [HardlineShellGuard] más abajo).
 *
 * **IMPORTANTE — esto NO reemplaza los `AlertDialog` que ya existen y funcionan bien en la app**
 * (ej. `BaseModuleFragment.confirmUninstallModule()`). Es una capa de POLÍTICA por encima: decide
 * SI hace falta preguntar y A QUIÉN avisarle (vía [ApprovalPresenter]) — el mecanismo real de UI
 * que junta el "sí"/"no" de un humano (un `AlertDialog` para un tap directo, un mensaje con
 * teclado inline para el bot de Telegram, una voz para un futuro Agente con salida de audio)
 * sigue siendo responsabilidad de quien llama. Ver [androidAlertDialogPresenter] para el ejemplo
 * concreto que reusa el mecanismo de `AlertDialog.Builder` ya estándar en el proyecto — no un
 * widget nuevo.
 */
object ActionApprovalGate {

    /**
     * Qué tan estricta es la política de aprobación de un [ActionType] — criterio adoptado
     * literalmente de `ToolApprovalDefaults` (rikkahub-agent): "¿la acción tiene efecto
     * secundario real (escribe a disco, corre un proceso, borra algo)? → requiere aprobación.
     * ¿Es una lectura pura? → no hace falta interrumpir al usuario."
     */
    enum class ApprovalPolicy {
        /** Auto-aprobado, nunca pregunta — acción de solo lectura, sin efecto real (ej. consultar
         *  si un módulo está corriendo). */
        NONE,

        /** Requiere aprobación, pero el usuario puede marcar "no volver a preguntar" y el gate
         *  recuerda esa elección (por acción+módulo) en llamadas futuras. */
        REMEMBERABLE,

        /** Requiere aprobación SIEMPRE — el subset que "NUNCA acepta 'recordar mi elección'"
         *  (`NO_ALWAYS_ALLOW` en rikkahub-agent): acciones destructivas reales del propio Kairos
         *  (borrado de paquete real instalado, reparación de bootstrap, borrado de proyecto). */
        ALWAYS_ASK,
    }

    /**
     * Verbos reales que ya existen en `ModuleController.kt` (y el equivalente de solo-lectura,
     * `isRunning`) — un tipo nuevo de acción riesgosa se agrega acá, nunca se infiere de un
     * string suelto en el caller.
     */
    enum class ActionType(val policy: ApprovalPolicy) {
        /** `ModuleController.isRunning()` — lectura pura, sin efecto. */
        MODULE_STATUS_QUERY(ApprovalPolicy.NONE),

        /** `ModuleController.cancelInstall()` — matar una instalación EN CURSO es seguro/
         *  reversible (el usuario puede reintentar), no destruye nada que ya existiera antes. */
        MODULE_CANCEL_INSTALL(ApprovalPolicy.NONE),

        /** `ModuleController.startModule()`. */
        MODULE_START(ApprovalPolicy.REMEMBERABLE),

        /** `ModuleController.stopModule()`. */
        MODULE_STOP(ApprovalPolicy.REMEMBERABLE),

        /** `ModuleController.stopAllModules()`. */
        MODULE_STOP_ALL(ApprovalPolicy.REMEMBERABLE),

        /** `ModuleController.installModule()`. */
        MODULE_INSTALL(ApprovalPolicy.REMEMBERABLE),

        /** `ModuleController.uninstallModule()` — borra la carpeta de scripts propia del módulo
         *  y sus entradas de registry, pero NUNCA el paquete real instalado (npm/binario/etc.). */
        MODULE_UNINSTALL_SOFT(ApprovalPolicy.REMEMBERABLE),

        /** `ModuleController.deepUninstallModule()` — SÍ borra el paquete real (ej.
         *  `npm uninstall -g`, `rm -rf` sobre el binario instalado) — destructivo de verdad. */
        MODULE_UNINSTALL_DEEP(ApprovalPolicy.ALWAYS_ASK),

        /** `ModuleController.cleanReinstallModule()` — encadena [MODULE_UNINSTALL_DEEP] +
         *  reinstalación forzada; hereda la misma política estricta que el borrado que contiene. */
        MODULE_REINSTALL_CLEAN(ApprovalPolicy.ALWAYS_ASK),

        /**
         * Preparado a futuro — Kairos NO pasa hoy un string de comando shell arbitrario generado
         * dinámicamente (a diferencia de un LLM con function-calling libre): todos los módulos
         * corren scripts .sh FIJOS de `modulos/` vía `ProcessBuilder` con argumentos propios (ver
         * `ModuleController.installModule/startModule/stopModule`), nunca un string armado en
         * runtime a partir de una decisión de un LLM. Se deja preparado y documentado para el día
         * que eso deje de ser cierto (ej. una tool de "Agente Kairos" que corra un comando que el
         * usuario tipeó) — no se fuerza un caso de uso que no existe todavía (YAGNI). Ver
         * [HardlineShellGuard] — el piso de bloqueo incondicional que corre ANTES de siquiera
         * llegar a pedir esta aprobación.
         */
        RUN_ARBITRARY_SHELL_COMMAND(ApprovalPolicy.ALWAYS_ASK),
    }

    /** Pedido de aprobación concreto — lo que [ApprovalPresenter] recibe para mostrárselo a un
     *  humano de la forma que le corresponda a cada canal (diálogo Android, mensaje de Telegram
     *  con teclado inline, etc.). */
    data class ApprovalRequest(
        val callId: String,
        val actionType: ActionType,
        val moduleId: String?,
        val detail: String,
    )

    /** Cómo se le muestra un [ApprovalRequest] a un humano — implementado distinto por canal
     *  (ver [androidAlertDialogPresenter] para el caso Android; un futuro presenter de Telegram
     *  enviaría un mensaje con teclado inline en vez de un diálogo). El presenter es responsable
     *  de llamar a [resolve] cuando el humano responda — [requestApproval] queda suspendido hasta
     *  entonces (o hasta el timeout). */
    fun interface ApprovalPresenter {
        fun present(request: ApprovalRequest)
    }

    private data class PendingApproval(
        val deferred: CompletableDeferred<Boolean>,
        val actionType: ActionType,
        val moduleId: String?,
    )

    // ConcurrentHashMap porque resolve() puede llegar desde un hilo distinto al que llamó a
    // requestApproval() — ej. el click listener de un AlertDialog corre en el hilo principal,
    // pero un futuro callback_query de Telegram llegaría desde el hilo de long-polling del bot.
    private val pending = ConcurrentHashMap<String, PendingApproval>()

    // Timeout real, no cosmético — pensado para que un humano note un diálogo/mensaje y responda
    // sin apuro (2 min), no para una decisión instantánea. Igual que ConsentBroker
    // (agent-nexus2.0, APPROVAL_TIMEOUT_SECONDS=300) y HardlineCommandGuard: fail-closed, un
    // timeout SIEMPRE se resuelve como "no aprobado", nunca como "aprobado por default".
    private const val DEFAULT_TIMEOUT_MS = 120_000L

    private const val PREFS_NAME = "kairos_action_approval_gate"

    private fun rememberedKey(actionType: ActionType, moduleId: String?): String =
        "remember.${actionType.name}.${moduleId ?: "*"}"

    /** true si el usuario ya marcó "no volver a preguntar" para esta acción+módulo — SIEMPRE
     *  false para acciones que no son [ApprovalPolicy.REMEMBERABLE] (nunca se persiste nada para
     *  ellas, ver [rememberAlways]). */
    fun isRemembered(context: Context, actionType: ActionType, moduleId: String?): Boolean {
        if (actionType.policy != ApprovalPolicy.REMEMBERABLE) return false
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(rememberedKey(actionType, moduleId), false)
    }

    /**
     * Persiste "no volver a preguntar" para [actionType]+[moduleId] — no-op silencioso a
     * propósito si [actionType] no es [ApprovalPolicy.REMEMBERABLE] (mismo criterio que
     * `NO_ALWAYS_ALLOW` en rikkahub-agent: esas acciones nunca deben poder saltearse con
     * "permitir siempre", ni por un bug de un caller que ignore la política y llame a esto
     * igual — el guard vive ACÁ, en el único punto de escritura, no en cada caller).
     */
    fun rememberAlways(context: Context, actionType: ActionType, moduleId: String?) {
        if (actionType.policy != ApprovalPolicy.REMEMBERABLE) return
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(rememberedKey(actionType, moduleId), true)
            .apply()
    }

    /** Olvida una elección "recordada" previamente — para un futuro tab de configuración del
     *  gate (ej. "olvidar todas las aprobaciones automáticas"). */
    fun forgetRemembered(context: Context, actionType: ActionType, moduleId: String?) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .remove(rememberedKey(actionType, moduleId))
            .apply()
    }

    /**
     * Mecanismo de espera real — quien pide la acción SUSPENDE hasta que [resolve] la resuelva
     * desde afuera (un click de `AlertDialog`, un callback_query de Telegram) o hasta que expire
     * [timeoutMs]. Nunca lanza: un timeout, una excepción de [presenter], o cualquier resultado
     * ambiguo se resuelve como `false` (fail-closed) — un futuro caller nunca debe asumir "sin
     * respuesta = aprobado".
     *
     * Camino corto: si [ActionType.policy] es [ApprovalPolicy.NONE], o si ya está
     * [isRemembered] para [ApprovalPolicy.REMEMBERABLE], devuelve `true` de inmediato sin invocar
     * [presenter] — un caller de solo-lectura (ej. `MODULE_STATUS_QUERY`) nunca interrumpe al
     * usuario.
     */
    suspend fun requestApproval(
        context: Context,
        actionType: ActionType,
        moduleId: String? = null,
        detail: String = "",
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        presenter: ApprovalPresenter,
    ): Boolean {
        if (actionType.policy == ApprovalPolicy.NONE) return true
        if (isRemembered(context, actionType, moduleId)) {
            KairosLogger.log(context, "ActionApprovalGate", "${actionType.name}($moduleId) — auto-aprobado (recordado)")
            return true
        }

        val callId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<Boolean>()
        pending[callId] = PendingApproval(deferred, actionType, moduleId)
        KairosLogger.log(context, "ActionApprovalGate", "${actionType.name}($moduleId) — pidiendo aprobación (callId=$callId)")
        return try {
            presenter.present(ApprovalRequest(callId, actionType, moduleId, detail))
            val approved = withTimeoutOrNull(timeoutMs) { deferred.await() } ?: false
            KairosLogger.log(
                context, "ActionApprovalGate",
                "${actionType.name}($moduleId) — ${if (approved) "aprobado" else "rechazado/timeout"} (callId=$callId)"
            )
            approved
        } catch (e: Exception) {
            KairosLogger.log(context, "ActionApprovalGate", "${actionType.name}($moduleId) — excepción del presenter: ${e.message}")
            false
        } finally {
            pending.remove(callId)
        }
    }

    /**
     * Resuelve una aprobación pendiente por [callId] — llamado por el [ApprovalPresenter] real
     * (el botón de un `AlertDialog`, el handler de un callback_query de Telegram) cuando el
     * humano responde. No-op silencioso si [callId] no existe (ya resuelto, o expiró por
     * timeout) — un doble-click/doble-respuesta no puede resolver dos veces la misma espera.
     *
     * [rememberAlways] solo tiene efecto si [approved] es `true` Y se pasa [context] — sin
     * `context` no hay forma de persistir la elección, así que se ignora en vez de fallar (un
     * presenter que no tiene Context a mano, ej. el bot de Telegram corriendo en un Service,
     * simplemente no ofrece la opción de "recordar").
     */
    fun resolve(callId: String, approved: Boolean, rememberAlways: Boolean = false, context: Context? = null) {
        val entry = pending.remove(callId) ?: return
        if (approved && rememberAlways && context != null) {
            rememberAlways(context, entry.actionType, entry.moduleId)
        }
        entry.deferred.complete(approved)
    }

    /**
     * Presenter por default para Android — reusa `AlertDialog.Builder`, el MISMO mecanismo que
     * ya usa `BaseModuleFragment.confirmUninstallModule()` y el resto de la app (ver regla dura
     * del pedido de esta ronda: esto no reinventa el diálogo, solo lo conecta al gate). El
     * checkbox "No volver a preguntar" solo aparece cuando la política de la acción lo permite
     * ([ApprovalPolicy.REMEMBERABLE]) — para [ApprovalPolicy.ALWAYS_ASK] nunca se ofrece, ni
     * visualmente.
     */
    fun androidAlertDialogPresenter(
        context: Context,
        title: String,
        messageFor: (ApprovalRequest) -> String = { it.detail },
    ): ApprovalPresenter = ApprovalPresenter { request ->
        val allowRemember = request.actionType.policy == ApprovalPolicy.REMEMBERABLE
        val rememberCheckbox = if (allowRemember) {
            android.widget.CheckBox(context).apply {
                text = "No volver a preguntar para esta acción"
                val pad = (16 * context.resources.displayMetrics.density).toInt()
                setPadding(pad, pad / 2, pad, 0)
            }
        } else null
        val builder = androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle(title)
            .setMessage(messageFor(request))
            .setPositiveButton("Aprobar") { _, _ ->
                resolve(request.callId, approved = true, rememberAlways = rememberCheckbox?.isChecked == true, context = context)
            }
            .setNegativeButton("Rechazar") { _, _ -> resolve(request.callId, approved = false) }
            .setOnCancelListener { resolve(request.callId, approved = false) }
        rememberCheckbox?.let { builder.setView(it) }
        builder.show()
    }
}

/**
 * Piso de bloqueo incondicional para un futuro comando shell arbitrario generado dinámicamente
 * (ej. una tool de un futuro "Agente Kairos" corriendo `bash -c "<lo que decida el LLM o el
 * usuario por chat>"`) — NUNCA se ejercita hoy: ver [ActionApprovalGate.ActionType.RUN_ARBITRARY_SHELL_COMMAND]
 * para el motivo completo de por qué este caso no existe todavía en Kairos.
 *
 * Patrón adoptado de `HardlineCommandGuard` (rikkahub-agent, ver
 * `docs/referencias/agentes/REFERENCIA_RIKKAHUB_AGENT.md` sección 1): regex anclados a POSICIÓN
 * de comando (inicio de string, o después de `;`/`&`/`|`/backtick/`$(`/newline, opcionalmente
 * tras `sudo`/`env VAR=val`/wrappers como `exec`/`nohup`/`setsid`/`time`) para no disparar falsos
 * positivos (`echo reboot` no matchea la regla de shutdown) ni dejar un hueco obvio de evasión
 * (`bash -c "shutdown now"` sí matchea, porque el anclado también mira dentro del string
 * literal que sigue a `bash -c "`/`sh -c '`).
 *
 * **Modelo de amenaza explícito, igual de honesto que el original**: esto es el piso contra el
 * error descuidado, NO contra un payload adversarial — variable indirection, heredocs,
 * parameter expansion no son detectables con regex y quedan fuera de alcance a propósito. Contra
 * eso, la defensa real sigue siendo la aprobación por-acción de
 * [ActionApprovalGate.requestApproval] de arriba, que para
 * [ActionApprovalGate.ActionType.RUN_ARBITRARY_SHELL_COMMAND] nunca acepta "permitir siempre"
 * (política [ActionApprovalGate.ApprovalPolicy.ALWAYS_ASK]) — este guard corre ANTES de siquiera
 * llegar a pedir esa aprobación, y su resultado NO es salteable ni con aprobación humana: un
 * comando bloqueado acá se rechaza de raíz, sin mostrarle la opción al usuario.
 */
object HardlineShellGuard {

    // Anclado a: inicio de string | tras ; & | ` $( newline — opcionalmente precedido por
    // sudo/env VAR=val/exec/nohup/setsid/time (mismos wrappers que el original).
    private const val CMD_START =
        """(?:^|[;&|`\n]|\$\()\s*(?:sudo\s+)?(?:env\s+\S+=\S+\s+)?(?:exec\s+|nohup\s+|setsid\s+|time\s+)*"""

    private val HARDLINE_PATTERNS: List<Regex> = listOf(
        // rm -rf (en cualquier orden de flags -rf/-fr/-r -f/etc.) apuntando a raíz o a un
        // directorio de sistema sin camino de recuperación.
        Regex("""$CMD_START rm\s+(?:-\S*\s+)*-[a-zA-Z]*r[a-zA-Z]*f\S*\s+/(?:etc|usr|var|bin|sbin|boot|lib|home|root)?(?:/|\s|$)"""),
        Regex("""$CMD_START mkfs(?:\.\w+)?\s"""),
        // Escritura cruda a un dispositivo de bloque real.
        Regex("""$CMD_START dd\s+.*of=/dev/(?:sd|mmcblk|nvme)"""),
        // Fork bomb clásica.
        Regex(""":\(\)\s*\{\s*:\s*\|\s*:\s*&\s*\}\s*;\s*:"""),
        Regex("""$CMD_START kill\s+-(?:1|HUP)\b"""),
        Regex("""$CMD_START (?:shutdown|reboot|halt|poweroff)\b"""),
        Regex("""$CMD_START systemctl\s+(?:poweroff|reboot|halt)\b"""),
        // Payload codificado (base64/hex) tuberado a un shell — patrón real de evasión de un
        // filtro de este tipo, no una regla contra el uso legítimo de base64.
        Regex("""(?:base64\s+-d|xxd\s+-r)[^|]*\|\s*(?:ba)?sh\b"""),
    )

    /** true si [command] matchea el piso de bloqueo — ver el modelo de amenaza documentado en el
     *  KDoc de la clase antes de confiar en este chequeo para algo más que "el error
     *  descuidado". */
    fun isBlocked(command: String): Boolean = HARDLINE_PATTERNS.any { it.containsMatchIn(command) }
}

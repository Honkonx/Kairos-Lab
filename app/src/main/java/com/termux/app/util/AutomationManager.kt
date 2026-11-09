package com.termux.app.util

import android.content.Context
import com.termux.app.ModuleController
import com.termux.app.data.ModuleCatalog
import com.termux.shared.termux.TermuxConstants
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persistencia + ejecutor real de "Automatizaciones" — pantalla nueva (ver
 * `docs/modulos/AUTOMATIZACIONES.md`) que le da a Kairos triggers registrables (arranque del
 * dispositivo, horario) que disparan una acción real sobre un módulo (iniciar/detener), sin que
 * el usuario tenga que abrir la app y tocar nada — el gap más grande identificado en la
 * investigación de `rikkahub-agent` (ver
 * `docs/referencias/agentes/REFERENCIA_RIKKAHUB_AGENT.md` sección 2, "Workflows autoescritos por
 * IA + jobs programados") y el que más encaja con `.claude/rules/kairos-product-philosophy.md`
 * ("Kairos existe para no vivir en la terminal" — acá ni siquiera hace falta abrir la app).
 *
 * Alcance original (pedido explícito del usuario): 2 triggers reales de punta a punta —
 * [TriggerType.BOOT] (vía [com.termux.app.AutomationBootReceiver]) y [TriggerType.SCHEDULE] (vía
 * [AutomationScheduler] + [com.termux.app.AutomationAlarmReceiver], AlarmManager) — y 2 acciones
 * reales, reusando [ModuleController] tal cual ya existe (`startModule`/`stopModule`), sin
 * reimplementar nada de arranque/parada de módulos.
 *
 * **Ronda 2026-09-16**: se sumó [TriggerType.BATTERY_LEVEL] (ver
 * [com.termux.app.AutomationBatteryReceiver]) — diseño ya documentado en
 * `docs/arquitectura/FUTURO.md` sección "Automatizaciones — triggers diferidos" (`BatteryTrigger`,
 * el más simple de los 4 restantes: sin permiso especial, solo un receiver dinámico). Notificación,
 * geofence y app-en-primer-plano siguen sin implementar — ver esa misma sección.
 *
 * Mismo patrón de registry que [HomelabManager]/`RemoteManager` (una clave con un array JSON
 * compacto en `~/.android_server_registry`, protegido por [RegistryLock]) — se duplica el mismo
 * read-modify-write chico en vez de extraer una base compartida porque el proyecto ya tiene esta
 * duplicación consolidada así en varios managers (Homelab, Remote, Tunnel): cada manager es dueño
 * de su(s) propia(s) clave(s) del mismo archivo compartido, sin herencia entre ellos.
 */
object AutomationManager {

    enum class TriggerType { BOOT, SCHEDULE, BATTERY_LEVEL }

    // GATEWAY_ACTION (2026-11-05, hallazgo real de docs/arquitectura/
    // INVESTIGACION_MODULOS_NUEVOS_Y_PENDIENTES_REFERENCIA_2026-11-05.md: "automatizaciones que
    // ejecuten acciones del gateway -- hoy solo START_MODULE/STOP_MODULE") -- corre cualquier
    // acción real ya registrada en el manifiesto del gateway (ej. "restic.backup" programado a
    // diario) vía [GatewayAppBridge.dispatchInternal], sin pasar por HTTP (mismo proceso). Solo
    // funciona si el gateway YA está corriendo cuando el trigger dispara -- no lo arranca por su
    // cuenta (decisión deliberada: arrancar un proceso de fondo desde un BroadcastReceiver sin
    // que el usuario lo haya pedido explícitamente excede el alcance de esta automatización).
    enum class ActionType { START_MODULE, STOP_MODULE, GATEWAY_ACTION }

    /** Solo para [TriggerType.BATTERY_LEVEL] — BELOW dispara cuando el nivel CRUZA por debajo del
     *  umbral, ABOVE cuando lo cruza por encima (ver [com.termux.app.AutomationBatteryReceiver]). */
    enum class BatteryDirection { BELOW, ABOVE }

    data class Automation(
        val id: String,
        val name: String,
        val triggerType: TriggerType,
        // Solo para SCHEDULE — formato "HH:mm" (24h, hora local del dispositivo). Vacío/sin uso
        // para BOOT/BATTERY_LEVEL. Validado con [isValidHHmm] antes de persistir, nunca a medio
        // validar.
        val scheduleTime: String = "",
        val actionType: ActionType,
        val moduleId: String,
        val enabled: Boolean = true,
        val lastRunTs: Long = 0L,
        // Solo para BATTERY_LEVEL — porcentaje 1..100. Sin uso para el resto de triggers.
        val batteryThreshold: Int = 20,
        val batteryDirection: BatteryDirection = BatteryDirection.BELOW,
        // Solo para ActionType.GATEWAY_ACTION -- target real del manifiesto del gateway (ej.
        // "restic.backup") + sus params como JSON compacto (ej. {"path":"/sdcard/fotos",
        // "repo":"restic_fotos"}). Vacíos/sin uso para START_MODULE/STOP_MODULE.
        val gatewayActionId: String = "",
        val gatewayParamsJson: String = "{}"
    )

    data class ActionResult(val ok: Boolean, val message: String = "", val error: String = "")

    private val HOME = TermuxConstants.TERMUX_HOME_DIR_PATH
    private val REGISTRY_LOCK_FILE get() = ManagerNativeUtils.registryLockFile
    private fun registryFile() = File(HOME, ".android_server_registry")
    private const val AUTOMATIONS_KEY = "automations.workflows"

    private fun registryValues(): Map<String, String> {
        val file = registryFile()
        if (!file.exists()) return emptyMap()
        return try {
            file.readLines().mapNotNull { line ->
                val t = line.trim()
                if (t.isEmpty() || t.startsWith("#")) null
                else {
                    val eq = t.indexOf('=')
                    if (eq > 0) t.substring(0, eq).trim() to t.substring(eq + 1).trim() else null
                }
            }.toMap()
        } catch (_: Exception) { emptyMap() }
    }

    private fun writeRegistryFileLocked(mutate: (MutableMap<String, String>) -> Unit) {
        val file = registryFile()
        val current = registryValues().toMutableMap()
        mutate(current)
        file.writeText(current.entries.joinToString("\n") { "${it.key}=${it.value}" } + "\n")
    }

    private fun serialize(entries: List<Automation>): String {
        val arr = JSONArray()
        entries.forEach { a ->
            arr.put(JSONObject().apply {
                put("id", a.id)
                put("name", a.name)
                put("triggerType", a.triggerType.name)
                put("scheduleTime", a.scheduleTime)
                put("actionType", a.actionType.name)
                put("moduleId", a.moduleId)
                put("enabled", a.enabled)
                put("lastRunTs", a.lastRunTs)
                put("batteryThreshold", a.batteryThreshold)
                put("batteryDirection", a.batteryDirection.name)
                put("gatewayActionId", a.gatewayActionId)
                put("gatewayParamsJson", a.gatewayParamsJson)
            })
        }
        return arr.toString()
    }

    private fun parse(raw: String?): List<Automation> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val trigger = try { TriggerType.valueOf(o.optString("triggerType")) } catch (_: Exception) { return@mapNotNull null }
                val action = try { ActionType.valueOf(o.optString("actionType")) } catch (_: Exception) { return@mapNotNull null }
                Automation(
                    id = o.getString("id"),
                    name = o.optString("name").ifBlank { o.optString("moduleId") },
                    triggerType = trigger,
                    scheduleTime = o.optString("scheduleTime", ""),
                    actionType = action,
                    // optString, no getString: una automatización GATEWAY_ACTION no tiene
                    // moduleId real (usa gatewayActionId en su lugar) -- antes del soporte de
                    // GATEWAY_ACTION, todo entry siempre tenía moduleId, así que esto sigue
                    // retrocompatible con el formato viejo.
                    moduleId = o.optString("moduleId", ""),
                    enabled = o.optBoolean("enabled", true),
                    lastRunTs = o.optLong("lastRunTs", 0L),
                    batteryThreshold = o.optInt("batteryThreshold", 20),
                    batteryDirection = try {
                        BatteryDirection.valueOf(o.optString("batteryDirection", "BELOW"))
                    } catch (_: Exception) { BatteryDirection.BELOW },
                    gatewayActionId = o.optString("gatewayActionId", ""),
                    gatewayParamsJson = o.optString("gatewayParamsJson", "{}")
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Todos los workflows configurados, ordenados por nombre para una lista estable. */
    fun listAutomations(): List<Automation> =
        parse(registryValues()[AUTOMATIONS_KEY]).sortedBy { it.name.lowercase() }

    /** Workflows habilitados de un trigger dado — lo que consumen [com.termux.app.AutomationBootReceiver]
     *  y [AutomationScheduler]/[com.termux.app.AutomationAlarmReceiver]. */
    fun automationsFor(triggerType: TriggerType): List<Automation> =
        listAutomations().filter { it.enabled && it.triggerType == triggerType }

    /** "HH:mm" en 24h — mismo formato que devuelve `TimePickerDialog`, validado acá para no
     *  confiar en que el caller (la UI) nunca mande un valor mal formado. */
    fun isValidHHmm(value: String): Boolean = Regex("^([01]\\d|2[0-3]):[0-5]\\d$").matches(value)

    /**
     * Confirma que [moduleId] existe de verdad en el catálogo de módulos ANTES de persistir un
     * workflow que lo referencie — mismo criterio que `knownToolNamesProvider` de rikkahub-agent
     * (ver `docs/referencias/agentes/REFERENCIA_RIKKAHUB_AGENT.md` sección 2: "el LLM puede crear
     * un cron job él mismo, validando que las tools que el job va a usar existan de verdad...
     * validación contra la lista real ya construida, no una lista estática que se
     * desincroniza"). La UI de Kairos ([com.termux.app.ui.AutomationsFragment]) ya restringe la
     * selección a un Spinner poblado desde el mismo catálogo — esta función es la segunda capa
     * real (nunca confiar solo en que la UI no deje elegir mal, ver
     * `.claude/rules/empirical-verification-before-fix.md`).
     */
    // hasSwitch=true es el mismo campo que ModuleListAdapter/AutomationsFragment usan para
    // decidir qué módulos tienen un toggle real de iniciar/detener — un CLI puro sin switch
    // (hasSwitch=false, ej. Claude Code, Codex) no tiene start/stop script real, así que
    // START_MODULE/STOP_MODULE no tendrían nada que hacer con él. Validar esto acá (y no solo
    // filtrar la lista del Spinner en la UI) es la misma "segunda capa real" documentada arriba.
    fun moduleExists(context: Context, moduleId: String): Boolean =
        try { ModuleCatalog.load(context).any { it.id == moduleId && it.hasSwitch } } catch (_: Exception) { false }

    /** Acciones reales del gateway seguras para programar sin supervisión (idempotentes o de
     *  solo lectura, sin "confirm=true" de por medio) -- hallazgo real: no se habilita CUALQUIER
     *  acción del manifiesto (ej. "module.uninstall"/"fs.delete" serían peligrosas disparadas
     *  solas a una hora fija) tal como [ModuleDetailNavigator]/el manifiesto del gateway no
     *  exponen TODO sin criterio. Ampliar esta lista exige el mismo análisis caso por caso.
     */
    val SAFE_GATEWAY_ACTIONS = listOf("restic.backup", "restic.snapshots", "db.backup", "notify.send")

    fun addAutomation(
        context: Context,
        name: String,
        triggerType: TriggerType,
        scheduleTime: String,
        actionType: ActionType,
        moduleId: String,
        batteryThreshold: Int = 20,
        batteryDirection: BatteryDirection = BatteryDirection.BELOW,
        gatewayActionId: String = "",
        gatewayParamsJson: String = "{}",
        gatewaySecret: String = ""
    ): ActionResult {
        val error = validate(context, triggerType, scheduleTime, moduleId, batteryThreshold, actionType, gatewayActionId)
        if (error != null) return ActionResult(false, error = error)
        val id = "wf_" + System.currentTimeMillis()
        val effectiveName = name.ifBlank {
            if (actionType == ActionType.GATEWAY_ACTION) "$gatewayActionId (${triggerType.name})" else "$moduleId (${triggerType.name})"
        }
        RegistryLock.withLock(REGISTRY_LOCK_FILE) {
            writeRegistryFileLocked { current ->
                val list = parse(current[AUTOMATIONS_KEY])
                current[AUTOMATIONS_KEY] = serialize(
                    list + Automation(
                        id, effectiveName, triggerType, scheduleTime, actionType, moduleId,
                        batteryThreshold = batteryThreshold, batteryDirection = batteryDirection,
                        gatewayActionId = gatewayActionId, gatewayParamsJson = gatewayParamsJson
                    )
                )
            }
        }
        if (actionType == ActionType.GATEWAY_ACTION && gatewaySecret.isNotBlank()) {
            AutomationSecrets.save(context, id, gatewaySecret)
        }
        return ActionResult(true, message = id)
    }

    /** Preserva [Automation.enabled]/[Automation.lastRunTs] del registro existente — este diálogo
     *  nunca toca esos 2 campos (ver [setEnabled]/[markRun] para eso, siempre por separado).
     *  [gatewaySecret] vacío conserva el secreto ya guardado (mismo criterio "nunca se borra sin
     *  querer por dejar el campo en blanco" que `AiProviderPrefs.saveSelection()`). */
    fun updateAutomation(
        context: Context,
        id: String,
        name: String,
        triggerType: TriggerType,
        scheduleTime: String,
        actionType: ActionType,
        moduleId: String,
        batteryThreshold: Int = 20,
        batteryDirection: BatteryDirection = BatteryDirection.BELOW,
        gatewayActionId: String = "",
        gatewayParamsJson: String = "{}",
        gatewaySecret: String = ""
    ): ActionResult {
        val error = validate(context, triggerType, scheduleTime, moduleId, batteryThreshold, actionType, gatewayActionId)
        if (error != null) return ActionResult(false, error = error)
        var found = false
        RegistryLock.withLock(REGISTRY_LOCK_FILE) {
            writeRegistryFileLocked { current ->
                val list = parse(current[AUTOMATIONS_KEY])
                val updated = list.map {
                    if (it.id == id) {
                        found = true
                        it.copy(
                            name = name.ifBlank { if (actionType == ActionType.GATEWAY_ACTION) gatewayActionId else moduleId },
                            triggerType = triggerType,
                            scheduleTime = scheduleTime,
                            actionType = actionType,
                            moduleId = moduleId,
                            batteryThreshold = batteryThreshold,
                            batteryDirection = batteryDirection,
                            gatewayActionId = gatewayActionId,
                            gatewayParamsJson = gatewayParamsJson
                        )
                    } else it
                }
                current[AUTOMATIONS_KEY] = serialize(updated)
            }
        }
        if (found && actionType == ActionType.GATEWAY_ACTION && gatewaySecret.isNotBlank()) {
            AutomationSecrets.save(context, id, gatewaySecret)
        }
        return if (found) ActionResult(true) else ActionResult(false, error = "Automatización no encontrada")
    }

    fun deleteAutomation(context: Context, id: String): ActionResult {
        AutomationSecrets.remove(context, id)
        RegistryLock.withLock(REGISTRY_LOCK_FILE) {
            writeRegistryFileLocked { current ->
                val list = parse(current[AUTOMATIONS_KEY])
                current[AUTOMATIONS_KEY] = serialize(list.filterNot { it.id == id })
            }
        }
        return ActionResult(true)
    }

    fun setEnabled(id: String, enabled: Boolean): ActionResult {
        var found = false
        RegistryLock.withLock(REGISTRY_LOCK_FILE) {
            writeRegistryFileLocked { current ->
                val list = parse(current[AUTOMATIONS_KEY])
                current[AUTOMATIONS_KEY] = serialize(
                    list.map { if (it.id == id) { found = true; it.copy(enabled = enabled) } else it }
                )
            }
        }
        return if (found) ActionResult(true) else ActionResult(false, error = "Automatización no encontrada")
    }

    private fun markRun(id: String) {
        RegistryLock.withLock(REGISTRY_LOCK_FILE) {
            writeRegistryFileLocked { current ->
                val list = parse(current[AUTOMATIONS_KEY])
                current[AUTOMATIONS_KEY] = serialize(
                    list.map { if (it.id == id) it.copy(lastRunTs = System.currentTimeMillis()) else it }
                )
            }
        }
    }

    private fun validate(
        context: Context,
        triggerType: TriggerType,
        scheduleTime: String,
        moduleId: String,
        batteryThreshold: Int = 20,
        actionType: ActionType = ActionType.START_MODULE,
        gatewayActionId: String = ""
    ): String? {
        if (actionType == ActionType.GATEWAY_ACTION) {
            if (gatewayActionId !in SAFE_GATEWAY_ACTIONS) return "Esa acción del gateway no está habilitada para programarse sin supervisión"
        } else {
            if (moduleId.isBlank()) return "Elegí un módulo"
            if (!moduleExists(context, moduleId)) return "El módulo \"$moduleId\" ya no existe en el catálogo"
        }
        if (triggerType == TriggerType.SCHEDULE && !isValidHHmm(scheduleTime)) return "Elegí un horario válido"
        if (triggerType == TriggerType.BATTERY_LEVEL && batteryThreshold !in 1..100) return "Elegí un umbral de batería entre 1 y 100"
        return null
    }

    /**
     * Ejecuta la acción real de [automation] vía [ModuleController] — punto único que llaman
     * tanto [com.termux.app.AutomationBootReceiver] como [com.termux.app.AutomationAlarmReceiver].
     *
     * **Decisión deliberada: esto NO pasa por [ActionApprovalGate]** — documentado acá porque el
     * pedido de esta ronda pedía evaluarlo con criterio, no asumirlo. Dos motivos, ninguno
     * cosmético:
     *  1. **Técnico**: BOOT/SCHEDULE disparan desde un `BroadcastReceiver` sin ninguna `Activity`
     *     visible (el dispositivo recién arrancó, o el teléfono puede estar con la pantalla
     *     apagada/la app cerrada al sonar la alarma) — [ActionApprovalGate.androidAlertDialogPresenter]
     *     necesita un `Context` con ventana real para mostrar un `AlertDialog`; sin eso, cualquier
     *     `requestApproval()` acá SIEMPRE terminaría en timeout → `false` (fail-closed), es decir,
     *     NINGUNA automatización llegaría a ejecutarse nunca. Gatear esto sería, en la práctica,
     *     una automatización que nunca automatiza nada.
     *  2. **De diseño, igual al de rikkahub-agent**: la aprobación real ya ocurrió en el momento
     *     en que el usuario CREÓ/HABILITÓ el workflow desde [com.termux.app.ui.AutomationsFragment]
     *     — mismo criterio que `WorkflowApprovalRenderer` de rikkahub-agent (aprueba la
     *     CREACIÓN/actualización del workflow, no cada disparo individual del trigger, ver
     *     `docs/referencias/agentes/REFERENCIA_RIKKAHUB_AGENT.md` sección 2). Las acciones que
     *     puede ejecutar un workflow (`START_MODULE`/`STOP_MODULE`) son, además, las mismas 2 que
     *     [ActionApprovalGate.ActionType.MODULE_START]/[ActionApprovalGate.ActionType.MODULE_STOP]
     *     ya clasifican como [ActionApprovalGate.ApprovalPolicy.REMEMBERABLE] (nunca
     *     [ActionApprovalGate.ApprovalPolicy.ALWAYS_ASK]) — no hay ninguna acción destructiva
     *     detrás de un workflow en esta primera versión.
     */
    fun execute(context: Context, automation: Automation, onDone: (Boolean, String) -> Unit) {
        markRun(automation.id)
        when (automation.actionType) {
            ActionType.START_MODULE -> ModuleController.startModule(automation.moduleId, context) { ok, msg -> onDone(ok, msg) }
            ActionType.STOP_MODULE -> ModuleController.stopModule(automation.moduleId, context) { ok -> onDone(ok, "") }
            ActionType.GATEWAY_ACTION -> executeGatewayAction(context, automation, onDone)
        }
    }

    /** [ActionType.GATEWAY_ACTION] -- corre vía [GatewayAppBridge.dispatchInternal], sin HTTP de
     *  por medio (mismo proceso). Solo funciona si el gateway YA está arriba (ver KDoc de
     *  [ActionType.GATEWAY_ACTION]) -- un dispatchInternal() que devuelve null significa que no
     *  hay nada corriendo, se reporta como fallo claro en vez de intentar arrancarlo solo. El
     *  secreto guardado (si lo hay, ver [AutomationSecrets]) se inyecta como "password" recién
     *  acá, en el momento de ejecutar -- nunca viaja por el registry en texto plano.
     *
     *  Excepción real: "notify.send" es "kind":"native" (lo ejecuta el router Go, no
     *  GatewayAppBridge) -- se resuelve con [NtfySender] directo, SIN necesitar el gateway activo.
     */
    private fun executeGatewayAction(context: Context, automation: Automation, onDone: (Boolean, String) -> Unit) {
        val params = try { JSONObject(automation.gatewayParamsJson) } catch (_: Exception) { JSONObject() }
        AutomationSecrets.get(context, automation.id).takeIf { it.isNotBlank() }?.let { params.put("password", it) }
        // notify.send es "kind":"native" en default_manifest.json (lo ejecuta el router Go del
        // gateway directamente, nunca pasa por GatewayAppBridge -- ese bridge solo reconoce
        // targets "inapp" como restic.backup/db.backup, ver su `dispatch()`). Para que una
        // automatización pueda mandar un push SIN necesitar el gateway activo (a diferencia de
        // restic/db, que sí necesitan el proceso del gateway corriendo), se resuelve acá mismo
        // con un POST HTTP directo -- mismo protocolo real que internal/notify/ntfy.go del lado
        // Go, sin duplicar ESE código (no hay forma de compartirlo entre Kotlin y Go).
        if (automation.gatewayActionId == "notify.send") {
            val (ok, message) = NtfySender.send(params)
            onDone(ok, message)
            return
        }
        val result = GatewayAppBridge.dispatchInternal(automation.gatewayActionId, params)
        if (result == null) {
            onDone(false, "El gateway no está activo -- iniciá el Gateway desde Homelab para que las automatizaciones de acciones del gateway puedan correr")
            return
        }
        val (code, body) = result
        val ok = code in 200..299
        val message = try { JSONObject(body).optString(if (ok) "message" else "error", body) } catch (_: Exception) { body }
        onDone(ok, message)
    }
}

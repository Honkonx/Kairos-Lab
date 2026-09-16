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
 * Alcance de esta primera versión (pedido explícito del usuario): 2 triggers reales de punta a
 * punta — [TriggerType.BOOT] (vía [com.termux.app.AutomationBootReceiver]) y
 * [TriggerType.SCHEDULE] (vía [AutomationScheduler] + [com.termux.app.AutomationAlarmReceiver],
 * AlarmManager) — y 2 acciones reales, reusando [ModuleController] tal cual ya existe
 * (`startModule`/`stopModule`), sin reimplementar nada de arranque/parada de módulos. Triggers de
 * batería/notificación/geofence (también presentes en rikkahub-agent) quedan documentados como
 * diseño real y accionable en `docs/arquitectura/FUTURO.md` sección "Automatizaciones — triggers
 * diferidos", NO implementados en esta ronda.
 *
 * Mismo patrón de registry que [HomelabManager]/`RemoteManager` (una clave con un array JSON
 * compacto en `~/.android_server_registry`, protegido por [RegistryLock]) — se duplica el mismo
 * read-modify-write chico en vez de extraer una base compartida porque el proyecto ya tiene esta
 * duplicación consolidada así en varios managers (Homelab, Remote, Tunnel): cada manager es dueño
 * de su(s) propia(s) clave(s) del mismo archivo compartido, sin herencia entre ellos.
 */
object AutomationManager {

    enum class TriggerType { BOOT, SCHEDULE }

    enum class ActionType { START_MODULE, STOP_MODULE }

    data class Automation(
        val id: String,
        val name: String,
        val triggerType: TriggerType,
        // Solo para SCHEDULE — formato "HH:mm" (24h, hora local del dispositivo). Vacío/sin uso
        // para BOOT. Validado con [isValidHHmm] antes de persistir, nunca a medio validar.
        val scheduleTime: String = "",
        val actionType: ActionType,
        val moduleId: String,
        val enabled: Boolean = true,
        val lastRunTs: Long = 0L
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
                    moduleId = o.getString("moduleId"),
                    enabled = o.optBoolean("enabled", true),
                    lastRunTs = o.optLong("lastRunTs", 0L)
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

    fun addAutomation(
        context: Context,
        name: String,
        triggerType: TriggerType,
        scheduleTime: String,
        actionType: ActionType,
        moduleId: String
    ): ActionResult {
        val error = validate(context, triggerType, scheduleTime, moduleId)
        if (error != null) return ActionResult(false, error = error)
        val id = "wf_" + System.currentTimeMillis()
        val effectiveName = name.ifBlank { "$moduleId (${triggerType.name})" }
        RegistryLock.withLock(REGISTRY_LOCK_FILE) {
            writeRegistryFileLocked { current ->
                val list = parse(current[AUTOMATIONS_KEY])
                current[AUTOMATIONS_KEY] = serialize(
                    list + Automation(id, effectiveName, triggerType, scheduleTime, actionType, moduleId)
                )
            }
        }
        return ActionResult(true, message = id)
    }

    /** Preserva [Automation.enabled]/[Automation.lastRunTs] del registro existente — este diálogo
     *  nunca toca esos 2 campos (ver [setEnabled]/[markRun] para eso, siempre por separado). */
    fun updateAutomation(
        context: Context,
        id: String,
        name: String,
        triggerType: TriggerType,
        scheduleTime: String,
        actionType: ActionType,
        moduleId: String
    ): ActionResult {
        val error = validate(context, triggerType, scheduleTime, moduleId)
        if (error != null) return ActionResult(false, error = error)
        var found = false
        RegistryLock.withLock(REGISTRY_LOCK_FILE) {
            writeRegistryFileLocked { current ->
                val list = parse(current[AUTOMATIONS_KEY])
                val updated = list.map {
                    if (it.id == id) {
                        found = true
                        it.copy(
                            name = name.ifBlank { moduleId },
                            triggerType = triggerType,
                            scheduleTime = scheduleTime,
                            actionType = actionType,
                            moduleId = moduleId
                        )
                    } else it
                }
                current[AUTOMATIONS_KEY] = serialize(updated)
            }
        }
        return if (found) ActionResult(true) else ActionResult(false, error = "Automatización no encontrada")
    }

    fun deleteAutomation(id: String): ActionResult {
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

    private fun validate(context: Context, triggerType: TriggerType, scheduleTime: String, moduleId: String): String? {
        if (moduleId.isBlank()) return "Elegí un módulo"
        if (!moduleExists(context, moduleId)) return "El módulo \"$moduleId\" ya no existe en el catálogo"
        if (triggerType == TriggerType.SCHEDULE && !isValidHHmm(scheduleTime)) return "Elegí un horario válido"
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
        }
    }
}

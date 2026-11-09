package com.termux.app.util

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Paridad real para el gateway (2026-10-16, ver meta 4-5) — "Automatizaciones"
 * no es un módulo del catálogo (no tiene `.sh` ni entrada protegida en `modules.json`, es una
 * pantalla propia de la app) y encaja de lleno con el mandato de Homelab: triggers (arranque,
 * horario, batería) que disparan iniciar/detener un módulo sin abrir la app. Delega TODO en
 * [AutomationManager] ya existente — mismo registry, misma validación, nada reimplementado.
 */
interface GatewayAutomationsOps {
    fun list(): JSONObject
    fun add(name: String, trigger: String, scheduleTime: String, action: String, moduleId: String, batteryThreshold: Int, batteryDirection: String): JSONObject
    fun delete(id: String): JSONObject
    fun setEnabled(id: String, enabled: Boolean): JSONObject
}

class AppAutomationsOps(private val context: Context) : GatewayAutomationsOps {
    private fun toJson(a: AutomationManager.Automation) = JSONObject()
        .put("id", a.id).put("name", a.name).put("trigger", a.triggerType.name)
        .put("schedule_time", a.scheduleTime).put("action", a.actionType.name).put("module_id", a.moduleId)
        .put("enabled", a.enabled).put("last_run_ts", a.lastRunTs)
        .put("battery_threshold", a.batteryThreshold).put("battery_direction", a.batteryDirection.name)

    private fun result(r: AutomationManager.ActionResult) =
        JSONObject().put("ok", r.ok).apply { if (!r.ok) put("error", r.error) else put("message", r.message) }

    override fun list(): JSONObject =
        JSONObject().put("ok", true).put("automations", JSONArray(AutomationManager.listAutomations().map { toJson(it) }))

    override fun add(name: String, trigger: String, scheduleTime: String, action: String, moduleId: String, batteryThreshold: Int, batteryDirection: String): JSONObject {
        val triggerType = runCatching { AutomationManager.TriggerType.valueOf(trigger) }.getOrNull()
            ?: return JSONObject().put("ok", false).put("error", "trigger inválido: $trigger")
        val actionType = runCatching { AutomationManager.ActionType.valueOf(action) }.getOrNull()
            ?: return JSONObject().put("ok", false).put("error", "action inválida: $action")
        val direction = runCatching { AutomationManager.BatteryDirection.valueOf(batteryDirection) }.getOrDefault(AutomationManager.BatteryDirection.BELOW)
        return result(AutomationManager.addAutomation(context, name, triggerType, scheduleTime, actionType, moduleId, batteryThreshold, direction))
    }

    override fun delete(id: String): JSONObject = result(AutomationManager.deleteAutomation(context, id))
    override fun setEnabled(id: String, enabled: Boolean): JSONObject = result(AutomationManager.setEnabled(id, enabled))
}

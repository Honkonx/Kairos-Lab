package com.termux.app.util

import android.content.Context

/**
 * Chequeos reales que la carita flotante del Agente ([com.termux.app.AgentAvatarService]) corre
 * en cada ciclo de habla, en vez de mostrar SIEMPRE una frase decorativa al azar — pedido
 * explícito del usuario (ver meta 11: "mejorar el agente karios para que
 * pueda hacer todo verificar notificar desde la carita flotante cosas"). Dos señales baratas (sin
 * recorrer los ~65 módulos del catálogo con pgrep cada vez, que sería caro y nada decorativo):
 *
 * 1. El gateway deja de responder DESPUÉS de haber respondido antes — nunca se avisa si el
 *    usuario nunca lo prendió (no hay ninguna preferencia persistida de "el gateway debería estar
 *    corriendo" para comparar, así que la única señal honesta es una transición real sano→caído).
 * 2. Una Automatización (ver [AutomationManager]) acaba de correr — se detecta comparando
 *    `lastRunTs` contra el último valor visto por id, igual de barato que el chequeo de batería
 *    (sin recorrer el catálogo de módulos, [AutomationManager.listAutomations] ya lee solo su
 *    propio registry). Cierra el círculo entre la pantalla de Automatizaciones y la carita: el
 *    usuario se entera de que algo corrió sin tener que abrir la app.
 * 3. Batería baja — una sola vez por ciclo de carga (se rearma cuando vuelve a subir), para no
 *    repetir el mismo aviso en cada chequeo mientras sigue baja.
 * 4. Almacenamiento casi lleno — mismo patrón de rearme que la batería (se rearma cuando vuelve
 *    a bajar), reusando [SystemMetrics.readStorage] (ya lo usa Monitor/Homelab, ninguna lectura
 *    nueva de `/proc` o `StatFs`).
 *
 * Estado en memoria, por proceso — se reinicia si Android mata el servicio, igual que
 * [AgentAvatarService] ya acepta para el resto de su estado (no hay persistencia de sesión). Por
 * eso una automatización que corrió ANTES de que el servicio arrancara nunca se reporta — solo
 * transiciones vistas en vivo durante este proceso, mismo criterio que el chequeo de gateway.
 */
object AgentAvatarWatcher {
    private const val LOW_BATTERY_PERCENT = 15
    private const val BATTERY_REARM_PERCENT = 30
    private const val HIGH_DISK_PERCENT = 90
    private const val DISK_REARM_PERCENT = 80

    private var lastGatewayHealthy: Boolean? = null
    private var lowBatteryWarned = false
    private var lowDiskWarned = false
    private val lastSeenAutomationRunTs = mutableMapOf<String, Long>()

    /** Null si no hay nada notable que decir este ciclo — [com.termux.app.AgentAvatarService] cae
     *  a su frase decorativa normal en ese caso. */
    fun check(context: Context): String? {
        checkGateway()?.let { return it }
        checkAutomations()?.let { return it }
        checkBattery(context)?.let { return it }
        checkDiskSpace()?.let { return it }
        return null
    }

    private fun checkGateway(): String? {
        val healthy = GatewayManager.isHealthy()
        val prev = lastGatewayHealthy
        lastGatewayHealthy = healthy
        return if (prev == true && !healthy) "El gateway dejó de responder — revisalo en Homelab." else null
    }

    private fun checkAutomations(): String? {
        var fired: String? = null
        for (a in AutomationManager.listAutomations()) {
            val prev = lastSeenAutomationRunTs[a.id]
            lastSeenAutomationRunTs[a.id] = a.lastRunTs
            if (fired == null && prev != null && a.lastRunTs > prev) {
                fired = "Automatización \"${a.name}\" se ejecutó recién."
            }
        }
        return fired
    }

    private fun checkBattery(context: Context): String? {
        val level = SystemMetrics.batteryLevel(context) ?: return null
        if (level <= LOW_BATTERY_PERCENT && !lowBatteryWarned) {
            lowBatteryWarned = true
            return "Batería al $level% — quizás quieras cargar antes de seguir con sesiones largas."
        }
        if (level >= BATTERY_REARM_PERCENT) lowBatteryWarned = false
        return null
    }

    private fun checkDiskSpace(): String? {
        val storage = SystemMetrics.readStorage()
        if (!storage.hasPermission) return null
        if (storage.percent >= HIGH_DISK_PERCENT && !lowDiskWarned) {
            lowDiskWarned = true
            return "Almacenamiento al ${storage.percent}% — puede que te quedes sin espacio para modelos/proyectos nuevos."
        }
        if (storage.percent <= DISK_REARM_PERCENT) lowDiskWarned = false
        return null
    }
}

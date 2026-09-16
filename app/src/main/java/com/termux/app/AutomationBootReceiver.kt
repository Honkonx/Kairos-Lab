package com.termux.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.termux.app.util.AutomationManager
import com.termux.app.util.AutomationScheduler
import com.termux.app.util.KairosLogger
import java.util.concurrent.atomic.AtomicInteger

/**
 * Ejecutor real del trigger [AutomationManager.TriggerType.BOOT] de Automatizaciones (ver
 * `docs/modulos/AUTOMATIZACIONES.md`) — `BroadcastReceiver` ESTÁTICO dedicado, declarado propio
 * en el manifest con su propio `<intent-filter>` de `BOOT_COMPLETED`, separado a propósito de
 * `com.termux.app.event.SystemEventReceiver` (que ya escucha esa misma acción para
 * `TermuxShellManager.onActionBootCompleted()` — compatibilidad con scripts de Termux:Boot).
 * Android permite registrar más de un `<receiver>` estático para la misma acción sin conflicto
 * — se prefirió un receiver propio en vez de sumar esta responsabilidad nueva dentro de
 * `SystemEventReceiver.java` para no mezclar "compatibilidad Termux:Boot heredada" (Java, del
 * fork de termux-app) con "automatizaciones propias de Kairos" (Kotlin, feature nueva), dos
 * superficies con evolución independiente.
 *
 * Responsabilidades al arrancar el dispositivo:
 *  1. Re-armar las alarmas de `AlarmManager` para triggers SCHEDULE — sus `PendingIntent` NO
 *     sobreviven un reinicio (ver [AutomationScheduler.scheduleAll]).
 *  2. Ejecutar la acción de cada workflow habilitado con trigger BOOT.
 *
 * `goAsync()`: `ModuleController.startModule()`/`stopModule()` son asíncronos (Thread propio, con
 * timeout de hasta 120s) — sin esto, Android podría reclamar el proceso apenas `onReceive()`
 * retorna, antes de que esos Threads terminen su trabajo real.
 */
class AutomationBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val appContext = context.applicationContext
        KairosLogger.log(appContext, "Automation", "BOOT_COMPLETED — re-armando SCHEDULE + evaluando workflows BOOT")

        AutomationScheduler.scheduleAll(appContext)

        val bootAutomations = try {
            AutomationManager.automationsFor(AutomationManager.TriggerType.BOOT)
        } catch (e: Exception) {
            KairosLogger.log(appContext, "Automation", "BOOT — error leyendo workflows: ${e.message}")
            emptyList()
        }
        if (bootAutomations.isEmpty()) return

        val pendingResult = goAsync()
        val remaining = AtomicInteger(bootAutomations.size)
        bootAutomations.forEach { automation ->
            AutomationManager.execute(appContext, automation) { ok, detail ->
                KairosLogger.log(
                    appContext, "Automation",
                    "BOOT — \"${automation.name}\" (${automation.actionType} ${automation.moduleId}) -> ok=$ok $detail"
                )
                if (remaining.decrementAndGet() <= 0) {
                    try { pendingResult.finish() } catch (_: Exception) {
                        // finish() puede lanzar si ya se llamó antes (no debería, remaining lo
                        // evita) o si el sistema ya reclamó el PendingResult — best-effort.
                    }
                }
            }
        }
    }
}

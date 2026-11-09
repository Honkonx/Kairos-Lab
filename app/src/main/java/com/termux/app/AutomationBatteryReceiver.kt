package com.termux.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.termux.app.util.AutomationManager
import com.termux.app.util.KairosLogger

/**
 * Ejecutor real del trigger [AutomationManager.TriggerType.BATTERY_LEVEL] de Automatizaciones —
 * diseño ya documentado en `docs/arquitectura/FUTURO.md` sección "Automatizaciones — triggers
 * diferidos" (`BatteryTrigger`, el más simple de los 4 que quedaban sin implementar: sin permiso
 * especial, solo un receiver dinámico).
 *
 * `ACTION_BATTERY_CHANGED` es una de las "broadcast exceptions" de Android 8+ (ya no se puede
 * declarar como `<receiver>` estático con `<intent-filter>` implícito en el manifest) — se
 * registra DINÁMICAMENTE en [com.termux.app.TermuxApplication.onCreate] vía
 * [AutomationBatteryReceiver.register], mismo motivo por el que
 * `com.termux.app.event.SystemEventReceiver.registerPackageUpdateEvents()` ya hace lo mismo para
 * otros broadcasts equivalentes.
 *
 * **Costo real, ya documentado en el diseño original**: a diferencia de BOOT/SCHEDULE (que
 * "despiertan desde cero" vía el sistema operativo), este trigger solo es confiable mientras el
 * proceso de Kairos ya esté vivo en memoria — no sobrevive un kill del proceso salvo que el
 * usuario haya abierto la app recientemente o un módulo esté corriendo. No se agregó un
 * foreground service dedicado solo para sostener este trigger (fuera de alcance de esta ronda,
 * YAGNI — ver `.claude/rules/clean-code-principles.md`).
 *
 * Detecta el CRUCE del umbral, no cada `ACTION_BATTERY_CHANGED` (el sistema lo dispara con cada
 * punto porcentual que cambia, varias veces por minuto durante la carga/descarga) — guarda el
 * último nivel visto en memoria del proceso y compara contra el nuevo nivel según la
 * [AutomationManager.BatteryDirection] de cada workflow habilitado, exactamente como describe el
 * diseño de `FUTURO.md` ("comparar lastLevel vs currentLevel contra threshold según direction").
 */
class AutomationBatteryReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null || intent.action != Intent.ACTION_BATTERY_CHANGED) return
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return
        val percent = (level * 100) / scale
        val appContext = context.applicationContext

        val previous = lastLevel
        lastLevel = percent
        // Primer ACTION_BATTERY_CHANGED tras registrar el receiver (sticky broadcast, llega de
        // inmediato con el estado actual) — sin nivel anterior todavía, nada que "cruzar".
        if (previous < 0) return

        val automations = try {
            AutomationManager.automationsFor(AutomationManager.TriggerType.BATTERY_LEVEL)
        } catch (e: Exception) {
            KairosLogger.log(appContext, "Automation", "BATTERY_LEVEL — error leyendo workflows: ${e.message}")
            return
        }
        if (automations.isEmpty()) return

        automations.forEach { automation ->
            val threshold = automation.batteryThreshold
            val crossed = when (automation.batteryDirection) {
                AutomationManager.BatteryDirection.BELOW -> previous >= threshold && percent < threshold
                AutomationManager.BatteryDirection.ABOVE -> previous <= threshold && percent > threshold
            }
            if (!crossed) return@forEach
            KairosLogger.log(
                appContext, "Automation",
                "BATTERY_LEVEL $previous%->$percent% cruzó ${automation.batteryDirection} ${threshold}% — ejecutando \"${automation.name}\""
            )
            AutomationManager.execute(appContext, automation) { ok, detail ->
                KairosLogger.log(appContext, "Automation", "BATTERY_LEVEL — \"${automation.name}\" -> ok=$ok $detail")
            }
        }
    }

    companion object {
        // Registro único por proceso — TermuxApplication.onCreate() corre una vez por cada
        // proceso de la app que arranca (incluido ":xserver", ver comentario de
        // EntornoNative.ensureX11LandscapeDefault() en TermuxApplication.java para el mismo
        // criterio) — este guard evita registrar el receiver 2 veces si onCreate() se llamara más
        // de una vez en el mismo proceso. Registrarlo también en ":xserver" es inofensivo: ese
        // proceso rara vez está vivo, y ModuleController.startModule()/stopModule() son
        // operaciones seguras de repetir (idempotentes en la práctica: iniciar un módulo ya
        // corriendo o detener uno ya detenido no rompe nada).
        @Volatile private var registered = false
        @Volatile private var lastLevel: Int = -1

        fun register(context: Context) {
            if (registered) return
            synchronized(this) {
                if (registered) return
                try {
                    context.applicationContext.registerReceiver(
                        AutomationBatteryReceiver(),
                        IntentFilter(Intent.ACTION_BATTERY_CHANGED)
                    )
                    registered = true
                } catch (_: Exception) {
                    // Best-effort — un fallo acá (no debería pasar, ACTION_BATTERY_CHANGED no
                    // requiere permiso) no debe frenar el resto del arranque de la app.
                }
            }
        }
    }
}

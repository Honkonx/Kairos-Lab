package com.termux.app.util

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.termux.app.AutomationAlarmReceiver
import java.util.Calendar

/**
 * Programación real de triggers [AutomationManager.TriggerType.SCHEDULE] vía `AlarmManager` —
 * ver `docs/modulos/AUTOMATIZACIONES.md` y el KDoc de [AutomationManager] para el diseño
 * completo. Se usa `setInexactRepeating(RTC_WAKEUP, ..., INTERVAL_DAY, ...)` — NO
 * `setExactAndAllowWhileIdle()` — a propósito: un workflow tipo "iniciar Ollama a las 8am" no
 * necesita precisión al segundo, y la variante exacta requiere el permiso `SCHEDULE_EXACT_ALARM`
 * (Android 12+/API 31+), que el usuario tendría que conceder a mano en Ajustes del sistema fuera
 * de la propia app — se evita esa fricción a propósito para esta primera versión (YAGNI, ver
 * `.claude/rules/clean-code-principles.md`). `setInexactRepeating` puede desviarse algunos
 * minutos bajo Doze — aceptable para este caso de uso, documentado acá para que no sorprenda.
 *
 * Cada [AutomationManager.Automation] con trigger SCHEDULE tiene su propio `PendingIntent`,
 * distinguido por un `requestCode` = hash estable de su `id` — nunca colisiona entre
 * automatizaciones distintas, y reprogramar la MISMA automatización (`FLAG_UPDATE_CURRENT`)
 * reemplaza su alarma anterior en vez de acumular una nueva.
 */
object AutomationScheduler {

    // Sin intent-filter en el manifest para AutomationAlarmReceiver (se apunta por clase
    // explícita, no por acción implícita) — esta constante es solo un tag defensivo, no un
    // contrato de IntentFilter real.
    private const val ACTION_AUTOMATION_ALARM = "com.termux.app.AUTOMATION_ALARM_FIRED"
    private const val EXTRA_AUTOMATION_ID = "automation_id"

    private fun requestCodeFor(automationId: String): Int = automationId.hashCode()

    private fun pendingIntentFor(context: Context, automationId: String): PendingIntent {
        val intent = Intent(context, AutomationAlarmReceiver::class.java).apply {
            action = ACTION_AUTOMATION_ALARM
            putExtra(EXTRA_AUTOMATION_ID, automationId)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, requestCodeFor(automationId), intent, flags)
    }

    private fun nextTriggerMillis(hhmm: String): Long {
        val parts = hhmm.split(":")
        val hour = parts.getOrNull(0)?.toIntOrNull() ?: 0
        val minute = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= System.currentTimeMillis()) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return cal.timeInMillis
    }

    /** Arma (o desarma, si [automation] está deshabilitada) la alarma diaria de UNA
     *  automatización. No-op para triggers que no sean SCHEDULE. */
    fun scheduleOne(context: Context, automation: AutomationManager.Automation) {
        if (automation.triggerType != AutomationManager.TriggerType.SCHEDULE) return
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pendingIntent = pendingIntentFor(context, automation.id)
        if (!automation.enabled || !AutomationManager.isValidHHmm(automation.scheduleTime)) {
            alarmManager.cancel(pendingIntent)
            return
        }
        alarmManager.setInexactRepeating(
            AlarmManager.RTC_WAKEUP,
            nextTriggerMillis(automation.scheduleTime),
            AlarmManager.INTERVAL_DAY,
            pendingIntent
        )
    }

    fun cancelOne(context: Context, automationId: String) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        alarmManager.cancel(pendingIntentFor(context, automationId))
    }

    /**
     * Re-arma TODAS las alarmas SCHEDULE habilitadas — llamado tras `BOOT_COMPLETED`
     * ([com.termux.app.AutomationBootReceiver], porque los `PendingIntent` de `AlarmManager` NO
     * sobreviven un reinicio del dispositivo — comportamiento estándar de Android, no un bug) y
     * al abrir [com.termux.app.ui.AutomationsFragment] (red de seguridad barata para cualquier
     * alarma que se haya perdido por otro motivo, ej. la app se instaló de nuevo).
     */
    fun scheduleAll(context: Context) {
        try {
            AutomationManager.listAutomations()
                .filter { it.triggerType == AutomationManager.TriggerType.SCHEDULE }
                .forEach { scheduleOne(context, it) }
        } catch (_: Exception) {
            // Best-effort — un fallo leyendo el registry (ej. corrompido) no debe frenar el
            // resto del boot ni tirar abajo AutomationBootReceiver.
        }
    }

    fun automationIdFromIntent(intent: Intent?): String? = intent?.getStringExtra(EXTRA_AUTOMATION_ID)
}

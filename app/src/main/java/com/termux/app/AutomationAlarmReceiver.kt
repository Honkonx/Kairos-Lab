package com.termux.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.termux.app.util.AutomationManager
import com.termux.app.util.AutomationScheduler
import com.termux.app.util.KairosLogger

/**
 * Ejecutor real del trigger [AutomationManager.TriggerType.SCHEDULE] — disparado por el
 * `PendingIntent` que arma `AutomationScheduler.scheduleOne()`/`scheduleAll()`. Ver
 * `docs/modulos/AUTOMATIZACIONES.md` y el KDoc de [AutomationManager]/[AutomationScheduler] para
 * el diseño completo.
 *
 * `setInexactRepeating(..., INTERVAL_DAY, ...)` ya reprograma el siguiente disparo diario por su
 * cuenta — este receiver NO necesita volver a llamar `scheduleOne()` en el caso normal. La
 * excepción: la automatización referenciada por el `PendingIntent` puede haberse borrado,
 * deshabilitado, o dejado de ser SCHEDULE desde que se armó esa alarma (el usuario la editó desde
 * [com.termux.app.ui.AutomationsFragment] mientras tanto) — se re-lee el registry en vivo en vez
 * de confiar en cualquier snapshot que pudiera viajar en el propio `Intent`, y si ya no aplica se
 * cancela el `PendingIntent` huérfano en vez de dejarlo repitiendo para siempre disparando en el
 * vacío.
 */
class AutomationAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val automationId = AutomationScheduler.automationIdFromIntent(intent) ?: return
        val appContext = context.applicationContext

        val automation = try {
            AutomationManager.listAutomations().firstOrNull { it.id == automationId }
        } catch (e: Exception) {
            KairosLogger.log(appContext, "Automation", "SCHEDULE — error leyendo workflows: ${e.message}")
            null
        }

        if (automation == null || !automation.enabled || automation.triggerType != AutomationManager.TriggerType.SCHEDULE) {
            AutomationScheduler.cancelOne(appContext, automationId)
            return
        }

        // goAsync(): mismo motivo que AutomationBootReceiver — ModuleController.startModule()/
        // stopModule() corren en su propio Thread, con timeout de hasta 120s.
        val pendingResult = goAsync()
        KairosLogger.log(appContext, "Automation", "SCHEDULE ${automation.scheduleTime} — ejecutando \"${automation.name}\"")
        AutomationManager.execute(appContext, automation) { ok, detail ->
            KairosLogger.log(appContext, "Automation", "SCHEDULE — \"${automation.name}\" -> ok=$ok $detail")
            try { pendingResult.finish() } catch (_: Exception) {}
        }
    }
}

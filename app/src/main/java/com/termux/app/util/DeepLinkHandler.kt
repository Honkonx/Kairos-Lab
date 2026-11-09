package com.termux.app.util

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.termux.R
import com.termux.app.ModuleController
import com.termux.app.data.ModuleCatalog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Deep link externo `kairos://module/<id>/start|stop` — pedido explícito de la ronda
 * "comunicación externa con Android" (2026-09-16): antes de esto, un módulo solo se podía
 * arrancar/detener desde DENTRO de la app (tap directo, App Shortcut, Quick Settings Tile, o un
 * [AutomationManager] con trigger 100% interno a Android — arranque/horario/batería). Ninguno de
 * esos mecanismos le sirve a una app de automatización EXTERNA real (Tasker, MacroDroid, un
 * atajo de Google Assistant) que quiera disparar un módulo de Kairos como parte de un flujo más
 * grande — eso necesita una superficie que otra app pueda invocar con un `Intent.ACTION_VIEW`
 * normal, sin saber nada de las clases internas de Kairos. Este objeto es esa superficie.
 *
 * **Por qué SÍ pasa por [ActionApprovalGate] (a diferencia de [AutomationManager.execute])**:
 * [AutomationManager] bypassea el gate a propósito porque sus triggers (BOOT/SCHEDULE/
 * BATTERY_LEVEL) disparan desde un `BroadcastReceiver` sin ninguna `Activity` visible — pedir
 * aprobación ahí siempre terminaría en timeout→rechazado (ver el KDoc de `execute()` para el
 * razonamiento completo). Un deep link es distinto: SIEMPRE abre `TermuxActivity` primero (el
 * mismo mecanismo que el ícono del launcher) — hay ventana real, Y a diferencia de un trigger
 * interno de Android, **cualquier app instalada en el dispositivo puede conocer y usar este
 * scheme** (no solo Tasker configurado a propósito por el usuario) — sin aprobación, sería una
 * superficie de control remoto de Kairos abierta a cualquier app de terceros. `MODULE_START`/
 * `MODULE_STOP` son `ApprovalPolicy.REMEMBERABLE` — el usuario aprueba una vez por módulo+acción
 * (ej. la primera vez que Tasker dispara "iniciar Ollama") y puede marcar "no volver a
 * preguntar" para no interrumpirse en automatizaciones repetidas.
 */
object DeepLinkHandler {

    const val SCHEME = "kairos"
    private const val HOST_MODULE = "module"

    /**
     * Punto de entrada único — llamado desde `TermuxActivity.onCreate()` (cold start) y
     * `onNewIntent()` (la Activity ya existía, `launchMode="singleTask"`), mismo patrón que
     * `handleShortcutIntent()`. No-op silencioso si el intent no matchea el contrato — cualquier
     * otro `ACTION_VIEW` (compartir archivo, abrir link normal) sigue su curso normal sin verse
     * afectado por este handler.
     */
    @JvmStatic
    fun handle(activity: AppCompatActivity, intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != SCHEME || data.host != HOST_MODULE) return

        val segments = data.pathSegments
        if (segments.size < 2) return
        val moduleId = segments[0]
        val action = segments[1].lowercase()
        if (action != "start" && action != "stop") return

        // Evita re-procesar el mismo link si la Activity se recrea (rotación, cambio de tema)
        // sin que llegue un Intent nuevo — mismo criterio que handleShortcutIntent()/removeExtra().
        intent.data = null

        val module = ModuleCatalog.load(activity).firstOrNull { it.id == moduleId } ?: return
        if (!module.hasSwitch) return // sin switch real de start/stop (ej. un CLI puro) — nada que hacer

        val bottomNav = activity.findViewById<BottomNavigationView>(R.id.bottom_navigation)
        bottomNav?.setSelectedItemId(R.id.nav_modules)

        val actionType = if (action == "start") {
            ActionApprovalGate.ActionType.MODULE_START
        } else {
            ActionApprovalGate.ActionType.MODULE_STOP
        }
        val verb = if (action == "start") "iniciar" else "detener"

        CoroutineScope(Dispatchers.Main).launch {
            val approved = ActionApprovalGate.requestApproval(
                context = activity,
                actionType = actionType,
                moduleId = moduleId,
                detail = "Un enlace externo pide $verb el módulo \"${module.name}\".",
                presenter = ActionApprovalGate.androidAlertDialogPresenter(
                    activity,
                    title = "Acceso externo a Kairos"
                )
            )
            if (!approved) return@launch
            if (action == "start") {
                ModuleController.startModule(moduleId, activity) { _, _ -> }
            } else {
                ModuleController.stopModule(moduleId, activity) { _ -> }
            }
        }
    }
}

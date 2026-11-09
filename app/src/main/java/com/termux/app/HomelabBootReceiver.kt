package com.termux.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.termux.app.util.HomelabPanelPrefs
import com.termux.app.util.HomelabServer
import com.termux.app.util.KairosLogger

/**
 * Arranca el panel web de Homelab ([HomelabServer]) al encender el teléfono, SOLO si la persona
 * activó la preferencia correspondiente ([HomelabPanelPrefs.isAutoStartEnabled] — switch en la
 * pestaña Panel de Homelab, apagado por defecto). Ítem #6 de
 * `docs/referencias/homelab/AUDITORIA_homelab_2026-10-09.md` (inspirado en el
 * `BootReceiver`/modos `ByUser/ByIntent/OnBoot` de Sshd4a-dev, GPL-3 — solo se adoptó la IDEA,
 * código propio, ver `.claude/rules/kairos-port-or-build-own.md`).
 *
 * `MY_PACKAGE_REPLACED` además de `BOOT_COMPLETED` — una actualización del APK también mata el
 * proceso de la app, y [HomelabServer] es un `object` en memoria (sin servicio en primer plano
 * todavía, ver nota de alcance abajo): sin este segundo disparador, actualizar la app dejaría el
 * panel apagado hasta que alguien lo abra a mano, aunque la persona haya pedido que arranque solo.
 *
 * Receiver estático propio, separado de `AutomationBootReceiver`/`SystemEventReceiver` (mismo
 * criterio que esos dos: Android permite más de un `<receiver>` para la misma acción sin
 * conflicto, y mezclar "automatizaciones del usuario" con "arranque del panel de Homelab" en una
 * sola clase complicaría ambas sin necesidad real).
 *
 * **Alcance real, sin verificar en dispositivo todavía** (ver ``): esto arranca
 * [HomelabServer] en el hilo del propio proceso de la app, SIN un servicio en primer plano —
 * en Android 8+ el sistema puede matar procesos en segundo plano agresivamente según el
 * fabricante/ajustes de batería. Si el panel no sigue corriendo tras un rato con la app cerrada,
 * el gap real es la ausencia de un foreground service (ítem ya anotado como "robustecer el
 * arranque" en la auditoría), no un bug de este receiver — queda pendiente de confirmar con un
 * dispositivo real antes de prometer que "siempre" sobrevive.
 */
class HomelabBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED && intent?.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val appContext = context.applicationContext
        if (!HomelabPanelPrefs.isAutoStartEnabled(appContext)) return
        val started = HomelabServer.start(context = appContext)
        KairosLogger.log(appContext, "Homelab", "Arranque automático (${intent.action}) -> iniciado=$started")
    }
}

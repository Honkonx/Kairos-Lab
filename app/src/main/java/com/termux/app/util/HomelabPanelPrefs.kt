package com.termux.app.util

import android.content.Context

/**
 * Preferencia "iniciar el panel web de Homelab al encender el teléfono" — ítem #6 de la auditoría
 * de referencia (`docs/referencias/homelab/AUDITORIA_homelab_2026-10-09.md`, inspirado en el
 * `BootReceiver`/`StartMode.ByUser/ByIntent/OnBoot` de Sshd4a-dev, GPL-3: solo se adoptó la IDEA
 * de un flag de arranque automático, código propio desde cero — ver
 * `.claude/rules/kairos-port-or-build-own.md`).
 *
 * Mismo archivo `SharedPreferences` ("kairos_prefs") que ya usa [QuickAccessPrefs] — no un
 * registry nuevo, es puramente UI/comportamiento de la app, ningún script bash lo necesita leer.
 */
object HomelabPanelPrefs {

    private const val PREFS_NAME = "kairos_prefs"
    private const val KEY_AUTO_START = "pref_homelab_auto_start_boot"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isAutoStartEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_START, false)

    fun setAutoStartEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_START, enabled).apply()
    }
}

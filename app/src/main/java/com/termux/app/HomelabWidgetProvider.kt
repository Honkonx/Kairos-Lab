package com.termux.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.termux.R
import com.termux.app.util.SystemMetrics
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Widget de home screen "Homelab" — gap real de "comunicación externa con Android" (ronda
 * 2026-09-16, ver `.claude/rules/kairos-product-philosophy.md`): Kairos no tenía NINGÚN widget
 * de pantalla de inicio pese a que `HomelabFragment`/[SystemMetrics] ya exponen exactamente los
 * datos que un widget de este tipo necesita (CPU/RAM/módulos corriendo) — reusa [SystemMetrics]
 * tal cual (mismo criterio DRY que ya documenta el KDoc de ese objeto: una sola fuente de verdad
 * para Homelab nativo, el panel web de `HomelabServer`, y ahora este widget).
 *
 * **Alcance v1, deliberadamente acotado** (mismo criterio que `QuickModuleTileService`/
 * `QuickAccessPrefs`, ronda 2026-09-15 — "v1: ... no N tiles dinámicos", documentado como
 * limitación explícita, no una omisión silenciosa): un solo tipo de widget (métricas +
 * conteo de módulos), sin selector de qué métricas mostrar. Se actualiza:
 *  1. Cada vez que Android lo llama por [onUpdate] — al agregar el widget, y cada
 *     `updatePeriodMillis` (30 min, ver `homelab_widget_info.xml` — el mínimo real que Android
 *     respeta para un `AppWidgetProvider`, documentado oficialmente; no hay forma de pedir un
 *     intervalo más corto sin `WorkManager`/`AlarmManager` propio, fuera de alcance de esta
 *     ronda).
 *  2. Tocando el widget — el tap ABRE Kairos en el tab Homelab (vía
 *     `TermuxActivity.EXTRA_SHORTCUT_NAV_ID`, el mismo mecanismo que el App Shortcut estático
 *     "Homelab"), no refresca el widget en el lugar — refrescar sin abrir la app exigiría un
 *     segundo `PendingIntent` de broadcast + `RemoteViews` con 2 targets superpuestos, complejidad
 *     real que no se justifica para un widget de solo-lectura cuyo dato más importante (módulos
 *     corriendo) ya cambia lento en la práctica.
 *
 * Sin permisos nuevos: [SystemMetrics] ya usa únicamente lecturas que la app tiene acceso
 * (`/proc/meminfo`, `/proc/stat`, `ModuleController.isRunning()`), nada exclusivo de este widget.
 */
class HomelabWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { id -> updateWidget(context, appWidgetManager, id) }
    }

    companion object {

        /** Llamable desde cualquier parte de la app (ej. tras un cambio real de estado de
         *  módulos) para refrescar el widget sin esperar al próximo `onUpdate()` periódico —
         *  no usado todavía en esta ronda (ver KDoc de la clase, punto 2: se mantiene simple a
         *  propósito), pero expuesto como punto de extensión real, no una promesa vacía. */
        @JvmStatic
        fun requestUpdate(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                android.content.ComponentName(context, HomelabWidgetProvider::class.java)
            )
            ids.forEach { id -> updateWidget(context, manager, id) }
        }

        private fun updateWidget(context: Context, appWidgetManager: AppWidgetManager, widgetId: Int) {
            val appContext = context.applicationContext
            val views = RemoteViews(appContext.packageName, R.layout.widget_homelab)

            val ram = SystemMetrics.readRam()
            views.setTextViewText(
                R.id.widget_cpu,
                appContext.getString(R.string.widget_homelab_cpu_unavailable)
            )
            // CPU real necesita 2 muestras con delay (ver SystemMetrics.sampleCpu()/cpuPercent()) —
            // un widget no tiene forma limpia de esperar sin bloquear el hilo del AppWidgetProvider
            // (llamado en el hilo principal por Android), así que esta v1 muestra RAM+módulos
            // (lecturas de 1 sola muestra) y deja CPU como "—" en vez de una medición falsa de una
            // sola muestra (0% o 100% según el jiffy exacto en que se lea) — documentado como
            // limitación v1 explícita, no un bug.
            if (ram != null) {
                views.setTextViewText(R.id.widget_ram, "RAM ${ram.percent}%")
            } else {
                views.setTextViewText(R.id.widget_ram, "RAM —")
            }

            val running = SystemMetrics.moduleStates(appContext).count { it.running }
            views.setTextViewText(
                R.id.widget_modules,
                appContext.resources.getQuantityString(R.plurals.widget_homelab_modules_running, running, running)
            )

            val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
            views.setTextViewText(
                R.id.widget_updated_at,
                appContext.getString(R.string.widget_homelab_updated_at, timeFmt.format(Date()))
            )

            val openIntent = Intent(appContext, TermuxActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(TermuxActivity.EXTRA_SHORTCUT_NAV_ID, "nav_homelab")
            }
            val openPendingIntent = PendingIntent.getActivity(
                appContext, widgetId, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_root, openPendingIntent)

            appWidgetManager.updateAppWidget(widgetId, views)
        }
    }
}

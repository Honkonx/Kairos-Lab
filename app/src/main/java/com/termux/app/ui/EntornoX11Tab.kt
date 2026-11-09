package com.termux.app.ui

import android.content.Intent
import android.widget.LinearLayout
import com.termux.R
import com.termux.app.X11Service
import com.termux.app.x11.KairosX11PreferencesActivity
import com.termux.app.util.EntornoNative

/**
 * Pestaña "X11" de EntornoFragment (Mini PC) — servidor X11 embebido en sí (Xlorie/termux-x11
 * fork, ver docs/x11/X11_EMBEBIDO.md), separado de "Nativo": el usuario pidió la separación
 * explícitamente porque X11 es la base compartida por Nativo, proot-distro, y a futuro
 * Wine+DXVK+Box64+FEX (docs/arquitectura/FUTURO.md §9) — no es una acción propia del
 * escritorio nativo. Extraído de EntornoFragment.kt en el refactor de separación por pestaña
 * (reorganización pura, ver git history de EntornoFragment.kt para el detalle histórico
 * completo de cada decisión).
 *
 * `launchX11()` en sí sigue viviendo en EntornoFragment (internal) porque también lo llaman
 * EntornoNativoTab y EntornoDistrosTab desde sus Snackbars de "escritorio listo" — es
 * genuinamente compartido por 3 pestañas, no algo propio de esta.
 */
internal class EntornoX11Tab(private val fragment: EntornoFragment) {

    /**
     * Pestaña "X11" — servidor X11 embebido. Incluye "Detener servidor X11" (mata el proceso
     * Xlorie), acción sobre el servidor X11 mismo, no sobre el escritorio/DE que corre encima.
     */
    fun render(parent: LinearLayout) {
        val ctx = fragment.requireContext()
        sectionLabel(ctx, parent, fragment.getString(R.string.entorno_seccion_servidor_x11))
        parent.addView(inventorySubLabel(ctx, fragment.getString(R.string.entorno_subtitulo_display_x11)))
        rowList(ctx, parent, listOf(
            actionRow(ctx, RowAction(fragment.getString(R.string.entorno_tile_entrar_x11), fragment.getString(R.string.entorno_row_desc_x11_entrar), R.drawable.ic_x11, running = { fragment.x11Running }) { fragment.launchX11() }),
            actionRow(ctx, RowAction(fragment.getString(R.string.entorno_tile_configuracion_x11), fragment.getString(R.string.entorno_row_desc_x11_config), R.drawable.ic_settings) { openX11Settings() }),
            actionRow(ctx, RowAction(fragment.getString(R.string.entorno_tile_detener_servidor_x11), fragment.getString(R.string.entorno_row_desc_x11_detener), R.drawable.ic_stop, running = { fragment.x11Running }) { stopEmbeddedX11() })
        ))
        // Wine (Hangover) quitado de la interfaz 2026-09-22 (orden explícita del usuario, ver
        //) — Wine/Box64/FEX queda diferido para una ronda futura, no se
        // muestra en la UI mientras tanto. Corrección 2026-11-02 (auditoría Mini PC,
        // docs/mini-pc/PLAN_MEJORAS_MINIPC_AUDITORIA_REFERENCIA_2026-11-02.md §9): el
        // comentario anterior decía que "modulos/wine.sh y ModuleController siguen intactos" —
        // eso ya no es cierto, modulos/wine.sh no existe en disco y "wine" está en
        // ModuleCatalog.RETIRED_IDS. El código muerto que dependía de él
        // (EntornoFragment.pickWineExe/copyUriToWineExeFile/applyPickedExe,
        // EntornoNative.wineInstalled/wineRunExe) se eliminó la misma ronda.
    }

    /**
     * "Configuración de X11" — KairosX11PreferencesActivity (subclase de LoriePreferences, la
     * pantalla de preferencias ORIGINAL de termux-x11: resolución, escala, orientación
     * forzada, fullscreen, PiP, teclado extra, touch...). Se abre standalone; los cambios se
     * propagan por broadcast ACTION_PREFERENCES_CHANGED al visor en vivo.
     */
    private fun openX11Settings() {
        fragment.startActivity(Intent(fragment.requireContext(), KairosX11PreferencesActivity::class.java).apply {
            action = Intent.ACTION_MAIN
        })
    }

    /** Mismo contrato que X11Fragment.stopX11(): broadcast ACTION_STOP (cierra el visor si está abierto) + X11Service.stop() (mata el proceso :xserver). */
    private fun stopEmbeddedX11() {
        val ctx = fragment.requireContext()
        ctx.sendBroadcast(Intent("com.termux.x11.ACTION_STOP").setPackage(ctx.packageName))
        X11Service.stop(ctx)
        // Apagar el servidor X11 mata cualquier DE (nativo o de distro) que estuviera arriba
        // con él — limpia la marca de exclusividad para no dejar bloqueado el próximo arranque.
        EntornoNative.clearDesktopMode()
        fragment.toastMsg(fragment.getString(R.string.entorno_toast_servidor_x11_detenido))
        fragment.refreshStatus()
    }
}

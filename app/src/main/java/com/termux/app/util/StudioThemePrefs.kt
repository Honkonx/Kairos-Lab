package com.termux.app.util

import android.content.Context
import com.termux.R

/**
 * Selector de tema de Estudio (IDE embebido) — INDEPENDIENTE de [KairosThemePrefs] (tema del
 * apk), pedido explícito del usuario (ver docs/humano/humano202.md): "el tema del ide/estudio no debe
 * ser el mismo que el del apk si es posible que ambos tengas tenga su botones de temas". Fase
 * mínima (ver docs/ide/PLAN_REDISENO_ESTUDIO_IDE_2026-08-22.md §3-4): solo 2 temas
 * propios (Oscuro/Claro) + una opción [StudioTheme.SYNC_WITH_APP] que no es un 3er style propio
 * — reusa Oscuro o Claro según la polaridad del tema Kairos activo (ver [resolveStyleRes]), para
 * el usuario que prefiera un solo tema global sin tener que elegir 2 veces.
 *
 * "Oscuro" mapea a los mismos valores que `Theme.KairosIde` (studio_themes.xml) ya usaba fijo —
 * cero cambio visual para quien no toque el picker nuevo, mismo criterio que [KairosThemePrefs].
 */
object StudioThemePrefs {

    enum class StudioTheme(val id: String, val label: String, val styleRes: Int?) {
        OSCURO("oscuro", "Oscuro", R.style.Theme_Studio_Oscuro),
        CLARO("claro", "Claro", R.style.Theme_Studio_Claro),
        SYNC_WITH_APP("sync", "Igual que el apk", null);

        companion object {
            fun fromId(id: String?): StudioTheme = entries.firstOrNull { it.id == id } ?: OSCURO
        }
    }

    private const val PREFS_NAME = "kairos_studio_theme_prefs"
    private const val KEY_THEME_ID = "studio_theme_id"

    fun getSelectedTheme(context: Context): StudioTheme {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return StudioTheme.fromId(prefs.getString(KEY_THEME_ID, null))
    }

    fun setSelectedTheme(context: Context, theme: StudioTheme) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_THEME_ID, theme.id).apply()
    }

    /**
     * Resuelve el style real a aplicar — nunca null, a diferencia de [StudioTheme.styleRes]
     * (que sí puede serlo para SYNC_WITH_APP). Cuando el usuario eligió "igual que el apk", no
     * existe un 3er `Theme.Studio.*` — se aproxima por polaridad: Claro del apk → Claro de
     * Estudio, Oscuro/Señal del apk → Oscuro de Estudio (Señal es una variante oscura de acento,
     * no un tema claro — ver KairosThemePrefs.KairosTheme).
     */
    fun resolveStyleRes(context: Context): Int {
        val selected = getSelectedTheme(context)
        selected.styleRes?.let { return it }
        val appTheme = KairosThemePrefs.getSelectedTheme(context)
        return if (appTheme == KairosThemePrefs.KairosTheme.CLARO) {
            StudioTheme.CLARO.styleRes!!
        } else {
            StudioTheme.OSCURO.styleRes!!
        }
    }
}

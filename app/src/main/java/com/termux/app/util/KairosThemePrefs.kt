package com.termux.app.util

import android.content.Context
import com.termux.R

/**
 * Selector de tema visual de Kairos — 3 temas (Oscuro/Señal/Claro, ver
 * res/values/themes_kairos.xml), pedido explícito del usuario (ver docs/humano/humano190.md): "no es
 * eliminar la tematica/tema/estilo que tenemos es agregar una opcion para cambiar el tema...
 * dejar el que tenemos, añadir ese que te dije y tambien un modo claro". "Oscuro" es el default
 * (mismos valores que colors_kairos.xml de siempre) — instalar la app y no tocar el selector se
 * ve exactamente igual que antes de este cambio.
 *
 * TermuxActivity.setActivityTheme() llama [applyTheme] ANTES de super.onCreate() (mismo punto
 * donde ya se resuelve el day/night mode) — Android requiere que setTheme() se llame antes de
 * que la Activity infle su contenido, no se puede cambiar después sin recreate().
 */
object KairosThemePrefs {

    enum class KairosTheme(val id: String, val label: String, val styleRes: Int) {
        OSCURO("oscuro", "Oscuro", R.style.Theme_Kairos_Oscuro),
        SENAL("senal", "Señal", R.style.Theme_Kairos_Senal),
        CLARO("claro", "Claro", R.style.Theme_Kairos_Claro);

        companion object {
            fun fromId(id: String?): KairosTheme = entries.firstOrNull { it.id == id } ?: OSCURO
        }
    }

    private const val PREFS_NAME = "kairos_theme_prefs"
    private const val KEY_THEME_ID = "theme_id"

    fun getSelectedTheme(context: Context): KairosTheme {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return KairosTheme.fromId(prefs.getString(KEY_THEME_ID, null))
    }

    fun setSelectedTheme(context: Context, theme: KairosTheme) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_THEME_ID, theme.id).apply()
    }

    /** Llamar ANTES de super.onCreate() en cualquier Activity que use ?attr/kairos*. */
    fun applyTheme(context: android.app.Activity) {
        context.setTheme(getSelectedTheme(context).styleRes)
    }
}

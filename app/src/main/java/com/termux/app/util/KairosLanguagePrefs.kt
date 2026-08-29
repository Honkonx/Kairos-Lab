package com.termux.app.util

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * Selector de idioma de Kairos (español/inglés/sistema) — pedido explícito del usuario tras la
 * migración i18n de hoy (3081 strings, pares `values/strings_*.xml` ↔ `values-en/strings_*.xml`):
 * "en la pantalla de config no veo donde diga idioma y salga español y ingles". El i18n ya
 * funcionaba automáticamente según el locale del sistema — lo que faltaba era un selector real
 * en la UI para forzar un idioma sin depender de la configuración de Android.
 *
 * Usa `AppCompatDelegate.setApplicationLocales()` (androidx.appcompat, API per-app language
 * moderna, backport funcional desde API 24) — NO un hack manual de `Locale.setDefault()` +
 * `Context.createConfigurationContext()` + recreate propio: AppCompatDelegate ya maneja la
 * recreación de Activities y la persistencia entre reinicios de la app internamente (guarda el
 * locale elegido vía un `SharedPreferences` propio de AndroidX, no hace falta guardarlo acá
 * también). `getSelectedLanguage()` solo refleja ese estado para poder pintar el check ✓ correcto
 * en el picker — la fuente de verdad real es `AppCompatDelegate.getApplicationLocales()`.
 */
object KairosLanguagePrefs {

    enum class KairosLanguage(val id: String, val label: String) {
        SISTEMA("system", "Sistema"),
        ESPANOL("es", "Español"),
        INGLES("en", "English");

        companion object {
            fun fromId(id: String?): KairosLanguage = entries.firstOrNull { it.id == id } ?: SISTEMA
        }
    }

    fun getSelectedLanguage(): KairosLanguage {
        val locales = AppCompatDelegate.getApplicationLocales()
        if (locales.isEmpty) return KairosLanguage.SISTEMA
        return KairosLanguage.fromId(locales[0]?.language)
    }

    fun setSelectedLanguage(language: KairosLanguage) {
        val locales = if (language == KairosLanguage.SISTEMA) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(language.id)
        }
        // AppCompatDelegate.setApplicationLocales() ya dispara la recreación de todas las
        // Activities vivas por su cuenta (vía LocaleAwareCompatActivity/base delegate) — no hace
        // falta llamar a requireActivity().recreate() manualmente después de esto.
        AppCompatDelegate.setApplicationLocales(locales)
    }
}

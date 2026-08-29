package com.termux.app.util

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Almacenamiento cifrado para los secretos (API keys) que maneja ChatFragment — las 5 claves BYO
 * de proveedores cloud ("cloud_api_key_<provider>") y la key de la Web Search API de Ollama
 * ("ollama_web_api_key"). Antes vivían en texto plano dentro de "kairos_llm_prefs" mezcladas con
 * preferencias no sensibles (proveedor activo, toggles, historial, etc) — hallazgo 2.2 de
 * docs/arquitectura/AUDITORIA_IA_CODIGO_2026-08-19.md. Mismo patrón que
 * com.termux.app.ui.studio.ai.AiProviderPrefs (MasterKey + EncryptedSharedPreferences), pero sin
 * duplicar esa clase: acá el proveedor es un String suelto (no el enum AiProvider de Estudio) y
 * hace falta migración lazy desde el storage plano viejo, que AiProviderPrefs no necesita porque
 * nunca tuvo datos previos en texto plano.
 *
 * Migración lazy: en cada lectura, si la clave todavía no está en el storage cifrado pero SÍ
 * existe en el storage plano viejo, se migra de forma transparente (lee del viejo, escribe en el
 * nuevo, borra del viejo) — no hay paso de instalación aparte, ningún usuario con una clave ya
 * guardada la pierde. Solo los secretos pasan por acá — el resto de "kairos_llm_prefs"
 * (proveedor activo, toggles, transporte, historial) sigue en SharedPreferences normales, ver
 * cloudPrefs() en ChatFragment.
 */
class SecureChatPrefs(context: Context) {

    private val legacyPrefs: SharedPreferences =
        context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)

    private val securePrefs: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            SECURE_PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /** Lee un secreto por su clave, migrando en el momento desde el storage plano viejo si hace falta. */
    fun getSecret(key: String): String {
        val fromSecure = securePrefs.getString(key, null)
        if (fromSecure != null) return fromSecure
        val fromLegacy = legacyPrefs.getString(key, null)
        if (fromLegacy != null) {
            securePrefs.edit().putString(key, fromLegacy).apply()
            legacyPrefs.edit().remove(key).apply()
            return fromLegacy
        }
        return ""
    }

    /** Guarda un secreto en el storage cifrado y limpia cualquier resto en el storage plano viejo. */
    fun setSecret(key: String, value: String) {
        securePrefs.edit().putString(key, value).apply()
        if (legacyPrefs.contains(key)) {
            legacyPrefs.edit().remove(key).apply()
        }
    }

    companion object {
        private const val LEGACY_PREFS_NAME = "kairos_llm_prefs"
        private const val SECURE_PREFS_NAME = "kairos_llm_secure_prefs"
    }
}

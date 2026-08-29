package com.termux.app.ui.studio.ai

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Persistencia de la elección de proveedor de IA + la API key BYO de cada uno.
 * EncryptedSharedPreferences (androidx.security:security-crypto:1.1.0, versión estable real) —
 * a diferencia de la referencia android-code-studio-dev, que guarda las keys en
 * SharedPreferences planas (prefManager.getString sin cifrar, ver
 * artificial/secrets/ApiKey.kt) — acá se prefiere cifrado ya que es la primera vez que se
 * implementa este guardado desde cero, sin una razón para replicar esa debilidad.
 */
class AiProviderPrefs(context: Context) {

    private val prefs: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "kairos_ide_ai_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun getSelectedProvider(): AiProvider =
        AiProvider.fromId(prefs.getString(KEY_SELECTED_PROVIDER, null))

    fun setSelectedProvider(provider: AiProvider) {
        prefs.edit().putString(KEY_SELECTED_PROVIDER, provider.id).apply()
    }

    fun getApiKey(provider: AiProvider): String =
        prefs.getString(apiKeyPrefKey(provider), "") ?: ""

    fun setApiKey(provider: AiProvider, apiKey: String) {
        prefs.edit().putString(apiKeyPrefKey(provider), apiKey).apply()
    }

    private fun apiKeyPrefKey(provider: AiProvider) = "api_key_${provider.id}"

    companion object {
        private const val KEY_SELECTED_PROVIDER = "selected_provider"
    }
}

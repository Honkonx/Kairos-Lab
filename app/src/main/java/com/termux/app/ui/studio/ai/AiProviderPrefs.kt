package com.termux.app.ui.studio.ai

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.termux.app.util.AiCompany

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

    fun getSelectedProvider(): AiCompany =
        AiCompany.fromId(prefs.getString(KEY_SELECTED_PROVIDER, null))

    fun setSelectedProvider(provider: AiCompany) {
        prefs.edit().putString(KEY_SELECTED_PROVIDER, provider.id).apply()
    }

    fun getApiKey(provider: AiCompany): String =
        prefs.getString(apiKeyPrefKey(provider), "") ?: ""

    fun setApiKey(provider: AiCompany, apiKey: String) {
        prefs.edit().putString(apiKeyPrefKey(provider), apiKey).apply()
    }

    private fun apiKeyPrefKey(provider: AiCompany) = "api_key_${provider.id}"

    /** Base URL + modelo del proveedor "Personalizado (OpenAI-compatible)" (hallazgo real de
     *  docs/referencias/ia/REFERENCIA_CROSS_PLATFORM_LLM_CLIENT.md) — solo relevante cuando
     *  [getSelectedProvider] es [AiCompany.CUSTOM]. No secretos, no necesitan el mismo
     *  cifrado reforzado que la API key, pero igual viven en el mismo EncryptedSharedPreferences
     *  por simplicidad (no vale la pena un segundo almacén para 2 strings). */
    fun getCustomBaseUrl(): String = prefs.getString(KEY_CUSTOM_BASE_URL, "") ?: ""

    fun setCustomBaseUrl(url: String) {
        prefs.edit().putString(KEY_CUSTOM_BASE_URL, url).apply()
    }

    fun getCustomModel(): String = prefs.getString(KEY_CUSTOM_MODEL, "") ?: ""

    fun setCustomModel(model: String) {
        prefs.edit().putString(KEY_CUSTOM_MODEL, model).apply()
    }

    companion object {
        private const val KEY_SELECTED_PROVIDER = "selected_provider"
        private const val KEY_CUSTOM_BASE_URL = "custom_base_url"
        private const val KEY_CUSTOM_MODEL = "custom_model"
    }
}

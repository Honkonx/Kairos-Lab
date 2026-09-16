package com.termux.app.ui.studio.git

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Almacenamiento cifrado del access token de GitHub (Device Flow, ver [GitHubDeviceAuth]) usado
 * por [GitPanelActivity] para push/pull autenticado. Mismo patrón que
 * `com.termux.app.ui.studio.ai.AiProviderPrefs` (EncryptedSharedPreferences propio, prefs file
 * dedicado) en vez de reusar `com.termux.app.util.SecureChatPrefs` — ese storage es
 * específicamente de los secretos de ChatFragment (API keys BYO de proveedores cloud), no un
 * store genérico compartido entre features.
 *
 * Regla dura del proyecto: el token guardado acá NUNCA se
 * expone de nuevo en la UI — [GitPanelActivity] solo lo usa para armar la URL del remote al
 * ejecutar push/pull, nunca lo muestra. Las únicas acciones sobre el token guardado son
 * usar/reemplazar/borrar.
 */
class GitHubAuthPrefs(context: Context) {

    private val prefs: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "kairos_ide_git_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun getToken(): String? = prefs.getString(KEY_TOKEN, null)?.ifBlank { null }

    fun hasToken(): Boolean = !getToken().isNullOrBlank()

    fun setToken(token: String) {
        prefs.edit().putString(KEY_TOKEN, token).apply()
    }

    /** También borra el login (usuario GitHub) guardado junto al token — ver [setLogin]. */
    fun clearToken() {
        prefs.edit().remove(KEY_TOKEN).remove(KEY_LOGIN).apply()
    }

    /** Login de GitHub (ej. "Honkonx") resuelto una sola vez tras autenticar, solo para mostrar
     * "Conectado como <login>" en la UI — no es un secreto, pero vive en el mismo store cifrado
     * por simplicidad (un solo archivo de prefs para todo el estado de conexión). */
    fun getLogin(): String? = prefs.getString(KEY_LOGIN, null)?.ifBlank { null }

    fun setLogin(login: String) {
        prefs.edit().putString(KEY_LOGIN, login).apply()
    }

    companion object {
        private const val KEY_TOKEN = "access_token"
        private const val KEY_LOGIN = "login"
    }
}

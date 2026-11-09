package com.termux.app.util

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Almacenamiento cifrado de secretos de [AutomationManager.ActionType.GATEWAY_ACTION] (ej. la
 * contraseña de un repositorio restic para un `restic.backup` programado) — mismo patrón que
 * [SecureChatPrefs]/`com.termux.app.ui.studio.ai.AiProviderPrefs` (MasterKey +
 * EncryptedSharedPreferences), separado del resto de la automatización (que vive sin cifrar en
 * `.android_server_registry` junto a `RemoteManager`/`HomelabManager`): un secreto real NUNCA
 * se mezcla en texto plano con metadata no sensible (nombre, trigger, horario), mismo criterio
 * de `docs/arquitectura/AUDITORIA_IA_CODIGO_2026-08-19.md` hallazgo 2.2 que ya motivó separar
 * `SecureChatPrefs` de `kairos_llm_prefs`.
 *
 * Una clave por `automationId` — se guarda/lee/borra junto con el ciclo de vida de la propia
 * automatización (ver [AutomationManager.addAutomation]/[AutomationManager.deleteAutomation]).
 * Nunca se expone de vuelta a la UI una vez guardado (`.claude/rules/kairos-secrets-never-
 * revealed.md`) — [AutomationsFragment] solo puede reemplazarlo (campo vacío al reabrir) o
 * borrarlo, nunca leerlo.
 */
object AutomationSecrets {
    private const val PREFS_NAME = "kairos_automation_secrets"

    private fun prefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun save(context: Context, automationId: String, secret: String) {
        if (secret.isBlank()) return
        prefs(context).edit().putString(automationId, secret).apply()
    }

    /** Vacío si no hay nada guardado para esta automatización — nunca lanza. */
    fun get(context: Context, automationId: String): String =
        try { prefs(context).getString(automationId, "") ?: "" } catch (_: Exception) { "" }

    fun remove(context: Context, automationId: String) {
        try { prefs(context).edit().remove(automationId).apply() } catch (_: Exception) { }
    }

    fun has(context: Context, automationId: String): Boolean = get(context, automationId).isNotBlank()
}

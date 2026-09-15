package com.termux.app.ui.studio.git

/**
 * Client ID de la GitHub OAuth App propia de Kairos, usada para el flujo "Device Flow"
 * (ver [GitHubDeviceAuth]) que autentica el panel Git de Estudio contra GitHub.
 *
 * ⚠️ NO POBLADO: a diferencia del flujo de Antigravity (`com.termux.app.oauth.AntigravitySecrets`,
 * que impersona un cliente OAuth ajeno ya existente), este SÍ necesita una OAuth App propia y
 * real registrada en GitHub — no hay ningún client_id de terceros para reusar acá.
 *
 * Para completar esto:
 * 1. Ir a https://github.com/settings/developers → "New OAuth App".
 * 2. "Authorization callback URL" puede ser cualquier valor (ej. https://github.com/Honkonx/kairos-dev)
 *    — Device Flow no lo usa, GitHub lo exige igual para crear la App.
 * 3. Activar explícitamente "Enable Device Flow" en la configuración de la App (checkbox, no viene
 *    activado por default) — sin esto, `POST /login/device/code` devuelve error.
 * 4. Device Flow NO necesita client secret (es un cliente público, como PKCE) — solo pegar el
 *    "Client ID" acá abajo. Nunca commitear un client secret real en este archivo.
 */
internal object GitHubClientId {
    const val VALUE: String = "TODO_GITHUB_CLIENT_ID"

    /** true si todavía es el placeholder sin completar — usado para avisarle al usuario en vez
     * de dejar que el request falle silenciosamente contra la API real de GitHub. */
    fun isConfigured(): Boolean = VALUE.isNotBlank() && VALUE != "TODO_GITHUB_CLIENT_ID"
}

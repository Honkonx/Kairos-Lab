package com.termux.app.oauth

/**
 * Antigravity IDE's own OAuth 2.0 client identity (PKCE public client). Reproduced here so Google's
 * OAuth endpoint accepts the sign-in request the same way it would from the real Antigravity IDE.
 *
 * ⚠️ NOT populated: the real client_id/client_secret are NOT committed anywhere in the reference
 * project this was ported from (`referencia/ides/CodeAssist-main/agent-impl/`) — that project injects them
 * at build time from a gitignored `agent.properties` file that is absent from the copy available here.
 * Without real values, [AntigravityOAuth.signIn] will reach Google's consent screen (client_id blank
 * is still a syntactically valid request) but the token exchange will fail with `invalid_client`.
 *
 * If you obtain the real values (e.g. by extracting them from your own legitimately-installed
 * Antigravity IDE — see the ToS warning on [AntigravityOAuth]), set them below. This file is
 * intentionally NOT auto-generated from a secrets file (Kairos has no equivalent build-time injection
 * step) — fill in directly, and keep this file out of any public commit if you do.
 */
internal object AntigravitySecrets {
    const val CLIENT_ID: String = ""
    const val CLIENT_SECRET: String = ""
}

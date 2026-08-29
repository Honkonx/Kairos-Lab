package com.termux.app.oauth

import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import org.json.JSONObject

/**
 * The Antigravity "Sign in with Google" flow: an OAuth 2.0 authorization-code + PKCE exchange that
 * mints the refresh token stored as the module's credential. Reproduces the Antigravity IDE's own
 * (public, PKCE) OAuth client so Google issues a token scoped to the Code Assist backend.
 *
 * Ported from `referencia/ides/CodeAssist-main/agent-impl/.../AntigravityOAuth.kt` (GPL-3.0-or-later,
 * license-compatible — Kairos is GPLv3 too) — same logic, Android-appropriate networking
 * (`HttpURLConnection` instead of the original project's injected transport abstraction).
 *
 * Because Antigravity's client only registers a **loopback** redirect
 * (`http://localhost:36742/oauth-callback`), this runs a one-shot local TCP listener on that fixed
 * port, hands the consent URL to the caller (to open in the system browser), waits for the browser
 * to redirect back to the listener, and exchanges the returned code for tokens.
 *
 * ⚠️ EXPERIMENTAL, riesgo asumido a propósito (mismo patrón que el binario nativo no oficial de Codex
 * en modulos/codex.sh): esto impersona el cliente OAuth de la IDE Antigravity real — Google prohíbe
 * esto en sus Términos de Servicio y puede resultar en la suspensión de la cuenta usada. Además,
 * [AntigravitySecrets] no tiene credenciales reales cargadas por defecto (ver ese archivo) — sin
 * completarlas, el intercambio de token fallará con `invalid_client` aunque la pantalla de consentimiento
 * de Google sí se abra.
 *
 * Debe correr siempre en un hilo de fondo — bloquea en I/O de red (`ServerSocket.accept`,
 * `HttpURLConnection`), Android prohíbe eso en el hilo principal.
 */
class AntigravityOAuth {

    class OAuthException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /**
     * Runs the full flow and returns the OAuth **refresh token**. [onAuthUrl] is invoked once with
     * the Google consent URL as soon as the listener is up — the caller opens it (system browser).
     * Blocks (polling) until the browser redirects back, then exchanges the code. Must be called from
     * a background thread.
     */
    @Throws(OAuthException::class)
    fun signIn(onAuthUrl: (String) -> Unit): String {
        val verifier = pkceVerifier()
        val stateToken = randomToken()
        val server = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), CALLBACK_PORT))
                soTimeout = POLL_MS
            }
        } catch (e: Exception) {
            throw OAuthException(
                "No se pudo iniciar el listener de sign-in en el puerto $CALLBACK_PORT (¿ya está en uso? " +
                    "cerrá cualquier otra sesión de Antigravity/Gemini CLI e intentá de nuevo).", e
            )
        }
        try {
            onAuthUrl(buildAuthUrl(pkceChallenge(verifier), stateToken))
            val (code, returnedState) = awaitRedirect(server)
            if (returnedState != stateToken) {
                throw OAuthException("Sign-in cancelado: el estado OAuth no coincidió (posible interferencia).")
            }
            return exchangeCode(code, verifier)
                ?: throw OAuthException(
                    "Google no devolvió un refresh token. Quitá el acceso de terceros de CodeAssist en tu " +
                        "cuenta de Google e intentá de nuevo."
                )
        } finally {
            try { server.close() } catch (_: Exception) {}
        }
    }

    /** Blocks (polling, so a Thread.interrupt() breaks the loop) until the OAuth redirect arrives. */
    private fun awaitRedirect(server: ServerSocket): Pair<String, String> {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (true) {
            if (Thread.currentThread().isInterrupted) {
                throw OAuthException("Sign-in cancelado.")
            }
            if (System.currentTimeMillis() > deadline) {
                throw OAuthException("Se agotó el tiempo esperando el login en el navegador. Intentá de nuevo.")
            }
            val socket = try {
                server.accept()
            } catch (_: SocketTimeoutException) {
                continue
            }
            socket.use { s ->
                val requestLine = s.getInputStream().bufferedReader().readLine().orEmpty()
                val path = requestLine.split(' ').getOrNull(1).orEmpty()
                if (!path.startsWith("/oauth-callback")) {
                    respond(s, PAGE_WAIT)
                    return@use
                }
                val params = parseQuery(path.substringAfter('?', ""))
                params["error"]?.let { err ->
                    respond(s, PAGE_FAIL)
                    throw OAuthException("Google devolvió '$err'. El sign-in no se completó.")
                }
                val code = params["code"]
                if (code != null) {
                    respond(s, PAGE_DONE)
                    return code to params["state"].orEmpty()
                }
                respond(s, PAGE_WAIT)
            }
        }
    }

    /** Exchanges the authorization code for tokens; returns the refresh token, or null if none was issued. */
    private fun exchangeCode(code: String, verifier: String): String? {
        val body = postForm(
            TOKEN_URL,
            mapOf(
                "client_id" to AntigravitySecrets.CLIENT_ID,
                "client_secret" to AntigravitySecrets.CLIENT_SECRET,
                "code" to code,
                "grant_type" to "authorization_code",
                "redirect_uri" to REDIRECT_URI,
                "code_verifier" to verifier,
            ),
        )
        return runCatching { JSONObject(body).optString("refresh_token").takeIf { it.isNotBlank() } }.getOrNull()
    }

    private fun postForm(url: String, fields: Map<String, String>): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            val body = fields.entries.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            return stream?.bufferedReader()?.readText() ?: ""
        } finally {
            conn.disconnect()
        }
    }

    private fun buildAuthUrl(challenge: String, state: String): String {
        val params = linkedMapOf(
            "client_id" to AntigravitySecrets.CLIENT_ID,
            "response_type" to "code",
            "redirect_uri" to REDIRECT_URI,
            "scope" to SCOPES.joinToString(" "),
            "code_challenge" to challenge,
            "code_challenge_method" to "S256",
            "state" to state,
            "access_type" to "offline",
            "prompt" to "consent",
        )
        val query = params.entries.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }
        return "https://accounts.google.com/o/oauth2/v2/auth?$query"
    }

    private fun respond(socket: Socket, message: String) {
        val html = "<!doctype html><meta charset=\"utf-8\">" +
            "<body style=\"font-family:system-ui,sans-serif;text-align:center;padding:48px;color:#333\"><h2>$message</h2></body>"
        val bytes = html.toByteArray(Charsets.UTF_8)
        socket.getOutputStream().apply {
            write(
                ("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n" +
                    "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII)
            )
            write(bytes)
            flush()
        }
    }

    private fun parseQuery(query: String): Map<String, String> =
        query.split('&').filter { it.isNotEmpty() }.associate {
            val eq = it.indexOf('=')
            if (eq < 0) dec(it) to "" else dec(it.substring(0, eq)) to dec(it.substring(eq + 1))
        }

    private fun pkceVerifier(): String = base64Url(ByteArray(64).also { SecureRandom().nextBytes(it) })

    private fun pkceChallenge(verifier: String): String =
        base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    private fun randomToken(): String = base64Url(ByteArray(16).also { SecureRandom().nextBytes(it) })

    private fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
    private fun dec(s: String): String = URLDecoder.decode(s, "UTF-8")

    companion object {
        const val CALLBACK_PORT = 36742
        const val REDIRECT_URI = "http://localhost:36742/oauth-callback"
        const val TOKEN_URL = "https://oauth2.googleapis.com/token"

        /** Accept poll interval; also the responsiveness ceiling for interrupt-based cancellation. */
        const val POLL_MS = 500

        /** Give the user five minutes to complete the consent screen. */
        const val TIMEOUT_MS = 300_000L

        val SCOPES = listOf(
            "https://www.googleapis.com/auth/cloud-platform",
            "https://www.googleapis.com/auth/userinfo.email",
            "https://www.googleapis.com/auth/userinfo.profile",
            "https://www.googleapis.com/auth/cclog",
            "https://www.googleapis.com/auth/experimentsandconfigs",
        )

        const val PAGE_DONE = "Sesión iniciada. Podés cerrar esta pestaña y volver a Kairos."
        const val PAGE_WAIT = "Completando sign-in…"
        const val PAGE_FAIL = "El sign-in no se completó. Volvé a Kairos e intentá de nuevo."
    }
}

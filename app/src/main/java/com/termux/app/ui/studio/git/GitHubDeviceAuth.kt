package com.termux.app.ui.studio.git

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * GitHub OAuth **Device Flow** — el mismo mecanismo que usa `gh auth login`: la app pide un
 * `user_code` corto + una URL de verificación, el usuario los abre en CUALQUIER navegador/
 * dispositivo, autoriza, y esta clase hace polling hasta recibir el `access_token`. No necesita
 * callback URL propio ni servidor -- por eso es el flujo correcto para una app móvil/CLI, a
 * diferencia del Authorization Code + PKCE que usa `com.termux.app.oauth.AntigravityOAuth`
 * (ese sí necesita un listener local en un puerto fijo).
 *
 * Hallazgo de auditoría de `referencia/ides/nomacode-main` (`github-auth.js`) -- Device Flow es
 * el flujo real que ese proyecto implementa para conectar GitHub sin pedir usuario/contraseña ni
 * un token pegado a mano. Regla dura del proyecto: el token que
 * resulta de este flujo se guarda vía [GitHubAuthPrefs] y nunca se vuelve a mostrar.
 *
 * Todo el trabajo de red corre en background (Thread propio) y los callbacks se entregan
 * siempre en el main looper -- mismo criterio que `com.termux.app.ui.studio.termux.TermuxBridge`.
 */
object GitHubDeviceAuth {

    private val mainHandler = Handler(Looper.getMainLooper())

    class AuthException(message: String) : Exception(message)

    /** Respuesta de `POST /login/device/code` -- lo que hay que mostrarle al usuario. */
    data class DeviceCode(
        val deviceCode: String,
        val userCode: String,
        val verificationUri: String,
        val intervalSeconds: Int,
        val expiresInSeconds: Int
    )

    /** Paso 1: pide un `device_code`/`user_code` nuevo. [onResult] corre en el main looper. */
    fun requestDeviceCode(onResult: (Result<DeviceCode>) -> Unit) {
        if (!GitHubClientId.isConfigured()) {
            deliver(onResult, Result.failure(AuthException(
                "Falta configurar el Client ID de GitHub -- ver el comentario en GitHubClientId.kt " +
                    "(hay que crear una OAuth App propia en https://github.com/settings/developers " +
                    "con 'Enable Device Flow' activado)."
            )))
            return
        }
        Thread {
            val result = try {
                val body = "client_id=${urlEncode(GitHubClientId.VALUE)}&scope=${urlEncode("repo")}"
                val json = postForm(DEVICE_CODE_URL, body)
                if (json.has("error")) {
                    Result.failure(AuthException(githubErrorMessage(json)))
                } else {
                    Result.success(
                        DeviceCode(
                            deviceCode = json.getString("device_code"),
                            userCode = json.getString("user_code"),
                            verificationUri = json.optString("verification_uri", "https://github.com/login/device"),
                            intervalSeconds = json.optInt("interval", 5),
                            expiresInSeconds = json.optInt("expires_in", 900)
                        )
                    )
                }
            } catch (e: Exception) {
                Result.failure(AuthException(e.message ?: "No se pudo contactar a GitHub."))
            }
            deliver(onResult, result)
        }.start()
    }

    /**
     * Paso 2: hace polling a `POST /login/oauth/access_token` cada `device.intervalSeconds`
     * (ajustado en vivo si GitHub pide `slow_down`) hasta recibir el token, expirar, o que
     * [cancelled] se ponga en true (ej. el usuario cerró el diálogo). [onResult] corre en el
     * main looper, se llama una sola vez.
     */
    fun pollForToken(device: DeviceCode, cancelled: AtomicBoolean, onResult: (Result<String>) -> Unit) {
        Thread {
            var intervalMs = device.intervalSeconds.coerceAtLeast(1) * 1000L
            val deadline = System.currentTimeMillis() + device.expiresInSeconds * 1000L
            var result: Result<String>? = null

            while (result == null) {
                if (cancelled.get()) {
                    result = Result.failure(AuthException("Cancelado."))
                    break
                }
                if (System.currentTimeMillis() > deadline) {
                    result = Result.failure(AuthException("El código expiró. Probá de nuevo."))
                    break
                }

                try {
                    Thread.sleep(intervalMs)
                } catch (e: InterruptedException) {
                    result = Result.failure(AuthException("Cancelado."))
                    break
                }
                if (cancelled.get()) {
                    result = Result.failure(AuthException("Cancelado."))
                    break
                }

                try {
                    val body = "client_id=${urlEncode(GitHubClientId.VALUE)}" +
                        "&device_code=${urlEncode(device.deviceCode)}" +
                        "&grant_type=${urlEncode("urn:ietf:params:oauth:grant-type:device_code")}"
                    val json = postForm(TOKEN_URL, body)
                    when (val error = json.optString("error", "")) {
                        "" -> {
                            val token = json.optString("access_token", "")
                            result = if (token.isNotBlank()) {
                                Result.success(token)
                            } else {
                                Result.failure(AuthException("GitHub no devolvió un token válido."))
                            }
                        }
                        "authorization_pending" -> { /* seguir esperando -- normal, no es un error */ }
                        "slow_down" -> {
                            val extra = json.optInt("interval", 0)
                            intervalMs += (if (extra > 0) extra else 5) * 1000L
                        }
                        "expired_token" -> result = Result.failure(AuthException("El código expiró. Probá de nuevo."))
                        "access_denied" -> result = Result.failure(AuthException("Autorización rechazada en GitHub."))
                        else -> result = Result.failure(AuthException(githubErrorMessage(json, error)))
                    }
                } catch (e: Exception) {
                    // Error de red puntual durante el polling -- no abortar el flujo entero por un
                    // timeout aislado, seguir intentando hasta el deadline.
                }
            }

            // El loop de arriba solo termina (`while (result == null)`) asignando `result` antes
            // de cada `break` -- el compilador no puede probar ese invariante a través del loop,
            // así que el fallback de acá abajo es puramente defensivo, nunca debería dispararse.
            deliver(onResult, result ?: Result.failure(AuthException("Tiempo de espera agotado.")))
        }.start()
    }

    /** Resuelve el login (username) del token recién obtenido, vía `GET /user` -- solo para
     * mostrar "Conectado como <login>" en la UI (ver [GitHubAuthPrefs.setLogin]). Falla en
     * silencio (retorna null) si algo sale mal -- no es crítico para que el login del flujo haya
     * funcionado, el token ya es válido igual. */
    fun fetchLogin(token: String, onResult: (String?) -> Unit) {
        Thread {
            val login = try {
                val conn = (URL(USER_URL).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("Authorization", "Bearer $token")
                    setRequestProperty("Accept", "application/vnd.github+json")
                    connectTimeout = 10_000
                    readTimeout = 10_000
                }
                val text = readBody(conn)
                conn.disconnect()
                JSONObject(text).optString("login", "").ifBlank { null }
            } catch (e: Exception) {
                null
            }
            mainHandler.post { onResult(login) }
        }.start()
    }

    // ── HTTP helpers ────────────────────────────────────────────────────────────────────

    private fun postForm(url: String, body: String): JSONObject {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connectTimeout = 15_000
            readTimeout = 15_000
        }
        OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }
        val text = readBody(conn)
        conn.disconnect()
        return JSONObject(text)
    }

    private fun readBody(conn: HttpURLConnection): String {
        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        return BufferedReader(InputStreamReader(stream ?: return "{}", Charsets.UTF_8)).use { it.readText() }
    }

    private fun githubErrorMessage(json: JSONObject, fallbackCode: String = ""): String {
        val description = json.optString("error_description", "")
        val code = json.optString("error", fallbackCode)
        return if (description.isNotBlank()) description else "GitHub devolvió el error '$code'."
    }

    private fun urlEncode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8")

    private fun <T> deliver(onResult: (Result<T>) -> Unit, result: Result<T>) {
        mainHandler.post { onResult(result) }
    }

    private const val DEVICE_CODE_URL = "https://github.com/login/device/code"
    private const val TOKEN_URL = "https://github.com/login/oauth/access_token"
    private const val USER_URL = "https://api.github.com/user"
}

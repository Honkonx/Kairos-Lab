package com.termux.app.util

import org.json.JSONObject
import java.io.File

/**
 * Complemento "Tor" de Ciberseguridad (orden explícita del usuario 2026-10-08, ver
 * mensaje 6: "el módulo tor pasa a complemento para el módulo ciberseguridad
 * [...] se mete como opción y pestaña"). Antes era el módulo `tor` (modulos/tor.sh, retirado del
 * catálogo); ahora es un complemento sobre el motor común [AddonEngine], sin script bash ni entrada
 * en modules.json.
 *
 * Qué agrega sobre el motor: deja `SocksPort 9050` explícito en el torrc y puede comprobar que el
 * tráfico realmente sale por Tor.
 * Qué NO hace: no enruta por Tor las herramientas de Ciberseguridad por su cuenta — solo expone
 * el proxy SOCKS local; usarlo es decisión de cada herramienta.
 */
object TorManager {

    const val SOCKS_PORT = 9050
    const val SOCKS_URL = "socks5h://127.0.0.1:$SOCKS_PORT"

    private const val TOR_BIN = "$TERMUX_PREFIX_PATH/bin/tor"
    private const val TORRC = "$TERMUX_PREFIX_PATH/etc/tor/torrc"
    private const val CURL_BIN = "$TERMUX_PREFIX_PATH/bin/curl"
    private const val CHECK_URL = "https://check.torproject.org/api/ip"

    private val SPEC = AddonEngine.Spec(
        id = "tor",
        pkg = "tor",
        bin = TOR_BIN,
        service = AddonEngine.Service(
            session = "tor-server",
            port = SOCKS_PORT,
            command = listOf(TOR_BIN, "-f", TORRC),
            logName = "tor_serve.log"
        )
    )

    fun status(): AddonEngine.Status = AddonEngine.status(SPEC)

    fun isInstalled(): Boolean = AddonEngine.isInstalled(SPEC)

    fun isRunning(): Boolean = AddonEngine.isRunning(SPEC)

    fun install(): AddonEngine.Result {
        val result = AddonEngine.install(SPEC)
        if (result.ok) ensureSocksPort()
        return result
    }

    /**
     * El binario abre 9050 por defecto aunque el torrc no lo diga (comportamiento documentado en
     * el torrc.sample de Tor upstream, ver el antiguo modulos/tor.sh), pero se deja explícito
     * para no depender de un default implícito. Devuelve true si el torrc ya lo tenía o se lo
     * agregó; false si no hay torrc que parchear.
     */
    fun ensureSocksPort(): Boolean {
        val file = File(TORRC)
        if (!file.isFile) return false
        return try {
            if (torrcHasSocksPort(file.readText())) return true
            file.appendText("\n# Agregado por Kairos (TorManager) — SocksPort explícito\nSocksPort $SOCKS_PORT\n")
            true
        } catch (_: Exception) {
            false
        }
    }

    fun start(): AddonEngine.Result {
        ensureSocksPort()
        return AddonEngine.start(SPEC)
    }

    fun stop(): AddonEngine.Result = AddonEngine.stop(SPEC)

    /** Pide a check.torproject.org por el proxy SOCKS con resolución remota de DNS. */
    fun testConnection(): AddonEngine.Result {
        if (!isRunning()) return AddonEngine.Result(false, "Tor no está corriendo")
        if (!File(CURL_BIN).canExecute()) return AddonEngine.Result(false, "curl no está disponible en Termux")
        val (rc, out, err) = ManagerNativeUtils.runExec(
            listOf(CURL_BIN, "-s", "--max-time", "30", "--socks5-hostname", "127.0.0.1:$SOCKS_PORT", CHECK_URL), 40
        )
        if (rc != 0) {
            return AddonEngine.Result(
                false,
                "curl falló (código $rc). Tor puede estar conectándose todavía (hasta ~1 min). ${AddonEngine.lastLines(err)}"
            )
        }
        val parsed = parseCheckResponse(out)
            ?: return AddonEngine.Result(false, "Respuesta no reconocida: ${out.take(120)}")
        return AddonEngine.Result(parsed.first, parsed.second)
    }

    /** `{"IsTor":true,"IP":"x.x.x.x"}` → (esTor, ip). null si no es JSON con esos campos. */
    internal fun parseCheckResponse(body: String): Pair<Boolean, String>? = try {
        val json = JSONObject(body)
        if (!json.has("IsTor")) null else Pair(json.getBoolean("IsTor"), json.optString("IP", "?"))
    } catch (_: Exception) {
        null
    }

    /** true si hay una línea `SocksPort` activa (no comentada) en el texto del torrc. */
    internal fun torrcHasSocksPort(text: String): Boolean =
        text.lineSequence().any { it.trim().startsWith("SocksPort") }
}

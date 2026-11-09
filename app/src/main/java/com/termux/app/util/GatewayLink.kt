package com.termux.app.util

/**
 * Dirección con la que se abre o se comparte el gateway (Homelab). Prioridad: dirección manual (IP o dominio que escribió
 * la persona) → túnel activo → red local (IP del teléfono) → este teléfono. El enlace nunca lleva token: la página del
 * gateway lo pide aparte.
 */
object GatewayLink {
    /** Convierte lo que escribió la persona en una URL: añade `http://` y el puerto si falta (un `https://` se respeta tal cual). */
    fun normalizeCustom(input: String, port: Int): String {
        val text = input.trim().trimEnd('/')
        if (text.isEmpty()) return ""
        if (text.startsWith("https://")) return text
        val withScheme = if (text.startsWith("http://")) text else "http://$text"
        val hostPart = withScheme.removePrefix("http://")
        return if (hostPart.contains(':')) withScheme else "$withScheme:$port"
    }

    // "https" para el caso LAN (hallazgo real de seguridad: "TLS en el listener LAN -- el token
    // viaja en texto plano") -- el gateway nativo (native/gateway/cmd/kairos-gateway/main.go)
    // ahora sirve HTTPS con un certificado autofirmado automáticamente en cuanto bindea fuera de
    // loopback (ver server.EnsureSelfSignedCert), que es exactamente lo que pasa en modo LAN.
    // 127.0.0.1 (el teléfono hablando con su propio proceso) sigue en http, sin cambios -- ahí
    // nunca hubo una red de por medio que TLS protegiera.
    fun resolve(custom: String, tunnelUrl: String, lan: Boolean, lanIp: String, port: Int): String = when {
        custom.isNotBlank() -> custom
        tunnelUrl.isNotBlank() -> tunnelUrl.trimEnd('/')
        lan && lanIp.isNotBlank() && lanIp != "localhost" -> "https://$lanIp:$port"
        else -> "http://127.0.0.1:$port"
    }
}

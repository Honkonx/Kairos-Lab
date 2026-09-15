package com.termux.app.util

import java.net.InetAddress
import java.net.URI
import java.net.UnknownHostException

/**
 * Protección SSRF real (hallazgo de referencia, ver
 * docs/referencias/agentes/REFERENCIA_RIKKAHUB_AGENT.md sección 8, "search/.../net/
 * SearchEgressGuard.kt" del proyecto original) — bloquea que Kairos siga/scrapee una URL cuyo
 * host apunta a un rango de red privado/local, sea por IP literal en la propia URL o porque el
 * hostname resuelve ahí vía DNS (DNS rebinding).
 *
 * Sin este guard, un futuro tool de "abrir/leer resultado de búsqueda" (o cualquier otro tool
 * que reciba una URL de una fuente no confiable — un resultado de búsqueda, una respuesta del
 * modelo) podría pedirle a la app que haga de proxy hacia `http://169.254.169.254/` (metadata
 * endpoint típico de nube, no aplica directo a un teléfono pero es el ejemplo canónico de SSRF)
 * o hacia una IP de la LAN del propio usuario (router, cámara IP, NAS sin autenticación).
 *
 * Uso HOY: [WebSearchService] filtra con esto las URLs de resultados de búsqueda antes de
 * mostrarlas (defensa en profundidad, aunque hoy el chat no las sigue automáticamente). Uso
 * FUTURO: cualquier tool nueva que reciba una URL arbitraria (del LLM o de un resultado externo)
 * y vaya a hacer un HTTP request con ella debe llamar [isUrlSafeToFetch] antes de conectar.
 */
object SearchEgressGuard {

    /** Rangos IPv4 privados/locales bloqueados — RFC 1918 (LAN) + loopback + link-local. */
    private data class Cidr(val network: Long, val maskBits: Int) {
        fun contains(address: Long): Boolean {
            if (maskBits == 0) return true
            val mask = -1L shl (32 - maskBits)
            return (address and mask) == (network and mask)
        }
    }

    private val BLOCKED_RANGES = listOf(
        cidr("10.0.0.0", 8),
        cidr("172.16.0.0", 12),
        cidr("192.168.0.0", 16),
        cidr("127.0.0.0", 8),
        cidr("169.254.0.0", 16)
    )

    private fun cidr(baseIp: String, maskBits: Int): Cidr = Cidr(ipToLong(baseIp), maskBits)

    private fun ipToLong(ip: String): Long {
        val parts = ip.split(".").map { it.toLong() }
        return (parts[0] shl 24) or (parts[1] shl 16) or (parts[2] shl 8) or parts[3]
    }

    private fun inetAddressToLong(addr: InetAddress): Long? {
        val bytes = addr.address
        if (bytes.size != 4) return null // solo IPv4 — direcciones IPv6 se rechazan por defecto (ver isUrlSafeToFetch)
        var value = 0L
        for (b in bytes) value = (value shl 8) or (b.toLong() and 0xFF)
        return value
    }

    private fun isBlockedAddress(addr: InetAddress): Boolean {
        if (addr.isLoopbackAddress || addr.isLinkLocalAddress || addr.isSiteLocalAddress || addr.isAnyLocalAddress) return true
        val asLong = inetAddressToLong(addr) ?: return false
        return BLOCKED_RANGES.any { it.contains(asLong) }
    }

    /**
     * true si `urlString` es segura para que Kairos la siga con un HTTP request propio (ni el
     * host literal ni ninguna IP a la que resuelva cae en un rango privado/local). Falla cerrado
     * (false) ante cualquier URL malformada, esquema no-http(s), o error de resolución DNS —
     * nunca se asume "segura" por defecto.
     */
    fun isUrlSafeToFetch(urlString: String): Boolean {
        val uri = try {
            URI(urlString.trim())
        } catch (e: Exception) {
            return false
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return false
        val host = uri.host?.trim() ?: return false
        if (host.isEmpty() || host.equals("localhost", ignoreCase = true)) return false

        return try {
            // getAllByName() resuelve TODAS las IPs del hostname (no solo la primera) — un
            // hostname que resuelve a varias IPs es inseguro si CUALQUIERA de ellas es privada.
            InetAddress.getAllByName(host).none { isBlockedAddress(it) }
        } catch (e: UnknownHostException) {
            false
        } catch (e: Exception) {
            false
        }
    }
}

package com.termux.app.util

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Paridad de módulo real para el gateway (2026-10-16, ver meta 4-5) — mismo
 * `nmap -sn <subred>/24` real que `CiberseguridadFragment.runLanScan()` (descubre hosts vivos en
 * la red local, ping-scan sin puertos). Solo corre cuando se llama explícitamente (igual que el
 * botón "Escanear" de la app: nunca un escaneo de red sin que alguien lo pida a propósito).
 */
interface GatewayLanScanOps {
    fun scan(): JSONObject
}

class AppLanScanOps(private val context: Context) : GatewayLanScanOps {
    override fun scan(): JSONObject {
        val (checkCode, _) = ShellCommandRunner.run(listOf("sh", "-c", "command -v nmap"), 5)
        if (checkCode != 0) return JSONObject().put("ok", false).put("error", "nmap no está instalado")

        val ip = try { RemoteManager.getLocalIp(context) } catch (_: Exception) { "" }
        val octets = ip.split(".")
        if (octets.size != 4) return JSONObject().put("ok", false).put("error", "IP local inválida: $ip")
        val subnet = "${octets[0]}.${octets[1]}.${octets[2]}.0/24"

        val (exitCode, stdout) = ShellCommandRunner.run(listOf("sh", "-c", "nmap -sn $subnet"), 25)
        if (exitCode != 0) return JSONObject().put("ok", false).put("error", stdout.takeLast(500).ifBlank { "nmap falló" })

        val hostRegex = Regex("""Nmap scan report for (\S+)(?:\s+\(([\d.]+)\))?""")
        val hosts = hostRegex.findAll(stdout).map { match ->
            val name = match.groupValues[1]
            val addr = match.groupValues[2]
            if (addr.isNotBlank() && addr != name) "$name ($addr)" else name
        }.toList()
        return JSONObject().put("ok", true).put("subnet", subnet).put("hosts", JSONArray(hosts))
    }
}

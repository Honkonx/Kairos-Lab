package com.termux.app.util

import android.content.Context
import org.json.JSONObject

/**
 * Paridad de módulo real para el gateway (2026-10-16, ver meta 4-5) — delega
 * TODO en [TunnelManager] (lo mismo que ya usa `TunnelFragment`/`HomelabFragment` para el túnel
 * del propio gateway), sin reimplementar nada. [port]/[provider]/[token]/[domain] tienen
 * exactamente el mismo significado que en `TunnelManager.start()` — "provider" solo acepta
 * "cloudflared"/"ngrok" (lo valida TunnelManager, no acá).
 */
interface GatewayTunnelOps {
    fun status(port: Int): JSONObject
    fun start(port: Int, provider: String, token: String?, domain: String?): JSONObject
    fun stop(port: Int): JSONObject
}

class AppTunnelOps(private val context: Context) : GatewayTunnelOps {
    override fun status(port: Int): JSONObject {
        val s = TunnelManager.status(port)
        return JSONObject().put("ok", true).put("running", s.running).put("url", s.url).put("own_tunnel", s.ownTunnel)
    }

    override fun start(port: Int, provider: String, token: String?, domain: String?): JSONObject {
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        val r = TunnelManager.start(port, provider, token, domain, nativeLibDir = nativeLibDir)
        return JSONObject().put("ok", r.ok).apply { if (r.ok) put("message", r.message) else put("error", r.error) }
    }

    override fun stop(port: Int): JSONObject {
        val r = TunnelManager.stop(port)
        return JSONObject().put("ok", r.ok).apply { if (r.ok) put("message", r.message) else put("error", r.error) }
    }
}

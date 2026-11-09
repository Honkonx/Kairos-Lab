package com.termux.app.util

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Paridad de módulo real para el gateway (2026-10-16, ver meta 4-5 y meta 7:
 * "ver y controlar el móvil por completo... por red, ip, túnel o usb") — delega TODO en
 * [RemoteManager] (SSH + túnel Cloudflare, lo mismo que ya usa `RemoteFragment`). Solo lectura +
 * encender/apagar el túnel esta ronda — escribir claves/contraseñas nuevas
 * (`sshAddKey`/`sshSetPassword`/`cfSetToken`) queda deliberadamente afuera: son acciones que
 * GUARDAN un secreto nuevo (no leen uno existente, así que no violan
 * `kairos-secrets-never-revealed.md` por sí solas), pero merecen su propia ronda con más cuidado
 * en vez de agregarse de apuro al final de un barrido de módulos.
 */
interface GatewayRemoteOps {
    fun info(): JSONObject
    fun sshConnections(): JSONObject
    fun sshSecurity(): JSONObject
    fun cfInfo(): JSONObject
    fun cfStart(): JSONObject
    fun cfStop(): JSONObject
}

class AppRemoteOps(private val context: Context) : GatewayRemoteOps {
    override fun info(): JSONObject {
        val i = RemoteManager.info(context)
        return JSONObject()
            .put("ok", true).put("ssh_running", i.sshRunning).put("ssh_port", i.sshPort)
            .put("cf_running", i.cfRunning).put("cf_has_token", i.cfHasToken)
            .put("ip", i.ip).put("user", i.user).put("connections", i.connections)
    }

    override fun sshConnections(): JSONObject {
        val c = RemoteManager.sshConnections()
        return JSONObject().put("ok", true).put("count", c.count).put("daemon_running", c.daemonRunning).put("connections", JSONArray(c.connections))
    }

    override fun sshSecurity(): JSONObject {
        val s = RemoteManager.sshSecurityConfig()
        return JSONObject().put("ok", true).put("port", s.port).put("password_auth_enabled", s.passwordAuthEnabled)
            .put("permit_root_login", s.permitRootLogin).put("has_authorized_keys", s.hasAuthorizedKeys)
            .put("has_own_keypair", s.hasOwnKeypair).put("own_public_key", s.ownPublicKey ?: JSONObject.NULL)
            .put("sshd_running", s.sshdRunning)
    }

    override fun cfInfo(): JSONObject {
        val c = RemoteManager.cfInfo()
        return JSONObject().put("ok", true).put("running", c.running).put("has_token", c.hasToken).put("user", c.user)
    }

    override fun cfStart(): JSONObject {
        val r = RemoteManager.cfStart()
        return JSONObject().put("ok", r.ok).apply { if (r.ok) put("message", r.message) else put("error", r.error) }
    }

    override fun cfStop(): JSONObject {
        val r = RemoteManager.cfStop()
        return JSONObject().put("ok", r.ok).apply { if (r.ok) put("message", r.message) else put("error", r.error) }
    }
}

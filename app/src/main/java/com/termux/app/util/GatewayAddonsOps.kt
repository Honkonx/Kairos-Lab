package com.termux.app.util

import org.json.JSONObject

/**
 * Paridad real para el gateway de los COMPLEMENTOS (2026-10-16, ver meta 4-5 —
 * esto además cierra el gap que el propio panel documentaba como "sin ninguna acción todavía" en
 * la vista Plugins, ver `native/gateway/internal/server/panel/index.html` § v-plugins). Tor y
 * Syncthing son daemons con `start`/`stop` reales vía [AddonEngine]/[TorManager] — Restic NO tiene
 * un daemon (su `AddonEngine.Spec` no trae `service`, son operaciones puntuales de backup/restore
 * que además reciben una contraseña del usuario como entrada) y queda deliberadamente afuera de
 * esta ronda, mismo criterio que las acciones de escribir credenciales de Remote.
 */
interface GatewayAddonsOps {
    fun torStatus(): JSONObject
    fun torStart(): JSONObject
    fun torStop(): JSONObject
    fun syncthingStatus(): JSONObject
    fun syncthingStart(): JSONObject
    fun syncthingStop(): JSONObject
}

class AppAddonsOps : GatewayAddonsOps {
    private fun statusJson(s: AddonEngine.Status) =
        JSONObject().put("ok", true).put("installed", s.installed).put("running", s.running).put("version", s.version ?: JSONObject.NULL)

    private fun resultJson(r: AddonEngine.Result) =
        JSONObject().put("ok", r.ok).apply { if (!r.ok) put("error", r.message) else put("message", r.message) }

    override fun torStatus(): JSONObject = statusJson(TorManager.status())
    override fun torStart(): JSONObject = resultJson(TorManager.start())
    override fun torStop(): JSONObject = resultJson(TorManager.stop())

    override fun syncthingStatus(): JSONObject = statusJson(AddonEngine.status(HomelabAddons.SYNCTHING))
    override fun syncthingStart(): JSONObject = resultJson(AddonEngine.start(HomelabAddons.SYNCTHING))
    override fun syncthingStop(): JSONObject = resultJson(AddonEngine.stop(HomelabAddons.SYNCTHING))
}

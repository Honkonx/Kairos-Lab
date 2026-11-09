package com.termux.app.util

import org.json.JSONObject

/**
 * Paridad de módulo real para el gateway (2026-10-16,) — Restic quedó
 * deliberadamente afuera de la ronda del complemento Tor/Syncthing porque recibe una contraseña
 * como ENTRADA (no la guarda ni la devuelve — viaja directo a `RESTIC_PASSWORD`, nunca al argv ni
 * a ningún log, ver [HomelabAddons.runRestic]) — se implementa ahora, con el mismo cuidado: la
 * contraseña nunca aparece en la respuesta JSON de vuelta, solo el resultado de la operación.
 * `backup`/`snapshots` son las dos únicas operaciones reales que [HomelabAddons] ya tiene —
 * restaurar no existe todavía ahí, así que no se expone (no se inventa una capacidad que la app
 * no tiene).
 */
interface GatewayResticOps {
    fun backup(repo: String, folder: String, password: String): JSONObject
    fun snapshots(repo: String, password: String): JSONObject
}

class AppResticOps : GatewayResticOps {
    private fun toJson(r: AddonEngine.Result) =
        JSONObject().put("ok", r.ok).apply { if (r.ok) put("message", r.message) else put("error", r.message) }

    override fun backup(repo: String, folder: String, password: String): JSONObject = toJson(HomelabAddons.resticBackup(repo, folder, password))
    override fun snapshots(repo: String, password: String): JSONObject = toJson(HomelabAddons.resticSnapshots(repo, password))
}

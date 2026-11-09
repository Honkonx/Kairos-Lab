package com.termux.app.util

import org.json.JSONObject

/**
 * Paridad de módulo real para el gateway (2026-10-16, ver meta 4-5: "OpenClaw
 * tiene su propio gateway" — segundo módulo nombrado explícitamente por el usuario, después de
 * Ollama). Delega en [OpenClawNative.gatewayUrl] (lo mismo que ya usa `OpenClawFragment`) — pero
 * a diferencia del Fragment (misma app, mismo proceso), esta acción viaja por HTTP del gateway,
 * así que el token NUNCA sale completo: se enmascara EXACTO igual que `OpenClawFragment.loadInfo()`
 * (6 primeros + 4 últimos caracteres) antes de serializar, y la URL (que trae el token embebido en
 * `#token=...`) no se devuelve tal cual — ver `.claude/rules/kairos-secrets-never-revealed.md`.
 */
interface GatewayOpenClawOps {
    fun status(): JSONObject
}

class AppOpenClawOps : GatewayOpenClawOps {
    override fun status(): JSONObject {
        val real = OpenClawNative.gatewayUrl()
        val token = real.optString("token", "")
        // PORT es privado en OpenClawNative — se extrae del propio "url" real ("http://localhost:<puerto>/...")
        // en vez de duplicar el número a mano, que quedaría desincronizado si cambia ahí.
        val port = Regex("""localhost:(\d+)""").find(real.optString("url", ""))?.groupValues?.get(1)?.toIntOrNull()
        return JSONObject().apply {
            put("ok", true)
            put("running", real.optBoolean("running", false))
            put("port", port ?: JSONObject.NULL)
            if (token.isNotEmpty()) {
                put("token_masked", "${token.take(6)}…${token.takeLast(4)}")
            } else {
                put("token_masked", JSONObject.NULL)
            }
        }
    }
}

package com.termux.app.util

import org.json.JSONObject

/**
 * Paridad de módulo real para el gateway (2026-10-16, ver meta 4-5 — "algunos
 * [módulos] comparte casi iguales [opciones]": Hermes tiene su propio gateway igual que
 * OpenClaw). Delega en [HermesNative] ya existente — `info()` es seguro de exponer tal cual (no
 * trae secretos); `configGet()` de HermesNative SÍ trae la API key cruda del proveedor en texto
 * plano (`.env`) y por eso NO se expone acá — ver `.claude/rules/kairos-secrets-never-revealed.md`,
 * mismo criterio que ya aplicó `AppOpenClawOps` con el token de OpenClaw.
 */
interface GatewayHermesOps {
    fun info(): JSONObject
    fun gatewayStart(): JSONObject
    fun gatewayStop(): JSONObject
}

class AppHermesOps : GatewayHermesOps {
    override fun info(): JSONObject = HermesNative.info()
    override fun gatewayStart(): JSONObject = HermesNative.gatewayStart()
    override fun gatewayStop(): JSONObject = HermesNative.gatewayStop()
}

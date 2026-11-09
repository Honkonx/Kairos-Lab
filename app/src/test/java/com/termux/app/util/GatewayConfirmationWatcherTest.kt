package com.termux.app.util

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayConfirmationWatcherTest {
    @Test
    fun neverHidesTheProviderBehindTheCommand() {
        // Hallazgo de seguridad real: antes solo mostraba "cmd", sin decir qué proveedor
        // (app/root/shizuku/adb) iba a ejecutarlo — alguien podía aprobar sin ver que corría como root.
        assertEquals("provider: root · cmd: rm -rf /", describeGatewayPendingParams(JSONObject().put("provider", "root").put("cmd", "rm -rf /")))
    }

    @Test
    fun showsAllParamsInPriorityOrderNeverDroppingOne() {
        assertEquals("name: claude · text: hola", describeGatewayPendingParams(JSONObject().put("name", "claude").put("text", "hola")))
        assertEquals("path: a.txt · content: x", describeGatewayPendingParams(JSONObject().put("path", "a.txt").put("content", "x")))
    }

    @Test
    fun includesUnknownKeysToo() {
        assertEquals("foo: 1", describeGatewayPendingParams(JSONObject().put("foo", "1")))
    }

    @Test
    fun aSingleTruncatedFieldAlwaysShowsTheCutMark() {
        // Hallazgo de seguridad real: un campo recortado a FIELD_MAX sin marca de corte parecía
        // completo aunque no lo era — alguien podía aprobar un comando creyendo que lo vio entero.
        val result = describeGatewayPendingParams(JSONObject().put("cmd", "x".repeat(5000)))
        assertTrue("un campo recortado debe terminar en la marca de corte", result.endsWith("…"))
        assertTrue("el campo recortado no debe parecer completo", result.length < 5000)
    }

    @Test
    fun theOverallResultIsAlsoTruncatedWithAVisibleMark() {
        val many = JSONObject()
        for (i in 0 until 10) many.put("k$i", "y".repeat(300))
        val result = describeGatewayPendingParams(many)
        assertTrue("el total con varios campos largos se recorta", result.length <= 601)
        assertTrue("termina con marca de corte", result.endsWith("…"))
    }
}

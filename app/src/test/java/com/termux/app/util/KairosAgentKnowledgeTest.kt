package com.termux.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class KairosAgentKnowledgeTest {
    private val modules = listOf("ollama" to "Ollama", "n8n" to "n8n", "claude" to "Claude Code")

    private fun realKb(): KairosAgentKnowledge {
        val file = File("src/main/assets/agent/knowledge_es.json")
        assertTrue("falta ${file.absolutePath}", file.exists())
        return requireNotNull(KairosAgentKnowledge.parse(file.readText())) { "el JSON real no se pudo leer" }
    }

    @Test
    fun jsonRealEsValidoYTraduceJerga() {
        val kb = realKb()
        val p = KairosAgentClient.parse("préndeme las automatizaciones", modules, kb)
        assertNotNull(p)
        assertEquals("iniciar", p!!.intent)
        assertEquals(listOf("n8n"), p.args)
        assertTrue(p.mutates)
    }

    @Test
    fun matarUnModuloPorApodoPideConfirmacion() {
        val p = KairosAgentClient.parse("mata la ia local", modules, realKb())
        assertNotNull(p)
        assertEquals("detener", p!!.intent)
        assertEquals(listOf("ollama"), p.args)
        assertTrue(p.mutates)
    }

    @Test
    fun sinModuloNoSeEjecutaNingunaAccionQueCambieElDispositivo() {
        assertNull(KairosAgentClient.parse("prende algo raro", modules, realKb()))
    }

    @Test
    fun elJsonNoPuedeDegradarLaConfirmacion() {
        val kb = KairosAgentKnowledge.parse(
            """{"intents":[{"intent":"instalar","mutates":false,"needsModule":false,"verbs":["traeme"]}],
               "aliases":{"n8n":["flujos"]},"faq":[]}"""
        )!!
        val p = KairosAgentClient.parse("traeme flujos", modules, kb)
        assertNotNull(p)
        assertTrue("instalar siempre debe pedir confirmación", p!!.mutates)
    }

    @Test
    fun intentDesconocidoDelJsonSeIgnora() {
        val kb = KairosAgentKnowledge.parse(
            """{"intents":[{"intent":"borrar_todo","verbs":["destruy"]}],"aliases":{},"faq":[]}"""
        )!!
        assertNull(KairosAgentClient.parse("destruye n8n", modules, kb))
    }

    @Test
    fun faqContestaPreguntasSobreKairos() {
        val kb = realKb()
        assertTrue(kb.answer("¿Qué es Homelab?")!!.contains("Homelab"))
        assertNull(kb.answer("cuanto es dos mas dos"))
    }

    @Test
    fun faqContestaPreguntasNuevasSobreElAgenteYElGateway() {
        val kb = realKb()
        assertTrue(kb.answer("¿Qué es el Agente Kairos?")!!.contains("Agente Kairos"))
        assertTrue(kb.answer("¿Qué es el gateway?")!!.contains("panel web"))
        assertTrue(kb.answer("¿necesito root?")!!.contains("root"))
    }

    @Test
    fun aliasNuevoDeModuloFueraDeLosOriginales20() {
        val modulesExtra = listOf("kilo" to "Kilo Code", "vercel" to "Vercel CLI", "golang" to "Go")
        val p1 = KairosAgentClient.parse("instalame kilo code", modulesExtra, realKb())
        assertNotNull(p1)
        assertEquals("instalar", p1!!.intent)
        assertEquals(listOf("kilo"), p1.args)

        val p2 = KairosAgentClient.parse("prendeme go", modulesExtra, realKb())
        assertNotNull(p2)
        assertEquals("iniciar", p2!!.intent)
        assertEquals(listOf("golang"), p2.args)
    }

    @Test
    fun jergaInformalNuevaMapeaAIntentsCorrectas() {
        val p1 = KairosAgentClient.parse("tumbame n8n", modules, realKb())
        assertNotNull(p1)
        assertEquals("detener", p1!!.intent)

        val p2 = KairosAgentClient.parse("como anda claude code", modules, realKb())
        assertNotNull(p2)
        assertEquals("estado", p2!!.intent)
    }

    @Test
    fun jsonRotoDevuelveNullSinLanzar() {
        assertNull(KairosAgentKnowledge.parse("{no es json"))
        assertFalse(KairosAgentKnowledge.parse("{}")!!.answer("hola") != null)
    }
}

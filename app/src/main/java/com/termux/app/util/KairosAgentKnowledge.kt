package com.termux.app.util

import android.content.Context
import org.json.JSONObject
import java.text.Normalizer

/**
 * Base de conocimiento del Agente Kairos (`assets/agent/knowledge_es.json`), en "jerga humana": cómo
 * habla la gente cuando pide cosas ("préndeme", "mátalo", "levanta el flujo") y respuestas fijas sobre
 * Kairos. Es un dato auxiliar: si el archivo falta o está roto el agente sigue funcionando con su
 * análisis básico ([KairosAgentClient.parse]) — nunca es la única fuente de verdad (regla del
 * proyecto: `modules.json`/JSON de apoyo no reemplazan al código).
 *
 * Formato (version 1):
 *  - `intents[]`: `{ "intent", "mutates", "needsModule", "verbs": [raíces] }` — una raíz empata con el
 *    inicio de una palabra del pedido ("prend" ⇒ "préndeme", "prende").
 *  - `aliases{ idDeModulo: [apodos] }` — nombres coloquiales de un módulo.
 *  - `faq[]`: `{ "keys": [frases], "answer" }` — se contestan sin ejecutar nada.
 */
class KairosAgentKnowledge private constructor(
    private val intents: List<IntentRule>,
    private val aliases: Map<String, List<String>>,
    private val faq: List<Faq>,
) {
    data class IntentRule(val intent: String, val mutates: Boolean, val needsModule: Boolean, val verbs: List<String>)
    data class Faq(val keys: List<String>, val answer: String)

    /**
     * Intent cuyo verbo aparece en el pedido; null si ninguno. Un verbo de una sola palabra ("prend")
     * empata con el INICIO de alguna palabra ("préndeme"); un verbo de varias palabras ("pon en
     * marcha") empata como substring del texto normalizado completo — antes de este fix esos verbos
     * de varias palabras nunca podían matchear (words.any{it.startsWith(v)} compara contra tokens
     * sueltos, que nunca contienen un espacio), así que quedaban silenciosamente muertos.
     */
    fun intentFor(normalizedText: String, words: List<String>): IntentRule? =
        intents.firstOrNull { rule ->
            rule.verbs.any { v -> if (v.contains(' ')) normalizedText.contains(v) else words.any { it.startsWith(v) } }
        }

    /** Id del módulo cuyo apodo aparece en el pedido ya normalizado; gana el apodo más largo. */
    fun moduleByAlias(normalizedText: String, words: List<String>): String? =
        aliases.flatMap { (id, names) -> names.map { id to it } }
            .filter { (_, name) -> name.contains(' ') && normalizedText.contains(name) || name in words }
            .maxByOrNull { it.second.length }?.first

    /** Respuesta fija para una pregunta sobre Kairos; gana la entrada con más frases coincidentes. */
    fun answer(text: String): String? =
        faq.map { f -> f to f.keys.count { normalize(text).contains(it) } }
            .filter { it.second > 0 }
            .maxByOrNull { it.second }?.first?.answer

    companion object {
        private const val ASSET = "agent/knowledge_es.json"
        @Volatile private var cached: KairosAgentKnowledge? = null

        fun normalize(s: String): String =
            Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").trim()

        /** Lee el asset una sola vez; null si no existe o no es válido. */
        fun load(context: Context): KairosAgentKnowledge? {
            cached?.let { return it }
            val text = try { context.assets.open(ASSET).bufferedReader().use { it.readText() } } catch (_: Exception) { return null }
            return parse(text)?.also { cached = it }
        }

        fun parse(json: String): KairosAgentKnowledge? = try {
            val root = JSONObject(json)
            val intents = root.optJSONArray("intents")?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.mapNotNull { o ->
                    val name = o.optString("intent").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val verbs = o.optJSONArray("verbs")?.let { v -> (0 until v.length()).map { normalize(v.optString(it)) } }.orEmpty()
                    IntentRule(name, o.optBoolean("mutates"), o.optBoolean("needsModule"), verbs.filter { it.isNotEmpty() })
                }
            }.orEmpty()
            val aliasObj = root.optJSONObject("aliases")
            val aliases = aliasObj?.keys()?.asSequence()?.associateWith { id ->
                aliasObj.optJSONArray(id)?.let { a -> (0 until a.length()).map { normalize(a.optString(it)) } }.orEmpty()
            }.orEmpty()
            val faq = root.optJSONArray("faq")?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.mapNotNull { o ->
                    val answer = o.optString("answer").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val keys = o.optJSONArray("keys")?.let { k -> (0 until k.length()).map { normalize(k.optString(it)) } }.orEmpty()
                    Faq(keys.filter { it.isNotEmpty() }, answer)
                }
            }.orEmpty()
            KairosAgentKnowledge(intents, aliases, faq)
        } catch (_: Exception) {
            null
        }
    }
}

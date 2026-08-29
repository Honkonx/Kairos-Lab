package com.termux.app.data

import android.content.Context
import android.util.Log
import com.termux.app.model.ModuleInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Catálogo híbrido de módulos/plugins (Fase B del sistema de plugins, 2026-08-10 — ver
 * docs/arquitectura/PLAN.md "Sistema de plugins / Tienda de módulos").
 *
 * FUENTES (en orden de precedencia):
 * 1. **Remote cache** (`context.filesDir/modules_catalog.json`) — resultado del último
 *    refresco remoto exitoso. Sobrevive reinicios sin red.
 * 2. **Remote live** — `https://raw.githubusercontent.com/Honkonx/kairos-lab/<branch>/app/src/main/assets/modules.json`
 *    (apunta al futuro repo PÚBLICO kairos-lab a propósito, ver docs/humano267.md — ningún
 *    link de descarga debe quedar apuntando al privado kairos-dev, ni siquiera antes de que
 *    kairos-lab exista de verdad)
 *    cuando el usuario toca "Buscar actualizaciones" en la Tienda.
 * 3. **Bundled** (`assets/modules.json`) — catálogo que viaja dentro del APK; siempre
 *    presente como fallback offline.
 *
 * MERGE por `id`: el remoto gana campo a campo sobre el bundled (la app no baja de versión
 * de un plugin existente), y los plugins nuevos del remoto se agregan. El fallback a bundled
 * es silencioso si no hay red o el fetch falla.
 *
 * La función [parse] es COMPARTIDA con ModulesFragment (que antes parseaba el JSON a mano,
 * ver ModulesFragment.loadModuleDefinitions()) — un solo lugar que convierte JSONArray →
 * List<ModuleInfo> con los campos viejos + nuevos de la Fase B.
 */
object ModuleCatalog {

    private const val TAG = "ModuleCatalog"

    // Rama por defecto para el catálogo remoto. El catálogo live debe publicarse en la rama
    // estable; la rama de trabajo (xlorie) solo se usa para desarrollo del catálogo bundled.
    private const val DEFAULT_BRANCH = "main"
    private const val CATALOG_PATH = "app/src/main/assets/modules.json"
    private const val CACHE_FILE = "modules_catalog.json"

    fun remoteUrl(branch: String = DEFAULT_BRANCH): String =
        "https://raw.githubusercontent.com/Honkonx/kairos-lab/$branch/$CATALOG_PATH"

    /** Lee el catálogo bundled del APK. Nunca lanza: devuelve lista vacía si algo falla. */
    fun loadBundled(context: Context): List<ModuleInfo> {
        return try {
            val inputStream = context.assets.open("modules.json")
            val sb = StringBuilder()
            BufferedReader(InputStreamReader(inputStream)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) sb.append(line)
            }
            parse(JSONArray(sb.toString()))
        } catch (e: Exception) {
            Log.e(TAG, "loadBundled() falló", e)
            emptyList()
        }
    }

    /**
     * Carga el catálogo con precedencia: cache remoto → bundled, y después mergea los
     * plugins locales (paquetes .tar.gz/.deb instalados por LocalPluginManager desde la
     * Tienda, ronda 2026-08-13 — ver docs/humano/humano100.md). El local gana por id.
     * Corre en el hilo que lo llame (los callers usan background thread).
     */
    fun load(context: Context): List<ModuleInfo> {
        val cached = loadCache(context)
        val base = if (cached.isNotEmpty()) cached else loadBundled(context)
        return com.termux.app.util.LocalPluginManager.mergeLocal(base)
    }

    /**
     * Refresco híbrido: trae el catálogo remoto y lo mergea sobre el bundled. Si el fetch
     * falla (sin red, repo caído, JSON inválido) devuelve el catálogo ACTUAL (cache o
     * bundled) sin tocar nada — fallback silencioso. Corre en el hilo que lo llame.
     */
    fun refreshRemote(context: Context, branch: String = DEFAULT_BRANCH): List<ModuleInfo> {
        return try {
            val remote = fetchRemote(branch)
            val merged = merge(loadBundled(context), remote)
            saveCache(context, merged)
            com.termux.app.util.LocalPluginManager.mergeLocal(merged)
        } catch (e: Exception) {
            Log.w(TAG, "refreshRemote() falló (usa cache/bundled)", e)
            load(context)
        }
    }

    private fun fetchRemote(branch: String): List<ModuleInfo> {
        val conn = URL(remoteUrl(branch)).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("Accept", "application/json")
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                throw RuntimeException("HTTP ${conn.responseCode}")
            }
            val sb = StringBuilder()
            conn.inputStream.bufferedReader().use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) sb.append(line)
            }
            return parse(JSONArray(sb.toString()))
        } finally {
            conn.disconnect()
        }
    }

    /** Merge campo a campo por `id`: remote gana sobre bundled. */
    private fun merge(bundled: List<ModuleInfo>, remote: List<ModuleInfo>): List<ModuleInfo> {
        val byId = linkedMapOf<String, ModuleInfo>()
        bundled.forEach { byId[it.id] = it }
        remote.forEach { byId[it.id] = it }
        return byId.values.toList()
    }

    private fun saveCache(context: Context, modules: List<ModuleInfo>) {
        try {
            val arr = JSONArray()
            for (m in modules) {
                val o = JSONObject()
                    .put("id", m.id)
                    .put("name", m.name)
                    .put("icon", m.icon)
                    .put("iconBg", m.iconBg)
                    .put("port", m.port)
                    .put("script", m.script)
                    .put("description", m.description)
                    .put("size", m.size)
                    .put("type", m.type)
                    .put("requiresProot", m.requiresProot)
                    .put("hasVariants", m.hasVariants)
                    .put("estimate", m.estimate)
                    .put("hasSwitch", m.hasSwitch)
                    .put("tmuxSession", m.tmuxSession)
                    .put("webviewUrl", m.webviewUrl)
                    .put("terminalCommand", m.terminalCommand)
                    .put("arch", m.arch)
                    .put("category", m.category)
                    .put("catalogVersion", m.catalogVersion)
                    .put("installMethods", JSONArray(m.installMethods))
                    .put("requires", JSONArray(m.requires))
                    .put("recommended", m.recommended)
                    .put("downloads", m.downloads)
                    .put("internal", m.internal)
                    .put("hideFromCatalog", m.hideFromCatalog)
                    .put("iconAsset", m.iconAsset ?: JSONObject.NULL)
                arr.put(o)
            }
            File(context.filesDir, CACHE_FILE).writeText(arr.toString())
        } catch (e: Exception) {
            Log.w(TAG, "saveCache() falló", e)
        }
    }

    private fun loadCache(context: Context): List<ModuleInfo> {
        return try {
            val f = File(context.filesDir, CACHE_FILE)
            if (!f.exists()) return emptyList()
            parse(JSONArray(f.readText()))
        } catch (e: Exception) {
            Log.w(TAG, "loadCache() falló (ignorado)", e)
            emptyList()
        }
    }

    /**
     * JSONArray → List<ModuleInfo>. Los campos nuevos de la Fase B usan opt* (toleran
     * catálogos viejos que no los traen). Compartida con ModulesFragment.
     */
    fun parse(arr: JSONArray): List<ModuleInfo> {
        val result = mutableListOf<ModuleInfo>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            result.add(parseOne(obj))
        }
        return result
    }

    /**
     * Un solo objeto JSON → ModuleInfo. La versión para catálogo local (LocalPluginManager
     * lee manifest.json de un .tar.gz con este mismo schema) reusa este parseo en vez de
     * duplicarlo — ver deduplicación en docs/humano/humano123.md.
     */
    @JvmStatic
    fun parseOne(obj: JSONObject): ModuleInfo = ModuleInfo(
        id = obj.getString("id"),
        name = obj.optString("name", obj.getString("id")),
        icon = obj.optString("icon", "⬡"),
        iconBg = obj.optString("iconBg", "#111111"),
        port = obj.optString("port", ""),
        // Fallback defensivo con la misma convención que ModuleController.installScriptFile()
        // usaba hardcodeada antes de esta ronda — no debería activarse con el JSON real, que
        // siempre trae "script" explícito (ver campo en ModuleInfo.kt).
        script = obj.optString("script", "${obj.getString("id")}.sh"),
        description = obj.optString("description", ""),
        size = obj.optString("size", ""),
        type = obj.optString("type", ""),
        requiresProot = obj.optBoolean("requiresProot", false),
        hasVariants = obj.optBoolean("hasVariants", false),
        estimate = obj.optString("estimate", ""),
        hasSwitch = obj.optBoolean("hasSwitch", true),
        tmuxSession = obj.optString("tmuxSession", ""),
        webviewUrl = obj.optString("webviewUrl", ""),
        terminalCommand = obj.optString("terminalCommand", ""),
        arch = obj.optString("arch", ""),
        category = obj.optString("category", ""),
        catalogVersion = obj.optString("catalogVersion", ""),
        installMethods = obj.optJSONArray("installMethods")?.let { ja ->
            (0 until ja.length()).map { ja.getString(it) }
        } ?: emptyList(),
        requires = obj.optJSONArray("requires")?.let { ja ->
            (0 until ja.length()).map { ja.getString(it) }
        } ?: emptyList(),
        recommended = obj.optBoolean("recommended", false),
        downloads = obj.optInt("downloads", 0),
        internal = obj.optBoolean("internal", false),
        hideFromCatalog = obj.optBoolean("hideFromCatalog", false),
        iconAsset = obj.optString("iconAsset", "").takeIf { it.isNotEmpty() }
    )
}

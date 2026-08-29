package com.termux.app.util

import android.content.Context
import android.net.Uri
import android.util.Log
import com.termux.app.model.ModuleInfo
import com.termux.shared.termux.TermuxConstants
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * Instalación de PLUGINS LOCALES desde la Tienda (pedido explícito del usuario, ronda
 * 2026-08-13, ver docs/humano/humano100.md): "agregar una opcion en la tienda de plugins
 * para instalar paquetes locales, por ejemplo tar.gz o .deb — seran los mismos plugins
 * pero en paquetes que yo creare".
 *
 * DOS formatos soportados (elegidos por extensión del archivo elegido en el SAF picker):
 *
 * 1. **.deb** — un paquete Termux (apt/dpkg) real: se copia a $HOME/tmp/ y se instala
 *    con `pkg install -y <archivo>`. Es un plugin como los de modules.json pero
 *    distribuido como paquete de paquetes Termux (apt) en vez de por el catálogo remoto.
 *
 * 2. **.tar.gz** — paquete "hecho a mano" con este formato (documentado acá):
 *      - `manifest.json`  → mismo schema que una entrada de modules.json (ModuleInfo).
 *      - `<id>.sh`        → script de instalación con el contrato de los módulos
 *        (--silent/--force/--describe), que se copia a ~/scripts/install/<id>.sh.
 *      Se extrae a ~/kairos_local/<id>/, el script se copia a ~/scripts/install/ y el
 *      manifest se mergea en un catálogo local persistente (~/kairos_local/catalog.json)
 *      que ModuleCatalog.load() lee además de cache/bundled — el plugin aparece en la
 *      Tienda como cualquier otro y se instala/desinstala por el flujo normal.
 *
 * El picker usa Storage Access Framework (OpenDocument) — devuelve una content Uri que
 * NO es un path accesible directamente, por eso el archivo se copia primero a $HOME.
 */
object LocalPluginManager {

    private const val TAG = "LocalPluginManager"

    private val home: String get() = TermuxConstants.TERMUX_HOME_DIR_PATH
    private val localDir get() = File(home, "kairos_local")
    private val catalogFile get() = File(localDir, "catalog.json")
    private val stagingDir get() = File(home, "tmp/kairos_local_staging")
    private val scriptsInstallDir get() = File(home, "scripts/install")

    /** Contrato del paquete .tar.gz — documentado para quien quiera crear sus propios plugins. */
    fun packageSpec(): String = """
        Paquete de plugin local (.tar.gz):
          manifest.json   — entrada igual a modules.json: id, name, icon, iconBg, port,
                            description, size, type, arch, category, hasSwitch, terminalCommand, ...
          <id>.sh         — script de instalación (contrato --silent/--force/--describe),
                            se copia a ~/scripts/install/<id>.sh

        Ejemplo (crear tu propio plugin):
          mkdir -p mi_plugin && cd mi_plugin
          cat > manifest.json << 'EOF'
          {"id":"mimodulo","name":"Mi Módulo","icon":"🚀","iconBg":"#123456","port":"8080",
           "description":"Plugin hecho por mí","arch":"bionic","category":"dev",
           "hasSwitch":true,"terminalCommand":""}
          EOF
          # script de instalación con el contrato estándar de módulos:
          #   --silent / --force / --describe (manifest JSON declarativo)
          cat > mimodulo.sh << 'EOF'
          #!/data/data/com.termux/files/usr/bin/bash
          echo "instalando mi plugin..."; exit 0
          EOF
          tar -czf mi_plugin.tar.gz manifest.json mimodulo.sh

        Los .deb se instalan directo con apt/pkg.
    """.trimIndent()

    /**
     * Instala un paquete local elegido en la Tienda. Corre en el hilo que lo llame
     * (los callers usan background thread). Devuelve JSONObject con ok/error/output.
     */
    fun install(context: Context, uri: Uri): JSONObject {
        return try {
            val name = uri.lastPathSegment?.substringAfterLast("/")?.ifEmpty { "paquete" } ?: "paquete"
            val ext = name.substringAfterLast('.', "").lowercase()
            when (ext) {
                "deb" -> installDeb(context, uri, name)
                "gz", "tgz", "tar" -> installTarGz(context, uri, name)
                else -> JSONObject().put("ok", false).put("error", "Formato no soportado (.deb o .tar.gz) — recibido: .$ext")
            }
        } catch (e: Exception) {
            Log.e(TAG, "install() falló", e)
            JSONObject().put("ok", false).put("error", e.message ?: "error desconocido")
        }
    }

    // ── .deb → apt ────────────────────────────────────────────────────
    private fun installDeb(context: Context, uri: Uri, name: String): JSONObject {
        val tmp = File(home, "tmp")
        tmp.mkdirs()
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val dest = File(tmp, safe)
        copyUriToFile(context, uri, dest)
        if (!dest.exists() || dest.length() == 0L) {
            return JSONObject().put("ok", false).put("error", "No se pudo copiar el paquete .deb")
        }
        val (rc, out, err) = ManagerNativeUtils.runShell(
            "$TERMUX_PKG_PATH install -y ${shellQuote(dest.absolutePath)} 2>&1 | tail -30", 180
        )
        val output = out.ifEmpty { err }
        Log.i(TAG, "installDeb: rc=$rc output=${output.takeLast(400)}")
        if (rc != 0) {
            return JSONObject().put("ok", false).put("error", "No se pudo instalar el paquete .deb")
                .put("output", output.takeLast(400))
        }
        return JSONObject().put("ok", true)
            .put("message", "Paquete .deb instalado con apt")
            .put("output", output.takeLast(400))
    }

    // ── .tar.gz → manifest.json + script ──────────────────────────────
    private fun installTarGz(context: Context, uri: Uri, name: String): JSONObject {
        stagingDir.deleteRecursively()
        stagingDir.mkdirs()
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val archive = File(stagingDir, safe)
        copyUriToFile(context, uri, archive)
        if (!archive.exists() || archive.length() == 0L) {
            return JSONObject().put("ok", false).put("error", "No se pudo copiar el paquete .tar.gz")
        }
        val extract = ManagerNativeUtils.runShell("tar -xzf ${shellQuote(archive.absolutePath)} -C ${shellQuote(stagingDir.absolutePath)}", 30)
        if (extract.first != 0) {
            return JSONObject().put("ok", false).put("error", "No se pudo extraer el .tar.gz — ¿es un archivo tar.gz válido?")
                .put("output", extract.third.ifEmpty { extract.second }.takeLast(300))
        }
        val manifestFile = stagingDir.resolve("manifest.json")
        if (!manifestFile.exists()) {
            return JSONObject().put("ok", false).put("error", "Falta manifest.json en la raíz del .tar.gz")
        }
        val info = parseManifest(manifestFile)
            ?: return JSONObject().put("ok", false).put("error", "manifest.json inválido — falta el campo \"id\"")
        val scriptName = "${info.id}.sh"
        val scriptFile = stagingDir.resolve(scriptName)
        if (!scriptFile.exists()) {
            return JSONObject().put("ok", false).put("error", "Falta $scriptName en el .tar.gz (script de instalación con el contrato de módulos)")
        }
        // Copiar script al directorio de instalación estándar de la app.
        scriptsInstallDir.mkdirs()
        val destScript = File(scriptsInstallDir, scriptName)
        scriptFile.copyTo(destScript, overwrite = true)
        destScript.setExecutable(true)
        // Registrar en el catálogo local persistente (merge por id, local gana).
        localDir.mkdirs()
        val localCatalog = readLocalCatalog()
        localCatalog[info.id] = info
        catalogFile.writeText(JSONArray(localCatalog.values.map { toJson(it) }).toString())
        return JSONObject().put("ok", true)
            .put("message", "${info.name} agregado al catálogo local — instalalo desde la Tienda")
            .put("id", info.id)
    }

    // ── catálogo local ────────────────────────────────────────────────
    private fun readLocalCatalog(): MutableMap<String, ModuleInfo> {
        if (!catalogFile.exists()) return mutableMapOf()
        return try {
            val arr = JSONArray(catalogFile.readText())
            val map = mutableMapOf<String, ModuleInfo>()
            for (i in 0 until arr.length()) {
                val info = fromJson(arr.getJSONObject(i)) ?: continue
                map[info.id] = info
            }
            map
        } catch (e: Exception) {
            Log.w(TAG, "readLocalCatalog() falló", e)
            mutableMapOf()
        }
    }

    /** Mergea los plugins locales sobre un catálogo ya cargado (cache/bundled). */
    fun mergeLocal(modules: List<ModuleInfo>): List<ModuleInfo> {
        val local = readLocalCatalog()
        if (local.isEmpty()) return modules
        val byId = linkedMapOf<String, ModuleInfo>()
        modules.forEach { byId[it.id] = it }
        local.forEach { (id, info) -> byId[id] = info }
        return byId.values.toList()
    }

    /** Lista de plugins locales (para mostrar el contador o estado). */
    fun localCount(): Int = readLocalCatalog().size

    /** Ids de los plugins del catálogo local — para que la UI pueda marcar "local" y ofrecer quitarlo. */
    fun localIds(): Set<String> = readLocalCatalog().keys

    /**
     * Quita un plugin del catálogo local — pedido explícito del usuario (humano123, ver
     * docs/humano/humano123.md): los plugins instalados vía .tar.gz quedaban permanentemente
     * en la Tienda sin forma de sacarlos desde la UI. Borra su entrada del catálogo local
     * persistente y su script (~/scripts/install/<id>.sh) — NO toca paquetes instalados por
     * apt (un .deb se gestiona con "Desinstalar" normal vía ModuleController). Devuelve true
     * si había algo que quitar.
     */
    fun removeLocal(moduleId: String): Boolean {
        val local = readLocalCatalog()
        val removed = local.remove(moduleId) != null
        if (removed) {
            catalogFile.writeText(JSONArray(local.values.map { toJson(it) }).toString())
            val script = File(scriptsInstallDir, "$moduleId.sh")
            if (script.exists()) script.delete()
            Log.i(TAG, "removeLocal: plugin local '$moduleId' quitado del catálogo")
        }
        return removed
    }

    private fun parseManifest(file: File): ModuleInfo? {
        return try {
            fromJson(JSONObject(file.readText()))
        } catch (e: Exception) {
            Log.w(TAG, "parseManifest() falló", e)
            null
        }
    }

    private fun copyUriToFile(context: Context, uri: Uri, dest: File) {
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(dest).use { output -> input.copyTo(output) }
        }
    }

    private fun toJson(info: ModuleInfo): JSONObject = JSONObject()
        .put("id", info.id)
        .put("name", info.name)
        .put("icon", info.icon)
        .put("iconBg", info.iconBg)
        .put("port", info.port)
        .put("description", info.description)
        .put("size", info.size)
        .put("type", info.type)
        .put("requiresProot", info.requiresProot)
        .put("hasVariants", info.hasVariants)
        .put("estimate", info.estimate)
        .put("hasSwitch", info.hasSwitch)
        .put("tmuxSession", info.tmuxSession)
        .put("webviewUrl", info.webviewUrl)
        .put("terminalCommand", info.terminalCommand)
        .put("arch", info.arch)
        .put("category", info.category)
        .put("catalogVersion", info.catalogVersion)
        .put("installMethods", JSONArray(info.installMethods))
        .put("requires", JSONArray(info.requires))
        .put("recommended", info.recommended)
        .put("downloads", info.downloads)

    /**
     * Mismo parseo que ModuleCatalog.parseOne() para un solo objeto — reusado por el
     * catálogo local (manifest.json de un .tar.gz usa el mismo schema que modules.json).
     * Deduplicado en la ronda 2026-08-14 (humano123): antes era una copia literal del
     * parseo de ModuleCatalog.parse() — cualquier cambio de campo había que replicarlo
     * en dos lugares. ModuleCatalog está en data/ (capa de datos) y no depende de util/,
     * así que no hay ciclo data<->util (la dependencia original "a propósito para evitar
     * el ciclo" era al revés — util importando data está permitido por la regla de
     * dependencias del proyecto).
     */
    private fun fromJson(obj: JSONObject): ModuleInfo? {
        if (!obj.has("id")) return null
        return com.termux.app.data.ModuleCatalog.parseOne(obj)
    }
}
package com.termux.app.util

import com.termux.shared.termux.TermuxConstants
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Sistema ÚNICO de instalación de `.deb` de módulos de Kairos (formato v2, ver `docs/paquetes_deb/`).
 *
 * Las tres formas de obtener un paquete pasan por EXACTAMENTE el mismo camino ([installFromDeb]):
 *  1. **GitHub**: [installFromGithub] baja el `.deb` del Release de `Honkonx/kairos-lab` y compara su
 *     sha256 con el que publica el mismo Release (`catalog.json` o `SHA256SUMS.txt`).
 *  2. **Paquete local**: el usuario elige un archivo (Tienda → "Paquete local" → `LocalPluginManager`).
 *     No hay hash externo, así que SIEMPRE se le muestra el resumen y debe confirmar.
 *  3. **Creado desde el módulo**: `moduledeb pack <id>` (RepoFragment) genera el mismo formato v2.
 *
 * Orden obligatorio (pedido explícito del usuario, ``): primero se leen SOLO
 * `DEBIAN/manifest.json` y `DEBIAN/SHA256SUMS.txt` (`moduledeb inspect`, no extrae ni ejecuta nada);
 * recién después se compara contra el sistema (paquetes `pkg`, complementos, módulos), se descargan los
 * requisitos que falten y se instala. Un `.deb` sin esos dos archivos no se instala nunca.
 */
object ModuleDebInstaller {

    private const val REPO = "Honkonx/kairos-lab"
    private const val USER_AGENT = "kairos-app/module-deb-installer (+https://github.com/$REPO)"
    private val PKG_NAME = Regex("^[A-Za-z0-9+._-]+$")

    /** Carpeta pública donde `moduledeb.sh pack` guarda los .deb que el usuario CREA (para compartirlos). */
    fun kairosDownloadsDir(): File = File("/storage/emulated/0/Download/kairos").apply { mkdirs() }

    /** Los descargados de GitHub van a una carpeta privada de la app: entre bajar y verificar el hash
     * ninguna otra app con acceso al almacenamiento puede reemplazar el archivo. */
    private fun privateDownloadsDir(): File =
        File(TermuxConstants.TERMUX_HOME_DIR_PATH, "kairos_local/module_deb_downloads").apply { mkdirs() }

    data class GithubAsset(val downloadUrl: String, val fileName: String, val releaseTag: String, val sha256: String?)

    data class Requirement(
        val id: String,
        /** package = `pkg install -y` verificable; addon/module = capacidad de Kairos; other = comando libre. */
        val kind: String,
        val packages: List<String>,
        val installHint: String,
        /** present | missing | unknown (solo `package` se puede comprobar antes de confirmar). */
        val status: String
    )

    /** Resultado de `moduledeb inspect`: lo único que se sabe del paquete antes de instalar nada. */
    data class PackageReport(
        val ok: Boolean,
        val reasons: List<String>,
        val id: String = "",
        val version: String = "",
        val filesCount: Int = 0,
        val declaredPaths: List<String> = emptyList(),
        val requires: List<Requirement> = emptyList(),
        val runsVerifyCmd: Boolean = false,
        val runsPatchCmd: Boolean = false,
        /** Texto REAL de los comandos (hallazgo de seguridad 2026-10-31: antes solo se sabía
         *  QUE el manifest declaraba verify_cmd/patch_cmd, nunca QUÉ decían — moduledeb.sh
         *  inspect() ahora los expone en texto para que el usuario pueda revisarlos antes de
         *  confirmar, en vez de solo un aviso genérico "ejecuta comandos"). */
        val verifyCmdText: String = "",
        val patchCmdText: String = "",
        val notCovered: List<String> = emptyList()
    ) {
        val missing: List<Requirement> get() = requires.filter { it.status != "present" }
    }

    data class InstallResult(val ok: Boolean, val message: String)

    // ── GitHub ──────────────────────────────────────────────────────────────

    /**
     * Busca el `.deb` más reciente de [moduleId] entre los Releases y, en ese mismo Release, el hash que
     * publica el generador (`catalog.json` v2 o `SHA256SUMS.txt`). Sin hash devuelve igual el asset
     * (con `sha256 = null`): el instalador decide qué hacer (GitHub sin hash NO se instala).
     */
    fun findGithubAsset(moduleId: String): GithubAsset? {
        return try {
            val json = httpGet("https://api.github.com/repos/$REPO/releases?per_page=30") ?: return null
            val releases = JSONArray(json)
            val prefix = "kairos-module-$moduleId"
            for (i in 0 until releases.length()) {
                val release = releases.optJSONObject(i) ?: continue
                val tag = release.optString("tag_name", "?")
                val assets = release.optJSONArray("assets") ?: continue
                var debUrl = ""
                var debName = ""
                var catalogUrl = ""
                var sumsUrl = ""
                for (j in 0 until assets.length()) {
                    val asset = assets.optJSONObject(j) ?: continue
                    val name = asset.optString("name", "")
                    val url = asset.optString("browser_download_url", "")
                    when {
                        name.startsWith(prefix) && name.endsWith(".deb") && debUrl.isEmpty() -> { debUrl = url; debName = name }
                        name == "catalog.json" -> catalogUrl = url
                        name == "SHA256SUMS.txt" -> sumsUrl = url
                    }
                }
                if (debUrl.isBlank()) continue
                val sha = hashFromCatalog(catalogUrl, debName) ?: hashFromSums(sumsUrl, debName)
                return GithubAsset(debUrl, debName, tag, sha)
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    /** `catalog.json` v2: `{"packages":[{"filename":"…","sha256":"…"}]}` (material/catalog.json es la v1, sin hash). */
    private fun hashFromCatalog(url: String, fileName: String): String? {
        if (url.isBlank()) return null
        return try {
            val arr = JSONObject(httpGet(url) ?: return null).optJSONArray("packages") ?: return null
            for (i in 0 until arr.length()) {
                val p = arr.optJSONObject(i) ?: continue
                if (p.optString("filename") == fileName) return p.optString("sha256").lowercase().takeIf { it.length == 64 }
            }
            null
        } catch (e: Exception) { null }
    }

    /** `SHA256SUMS.txt` estándar de `sha256sum`: `<hash>  <archivo>`. */
    private fun hashFromSums(url: String, fileName: String): String? {
        if (url.isBlank()) return null
        val text = httpGet(url) ?: return null
        return text.lineSequence().mapNotNull { line ->
            val parts = line.trim().split(Regex("\\s+"), limit = 2)
            if (parts.size == 2 && parts[1].trimStart('*') == fileName && parts[0].length == 64) parts[0].lowercase() else null
        }.firstOrNull()
    }

    private fun httpGet(url: String): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 12_000
            conn.readTimeout = 12_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", USER_AGENT)
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    /** Descarga a la carpeta PRIVADA de la app. Devuelve el File o null si falló. */
    fun downloadAsset(asset: GithubAsset, onProgress: (String) -> Unit): File? {
        val dest = File(privateDownloadsDir(), asset.fileName)
        return try {
            onProgress("Descargando ${asset.fileName} (release ${asset.releaseTag})…")
            val conn = URL(asset.downloadUrl).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.connect()
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                conn.disconnect()
                return null
            }
            conn.inputStream.use { input -> dest.outputStream().use { output -> input.copyTo(output) } }
            conn.disconnect()
            dest
        } catch (e: Exception) {
            dest.delete()
            null
        }
    }

    fun sha256Of(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    // ── moduledeb ───────────────────────────────────────────────────────────

    private val moduledebScriptPath: String
        get() = File(TermuxConstants.TERMUX_HOME_DIR_PATH, "scripts/install/moduledeb.sh").absolutePath

    private fun runModuledeb(args: List<String>, timeoutSeconds: Long = 60): Triple<Int, String, String> {
        return try {
            val pb = ProcessBuilder(listOf(TERMUX_BASH_PATH, moduledebScriptPath) + args)
            pb.applyTermuxEnv()
            val process = pb.start()
            val out = StringBuilder()
            val err = StringBuilder()
            // Bug real confirmado por ADB (2026-08-24): sin try/catch, destroyForcibly
            // cierra los streams con el Thread bloqueado en readText() y la excepción mata la app.
            val outThread = Thread { try { out.append(process.inputStream.bufferedReader().readText()) } catch (_: Exception) {} }
            val errThread = Thread { try { err.append(process.errorStream.bufferedReader().readText()) } catch (_: Exception) {} }
            outThread.start(); errThread.start()
            val finished = process.waitFor(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)
            if (!finished) process.destroyForcibly()
            outThread.join(); errThread.join()
            if (!finished) Triple(1, "", "timeout")
            else Triple(process.exitValue(), out.toString().trim(), err.toString().trim())
        } catch (e: Exception) {
            Triple(1, "", e.message ?: "error")
        }
    }

    /** `moduledeb inspect`: lee SOLO manifest.json + SHA256SUMS.txt. No extrae ni ejecuta nada del paquete. */
    fun inspect(debFile: File): PackageReport {
        if (!debFile.exists()) return PackageReport(false, listOf("El archivo .deb no existe: ${debFile.absolutePath}"))
        val (_, out, err) = runModuledeb(listOf("inspect", debFile.absolutePath), timeoutSeconds = 90)
        val json = try { JSONObject(out) } catch (e: Exception) {
            return PackageReport(false, listOf(err.ifBlank { "moduledeb inspect no devolvió un resultado válido" }))
        }
        return parseReport(json)
    }

    internal fun parseReport(json: JSONObject): PackageReport {
        if (!json.optBoolean("ok", false)) {
            val reasons = json.optJSONArray("reasons")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
            return PackageReport(false, reasons.ifEmpty { listOf("paquete no válido") })
        }
        fun strings(a: JSONArray?) = a?.let { arr -> (0 until arr.length()).map { arr.optString(it) } }.orEmpty()
        val reqs = json.optJSONArray("requires")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                Requirement(
                    id = o.optString("id"), kind = o.optString("kind", "other"),
                    packages = strings(o.optJSONArray("packages")),
                    installHint = o.optString("install_hint"), status = o.optString("status", "unknown")
                )
            }
        }.orEmpty()
        val runs = json.optJSONObject("runs_commands")
        return PackageReport(
            ok = true, reasons = emptyList(),
            id = json.optString("id"), version = json.optString("version"),
            filesCount = json.optInt("files_count"), declaredPaths = strings(json.optJSONArray("declared_paths")),
            requires = reqs, runsVerifyCmd = runs?.optBoolean("verify_cmd") ?: false,
            runsPatchCmd = runs?.optBoolean("patch_cmd") ?: false,
            verifyCmdText = runs?.optString("verify_cmd_text").orEmpty(),
            patchCmdText = runs?.optString("patch_cmd_text").orEmpty(),
            notCovered = strings(json.optJSONArray("not_covered"))
        )
    }

    /** Instala los requisitos de tipo `package` que faltan (nombres validados, sin shell). Devuelve los que siguen faltando. */
    private fun installMissingPackages(missing: List<Requirement>, onProgress: (String) -> Unit): List<String> {
        val names = missing.filter { it.kind == "package" }.flatMap { it.packages }.distinct().filter { PKG_NAME.matches(it) }
        if (names.isEmpty()) return emptyList()
        onProgress("Descargando requisitos: ${names.joinToString(", ")}…")
        ManagerNativeUtils.runExec(listOf(TERMUX_PKG_PATH, "install", "-y") + names, 900)
        return names.filter { ManagerNativeUtils.runExec(listOf("$TERMUX_PREFIX_PATH/bin/dpkg", "-s", it), 15).first != 0 }
    }

    /**
     * Camino común de los tres orígenes. [expectedSha256] = hash publicado por el canal de confianza
     * (GitHub); `null` = origen sin hash externo (local), que exige [confirm] con el resumen del paquete.
     * Corre en el hilo que lo llama (siempre un Thread de background).
     */
    fun installFromDeb(
        debFile: File,
        expectedSha256: String? = null,
        confirm: (PackageReport) -> Boolean = { false },
        onProgress: (String) -> Unit = {}
    ): InstallResult {
        if (!debFile.exists()) return InstallResult(false, "El archivo .deb no existe: ${debFile.absolutePath}")

        // 1) Hash del archivo contra el canal de confianza (si lo hay).
        if (expectedSha256 != null) {
            onProgress("Verificando hash del paquete…")
            if (sha256Of(debFile) != expectedSha256.lowercase()) {
                debFile.delete()
                return InstallResult(false, "El hash del .deb no coincide con el publicado — descarga descartada")
            }
        }

        // 2) Leer SOLO el JSON y el .txt. Sin ambos, no se instala.
        onProgress("Leyendo manifest.json y SHA256SUMS.txt…")
        val report = inspect(debFile)
        if (!report.ok) return InstallResult(false, "El paquete no se instala: ${report.reasons.joinToString("; ")}")

        // 3) Sin hash externo (paquete local) hace falta consentimiento explícito con el resumen.
        if (expectedSha256 == null && !confirm(report)) {
            return InstallResult(false, "Instalación cancelada — el paquete no tiene hash verificado por un canal de confianza")
        }

        // 4) Extraer el payload y exigir que sea EXACTAMENTE lo que lista SHA256SUMS.txt.
        onProgress("Extrayendo y comprobando archivos…")
        val (rcExtract, outExtract, errExtract) = runModuledeb(listOf("extract", debFile.absolutePath), timeoutSeconds = 300)
        if (rcExtract != 0) return InstallResult(false, "El paquete no se instala: ${errExtract.ifBlank { outExtract }}")
        val extractDir = outExtract.lines().lastOrNull { it.isNotBlank() }?.trim()
            ?: return InstallResult(false, "moduledeb extract no devolvió una ruta válida")

        // 5) Comparar contra el sistema; descargar lo que falte.
        onProgress("Comparando con el sistema…")
        var (rcVerify, outVerify, _) = runModuledeb(listOf("verify", extractDir))
        var summary = try { JSONObject(outVerify) } catch (e: Exception) { null }
        if (summary == null || !summary.optBoolean("kairos_package_valid", false)) {
            return InstallResult(false, "El paquete no es un paquete Kairos válido — ${summary?.optString("error").orEmpty().ifBlank { "manifest.json inválido" }}")
        }
        if (rcVerify == 2) {
            val deps = summary.optJSONArray("dependencies") ?: JSONArray()
            val missingIds = (0 until deps.length()).mapNotNull { deps.optJSONObject(it) }.filter { !it.optBoolean("ok", true) }.map { it.optString("id") }.toSet()
            val toInstall = report.requires.filter { it.id in missingIds }
            installMissingPackages(toInstall, onProgress)
            // Comandos libres (kind=other): solo tras confirmar/ser de canal verificado, nunca por eval silencioso.
            for (req in toInstall.filter { it.kind == "other" && it.installHint.isNotBlank() }) {
                onProgress("Instalando requisito: ${req.id}…")
                ManagerNativeUtils.runExec(listOf(TERMUX_BASH_PATH, "-c", req.installHint), 300)
            }
            onProgress("Re-verificando requisitos…")
            val (rc2, out2, _) = runModuledeb(listOf("verify", extractDir))
            rcVerify = rc2
            summary = try { JSONObject(out2) } catch (e: Exception) { summary }
        }
        if (rcVerify == 2) {
            val missing = summary?.optJSONArray("dependencies")?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.takeIf { d -> !d.optBoolean("ok", true) }?.optString("id") }
            }?.joinToString(", ") ?: "desconocidos"
            return InstallResult(false, "Faltan requisitos que no se pudieron resolver ($missing) — instala el módulo con su script normal")
        }
        if (rcVerify == 1) return InstallResult(true, "Ya estaba instalado y verificado — no hacía falta tocar nada")

        // 6) Aplicar.
        onProgress("Instalando…")
        val (rcApply, outApply, errApply) = runModuledeb(listOf("apply", extractDir), timeoutSeconds = 300)
        return if (rcApply == 0) InstallResult(true, "Instalado desde ${debFile.name}")
        else InstallResult(false, "Falló al aplicar: ${errApply.ifBlank { outApply }}")
    }

    /** GitHub → descarga a carpeta privada → hash → camino común. Sin hash publicado NO se instala. */
    fun installFromGithub(moduleId: String, onProgress: (String) -> Unit): InstallResult {
        onProgress("Buscando paquete de '$moduleId' en GitHub Releases…")
        val asset = findGithubAsset(moduleId)
            ?: return InstallResult(false, "No hay ningún paquete publicado para '$moduleId' en GitHub todavía — prueba la instalación limpia")
        if (asset.sha256 == null) {
            return InstallResult(false, "El Release ${asset.releaseTag} no publica el hash de ${asset.fileName} (catalog.json / SHA256SUMS.txt) — no se instala sin poder verificarlo")
        }
        val file = downloadAsset(asset, onProgress) ?: return InstallResult(false, "No se pudo descargar ${asset.fileName}")
        return installFromDeb(file, asset.sha256, onProgress = onProgress)
    }
}

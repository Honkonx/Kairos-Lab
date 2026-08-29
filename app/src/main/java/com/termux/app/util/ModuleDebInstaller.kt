package com.termux.app.util

import android.content.Context
import com.termux.shared.termux.TermuxConstants
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Descarga+instalación de un `.deb` de módulo desde GitHub Releases (pedido explícito del
 * usuario, ver docs/humano206.md — "poder compilar/tener los modulos en paquetes y subirlo a
 * release y que el apk los pueda [...] descargar y instalar"). Complementa a
 * `RepoFragment.runModuleDebPack()` (que solo CREA el .deb, on-device, vía `moduledeb pack`) —
 * este archivo es el lado de INSTALAR desde un `.deb` ya armado, sea local o descargado.
 *
 * La Fase 3 (build+publish diario automatizado, ver conversación de esta ronda) queda FUERA de
 * alcance a propósito — hoy es muy probable que no exista ningún Release con paquetes todavía.
 * [findGithubAsset] devuelve `null` limpio en ese caso (no es un error, es el estado real actual)
 * — el mecanismo queda armado y funcional para cuando esos Releases empiecen a existir.
 */
object ModuleDebInstaller {

    // Apunta al futuro repo PÚBLICO kairos-lab (todavía no creado/publicado) a propósito —
    // pedido explícito del usuario (docs/humano267.md): ningún link de descarga debe apuntar
    // al repo privado kairos-dev, ni siquiera hoy que kairos-lab no existe aún, para que el
    // día que se cree y se suba la Release real esto ya funcione sin tocar código de nuevo.
    private const val REPO = "Honkonx/kairos-lab"
    private const val USER_AGENT = "kairos-app/module-deb-installer (+https://github.com/$REPO)"

    /** Carpeta pública donde `moduledeb.sh pack` ya guarda los .deb (ver moduledeb.sh,
     * MODULEDEB_OUT_DIR) — se reusa el mismo destino para los descargados de GitHub, así el
     * usuario ve todos sus paquetes (creados u obtenidos) en un solo lugar con cualquier
     * explorador de archivos. */
    fun kairosDownloadsDir(): File = File("/storage/emulated/0/Download/kairos").apply { mkdirs() }

    data class GithubAsset(val downloadUrl: String, val fileName: String, val releaseTag: String)

    /**
     * Busca, entre TODOS los Releases del repo (no solo "latest" — un build diario futuro podría
     * publicar cada versión como un Release propio), el asset más reciente que matchee
     * `kairos-module-<id>_*.deb` (mismo patrón de nombre que genera `moduledeb.sh pack`,
     * `${_pkgname}_${_ver}_aarch64.deb` con `_pkgname` default `kairos-module-<id>`).
     * Devuelve null si no hay ninguno — no es un error, es el estado real mientras no exista
     * la Fase 3 de publicación automática.
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
                for (j in 0 until assets.length()) {
                    val asset = assets.optJSONObject(j) ?: continue
                    val name = asset.optString("name", "")
                    if (name.startsWith(prefix) && name.endsWith(".deb")) {
                        val url = asset.optString("browser_download_url", "")
                        if (url.isNotBlank()) return GithubAsset(url, name, tag)
                    }
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun httpGet(url: String): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
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

    /** Descarga simple (sin resume — los .deb de módulo son chicos, a diferencia de los GGUF de
     * LocalModelManager) a [kairosDownloadsDir]. Devuelve el File final o null si falló. */
    fun downloadAsset(asset: GithubAsset, onProgress: (String) -> Unit): File? {
        val dest = File(kairosDownloadsDir(), asset.fileName)
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
            conn.inputStream.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            conn.disconnect()
            dest
        } catch (e: Exception) {
            dest.delete()
            null
        }
    }

    private val moduledebScriptPath: String
        get() = File(TermuxConstants.TERMUX_HOME_DIR_PATH, "scripts/install/moduledeb.sh").absolutePath

    private fun runModuledeb(args: List<String>, timeoutSeconds: Long = 60): Triple<Int, String, String> {
        return try {
            val pb = ProcessBuilder(listOf(TERMUX_BASH_PATH, moduledebScriptPath) + args)
            pb.applyTermuxEnv()
            val process = pb.start()
            val out = StringBuilder()
            val err = StringBuilder()
            // Bug real confirmado por ADB (2026-08-24, ver docs/humano222.md): sin try/catch
            // acá, destroyForcibly() más abajo cierra los streams mientras este Thread está
            // bloqueado en readText() — la excepción (InterruptedIOException) sin capturar
            // se propaga fuera de un Thread sin manejador propio y mata TODO el proceso de la
            // app (mismo patrón real confirmado en ModuleController.startModule()).
            val outThread = Thread { try { out.append(process.inputStream.bufferedReader().readText()) } catch (_: Exception) {} }
            val errThread = Thread { try { err.append(process.errorStream.bufferedReader().readText()) } catch (_: Exception) {} }
            outThread.start(); errThread.start()
            val finished = process.waitFor(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)
            // Mismo bug real que ManagerNativeUtils.runShell() (ver docs/humano222.md) —
            // destroyForcibly() debe correr ANTES de join(), si no los threads lectores
            // bloquean para siempre esperando que el proceso colgado cierre sus streams.
            if (!finished) process.destroyForcibly()
            outThread.join(); errThread.join()
            if (!finished) Triple(1, "", "timeout")
            else Triple(process.exitValue(), out.toString().trim(), err.toString().trim())
        } catch (e: Exception) {
            Triple(1, "", e.message ?: "error")
        }
    }

    data class InstallResult(val ok: Boolean, val message: String)

    /**
     * Instala un `.deb` de módulo YA presente en disco (local o recién descargado) — encadena
     * extract → verify → (si faltan dependencias, las instala corriendo su install_hint real,
     * pedido explícito del usuario) → verify de nuevo → apply. Corre en el hilo que la llama —
     * el caller (BottomSheetInstalacion) debe invocar esto desde un Thread de background.
     */
    fun installFromDeb(debFile: File, onProgress: (String) -> Unit): InstallResult {
        if (!debFile.exists()) return InstallResult(false, "El archivo .deb no existe: ${debFile.absolutePath}")

        onProgress("Extrayendo paquete…")
        val (rcExtract, outExtract, errExtract) = runModuledeb(listOf("extract", debFile.absolutePath))
        if (rcExtract != 0) return InstallResult(false, "No se pudo extraer el .deb: ${errExtract.ifBlank { outExtract }}")
        val extractDir = outExtract.lines().lastOrNull { it.isNotBlank() }?.trim()
            ?: return InstallResult(false, "moduledeb extract no devolvió una ruta válida")

        onProgress("Verificando paquete y dependencias…")
        var (rcVerify, outVerify, errVerify) = runModuledeb(listOf("verify", extractDir))
        var summary = try { JSONObject(outVerify) } catch (e: Exception) { null }

        if (summary == null || !summary.optBoolean("kairos_package_valid", false)) {
            return InstallResult(false, "El .deb no es un paquete Kairos válido — ${summary?.optString("error") ?: errVerify.ifBlank { "manifest.json inválido o ausente" }}")
        }

        // rc=2: dependencias faltantes — instalarlas de verdad corriendo su install_hint real
        // (pedido explícito del usuario: "debe verificar y ver si esta las dependencias etc y
        // instalarlas si hace falta", no solo avisar) — un best-effort por cada una, después se
        // re-verifica UNA vez más antes de decidir si se puede seguir.
        if (rcVerify == 2) {
            val deps = summary.optJSONArray("dependencies") ?: JSONArray()
            var installedAny = false
            for (i in 0 until deps.length()) {
                val dep = deps.optJSONObject(i) ?: continue
                if (dep.optBoolean("ok", true)) continue
                val hint = dep.optString("install_hint", "")
                if (hint.isBlank()) continue
                onProgress("Instalando dependencia: ${dep.optString("id", "?")}…")
                val pb = ProcessBuilder(TERMUX_BASH_PATH, "-c", hint)
                pb.applyTermuxEnv()
                try {
                    val p = pb.start()
                    p.inputStream.bufferedReader().readText()
                    p.errorStream.bufferedReader().readText()
                    p.waitFor(120, java.util.concurrent.TimeUnit.SECONDS)
                    installedAny = true
                } catch (_: Exception) { /* best-effort — se refleja en el re-verify de abajo */ }
            }
            if (installedAny) {
                onProgress("Re-verificando tras instalar dependencias…")
                val (rc2, out2, _) = runModuledeb(listOf("verify", extractDir))
                rcVerify = rc2
                summary = try { JSONObject(out2) } catch (e: Exception) { summary }
            }
        }

        if (rcVerify == 2) {
            val missing = summary?.optJSONArray("dependencies")?.let { arr ->
                (0 until arr.length()).mapNotNull { idx ->
                    arr.optJSONObject(idx)?.takeIf { !it.optBoolean("ok", true) }?.optString("id")
                }
            }?.joinToString(", ") ?: "desconocidas"
            return InstallResult(false, "No se pudieron resolver todas las dependencias ($missing) — instalá el módulo con su script normal")
        }

        if (rcVerify == 1) {
            return InstallResult(true, "Ya estaba instalado y verificado — no hacía falta tocar nada")
        }

        onProgress("Aplicando — copiando archivos e instalando…")
        val (rcApply, outApply, errApply) = runModuledeb(listOf("apply", extractDir), timeoutSeconds = 90)
        return if (rcApply == 0) {
            InstallResult(true, "Instalado desde ${debFile.name}")
        } else {
            InstallResult(false, "Falló al aplicar: ${errApply.ifBlank { outApply }}")
        }
    }

    /** Flujo completo GitHub → descarga → instala. Corre en el hilo que la llama. */
    fun installFromGithub(moduleId: String, onProgress: (String) -> Unit): InstallResult {
        onProgress("Buscando paquete de '$moduleId' en GitHub Releases…")
        val asset = findGithubAsset(moduleId)
            ?: return InstallResult(false, "No hay ningún paquete publicado para '$moduleId' en GitHub todavía — probá la instalación limpia")
        val file = downloadAsset(asset, onProgress)
            ?: return InstallResult(false, "No se pudo descargar ${asset.fileName}")
        return installFromDeb(file, onProgress)
    }
}

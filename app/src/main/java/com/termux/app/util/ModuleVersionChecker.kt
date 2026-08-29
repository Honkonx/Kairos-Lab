package com.termux.app.util

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Chequeo de "¿hay una versión más nueva disponible?" a nivel de MÓDULO individual — pedido
 * explícito del usuario, complemento de RootfsPackageChecker.kt (que solo cubre el rootfs base
 * compartido, ~189 paquetes vía apt). Cada módulo tiene su propio mecanismo de instalación/
 * versión (npm, pip, GitHub Releases, pkg, manifest propio) — NO existe una fuente de verdad
 * única, así que este archivo cubre los módulos donde hay una señal de versión limpia y
 * verificable sin inventar nada, agrupada por MECANISMO de instalación:
 *
 * 1. **npm global** — "npm view <pkg> version" (versión publicada en el registry) y
 *    "npm list -g <pkg> --depth=0" (versión instalada). Confirmado línea por línea contra
 *    modulos (grep de "npm install -g" + su variable *_PKG en los .sh):
 *      freebuff.sh   → FREEBUFF_PKG="freebuff@latest"
 *      codebuff.sh   → CODEBUFF_PKG="codebuff@latest"
 *      mimocode.sh   → MIMOCODE_PKG="@mimo-ai/cli@latest"
 *      minimaxcli.sh → MINIMAX_PKG="mmx-cli@latest"
 *      copilotcli.sh → COPILOT_PKG="@github/copilot@latest"
 *      qwencode.sh   → QWENCODE_PKG="@qwen-code/qwen-code@latest"
 *      codex.sh      → CODEX_PKG="@mmmbuto/codex-cli-termux@latest"
 *
 * 2. **GitHub Releases (binario standalone)** — opencode.sh y antigravity.sh descargan un
 *    binario de releases/latest de un fork propio; la versión REMOTA disponible se consulta
 *    con la GitHub API (mismo endpoint que ya usa opencode.sh, GITHUB_API) y la instalada con
 *    el binario "--version". Ampliado en la ronda 2026-08-14 (humano123, C1) — antes estaban
 *    deliberadamente sin cubrir.
 *
 * 3. **PyPI** — mistralvibe.sh instala vía pip ("$PIP_PYTHON -m pip install mistral-vibe");
 *    versión remota = JSON API de PyPI, instalada = "vibe --version". Ampliado en C1.
 *
 * 4. **claude** — dos variantes (native: binario con manifest.json de downloads.claude.ai,
 *    versión remote = ese manifest con "--version latest"; legacy: npm). El chequeo consulta
 *    el manifest oficial de Claude Code (fuente única de "latest") y compara contra la versión
 *    instalada detectada por claude.sh en el registry. Ampliado en C1.
 *
 * 5. **pkg/apt** — módulos que instalan su runtime principal con "pkg install" (db, entorno,
 *    ide, ciberseguridad, apk): se usa "apt-cache policy <pkg>" que imprime Installed y
 *    Candidate — dos comandos/líneas de apt reales. Ampliado en C1.
 *
 * Deliberadamente NO cubiertos acá (no hay un mecanismo de versión limpio, no se inventa uno):
 *   - ollama.sh: fuera de alcance esta ronda (otro agente trabaja en paralelo sobre Ollama).
 *   - n8n/hermes/remote/python/engram/cactus/...: instalados vía proot-distro/compilación
 *     propia/scripts multi-paquete — ninguno expone un comando de "versión remota disponible"
 *     tan directo. Quedan sin cubrir hasta que alguno lo tenga.
 */
object ModuleVersionChecker {

    private val NPM_PACKAGES = mapOf(
        "freebuff" to "freebuff",
        "codebuff" to "codebuff",
        "mimocode" to "@mimo-ai/cli",
        "minimaxcli" to "mmx-cli",
        "copilotcli" to "@github/copilot",
        "qwencode" to "@qwen-code/qwen-code",
        "codex" to "@mmmbuto/codex-cli-termux",
    )

    /** Módulos npm de arriba que dejaron de ser 100% npm — freebuff.sh (2026-08-28, auditoría
     * freebuff/minimax) agregó 2 métodos preferidos ANTES de npm (runtime Bun nativo Bionic vía
     * bun-termux, y binario nativo ARM64 glibc+patchelf de CodebuffAI) — en aarch64 (la mayoría
     * de los dispositivos reales) freebuff casi nunca termina instalado vía "npm install -g", así
     * que "npm list -g freebuff" reporta "no instalado" con el CLI real funcionando en PATH. El
     * binario "freebuff" en $PREFIX/bin es un wrapper real en los 3 canales (ver freebuff.sh) —
     * "freebuff --version" es una señal de "instalado" válida sin importar el canal, se usa como
     * fallback cuando npm no lo ve. */
    private val NPM_BINARY_FALLBACK = mapOf(
        "freebuff" to "freebuff",
    )

    /** Módulos con binario standalone de GitHub Releases (owner/repo del fork usado en el script). */
    private val GITHUB_RELEASES = mapOf(
        "opencode" to "Honkonx/opencode-termux",
        "antigravity" to "Honkonx/antigravity-cli-termux",
    )

    /** Módulos pip: id → nombre del paquete en PyPI. */
    private val PYPI_PACKAGES = mapOf(
        "mistralvibe" to "mistral-vibe",
    )

    /** Módulos pkg/apt: id → paquete apt principal que define su versión de runtime. */
    private val APT_PACKAGES = mapOf(
        "db" to "mariadb",
        "entorno" to "proot-distro",
        "ide" to "neovim",
        "ciberseguridad" to "nmap",
        "apk" to "aapt2",
    )

    private val NPM_PATH = "$TERMUX_PREFIX_PATH/bin/npm"

    /** Módulos con mecanismo de versión soportado (todas las fuentes) — para la UI. */
    @JvmStatic
    fun supportedModuleIds(): Set<String> =
        NPM_PACKAGES.keys + GITHUB_RELEASES.keys + PYPI_PACKAGES.keys + APT_PACKAGES.keys + setOf("claude")

    data class ModuleVersionStatus(
        val moduleId: String,
        // false = no hay mecanismo de versión automático para este módulo todavía (ver
        // comentario de cabecera) — installedVersion/latestVersion siempre null en ese caso.
        val covered: Boolean,
        val installedVersion: String?,
        val latestVersion: String?,
        val updateAvailable: Boolean,
        val note: String? = null,
    )

    /** Módulos npm-basados soportados por checkModuleVersion() — para que ConfigFragment sepa
     * qué botón/lista mostrar sin duplicar el mapa. */
    @JvmStatic
    fun npmModuleIds(): Set<String> = NPM_PACKAGES.keys

    @JvmStatic
    fun checkModuleVersion(moduleId: String): ModuleVersionStatus {
        return when {
            moduleId in NPM_PACKAGES -> checkNpm(moduleId)
            moduleId in GITHUB_RELEASES -> checkGithub(moduleId)
            moduleId in PYPI_PACKAGES -> checkPypi(moduleId)
            moduleId in APT_PACKAGES -> checkApt(moduleId)
            moduleId == "claude" -> checkClaude()
            else -> ModuleVersionStatus(
                moduleId, covered = false, installedVersion = null, latestVersion = null,
                updateAvailable = false,
                note = "Sin mecanismo de versión automático confirmado para este módulo todavía"
            )
        }
    }

    // ── npm global ──────────────────────────────────────────────────

    private fun checkNpm(moduleId: String): ModuleVersionStatus {
        val pkg = NPM_PACKAGES[moduleId]!!

        // Se parsea la salida de ambos comandos independientemente de su exit code — "npm
        // list -g" puede terminar con exit != 0 por warnings de peer deps ajenos al paquete
        // que nos importa aunque SÍ esté instalado y aparezca en el output; el regex de
        // parseInstalledVersion()/parseLatestVersion() ya descarta texto de error por su
        // cuenta (no matchea "pkg@version" ni empieza con dígito), así que gatear por exit
        // code acá solo perdería instalaciones válidas sin ganar nada en falsos positivos.
        val (_, listOutput) = runNpm("list", "-g", pkg, "--depth=0")
        var installedVersion = parseInstalledVersion(pkg, listOutput)
        var installedViaFallbackBinary = false

        val (_, viewOutput) = runNpm("view", pkg, "version")
        val latestVersion = parseLatestVersion(viewOutput)

        if (installedVersion == null) {
            val fallbackBinary = NPM_BINARY_FALLBACK[moduleId]
            if (fallbackBinary != null) {
                installedVersion = runVersionOf(fallbackBinary)
                installedViaFallbackBinary = installedVersion != null
            }
        }

        if (installedVersion == null) {
            return ModuleVersionStatus(
                moduleId, covered = true, installedVersion = null, latestVersion = latestVersion,
                updateAvailable = false,
                note = "No aparece instalado globalmente vía npm (¿no instalado, o instalado por otro medio?)"
            )
        }
        val fallbackNote = if (installedViaFallbackBinary)
            "instalado detectado por \"$moduleId --version\" (no vía npm — canal nativo, ver freebuff.sh)"
        else null
        if (latestVersion == null) {
            return ModuleVersionStatus(
                moduleId, covered = true, installedVersion = installedVersion, latestVersion = null,
                updateAvailable = false,
                note = fallbackNote ?: "No se pudo consultar la versión remota (¿sin conexión a internet?)"
            )
        }
        return ModuleVersionStatus(
            moduleId, covered = true, installedVersion, latestVersion,
            updateAvailable = installedVersion != latestVersion,
            note = fallbackNote
        )
    }

    // ── GitHub Releases (binario standalone) ────────────────────────

    private fun checkGithub(moduleId: String): ModuleVersionStatus {
        val repo = GITHUB_RELEASES[moduleId]!!
        val latest = fetchGitHubLatest(repo)
        val installed = runVersionOf(binaryOf(moduleId))
        return statusFor(moduleId, installed, latest, "GitHub Releases (${repo.substringAfter("/")})")
    }

    private fun binaryOf(moduleId: String): String = when (moduleId) {
        "opencode" -> "opencode"
        "antigravity" -> "agy"
        else -> moduleId
    }

    // ── PyPI ────────────────────────────────────────────────────────

    private fun checkPypi(moduleId: String): ModuleVersionStatus {
        val pkg = PYPI_PACKAGES[moduleId]!!
        val latest = fetchPypiLatest(pkg)
        val installed = runVersionOf(binaryOf(moduleId))
        return statusFor(moduleId, installed, latest, "PyPI ($pkg)")
    }

    // ── claude (manifest downloads.claude.ai) ───────────────────────

    private fun checkClaude(): ModuleVersionStatus {
        val latest = fetchClaudeLatest()
        // El binario "claude" del wrapper native responde "--version"; la variante legacy
        // comparte el mismo comando, así que la versión instalada detectada es la misma señal
        // que claude.sh escribe en el registry (ver _detect_version en modulos/claude.sh).
        val installed = runVersionOf("claude")
        return statusFor("claude", installed, latest, "manifest downloads.claude.ai")
    }

    // ── pkg/apt (apt-cache policy) ──────────────────────────────────

    private fun checkApt(moduleId: String): ModuleVersionStatus {
        val pkg = APT_PACKAGES[moduleId]!!
        val (_, policy) = runApt("cache", "policy", pkg)
        val installed = parseAptField(policy, "Installed")
        val candidate = parseAptField(policy, "Candidate")
        if (installed == null && candidate == null) {
            return ModuleVersionStatus(
                moduleId, covered = true, installedVersion = null, latestVersion = null,
                updateAvailable = false, note = "apt no reporta el paquete $pkg (¿instalado por otro medio?)"
            )
        }
        if (installed == null) {
            return ModuleVersionStatus(
                moduleId, covered = true, installedVersion = null, latestVersion = candidate,
                updateAvailable = false, note = "apt reporta Candidate pero no Installed para $pkg"
            )
        }
        val hasUpdate = candidate != null && candidate != "(none)" && candidate != installed
        return ModuleVersionStatus(
            moduleId, covered = true, installed, candidate?.takeUnless { it == "(none)" },
            updateAvailable = hasUpdate, note = "paquete apt $pkg"
        )
    }

    // ── helpers compartidos ─────────────────────────────────────────

    /** Instalada (binario --version) vs remota (fuente externa) → estado final. */
    private fun statusFor(moduleId: String, installed: String?, latest: String?, source: String): ModuleVersionStatus {
        if (installed == null) {
            return ModuleVersionStatus(
                moduleId, covered = true, installedVersion = null, latestVersion = latest,
                updateAvailable = false,
                note = "No aparece instalado (¿no instalado, o instalado por otro medio?)"
            )
        }
        if (latest == null) {
            return ModuleVersionStatus(
                moduleId, covered = true, installedVersion = installed, latestVersion = null,
                updateAvailable = false,
                note = "No se pudo consultar la versión remota ($source) — ¿sin conexión a internet?"
            )
        }
        return ModuleVersionStatus(
            moduleId, covered = true, installed, latest,
            updateAvailable = installed != latest
        )
    }

    /** "<binario> --version" → primer X.Y.Z de la salida (mismo parseo que los .sh de modulos
     * usan con grep -oE '[0-9]+\.[0-9]+\.[0-9]+'). El binario debe existir en PATH del Termux. */
    private fun runVersionOf(binary: String): String? {
        val (_, out) = runShell("$TERMUX_PREFIX_PATH/bin/$binary", "--version")
        return Regex("[0-9]+\\.[0-9]+\\.[0-9]+").find(out)?.value
    }

    private fun runShell(vararg args: String, timeoutMs: Long = 20_000): Pair<Boolean, String> {
        return try {
            val pb = ProcessBuilder(mutableListOf(*args))
            pb.applyTermuxEnv()
            pb.redirectErrorStream(true)
            val process = pb.start()
            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                return false to "timeout"
            }
            val output = process.inputStream.bufferedReader().readText()
            (process.exitValue() == 0) to output
        } catch (e: Exception) {
            false to (e.message ?: "error desconocido")
        }
    }

    private fun runApt(vararg args: String, timeoutMs: Long = 25_000): Pair<Boolean, String> {
        return runShell("$TERMUX_PREFIX_PATH/bin/apt", *args, timeoutMs = timeoutMs)
    }

    /** "Installed: 10.11.0" / "Candidate: 10.11.0" de "apt-cache policy <pkg>". */
    private fun parseAptField(output: String, field: String): String? {
        return output.lineSequence()
            .mapNotNull { line ->
                val idx = line.indexOf(field + ":")
                if (idx >= 0) line.substring(idx + field.length + 1).trim().takeIf { it.isNotEmpty() } else null
            }
            .firstOrNull()
    }

    // ── fuentes remotas (HTTP) ──────────────────────────────────────

    private fun fetchGitHubLatest(repo: String): String? {
        return try {
            val json = httpGet("https://api.github.com/repos/$repo/releases/latest", timeoutMs = 12_000)
                ?: return null
            JSONObject(json).optString("tag_name", "").removePrefix("v").takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        }
    }

    private fun fetchPypiLatest(pkg: String): String? {
        return try {
            val json = httpGet("https://pypi.org/pypi/$pkg/json", timeoutMs = 12_000) ?: return null
            JSONObject(json).optJSONObject("info")?.optString("version", "")?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        }
    }

    /** Manifest de releases de Claude Code (misma URL que modulos/claude.sh --version latest). */
    private fun fetchClaudeLatest(): String? {
        return try {
            val json = httpGet("https://downloads.claude.ai/claude-code-releases/latest/manifest.json", timeoutMs = 12_000)
                ?: return null
            JSONObject(json).optString("version", "").takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        }
    }

    private fun httpGet(url: String, timeoutMs: Int): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", "kairos-app/version-check (+https://github.com/Honkonx/kairos-lab)")
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    /** Corre checkModuleVersion() para todos los módulos npm-basados conocidos — pensado para
     * el botón "Verificar actualizaciones de módulos" de ConfigFragment. Secuencial (7 módulos
     * x 2 llamadas npm) — no hay UI que necesite esto en paralelo, y evita saturar de procesos
     * npm concurrentes sobre un dispositivo ARM64 de gama media. */
    @JvmStatic
    fun checkAllNpmModules(): List<ModuleVersionStatus> = NPM_PACKAGES.keys.map { checkModuleVersion(it) }

    /**
     * Versión ampliada del chequeo (ronda 2026-08-14, humano123 C1): "Verificar todas" — cubre
     * npm + GitHub Releases + PyPI + claude + apt. Corre sobre el hilo que lo llame (ConfigFragment
     * usa Thread); secuencial a propósito (varias llamadas HTTP/npm/apt sin saturar el dispositivo).
     */
    @JvmStatic
    fun checkAllModules(): List<ModuleVersionStatus> =
        supportedModuleIds().map { checkModuleVersion(it) }

    /** "pkgname@1.2.3" en la salida de "npm list -g <pkg> --depth=0" — funciona igual para
     * paquetes con scope ("@scope/pkg@1.2.3") porque Regex.escape(pkg) incluye el "@scope/"
     * literal si está presente en el nombre. */
    private fun parseInstalledVersion(pkg: String, output: String): String? {
        val regex = Regex(Regex.escape(pkg) + "@([0-9][\\w.\\-+]*)")
        return regex.find(output)?.groupValues?.get(1)
    }

    /** "npm view <pkg> version" imprime solo el número de versión en su propia línea (sin
     * decoración) cuando el paquete existe — cualquier otra cosa (error, "npm ERR!", vacío) no
     * empieza con un dígito, así que se descarta en vez de mostrarse como si fuera una versión. */
    private fun parseLatestVersion(output: String): String? {
        val trimmed = output.trim().lineSequence().firstOrNull()?.trim()
        return trimmed?.takeIf { it.isNotEmpty() && it.first().isDigit() }
    }

    private fun runNpm(vararg args: String, timeoutMs: Long = 20_000): Pair<Boolean, String> {
        return try {
            val cmd = mutableListOf(NPM_PATH)
            cmd.addAll(args)
            val pb = ProcessBuilder(cmd)
            pb.applyTermuxEnv()
            pb.redirectErrorStream(true)
            val process = pb.start()
            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                return false to "timeout"
            }
            val output = process.inputStream.bufferedReader().readText()
            (process.exitValue() == 0) to output
        } catch (e: Exception) {
            false to (e.message ?: "error desconocido")
        }
    }
}

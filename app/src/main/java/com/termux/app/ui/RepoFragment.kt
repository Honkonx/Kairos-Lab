package com.termux.app.ui

import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.snackbar.Snackbar
import com.termux.R
import com.termux.app.ui.BaseModuleFragment.ButtonStyle.GHOST
import com.termux.app.util.TERMUX_BASH_PATH
import com.termux.app.util.applyTermuxEnv
import com.termux.app.util.friendlyProcessErrorMessage
import com.termux.shared.termux.TermuxConstants
import org.json.JSONArray
import java.io.File
import com.termux.app.util.kairosThemeColor

/**
 * Detalle del módulo `repo` (modulos/repo.sh) — repo apt LOCAL de Kairos
 * ($PREFIX/../kairos-repo) + empaquetado de .deb reales:
 *  - `repo add <módulo_id>`: empaqueta un módulo de Kairos ya instalado
 *    (usado por el flujo de distribución propio, botones INIT/PUBLISH/SOURCE
 *    de acá abajo).
 *  - `repo pack <paquete>` (nuevo, esta pantalla): empaqueta CUALQUIER
 *    paquete apt/pkg ya instalado en el dispositivo como .deb real — no usa
 *    dpkg-repack (no está empaquetado para Termux, confirmado), replica su
 *    mecanismo real: `dpkg -L <paquete>` para los archivos ya en disco +
 *    `dpkg-deb -b` para reconstruir el .deb. Cada pack queda registrado en
 *    ~/kairos_local/repo_registry.json (paquete, versión, fecha, ruta del
 *    .deb) para poder reinstalarlo más adelante sin re-descargar nada.
 *
 * Antes este módulo caía en GenericModuleFragment (sin UI propia, solo el
 * botón genérico "Instalar" del catálogo) — pedido explícito del usuario:
 * agregar la opción REAL de empaquetar paquetes ya instalados.
 */
class RepoFragment : BaseModuleFragment() {

    override fun getModuleId() = "repo"
    override fun getModuleName() = getString(R.string.repo_module_name)

    private lateinit var statusContainer: LinearLayout
    private lateinit var installedContainer: LinearLayout
    private lateinit var generatedContainer: LinearLayout
    private lateinit var moduleDebContainer: LinearLayout

    /** Módulos deliberadamente excluidos de "Crear .deb de un módulo" (2026-08-23, ver
     * docs/humano206.md) — no porque no tengan valor, sino porque empaquetar como archivos
     * portátiles no tiene sentido para ellos: infraestructura completa/estado pesado
     * (entorno, db, stacks, udocker, qemu, docker), contenedores meta de otros módulos sin
     * archivos propios (languages, packages), o el propio mecanismo de empaquetado
     * (repo, moduledeb, apk — apk.sh SÍ tiene describe-files pero es el compilador de
     * APKs en sí, no un módulo típico para reinstalar en otro device). Exclusión por ID, no
     * lista de inclusión — un módulo nuevo aparece acá automáticamente en cuanto lo instala
     * el usuario, sin tocar este archivo, salvo que sea uno de estos casos especiales. */
    private val moduleDebExcludedIds = setOf(
        "entorno", "db", "stacks", "udocker", "qemu", "docker",
        "languages", "packages", "repo", "moduledeb"
    )

    private val scriptPath: String
        get() = File(TermuxConstants.TERMUX_HOME_DIR_PATH, "scripts/install/repo.sh").absolutePath

    private val registryFile: File
        get() = File(TermuxConstants.TERMUX_HOME_DIR_PATH, "kairos_local/repo_registry.json")

    /** $PREFIX/../kairos-repo — mismo REPO_ROOT que calcula repo.sh (TERMUX_PREFIX="$PREFIX",
     * REPO_ROOT="$TERMUX_PREFIX/../kairos-repo"), acá construido directo desde
     * TERMUX_FILES_DIR_PATH ("$FILES_DIR/kairos-repo") para no depender de resolver "..". */
    private val repoRoot: File
        get() = File(TermuxConstants.TERMUX_FILES_DIR_PATH, "kairos-repo")

    private val repoReleaseFile: File
        get() = File(repoRoot, "dists/stable/Release")

    private val repoBinaryDir: File
        get() = File(repoRoot, "dists/stable/main/binary-aarch64")

    private val repoInReleaseFile: File
        get() = File(repoRoot, "dists/stable/InRelease")

    override fun buildContent() {
        if (!isModuleInstalled()) {
            showNotInstalled(getModuleName()) { installModuleInBackground(null) { refreshAll() } }
            return
        }

        addCard(getString(R.string.repo_card_local_repo)) {
            addView(TextView(requireContext()).apply {
                text = getString(R.string.repo_desc_local_repo)
                textSize = 12f
                setTextColor(requireContext().kairosThemeColor(R.attr.kairosText3))
                setPadding(dp(14), dp(8), dp(14), dp(8))
            })
            statusContainer = this
            addLoadingRow(statusContainer)
            actionButton(getString(R.string.repo_btn_init), GHOST) { runScript(listOf("init"), getString(R.string.repo_msg_initializing)) { refreshStatus() } }
            actionButton(getString(R.string.repo_btn_publish), GHOST) { runScript(listOf("publish"), getString(R.string.repo_msg_publishing)) { refreshGenerated(); refreshStatus() } }
            actionButton(getString(R.string.repo_btn_source), GHOST) { showSourceLine() }
        }

        // Firma GPG del repo (opcional, opt-in) — reemplaza [trusted=yes] por
        // signed-by= cuando el usuario firma con una clave PROPIA. Kairos nunca
        // genera la clave privada: si no existe ninguna, se explica cómo crearla
        // en una terminal real (`gpg --full-generate-key`, paso interactivo por
        // diseño — no se puede scriptear sin comprometer el control del usuario
        // sobre su propia clave). Ver docs/arquitectura/
        // AUDITORIA_MODULOS_SISTEMA_SEGURIDAD_VS_OFICIAL_2026-08-19.md sección
        // "Flags/opciones oficiales NO expuestas" → repo.
        addCard(getString(R.string.repo_card_gpg)) {
            addView(TextView(requireContext()).apply {
                text = getString(R.string.repo_desc_gpg)
                textSize = 12f
                setTextColor(requireContext().kairosThemeColor(R.attr.kairosText3))
                setPadding(dp(14), dp(8), dp(14), dp(8))
            })
            actionButton(getString(R.string.repo_btn_sign), GHOST) { startSignFlow() }
        }

        addCard(getString(R.string.repo_card_installed)) {
            installedContainer = this
            addLoadingRow(installedContainer)
        }

        addCard(getString(R.string.repo_card_generated)) {
            generatedContainer = this
            addLoadingRow(generatedContainer)
        }

        // Empaquetado de MÓDULOS de Kairos — generación dinámica (2026-08-23, ver
        // docs/humano206.md y docs/arquitectura/MODULEDEB_GENERICO.md): distinto de "repo
        // pack" de arriba (que empaqueta cualquier paquete apt ya instalado), esto arma un
        // .deb con el contenido YA instalado/parcheado de un módulo real de Kairos + el
        // manifest que sale de `<id>.sh --describe-files` — el post-install usa eso para
        // verificar/parchear en el destino, sin reinstalar todo de cero. Antes esta lista
        // era 3 botones hardcodeados (claude/opencode/n8n, el piloto original); ahora se
        // arma en vivo con TODOS los módulos instalados que tengan sentido empaquetar —
        // se excluyen por ID en vez de armar una lista de inclusión que hay que mantener a
        // mano cada vez que un módulo nuevo suma soporte de --describe-files.
        addCard(getString(R.string.repo_card_create_deb)) {
            addView(TextView(requireContext()).apply {
                text = getString(R.string.repo_desc_create_deb)
                textSize = 12f
                setTextColor(requireContext().kairosThemeColor(R.attr.kairosText3))
                setPadding(dp(14), dp(8), dp(14), dp(8))
            })
            moduleDebContainer = this
            addLoadingRow(moduleDebContainer)
        }
        refreshModuleDebCandidates()

        // Gap real (auditoría de consistencia de menús 2026-08-19): este módulo no tenía
        // NINGÚN botón de Actualizar/Desinstalar (genérico, no confundir con "init/publish/pack"
        // de arriba, específicos de este módulo) desde su propia pantalla — GenericModuleFragment
        // lo da gratis a cualquier módulo sin pantalla propia. Ver BaseModuleFragment.
        // addMaintenanceCard().
        addMaintenanceCard()

        refreshAll()
    }

    private val moduleDebScriptPath: String
        get() = File(TermuxConstants.TERMUX_HOME_DIR_PATH, "scripts/install/moduledeb.sh").absolutePath

    private fun runModuleDebPack(moduleId: String) {
        val v = view ?: return
        Snackbar.make(v, getString(R.string.repo_msg_packaging, moduleId), Snackbar.LENGTH_SHORT).show()
        Thread {
            val result = try {
                val pb = ProcessBuilder(listOf(TERMUX_BASH_PATH, moduleDebScriptPath, "pack", moduleId, "--silent"))
                pb.applyTermuxEnv()
                pb.redirectErrorStream(true)
                val output = pb.start().inputStream.bufferedReader().use { it.readText() }
                output.lines().lastOrNull { it.startsWith("[OK]") || it.startsWith("[ERROR]") } ?: output.lines().lastOrNull { it.isNotBlank() } ?: getString(R.string.repo_no_output)
            } catch (e: Exception) {
                friendlyProcessErrorMessage(e, "moduledeb")
            }
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                Snackbar.make(v, result, Snackbar.LENGTH_LONG).show()
                refreshGenerated()
            }
        }.start()
    }

    /** Arma la lista de botones "Crear .deb de <id>" en vivo — todos los módulos del catálogo
     * que estén instalados en este device y no estén en [moduleDebExcludedIds]. Tocar el botón
     * corre `moduledeb pack <id>`, que a su vez llama `<id>.sh --describe-files` — si ese módulo
     * todavía no implementa el flag, el propio script devuelve un error claro (ver
     * `_moduledeb_resolve_manifest` en moduledeb.sh), mostrado igual que cualquier otro error de
     * pack en el Snackbar de `runModuleDebPack()`. No hace falta que esta lista de Kotlin sepa
     * de antemano cuáles lo soportan — eso es responsabilidad del script, no de la UI. */
    private fun refreshModuleDebCandidates() {
        Thread {
            val ctx = requireContext().applicationContext
            val candidates = try {
                com.termux.app.data.ModuleCatalog.loadBundled(ctx)
                    .filter { !it.internal && it.id !in moduleDebExcludedIds }
                    .filter { com.termux.app.data.ModuleInstalled.isInstalled(ctx, it.id) }
                    .sortedBy { it.name }
            } catch (e: Exception) {
                emptyList()
            }
            runOnMain {
                if (!::moduleDebContainer.isInitialized) return@runOnMain
                moduleDebContainer.removeAllViews()
                if (candidates.isEmpty()) {
                    addStatusRow(moduleDebContainer, getString(R.string.repo_no_modules_available))
                    return@runOnMain
                }
                for (m in candidates) {
                    moduleDebContainer.addView(createActionButton(getString(R.string.repo_btn_create_deb_of, m.name), GHOST) { runModuleDebPack(m.id) })
                }
            }
        }.start()
    }

    private fun refreshAll() {
        refreshStatus()
        refreshInstalledPackages()
        refreshGenerated()
    }

    // ────────────────────────────────────────────────────────────
    // Estado del repo local (inicializado o no, cuántos .deb publicados)
    // ────────────────────────────────────────────────────────────

    private fun refreshStatus() {
        Thread {
            val initialized = try {
                repoReleaseFile.exists()
            } catch (e: Exception) {
                false
            }
            val debCount = try {
                repoBinaryDir.listFiles { f -> f.isFile && f.name.endsWith(".deb") }?.size ?: 0
            } catch (e: Exception) {
                0
            }
            val signed = try {
                repoInReleaseFile.exists()
            } catch (e: Exception) {
                false
            }
            runOnMain {
                if (!::statusContainer.isInitialized) return@runOnMain
                statusContainer.removeAllViews()
                val text = if (initialized) {
                    val signLabel = if (signed) getString(R.string.repo_signed_gpg) else getString(R.string.repo_unsigned)
                    getString(R.string.repo_status_initialized, repoRoot, debCount, signLabel)
                } else {
                    getString(R.string.repo_not_initialized)
                }
                statusContainer.addView(TextView(requireContext()).apply {
                    this.text = text
                    textSize = 12f
                    setTextColor(
                        requireContext().kairosThemeColor(
                            if (initialized) R.attr.kairosText3 else R.attr.kairosAmber
                        )
                    )
                    setPadding(dp(14), dp(6), dp(14), dp(10))
                })
            }
        }.start()
    }

    // ────────────────────────────────────────────────────────────
    // Paquetes instalados (dpkg-query) + botón "Crear .deb" por fila
    // ────────────────────────────────────────────────────────────

    private fun refreshInstalledPackages() {
        Thread {
            val packages = try {
                listInstalledPackages()
            } catch (e: Exception) {
                null
            }
            runOnMain {
                if (!::installedContainer.isInitialized) return@runOnMain
                installedContainer.removeAllViews()
                if (packages == null) {
                    addStatusRow(installedContainer, getString(R.string.repo_error_read_packages))
                    return@runOnMain
                }
                if (packages.isEmpty()) {
                    addStatusRow(installedContainer, getString(R.string.repo_no_installed_packages))
                    return@runOnMain
                }
                for ((i, pkg) in packages.withIndex()) {
                    installedContainer.addView(buildPackageRow(pkg))
                    if (i < packages.size - 1) addDividerRow(installedContainer)
                }
            }
        }.start()
    }

    private data class InstalledPackage(val name: String, val version: String)

    /** `dpkg-query -W` — mismo binario que usa repo.sh internamente para `repo pack`, así la
     * lista que ve el usuario es exactamente lo que `dpkg -s <paquete>` va a aceptar. */
    private fun listInstalledPackages(): List<InstalledPackage> {
        val pb = ProcessBuilder(TERMUX_BASH_PATH, "-c", "dpkg-query -W -f='\${Package}\\t\${Version}\\n' 2>/dev/null")
        pb.applyTermuxEnv()
        pb.redirectErrorStream(false)
        val process = pb.start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor()
        return output.lineSequence()
            .mapNotNull { line ->
                val parts = line.split("\t")
                if (parts.size >= 2 && parts[0].isNotBlank()) InstalledPackage(parts[0], parts[1]) else null
            }
            .sortedBy { it.name }
            .toList()
    }

    private fun buildPackageRow(pkg: InstalledPackage): LinearLayout {
        val ctx = requireContext()
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(ctx).apply {
                    text = pkg.name
                    textSize = 13f
                    setTextColor(ctx.kairosThemeColor(R.attr.kairosText))
                })
                addView(TextView(ctx).apply {
                    text = getString(R.string.repo_version_label, pkg.version)
                    textSize = 11f
                    setTextColor(ctx.kairosThemeColor(R.attr.kairosText3))
                })
            })
            addView(TextView(ctx).apply {
                text = getString(R.string.repo_btn_create_deb_short)
                textSize = 13f
                setTextColor(ctx.kairosThemeColor(R.attr.kairosBlue))
                setPadding(dp(12), dp(6), dp(12), dp(6))
                setOnClickListener { packPackage(pkg.name) }
            })
        }
    }

    private fun packPackage(pkgName: String) {
        runScript(listOf("pack", pkgName), getString(R.string.repo_msg_packaging, pkgName)) { refreshGenerated() }
    }

    // ────────────────────────────────────────────────────────────
    // .deb ya generados (repo_registry.json, escrito por `repo pack`)
    // ────────────────────────────────────────────────────────────

    private fun refreshGenerated() {
        Thread {
            val entries = try {
                readRegistry()
            } catch (e: Exception) {
                null
            }
            runOnMain {
                if (!::generatedContainer.isInitialized) return@runOnMain
                generatedContainer.removeAllViews()
                if (entries == null) {
                    addStatusRow(generatedContainer, getString(R.string.repo_error_read_registry))
                    return@runOnMain
                }
                if (entries.isEmpty()) {
                    addStatusRow(generatedContainer, getString(R.string.repo_no_generated_debs))
                    return@runOnMain
                }
                for ((i, e) in entries.withIndex()) {
                    generatedContainer.addView(buildGeneratedRow(e))
                    if (i < entries.size - 1) addDividerRow(generatedContainer)
                }
            }
        }.start()
    }

    private data class GeneratedDeb(
        val pkg: String,
        val version: String,
        val date: String,
        val debPath: String,
        val scriptsPreserved: Boolean,
    )

    /** Lee $HOME/kairos_local/repo_registry.json directo (mismo archivo que escribe
     * `_repo_registry_upsert` en repo.sh vía jq) — no hace falta pasar por el script para
     * listar, es solo lectura de un archivo JSON plano. */
    private fun readRegistry(): List<GeneratedDeb> {
        val file = registryFile
        if (!file.exists()) return emptyList()
        val text = file.readText()
        if (text.isBlank()) return emptyList()
        val array = JSONArray(text)
        val out = mutableListOf<GeneratedDeb>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            out.add(
                GeneratedDeb(
                    pkg = obj.optString("package", "?"),
                    version = obj.optString("version", "?"),
                    date = obj.optString("date", "?"),
                    debPath = obj.optString("deb_path", ""),
                    // Entradas generadas antes de esta ronda no tienen el campo (repo.sh
                    // viejo) — optBoolean(false) es correcto ahí: esos .deb en efecto NO
                    // preservaron scripts de mantenimiento.
                    scriptsPreserved = obj.optBoolean("scripts_preserved", false),
                )
            )
        }
        return out.sortedByDescending { it.date }
    }

    private fun buildGeneratedRow(entry: GeneratedDeb): LinearLayout {
        val ctx = requireContext()
        val debFile = if (entry.debPath.isNotBlank()) File(entry.debPath) else null
        val sizeLabel = debFile?.takeIf { it.exists() }?.let { formatFileSize(it.length()) } ?: getString(R.string.repo_size_unknown)
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                addView(TextView(ctx).apply {
                    text = "${entry.pkg}  ·  v${entry.version}  ·  $sizeLabel"
                    textSize = 13f
                    setTextColor(ctx.kairosThemeColor(R.attr.kairosText))
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                addView(TextView(ctx).apply {
                    text = getString(R.string.repo_btn_view)
                    textSize = 13f
                    setTextColor(ctx.kairosThemeColor(R.attr.kairosBlue))
                    setPadding(dp(12), dp(6), dp(0), dp(6))
                    setOnClickListener { previewDeb(entry) }
                })
                addView(TextView(ctx).apply {
                    text = getString(R.string.repo_btn_delete)
                    textSize = 13f
                    setTextColor(ctx.kairosThemeColor(R.attr.kairosAmber))
                    setPadding(dp(12), dp(6), dp(0), dp(6))
                    setOnClickListener { confirmRemovePackage(entry.pkg) }
                })
            })
            addView(TextView(ctx).apply {
                text = "${entry.date}  ·  ${entry.debPath}"
                textSize = 11f
                setTextColor(ctx.kairosThemeColor(R.attr.kairosText3))
            })
            addView(TextView(ctx).apply {
                text = if (entry.scriptsPreserved) {
                    getString(R.string.repo_scripts_preserved)
                } else {
                    getString(R.string.repo_scripts_not_preserved)
                }
                textSize = 11f
                setTextColor(
                    ctx.kairosThemeColor(
                        if (entry.scriptsPreserved) R.attr.kairosText3 else R.attr.kairosAmber
                    )
                )
            })
        }
    }

    private fun formatFileSize(bytes: Long): String = when {
        bytes >= 1_048_576 -> getString(R.string.repo_size_mb, bytes / 1_048_576.0)
        bytes >= 1_024 -> getString(R.string.repo_size_kb, bytes / 1_024.0)
        else -> getString(R.string.repo_size_b, bytes)
    }

    private fun confirmRemovePackage(pkgName: String) {
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.repo_confirm_remove_title))
            .setMessage(getString(R.string.repo_confirm_remove_message, pkgName))
            .setPositiveButton(getString(R.string.repo_eliminar)) { _, _ -> removePackage(pkgName) }
            .setNegativeButton(getString(R.string.repo_cancel), null)
            .show()
    }

    private fun removePackage(pkgName: String) {
        runScript(listOf("remove", pkgName), getString(R.string.repo_msg_removing, pkgName)) { refreshGenerated(); refreshStatus() }
    }

    /**
     * `dpkg-deb --info <archivo.deb>` / `dpkg-deb --contents <archivo.deb>` (auditoría 2026-08-19,
     * 3ra ronda — "vista previa de un .deb antes de instalarlo") — comandos oficiales confirmados
     * (Debian `dpkg-deb(1)`): `--info` imprime el control file (paquete/versión/dependencias/
     * mantenedor), `--contents` lista el árbol de archivos que trae el paquete (formato `ls -l`
     * real). Solo lectura — no instala ni modifica nada, corre directo sobre entry.debPath.
     */
    private fun previewDeb(entry: GeneratedDeb) {
        if (entry.debPath.isBlank() || !File(entry.debPath).exists()) {
            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.repo_not_available_title))
                .setMessage(getString(R.string.repo_deb_missing_message, entry.debPath.ifBlank { getString(R.string.repo_unknown_path) }))
                .setPositiveButton(getString(R.string.repo_close), null)
                .show()
            return
        }
        Thread {
            val (infoRc, infoOut, infoErr) = runShellCommand("dpkg-deb --info '${entry.debPath}'")
            val (contentsRc, contentsOut, contentsErr) = runShellCommand("dpkg-deb --contents '${entry.debPath}'")
            val body = buildString {
                append(getString(R.string.repo_preview_info_header)).append("\n")
                append(if (infoRc == 0) infoOut.trim() else (infoErr.ifBlank { infoOut }).ifBlank { getString(R.string.repo_no_output_paren) })
                append("\n\n").append(getString(R.string.repo_preview_contents_header)).append("\n")
                append(if (contentsRc == 0) contentsOut.trim() else (contentsErr.ifBlank { contentsOut }).ifBlank { getString(R.string.repo_no_output_paren) })
            }
            runOnMain { showDebPreviewDialog("${entry.pkg} v${entry.version}", body) }
        }.start()
    }

    private fun runShellCommand(command: String): Triple<Int, String, String> {
        return try {
            val pb = ProcessBuilder(TERMUX_BASH_PATH, "-c", command)
            pb.applyTermuxEnv()
            val process = pb.start()
            val out = process.inputStream.bufferedReader().use { it.readText() }
            val err = process.errorStream.bufferedReader().use { it.readText() }
            val code = process.waitFor()
            Triple(code, out, err)
        } catch (e: Exception) {
            Triple(-1, "", e.message ?: getString(R.string.repo_error_unknown))
        }
    }

    private fun showDebPreviewDialog(title: String, body: String) {
        val ctx = requireContext()
        val text = TextView(ctx).apply {
            text = body
            textSize = 12f
            setTypeface(android.graphics.Typeface.MONOSPACE)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setTextIsSelectable(true)
        }
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle(title)
            .setView(android.widget.ScrollView(ctx).apply { addView(text) })
            .setPositiveButton(getString(R.string.repo_close), null)
            .show()
    }

    // ────────────────────────────────────────────────────────────
    // Ejecución de repo.sh (init/publish/pack) + helpers de UI
    // ────────────────────────────────────────────────────────────

    private fun showSourceLine() {
        Thread {
            val output = try {
                val pb = ProcessBuilder(TERMUX_BASH_PATH, scriptPath, "source", "--silent")
                pb.applyTermuxEnv()
                pb.redirectErrorStream(true)
                pb.start().inputStream.bufferedReader().use { it.readText() }
            } catch (e: Exception) {
                friendlyProcessErrorMessage(e, getString(R.string.repo_module_name))
            }
            runOnMain {
                androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.repo_dialog_source_title))
                    .setMessage(output)
                    .setPositiveButton(getString(R.string.repo_close), null)
                    .show()
            }
        }.start()
    }

    // ────────────────────────────────────────────────────────────
    // Firma GPG (opt-in) — nunca genera una clave, solo lista/usa las que
    // el usuario ya creó por su cuenta. Ver comentario de la card
    // "FIRMA GPG (opcional)" en buildContent() para el razonamiento completo.
    // ────────────────────────────────────────────────────────────

    private data class GpgKey(val id: String, val label: String)

    /** `gpg --list-secret-keys --keyid-format LONG` — solo lectura, no crea ni modifica
     * ninguna clave. Parsea las líneas "sec   <algo>/<KEYID> ..." + el "uid" que sigue
     * para armar una etiqueta legible ("Nombre <email> (KEYID)"). */
    private fun listGpgSecretKeys(): List<GpgKey> {
        val pb = ProcessBuilder(TERMUX_BASH_PATH, "-c", "gpg --list-secret-keys --keyid-format LONG 2>/dev/null")
        pb.applyTermuxEnv()
        pb.redirectErrorStream(false)
        val process = pb.start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor()

        val secRegex = Regex("""^sec\s+\S+/([0-9A-Fa-f]+)""")
        val lines = output.lines()
        val keys = mutableListOf<GpgKey>()
        var i = 0
        while (i < lines.size) {
            val match = secRegex.find(lines[i])
            if (match != null) {
                val keyId = match.groupValues[1]
                var label = keyId
                var j = i + 1
                while (j < lines.size && !lines[j].trimStart().startsWith("uid") && !lines[j].trimStart().startsWith("sec")) {
                    j++
                }
                if (j < lines.size && lines[j].trimStart().startsWith("uid")) {
                    val uidTrimmed = lines[j].trimStart()
                    val uidText = uidTrimmed.substringAfter("]", uidTrimmed.removePrefix("uid")).trim()
                    if (uidText.isNotBlank()) label = "$uidText ($keyId)"
                }
                keys.add(GpgKey(keyId, label))
            }
            i++
        }
        return keys
    }

    private fun startSignFlow() {
        Thread {
            val keys = try {
                listGpgSecretKeys()
            } catch (e: Exception) {
                emptyList()
            }
            runOnMain {
                if (keys.isEmpty()) showNoGpgKeyDialog() else showPickGpgKeyDialog(keys)
            }
        }.start()
    }

    private fun showNoGpgKeyDialog() {
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.repo_gpg_no_key_title))
            .setMessage(getString(R.string.repo_gpg_no_key_message))
            .setPositiveButton(getString(R.string.repo_entendido), null)
            .show()
    }

    private fun showPickGpgKeyDialog(keys: List<GpgKey>) {
        val labels = keys.map { it.label }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.repo_gpg_pick_title))
            .setItems(labels) { _, which -> confirmSign(keys[which]) }
            .setNegativeButton(getString(R.string.repo_cancel), null)
            .show()
    }

    private fun confirmSign(key: GpgKey) {
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.repo_gpg_sign_title))
            .setMessage(getString(R.string.repo_gpg_sign_message, key.label))
            .setPositiveButton(getString(R.string.repo_firmar)) { _, _ -> runScript(listOf("sign", key.id), getString(R.string.repo_msg_signing)) { refreshStatus() } }
            .setNegativeButton(getString(R.string.repo_cancel), null)
            .show()
    }

    private fun runScript(args: List<String>, loadingMsg: String, onDone: (() -> Unit)? = null) {
        val v = view ?: return
        Snackbar.make(v, loadingMsg, Snackbar.LENGTH_SHORT).show()
        Thread {
            val result = try {
                val pb = ProcessBuilder(listOf(TERMUX_BASH_PATH, scriptPath) + args + "--silent")
                pb.applyTermuxEnv()
                pb.redirectErrorStream(true)
                val output = pb.start().inputStream.bufferedReader().use { it.readText() }
                output.lines().lastOrNull { it.startsWith("[OK]") || it.startsWith("[ERROR]") } ?: output.lines().lastOrNull { it.isNotBlank() } ?: getString(R.string.repo_no_output)
            } catch (e: Exception) {
                friendlyProcessErrorMessage(e, getString(R.string.repo_module_name))
            }
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                Snackbar.make(v, result, Snackbar.LENGTH_LONG).show()
                onDone?.invoke()
            }
        }.start()
    }

    private fun runOnMain(block: () -> Unit) {
        if (!isAdded) return
        activity?.runOnUiThread { if (isAdded) block() }
    }

    private fun addLoadingRow(target: LinearLayout) {
        target.addView(TextView(requireContext()).apply {
            text = getString(R.string.repo_loading)
            textSize = 13f
            setTextColor(requireContext().kairosThemeColor(R.attr.kairosText3))
            setPadding(dp(14), dp(12), dp(14), dp(12))
        })
    }

    private fun addStatusRow(target: LinearLayout, text: String) {
        target.addView(TextView(requireContext()).apply {
            this.text = text
            textSize = 13f
            setTextColor(requireContext().kairosThemeColor(R.attr.kairosText3))
            setPadding(dp(14), dp(12), dp(14), dp(12))
        })
    }

    private fun addDividerRow(target: LinearLayout) {
        target.addView(android.view.View(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).also {
                it.leftMargin = dp(14); it.rightMargin = dp(14)
            }
            setBackgroundColor(requireContext().kairosThemeColor(R.attr.kairosBorder))
        })
    }
}

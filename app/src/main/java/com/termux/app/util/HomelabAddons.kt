package com.termux.app.util

import com.termux.shared.termux.TermuxConstants
import java.io.File

/**
 * Complementos de Homelab (orden explícita del usuario 2026-10-08, mensaje 6:
 * "syncthing pasa de ser módulo a complemento de homelab [...] restic igual"). Antes eran los
 * módulos `syncthing` y `restic` (scripts bash retirados del catálogo); ahora son complementos
 * sobre el motor común [AddonEngine] que se muestran en Homelab → Servicios → Complementos.
 *
 * - **Syncthing**: daemon con interfaz web en 127.0.0.1:8384 (mismo comando que usaba el antiguo
 *   modulos/syncthing.sh). Solo escucha en loopback.
 * - **Restic**: CLI sin servicio. Las operaciones reciben la contraseña del repositorio por la
 *   variable de entorno RESTIC_PASSWORD de ESE proceso — nunca se guarda ni se muestra
 *   (.claude/rules/kairos-secrets-never-revealed.md).
 */
object HomelabAddons {

    const val SYNCTHING_PORT = 8384
    const val SYNCTHING_URL = "http://127.0.0.1:$SYNCTHING_PORT"

    private const val SYNCTHING_BIN = "$TERMUX_PREFIX_PATH/bin/syncthing"
    private const val RESTIC_BIN = "$TERMUX_PREFIX_PATH/bin/restic"

    val SYNCTHING = AddonEngine.Spec(
        id = "syncthing",
        pkg = "syncthing",
        bin = SYNCTHING_BIN,
        service = AddonEngine.Service(
            session = "syncthing-server",
            port = SYNCTHING_PORT,
            command = listOf(SYNCTHING_BIN, "serve", "--no-browser", "--no-restart", "--gui-address=127.0.0.1:$SYNCTHING_PORT"),
            logName = "syncthing_serve.log"
        )
    )

    // ── Syncthing: usuario y contraseña de su interfaz web ────────────────────────────────────
    // Syncthing arranca sin credenciales (cualquier app del teléfono podría abrir 127.0.0.1:8384).
    // `syncthing generate --gui-user=U --gui-password=-` escribe el hash en su config.xml leyendo la
    // contraseña por stdin: nunca queda en argv. Kairos guarda SOLO el nombre de usuario (para
    // mostrarlo); la contraseña no se guarda ni se vuelve a mostrar
    // (.claude/rules/kairos-secrets-never-revealed.md). Pedido del usuario:.
    private val guiUserFile get() = File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".kairos_syncthing_gui_user")

    fun syncthingGuiUser(): String? = try { guiUserFile.takeIf { it.isFile }?.readText()?.trim()?.ifEmpty { null } } catch (_: Exception) { null }

    internal fun validateSyncthingCredentials(user: String, password: String): String? = when {
        !Regex("^[A-Za-z0-9._-]{3,32}$").matches(user) -> "El usuario debe tener 3-32 caracteres (letras, números, . _ -)"
        password.length < 8 -> "La contraseña debe tener al menos 8 caracteres"
        password.any { it == '\n' || it == '\r' } -> "La contraseña no puede tener saltos de línea"
        else -> null
    }

    /** Aplica usuario+contraseña. Si Syncthing estaba corriendo se detiene para escribir su config y se reinicia. */
    fun syncthingSetCredentials(user: String, password: String): AddonEngine.Result {
        validateSyncthingCredentials(user, password)?.let { return AddonEngine.Result(false, it) }
        if (!AddonEngine.isInstalled(SYNCTHING)) return AddonEngine.Result(false, "Syncthing no está instalado")
        val wasRunning = AddonEngine.isRunning(SYNCTHING)
        if (wasRunning) AddonEngine.stop(SYNCTHING)
        val (rc, out, err) = ManagerNativeUtils.runExecWithStdin(
            listOf(SYNCTHING_BIN, "generate", "--gui-user=$user", "--gui-password=-"), password + "\n", 60
        )
        if (rc != 0) {
            if (wasRunning) AddonEngine.start(SYNCTHING)
            return AddonEngine.Result(false, AddonEngine.lastLines(err.ifBlank { out }))
        }
        try { guiUserFile.writeText(user) } catch (_: Exception) { }
        if (wasRunning) AddonEngine.start(SYNCTHING)
        return AddonEngine.Result(true, "Credenciales aplicadas")
    }

    /** Quita la protección de la interfaz (solo borra el nombre guardado; para dejarla sin contraseña hay que generar una nueva). */
    fun syncthingForgetUser() { try { guiUserFile.delete() } catch (_: Exception) { } }

    // `restic version` (subcomando), no `--version`: así lo documenta restic.
    val RESTIC = AddonEngine.Spec(
        id = "restic",
        pkg = "restic",
        bin = RESTIC_BIN,
        versionArgs = listOf("version")
    )

    // ── Restic: operaciones reales de la CLI (`restic -r <repo> init|backup|snapshots`) ──────────

    fun resticRepoExists(repo: String): Boolean = File(repo, "config").isFile

    /** Crea el repositorio si todavía no existe y respalda [folder]. */
    fun resticBackup(repo: String, folder: String, password: String): AddonEngine.Result {
        val check = validateResticInput(repo, password) ?: validateFolder(folder)
        if (check != null) return AddonEngine.Result(false, check)
        if (!resticRepoExists(repo)) {
            val init = runRestic(listOf("-r", repo, "init"), password, 60)
            if (!init.ok) return AddonEngine.Result(false, "No se pudo crear el repositorio: ${init.message}")
        }
        return runRestic(listOf("-r", repo, "backup", folder), password, 3600)
    }

    fun resticSnapshots(repo: String, password: String): AddonEngine.Result {
        validateResticInput(repo, password)?.let { return AddonEngine.Result(false, it) }
        if (!resticRepoExists(repo)) return AddonEngine.Result(false, "No existe un repositorio restic en $repo")
        return runRestic(listOf("-r", repo, "snapshots"), password, 120)
    }

    internal fun validateResticInput(repo: String, password: String): String? = when {
        repo.isBlank() -> "Indica la carpeta del repositorio"
        !repo.startsWith("/") -> "El repositorio debe ser una ruta absoluta"
        password.isEmpty() -> "Indica la contraseña del repositorio"
        else -> null
    }

    private fun validateFolder(folder: String): String? = when {
        folder.isBlank() -> "Indica la carpeta a respaldar"
        !File(folder).isDirectory -> "La carpeta a respaldar no existe: $folder"
        else -> null
    }

    private fun runRestic(args: List<String>, password: String, timeoutSeconds: Long): AddonEngine.Result {
        if (!AddonEngine.isInstalled(RESTIC)) return AddonEngine.Result(false, "restic no está instalado")
        val (rc, out, err) = ManagerNativeUtils.runExecWithEnv(
            listOf(RESTIC_BIN) + args, mapOf("RESTIC_PASSWORD" to password), timeoutSeconds
        )
        val text = out.ifBlank { err }.takeLast(2000)
        return AddonEngine.Result(rc == 0, if (rc == 0) text else "código $rc — ${AddonEngine.lastLines(err.ifBlank { out }, 4)}")
    }
}

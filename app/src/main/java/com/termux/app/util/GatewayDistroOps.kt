package com.termux.app.util

/** Una distro de proot-distro y si está instalada. */
data class DistroInfo(val alias: String, val name: String, val installed: Boolean)

/** Una imagen de udocker (`udocker images`). */
data class ImageInfo(val name: String)

/**
 * Distros (proot-distro) e imágenes (udocker) para el gateway. Instalar y abrir una terminal dentro de una distro se hace
 * como sesión tmux (`kx-…`), así la persona la ve y la maneja desde la pestaña Terminales. Los nombres se validan y viajan
 * como argumentos sueltos, nunca interpolados en una línea de shell.
 */
class DistroManager(
    private val runner: CommandRunner = ShellCommandRunner,
    private val sessions: SessionManager = SessionManager(runner),
    private val bin: String = com.termux.shared.termux.TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH,
) {

    /** Lista de `proot-distro list`: bloques con «Alias:» e «Installed:». Vacía si el programa no está o no responde. */
    fun list(): List<DistroInfo> {
        val (code, out) = runner.run(listOf("proot-distro", "list"), 15)
        return if (code == 0) parseDistros(out) else emptyList()
    }

    fun open(alias: String): PrivilegeResult {
        if (!ALIAS.matches(alias)) return PrivilegeResult(false, "distro inválida", 400)
        return sessions.open("d-" + alias.take(20), "$bin/proot-distro login $alias")
    }

    fun install(alias: String): PrivilegeResult {
        if (!ALIAS.matches(alias)) return PrivilegeResult(false, "distro inválida", 400)
        return sessions.open("i-" + alias.take(20), "$bin/proot-distro install $alias")
    }

    fun images(): List<ImageInfo> {
        val (code, out) = runner.run(listOf("udocker", "images"), 15)
        return if (code == 0) parseImages(out) else emptyList()
    }

    /** Terminal dentro de una imagen ya descargada de udocker (`udocker run --rm -it <imagen>`). */
    fun openImage(image: String): PrivilegeResult {
        if (!IMAGE.matches(image)) return PrivilegeResult(false, "imagen inválida", 400)
        val name = "u-" + image.substringAfterLast('/').substringBefore(':').lowercase().replace(Regex("[^a-z0-9_-]"), "-").take(20)
        return sessions.open(name, "$bin/udocker run --rm -it $image")
    }

    /**
     * Corre un comando DENTRO de una distro sin abrir sesión de terminal — para instalar un
     * paquete/programa y verificarlo de una ("¿quedó instalado?") desde el gateway, en vez de
     * tener que abrir Terminales y leer la salida a mano. `proot-distro login <alias>
     * --shared-tmp --shared-home -- <comando>` es el mismo patrón real ya usado en
     * `modulos/entorno.sh` (confirmado por grep, no inventado) — nunca a través de `sh -c` con el
     * comando interpolado en la línea de shell del HOST: se pasa como argumentos sueltos al
     * `ProcessBuilder` de [runner], y DENTRO de la distro se ejecuta vía `sh -c` (ahí sí hace falta,
     * es la forma en que `proot-distro login -- CMD` espera recibir un comando compuesto).
     */
    /**
     * [sandbox]=true (2026-10-16, ver meta 9) usa `--isolated` en vez de
     * `--shared-tmp --shared-home` — hallazgo de una ronda anterior (``:
     * "`proot-distro login --isolated` YA restringe la vista de filesystem sin necesitar ningún
     * mecanismo nuevo") que esta ronda NO pudo re-verificar empíricamente (sin dispositivo
     * conectado, sin el código fuente de proot-distro a mano para confirmarlo a ojo) — si el
     * flag no existiera de verdad, el comando fallaría fuerte y visible ("unrecognized option"),
     * nunca en silencio con un falso "aislado". `--shared-tmp --shared-home` (el modo normal, NO
     * sandbox) comparte a propósito el HOME/tmp del host con la distro, por conveniencia de UX de
     * un solo usuario — lo opuesto de aislar, por eso son mutuamente excluyentes.
     */
    fun run(alias: String, command: String, timeoutSec: Long = 60, sandbox: Boolean = false): PrivilegeResult {
        if (!ALIAS.matches(alias)) return PrivilegeResult(false, "distro inválida", 400)
        if (command.isBlank()) return PrivilegeResult(false, "comando vacío", 400)
        val modeArgs = if (sandbox) listOf("--isolated") else listOf("--shared-tmp", "--shared-home")
        val (code, out) = runner.run(
            listOf("$bin/proot-distro", "login", alias) + modeArgs + listOf("--", "sh", "-c", command),
            timeoutSec
        )
        return PrivilegeResult(code == 0, out, if (code == 0) 200 else 500)
    }

    /**
     * Igual que [run] pero dentro de una imagen de udocker (`udocker run <imagen> sh -c
     * <comando>`, sin `-it` — mismo patrón ya usado por `modulos/stacks.sh`, confirmado por grep).
     * El contenedor es efímero por diseño de udocker (sin `--rm` explícito tampoco hace falta:
     * udocker no deja el contenedor "corriendo" después de que el comando termina, a diferencia
     * de Docker real) — cada llamada arranca de la imagen base, no acumula estado entre llamadas.
     */
    fun runImage(image: String, command: String, timeoutSec: Long = 60): PrivilegeResult {
        if (!IMAGE.matches(image)) return PrivilegeResult(false, "imagen inválida", 400)
        if (command.isBlank()) return PrivilegeResult(false, "comando vacío", 400)
        val (code, out) = runner.run(listOf("$bin/udocker", "run", image, "sh", "-c", command), timeoutSec)
        return PrivilegeResult(code == 0, out, if (code == 0) 200 else 500)
    }

    companion object {
        val ALIAS = Regex("[a-z0-9][a-z0-9._-]{0,29}")
        val IMAGE = Regex("[A-Za-z0-9][A-Za-z0-9._/:@-]{0,79}")

        fun parseDistros(text: String): List<DistroInfo> {
            val result = mutableListOf<DistroInfo>()
            var title = ""
            var alias = ""
            fun flush(installed: Boolean) { if (alias.isNotEmpty()) result += DistroInfo(alias, title.ifEmpty { alias }, installed) }
            var pendingInstalled = false
            for (raw in text.lineSequence()) {
                val line = raw.trim()
                when {
                    line.startsWith("*") -> { flush(pendingInstalled); title = line.removePrefix("*").trim(); alias = ""; pendingInstalled = false }
                    line.startsWith("Alias:", true) -> alias = line.substringAfter(':').trim()
                    line.startsWith("Installed:", true) -> pendingInstalled = line.substringAfter(':').trim().startsWith("yes", true)
                }
            }
            flush(pendingInstalled)
            return result
        }

        /** Líneas de `udocker images`: primera columna = nombre de imagen; se ignora la cabecera. */
        fun parseImages(text: String): List<ImageInfo> =
            text.lineSequence().map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("REPOSITORY", true) && !it.startsWith("Info", true) }
                .map { it.split(Regex("\\s+"))[0] }
                .filter { IMAGE.matches(it) }
                .map(::ImageInfo).toList()
    }
}

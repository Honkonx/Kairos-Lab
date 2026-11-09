package com.termux.app.util

import java.io.File

/**
 * Detecta qué comando corre en primer plano dentro de una sesión de terminal, sin root, leyendo
 * `/proc/<pid>/stat` — mecanismo confirmado de forma independiente por 3 proyectos de referencia
 * (`stdusk`, `ttyx_`, `tilix-next`; ver `docs/referencias/terminal/AUDITORIA_CATEGORIA_TERMINAL.md`,
 * sección "detección de proceso en foreground vía /proc/<pid>/stat").
 *
 * Kairos abre cada sesión TUI adaptada (Claude Code, Codex, OpenCode, ...) como un shell bash que
 * hace `setsid()` (termux.c) y luego ejecuta el CLI real como comando —
 * `TerminalSession.getPid()` da el PID de ESE shell, no del CLI. Mientras el CLI corre en primer
 * plano, bash lo mueve a su propio grupo de procesos (mismo mecanismo que
 * `TermuxActivity.killSessionProcessGroup` ya documenta, ver comentario de) y
 * actualiza el `tpgid` del tty al pgid de ese grupo — leer ese campo directo de
 * `/proc/<shellPid>/stat` evita tener que parsear el output de texto de la terminal o
 * instrumentar el shell.
 */
object ForegroundProcessDetector {

    /**
     * PID (no necesariamente el mismo que shellPid) y línea de comando completa del proceso en
     * primer plano de la sesión cuyo shell líder es shellPid.
     */
    data class ForegroundProcess(val pid: Int, val commandLine: String)

    /**
     * null si no se pudo determinar (proceso ya murió, /proc no legible) o si el shell mismo es
     * el grupo en foreground — el usuario está en el prompt, sin ningún comando corriendo.
     */
    fun detect(shellPid: Int): ForegroundProcess? {
        if (shellPid <= 0) return null
        val shellFields = parseStatFields(shellPid) ?: return null
        val tpgid = shellFields.tpgid
        // El shell es líder de su propio grupo (setsid() en termux.c, pgid == pid) — si el tty
        // sigue apuntando al grupo del shell, no hay ningún job en primer plano ahora mismo.
        if (tpgid == shellPid) return null

        // Cualquier proceso vivo cuyo propio pgrp coincida con tpgid pertenece al grupo en
        // primer plano — se toma el primero que aparezca (típicamente el líder del job, el
        // binario real del CLI), mismo criterio que ttyx_/activeprocess.d:getForegroundProcess()
        // y tilix-next/terminal/activeprocess.d:isForeground() (ver auditoría citada arriba).
        val procDir = File("/proc")
        val candidates = procDir.listFiles { f -> f.isDirectory && f.name.toIntOrNull() != null }
            ?: return null
        for (dir in candidates) {
            val pid = dir.name.toIntOrNull() ?: continue
            val fields = parseStatFields(pid) ?: continue
            if (fields.pgrp == tpgid) {
                val cmdline = readCmdline(pid) ?: fields.comm
                return ForegroundProcess(pid, cmdline)
            }
        }
        return null
    }

    /**
     * moduleId real (modules.json) del CLI de IA en primer plano según el binario/argv completo —
     * null si no coincide con ningún CLI conocido. Compara contra el argv COMPLETO (no solo el
     * nombre del binario en `comm`, que Linux trunca a 15 caracteres), mismo criterio que
     * `stdusk/src/procwatch.rs:classify()` citado en la auditoría — cubre el caso real
     * `node /data/.../claude-code/cli.js` (comm trunca a "node", pero el path completo sigue
     * diciendo "claude").
     */
    fun classifyAiModule(commandLine: String): String? {
        // Fix de falso positivo real (auditoría referencia/terminal/, 2026-11-02,
        //): `contains` sobre la línea completa marcaba comandos que solo
        // MENCIONAN el nombre como si el CLI estuviera corriendo (ej. "cat codex-notas.md" se
        // detectaba como Codex). Comparar por segmento de ruta exacto, mismo criterio que
        // stdusk/src/procwatch.rs:classify() (ver cita de arriba) — solo matchea el nombre del
        // binario o un directorio real del paquete (ej. ".../claude-code/cli.js"), nunca texto
        // arbitrario de un argumento.
        for (token in commandLine.split(Regex("\\s+"))) {
            for (segment in token.split('/')) {
                val seg = segment.lowercase()
                when {
                    seg == "claude" || seg == "claude-code" -> return "claude"
                    seg == "codex" -> return "codex"
                    seg == "opencode" -> return "opencode"
                }
            }
        }
        return null
    }

    /** Atajo: PID del shell líder de la sesión -> moduleId de IA en foreground, o null. */
    fun detectForegroundAiModule(shellPid: Int): String? {
        val fg = detect(shellPid) ?: return null
        return classifyAiModule(fg.commandLine)
    }

    /** Herramientas de acceso remoto reconocidas — hallazgo real de
     *  `docs/referencias/terminal/REFERENCIA_TTYX.md` (indicador SSH de `ttyx_`). */
    private val SSH_TOOL_NAMES = setOf("ssh", "scp", "sftp", "mosh", "mosh-client", "sshfs")

    /** Indicador visual de root/SSH (hallazgo real de `referencia/terminal/ttyx_`,
     *  `docs/referencias/terminal/REFERENCIA_TTYX.md`). SSH tiene precedencia sobre root —
     *  mismo criterio que el proyecto de origen: recordar en qué host se está, antes que
     *  recordar que se es root en ESE host. */
    enum class SessionIndicator { ROOT, SSH }

    /** Atajo: PID del shell líder de la sesión -> indicador de seguridad visible, o null si
     *  ninguno aplica (ni SSH ni root en el proceso en primer plano). */
    fun detectSessionIndicator(shellPid: Int): SessionIndicator? {
        val fg = detect(shellPid) ?: return null
        if (isSshTool(fg.commandLine)) return SessionIndicator.SSH
        if (isRootProcess(fg.pid)) return SessionIndicator.ROOT
        return null
    }

    /** Mismo criterio de comparación por segmento de ruta exacto que [classifyAiModule] —
     *  evita falsos positivos tipo "cat notas-de-ssh.txt". */
    private fun isSshTool(commandLine: String): Boolean {
        for (token in commandLine.split(Regex("\\s+"))) {
            for (segment in token.split('/')) {
                if (segment.lowercase() in SSH_TOOL_NAMES) return true
            }
        }
        return false
    }

    /** UID EFECTIVO real de [pid] vía `/proc/<pid>/status` (línea "Uid:\t<real>\t<efectivo>\t...",
     *  el 2º campo es el EUID real del kernel — no falsificable desde el propio proceso). No
     *  verificado en dispositivo si un proceso dentro de proot sin root real del host puede
     *  reportar EUID 0 igual (usuario simulado dentro del chroot) — si eso pasa, el indicador
     *  sería un falso positivo real; documentado como pendiente de confirmar en
     *  `docs/mini-pc/PLAN_MEJORAS_MINIPC_AUDITORIA_REFERENCIA_2026-11-02.md`. */
    private fun isRootProcess(pid: Int): Boolean {
        val raw = try {
            File("/proc/$pid/status").readText()
        } catch (_: Exception) {
            return false
        }
        val line = raw.lineSequence().firstOrNull { it.startsWith("Uid:") } ?: return false
        val euid = line.split(Regex("\\s+")).getOrNull(2)?.toIntOrNull() ?: return false
        return euid == 0
    }

    private data class StatFields(val comm: String, val pgrp: Int, val tpgid: Int)

    /**
     * `/proc/<pid>/stat`: "pid (comm) state ppid pgrp session tty_nr tpgid ...". El campo `comm`
     * puede contener espacios y paréntesis (nombres de proceso raros) — se busca el ÚLTIMO ')'
     * para no cortar el parseo si `comm` trae un '(' propio, patrón estándar para este archivo.
     */
    private fun parseStatFields(pid: Int): StatFields? {
        val raw = try {
            File("/proc/$pid/stat").readText()
        } catch (_: Exception) {
            return null
        }
        val openParen = raw.indexOf('(')
        val closeParen = raw.lastIndexOf(')')
        if (openParen < 0 || closeParen < 0 || closeParen <= openParen) return null
        val comm = raw.substring(openParen + 1, closeParen)
        val statTail = raw.substring(closeParen + 1).trim()
        val rest = statTail.split(Regex("\\s+"))
        // rest[0]=state rest[1]=ppid rest[2]=pgrp rest[3]=session rest[4]=tty_nr rest[5]=tpgid
        if (rest.size < 6) return null
        val pgrp = rest[2].toIntOrNull() ?: return null
        val tpgid = rest[5].toIntOrNull() ?: return null
        return StatFields(comm, pgrp, tpgid)
    }

    /**
     * /proc/<pid>/cmdline separa argv[] con bytes NUL (código de carácter 0), no espacios — se
     * reconstruye el separador a partir de su código de carácter (Char(0), no un literal NUL
     * tipeado en el fuente) para no arriesgar corrupción de encoding al guardar/leer este
     * archivo (ver .claude/skills/kairos-windows-encoding).
     */
    private fun readCmdline(pid: Int): String? {
        return try {
            val raw = File("/proc/$pid/cmdline").readBytes()
            if (raw.isEmpty()) return null
            val separator = Char(0)
            raw.toString(Charsets.UTF_8)
                .split(separator)
                .filter { it.isNotEmpty() }
                .joinToString(" ")
        } catch (_: Exception) {
            null
        }
    }
}

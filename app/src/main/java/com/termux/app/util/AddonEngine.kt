package com.termux.app.util

import com.termux.shared.termux.TermuxConstants
import java.io.File

/**
 * Motor común de los "complementos" de Kairos (ver docs/arquitectura/PLAN_COMPLEMENTOS_2026-10-08.md):
 * capacidades que otra pantalla invoca (Tor desde Ciberseguridad, Syncthing y Restic desde Homelab)
 * y que NO son módulos del catálogo. Cada complemento se describe con un [Spec] y este motor hace lo
 * repetitivo: instalar con `pkg` verificando que el binario de verdad ejecuta, consultar estado,
 * y arrancar/detener el daemon en una sesión tmux cuando el complemento es un servicio.
 *
 * Sin estado propio: el estado real se consulta siempre (binario ejecutable, puerto abierto), nunca
 * se confía en un flag guardado — ver .claude/rules/empirical-verification-before-fix.md.
 */
object AddonEngine {

    /** Daemon de un complemento: [command] es el argv completo (binario incluido). */
    data class Service(val session: String, val port: Int, val command: List<String>, val logName: String)

    /** [bin] = ruta absoluta al ejecutable; [versionArgs] = cómo pedirle la versión de verdad. */
    data class Spec(
        val id: String,
        val pkg: String,
        val bin: String,
        val versionArgs: List<String> = listOf("--version"),
        val service: Service? = null
    )

    data class Status(val installed: Boolean, val running: Boolean, val version: String?)
    data class Result(val ok: Boolean, val message: String)

    fun isInstalled(spec: Spec): Boolean = File(spec.bin).canExecute()

    /**
     * "Corriendo" = el puerto responde Y la sesión tmux es la nuestra. Solo mirar el puerto
     * permitía que otro proceso local que ocupara 9050/8384 pasara por el complemento (hallazgo
     * del revisor de seguridad, 2026-10-08: suplantación de servicio). Un daemon lanzado a mano
     * desde una terminal cuenta como "ocupado por otro proceso" (ver [isPortTakenByOther]).
     */
    fun isRunning(spec: Spec): Boolean =
        spec.service?.let { ManagerNativeUtils.checkPort(it.port) && ManagerNativeUtils.tmuxHas(it.session) } ?: false

    /** true si el puerto del servicio responde pero NO lo lanzó Kairos (sin nuestra sesión tmux). */
    fun isPortTakenByOther(spec: Spec): Boolean =
        spec.service?.let { ManagerNativeUtils.checkPort(it.port) && !ManagerNativeUtils.tmuxHas(it.session) } ?: false

    /** Versión real del binario (corre el ejecutable), o null si no existe o no ejecuta. */
    fun version(spec: Spec): String? {
        if (!isInstalled(spec)) return null
        val (rc, out, _) = ManagerNativeUtils.runExec(listOf(spec.bin) + spec.versionArgs, 10)
        return if (rc == 0) out.lineSequence().firstOrNull { it.isNotBlank() }?.trim() else null
    }

    fun status(spec: Spec): Status = Status(isInstalled(spec), isRunning(spec), version(spec))

    /** `pkg install -y <pkg>` y verificación real de la post-condición (el binario corre). */
    fun install(spec: Spec): Result {
        val (rc, out, err) = ManagerNativeUtils.runExec(listOf(TERMUX_PKG_PATH, "install", "-y", spec.pkg), 900)
        if (rc != 0) return Result(false, lastLines(err.ifBlank { out }))
        val v = version(spec) ?: return Result(false, "El paquete ${spec.pkg} se instaló pero ${File(spec.bin).name} no ejecuta")
        return Result(true, v)
    }

    fun start(spec: Spec, timeoutSeconds: Int = 40): Result {
        val service = spec.service ?: return Result(false, "${spec.id} no es un servicio")
        if (!isInstalled(spec)) return Result(false, "${spec.id} no está instalado")
        if (isRunning(spec)) return Result(true, "${spec.id} ya estaba corriendo")
        if (isPortTakenByOther(spec)) return Result(false, "El puerto ${service.port} ya lo usa otro proceso que no inició Kairos")
        val log = File(TermuxConstants.TERMUX_HOME_DIR_PATH, "kairos_logs/${service.logName}")
        log.parentFile?.mkdirs()
        // Sesión vieja sin puerto abierto = proceso muerto que dejó la sesión; se limpia antes.
        if (ManagerNativeUtils.tmuxHas(service.session)) {
            ManagerNativeUtils.runExec(listOf(TERMUX_TMUX_PATH, "kill-session", "-t", service.session), 10)
        }
        val command = "exec ${service.command.joinToString(" ") { shellQuote(it) }} > ${shellQuote(log.absolutePath)} 2>&1"
        val (rc, _, err) = ManagerNativeUtils.runExec(
            listOf(TERMUX_TMUX_PATH, "new-session", "-d", "-s", service.session, command), 15
        )
        if (rc != 0) return Result(false, "tmux no pudo crear la sesión: ${lastLines(err)}")
        repeat(timeoutSeconds) {
            if (isRunning(spec)) return Result(true, "${spec.id} escuchando en el puerto ${service.port}")
            Thread.sleep(1000)
        }
        val tail = try { lastLines(log.readText()) } catch (_: Exception) { "sin log" }
        return Result(false, "${spec.id} no abrió el puerto ${service.port} en $timeoutSeconds s — $tail")
    }

    fun stop(spec: Spec): Result {
        val service = spec.service ?: return Result(false, "${spec.id} no es un servicio")
        ManagerNativeUtils.runExec(listOf(TERMUX_TMUX_PATH, "kill-session", "-t", service.session), 10)
        repeat(10) {
            if (!isRunning(spec)) return Result(true, "${spec.id} detenido")
            Thread.sleep(500)
        }
        return Result(false, "El puerto ${service.port} sigue abierto (¿lo lanzó otra sesión?)")
    }

    internal fun lastLines(text: String, n: Int = 3): String =
        text.trim().lines().takeLast(n).joinToString(" | ").take(300)
}

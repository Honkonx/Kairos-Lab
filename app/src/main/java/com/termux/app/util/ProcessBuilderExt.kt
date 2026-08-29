package com.termux.app.util

import com.termux.shared.termux.TermuxConstants
import java.io.File

// Rutas absolutas de los binarios de Termux más sensibles al orden/timing del bootstrap.
// Bug real reportado (ver docs/humano/humano62.md): "Cannot run program 'apt'"/"Cannot run
// program 'bash'" seguían pasando en el paso de instalación del wizard AUNQUE
// applyTermuxEnv() ya estaba aplicado — la resolución de PATH vía nombre relativo funciona
// para el resto de la app (confirmado en docenas de instalaciones de módulos), pero justo
// después de que el bootstrap termina de extraerse es donde más falla, sea por una demora
// real del sistema de archivos/SELinux en dejar el binario recién extraído ejecutable, o por
// algo específico del dispositivo. La ruta absoluta es más robusta en ambos casos — y si el
// binario genuinamente no está listo todavía, el mensaje de error al menos va a mostrar la
// ruta completa intentada en vez de solo el nombre, dato más útil para diagnosticar.
// Auditoría 2026-08-04 (ver docs/humano/humano63.md): el mismo riesgo aplica a cualquier
// binario invocado justo después de instalar/reinstalar un módulo (ModuleController es el de
// mayor superficie — instala/inicia/detiene TODOS los módulos), no solo a los del bootstrap.
const val TERMUX_PREFIX_PATH = "/data/data/com.termux/files/usr"
const val TERMUX_BASH_PATH = "$TERMUX_PREFIX_PATH/bin/bash"
const val TERMUX_APT_PATH = "$TERMUX_PREFIX_PATH/bin/apt"
const val TERMUX_TMUX_PATH = "$TERMUX_PREFIX_PATH/bin/tmux"
const val TERMUX_PGREP_PATH = "$TERMUX_PREFIX_PATH/bin/pgrep"
// Agregada junto con TERMUX_HERMES_PATH (2026-08-23, docs/humano219.md/humano220.md):
// HermesNative.gatewayStart()/gatewayStop() invocaban "tmux"/"pkill" por nombre relativo en
// vez de usar TERMUX_TMUX_PATH (ya usado por ManagerNativeUtils.tmuxHas()/pgrepF() — la propia
// asimetría que motivó esas dos funciones nunca se aplicó a las llamadas directas de
// gatewayStart/gatewayStop). Mismo fix, misma causa.
const val TERMUX_PKILL_PATH = "$TERMUX_PREFIX_PATH/bin/pkill"
const val TERMUX_MKFIFO_PATH = "$TERMUX_PREFIX_PATH/bin/mkfifo"
// Bug real (2026-08-06, ver docs/humano/humano77.md): mismo patrón de arriba, nunca
// aplicado a EntornoNative.kt — "Cannot run program 'proot-distro'"/"Cannot run program
// 'pkg'" en dispositivo real (log real: distroInstall(debian) e installDesktop(xfce4)),
// pese a que isTermuxBinaryAvailable() (bash -c "command -v ...") confirma que el binario
// SÍ se resuelve bien por PATH justo antes — la misma asimetría bash-resuelve-bien/
// ProcessBuilder-resuelve-mal que motivó las constantes de arriba.
const val TERMUX_PROOT_DISTRO_PATH = "$TERMUX_PREFIX_PATH/bin/proot-distro"
const val TERMUX_PKG_PATH = "$TERMUX_PREFIX_PATH/bin/pkg"
// Bug real (2026-08-07, ver docs/humano/humano90.md): mismo patrón de arriba, nunca
// aplicado a MonitorFragment.kt — "pkg"/"python3" invocados por nombre relativo (aun con
// applyTermuxEnv() ya seteando PATH) dejaban las casillas de "Paquetes de Termux"/
// "Paquetes de Python" del tab Monitor sin funcionar de forma confiable.
const val TERMUX_PYTHON3_PATH = "$TERMUX_PREFIX_PATH/bin/python3"
// Bug real (2026-08-18, ver captura de dispositivo): UdockerFragment.kt invocaba
// ManagerNativeUtils.runExec(listOf("udocker", ...)) por nombre relativo — pese a que
// runExec() ya aplica applyTermuxEnv() (PATH incluido), la resolución de PATH vía
// ProcessBuilder sigue siendo poco confiable en este proyecto (misma asimetría
// bash-resuelve-bien/ProcessBuilder-resuelve-mal documentada arriba para
// proot-distro/pkg/python3) — mensaje real: "Cannot run program "udocker": error=2,
// No such file or directory" al tocar "Instalar distro". Ruta absoluta = mismo fix.
const val TERMUX_UDOCKER_PATH = "$TERMUX_PREFIX_PATH/bin/udocker"
// Bug real (2026-08-20, reportado por el usuario en uso real: "en cactus needle anque se
// instala al poner un comando ... dice que cactus no esta instalado"). CactusFragment.kt
// invocaba ManagerNativeUtils.runExec(listOf("cactus", ...)) por nombre relativo — misma
// asimetría bash-resuelve-bien/ProcessBuilder-resuelve-mal de arriba (ver TERMUX_UDOCKER_PATH):
// isModuleInstalled() usa `bash -c "command -v cactus"` (SIEMPRE resuelve bien vía PATH de
// bash), así que la pantalla de detalle mostraba el módulo como instalado — pero
// reportCactusResult()/reportExtractResult() interpretaban el "Cannot run program "cactus":
// error=2, No such file or directory" de ProcessBuilder (que NO hereda PATH-lookup confiable)
// como "cactus no está instalado", contradiciendo lo que la propia pantalla ya había mostrado.
// Ruta absoluta = mismo fix que udocker/proot-distro/pkg/python3.
const val TERMUX_CACTUS_PATH = "$TERMUX_PREFIX_PATH/bin/cactus"
// Bug real confirmado por ADB en dispositivo (2026-08-23, ver docs/humano219.md/humano220.md,
// probando "Configurar proveedor IA local" de Hermes con Ollama realmente corriendo): toast
// real "Error: Cannot run program "hermes": error=2, No such file or directory" — misma
// asimetría bash-resuelve-bien/ProcessBuilder-resuelve-mal de arriba (ver TERMUX_CACTUS_PATH).
// HermesNative.configSetLocalProvider() invocaba ManagerNativeUtils.runExec(listOf("hermes",
// "config", "set", ...)) por nombre relativo — `command -v hermes` confirma que vive en
// "$TERMUX_PREFIX_PATH/bin/hermes" (instalación nativa estándar, no venv/TUR en este caso).
// Ruta absoluta = mismo fix que udocker/cactus/proot-distro/pkg/python3.
const val TERMUX_HERMES_PATH = "$TERMUX_PREFIX_PATH/bin/hermes"
// Bug real confirmado por ADB en dispositivo (2026-08-24, ver docs/humano222.md): mismo patrón
// que TERMUX_HERMES_PATH — EntornoNative.kt invocaba ManagerNativeUtils.runExec(listOf(
// "vncserver"/"tigervncserver"/"vncpasswd", ...)) por nombre relativo. Confirmado instalando
// tigervnc de verdad en el dispositivo: el paquete real (1.16.2) solo provee "vncserver" y
// "vncpasswd" en "$TERMUX_PREFIX_PATH/bin/" — NO existe un binario separado "tigervncserver"/
// "tigervncpasswd" en esta versión (los fallbacks a esos nombres en EntornoNative.kt quedan
// como código muerto inofensivo, no se tocan — fuera del alcance de este fix puntual).
const val TERMUX_VNCSERVER_PATH = "$TERMUX_PREFIX_PATH/bin/vncserver"
const val TERMUX_VNCPASSWD_PATH = "$TERMUX_PREFIX_PATH/bin/vncpasswd"
// Bug real confirmado por ADB (2026-08-24, ver docs/humano222.md): OpenCodeNative.kt::info()
// invocaba "opencode" por nombre relativo como fallback cuando el registry no tiene la versión
// cacheada. Ruta confirmada instalando opencode.sh de verdad en el dispositivo (paquete nativo
// .pkg.tar.xz, no npm): "$TERMUX_PREFIX_PATH/bin/opencode" — no se asumió sin confirmar primero
// (a diferencia de los CLIs npm, este método de instalación sí deja el binario en $PREFIX/bin).
const val TERMUX_OPENCODE_PATH = "$TERMUX_PREFIX_PATH/bin/opencode"

/**
 * El proceso Java de la app hereda el PATH de Android, no el de Termux ($PREFIX/bin) —
 * cualquier ProcessBuilder que ejecute un binario de Termux por nombre no-absoluto
 * (python3, bash, git, tmux, pgrep, ...) sin esto falla a resolverlo. Bug real
 * encontrado 2026-07-28: varios fragments solo seteaban HOME, dejando python3/bash/git
 * sin PATH — togglear un módulo, abrir su pantalla de detalle, o el botón "Actualizar"
 * fallaban con "Cannot run program ...: error=2, No such file or directory".
 */
fun ProcessBuilder.applyTermuxEnv() {
    val env = environment()
    env["HOME"] = TermuxConstants.TERMUX_HOME_DIR_PATH
    env["PREFIX"] = TERMUX_PREFIX_PATH
    // Bug real confirmado por ADB en dispositivo (2026-08-21, ver docs/humano/humano182.md): Claude
    // Code (claude.sh, método "native") instala su wrapper real en $HOME/.local/bin/claude,
    // NUNCA en $TERMUX_PREFIX_PATH/bin (el comentario viejo de ModuleInstalled.BINARY_FALLBACK
    // que decía "wrapper en $HOME/.local/bin y $TERMUX_PREFIX_PATH/bin" era falso — confirmado
    // en el dispositivo real: el binario en $TERMUX_PREFIX_PATH/bin/claude no existe). Sin
    // $HOME/.local/bin acá, `command -v claude`/`isTermuxBinaryAvailable("claude")` SIEMPRE
    // fallan aunque el binario funcione perfecto (confirmado corriéndolo por ruta completa vía
    // adb shell run-as: exit 0, "2.1.152 (Claude Code)") — el registry decía instalado pero
    // ModuleInstalled/verificar.sh lo reportaban como fallando. Mismo patrón de convención que
    // otros instaladores nativos (hf.sh, mimocode.sh, etc.) — agregar $HOME/.local/bin acá
    // arregla el chequeo para TODOS los módulos con ese patrón, no solo claude.
    // Bug real encontrado 2026-08-24 (ver docs/humano216.md, pruebas funcionales reales por
    // ADB): openclaw.sh corre "npm config set prefix ~/.npm-global" en su primera instalación,
    // que muta ~/.npmrc de forma GLOBAL (afecta a TODOS los "npm install -g" siguientes, de
    // cualquier módulo, no solo OpenClaw) — confirmado en dispositivo: openclaw, copilotcli,
    // minimaxcli, qwencode, kimi y pi quedaron instalados en $HOME/.npm-global/bin, no en
    // $HOME/.local/bin. modulos/lib.sh ya compensa esto del lado bash (línea ~36, prepende
    // $HOME/.npm-global/bin al PATH de cada script que lo sourcea) desde hace tiempo — pero
    // ESTA función (el PATH real que usa la app Kotlin para invocar cualquier CLI: "Enviar
    // prompt", checks de versión, etc.) nunca lo tenía, así que esos 6+ módulos fallaban en
    // silencio ("Cannot run program") pese a que isModuleInstalled()/verificar.sh (que sí pasan
    // por bash) los reportaban como instalados y funcionando.
    env["PATH"] = "${TermuxConstants.TERMUX_HOME_DIR_PATH}/.local/bin:${TermuxConstants.TERMUX_HOME_DIR_PATH}/.npm-global/bin:$TERMUX_PREFIX_PATH/bin:$TERMUX_PREFIX_PATH/sbin"
    env["LD_LIBRARY_PATH"] = "$TERMUX_PREFIX_PATH/lib"
    // Bug real (2026-08-07, ver docs/humano/humano88.md): faltaba TMPDIR — TermuxShellEnvironment
    // (el builder de entorno real de una sesión de terminal interactiva) SÍ lo setea a
    // "$PREFIX/tmp" (ver TermuxShellEnvironment.java, ENV_TMPDIR), pero acá nunca se replicó. Sin
    // esto, cualquier binario que resuelva su directorio temporal vía os.tmpdir()/getenv("TMPDIR")
    // (confirmado el caso real: install.js de @mmmbuto/ollama-termux, que usa
    // "process.env.TMPDIR || os.tmpdir() || ...") cae a un fallback tipo "/tmp" — que en Termux no
    // existe como filesystem normal, y cuya extracción de archivos puede terminar con bits de
    // permisos (setuid/setgid/sticky) distintos a los de "$PREFIX/tmp", dependiendo del punto de
    // montaje. Esto explica por qué "npm install -g @mmmbuto/ollama-termux" + "ollama-termux"
    // funcionan a mano en una terminal real (con TMPDIR ya exportado) pero fallan con
    // "Installation aborted: privileged extracted mode: bin" cuando los lanza la app vía
    // ProcessBuilder (sin TMPDIR). Setearlo acá cubre TODOS los módulos que pasan por este helper,
    // no solo Ollama.
    env["TMPDIR"] = "$TERMUX_PREFIX_PATH/tmp"
    // Faltaba (auditoría 2026-08-01, bug real de Ollama nunca arrancando su sesión tmux
    // — ver ollama_start.sh en modulos/ollama.sh): "bash script.sh" invocado así no es ni
    // login ni el terminal real de Termux, así que $SHELL nunca llega seteado desde
    // ningún lado. Cualquier binario que resuelva su shell hijo a partir de $SHELL (tmux
    // para el "default-shell" de una sesión nueva, Node/npm para child_process con
    // scripts de instalación, etc.) puede terminar cayendo a un fallback que no existe en
    // Termux (ej. "/bin/sh" — Termux no tiene /bin, todo vive bajo $PREFIX). Setearlo acá
    // cubre los ~9 call sites que ya comparten este helper de una sola vez.
    env["SHELL"] = TERMUX_BASH_PATH
    // Bug real encontrado 2026-08-24 (ver docs/humano216.md, pruebas funcionales reales por
    // ADB): "agy" (binario real del módulo "antigravity", mapeado en ModuleVersionChecker.kt)
    // detecta si corre en Termux nativo leyendo $TERMUX_VERSION — sin ella, imprime "This
    // standalone port is only for native Termux. PRoot environments can use Google's official
    // Antigravity CLI binary directly." en vez de la versión real, aunque SÍ está corriendo en
    // Termux nativo (mismo proceso que la app). Confirmado en dispositivo: con TERMUX_VERSION
    // seteada, `agy --version` responde bien ("1.1.19"); sin ella, ModuleVersionChecker.kt
    // (que ya usa este helper) reporta ese mensaje confuso como si fuera la versión. Leído en
    // runtime desde el bootstrap real instalado (no hardcodeado — la versión de Termux puede
    // cambiar entre actualizaciones del bootstrap embebido en el APK).
    runCatching {
        File("$TERMUX_PREFIX_PATH/etc/termux/termux.env").readLines()
            .firstOrNull { it.startsWith("export TERMUX_VERSION=") }
            ?.substringAfter('=')
            ?.trim('"')
    }.getOrNull()?.let { env["TERMUX_VERSION"] = it }
}

/**
 * Verifica que un binario de Termux REALMENTE se pueda ejecutar ahora mismo — no solo que
 * el registry (~/.android_server_registry) diga "<modulo>.installed=true". Bug real
 * confirmado con evidencia de dispositivo (capturas + logs, docs/humano*.md 2026-07-31):
 * python.installed=true y python.version=v3.14.6 en el registry, pero "Cannot run program
 * "python3": error=2" en cada pantalla que lo invoca — el registry se escribe una sola vez
 * al final de un install exitoso y nunca se corrige si el binario deja de funcionar después
 * (ver WizardInstallFragment.ensureBootstrapSecondStage(): ese fix solo corre en
 * instalaciones NUEVAS del bootstrap, no repara dispositivos que ya tenían Termux/Python
 * de antes de que ese fix existiera). Mismo patrón que el bug real de variant-id de
 * Ollama/Claude — no confiar ciegamente en un registry que puede haber quedado
 * desincronizado de la realidad.
 *
 * Usa `bash -c "command -v <name>"` en vez de invocar "command" directo — "command" es un
 * builtin de bash, no existe como binario en $PREFIX/bin, así que ProcessBuilder("command",
 * "-v", name) fallaría siempre con el mismo "Cannot run program" que se busca diagnosticar.
 */
fun isTermuxBinaryAvailable(name: String): Boolean {
    return try {
        val pb = ProcessBuilder(TERMUX_BASH_PATH, "-c", "command -v '$name'")
        pb.applyTermuxEnv()
        val process = pb.start()
        process.inputStream.bufferedReader().readText()
        process.errorStream.bufferedReader().readText()
        process.waitFor() == 0
    } catch (_: Exception) {
        false
    }
}

/**
 * "Cannot run program "X": error=2, No such file or directory" es el mensaje EXACTO que
 * lanza la JVM cuando ProcessBuilder no logra ni siquiera arrancar el binario — no un fallo
 * del script ya corriendo, sino que el binario de Termux nunca se pudo ejecutar (típicamente
 * porque no existe o quedó roto). Mostrar ese mensaje crudo en un Snackbar/Toast no le dice
 * nada accionable al usuario; evidencia real (capturas de dispositivo) mostró este mismo
 * mensaje sin traducir en 6+ pantallas distintas para python3/git.
 */
fun friendlyProcessErrorMessage(e: Exception, moduleLabel: String = "El módulo"): String {
    val raw = e.message ?: "error desconocido"
    return if (raw.contains("Cannot run program")) {
        "$moduleLabel no está disponible — la instalación pudo haber quedado incompleta. Probá reinstalarlo."
    } else {
        raw
    }
}

// Tabla estándar de señales POSIX (Linux/ARM64, mismos números que cualquier plataforma
// Linux — no varían por arquitectura) — pieza adoptada de Subprocess.kt de CodeAssist (ver
// docs/referencias/REFERENCIA_CODEASSIST.md, hallazgo 2026-08-01). Un proceso terminado por una señal
// reporta exit code = 128 + número de señal (convención estándar de shell) — sin esto,
// "exit code 139" no le dice a nadie que en realidad fue un segfault (128+11=139=SIGSEGV).
private val POSIX_SIGNAL_NAMES = mapOf(
    1 to "SIGHUP", 2 to "SIGINT", 3 to "SIGQUIT", 4 to "SIGILL", 5 to "SIGTRAP",
    6 to "SIGABRT", 7 to "SIGBUS", 8 to "SIGFPE", 9 to "SIGKILL", 10 to "SIGUSR1",
    11 to "SIGSEGV", 12 to "SIGUSR2", 13 to "SIGPIPE", 14 to "SIGALRM", 15 to "SIGTERM"
)

/**
 * Si [exitCode] corresponde a "terminado por señal" (128+N, rango 129-143 para las señales
 * conocidas de arriba), devuelve un texto legible como "SIGSEGV (crash nativo)" — null si el
 * exit code es un código de error normal del programa (0-128, o una señal no mapeada), para
 * no inventar una explicación donde no la hay.
 */
fun decodeExitSignal(exitCode: Int): String? {
    if (exitCode <= 128) return null
    val signalNum = exitCode - 128
    val name = POSIX_SIGNAL_NAMES[signalNum] ?: return null
    val hint = when (name) {
        "SIGSEGV", "SIGBUS", "SIGILL" -> "crash nativo"
        "SIGABRT" -> "abort — el propio binario detectó un estado inválido"
        "SIGKILL" -> "matado por el sistema (sin memoria, o el phantom process killer de Android — ver Sistema → Diagnóstico)"
        "SIGTERM" -> "terminado externamente"
        else -> "señal recibida"
    }
    return "$name ($hint)"
}

/**
 * Equivalente Kotlin de `shlex.quote()` de Python — usado por kairos_manager.py para
 * evitar que un valor de usuario (token de Cloudflare, etc.) interpolado en un comando
 * `bash -c "..."` rompa el shell o inyecte comandos extra (comillas, `;`, `$`, backticks).
 * Portado 2026-07-31 junto con TunnelManager/RemoteManager (ver cmd_tunnel/cmd_remote en
 * modulos/kairos_manager.py) — mismo algoritmo: si el string ya es "seguro" (solo caracteres
 * de la whitelist de POSIX shell), se devuelve tal cual; si no, se envuelve en comillas
 * simples y cada comilla simple interna se escapa como '"'"'.
 */
private val SHELL_SAFE_CHARS = Regex("^[A-Za-z0-9@%_\\-+=:,./]+$")

fun shellQuote(value: String): String {
    if (value.isEmpty()) return "''"
    if (SHELL_SAFE_CHARS.matches(value)) return value
    return "'" + value.replace("'", "'\"'\"'") + "'"
}

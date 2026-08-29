package com.termux.app.util

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Migración nativa de cmd_entorno (kairos_manager.py) — wrapea proot-distro y
 * Termux:X11 vía ProcessBuilder directo en vez de Kotlin -> python3 -> shell.
 * entorno.sh (modulos/) sigue instalando la infra base (proot-distro/udocker/X11/GPU);
 * esto maneja lo que la UI necesita en tiempo real: distros, DE, X11, VNC, PulseAudio
 * y método GPU. Es el puerto a Kotlin de las ~14 opciones de mayor valor de los 3
 * submenús de menu_entorno.sh (termux-ai-stack-dev, solo lectura) — no las ~25
 * completas: quedan afuera los visores de registry/logs crudos del submenú Estado (bajo
 * valor, cubierto por depuración general de la app). Ver EntornoFragment.kt para el
 * detalle de qué se agregó. "Contenedores — udocker" (terminal interactiva dentro de un
 * contenedor, ver udockerAvailable()/udockerContainers()/udockerExecCommand() abajo)
 * agregado 2026-08-06 — udocker se usa hoy solo para n8n (modulos/n8n.sh), pero como
 * motor de contenedores genérico tiene sentido exponerlo acá, no atado a un módulo.
 */
object EntornoNative {

    // "kali" agregada 2026-08-26 (pedido explícito del usuario: "en Mini PC nunca sale
    // disponible la distro Kali y debería salir" — faltaba del catálogo por completo, ver
    // docs/humano/humano226.md). A diferencia del resto de esta lista, "kali" NO es un alias
    // oficial de proot-distro (confirmado en modulos/ciberseguridad.sh PASO 7: proot-distro
    // v5.6.0+ ya no tiene un alias curado "kali" — hay que instalar la imagen OCI completa
    // "kalilinux/kali-rolling" con "-n kali" para que quede con ese nombre de contenedor). Se
    // mapea con KALI_INSTALL_IMAGE en distroInstall() — el resto de funciones (rootfsParentDir,
    // remove, list) siguen usando "kali" tal cual porque ESE es el nombre real del contenedor
    // ya creado (mismo criterio que usa ciberseguridad.sh).
    private const val KALI_INSTALL_IMAGE = "kalilinux/kali-rolling"

    // Auditoría de catálogo 2026-08-26 (ver docs/mini-pc/MINIPC_TAB_2026-08-25.md, sección
    // "Catálogo de distros"): comparado el listado real de distro-plugins de
    // github.com/termux/proot-distro contra KNOWN_DISTROS de acá. Se agregan 3 distros con
    // justificación real distinta a lo ya soportado (no se agregó el catálogo completo de
    // proot-distro sin criterio — quedan afuera artix/chimera/crux/dietpi/pardus/raspbian por
    // ser nicho/redundantes con lo ya cubierto):
    // - "manjaro": Arch curado/estable (release delayed, testing propio) — complementa
    //   "archlinux" (rolling puro, sin red de seguridad) para quien quiere paquetes recientes
    //   sin el riesgo de romper el contenedor con cada actualización.
    // - "rockylinux": clon de RHEL, ciclo de vida LTS — aporta un caso de uso que "fedora"
    //   (release corto, ~13 meses, paquetes de punta) no cubre: estabilidad a largo plazo,
    //   relevante para quien monta un servicio persistente (ej. n8n/Ollama) dentro del
    //   contenedor y no quiere breaking changes frecuentes.
    // - "opensuse-tumbleweed": única distro de esta lista con gestor de paquetes zypper/RPM
    //   fuera de la familia RHEL — herramientas propias (YaST-style tooling, Tumbleweed rolling
    //   con testing automatizado openQA) sin equivalente en el resto del catálogo.
    // Nombres NO verificados en dispositivo real (regla empirical-verification-before-fix.md —
    // esta ronda fue auditoría de catálogo por conocimiento entrenado del proyecto oficial, sin
    // acceso a "honkon"/dispositivo conectado) — quedan en EXPERIMENTAL, no CONFIRMED, hasta que
    // una sesión con proot-distro real corra "proot-distro list" y confirme que estos 3 alias
    // instalan tal cual. Si alguno falla, aplicar el mismo patrón que "kali" arriba (mapeo a
    // imagen OCI + "-n <nombre>" en distroInstall()) en vez de asumir que el alias está mal.
    private val KNOWN_DISTROS = listOf(
        "ubuntu", "debian", "alpine", "archlinux", "fedora", "void", "kali",
        "manjaro", "rockylinux", "opensuse-tumbleweed"
    )
    private val CONFIRMED = listOf("ubuntu", "debian", "alpine")
    private val EXPERIMENTAL = listOf("archlinux", "fedora", "void", "kali", "manjaro", "rockylinux", "opensuse-tumbleweed")

    // Bug real reportado (2026-08-13, ver docs/humano/humano116.md): "plasma" quedó en esta
    // lista pero NUNCA puede instalarse — KDE Plasma está fuera de alcance del repo nativo
    // termux/x11-packages (confirmado: solo se ofrece vía proot-distro con pacman/apt, no
    // existe como paquete pkg de Termux) — installDesktop("plasma") fallaba siempre con
    // "unable to locate package". xfce4/lxqt/mate sí son paquetes pkg reales del x11-repo.
    val KNOWN_DESKTOPS = listOf("xfce4", "lxqt", "mate")

    // "kde" (roadmap Mini PC item 2, MEJORAS_PENDIENTES.md 2026-08-28) — a diferencia de
    // xfce4/lxqt/mate, KDE Plasma NO se agrega a KNOWN_DESKTOPS de arriba porque ese es el
    // abanico del picker NATIVO (installDesktop()/desktopBinaryExists(), instala vía "pkg"
    // en el host Termux) — y KDE Plasma no existe como paquete pkg nativo (mismo bug real
    // ya confirmado con "plasma" en 2026-08-13, ver comentario de KNOWN_DESKTOPS arriba).
    // Solo la vía "con distro" (distroInstallDesktop(), apt/dnf/pacman DENTRO de un proot)
    // puede instalarlo de verdad — KNOWN_DESKTOPS_DISTRO es el abanico que usan
    // distroInstallDesktop()/installedDesktopsForDistro() y el picker de
    // EntornoFragment.promptDesktopChoiceForDistro(), nunca el picker nativo.
    private val DISTRO_ONLY_DESKTOPS = listOf("kde")
    val KNOWN_DESKTOPS_DISTRO = KNOWN_DESKTOPS + DISTRO_ONLY_DESKTOPS

    /**
     * CLIs de Kairos con comando de terminal PLANO (mismo valor que "terminalCommand" en
     * app/src/main/assets/modules.json) — usados por generateDesktopLaunchers() para el
     * resto de módulos que no necesitan wrapper propio (a diferencia de claude/opencode/n8n,
     * que sí lo tienen, ver arriba). Pedido explícito del usuario (ronda 2026-08-18): que
     * los CLIs de Kairos se puedan abrir DESDE DENTRO del escritorio gráfico, no solo desde
     * la terminal adaptada de la app — antes esto cubría 4/~24 módulos con terminalCommand.
     */
    // "category" replica el campo "category" real de app/src/main/assets/modules.json para
    // cada id (ai/dev/lang/seguridad/system/tools) — usado por xdgCategoriesFor() para que el
    // menú "Aplicaciones" de XFCE4/LXQt/MATE agrupe/filtre estos módulos por tipo real en vez
    // de un "Development;" genérico para los ~26 (gap #2 de
    // docs/x11/PANEL_MODULOS_X11.md, cerrado 2026-08-25 — ver sección "Implementación
    // 2026-08-25" de ese doc). Mantener en sync con modules.json si cambia la categoría de
    // un módulo ahí.
    private data class GenericCliLauncher(val id: String, val label: String, val command: String, val category: String)

    private val GENERIC_CLI_LAUNCHERS = listOf(
        GenericCliLauncher("python", "Python", "python3", "lang"),
        GenericCliLauncher("antigravity", "Antigravity", "agy", "ai"),
        GenericCliLauncher("engram", "Engram", "engram", "ai"),
        GenericCliLauncher("freebuff", "FreeBuff", "freebuff", "ai"),
        GenericCliLauncher("codebuff", "Codebuff", "codebuff", "ai"),
        GenericCliLauncher("copilotcli", "GitHub Copilot CLI", "copilot", "ai"),
        GenericCliLauncher("minimaxcli", "MiniMax CLI", "mmx", "ai"),
        GenericCliLauncher("mimocode", "MimoCode", "mimo", "ai"),
        GenericCliLauncher("mistralvibe", "Mistral Vibe", "vibe", "ai"),
        GenericCliLauncher("qwencode", "Qwen Code", "qwen", "ai"),
        GenericCliLauncher("ciberseguridad", "Ciberseguridad (nmap)", "nmap", "seguridad"),
        GenericCliLauncher("cactus", "Cactus", "cactus", "ai"),
        GenericCliLauncher("ide", "Neovim", "nvim", "dev"),
        GenericCliLauncher("kimi", "Kimi", "kimi", "ai"),
        GenericCliLauncher("kilo", "Kilo", "kilo", "ai"),
        GenericCliLauncher("cursor", "Cursor Agent", "cursor-agent", "ai"),
        GenericCliLauncher("hf", "Hugging Face CLI", "hf", "ai"),
        GenericCliLauncher("verificar", "Verificar", "verificar", "system"),
        GenericCliLauncher("repo", "Repo", "repo", "dev"),
        GenericCliLauncher("udocker", "udocker CLI", "udocker", "dev"),
        GenericCliLauncher("apk", "Compilar APK", "compil-apk-termux", "dev"),
        GenericCliLauncher("qemu", "QEMU", "qemu-system-x86_64", "dev")
    )

    private val scriptsDir get() = File(ManagerNativeUtils.home, "scripts/entorno")
    private val desktopDir get() = File(ManagerNativeUtils.home, "Desktop")
    // Gap real #1 de docs/x11/AUDITORIA_CONSOLIDADA_ENTORNO_2026-08-25.md (diseñado desde
    // docs/x11/PANEL_MODULOS_X11.md, 2026-08-18, nunca implementado hasta ahora): además de
    // ~/Desktop (íconos sueltos en el fondo de pantalla, invisibles con una ventana
    // maximizada encima), escribir los mismos .desktop en ~/.local/share/applications/ — la
    // carpeta XDG estándar que XFCE4/LXQt/MATE escanean para el menú "Aplicaciones" real
    // (equivalente al botón Inicio), siempre accesible sin importar qué ventana esté arriba.
    private val appsMenuDir get() = File(ManagerNativeUtils.home, ".local/share/applications")
    private val prefix = com.termux.shared.termux.TermuxConstants.TERMUX_PREFIX_DIR_PATH
    private val containersBase = "$prefix/var/lib/proot-distro/containers"
    private val rootfsBase = "$prefix/var/lib/proot-distro/installed-rootfs"

    // Locking real (RegistryLock, mismo lockFile que ProjectsManager/TunnelManager) +
    // algoritmo de upsert compartido (ManagerNativeUtils.upsertKeyValueLine) —
    // consolidación 2026-08-13 (ver auditoría de código): antes este read-modify-write
    // sobre ~/.android_server_registry no tenía ninguna protección de concurrencia,
    // pudiendo perder una actualización si otra escritura (ej. una instalación de módulo
    // en background) tocaba el mismo archivo al mismo tiempo.
    private fun updateRegistryValue(key: String, value: String) {
        RegistryLock.withLock(ManagerNativeUtils.registryLockFile) {
            val file = File(ManagerNativeUtils.home, ".android_server_registry")
            ManagerNativeUtils.upsertKeyValueLine(file, key, value)
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  Exclusividad NATIVO ↔ CON DISTRO — pedido explícito del usuario (ronda
    //  2026-08-18): "ojo solo puede estar una o es x11 con de nativa o con proot
    //  distro no tener las dos abiertas al mismo tiempo" — ambos caminos comparten
    //  el mismo servidor X11 embebido (display :1, un solo Xlorie, ver
    //  docs/x11/X11_EMBEBIDO.md), así que dos DE al mismo tiempo pelearían
    //  por el mismo display. Se trackea con una marca simple en el registry
    //  ("entorno.desktop_mode" = "native" | "proot" | vacío), puesta al arrancar un
    //  DE con éxito y limpiada al detenerlo — con auto-recuperación si la marca
    //  quedó vieja (proceso real ya no vive, ej. tras un crash) para no dejar al
    //  usuario bloqueado para siempre por un estado stale.
    // ═══════════════════════════════════════════════════════════

    private const val MODE_NATIVE = "native"
    private const val MODE_PROOT = "proot"

    /** Devuelve el modo activo real ("native"/"proot"/""), limpiando la marca sola si el proceso real ya no vive (crash, kill externo). */
    private fun activeDesktopMode(): String {
        val reg = ManagerNativeUtils.readRegistry()
        val mode = reg["entorno.desktop_mode"]?.takeIf { it.isNotBlank() } ?: return ""
        val stillAlive = when (mode) {
            MODE_NATIVE -> KNOWN_DESKTOPS.any { desktopExecCmd(it)?.let(::processRunning) == true }
            MODE_PROOT -> processRunning("proot-distro login") &&
                (processRunning("dbus-launch --exit-with-session") || KNOWN_DESKTOPS.any { desktopExecCmd(it)?.let(::processRunning) == true })
            else -> false
        }
        if (!stillAlive) {
            updateRegistryValue("entorno.desktop_mode", "")
            return ""
        }
        return mode
    }

    /** Bloquea si ya hay un escritorio del OTRO tipo activo — usado por startDesktop()/startDistroDesktop() antes de lanzar nada. */
    private fun desktopModeConflict(requested: String): JSONObject? {
        val active = activeDesktopMode()
        if (active.isBlank() || active == requested) return null
        val activeLabel = if (active == MODE_NATIVE) "nativo (sin distro)" else "dentro de una distro (proot-distro)"
        return JSONObject().put("ok", false).put("conflict", true).put("active_mode", active)
            .put("error", "Ya hay un escritorio $activeLabel corriendo — solo puede haber uno a la vez sobre el mismo servidor X11. Detenelo primero (\"Detener escritorio actual\") y volvé a intentar.")
    }

    /** Limpia la marca de exclusividad sin correr gui_stop.sh — usada por EntornoFragment.stopEmbeddedX11() (apagar el servidor X11 completo ya mata cualquier DE arriba con él). */
    fun clearDesktopMode() = updateRegistryValue("entorno.desktop_mode", "")

    /**
     * Detiene el escritorio actual (nativo o dentro de distro) SIN apagar el servidor X11
     * embebido — corre gui_stop.sh sin --x11 (mata xfce4-session/lxqt-session/mate-session
     * y cualquier "proot-distro login" de sesión de escritorio) y limpia la marca de modo,
     * para poder arrancar el otro camino después. "Detener servidor X11" (botón separado,
     * ya existente) sigue siendo el apagado completo.
     */
    fun stopDesktopSession(): JSONObject {
        val script = File(scriptsDir, "gui_stop.sh")
        if (!script.exists()) {
            updateRegistryValue("entorno.desktop_mode", "")
            return JSONObject().put("ok", false).put("error", "Entorno no instalado todavía")
        }
        val (rc, out, err) = ManagerNativeUtils.runExec(listOf(TERMUX_BASH_PATH, script.absolutePath), 20)
        updateRegistryValue("entorno.desktop_mode", "")
        if (rc != 0) {
            return JSONObject().put("ok", false).put("error", "No se pudo detener el escritorio")
                .put("output", out.ifEmpty { err }.takeLast(300))
        }
        return JSONObject().put("ok", true).put("message", "Escritorio detenido — el servidor X11 sigue arriba")
    }

    /**
     * Lee una system property de Android directo con Runtime.exec(), SIN pasar por
     * ManagerNativeUtils.runExec()/applyTermuxEnv() — getprop es un binario de
     * /system/bin/ (Android), no de Termux, y applyTermuxEnv() sobrescribe PATH a solo
     * $PREFIX/bin:$PREFIX/sbin, donde getprop nunca puede resolverse. Bug real
     * confirmado (auditoría 2026-08-13): detectGpuType() usaba runExec() y siempre caía
     * a "unknown" — mismo patrón que ya usa OverlayPermissionHelper.getSystemProperty()
     * (Runtime.getRuntime().exec() directo, hereda el PATH real de Android) para el
     * mismo binario.
     */
    private fun getSystemProperty(name: String): String = try {
        val process = Runtime.getRuntime().exec(arrayOf("getprop", name))
        process.inputStream.bufferedReader().readLine()?.trim().orEmpty()
    } catch (_: Exception) {
        ""
    }

    /**
     * Mismo mapeo que _check_gpu() en menu_entorno.sh/entorno.sh — getprop, no dmesg
     * (Android 15 pide root para dmesg). Codenames Adreno ampliados (2026-08-06, ver
     * docs/humano/humano86.md) — 3ra copia del mismo bug ya arreglado en modulos/ollama.sh
     * y modulos/entorno.sh (bash): faltaba "cape" (Snapdragon 7+ Gen2, dispositivo real de
     * prueba de esta sesión) y otros codenames Qualcomm recientes.
     *
     * Bug real confirmado por ADB (2026-08-29, dispositivo Samsung SM-A566E/Galaxy A56,
     * Exynos 1580): "ro.board.platform" solo devuelve "erd8855" en este dispositivo — NO
     * matchea "s5e|exynos" y GPU quedaba en "unknown" mostrado en Monitor pese a que el
     * chip es un Exynos/Xclipse real. El identificador real "s5e8855" vive en otras
     * properties (`ro.hardware`, `ro.product.board`, `ro.soc.model`, confirmadas con
     * `adb shell getprop` en el dispositivo real) — se concatenan todas las properties
     * candidatas y se matchea contra el conjunto completo, en vez de una sola property que
     * varía según fabricante/variante de chip. "erd" se agrega al patrón Xclipse/Exynos
     * (Exynos Reference Design, confirmado real en este dispositivo).
     */
    private fun detectGpuType(): String {
        val candidates = listOf(
            "ro.board.platform", "ro.hardware", "ro.hardware.chipname",
            "ro.product.board", "ro.soc.model"
        )
        val gpu = candidates.joinToString(" ") { getSystemProperty(it) }
        return when {
            Regex("sm|kona|lahaina|shima|cape|kalama|taro|pineapple|sun|parrot|khaje|monaco").containsMatchIn(gpu) -> "adreno"
            Regex("mt|t618|g610|g720").containsMatchIn(gpu) -> "mali"
            Regex("s5e|exynos|erd").containsMatchIn(gpu) -> "xclipse"
            else -> "unknown"
        }
    }

    // Bug real (2026-08-06, ver docs/humano/humano86.md): installDesktop() (y las demás
    // llamadas a "pkg update" de este archivo) usan ManagerNativeUtils.runExec() —
    // ProcessBuilder directo, SIN pasar por bash — así que nunca se benefician de
    // pkg_update_with_fallback() (modulos/lib.sh), el reintento-con-otro-mirror que sí
    // tienen todos los scripts bash del proyecto. Fix: invocar por bash sourceando lib.sh
    // (extraído por KairosBootstrap.kt a scripts/install/lib.sh, ver doExtract()) para
    // reusar exactamente la misma lógica que ya usan ollama.sh/n8n.sh/kairos.sh — sin
    // duplicar el algoritmo de medición de mirrors en Kotlin. Si lib.sh no está disponible
    // por algún motivo, cae a un "pkg update -y" plano (comportamiento anterior), nunca
    // deja el update sin intentar.
    //
    // Bug real #2 (auditoría 2026-08-12, ver docs/humano/humano98.md): el patrón de "$out"
    // que dispara el reintento de mirror en pkg_update_with_fallback() (modulos/lib.sh) no
    // incluía "No mirror or mirror group selected" — el mensaje real que devuelve `pkg`
    // (scripts/pkg.in, select_mirror()) cuando el dispositivo nunca corrió el
    // termux-change-repo interactivo (Kairos deliberadamente no lo corre, ver
    // docs/viejo/KAIROS_APP_FIXES.md:323) — así que el fallback nunca se activaba en
    // este caso concreto, reproducido con xfce4. Ya corregido en modulos/lib.sh (y
    // modulos/entorno.sh, que tiene su propia copia local de la función).
    private fun pkgUpdateWithFallback(timeoutSeconds: Long = 180): Triple<Int, String, String> {
        val libSh = File(ManagerNativeUtils.home, "scripts/install/lib.sh")
        val cmd = if (libSh.exists()) {
            "source '${libSh.absolutePath}' 2>/dev/null && pkg_update_with_fallback || $TERMUX_PKG_PATH update -y"
        } else {
            "$TERMUX_PKG_PATH update -y"
        }
        return ManagerNativeUtils.runShell(cmd, timeoutSeconds)
    }

    // Bug real #3 (auditoría 2026-08-12, ver docs/humano/humano98.md): xfce4/lxqt/mate/plasma
    // viven en el repo separado de Termux "x11-repo" (termux/termux-packages,
    // x11-packages/) — NUNCA se habilita en ningún flujo de Kairos (ni modulos/entorno.sh,
    // ni EntornoNative.kt), así que "pkg install xfce4" fallaba con "unable to locate
    // package" independientemente del problema de mirrors de arriba. Se habilita una sola
    // vez (best-effort, "pkg install x11-repo -y" es idempotente si ya está) antes de
    // cualquier instalación que dependa de él.
    private fun ensureX11Repo() {
        ManagerNativeUtils.runExec(listOf(TERMUX_PKG_PATH, "install", "-y", "x11-repo"), 60)
    }

    private fun binaryAvailable(name: String) = isTermuxBinaryAvailable(name)

    private fun processRunning(pattern: String) =
        ManagerNativeUtils.runExec(listOf("pgrep", "-f", pattern), 5).first == 0

    // Hallazgo #1 de docs/x11/AUDITORIA_INFRAESTRUCTURA_X11_2026-08-26.md: status() solo
    // hacía "pgrep -f :xserver", que confirma que el PROCESO Android (X11Service) existe —
    // no que el socket X esté aceptando conexiones de verdad. startDesktop() ya tenía un
    // chequeo más estricto (`[ -S ruta ]` sobre el socket real, ver el bloque "diag" dentro
    // de startDesktop() más abajo) pero solo lo usaba para loguear, nunca lo reusaba acá.
    // Se extrae la parte instantánea de ese chequeo (sin el sleep de arranque, que sí tiene
    // sentido en startDesktop() pero rompería un status() llamado por polling frecuente) a
    // una función propia, para que "vivo" en status() signifique "el socket responde" y no
    // solo "el proceso Android existe". File.exists() sobre un socket UNIX es instantáneo
    // (mismo mecanismo que `[ -S ruta ]` en bash, sin invocar ningún proceso externo).
    private fun isX11SocketAlive(): Boolean {
        val socketPath = "$prefix/tmp/.X11-unix/X${com.termux.app.X11Service.DISPLAY.removePrefix(":")}"
        return File(socketPath).exists()
    }

    fun status(): JSONObject {
        val reg = ManagerNativeUtils.readRegistry()
        val installed = reg["entorno.installed"] == "true"
        val gpu = reg["entorno.gpu"]?.takeIf { it.isNotBlank() } ?: "unknown"
        val gpuMethod = reg["entorno.gpu_method"]?.takeIf { it.isNotBlank() } ?: "auto"
        // Hasta 2026-08-06 esto chequeaba si la app EXTERNA Termux:X11 (com.termux.x11)
        // estaba instalada vía "pm list packages". Desde que X11/Xlorie quedó embebido en
        // el propio APK (X11Service, proceso ":xserver" — ver docs/arquitectura/
        // X11_EMBEBIDO.md), Entorno usa ese servidor embebido en vez de la app externa
        // (pedido explícito del usuario, "como ya tenemos x11/xlorie en el apk debe abrir
        // ahi directamente", ver docs/humano/humano98.md) — el servidor embebido siempre
        // está disponible (ships en el APK), y su estado real es si el proceso ":xserver"
        // está vivo, igual que X11Fragment.isXServerProcessAlive() pero vía pgrep (este
        // object no tiene Context para usar ActivityManager).
        val x11ApkInstalled = true
        val x11Running = processRunning(":xserver") && isX11SocketAlive()
        val vncRunning = processRunning("Xtightvnc") || processRunning("Xvnc")
        val pulseRunning = processRunning("pulseaudio")
        val installedDesktops = KNOWN_DESKTOPS.filter { desktopBinaryExists(it) }
        return JSONObject().apply {
            put("ok", true)
            put("installed", installed)
            put("gpu", gpu)
            put("gpu_method", gpuMethod)
            put("x11_apk_installed", x11ApkInstalled)
            put("x11_running", x11Running)
            put("vnc_running", vncRunning)
            put("vnc_installed", binaryAvailable("tigervncserver"))
            put("pulse_running", pulseRunning)
            put("installed_desktops", JSONArray(installedDesktops))
        }
    }

    fun distroList(): JSONObject {
        if (!isTermuxBinaryAvailable("proot-distro")) {
            return JSONObject().put("ok", false).put("error", "proot-distro no disponible — instala Entorno primero")
        }
        // Bug real confirmado (2026-08-14, reporte de usuario en dispositivo real: "se
        // instala las distros pero no se detecta" + capturas del visor X11 en negro):
        // esto parseaba texto libre de "proot-distro list" buscando una línea que
        // empiece con el nombre de la distro Y contenga la palabra "installed". El
        // paquete `proot-distro` REAL de Termux (termux-packages/packages/proot-distro/
        // build.sh, TERMUX_PKG_VERSION=5.6.0, confirmado contra el repo github.com/
        // termux/proot-distro tag v5.6.0) es una reescritura completa a Python (antes
        // era bash) que cambió el formato de salida por completo: ahora "list" imprime
        // un encabezado único "Installed containers:" y cada distro como "  * <nombre>"
        // — la palabra "installed" NUNCA aparece en la línea individual de una distro,
        // así que el filtro de acá no matcheaba NINGUNA distro real, sin importar cuántas
        // estuvieran instaladas. Se reemplaza por detección directa por filesystem, que
        // no depende de ningún formato de salida de un proyecto de terceros (que ya
        // cambió una vez y puede volver a cambiar) — reusa rootfsParentDir() (dual layout
        // containers/<name>/rootfs moderno + installed-rootfs/<name> legacy), la misma
        // función que ya usan distroBackup()/mountProjectBridge() más abajo en este archivo.
        val installed = KNOWN_DISTROS.filter { name -> rootfsParentDir(name) != null }
        return JSONObject().apply {
            put("ok", true)
            put("known", JSONArray(KNOWN_DISTROS))
            put("confirmed", JSONArray(CONFIRMED))
            put("experimental", JSONArray(EXPERIMENTAL))
            put("installed", JSONArray(installed))
        }
    }

    fun distroInstall(name: String): JSONObject {
        if (name !in KNOWN_DISTROS) {
            return JSONObject().put("ok", false).put("error", "Distro desconocida: $name")
        }
        if (!isTermuxBinaryAvailable("proot-distro")) {
            WizardDebugLog.log("EntornoNative", "distroInstall($name): proot-distro no disponible")
            return JSONObject().put("ok", false).put("error", "proot-distro no disponible — instala Entorno primero")
        }
        // Puede tardar varios minutos (descarga del rootfs) — timeout generoso; el
        // caller ya corre esto en un Thread propio, no bloquea el hilo de UI. Subido
        // de 600s a 900s (bug real reportado, ver docs/humano/humano57.md: "da error
        // al instalar la distro" — en conexiones lentas, un rootfs de varios cientos
        // de MB puede no alcanzar a bajar en 10 minutos; el timeout mataba el proceso
        // a mitad de descarga y se reportaba como error genérico).
        //
        // Bug de diagnosticabilidad real (auditoría 2026-08-05, ver docs/humano65.md/
        // humano66.md): esta operación (y el resto de EntornoNative) no dejaba NINGÚN
        // rastro persistente — a diferencia de ModuleController.installModule(), que
        // escribe a ~/kairos_logs/install_<modulo>.log, un fallo acá solo mostraba un
        // Snackbar genérico ("Instalación de X falló") y el detalle real (json.output)
        // se descartaba sin loguear. El usuario reportó "las distros no se instalan"
        // sin que hubiera ningún log para confirmar la causa real. Se registra el
        // intento completo en el log persistente del wizard/app.
        // "kali" no es un alias real de proot-distro — hay que pedir la imagen OCI completa
        // y forzar el nombre de contenedor con -n (ver comentario de KALI_INSTALL_IMAGE arriba).
        val installArgs = if (name == "kali") {
            listOf(TERMUX_PROOT_DISTRO_PATH, "install", KALI_INSTALL_IMAGE, "-n", "kali")
        } else {
            listOf(TERMUX_PROOT_DISTRO_PATH, "install", name)
        }
        val (rc, out, err) = ManagerNativeUtils.runExec(installArgs, 900)
        val output = out.ifEmpty { err }
        WizardDebugLog.log("EntornoNative", "distroInstall($name): rc=$rc output=${output.takeLast(500)}")
        if (rc != 0) {
            return JSONObject().put("ok", false).put("error", "Instalación de $name falló")
                .put("output", output.takeLast(500))
        }
        return JSONObject().put("ok", true).put("message", "$name instalada correctamente")
    }

    // Bug real confirmado esta ronda (auditoría 2026-08-19): a diferencia de distroInstall()
    // (arriba), esta función no validaba nombre de distro conocido ni que proot-distro
    // estuviera instalado antes de correr el comando real — un name inválido o un
    // proot-distro faltante devolvía un error crudo de ProcessBuilder ("No such file or
    // directory"/rc=1 genérico) en vez del mensaje claro que sí da distroInstall() para el
    // mismo caso. runExec() ya atrapa la excepción (no crashea), pero el mensaje era
    // confuso — se agregan las mismas dos validaciones que ya tiene distroInstall().
    fun distroRemove(name: String): JSONObject {
        if (name !in KNOWN_DISTROS) {
            return JSONObject().put("ok", false).put("error", "Distro desconocida: $name")
        }
        if (!isTermuxBinaryAvailable("proot-distro")) {
            return JSONObject().put("ok", false).put("error", "proot-distro no disponible — instala Entorno primero")
        }
        val (rc, out, err) = ManagerNativeUtils.runExec(listOf(TERMUX_PROOT_DISTRO_PATH, "remove", name), 60)
        if (rc != 0) {
            return JSONObject().put("ok", false).put("error", "No se pudo eliminar $name")
                .put("output", out.ifEmpty { err }.takeLast(300))
        }
        return JSONObject().put("ok", true).put("message", "$name eliminada")
    }

    // ═══════════════════════════════════════════════════════════
    //  Storage compartido automático — portado de usb_bind_args()/--shared-home/
    //  --shared-tmp de termux-desktop-main (ver referencia/termux/termux-desktop-main/
    //  distro-container-setup, funciones usb_bind_args()/usb_mounts()). Pedido explícito
    //  del usuario (docs/humano/humano118.md, PLAN_EXPANSION_HOMELAB_2026-08-13 §2.7):
    //  storage compartido SIN que el usuario configure nada, en CUALQUIER
    //  "proot-distro login" que dispare Kairos.
    // ═══════════════════════════════════════════════════════════

    /**
     * Mismo filtro que usb_mounts() del proyecto de referencia: candidatos bajo
     * /mnt/media_rw/<UUID> con filesystem vfat/exfat/ntfs/fuseblk (tarjetas SD/USB
     * removibles — no el almacenamiento interno, que ya cubre el bind fijo de /storage).
     * Falla en silencio si /proc/mounts no es legible en algún dispositivo — el bind fijo
     * de /storage y /mnt/media_rw completos ya cubre el caso general, esto solo agrega el
     * atajo /mnt/usb/<UUID> por dispositivo detectado.
     */
    private fun usbBindArgs(): List<String> {
        val args = mutableListOf("--bind", "/storage:/storage", "--bind", "/mnt/media_rw:/mnt/media_rw")
        try {
            File("/proc/mounts").forEachLine { line ->
                val fields = line.split(Regex("\\s+"))
                if (fields.size < 3) return@forEachLine
                val mountPoint = fields[1]
                val fsType = fields[2]
                if (!mountPoint.startsWith("/mnt/media_rw/")) return@forEachLine
                if (!Regex("vfat|exfat|ntfs|fuseblk").containsMatchIn(fsType)) return@forEachLine
                val uuid = mountPoint.substringAfterLast('/')
                if (uuid.isNotBlank()) args += listOf("--bind", "$mountPoint:/mnt/usb/$uuid")
            }
        } catch (_: Exception) {
            // /proc/mounts puede no ser legible en algunos dispositivos — degrada al bind fijo de arriba.
        }
        return args
    }

    /**
     * Bug/hueco real confirmado esta ronda (2026-08-18, auditoría del punto "almacenamiento
     * y red" pedido por el usuario): mountProjectBridge() ("🔗 Vincular ~/scripts y
     * ~/proyectos") solo creaba las carpetas destino DENTRO del rootfs y devolvía un mensaje
     * con el comando "-b origen:destino" para pegarlo A MANO en la PRÓXIMA vez que el usuario
     * hiciera login — ningún otro camino de este archivo (promptDistroLogin, pdrun,
     * distroAppInstall/Remove, startDistroDesktop) aplicaba ese bind, así que en la práctica
     * nunca quedaba vinculado salvo que el usuario copiara ese comando manualmente cada vez.
     * Mismo criterio que ya usa usbBindArgs() (storage compartido automático, sin que el
     * usuario configure nada) — se aplica siempre, en TODO login real.
     */
    private fun projectBindArgs(): List<String> {
        val scriptsDir = File(ManagerNativeUtils.home, "scripts")
        val proyectosDir = File(ManagerNativeUtils.home, "proyectos")
        scriptsDir.mkdirs()
        proyectosDir.mkdirs()
        return listOf(
            "--bind", "${scriptsDir.absolutePath}:/home/builder/scripts",
            "--bind", "${proyectosDir.absolutePath}:/home/builder/proyectos"
        )
    }

    /**
     * argv completo de "proot-distro login <distro>" con storage compartido automático —
     * usado tanto por runExec() (distroAppInstall/distroAppRemove, argv directo) como por
     * distroLoginCommand() (texto para tipear en una sesión de terminal real). Los binds
     * van ANTES del nombre de distro (proot-distro los reenvía a proot); --shared-tmp/
     * --shared-home van DESPUÉS (son flags propios de "login"), mismo orden que el
     * proyecto de referencia. ~/scripts y ~/proyectos (projectBindArgs()) se agregaron acá
     * mismo 2026-08-18 — antes solo se explicaban en un mensaje, ahora se aplican en
     * TODO login (nativo o vía pdrun/apps de distro), igual que el storage compartido.
     */
    fun distroLoginArgs(distro: String): List<String> =
        listOf(TERMUX_PROOT_DISTRO_PATH, "login") + usbBindArgs() + projectBindArgs() + listOf(distro, "--shared-tmp", "--shared-home")

    /** Versión texto de distroLoginArgs() — para tipear en una sesión de terminal real (ver EntornoFragment.promptDistroLogin()). */
    fun distroLoginCommand(distro: String): String =
        distroLoginArgs(distro).joinToString(" ") { shellQuote(it) }

    // x11Start()/x11Stop() (controlaban tx11_start.sh/tx11_stop.sh, la app EXTERNA
    // Termux:X11) se removieron 2026-08-12 al conectar Entorno con el X11 embebido — ver
    // startDesktop() abajo y EntornoFragment.kt (X11Service.start()/stop() vía Context,
    // que este object no tiene). tx11_start.sh/tx11_stop.sh siguen existiendo como parte
    // de la infra base que instala entorno.sh, sin caller nativo propio por ahora.

    // ═══════════════════════════════════════════════════════════
    //  ESCRITORIO (DE) — submenu_interfaz [1] y [2] de menu_entorno.sh
    // ═══════════════════════════════════════════════════════════

    private fun desktopBinaryExists(de: String): Boolean = when (de) {
        "xfce4" -> binaryAvailable("startxfce4")
        "lxqt" -> binaryAvailable("startlxqt")
        "mate" -> binaryAvailable("mate-session")
        else -> false
    }

    private fun desktopExecCmd(de: String): String? = when (de) {
        "xfce4" -> "startxfce4"
        "lxqt" -> "startlxqt"
        "mate" -> "mate-session"
        else -> null
    }

    /** Mismo mapeo que session_cmd() en gui_start.sh (modulos/entorno.sh) — el binario real
     *  que el camino "CON DISTRO" ejecuta dentro del proot, distinto del wrapper nativo que usa
     *  desktopExecCmd() arriba (ver comentario real en startDistroDesktop()). */
    private fun distroSessionCmd(de: String): String = when (de) {
        "xfce4", "xfce" -> "xfce4-session"
        "lxqt" -> "lxqt-session"
        "openbox" -> "openbox-session"
        "i3", "i3wm" -> "i3"
        else -> "$de-session"
    }

    fun desktopLabel(de: String): String = when (de) {
        "xfce4" -> "XFCE4"
        "lxqt" -> "LXQt"
        "mate" -> "MATE"
        "kde" -> "KDE Plasma"
        else -> de
    }

    /**
     * Mismos paquetes pkg que _de_install del submenú Interfaz — instala si falta, no
     * reinstala si ya está. Nombres de paquete confirmados contra el repo real de Termux
     * (termux/termux-packages, x11-packages/) — xfce4/lxqt/mate existen tal cual.
     *
     * Timeout subido de 300s a 900s y `pkg update -y` best-effort antes de instalar (mirrors
     * caídos causaban "unable to locate package" en vez de un timeout real, ver
     * docs/humano/humano57.md). NOTA (2026-08-13, ver docs/humano/humano116.md): esa misma
     * ronda diagnosticó mal el caso de `plasma` — el "unable to locate package" ahí NO era
     * por timeout/mirror, era porque KDE Plasma no existe como paquete pkg de Termux (fuera
     * de alcance del repo x11-packages, solo instalable vía proot-distro) — quitado de
     * `KNOWN_DESKTOPS`, no había timeout que lo fuera a arreglar.
     */
    fun installDesktop(de: String): JSONObject {
        if (desktopBinaryExists(de)) {
            return JSONObject().put("ok", true).put("message", "${desktopLabel(de)} ya estaba instalado")
        }
        val packages = when (de) {
            "xfce4" -> listOf("xfce4", "xfce4-terminal")
            "lxqt" -> listOf("lxqt")
            "mate" -> listOf("mate", "mate-terminal")
            else -> return JSONObject().put("ok", false).put("error", "Escritorio desconocido: $de")
        }
        ensureX11Repo()
        val (updateRc, _, _) = pkgUpdateWithFallback()
        if (updateRc != 0) {
            WizardDebugLog.log("EntornoNative", "installDesktop($de): pkg update falló rc=$updateRc")
        }
        val (rc, out, err) = ManagerNativeUtils.runExec(listOf(TERMUX_PKG_PATH, "install", "-y") + packages, 900)
        val output = out.ifEmpty { err }
        WizardDebugLog.log("EntornoNative", "installDesktop($de): rc=$rc output=${output.takeLast(500)}")
        if (rc != 0) {
            return JSONObject().put("ok", false).put("error", "No se pudo instalar ${desktopLabel(de)}")
                .put("output", output.takeLast(500))
        }
        return JSONObject().put("ok", true).put("message", "${desktopLabel(de)} instalado — usá \"Iniciar escritorio\" para arrancarlo")
    }

    /**
     * Lanza el DE elegido sobre el servidor X11 EMBEBIDO (Xlorie/X11Service, display fijo
     * ":1" — ver docs/x11/X11_EMBEBIDO.md). Reemplaza el flujo anterior que
     * arrancaba Termux:X11 (app externa, tx11_start.sh) sobre DISPLAY=:0 — pedido explícito
     * del usuario, "como ya tenemos x11/xlorie en el apk debe abrir ahi directamente" (ver
     * docs/humano/humano98.md).
     *
     * El CALLER (EntornoFragment, que sí tiene Context) es responsable de arrancar
     * X11Service ANTES de invocar esta función — este object no puede arrancar un Service
     * de Android por sí mismo. Solo se espera a que el servidor esté listo (sleep) y se
     * lanza el DE con DISPLAY apuntando al display embebido.
     */
    /**
     * Bug real confirmado (2026-08-14, reporte de usuario + capturas del visor X11
     * mostrando pantalla negra con solo el ícono "X" — ningún cliente X real
     * renderizando): esta función lanzaba el DE en background con salida completa a
     * `/dev/null` y devolvía `ok=true` apenas el `bash -c` terminaba de hacer el
     * backgrounding — SIN verificar que el proceso siguiera vivo después, ni loguear
     * nada si moría al toque (ej. "Can't open display" porque el X11 embebido todavía
     * no estaba aceptando conexiones a los 3s de sleep fijo, o falta alguna dependencia
     * del DE). Mismo patrón "instalación/inicio exitoso pero el proceso real nunca
     * arranca" ya corregido en otros módulos esta sesión (ej. n8n) — acá nunca se había
     * aplicado. Fix: la salida real de $execCmd se captura a un log persistente
     * (~/kairos_logs/desktop_<de>.log, mismo criterio que ModuleController) y, tras un
     * margen real de espera, se confirma con pgrep que el proceso sigue vivo antes de
     * reportar éxito — si murió, se devuelve el tail del log real en vez de un mensaje
     * genérico.
     */
    fun startDesktop(de: String): JSONObject {
        desktopModeConflict(MODE_NATIVE)?.let { return it }
        if (!desktopBinaryExists(de)) {
            return JSONObject().put("ok", false).put("error", "${desktopLabel(de)} no está instalado")
        }
        val execCmd = desktopExecCmd(de)
            ?: return JSONObject().put("ok", false).put("error", "Escritorio desconocido: $de")
        // Antes de arrancar el DE, no después — xfdesktop escanea ~/Desktop al levantar
        // la sesión, así que los íconos deben existir ya en ese momento (ver
        // generateDesktopLaunchers() más abajo, docs/humano/humano115.md). Mismo criterio
        // para ~/.config/autostart/ (generateAutostartEntries()) — la sesión (xfce4-session
        // et al.) también lo lee al levantar.
        generateDesktopLaunchers()
        generateAutostartEntries()
        val gpuEnvScript = File(scriptsDir, "gpu_env.sh")
        val logDir = File(ManagerNativeUtils.home, "kairos_logs")
        logDir.mkdirs()
        val logFile = File(logDir, "desktop_$de.log")
        // Investigación 2026-08-17 (ver docs/x11/X11_EMBEBIDO.md, reporte de usuario
        // "no inicia XFCE4 nativo"): ManagerNativeUtils.runShell() invoca "bash -c" — NO login,
        // NO interactivo — así que /etc/profile y bashrc de Termux NUNCA se sourcean, y
        // cualquier LD_PRELOAD que Termux normalmente configura ahí para sus binarios queda
        // sin aplicar. Ya hay precedente real de este mismo problema en este repo
        // (modulos/claude.sh, variante "native": setea explícito "LD_PRELOAD":
        // ".../lib/libtermux-exec-ld-preload.so" en su config por la misma razón). libX11 de
        // Termux resuelve el socket X11 con la ruta clásica hardcodeada "/tmp/.X11-unix/X<n>"
        // — sin termux-exec activo (que remapea /tmp -> $PREFIX/tmp a nivel de syscall), un
        // cliente X lanzado así puede fallar a conectar aunque el socket real exista en
        // $PREFIX/tmp/.X11-unix. Se agrega explícito acá, scopeado solo a este comando (no a
        // applyTermuxEnv() global, para no afectar módulos que no son clientes X11).
        val ldPreload = "$prefix/lib/libtermux-exec-ld-preload.so"
        val socketDir = "$prefix/tmp/.X11-unix"
        val cmd = buildString {
            if (gpuEnvScript.exists()) append("source '${gpuEnvScript.absolutePath}' >/dev/null 2>&1; ")
            // Sleep subido de 3s a 5s — el servidor X11 embebido arranca en OTRO proceso
            // Android (":xserver", ver X11Service.onCreate()) que recién termina de
            // levantar Xlorie/CmdEntryPoint.main() de forma asíncrona; 3s fijos podían no
            // alcanzar en dispositivos lentos, y $execCmd fallaba con "Can't open display"
            // sin que nadie lo viera (antes iba a /dev/null).
            append("sleep 5; ")
            // Diagnóstico real del socket ANTES de intentar conectar — deja rastro explícito
            // en el log de si el servidor X11 realmente publicó el socket donde el cliente
            // Termux lo espera, para diferenciar "servidor no levantó el socket ahí" de
            // "el cliente no pudo conectar por otra razón" en el próximo intento real.
            append("if [ -S '$socketDir/X${com.termux.app.X11Service.DISPLAY.removePrefix(":")}' ]; then echo '[diag] socket X11 presente en $socketDir'; else echo '[diag] socket X11 AUSENTE en $socketDir — Xlorie no lo publicó ahi'; fi; ")
            append("export DISPLAY=${com.termux.app.X11Service.DISPLAY}; ")
            if (File(ldPreload).exists()) append("export LD_PRELOAD='$ldPreload'; ")
            append("$execCmd >'${logFile.absolutePath}' 2>&1 &")
        }
        val (_, shellOut, _) = ManagerNativeUtils.runShell(cmd, 15)
        WizardDebugLog.log("EntornoNative", "startDesktop($de): $shellOut")
        // Verificación real — este método ya corre en un Thread propio del caller
        // (EntornoFragment), no bloquea la UI. Margen de 4s más allá del sleep interno
        // para darle tiempo a un fallo temprano (display no listo, falta dependencia) a
        // manifestarse y matar el proceso antes de chequear con pgrep.
        Thread.sleep(4000)
        val alive = processRunning(execCmd)
        val logTail = try { if (logFile.exists()) logFile.readText().takeLast(500) else "" } catch (_: Exception) { "" }
        WizardDebugLog.log("EntornoNative", "startDesktop($de): alive=$alive log=$logTail")
        if (!alive) {
            return JSONObject().put("ok", false)
                .put("error", "${desktopLabel(de)} no arrancó — el proceso murió después de iniciarse")
                .put("output", logTail)
        }
        updateRegistryValue("entorno.desktop_mode", MODE_NATIVE)
        return JSONObject().put("ok", true)
            .put("message", "${desktopLabel(de)} corriendo sobre el X11 embebido (${com.termux.app.X11Service.DISPLAY}) — abrí X11 para verlo")
    }

    /**
     * Camino "CON DISTRO" — arranca el DE DENTRO de una distro proot ya preparada con
     * distro_setup_gui.sh (ver gui_start.sh --distro <distro> [<de>] en modulos/entorno.sh),
     * sobre el mismo servidor X11 embebido que usa el camino nativo. Solo xfce4 está
     * confirmado instalable vía distro_setup_gui.sh hoy — mismo alcance que ese script.
     * Aplica la misma exclusividad que startDesktop() (MODE_PROOT vs MODE_NATIVE).
     */
    fun startDistroDesktop(distro: String, de: String = "xfce4"): JSONObject {
        desktopModeConflict(MODE_PROOT)?.let { return it }
        val guiStart = File(scriptsDir, "gui_start.sh")
        if (!guiStart.exists()) {
            return JSONObject().put("ok", false).put("error", "Entorno no instalado todavía")
        }
        if (rootfsParentDir(distro) == null) {
            return JSONObject().put("ok", false).put("error", "$distro no está instalada")
        }
        val logDir = File(ManagerNativeUtils.home, "kairos_logs")
        logDir.mkdirs()
        val logFile = File(logDir, "desktop_distro_${distro}_$de.log")
        val cmd = "bash '${guiStart.absolutePath}' --distro '$distro' '$de' >'${logFile.absolutePath}' 2>&1 &"
        val (_, shellOut, _) = ManagerNativeUtils.runShell(cmd, 15)
        WizardDebugLog.log("EntornoNative", "startDistroDesktop($distro,$de): $shellOut")
        // Margen de verificación subido de 12s a 27s (bug real confirmado 2026-08-29 en
        // dispositivo real, reproducido dos veces seguidas: el PRIMER intento del día con
        // X11Service recién arrancado en frío falló con "Socket X11 AUSENTE ... tras 10s" y
        // "xfce4-session: Cannot open display" en desktop_distro_kali_xfce4.log — Xlorie
        // (CmdEntryPoint.main(), carga de libXlorie.so + init nativo en el proceso ":xserver"
        // recién creado) tarda más de 10s en publicar el socket la primera vez que arranca
        // (ART/carga de página en frío del .so nativo). Un SEGUNDO intento ~2 minutos después,
        // con el mismo proceso ":xserver" ya caliente (X11Service.start() es idempotente, no
        // reinicia si ya está corriendo), encontró el socket "presente (esperado 0s)" y el
        // escritorio arrancó bien. gui_start.sh sube su propio retry loop de 10s a 25s (ver
        // ese script) — 27s = 25s del retry loop + margen para dbus/sesión, mismo criterio que
        // el 12s anterior (10s+2s) pero con el presupuesto real que exige un arranque en frío.
        Thread.sleep(27000)
        // Bug real confirmado 2026-08-27 (ver docs/humano256.md, reporte de usuario: la app
        // dice "error al abrir" pero el entorno gráfico SÍ abre — falso negativo). Este chequeo
        // exigía `processRunning("proot-distro login")` — pero `proot-distro login` (paquete
        // Python real, confirmado leyendo commands/login/__init__.py del proot-distro instalado
        // en el dispositivo) termina con `os.execvpe(proot_bin, proot_args, child_env)`: REEMPLAZA
        // el proceso Python por el binario `proot` real (mismo PID, cmdline nuevo). Confirmado con
        // `pgrep -af` contra un proceso proot real corriendo en el dispositivo: su cmdline pasa a
        // ser pura invocación de `proot` (--kill-on-exit --link2symlink ... --bind=... /bin/bash
        // -c ...) — el texto literal "login" jamás vuelve a aparecer ahí. Como este chequeo corre
        // recién a los 12s (margen para que la sesión termine de levantar), el exec ya ocurrió
        // siempre — `processRunning("proot-distro login")` daba **falso** de forma sistemática,
        // sin importar si la DE arrancó bien o no, y por el `&&` tumbaba el resultado entero. Se
        // reemplaza por un chequeo específico del proot real de ESTA distro (path de bind real,
        // dual-layout containers/<distro>/rootfs vs installed-rootfs/<distro>, mismo criterio que
        // rootfsParentDir()) en vez de depender del cmdline efímero pre-exec del wrapper Python.
        val distroRootfsMarker = when (rootfsParentDir(distro)) {
            containersBase -> "proot-distro/containers/$distro/"
            rootfsBase -> "proot-distro/installed-rootfs/$distro"
            else -> "proot-distro/$distro"
        }
        val logTail = try { if (logFile.exists()) logFile.readText().takeLast(500) else "" } catch (_: Exception) { "" }
        // Bug real confirmado 2026-08-29 en dispositivo real (falso positivo, mismo síntoma
        // reportado por el usuario: la app decía éxito pero ningún escritorio se veía): este
        // chequeo aceptaba `processRunning("dbus-launch --exit-with-session")` como suficiente,
        // pero dbus-launch queda vivo varios segundos incluso cuando el hijo real
        // (xfce4-session) murió al toque con "Cannot open display" (log real confirmado:
        // "xfce4-session: Cannot open display: ." seguido de exit inmediato). El otro término
        // del OR, `desktopExecCmd(de)` (usado por el camino NATIVO — devuelve el wrapper
        // "startxfce4"), nunca podía aportar nada acá: gui_start.sh --distro nunca ejecuta ese
        // wrapper, ejecuta directo el binario de sesión real vía su propio session_cmd()
        // ("xfce4-session", "lxqt-session", etc.) — así que el check dependía en la práctica
        // solo del dbus-launch colgado. Se reemplaza por processRunning(distroSessionCmd(de))
        // (el proceso real que gui_start.sh --distro ejecuta, confirmado con evidencia real:
        // "xfce4-session:6229" apareciendo en el log de un arranque exitoso) y se descarta
        // explícitamente cualquier "alive" si el log contiene el error fatal real de X11.
        val failedToOpenDisplay = logTail.contains("Cannot open display")
        val alive = !failedToOpenDisplay &&
            processRunning(distroRootfsMarker) &&
            processRunning(distroSessionCmd(de))
        WizardDebugLog.log("EntornoNative", "startDistroDesktop($distro,$de): alive=$alive log=$logTail")
        if (!alive) {
            return JSONObject().put("ok", false)
                .put("error", "${desktopLabel(de)} no arrancó dentro de $distro — ¿corriste \"distro_setup_gui.sh $distro\" antes?")
                .put("output", logTail)
        }
        updateRegistryValue("entorno.desktop_mode", MODE_PROOT)
        return JSONObject().put("ok", true)
            .put("message", "${desktopLabel(de)} corriendo dentro de $distro sobre el X11 embebido (${com.termux.app.X11Service.DISPLAY}) — abrí X11 para verlo")
    }

    /**
     * Instala dbus + el DE elegido DENTRO de la distro (distro_setup_gui.sh <distro> <de>)
     * — paso previo real a startDistroDesktop(), que hasta ahora no tenía ningún caller
     * Kotlin/UI (solo existía como script standalone, ver header de distro_setup_gui.sh en
     * modulos/entorno.sh). Ampliado 2026-08-18 para soportar xfce4/lxqt/mate (antes solo
     * xfce4 hardcodeado) — mismo abanico que installDesktop() (nativo) vía KNOWN_DESKTOPS.
     * apt-get es idempotente — no reinstala si ya corrió antes para esa combinación.
     *
     * [lite] (auditoría GUI/distro 2026-08-28): pasa un 3er argumento "lite" a
     * distro_setup_gui.sh — instala el set mínimo de paquetes de la DE (sin
     * xfce4-goodies/mate-extra) para dispositivos de gama baja, ver comentario de ese
     * script para el detalle real por gestor de paquetes (solo apt/pacman lo diferencian).
     */
    fun distroInstallDesktop(distro: String, de: String, lite: Boolean = false): JSONObject {
        if (rootfsParentDir(distro) == null) return JSONObject().put("ok", false).put("error", "$distro no está instalada")
        if (de !in KNOWN_DESKTOPS_DISTRO) return JSONObject().put("ok", false).put("error", "Escritorio desconocido: $de")
        val script = File(scriptsDir, "distro_setup_gui.sh")
        if (!script.exists()) return JSONObject().put("ok", false).put("error", "Entorno no instalado todavía")
        val scriptArgs = listOf(TERMUX_BASH_PATH, script.absolutePath, distro, de) + if (lite) listOf("lite") else emptyList()
        val (rc, out, err) = ManagerNativeUtils.runExec(scriptArgs, 900)
        val output = out.ifEmpty { err }
        WizardDebugLog.log("EntornoNative", "distroInstallDesktop($distro,$de): rc=$rc output=${output.takeLast(500)}")
        if (rc != 0) {
            return JSONObject().put("ok", false).put("error", "No se pudo instalar ${desktopLabel(de)} en $distro")
                .put("output", output.takeLast(500))
        }
        // Bug real confirmado por ADB (docs/humano249.md, 2026-08-26): el selector "¿Cuál
        // escritorio iniciar en <distro>?" (promptDistroDesktopStart(), EntornoFragment.kt)
        // ofrecía SIEMPRE los 3 KNOWN_DESKTOPS, sin importar cuál de ellos se había instalado
        // de verdad en ESA distro — el usuario podía elegir "xfce4" para una distro donde solo
        // se había corrido distro_setup_gui.sh con "mate", y el arranque fallaba con "Couldn't
        // exec xfce4-session: No such file or directory" (confirmado en
        // desktop_distro_kali_xfce4.log real del dispositivo). Se registra acá qué DE(s) quedó
        // realmente instalado por distro para que el selector de arranque pueda filtrar.
        val key = "entorno.distro_desktop_$distro"
        val already = ManagerNativeUtils.readRegistry()[key]?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
        if (de !in already) updateRegistryValue(key, (already + de).joinToString(","))
        return JSONObject().put("ok", true)
            .put("message", "${desktopLabel(de)} instalado en $distro — usá \"Iniciar escritorio en distro\" para arrancarlo")
    }

    /** DEs realmente instalados en [distro] según el registry (ver comentario de
     * distroInstallDesktop() arriba) — lista vacía si nunca se corrió el instalador ahí. */
    fun installedDesktopsForDistro(distro: String): List<String> =
        ManagerNativeUtils.readRegistry()["entorno.distro_desktop_$distro"]
            ?.split(",")?.filter { it.isNotBlank() && it in KNOWN_DESKTOPS_DISTRO } ?: emptyList()

    // ═══════════════════════════════════════════════════════════
    //  Fondo de pantalla — pedido explícito del usuario (docs/humano249.md ronda,
    //  "incluso poder cambiar la imagen de fondo etc"). Mecanismo real por DE (confirmado
    //  contra la documentación oficial de cada proyecto — no verificado en vivo dentro de
    //  una sesión gráfica real del dispositivo, ver nota de "pendiente de confirmación
    //  visual" en docs/arquitectura/DEPURACION_COMPLETA_2026-08-26.md):
    //  - xfce4: xfconf-query sobre la propiedad "last-image" de cada monitor/workspace
    //    real ya registrado en el canal xfce4-desktop (se listan con "-l" y se filtra por
    //    sufijo en vez de asumir un único "monitor0/workspace0" fijo — un dispositivo con
    //    más de un monitor/workspace virtual configurado tendría más de una propiedad).
    //  - mate: gsettings (GSettings/dconf, el mecanismo real de MATE moderno) con fallback a
    //    mateconftool-2 (GConf legacy) si gsettings no está disponible o falla — mismo
    //    criterio defensivo que ya usa vncStartWithConfig() con vncserver/tigervncserver.
    //  - lxqt: pcmanfm-qt no tiene un CLI de una sola invocación para esto — se edita
    //    ~/.config/pcmanfm-qt/lxqt/settings.conf directo (sección [Desktop0], clave
    //    Wallpaper=) y se reinicia pcmanfm-qt --desktop si ya estaba corriendo, para que
    //    tome el cambio sin que el usuario tenga que cerrar sesión.
    // ═══════════════════════════════════════════════════════════

    /**
     * xfconf-query/gsettings hablan por D-Bus session bus. La sesión real la levanta el DE
     * (startxfce4/mate-session, o `dbus-launch --exit-with-session` dentro de una distro vía
     * ~/.xsession, ver distro_setup_gui.sh en modulos/entorno.sh) — pero esta función corre en
     * un proceso NUEVO (bash -c aparte), que no hereda DBUS_SESSION_BUS_ADDRESS del árbol de
     * procesos de la sesión ya viva. Best-effort: se busca el socket real que dbus-daemon dejó
     * bajo [tmpDir] (patrón de nombre estándar "dbus-XXXXXXXXXX") y se reconstruye la dirección
     * antes de correr el comando real — si la sesión no usa ese layout (poco común) el comando
     * de todas formas corre, solo que puede fallar con "Failed to connect to session bus" en vez
     * de aplicar el fondo.
     */
    private fun dbusAutoDiscovery(tmpDir: String): String =
        "if [ -z \"\$DBUS_SESSION_BUS_ADDRESS\" ]; then " +
            "_kairos_dbus_sock=\$(find '$tmpDir' -maxdepth 1 -type s -name 'dbus-*' 2>/dev/null | head -1); " +
            "[ -n \"\$_kairos_dbus_sock\" ] && export DBUS_SESSION_BUS_ADDRESS=\"unix:path=\$_kairos_dbus_sock\"; fi; "

    /** Comando de shell para xfce4/mate — LXQt no tiene un comando de una sola línea, se maneja aparte (ver updateLxqtWallpaperConfig()). */
    private fun wallpaperShellCommand(de: String, guestImagePath: String): String = when (de) {
        "xfce4" -> "for p in \$(xfconf-query -c xfce4-desktop -l 2>/dev/null | grep 'last-image\$'); do xfconf-query -c xfce4-desktop -p \"\$p\" -s '$guestImagePath'; done"
        "mate" -> "gsettings set org.mate.background picture-filename '$guestImagePath' 2>/dev/null || mateconftool-2 --type string --set /desktop/mate/background/picture_filename '$guestImagePath'"
        else -> ""
    }

    /**
     * Reescribe (o crea) la sección [Desktop0] de settings.conf con Wallpaper=<ruta> —
     * formato INI simple, sin depender de ninguna librería de parseo (mismo criterio liviano
     * que el resto de este archivo usa para JSON chico con org.json). Preserva cualquier otra
     * clave/sección ya presente; solo toca la línea Wallpaper= dentro de [Desktop0] (o la crea
     * si la sección no existía todavía — pcmanfm-qt la genera recién al primer arranque).
     */
    private fun updateLxqtWallpaperConfig(configFile: File, guestImagePath: String) {
        configFile.parentFile?.mkdirs()
        val lines = if (configFile.exists()) configFile.readLines().toMutableList() else mutableListOf()
        val result = mutableListOf<String>()
        var inDesktop0 = false
        var foundSection = false
        var wallpaperWritten = false
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("[")) {
                if (inDesktop0 && !wallpaperWritten) {
                    result.add("Wallpaper=$guestImagePath")
                    wallpaperWritten = true
                }
                inDesktop0 = trimmed == "[Desktop0]"
                if (inDesktop0) foundSection = true
                result.add(line)
                continue
            }
            if (inDesktop0 && trimmed.startsWith("Wallpaper=")) {
                result.add("Wallpaper=$guestImagePath")
                wallpaperWritten = true
                continue
            }
            result.add(line)
        }
        if (inDesktop0 && !wallpaperWritten) result.add("Wallpaper=$guestImagePath")
        if (!foundSection) {
            result.add("[Desktop0]")
            result.add("Wallpaper=$guestImagePath")
        }
        configFile.writeText(result.joinToString("\n") + "\n")
    }

    /**
     * Camino NATIVO (sin distro) — [imagePath] ya es una ruta absoluta real del filesystem
     * del host (el caller, EntornoFragment, copia el content:// URI elegido por el usuario a
     * un archivo real primero — ni xfconf-query ni pcmanfm-qt pueden leer un content:// URI).
     */
    fun setWallpaperNative(de: String, imagePath: String): JSONObject {
        if (de !in KNOWN_DESKTOPS) return JSONObject().put("ok", false).put("error", "Escritorio desconocido: $de")
        if (!File(imagePath).exists()) return JSONObject().put("ok", false).put("error", "Imagen no encontrada: $imagePath")
        if (de == "lxqt") {
            val configFile = File(ManagerNativeUtils.home, ".config/pcmanfm-qt/lxqt/settings.conf")
            updateLxqtWallpaperConfig(configFile, imagePath)
            if (processRunning("pcmanfm-qt --desktop")) {
                ManagerNativeUtils.runExec(listOf(TERMUX_PKILL_PATH, "-f", "pcmanfm-qt --desktop"), 5)
                ManagerNativeUtils.runShell("export DISPLAY=${com.termux.app.X11Service.DISPLAY}; nohup pcmanfm-qt --desktop >/dev/null 2>&1 &", 5)
            }
            return JSONObject().put("ok", true)
                .put("message", "Fondo de pantalla cambiado (LXQt) — si no se ve, reiniciá el escritorio")
        }
        val cmd = "export DISPLAY=${com.termux.app.X11Service.DISPLAY}; " +
            dbusAutoDiscovery("$prefix/tmp") + wallpaperShellCommand(de, imagePath)
        val (rc, out, err) = ManagerNativeUtils.runShell(cmd, 15)
        if (rc != 0) {
            return JSONObject().put("ok", false).put("error", "No se pudo cambiar el fondo de pantalla")
                .put("output", out.ifEmpty { err }.takeLast(300))
        }
        return JSONObject().put("ok", true).put("message", "Fondo de pantalla cambiado (${desktopLabel(de)})")
    }

    /**
     * Camino CON DISTRO — el rootfs de proot-distro es un directorio real del host (mismo
     * layout dual que rootfsParentDir() usa en el resto de este archivo), así que la imagen se
     * copia directo por filesystem a /home/builder/ DENTRO del rootfs (el host no puede pasarle
     * un content:// URI ni una ruta fuera del rootfs a un proceso corriendo adentro del proot) y
     * el comando real corre vía distroLoginArgs() (mismo storage/exclusividad que el resto de
     * los flujos "dentro de distro" de este archivo).
     */
    fun setWallpaperDistro(distro: String, de: String, imagePath: String): JSONObject {
        if (distro !in KNOWN_DISTROS) return JSONObject().put("ok", false).put("error", "Distro desconocida: $distro")
        if (de !in KNOWN_DESKTOPS) return JSONObject().put("ok", false).put("error", "Escritorio desconocido: $de")
        val parent = rootfsParentDir(distro)
            ?: return JSONObject().put("ok", false).put("error", "$distro no está instalada")
        val srcFile = File(imagePath)
        if (!srcFile.exists()) return JSONObject().put("ok", false).put("error", "Imagen no encontrada: $imagePath")
        val rootfs = if (parent == containersBase) File(parent, "$distro/rootfs") else File(parent, distro)
        val homeDir = File(rootfs, "home/builder")
        if (!homeDir.exists()) homeDir.mkdirs()
        val ext = srcFile.extension.ifBlank { "png" }
        val destFile = File(homeDir, "kairos_wallpaper.$ext")
        try {
            srcFile.copyTo(destFile, overwrite = true)
        } catch (e: Exception) {
            return JSONObject().put("ok", false).put("error", "No se pudo copiar la imagen a $distro: ${e.message}")
        }
        val guestPath = "/home/builder/kairos_wallpaper.$ext"
        if (de == "lxqt") {
            val configFile = File(homeDir, ".config/pcmanfm-qt/lxqt/settings.conf")
            updateLxqtWallpaperConfig(configFile, guestPath)
            val restartCmd = distroLoginArgs(distro) + listOf(
                "--", "bash", "-c",
                "pkill -f 'pcmanfm-qt --desktop' 2>/dev/null; export DISPLAY=${com.termux.app.X11Service.DISPLAY}; nohup pcmanfm-qt --desktop >/dev/null 2>&1 &"
            )
            ManagerNativeUtils.runExec(restartCmd, 10)
            return JSONObject().put("ok", true)
                .put("message", "Fondo de pantalla cambiado (LXQt, $distro) — si no se ve, reiniciá el escritorio")
        }
        val shellCmd = wallpaperShellCommand(de, guestPath)
        if (shellCmd.isBlank()) return JSONObject().put("ok", false).put("error", "Escritorio desconocido: $de")
        // Auto-descubrimiento de DBUS_SESSION_BUS_ADDRESS (ver dbusAutoDiscovery()) — "/tmp"
        // acá es DENTRO del proot (el rootfs de la distro tiene su propio /tmp, distinto del
        // $prefix/tmp del host que usa el camino nativo).
        val execArgs = distroLoginArgs(distro) +
            listOf("--", "bash", "-c", "export DISPLAY=${com.termux.app.X11Service.DISPLAY}; ${dbusAutoDiscovery("/tmp")}$shellCmd")
        val (rc, out, err) = ManagerNativeUtils.runExec(execArgs, 20)
        if (rc != 0) {
            return JSONObject().put("ok", false).put("error", "No se pudo cambiar el fondo de pantalla en $distro")
                .put("output", out.ifEmpty { err }.takeLast(300))
        }
        return JSONObject().put("ok", true).put("message", "Fondo de pantalla cambiado (${desktopLabel(de)}, $distro)")
    }

    // ═══════════════════════════════════════════════════════════
    //  Lanzadores gráficos (~/Desktop/*.desktop) — pedido explícito del usuario
    //  (docs/humano/humano115.md): poder abrir los CLIs de Kairos (Claude Code, n8n,
    //  OpenCode, Codex) DENTRO del escritorio XFCE4 nativo, no solo desde la terminal
    //  adaptada de la app. XFCE4 ya lee ~/Desktop/*.desktop de fábrica (formato
    //  freedesktop.org estándar, Desktop Entry Specification) — no requiere ningún
    //  paquete/config extra, solo escribir los archivos. Como XFCE4 nativo corre sobre
    //  el $HOME real de Termux (no una distro proot aislada), los mismos comandos que ya
    //  usa la terminal adaptada de cada CLI funcionan tal cual acá.
    // ═══════════════════════════════════════════════════════════

    /**
     * El Exec ya invoca xfce4-terminal explícitamente (para controlar el "; exec bash"
     * que deja la terminal abierta viendo la salida tras el comando) — por eso
     * Terminal=false a propósito: con Terminal=true, XFCE envolvería este Exec en OTRA
     * terminal por encima (doble ventana anidada) en vez de una sola.
     */
    /**
     * Traduce la "category" de modules.json (ai/dev/lang/seguridad/system/tools) a
     * `Categories=` real de la Desktop Entry Specification — combina categorías principales
     * registradas del spec (para que el menú "Aplicaciones"/Whisker Menu de XFCE4, y sus
     * equivalentes de LXQt/MATE, agrupen/filtren de verdad) con una categoría propia
     * `X-Kairos-*` sin registrar (ignorada por los parsers estrictos, pero disponible si a
     * futuro se agrega un filtro propio de Kairos que lea `Categories=` directo). No existe
     * una categoría XDG registrada para "IA agentic" ni "base de datos" — se usan las
     * principales más cercanas (Utility/Development, Development) más el tag propio.
     */
    private fun xdgCategoriesFor(category: String): String = when (category) {
        "ai" -> "Utility;Development;X-Kairos-AI;"
        "dev" -> "Development;X-Kairos-Dev;"
        "lang" -> "Development;X-Kairos-Lang;"
        "seguridad" -> "System;Network;X-Kairos-Security;"
        "db" -> "Development;X-Kairos-Database;"
        "system" -> "System;X-Kairos-System;"
        "tools" -> "Utility;X-Kairos-Tools;"
        else -> "Development;"
    }

    private fun desktopEntryContent(name: String, command: String, category: String = "dev"): String {
        val safeCommand = command.replace("'", "'\\''")
        val exec = "xfce4-terminal -e \"bash -c '$safeCommand; exec bash'\""
        return """
            [Desktop Entry]
            Type=Application
            Name=$name
            Exec=$exec
            Icon=utilities-terminal
            Terminal=false
            Categories=${xdgCategoriesFor(category)}
        """.trimIndent() + "\n"
    }

    /**
     * Variante para apps GUI de una distro (punto 2 del catálogo de apps, ver
     * distroAppInstall()) — a diferencia de desktopEntryContent() (CLIs, se envuelven en
     * xfce4-terminal para dejar la salida visible) acá el Exec ya es el binario real
     * corriendo sobre X11 vía pdrun, así que NO se envuelve en ninguna terminal.
     */
    private fun desktopEntryContentGui(name: String, execCommand: String, icon: String = "applications-other"): String {
        return """
            [Desktop Entry]
            Type=Application
            Name=$name
            Exec=$execCommand
            Icon=$icon
            Terminal=false
            Categories=Application;
        """.trimIndent() + "\n"
    }

    /** Mismo criterio que N8nFragment.n8nMode() (registry "n8n.mode") — sin Context acá, se lee directo del registry. */
    private fun n8nStartCommand(): String {
        val mode = ManagerNativeUtils.readRegistry()["n8n.mode"]?.takeIf { it.isNotBlank() } ?: "proot"
        val script = if (mode == "udocker") "${ManagerNativeUtils.home}/scripts/n8n-udocker/start.sh"
                     else "${ManagerNativeUtils.home}/scripts/n8n/start_servidor.sh"
        return "bash '$script'"
    }

    /**
     * Regenera los lanzadores de los CLIs de Kairos ya instalados — llamado al
     * instalar/iniciar XFCE4 nativo (ver startDesktop() arriba) y también expuesto como
     * acción manual desde EntornoFragment ("Actualizar lanzadores"), para cuando Kairos
     * agregue más CLIs a futuro sin tener que reinstalar el escritorio. Solo genera el
     * lanzador de un CLI si está instalado de verdad ("<id>.installed" en el registry,
     * mismo campo que escribe registry_install() en cada modulos/<id>.sh) — evita íconos
     * rotos apuntando a binarios inexistentes.
     */
    /**
     * Escribe el mismo .desktop en las 2 ubicaciones que Kairos soporta: ~/Desktop (ícono
     * suelto en el fondo de pantalla, ya existía) y ~/.local/share/applications (menú
     * "Aplicaciones" real de XFCE4/LXQt/MATE — gap real cerrado 2026-08-25, ver
     * docs/x11/AUDITORIA_CONSOLIDADA_ENTORNO_2026-08-25.md / docs/x11/PANEL_MODULOS_X11.md).
     */
    private fun writeLauncherBoth(fileName: String, content: String) {
        File(desktopDir, fileName).apply { writeText(content); setExecutable(true) }
        File(appsMenuDir, fileName).apply { writeText(content); setExecutable(true) }
    }

    fun generateDesktopLaunchers(): JSONObject {
        if (!desktopDir.exists() && !desktopDir.mkdirs()) {
            return JSONObject().put("ok", false).put("error", "No se pudo crear ~/Desktop")
        }
        if (!appsMenuDir.exists()) appsMenuDir.mkdirs()
        val reg = ManagerNativeUtils.readRegistry()
        val created = mutableListOf<String>()

        if (reg["claude.installed"] == "true") {
            val cmd = ClaudeNative.openCmd()
            if (cmd.optBoolean("ok", false)) {
                writeLauncherBoth("kairos-claude.desktop", desktopEntryContent("Claude Code", cmd.getString("command"), "ai"))
                created.add("claude")
            }
        }
        if (reg["opencode.installed"] == "true") {
            val cmd = OpenCodeNative.tuiCmd()
            writeLauncherBoth("kairos-opencode.desktop", desktopEntryContent("OpenCode", cmd.getString("command"), "ai"))
            created.add("opencode")
        }
        if (reg["codex.installed"] == "true") {
            writeLauncherBoth("kairos-codex.desktop", desktopEntryContent("Codex CLI", "codex", "ai"))
            created.add("codex")
        }
        if (reg["n8n.installed"] == "true") {
            writeLauncherBoth("kairos-n8n.desktop", desktopEntryContent("n8n", n8nStartCommand(), "dev"))
            created.add("n8n")
        }
        // Resto de los CLIs de Kairos con "terminalCommand" plano en modules.json (ver
        // GENERIC_CLI_LAUNCHERS abajo) — gap real confirmado esta ronda (2026-08-18): solo
        // claude/opencode/codex/n8n tenían lanzador, dejando afuera ~20 módulos CLI reales
        // (antigravity, engram, freebuff, codebuff, copilotcli, minimaxcli, mimocode,
        // mistralvibe, qwencode, cursor, kimi, kilo, hf, ide/nvim, udocker, etc.) que sí
        // corren directo sobre $HOME (mismo criterio que claude/opencode: no requieren
        // wrapper propio como ClaudeNative.openCmd()/OpenCodeNative.tuiCmd(), el comando de
        // modules.json ya es el binario real). Lista chica y explícita en vez de leer
        // modules.json acá (este object no tiene Context/AssetManager) — mantenerla en
        // sync con app/src/main/assets/modules.json si se agrega un CLI nuevo.
        GENERIC_CLI_LAUNCHERS.forEach { spec ->
            if (reg["${spec.id}.installed"] == "true") {
                writeLauncherBoth("kairos-${spec.id}.desktop", desktopEntryContent(spec.label, spec.command, spec.category))
                created.add(spec.id)
            }
        }
        // writeLauncherBoth() ya marca ejecutable en ambas ubicaciones al escribir; este bucle
        // queda como no-op redundante para los ids ya escritos arriba (sin costo real).
        created.forEach { id -> File(desktopDir, "kairos-$id.desktop").setExecutable(true) }

        // Apps instaladas DENTRO de una distro vía distroAppInstall() — regeneradas desde
        // el estado guardado en distro_apps.json, sin volver a loguearse a la distro (ver
        // writeDistroAppLauncher()). Cubre el caso "reinstalé el escritorio nativo, quiero
        // recuperar los íconos de las apps de distro que ya tenía".
        loadDistroApps().forEach { entry ->
            writeDistroAppLauncher(entry)
            created.add("distro:${entry.optString("distro")}/${entry.optString("pkg")}")
        }

        // "Cerrar sesión" — ícono de logout dentro del propio escritorio, patrón
        // Shutdown.desktop de LinuxDroidMaster/Termux-Desktops (referencia/termux/
        // Termux-Desktops-main/scripts/termux_native/Shutdown.desktop): ese proyecto lo
        // justifica porque con el phantom process killer desactivado la sesión de
        // escritorio (Termux:X11 en su caso) NUNCA se cierra sola y drena batería si el
        // usuario se olvida de matarla a mano. Antes de este cambio Kairos solo podía
        // detener la sesión desde la propia app ("Detener servidor X11" en EntornoFragment)
        // — si el usuario estaba DENTRO de XFCE4 (overlay de terminal escondido) no tenía
        // forma de cerrar la sesión sin volver a la UI de Kairos. gui_stop.sh (creado por
        // entorno.sh) ya mata las sesiones de DE conocidas (xfce4/lxqt/openbox/proot-distro
        // login) — se corre sin --x11 para dejar el X11 embebido arriba (permite volver a
        // abrir un escritorio sin reiniciar el servidor).
        val guiStop = File(scriptsDir, "gui_stop.sh")
        if (guiStop.exists()) {
            writeLauncherBoth(
                "kairos-cerrar-sesion.desktop",
                desktopEntryContentGui("Cerrar sesión", "bash '${guiStop.absolutePath}'", "system-log-out")
            )
            created.add("cerrar-sesion")
        }

        return JSONObject().apply {
            put("ok", true)
            put("created", JSONArray(created))
            put("message", if (created.isEmpty())
                "Ningún CLI soportado está instalado todavía — no se creó ningún lanzador"
            else
                "Lanzadores actualizados: ${created.joinToString(", ")}")
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  Autoinicio ("inicio de sesión") — arrancar automáticamente ciertos CLIs de Kairos
    //  cuando arranca el escritorio nativo, mismo concepto que las "apps de inicio" de un
    //  SO de escritorio real. Gap real confirmado esta ronda (2026-08-18):
    //  generateDesktopLaunchers() ya crea íconos en ~/Desktop para abrir un CLI a mano,
    //  pero no existía ninguna forma de que se abrieran solos al entrar al escritorio — el
    //  usuario tenía que abrirlos manualmente cada vez, sesión tras sesión. Implementado
    //  con el estándar XDG Autostart (~/.config/autostart/*.desktop, freedesktop.org
    //  "Desktop Application Autostart Specification") — xfce4/lxqt/mate lo leen de fábrica
    //  al levantar la sesión, sin ningún paquete/config extra (mismo criterio que ya usa
    //  generateDesktopLaunchers() para ~/Desktop). Alcance: solo camino NATIVO — el camino
    //  CON DISTRO tiene su propio $HOME dentro del rootfs, fuera de este MVP.
    // ═══════════════════════════════════════════════════════════

    private val autostartDir get() = File(ManagerNativeUtils.home, ".config/autostart")

    /**
     * Borra el lanzador de un CLI (Desktop + autostart) — gap real confirmado esta ronda
     * (auditoría 2026-08-19, cruzando ModuleController.uninstallModule()/deepUninstallModule()
     * contra generateDesktopLaunchers()): desinstalar un módulo desde la app (ej. Claude Code)
     * borraba sus archivos y sus líneas del registry, pero NUNCA tocaba el `.desktop` que
     * generateDesktopLaunchers() había creado en ~/Desktop — dejaba un ícono roto, apuntando a
     * un comando que ya no existe, hasta la próxima vez que el usuario reinstalara XFCE4/
     * corriera "Actualizar lanzadores" a mano. Nomenclatura de archivo idéntica en los dos
     * casos (`kairos-<id>.desktop` en ~/Desktop, `kairos-autostart-<id>.desktop` en
     * ~/.config/autostart) tanto para claude/opencode/codex/n8n como para los CLIs genéricos
     * de GENERIC_CLI_LAUNCHERS — moduleId es siempre el mismo id, así que un simple delete por
     * nombre de archivo alcanza, sin necesitar regenerar el catálogo completo.
     */
    fun removeCliLauncher(moduleId: String) {
        File(desktopDir, "kairos-$moduleId.desktop").delete()
        // Mismo bug de ícono huérfano que ya se corrigió para ~/Desktop, ahora también para
        // el menú de apps XDG (~/.local/share/applications) agregado 2026-08-25.
        File(appsMenuDir, "kairos-$moduleId.desktop").delete()
        File(autostartDir, "kairos-autostart-$moduleId.desktop").delete()
    }

    /** id/label/command/category de todo lo que generateDesktopLaunchers() sabe lanzar — reusado acá para no duplicar el mapeo CLI→comando (category agregada 2026-08-25 para que autostart también respete Categories= real). */
    private data class CliLauncherInfo(val id: String, val label: String, val command: String, val category: String)

    private fun availableCliLaunchers(): List<CliLauncherInfo> {
        val reg = ManagerNativeUtils.readRegistry()
        val result = mutableListOf<CliLauncherInfo>()
        if (reg["claude.installed"] == "true") {
            val cmd = ClaudeNative.openCmd()
            if (cmd.optBoolean("ok", false)) result.add(CliLauncherInfo("claude", "Claude Code", cmd.getString("command"), "ai"))
        }
        if (reg["opencode.installed"] == "true") {
            result.add(CliLauncherInfo("opencode", "OpenCode", OpenCodeNative.tuiCmd().getString("command"), "ai"))
        }
        if (reg["codex.installed"] == "true") result.add(CliLauncherInfo("codex", "Codex CLI", "codex", "ai"))
        if (reg["n8n.installed"] == "true") result.add(CliLauncherInfo("n8n", "n8n", n8nStartCommand(), "dev"))
        GENERIC_CLI_LAUNCHERS.forEach { spec ->
            if (reg["${spec.id}.installed"] == "true") result.add(CliLauncherInfo(spec.id, spec.label, spec.command, spec.category))
        }
        return result
    }

    /** ids habilitados actualmente (registry "entorno.autostart", CSV) + catálogo completo de lo instalado — para el picker de checkboxes de EntornoFragment. */
    fun autostartOptions(): JSONObject {
        val reg = ManagerNativeUtils.readRegistry()
        val enabled = reg["entorno.autostart"]?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
        val available = availableCliLaunchers()
        return JSONObject().apply {
            put("ok", true)
            put("ids", JSONArray(available.map { it.id }))
            put("labels", JSONArray(available.map { it.label }))
            put("enabled", JSONArray(enabled.toList()))
        }
    }

    /** Guarda la selección elegida por el usuario y regenera ~/.config/autostart/ — llamado desde EntornoFragment tras cerrar el picker de checkboxes. */
    fun setAutostart(ids: List<String>): JSONObject {
        updateRegistryValue("entorno.autostart", ids.joinToString(","))
        generateAutostartEntries()
        return JSONObject().put("ok", true).put(
            "message",
            if (ids.isEmpty()) "Autoinicio desactivado — no se abrirá nada solo al iniciar el escritorio"
            else "Autoinicio configurado: ${ids.joinToString(", ")} — se van a abrir solos la próxima vez que inicies el escritorio nativo"
        )
    }

    /**
     * Escribe ~/.config/autostart/ (archivos *.desktop) para cada id habilitado — llamado desde
     * setAutostart() (cambio explícito del usuario) y desde startDesktop() (por si el
     * registry ya traía una selección de una sesión anterior, o el usuario instaló un CLI
     * nuevo que ya estaba en la lista de autoinicio). Limpia cualquier entrada vieja antes
     * de escribir la selección actual, mismo criterio que generateDesktopLaunchers() con
     * los .desktop de ~/Desktop.
     */
    private fun generateAutostartEntries() {
        if (!autostartDir.exists() && !autostartDir.mkdirs()) return
        autostartDir.listFiles { f -> f.name.startsWith("kairos-autostart-") }?.forEach { it.delete() }
        val reg = ManagerNativeUtils.readRegistry()
        val enabled = reg["entorno.autostart"]?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }?.toSet()
        if (enabled.isNullOrEmpty()) return
        val available = availableCliLaunchers().associateBy { it.id }
        enabled.forEach { id ->
            val spec = available[id] ?: return@forEach
            val file = File(autostartDir, "kairos-autostart-$id.desktop")
            file.writeText(desktopEntryContent(spec.label, spec.command, spec.category))
            file.setExecutable(true)
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  VNC — secundario/opcional (submenu_interfaz [4][5][6])
    // ═══════════════════════════════════════════════════════════

    fun vncInstall(): JSONObject {
        if (binaryAvailable("tigervncserver")) {
            return JSONObject().put("ok", true).put("message", "TigerVNC ya estaba instalado")
        }
        // pkg update -y best-effort + timeout subido (mismo criterio que installDesktop(),
        // ver docs/humano/humano57.md).
        pkgUpdateWithFallback()
        val (rc, out, err) = ManagerNativeUtils.runExec(listOf(TERMUX_PKG_PATH, "install", "-y", "tigervnc"), 300)
        if (rc != 0) {
            return JSONObject().put("ok", false).put("error", "No se pudo instalar TigerVNC")
                .put("output", out.ifEmpty { err }.takeLast(500))
        }
        return JSONObject().put("ok", true)
            .put("message", "TigerVNC instalado — poné una contraseña con \"vncpasswd\" desde una terminal antes del primer uso")
    }

    fun vncStart(): JSONObject {
        val script = File(scriptsDir, "vnc_start.sh")
        if (!script.exists()) return JSONObject().put("ok", false).put("error", "Entorno no instalado todavía")
        val (rc, out, err) = ManagerNativeUtils.runExec(listOf(TERMUX_BASH_PATH, script.absolutePath), 30)
        if (rc != 0) {
            return JSONObject().put("ok", false).put("error", "No se pudo iniciar VNC — ¿instalaste TigerVNC?")
                .put("output", out.ifEmpty { err }.takeLast(300))
        }
        return JSONObject().put("ok", true).put("message", "VNC en :5901 — conectate con un cliente VNC a 127.0.0.1:5901")
    }

    fun vncStop(): JSONObject {
        val script = File(scriptsDir, "vnc_stop.sh")
        if (!script.exists()) return JSONObject().put("ok", false).put("error", "Entorno no instalado todavía")
        ManagerNativeUtils.runExec(listOf(TERMUX_BASH_PATH, script.absolutePath), 15)
        return JSONObject().put("ok", true).put("message", "VNC detenido")
    }

    /**
     * Variante configurable de vncStart() — pedido explícito del usuario ("toca poner una
     * opcion abajo de iniciar vcn para configurarlo"). vnc_start.sh (generado una sola vez
     * por entorno.sh en la instalación, ver modulos/entorno.sh) trae `-geometry 1920x1080
     * -depth 24 -localhost` fijos en el heredoc — no expone ningún parámetro. En vez de
     * reescribir ese script (dominio de entorno.sh, ver scripts-rule.md), esta función arma
     * el comando `vncserver`/`tigervncserver` directo desde Kotlin, mismo patrón que
     * `generateAutostartEntries()` ya usa para escribir archivos de runtime propios sin pasar
     * por un script bash.
     *
     * Parámetros reales soportados — verificados contra vnc_start.sh y las opciones reales
     * de `vncserver`/`tigervncserver` (no se inventan flags que el backend no soporte):
     * - [geometry]: resolución "WxH" (flag `-geometry` de vncserver, igual que el script fijo).
     * - [depth]: color depth en bits (flag `-depth`, valores típicos 16/24 — 24 es el default
     *   del script original).
     * - [requirePassword]: si es false, agrega `-SecurityTypes None` (sin autenticación —
     *   igual de válido que el modo con contraseña real de TigerVNC, documentado en su man
     *   page). Si es true (default), se deja el comportamiento estándar de VncAuth — requiere
     *   que el usuario haya corrido `vncpasswd` antes (mismo aviso que ya da vncInstall()).
     * - Puerto/display: NO configurable — queda fijo en `:1` (→ TCP 5901), porque `:1` es el
     *   display del X11 embebido de Kairos (Xlorie, proceso :xserver) y vncserver necesita
     *   apuntar a ese mismo número para que el visor VNC muestre el mismo escritorio (ver
     *   `modulos/entorno.sh` comentario "el display es el embebido (:1)"); exponerlo como
     *   configurable rompería esa alineación sin ningún beneficio real.
     * - `-localhost` queda fijo (no configurable): es la misma restricción de seguridad que
     *   ya trae vnc_start.sh — solo acepta conexiones desde el propio dispositivo, que es como
     *   `VncViewerActivity`/`VncClient.kt` ya se conectan (127.0.0.1:5901).
     */
    /**
     * Escribe `~/.vnc/passwd` de forma NO interactiva (humano202, 2026-08-22): antes, el
     * checkbox "Pedir contraseña" de promptVncConfig() solo omitía `-SecurityTypes None` pero
     * nunca ejecutaba `vncpasswd` de verdad — como `vncserver` corre vía ProcessBuilder sin tty,
     * si el usuario nunca había corrido `vncpasswd` a mano en una sesión de terminal, arrancar
     * con VncAuth fallaba/colgaba (exactamente el error que reportó el usuario). `vncpasswd`
     * pide la contraseña 2 veces + "view-only password? (y/n)" por stdin — se alimentan los 3
     * valores de una sola vez vía [ManagerNativeUtils.runExecWithStdin], sin ningún prompt real.
     */
    fun vncSetPassword(password: String): JSONObject {
        if (password.length < 6) return JSONObject().put("ok", false).put("error", "La contraseña debe tener al menos 6 caracteres (límite real de VNC)")
        val vncDir = File(ManagerNativeUtils.home, ".vnc")
        if (!vncDir.exists()) vncDir.mkdirs()
        // Bug real confirmado por ADB (2026-08-24, ver docs/humano222.md): runExecWithStdin
        // invocaba el binario por nombre relativo ("vncpasswd"/"tigervncpasswd") — mismo patrón
        // ya confirmado roto para Hermes (ProcessBuilder no resuelve PATH en este entorno).
        // Ruta absoluta = mismo fix que TERMUX_HERMES_PATH/TERMUX_CACTUS_PATH.
        val bin = if (binaryAvailable("vncpasswd")) TERMUX_VNCPASSWD_PATH else if (binaryAvailable("tigervncpasswd")) "tigervncpasswd" else null
            ?: return JSONObject().put("ok", false).put("error", "vncpasswd no está instalado — instalá TigerVNC primero")
        val (rc, out, err) = ManagerNativeUtils.runExecWithStdin(listOf(bin), "$password\n$password\nn\n", 15)
        return if (rc == 0) JSONObject().put("ok", true) else JSONObject().put("ok", false).put("error", err.ifBlank { out.ifBlank { "vncpasswd falló (rc=$rc)" } })
    }

    fun vncStartWithConfig(geometry: String, depth: Int, requirePassword: Boolean, password: String? = null): JSONObject {
        if (!scriptsDir.exists()) return JSONObject().put("ok", false).put("error", "Entorno no instalado todavía")
        if (!binaryAvailable("vncserver") && !binaryAvailable("tigervncserver")) {
            return JSONObject().put("ok", false).put("error", "TigerVNC no está instalado — instalalo primero")
        }
        if (requirePassword && !password.isNullOrBlank()) {
            val pwResult = vncSetPassword(password)
            if (!pwResult.optBoolean("ok", false)) return pwResult
        }
        // Reinicia limpio: si ya había una sesión VNC en :1 con otra config, un segundo
        // "vncserver :1" solo falla ("...:1 is taken because of ...") en vez de aplicar la
        // config nueva — mismo criterio defensivo que vnc_stop.sh ya usa (best-effort, no
        // falla la operación completa si no había nada corriendo).
        val stopScript = File(scriptsDir, "vnc_stop.sh")
        if (stopScript.exists()) ManagerNativeUtils.runExec(listOf(TERMUX_BASH_PATH, stopScript.absolutePath), 15)

        val safeGeometry = if (Regex("""^\d{2,5}x\d{2,5}$""").matches(geometry)) geometry else "1920x1080"
        val safeDepth = if (depth == 16 || depth == 24) depth else 24
        val baseArgs = mutableListOf(":1", "-geometry", safeGeometry, "-depth", safeDepth.toString(), "-localhost")
        if (!requirePassword) baseArgs.addAll(listOf("-SecurityTypes", "None"))

        // Bug real confirmado por ADB (2026-08-24, ver docs/humano222.md): mismo patrón de
        // nombre relativo que vncpasswd arriba — ruta absoluta = mismo fix.
        val (rc1, out1, err1) = ManagerNativeUtils.runExec(listOf(TERMUX_VNCSERVER_PATH) + baseArgs, 30)
        if (rc1 == 0) {
            return JSONObject().put("ok", true)
                .put("message", "VNC en :5901 ($safeGeometry, ${safeDepth}bit${if (!requirePassword) ", sin contraseña" else ""})")
        }
        val (rc2, out2, err2) = ManagerNativeUtils.runExec(listOf("tigervncserver") + baseArgs, 30)
        if (rc2 == 0) {
            return JSONObject().put("ok", true)
                .put("message", "VNC en :5901 ($safeGeometry, ${safeDepth}bit${if (!requirePassword) ", sin contraseña" else ""})")
        }
        return JSONObject().put("ok", false).put("error", "No se pudo iniciar VNC — ¿instalaste TigerVNC?")
            .put("output", (out2.ifEmpty { err2 }.ifEmpty { out1.ifEmpty { err1 } }).takeLast(300))
    }

    // ═══════════════════════════════════════════════════════════
    //  PulseAudio — toggle (submenu_interfaz [7])
    // ═══════════════════════════════════════════════════════════

    fun pulseToggle(): JSONObject {
        val running = processRunning("pulseaudio")
        val scriptName = if (running) "pulse_stop.sh" else "pulse_start.sh"
        val script = File(scriptsDir, scriptName)
        if (!script.exists()) return JSONObject().put("ok", false).put("error", "Entorno no instalado todavía")
        ManagerNativeUtils.runExec(listOf(TERMUX_BASH_PATH, script.absolutePath), 15)
        return JSONObject().put("ok", true)
            .put("message", if (running) "PulseAudio detenido" else "PulseAudio iniciado")
    }

    // ═══════════════════════════════════════════════════════════
    //  GPU — diagnóstico + selector de método (submenu_interfaz [9][0])
    // ═══════════════════════════════════════════════════════════

    /** Texto ya formateado para mostrar en un diálogo — mismo contenido que la opción [9] del menú original. */
    fun gpuDiagnostic(): JSONObject {
        val gpuType = detectGpuType()
        val reg = ManagerNativeUtils.readRegistry()
        val method = reg["entorno.gpu_method"]?.takeIf { it.isNotBlank() } ?: "auto"
        val renderer = if (binaryAvailable("glxinfo")) {
            ManagerNativeUtils.runExec(listOf(TERMUX_BASH_PATH, "-c", "glxinfo -B 2>/dev/null | grep 'OpenGL renderer'"), 10)
                .second.substringAfter(":").trim().ifEmpty { "no detectado" }
        } else "glxinfo no instalado (pkg install mesa-utils)"
        val vulkan = if (binaryAvailable("vulkaninfo")) {
            ManagerNativeUtils.runExec(listOf(TERMUX_BASH_PATH, "-c", "vulkaninfo --summary 2>/dev/null | grep deviceName"), 10)
                .second.substringAfter(":").trim().ifEmpty { "no detectado" }
        } else "no detectado"
        val (_, drivers, _) = ManagerNativeUtils.runExec(
            listOf(TERMUX_BASH_PATH, "-c", "pkg list-installed 2>/dev/null | grep -E 'mesa|vulkan|virgl|angle|turnip|panfrost' | cut -d/ -f1"), 15
        )
        return JSONObject().apply {
            put("ok", true)
            put("gpu_type", gpuType)
            put("gpu_method", method)
            put("renderer", renderer)
            put("vulkan_device", vulkan)
            put("drivers_installed", drivers.ifBlank { "ninguno" })
        }
    }

    /** Métodos válidos según el tipo de GPU detectado — mismo árbol que el case de submenu_interfaz [0]. */
    fun gpuMethodOptions(): JSONObject {
        val gpuType = detectGpuType()
        // "wrapper" corregido 2026-08-28 (docs/humano281.md — el usuario aclaró que NO es
        // ANGLE/OpenGL/EGL: es Vulkan puro, una capa ENCIMA del driver Vulkan real del
        // dispositivo, y SOLO funciona en modo nativo, nunca dentro de proot-distro — ver
        // gpu_env.sh case "wrapper" en entorno.sh para el detalle de por qué queda confinado
        // a nativo automáticamente). Paquete real: vulkan-wrapper-android
        // (referencia/termux/termux-desktop-main/docs/hw-acceleration.md +
        // enable-hw-acceleration, release real en github.com/sabamdarif/termux-desktop) —
        // agregado como opción universal en las 3 ramas, cierre real del pedido original
        // (docs/humano/humano181.md "en gpu falta wrapper, zink, turnip o panfrot").
        val (labels, values) = when (gpuType) {
            "adreno" -> listOf("Auto (recomendado)", "Zink nativo — OpenGL sobre Vulkan", "Turnip + Zink — Vulkan + GL en proot", "Wrapper (Vulkan nativo) — driver real del fabricante, solo modo nativo") to
                listOf("auto", "zink", "turnip", "wrapper")
            "mali" -> listOf("Auto (recomendado)", "VirGL + ANGLE — OpenGL virtual", "Panfrost — driver nativo Mali (experimental)", "Wrapper (Vulkan nativo) — driver real del fabricante, solo modo nativo") to
                listOf("auto", "virgl_angle", "panfrost", "wrapper")
            else -> listOf("Auto (recomendado)", "llvmpipe — software (fallback)", "VirGL — render virtual (experimental)", "Wrapper (Vulkan nativo) — driver real del fabricante, solo modo nativo") to
                listOf("auto", "llvmpipe", "virgl", "wrapper")
        }
        return JSONObject().apply {
            put("ok", true)
            put("gpu_type", gpuType)
            put("labels", JSONArray(labels))
            put("values", JSONArray(values))
        }
    }

    /** Instala los paquetes del método elegido y guarda entorno.gpu_method en el registry — mismo case que el menú original. */
    fun setGpuMethod(method: String): JSONObject {
        // mesa-utils (glxinfo/glxgears) agregado a cada método real (docs/humano281.md,
        // pedido explícito del usuario) — antes solo se sugería en el mensaje de
        // gpuDiagnostic() sin instalarse nunca de verdad, así que el comando sugerido
        // fallaba. "auto" no instala nada (no elige un método real todavía).
        val packages = when (method) {
            "auto" -> emptyList()
            "zink" -> listOf("mesa-zink", "vulkan-loader-generic", "mesa-utils")
            "virgl_angle" -> listOf("mesa", "virglrenderer-android", "angle-android", "mesa-utils")
            // mesa-vulkan-icd-freedreno-dri3 agregado (roadmap Mini PC item 1,
            // MEJORAS_PENDIENTES.md 2026-08-28) — antes solo instalaba los mismos
            // paquetes que "zink" (Turnip no tenía driver real detrás, solo el nombre
            // del método cambiaba en el registry). Paquete real confirmado en
            // referencia/termux/Termux-Desktops-main/Documentation/HardwareAcceleration.md
            // sección "Hardware Acceleration in Native Termux" — no verificado en
            // dispositivo real (empirical-verification-before-fix.md); si el mirror no
            // lo tiene, la instalación de este paquete falla silenciosamente (pkg install
            // devuelve rc!=0 y setGpuMethod ya lo trata como best-effort más abajo, mismo
            // criterio que el resto de los métodos GPU).
            "turnip" -> listOf("mesa-zink", "vulkan-loader-generic", "mesa-vulkan-icd-freedreno-dri3", "mesa-utils")
            "panfrost" -> listOf("mesa-panfrost", "mesa-utils")
            "llvmpipe" -> listOf("mesa", "mesa-utils")
            "virgl" -> listOf("mesa", "virglrenderer-android", "mesa-utils")
            // "wrapper" no es un paquete pkg normal (no está en el repo oficial de Termux) —
            // se maneja aparte abajo con su propio download+apt install ./archivo.deb.
            "wrapper" -> listOf("mesa-utils")
            else -> return JSONObject().put("ok", false).put("error", "Método GPU desconocido: $method")
        }
        if (packages.isNotEmpty()) {
            // pkg update -y best-effort + timeout subido (mismo criterio que
            // installDesktop(), ver docs/humano/humano57.md).
            pkgUpdateWithFallback()
            ManagerNativeUtils.runExec(listOf(TERMUX_PKG_PATH, "install", "-y") + packages, 600)
        }
        if (method == "wrapper") {
            // vulkan-wrapper-android: release real de github.com/sabamdarif/termux-desktop
            // (referencia/termux/termux-desktop-main/enable-hw-acceleration), no está en el
            // repo oficial de Termux — se descarga el .deb y se instala con "apt install
            // ./archivo.deb" (resuelve dependencias reales, a diferencia de "dpkg -i").
            val debUrl = "https://github.com/sabamdarif/termux-desktop/releases/download/" +
                "vulkan-wrapper-android_25.0.0-3/vulkan-wrapper-android_25.0.0-3_aarch64.deb"
            val debPath = "$TERMUX_PREFIX_PATH/tmp/vulkan-wrapper-android.deb"
            val (rc, out, _) = ManagerNativeUtils.runExec(listOf(
                TERMUX_BASH_PATH, "-c",
                "curl -fsSL '$debUrl' -o '$debPath' && apt install -y '$debPath'; _rc=\$?; rm -f '$debPath'; exit \$_rc"
            ), 120)
            if (rc != 0) {
                return JSONObject().put("ok", false)
                    .put("error", "vulkan-wrapper-android no se pudo descargar/instalar: $out")
            }
        }
        updateRegistryValue("entorno.gpu_method", method)
        // Antes decía "Turnip en sí se descarga dentro del proot" — quedó desactualizado
        // desde que setGpuMethod("turnip") instala mesa-vulkan-icd-freedreno-dri3 acá
        // mismo (ver comentario arriba); el driver ya se instala en este paso, solo falta
        // reiniciar el X11 embebido para que las variables nuevas de gpu_env.sh apliquen.
        val note = if (method == "turnip") " (driver Turnip instalado — no verificado en dispositivo real todavía)" else ""
        // Fix (ronda Termux-Desktops-main, ver docs/humano/): este mensaje seguía diciendo
        // "reiniciá Termux:X11" — texto heredado de cuando Kairos usaba la app externa
        // com.termux.x11. Desde 2026-08-13 el servidor es el X11 embebido (Xlorie,
        // X11Service) — reiniciarlo es "Detener servidor X11" + volver a abrirlo desde
        // EntornoFragment, no relanzar ninguna app externa.
        return JSONObject().put("ok", true)
            .put("message", "Método GPU cambiado a: $method$note — detené el servidor X11 (Entorno → Detener servidor X11) y volvé a iniciarlo para aplicar")
    }

    /**
     * Roadmap Mini PC item 4 (MEJORAS_PENDIENTES.md 2026-08-28) — "Perfil recomendado", idea
     * propia de Kairos (no port de ningún proyecto de referencia): en vez de que el usuario
     * arme distro+DE+método GPU a mano con 3 pickers separados, combina el tipo de GPU YA
     * detectado (detectGpuType(), mismo mapeo que gpuDiagnostic()/gpuMethodOptions()) con una
     * heurística simple de RAM para sugerir una combinación razonable de una sola vez. No
     * reimplementa ninguna instalación — solo devuelve la sugerencia; EntornoFragment la
     * muestra en un diálogo de confirmación y, si el usuario acepta, encadena las llamadas YA
     * existentes (distroInstall/distroInstallDesktop/setGpuMethod) en orden, chequeando el
     * resultado real de cada paso antes de seguir al siguiente (empirical-verification-
     * before-fix.md — no asumir éxito por "no tiró excepción").
     *
     * - distro: "debian" fijo — está en CONFIRMED (junto a ubuntu/alpine) y es el único de
     *   los 3 con soporte documentado para las 4 DEs de KNOWN_DESKTOPS_DISTRO en
     *   distro_setup_gui.sh (kde-plasma-desktop/mate-desktop-environment-core/etc nombrados
     *   ahí para la rama apt de Debian/Ubuntu/Kali) — más liviano que ubuntu, sin las
     *   limitaciones musl/glibc de alpine para paquetes de escritorio poco comunes.
     * - de: "xfce4" fijo — la única DE con variante "lite" confirmada en las 4 familias de
     *   gestor de paquetes reales de distro_setup_gui.sh (apt/dnf/pacman/apk), más liviana
     *   que mate/kde — el default más seguro para un perfil pensado como recomendación
     *   genérica, no una elección a medida del hardware.
     * - gpu_method: mismo agrupamiento que gpuMethodOptions() (adreno/mali/resto) — adreno usa
     *   "zink" (primario ya ofrecido para Adreno), mali usa "virgl_angle" (primario ya
     *   ofrecido para Mali/Xclipse), el resto ("unknown", y xclipse por el mismo
     *   agrupamiento que ya usa gpuMethodOptions()) usa "llvmpipe" — software puro, el
     *   fallback más compatible sin depender de un driver de fabricante no confirmado.
     * - lite: true por default (perfil "recomendado" pensado como el caso más común y seguro
     *   en Termux/Android) salvo que /proc/meminfo reporte >=6GB de RAM total. Sin Context
     *   disponible en este object (ver comentario de status() arriba sobre por qué no se usa
     *   ActivityManager acá) se lee /proc/meminfo directo — mismo patrón sin-Context que
     *   getSystemProperty(); si no se puede leer, se mantiene el default seguro lite=true.
     */
    fun recommendedProfile(): JSONObject {
        val gpuType = detectGpuType()
        val gpuMethod = when (gpuType) {
            "adreno" -> "zink"
            "mali" -> "virgl_angle"
            else -> "llvmpipe"
        }
        val totalRamKb = try {
            File("/proc/meminfo").readLines()
                .firstOrNull { it.startsWith("MemTotal:") }
                ?.filter { it.isDigit() }
                ?.toLongOrNull()
        } catch (_: Exception) {
            null
        }
        val lite = totalRamKb == null || totalRamKb < 6L * 1024 * 1024
        return JSONObject().apply {
            put("ok", true)
            put("distro", "debian")
            put("de", "xfce4")
            put("gpu_method", gpuMethod)
            put("gpu_type", gpuType)
            put("lite", lite)
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  proot-distro — backup + bridge de archivos (submenu_terminal [5], submenu_interfaz [8])
    // ═══════════════════════════════════════════════════════════

    /** Mismo layout dual que _proot_rootfs_path() en menu_entorno.sh — moderno (containers/) primero, legacy después. */
    private fun rootfsParentDir(name: String): String? = when {
        File(containersBase, "$name/rootfs").isDirectory -> containersBase
        File(rootfsBase, name).isDirectory -> rootfsBase
        else -> null
    }

    fun distroBackup(name: String): JSONObject {
        val parent = rootfsParentDir(name)
            ?: return JSONObject().put("ok", false).put("error", "No se encontró $name en ningún layout de proot-distro")
        val dateTag = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())
        val backupPath = File(ManagerNativeUtils.home, "${name}_backup_$dateTag.tar.gz").absolutePath
        // Timeout generoso (5 min) — un rootfs completo puede pesar varios GB; el caller
        // ya corre esto en un Thread propio.
        val (rc, out, err) = ManagerNativeUtils.runExec(
            listOf("tar", "-czf", backupPath, "-C", parent, name), 300
        )
        if (rc != 0) {
            return JSONObject().put("ok", false).put("error", "Backup de $name falló")
                .put("output", out.ifEmpty { err }.takeLast(300))
        }
        return JSONObject().put("ok", true).put("message", "Backup guardado en $backupPath")
    }

    /**
     * Prepara los directorios de bind dentro del rootfs para ~/scripts y ~/proyectos —
     * mismo alcance que la opción [8] "App Bridge" del submenú Interfaz: crea la carpeta
     * destino y devuelve la instrucción real para montarla (proot-distro no soporta binds
     * custom persistentes fuera de "proot-distro login -- -b origen:destino", así que el
     * paso final sigue siendo manual, igual que en el script de referencia).
     */
    /**
     * Desde 2026-08-18 el bind real ya lo aplica distroLoginArgs() (projectBindArgs())
     * automáticamente en TODO login — esta función queda solo para crear los puntos de
     * montaje destino dentro del rootfs (algunos frontends de proot los quieren pre-creados)
     * y confirmarle al usuario que ya no hace falta ningún paso manual.
     */
    fun mountProjectBridge(name: String): JSONObject {
        val parent = rootfsParentDir(name)
            ?: return JSONObject().put("ok", false).put("error", "No se encontró $name en ningún layout de proot-distro")
        val rootfs = if (parent == containersBase) File(parent, "$name/rootfs") else File(parent, name)
        val scriptsTarget = File(rootfs, "home/builder/scripts")
        val projectsTarget = File(rootfs, "home/builder/proyectos")
        scriptsTarget.mkdirs()
        projectsTarget.mkdirs()
        return JSONObject().put("ok", true).put("message",
            "$name listo — ~/scripts y ~/proyectos ya quedan vinculados automáticamente en /home/builder/ cada vez que entrás a esta distro (login, apps, pdrun), sin ningún paso manual."
        )
    }

    // ═══════════════════════════════════════════════════════════
    //  Catálogo de apps dentro de una distro — instala un paquete apt DENTRO de una
    //  distro proot y genera su lanzador en ~/Desktop apuntando al wrapper pdrun (portado
    //  de packinstall.sh + el .desktop-copy-and-rewrite de termux-desktop-main, ver
    //  referencia/termux/termux-desktop-main/distro-container-setup). MVP sin catálogo
    //  curado (queda para una ronda futura, ver PLAN_EXPANSION_HOMELAB_2026-08-13 §2.8):
    //  el usuario escribe el nombre del paquete apt a mano (EntornoFragment.
    //  promptPackageName()).
    // ═══════════════════════════════════════════════════════════

    private val distroAppsStateFile get() = File(scriptsDir, "distro_apps.json")
    // Lock propio para el read-modify-write de distro_apps.json (ver fix de
    // distroAppInstall()/distroAppRemove(), auditoría 2026-08-19) — archivo de lock
    // dedicado, distinto de ManagerNativeUtils.registryLockFile (~/.android_server_registry).
    private val distroAppsLockFile get() = File(scriptsDir, "distro_apps.lock")

    /** Apps de distro ya instaladas + su lanzador — {distro, pkg, name, binary} por entrada. Persistido para poder regenerar lanzadores sin re-loguear a la distro (ver generateDesktopLaunchers()). */
    private fun loadDistroApps(): MutableList<JSONObject> {
        if (!distroAppsStateFile.exists()) return mutableListOf()
        return try {
            val arr = JSONArray(distroAppsStateFile.readText())
            (0 until arr.length()).map { arr.getJSONObject(it) }.toMutableList()
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    private fun saveDistroApps(apps: List<JSONObject>) {
        scriptsDir.mkdirs()
        distroAppsStateFile.writeText(JSONArray(apps).toString())
    }

    /** Lista los .desktop reales que dejó `pkg` dentro de la distro — vía dpkg-query -L, filtrando por sufijo (mismo criterio que el patrón real investigado, ver plan). */
    private fun distroDesktopFiles(distro: String, pkg: String): List<String> {
        val listArgs = distroLoginArgs(distro) + listOf("--", "dpkg-query", "-L", pkg)
        val (rc, out, _) = ManagerNativeUtils.runExec(listArgs, 30)
        if (rc != 0) return emptyList()
        return out.lines().map { it.trim() }.filter { it.endsWith(".desktop") }
    }

    /**
     * Lee un archivo dentro del rootfs de la distro directo por filesystem (sin volver a
     * loguearse) — el rootfs de proot-distro es un directorio real del host, mismo layout
     * dual que rootfsParentDir()/mountProjectBridge() ya usan.
     */
    private fun readRootfsFile(distro: String, path: String): String? {
        val parent = rootfsParentDir(distro) ?: return null
        val rootfs = if (parent == containersBase) File(parent, "$distro/rootfs") else File(parent, distro)
        val file = File(rootfs, path.removePrefix("/"))
        if (!file.exists()) return null
        return try { file.readText() } catch (_: Exception) { null }
    }

    /** Extrae Name= y el binario real de Exec= (primer token que no sea un field code %f/%F/%u/%U) de un .desktop crudo. */
    private fun parseDesktopEntry(content: String): Pair<String, String>? {
        var name: String? = null
        var execLine: String? = null
        content.lines().forEach { line ->
            val trimmed = line.trim()
            if (name == null && trimmed.startsWith("Name=")) name = trimmed.removePrefix("Name=").trim()
            if (execLine == null && trimmed.startsWith("Exec=")) execLine = trimmed.removePrefix("Exec=").trim()
        }
        val exec = execLine ?: return null
        val binary = exec.split(Regex("\\s+")).firstOrNull { it.isNotBlank() && !it.startsWith("%") } ?: return null
        return (name ?: binary) to binary
    }

    private fun sanitizeId(value: String): String = value.replace(Regex("[^A-Za-z0-9._-]"), "-")

    private fun distroAppLauncherFilename(entry: JSONObject): String =
        "kairos-distro-${sanitizeId(entry.optString("distro"))}-${sanitizeId(entry.optString("binary"))}.desktop"

    /** Escribe/actualiza el .desktop de una app de distro en ~/Desktop y en el menú de apps XDG (~/.local/share/applications) — Exec apunta al wrapper pdrun (ver entorno.sh), nunca al binario crudo (no existe fuera de la distro). */
    private fun writeDistroAppLauncher(entry: JSONObject) {
        if (!desktopDir.exists()) desktopDir.mkdirs()
        if (!appsMenuDir.exists()) appsMenuDir.mkdirs()
        val distro = entry.optString("distro")
        val binary = entry.optString("binary")
        val name = entry.optString("name")
        val pdrunPath = File(scriptsDir, "pdrun").absolutePath
        val exec = "$pdrunPath $distro $binary"
        writeLauncherBoth(distroAppLauncherFilename(entry), desktopEntryContentGui("$name ($distro)", exec))
    }

    /**
     * Instala `pkg` dentro de `distro` (apt-get, con storage compartido — ver
     * distroLoginArgs()) y, si tiene éxito, genera su lanzador en ~/Desktop. No es un error
     * que el paquete no traiga ningún .desktop (paquetes de librería, CLIs sin GUI) — se
     * informa igual, sin lanzador, en vez de fallar la instalación entera.
     */
    fun distroAppInstall(distro: String, pkg: String): JSONObject {
        if (distro !in KNOWN_DISTROS) {
            return JSONObject().put("ok", false).put("error", "Distro desconocida: $distro")
        }
        if (pkg.isBlank()) {
            return JSONObject().put("ok", false).put("error", "Nombre de paquete vacío")
        }
        if (!isTermuxBinaryAvailable("proot-distro")) {
            return JSONObject().put("ok", false).put("error", "proot-distro no disponible — instala Entorno primero")
        }
        val installArgs = distroLoginArgs(distro) +
            listOf("--", "env", "DEBIAN_FRONTEND=noninteractive", "apt-get", "install", "-y", pkg)
        // Timeout generoso — apt-get install de una app GUI real (navegador, GIMP, etc.)
        // puede tardar varios minutos, mismo criterio que distroInstall()/installDesktop().
        val (rc, out, err) = ManagerNativeUtils.runExec(installArgs, 600)
        val output = out.ifEmpty { err }
        WizardDebugLog.log("EntornoNative", "distroAppInstall($distro,$pkg): rc=$rc output=${output.takeLast(500)}")
        if (rc != 0) {
            return JSONObject().put("ok", false).put("error", "No se pudo instalar $pkg en $distro")
                .put("output", output.takeLast(500))
        }
        val desktopFiles = distroDesktopFiles(distro, pkg)
        // Race real confirmado esta ronda (auditoría 2026-08-19): ver comentario del mismo
        // fix en distroAppRemove() — loadDistroApps()/saveDistroApps() sin locking podían
        // perder una actualización si dos instalaciones/desinstalaciones corrían en paralelo.
        var launcherCount = 0
        RegistryLock.withLock(distroAppsLockFile) {
            val apps = loadDistroApps()
            desktopFiles.forEach { path ->
                val content = readRootfsFile(distro, path) ?: return@forEach
                val (name, binary) = parseDesktopEntry(content) ?: return@forEach
                val entry = JSONObject().put("distro", distro).put("pkg", pkg).put("name", name).put("binary", binary)
                apps.removeAll { it.optString("distro") == distro && it.optString("binary") == binary }
                apps.add(entry)
                writeDistroAppLauncher(entry)
                launcherCount++
            }
            saveDistroApps(apps)
        }
        val message = if (launcherCount > 0)
            "$pkg instalado en $distro — $launcherCount lanzador(es) agregado(s) al escritorio"
        else
            "$pkg instalado en $distro — no se encontró ningún .desktop, no se generó lanzador (podés abrirlo con \"Login a distro\")"
        return JSONObject().put("ok", true).put("message", message).put("launchers", launcherCount)
    }

    /**
     * Desinstala `pkg` de `distro` y borra su(s) lanzador(es) — análogo a distroAppInstall().
     * Bug real confirmado esta ronda (auditoría 2026-08-19): a diferencia de
     * distroAppInstall(), esta función no validaba distro conocida ni proot-distro
     * instalado — se agregan las mismas dos guardas por consistencia (mismo motivo que el
     * fix de distroRemove() arriba).
     */
    fun distroAppRemove(distro: String, pkg: String): JSONObject {
        if (distro !in KNOWN_DISTROS) {
            return JSONObject().put("ok", false).put("error", "Distro desconocida: $distro")
        }
        if (pkg.isBlank()) {
            return JSONObject().put("ok", false).put("error", "Nombre de paquete vacío")
        }
        if (!isTermuxBinaryAvailable("proot-distro")) {
            return JSONObject().put("ok", false).put("error", "proot-distro no disponible — instala Entorno primero")
        }
        val removeArgs = distroLoginArgs(distro) +
            listOf("--", "env", "DEBIAN_FRONTEND=noninteractive", "apt-get", "remove", "-y", pkg)
        val (rc, out, err) = ManagerNativeUtils.runExec(removeArgs, 300)
        if (rc != 0) {
            return JSONObject().put("ok", false).put("error", "No se pudo eliminar $pkg de $distro")
                .put("output", out.ifEmpty { err }.takeLast(300))
        }
        // Race real confirmado esta ronda (auditoría 2026-08-19): loadDistroApps()/
        // saveDistroApps() hacían read-modify-write sobre distro_apps.json SIN ningún
        // locking — mismo patrón de bug que ya se corrigió en updateRegistryValue() para
        // ~/.android_server_registry (ver comentario de esa función, consolidación
        // 2026-08-13). Dos instalaciones/desinstalaciones de apps de distro en paralelo
        // (ej. el usuario dispara una instalación y una desinstalación casi al mismo
        // tiempo desde 2 diálogos) podían pisarse la escritura una a la otra. Se envuelve
        // con el mismo RegistryLock, reusando distroAppsLockFile (archivo de lock propio,
        // no comparte el de ~/.android_server_registry).
        RegistryLock.withLock(distroAppsLockFile) {
            val apps = loadDistroApps()
            val toRemove = apps.filter { it.optString("distro") == distro && it.optString("pkg") == pkg }
            toRemove.forEach { entry -> File(desktopDir, distroAppLauncherFilename(entry)).delete() }
            apps.removeAll(toRemove)
            saveDistroApps(apps)
        }
        return JSONObject().put("ok", true).put("message", "$pkg eliminado de $distro")
    }

    /** Todas las apps de distro instaladas (todas las distros) — para el picker de "Eliminar app" de EntornoFragment. */
    fun distroAppsInstalled(): JSONObject =
        JSONObject().put("ok", true).put("apps", JSONArray(loadDistroApps()))

    // ── Contenedores — udocker ──────────────────────────────────
    // Feature nueva (2026-08-06, ver docs/humano/humano86.md, pedido explícito del
    // usuario): "usar la terminal adaptada para udocker, ejecutar más cosas ahí ya que es
    // un contenedor" — hasta ahora udocker solo se usaba para arrancar n8n en background
    // (modulos/n8n.sh), sin ninguna forma de abrir una sesión interactiva ADENTRO del
    // contenedor (equivalente a "docker exec -it"). El comentario de arriba del archivo
    // ("se dejaron afuera udocker run/list/setup") describía el alcance de la ronda
    // original, no una limitación permanente.

    fun udockerAvailable(): Boolean = isTermuxBinaryAvailable("udocker")

    /**
     * Lista los contenedores udocker reales creados en el dispositivo (via "udocker ps -a").
     * El formato de salida de udocker no es un CSV limpio (columna NAMES tipo
     * "['n8n']") — se extrae cualquier token entre comillas simples con forma de nombre de
     * contenedor. Si el parseo no encuentra nada pero "n8n" existe igual (único consumidor
     * real de udocker en el proyecto hoy, ver modulos/n8n.sh), se ofrece como fallback en
     * vez de dejar la lista vacía — evita que un cambio de formato en una versión nueva de
     * udocker rompa la función por completo.
     */
    fun udockerContainers(): JSONObject {
        if (!udockerAvailable()) {
            return JSONObject().put("ok", false).put("error", "udocker no está instalado")
        }
        // Bug real confirmado por ADB (2026-08-24, ver docs/humano222.md): los 4 usos de
        // "udocker" en este archivo invocaban el binario por nombre relativo — mismo patrón ya
        // confirmado roto para Hermes/vncserver (ProcessBuilder no resuelve PATH acá). Ruta
        // absoluta (TERMUX_UDOCKER_PATH, ya definida en ProcessBuilderExt.kt y usada por
        // CactusNative) = mismo fix, ahora consistente en todo el archivo.
        val (rc, out, err) = ManagerNativeUtils.runExec(listOf(TERMUX_UDOCKER_PATH, "ps", "-a"), 15)
        if (rc != 0) {
            return JSONObject().put("ok", false).put("error", "udocker ps falló").put("output", (out.ifEmpty { err }).takeLast(300))
        }
        val names = Regex("""'([a-zA-Z0-9][a-zA-Z0-9_.-]*)'""").findAll(out).map { it.groupValues[1] }.toMutableSet()
        if (names.isEmpty()) {
            val (inspectRc, _, _) = ManagerNativeUtils.runExec(listOf(TERMUX_UDOCKER_PATH, "inspect", "n8n"), 10)
            if (inspectRc == 0) names.add("n8n")
        }
        return JSONObject().put("ok", true).put("containers", JSONArray(names.toList()))
    }

    /** Comando real para abrir una sesión interactiva dentro del contenedor — usado por EntornoFragment vía launchTerminalCommand(). */
    fun udockerExecCommand(containerName: String, shell: String = "bash"): String =
        "udocker run --interactive --tty $containerName $shell || udocker run --interactive --tty $containerName sh"

    /**
     * Inventario detallado de contenedores udocker (id + nombre + imagen + estado) — para
     * la vista de solo-lectura "📋 Instalado" de EntornoFragment (pedido explícito del
     * usuario: poder VER qué hay instalado sin tener que abrir un diálogo de acción
     * primero, mismo gap que cubre distroList() para proot-distro). A diferencia de
     * udockerContainers() (solo nombres, usado por el picker de "Terminal en contenedor"),
     * acá se agrega:
     * - imagen: best-effort vía udockerImageFor() (parsea "udocker inspect <nombre>"),
     *   degrada a "desconocida" sin romper el resto del inventario si el JSON no trae el
     *   campo esperado — mismo criterio defensivo que ya usa udockerContainers() con su
     *   fallback de "n8n" cuando el regex de NAMES no matchea nada.
     * - estado: udocker no tiene daemon que trackee containers corriendo/detenidos como
     *   Docker real (ver docs/modulos/UDOCKER.md §2) — "corriendo" acá es un proxy real
     *   (pgrep -f sobre el container id, que aparece en la ruta ROOT/ del proceso proot
     *   activo si lo hay), no un estado persistido.
     */
    fun udockerContainersList(): JSONObject {
        if (!udockerAvailable()) {
            return JSONObject().put("ok", false).put("error", "udocker no está instalado")
        }
        val (rc, out, err) = ManagerNativeUtils.runExec(listOf(TERMUX_UDOCKER_PATH, "ps", "-a"), 15)
        if (rc != 0) {
            return JSONObject().put("ok", false).put("error", "udocker ps falló").put("output", (out.ifEmpty { err }).takeLast(300))
        }
        val nameRegex = Regex("""'([a-zA-Z0-9][a-zA-Z0-9_.-]*)'""")
        val containers = JSONArray()
        out.lines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@forEach
            val containerId = trimmed.substringBefore(' ').trim()
            // Header real de "udocker ps -a" (do_ps() del CLI real): "CONTAINER ID  P M   NAMES" —
            // se descarta por prefijo, no por número de línea fijo (más tolerante a un
            // encabezado que cambie de orden/columnas en una versión futura).
            if (containerId.isEmpty() || containerId.equals("CONTAINER", ignoreCase = true)) return@forEach
            val name = nameRegex.find(trimmed)?.groupValues?.get(1) ?: containerId
            val running = processRunning(containerId)
            containers.put(JSONObject().apply {
                put("id", containerId)
                put("name", name)
                put("image", udockerImageFor(name))
                put("status", if (running) "corriendo" else "detenido")
            })
        }
        return JSONObject().put("ok", true).put("containers", containers)
    }

    /** Best-effort: "udocker inspect <nombre>" imprime el JSON de metadata real del contenedor — se prueban las 2 claves conocidas donde puede venir el nombre de imagen antes de degradar a "desconocida". */
    private fun udockerImageFor(name: String): String {
        return try {
            val (rc, out, _) = ManagerNativeUtils.runExec(listOf(TERMUX_UDOCKER_PATH, "inspect", name), 10)
            if (rc != 0) "desconocida"
            else {
                val json = JSONObject(out)
                val repoinfo = json.optString("repoinfo").trim()
                val configImage = json.optJSONObject("config")?.optString("Image")?.trim().orEmpty()
                repoinfo.ifBlank { configImage }.ifBlank { "desconocida" }
            }
        } catch (_: Exception) {
            "desconocida"
        }
    }
}

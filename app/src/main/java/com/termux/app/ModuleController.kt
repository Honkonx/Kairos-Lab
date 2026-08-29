package com.termux.app

import com.termux.app.util.InstallQueueManager
import com.termux.app.util.ManagerNativeUtils
import com.termux.app.util.ModuleEventBridge
import com.termux.app.util.RegistryLock
import com.termux.app.util.TERMUX_BASH_PATH
import com.termux.app.util.TERMUX_PGREP_PATH
import com.termux.app.util.TERMUX_PREFIX_PATH
import com.termux.app.util.TERMUX_TMUX_PATH
import com.termux.app.util.applyTermuxEnv as applyTermuxEnvShared
import com.termux.app.util.decodeExitSignal
import com.termux.shared.termux.TermuxConstants
import java.io.File
import java.util.concurrent.TimeUnit

object ModuleController {

    private val HOME get() = TermuxConstants.TERMUX_HOME_DIR_PATH

    // Mensaje que [installModule] le pasa a [onProgress] cuando InstallQueueManager encola la
    // instalación en vez de arrancarla de inmediato (4+ módulos instalando ahora mismo) — un
    // string bien conocido para que los callers (BaseModuleFragment.installModuleInBackground,
    // BottomSheetInstalacion) lo detecten y muestren feedback real ("no pasó nada" era el bug
    // real reportado por el usuario antes de este mecanismo) en vez de descartar la línea.
    const val INSTALL_QUEUED_MESSAGE = "En cola — se instalará cuando termine otro módulo"

    // Instalaciones en curso por módulo — pedido explícito del usuario (ver cita real en
    // docs/viejo/AUDITORIA_UX_INSTALACION_2026-08-19.md): "al dar cancelar mientras se
    // instala se sigue instalando en segundo plano, al dar cancelar deberia matar esa terminal
    // y proceso de instalacion en backend". Antes BottomSheetInstalacion.Cancelar solo cerraba
    // la UI (dismissAllowingStateLoss()) — el Process real de installModule() seguía corriendo
    // sin que nadie lo matara. Este mapa es lo que le permite a [cancelInstall] encontrar el
    // Process real de un módulo dado sin que el caller (la UI) tenga que guardar su propia
    // referencia — instalar en segundo plano cierra la hoja de inmediato, así que la UI no
    // sobrevive para retener ese estado por su cuenta.
    private val runningInstalls = java.util.concurrent.ConcurrentHashMap<String, Process>()

    // Guard anti-duplicados (pedido explícito del usuario, docs/humano269.md, auditoría
    // 2026-08-27): antes NADA impedía llamar a installModule() dos veces para el MISMO
    // moduleId mientras la primera seguía en curso (ej. tocar "Instalar" en Ollama variante
    // standard y, sin esperar, volver a tocar "Instalar" con variante gpu) — cada llamada
    // lanzaba su propio Thread + proceso independiente, y ambos terminaban peleando por el
    // mismo lock de apt/pkg/npm (ver lib.sh flock), haciendo fallar en cascada TANTO al
    // duplicado como a cualquier otro módulo que se instalara al mismo tiempo (confirmado en
    // vivo por ADB: ollama.sh --variant standard y --variant gpu corriendo a la vez, más 4
    // instancias de qemu.sh, todas colgadas esperando el mismo flock). A diferencia de
    // [runningInstalls] (que solo trackea procesos YA arrancados), este set se puebla ANTES de
    // encolar en InstallQueueManager — cubre también el caso de un módulo todavía en cola,
    // esperando cupo, no solo uno ya corriendo. Se libera SIEMPRE en el finally() de
    // installModule() (éxito, fallo, o excepción) — por diseño, esto ya resuelve "solo dejar
    // reintentar si falló": mientras el entry sigue presente el módulo está en curso, y en
    // cuanto termina (sea como sea) el entry desaparece y un nuevo intento vuelve a poder
    // arrancar sin ningún flag especial.
    private val activeInstalls = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    // Lectura pública de [activeInstalls] (pedido explícito del usuario, auditoría de UX de
    // instalación 2026-08-29): antes de este fix, un doble-tap en "Instalar" mientras el módulo
    // ya estaba instalando pasaba de largo por el guard de arriba y terminaba disparando
    // onComplete(false) → una notificación real de "instalación falló" totalmente engañosa
    // (el módulo seguía instalando bien, solo el segundo intento fue rechazado). Los callers
    // (BaseModuleFragment.installModuleInBackground/reinstallModuleService) ahora chequean esto
    // ANTES de llamar a installModule(), así el guard interno de activeInstalls nunca se llega
    // a ejercitar para el caso de doble-tap — evita la notificación falsa de raíz.
    fun isInstalling(moduleId: String): Boolean = activeInstalls.contains(moduleId)

    // Antes duplicaba a mano el mismo bloque de env vars que ya vive en
    // util/ProcessBuilderExt.kt.applyTermuxEnv() (bug real de esta sesión, ver
    // docs/humano/humano63.md: 2 copias del mismo fix que podían quedar desincronizadas si
    // una se corregía y la otra no) — ahora delega en la función compartida y solo agrega
    // TERM/LANG, que ese helper no necesita para el resto de la app.
    private fun applyTermuxEnv(pb: ProcessBuilder) {
        pb.applyTermuxEnvShared()
        val env = pb.environment()
        env["TERM"] = "xterm-256color"
        env["LANG"] = "en_US.UTF-8"
    }

    /**
     * Espera hasta [timeoutMs] a que algo responda en 127.0.0.1:[port] (poll cada [intervalMs])
     * — patrón adoptado de AnyClaw (`CodexServerManager.waitForServer()`, ver
     * docs/referencias/REFERENCIA_OPENCLAW_ANDROID_ASSISTANT.md, hallazgo 2026-08-01). Un script de
     * arranque puede terminar con exit code 0 sin que el servicio real todavía esté
     * escuchando: una race de cientos de ms mientras el proceso hijo termina de bindear el
     * puerto, o un caso donde el script "tiene éxito" según su propia lógica interna sin que
     * el puerto real llegue a abrirse. Mismo patrón de "checkpoint marcado sin verificar" que
     * ya se corrigió del lado bash en varios módulos esta sesión (n8n/OpenCode/Ollama, ver
     * docs/humano*.md rondas 38-41) — esto agrega una SEGUNDA capa de verificación real, del
     * lado Kotlin, independiente de que el script haya hecho bien o mal su propio chequeo.
     */
    // n8n corre en proot (n8n_start.sh) — el boot completo del entorno proot + node.js puede
    // tardar bien más de 8s (bug real reportado, ver docs/humano/humano57.md: "n8n... dura
    // mucho" y el switch reportaba error aunque el módulo seguía arrancando bien, solo más
    // lento que el timeout). Sin esto, waitForPortOpen() cortaba a los 8s y startModule()
    // reportaba una falla falsa mientras n8n todavía estaba subiendo.
    private fun startTimeoutMsFor(moduleId: String): Long = when (moduleId) {
        "n8n" -> 60_000L
        else -> 8_000L
    }

    private fun waitForPortOpen(port: Int, timeoutMs: Long = 8000, intervalMs: Long = 400): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try {
                java.net.Socket().use { socket ->
                    socket.connect(java.net.InetSocketAddress("127.0.0.1", port), 800)
                    return true
                }
            } catch (_: Exception) {
                // Todavía no está escuchando — esperar y reintentar hasta el timeout.
            }
            Thread.sleep(intervalMs)
        }
        return false
    }

    // Bug real (2026-08-07, ver docs/humano/humano91.md): "Ollama queda atascado en
    // Iniciando…" — una de las causas encontradas es que process.waitFor() acá (y en
    // stopModule() abajo) es SIN timeout, a diferencia de ManagerNativeUtils.runExec()/
    // runShell() (mismo proyecto), que sí usan waitFor(timeout, TimeUnit) +
    // destroyForcibly() como red de seguridad. Si el script o algún hijo suyo se cuelga sin
    // cerrar su stdout (ej. un socket de tmux obsoleto), este hilo quedaba esperando para
    // siempre y onResult() nunca se invocaba — la UI se congelaba sin ningún aviso posible.
    private const val SCRIPT_TIMEOUT_SECONDS = 120L

    // context (2026-08-17, ver AUDITORIA_CATEGORIA_HERRAMIENTAS.md/AUDITORIA_CATEGORIA_INTERFAZ.md
    // "conectar ModuleEventBridge a más módulos" — pendiente abierto desde la ronda 2026-08-13):
    // el contrato session_started/session_stopped ya existía en ModuleEventBridge.kt desde
    // 2026-08-14/15 (parseSessionEvent()/notifySessionEvent()), pero NINGÚN caller lo emitía
    // todavía — ni el lado bash (ningún modulos/*.sh corre notify_event con esos eventos) ni el
    // lado Kotlin. Acá es donde vive el arranque/detención real de cualquier módulo con
    // start/stop script (ollama, n8n, openclaw, opencode, remote, llamaserver, db, etc.),
    // así que es el punto único que cierra el gap para todos a la vez, sin tocar cada script.
    // context es opcional (null-safe) para no romper callers existentes que todavía no lo pasan.
    // TermuxActivity.java sigue llamando a startModule(moduleId, callback) con la firma vieja
    // de 2 argumentos — un default param de Kotlin en un parámetro que NO es el último es
    // invisible para Java (@JvmOverloads solo genera overloads quitando parámetros desde el
    // final), así que se agrega un overload explícito de 2 argumentos más abajo en vez de
    // depender de @JvmOverloads acá.
    fun startModule(moduleId: String, context: android.content.Context? = null, onResult: (Boolean, String) -> Unit) {
        val script = getModuleStartScript(moduleId) ?: return onResult(false, "Unknown module")
        // Log interno de Kairos, nivel NORMAL — ciclo de vida de módulos (ver docs/humano231.md).
        // context puede ser null en el overload legacy de 2 argumentos; sin Context no hay forma
        // de leer las SharedPreferences del nivel configurado, así que ese caso queda sin
        // loguear acá (comportamiento honesto, no un no-op silencioso disfrazado de cobertura).
        context?.let { com.termux.app.util.KairosLogger.log(it, "Module", "startModule($moduleId) — iniciando") }
        Thread {
            try {
                val pb = ProcessBuilder(TERMUX_BASH_PATH, script)
                applyTermuxEnv(pb)
                pb.redirectErrorStream(true)
                val process = pb.start()
                val outputBuilder = StringBuilder()
                // Bug real confirmado por ADB (2026-08-24, ver docs/humano222.md — probando
                // el switch de n8n desde la UI real, la app entera crasheaba y reiniciaba en
                // loop cada ~2min): sin try/catch acá, destroyForcibly() más abajo cierra los
                // streams del proceso mientras este Thread está bloqueado en readText() —
                // igual que el bug ya arreglado en ManagerNativeUtils.runShell(), pero acá el
                // problema no es un hang, es una excepción (InterruptedIOException) sin capturar
                // que se propaga fuera de un Thread sin manejador propio, lo que mata TODO el
                // proceso de la app (confirmado en logcat: "FATAL EXCEPTION" en
                // ModuleController.kt:111, con el PID de la app cambiando entre crashes).
                val readerThread = Thread {
                    try {
                        outputBuilder.append(process.inputStream.bufferedReader().readText())
                    } catch (_: Exception) {
                        // Esperado cuando destroyForcibly() cierra el stream mientras se lee.
                    }
                }
                readerThread.start()
                val finished = process.waitFor(SCRIPT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                if (!finished) {
                    process.destroyForcibly()
                    readerThread.join(2000)
                    onResult(false, "El script de arranque no terminó en ${SCRIPT_TIMEOUT_SECONDS}s — forzado a detener.\n${outputBuilder}")
                    return@Thread
                }
                readerThread.join()
                val output = outputBuilder.toString()
                val startExitCode = process.exitValue()
                if (startExitCode != 0) {
                    val signalNote = decodeExitSignal(startExitCode)?.let { "\n[SEÑAL] $it (exit code $startExitCode)" } ?: ""
                    onResult(false, output + signalNote)
                    return@Thread
                }
                // El script terminó con éxito según SU propia lógica — confirmar acá, de
                // forma independiente, que el puerto real del módulo (modules.json) ya
                // responde antes de reportarle éxito a la UI. Módulos sin puerto fijo
                // (getModulePort devuelve null) se quedan con el exit code solo, igual que
                // antes de este fix.
                val port = getModulePort(moduleId)
                if (port == null) {
                    context?.let { ModuleEventBridge.notifySessionEvent(it, moduleId, ModuleEventBridge.SessionEvent.STARTED) }
                    onResult(true, output)
                    return@Thread
                }
                if (waitForPortOpen(port, timeoutMs = startTimeoutMsFor(moduleId))) {
                    context?.let {
                        ModuleEventBridge.notifySessionEvent(it, moduleId, ModuleEventBridge.SessionEvent.STARTED)
                        com.termux.app.util.KairosLogger.log(it, "Module", "startModule($moduleId) — OK, puerto $port respondiendo")
                    }
                    onResult(true, output)
                } else {
                    context?.let { com.termux.app.util.KairosLogger.log(it, "Module", "startModule($moduleId) — script OK pero puerto $port no respondió") }
                    onResult(
                        false,
                        "$output\n[Verificación] El script terminó bien, pero nadie respondió en el puerto $port después de esperar — revisá los logs del módulo."
                    )
                }
            } catch (e: Exception) {
                context?.let { com.termux.app.util.KairosLogger.log(it, "Module", "startModule($moduleId) — excepción: ${e.message}") }
                onResult(false, e.message ?: "Error desconocido")
            }
        }.start()
    }

    // Overload de 2 argumentos para callers Java pre-existentes (TermuxActivity.java) que no
    // pasan context — ver nota arriba en startModule().
    fun startModule(moduleId: String, onResult: (Boolean, String) -> Unit) {
        startModule(moduleId, null, onResult)
    }

    fun stopModule(moduleId: String, context: android.content.Context? = null, onResult: (Boolean) -> Unit) {
        val script = getModuleStopInfo(moduleId) ?: return onResult(false)
        context?.let { com.termux.app.util.KairosLogger.log(it, "Module", "stopModule($moduleId) — deteniendo") }
        Thread {
            try {
                val pb = ProcessBuilder(TERMUX_BASH_PATH, script)
                applyTermuxEnv(pb)
                val process = pb.start()
                if (!process.waitFor(SCRIPT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    context?.let { com.termux.app.util.KairosLogger.log(it, "Module", "stopModule($moduleId) — timeout, forzado") }
                    onResult(false)
                    return@Thread
                }
                context?.let {
                    ModuleEventBridge.notifySessionEvent(it, moduleId, ModuleEventBridge.SessionEvent.STOPPED)
                    com.termux.app.util.KairosLogger.log(it, "Module", "stopModule($moduleId) — OK")
                }
                onResult(true)
            } catch (e: Exception) {
                context?.let { com.termux.app.util.KairosLogger.log(it, "Module", "stopModule($moduleId) — excepción: ${e.message}") }
                onResult(false)
            }
        }.start()
    }

    // Overload de 2 argumentos para callers Java pre-existentes (TermuxActivity.java) que no
    // pasan context — ver nota arriba en startModule().
    fun stopModule(moduleId: String, onResult: (Boolean) -> Unit) {
        stopModule(moduleId, null, onResult)
    }

    /**
     * "Salir" (Config) — pedido explícito del usuario: detener todos los servicios en
     * ejecución y cerrar la app, "como si pusiera exit en la terminal" (a diferencia de un
     * botón de "cerrar app" genérico, que el propio usuario descartó — ver
     * docs/humano/humano67.md: eso no se puede lograr de forma confiable desde dentro de la
     * app, requiere "Forzar cierre" de Android). Esto sí es real: para cada módulo con
     * script de stop conocido (los mismos de [getModuleStopInfo]) que esté corriendo, corre
     * su stop script real — el mismo camino que usa el switch de cada módulo, uno por uno.
     * onComplete llega siempre en background thread, no en UI — el caller decide cómo cerrar
     * la Activity/proceso.
     */
    fun stopAllModules(onComplete: () -> Unit) {
        Thread {
            listOf("ollama", "n8n", "openclaw", "opencode", "remote", "llamaserver", "db").forEach { id ->
                if (isRunning(id)) {
                    getModuleStopInfo(id)?.let { stopScript ->
                        try {
                            val pb = ProcessBuilder(TERMUX_BASH_PATH, stopScript)
                            applyTermuxEnv(pb)
                            pb.start().waitFor()
                        } catch (_: Exception) {
                            // Best-effort — un módulo que no se pudo detener no debe frenar
                            // el resto ni impedir que la app cierre igual.
                        }
                    }
                }
            }
            onComplete()
        }.start()
    }

    // Módulos con sesión tmux (ollama/n8n/openclaw/opencode) se verifican vía
    // "tmux has-session"; remote (sshd, sin tmux) se verifica vía pgrep — antes esto
    // exigía sesión tmux para TODOS los módulos, así que remote nunca podía reportar
    // "corriendo" y su switch quedaba roto.
    fun isRunning(moduleId: String): Boolean {
        // db (2026-08-10, ampliado 2026-08-25 para Redis v1.1.0 — modulos/db.sh línea 108
        // "pgrep -f redis-server &>/dev/null && REDIS_RUNNING=true"): tres servidores
        // independientes (mysqld, postgres, redis-server) — el módulo cuenta como
        // "corriendo" si CUALQUIERA de los tres está vivo.
        if (moduleId == "db") {
            return isProcessAlive("mysqld") || isProcessAlive("postgres") || isProcessAlive("redis-server")
        }
        getTmuxSession(moduleId)?.let { session ->
            val tmuxAlive = try {
                val pb = ProcessBuilder(TERMUX_TMUX_PATH, "has-session", "-t", session)
                applyTermuxEnv(pb)
                val p = pb.start()
                p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0
            } catch (_: Exception) {
                false
            }
            if (tmuxAlive) return true
            // Bug real de duplicación de lógica (auditoría 2026-08-25, ver
            // docs/arquitectura/AUDITORIA_DUPLICACION_LOGICA_2026-08-25.md): OpenClawNative.kt
            // ya define "openclaw corriendo" como tmuxHas("openclaw") || checkPort(18789) (3
            // call-sites propios), pero esta fuente CENTRAL (la que usa ModulesFragment para el
            // switch/badge de toda la app) solo miraba tmux — un desfasaje real entre "lo que
            // dice la lista de Módulos" y "lo que dice la pantalla propia de OpenClaw" si la
            // sesión tmux muere mientras el proceso sigue respondiendo el puerto (o viceversa
            // durante el arranque). Se agrega el mismo fallback de puerto acá para que la fuente
            // central sea al menos tan robusta como la que ya tenía OpenClawNative, en vez de
            // reescribir OpenClawNative para que sea más débil.
            //
            // Generalizado a TODOS los módulos con sesión tmux + puerto fijo (2026-08-29,
            // bug real reportado por el usuario y confirmado con evidencia — Monitor/Chat
            // mostraban Ollama "Detenido"/"inactivo" pese a estar corriendo y respondiendo en
            // :11434): antes este fallback quedaba hardcodeado solo a "openclaw" pese a que el
            // comentario de arriba ya explicaba el problema en términos generales — cualquier
            // otro módulo tmux (ollama, n8n, opencode) con su sesión tmux muerta pero el
            // proceso real todavía vivo/respondiendo el puerto (ej. iniciado a mano fuera de
            // la app, o la sesión tmux killeada sin matar al hijo) caía igual en "false". Se
            // reusa getModulePort(), la misma fuente que ya usa refreshAiEngines() de
            // MonitorFragment para mostrar el puerto — ningún módulo pierde precisión: si no
            // tiene puerto fijo, getModulePort() devuelve null y el resultado es exactamente
            // el mismo "false" que antes.
            return getModulePort(moduleId)?.let { ManagerNativeUtils.checkPort(it) } ?: false
        }
        getProcessName(moduleId)?.let { process ->
            return isProcessAlive(process)
        }
        return false
    }

    private fun isProcessAlive(process: String): Boolean {
        return try {
            val pb = ProcessBuilder(TERMUX_PGREP_PATH, "-x", process)
            applyTermuxEnv(pb)
            val p = pb.start()
            p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0
        } catch (_: Exception) {
            false
        }
    }

    // Lee el nombre real del script desde el catálogo ya cargado (campo "script" de
    // app/src/main/assets/modules.json — ej. "ollama.sh", o "ssh.sh" para "remote") en vez
    // de calcularlo a mano. Antes esta función hardcodeaba la convención "$moduleId.sh" +
    // un caso especial para "remote", duplicando la misma info que modules.json ya trae
    // (hallazgo 2026-08-19, ver docs/arquitectura/AUDITORIA_EXTENSIBILIDAD_MODULOS_2026-08-19.md
    // sección 2.d — coincidían en los 57 módulos, pero era una segunda fuente de verdad
    // latente para cualquier módulo futuro con nombre de script no convencional).
    // context nulo (callers Java legacy, o el overload de 2 argumentos) cae al fallback
    // hardcodeado de siempre — mismo comportamiento que antes de este cambio.
    private fun installScriptFile(moduleId: String, context: android.content.Context?): String {
        val fromCatalog = context?.let { ctx ->
            try {
                com.termux.app.data.ModuleCatalog.load(ctx).firstOrNull { it.id == moduleId }?.script
            } catch (_: Exception) {
                null
            }
        }
        return fromCatalog?.takeIf { it.isNotBlank() } ?: legacyScriptFileFallback(moduleId)
    }

    // Fallback defensivo: catálogo no disponible (context null) o campo "script" vacío/null —
    // no debería pasar con el JSON real, ver comentario de installScriptFile() arriba.
    private fun legacyScriptFileFallback(moduleId: String): String = when (moduleId) {
        "remote" -> "ssh.sh"
        else -> "$moduleId.sh"
    }

    // Log completo por módulo en ~/kairos_logs/ — la UI solo muestra un spinner
    // (ver BottomSheetInstalacion), pero el output real de pkg/npm/etc. queda acá
    // para poder diagnosticar una instalación fallida después de que se cierre la hoja.
    // TermuxActivity.showModuleLogDialog() siempre lee esta ruta base (la última
    // instalación intentada, sin importar la variante).
    fun installLogFile(moduleId: String): File =
        File(HOME, "kairos_logs").apply { mkdirs() }.resolve("install_$moduleId.log")

    // Bug real (auditoría 2026-08-05, ver docs/humano65.md/humano66.md): instalar un
    // módulo con 2 variantes (ej. Ollama gpu/standard, n8n proot/udocker) hacía que el
    // segundo intento SOBREESCRIBIERA el log del primero en installLogFile() — si el
    // primer intento (ej. Ollama GPU) fallaba y el usuario reintentaba con otra
    // variante (standard) que sí funcionaba, la evidencia del fallo original quedaba
    // borrada, imposibilitando diagnosticar qué pasó. Se guarda una copia aparte por
    // variante, sin tocar el archivo base que ya usa el visor de logs.
    private fun installLogFileForVariant(moduleId: String, variant: String?): File? {
        if (variant == null) return null
        val suffix = variant.replace(Regex("[^A-Za-z0-9_-]"), "")
        return File(HOME, "kairos_logs").apply { mkdirs() }.resolve("install_${moduleId}_$suffix.log")
    }

    // force=false (default) preserva el comportamiento de BottomSheetInstalacion.kt (una
    // instalación nueva no debe forzar nada). force=true es lo que usa
    // BaseModuleFragment.reinstallModuleService() — bug real (auditoría 2026-08-05, ver
    // docs/humano65.md/humano66.md): sin --force, re-ejecutar el script de instalación de
    // un módulo YA instalado es casi siempre un no-op (todos los scripts chequean
    // "command -v X && ! $FORCE" y salen temprano) — el botón "Reinstalar/Actualizar" que
    // ya existía en 7 módulos no actualizaba nada en la práctica, solo lo aparentaba.
    // context (2026-08-19, ver AUDITORIA_EXTENSIBILIDAD_MODULOS_2026-08-19.md sección 2.d):
    // agregado para poder resolver el nombre real del script vía installScriptFile()
    // leyendo el catálogo (ModuleCatalog) en vez de calcularlo a mano. Default null para no
    // romper callers que todavía no lo pasen — cae al fallback hardcodeado de siempre.
    @JvmOverloads
    fun installModule(
        moduleId: String,
        context: android.content.Context? = null,
        variant: String?,
        force: Boolean = false,
        onProgress: (String) -> Unit,
        onComplete: (Boolean) -> Unit
    ) {
        // Bug real confirmado (auditoría ADB 2026-08-22, ver docs/humano/humano189.md, bug #26):
        // módulos CONTENEDOR ("languages"/"packages" — ver ModuleInstalled.kt línea ~95, sin
        // instalador propio a propósito, script="" en el catálogo) llegaban hasta acá igual
        // que un módulo normal — installScriptFile() interpreta CUALQUIER script en blanco
        // (catálogo no disponible O deliberadamente vacío) como "usar el fallback
        // '$moduleId.sh'", así que terminaba intentando `bash languages.sh --silent` contra un
        // archivo que nunca existió. Cortar acá explícitamente cuando el catálogo SÍ cargó y
        // el campo script es blank a propósito — evita el log roto y el reporte de fallo
        // falso para un módulo que en realidad no tiene (ni necesita) instalador.
        if (context != null) {
            val catalogEntry = try {
                com.termux.app.data.ModuleCatalog.load(context).firstOrNull { it.id == moduleId }
            } catch (_: Exception) {
                null
            }
            if (catalogEntry != null && catalogEntry.script.isBlank()) {
                onProgress("$moduleId es un módulo contenedor sin instalador propio")
                onComplete(true)
                return
            }
        }
        val script = "$HOME/scripts/install/${installScriptFile(moduleId, context)}"
        val logFile = installLogFile(moduleId)
        // Bug real reportado por el usuario ("claude code no descarga la ultima version, no se
        // porque"): claude.sh y ollama.sh tienen variant_required=true en su --describe — en
        // modo --silent, sin --variant el script imprime "[ERROR] Falta --variant" y sale con
        // exit 1 ANTES de tocar nada. reinstallModuleService() (BaseModuleFragment.kt, el único
        // mecanismo real de "Instalar / cambiar método"/"Actualizar") siempre llama acá con
        // variant=null — el botón de Claude Code fallaba en silencio en cada intento, nunca
        // llegaba ni a comparar versiones. Se resuelve la variante ya instalada desde el
        // registry (misma fuente que deepUninstallPlan() usa para "claude"/"codex" más abajo)
        // cuando el caller no la especifica explícitamente.
        val effectiveVariant = variant ?: resolveInstalledVariant(moduleId)
        // Guard anti-duplicados — ver comentario de [activeInstalls] arriba. Si ya hay una
        // instalación de este módulo en curso (corriendo o encolada), no se lanza una segunda
        // en paralelo — solo se libera cuando la primera termina, sea éxito o fallo.
        if (!activeInstalls.add(moduleId)) {
            onProgress("$moduleId ya se está instalando — esperá a que termine antes de reintentar")
            onComplete(false)
            return
        }
        context?.let { com.termux.app.util.KairosLogger.log(it, "Module", "installModule($moduleId, variant=$effectiveVariant, force=$force) — iniciando") }
        // Límite de instalaciones simultáneas (pedido explícito del usuario, ver
        // docs/arquitectura/COLA_INSTALACION_MODULOS.md): antes CADA llamada a installModule()
        // lanzaba su Thread de inmediato, sin ningún tope — más de 4 instalaciones a la vez
        // tendía a hacer fallar alguna (CPU/red/proot saturados, o rate-limit de algún repo).
        // InstallQueueManager.submit() corre la tarea de inmediato si hay cupo (máximo 4
        // corriendo, ver MAX_CONCURRENT_INSTALLS) o la encola — release() (en el finally de
        // abajo) SIEMPRE libera el cupo al terminar, éxito o error, y arranca la siguiente
        // tarea encolada si hay una esperando.
        InstallQueueManager.submit(onQueued = { onProgress(INSTALL_QUEUED_MESSAGE) }) {
        Thread {
            try {
                val args = mutableListOf(TERMUX_BASH_PATH, script, "--silent")
                if (effectiveVariant != null) {
                    args.addAll(listOf("--variant", effectiveVariant))
                }
                if (force) {
                    args.add("--force")
                }
                val pb = ProcessBuilder(args)
                applyTermuxEnv(pb)
                pb.redirectErrorStream(true)
                val process = pb.start()
                runningInstalls[moduleId] = process
                // Bug real (2026-08-06, ver docs/humano/humano77.md): BufferedWriter solo
                // vuelca a disco cuando su buffer interno se llena o el bloque .use{}
                // cierra el writer (o sea, cuando el proceso hijo termina). Si el script
                // se cuelga sin terminar (ej. "pkg install" sin timeout esperando red), el
                // log queda en 0 bytes en disco para siempre, aunque el script ya haya
                // impreso varias líneas — "log vacío" se confunde con "nunca arrancó"
                // cuando en realidad puede seguir corriendo colgado en segundo plano.
                // flush() por línea convierte eso en un log parcial diagnosticable.
                logFile.bufferedWriter().use { writer ->
                    process.inputStream.bufferedReader().forEachLine { line ->
                        writer.write(line)
                        writer.newLine()
                        writer.flush()
                        onProgress(line)
                    }
                }
                val exitCode = process.waitFor()
                if (exitCode != 0) {
                    // Pieza adoptada de CodeAssist (Subprocess.kt, ver
                    // docs/referencias/REFERENCIA_CODEASSIST.md) — si el script murió por una señal real
                    // (segfault, killed, etc.) en vez de un `exit 1` normal, dejarlo explícito
                    // en el log en vez de que el usuario solo vea "falló" sin saber por qué.
                    decodeExitSignal(exitCode)?.let { signal ->
                        try { logFile.appendText("\n[SEÑAL] Proceso terminado por $signal (exit code $exitCode)\n") } catch (_: Exception) {}
                    }
                }
                installLogFileForVariant(moduleId, effectiveVariant)?.let { variantLog ->
                    try { logFile.copyTo(variantLog, overwrite = true) } catch (_: Exception) {}
                }
                // Bug real (ver docs/humano/humano166.md/humano167.md, "al instalar un plugin no sale
                // instalado y todavía da la opción de instalar"): ModuleInstalled cachea el
                // registry (30s) y el binario/verificación en vivo (10s/30s) — sin invalidar acá,
                // un fragment que releía el estado justo después de que este script terminara
                // (y ya escribiera "<id>.installed=true" en el registry en disco) seguía viendo
                // el snapshot cacheado ANTES de la instalación. Se invalida siempre (éxito o
                // fallo) porque un intento fallido también puede haber dejado el módulo a medio
                // instalar (algunos scripts marcan checkpoints parciales).
                com.termux.app.data.ModuleInstalled.invalidate(moduleId)
                runningInstalls.remove(moduleId)
                context?.let { com.termux.app.util.KairosLogger.log(it, "Module", "installModule($moduleId) — terminó, exitCode=$exitCode") }
                onComplete(exitCode == 0)
            } catch (e: Exception) {
                try { logFile.appendText("\n[EXCEPCIÓN] ${e.message}\n") } catch (_: Exception) {}
                installLogFileForVariant(moduleId, effectiveVariant)?.let { variantLog ->
                    try { logFile.copyTo(variantLog, overwrite = true) } catch (_: Exception) {}
                }
                com.termux.app.data.ModuleInstalled.invalidate(moduleId)
                runningInstalls.remove(moduleId)
                context?.let { com.termux.app.util.KairosLogger.log(it, "Module", "installModule($moduleId) — excepción: ${e.message}") }
                onComplete(false)
            } finally {
                // Contraparte de InstallQueueManager.submit() de arriba — SIEMPRE libera el
                // cupo (éxito, fallo, o excepción) y arranca la siguiente instalación encolada,
                // si hay alguna esperando.
                InstallQueueManager.release()
                // Contraparte de activeInstalls.add() de arriba — SIEMPRE libera el guard
                // anti-duplicados (éxito, fallo, o excepción), permitiendo un reintento real.
                activeInstalls.remove(moduleId)
            }
        }.start()
        }
    }

    /**
     * Mata la instalación en curso de [moduleId] (si hay una) — pedido explícito del usuario
     * (ver cita real arriba, en el comentario de [runningInstalls]): "Cancelar" debe matar el
     * proceso/terminal real de instalación, no solo cerrar la ventana. No-op silencioso si no
     * hay ninguna instalación en curso para ese módulo (ej. el usuario canceló antes de tocar
     * "Instalar").
     *
     * Primero intenta matar el árbol de hijos del script (`pkill -P <pid>`) — un script
     * `--silent` puede lanzar sub-procesos (curl, npm, tar, pip) que ya se independizaron del
     * proceso padre; `Process.destroyForcibly()` por sí solo envía SIGKILL únicamente al proceso
     * bash de tope, no necesariamente a sus hijos. Best-effort: si `pkill` falla o no existe,
     * igual se fuerza el proceso padre.
     */
    fun cancelInstall(moduleId: String): Boolean {
        val process = runningInstalls.remove(moduleId) ?: return false
        return try {
            try {
                // "Process.pid()" (Java 9+) no resuelve en el toolchain de compilación de este
                // módulo — se obtiene el PID vía reflexión sobre el campo interno "pid" que
                // expone la implementación real de Process en la JVM/ART (mismo patrón usado
                // antes de que Process.pid() existiera como API pública).
                val pid = try {
                    val f = process.javaClass.getDeclaredField("pid")
                    f.isAccessible = true
                    f.getLong(process)
                } catch (_: Exception) {
                    null
                }
                if (pid != null) {
                    val pb = ProcessBuilder(TERMUX_BASH_PATH, "-c", "pkill -P $pid 2>/dev/null || true")
                    applyTermuxEnv(pb)
                    pb.start().waitFor(3, TimeUnit.SECONDS)
                }
            } catch (_: Exception) {
                // Best-effort — seguir igual con destroyForcibly() del proceso padre.
            }
            process.destroyForcibly()
            true
        } catch (_: Exception) {
            false
        }
    }

    // Mapa moduleId → clave del registry que guarda la variante/método realmente instalado,
    // para los módulos con variant_required=true en su --describe (confirmado por grep sobre
    // modulos/*.sh: solo "claude" y "ollama" a la fecha de este fix — codex/n8n tienen
    // variant_required=false y usan su propio default cuando --variant no llega). Las claves
    // ("method"/"install_mode") son las mismas que escribe cada script en
    // registry_install()/registry_write() (ver claude.sh::_update_registry(),
    // ollama.sh línea ~110) — si se agrega un módulo nuevo con variant_required=true, agregar
    // su clave acá también o el mismo bug se reproduce silenciosamente.
    private val VARIANT_REQUIRED_REGISTRY_KEY = mapOf(
        "claude" to "method",
        "ollama" to "install_mode"
    )

    private fun resolveInstalledVariant(moduleId: String): String? =
        VARIANT_REQUIRED_REGISTRY_KEY[moduleId]?.let { key -> readRegistryValue(moduleId, key) }

    // Extraído de uninstallModule() (ver KDoc de esa función abajo) para que
    // deepUninstallModule() pueda reusar el mismo "reset de estado" sin duplicarlo —
    // detiene el módulo si está corriendo, best-effort (un módulo que no se puede
    // detener no debe frenar el resto de la desinstalación).
    private fun stopIfRunning(moduleId: String) {
        if (!isRunning(moduleId)) return
        getModuleStopInfo(moduleId)?.let { stopScript ->
            val pb = ProcessBuilder(TERMUX_BASH_PATH, stopScript)
            applyTermuxEnv(pb)
            try { pb.start().waitFor() } catch (_: Exception) {}
        }
    }

    // Borra la carpeta de scripts propia del módulo (~/scripts/<id>/, y
    // ~/scripts/<id>-udocker/ para n8n) y sus checkpoint(s) de instalación.
    private fun clearModuleFiles(moduleId: String) {
        listOf(File(HOME, "scripts/$moduleId"), File(HOME, "scripts/$moduleId-udocker"))
            .forEach { if (it.exists()) it.deleteRecursively() }

        File(HOME).listFiles { f ->
            f.name.startsWith(".install_$moduleId") && f.name.endsWith("checkpoint")
        }?.forEach { it.delete() }
    }

    // Borra las líneas "<id>.*" del registry — mismo formato que registry_write() en
    // modulos/lib.sh (ver readRegistryValue() más abajo, que lee ese mismo formato).
    //
    // Bug real (auditoría de código 2026-08-27): read-modify-write directo sobre el mismo
    // archivo (~/.android_server_registry) que también escriben en paralelo TunnelManager,
    // EntornoNative, ConfigExportManager, ProjectsManager y RemoteManager — TODOS esos
    // managers ya envuelven su read-modify-write en RegistryLock.withLock() (ver
    // RegistryLock.kt, "extraído... porque reimplementaban el mismo read-modify-write... SIN
    // ninguna protección de concurrencia"), pero esta función, en el mismo proyecto, se
    // había quedado afuera de esa consolidación. Sin el lock, dos desinstalaciones
    // concurrentes (o una desinstalación mientras otro manager escribe una clave propia) se
    // pueden pisar: cada uno lee el archivo, modifica su copia en memoria y sobreescribe con
    // writeText() — el que termina último gana, descartando en silencio cualquier cambio del
    // otro (lost update). Mismo lockFile que el resto (ManagerNativeUtils.registryLockFile)
    // para que queden serializados entre sí, no solo dentro de ModuleController.
    private fun clearRegistryEntries(moduleId: String) {
        val registryFile = File(HOME, ".android_server_registry")
        RegistryLock.withLock(ManagerNativeUtils.registryLockFile) {
            if (registryFile.exists()) {
                val kept = registryFile.readLines().filterNot { it.startsWith("$moduleId.") }
                registryFile.writeText(if (kept.isEmpty()) "" else kept.joinToString("\n") + "\n")
            }
        }
    }

    /**
     * Desinstala un módulo — pedido explícito del usuario (auditoría 2026-08-05, ver
     * docs/humano65.md/humano66.md: "en ningún tab o menú... sale para desinstalar
     * módulos"). Alcance DELIBERADAMENTE conservador: detiene el módulo si está corriendo,
     * borra su carpeta de scripts propia (~/scripts/<id>/, y ~/scripts/<id>-udocker/ para
     * n8n), borra su(s) checkpoint(s) de instalación y sus líneas del registry — deja el
     * módulo como si nunca se hubiera instalado desde el punto de vista de la app, lista
     * para reinstalar limpio. NO corre "npm uninstall -g"/"pkg uninstall"/"proot-distro
     * remove" sobre el paquete real: varios módulos comparten runtimes (nodejs, python,
     * proot-distro) — desinstalar el paquete de un módulo a ciegas podría romper otro que
     * dependa del mismo binario. Ese nivel de limpieza de disco queda fuera de este alcance
     * (ver [deepUninstallModule] para la versión opt-in que sí lo hace, por-módulo).
     */
    fun uninstallModule(moduleId: String, onComplete: (Boolean) -> Unit) {
        Thread {
            try {
                stopIfRunning(moduleId)
                clearModuleFiles(moduleId)
                clearRegistryEntries(moduleId)
                // Bug real confirmado (auditoría Entorno 2026-08-19): sin esto, un CLI
                // desinstalado dejaba su ícono en ~/Desktop (y ~/.config/autostart si estaba
                // en autoinicio) apuntando a un comando que ya no existe — ver
                // EntornoNative.removeCliLauncher() para el detalle completo. No-op si el
                // módulo nunca tuvo lanzador (delete() sobre un archivo inexistente no falla).
                com.termux.app.util.EntornoNative.removeCliLauncher(moduleId)
                com.termux.app.data.ModuleInstalled.invalidate(moduleId)
                onComplete(true)
            } catch (_: Exception) {
                onComplete(false)
            }
        }.start()
    }

    // Lee "<moduleId>.<key>=valor" del registry (~/.android_server_registry) — mismo
    // formato que escribe registry_write()/registry_install() en modulos/lib.sh. Devuelve
    // null si el archivo no existe, la clave no está, o el valor quedó vacío (nunca un
    // string vacío como "encontrado pero sin valor" — evita que el caller confunda eso con
    // un valor real).
    private fun readRegistryValue(moduleId: String, key: String): String? {
        val file = File(HOME, ".android_server_registry")
        if (!file.exists()) return null
        return try {
            file.readLines()
                .firstOrNull { it.trim().startsWith("$moduleId.$key=") }
                ?.substringAfter("=")
                ?.trim()
                ?.ifEmpty { null }
        } catch (_: Exception) {
            null
        }
    }

    private data class DeepUninstallPlan(val command: String, val description: String)

    // Mapa moduleId → comando de desinstalación REAL, construido revisando cómo cada
    // modulos/<id>.sh instala su binario (ver reporte de esta ronda). Solo cubre módulos
    // donde el método de instalación quedó confirmado con evidencia directa del script —
    // para cualquier otro moduleId (o una variante/canal que el registry no reconoce),
    // devuelve null a propósito: deepUninstallModule() hace fallback honesto a
    // uninstallModule() en vez de arriesgar un comando inventado.
    //
    // Runtimes compartidos (nodejs, python, proot-distro) NUNCA se tocan acá — cada
    // comando apunta al paquete específico de ESE módulo (ej. "npm uninstall -g
    // <paquete>", nunca "pkg uninstall -y nodejs-lts").
    private fun deepUninstallPlan(moduleId: String): DeepUninstallPlan? = when (moduleId) {
        // claude.sh ya tiene _uninstall_native()/_uninstall_legacy() — se replica la misma
        // lógica acá en vez de invocar claude.sh de nuevo (ese script no expone un flag
        // "--uninstall", solo se auto-desinstala como parte de resolver un conflicto de
        // variante). Método real instalado se lee de "claude.method" (native|legacy),
        // escrito por claude.sh::_update_registry().
        "claude" -> when (readRegistryValue("claude", "method")) {
            "native" -> DeepUninstallPlan(
                "rm -rf \"$HOME/.local/share/claude-code\" \"$HOME/.local/bin/claude\"",
                "Binario nativo de Claude Code eliminado (~/.local/share/claude-code, ~/.local/bin/claude)"
            )
            "legacy" -> DeepUninstallPlan(
                "npm uninstall -g @anthropic-ai/claude-code",
                "Paquete npm '@anthropic-ai/claude-code' desinstalado"
            )
            else -> null
        }
        // codex.sh: canal "termux" instala vía npm (@mmmbuto/codex-cli-termux); canal
        // "native" baja un binario prebuilt a $PREFIX/opt/codex-native + symlink en
        // $PREFIX/bin/codex (ver codex.sh --variant native). Canal real leído de
        // "codex.channel", escrito al final de codex.sh en ambas ramas.
        "codex" -> when (readRegistryValue("codex", "channel")) {
            "native" -> DeepUninstallPlan(
                "rm -rf \"$TERMUX_PREFIX_PATH/opt/codex-native\" \"$TERMUX_PREFIX_PATH/bin/codex\"",
                "Binario nativo de Codex eliminado ($TERMUX_PREFIX_PATH/opt/codex-native)"
            )
            "termux" -> DeepUninstallPlan(
                "npm uninstall -g @mmmbuto/codex-cli-termux",
                "Paquete npm '@mmmbuto/codex-cli-termux' desinstalado"
            )
            else -> null
        }
        // opencode.sh no usa npm ni pkg — extrae el .pkg.tar.xz/.deb del release de
        // GitHub directo sobre $PREFIX (cp -r extract/usr/* "$PREFIX/"), así que no hay
        // "pkg uninstall" real disponible. Se borran los dos artefactos específicos y
        // no-compartidos que el script SÍ documenta que crea: el binario
        // ($PREFIX/bin/opencode) y su carpeta de runtime propia ($PREFIX/lib/opencode) —
        // nunca el resto de archivos que "usr/*" pudo haber tocado (headers, man pages,
        // etc.), que si están compartidos con otros paquetes de Termux.
        "opencode" -> DeepUninstallPlan(
            "rm -f \"$TERMUX_PREFIX_PATH/bin/opencode\"; rm -rf \"$TERMUX_PREFIX_PATH/lib/opencode\"",
            "Binario y runtime de OpenCode eliminados ($TERMUX_PREFIX_PATH/bin/opencode, $TERMUX_PREFIX_PATH/lib/opencode)"
        )
        "freebuff" -> DeepUninstallPlan("npm uninstall -g freebuff", "Paquete npm 'freebuff' desinstalado")
        "codebuff" -> DeepUninstallPlan("npm uninstall -g codebuff", "Paquete npm 'codebuff' desinstalado")
        "qwencode" -> DeepUninstallPlan(
            "npm uninstall -g @qwen-code/qwen-code",
            "Paquete npm '@qwen-code/qwen-code' desinstalado"
        )
        "mimocode" -> DeepUninstallPlan("npm uninstall -g @mimo-ai/cli", "Paquete npm '@mimo-ai/cli' desinstalado")
        // mistralvibe.sh instala con "$PIP_PYTHON -m pip install", donde PIP_PYTHON
        // prefiere el binario "python" y cae a "python3" si no existe (mismo criterio
        // acá, para desinstalar con el mismo intérprete que instaló). Solo se desinstala
        // el paquete pip 'mistral-vibe' — nunca Python en sí, que es un módulo/runtime
        // compartido.
        "mistralvibe" -> DeepUninstallPlan(
            "PIP_PY=\$(command -v python 2>/dev/null || command -v python3 2>/dev/null); " +
                "\"\$PIP_PY\" -m pip uninstall -y mistral-vibe",
            "Paquete pip 'mistral-vibe' desinstalado"
        )
        "minimaxcli" -> DeepUninstallPlan("npm uninstall -g mmx-cli", "Paquete npm 'mmx-cli' desinstalado")
        "copilotcli" -> DeepUninstallPlan(
            "npm uninstall -g @github/copilot",
            "Paquete npm '@github/copilot' desinstalado"
        )
        // ollama.sh: variante "standard" instala vía "pkg install ollama" (paquete real de
        // Termux); variante "termux_npm" (GPU) instala el wrapper vía npm
        // (@mmmbuto/ollama-termux) y ese wrapper baja el binario real a $PREFIX/bin/ollama
        // en su primer arranque (ver comentario largo en ollama.sh PASO 2) — "npm
        // uninstall" no toca ese binario descargado aparte, así que se borra explícito.
        // Variante real leída de "ollama.install_mode".
        "ollama" -> when (readRegistryValue("ollama", "install_mode")) {
            "standard" -> DeepUninstallPlan(
                "pkg uninstall -y ollama",
                "Paquete de Termux 'ollama' desinstalado (pkg uninstall)"
            )
            "termux_npm" -> DeepUninstallPlan(
                "npm uninstall -g @mmmbuto/ollama-termux; rm -f \"$TERMUX_PREFIX_PATH/bin/ollama\"",
                "Paquete npm '@mmmbuto/ollama-termux' desinstalado y binario 'ollama' eliminado"
            )
            else -> null
        }
        // Lenguajes nativos (pkg) consolidados en el módulo "Lenguajes" (LanguagesFragment,
        // ver docs/modulos/LENGUAJES.md, 2026-08-18) — cada uno instala con
        // install_single_pkg() en modulos/<id>.sh (pkg install real, ver lib.sh), así que
        // "OFF" en su switch corre el "pkg uninstall" simétrico real, no solo un reset de
        // estado. nodejs usa el paquete real "nodejs-lts" (no "nodejs" a secas).
        //
        // nodejs/rust/golang además borran sus carpetas de config/caché propias tras el "pkg
        // uninstall" — patrón adoptado de referencia/termux/core-termux-main/core/tools/lang/
        // {nodejs,rust,golang}/install.sh::uninstall_*() (confirmado con el código real de esa
        // referencia, auditoría lang 2026-08-19): "pkg uninstall" por sí solo no las toca
        // (son creadas por el binario en uso, no por el paquete apt), así que quedaban
        // huérfanas tras desinstalar — mismo criterio ya usado en clearModuleFiles() para las
        // carpetas propias de otros módulos. perl/php/clang se confirmaron SIN carpetas de
        // config propias en esa misma referencia — no se les agrega nada acá a propósito, no
        // es una omisión.
        "nodejs" -> DeepUninstallPlan(
            "pkg uninstall -y nodejs-lts; rm -rf \"$HOME/.npm\" \"$HOME/.npmrc\" " +
                "\"$HOME/.node_repl_history\" \"$HOME/.config/yarn\" \"$HOME/.cache/yarn\"",
            "Paquete de Termux 'nodejs-lts' desinstalado (+ caché npm/yarn eliminada)"
        )
        "perl" -> DeepUninstallPlan("pkg uninstall -y perl", "Paquete de Termux 'perl' desinstalado")
        "php" -> DeepUninstallPlan("pkg uninstall -y php", "Paquete de Termux 'php' desinstalado")
        "rust" -> DeepUninstallPlan(
            "pkg uninstall -y rust; rm -rf \"$HOME/.cargo\"",
            "Paquete de Termux 'rust' desinstalado (+ caché ~/.cargo eliminada)"
        )
        "clang" -> DeepUninstallPlan("pkg uninstall -y clang", "Paquete de Termux 'clang' desinstalado")
        "golang" -> DeepUninstallPlan(
            "pkg uninstall -y golang; rm -rf \"$HOME/.cache/go\" \"$HOME/.cache/go-build\"",
            "Paquete de Termux 'golang' desinstalado (+ caché de build eliminada)"
        )
        // Herramientas npm consolidadas en el módulo "Paquetes" (PackagesFragment, mismo
        // origen 2026-08-18) — cada una instala con install_npm_global() en modulos/<id>.sh
        // (npm install -g real). psqlformat además instaló "perl" como dependencia pkg
        // (ver install_npm_global de psqlformat.sh) — se desinstala solo el paquete npm acá,
        // igual que el resto: perl es un runtime compartido (podría estar en uso por otra
        // herramienta), mismo criterio que nodejs/python en el resto de este archivo.
        "typescript" -> DeepUninstallPlan("npm uninstall -g typescript", "Paquete npm 'typescript' desinstalado")
        "nestjs" -> DeepUninstallPlan("npm uninstall -g @nestjs/cli", "Paquete npm '@nestjs/cli' desinstalado")
        "prettier" -> DeepUninstallPlan("npm uninstall -g prettier", "Paquete npm 'prettier' desinstalado")
        "livesrv" -> DeepUninstallPlan("npm uninstall -g live-server", "Paquete npm 'live-server' desinstalado")
        "localtunnel" -> DeepUninstallPlan("npm uninstall -g localtunnel", "Paquete npm 'localtunnel' desinstalado")
        "vercel" -> DeepUninstallPlan("npm uninstall -g vercel", "Paquete npm 'vercel' desinstalado")
        "markserv" -> DeepUninstallPlan("npm uninstall -g markserv", "Paquete npm 'markserv' desinstalado")
        "psqlformat" -> DeepUninstallPlan("npm uninstall -g psqlformat", "Paquete npm 'psqlformat' desinstalado")
        "ncu" -> DeepUninstallPlan("npm uninstall -g npm-check-updates", "Paquete npm 'npm-check-updates' desinstalado")
        "ngrok" -> DeepUninstallPlan("npm uninstall -g ngrok", "Paquete npm 'ngrok' desinstalado")
        // hf.sh instala vía el instalador oficial (curl -LsSf https://hf.co/cli/install.sh |
        // bash), que deja el binario en ~/.local/bin/hf — no hay "pkg"/"npm uninstall" real
        // disponible. Se borra el binario + las carpetas de config/caché propias que el CLI
        // crea aparte del binario (~/.cache/huggingface, ~/.config/huggingface), mismo patrón
        // adoptado de uninstall_hugging_face() en
        // referencia/termux/core-termux-main/core/tools/ai/hugging-face/install.sh (auditoría
        // 2026-08-19, ver AUDITORIA_MODULOS_IA_DEV_VS_REFERENCIA_2026-08-19.md).
        "hf" -> DeepUninstallPlan(
            "rm -f \"$HOME/.local/bin/hf\"; rm -rf \"$HOME/.cache/huggingface\" \"$HOME/.config/huggingface\"",
            "Binario de Hugging Face CLI y su config/caché eliminados (~/.local/bin/hf, ~/.cache/huggingface, ~/.config/huggingface)"
        )
        else -> null
    }

    // Corre un comando de shell arbitrario (ej. "npm uninstall -g X") con el mismo entorno
    // Termux que el resto de ModuleController — devuelve true solo si terminó a tiempo Y
    // con exit code 0, nunca solo "no lanzó excepción" (mismo criterio de verificación real
    // que el resto de este archivo, no confiar en que "no crasheó" sea lo mismo que "salió
    // bien").
    private fun runShellCommand(command: String): Boolean {
        return try {
            val pb = ProcessBuilder(TERMUX_BASH_PATH, "-c", command)
            applyTermuxEnv(pb)
            pb.redirectErrorStream(true)
            val process = pb.start()
            process.inputStream.bufferedReader().readText()
            val finished = process.waitFor(SCRIPT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return false
            }
            process.exitValue() == 0
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Versión opt-in de [uninstallModule] — pedido explícito de esta ronda (ver
     * investigación de core-termux-main sobre desinstalación real por-herramienta). Además
     * de todo lo que ya hace uninstallModule() (detener, borrar scripts/checkpoints/
     * registry), intenta correr el comando de desinstalación REAL del paquete de ESE
     * módulo específico (ej. "npm uninstall -g <paquete>", "pkg uninstall -y <paquete>", o
     * borrar un binario descargado a mano) — nunca toca un runtime compartido (nodejs,
     * python, proot-distro) en sí mismo.
     *
     * El orden importa: [deepUninstallPlan] lee el método/canal/variante real desde el
     * registry ANTES de que [clearRegistryEntries] borre esas mismas claves — invertir el
     * orden dejaría siempre en null la detección de variante.
     *
     * Si [deepUninstallPlan] no tiene un comando confirmado para este módulo, hace fallback
     * silencioso al reset de estado de uninstallModule() y lo reporta honestamente en el
     * mensaje — nunca inventa un comando arriesgado para un módulo sin evidencia clara de
     * cómo se instaló.
     */
    fun deepUninstallModule(moduleId: String, onComplete: (Boolean, String) -> Unit) {
        Thread {
            try {
                stopIfRunning(moduleId)
                val plan = deepUninstallPlan(moduleId)
                val message = if (plan == null) {
                    "Solo se reseteó el estado — desinstalación real no soportada para este módulo todavía."
                } else if (runShellCommand(plan.command)) {
                    "${plan.description} — y se reseteó el estado del módulo."
                } else {
                    "No se pudo confirmar la desinstalación real del paquete (¿ya estaba desinstalado, o falló el comando?) — se reseteó el estado del módulo igual."
                }
                clearModuleFiles(moduleId)
                clearRegistryEntries(moduleId)
                // Mismo fix que uninstallModule() — ver EntornoNative.removeCliLauncher().
                com.termux.app.util.EntornoNative.removeCliLauncher(moduleId)
                com.termux.app.data.ModuleInstalled.invalidate(moduleId)
                onComplete(true, message)
            } catch (e: Exception) {
                onComplete(false, "Error al desinstalar: ${e.message ?: "desconocido"}")
            }
        }.start()
    }

    /**
     * "Reinstalar limpio" — combo pedido explícito de esta ronda: para un módulo que quedó en
     * un estado roto que la reinstalación normal (installModule(force=true), que NUNCA toca el
     * paquete real ya instalado — ver el comentario de [installModule]) no soluciona, encadena
     * [deepUninstallModule] (borra el paquete real de ESE módulo, best-effort, sin tocar
     * runtimes compartidos) seguido de [installModule] con force=true desde cero.
     *
     * Ambos pasos ya son thread-safe por su cuenta (cada uno lanza su propio Thread) — acá solo
     * se encadena el callback de deepUninstallModule() con la llamada a installModule(), sin
     * lanzar un Thread propio extra. onComplete combina los dos mensajes de resultado en uno
     * solo, para que la UI muestre un único diálogo/Toast con el resultado completo del combo
     * en vez de dos notificaciones separadas.
     */
    fun cleanReinstallModule(moduleId: String, onComplete: (Boolean, String) -> Unit) {
        deepUninstallModule(moduleId) { _, uninstallMessage ->
            installModule(
                moduleId,
                variant = null,
                force = true,
                onProgress = {},
                onComplete = { installOk ->
                    val finalMessage = if (installOk) {
                        "$uninstallMessage Reinstalado desde cero correctamente."
                    } else {
                        "$uninstallMessage La reinstalación falló — revisá ~/kairos_logs/install_$moduleId.log."
                    }
                    onComplete(installOk, finalMessage)
                }
            )
        }
    }

    // Bug real (2026-08-06, ver docs/humano/humano83.md): n8n tiene 2 variantes (proot/udocker,
    // ver modulos/n8n.sh) con scripts de control y nombre de sesión tmux DISTINTOS
    // ("scripts/n8n/*_servidor.sh" + sesión "n8n-server" para proot vs.
    // "scripts/n8n-udocker/*.sh" + sesión "n8n-udocker" para udocker) — pero
    // getModuleStartScript/getModuleStopInfo/getTmuxSession tenían la variante proot
    // hardcodeada sin importar cuál se instaló de verdad. udocker es la variante recomendada
    // desde una ronda anterior (ver BottomSheetInstalacion.kt) — con este bug, instalar
    // udocker (que sí completaba bien, confirmado en log real) dejaba "Iniciar"/"Detener"/el
    // chequeo de "¿está corriendo?" intentando el script y la sesión tmux de proot, que nunca
    // existían — el usuario solo lograba usar n8n arrancándolo a mano desde la terminal.
    // Lee el registry directo (mismo archivo y lógica mínima que ModuleRegistry.kt, sin
    // necesitar Context acá — ModuleController es un object sin Context inyectado).
    // Delegado en readRegistryValue() (agregado junto con deepUninstallModule() — mismo
    // formato "<id>.<key>=valor" que esta función ya leía a mano) para no mantener dos
    // copias casi idénticas del mismo parseo de registry.
    private fun readN8nMode(): String = readRegistryValue("n8n", "mode") ?: "proot"

    private fun getModuleStartScript(id: String): String? = when (id) {
        "ollama" -> "$HOME/scripts/ollama/ollama_start.sh"
        "n8n" -> if (readN8nMode() == "udocker") "$HOME/scripts/n8n-udocker/start.sh" else "$HOME/scripts/n8n/start_servidor.sh"
        "openclaw" -> "$HOME/scripts/openclaw/openclaw_start.sh"
        "opencode" -> "$HOME/scripts/opencode/opencode_start.sh"
        "remote" -> "$HOME/scripts/remote/ssh_start.sh"
        // Rama "llama-server-and-terminal-ux" (2026-08-05, ver docs/humano/humano71.md).
        "llamaserver" -> "$HOME/scripts/llamaserver/start.sh"
        // Módulo Base de Datos (2026-08-10): wrappers en ~/scripts/db/ que arrancan
        // ambos servidores (mysql_start.sh + postgres_start.sh, ver modulos/db.sh).
        "db" -> "$HOME/scripts/db/start.sh"
        // Servidor HTTP opt-in de Cactus (`cactus serve`, ver modulos/cactus.sh PASO 5) —
        // apagado por defecto, solo arranca cuando el usuario prende el switch en
        // CactusFragment.kt (docs/arquitectura/PROPUESTA_ORQUESTACION_CRUZADA_2026-08-25.md).
        "cactus" -> "$HOME/scripts/cactus/start.sh"
        else -> null
    }

    private fun getModuleStopInfo(id: String): String? = when (id) {
        "ollama" -> "$HOME/scripts/ollama/ollama_stop.sh"
        "n8n" -> if (readN8nMode() == "udocker") "$HOME/scripts/n8n-udocker/stop.sh" else "$HOME/scripts/n8n/stop_servidor.sh"
        "openclaw" -> "$HOME/scripts/openclaw/openclaw_stop.sh"
        "opencode" -> "$HOME/scripts/opencode/opencode_stop.sh"
        "remote" -> "$HOME/scripts/remote/ssh_stop.sh"
        "llamaserver" -> "$HOME/scripts/llamaserver/stop.sh"
        "db" -> "$HOME/scripts/db/stop.sh"
        "cactus" -> "$HOME/scripts/cactus/stop.sh"
        else -> null
    }

    private fun getTmuxSession(id: String): String? = when (id) {
        "ollama" -> "ollama-server"
        "n8n" -> if (readN8nMode() == "udocker") "n8n-udocker" else "n8n-server"
        "openclaw" -> "openclaw"
        "opencode" -> "opencode"
        "llamaserver" -> "llamaserver"
        "cactus" -> "cactus-server"
        else -> null
    }

    // sshd no corre en tmux (ver ssh_start.sh/ssh_stop.sh — usan pgrep/pkill directo).
    private fun getProcessName(id: String): String? = when (id) {
        "remote" -> "sshd"
        else -> null
    }

    // Puerto real por módulo (ver waitForPortOpen()) — mismos valores que "port" en
    // modules.json para cada módulo con start script. Se hardcodea acá (mismo criterio que
    // getModuleStartScript/getTmuxSession de arriba) en vez de parsear modules.json de nuevo
    // — ModuleController no tiene Context a mano para leer assets, y estos 5 valores son
    // estables (cambia el puerto de un módulo, cambia acá también, no es algo que varíe en
    // runtime).
    private fun getModulePort(id: String): Int? = when (id) {
        "ollama" -> 11434
        "n8n" -> 5678
        "openclaw" -> 18789
        "opencode" -> 3000
        "remote" -> 8022
        "llamaserver" -> 8085
        "db" -> 3306
        "cactus" -> 8977
        else -> null
    }

    /**
     * "Auto-iniciar módulos" (Ajustes): arranca los módulos con switch (hasSwitch=true)
     * que ya están instalados pero detenidos, al abrir la app — NO es un boot receiver
     * real (no hay forma segura de auto-arrancar al encender el teléfono sin más
     * infraestructura), es el alcance honesto que la app puede ofrecer hoy: "al abrir
     * Kairos, retoma los servicios que ya tenías instalados".
     */
    // TermuxActivity.java (Java) llama esto como ModuleController.autoStartEligibleModules(ctx).
    // ModuleController es un Kotlin `object` (singleton) — sin @JvmStatic, sus funciones
    // son métodos de instancia sobre INSTANCE y Java solo puede llamarlas como
    // ModuleController.INSTANCE.foo(), nunca como ModuleController.foo() (error real de
    // build: "non-static method autoStartEligibleModules(Context) cannot be referenced
    // from a static context" — mismo patrón ya usado en KairosBootstrap.kt, ver sus
    // @JvmStatic). @JvmOverloads además genera el overload de un solo argumento que
    // necesita Java para el parámetro onEach con valor por defecto.
    @JvmStatic
    @JvmOverloads
    fun autoStartEligibleModules(context: android.content.Context, onEach: (String, Boolean) -> Unit = { _, _ -> }) {
        Thread {
            try {
                val json = context.assets.open("modules.json").bufferedReader().use { it.readText() }
                val registry = com.termux.app.data.ModuleRegistry(context).load()
                val modules = org.json.JSONArray(json)
                for (i in 0 until modules.length()) {
                    val m = modules.getJSONObject(i)
                    val id = m.optString("id")
                    if (!m.optBoolean("hasSwitch", false)) continue
                    if (registry.get("$id.installed") != "true") continue
                    if (isRunning(id)) continue
                    startModule(id) { ok, _ -> onEach(id, ok) }
                }
            } catch (_: Exception) {
                // Silencioso — esto corre en segundo plano al abrir la app, no debe
                // interrumpir el arranque normal si algo sale mal acá.
            }
        }.start()
    }
}

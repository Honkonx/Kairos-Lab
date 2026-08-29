package com.termux.app.data

import android.content.Context
import com.termux.app.util.TERMUX_BASH_PATH
import com.termux.app.util.applyTermuxEnv
import com.termux.app.util.isTermuxBinaryAvailable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Fuente de verdad unificada de "¿está instalado este módulo?" (2026-08-10, rediseño de la
 * pantalla principal: Módulos muestra SOLO lo instalado — ver docs/arquitectura/PLAN.md
 * "Rediseño de pantallas 2026-08-10").
 *
 * El registry (~/.android_server_registry) es la fuente primaria, pero hay módulos con
 * evidencia real de registry desincronizado del dispositivo (python: registry dice
 * instalado con binario roto — se usa AND en PythonFragment; ollama: registry no sabe pero
 * el binario funciona — se usa OR en OllamaFragment). Cada fragment dedicado resolvía esto
 * con su propio override de isModuleInstalled(). Este helper centraliza el patrón para que
 * ModulesFragment (filtro de la pantalla principal) use la MISMA lógica que los fragments
 * de detalle, sin duplicar el criterio en dos lugares.
 *
 * Regla usada acá (la más inclusiva, correcta para el filtro de la pantalla principal):
 * un módulo está instalado si el registry dice "true" **o** si su binario real responde
 * (isTermuxBinaryAvailable). Así un módulo instalado a mano en terminal (bypaseando la app)
 * sigue apareciendo en Módulos, y un módulo base del wizard (python, que kairos.sh ahora
 * marca en el registry) aparece desde el primer arranque.
 *
 * El chequeo binario spawna bash (isTermuxBinaryAvailable) — se cachea con TTL corto (30s)
 * para que el poll de 5s de ModulesFragment no spawnen bash por módulo en cada ciclo.
 */
object ModuleInstalled {

    // Módulos que se verifican también por binario real cuando el registry NO dice "true".
    // Mismo criterio que los overrides de isModuleInstalled() en PythonFragment/OllamaFragment.
    // remote = sshd (sin tmux, ver ModuleController.isRunning). llamaserver = binario propio.
    //
    // Expandido 2026-08-13 (ver docs/humano/humano118.md, PLAN_EXPANSION_HOMELAB_2026-08-13.md)
    // a todos los módulos con un binario CLI real y de nombre confirmado — cada nombre se
    // verificó contra el `terminalCommand` de modules.json Y el `command -v <bin>` real dentro
    // de su modulos/<id>.sh (no se adivinó ninguno). Quedan afuera a propósito los módulos sin
    // un solo binario representativo: n8n (corre en tmux, sin binario propio único), entorno
    // (proot-distro, no expone un binario en $PREFIX/bin), stacks (catálogo de presets, no un
    // módulo instalable en sí).
    private val BINARY_FALLBACK = mapOf(
        "python" to "python3",
        "ollama" to "ollama",
        "llamaserver" to "llama-server",
        "remote" to "sshd",
        "db" to "mariadbd",
        // claude.sh (método "native"): wrapper real SOLO en $HOME/.local/bin/claude —
        // corregido 2026-08-21 (ver docs/humano/humano182.md), la afirmación vieja de que también
        // había uno en $TERMUX_PREFIX/bin/claude era falsa (confirmado por ADB en dispositivo
        // real: ese archivo no existe). El fix real vive en applyTermuxEnv()
        // (ProcessBuilderExt.kt), que ahora agrega $HOME/.local/bin al PATH usado acá.
        "claude" to "claude",
        // codex.sh: `command -v codex`.
        "codex" to "codex",
        // antigravity.sh: `command -v agy` — mismo binario que ya usaba
        // AntigravityFragment.refreshEstadoPill() para el pill "listo/no responde".
        "antigravity" to "agy",
        // opencode.sh: `command -v opencode`.
        "opencode" to "opencode",
        // openclaw.sh: `command -v openclaw` (instalado en $NPM_BIN).
        "openclaw" to "openclaw",
        // hermes.sh: wrapper final en $INSTALL_DIR/venv/bin/hermes, symlinkeado a
        // $TERMUX_PREFIX/bin/hermes — `command -v hermes` en el script real.
        "hermes" to "hermes",
        // expo.sh: no instala un binario "expo" — instala eas-cli (`command -v eas`), que es
        // el CLI real que se invoca (`eas build`, `eas login`, etc.).
        "expo" to "eas",
        // engram.sh: compila el binario Go en $TERMUX_PREFIX/bin/engram.
        "engram" to "engram",
        // Los 7 CLIs de i-Haklab (freebuff.sh/codebuff.sh/copilotcli.sh/minimaxcli.sh/
        // mimocode.sh/mistralvibe.sh/qwencode.sh) — cada uno confirma su binario real con
        // `command -v <bin> || error "<bin> no disponible tras instalación"` al final del
        // script, mismo nombre que su terminalCommand en modules.json.
        "freebuff" to "freebuff",
        "codebuff" to "codebuff",
        "copilotcli" to "copilot",
        "minimaxcli" to "mmx",
        "mimocode" to "mimo",
        "mistralvibe" to "vibe",
        "qwencode" to "qwen",
        // ciberseguridad.sh: nmap es el primero de los 3 binarios que confirma el script
        // (`command -v nmap && command -v theHarvester && command -v sqlmap`) — representativo
        // del kit completo, mismo terminalCommand del catálogo.
        "ciberseguridad" to "nmap",
        // cactus.sh: `command -v cactus` (wrapper en $TERMUX_PREFIX/bin/cactus).
        "cactus" to "cactus",
        // ide.sh: `command -v nvim` (Neovim + NvChad).
        "ide" to "nvim",
        // apk.sh: wrapper en $TERMUX_PREFIX/bin/compil-apk-termux.
        "apk" to "compil-apk-termux",
        // "languages"/"packages" (2026-08-18, ver docs/modulos/LENGUAJES.md): son módulos
        // CONTENEDOR — sin instalador propio, cada fila interna se instala/desinstala con su
        // propio switch (LanguagesFragment/PackagesFragment). "bash" es un sentinel siempre
        // presente en Termux (requisito base de la app) — el efecto real es que estos 2
        // módulos aparecen SIEMPRE como "instalados" en Módulos, nunca disparan la hoja de
        // instalación (BottomSheetInstalacion), que no tendría ningún sentido para un
        // contenedor sin script de instalación propio.
        "languages" to "bash",
        "packages" to "bash",
        // "verificar" (auditoría 2026-08-20, ver docs/arquitectura/
        // AUDITORIA_BUGS_MONITOR_VERIFICAR_2026-08-19.md — causa real de "el modulo de
        // verificacion da error"): modulos/verificar.sh es una herramienta de diagnóstico
        // que NUNCA se marca "instalada" — no llama a registry_write()/registry_install()
        // para sí misma (solo usa su propio checkpoint privado), y no tenía entrada acá ni
        // en LIVE_FALLBACK, así que isInstalledRobust() siempre devolvía false. Eso hacía
        // que VerificarFragment mostrara siempre "Módulo no instalado", y al tocar
        // "Instalar en segundo plano" corría verificar.sh SIN --json (exit code 1 cuando
        // hay algún WARN — comportamiento intencional del script, ver su cabecera) —
        // ModuleController.installModule() interpreta cualquier exit != 0 como fallo de
        // instalación, así que el usuario veía un error de "instalación" en un script que
        // en realidad corrió bien y solo estaba reportando módulos con problemas. Igual
        // que "languages"/"packages" arriba: verificar.sh se extrae siempre en el bootstrap
        // (KairosBootstrap.doExtract() copia todo *.sh sin condición), no requiere un paso
        // de instalación propio — "bash" como sentinel (siempre presente en Termux) hace
        // que se trate como disponible desde el primer arranque, igual que la realidad.
        "verificar" to "bash",
        // Los 6 lenguajes + 10 herramientas npm consolidados en "languages"/"packages"
        // (2026-08-18) — mismo binario que ya usaban como terminalCommand en modules.json
        // antes de volverse "internal" (ver ModuleInfo.kt). El fallback de binario importa
        // ACÁ más que en el resto de la app: los switches de LanguagesFragment/
        // PackagesFragment necesitan reflejar un pkg/npm instalado a mano en terminal, no
        // solo lo que instaló la propia app.
        "nodejs" to "node",
        "perl" to "perl",
        "php" to "php",
        "rust" to "rustc",
        "clang" to "clang",
        "golang" to "go",
        "typescript" to "tsc",
        "nestjs" to "nest",
        "prettier" to "prettier",
        "livesrv" to "live-server",
        "localtunnel" to "lt",
        "vercel" to "vercel",
        "markserv" to "markserv",
        "psqlformat" to "psqlformat",
        "ncu" to "ncu",
        "ngrok" to "ngrok",
        // "udocker" to "udocker" — REVERTIDO 2026-08-27 (docs/humano272.md, pedido explícito
        // del usuario): el fallback a `command -v udocker` (agregado 2026-08-26 para detectar
        // una instalación manual fuera de la app) detectaba como "instalado" el binario que
        // n8n.sh --variant udocker deja como efecto colateral de su PASO 0 (instala udocker
        // como dependencia interna ANTES de intentar bajar la imagen n8nio/n8n) incluso cuando
        // esa instalación de n8n fallaba después y el usuario nunca tocó "instalar" en el
        // módulo udocker en sí. El binario en sí se deja tal cual en el dispositivo (n8n lo
        // necesita) — solo se saca la detección por PATH, que ahora depende exclusivamente del
        // registry real (`udocker.installed=true`, que solo escribe udocker.sh cuando corre de
        // punta a punta). Contrapartida aceptada explícitamente: una instalación manual de
        // udocker fuera de la app (pkg/pip install a mano en la terminal) ya no se detecta acá
        // — mismo trade-off que ya acepta "repo" (ver comentario debajo).
        // Auditoría docs/arquitectura/AUDITORIA_CONSISTENCIA_MODULOS_2026-08-26.md § 5 — 4
        // entradas ausentes cuyo fallback-a-id (command -v <id>) NUNCA coincide con el binario
        // real que instala el script, confirmado leyendo cada modulos/<id>.sh:
        // cursor.sh: instala cursor-agent en $HOME/.local/bin (terminalCommand en modules.json
        // ya dice "cursor-agent" — solo este mapa Kotlin había quedado desactualizado).
        "cursor" to "cursor-agent",
        // kotlin.sh: install_single_pkg "kotlin" "kotlinc" kotlin — el binario real es el
        // compilador "kotlinc", no "kotlin" (terminalCommand ya correcto en modules.json).
        "kotlin" to "kotlinc",
        // ohmypi.sh: "Comando final: omp" — el binario real es "omp", no "ohmypi".
        "ohmypi" to "omp",
        // qemu.sh: no existe binario "qemu" — instala qemu-user (qemu-x86_64, modo usuario,
        // sin root) y qemu-system-x86_64 (headless, TCG). qemu-x86_64 es el caso de uso sólido
        // documentado en la cabecera del script (correr binarios de otra arch sin root); se usa
        // como representativo, igual que "nmap" para el kit de ciberseguridad.
        "qemu" to "qemu-x86_64",
        // Entradas explícitas por prolijidad (auditoría § 5, "Lista priorizada" ítem 4) — hoy
        // funcionalmente correctas por coincidencia (el fallback-a-id ya acierta), pero
        // documentadas acá para no depender silenciosamente de esa coincidencia si el binario
        // real de un CLI externo cambia de nombre en el futuro. Confirmado el binario real
        // leyendo cada modulos/<id>.sh:
        "kimi" to "kimi",
        "kilo" to "kilo",
        "hf" to "hf",
        // repo.sh (auditoría 2026-08-27, docs/arquitectura/DEPURACION_COMPLETA_2026-08-26.md
        // Ronda R7): confirmado leyendo el script completo — NUNCA instala un binario "repo" (ni
        // ningún otro nombre) en $PATH, es un gestor de repo apt LOCAL (dpkg-deb/jq/gpg, ya
        // presentes en Termux) que solo crea un directorio ($PREFIX/../kairos-repo) y escribe al
        // registry. Esta entrada quedaba SIEMPRE en false (command -v repo nunca puede tener
        // éxito) — se deja documentada por prolijidad, pero la detección real de "repo" vive en
        // LIVE_FALLBACK (DIRECTORY sobre kairos-repo/), no acá.
        "repo" to "repo",
        "pi" to "pi",
        "codegraph" to "codegraph",
    )

    /**
     * Estrategias de verificación EN VIVO de un módulo (2026-08-15, ModuleDoctor +
     * verificación robusta). Port del manifiesto MOD_STRAT de modulos/verificar.sh (6
     * estrategias, mismas que core-termux list.sh — referencia/termux/core-termux-main/
     * core/cli/commands/list.sh): verificar.sh y este map quedan como las dos caras del
     * mismo contrato de "qué significa que un módulo esté realmente instalado".
     *
     * PATH_BINARY     → command -v <bin>                    (CLIs npm/pip/glibc)
     * DPKG_PACKAGE    → dpkg -s <pkg> | grep 'install ok'   (paquetes pkg/apt de Termux)
     * DIRECTORY       → [ -d <dir> ]                        (rootfs, configs, plugins)
     * FILE            → [ -f <archivo> ]                    (wrappers, tokens)
     * PLUGIN_DIR      → [ -d <dir> ]                        (plugins zsh)
     * CONFIG_PATTERN  → ls <glob> | head -1                 (configs con patrón)
     */
    enum class LiveCheck {
        PATH_BINARY, DPKG_PACKAGE, DIRECTORY, FILE, PLUGIN_DIR, CONFIG_PATTERN
    }

    // Estrategia por módulo. Los no listados caen en PATH_BINARY con el binario de
    // BINARY_FALLBACK (o el propio id si no está en ese map). Verificado contra qué
    // instala realmente cada modulos/*.sh (mismo criterio que verificar.sh, 2026-08-13).
    val LIVE_FALLBACK: Map<String, LiveCheck> = mapOf(
        "ollama" to LiveCheck.PATH_BINARY,
        "n8n" to LiveCheck.PATH_BINARY,
        "python" to LiveCheck.PATH_BINARY,
        "claude" to LiveCheck.PATH_BINARY,
        "codex" to LiveCheck.PATH_BINARY,
        "antigravity" to LiveCheck.PATH_BINARY,
        "openclaw" to LiveCheck.PATH_BINARY,
        "opencode" to LiveCheck.PATH_BINARY,
        "hermes" to LiveCheck.PATH_BINARY,
        "remote" to LiveCheck.PATH_BINARY,
        "expo" to LiveCheck.PATH_BINARY,
        "engram" to LiveCheck.PATH_BINARY,
        "freebuff" to LiveCheck.PATH_BINARY,
        "codebuff" to LiveCheck.PATH_BINARY,
        "copilotcli" to LiveCheck.PATH_BINARY,
        "minimaxcli" to LiveCheck.PATH_BINARY,
        "mimocode" to LiveCheck.PATH_BINARY,
        "mistralvibe" to LiveCheck.PATH_BINARY,
        "qwencode" to LiveCheck.PATH_BINARY,
        "ciberseguridad" to LiveCheck.PATH_BINARY,
        "db" to LiveCheck.PATH_BINARY,
        "llamaserver" to LiveCheck.PATH_BINARY,
        "cactus" to LiveCheck.PATH_BINARY,
        "apk" to LiveCheck.PATH_BINARY,
        "kimi" to LiveCheck.PATH_BINARY,
        "kilo" to LiveCheck.PATH_BINARY,
        "cursor" to LiveCheck.PATH_BINARY,
        "hf" to LiveCheck.PATH_BINARY,
        // "udocker" to LiveCheck.PATH_BINARY — revertido junto con BINARY_FALLBACK arriba,
        // mismo motivo real (docs/humano272.md).
        "entorno" to LiveCheck.DPKG_PACKAGE,
        "stacks" to LiveCheck.DPKG_PACKAGE,
        "ide" to LiveCheck.DIRECTORY,
        "kairos" to LiveCheck.DIRECTORY,
        // repo.sh crea $PREFIX/../kairos-repo (mkdir -p en _repo_init(), ver modulos/repo.sh) —
        // el único artefacto real y estable que deja en disco, ver comentario en BINARY_FALLBACK.
        "repo" to LiveCheck.DIRECTORY,
    )

    // Blanco por estrategia cuando no se puede derivar del id: paquete dpkg, ruta de
    // directorio/archivo, o glob de config. Las rutas se interpolan literales en el
    // comando bash (valores fijos del map de desarrollador, no input de usuario) y
    // permiten $HOME para que bash las expanda.
    private val LIVE_TARGET: Map<String, String> = mapOf(
        "entorno" to "proot-distro",
        "stacks" to "proot-distro",
        "ide" to "\$HOME/.config/nvim",
        "kairos" to "\$HOME/kairos",
        // Mismo valor que REPO_ROOT en modulos/repo.sh — applyTermuxEnv() ya setea $PREFIX en
        // el entorno del bash que corre este chequeo (runBashCheck), así que se expande igual.
        "repo" to "\$PREFIX/../kairos-repo",
    )

    private class CacheEntry(val installed: Boolean, val checkedAt: Long)

    private val binaryCache = ConcurrentHashMap<String, CacheEntry>()
    private const val TTL_MS = 30_000L

    // Cache corto de la verificación en vivo (10s): isInstalledRobust() lo usa como
    // segundo nivel cuando el registry y el binario fallan, para no spawnear bash por
    // módulo en cada poll de 5s de ModulesFragment.
    private val liveCache = ConcurrentHashMap<String, CacheEntry>()
    private const val LIVE_TTL_MS = 10_000L

    private class RegistryCacheEntry(val registry: Map<String, String>, val checkedAt: Long)

    @Volatile
    private var registryCache: RegistryCacheEntry? = null

    fun isInstalled(context: Context, moduleId: String): Boolean {
        val registrySaysTrue = registryCached(context)["$moduleId.installed"] == "true"
        if (registrySaysTrue) return true
        val binary = BINARY_FALLBACK[moduleId] ?: return false
        return binaryCheckCached(binary)
    }

    // Módulos donde isInstalledRobust() NO debe caer al chequeo en vivo por defecto
    // (liveIsInstalled() con LiveCheck.PATH_BINARY sin entrada explícita en LIVE_FALLBACK
    // cae a "command -v <moduleId>" — ver liveIsInstalled() abajo). Bug real reportado por
    // el usuario 2026-08-28 (docs/humano278.md): udocker aparecía "Desactivar" (instalado)
    // en la Tienda de plugins (PluginsFragment, que usa isInstalledRobust()) pero NO en la
    // pantalla principal Módulos (ModulesFragment, que usa isInstalled() sin el nivel
    // robusto) — mismo binario "udocker" real en PATH, dos resultados distintos para la
    // misma pregunta. Causa raíz: el revert de 2026-08-27 (docs/humano272.md) sacó "udocker"
    // de BINARY_FALLBACK y de LIVE_FALLBACK para dejar de detectarlo por
    // `command -v udocker` (falso positivo: n8n --variant udocker instala ese binario como
    // dependencia interna aunque el módulo n8n falle después, sin que el usuario haya
    // tocado "instalar" en udocker) — pero liveIsInstalled() con LiveCheck.PATH_BINARY
    // default (ningún override en LIVE_FALLBACK) sigue cayendo a `BINARY_FALLBACK[moduleId]
    // ?: moduleId`, es decir "command -v udocker" de nuevo, sin que la entrada explícita
    // borrada hiciera falta. isInstalledRobust() (no isInstalled()) es el único de los dos
    // niveles que llega a liveIsInstalled(), así que solo la Tienda quedaba con el falso
    // positivo — de ahí el desfasaje exacto reportado. Confirmado en el dispositivo del
    // usuario: ~/.android_server_registry sin ninguna clave "udocker.*".
    private val LIVE_CHECK_DISABLED = setOf("udocker")

    /**
     * Versión robusta: registry O binario (isInstalled, cache 30s) y, si ambos fallan,
     * la verificación EN VIVO específica de la estrategia del módulo (LIVE_FALLBACK,
     * cache 10s). Es lo que usa la UI de detalle (BaseModuleFragment.isModuleInstalled)
     * para que el estado refleje el filesystem real y no solo lo que el registry escribió
     * al final de la instalación.
     */
    fun isInstalledRobust(context: Context, moduleId: String): Boolean {
        if (isInstalled(context, moduleId)) return true
        if (moduleId in LIVE_CHECK_DISABLED) return false
        val now = System.currentTimeMillis()
        liveCache[moduleId]?.let { entry ->
            if (now - entry.checkedAt < LIVE_TTL_MS) return entry.installed
        }
        val live = liveIsInstalled(moduleId)
        liveCache[moduleId] = CacheEntry(live, now)
        return live
    }

    /**
     * Verificación en vivo por estrategia. El binario/programa resuelve el PATH real de
     * Termux via TERMUX_BASH_PATH + applyTermuxEnv() (mismo patrón que isTermuxBinaryAvailable
     * — ver skill kairos-termux-process-exec). Timeout ~8s con destroyForcibly como red de
     * seguridad (mismo criterio que ModuleController).
     */
    fun liveIsInstalled(moduleId: String): Boolean {
        val probe = LIVE_FALLBACK[moduleId] ?: LiveCheck.PATH_BINARY
        val command = when (probe) {
            LiveCheck.PATH_BINARY -> "command -v ${BINARY_FALLBACK[moduleId] ?: moduleId}"
            LiveCheck.DPKG_PACKAGE ->
                "dpkg -s ${LIVE_TARGET[moduleId] ?: moduleId} 2>/dev/null | grep -q 'Status: install ok installed'"
            LiveCheck.DIRECTORY, LiveCheck.PLUGIN_DIR -> {
                val target = LIVE_TARGET[moduleId] ?: return false
                "[ -d $target ]"
            }
            LiveCheck.FILE -> {
                val target = LIVE_TARGET[moduleId] ?: return false
                "[ -f $target ]"
            }
            LiveCheck.CONFIG_PATTERN -> {
                val target = LIVE_TARGET[moduleId] ?: return false
                "ls $target 2>/dev/null | head -1"
            }
        }
        return runBashCheck(command)
    }

    private fun runBashCheck(command: String): Boolean {
        return try {
            val pb = ProcessBuilder(TERMUX_BASH_PATH, "-c", command)
            pb.applyTermuxEnv()
            val process = pb.start()
            process.inputStream.bufferedReader().readText()
            process.errorStream.bufferedReader().readText()
            val finished = process.waitFor(8, TimeUnit.SECONDS)
            if (!finished) process.destroyForcibly()
            finished && process.exitValue() == 0
        } catch (_: Exception) {
            false
        }
    }

    private fun registryCached(context: Context): Map<String, String> {
        val now = System.currentTimeMillis()
        registryCache?.let { entry ->
            if (now - entry.checkedAt < TTL_MS) return entry.registry
        }
        val registry = ModuleRegistry(context).load().getModules()
        registryCache = RegistryCacheEntry(registry, now)
        return registry
    }

    /**
     * Invalida el cache de "¿está instalado?" para [moduleId] (o todo el cache si es null) —
     * bug real reportado por el usuario: "al instalar un plugin no sale instalado y todavía da
     * la opción de instalar" (ver docs/humano/humano166.md/humano167.md). El registry en disco ya
     * puede decir `<id>.installed=true` apenas termina el script de instalación, pero
     * [registryCached] seguía sirviendo el snapshot leído hasta 30s ANTES de eso — cualquier
     * Fragment que releía el estado justo después de una instalación (BaseModuleFragment.
     * isModuleInstalled(), ModulesFragment/PluginsFragment.pollStatus()) podía seguir mostrando
     * "no instalado" hasta que el TTL expirara solo. Se llama desde ModuleController tras
     * install/uninstall/deepUninstall — el único lugar central donde termina cualquier cambio
     * real de instalación, sin importar qué UI lo disparó (BottomSheetInstalacion,
     * installModuleInBackground, la Tienda de plugins, etc.).
     */
    fun invalidate(moduleId: String? = null) {
        registryCache = null
        if (moduleId == null) {
            binaryCache.clear()
            liveCache.clear()
            return
        }
        BINARY_FALLBACK[moduleId]?.let { binaryCache.remove(it) }
        liveCache.remove(moduleId)
    }

    private fun binaryCheckCached(binary: String): Boolean {
        val now = System.currentTimeMillis()
        binaryCache[binary]?.let { entry ->
            if (now - entry.checkedAt < TTL_MS) return entry.installed
        }
        val installed = isTermuxBinaryAvailable(binary)
        binaryCache[binary] = CacheEntry(installed, now)
        return installed
    }
}

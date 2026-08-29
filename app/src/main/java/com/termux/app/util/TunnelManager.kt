package com.termux.app.util

import com.termux.shared.termux.TermuxConstants
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Lógica de `cmd_tunnel` (+ helpers `_ensure_ngrok`/`_ensure_cloudflared`/`_tunnel_session`)
 * de modulos/kairos_manager.py portada a Kotlin (ronda 2026-07-31). TunnelFragment dependía
 * de `python3 kairos_manager.py tunnel ...` para TODO — mismo bug sistémico "Cannot run
 * program python3" con evidencia real de dispositivo que ya rompió Procesos/Monitor (ver
 * docs/humano*.md de esa ronda). Mecanismo genérico (cloudflared/ngrok quick-tunnel por
 * puerto) separado de los túneles bespoke de n8n (N8nFragment) y Remote/SSH (RemoteManager)
 * — no los reemplaza, sigue siendo un camino nuevo y único para CUALQUIER puerto.
 */
object TunnelManager {

    data class ActionResult(val ok: Boolean, val message: String = "", val error: String = "")
    // ownTunnel: true cuando lo que se está reportando NO es una sesión tmux-$port
    // manejada por este objeto, sino el túnel bespoke que modulos/n8n.sh arranca solo
    // (ver n8nOwnTunnelActive() más abajo y docs/arquitectura/AUDITORIA_NUBE_CODIGO_2026-08-19.md
    // § 2) — el fragment lo usa para no ofrecer "Detener" como si fuera a matarlo de verdad.
    data class TunnelStatus(val running: Boolean, val url: String, val ownTunnel: Boolean = false)
    data class TunnelInfo(val moduleId: String, val port: Int, val tunnelRunning: Boolean, val serviceUp: Boolean, val ownTunnel: Boolean = false)

    /**
     * Configuración persistente de un proveedor de túnel (Cloudflare / ngrok) guardada
     * en el registry ~/.android_server_registry (claves tunnel.cloudflare.* y
     * tunnel.ngrok.*). Pedido explícito del usuario (ronda 2026-08-13, ver
     * docs/humano/humano100.md): poder agregar/cambiar/eliminar dominios y tokens desde
     * la pantalla de Túnel, y que los botones rápidos reutilicen lo guardado.
     */
    data class ProviderConfig(val token: String = "", val domain: String = "") {
        val configured: Boolean get() = token.isNotBlank() || domain.isNotBlank()
    }

    private data class CmdResult(val exitCode: Int, val stdout: String, val stderr: String)

    private val HOME = TermuxConstants.TERMUX_HOME_DIR_PATH
    private val PREFIX = "/data/data/com.termux/files/usr"
    private val TUNNEL_LOG_DIR = File(HOME, ".tunnel_logs")
    // Mismo archivo de lock que ProjectsManager/EntornoNative — consolidación 2026-08-13
    // (ver auditoría de código): las escrituras del registry de acá ahora se serializan
    // contra el resto de escritores del mismo ~/.android_server_registry, no solo entre sí.
    private val REGISTRY_LOCK_FILE get() = ManagerNativeUtils.registryLockFile

    // Puertos conocidos de los módulos con proceso — el tab Túnel los ofrece todos,
    // no solo n8n/remote (que ya tenían su propio camino bespoke).
    //
    // "remote" (sshd, :8022) se agrega acá 2026-08-19 (pedido explícito del usuario:
    // "el dispositivo puede ser controlado por ssh no? toca mejorar esa area para que el
    // dispositivo pueda actuar como vps") — es el path más realista para que el
    // dispositivo actúe como VPS sin IP pública: exponer sshd a internet vía este mismo
    // mecanismo genérico, en vez de depender solo del túnel bespoke de RemoteManager
    // (cfStart/cfStop, que YA hacía esto pero únicamente con Cloudflare + token). Ver
    // TCP_ONLY_MODULES abajo — SSH es tráfico TCP crudo, no HTTP, así que no todos los
    // botones de este tab le aplican igual que a ollama/n8n/openclaw/opencode.
    // Público desde 2026-08-26 (feature de Túnel multi-dominio, ver docs/estructura/TUNEL.md)
    // — TunnelFragment.buildAssignDialog() necesita listar los módulos reales a los que se
    // puede asignar un dominio guardado, sin duplicar esta lista en dos archivos.
    val KNOWN_MODULES = linkedMapOf(
        "ollama" to 11434,
        "n8n" to 5678,
        "openclaw" to 18789,
        "opencode" to 3000,
        "remote" to 8022,
        // Gap real encontrado en la auditoría 2026-08-25 (docs/modulos/N8N.md sección 6): IA
        // Local (llama.cpp, LlamaServerFragment) nunca se agregó acá pese a tener puerto fijo
        // real confirmado (llamaserver.port=8085 en el registry) — quedaba sin ninguna opción
        // de exposición por túnel, ni genérica (acá) ni bespoke propia.
        "llamaserver" to 8085
    )

    // Módulos cuyo puerto es TCP crudo (no HTTP) — determina qué comando de túnel se arma:
    // cloudflared quick-tunnel (`--url http://...`) y `ngrok http` NO sirven para SSH, solo
    // `ngrok tcp` o un túnel nombrado de Cloudflare con ingress TCP configurado del lado del
    // dashboard (que ya funciona hoy sin cambios — ver rama `effectiveToken` de start()).
    val TCP_ONLY_MODULES = setOf("remote")

    private fun isTcpOnly(moduleId: String): Boolean = moduleId in TCP_ONLY_MODULES

    // Los 4 patrones combinados en un único regex de alternancia — igual que el
    // re.findall(patrón único) de Python, para que "la última URL que aparece en el
    // log" sea la misma sin importar de qué proveedor sea (findAll ya respeta orden
    // de aparición en el texto, no orden de patrón).
    // "tcp://...ngrok.io:PORT" agregado 2026-08-19 (soporte SSH/`ngrok tcp`, ver
    // KNOWN_MODULES/TCP_ONLY_MODULES arriba) — es el único de los 5 patrones que no
    // empieza con "https://": el log de `ngrok tcp` imprime la URL de forwarding con
    // esquema tcp://, no http(s)://.
    private val TUNNEL_URL_REGEX = Regex(
        "https://[a-zA-Z0-9.-]+trycloudflare\\.com\\S*" +
            "|https://[a-zA-Z0-9.-]+\\.cfargotunnel\\.com\\S*" +
            "|https://[a-zA-Z0-9.-]+\\.ngrok(?:-free)?\\.app\\S*" +
            "|https://[a-zA-Z0-9.-]+\\.ngrok\\.io\\S*" +
            "|tcp://[a-zA-Z0-9.-]+\\.ngrok\\.io:[0-9]+"
    )

    // Delegado a ManagerNativeUtils.runShell() — antes reimplementaba el mismo
    // ProcessBuilder+applyTermuxEnv()+waitFor(timeout)+destroyForcibly() (consolidación
    // 2026-08-13, ver auditoría de código). Envuelve el Triple en CmdResult para no tocar
    // los ~15 call sites de este archivo que ya usan `.exitCode`/`.stdout`/`.stderr`.
    private fun runCmd(cmd: String, timeoutSec: Long = 30): CmdResult {
        val (exitCode, stdout, stderr) = ManagerNativeUtils.runShell(cmd, timeoutSec)
        return CmdResult(exitCode, stdout, stderr)
    }

    private fun tunnelSession(port: Int) = "tunnel-$port"

    // ═══════════════════════════════════════════════════════════
    //  DETECCIÓN DEL TÚNEL PROPIO DE n8n (modulos/n8n.sh)
    // ═══════════════════════════════════════════════════════════
    // Fix acotado (auditoría 2026-08-19, ver
    // docs/arquitectura/AUDITORIA_NUBE_CODIGO_2026-08-19.md § 2): n8n.sh arranca su
    // PROPIO `cloudflared tunnel` dentro de su propia sesión tmux ("n8n-udocker" o
    // "n8n-server", ventana "tunnel") cada vez que arranca n8n, salvo que exista
    // ~/.n8n_local_only — completamente por fuera de este objeto. Sin esta detección,
    // `start()` podía arrancar un SEGUNDO cloudflared para el mismo puerto 5678 sin que
    // el usuario lo supiera. No se migra el túnel de n8n a este mecanismo (romperia el
    // flujo standalone/TUI de termux-ai-stack-dev) — solo se detecta y se avisa.
    private val N8N_OWN_TUNNEL_SESSIONS = listOf("n8n-udocker", "n8n-server")
    private val N8N_LOCAL_ONLY_FLAG get() = File(HOME, ".n8n_local_only")
    private val N8N_LAST_URL_FILE get() = File(HOME, ".last_cf_url")

    private fun n8nOwnTunnelActive(): Boolean {
        if (N8N_LOCAL_ONLY_FLAG.exists()) return false
        return N8N_OWN_TUNNEL_SESSIONS.any { ManagerNativeUtils.tmuxHas(it) }
    }

    // n8n.sh escribe la URL final (si la hay) en ~/.last_cf_url — mismo archivo que
    // N8nFragment ya lee para mostrarla en su propia pantalla, así que no hace falta
    // parsear el log del túnel de nuevo acá.
    private fun n8nOwnTunnelUrl(): String = try {
        if (N8N_LAST_URL_FILE.exists()) N8N_LAST_URL_FILE.readText().trim() else ""
    } catch (e: Exception) {
        ""
    }

    // Patrón confirmado en core-termux-main (docs/humano/humano1.md punto 3): ngrok se instala
    // vía su paquete npm oficial, que baja su propio binario real en el postinstall —
    // mucho más simple que cloudflared (sin build ARM64 propio).
    private fun ensureNgrok(): ActionResult {
        if (runCmd("command -v ngrok", 5).exitCode == 0 && runCmd("ngrok version", 5).exitCode == 0) {
            return ActionResult(true)
        }
        if (runCmd("command -v node", 5).exitCode != 0) {
            val install = runCmd("pkg install -y nodejs-lts", 90)
            if (install.exitCode != 0) {
                return ActionResult(false, error = install.stderr.ifBlank { "no se pudo instalar nodejs-lts" })
            }
        }
        val npm = runCmd("npm install -g ngrok", 90)
        if (npm.exitCode != 0) return ActionResult(false, error = npm.stderr.ifBlank { "npm install -g ngrok falló" })
        val verify = runCmd("ngrok version", 5)
        if (verify.exitCode != 0) return ActionResult(false, error = "ngrok instalado pero no ejecuta")
        return ActionResult(true)
    }

    // cloudflared nativo embebido (bug #2, DNS — causa raíz confirmada con pruebas reales
    // en dispositivo, ver docs/adb/AUDITORIA_ADB_DISPOSITIVO_REAL_2026-08-21.md
    // Ronda 6): el binario oficial de cloudflared (releases GitHub, CGO_ENABLED=0) usa el
    // resolver DNS puro de Go, que en Android busca /etc/resolv.conf real del sistema (no
    // existe, solo lectura sin root) y falla siempre — ni GODEBUG=netdns=go ni HOSTALIASES
    // cambian nada porque el binario oficial nunca tuvo soporte cgo compilado. CI (ver
    // .github/workflows/build-app.yml) compila cloudflared desde fuente con CGO_ENABLED=1 +
    // el clang del NDK apuntando a Android, empaquetado como app/src/main/jniLibs/
    // arm64-v8a/libcloudflared.so — ese binario usa getaddrinfo() real de Bionic (resolver
    // nativo de Android) en vez del resolver puro de Go, evitando el bug de raíz. Se prefiere
    // sobre el cloudflared de Termux (pkg/descarga) cuando existe.
    private fun nativeCloudflaredPath(nativeLibDir: String?): String? {
        if (nativeLibDir.isNullOrEmpty()) return null
        val f = File(nativeLibDir, "libcloudflared.so")
        return if (f.exists() && f.canExecute()) f.absolutePath else null
    }

    private fun ensureCloudflared(nativeLibDir: String? = null): ActionResult {
        if (nativeCloudflaredPath(nativeLibDir) != null) return ActionResult(true)
        if (runCmd("command -v cloudflared", 5).exitCode == 0) {
            // Ya existe en PATH — pero puede ser un binario roto de un intento previo
            // fallido, así que igual se verifica que corra de verdad.
            if (runCmd("cloudflared --version", 5).exitCode == 0) return ActionResult(true)
        }
        if (runCmd("pkg install -y cloudflared", 60).exitCode == 0) return ActionResult(true)
        val dest = "$PREFIX/bin/cloudflared"
        val url = "https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-arm64"
        val download = runCmd("wget -q -O '$dest' '$url' && chmod +x '$dest'", 60)
        if (download.exitCode != 0) return ActionResult(false, error = download.stderr.ifBlank { "descarga falló" })
        // Verificación real de ejecución — no solo que el archivo exista (el binario
        // ARM64 de cloudflared no siempre corre sobre Bionic, mismo bug ya encontrado
        // y corregido en ssh.sh/n8n.sh).
        val verify = runCmd("cloudflared --version", 5)
        if (verify.exitCode != 0) {
            runCmd("rm -f '$dest'", 5)
            return ActionResult(false, error = "cloudflared descargado pero no ejecuta (incompatible con Bionic)")
        }
        return ActionResult(true)
    }

    // domain: dominio propio/reservado (ngrok: "--domain", uno gratis por cuenta con
    // authtoken configurado; cloudflared: no aplica acá — con --token el dominio ya
    // quedó atado del lado del dashboard de Cloudflare al crear el named tunnel, así
    // que pedirlo de nuevo en la app sería redundante). Pedido explícito del usuario,
    // 2026-08-12 (ver docs/humano/humano99.md): "agregar una opcion en tunel para
    // agregar los dominios y tokens, sea de cloudflare o ngrok" — cloudflare con token
    // ya existía (promptTokenAndStart en TunnelFragment); lo que faltaba de verdad era
    // el authtoken de ngrok, sin el cual ngrok solo da URLs efímeras al azar y no deja
    // usar un dominio propio en absoluto.
    //
    // 2026-08-13 (ver docs/humano/humano100.md): si NO se pasan token/domain explícitos
    // (null), se reutiliza la config guardada en el registry (getConfig) — así los
    // botones rápidos "▶ Cloudflare"/"🚇 ngrok" usan el token/dominio persistidos en
    // vez de volver a preguntar. El valor explícito siempre tiene prioridad.
    // nativeLibDir: applicationInfo.nativeLibraryDir del Fragment que llama (TunnelFragment/
    // NubeFragment/StacksProjectFragment) — se resuelve en runtime porque Android randomiza
    // ese path por instalación, no se puede hardcodear. null (default) preserva el
    // comportamiento previo a esta ronda para cualquier caller que no lo pase todavía.
    fun start(port: Int, provider: String, token: String?, domain: String? = null, moduleId: String = "", nativeLibDir: String? = null): ActionResult {
        if (provider != "cloudflared" && provider != "ngrok") {
            return ActionResult(false, error = "Provider desconocido: $provider")
        }
        // n8n.sh ya arranca su propio cloudflared al iniciar n8n (salvo
        // ~/.n8n_local_only) — evita un segundo túnel concurrente para el mismo :5678
        // (ver docs/arquitectura/AUDITORIA_NUBE_CODIGO_2026-08-19.md § 2).
        if (moduleId == "n8n" && n8nOwnTunnelActive()) {
            val url = n8nOwnTunnelUrl()
            return ActionResult(
                false,
                error = if (url.isNotEmpty())
                    "n8n ya tiene su propio túnel activo: $url — no hace falta arrancar otro."
                else
                    "n8n ya tiene su propio túnel activo (aún sin URL detectada — revisá el log de n8n) — no hace falta arrancar otro. Para desactivarlo, creá ~/.n8n_local_only y reiniciá n8n."
            )
        }
        var effectiveToken = token
        var effectiveDomain = domain
        if (effectiveToken.isNullOrEmpty()) {
            val saved = getConfig(provider)
            if (saved.token.isNotBlank()) effectiveToken = saved.token
            if (effectiveDomain.isNullOrEmpty() && saved.domain.isNotBlank()) effectiveDomain = saved.domain
        }
        val session = tunnelSession(port)
        if (ManagerNativeUtils.tmuxHas(session)) return ActionResult(false, error = "Ya hay un túnel activo en :$port")
        TUNNEL_LOG_DIR.mkdirs()
        val log = File(TUNNEL_LOG_DIR, "$port.log").absolutePath

        val tcpOnly = isTcpOnly(moduleId)
        val cmd: String
        if (provider == "ngrok") {
            val ensure = ensureNgrok()
            if (!ensure.ok) return ActionResult(false, error = "ngrok no disponible: ${ensure.error}")
            if (!effectiveToken.isNullOrEmpty()) {
                val authtoken = runCmd("ngrok config add-authtoken ${shellQuote(effectiveToken)}", 10)
                if (authtoken.exitCode != 0) {
                    return ActionResult(false, error = "No se pudo guardar el authtoken de ngrok: ${authtoken.stderr.ifBlank { authtoken.stdout }}")
                }
            }
            cmd = if (tcpOnly) {
                // `ngrok tcp` reenvía el puerto crudo (SSH, no HTTP) — no acepta `--domain`
                // (eso es una feature de dominio reservado HTTP); una dirección TCP
                // reservada usa `--remote-addr`, que no se expone en la UI todavía (fuera
                // de alcance de esta ronda — YAGNI hasta que alguien lo pida).
                "ngrok tcp $port --log stdout"
            } else if (!effectiveDomain.isNullOrEmpty()) {
                "ngrok http $port --domain=${shellQuote(effectiveDomain)} --log stdout"
            } else {
                "ngrok http $port --log stdout"
            }
        } else {
            val ensure = ensureCloudflared(nativeLibDir)
            if (!ensure.ok) return ActionResult(false, error = "cloudflared no disponible: ${ensure.error}")
            // Binario nativo (CGO+NDK, resolver Bionic real) si CI lo compiló para este
            // APK; si no (build viejo sin este paso, o falta el .so por algún motivo),
            // cae al cloudflared de Termux (pkg/descarga) como hasta ahora.
            val cfBin = nativeCloudflaredPath(nativeLibDir)?.let { shellQuote(it) } ?: "cloudflared"
            if (effectiveToken.isNullOrEmpty()) {
                if (tcpOnly) {
                    // El quick-tunnel anónimo de cloudflared (`--url http://...`) solo sirve
                    // para HTTP — SSH necesita un túnel nombrado (con token) cuyo ingress TCP
                    // ya se configura del lado del dashboard de Cloudflare.
                    return ActionResult(
                        false,
                        error = "Cloudflare sin token solo soporta HTTP — para SSH usá 'Con token' (túnel nombrado con ingress TCP configurado en el dashboard) o ngrok tcp."
                    )
                }
                cmd = "$cfBin tunnel --no-autoupdate --url http://localhost:$port"
            } else {
                // El token viene de un diálogo de texto libre en la app — sin quoting, uno
                // con comillas/`;`/`$` rompía el comando armado a mano (mismo fix que
                // shlex.quote() aplicaba en el Python original). El puerto NO se referencia
                // acá — el ingress (HTTP o TCP) ya queda atado del lado del dashboard al
                // crear el túnel nombrado, por eso este mismo camino ya sirve para SSH sin
                // cambios: solo hacía falta que "remote" apareciera en KNOWN_MODULES.
                cmd = "$cfBin tunnel --no-autoupdate run --token ${shellQuote(effectiveToken)}"
            }
        }
        // Bug real confirmado (auditoría ADB 2026-08-21, ver docs/humano/humano183.md/humano184.md):
        // tanto cloudflared como ngrok (ambos binarios Go oficiales, CGO_ENABLED=0) fallan en
        // este dispositivo con "lookup ... on [::1]:53: read: connection refused" — el resolver
        // DNS puro de Go busca /etc/resolv.conf real de Android (no existe, solo lectura) en vez
        // del resolv.conf de Termux. GODEBUG=netdns=go/cgo NO arregla esto (confirmado en Ronda 6,
        // docs/adb/AUDITORIA_ADB_DISPOSITIVO_REAL_2026-08-21.md): el binario oficial
        // nunca tuvo soporte cgo compilado, el GODEBUG no tiene nada que alternar. Se sigue
        // aplicando para ngrok (sin alternativa nativa — CLI de código cerrado, no se puede
        // recompilar con CGO) y para el cloudflared de fallback no-nativo, pero NO para el
        // binario nativo (cfBin de nativeCloudflaredPath) — ese SÍ tiene cgo real compilado y
        // usa getaddrinfo() de Bionic correctamente; forzar GODEBUG=netdns=go ahí revertiría al
        // mismo resolver puro de Go que causa el bug, anulando el punto de compilarlo nativo.
        val usingNativeCf = provider == "cloudflared" && nativeCloudflaredPath(nativeLibDir) != null
        val envPrefix = if (usingNativeCf) "" else "GODEBUG=netdns=go "
        runCmd("tmux new-session -d -s $session \"$envPrefix$cmd 2>&1 | tee '$log'\"", 10)
        return ActionResult(true, message = "Iniciando túnel ($provider) en :$port — consultar 'status' en unos segundos para la URL")
    }

    fun stop(port: Int, moduleId: String = ""): ActionResult {
        val session = tunnelSession(port)
        if (!ManagerNativeUtils.tmuxHas(session)) {
            // El túnel visible en la card puede ser el propio de n8n, no uno que este
            // objeto haya arrancado — "Detener" acá no lo toca de verdad, así que se
            // avisa en vez de devolver un falso "detenido" (ver AUDITORIA_NUBE_CODIGO
            // 2026-08-19 § 2, punto "el botón Detener no toca la ventana tunnel de n8n").
            if (moduleId == "n8n" && n8nOwnTunnelActive()) {
                return ActionResult(
                    false,
                    error = "Ese es el túnel propio de n8n (no uno arrancado desde acá) — no se puede detener desde este botón. Para apagarlo: detené n8n, o creá ~/.n8n_local_only y reiniciá n8n para que no lo vuelva a arrancar."
                )
            }
            return ActionResult(false, error = "No hay túnel activo en :$port")
        }
        runCmd("tmux kill-session -t $session", 5)
        return ActionResult(true, message = "Túnel :$port detenido")
    }

    fun status(port: Int, moduleId: String = ""): TunnelStatus {
        val session = tunnelSession(port)
        val running = ManagerNativeUtils.tmuxHas(session)
        var url = ""
        val log = File(TUNNEL_LOG_DIR, "$port.log")
        if (running && log.exists()) {
            try {
                // Bug real confirmado (auditoría ADB 2026-08-21, ver docs/humano/humano183.md/
                // humano184.md, "falso positivo de éxito" — el hallazgo más grave del día):
                // cuando cloudflared falla, su propio mensaje de error contiene el literal
                // "https://api.trycloudflare.com/tunnel":" (la URL del endpoint de la API que
                // intentó llamar, con las comillas/dos puntos del texto de error) — el regex
                // de arriba lo matcheaba igual que una URL de túnel real, marcando el túnel
                // como "activo" con un link roto pese a que ningún proceso cloudflared/ngrok
                // seguía corriendo. Dos filtros: (a) "api.trycloudflare.com" es siempre el host
                // fijo de la API, nunca el subdominio real asignado a un quick-tunnel — se
                // descarta explícitamente; (b) solo se buscan matches en líneas que no sean de
                // error (nivel "eror"/"error", o la línea de request "Post \"..." que cloudflared
                // imprime al fallar el dial).
                val candidateLines = log.readText().lineSequence().filterNot { line ->
                    val lower = line.lowercase()
                    "lvl=eror" in lower || "level=error" in lower || (lower.contains("post \"") && lower.contains("dial"))
                }.joinToString("\n")
                val matches = TUNNEL_URL_REGEX.findAll(candidateLines)
                    .map { it.value }
                    .filterNot { it.contains("api.trycloudflare.com") }
                    .toList()
                if (matches.isNotEmpty()) url = matches.last()
            } catch (e: Exception) {
                // Igual que el `except Exception: pass` de Python — un log ilegible no
                // debe tumbar el poll de estado, solo se reporta sin URL todavía.
            }
        }
        if (!running && moduleId == "n8n" && n8nOwnTunnelActive()) {
            return TunnelStatus(true, n8nOwnTunnelUrl(), ownTunnel = true)
        }
        return TunnelStatus(running, url)
    }

    fun list(): List<TunnelInfo> {
        return KNOWN_MODULES.map { (moduleId, staticPort) ->
            // "remote" (sshd) es el único puerto de KNOWN_MODULES que el usuario puede cambiar
            // en vivo (ver RemoteManager.sshSetPort(), panel de seguridad SSH 2026-08-19) — se
            // resuelve acá contra sshd_config real en vez de asumir el 8022 fijo, para que el
            // tab Túnel no ofrezca exponer un puerto que sshd ya dejó de escuchar.
            val port = if (moduleId == "remote") RemoteManager.sshSecurityConfig().port else staticPort
            val managedRunning = ManagerNativeUtils.tmuxHas(tunnelSession(port))
            val ownActive = !managedRunning && moduleId == "n8n" && n8nOwnTunnelActive()
            TunnelInfo(moduleId, port, managedRunning || ownActive, ManagerNativeUtils.checkPort(port), ownTunnel = ownActive)
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  CONFIG PERSISTENTE — dominios y tokens por proveedor
    // ═══════════════════════════════════════════════════════════
    // Guardadas en ~/.android_server_registry (mismo registro compartido con el resto
    // del proyecto, ver docs/arquitectura/REGISTRO.md si existe). Claves:
    //   tunnel.cloudflare.token / tunnel.cloudflare.domain
    //   tunnel.ngrok.token      / tunnel.ngrok.domain
    // Pedido explícito del usuario, 2026-08-13 (ver docs/humano/humano100.md): "agregar
    // opciones de cloudflare, agregar dominio y token para cloudflare y ngrok... poder
    // agregar los dominios que quieran, los token, poder cambiarlos, modificarlos,
    // eliminarlos, configurar cloudflare y ngrok desde la pantalla de tunel".
    private fun registryFile() = File(HOME, ".android_server_registry")

    private fun registryValues(): Map<String, String> {
        val file = registryFile()
        if (!file.exists()) return emptyMap()
        return try {
            file.readLines().mapNotNull { line ->
                val t = line.trim()
                if (t.isEmpty() || t.startsWith("#")) null
                else {
                    val eq = t.indexOf('=')
                    if (eq > 0) t.substring(0, eq).trim() to t.substring(eq + 1).trim() else null
                }
            }.toMap()
        } catch (e: Exception) { emptyMap() }
    }

    // Escritura cruda sin lock propio — SIEMPRE debe llamarse desde dentro de un
    // RegistryLock.withLock(REGISTRY_LOCK_FILE) ya abierto por el caller. Extraída para que
    // setRegistryValues()/removeRegistryValues()/addSaved()/deleteSaved() puedan compartir
    // un ÚNICO lock por operación en vez de anidar RegistryLock.withLock() dentro de sí
    // mismo — RegistryLock usa `channel.lock()` de java.nio, que NO es reentrante ni
    // siquiera dentro del mismo thread/JVM (lanzaría OverlappingFileLockException si
    // addSaved() adquiriera el lock y, adentro, llamara a un setRegistryValues() que
    // intenta adquirirlo de nuevo antes de soltar el primero).
    private fun writeRegistryFileLocked(mutate: (MutableMap<String, String>) -> Unit) {
        val file = registryFile()
        val current = registryValues().toMutableMap()
        mutate(current)
        file.writeText(current.entries.joinToString("\n") { "${it.key}=${it.value}" } + "\n")
    }

    // read-modify-write completo envuelto en RegistryLock (consolidación 2026-08-13, ver
    // auditoría de código) — antes sin ninguna protección de concurrencia contra otras
    // escrituras simultáneas del mismo ~/.android_server_registry (ProjectsManager,
    // EntornoNative, una instalación de módulo en background, etc.).
    private fun setRegistryValues(values: Map<String, String>) {
        RegistryLock.withLock(REGISTRY_LOCK_FILE) {
            writeRegistryFileLocked { current -> current.putAll(values) }
        }
    }

    private fun removeRegistryValues(keys: List<String>) {
        RegistryLock.withLock(REGISTRY_LOCK_FILE) {
            writeRegistryFileLocked { current -> keys.forEach { current.remove(it) } }
        }
    }

    // moduleId vacío ("", default) = config GLOBAL compartida por los botones rápidos de
    // TunnelFragment (comportamiento previo, claves "tunnel.cloudflare.*"/"tunnel.ngrok.*",
    // sin cambios). moduleId no vacío = config PROPIA de ese módulo (claves
    // "tunnel.<id>.cloudflare.*"/"tunnel.<id>.ngrok.*") — pedido explícito del usuario
    // (ronda 2026-08-25): n8n/Remote/etc. conservan su botón propio de "agregar token y
    // dominio" en su pantalla, pero el valor se guarda ACÁ (puente interno a Tunnel, con
    // el id del módulo que lo pidió) en vez de en un archivo propio de cada módulo — un
    // solo lugar de guardado, sin perder el atajo cómodo desde la pantalla de cada módulo.
    private fun configKeys(provider: String, moduleId: String = ""): Pair<String, String> {
        val prefix = if (moduleId.isBlank()) "tunnel" else "tunnel.$moduleId"
        return when (provider) {
            "cloudflared" -> "$prefix.cloudflare.token" to "$prefix.cloudflare.domain"
            "ngrok" -> "$prefix.ngrok.token" to "$prefix.ngrok.domain"
            else -> "$prefix.unknown.token" to "$prefix.unknown.domain"
        }
    }

    /**
     * Devuelve la config guardada para un proveedor ("cloudflared" | "ngrok"). [moduleId]
     * vacío (default) lee la config global compartida (botones rápidos de TunnelFragment);
     * un id de módulo (ej. "n8n", "remote") lee la config propia de ese módulo.
     */
    fun getConfig(provider: String, moduleId: String = ""): ProviderConfig {
        if (provider != "cloudflared" && provider != "ngrok") return ProviderConfig()
        val (tokenKey, domainKey) = configKeys(provider, moduleId)
        val reg = registryValues()
        return ProviderConfig(reg[tokenKey].orEmpty(), reg[domainKey].orEmpty())
    }

    /** Guarda (o actualiza) token/dominio de un proveedor en el registry, opcionalmente
     *  asociado a un [moduleId] propio (ver [getConfig]). */
    fun saveConfig(provider: String, token: String, domain: String, moduleId: String = ""): ActionResult {
        if (provider != "cloudflared" && provider != "ngrok") {
            return ActionResult(false, error = "Provider desconocido: $provider")
        }
        val (tokenKey, domainKey) = configKeys(provider, moduleId)
        setRegistryValues(mapOf(tokenKey to token, domainKey to domain))
        val mirrorOk = mirrorBespokeFiles(provider, moduleId, token, domain)
        return if (mirrorOk) ActionResult(true, message = "Configuración guardada para $provider")
        else ActionResult(false, error = "Config guardada en el registry pero no se pudo escribir el archivo que $moduleId lee de verdad — revisá manualmente")
    }

    /** Elimina token/dominio guardados de un proveedor, opcionalmente de un [moduleId]
     *  propio (ver [getConfig]). */
    fun clearConfig(provider: String, moduleId: String = ""): ActionResult {
        if (provider != "cloudflared" && provider != "ngrok") {
            return ActionResult(false, error = "Provider desconocido: $provider")
        }
        val (tokenKey, domainKey) = configKeys(provider, moduleId)
        removeRegistryValues(listOf(tokenKey, domainKey))
        mirrorBespokeFiles(provider, moduleId, "", "")
        return ActionResult(true, message = "Configuración eliminada para $provider")
    }

    // ═══════════════════════════════════════════════════════════
    //  ESPEJO A LOS ARCHIVOS REALES QUE LOS SCRIPTS BASH LEEN
    // ═══════════════════════════════════════════════════════════
    // Bug real confirmado 2026-08-26 (auditoría de Túnel, ver docs/estructura/TUNEL.md):
    // el registry (~/.android_server_registry) es la fuente de verdad para la UI, pero
    // modulos/n8n.sh (ambas variantes proot/udocker, líneas ~305 y ~470) NO sabe leer ese
    // archivo — sigue leyendo el token de arranque real desde ~/.cf_token, un archivo de
    // una sola línea. Antes de este fix, N8nFragment.writeCfToken() guardaba en el
    // registry y BORRABA ~/.cf_token como "legacy" — el token quedaba "guardado" en la UI
    // pero n8n.sh nunca lo veía, así que siempre arrancaba con túnel anónimo pese a tener
    // token configurado. mirrorBespokeFiles() centraliza el fix acá (en vez de solo en
    // N8nFragment) para que CUALQUIER camino que llame a saveConfig/clearConfig con
    // moduleId="n8n" (incluida la asignación de dominios desde el tab Túnel) mantenga
    // ~/.cf_token sincronizado, no solo el diálogo propio de la pantalla de n8n.
    //
    // "remote" (SSH) NO necesita este espejo — RemoteManager.cfStart() ya lee el token
    // directo de TunnelManager.getConfig() en Kotlin, no pasa por un script bash que lea
    // un archivo (confirmado por grep, ver RemoteManager.kt líneas ~428-431) — nunca tuvo
    // este bug.
    private fun mirrorBespokeFiles(provider: String, moduleId: String, token: String, domain: String): Boolean {
        if (provider != "cloudflared" || moduleId != "n8n") return true
        return try {
            val tokenFile = File(HOME, ".cf_token")
            if (token.isBlank()) tokenFile.delete() else tokenFile.writeText(token)
            true
        } catch (e: Exception) {
            false
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  DOMINIOS/TOKENS GUARDADOS (N por proveedor, sin dueño hasta asignarse)
    // ═══════════════════════════════════════════════════════════
    // Pedido explícito del usuario (ronda 2026-08-26, ver docs/estructura/TUNEL.md):
    // el panel de arriba (getConfig/saveConfig/clearConfig) solo soportaba UNA config por
    // proveedor (global) o una por módulo — no una LISTA de dominios/tokens que el usuario
    // pueda ir agregando y asignar a distintos módulos con el tiempo. Modelo de datos
    // elegido: una única clave de registry por proveedor ("tunnel.saved.<provider>") con un
    // array JSON compacto (una sola línea, sin saltos internos — el registry es texto plano
    // línea por línea) de {"id","domain","token"} — preferido sobre un prefijo
    // "tunnel.unassigned.<n>.<provider>.token/domain" (la otra opción considerada) porque
    // evita tener que escanear el registry completo por claves con patrón numérico variable
    // para reconstruir la lista; leer/escribir la lista completa es una sola clave, un solo
    // parse JSON, consistente con cómo ya se usa JSONObject/JSONArray en el resto del
    // proyecto (ModuleCatalog.kt, ProjectsManager.kt, etc.) en vez de un formato ad-hoc nuevo.
    data class SavedTunnel(val id: String, val provider: String, val domain: String, val token: String) {
        val displayLabel: String get() = domain.ifBlank { "(sin dominio, solo token)" }
    }

    private fun savedKey(provider: String) = "tunnel.saved.$provider"

    fun listSaved(provider: String): List<SavedTunnel> {
        if (provider != "cloudflared" && provider != "ngrok") return emptyList()
        return parseSaved(provider, registryValues()[savedKey(provider)])
    }

    private fun serializeSaved(entries: List<SavedTunnel>): String {
        val arr = JSONArray()
        entries.forEach { e ->
            arr.put(JSONObject().apply {
                put("id", e.id)
                put("domain", e.domain)
                put("token", e.token)
            })
        }
        return arr.toString()
    }

    private fun parseSaved(provider: String, raw: String?): List<SavedTunnel> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                SavedTunnel(o.getString("id"), provider, o.optString("domain"), o.optString("token"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Agrega un dominio/token nuevo a la lista de guardados de [provider] (sin asignar a
     *  ningún módulo todavía). Requiere al menos uno de los dos no vacío. Todo el
     *  read-modify-write ocurre dentro de UN solo RegistryLock (ver writeRegistryFileLocked)
     *  para que dos "Agregar" concurrentes no se pisen entre sí. */
    fun addSaved(provider: String, domain: String, token: String): ActionResult {
        if (provider != "cloudflared" && provider != "ngrok") {
            return ActionResult(false, error = "Provider desconocido: $provider")
        }
        if (domain.isBlank() && token.isBlank()) {
            return ActionResult(false, error = "Necesitás al menos un dominio o un token")
        }
        val newId = System.currentTimeMillis().toString()
        RegistryLock.withLock(REGISTRY_LOCK_FILE) {
            writeRegistryFileLocked { current ->
                val list = parseSaved(provider, current[savedKey(provider)])
                current[savedKey(provider)] = serializeSaved(list + SavedTunnel(newId, provider, domain, token))
            }
        }
        return ActionResult(true, message = "Guardado")
    }

    /** Elimina UNA entrada puntual de la lista de guardados (no toca ninguna asignación de
     *  módulo ya hecha con esos datos — el módulo asignado conserva su propia copia en
     *  tunnel.<moduleId>.<provider>.*). */
    fun deleteSaved(provider: String, id: String): ActionResult {
        RegistryLock.withLock(REGISTRY_LOCK_FILE) {
            writeRegistryFileLocked { current ->
                val list = parseSaved(provider, current[savedKey(provider)])
                current[savedKey(provider)] = serializeSaved(list.filterNot { it.id == id })
            }
        }
        return ActionResult(true, message = "Eliminado")
    }

    /** Asigna una entrada guardada a un módulo real (debe estar en [KNOWN_MODULES]) — copia
     *  su domain/token a la config propia de ese módulo (tunnel.<moduleId>.<provider>.*),
     *  reemplazando lo que hubiera antes. El caller (UI) es responsable de confirmar con el
     *  usuario si el módulo ya tenía un dominio distinto asignado (ver
     *  TunnelFragment.confirmAndAssign()) — acá no hay confirmación interactiva posible. */
    fun assignSaved(provider: String, id: String, moduleId: String): ActionResult {
        if (moduleId !in KNOWN_MODULES) return ActionResult(false, error = "Módulo desconocido: $moduleId")
        val entry = listSaved(provider).firstOrNull { it.id == id }
            ?: return ActionResult(false, error = "Ese dominio/token guardado ya no existe")
        return saveConfig(provider, entry.token, entry.domain, moduleId)
    }

    // ═══════════════════════════════════════════════════════════
    //  VERIFICAR — consistencia real registry ↔ archivo ↔ proceso
    // ═══════════════════════════════════════════════════════════
    // Pedido explícito del usuario: chequeo real, no solo "el registry dice que está
    // configurado" (ver .claude/rules/empirical-verification-before-fix.md — mismo
    // principio: verificar la post-condición real, no confiar solo en que se escribió un
    // valor). Por cada módulo con config propia asignada: (a) confirma que el registry
    // sigue teniendo token/dominio, (b) para n8n, que ~/.cf_token existe Y coincide con el
    // token del registry (el bug real que originó todo este fix), (c) si el túnel de ese
    // módulo está corriendo, confirma con pgrep que el PROCESO cloudflared/ngrok sigue vivo
    // — no solo que la sesión tmux exista (una sesión tmux puede seguir "viva" con el
    // proceso adentro ya muerto, ej. si crasheó y tmux no cerró la ventana).
    data class VerifyResult(val moduleId: String, val provider: String, val ok: Boolean, val detail: String)

    fun verifyAll(): List<VerifyResult> {
        val results = mutableListOf<VerifyResult>()
        for (moduleId in KNOWN_MODULES.keys) {
            for (provider in listOf("cloudflared", "ngrok")) {
                val cfg = getConfig(provider, moduleId)
                if (!cfg.configured) continue
                results.add(verifyOne(moduleId, provider, cfg))
            }
        }
        return results
    }

    private fun verifyOne(moduleId: String, provider: String, cfg: ProviderConfig): VerifyResult {
        val problems = mutableListOf<String>()

        // (b) Espejo de archivo real — hoy solo aplica a n8n+cloudflared (ver
        // mirrorBespokeFiles()); para el resto de módulos el registry ES la fuente real que
        // lee TunnelManager.start(), así que no hay archivo bespoke que verificar.
        if (provider == "cloudflared" && moduleId == "n8n") {
            val tokenFile = File(HOME, ".cf_token")
            val fileContent = try { if (tokenFile.isFile) tokenFile.readText().trim() else null } catch (e: Exception) { null }
            if (cfg.token.isNotBlank() && fileContent != cfg.token) {
                problems.add("~/.cf_token no coincide con el registry (n8n.sh arrancaría con el token viejo o sin token)")
            }
        }

        // (c) Proceso real vivo, no solo la sesión tmux — chequea el moduleId propio de n8n
        // (sesiones bespoke) o la sesión genérica tunnel-<port> de TunnelManager.
        val port = if (moduleId == "remote") RemoteManager.sshSecurityConfig().port else KNOWN_MODULES[moduleId] ?: 0
        val ownRunning = moduleId == "n8n" && n8nOwnTunnelActive()
        val managedRunning = ManagerNativeUtils.tmuxHas(tunnelSession(port))
        if (ownRunning || managedRunning) {
            val processPattern = if (provider == "ngrok") "ngrok" else "cloudflared"
            if (!ManagerNativeUtils.pgrepF(processPattern)) {
                problems.add("La sesión tmux del túnel existe pero no hay proceso $processPattern corriendo de verdad")
            }
        }

        return if (problems.isEmpty()) {
            VerifyResult(moduleId, provider, true, "OK — registry, archivo y proceso consistentes")
        } else {
            VerifyResult(moduleId, provider, false, problems.joinToString("; "))
        }
    }
}

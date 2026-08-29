package com.termux.app.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.termux.shared.termux.TermuxConstants
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Lógica de `cmd_remote` de modulos/kairos_manager.py portada a Kotlin (ronda 2026-07-31).
 * RemoteFragment dependía de `python3 kairos_manager.py remote ...` para info/ssh-start/
 * ssh-stop/ssh-add-key/ssh-password/ssh-connections/cf-start/cf-stop/cf-set-token/cf-info —
 * mismo bug sistémico "Cannot run program python3" con evidencia real de dispositivo (el
 * tab Remote quedaba roto exactamente así, capturas de una ronda anterior). El toggle
 * running/stopped de este módulo YA usaba ModuleController.isRunning() (fix previo), esto
 * completa la migración del resto de las acciones.
 */
object RemoteManager {

    data class ActionResult(val ok: Boolean, val message: String = "", val error: String = "")

    data class RemoteInfo(
        val sshRunning: Boolean,
        val sshPort: Int,
        val cfRunning: Boolean,
        val cfHasToken: Boolean,
        val ip: String,
        val user: String,
        val connections: Int,
        val connectCmd: String,
        val scpCmd: String
    )

    data class SshConnections(val count: Int, val connections: List<String>, val daemonRunning: Boolean)
    data class CfInfo(val running: Boolean, val hasToken: Boolean, val user: String)

    /**
     * Estado REAL de seguridad de sshd — leído en vivo de `sshd_config` en cada llamada (no
     * cacheado) para que el panel de seguridad nunca muestre un valor desincronizado de lo que
     * el binario realmente aplicaría al reiniciar. Ver [sshSecurityConfig].
     *
     * `restartRequired` es true cuando sshd está corriendo pero el archivo se tocó después del
     * último arranque conocido (heurística simple: siempre true si sshd corre — sshd no
     * recarga sshd_config solo, necesita SIGHUP o reinicio) — el panel lo usa para avisar
     * "reiniciá SSH para aplicar" en vez de dejar que el usuario asuma que ya aplicó.
     */
    data class SshSecurityConfig(
        val port: Int,
        val passwordAuthEnabled: Boolean,
        val permitRootLogin: Boolean,
        val hasAuthorizedKeys: Boolean,
        val hasOwnKeypair: Boolean,
        val ownPublicKey: String?,
        val sshdRunning: Boolean
    )

    private data class CmdResult(val exitCode: Int, val stdout: String, val stderr: String)

    private val HOME = TermuxConstants.TERMUX_HOME_DIR_PATH
    private val CF_SSH_TOKEN_FILE = File(HOME, ".cf_ssh_token")
    private val CF_SSH_LOG_FILE = File(HOME, ".cf_ssh.log")
    private const val CF_SSH_TMUX_SESSION = "cf-ssh-tunnel"
    private const val SSH_PORT = 8022

    // Rango de puertos permitido para el campo "Puerto" del panel de seguridad. sshd de
    // Termux corre como usuario normal (sin root) — los puertos <1024 son privilegiados y
    // Bionic/Android igual los bloquea para procesos no-root, así que ofrecerlos rompería el
    // arranque de sshd en vez de solo ser una mala idea de seguridad.
    private const val SSH_MIN_PORT = 1024
    private const val SSH_MAX_PORT = 65535

    private val SSHD_CONFIG_FILE = File(TERMUX_PREFIX_PATH, "etc/ssh/sshd_config")
    private val SSH_DIR = File(HOME, ".ssh")
    private val OWN_KEY_PRIVATE = File(SSH_DIR, "id_ed25519")
    private val OWN_KEY_PUBLIC = File(SSH_DIR, "id_ed25519.pub")

    // ── Comandos shell ──────────────────────────────────────────────────

    // Delegado a ManagerNativeUtils.runShell() — antes reimplementaba el mismo
    // ProcessBuilder+applyTermuxEnv()+waitFor(timeout)+destroyForcibly() (consolidación
    // 2026-08-13, ver auditoría de código). Envuelve el Triple en CmdResult para no tocar
    // los call sites de este archivo que ya usan `.exitCode`/`.stdout`/`.stderr`. Alto
    // fan-out dentro de Remote: todo el módulo pasa por acá.
    private fun runCmd(cmd: String, timeoutSec: Long = 30): CmdResult {
        val (exitCode, stdout, stderr) = ManagerNativeUtils.runShell(cmd, timeoutSec)
        return CmdResult(exitCode, stdout, stderr)
    }

    // pgrepX/pgrepF/tmuxHas delegados a ManagerNativeUtils (consolidación 2026-08-13, ver
    // auditoría de código) — mismas funciones ya reimplementadas acá vía runCmd()/pgrep
    // por bash, ahora vía ProcessBuilder directo (sin shell de por medio, sin necesidad de
    // escapar el patrón).
    private fun pgrepX(name: String): Boolean = ManagerNativeUtils.pgrepX(name)
    private fun pgrepF(pattern: String): Boolean = ManagerNativeUtils.pgrepF(pattern)
    private fun tmuxHas(session: String): Boolean = ManagerNativeUtils.tmuxHas(session)
    private fun whoami(): String = runCmd("whoami", 5).stdout

    // Mismo truco que el Python: "conectar" un socket UDP a 8.8.8.8:80 no manda
    // ningún paquete, solo obliga al SO a elegir la IP local de salida real (no
    // 127.0.0.1) — con fallback a ifconfig si no hay red.
    //
    // Expuesta como getLocalIp() (2026-08-16, pedido "mini terminal windows" con IP del
    // dispositivo para el módulo Ciberseguridad — ver CiberseguridadFragment.kt): antes era
    // `private fun getIp()`, solo usada acá dentro por info(). Se renombra a público en vez de
    // duplicar la misma lógica de socket UDP + fallback ifconfig en otro archivo — mismo
    // criterio DRY que el resto de este objeto (runCmd/pgrepX/pgrepF ya delegan a
    // ManagerNativeUtils en vez de reimplementar).
    fun getLocalIp(): String {
        return try {
            DatagramSocket().use { socket ->
                socket.connect(InetAddress.getByName("8.8.8.8"), 80)
                socket.localAddress?.hostAddress ?: "localhost"
            }
        } catch (e: Exception) {
            val out = runCmd("ifconfig 2>/dev/null | grep 'inet ' | grep -v '127.' | awk '{print $2}' | head -1", 5).stdout
            out.ifBlank { "localhost" }
        }
    }

    // ── SSH ──────────────────────────────────────────────────────────────

    fun info(): RemoteInfo {
        val ip = getLocalIp()
        val user = whoami()
        val connsRaw = runCmd("ps aux 2>/dev/null | grep 'sshd:' | grep -v grep | grep -v 'sshd -D' | wc -l", 5).stdout
        val connections = connsRaw.toIntOrNull() ?: 0
        // Bug real de duplicación de lógica (auditoría 2026-08-25, ver
        // docs/arquitectura/AUDITORIA_DUPLICACION_LOGICA_2026-08-25.md): tras la migración a
        // TunnelManager (commit 6c9f7bd), cfSetToken() BORRA CF_SSH_TOKEN_FILE al guardar el
        // token nuevo (ver más abajo) — así que este chequeo, sin actualizar, siempre reportaba
        // "sin token" después de que el usuario guardara uno con el flujo nuevo (mismo síntoma
        // ya visto hoy en el diálogo de n8n antes del fix). Fuente real ahora: TunnelManager.
        val hasToken = TunnelManager.getConfig("cloudflared", "remote").token.isNotBlank()
        val cfRunning = tmuxHas(CF_SSH_TMUX_SESSION) || pgrepF("cloudflared.*tunnel")
        return RemoteInfo(
            sshRunning = pgrepX("sshd"),
            sshPort = SSH_PORT,
            cfRunning = cfRunning,
            cfHasToken = hasToken,
            ip = ip,
            user = user,
            connections = connections,
            connectCmd = "ssh -p $SSH_PORT $user@$ip",
            scpCmd = "scp -P $SSH_PORT archivo.txt $user@$ip:~/"
        )
    }

    // sshStart()/sshStop() se eliminaron (2026-08-22): corrían exactamente el mismo script
    // que ModuleController.startModule("remote")/stopModule("remote") — dos caminos
    // paralelos para lo mismo, ver comentario en RemoteFragment.buildContent() (sshSwitch).
    // Iniciar/detener SSH ahora pasa solo por ModuleController.

    fun sshAddKey(rawKey: String): ActionResult {
        val publicKey = rawKey.trim()
        if (publicKey.isEmpty()) return ActionResult(false, error = "Falta clave")
        val validPrefixes = listOf("ssh-rsa ", "ssh-ed25519 ", "ssh-ecdsa ", "ecdsa-sha2-")
        if (validPrefixes.none { publicKey.startsWith(it) }) return ActionResult(false, error = "Formato inválido")

        val sshDir = File(HOME, ".ssh")
        sshDir.mkdirs()
        runCmd("chmod 700 '${sshDir.absolutePath}'", 5)

        val authorizedKeysFile = File(sshDir, "authorized_keys")
        if (authorizedKeysFile.exists() && authorizedKeysFile.readText().contains(publicKey)) {
            return ActionResult(true, message = "Clave ya existe")
        }
        return try {
            authorizedKeysFile.appendText(publicKey + "\n")
            runCmd("chmod 600 '${authorizedKeysFile.absolutePath}'", 5)
            ActionResult(true, message = "Clave agregada")
        } catch (e: Exception) {
            ActionResult(false, error = e.message ?: "no se pudo escribir authorized_keys")
        }
    }

    // La contraseña se manda por el stdin del proceso "passwd" en vez de interpolarla
    // en un string de shell (mismo patrón anti-injection que N8nFragment.writeCfToken()
    // usa para el token de Cloudflare) — así una contraseña con comillas/`$`/backticks
    // no puede romper ni inyectar comandos, a diferencia del shlex.quote() del Python
    // original que igual construía el comando a mano con `echo -e ... | passwd`.
    fun sshSetPassword(password: String): ActionResult {
        if (password.isEmpty()) return ActionResult(false, error = "Falta contraseña")
        return try {
            val pb = ProcessBuilder("$TERMUX_PREFIX_PATH/bin/passwd")
            pb.applyTermuxEnv()
            pb.redirectErrorStream(true)
            val process = pb.start()
            process.outputStream.use { it.write("$password\n$password\n".toByteArray()) }
            val output = process.inputStream.bufferedReader().readText()
            val finished = process.waitFor(10, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return ActionResult(false, error = "timeout")
            }
            val exitOk = process.exitValue() == 0
            if (exitOk || output.lowercase().contains("changed")) {
                ActionResult(true, message = "Contraseña cambiada")
            } else {
                ActionResult(false, error = output.ifBlank { "Error" })
            }
        } catch (e: Exception) {
            ActionResult(false, error = e.message ?: "error desconocido")
        }
    }

    fun sshConnections(): SshConnections {
        val raw = runCmd("ps aux 2>/dev/null | grep 'sshd:' | grep -v grep | grep -v 'sshd -D'", 5).stdout
        val lines = raw.lines().map { it.trim() }.filter { it.isNotEmpty() }
        return SshConnections(lines.size, lines, pgrepX("sshd"))
    }

    // Huella (fingerprint) de cada clave de host generada por `ssh-keygen -A` durante la
    // instalación (ver PASO 3 de modulos/ssh.sh) — sirve para que el usuario la compare
    // contra el warning "authenticity of host ... can't be established" que muestra su
    // cliente SSH en el primer connect, antes de aceptarlo a ciegas. Solo lee/ejecuta
    // ssh-keygen -lf sobre archivos *_key.pub ya existentes — no genera ni modifica claves.
    fun sshHostKeyFingerprints(): List<String> {
        val sshEtcDir = File(TERMUX_PREFIX_PATH, "etc/ssh")
        val keyFiles = sshEtcDir.listFiles { file ->
            file.name.startsWith("ssh_host_") && file.name.endsWith("_key.pub")
        } ?: return emptyList()
        return keyFiles.sortedBy { it.name }.mapNotNull { file ->
            val result = runCmd("ssh-keygen -lf '${file.absolutePath}'", 5)
            result.stdout.trim().ifBlank { null }
        }
    }

    // ── Panel de seguridad SSH (pedido explícito del usuario 2026-08-19, ver
    //    docs/humano/humano172.md/humano173.md: "la exposición SSH como VPS no debe tener clave,
    //    usuario o algo? podemos crear un panel para ello?") ──────────────────────────────
    //
    // Confirmado contra sshd_config real (ver PASO 3 de modulos/ssh.sh): sshd de Termux usa
    // PAM/la contraseña real del usuario del sistema (la que setea `passwd`), NO un mecanismo
    // propio — sshSetPassword() (arriba) ya llamaba a `passwd` real, no había que cambiar eso.
    // Lo que faltaba era: (a) togglear PasswordAuthentication/PermitRootLogin en vivo, (b)
    // gestionar un par de claves PROPIO del dispositivo (para usarlo como cliente SSH hacia
    // otros servidores — no confundir con authorized_keys, que es al revés: claves de
    // clientes externos que SÍ pueden entrar a este dispositivo), y (c) exponer el puerto real.

    /**
     * Lee el estado REAL de seguridad de sshd — parseando `sshd_config` línea por línea en
     * cada llamada (nunca cacheado, ver docstring de [SshSecurityConfig]: un valor cacheado
     * puede desincronizarse si el usuario edita el archivo a mano en terminal).
     */
    fun sshSecurityConfig(): SshSecurityConfig {
        val lines = if (SSHD_CONFIG_FILE.isFile) {
            try { SSHD_CONFIG_FILE.readLines() } catch (_: Exception) { emptyList() }
        } else emptyList()

        fun findValue(key: String): String? {
            for (rawLine in lines) {
                val trimmed = rawLine.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
                if (!trimmed.startsWith(key, ignoreCase = true)) continue
                val rest = trimmed.substring(key.length)
                if (rest.isEmpty() || rest[0].isWhitespace()) return rest.trim()
            }
            return null
        }

        val port = findValue("Port")?.toIntOrNull() ?: SSH_PORT
        // Default real de sshd cuando la directiva no está presente: "yes" (mismo default
        // documentado por OpenSSH) — el archivo que escribe ssh.sh siempre la deja explícita,
        // pero un sshd_config editado a mano podría no tenerla.
        val passwordAuth = findValue("PasswordAuthentication")?.equals("yes", ignoreCase = true) ?: true
        // Default seguro del propio ssh.sh ("PermitRootLogin no") — si la línea no está,
        // se asume el default más restrictivo, no el permisivo.
        val permitRoot = findValue("PermitRootLogin")?.equals("yes", ignoreCase = true) ?: false

        val authorizedKeysFile = File(SSH_DIR, "authorized_keys")
        val hasAuthorizedKeys = try {
            authorizedKeysFile.isFile && authorizedKeysFile.readText().isNotBlank()
        } catch (_: Exception) {
            false
        }
        val hasOwnKeypair = OWN_KEY_PRIVATE.isFile && OWN_KEY_PUBLIC.isFile
        val ownPublicKey = if (hasOwnKeypair) {
            try { OWN_KEY_PUBLIC.readText().trim().ifBlank { null } } catch (_: Exception) { null }
        } else null

        return SshSecurityConfig(
            port = port,
            passwordAuthEnabled = passwordAuth,
            permitRootLogin = permitRoot,
            hasAuthorizedKeys = hasAuthorizedKeys,
            hasOwnKeypair = hasOwnKeypair,
            ownPublicKey = ownPublicKey,
            sshdRunning = pgrepX("sshd")
        )
    }

    // Escribe/reemplaza una directiva "Key value" en sshd_config — mismo algoritmo que
    // ManagerNativeUtils.upsertKeyValueLine() pero para el formato "espacio", no "Key=Value"
    // (sshd_config no usa '='). No se reusa upsertKeyValueLine() para no forzarle un formato
    // que no es el suyo.
    private fun upsertSshdConfigDirective(key: String, value: String): ActionResult {
        if (!SSHD_CONFIG_FILE.isFile) {
            return ActionResult(false, error = "sshd_config no existe — instalá/reinstalá el módulo Remote primero")
        }
        return try {
            val lines = SSHD_CONFIG_FILE.readLines().toMutableList()
            val idx = lines.indexOfFirst { rawLine ->
                val trimmed = rawLine.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    false
                } else if (!trimmed.startsWith(key, ignoreCase = true)) {
                    false
                } else {
                    val rest = trimmed.substring(key.length)
                    rest.isEmpty() || rest[0].isWhitespace()
                }
            }
            val newLine = "$key $value"
            if (idx >= 0) lines[idx] = newLine else lines.add(newLine)
            SSHD_CONFIG_FILE.writeText(lines.joinToString("\n") + "\n")
            val restartMessage = if (pgrepX("sshd")) {
                if (restartSshDaemon()) " — SSH reiniciado, ya está activo" else " — no se pudo reiniciar SSH solo, reinicialo a mano"
            } else {
                " — se aplicará la próxima vez que inicies SSH"
            }
            ActionResult(true, message = "Configuración actualizada$restartMessage")
        } catch (e: Exception) {
            ActionResult(false, error = e.message ?: "no se pudo escribir sshd_config")
        }
    }

    // sshd no relee su config solo (necesita SIGHUP o reinicio) — si estaba corriendo, se
    // reinicia para que el cambio de seguridad aplique de inmediato en vez de dejar al
    // usuario pensando que ya está activo cuando en realidad sigue con la config vieja en
    // memoria hasta el próximo reinicio manual.
    private fun restartSshDaemon(): Boolean {
        runCmd("pkill sshd", 10)
        Thread.sleep(500)
        runCmd("sshd", 10)
        return pgrepX("sshd")
    }

    /** Toggle real de `PasswordAuthentication` — desactivarlo exige que ya exista al menos
     *  una clave en authorized_keys, si no el usuario se bloquea a sí mismo sin forma de
     *  entrar (ni contraseña ni clave aceptada). */
    fun sshSetPasswordAuth(enabled: Boolean): ActionResult {
        if (!enabled) {
            val authorizedKeysFile = File(SSH_DIR, "authorized_keys")
            val hasKeys = try {
                authorizedKeysFile.isFile && authorizedKeysFile.readText().isNotBlank()
            } catch (_: Exception) {
                false
            }
            if (!hasKeys) {
                return ActionResult(
                    false,
                    error = "Agregá primero al menos una clave pública en \"Agregar clave pública\" — si no, te quedás sin forma de entrar"
                )
            }
        }
        return upsertSshdConfigDirective("PasswordAuthentication", if (enabled) "yes" else "no")
    }

    /** Toggle real de `PermitRootLogin` — default seguro es "no" (ver ssh.sh PASO 3); activarlo
     *  requiere que el caller ya haya mostrado una advertencia explícita al usuario, este
     *  método no la impone porque no tiene acceso a la UI. */
    fun sshSetPermitRootLogin(enabled: Boolean): ActionResult =
        upsertSshdConfigDirective("PermitRootLogin", if (enabled) "yes" else "no")

    /** Cambia el puerto de sshd — valida rango 1024-65535 (ver [SSH_MIN_PORT]/[SSH_MAX_PORT]:
     *  sshd de Termux corre sin root, los puertos privilegiados <1024 fallarían al bindear). */
    fun sshSetPort(port: Int): ActionResult {
        if (port < SSH_MIN_PORT || port > SSH_MAX_PORT) {
            return ActionResult(false, error = "Puerto fuera de rango ($SSH_MIN_PORT-$SSH_MAX_PORT)")
        }
        return upsertSshdConfigDirective("Port", port.toString())
    }

    /**
     * Genera el par de claves PROPIO del dispositivo (`~/.ssh/id_ed25519[.pub]`) si todavía no
     * existe — para usar este dispositivo como CLIENTE SSH (conectarse a otro servidor
     * agregando la pública acá generada al `authorized_keys` de ESE OTRO servidor), o para
     * copiar/compartir la pública. No toca `authorized_keys` local — eso sigue siendo
     * exclusivamente [sshAddKey] (clave de un cliente externo que se quiere dejar entrar A este
     * dispositivo, dirección opuesta).
     */
    fun sshGenerateOwnKeypair(): ActionResult {
        if (OWN_KEY_PRIVATE.isFile && OWN_KEY_PUBLIC.isFile) {
            return ActionResult(true, message = "Ya existe un par de claves propio")
        }
        SSH_DIR.mkdirs()
        runCmd("chmod 700 '${SSH_DIR.absolutePath}'", 5)
        val result = runCmd(
            "ssh-keygen -t ed25519 -f '${OWN_KEY_PRIVATE.absolutePath}' -N '' -q",
            15
        )
        return if (OWN_KEY_PRIVATE.isFile && OWN_KEY_PUBLIC.isFile) {
            runCmd("chmod 600 '${OWN_KEY_PRIVATE.absolutePath}'", 5)
            ActionResult(true, message = "Par de claves generado")
        } else {
            ActionResult(false, error = result.stderr.ifBlank { "ssh-keygen falló" })
        }
    }

    // ── Cloudflare (túnel SSH dedicado — no confundir con TunnelManager, que es el
    //    mecanismo genérico nuevo del tab Túnel) ─────────────────────────────────

    // Puente a Tunnel (ronda 2026-08-25, pedido explícito del usuario — mismo patrón que
    // N8nFragment.kt): el token de Cloudflare de este túnel bespoke de SSH pasa a guardarse
    // vía TunnelManager con id "remote", en vez del archivo propio ~/.cf_ssh_token. SSH es
    // TCP crudo — no hay campo de dominio real acá (Cloudflare no expone túneles TCP con un
    // dominio custom del mismo modo que HTTP), así que solo se migra/guarda el token.
    private fun migrateLegacyCfSshTokenIfNeeded() {
        if (TunnelManager.getConfig("cloudflared", "remote").token.isNotBlank()) return
        if (!CF_SSH_TOKEN_FILE.isFile || CF_SSH_TOKEN_FILE.length() == 0L) return
        try {
            val legacy = CF_SSH_TOKEN_FILE.readText().trim()
            if (legacy.isNotEmpty()) TunnelManager.saveConfig("cloudflared", legacy, "", "remote")
        } catch (_: Exception) { }
    }

    fun cfStart(): ActionResult {
        migrateLegacyCfSshTokenIfNeeded()
        val token = TunnelManager.getConfig("cloudflared", "remote").token
        if (token.isBlank()) return ActionResult(false, error = "Sin token")
        if (!pgrepX("sshd")) runCmd("sshd", 5)
        runCmd("tmux kill-session -t $CF_SSH_TMUX_SESSION 2>/dev/null", 5)
        // shellQuote() acá — el Python original interpolaba el token sin escapar en este
        // punto (bug real, aunque cmd_tunnel sí lo hacía para el mismo tipo de valor);
        // se cierra la misma brecha de shell injection de forma consistente.
        runCmd(
            "tmux new-session -d -s $CF_SSH_TMUX_SESSION \"cloudflared tunnel run --token ${shellQuote(token)} 2>&1 | tee '${CF_SSH_LOG_FILE.absolutePath}'\"",
            10
        )
        Thread.sleep(3000)
        return if (tmuxHas(CF_SSH_TMUX_SESSION)) ActionResult(true, message = "Tunnel iniciado") else ActionResult(false, error = "Falló")
    }

    fun cfStop(): ActionResult {
        runCmd("tmux kill-session -t $CF_SSH_TMUX_SESSION 2>/dev/null", 5)
        runCmd("pkill -f 'cloudflared.*tunnel' 2>/dev/null", 5)
        return ActionResult(true, message = "Tunnel detenido")
    }

    fun cfSetToken(token: String): ActionResult {
        if (token.isEmpty()) return ActionResult(false, error = "Falta token")
        val result = TunnelManager.saveConfig("cloudflared", token, "", "remote")
        // Best-effort: borra el archivo legacy para que no quede una fuente de verdad vieja
        // compitiendo con el registry — no crítico si falla.
        try { CF_SSH_TOKEN_FILE.delete() } catch (_: Exception) { }
        return if (result.ok) ActionResult(true, message = "Token guardado")
        else ActionResult(false, error = "No se pudo guardar el token")
    }

    fun cfInfo(): CfInfo {
        migrateLegacyCfSshTokenIfNeeded()
        val hasToken = TunnelManager.getConfig("cloudflared", "remote").token.isNotBlank()
        return CfInfo(running = tmuxHas(CF_SSH_TMUX_SESSION), hasToken = hasToken, user = whoami())
    }

    // ── Subnet scan (descubrimiento de servidores en la LAN) ─────────────
    // Portado 2026-08-14 del algoritmo real de la referencia whispercode-dev
    // (MobileBridgePlugin.kt, subnetHosts/runScan/probeHost) a Kotlin puro, sin
    // dependencias nuevas. El módulo Remote se conecta a servidores OpenCode/agentes por IP
    // y hasta ahora el usuario la escribía a mano — este scan la descubre solo.

    /** Puerto TCP donde escucha el servidor OpenCode/agente remoto que el scan busca. */
    const val REMOTE_SCAN_PORT = 4096

    /** Servidor remoto detectado por [scanSubnet]. */
    data class RemoteServer(val host: String, val port: Int, val url: String)

    // Generación del scan para cancelación por stale (mismo patrón que la referencia):
    // cada scan incrementa el contador y cada worker del pool se frena apenas detecta que
    // su generación ya no es la vigente.
    @Volatile
    private var scanGeneration = 0

    private fun isScanStale(gen: Int): Boolean = gen != scanGeneration

    // Prefijo por defecto para el fallback por NetworkInterface (la red doméstica típica
    // es /24; ConnectivityManager, cuando está disponible, da el real).
    private const val DEFAULT_PREFIX = 24

    private data class WifiNet(val ip: String, val prefix: Int)

    // IP v4 local + prefijo, igual que wifiAddress() de la referencia: primero vía
    // ConnectivityManager (activeNetwork + capabilities TRANSPORT_WIFI + linkAddresses),
    // con fallback a iterar NetworkInterface filtrando interfaces wlan*.
    private fun wifiAddress(context: Context): WifiNet? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val network = cm?.activeNetwork
        if (cm != null && network != null) {
            val caps = cm.getNetworkCapabilities(network)
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                val linkAddr = cm.getLinkProperties(network)?.linkAddresses?.firstOrNull {
                    it.address is Inet4Address
                }
                if (linkAddr != null) {
                    return WifiNet(linkAddr.address.hostAddress, linkAddr.prefixLength)
                }
            }
        }
        return try {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { it.name.startsWith("wlan") || it.name.startsWith("wifi") }
                .flatMap { it.inetAddresses.asSequence() }
                .filter { !it.isLoopbackAddress && it is Inet4Address }
                .map { WifiNet(it.hostAddress, DEFAULT_PREFIX) }
                .firstOrNull()
        } catch (_: Exception) {
            null
        }
    }

    /** true si hay una red WiFi activa — para que el Fragment muestre "Conectate a WiFi" antes de escanear. */
    fun isOnWifi(context: Context): Boolean = wifiAddress(context) != null

    private fun ipToInt(ip: String): Int? {
        val parts = ip.split(".")
        if (parts.size != 4) return null
        val bytes = parts.map { it.toIntOrNull() ?: return null }
        if (bytes.any { it !in 0..255 }) return null
        return (bytes[0] shl 24) or (bytes[1] shl 16) or (bytes[2] shl 8) or bytes[3]
    }

    private fun intToIp(value: Int): String =
        "${(value ushr 24) and 0xFF}.${(value ushr 16) and 0xFF}.${(value ushr 8) and 0xFF}.${value and 0xFF}"

    // Lista de hosts candidatos de la subred — cap /20 (misma decisión que la referencia:
    // no tardar en redes grandes), priorizando los del mismo /24 que el dispositivo.
    private fun subnetHosts(ip: String, prefixLength: Int): List<String> {
        val local = ipToInt(ip) ?: return emptyList()
        val prefix = prefixLength.coerceIn(20, 30)
        val mask = if (prefix == 0) 0 else (-1 shl (32 - prefix))
        val network = local and mask
        val broadcast = network or mask.inv()
        val primary = mutableListOf<String>()
        val secondary = mutableListOf<String>()
        var host = network + 1
        while (host < broadcast) {
            val h = intToIp(host)
            if ((host and 0xFFFFFF) == (local and 0xFFFFFF)) primary.add(h) else secondary.add(h)
            host++
        }
        return primary + secondary
    }

    // GET /global/health con fallback a /health, 2 intentos por URL, timeouts 1200ms,
    // acepta 200-299 — mismo health check dual de la referencia.
    private fun healthCheck(host: String): Boolean {
        val urls = listOf(
            "http://$host:$REMOTE_SCAN_PORT/global/health",
            "http://$host:$REMOTE_SCAN_PORT/health"
        )
        for (url in urls) {
            repeat(2) {
                var conn: HttpURLConnection? = null
                try {
                    conn = URL(url).openConnection() as HttpURLConnection
                    conn.connectTimeout = 1200
                    conn.readTimeout = 1200
                    conn.requestMethod = "GET"
                    val code = conn.responseCode
                    if (code in 200..299) return true
                } catch (_: Exception) {
                    // Reintenta (o pasa a la URL de fallback).
                } finally {
                    conn?.disconnect()
                }
            }
        }
        return false
    }

    // Sonda de un host: TCP connect al puerto del servidor con timeout corto; si conecta,
    // valida con el health check HTTP antes de reportar (la referencia solo reporta cuando
    // el health check pasa). Cierra el socket siempre en finally.
    private fun probeServer(host: String): RemoteServer? {
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(host, REMOTE_SCAN_PORT), 500)
            if (!healthCheck(host)) return null
            return RemoteServer(host, REMOTE_SCAN_PORT, "http://$host:$REMOTE_SCAN_PORT")
        } catch (_: Exception) {
            return null
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    /**
     * Escanea la LAN local en busca de servidores OpenCode/agentes escuchando en
     * [REMOTE_SCAN_PORT]. El escaneo corre en los hilos del caller (el Fragment la llama
     * desde un Thread — NUNCA red en el main thread). [onProgress] se invoca (desde un
     * worker del pool) con cada servidor encontrado para mostrarlo en vivo; devuelve la
     * lista completa. Pool de tamaño limitado (máx. 32) según la cantidad de hosts.
     *
     * `context` se usa solo para resolver la IP/prefix de la red WiFi (ConnectivityManager).
     */
    fun scanSubnet(context: Context, onProgress: (RemoteServer) -> Unit): List<RemoteServer> {
        val gen = ++scanGeneration
        val wifi = wifiAddress(context) ?: return emptyList()
        val hosts = subnetHosts(wifi.ip, wifi.prefix)
        if (hosts.isEmpty()) return emptyList()

        val poolSize = minOf(32, hosts.size.coerceAtLeast(1))
        val pool = Executors.newFixedThreadPool(poolSize)
        try {
            val futures = hosts.map { host ->
                pool.submit(Callable<RemoteServer?> {
                    if (isScanStale(gen)) {
                        null
                    } else {
                        val server = probeServer(host)
                        if (server != null) onProgress(server)
                        server
                    }
                })
            }
            return futures.mapNotNull { future ->
                runCatching { future.get() }.getOrNull()
            }
        } finally {
            pool.shutdown()
        }
    }

    // ── Cliente SSH (pestaña "Receptor" — Kairos conectándose a OTROS servidores) ──────────
    // Terminología corregida 2026-08-27 (ver docs/humano256.md, bug real confirmado: la app
    // tenía Receptor/Emisor exactamente invertidos respecto al modelo del usuario) — "Emisor"
    // es Kairos EMITIENDO acceso (servidor, alguien más lo controla); "Receptor" es Kairos
    // RECIBIENDO control de otros (cliente, nosotros controlamos otro dispositivo/VPS).
    // Pedido explícito del usuario (ronda 2026-08-26): "ssh es para controlar y ser
    // controlado" — todo lo de arriba (info/seguridad/cloudflared) es Kairos siendo
    // controlado (EMISOR); esta sección es la dirección opuesta, Kairos como cliente que
    // guarda conexiones a servidores remotos y las abre en una terminal real. Mismo patrón
    // de registry que TunnelManager.SavedTunnel/listSaved/addSaved/deleteSaved (una única
    // clave "ssh_client.saved" con un array JSON compacto, un solo RegistryLock por
    // operación) — ver comentario de TunnelManager.kt línea ~531 para el razonamiento
    // completo de por qué ese formato en vez de claves numeradas sueltas.
    data class SshClientConnection(
        val id: String,
        val alias: String,
        val host: String,
        val port: Int,
        val user: String,
        val useOwnKey: Boolean,
        // Referencia (id) a una clave privada IMPORTADA y guardada (ver sección "Claves
        // privadas importadas" más abajo) — null cuando la conexión usa la clave propia del
        // dispositivo (useOwnKey) o ninguna clave (contraseña interactiva). Mutuamente
        // excluyente con useOwnKey en la práctica (buildClientConnectCommand() prioriza
        // importedKeyId si está seteado), pero no se valida acá para no romper conexiones ya
        // guardadas si algún día coexisten.
        val importedKeyId: String? = null,
        val lastConnectedAt: Long = 0L
    )

    private val REGISTRY_LOCK_FILE get() = ManagerNativeUtils.registryLockFile
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
        } catch (_: Exception) { emptyMap() }
    }

    private fun writeRegistryFileLocked(mutate: (MutableMap<String, String>) -> Unit) {
        val file = registryFile()
        val current = registryValues().toMutableMap()
        mutate(current)
        file.writeText(current.entries.joinToString("\n") { "${it.key}=${it.value}" } + "\n")
    }

    private const val SSH_CLIENT_SAVED_KEY = "ssh_client.saved"

    private fun serializeClientConnections(entries: List<SshClientConnection>): String {
        val arr = JSONArray()
        entries.forEach { e ->
            arr.put(JSONObject().apply {
                put("id", e.id)
                put("alias", e.alias)
                put("host", e.host)
                put("port", e.port)
                put("user", e.user)
                put("useOwnKey", e.useOwnKey)
                if (e.importedKeyId != null) put("importedKeyId", e.importedKeyId)
                put("lastConnectedAt", e.lastConnectedAt)
            })
        }
        return arr.toString()
    }

    private fun parseClientConnections(raw: String?): List<SshClientConnection> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                SshClientConnection(
                    id = o.getString("id"),
                    alias = o.optString("alias").ifBlank { o.optString("host") },
                    host = o.getString("host"),
                    port = o.optInt("port", SSH_PORT),
                    user = o.optString("user").ifBlank { "root" },
                    useOwnKey = o.optBoolean("useOwnKey", true),
                    importedKeyId = o.optString("importedKeyId", "").ifBlank { null },
                    lastConnectedAt = o.optLong("lastConnectedAt", 0L)
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Lista de servidores remotos guardados para conectarse DESDE este dispositivo. */
    fun listClientConnections(): List<SshClientConnection> =
        parseClientConnections(registryValues()[SSH_CLIENT_SAVED_KEY])

    /**
     * Agrega una conexión guardada nueva. [port] no default a 22 en la UI a propósito —
     * pedido explícito del usuario ("el tipo de puerto si no quieren el 22") — acá solo se
     * valida que sea un puerto TCP válido, sin restringir a >=1024 (a diferencia de
     * [sshSetPort], que sí restringe: ese es el puerto del sshd LOCAL sin root; este es el
     * puerto de un servidor AJENO, que puede correr como root y usar el 22 real).
     */
    fun addClientConnection(
        alias: String,
        host: String,
        port: Int,
        user: String,
        useOwnKey: Boolean,
        importedKeyId: String? = null
    ): ActionResult {
        if (host.isBlank()) return ActionResult(false, error = "Falta el host/IP")
        if (port !in 1..65535) return ActionResult(false, error = "Puerto fuera de rango (1-65535)")
        if (user.isBlank()) return ActionResult(false, error = "Falta el usuario")
        val effectiveAlias = alias.ifBlank { host }
        val newId = System.currentTimeMillis().toString()
        RegistryLock.withLock(REGISTRY_LOCK_FILE) {
            writeRegistryFileLocked { current ->
                val list = parseClientConnections(current[SSH_CLIENT_SAVED_KEY])
                current[SSH_CLIENT_SAVED_KEY] = serializeClientConnections(
                    list + SshClientConnection(newId, effectiveAlias, host, port, user, useOwnKey, importedKeyId)
                )
            }
        }
        return ActionResult(true, message = "Conexión guardada")
    }

    fun deleteClientConnection(id: String): ActionResult {
        RegistryLock.withLock(REGISTRY_LOCK_FILE) {
            writeRegistryFileLocked { current ->
                val list = parseClientConnections(current[SSH_CLIENT_SAVED_KEY])
                current[SSH_CLIENT_SAVED_KEY] = serializeClientConnections(list.filterNot { it.id == id })
            }
        }
        return ActionResult(true, message = "Eliminada")
    }

    /** Actualiza `lastConnectedAt` a "ahora" — se llama tras un probe TCP exitoso (ver
     *  [probeClientReachable]), nunca tras solo abrir la terminal (eso no confirma nada). */
    private fun touchClientConnection(id: String) {
        RegistryLock.withLock(REGISTRY_LOCK_FILE) {
            writeRegistryFileLocked { current ->
                val list = parseClientConnections(current[SSH_CLIENT_SAVED_KEY])
                current[SSH_CLIENT_SAVED_KEY] = serializeClientConnections(
                    list.map { if (it.id == id) it.copy(lastConnectedAt = System.currentTimeMillis()) else it }
                )
            }
        }
    }

    /**
     * Monitoreo barato (pedido explícito del usuario, "ssh [...] monitorear etc"): un simple
     * TCP connect al host:puerto guardado, con timeout corto — NO valida credenciales, solo
     * si el servidor está escuchando ahí. Si responde, marca `lastConnectedAt` como
     * "conectividad confirmada ahora" antes de abrir la terminal real.
     */
    fun probeClientReachable(host: String, port: Int): Boolean {
        val socket = Socket()
        return try {
            socket.connect(InetSocketAddress(host, port), 1500)
            true
        } catch (_: Exception) {
            false
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    /** Sonda + marca la conexión como recién confirmada si respondió — llamar SIEMPRE antes
     *  de [buildClientConnectCommand] para que "última vez conectado" refleje intentos reales. */
    fun probeAndTouchClientConnection(conn: SshClientConnection): Boolean {
        val reachable = probeClientReachable(conn.host, conn.port)
        if (reachable) touchClientConnection(conn.id)
        return reachable
    }

    /**
     * Comando real "ssh -p <puerto> [-i clave] usuario@host" para lanzar en una sesión de
     * terminal (ver BaseModuleFragment.launchTerminalCommand()) — NUNCA reimplementa el
     * manejo de terminal, solo arma el string de comando. Si [SshClientConnection.useOwnKey]
     * es true pero la clave propia todavía no fue generada ([sshGenerateOwnKeypair]), cae a
     * conexión sin -i (el cliente ssh real pedirá contraseña interactivamente).
     */
    fun buildClientConnectCommand(conn: SshClientConnection): String {
        val keyFlag = when {
            conn.importedKeyId != null && importedKeyFile(conn.importedKeyId).isFile ->
                "-i '${importedKeyFile(conn.importedKeyId).absolutePath}' "
            conn.useOwnKey && OWN_KEY_PRIVATE.isFile -> "-i '${OWN_KEY_PRIVATE.absolutePath}' "
            else -> ""
        }
        return "ssh -p ${conn.port} $keyFlag${shellQuote(conn.user)}@${shellQuote(conn.host)}"
    }

    // ── Claves privadas importadas (Receptor — "pegar/importar una clave privada de un
    //    tercero", pedido explícito del owner del proyecto en esta ronda) ────────────────────
    //
    // GARANTÍA DURA DE SEGURIDAD (no negociable): una vez que una clave privada se GUARDA acá
    // (importPrivateKey), NINGUNA función de este objeto vuelve a exponer su contenido — no
    // existe un "getImportedKeyContent()" ni equivalente. Lo único que se expone hacia la UI es
    // metadata pública derivada (alias elegido por el usuario, fingerprint de
    // `ssh-keygen -lf`, que es de un solo sentido — no permite reconstruir la clave). Las
    // únicas acciones disponibles sobre una clave guardada son: usarla (por id, en
    // buildClientConnectCommand), reemplazarla (replaceImportedKey — sobreescribe el archivo,
    // nunca lo lee de vuelta para mostrarlo) o borrarla (deleteImportedKey). RemoteFragment.kt
    // no debe agregar ningún botón "Ver clave" sobre estas entradas — si se necesita depurar
    // algo, el archivo sigue siendo legible por el propio usuario vía terminal (mismo nivel de
    // acceso que cualquier archivo de $HOME), pero la UI de Kairos en sí nunca lo imprime.
    data class ImportedSshKey(val id: String, val alias: String, val fingerprint: String)
    data class ImportKeyResult(val ok: Boolean, val keyId: String = "", val fingerprint: String = "", val error: String = "")

    private val IMPORTED_KEYS_DIR = File(SSH_DIR, "imported")
    private const val IMPORTED_KEYS_META_KEY = "ssh_client.imported_keys"

    private fun importedKeyFile(id: String) = File(IMPORTED_KEYS_DIR, id)

    private fun looksLikePrivateKey(text: String): Boolean =
        text.trim().startsWith("-----BEGIN") && text.contains("PRIVATE KEY")

    private fun serializeImportedKeys(entries: List<ImportedSshKey>): String {
        val arr = JSONArray()
        entries.forEach { e ->
            arr.put(JSONObject().apply {
                put("id", e.id)
                put("alias", e.alias)
                put("fingerprint", e.fingerprint)
            })
        }
        return arr.toString()
    }

    private fun parseImportedKeys(raw: String?): List<ImportedSshKey> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                ImportedSshKey(
                    id = o.getString("id"),
                    alias = o.optString("alias").ifBlank { "Clave" },
                    fingerprint = o.optString("fingerprint")
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Metadata pública (alias + fingerprint) de las claves privadas guardadas — nunca el
     *  contenido, ver garantía de seguridad arriba. */
    fun listImportedKeys(): List<ImportedSshKey> = parseImportedKeys(registryValues()[IMPORTED_KEYS_META_KEY])

    /**
     * Guarda una clave privada pegada por el usuario bajo `~/.ssh/imported/<id>` (permisos
     * 600, directorio 700 — mismo criterio que [OWN_KEY_PRIVATE]). Requerimiento duro del
     * owner del proyecto: después de esta llamada el contenido de [keyText] no vuelve a
     * exponerse por ningún camino de este objeto — solo su fingerprint (derivado, no
     * reversible) queda visible.
     */
    fun importPrivateKey(alias: String, keyText: String): ImportKeyResult {
        val trimmed = keyText.trim()
        if (trimmed.isEmpty()) return ImportKeyResult(false, error = "Falta la clave")
        if (!looksLikePrivateKey(trimmed)) {
            return ImportKeyResult(false, error = "No parece una clave privada (falta '-----BEGIN ... PRIVATE KEY-----')")
        }
        IMPORTED_KEYS_DIR.mkdirs()
        runCmd("chmod 700 '${IMPORTED_KEYS_DIR.absolutePath}'", 5)
        val id = "ik_" + System.currentTimeMillis()
        val file = importedKeyFile(id)
        return try {
            file.writeText(trimmed + "\n")
            runCmd("chmod 600 '${file.absolutePath}'", 5)
            val fingerprint = runCmd("ssh-keygen -lf '${file.absolutePath}'", 5).stdout.trim().ifBlank { "?" }
            val effectiveAlias = alias.ifBlank { "Clave ${listImportedKeys().size + 1}" }
            RegistryLock.withLock(REGISTRY_LOCK_FILE) {
                writeRegistryFileLocked { current ->
                    val list = parseImportedKeys(current[IMPORTED_KEYS_META_KEY])
                    current[IMPORTED_KEYS_META_KEY] = serializeImportedKeys(list + ImportedSshKey(id, effectiveAlias, fingerprint))
                }
            }
            ImportKeyResult(true, keyId = id, fingerprint = fingerprint)
        } catch (e: Exception) {
            try { file.delete() } catch (_: Exception) {}
            ImportKeyResult(false, error = e.message ?: "no se pudo guardar la clave")
        }
    }

    /** Reemplaza el CONTENIDO de una clave ya guardada (mismo id/archivo/alias) — así las
     *  conexiones que ya la referencian por [SshClientConnection.importedKeyId] siguen
     *  apuntando al mismo lugar sin tener que editarlas. Nunca lee el contenido viejo antes de
     *  sobreescribir — no hace falta, ni se debe, mostrarlo. */
    fun replaceImportedKey(id: String, newKeyText: String): ImportKeyResult {
        val trimmed = newKeyText.trim()
        if (trimmed.isEmpty()) return ImportKeyResult(false, error = "Falta la clave")
        if (!looksLikePrivateKey(trimmed)) return ImportKeyResult(false, error = "No parece una clave privada")
        if (listImportedKeys().none { it.id == id }) return ImportKeyResult(false, error = "Clave no encontrada")
        val file = importedKeyFile(id)
        return try {
            file.writeText(trimmed + "\n")
            runCmd("chmod 600 '${file.absolutePath}'", 5)
            val fingerprint = runCmd("ssh-keygen -lf '${file.absolutePath}'", 5).stdout.trim().ifBlank { "?" }
            RegistryLock.withLock(REGISTRY_LOCK_FILE) {
                writeRegistryFileLocked { current ->
                    val list = parseImportedKeys(current[IMPORTED_KEYS_META_KEY])
                    current[IMPORTED_KEYS_META_KEY] = serializeImportedKeys(
                        list.map { if (it.id == id) it.copy(fingerprint = fingerprint) else it }
                    )
                }
            }
            ImportKeyResult(true, keyId = id, fingerprint = fingerprint)
        } catch (e: Exception) {
            ImportKeyResult(false, error = e.message ?: "no se pudo reemplazar la clave")
        }
    }

    /** Borra el archivo + la metadata. Cualquier conexión guardada que la referenciaba queda
     *  sin método de auth por clave (cae a contraseña interactiva) en vez de apuntar en
     *  silencio a un archivo que ya no existe. */
    fun deleteImportedKey(id: String): ActionResult {
        try { importedKeyFile(id).delete() } catch (_: Exception) {}
        RegistryLock.withLock(REGISTRY_LOCK_FILE) {
            writeRegistryFileLocked { current ->
                val list = parseImportedKeys(current[IMPORTED_KEYS_META_KEY])
                current[IMPORTED_KEYS_META_KEY] = serializeImportedKeys(list.filterNot { it.id == id })
                val conns = parseClientConnections(current[SSH_CLIENT_SAVED_KEY])
                current[SSH_CLIENT_SAVED_KEY] = serializeClientConnections(
                    conns.map { if (it.importedKeyId == id) it.copy(importedKeyId = null) else it }
                )
            }
        }
        return ActionResult(true, message = "Clave eliminada")
    }

    /**
     * Conexión "solo esta sesión" (Receptor — el owner pidió poder elegir entre guardar la
     * clave persistentemente o usarla una sola vez sin que quede en el dispositivo). La clave
     * NUNCA se agrega a [listImportedKeys] ni al registry — se escribe en un archivo privado
     * transitorio (permisos 600, bajo `~/.ssh/.ephemeral/`, no accesible a otras apps sin
     * root) el tiempo justo para que el proceso `ssh` real (lanzado en una TerminalSession) lo
     * abra, y se borra sola desde un hilo de fondo poco después.
     *
     * Nota de diseño (judgment call, documentado — Kairos no controla el instante exacto en
     * que el subproceso `ssh` dentro de la terminal abre el archivo, así que no se puede
     * borrar de forma síncrona apenas se lanza el comando sin arriesgar una carrera): el
     * borrado se agenda con un margen de 30s en vez de ser inmediato. Sigue siendo
     * sustancialmente distinto de "guardado" — no aparece en ninguna lista, no es reutilizable,
     * y desaparece del disco por sí sola sin intervención del usuario.
     */
    fun buildEphemeralConnectCommand(user: String, host: String, port: Int, keyText: String): Pair<String, ActionResult> {
        val trimmed = keyText.trim()
        if (host.isBlank() || user.isBlank()) return "" to ActionResult(false, error = "Falta host o usuario")
        if (!looksLikePrivateKey(trimmed)) return "" to ActionResult(false, error = "No parece una clave privada")
        val dir = File(HOME, ".ssh/.ephemeral")
        dir.mkdirs()
        runCmd("chmod 700 '${dir.absolutePath}'", 5)
        val file = File(dir, "session_" + System.currentTimeMillis())
        return try {
            file.writeText(trimmed + "\n")
            runCmd("chmod 600 '${file.absolutePath}'", 5)
            val cmd = "ssh -p $port -i '${file.absolutePath}' ${shellQuote(user)}@${shellQuote(host)}"
            Thread {
                try { Thread.sleep(30_000) } catch (_: InterruptedException) {}
                try { file.delete() } catch (_: Exception) {}
            }.start()
            cmd to ActionResult(true)
        } catch (e: Exception) {
            try { file.delete() } catch (_: Exception) {}
            "" to ActionResult(false, error = e.message ?: "no se pudo preparar la clave")
        }
    }
}

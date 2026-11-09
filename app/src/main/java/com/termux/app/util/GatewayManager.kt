package com.termux.app.util

import android.content.Context
import com.termux.shared.termux.TermuxConstants
import java.io.File
import java.security.SecureRandom

/** Desde dónde se puede llegar al gateway. Fuera de loopback no hay TLS: el tráfico viaja en claro (usar túnel). */
enum class GatewayAccess(val id: String, val bindHost: String) {
    LOCALHOST("localhost", "127.0.0.1"),
    LAN("lan", "0.0.0.0");

    companion object {
        fun fromId(id: String?) = entries.firstOrNull { it.id == id } ?: LOCALHOST
    }
}

/**
 * Estado en disco del gateway: secretos y modo de acceso. Separado de [GatewayManager] para probarlo con una carpeta
 * temporal. Los secretos (administración de la app y puente app↔gateway) se crean una vez, quedan con permisos solo para el
 * dueño y **nunca se devuelven a la interfaz** (regla `kairos-secrets-never-revealed.md`): solo los usa el código que
 * arranca el proceso y habla con él.
 */
class GatewayFiles(val dir: File) {
    private val random = SecureRandom()

    init {
        dir.mkdirs()
        restrict(dir)
    }

    val adminSecret: String get() = secret("admin.secret")
    val appSecret: String get() = secret("app.secret")

    var access: GatewayAccess
        get() = GatewayAccess.fromId(File(dir, "access.mode").takeIf { it.isFile }?.readText()?.trim())
        set(value) { File(dir, "access.mode").writeText(value.id) }

    private fun secret(name: String): String {
        val file = File(dir, name)
        if (file.isFile) file.readText().trim().takeIf { it.isNotEmpty() }?.let { return it }
        val bytes = ByteArray(32).also(random::nextBytes)
        val value = bytes.joinToString("") { "%02x".format(it) }
        file.writeText(value)
        restrict(file)
        return value
    }

    private fun restrict(f: File) {
        f.setReadable(false, false); f.setWritable(false, false); f.setExecutable(false, false)
        f.setReadable(true, true); f.setWritable(true, true)
        if (f.isDirectory) f.setExecutable(true, true)
    }
}

data class GatewayStartResult(val ok: Boolean, val message: String = "")

/**
 * Arranca, vigila y detiene `libkairos_gateway.so` (Go, ver `native/gateway`). Todo es bloqueante: llamar fuera del hilo
 * principal. El gateway escucha en `127.0.0.1:8973` por defecto; el modo [GatewayAccess.LAN] lo abre a la red local.
 * Siempre se le habla (salud, administración) por loopback.
 */
object GatewayManager {
    const val PORT = 8973
    private const val LIB_NAME = "libkairos_gateway.so"
    private const val START_WAIT_MS = 6_000L

    private val home = TermuxConstants.TERMUX_HOME_DIR_PATH
    val files: GatewayFiles by lazy { GatewayFiles(File(home, ".kairos_gateway")) }
    @Volatile private var process: Process? = null

    fun adminClient() = GatewayAdminClient("http://127.0.0.1:$PORT", files.adminSecret)

    fun isHealthy(): Boolean = GatewayAdminClient("http://127.0.0.1:$PORT", "", 1500).health()

    @Synchronized
    fun start(context: Context): GatewayStartResult {
        val ops = AppModuleOps(context.applicationContext)
        val app = context.applicationContext
        val control = GatewayControl(
            SessionManager(),
            HomeFiles(java.io.File(com.termux.shared.termux.TermuxConstants.TERMUX_HOME_DIR_PATH), listOf(files.dir)),
            AppPrefsSettings(app),
            terminalCommandOf = { id -> com.termux.app.data.ModuleCatalog.load(app).firstOrNull { it.id == id }?.terminalCommand },
        )
        val screenJar = File(home, "${ScreenMirrorManager.JAR_SUBDIR}/${ScreenMirrorManager.JAR_NAME}")
        ScreenMirrorManager(PrivilegeRegistry.default(context), files.dir).ensureJarExtracted(app)
        // storage.* / ftp.*: mismo alcance que fs.* (HOME real de Termux, files.dir —
        // $HOME/.kairos_gateway — vedado) para el FTP; storage.* además ve volúmenes FUERA del HOME
        // (microSD/USB) cuando Android los expone, ver GatewayStorageOps.kt.
        val homeFile = File(home)
        val ftpCredsDir = File(files.dir, "ftp")
        val bridgePort = GatewayAppBridge.start(
            files.appSecret, ops, privilege = PrivilegeRegistry.default(context), control = control,
            metrics = AppMetricsOps(app), cloud = AppCloudOps(app), minipc = AppMinipcOps(), agent = AppAgentOps(app),
            aiChat = AppAiChatOps(),
            storage = AppStorageOps(app), ftp = AppFtpOps(homeFile, listOf(files.dir), ftpCredsDir), ide = AppIdeOps(app),
            ollama = AppOllamaOps(), openclaw = AppOpenClawOps(), tunnel = AppTunnelOps(app), hermes = AppHermesOps(), remote = AppRemoteOps(app), addons = AppAddonsOps(), restic = AppResticOps(), qemuRam = AppQemuRamOps(app), lanScan = AppLanScanOps(app), stacks = { GatewayStacksOps.projects(app) }, automations = AppAutomationsOps(app),
            // Mismo chequeo de SOLO LECTURA que ConfigFragment.hasNoAccountsConfigured ( meta 1)
            // -- dpm set-device-owner solo acepta el pedido si el dispositivo no tiene ninguna cuenta
            // configurada; esto nunca activa Dhizuku, solo informa si HOY sería posible.
            dhizukuEligible = { try { android.accounts.AccountManager.get(app).accounts.isEmpty() } catch (_: Exception) { false } },
            screenDir = files.dir, screenJarReady = { screenJar.isFile && screenJar.length() > 0 },
        )
        if (bridgePort == 0) return GatewayStartResult(false, "no se pudo abrir el puente interno")
        // Un gateway que ya corría solo se adopta si su puente apunta al puerto de ESTE arranque. Si apuntara a otro
        // (el puerto habitual estaba ocupado), podría estar enviando el secreto del puente a una app impostora: se reinicia.
        val bridgeFile = File(files.dir, "bridge.port")
        if (isHealthy()) {
            if (bridgeFile.takeIf { it.isFile }?.readText()?.trim() == bridgePort.toString()) {
                GatewayConfirmationWatcher.start(context)
                return GatewayStartResult(true, "ya estaba en marcha")
            }
            adminClient().shutdown()
            val gone = System.currentTimeMillis() + 3_000
            while (isHealthy() && System.currentTimeMillis() < gone) Thread.sleep(100)
            if (isHealthy()) return GatewayStartResult(false, "hay otro servicio en el puerto $PORT y no es el gateway de Kairos")
        }
        bridgeFile.writeText(bridgePort.toString())

        val binary = File(context.applicationInfo.nativeLibraryDir, LIB_NAME)
        if (!binary.isFile) return GatewayStartResult(false, "esta compilación no incluye el gateway ($LIB_NAME)")

        val logFile = File(home, "kairos_logs/gateway.log").also { it.parentFile?.mkdirs() }
        val pb = ProcessBuilder(binary.absolutePath, "--dir", files.dir.absolutePath, "--listen", "${files.access.bindHost}:$PORT")
        pb.environment().apply {
            put("HOME", home)
            put("KAIROS_GATEWAY_DIR", files.dir.absolutePath)
            put("KAIROS_GATEWAY_ADMIN_SECRET", files.adminSecret)
            put("KAIROS_GATEWAY_APP_URL", "http://127.0.0.1:$bridgePort/internal/action")
            put("KAIROS_GATEWAY_APP_SECRET", files.appSecret)
        }
        pb.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
        process = try { pb.start() } catch (e: Exception) {
            return GatewayStartResult(false, e.message ?: "no se pudo lanzar el gateway")
        }
        val deadline = System.currentTimeMillis() + START_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            if (isHealthy()) { GatewayConfirmationWatcher.start(context); return GatewayStartResult(true) }
            if (process?.isAlive == false) break
            Thread.sleep(150)
        }
        return GatewayStartResult(false, "el gateway no respondió; revisa kairos_logs/gateway.log")
    }

    /** Pide el apagado ordenado; si no responde, termina el proceso hijo. */
    @Synchronized
    fun stop() {
        GatewayConfirmationWatcher.stop()
        adminClient().shutdown()
        val deadline = System.currentTimeMillis() + 3_000
        while (isHealthy() && System.currentTimeMillis() < deadline) Thread.sleep(100)
        process?.destroy()
        process = null
        GatewayAppBridge.stop()
    }

    /** Aplica un cambio de acceso: se reinicia para volver a enlazar el puerto en la dirección nueva. */
    fun restartWithAccess(context: Context, access: GatewayAccess): GatewayStartResult {
        val wasRunning = isHealthy()
        files.access = access
        if (!wasRunning) return GatewayStartResult(true)
        stop()
        return start(context)
    }
}

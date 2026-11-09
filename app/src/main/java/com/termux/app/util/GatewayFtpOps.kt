package com.termux.app.util

import java.io.File

/** Estado real de [FtpServer] para el gateway/panel. [username] se puede mostrar (no es secreto); la contraseña
 *  nunca tiene un getter — ver [FtpCredentials]. */
data class FtpStatusInfo(val running: Boolean, val port: Int, val configured: Boolean, val username: String?)

data class FtpOpResult(val ok: Boolean, val message: String = "")

/** `ftp.status` / `ftp.start` / `ftp.stop` / `ftp.set_credentials` — ver [AppFtpOps]. */
interface GatewayFtpOps {
    fun status(): FtpStatusInfo
    fun start(): FtpOpResult
    fun stop(): FtpOpResult
    fun setCredentials(username: String, password: String): FtpOpResult
}

/**
 * Implementación real: delega en el objeto [FtpServer] (proceso único de la app, igual que [NubeServer]).
 * [home]/[deniedDirs] definen el mismo alcance que ya usa `fs.*` ([HomeFiles]) — nunca más que eso, ver
 * `GatewayManager.start()` para de dónde vienen esos dos valores. [credsDir] vive dentro de la carpeta propia
 * del gateway (`$HOME/.kairos_gateway/ftp`), ya excluida de `fs.*`/FTP por estar en [deniedDirs].
 */
class AppFtpOps(
    private val home: File,
    private val deniedDirs: List<File>,
    credsDir: File,
) : GatewayFtpOps {
    private val creds = FtpCredentials(credsDir)

    override fun status() = FtpStatusInfo(FtpServer.isRunning, FtpServer.port, creds.configured, creds.username)

    override fun start(): FtpOpResult {
        if (FtpServer.isRunning) return FtpOpResult(true, "ya estaba en marcha")
        if (!creds.configured) return FtpOpResult(false, "configurá usuario y contraseña primero (ftp.set_credentials)")
        val port = FtpServer.start(home, deniedDirs, creds)
        return if (port != 0) FtpOpResult(true) else FtpOpResult(false, "no se pudo abrir el puerto FTP")
    }

    override fun stop(): FtpOpResult {
        FtpServer.stop()
        return FtpOpResult(true)
    }

    override fun setCredentials(username: String, password: String): FtpOpResult {
        return if (creds.set(username, password)) FtpOpResult(true)
        else FtpOpResult(false, "usuario o contraseña inválidos (usuario: letras/números/._- ; contraseña: ${FtpCredentials.MIN_PASSWORD_LEN}-${FtpCredentials.MAX_PASSWORD_LEN} caracteres)")
    }
}

package com.termux.app.util

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Servidor FTP mínimo propio — pedido del usuario (``): "una opción de FTP" para acceder al
 * almacenamiento de Kairos/Termux desde un cliente externo (PC, otro teléfono). No existía ningún proyecto FTP
 * en `referencia/`/`ver/` (ver auditoría de esta ronda) ni ninguna librería ya vendida en el proyecto — mismo
 * criterio que [NubeServer] (servidor HTTP a mano sobre `ServerSocket`, sin dependencias nuevas,
 * `.claude/rules/kairos-port-or-build-own.md`): esto es RFC 959 mínimo, a mano, en modo pasivo únicamente.
 *
 * Alcance: SIEMPRE el HOME de Termux (mismo árbol que `fs.*`/[HomeFiles] del gateway, nunca más) — reusa
 * [HomeFiles.resolve] para la misma barrera contra `..`/símlinks/nombres de secretos, así que no hay un segundo
 * mecanismo de seguridad de rutas que mantener sincronizado con el de `fs.*`.
 *
 * Lo que SÍ falta a propósito en esta primera versión (documentado, no fingido): modo activo (`PORT`), reanudar
 * transferencias (`REST`), listados `MLSD`. Lo que sí soporta de punta a punta: login, navegación (`CWD`/`PWD`/
 * `CDUP`), listar (`LIST`/`NLST`), descargar (`RETR`), subir (`STOR`), borrar (`DELE`), crear carpeta (`MKD`),
 * tamaño (`SIZE`) — en modo pasivo (`PASV`), que es lo que usan por defecto los clientes modernos.
 *
 * Sin TLS (FTP en claro) — igual que el modo [GatewayAccess.LAN] del gateway, pensado para red local o cable
 * USB, nunca para exponerlo a internet.
 */
object FtpServer {
    const val DEFAULT_PORT = 2121
    internal const val DATA_PORT_RANGE_START = 50100
    internal const val DATA_PORT_RANGE_END = 50199

    /** Tope de subida por archivo — a diferencia de `fs.write` (pensado para archivos chicos de config, 2 MB),
     *  FTP existe específicamente para mover archivos grandes; el tope real es generoso pero sigue existiendo
     *  para no dejar que un cliente llene el almacenamiento del teléfono sin límite (mismo espíritu que
     *  `NubeServer.MAX_UPLOAD_BYTES`, pedido explícito del usuario en esa ronda: "ajustable, pero DEBE existir
     *  un límite"). */
    internal const val MAX_UPLOAD_BYTES = 4L * 1024 * 1024 * 1024

    internal const val CONTROL_SOCKET_TIMEOUT_MS = 5 * 60_000
    private const val MAX_CLIENTS = 4
    private const val BACKLOG = 4

    @Volatile private var server: ServerSocket? = null
    @Volatile private var pool: ExecutorService? = null

    val isRunning: Boolean get() = server?.isClosed == false
    val port: Int get() = server?.localPort ?: 0

    /** Arranca en [preferred] (o uno libre si está ocupado) y acepta conexiones hasta [stop]. [credentials] debe
     *  tener usuario/contraseña ya configurados — ver [FtpCredentials.configured]. Devuelve el puerto real o 0. */
    @Synchronized
    fun start(home: File, deniedDirs: List<File>, credentials: FtpCredentials, preferred: Int = DEFAULT_PORT): Int {
        if (isRunning) return port
        if (!credentials.configured) return 0
        val socket = try {
            ServerSocket(preferred, BACKLOG)
        } catch (_: Exception) {
            try { ServerSocket(0, BACKLOG) } catch (_: Exception) { return 0 }
        }
        server = socket
        val files = HomeFiles(home, deniedDirs)
        val execPool = Executors.newFixedThreadPool(MAX_CLIENTS)
        pool = execPool
        Thread {
            while (!socket.isClosed) {
                val client = try { socket.accept() } catch (_: Exception) { break }
                execPool.submit {
                    try { FtpSession(client, files, credentials).serve() } catch (_: Exception) { }
                    finally { try { client.close() } catch (_: Exception) { } }
                }
            }
        }.apply { isDaemon = true; name = "ftp-server"; start() }
        return socket.localPort
    }

    @Synchronized
    fun stop() {
        try { server?.close() } catch (_: Exception) { }
        server = null
        pool?.shutdownNow()
        pool = null
    }
}

/** Una sesión de control FTP con un cliente — un hilo del pool de [FtpServer] por conexión. */
private class FtpSession(
    private val control: Socket,
    private val files: HomeFiles,
    private val creds: FtpCredentials,
) {
    private var authenticated = false
    private var pendingUser: String? = null
    /** Directorio actual, SIEMPRE relativo a la raíz del HOME (sin `/` inicial ni final). */
    private var cwd = ""
    private var pasvSocket: ServerSocket? = null

    /**
     * Revisión de seguridad real: nada frenaba antes a alguien probando contraseñas una tras otra en la misma
     * sesión — ni demora ni límite, solo el tiempo que tarda el hash (rápido igual en cualquier CPU moderna
     * corriendo muchos intentos en paralelo). Cada intento fallido suma una demora creciente (1 s, 2 s, 3 s...)
     * antes de responder, y a partir de [MAX_FAILED_ATTEMPTS] la conexión de control se corta — fuerza a
     * reconectar, lo que ya es mucho más lento que seguir probando en la misma sesión.
     */
    private var failedAttempts = 0

    private val commandsAllowedBeforeLogin = setOf("USER", "PASS", "QUIT", "NOOP", "SYST", "FEAT")

    fun serve() {
        control.soTimeout = FtpServer.CONTROL_SOCKET_TIMEOUT_MS
        val out = control.getOutputStream()
        send(out, "220 Kairos FTP listo")
        val reader = control.getInputStream().bufferedReader(Charsets.UTF_8)
        while (true) {
            val line = try { reader.readLine() } catch (_: Exception) { null } ?: break
            if (!handle(line.trim(), out)) break
        }
        closePasv()
    }

    private fun send(out: OutputStream, msg: String) {
        try { out.write((msg + "\r\n").toByteArray(Charsets.UTF_8)); out.flush() } catch (_: Exception) { }
    }

    /** Devuelve false para cerrar la conexión de control después de responder. */
    private fun handle(line: String, out: OutputStream): Boolean {
        if (line.isEmpty()) return true
        val sp = line.indexOf(' ')
        val cmd = (if (sp < 0) line else line.substring(0, sp)).uppercase(Locale.US)
        val arg = if (sp < 0) "" else line.substring(sp + 1).trim()
        if (!authenticated && cmd !in commandsAllowedBeforeLogin) {
            send(out, "530 iniciá sesión primero"); return true
        }
        return when (cmd) {
            "USER" -> { pendingUser = arg; send(out, "331 contraseña, por favor"); true }
            "PASS" -> {
                val user = pendingUser
                authenticated = user != null && creds.matches(user, arg)
                if (authenticated) {
                    send(out, "230 autenticado")
                    true
                } else {
                    failedAttempts++
                    try { Thread.sleep((failedAttempts * 1000L).coerceAtMost(5000L)) } catch (_: InterruptedException) { }
                    send(out, "530 usuario o contraseña incorrectos")
                    failedAttempts < MAX_FAILED_ATTEMPTS
                }
            }
            "SYST" -> { send(out, "215 UNIX Type: L8"); true }
            "FEAT" -> { send(out, "211 sin extensiones"); true }
            "OPTS" -> { send(out, "200 ok"); true }
            "TYPE" -> { send(out, "200 tipo configurado"); true }
            "PWD" -> { send(out, "257 \"/$cwd\" es el directorio actual"); true }
            "CWD" -> { changeDir(arg, out); true }
            "CDUP" -> { changeDir("..", out); true }
            "PASV" -> { enterPassive(out); true }
            "LIST" -> { list(arg, out, namesOnly = false); true }
            "NLST" -> { list(arg, out, namesOnly = true); true }
            "RETR" -> { retrieve(arg, out); true }
            "STOR" -> { store(arg, out); true }
            "SIZE" -> { size(arg, out); true }
            "DELE" -> { delete(arg, out); true }
            "MKD" -> { makeDir(arg, out); true }
            "NOOP" -> { send(out, "200 ok"); true }
            "QUIT" -> { send(out, "221 adiós"); false }
            else -> { send(out, "502 comando no soportado"); true }
        }
    }

    /** [rel] puede ser absoluta (empieza con `/`, relativa al HOME) o relativa al [cwd] actual. */
    private fun joined(rel: String): String = when {
        rel.startsWith("/") -> rel.trimStart('/')
        cwd.isEmpty() -> rel
        else -> "$cwd/$rel"
    }

    private fun changeDir(arg: String, out: OutputStream) {
        val target = if (arg == "..") cwd.substringBeforeLast('/', "") else joined(arg).trim('/')
        val dir = files.resolve(target)
        if (dir == null || !dir.isDirectory) { send(out, "550 no existe o no es una carpeta"); return }
        cwd = target
        send(out, "250 carpeta cambiada a /$cwd")
    }

    private fun closePasv() {
        pasvSocket?.let { try { it.close() } catch (_: Exception) { } }
        pasvSocket = null
    }

    private fun enterPassive(out: OutputStream) {
        closePasv()
        var opened: ServerSocket? = null
        // Atado a la misma dirección local que el canal de control (no todas las interfaces) — mismo espíritu
        // que la verificación de IP en acceptData(): el socket de datos no debe ser alcanzable por una ruta de
        // red distinta a la que ya está usando este cliente.
        for (candidate in FtpServer.DATA_PORT_RANGE_START..FtpServer.DATA_PORT_RANGE_END) {
            opened = try { ServerSocket(candidate, 1, control.localAddress) } catch (_: Exception) { null }
            if (opened != null) break
        }
        if (opened == null) { send(out, "425 no se pudo abrir el puerto de datos"); return }
        val ip = control.localAddress.address
        if (ip.size != 4) { try { opened.close() } catch (_: Exception) { }; send(out, "425 conexión IPv6 no soportada en modo pasivo"); return }
        pasvSocket = opened
        fun u(b: Byte) = b.toInt() and 0xFF
        val p1 = opened.localPort shr 8
        val p2 = opened.localPort and 0xFF
        send(out, "227 Entrando en modo pasivo (${u(ip[0])},${u(ip[1])},${u(ip[2])},${u(ip[3])},$p1,$p2)")
    }

    /**
     * Acepta la conexión de datos ya anunciada por [enterPassive]. `null` si algo falla (ya respondió el error).
     *
     * Revisión de seguridad real: el puerto de datos queda abierto y anunciado por `227`, en un rango fijo y
     * predecible (50100-50199) — sin esta comprobación, cualquier otro proceso del dispositivo (o de la misma red
     * local si el gateway está en modo LAN) podría conectarse a ese puerto ANTES que el cliente real y robar la
     * conexión de datos (leer lo que se iba a subir, o hacerse pasar por el servidor para una descarga). Se
     * descarta cualquier conexión cuya IP no coincida con la del cliente ya autenticado en el canal de control, y
     * se sigue esperando (dentro del mismo plazo) a que llegue la correcta — así una conexión ajena no alcanza
     * para tirar abajo la transferencia real con solo adelantarse.
     */
    private fun acceptData(out: OutputStream): Socket? {
        val listener = pasvSocket
        if (listener == null) { send(out, "425 usá PASV primero"); return null }
        pasvSocket = null
        val deadline = System.currentTimeMillis() + 15_000
        try {
            while (true) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) { send(out, "425 no se pudo abrir la conexión de datos"); return null }
                listener.soTimeout = remaining.toInt()
                val client = try { listener.accept() } catch (_: Exception) { null } ?: run {
                    send(out, "425 no se pudo abrir la conexión de datos"); return null
                }
                if (client.inetAddress == control.inetAddress) return client
                try { client.close() } catch (_: Exception) { }
                // Conexión de otra IP: se descarta en silencio (no se le manda ninguna respuesta FTP, porque no
                // forma parte de esta sesión) y se sigue esperando a la real, sin reiniciar el plazo completo.
            }
        } finally {
            try { listener.close() } catch (_: Exception) { }
        }
    }

    private fun list(arg: String, out: OutputStream, namesOnly: Boolean) {
        val target = (if (arg.isBlank()) cwd else joined(arg)).trim('/')
        val dir = files.resolve(target)
        if (dir == null || !dir.isDirectory) { send(out, "550 no existe o no es una carpeta"); return }
        val entries = (dir.listFiles() ?: arrayOf())
            .filter { files.resolve(if (target.isEmpty()) it.name else "$target/${it.name}") != null }
            .sortedBy { it.name.lowercase(Locale.US) }
        send(out, "150 abriendo conexión de datos")
        val data = acceptData(out) ?: return
        try {
            val w = data.getOutputStream()
            entries.forEach { f -> w.write(((if (namesOnly) f.name else unixLine(f)) + "\r\n").toByteArray(Charsets.UTF_8)) }
            w.flush()
        } catch (_: Exception) { } finally { try { data.close() } catch (_: Exception) { } }
        send(out, "226 listado terminado")
    }

    private fun unixLine(f: File): String {
        val perm = if (f.isDirectory) "drwxr-xr-x" else "-rw-r--r--"
        val size = if (f.isFile) f.length() else 0L
        val date = SimpleDateFormat("MMM dd HH:mm", Locale.US).format(Date(f.lastModified()))
        return "$perm 1 kairos kairos ${"%12d".format(size)} $date ${f.name}"
    }

    private fun retrieve(arg: String, out: OutputStream) {
        val f = files.resolve(joined(arg))
        if (f == null || !f.isFile) { send(out, "550 no existe"); return }
        send(out, "150 abriendo conexión de datos (${f.length()} bytes)")
        val data = acceptData(out) ?: return
        try {
            FileInputStream(f).use { input -> input.copyTo(data.getOutputStream()) }
        } catch (_: Exception) { send(out, "451 error leyendo el archivo"); return } finally {
            try { data.close() } catch (_: Exception) { }
        }
        send(out, "226 transferencia completa")
    }

    private fun store(arg: String, out: OutputStream) {
        val f = files.resolve(joined(arg))
        if (f == null || f.isDirectory) { send(out, "550 ruta inválida"); return }
        send(out, "150 abriendo conexión de datos")
        val data = acceptData(out) ?: return
        var tooBig = false
        try {
            f.parentFile?.mkdirs()
            FileOutputStream(f).use { dest ->
                val buf = ByteArray(64 * 1024)
                var total = 0L
                val input = data.getInputStream()
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > FtpServer.MAX_UPLOAD_BYTES) { tooBig = true; break }
                    dest.write(buf, 0, n)
                }
            }
        } catch (_: Exception) { send(out, "451 error escribiendo el archivo"); return } finally {
            try { data.close() } catch (_: Exception) { }
        }
        if (tooBig) { f.delete(); send(out, "552 archivo demasiado grande"); return }
        send(out, "226 transferencia completa")
    }

    private fun size(arg: String, out: OutputStream) {
        val f = files.resolve(joined(arg))
        if (f == null || !f.isFile) { send(out, "550 no existe"); return }
        send(out, "213 ${f.length()}")
    }

    private fun delete(arg: String, out: OutputStream) {
        val f = files.resolve(joined(arg))
        if (f == null || !f.isFile || !f.delete()) { send(out, "550 no se pudo borrar"); return }
        send(out, "250 borrado")
    }

    private fun makeDir(arg: String, out: OutputStream) {
        val f = files.resolve(joined(arg))
        if (f == null || (!f.isDirectory && !f.mkdirs())) { send(out, "550 no se pudo crear"); return }
        send(out, "257 carpeta creada")
    }

    private companion object {
        const val MAX_FAILED_ATTEMPTS = 3
    }
}

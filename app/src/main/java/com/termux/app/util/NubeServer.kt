package com.termux.app.util

import com.termux.shared.termux.TermuxConstants
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLConnection
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Servidor HTTP embebido mínimo — pedido explícito del usuario: "que actúe como una
 * nube de almacenamiento tipo Drive/Mediafire" accesible desde CUALQUIER navegador,
 * no solo desde la app. Android no trae `com.sun.net.httpserver` garantizado en todas
 * las versiones ni NanoHTTPD como dependencia del proyecto (CLAUDE.md: sin librerías
 * nuevas), así que esto es HTTP/1.1 a mano sobre ServerSocket/Socket — el patrón
 * estándar de "leer request-line + headers línea por línea, servir con líneas de texto
 * crudas". Sin keep-alive (cada respuesta cierra la conexión, `Connection: close`) para
 * mantener el parser simple; un pool chico de hilos limita cuántas conexiones
 * concurrentes se aceptan (evita que alguien lo tumbe abriendo sockets sin fin).
 *
 * Gateo por token: toda ruta exige `?token=...` con el valor persistido en
 * `$HOME/.nube_token` (mismo patrón que `.cf_token`/`.cf_ssh_token`) — sin esto, la
 * URL pública del túnel (ver TunnelManager) sería lectura/escritura de archivos sin
 * autenticación para cualquiera que la vea.
 */
object NubeServer {

    // 500MB — el tope que pide el usuario ("ajustable, pero DEBE existir un límite").
    private const val MAX_UPLOAD_BYTES = 500L * 1024 * 1024
    private const val MAX_CONCURRENT_CONNECTIONS = 8
    private const val SOCKET_TIMEOUT_MS = 30_000
    private const val DEFAULT_PORT = 8971

    private const val TEXT_PLAIN = "text/plain; charset=utf-8"
    private const val TEXT_HTML = "text/html; charset=utf-8"

    /** Carpeta acotada — la ÚNICA que este servidor puede leer/escribir. Vive en el
     *  $HOME real de Termux (no en filesDir de la app) para que el usuario también
     *  pueda llenarla a mano desde Termux/otra herramienta, tal como pidió. */
    val nubeRoot: File by lazy {
        File(TermuxConstants.TERMUX_HOME_DIR_PATH, "nube").apply { mkdirs() }
    }

    private val tokenFile: File by lazy { File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".nube_token") }

    /** Mismo patrón que `.cf_token`/`.cf_ssh_token`: se genera una sola vez (SecureRandom,
     *  24 bytes) y se persiste — así el link no cambia entre reinicios del servidor. */
    val token: String by lazy { loadOrCreateToken() }

    var port: Int = DEFAULT_PORT
        private set

    private var serverSocket: ServerSocket? = null
    private var executor: ExecutorService? = null
    private val activeConnections = AtomicInteger(0)

    val isRunning: Boolean get() = serverSocket?.isClosed == false

    private fun loadOrCreateToken(): String {
        try {
            if (tokenFile.exists()) {
                val existing = tokenFile.readText().trim()
                if (existing.isNotEmpty()) return existing
            }
        } catch (_: Exception) { }
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        val generated = bytes.joinToString("") { "%02x".format(it) }
        try { tokenFile.writeText(generated) } catch (_: Exception) { }
        return generated
    }

    @Synchronized
    fun start(preferredPort: Int = DEFAULT_PORT): Boolean {
        if (isRunning) return true
        nubeRoot.mkdirs()
        return try {
            val socket = ServerSocket()
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(preferredPort))
            serverSocket = socket
            port = preferredPort
            val pool = Executors.newFixedThreadPool(MAX_CONCURRENT_CONNECTIONS)
            executor = pool
            Thread { acceptLoop(socket, pool) }.apply { isDaemon = true; start() }
            true
        } catch (e: Exception) {
            serverSocket = null
            false
        }
    }

    @Synchronized
    fun stop() {
        try { serverSocket?.close() } catch (_: Exception) { }
        serverSocket = null
        executor?.shutdownNow()
        executor = null
    }

    private fun acceptLoop(socket: ServerSocket, pool: ExecutorService) {
        while (!socket.isClosed) {
            val client = try { socket.accept() } catch (_: Exception) { break }
            if (activeConnections.get() >= MAX_CONCURRENT_CONNECTIONS) {
                try { client.close() } catch (_: Exception) { }
                continue
            }
            pool.submit {
                activeConnections.incrementAndGet()
                try {
                    client.soTimeout = SOCKET_TIMEOUT_MS
                    handleClient(client)
                } catch (_: Exception) {
                    // Conexión cortada a mitad de request/upload — no debe tumbar el pool.
                } finally {
                    activeConnections.decrementAndGet()
                    try { client.close() } catch (_: Exception) { }
                }
            }
        }
    }

    // ── Dispatcher ───────────────────────────────────────────────────────

    private fun handleClient(client: Socket) {
        val input = client.getInputStream()
        val output = client.getOutputStream()

        val requestLine = readLine(input) ?: return
        val parts = requestLine.split(" ")
        if (parts.size < 2) { writeText(output, 400, TEXT_PLAIN, "400 Bad Request"); return }
        val method = parts[0]

        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0) headers[line.substring(0, idx).trim().lowercase(Locale.US)] = line.substring(idx + 1).trim()
        }

        val (path, query) = splitPathQuery(parts[1])
        val params = parseQuery(query)

        // Gateo por token — igual para GET y POST, sin excepciones (ver cabecera del archivo).
        // MessageDigest.isEqual() en vez de "!=" (comparación de String estándar, no
        // constant-time) — mitiga un timing attack teórico contra el token (auditoría
        // 2026-08-13); impacto práctico bajo (servidor local/túnel personal), pero trivial
        // de aplicar.
        val providedToken = params["token"].orEmpty()
        if (!MessageDigest.isEqual(providedToken.toByteArray(Charsets.UTF_8), token.toByteArray(Charsets.UTF_8))) {
            writeText(output, 403, TEXT_PLAIN, "403 Forbidden — token inválido o ausente")
            return
        }

        when {
            method == "GET" && path == "/" -> serveIndex(output, params["path"] ?: "")
            method == "GET" && path == "/download" -> serveDownload(output, params["path"] ?: "")
            method == "POST" && path == "/upload" -> handleUpload(output, input, headers, params["path"] ?: "")
            else -> writeText(output, 404, TEXT_PLAIN, "404 Not Found")
        }
    }

    // ── Validación de path traversal ────────────────────────────────────

    /**
     * Resuelve `relative` (viene de `?path=...`, texto arbitrario del cliente) contra
     * `nubeRoot` y exige que el resultado canónico siga viviendo DENTRO de `nubeRoot`.
     * Mismo principio que `pasteInto()` en FileManagerFragment.kt (comparar
     * canonicalPath, no solo concatenar Strings) — bloquea por ejemplo
     * `?path=../../../data/data/com.termux/files/home/.ssh` (canonicalPath resuelve
     * los ".." y el resultado ya no empieza con el canonicalPath de nubeRoot) o
     * `?path=/etc/passwd` (ruta absoluta: File(nubeRoot, "/etc/passwd") en Java
     * igual la trata relativa al primer argumento, pero por las dudas se valida
     * igual con canonicalPath en vez de confiar en eso).
     */
    private fun resolveSafePath(relative: String): File? {
        val target = if (relative.isBlank()) nubeRoot else File(nubeRoot, relative)
        return try {
            val canonicalTarget = target.canonicalFile
            val canonicalRoot = nubeRoot.canonicalFile
            if (canonicalTarget.path == canonicalRoot.path ||
                canonicalTarget.path.startsWith(canonicalRoot.path + File.separator)
            ) {
                canonicalTarget
            } else null
        } catch (_: Exception) {
            null
        }
    }

    private fun relPathOf(file: File): String {
        val root = nubeRoot.canonicalPath
        val full = try { file.canonicalPath } catch (_: Exception) { file.path }
        return full.removePrefix(root).trimStart(File.separatorChar)
    }

    // ── Rutas ────────────────────────────────────────────────────────────

    private fun serveIndex(output: OutputStream, relPath: String) {
        val dir = resolveSafePath(relPath)
        if (dir == null || !dir.isDirectory) {
            writeText(output, 400, TEXT_PLAIN, "400 Bad Request — ruta inválida o fuera de la carpeta nube")
            return
        }
        val entries = dir.listFiles()
            ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase(Locale.getDefault()) }))
            ?: emptyList()

        val rows = StringBuilder()
        if (dir.path != nubeRoot.path) {
            val parentRel = relPathOf(dir.parentFile ?: nubeRoot)
            rows.append("<tr><td>📁</td><td colspan=2><a href=\"/?path=${urlEncode(parentRel)}&token=$token\">.. (subir)</a></td></tr>")
        }
        for (f in entries) {
            val rel = relPathOf(f)
            if (f.isDirectory) {
                rows.append("<tr><td>📁</td><td colspan=2><a href=\"/?path=${urlEncode(rel)}&token=$token\">${escapeHtml(f.name)}</a></td></tr>")
            } else {
                rows.append(
                    "<tr><td>📄</td><td>${escapeHtml(f.name)}</td>" +
                        "<td>${ManagerNativeUtils.humanSize(f.length())} — " +
                        "<a href=\"/download?path=${urlEncode(rel)}&token=$token\">descargar</a></td></tr>"
                )
            }
        }
        val currentRel = relPathOf(dir)
        val html = buildIndexHtml(currentRel, rows.toString())
        writeText(output, 200, TEXT_HTML, html)
    }

    private fun buildIndexHtml(currentRel: String, rows: String): String = """
        <!doctype html><html><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <title>Nube — Kairos</title>
        <style>
          body{font-family:sans-serif;background:#050505;color:#e8e8e8;padding:16px;max-width:720px;margin:auto}
          h1{font-size:20px} table{width:100%;border-collapse:collapse}
          td{padding:8px 4px;border-bottom:1px solid #1f1f1f;font-size:14px}
          a{color:#22c55e;text-decoration:none} a:hover{text-decoration:underline}
          form{margin-top:20px;padding:14px;background:#0a0a0a;border:1px solid #1f1f1f;border-radius:10px}
          button{background:#22c55e;color:#050505;border:none;padding:8px 16px;border-radius:6px;font-weight:bold}
        </style>
        </head><body>
        <h1>☁ Nube</h1>
        <p style="color:#888">/${escapeHtml(currentRel)}</p>
        <table>$rows</table>
        <form method="POST" action="/upload?path=${urlEncode(currentRel)}&token=$token" enctype="multipart/form-data">
            <input type="file" name="file" required>
            <button type="submit">Subir archivo</button>
        </form>
        </body></html>
    """.trimIndent()

    private fun serveDownload(output: OutputStream, relPath: String) {
        val file = resolveSafePath(relPath)
        if (file == null || !file.isFile) {
            writeText(output, 404, TEXT_PLAIN, "404 Not Found")
            return
        }
        val contentType = URLConnection.guessContentTypeFromName(file.name) ?: "application/octet-stream"
        val safeName = file.name.replace("\"", "")
        val headers = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: $contentType\r\n" +
            "Content-Length: ${file.length()}\r\n" +
            "Content-Disposition: attachment; filename=\"$safeName\"\r\n" +
            "Connection: close\r\n\r\n"
        output.write(headers.toByteArray(Charsets.US_ASCII))
        FileInputStream(file).use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                output.write(buffer, 0, read)
            }
        }
        output.flush()
    }

    private fun handleUpload(output: OutputStream, input: InputStream, headers: Map<String, String>, targetPathParam: String) {
        val contentType = headers["content-type"] ?: ""
        val boundary = Regex("boundary=\"?([^\";]+)\"?").find(contentType)?.groupValues?.get(1)
        val contentLength = headers["content-length"]?.toLongOrNull()

        if (boundary == null || contentLength == null) {
            writeText(output, 400, TEXT_PLAIN, "400 Bad Request — falta boundary/Content-Length de multipart")
            return
        }
        if (contentLength > MAX_UPLOAD_BYTES) {
            writeText(output, 413, TEXT_PLAIN, "413 Payload Too Large — máximo ${MAX_UPLOAD_BYTES / 1024 / 1024}MB")
            return
        }
        val targetDir = resolveSafePath(targetPathParam)
        if (targetDir == null || !targetDir.isDirectory) {
            writeText(output, 400, TEXT_PLAIN, "400 Bad Request — carpeta destino inválida")
            return
        }

        val bounded = LimitedInputStream(input, contentLength)
        val fileName = readMultipartFileName(bounded)
        if (fileName == null) {
            writeText(output, 400, TEXT_PLAIN, "400 Bad Request — formulario multipart inválido")
            return
        }
        val destFile = File(targetDir, sanitizeFileName(fileName))
        // Defensa en profundidad: sanitizeFileName ya se queda con un único segmento sin
        // separadores, pero igual se revalida con canonicalPath (mismo criterio que
        // resolveSafePath) antes de escribir un solo byte a disco.
        val destCanonical = try { destFile.canonicalFile } catch (_: Exception) { null }
        if (destCanonical == null || !destCanonical.path.startsWith(targetDir.canonicalPath + File.separator)) {
            writeText(output, 400, TEXT_PLAIN, "400 Bad Request — nombre de archivo inválido")
            return
        }

        val ok = streamPartToFile(bounded, "\r\n--$boundary", destCanonical)
        if (ok) {
            writeText(output, 200, TEXT_HTML, buildIndexHtmlAfterUpload(targetPathParam))
        } else {
            writeText(output, 500, TEXT_PLAIN, "500 — no se pudo guardar el archivo")
        }
    }

    private fun buildIndexHtmlAfterUpload(targetPathParam: String): String =
        "<!doctype html><meta charset=\"utf-8\"><meta http-equiv=\"refresh\" content=\"0; url=/?path=${urlEncode(targetPathParam)}&token=$token\">" +
            "Subida correcta — redirigiendo…"

    // ── Multipart (caso simple: un único <input type=file>, sin soportar el estándar completo) ──

    /** Descarta la línea de apertura `--boundary` y lee las cabeceras de la parte hasta
     *  la línea en blanco, extrayendo `filename="..."` de Content-Disposition. */
    private fun readMultipartFileName(input: InputStream): String? {
        readLine(input) ?: return null
        var fileName: String? = null
        while (true) {
            val line = readLine(input) ?: return null
            if (line.isEmpty()) break
            if (line.startsWith("Content-Disposition", ignoreCase = true)) {
                fileName = Regex("filename=\"([^\"]*)\"").find(line)?.groupValues?.get(1)
            }
        }
        return fileName?.ifBlank { null } ?: "archivo_${System.currentTimeMillis()}"
    }

    private fun sanitizeFileName(name: String): String {
        // File(name).name se queda solo con el último segmento — tira cualquier "../"
        // o ruta absoluta que un cliente malicioso mande en el nombre del archivo.
        val base = File(name.replace('\\', '/')).name.trim()
        val cleaned = base.ifBlank { "archivo_${System.currentTimeMillis()}" }
            .replace(Regex("[\\x00-\\x1F]"), "_")
        return if (cleaned == "." || cleaned == "..") "archivo_${System.currentTimeMillis()}" else cleaned
    }

    /**
     * Copia el contenido binario de la parte a `destFile` hasta encontrar `delimiter`
     * (`\r\n--boundary`), sin cargar el archivo completo en memoria — decisivo en un
     * teléfono con RAM limitada para subidas de cientos de MB. Se lee en chunks de 8KB,
     * decodificados como ISO-8859-1 (mapeo 1:1 byte↔char, sin pérdida) solo para poder
     * usar `String.indexOf` — mucho más rápido que comparar el delimitador byte a byte a
     * mano — y se conserva como "carry" la cola del chunk (delimiter.length - 1 bytes)
     * por si el delimitador quedó partido justo en el borde entre dos chunks.
     */
    private fun streamPartToFile(input: InputStream, delimiter: String, destFile: File): Boolean {
        val chunk = ByteArray(8192)
        var carry = ByteArray(0)
        return try {
            FileOutputStream(destFile).use { out ->
                while (true) {
                    val read = input.read(chunk)
                    if (read <= 0) {
                        if (carry.isNotEmpty()) out.write(carry)
                        return@use
                    }
                    val combined = if (carry.isEmpty()) chunk.copyOf(read) else carry + chunk.copyOf(read)
                    val combinedStr = String(combined, Charsets.ISO_8859_1)
                    val idx = combinedStr.indexOf(delimiter)
                    if (idx >= 0) {
                        out.write(combined, 0, idx)
                        return@use
                    }
                    val safeLen = maxOf(0, combined.size - delimiter.length + 1)
                    out.write(combined, 0, safeLen)
                    carry = combined.copyOfRange(safeLen, combined.size)
                }
            }
            true
        } catch (_: Exception) {
            try { destFile.delete() } catch (_: Exception) { }
            false
        }
    }

    /** Envoltorio que corta la lectura en `limit` bytes — así una parte multipart no
     *  puede leer más allá del Content-Length declarado y quedarse bloqueada esperando
     *  bytes que nunca llegan. */
    private class LimitedInputStream(private val delegate: InputStream, private var remaining: Long) : InputStream() {
        override fun read(): Int {
            if (remaining <= 0) return -1
            val b = delegate.read()
            if (b >= 0) remaining--
            return b
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            val toRead = minOf(len.toLong(), remaining).toInt()
            val read = delegate.read(b, off, toRead)
            if (read > 0) remaining -= read
            return read
        }
    }

    // ── HTTP helpers ─────────────────────────────────────────────────────

    private fun readLine(input: InputStream): String? {
        val buffer = ByteArrayOutputStream()
        var readAny = false
        while (true) {
            val b = input.read()
            if (b == -1) return if (readAny) buffer.toString("ISO-8859-1") else null
            readAny = true
            if (b == '\n'.code) {
                val bytes = buffer.toByteArray()
                val len = if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) bytes.size - 1 else bytes.size
                return String(bytes, 0, len, Charsets.ISO_8859_1)
            }
            buffer.write(b)
        }
    }

    private fun splitPathQuery(raw: String): Pair<String, String> {
        val idx = raw.indexOf('?')
        return if (idx < 0) raw to "" else raw.substring(0, idx) to raw.substring(idx + 1)
    }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        return query.split("&").mapNotNull { pair ->
            if (pair.isBlank()) return@mapNotNull null
            val idx = pair.indexOf('=')
            if (idx < 0) urlDecode(pair) to "" else urlDecode(pair.substring(0, idx)) to urlDecode(pair.substring(idx + 1))
        }.toMap()
    }

    private fun urlDecode(s: String): String = try { URLDecoder.decode(s, "UTF-8") } catch (_: Exception) { s }
    private fun urlEncode(s: String): String = try { URLEncoder.encode(s, "UTF-8") } catch (_: Exception) { s }

    private fun escapeHtml(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&#39;")

    private fun statusText(code: Int): String = when (code) {
        200 -> "OK"; 400 -> "Bad Request"; 403 -> "Forbidden"; 404 -> "Not Found"
        413 -> "Payload Too Large"; 500 -> "Internal Server Error"; else -> "Unknown"
    }

    // Gap real encontrado en la auditoría 2026-08-25 (docs/referencias/interfaz/
    // REFERENCIA_ARYTERLINK.md — panel web de referencia investigado hoy mismo, patrón de CSP
    // completo): la respuesta HTML del listado de archivos no mandaba ningún header CSP.
    // Defensa en profundidad barata contra XSS si algún día se muestra un nombre de archivo sin
    // escapar correctamente (hoy ya pasa por escapeHtml(), esto es una capa extra, no el fix
    // primario) — solo en respuestas HTML, no interfiere con la descarga de archivos.
    private fun writeText(output: OutputStream, status: Int, contentType: String, body: String) {
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        val cspHeader = if (contentType.startsWith("text/html")) {
            "Content-Security-Policy: default-src 'self'; script-src 'none'; frame-ancestors 'none'\r\n"
        } else ""
        val headers = "HTTP/1.1 $status ${statusText(status)}\r\n" +
            "Content-Type: $contentType\r\n" +
            "Content-Length: ${bodyBytes.size}\r\n" +
            cspHeader +
            "Connection: close\r\n\r\n"
        output.write(headers.toByteArray(Charsets.US_ASCII))
        output.write(bodyBytes)
        output.flush()
    }

    // ── Info para la UI (NubeFragment) ──────────────────────────────────

    fun localIpAddress(): String? {
        return try {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .flatMap { it.inetAddresses.asSequence() }
                .filter { !it.isLoopbackAddress && it is java.net.Inet4Address }
                .map { it.hostAddress }
                .firstOrNull()
        } catch (_: Exception) {
            null
        }
    }
}

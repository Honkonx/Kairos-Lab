package com.termux.app.util

import android.content.Context
import com.termux.shared.termux.TermuxConstants
import java.io.BufferedOutputStream
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

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

    // Cap de un campo de TEXTO del multipart (category/pw/compress) — nada que ver con
    // MAX_UPLOAD_BYTES (eso es el archivo). Sin este tope, un cliente malicioso podría mandar
    // un campo de texto de cientos de MB antes de la parte del archivo y hacerlo acumular
    // entero en RAM (ver MultipartReader/handleUpload) — 4KB es de sobra para una contraseña
    // o un nombre de categoría real.
    private const val MAX_TEXT_FIELD_BYTES = 4096

    // Investigación previa (docs/arquitectura/INVESTIGACION_NUBE_PERSONAL_2026-09-01.md):
    // cifrado real por contraseña de lo que sube un tercero (pedido explícito del usuario,
    // "no solo un flag cosmético"). AES-256-GCM con clave derivada por PBKDF2-HMAC-SHA256 —
    // ambos algoritmos son parte del JCE estándar de Android desde API 26 (minSdk de Kairos),
    // sin librerías nuevas. Formato del archivo cifrado (".kenc"): MAGIC(6) + salt(16) + iv(12)
    // + ciphertext-con-tag-GCM-embebido — ver openDestinationStream()/deriveKey() más abajo.
    private const val ENC_MAGIC = "KNUBE1"
    private const val PBKDF2_ITERATIONS = 120_000
    private const val AES_KEY_BITS = 256
    private const val GCM_IV_BYTES = 12
    private const val GCM_TAG_BITS = 128
    private const val SALT_BYTES = 16

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

    // applicationContext guardado solo para poder disparar una notificación Android real al
    // recibir una subida (ver handleUpload()) — gap real encontrado en la auditoría de
    // referencia/ del 2026-09-01 (docs/referencias/herramientas/, patrón guest→host bridge de
    // Podroid ya adoptado en Kairos vía ModuleEventBridge, pero NubeServer nunca lo disparaba:
    // si la app estaba en segundo plano, el dueño del teléfono no se enteraba de que alguien
    // subió algo por el link). Nunca se usa para nada más que esto — NubeServer sigue sin
    // depender de Context para servir archivos.
    @Volatile private var appContext: Context? = null

    val isRunning: Boolean get() = serverSocket?.isClosed == false

    /** Conteo de archivos (no carpetas) dentro de `nubeRoot`, recursivo — para `cloud.status` del gateway
     *  (`GatewayAppBridge.kt`/`AppCloudOps`). Nunca devuelve el token ni lista nombres, solo un número. */
    fun fileCount(): Int = try {
        nubeRoot.walkTopDown().count { it.isFile }
    } catch (_: Exception) {
        0
    }

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
    fun start(preferredPort: Int = DEFAULT_PORT, context: Context? = null): Boolean {
        if (isRunning) return true
        context?.let { appContext = it.applicationContext }
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
            method == "GET" && path == "/" -> serveIndex(
                output,
                params["path"] ?: "",
                params["q"] ?: "",
                params["filter"] ?: "",
                params["sort"] ?: "name"
            )
            method == "GET" && path == "/download" -> serveDownload(output, params["path"] ?: "")
            method == "GET" && path == "/confirm-delete" -> serveConfirmDelete(output, params["path"] ?: "")
            method == "POST" && path == "/upload" -> handleUpload(output, input, headers, params["path"] ?: "")
            method == "POST" && path == "/delete" -> handleDelete(output, params["path"] ?: "", params["confirm"] ?: "")
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

    /** Icono + color de acento por TIPO de archivo, un único lookup en vez de dos `when`
     *  paralelos que podrían desincronizarse (DRY, ver .claude/rules/clean-code-principles.md).
     *  El color es el mismo que pinta el fondo/borde del cuadrado del icono en la tarjeta (ver
     *  serveIndex()) — paleta inspirada en el material de referencia de `ver/nube/` (tarjetas
     *  con icono coloreado por tipo: sky=imagen, rose=video, violeta=audio, púrpura=comprimido,
     *  azul=documento, teal=hoja de cálculo, naranja=presentación/apk, cian=código), adaptada a
     *  la paleta oscura ya vigente de Kairos (mismo criterio que el resto del archivo: hex fijo,
     *  esta página es standalone y no puede usar `?attr/kairos*`). Solo mira la extensión (sin
     *  abrir el archivo) — mismo criterio liviano que `URLConnection.guessContentTypeFromName`
     *  ya usa en serveDownload() para el Content-Type.
     */
    private data class FileTypeStyle(val icon: String, val color: String)

    /** Categoría de filtro (`?filter=...`) para un archivo — mejora de esta ronda: agrupa por el
     *  mismo criterio visual que [styleForFile] ya usa (misma lista de extensiones, DRY real
     *  hubiera significado que ambas funciones lean de una sola tabla, pero los "buckets" de
     *  filtro son deliberadamente más gruesos que los íconos — ej. doc/hoja/presentación se
     *  agrupan en "documentos" para el filtro, no 3 opciones separadas en el dropdown). Ver
     *  [buildSearchFormHtml] para dónde se usan estas claves. */
    private fun categoryForFile(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.US)
        return when (ext) {
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "svg" -> "imagenes"
            "mp4", "mkv", "mov", "avi", "webm", "3gp" -> "videos"
            "mp3", "wav", "ogg", "flac", "m4a", "aac" -> "audio"
            "zip", "tar", "gz", "xz", "7z", "rar", "kenc" -> "comprimidos"
            "pdf", "doc", "docx", "odt", "xls", "xlsx", "csv", "ods", "ppt", "pptx", "odp", "txt", "md", "log" -> "documentos"
            "apk" -> "apps"
            "json", "xml", "yml", "yaml", "sh", "py", "js", "kt", "java" -> "codigo"
            else -> "otros"
        }
    }

    /** Orden servidor-side (`?sort=name|date|size`) — carpetas siempre primero, mismo criterio
     *  que el `sortedWith` original tenía hardcodeado solo para "name"; esta ronda lo generaliza
     *  a las 3 opciones pedidas sin perder ese comportamiento (nunca intercalar carpetas y
     *  archivos por fecha/tamaño, sería confuso navegar). */
    private fun sortEntries(entries: List<File>, sort: String): List<File> {
        val secondary: Comparator<File> = when (sort) {
            "date" -> compareByDescending { it.lastModified() }
            "size" -> compareByDescending { it.length() }
            else -> compareBy { it.name.lowercase(Locale.getDefault()) }
        }
        return entries.sortedWith(compareBy<File> { !it.isDirectory }.then(secondary))
    }

    private fun styleForFile(name: String): FileTypeStyle {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.US)
        return when (ext) {
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "svg" -> FileTypeStyle("🖼️", "#38bdf8")
            "mp4", "mkv", "mov", "avi", "webm", "3gp" -> FileTypeStyle("🎬", "#fb7185")
            "mp3", "wav", "ogg", "flac", "m4a", "aac" -> FileTypeStyle("🎵", "#a78bfa")
            "zip", "tar", "gz", "xz", "7z", "rar", "kenc" -> FileTypeStyle("🗜️", "#c084fc")
            "pdf" -> FileTypeStyle("📕", "#f87171")
            "doc", "docx", "odt" -> FileTypeStyle("📝", "#60a5fa")
            "xls", "xlsx", "csv", "ods" -> FileTypeStyle("📊", "#2dd4bf")
            "ppt", "pptx", "odp" -> FileTypeStyle("📽️", "#fb923c")
            "apk" -> FileTypeStyle("📱", "#facc15")
            "txt", "md", "log" -> FileTypeStyle("📃", "#94a3b8")
            "json", "xml", "yml", "yaml", "sh", "py", "js", "kt", "java" -> FileTypeStyle("💻", "#22d3ee")
            else -> FileTypeStyle("📄", "#94a3b8")
        }
    }

    /** Fecha real del archivo (`lastModified()`), no simulada — el material de referencia de
     *  `ver/nube/` muestra "Hace 10 min" / "Ayer 18:40" junto al tamaño de cada tarjeta; acá se
     *  usa una fecha corta real en vez de un cálculo relativo (evita mantener un texto que se
     *  desactualiza si la página queda abierta) — dato genuino disponible en el filesystem, no
     *  una simulación de UI como en el HTML de referencia. */
    private fun formatFileDate(epochMs: Long): String = try {
        SimpleDateFormat("dd MMM, HH:mm", Locale("es", "ES")).format(Date(epochMs))
    } catch (_: Exception) {
        ""
    }

    /** Uso REAL de disco del dispositivo (no simulado como en el material de referencia de
     *  `ver/nube/`, que mockea "15 GB / 500 GB" fijo) — `File.totalSpace`/`usableSpace` sobre
     *  `nubeRoot` reflejan la partición real donde vive `$HOME/nube` (API nativa de Android/Java,
     *  sin librerías nuevas). Alimenta el widget "Uso de Disco Local" del header, pedido
     *  explícito de la imagen y ambos HTML de referencia como el elemento más repetido de los 3. */
    private data class DiskUsage(val usedHuman: String, val totalHuman: String, val percent: Int)

    private fun diskUsage(): DiskUsage {
        val total = nubeRoot.totalSpace
        val usable = nubeRoot.usableSpace
        val used = (total - usable).coerceAtLeast(0)
        val pct = if (total > 0) ((used.toDouble() / total.toDouble()) * 100).toInt().coerceIn(0, 100) else 0
        return DiskUsage(ManagerNativeUtils.humanSize(used), ManagerNativeUtils.humanSize(total), pct)
    }

    private fun relPathOf(file: File): String {
        val root = nubeRoot.canonicalPath
        val full = try { file.canonicalPath } catch (_: Exception) { file.path }
        return full.removePrefix(root).trimStart(File.separatorChar)
    }

    // ── Rutas ────────────────────────────────────────────────────────────

    /**
     * Mejora de esta ronda: el buscador/filtro que ya existía en la versión nativa
     * ([NubeFragment], JS-driven) no tenía equivalente en el panel WEB — esta página se sirve
     * con `script-src 'none'` (ver [writeText]), así que el equivalente es 100% server-side:
     * `?q=`/`?filter=`/`?sort=` recargan la página vía un `<form method="get">` real (sin JS),
     * ver [buildSearchFormHtml]. `q`/`filter` se aplican solo a ARCHIVOS (las carpetas siempre
     * se listan si su nombre matchea `q`, o siempre si `q` está vacío) — filtrar carpetas por
     * "tipo de archivo" no tiene sentido real.
     */
    private fun serveIndex(output: OutputStream, relPath: String, query: String, filter: String, sort: String) {
        val dir = resolveSafePath(relPath)
        if (dir == null || !dir.isDirectory) {
            writeText(output, 400, TEXT_PLAIN, "400 Bad Request — ruta inválida o fuera de la carpeta nube")
            return
        }
        var entries = dir.listFiles()?.toList() ?: emptyList()
        val trimmedQuery = query.trim()
        if (trimmedQuery.isNotEmpty()) {
            entries = entries.filter { it.name.contains(trimmedQuery, ignoreCase = true) }
        }
        val activeFilter = filter.ifBlank { "todos" }
        if (activeFilter != "todos") {
            entries = entries.filter { it.isDirectory || categoryForFile(it.name) == activeFilter }
        }
        val activeSort = sort.ifBlank { "name" }
        entries = sortEntries(entries, activeSort)

        // Grid de tarjetas (antes: filas planas de lista) — adopta el patrón visual más fuerte
        // del material de referencia de `ver/nube/` (nube_personal_web.html / _interfaz_web.html:
        // grid de tarjetas con icono coloreado por tipo, badge de estado, extensión+acción en el
        // pie). 100% CSS/HTML — sin JS, el CSP (`script-src 'none'`) sigue intacto.
        val cards = StringBuilder()
        if (dir.path != nubeRoot.path) {
            val parentRel = relPathOf(dir.parentFile ?: nubeRoot)
            cards.append(
                "<a class=\"file-card file-card--dir\" href=\"/?path=${urlEncode(parentRel)}&token=$token\">" +
                    "<span class=\"file-card-icon\" style=\"background:#ffffff1a;color:#cbd5e1;border-color:#ffffff26\">📁</span>" +
                    "<p class=\"file-card-name\">.. (subir)</p></a>"
            )
        }
        for (f in entries) {
            val rel = relPathOf(f)
            if (f.isDirectory) {
                cards.append(
                    "<a class=\"file-card file-card--dir\" href=\"/?path=${urlEncode(rel)}&token=$token\">" +
                        "<span class=\"file-card-icon\" style=\"background:#ffffff1a;color:#cbd5e1;border-color:#ffffff26\">📁</span>" +
                        "<p class=\"file-card-name\" title=\"${escapeHtml(f.name)}\">${escapeHtml(f.name)}</p></a>"
                )
            } else {
                // Badge real "🔒 Cifrado" para archivos ".kenc" (cifrados con la contraseña que
                // puso quien subió, ver openDestinationStream) — antes era un prefijo de texto
                // suelto en el nombre, ahora una píldora en la esquina de la tarjeta, mismo
                // patrón que el badge "Cifrado"/"Verificado" del material de referencia.
                val style = styleForFile(f.name)
                val encryptedBadge = if (f.name.endsWith(".kenc")) {
                    "<span class=\"badge badge--locked\">🔒 Cifrado</span>"
                } else ""
                val ext = f.name.substringAfterLast('.', "").uppercase(Locale.US).ifBlank { "ARCHIVO" }
                cards.append(
                    "<div class=\"file-card\">" +
                        "<div class=\"file-card-top\">" +
                        "<span class=\"file-card-icon\" style=\"background:${style.color}24;color:${style.color};border-color:${style.color}4d\">${style.icon}</span>" +
                        encryptedBadge +
                        "</div>" +
                        "<p class=\"file-card-name\" title=\"${escapeHtml(f.name)}\">${escapeHtml(f.name)}</p>" +
                        "<p class=\"file-card-meta\">${ManagerNativeUtils.humanSize(f.length())} • ${formatFileDate(f.lastModified())}</p>" +
                        "<div class=\"file-card-foot\">" +
                        "<span class=\"file-card-ext\">$ext</span>" +
                        "<span class=\"file-card-actions\">" +
                        "<a class=\"file-card-dl\" href=\"/download?path=${urlEncode(rel)}&token=$token\" title=\"Descargar\">⬇️</a>" +
                        "<a class=\"file-card-dl file-card-del\" href=\"/confirm-delete?path=${urlEncode(rel)}&token=$token\" title=\"Eliminar\">🗑️</a>" +
                        "</span>" +
                        "</div></div>"
                )
            }
        }
        val noResults = trimmedQuery.isNotEmpty() || activeFilter != "todos"
        val emptyState = when {
            entries.isEmpty() && noResults -> "<div class=\"empty-state\"><span class=\"empty-state-icon\">🔎</span>" +
                "<p class=\"empty-state-title\">Sin resultados</p>" +
                "<p class=\"empty-state-sub\">Nada coincide con la búsqueda/filtro actual.</p></div>"
            entries.isEmpty() && dir.path == nubeRoot.path -> "<div class=\"empty-state\"><span class=\"empty-state-icon\">🗂️</span>" +
                "<p class=\"empty-state-title\">Tu PC es tu Nube</p>" +
                "<p class=\"empty-state-sub\">Vacío por ahora — subí algo desde las tarjetas de abajo.</p></div>"
            else -> ""
        }
        val countLabel = if (entries.isNotEmpty()) {
            "<span class=\"card-count\">${entries.size} elemento${if (entries.size == 1) "" else "s"}</span>"
        } else ""
        val currentRel = relPathOf(dir)
        val searchForm = buildSearchFormHtml(currentRel, trimmedQuery, activeFilter, activeSort)
        val html = buildIndexHtml(currentRel, cards.toString() + emptyState, countLabel, searchForm)
        writeText(output, 200, TEXT_HTML, html)
    }

    /** Buscador/filtro/orden server-side — un único `<form method="get">` sin JS (ver cabecera
     *  de [serveIndex]). `path`/`token` viajan como campos ocultos para no perder la carpeta
     *  actual ni la sesión al enviar el form. */
    private fun buildSearchFormHtml(currentRel: String, query: String, filter: String, sort: String): String {
        val filterOptions = listOf(
            "todos" to "Todos los tipos", "imagenes" to "🖼️ Imágenes", "videos" to "🎬 Videos",
            "audio" to "🎵 Audio", "documentos" to "📄 Documentos", "comprimidos" to "🗜️ Comprimidos",
            "apps" to "📱 Apps (APK)", "codigo" to "💻 Código", "otros" to "❓ Otros"
        )
        val sortOptions = listOf("name" to "Nombre (A-Z)", "date" to "Más reciente", "size" to "Más pesado")
        fun optionsHtml(options: List<Pair<String, String>>, current: String) = options.joinToString("") { (value, label) ->
            val sel = if (value == current) " selected" else ""
            "<option value=\"$value\"$sel>${escapeHtml(label)}</option>"
        }
        val clearLink = if (query.isNotEmpty() || filter != "todos" || sort != "name") {
            "<a class=\"search-clear\" href=\"/?path=${urlEncode(currentRel)}&token=$token\">✕ Limpiar</a>"
        } else ""
        return """
            <form method="get" class="search-form">
                <input type="hidden" name="path" value="${escapeHtml(currentRel)}">
                <input type="hidden" name="token" value="$token">
                <input type="text" name="q" value="${escapeHtml(query)}" placeholder="🔎 Buscar por nombre…" class="search-input">
                <select name="filter" class="search-select">${optionsHtml(filterOptions, filter)}</select>
                <select name="sort" class="search-select">${optionsHtml(sortOptions, sort)}</select>
                <button type="submit" class="search-btn">Buscar</button>
                $clearLink
            </form>
        """.trimIndent()
    }

    // Paleta hardcodeada a mano — esta página es HTML standalone servido por un socket crudo
    // (no hay AAPT/build de recursos Android acá), así que no puede usar `?attr/kairos*`
    // (docs/estructura/COLORES_Y_TEMAS.md). Se toman los valores reales del tema "Oscuro" (el
    // default histórico de la app) para que el link se sienta parte de Kairos y no una página
    // genérica — bg/bg2/bg3/text/text2/text3/green/border son 1:1 los mismos hex que
    // `themes_kairos.xml` define para `Theme.Kairos.Oscuro` (solo se copian los tokens que esta
    // página realmente usa — YAGNI, no un volcado completo de la paleta).
    //
    // Rediseño completo (pedido explícito del usuario: "el enlace web está horrible, [...] sin
    // ser simétrico, sin animaciones") — de una `<table>`/`<form>` sin estructura a un layout de
    // tarjetas simétrico, con transiciones/keyframes CSS reales. Decisión de CSP: la CSP sigue
    // siendo `script-src 'none'` (ver writeText()) — TODO acá es CSS puro, cero JS. Las
    // animaciones (fade-in de tarjetas, hover/focus de botones e inputs, el switch de
    // "comprimir") son 100% alcanzables con transiciones/`@keyframes`/`:hover`/`:focus-within`/
    // `:checked` — no hacía falta relajar la CSP para lograr una UI pulida. El botón nativo del
    // `<input type=file>` se restylea con el pseudo-elemento estándar `::file-selector-button`
    // (soportado en Chrome/Firefox/Safari modernos) — sigue siendo el picker nativo del SO, sin
    // ocultar el input detrás de un `<label>` que necesitaría JS para reflejar el nombre elegido.
    private fun buildIndexHtml(currentRel: String, cards: String, countLabel: String, searchForm: String): String {
        val disk = diskUsage()
        return """
        <!doctype html><html><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <title>Nube — Kairos</title>
        <style>
          :root{
            --bg:#050505; --bg2:#0a0a0a; --bg3:#111111;
            --text:#e8e8e8; --text2:#888888; --text3:#555555;
            --green:#22c55e; --border:#1f1f1f;
          }
          *{box-sizing:border-box}
          body{
            font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;
            background:
              radial-gradient(1100px 480px at 50% -120px, rgba(34,197,94,.10), transparent 60%),
              var(--bg);
            color:var(--text);margin:0;padding:20px 16px 48px;
          }
          .page{max-width:820px;margin:0 auto}

          .page-header{
            display:flex;flex-direction:column;align-items:center;text-align:center;
            gap:8px;margin-bottom:22px;
            animation:fadeInUp .5s ease both;
          }
          .brand{display:flex;align-items:center;gap:10px}
          .brand-icon{font-size:26px}
          .brand h1{font-size:21px;margin:0;letter-spacing:.2px}
          .tagline{margin:0;font-size:12.5px;color:var(--text2)}
          .path-pill{
            margin:0;font-size:12.5px;color:var(--text2);
            background:var(--bg2);border:1px solid var(--border);
            padding:4px 12px;border-radius:999px;word-break:break-all;
          }
          .conn-badge{
            display:inline-flex;align-items:center;gap:7px;font-size:11.5px;
            color:var(--green);background:rgba(34,197,94,.10);
            border:1px solid rgba(34,197,94,.25);padding:4px 12px;border-radius:999px;
          }
          .conn-dot{
            width:7px;height:7px;border-radius:50%;background:var(--green);
            box-shadow:0 0 0 0 rgba(34,197,94,.5);
            animation:pulseDot 2.2s ease infinite;
          }
          .storage-pill{
            display:flex;flex-direction:column;gap:4px;width:100%;max-width:260px;margin-top:2px;
          }
          .storage-label{
            display:flex;justify-content:space-between;gap:8px;font-size:11px;color:var(--text2);
          }
          .storage-label b{color:var(--text);font-weight:600}
          .storage-bar{
            width:100%;height:6px;background:var(--bg3);border:1px solid var(--border);
            border-radius:999px;overflow:hidden;
          }
          .storage-bar-fill{
            height:100%;background:linear-gradient(90deg,var(--green),#4ade80);
            border-radius:999px;transition:width .4s ease;
          }

          .card{
            background:var(--bg2);border:1px solid var(--border);border-radius:14px;
            padding:16px;margin-bottom:16px;
            animation:fadeInUp .5s ease both;
          }
          .card-title-row{
            display:flex;align-items:center;justify-content:space-between;margin-bottom:10px;
          }
          .card-title{
            font-size:13px;text-transform:uppercase;letter-spacing:.6px;
            color:var(--text2);margin:0;font-weight:600;
          }
          .card-count{font-size:11.5px;color:var(--text3)}

          .files-grid{
            display:grid;grid-template-columns:repeat(auto-fill,minmax(148px,1fr));gap:10px;
          }
          .file-card{
            background:var(--bg3);border:1px solid var(--border);border-radius:12px;
            padding:12px;display:flex;flex-direction:column;gap:8px;min-height:96px;
            text-decoration:none;color:inherit;
            transition:border-color .15s ease, transform .15s ease;
          }
          .file-card:hover{border-color:rgba(34,197,94,.35);transform:translateY(-2px)}
          .file-card:focus-visible{outline:none;border-color:var(--green);box-shadow:0 0 0 3px rgba(34,197,94,.18)}
          .file-card--dir{flex-direction:row;align-items:center;min-height:auto}
          .file-card-top{display:flex;align-items:flex-start;justify-content:space-between;gap:6px}
          .file-card-icon{
            width:36px;height:36px;border-radius:10px;flex:0 0 auto;
            display:flex;align-items:center;justify-content:center;
            font-size:17px;border:1px solid transparent;
          }
          .badge{
            font-size:9.5px;font-weight:700;padding:2px 7px;border-radius:999px;
            white-space:nowrap;letter-spacing:.2px;
          }
          .badge--locked{background:rgba(245,158,11,.14);color:#fbbf24;border:1px solid rgba(245,158,11,.3)}
          .file-card-name{
            font-size:12.5px;font-weight:500;color:var(--text);margin:0;
            overflow:hidden;text-overflow:ellipsis;white-space:nowrap;
          }
          .file-card-meta{font-size:10.5px;color:var(--text3);margin:0}
          .file-card-foot{
            display:flex;align-items:center;justify-content:space-between;
            margin-top:auto;padding-top:8px;border-top:1px solid var(--border);
          }
          .file-card-ext{
            font-size:9.5px;color:var(--text3);letter-spacing:.4px;
            font-family:ui-monospace,SFMono-Regular,Menlo,monospace;
          }
          .file-card-actions{display:flex;align-items:center;gap:2px}
          .file-card-dl{
            color:var(--green);text-decoration:none;font-size:13px;padding:3px 9px;
            border-radius:6px;border:1px solid transparent;line-height:1;
            transition:background-color .15s ease, border-color .15s ease;
          }
          .file-card-dl:hover{background:rgba(34,197,94,.12);border-color:rgba(34,197,94,.35)}
          .file-card-dl:focus-visible{outline:none;background:rgba(34,197,94,.12);border-color:var(--green)}
          .file-card-del{color:#f87171}
          .file-card-del:hover{background:rgba(248,113,113,.12);border-color:rgba(248,113,113,.35)}
          .file-card-del:focus-visible{background:rgba(248,113,113,.12);border-color:#f87171}

          .search-form{
            display:flex;flex-wrap:wrap;gap:8px;margin-bottom:14px;align-items:center;
          }
          .search-input{
            flex:1 1 160px;min-width:0;padding:8px 10px;background:var(--bg);color:var(--text);
            border:1px solid var(--border);border-radius:8px;font-size:12.5px;
            transition:border-color .15s ease, box-shadow .15s ease;
          }
          .search-input:focus{outline:none;border-color:var(--green);box-shadow:0 0 0 3px rgba(34,197,94,.15)}
          .search-select{
            padding:8px 8px;background:var(--bg3);color:var(--text);
            border:1px solid var(--border);border-radius:8px;font-size:12px;
          }
          .search-btn{
            background:var(--bg3);color:var(--text);border:1px solid var(--border);
            padding:8px 14px;border-radius:8px;font-size:12.5px;font-weight:600;cursor:pointer;
            transition:border-color .15s ease;
          }
          .search-btn:hover{border-color:var(--green)}
          .search-clear{
            font-size:11.5px;color:var(--text3);text-decoration:none;padding:4px 6px;
          }
          .search-clear:hover{color:var(--text)}

          .empty-state{
            grid-column:1/-1;
            display:flex;flex-direction:column;align-items:center;gap:4px;
            padding:28px 0;text-align:center;
          }
          .empty-state-icon{font-size:30px;opacity:.8}
          .empty-state-title{margin:6px 0 0;font-size:14px;font-weight:600;color:var(--text)}
          .empty-state-sub{margin:0;font-size:12.5px;color:var(--text3)}

          .upload-grid{
            display:grid;grid-template-columns:1fr 1fr;gap:16px;
          }
          @media (max-width:560px){ .upload-grid{grid-template-columns:1fr} }

          .upload-card{
            background:var(--bg2);border:1px solid var(--border);border-radius:14px;
            padding:16px;display:flex;flex-direction:column;gap:12px;
            animation:fadeInUp .55s ease both;
            transition:border-color .2s ease, box-shadow .2s ease;
          }
          .upload-card:focus-within{
            border-color:rgba(34,197,94,.45);
            box-shadow:0 0 0 3px rgba(34,197,94,.12);
          }
          .upload-card-head{display:flex;align-items:center;gap:8px}
          .upload-card-icon{font-size:18px}
          .upload-card-title{font-size:14px;font-weight:600;margin:0}
          .upload-card-sub{font-size:11px;color:var(--text3);margin:2px 0 0}

          label{display:block;font-size:12px;color:var(--text2);margin-bottom:4px}

          input[type=password]{
            width:100%;padding:9px 10px;background:var(--bg);color:var(--text);
            border:1px solid var(--border);border-radius:8px;font-size:13px;
            transition:border-color .15s ease, box-shadow .15s ease;
          }
          input[type=password]:focus{
            outline:none;border-color:var(--green);
            box-shadow:0 0 0 3px rgba(34,197,94,.15);
          }

          input[type=file]{
            width:100%;font-size:12.5px;color:var(--text2);
          }
          input[type=file]::file-selector-button{
            background:var(--bg3);color:var(--text);border:1px solid var(--border);
            padding:8px 12px;border-radius:7px;font-size:12.5px;font-weight:600;
            margin-right:10px;cursor:pointer;
            transition:background-color .15s ease, border-color .15s ease, transform .1s ease;
          }
          input[type=file]::file-selector-button:hover{
            background:var(--bg);border-color:var(--green);
          }
          input[type=file]::file-selector-button:active{transform:scale(.97)}

          .switch-row{display:flex;align-items:center;gap:10px;font-size:13px;color:#ccc}
          .switch{
            position:relative;width:38px;height:22px;flex:0 0 auto;
          }
          .switch input{position:absolute;opacity:0;width:100%;height:100%;margin:0;cursor:pointer}
          .switch-track{
            position:absolute;inset:0;background:var(--bg3);border:1px solid var(--border);
            border-radius:999px;transition:background-color .2s ease, border-color .2s ease;
          }
          .switch-track::before{
            content:"";position:absolute;top:2px;left:2px;width:16px;height:16px;
            background:var(--text2);border-radius:50%;
            transition:transform .2s ease, background-color .2s ease;
          }
          .switch input:checked ~ .switch-track{background:rgba(34,197,94,.25);border-color:var(--green)}
          .switch input:checked ~ .switch-track::before{transform:translateX(16px);background:var(--green)}
          .switch input:focus-visible ~ .switch-track{box-shadow:0 0 0 3px rgba(34,197,94,.2)}

          button[type=submit]{
            background:var(--green);color:#050505;border:none;
            padding:10px 16px;border-radius:8px;font-weight:700;font-size:13.5px;
            cursor:pointer;margin-top:2px;
            transition:transform .12s ease, box-shadow .12s ease, filter .12s ease;
          }
          button[type=submit]:hover{filter:brightness(1.08);box-shadow:0 4px 14px rgba(34,197,94,.25)}
          button[type=submit]:active{transform:scale(.97)}
          button[type=submit]:focus-visible{outline:none;box-shadow:0 0 0 3px rgba(34,197,94,.35)}
          input[type=file]::file-selector-button:focus-visible{outline:none;border-color:var(--green);box-shadow:0 0 0 3px rgba(34,197,94,.2)}

          @keyframes fadeInUp{
            from{opacity:0;transform:translateY(8px)}
            to{opacity:1;transform:translateY(0)}
          }
          @keyframes pulseDot{
            0%{box-shadow:0 0 0 0 rgba(34,197,94,.45)}
            70%{box-shadow:0 0 0 6px rgba(34,197,94,0)}
            100%{box-shadow:0 0 0 0 rgba(34,197,94,0)}
          }
          .card{animation-delay:.02s}
          .upload-grid .upload-card:nth-child(1){animation-delay:.06s}
          .upload-grid .upload-card:nth-child(2){animation-delay:.12s}
        </style>
        </head><body>
        <div class="page">
        <header class="page-header">
          <div class="brand"><span class="brand-icon">☁</span><h1>Nube</h1></div>
          <p class="tagline">Tu PC es tu Nube — almacenamiento privado, sin servidores de terceros</p>
          <div class="conn-badge"><span class="conn-dot"></span> Conectado${localIpAddress()?.let { " · $it:$port" } ?: ""}</div>
          <div class="storage-pill">
            <span class="storage-label">💾 Disco usado <b>${disk.usedHuman} / ${disk.totalHuman}</b></span>
            <div class="storage-bar"><div class="storage-bar-fill" style="width:${disk.percent}%"></div></div>
          </div>
          <p class="path-pill">/${escapeHtml(currentRel)}</p>
        </header>

        <section class="card">
          <div class="card-title-row">
            <h2 class="card-title">Archivos</h2>
            $countLabel
          </div>
          $searchForm
          <div class="files-grid">$cards</div>
        </section>

        <div class="upload-grid">
          ${buildUploadForm(currentRel, "imagenes", "image/*", "📷", "Subir imagen", "Galería, fotos y capturas", "img-upload")}
          ${buildUploadForm(currentRel, "archivos", null, "📄", "Subir archivo", "Documentos, ejecutables y ZIPs", "file-upload")}
        </div>
        </div>
        </body></html>
        """.trimIndent()
    }

    /**
     * Un `<form>` estático por categoría — sin JS de por medio (la respuesta HTML manda
     * `Content-Security-Policy: script-src 'none'`, ver writeText()), así que la selección de
     * categoría es un botón real por formulario, no un selector con lógica en el cliente.
     * `accept` sin `capture`: `capture` fuerza la cámara y salta el selector nativo; sin él,
     * el picker de Android/iOS ya ofrece Galería/Fotos como una de las opciones — más cerca de
     * lo que pidió el usuario ("abra el selector nativo de galería/fotos").
     *
     * Contraseña y "comprimir" son opcionales para QUIEN SUBE (pedido explícito del usuario) —
     * ver handleUpload()/openDestinationStream() para qué hace cada uno del lado del backend.
     * El "switch" de comprimir es un checkbox real (`<input type=checkbox>`) solo maquillado con
     * CSS (`:checked ~ .switch-track`, ver buildIndexHtml) — el valor que llega a `handleUpload`
     * es exactamente el mismo de siempre, cero cambio funcional.
     */
    private fun buildUploadForm(
        currentRel: String,
        category: String,
        accept: String?,
        icon: String,
        buttonLabel: String,
        subtitle: String,
        fieldId: String
    ): String {
        val acceptAttr = if (accept != null) " accept=\"$accept\"" else ""
        return """
            <form method="POST" action="/upload?path=${urlEncode(currentRel)}&token=$token" enctype="multipart/form-data" class="upload-card">
                <div class="upload-card-head">
                    <span class="upload-card-icon">$icon</span>
                    <div>
                        <p class="upload-card-title">$buttonLabel</p>
                        <p class="upload-card-sub">$subtitle</p>
                    </div>
                </div>
                <input type="hidden" name="category" value="$category">
                <div>
                    <label for="$fieldId-pw">🔑 Contraseña (opcional, cifra el archivo)</label>
                    <input type="password" id="$fieldId-pw" name="pw" placeholder="••••••••" autocomplete="new-password">
                </div>
                <div>
                    <label for="$fieldId-file">📎 Archivo</label>
                    <input type="file" id="$fieldId-file" name="file"$acceptAttr required>
                </div>
                <label class="switch-row" for="$fieldId-compress">
                    <span class="switch">
                        <input type="checkbox" id="$fieldId-compress" name="compress" value="1">
                        <span class="switch-track"></span>
                    </span>
                    🗜️ Comprimir (zip) esta subida
                </label>
                <button type="submit">⬆️ $buttonLabel</button>
            </form>
        """.trimIndent()
    }

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

    /**
     * Página de confirmación real (GET, sin efecto) antes de borrar — pedido explícito de esta
     * ronda: "nunca sin confirmar un borrado" (ver `.claude/rules/kairos-secrets-never-revealed.md`,
     * mismo principio aplicado acá a contenido en vez de a un secreto). El único botón de la
     * tarjeta (🗑️) navega ACÁ primero — el borrado real solo ocurre si el usuario manda el
     * `<form method="post">` de esta página, nunca desde un `GET` directo.
     */
    private fun serveConfirmDelete(output: OutputStream, relPath: String) {
        val file = resolveSafePath(relPath)
        if (file == null || (!file.isFile && !file.isDirectory) || file.path == nubeRoot.path) {
            writeText(output, 400, TEXT_PLAIN, "400 Bad Request — nada que eliminar en esa ruta")
            return
        }
        val parentRel = relPathOf(file.parentFile ?: nubeRoot)
        val kind = if (file.isDirectory) "carpeta" else "archivo"
        val html = """
            <!doctype html><html><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>Eliminar — Nube — Kairos</title>
            <style>
              body{font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;
                background:#050505;color:#e8e8e8;margin:0;min-height:100vh;
                display:flex;align-items:center;justify-content:center;padding:24px}
              .box{background:#0a0a0a;border:1px solid #1f1f1f;border-radius:14px;padding:22px;
                max-width:360px;width:100%;text-align:center}
              .icon{font-size:30px;margin-bottom:8px;display:block}
              h1{font-size:16px;margin:0 0 6px}
              p{font-size:12.5px;color:#888;margin:0 0 18px;word-break:break-word}
              .row{display:flex;gap:10px}
              a,button{flex:1;padding:10px;border-radius:8px;font-size:13px;font-weight:600;
                text-decoration:none;border:1px solid #1f1f1f;cursor:pointer}
              .cancel{background:#111111;color:#e8e8e8}
              .confirm{background:#ef4444;color:#050505;border:none}
            </style>
            </head><body>
            <div class="box">
              <span class="icon">⚠️</span>
              <h1>¿Eliminar este $kind?</h1>
              <p>${escapeHtml(file.name)} — esta acción no se puede deshacer.</p>
              <div class="row">
                <a class="cancel" href="/?path=${urlEncode(parentRel)}&token=$token">Cancelar</a>
                <form method="post" action="/delete" style="flex:1;margin:0">
                  <input type="hidden" name="path" value="${escapeHtml(relPath)}">
                  <input type="hidden" name="token" value="$token">
                  <input type="hidden" name="confirm" value="1">
                  <button type="submit" class="confirm">Eliminar</button>
                </form>
              </div>
            </div>
            </body></html>
        """.trimIndent()
        writeText(output, 200, TEXT_HTML, html)
    }

    /** Borrado real (POST únicamente, `confirm=1` obligatorio) — [resolveSafePath] ya impide
     *  salir de `nubeRoot`, y acá además se rechaza explícitamente borrar `nubeRoot` en sí
     *  (mismo espíritu que el resto del archivo: la carpeta contenedora nunca es un objetivo
     *  válido, solo su contenido). */
    private fun handleDelete(output: OutputStream, relPath: String, confirm: String) {
        if (confirm != "1") {
            writeText(output, 400, TEXT_PLAIN, "400 Bad Request — falta confirmación")
            return
        }
        val file = resolveSafePath(relPath)
        if (file == null || (!file.isFile && !file.isDirectory) || file.path == nubeRoot.path) {
            writeText(output, 400, TEXT_PLAIN, "400 Bad Request — nada que eliminar en esa ruta")
            return
        }
        val parentRel = relPathOf(file.parentFile ?: nubeRoot)
        val deleted = try {
            if (file.isDirectory) file.deleteRecursively() else file.delete()
        } catch (_: Exception) {
            false
        }
        if (!deleted) {
            writeText(output, 500, TEXT_PLAIN, "500 — no se pudo eliminar (¿permisos?)")
            return
        }
        writeText(output, 200, TEXT_HTML, buildRedirectHtml(parentRel))
    }

    /** Página transitoria de redirect (0s, `<meta refresh>`) — mismo patrón que
     *  [buildIndexHtmlAfterUpload], reusado acá para no duplicar el mismo bloque de estilos con
     *  un texto de éxito distinto (DRY real: mismo esqueleto visual, mensaje parametrizado). */
    private fun buildRedirectHtml(targetPathParam: String): String = """
        <!doctype html><html><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <meta http-equiv="refresh" content="0; url=/?path=${urlEncode(targetPathParam)}&token=$token">
        <title>Nube — Kairos</title>
        <style>
          body{font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;
            background:#050505;color:#e8e8e8;margin:0;height:100vh;
            display:flex;align-items:center;justify-content:center;text-align:center}
          p{font-size:14px;color:#888}
          .ok{color:#22c55e;font-size:22px;display:block;margin-bottom:8px;animation:pop .35s ease}
          @keyframes pop{from{opacity:0;transform:scale(.8)}to{opacity:1;transform:scale(1)}}
        </style>
        </head><body>
        <div><span class="ok">✔ Eliminado</span><p>Redirigiendo…</p></div>
        </body></html>
    """.trimIndent()

    /**
     * Subida con categorización real (pedido explícito del usuario, 2026-09-01, ver
     * docs/arquitectura/INVESTIGACION_NUBE_PERSONAL_2026-09-01.md): el formulario manda
     * `category` (imagenes/archivos), `pw` (contraseña opcional → cifrado real) y `compress`
     * (checkbox opcional → zip de la subida) ANTES del campo `file` — mismo orden en que
     * `buildUploadForm()` los declara, y los navegadores mandan las partes multipart en el
     * orden del DOM, así que MultipartReader los puede leer secuencialmente sin necesidad de
     * "mirar hacia atrás". `targetPathParam` ya NO decide dónde se guarda el archivo (eso lo
     * decide la categoría, ver más abajo) — solo se usa para volver al mismo listado tras
     * subir, y se sigue validando para no armar una URL de redirect fuera de nubeRoot.
     */
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
        if (resolveSafePath(targetPathParam) == null) {
            writeText(output, 400, TEXT_PLAIN, "400 Bad Request — carpeta de retorno inválida")
            return
        }

        val bounded = LimitedInputStream(input, contentLength)
        val reader = MultipartReader(bounded)
        reader.readLine() // descarta la línea de apertura "--boundary"

        var category = "archivos"
        var password = ""
        var compress = false
        var fileName: String? = null

        while (fileName == null) {
            var partName: String? = null
            var partFileName: String? = null
            while (true) {
                val line = reader.readLine()
                if (line == null) {
                    writeText(output, 400, TEXT_PLAIN, "400 Bad Request — multipart truncado")
                    return
                }
                if (line.isEmpty()) break
                if (line.startsWith("Content-Disposition", ignoreCase = true)) {
                    partName = Regex("name=\"([^\"]*)\"").find(line)?.groupValues?.get(1)
                    partFileName = Regex("filename=\"([^\"]*)\"").find(line)?.groupValues?.get(1)
                }
            }
            if (partFileName != null) {
                fileName = partFileName.ifBlank { "archivo_${System.currentTimeMillis()}" }
            } else {
                val valueBytes = ByteArrayOutputStream()
                val complete = reader.readPartBody(boundary) { buf, len ->
                    val room = MAX_TEXT_FIELD_BYTES - valueBytes.size()
                    if (room > 0) valueBytes.write(buf, 0, minOf(len, room))
                }
                // readPartBody() consume el delimitador "\r\n--boundary" pero NO el resto de
                // esa línea de boundary (el "\r\n" de continuación, o el "--\r\n" de cierre) —
                // hay que descartar esa línea suelta antes de volver a leer headers, si no el
                // primer readLine() de la próxima parte lee una línea vacía en vez del
                // Content-Disposition real y desalinea el parseo entero.
                if (!complete || reader.readLine() == null) {
                    writeText(output, 400, TEXT_PLAIN, "400 Bad Request — multipart truncado")
                    return
                }
                val value = valueBytes.toString("UTF-8")
                when (partName) {
                    "category" -> category = if (value == "imagenes") "imagenes" else "archivos"
                    "pw" -> password = value
                    "compress" -> compress = value.isNotBlank()
                }
            }
        }

        // fileName siempre no-nulo acá (el while de arriba solo termina cuando se asignó) —
        // se recapta en un val para no depender de que el compilador infiera el smart-cast de
        // un `var` mutado dentro del loop.
        val resolvedFileName = fileName ?: run {
            writeText(output, 400, TEXT_PLAIN, "400 Bad Request — multipart sin archivo")
            return
        }

        // Cada subida vive en su propia carpeta de sesión (timestamp+id al azar) dentro de
        // la categoría — así "comprimir esta subida" tiene un contenido bien delimitado para
        // zipear (ver zipFolder()) sin arrastrar subidas previas de otros visitantes.
        val categoryDir = File(nubeRoot, category).apply { mkdirs() }
        restrictPermissions(categoryDir)
        val sessionDir = File(categoryDir, sessionFolderId()).apply { mkdirs() }
        restrictPermissions(sessionDir)

        val encrypted = password.isNotBlank()
        val baseName = sanitizeFileName(resolvedFileName)
        val destFile = File(sessionDir, if (encrypted) "$baseName.kenc" else baseName)
        // Defensa en profundidad: sanitizeFileName ya se queda con un único segmento sin
        // separadores, pero igual se revalida con canonicalPath (mismo criterio que
        // resolveSafePath) antes de escribir un solo byte a disco.
        val destCanonical = try { destFile.canonicalFile } catch (_: Exception) { null }
        if (destCanonical == null || !destCanonical.path.startsWith(sessionDir.canonicalPath + File.separator)) {
            writeText(output, 400, TEXT_PLAIN, "400 Bad Request — nombre de archivo inválido")
            return
        }

        val ok = try {
            var success = false
            openDestinationStream(destCanonical, if (encrypted) password else null).use { out ->
                success = reader.readPartBody(boundary) { buf, len -> out.write(buf, 0, len) }
            }
            success
        } catch (_: Exception) {
            false
        }
        if (!ok) {
            try { destCanonical.delete() } catch (_: Exception) { }
            writeText(output, 500, TEXT_PLAIN, "500 — no se pudo guardar el archivo")
            return
        }
        restrictPermissions(destCanonical)

        // Notificación real (no solo la marca "🔒" del listado) — pedido implícito resuelto por
        // la auditoría de referencia/ del 2026-09-01 (ver comentario de [appContext] arriba):
        // si Kairos está en segundo plano cuando alguien sube algo por el link, el dueño del
        // teléfono se entera igual, con el mismo mecanismo de notificación que ya usan los
        // eventos de sesión de módulos. Best-effort — appContext puede ser null si el servidor
        // arrancó desde un caller que todavía no pasa Context (compatibilidad hacia atrás).
        appContext?.let {
            ModuleEventBridge.notifyDirect(it, "nube", "upload_received", "$baseName ($category)")
        }

        if (compress) {
            val zipFile = File(categoryDir, "${sessionDir.name}.zip")
            // Si falla el zip se deja la carpeta sin comprimir tal cual — mejor entregar la
            // subida sin comprimir que perderla por un error de empaquetado.
            if (zipFolder(sessionDir, zipFile)) {
                sessionDir.deleteRecursively()
                restrictPermissions(zipFile)
            }
        }

        writeText(output, 200, TEXT_HTML, buildIndexHtmlAfterUpload(targetPathParam))
    }

    // Página transitoria (0s de refresh, casi nunca visible más de un instante) — igual se
    // mantiene en línea con la paleta del resto del sitio en vez de texto plano sin estilo,
    // por si la conexión es lenta y el usuario la ve un segundo de más.
    private fun buildIndexHtmlAfterUpload(targetPathParam: String): String = """
        <!doctype html><html><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <meta http-equiv="refresh" content="0; url=/?path=${urlEncode(targetPathParam)}&token=$token">
        <title>Nube — Kairos</title>
        <style>
          body{font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;
            background:#050505;color:#e8e8e8;margin:0;height:100vh;
            display:flex;align-items:center;justify-content:center;text-align:center}
          p{font-size:14px;color:#888}
          .ok{color:#22c55e;font-size:22px;display:block;margin-bottom:8px;animation:pop .35s ease}
          @keyframes pop{from{opacity:0;transform:scale(.8)}to{opacity:1;transform:scale(1)}}
        </style>
        </head><body>
        <div><span class="ok">✔ Subida correcta</span><p>Redirigiendo…</p></div>
        </body></html>
    """.trimIndent()

    private fun sessionFolderId(): String {
        val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val rand = ByteArray(3).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        return "$ts-$rand"
    }

    // ── Cifrado real por contraseña (opcional, lo pide quien sube) ────────

    /**
     * Si `password` viene con algo, envuelve el FileOutputStream en un CipherOutputStream
     * AES-256-GCM cuya clave se deriva de esa contraseña (PBKDF2-HMAC-SHA256, salt propio por
     * archivo) — cifrado real, no un flag cosmético (pedido explícito del usuario). El header
     * en claro (MAGIC+salt+iv) es imprescindible para poder re-derivar la misma clave al
     * descifrar; la contraseña en sí NUNCA se guarda en ningún lado (mismo criterio que
     * .claude/rules/kairos-secrets-never-revealed.md aplica a credenciales).
     *
     * Todavía no hay UI de descifrado dentro de la app (fase 2 documentada, ver
     * docs/arquitectura/INVESTIGACION_NUBE_PERSONAL_2026-09-01.md) — el dueño del teléfono
     * puede descifrar a mano con el mismo esquema documentado ahí mientras esa UI no exista.
     */
    private fun openDestinationStream(destFile: File, password: String?): OutputStream {
        val fos = FileOutputStream(destFile)
        if (password.isNullOrBlank()) return fos
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(GCM_IV_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(GCM_TAG_BITS, iv))
        fos.write(ENC_MAGIC.toByteArray(Charsets.US_ASCII))
        fos.write(salt)
        fos.write(iv)
        return CipherOutputStream(fos, cipher)
    }

    private fun deriveKey(password: String, salt: ByteArray): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, AES_KEY_BITS)
        return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
    }

    // ── Permisos restrictivos sobre lo que sube un tercero ─────────────────

    /**
     * chmod 700 (carpetas) / 600 (archivos) reales sobre todo lo que llega por el link de
     * Nube — mismo criterio de "restringir en disco, no solo ocultar en la UI" que
     * .claude/rules/kairos-secrets-never-revealed.md aplica a credenciales, extendido acá a
     * contenido de terceros que el dueño del teléfono no pidió hacer accesible más allá del
     * propio link. Usa `chmod` real vía ManagerNativeUtils.runShell (bash de Termux) en vez de
     * File.setReadable/setWritable de Java — mismo patrón ya usado por RemoteManager.kt para
     * directorios/archivos sensibles (SSH). Best-effort: si `chmod` falla (bash no disponible
     * todavía, permiso denegado) no aborta la subida — el path/token siguen siendo la barrera
     * primaria de este servidor.
     */
    private fun restrictPermissions(file: File) {
        val mode = if (file.isDirectory) "700" else "600"
        val escapedPath = file.absolutePath.replace("'", "'\\''")
        ManagerNativeUtils.runShell("chmod $mode '$escapedPath'", 5)
    }

    // ── Compresión opcional de una subida ───────────────────────────────

    /** Comprime los archivos sueltos de `folder` (una carpeta de sesión siempre tiene un único
     *  archivo — ver handleUpload) en `destZip`, sin depender de ningún binario externo
     *  (java.util.zip, siempre disponible en el runtime de Android). */
    private fun zipFolder(folder: File, destZip: File): Boolean {
        return try {
            ZipOutputStream(BufferedOutputStream(FileOutputStream(destZip))).use { zos ->
                folder.listFiles()?.filter { it.isFile }?.forEach { f ->
                    zos.putNextEntry(ZipEntry(f.name))
                    FileInputStream(f).use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
            true
        } catch (_: Exception) {
            try { destZip.delete() } catch (_: Exception) { }
            false
        }
    }

    // ── Multipart genérico — a diferencia del parser viejo (que solo soportaba UN
    //    <input type=file> y trataba el resto del body como su contenido binario), este
    //    recorre partes de TEXTO (category/pw/compress) Y la parte BINARIA (file) en el mismo
    //    stream. Usa un buffer "pending" que permite alternar entre lectura por línea
    //    (headers de cada parte, valor de un campo de texto) y lectura binaria delimitada por
    //    boundary (el archivo) sin perder ni duplicar bytes en el borde entre ambos modos. ──

    private class MultipartReader(private val source: InputStream) {
        private var pending: ByteArray = ByteArray(0)

        private fun refill(): Boolean {
            val chunk = ByteArray(8192)
            val n = source.read(chunk)
            if (n <= 0) return false
            pending = if (pending.isEmpty()) chunk.copyOf(n) else pending + chunk.copyOf(n)
            return true
        }

        fun readLine(): String? {
            while (true) {
                val idx = pending.indexOf('\n'.code.toByte())
                if (idx >= 0) {
                    val lineBytes = pending.copyOfRange(0, idx)
                    val len = if (lineBytes.isNotEmpty() && lineBytes.last() == '\r'.code.toByte()) lineBytes.size - 1 else lineBytes.size
                    val line = String(lineBytes, 0, len, Charsets.ISO_8859_1)
                    pending = pending.copyOfRange(idx + 1, pending.size)
                    return line
                }
                if (!refill()) {
                    if (pending.isEmpty()) return null
                    val line = String(pending, Charsets.ISO_8859_1)
                    pending = ByteArray(0)
                    return line
                }
            }
        }

        /** Lee el cuerpo binario de la parte actual hasta el delimitador `\r\n--boundary` (sin
         *  incluirlo), pasando los bytes seguros a [sink] a medida que llegan — nunca carga la
         *  parte completa en memoria. Al terminar deja `pending` justo después del delimitador
         *  consumido, listo para que [readLine] siga con la próxima parte (o vea "--", fin del
         *  multipart). Devuelve false si el stream se cortó antes de encontrar el delimitador
         *  (multipart truncado / Content-Length mentiroso). */
        fun readPartBody(boundary: String, sink: (ByteArray, Int) -> Unit): Boolean {
            val delim = "\r\n--$boundary".toByteArray(Charsets.ISO_8859_1)
            while (true) {
                val idx = indexOf(pending, delim)
                if (idx >= 0) {
                    if (idx > 0) sink(pending, idx)
                    pending = pending.copyOfRange(idx + delim.size, pending.size)
                    return true
                }
                val safeLen = maxOf(0, pending.size - (delim.size - 1))
                if (safeLen > 0) {
                    sink(pending, safeLen)
                    pending = pending.copyOfRange(safeLen, pending.size)
                }
                if (!refill()) return false
            }
        }

        private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
            if (needle.isEmpty() || haystack.size < needle.size) return -1
            outer@ for (i in 0..haystack.size - needle.size) {
                for (j in needle.indices) {
                    if (haystack[i + j] != needle[j]) continue@outer
                }
                return i
            }
            return -1
        }
    }

    private fun sanitizeFileName(name: String): String {
        // File(name).name se queda solo con el último segmento — tira cualquier "../"
        // o ruta absoluta que un cliente malicioso mande en el nombre del archivo.
        val base = File(name.replace('\\', '/')).name.trim()
        val cleaned = base.ifBlank { "archivo_${System.currentTimeMillis()}" }
            .replace(Regex("[\\x00-\\x1F]"), "_")
        return if (cleaned == "." || cleaned == "..") "archivo_${System.currentTimeMillis()}" else cleaned
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
            "Content-Security-Policy: default-src 'self'; style-src 'unsafe-inline'; img-src 'self' data:; script-src 'none'; frame-ancestors 'none'\r\n"
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

    /**
     * URL compartible de un archivo/carpeta dentro de [nubeRoot] — mismo mecanismo que ya usa
     * `NubeFragment.refreshServerStatus()`/`pollTunnelStatus()` (IP local + puerto + [token]),
     * pero resuelto acá para que el gateway (`cloud.share_link`, ver `GatewayAppBridge.kt`)
     * pueda construirla sin duplicar esa lógica. `relativePath` pasa por [resolveSafePath] —
     * las mismas reglas de path-traversal que el resto del servidor, nunca una ruta fuera de
     * `nubeRoot`. Null si el servidor no corre, si la ruta no existe, o si se sale de la carpeta.
     * Un archivo apunta a `/download` (descarga directa); una carpeta (o la raíz, `relativePath`
     * vacío) apunta a `/` con ese `path` — el mismo listado que ve quien abre el enlace a mano.
     */
    fun shareLinkFor(relativePath: String): String? {
        if (!isRunning) return null
        val file = resolveSafePath(relativePath) ?: return null
        if (!file.exists()) return null
        val ip = localIpAddress() ?: "127.0.0.1"
        val rel = relPathOf(file)
        // Sin "path=" cuando apunta a la raíz — mismo formato exacto que ya muestra
        // NubeFragment.refreshServerStatus() para el link local ("http://ip:puerto/?token=...",
        // sin un "path=" vacío colgando).
        return if (file.isDirectory) {
            if (rel.isEmpty()) "http://$ip:$port/?token=$token" else "http://$ip:$port/?path=${urlEncode(rel)}&token=$token"
        } else {
            "http://$ip:$port/download?path=${urlEncode(rel)}&token=$token"
        }
    }

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

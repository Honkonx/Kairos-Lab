package com.termux.app.ui.studio.search

import androidx.documentfile.provider.DocumentFile
import com.termux.app.ui.studio.filetree.FileTreeLoader
import java.io.InputStream

/**
 * Recorrido + búsqueda de texto sobre todo el árbol del proyecto abierto. Reusa
 * [FileTreeLoader.walkFilesBlocking] para enumerar el árbol (misma lógica de recorrido que ya
 * usa el sidebar, no una copia) y le agrega lectura línea por línea de cada archivo de texto.
 *
 * Corre por completo en el hilo que la llama — [ProjectSearchActivity] es responsable de
 * lanzarla en un `Thread` aparte y de volver al hilo principal para actualizar la UI.
 * [openInputStream] se recibe como parámetro (en vez de que esta clase dependa de un `Context`)
 * porque `DocumentFile` no expone un stream directamente — solo `ContentResolver.openInputStream`
 * puede abrir el `content://` de cada archivo.
 *
 * LIMITACIÓN CONOCIDA: no se probó el rendimiento en un proyecto grande (miles de archivos) en
 * un dispositivo real — los límites de abajo ([MAX_RESULTS], [MAX_FILE_SIZE_BYTES]) son un tope
 * de seguridad razonable, no un número medido contra un caso real.
 */
object ProjectSearchEngine {

    private const val MAX_RESULTS = 500
    private const val MAX_FILE_SIZE_BYTES = 2L * 1024 * 1024 // 2 MB — evita leer binarios grandes por error

    private val likelyBinaryExtensions = setOf(
        "png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "svg",
        "zip", "jar", "aar", "apk", "gz", "tar", "7z",
        "so", "dex", "class", "ttf", "otf", "woff", "woff2",
        "mp3", "mp4", "wav", "ogg", "webm", "pdf"
    )

    /**
     * [onResult] se invoca (todavía en el hilo de fondo, no en el principal) por cada
     * coincidencia hasta llegar a [MAX_RESULTS]. [isCancelled] se consulta entre archivo y
     * archivo para poder abortar temprano si el usuario cierra la pantalla o lanza una búsqueda
     * nueva. Devuelve la cantidad total de coincidencias encontradas.
     */
    fun searchBlocking(
        root: DocumentFile,
        query: String,
        openInputStream: (android.net.Uri) -> InputStream?,
        isCancelled: () -> Boolean,
        onResult: (ProjectSearchResult) -> Unit
    ): Int {
        if (query.isBlank()) return 0
        var resultCount = 0
        FileTreeLoader.walkFilesBlocking(root) { file, relativePath ->
            if (isCancelled() || resultCount >= MAX_RESULTS) return@walkFilesBlocking
            if (!isLikelyTextFile(file, relativePath)) return@walkFilesBlocking

            searchInFile(file, relativePath, query, openInputStream) { lineNumber, lineText ->
                if (resultCount >= MAX_RESULTS) return@searchInFile
                resultCount++
                onResult(
                    ProjectSearchResult(
                        uri = file.uri,
                        fileName = file.name ?: relativePath,
                        relativePath = relativePath,
                        lineNumber = lineNumber,
                        lineText = lineText.trim().take(200)
                    )
                )
            }
        }
        return resultCount
    }

    private fun isLikelyTextFile(file: DocumentFile, relativePath: String): Boolean {
        val extension = relativePath.substringAfterLast('.', "").lowercase()
        if (extension in likelyBinaryExtensions) return false
        val size = file.length()
        return size in 0..MAX_FILE_SIZE_BYTES
    }

    private inline fun searchInFile(
        file: DocumentFile,
        relativePath: String,
        query: String,
        openInputStream: (android.net.Uri) -> InputStream?,
        onMatch: (Int, String) -> Unit
    ) {
        try {
            openInputStream(file.uri)?.use { stream ->
                stream.bufferedReader().useLines { lines ->
                    var lineNumber = 0
                    for (line in lines) {
                        lineNumber++
                        if (line.contains(query, ignoreCase = true)) {
                            onMatch(lineNumber, line)
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // Archivo no legible como texto (binario disfrazado de extensión de texto, encoding
            // raro, permiso revocado a mitad de recorrido) — se salta, no aborta el resto de la
            // búsqueda.
        }
    }
}

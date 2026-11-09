package com.termux.app.util

import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.sevenz.SevenZMethod
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.archivers.zip.Zip64Mode
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorOutputStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.util.Enumeration
import java.util.Locale
import java.util.UUID

/** Formats exposed by the APK file manager. tar.zst keeps multiple files together while using
 *  the Zstandard stream format; Zstandard by itself is a stream compressor, not an archive. */
enum class ArchiveFormat(val suffix: String) {
    ZIP(".zip"), SEVEN_Z(".7z"), TAR_ZSTD(".tar.zst")
}

enum class ArchiveKind { ZIP, SEVEN_Z, TAR_ZSTD }

data class ArchiveEntryInfo(val path: String, val size: Long, val directory: Boolean)

/** APK-native archive operations for the file manager. Supports only regular files and folders;
 *  extraction rejects path traversal, links, duplicate names and overwrites. */
object ArchiveManager {
    private const val MAX_ENTRIES = 100_000
    private const val BUFFER_SIZE = 64 * 1024

    // Auditoría 2026-10-07: la extracción no tenía tope de tamaño
    // descomprimido ni chequeo de espacio — un zip-bomb (pocos KB que expanden a TB) podía
    // llenar el almacenamiento. El tamaño declarado en el índice del archivo se puede falsear,
    // así que además de validarlo se cuenta lo que realmente se escribe (ver [ExtractBudget]).
    internal const val MAX_EXTRACTED_BYTES = 8L * 1024 * 1024 * 1024

    /** Bytes que todavía se permite escribir durante UNA extracción. */
    internal class ExtractBudget(var remaining: Long)

    internal fun copyBounded(input: java.io.InputStream, output: java.io.OutputStream, budget: ExtractBudget) {
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            budget.remaining -= read
            if (budget.remaining < 0) throw IOException("El contenido descomprimido supera el límite permitido")
            output.write(buffer, 0, read)
        }
    }

    fun kindFor(file: File): ArchiveKind? {
        val name = file.name.lowercase(Locale.ROOT)
        return when {
            name.endsWith(".tar.zst") || name.endsWith(".tzst") -> ArchiveKind.TAR_ZSTD
            name.endsWith(".zip") -> ArchiveKind.ZIP
            name.endsWith(".7z") -> ArchiveKind.SEVEN_Z
            else -> null
        }
    }

    /** Normalizes a portable archive path and rejects absolute paths and parent traversal. */
    internal fun safeRelativePath(rawName: String): String {
        if (rawName.isBlank() || rawName.indexOf('\u0000') >= 0) throw IOException("Ruta vacía o inválida")
        val portable = rawName.replace('\\', '/')
        if (portable.startsWith('/') || Regex("^[A-Za-z]:").containsMatchIn(portable)) {
            throw IOException("El archivo contiene una ruta absoluta")
        }
        val parts = portable.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty() || parts.any { it == ".." }) throw IOException("El archivo intenta salir de la carpeta destino")
        return parts.joinToString(File.separator)
    }

    fun list(file: File): List<ArchiveEntryInfo> {
        val kind = kindFor(file) ?: throw IOException("Formato de archivo no compatible")
        if (!file.isFile) throw IOException("El archivo no existe")
        val entries = when (kind) {
            ArchiveKind.ZIP -> listZip(file)
            ArchiveKind.SEVEN_Z -> listSevenZ(file)
            ArchiveKind.TAR_ZSTD -> listTarZstd(file)
        }
        validateEntries(entries)
        return entries
    }

    /** Writes to a temporary sibling first, so an interrupted compression never leaves a
     *  seemingly complete archive at the selected output path. */
    fun compress(files: List<File>, output: File, format: ArchiveFormat) {
        if (files.isEmpty()) throw IOException("No hay archivos seleccionados")
        if (output.exists()) throw IOException("Ya existe un archivo con ese nombre")
        val parent = output.parentFile ?: throw IOException("No se pudo determinar la carpeta destino")
        if (!parent.isDirectory) throw IOException("La carpeta destino ya no existe")
        val partial = File(parent, ".${output.name}.${UUID.randomUUID()}.partial")
        try {
            when (format) {
                ArchiveFormat.ZIP -> compressZip(files, partial)
                ArchiveFormat.SEVEN_Z -> compressSevenZ(files, partial)
                ArchiveFormat.TAR_ZSTD -> compressTarZstd(files, partial)
            }
            if (output.exists() || !partial.renameTo(output)) throw IOException("No se pudo guardar el archivo comprimido")
        } catch (e: Exception) {
            partial.delete()
            throw e
        }
    }

    /** Extracts into a temporary child directory, then moves the completed top-level items into
     *  [destination]. Existing files are never overwritten. */
    fun extractAll(archive: File, destination: File) {
        val kind = kindFor(archive) ?: throw IOException("Formato de archivo no compatible")
        val entries = list(archive)
        if (entries.isEmpty()) throw IOException("El archivo no contiene elementos")
        if (!destination.isDirectory) throw IOException("La carpeta destino no existe")

        val root = destination.canonicalFile
        val declaredTotal = entries.sumOf { it.size }
        if (declaredTotal > MAX_EXTRACTED_BYTES) throw IOException("El contenido descomprimido supera el límite permitido")
        if (declaredTotal > root.usableSpace) throw IOException("No hay espacio suficiente en la carpeta destino")
        val budget = ExtractBudget(minOf(MAX_EXTRACTED_BYTES, root.usableSpace))
        val normalized = entries.map { it to safeRelativePath(it.path) }
        val normalizedNames = normalized.map { it.second.replace(File.separatorChar, '/') }
        if (normalizedNames.toSet().size != normalizedNames.size) throw IOException("El archivo contiene nombres duplicados")
        val entryTypes = normalizedNames.zip(entries).associate { it.first to it.second.directory }
        normalized.forEach { (_, relative) ->
            val target = File(root, relative).canonicalFile
            if (!target.path.startsWith(root.path + File.separator)) throw IOException("Ruta de extracción insegura")
            if (target.exists() && !(entryTypes[relative.replace(File.separatorChar, '/')] == true && target.isDirectory)) {
                throw IOException("Ya existe ${target.name} en la carpeta destino")
            }
        }

        val staging = File(root, ".kairos-extraer-${UUID.randomUUID()}")
        if (!staging.mkdir()) throw IOException("No se pudo preparar la extracción")
        try {
            when (kind) {
                ArchiveKind.ZIP -> extractZip(archive, staging, budget)
                ArchiveKind.SEVEN_Z -> extractSevenZ(archive, staging, budget)
                ArchiveKind.TAR_ZSTD -> extractTarZstd(archive, staging, budget)
            }
            val children = staging.listFiles() ?: throw IOException("No se pudo leer la extracción temporal")
            val moved = mutableListOf<File>()
            for (child in children) {
                val target = File(root, child.name)
                if (target.exists() || !child.renameTo(target)) {
                    moved.asReversed().forEach { committed ->
                        val staged = File(staging, committed.name)
                        if (!committed.renameTo(staged)) committed.deleteRecursively()
                    }
                    throw IOException("No se pudo colocar ${child.name}")
                }
                moved.add(target)
            }
            staging.delete()
        } catch (e: Exception) {
            staging.deleteRecursively()
            throw e
        }
    }

    private fun listZip(file: File): List<ArchiveEntryInfo> = ZipFile(file).use { zip ->
        val result = mutableListOf<ArchiveEntryInfo>()
        val entries: Enumeration<ZipArchiveEntry> = zip.entries
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            if (entry.isUnixSymlink) throw IOException("No se extraen enlaces simbólicos")
            result.add(ArchiveEntryInfo(entry.name, entry.size.coerceAtLeast(0L), entry.isDirectory))
            if (result.size > MAX_ENTRIES) throw IOException("El archivo tiene demasiados elementos")
        }
        result
    }

    private fun listSevenZ(file: File): List<ArchiveEntryInfo> = SevenZFile(file).use { seven ->
        val result = mutableListOf<ArchiveEntryInfo>()
        for (entry in seven.entries) {
            if (entry.isAntiItem) throw IOException("El archivo contiene elementos especiales no compatibles")
            result.add(ArchiveEntryInfo(entry.name, entry.size.coerceAtLeast(0L), entry.isDirectory))
            if (result.size > MAX_ENTRIES) throw IOException("El archivo tiene demasiados elementos")
        }
        result
    }

    private fun listTarZstd(file: File): List<ArchiveEntryInfo> = openTarZstd(file).use { tar ->
        val result = mutableListOf<ArchiveEntryInfo>()
        while (true) {
            val entry = tar.nextTarEntry ?: break
            if (entry.isSymbolicLink || entry.isLink || entry.isBlockDevice || entry.isCharacterDevice || entry.isFIFO) {
                throw IOException("No se extraen enlaces ni archivos especiales")
            }
            result.add(ArchiveEntryInfo(entry.name, entry.size.coerceAtLeast(0L), entry.isDirectory))
            if (result.size > MAX_ENTRIES) throw IOException("El archivo tiene demasiados elementos")
        }
        result
    }

    private fun validateEntries(entries: List<ArchiveEntryInfo>) {
        val seen = HashSet<String>()
        val types = HashMap<String, Boolean>()
        entries.forEach { entry ->
            val path = safeRelativePath(entry.path).replace(File.separatorChar, '/')
            if (!seen.add(path)) throw IOException("El archivo contiene nombres duplicados")
            types[path] = entry.directory
        }
        types.forEach { (path, directory) ->
            val parent = path.substringBeforeLast('/', "")
            if (parent.isNotEmpty() && types[parent] == false) throw IOException("Un archivo del archivo comprimido contiene otros elementos")
            if (!directory && types.keys.any { it.startsWith("$path/") }) throw IOException("Un archivo del archivo comprimido contiene otros elementos")
        }
    }

    private fun compressZip(files: List<File>, output: File) {
        ZipArchiveOutputStream(BufferedOutputStream(FileOutputStream(output))).use { zip ->
            zip.setUseZip64(Zip64Mode.AsNeeded)
            files.forEach { addZipFile(zip, it, it.name) }
        }
    }

    private fun addZipFile(zip: ZipArchiveOutputStream, file: File, path: String) {
        ensureRegularSource(file)
        val entry = ZipArchiveEntry(file, path)
        zip.putArchiveEntry(entry)
        if (file.isFile) FileInputStream(file).use { it.copyTo(zip, BUFFER_SIZE) }
        zip.closeArchiveEntry()
        if (file.isDirectory) file.listFiles()?.sortedBy { it.name }?.forEach { addZipFile(zip, it, "$path/${it.name}") }
    }

    private fun compressSevenZ(files: List<File>, output: File) {
        SevenZOutputFile(output).use { seven ->
            seven.setContentCompression(SevenZMethod.LZMA2)
            files.forEach { addSevenZFile(seven, it, it.name) }
        }
    }

    private fun addSevenZFile(seven: SevenZOutputFile, file: File, path: String) {
        ensureRegularSource(file)
        val entry = seven.createArchiveEntry(file, path)
        seven.putArchiveEntry(entry)
        if (file.isFile) FileInputStream(file).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                seven.write(buffer, 0, read)
            }
        }
        seven.closeArchiveEntry()
        if (file.isDirectory) file.listFiles()?.sortedBy { it.name }?.forEach { addSevenZFile(seven, it, "$path/${it.name}") }
    }

    private fun compressTarZstd(files: List<File>, output: File) {
        val zstd = ZstdCompressorOutputStream(BufferedOutputStream(FileOutputStream(output)))
        TarArchiveOutputStream(zstd).use { tar ->
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)
            files.forEach { addTarFile(tar, it, it.name) }
        }
    }

    private fun addTarFile(tar: TarArchiveOutputStream, file: File, path: String) {
        ensureRegularSource(file)
        val entry = TarArchiveEntry(file, path)
        tar.putArchiveEntry(entry)
        if (file.isFile) FileInputStream(file).use { it.copyTo(tar, BUFFER_SIZE) }
        tar.closeArchiveEntry()
        if (file.isDirectory) file.listFiles()?.sortedBy { it.name }?.forEach { addTarFile(tar, it, "$path/${it.name}") }
    }

    private fun ensureRegularSource(file: File) {
        if (!file.exists()) throw IOException("El elemento ya no existe: ${file.name}")
        if (!file.isFile && !file.isDirectory) throw IOException("Solo se comprimen archivos y carpetas")
        if (Files.isSymbolicLink(file.toPath())) throw IOException("No se comprimen enlaces simbólicos")
    }

    private fun extractZip(archive: File, staging: File, budget: ExtractBudget) {
        ZipFile(archive).use { zip ->
            val entries = zip.entries
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isUnixSymlink) throw IOException("No se extraen enlaces simbólicos")
                val out = safeTarget(staging, entry.name, entry.isDirectory)
                if (entry.isDirectory) out.mkdirs() else {
                    out.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input -> FileOutputStream(out).use { copyBounded(input, it, budget) } }
                }
            }
        }
    }

    private fun extractSevenZ(archive: File, staging: File, budget: ExtractBudget) {
        SevenZFile(archive).use { seven ->
            for (entry in seven.entries) {
                if (entry.isAntiItem) throw IOException("El archivo contiene elementos especiales no compatibles")
                val out = safeTarget(staging, entry.name, entry.isDirectory)
                if (entry.isDirectory) out.mkdirs() else {
                    out.parentFile?.mkdirs()
                    seven.getInputStream(entry).use { input -> FileOutputStream(out).use { copyBounded(input, it, budget) } }
                }
            }
        }
    }

    private fun extractTarZstd(archive: File, staging: File, budget: ExtractBudget) {
        openTarZstd(archive).use { tar ->
            while (true) {
                val entry = tar.nextTarEntry ?: break
                if (entry.isSymbolicLink || entry.isLink || entry.isBlockDevice || entry.isCharacterDevice || entry.isFIFO) {
                    throw IOException("No se extraen enlaces ni archivos especiales")
                }
                val out = safeTarget(staging, entry.name, entry.isDirectory)
                if (entry.isDirectory) out.mkdirs() else {
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { copyBounded(tar, it, budget) }
                }
            }
        }
    }

    private fun openTarZstd(file: File): TarArchiveInputStream = TarArchiveInputStream(
        ZstdCompressorInputStream(BufferedInputStream(FileInputStream(file)))
    )

    private fun safeTarget(root: File, rawName: String, directory: Boolean): File {
        val relative = safeRelativePath(rawName)
        val canonicalRoot = root.canonicalFile
        val target = File(canonicalRoot, relative).canonicalFile
        if (!target.path.startsWith(canonicalRoot.path + File.separator)) throw IOException("Ruta de extracción insegura")
        if (target.exists() && !(directory && target.isDirectory)) throw IOException("El archivo contiene nombres duplicados")
        return target
    }
}

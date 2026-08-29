package com.termux.app.util

import android.content.Context
import android.net.Uri
import java.io.File
import java.security.MessageDigest

/**
 * Gestión de modelos GGUF para el motor de inferencia local embebido
 * (llama-engine, ver docs/ia-local/LLAMA_CPP_EMBEBIDO.md) — descarga, listado y
 * borrado. Independiente de Termux: los modelos viven en el storage
 * privado de la app (`context.filesDir/models/`), no en `$HOME`.
 *
 * Mismo patrón de descarga+progreso que RootfsInstaller.kt (bytes leídos
 * vs. Content-Length, sin checksum obligatorio porque no hay un valor
 * publicado de antemano para una URL de modelo arbitraria del usuario).
 */
object LocalModelManager {

    /** Primeros 4 bytes de cualquier .gguf válido — ASCII literal "GGUF" (ver formato GGUF de ggml-org/llama.cpp). */
    private val GGUF_MAGIC_BYTES = byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte())

    /** Umbral de descarga completa vs. Content-Length declarado — no exacto porque algunos servidores no mandan el header exacto (mismo criterio documentado en model_controller.dart de PrivateLM, ver docs/referencias/REFERENCIA_CROSS_PLATFORM_LLM_CLIENT.md). */
    private const val MIN_DOWNLOAD_RATIO = 0.95

    /**
     * Margen de seguridad sobre el tamaño esperado para el chequeo de espacio libre — 15% extra
     * (auditoría docs/ia-local/AUDITORIA_LLAMA_CPP_2026-08-26.md, hallazgo: downloadModel() no
     * chequeaba espacio libre antes de arrancar, dejaba que un .gguf de varios GB se cortara a
     * mitad de camino por ENOSPC en vez de avisar antes). No es solo el archivo final — hay que
     * dejar margen para el propio filesystem/journal y para que el resto de la app siga andando.
     */
    private const val SPACE_SAFETY_MARGIN = 1.15

    data class LocalModel(
        val file: File,
        val name: String,
        val sizeBytes: Long,
    )

    @JvmStatic
    fun modelsDir(context: Context): File =
        File(context.filesDir, "models").apply { mkdirs() }

    /**
     * Borra archivos `.part` huérfanos (de descargas anteriores canceladas o
     * que la app mató a mitad de camino) — antes quedaban tirados para
     * siempre en `modelsDir()` sin que nada los limpiara. Se llama al abrir
     * LocalAIFragment y al arrancar cada descarga nueva. Devuelve cuántos se
     * borraron (solo para logging/debug, no se muestra al usuario).
     *
     * `exclude` (opcional, ronda 2026-08-14): permite saltar un `.part` puntual — el de la
     * descarga en curso, que con soporte de resume (header `Range`, ver downloadModel) es
     * justamente el que NO hay que borrar para poder retomar.
     */
    @JvmStatic
    fun cleanupOrphanedPartFiles(context: Context, exclude: File? = null): Int {
        val partFiles = modelsDir(context).listFiles { f -> f.isFile && f.name.endsWith(".part") } ?: return 0
        return partFiles.count { it != exclude && it.delete() }
    }

    @JvmStatic
    fun listModels(context: Context): List<LocalModel> =
        modelsDir(context).listFiles { f -> f.isFile && f.extension.equals("gguf", ignoreCase = true) }
            ?.sortedByDescending { it.lastModified() }
            ?.map { LocalModel(it, it.name, it.length()) }
            ?: emptyList()

    @JvmStatic
    fun deleteModel(context: Context, name: String): Boolean {
        val f = File(modelsDir(context), name)
        return f.exists() && f.delete()
    }

    /**
     * Descarga un modelo GGUF desde una URL directa (ej. un link de
     * Hugging Face a un archivo .gguf específico). Corre en el hilo que la
     * llama — el caller es responsable de correrlo en background.
     *
     * Ronda 2026-08-14 — resume por header `Range` (hallazgo #2 de
     * docs/referencias/AUDITORIA_CATEGORIA_IA.md, patrón de
     * Uncensored-Local-AI): antes una descarga cortada reiniciaba SIEMPRE de cero,
     * y para un .gguf de 5-8GB un corte de red era el peor caso de uso. Ahora:
     *  - Si hay un `.part` con bytes ya bajados, se manda `Range: bytes=N-` (N = tamaño
     *    actual del .part) en vez de re-descargar todo desde cero.
     *  - Si el servidor responde `206 Partial Content`, se APPEND al `.part` existente
     *    (FileOutputStream en modo append) y el contador de progreso arranca en N.
     *  - Si responde `200 OK` (servidor sin soporte de Range), se trunca el `.part` y se
     *    arranca de cero (comportamiento original).
     *
     * onProgress recibe un texto corto de estado (mismo criterio de UX
     * silenciosa del resto de la app — nunca output crudo).
     *
     * `estimatedSizeBytes` (opcional, ronda 2026-08-26 — ver
     * docs/ia-local/AUDITORIA_LLAMA_CPP_2026-08-26.md): tamaño esperado del archivo según el
     * catálogo curado (LocalAIFragment.CatalogModel.sizeLabel vía parseSizeLabel()), usado como
     * fallback para el chequeo de espacio libre SOLO cuando el servidor no manda un
     * Content-Length confiable (Content-Length real declarado por el servidor, si existe,
     * siempre tiene prioridad). "Agregar modelo por URL" no tiene esta info — queda en 0.
     */
    @JvmStatic
    fun downloadModel(
        context: Context,
        url: String,
        fileName: String,
        estimatedSizeBytes: Long = 0L,
        onProgress: (String) -> Unit,
    ): File {
        require(fileName.endsWith(".gguf", ignoreCase = true)) { "El archivo debe terminar en .gguf" }
        val dest = File(modelsDir(context), fileName)
        val tmp = File(modelsDir(context), "$fileName.part")

        // Bytes ya bajados en un intento previo — con resume son el offset de partida.
        val existingSize = if (tmp.exists()) tmp.length() else 0L
        // Se limpian los .part huérfanos EXCEPTO el de esta descarga (ver
        // cleanupOrphanedPartFiles): el nuestro puede retomarse, los otros son basura.
        cleanupOrphanedPartFiles(context, exclude = tmp)

        onProgress("Conectando…")
        var connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 15000
        connection.readTimeout = 30000
        if (existingSize > 0) {
            // Header estándar de resume HTTP — pedir solo los bytes que faltan.
            connection.setRequestProperty("Range", "bytes=$existingSize-")
        }
        connection.connect()
        var responseCode = connection.responseCode
        var resuming = responseCode == java.net.HttpURLConnection.HTTP_PARTIAL // 206
        var offset = existingSize

        // 416 Range Not Satisfiable: el .part viejo ya supera (o iguala) el tamaño actual
        // del archivo en el servidor — el modelo cambió de tamaño o se subió de nuevo. Se
        // descarta el .part y se reintenta UNA vez sin Range, como descarga completa.
        if (responseCode == 416 && offset > 0) { // HTTP_NOT_SATISFIABLE (416)
            connection.disconnect()
            tmp.delete()
            offset = 0
            connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 15000
            connection.readTimeout = 30000
            connection.connect()
            responseCode = connection.responseCode
            resuming = false
        }

        // Servidor sin soporte de Range (200 OK): el body es el archivo COMPLETO, así que el
        // .part viejo se trunca (FileOutputStream en overwrite) y se arranca de cero.
        if (!resuming && offset > 0) {
            tmp.delete()
            offset = 0
        }

        // Con 206 el Content-Length es SOLO la longitud del rango, no del archivo completo —
        // el total real es offset + rango (ver AUDITORIA_CATEGORIA_IA.md hallazgo #2).
        val declaredLength = connection.contentLengthLong
        val totalBytes = if (resuming && declaredLength > 0) offset + declaredLength else declaredLength

        // Chequeo de espacio libre real ANTES de escribir el primer byte — antes la descarga
        // arrancaba siempre y solo fallaba a mitad de camino (ENOSPC) sin ningún aviso claro
        // (ver docs/ia-local/AUDITORIA_LLAMA_CPP_2026-08-26.md). Mismo mecanismo StatFs que
        // MonitorFragment.kt (readStorageInfo) usa para el chequeo de almacenamiento del
        // dispositivo. Se usa el Content-Length real del servidor si vino; si no, el tamaño
        // estimado del catálogo curado como fallback — sin ninguno de los dos, no hay número
        // real que mostrar, así que se deja pasar con un aviso en vez de bloquear a ciegas.
        val expectedTotalBytes = if (totalBytes > 0) totalBytes else estimatedSizeBytes
        if (expectedTotalBytes > 0) {
            val remainingBytes = (expectedTotalBytes - offset).coerceAtLeast(0)
            val neededBytes = (remainingBytes * SPACE_SAFETY_MARGIN).toLong()
            val stat = android.os.StatFs(modelsDir(context).path)
            val freeBytes = stat.availableBlocksLong * stat.blockSizeLong
            if (freeBytes < neededBytes) {
                connection.disconnect()
                val neededGb = neededBytes / (1024.0 * 1024 * 1024)
                val freeGb = freeBytes / (1024.0 * 1024 * 1024)
                throw IllegalStateException(
                    "Espacio insuficiente para descargar $fileName: necesitás ~%.1f GB libres, tenés %.1f GB disponibles.".format(neededGb, freeGb)
                )
            }
        } else {
            onProgress("Aviso: no se pudo determinar el tamaño del modelo para chequear espacio libre — continuando de todas formas.")
        }

        // Velocidad/ETA real — patrón adoptado de MiceWine (AdapterRatPackageDownload/
        // RatDownloaderFragment, ver docs/referencias/REFERENCIA_MICEWINE.md "Pieza #1"): throttle de
        // 500ms para no recalcular en cada byte leído, delta de bytes entre updates / delta
        // de tiempo = velocidad instantánea. Antes esto solo mostraba "45%" sin ninguna
        // noción de cuánto faltaba en tiempo real.
        connection.inputStream.use { input ->
            // Append (FileOutputStream en modo true) solo en resume 206 — en cualquier otro
            // caso el archivo arranca vacío/truncado y el overwrite es el comportamiento
            // correcto. `downloaded` arranca en el offset para que el % siga siendo real.
            java.io.FileOutputStream(tmp, resuming).use { output ->
                val buffer = ByteArray(1 shl 16)
                var downloaded = offset
                var readBytes: Int
                var lastUpdateTime = System.currentTimeMillis()
                var lastBytesAtUpdate = offset
                while (input.read(buffer).also { readBytes = it } >= 0) {
                    output.write(buffer, 0, readBytes)
                    downloaded += readBytes
                    val now = System.currentTimeMillis()
                    if (now - lastUpdateTime >= 500) {
                        val deltaSeconds = (now - lastUpdateTime) / 1000.0
                        val speedBps = (downloaded - lastBytesAtUpdate) / deltaSeconds
                        lastUpdateTime = now
                        lastBytesAtUpdate = downloaded
                        onProgress(formatDownloadProgress(fileName, downloaded, totalBytes, speedBps))
                    }
                }
            }
        }

        // Validación multi-capa antes de aceptar la descarga como válida — antes
        // solo se chequeaba tmp.length() < 1024, lo que dejaba pasar descargas
        // cortadas a mitad de camino (ej. un .gguf de 2GB que se corta en el
        // primer 1KB) como si fueran archivos completos. Ver
        // docs/referencias/REFERENCIA_CROSS_PLATFORM_LLM_CLIENT.md (validación de
        // model_controller.dart de PrivateLM).
        if (totalBytes > 0) {
            val minAcceptable = (totalBytes * MIN_DOWNLOAD_RATIO).toLong()
            if (tmp.length() < minAcceptable) {
                val gotMb = tmp.length() / (1024 * 1024)
                val expectedMb = totalBytes / (1024 * 1024)
                tmp.delete()
                throw IllegalStateException(
                    "La descarga se cortó a mitad de camino ($gotMb MB de $expectedMb MB esperados) — probá de nuevo."
                )
            }
        } else if (tmp.length() < 1024) {
            // El servidor no mandó Content-Length — único caso donde el chequeo
            // de tamaño mínimo absoluto sigue siendo la única señal disponible.
            tmp.delete()
            throw IllegalStateException("Descarga demasiado chica — probablemente no es un .gguf válido o la URL falló")
        }

        if (!hasValidGgufMagic(tmp)) {
            tmp.delete()
            throw IllegalStateException(
                "El archivo descargado no es un .gguf válido (falta la cabecera GGUF) — la URL puede no apuntar a un modelo GGUF real."
            )
        }

        tmp.renameTo(dest)
        onProgress("Listo — ${dest.name}")
        return dest
    }

    /**
     * Importa un .gguf ya descargado por el usuario desde el almacenamiento del
     * dispositivo (selector SAF, `ACTION_OPEN_DOCUMENT` — mismo patrón que
     * `LocalPluginManager.install()`/`PluginsFragment.localPackagePicker`). Copia el
     * stream del `Uri` a `modelsDir()/fileName` y aplica la MISMA validación de magic
     * bytes que `downloadModel()` — un archivo elegido a mano puede no ser un GGUF
     * real igual que uno bajado por URL. Corre en el hilo que la llama — el caller es
     * responsable del background thread (mismo contrato que downloadModel).
     */
    @JvmStatic
    fun importFromUri(context: Context, uri: Uri, fileName: String): File {
        require(fileName.endsWith(".gguf", ignoreCase = true)) { "El archivo debe terminar en .gguf" }
        val dest = File(modelsDir(context), fileName)
        val tmp = File(modelsDir(context), "$fileName.part")

        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("No se pudo abrir el archivo elegido")
        input.use { stream ->
            java.io.FileOutputStream(tmp).use { output ->
                stream.copyTo(output)
            }
        }

        if (tmp.length() < 1024) {
            tmp.delete()
            throw IllegalStateException("Archivo demasiado chico — no parece un .gguf válido")
        }
        if (!hasValidGgufMagic(tmp)) {
            tmp.delete()
            throw IllegalStateException(
                "El archivo elegido no es un .gguf válido (falta la cabecera GGUF)."
            )
        }

        tmp.renameTo(dest)
        return dest
    }

    /**
     * Verifica el magic header GGUF real (primeros 4 bytes = ASCII "GGUF")
     * en vez de confiar solo en el tamaño del archivo — un archivo puede
     * tener el tamaño "correcto" y aun así no ser un GGUF válido (ej. una
     * página de error HTML de Hugging Face devuelta con HTTP 200 y un
     * Content-Length propio).
     */
    private fun hasValidGgufMagic(file: File): Boolean {
        if (file.length() < GGUF_MAGIC_BYTES.size) return false
        val header = ByteArray(GGUF_MAGIC_BYTES.size)
        val read = file.inputStream().use { it.read(header) }
        return read == header.size && header.contentEquals(GGUF_MAGIC_BYTES)
    }

    /**
     * "~491 MB" / "~1.06 GB" (sizeLabel del catálogo curado de LocalAIFragment) → bytes.
     * Usado como fallback de estimatedSizeBytes en downloadModel() cuando el servidor no manda
     * Content-Length. Devuelve 0 si no puede parsear (mismo criterio "no bloquear sin poder dar
     * un número real" que downloadModel aplica al chequeo de espacio libre).
     */
    @JvmStatic
    fun parseSizeLabel(label: String): Long {
        val cleaned = label.removePrefix("~").trim()
        val value = cleaned.takeWhile { it.isDigit() || it == '.' }.toDoubleOrNull() ?: return 0L
        return when {
            cleaned.contains("GB", ignoreCase = true) -> (value * 1024 * 1024 * 1024).toLong()
            cleaned.contains("MB", ignoreCase = true) -> (value * 1024 * 1024).toLong()
            cleaned.contains("KB", ignoreCase = true) -> (value * 1024).toLong()
            else -> value.toLong()
        }
    }

    /** Hash corto (primeros 8 bytes de SHA-256 del path) — útil solo como id estable para UI, no verificación de integridad. */
    @JvmStatic
    fun shortId(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(file.absolutePath.toByteArray())
        return digest.take(4).joinToString("") { "%02x".format(it) }
    }

    private fun formatDownloadProgress(fileName: String, downloaded: Long, total: Long, speedBps: Double): String {
        val pctInfo = if (total > 0) "${(downloaded * 100 / total).toInt()}% · " else ""
        val sizeInfo = if (total > 0) "${humanBytes(downloaded)}/${humanBytes(total)}" else humanBytes(downloaded)
        val speedInfo = if (speedBps > 0) " · ${humanBytes(speedBps.toLong())}/s" else ""
        val etaInfo = if (speedBps > 0 && total > 0) {
            " · ETA ${humanDuration(((total - downloaded) / speedBps).toInt())}"
        } else ""
        return "Descargando $fileName… $pctInfo$sizeInfo$speedInfo$etaInfo"
    }

    private fun humanBytes(bytes: Long): String {
        var b = bytes.toDouble()
        for (unit in listOf("B", "KB", "MB", "GB")) {
            if (b < 1024) return "%.1f%s".format(b, unit)
            b /= 1024
        }
        return "%.1fTB".format(b)
    }

    private fun humanDuration(seconds: Int): String {
        val s = seconds.coerceAtLeast(0)
        if (s < 60) return "${s}s"
        return "${s / 60}m ${s % 60}s"
    }
}

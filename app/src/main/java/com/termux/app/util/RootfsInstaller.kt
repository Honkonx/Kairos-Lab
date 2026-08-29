package com.termux.app.util

import android.content.Context
import com.termux.shared.termux.TermuxConstants
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Instala el rootfs embebido de Kairos — paquetes .deb REALES de Termux, pre-armados
 * por el workflow build-rootfs.yml (ver tools/rootfs/build_rootfs.py y
 * docs/bootstrap/ROOTFS_EMBEBIDO.md). Dos fuentes posibles, misma extracción/instalación:
 *
 *   1. "Embebido": si app/src/main/assets/kairos_rootfs.tar.xz existe (lo agrega
 *      build-app-rootfs.yml antes de compilar, descargándolo desde la Release de
 *      build-rootfs.yml), se copia desde los assets del propio APK — sin red.
 *   2. "Descarga": si no está embebido (variante liviana de build-app.yml), se baja
 *      desde la misma Release en tiempo de ejecución, verificando el .sha256
 *      publicado junto al tar.xz (evita hardcodear un checksum en el código fuente,
 *      que quedaría desactualizado cada vez que se re-arma el rootfs).
 *
 * El tar.xz contiene los .deb SIN extraer (ver build_rootfs.py) — este archivo los
 * desempaqueta con Apache Commons Compress + XZ for Java (100% JVM, sin invocar al
 * `tar` de Termux por ProcessBuilder — patrón tomado de ver/MiceWine-Application-master/,
 * que usa exactamente estas mismas librerías para su propio formato de paquete ".rat".
 * Progreso real en vivo, no un spinner ciego). Los .deb ya extraídos se INSTALAN con
 * `apt install` real (no copiando archivos a mano a $PREFIX) — acá sí se usa el `apt`
 * real de Termux vía ProcessBuilder, a propósito: es lo que hace que dpkg/apt en el
 * dispositivo registren esos paquetes como instalados de verdad, y que la comprobación
 * de actualizaciones (ver RootfsPackageChecker.kt) los vea igual que si el usuario
 * hubiera hecho `pkg install` a mano. Ver docs/bootstrap/ROOTFS_EMBEBIDO.md, sección "por qué no
 * copiar el enfoque de MiceWine tal cual" — sus paquetes .rat son autocontenidos y
 * nunca los toca ningún otro gestor de paquetes; los de Kairos sí, constantemente.
 *
 * Si esto falla por cualquier motivo (Release todavía no existe, sin red, un .deb
 * corrupto, etc.), el caller NO debe marcar los checkpoints de kairos.sh — el flujo
 * normal de `pkg install` sigue funcionando exactamente igual que hoy, sin cambios.
 * Esta clase es un atajo opcional, no un reemplazo obligatorio.
 */
object RootfsInstaller {

    // Guard anti-concurrencia — bug real confirmado por ADB (docs/humano269.md, auditoría
    // 2026-08-27): un wizard fresco en un dispositivo recién flasheado reportó "Rootfs no
    // disponible" con un error de dependencias sin sentido (svt-av1/xvidcore/oniguruma/
    // libresolv-wrapper "not installable" pese a estar TODOS presentes en el rootfs) — el log
    // real (wizard_debug.log) mostró DOS invocaciones completas de install() en ~3 segundos,
    // la segunda extrayendo solo 87 de 190 archivos. Causa raíz: extractTar() hace
    // `debsDir.deleteRecursively(); mkdirs()` al arrancar — si dos install() corren a la vez
    // (ej. el usuario navega hacia atrás y la Activity/Fragment se recrea mientras el Thread
    // de la instalación vieja seguía vivo, sin ningún hook de cancelación atado al ciclo de
    // vida) la segunda invocación borra el directorio que la primera está escribiendo a mitad
    // de camino, dejando una extracción a medias sin que ninguna de las dos lance una
    // excepción real. Este guard rechaza una segunda llamada mientras la primera sigue en
    // curso, en vez de dejar que ambas corran y se pisen.
    @Volatile private var installInProgress = false

    private const val ASSET_NAME = "kairos_rootfs.tar.xz"

    // Tag de la Release de build-rootfs.yml a consumir — se actualiza a mano cada vez
    // que se re-corre ese workflow con una lista de paquetes nueva. No existe todavía
    // ninguna Release real con este tag (pendiente de la primera corrida manual) —
    // hasta entonces, install() falla limpiamente y el caller cae al flujo normal.
    //
    // Apunta al futuro repo PÚBLICO kairos-lab (todavía no creado) a propósito — pedido
    // explícito del usuario (docs/humano267.md): ningún link de descarga debe apuntar al
    // repo privado kairos-dev, ni siquiera hoy que kairos-lab no existe aún, para que el
    // día que se cree y se suba la Release real esto funcione sin tocar código de nuevo.
    // A diferencia del repo privado (ver comentario de downloadWithChecksum() más abajo),
    // kairos-lab será PÚBLICO — esta descarga en runtime SÍ va a poder funcionar sin
    // ningún token una vez que exista la Release, no es una limitación permanente.
    private const val ROOTFS_RELEASE_TAG = "rootfs-2026.07.28"
    private const val ROOTFS_BASE_URL =
        "https://github.com/Honkonx/kairos-lab/releases/download/$ROOTFS_RELEASE_TAG"
    private const val ROOTFS_TAR_URL = "$ROOTFS_BASE_URL/kairos-rootfs-aarch64.tar.xz"
    private const val ROOTFS_SHA256_URL = "$ROOTFS_TAR_URL.sha256"

    // Los mismos nombres de checkpoint que modulos/kairos.sh usa en PASO 3/4/6 — si
    // el rootfs se extrajo bien, esos pasos quedan marcados "ya hechos" y kairos.sh
    // los salta solo con su propio mecanismo existente (check_done), sin que este
    // archivo necesite tocar el script en absoluto.
    private val KAIROS_CHECKPOINTS = listOf("core_pkgs", "build_pkgs", "media_util_pkgs")

    // Mensaje único para el 404 al descargar el rootfs desde kairos-lab — lo tiran los dos
    // pasos de downloadWithChecksum() (sha256 y tar.xz), ver docstring de ese método.
    // Caso esperado HOY (2026-08-27, ver docs/humano267.md): kairos-lab todavía no existe
    // como repo público, así que cualquier descarga acá da 404 — no por ser privado (ya no
    // apunta al repo privado kairos-dev a propósito), sino porque el repo/Release reales
    // todavía no se crearon. El día que kairos-lab exista y tenga la Release publicada, esta
    // descarga funciona sola, sin token (repo público real), sin tocar código de nuevo.
    private const val PRIVATE_REPO_ERROR_MESSAGE =
        "No se pudo descargar el rootfs: el repositorio kairos-lab todavía no existe (o no " +
            "tiene esta Release publicada todavía). Mientras tanto, el instalador sigue con " +
            "'pkg install' normal, sin rootfs embebido."

    private fun privateRepoError(cause: java.io.FileNotFoundException): IllegalStateException =
        IllegalStateException(PRIVATE_REPO_ERROR_MESSAGE, cause)

    @JvmStatic
    fun isEmbedded(context: Context): Boolean = try {
        context.assets.open(ASSET_NAME).use { true }
    } catch (_: Exception) {
        false
    }

    // @JvmStatic no es estrictamente necesario ahora que el caller (WizardInstallFragment.kt)
    // es Kotlin, pero se deja por si algún día vuelve a llamarse desde Java — sin esto, un
    // caller Java tendría que usar RootfsInstaller.INSTANCE.install(...) en vez de
    // RootfsInstaller.install(...) directo (mismo bug real ya encontrado antes en
    // ModuleController.kt, ver docs/viejo/KAIROS_APP_FIXES.md "parte 9").
    /** Corre en background. onProgress se llama en el hilo de fondo (el caller decide si postea a UI). */
    @JvmStatic
    fun install(context: Context, onProgress: (String) -> Unit, onDone: (Boolean, String) -> Unit) {
        synchronized(this) {
            if (installInProgress) {
                WizardDebugLog.log("RootfsInstaller", "install(): ya hay una instalación en curso, se ignora esta llamada duplicada")
                onDone(false, "Ya hay una instalación de rootfs en curso")
                return
            }
            installInProgress = true
        }
        Thread {
            var tarFile: File? = null
            var debsDir: File? = null
            try {
                WizardDebugLog.log("RootfsInstaller", "install(): isEmbedded=${isEmbedded(context)}")
                tarFile = if (isEmbedded(context)) {
                    onProgress("Copiando rootfs embebido…")
                    copyEmbeddedAsset(context)
                } else {
                    onProgress("Descargando rootfs…")
                    downloadWithChecksum(context, onProgress)
                }
                WizardDebugLog.log("RootfsInstaller", "install(): tar.xz listo en ${tarFile.absolutePath}")
                onProgress("Extrayendo paquetes…")
                debsDir = extractTar(context, tarFile, onProgress)
                WizardDebugLog.log("RootfsInstaller", "install(): extraído en ${debsDir.absolutePath}, ${debsDir.listFiles()?.size ?: 0} archivos")
                onProgress("Instalando paquetes… (puede tardar hasta 10 minutos)")
                installDebs(debsDir, onProgress)
                markKairosCheckpoints()
                WizardDebugLog.log("RootfsInstaller", "install(): OK, checkpoints marcados")
                onDone(true, "Rootfs instalado")
            } catch (e: Exception) {
                WizardDebugLog.logException("RootfsInstaller", e)
                onDone(false, e.message ?: "error desconocido")
            } finally {
                tarFile?.delete()
                debsDir?.deleteRecursively()
                installInProgress = false
            }
        }.start()
    }

    private fun copyEmbeddedAsset(context: Context): File {
        val out = File(context.cacheDir, ASSET_NAME)
        context.assets.open(ASSET_NAME).use { input ->
            out.outputStream().use { output -> input.copyTo(output) }
        }
        return out
    }

    // ESTADO REAL HOY (2026-08-27, ver docs/humano267.md) — no es un bug a arreglar acá:
    // kairos-lab (repo PÚBLICO de destino, ver ROOTFS_BASE_URL arriba) todavía no existe —
    // esta descarga en runtime va a fallar con 404 hasta que se cree el repo y se publique
    // la Release real. A diferencia del repo privado kairos-dev (que SIEMPRE habría fallado
    // por falta de autenticación — no se embebe ningún token en el APK, sería extraíble/
    // decompilable y abusable), kairos-lab será público desde el día que exista, así que esta
    // descarga va a funcionar sola para cualquier usuario real, sin token, apenas la Release
    // esté publicada — no hace falta tocar este código de nuevo llegado ese momento.
    // La variante "con rootfs embebido" (build-app-rootfs.yml) no depende de este método —
    // el .tar.xz ya viene adentro del APK. Ver docs/bootstrap/rootfs-embebido.md.
    private fun downloadWithChecksum(context: Context, onProgress: (String) -> Unit): File {
        val expectedSha256 = try {
            java.net.URL(ROOTFS_SHA256_URL).openStream()
                .bufferedReader().use { it.readText().trim().split(Regex("\\s+")).first() }
        } catch (e: java.io.FileNotFoundException) {
            throw privateRepoError(e)
        }

        val out = File(context.cacheDir, ASSET_NAME)
        val connection = java.net.URL(ROOTFS_TAR_URL).openConnection() as java.net.HttpURLConnection
        connection.instanceFollowRedirects = true
        val digest = MessageDigest.getInstance("SHA-256")

        try {
            val totalBytes = connection.contentLengthLong
            connection.inputStream.use { input ->
                out.outputStream().use { output ->
                    val buffer = ByteArray(1 shl 16)
                    var readBytes: Int
                    var downloaded = 0L
                    var lastReportedPct = -1
                    while (input.read(buffer).also { readBytes = it } >= 0) {
                        output.write(buffer, 0, readBytes)
                        digest.update(buffer, 0, readBytes)
                        downloaded += readBytes
                        if (totalBytes > 0) {
                            val pct = (downloaded * 100 / totalBytes).toInt()
                            if (pct != lastReportedPct) {
                                lastReportedPct = pct
                                onProgress("Descargando rootfs… $pct%")
                            }
                        }
                    }
                }
            }
        } catch (e: java.io.FileNotFoundException) {
            out.delete()
            throw privateRepoError(e)
        }

        val actualSha256 = digest.digest().joinToString("") { "%02x".format(it) }
        if (!actualSha256.equals(expectedSha256, ignoreCase = true)) {
            out.delete()
            throw IllegalStateException("Checksum inválido del rootfs descargado")
        }
        return out
    }

    /** Cuenta bytes leídos del .xz comprimido (no de la salida descomprimida, que no se
     * sabe de antemano) — suficiente para un % de progreso razonable, misma idea que
     * el untar() de MiceWine (ellos miden el .xz completo, no la salida). */
    private class CountingInputStream(
        private val delegate: FileInputStream,
        private val onBytesRead: (Long) -> Unit,
    ) : java.io.InputStream() {
        private var total = 0L
        override fun read(): Int {
            val b = delegate.read()
            if (b >= 0) { total++; onBytesRead(total) }
            return b
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = delegate.read(b, off, len)
            if (n > 0) { total += n; onBytesRead(total) }
            return n
        }
        override fun close() = delegate.close()
    }

    /**
     * Resuelve `entryName` (viene del tar.xz, en principio de fuente propia + checksum
     * verificado, pero defensa en profundidad barata — mismo criterio que
     * NubeServer.resolveSafePath() aplica para uploads de usuario) contra `baseDir` y
     * exige que el resultado canónico siga viviendo DENTRO de `baseDir`. Bloquea entradas
     * tipo "../../../data/data/com.termux/files/home/.ssh/authorized_keys" (zip-slip).
     */
    private fun resolveSafeEntryPath(baseDir: File, entryName: String): File {
        val target = File(baseDir, entryName)
        val canonicalTarget = target.canonicalFile
        val canonicalBase = baseDir.canonicalFile
        if (canonicalTarget.path != canonicalBase.path &&
            !canonicalTarget.path.startsWith(canonicalBase.path + File.separator)
        ) {
            throw java.io.IOException("Entrada del rootfs fuera del directorio esperado: $entryName")
        }
        return canonicalTarget
    }

    /** Extrae el tar.xz (contiene .deb sueltos, ver build_rootfs.py) a una carpeta
     * temporal — NO a $PREFIX directo, los .deb se instalan con apt real después.
     * 100% Java (Commons Compress + XZ for Java), sin invocar al `tar` de Termux. */
    private fun extractTar(context: Context, tarFile: File, onProgress: (String) -> Unit): File {
        val debsDir = File(context.cacheDir, "kairos_rootfs_debs").apply {
            deleteRecursively()
            mkdirs()
        }
        val totalCompressedBytes = tarFile.length().coerceAtLeast(1)
        var lastReportedPct = -1
        var extractedCount = 0

        val countingStream = CountingInputStream(FileInputStream(tarFile)) { bytesRead ->
            val pct = (bytesRead * 100 / totalCompressedBytes).toInt().coerceIn(0, 100)
            if (pct != lastReportedPct) {
                lastReportedPct = pct
                onProgress("Extrayendo paquetes… $pct%")
            }
        }

        BufferedInputStream(countingStream).use { buffered ->
            XZCompressorInputStream(buffered).use { xz ->
                TarArchiveInputStream(xz).use { tar ->
                    var entry = tar.nextEntry
                    while (entry != null) {
                        val outFile = resolveSafeEntryPath(debsDir, entry.name)
                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            FileOutputStream(outFile).use { out ->
                                tar.copyTo(out)
                            }
                            extractedCount++
                        }
                        entry = tar.nextEntry
                    }
                }
            }
        }

        if (extractedCount == 0) {
            throw IllegalStateException("El rootfs no contenía ningún archivo tras extraer")
        }
        return debsDir
    }

    // Timeout + --force-confdef/--force-confold — mismo patrón que RootfsPackageChecker.runApt()
    // (mismo binario `apt install -y`, mismo proyecto). Bug real (auditoría 2026-08-13):
    // installDebs() no tenía ninguna de las dos protecciones — un .deb del rootfs embebido
    // que disparara un prompt de conffile colgaba el proceso para siempre (stdin no
    // interactivo, sin timeout que lo mate), dejando el wizard en "instalando paquetes…"
    // indefinidamente.
    private const val INSTALL_DEBS_TIMEOUT_MS = 300_000L

    /** Instala los .deb reales con "apt install" — no con dpkg-deb/copiado a mano —
     * para que dpkg/apt los registren como instalados de verdad (ver docstring de la
     * clase: esto es lo que permite que "pkg list --upgradable" los detecte después).
     *
     * Bug real reportado (ver docs/humano/humano62.md): "Cannot run program 'apt': error=2,
     * No such file or directory" — pese a que este método ya llamaba `applyTermuxEnv()`
     * antes de este fix. Ahora usa la ruta absoluta de `apt` (`TERMUX_APT_PATH`) en vez de
     * confiar en la resolución por PATH, y reintenta una vez con una pausa corta antes de
     * rendirse — este paso corre justo después de que el bootstrap termina de extraerse, y
     * es plausible que el sistema de archivos/SELinux del dispositivo tarde un instante en
     * dejar el binario recién extraído ejecutable de verdad. `WizardDebugLog` deja registro
     * de cada intento para poder confirmar cuál era la causa real la próxima vez que falle. */
    // Antes esto corría `apt install` con los 190 .deb de una y esperaba el exit code
    // en silencio (process.inputStream.bufferedReader().readText() bloqueaba hasta el
    // final) — 5+ minutos sin ningún dato real para el wizard, reportado por el usuario
    // (docs/humano268.md: "dura mas de 5 minutos extrayendo [...] no dice nada para saber
    // por donde va"). apt/dpkg SÍ emite una línea "Unpacking <paquete>" por cada .deb a
    // medida que instala — se lee línea por línea en un hilo separado (sin esperar a que
    // el proceso termine) y se cuenta cuántas ya pasaron para reportar un % real.
    private fun installDebs(debsDir: File, onProgress: (String) -> Unit) {
        val debFiles = debsDir.listFiles { f -> f.extension == "deb" }?.map { it.absolutePath }
            ?: emptyList()
        if (debFiles.isEmpty()) {
            throw IllegalStateException("El rootfs no contenía ningún archivo .deb")
        }
        var lastError: Exception? = null
        for (attempt in 1..2) {
            try {
                WizardDebugLog.log("RootfsInstaller", "installDebs() intento $attempt/2 — apt install de ${debFiles.size} paquetes")
                val cmd = mutableListOf(
                    TERMUX_APT_PATH, "install", "-y", "--allow-downgrades",
                    "-o", "Dpkg::Options::=--force-confdef", "-o", "Dpkg::Options::=--force-confold"
                )
                cmd.addAll(debFiles)
                val pb = ProcessBuilder(cmd)
                pb.applyTermuxEnv()
                pb.redirectErrorStream(true)
                val process = pb.start()
                val outputBuilder = StringBuilder()
                var installedCount = 0
                val readerThread = Thread {
                    process.inputStream.bufferedReader().forEachLine { line ->
                        outputBuilder.append(line).append('\n')
                        // Solo "Unpacking " — dpkg emite "Preparing to unpack .../X.deb ..." Y
                        // "Unpacking X (versión) ..." como 2 líneas SEPARADAS por cada paquete
                        // (confirmado en vivo, ver docs/humano271.md): contar ambas duplicaba
                        // el conteo real, mostrando "99% (248/190)" — más instalado que el
                        // total de paquetes.
                        if (line.startsWith("Unpacking ")) {
                            installedCount++
                            val pct = (installedCount * 100 / debFiles.size).coerceIn(0, 99)
                            onProgress("Instalando paquetes… $pct% ($installedCount/${debFiles.size})")
                        }
                    }
                }
                readerThread.start()
                val finished = process.waitFor(INSTALL_DEBS_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
                if (!finished) {
                    process.destroyForcibly()
                    throw IllegalStateException("apt install no respondió (timeout) — posible prompt de conffile colgado")
                }
                readerThread.join(3000)
                val output = outputBuilder.toString()
                val exitCode = process.exitValue()
                if (exitCode != 0) {
                    throw IllegalStateException("apt install salió con código $exitCode: ${output.takeLast(300)}")
                }
                WizardDebugLog.log("RootfsInstaller", "installDebs() OK en el intento $attempt")
                return
            } catch (e: Exception) {
                lastError = e
                WizardDebugLog.logException("RootfsInstaller", e)
                if (attempt == 1) Thread.sleep(1500)
            }
        }
        throw lastError ?: IllegalStateException("apt install falló sin excepción capturada")
    }

    private fun markKairosCheckpoints() {
        val checkpointFile = File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".kairos_bootstrap_checkpoint")
        val existing = if (checkpointFile.exists()) checkpointFile.readLines().toMutableSet() else mutableSetOf()
        val newLines = KAIROS_CHECKPOINTS.filterNot { it in existing }
        if (newLines.isNotEmpty()) {
            checkpointFile.appendText(newLines.joinToString("") { "$it\n" })
        }
    }
}

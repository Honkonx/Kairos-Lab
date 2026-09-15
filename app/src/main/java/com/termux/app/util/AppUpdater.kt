package com.termux.app.util

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Auto-actualización de Kairos fuera de Play Store — puerto directo del mecanismo real y ya
 * en producción de `techjarves/Mobile-Harness` (`app/src/main/java/com/jarves/mh/update/
 * AppUpdater.kt`), ver auditoría completa línea por línea en
 * `docs/referencias/herramientas/AUDITORIA_MOBILE_HARNESS_2026-09-09.md`. `CLAUDE.md` fija
 * "Distribución: SOLO GitHub Releases" como decisión de arquitectura — sin este mecanismo cada
 * actualización dependía de que el usuario entrara a GitHub a mano.
 *
 * A diferencia del original (variantes `online`/`offline`, un artifact por variante), Kairos
 * solo tiene una variante de build hoy — el manifiesto usa una única clave `"default"` en vez de
 * un mapa de variantes; simplificación deliberada, no una limitación del formato (agregar una
 * variante nueva el día de mañana es un campo más, no un cambio de forma).
 *
 * Formato del manifiesto (`kairos-update.json`, publicado como asset fijo de cada Release —
 * mismo criterio que ya usa el rootfs embebido para `kairos_rootfs.tar.xz`, ver
 * `app/build.gradle` tarea `downloadRootfsAsset`):
 * ```json
 * {
 *   "versionCode": 128,
 *   "versionName": "0.1.1",
 *   "notes": "Texto libre con las novedades de esta versión.",
 *   "artifacts": {
 *     "default": { "url": "https://...", "sha256": "...", "sizeBytes": 123456789 }
 *   }
 * }
 * ```
 *
 * Las 3 verificaciones de seguridad, en el orden real en que se aplican antes de instalar:
 * 1. [download] — SHA-256 del archivo completo contra el manifiesto, ANTES de aceptarlo como
 *    el `.apk` final (se descarga a un `.part`, nunca se instala un archivo con hash inválido).
 * 2. [verifyApk] parte (b) — `versionCode` del archivo coincide con el manifiesto Y es mayor al
 *    instalado (`android:sharedUserId` de Kairos no cambia esto, pero si no se chequea acá,
 *    Android igual rechazaría un downgrade — se prefiere fallar temprano con un mensaje claro).
 * 3. [verifyApk] parte (c) — el certificado de firma del `.apk` descargado coincide EXACTAMENTE
 *    (como conjunto de hashes SHA-256, no un solo certificado asumido) con el de la app ya
 *    instalada. Esta es la verificación que hace que el mecanismo sea seguro incluso si el
 *    manifiesto o el CDN estuvieran comprometidos — Android mismo rechazaría instalar un `.apk`
 *    con firma distinta sobre una app existente, pero validarlo ANTES de llamar al instalador
 *    da un mensaje de error claro en vez de un fallo críptico del `PackageInstaller` del sistema.
 *
 * `AppInstaller.install()` (siguiente paso del flujo) es quien realmente entrega el `.apk` al
 * `PackageManager` — ese paso pide confirmación explícita del usuario (`setRequireUserAction`),
 * nunca se salta.
 */
object AppUpdater {

    data class UpdateArtifact(val url: String, val sha256: String, val sizeBytes: Long)

    data class UpdateManifest(
        val versionCode: Int,
        val versionName: String,
        val notes: String,
        val artifact: UpdateArtifact
    )

    sealed class CheckResult {
        data class UpdateAvailable(val manifest: UpdateManifest) : CheckResult()
        object UpToDate : CheckResult()
        data class Error(val message: String) : CheckResult()
    }

    data class DownloadResult(val ok: Boolean, val file: File? = null, val error: String? = null)

    data class VerifyResult(val ok: Boolean, val error: String? = null)

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val DOWNLOAD_READ_TIMEOUT_MS = 60_000
    private const val UPDATE_APK_NAME = "kairos-update.apk"

    /**
     * Resuelve la URL real del manifiesto a consultar. En builds RELEASE siempre devuelve
     * [com.termux.BuildConfig.APP_UPDATE_MANIFEST_URL] — la rama de abajo que lee el override
     * de debug queda dentro de un `if (com.termux.BuildConfig.DEBUG)` (constante en tiempo de
     * compilación), así que R8/ProGuard la elimina por completo del binario de release; no es
     * solo "la UI no la expone", el código en sí no queda compilado ahí (Fase 5, pedido
     * explícito: "nunca compilado en release"). Ver `AppUpdateDebugPrefs` (ConfigFragment.kt)
     * para dónde se guarda el override y `tools/serve-update-server.sh` para cómo se genera.
     */
    fun resolveManifestUrl(context: Context): String {
        if (com.termux.BuildConfig.DEBUG) {
            val override = context.getSharedPreferences("kairos_prefs", Context.MODE_PRIVATE)
                .getString(PREF_DEBUG_MANIFEST_URL, null)
            if (!override.isNullOrBlank()) return override
        }
        return com.termux.BuildConfig.APP_UPDATE_MANIFEST_URL
    }

    const val PREF_DEBUG_MANIFEST_URL = "pref_debug_update_manifest_url"
    const val PREF_LAST_UPDATE_CHECK_MS = "pref_last_update_check_ms"

    /** Chequeo periódico no intrusivo (Fase 4): 24h entre checks automáticos salvo `force=true`. */
    const val CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L

    fun shouldAutoCheck(context: Context): Boolean {
        val last = context.getSharedPreferences("kairos_prefs", Context.MODE_PRIVATE)
            .getLong(PREF_LAST_UPDATE_CHECK_MS, 0L)
        return System.currentTimeMillis() - last >= CHECK_INTERVAL_MS
    }

    private fun markChecked(context: Context) {
        context.getSharedPreferences("kairos_prefs", Context.MODE_PRIVATE)
            .edit().putLong(PREF_LAST_UPDATE_CHECK_MS, System.currentTimeMillis()).apply()
    }

    /**
     * GET al manifiesto + comparación de `versionCode`. Llamar SIEMPRE desde un background
     * thread (bloqueante, igual que el resto de Managers de este paquete — TunnelManager,
     * RemoteManager, etc. — no coroutines).
     */
    fun check(context: Context, manifestUrl: String = resolveManifestUrl(context)): CheckResult {
        if (!manifestUrl.startsWith("https://") && !(com.termux.BuildConfig.DEBUG && manifestUrl.startsWith("http://"))) {
            // http:// solo se tolera en builds DEBUG (para poder apuntar a
            // http://127.0.0.1:PUERTO/ del script de prueba local sin exponer un túnel HTTPS
            // real cada vez) — en release, exigir HTTPS es incondicional, sin excepción.
            return CheckResult.Error("El manifiesto debe servirse por HTTPS: $manifestUrl")
        }
        markChecked(context)
        val body = try {
            httpGetText(manifestUrl)
        } catch (e: Exception) {
            return CheckResult.Error(e.message ?: "No se pudo descargar el manifiesto")
        } ?: return CheckResult.Error("Manifiesto vacío o inexistente en $manifestUrl")

        val manifest = try {
            parseManifest(body)
        } catch (e: Exception) {
            return CheckResult.Error("Manifiesto inválido: ${e.message}")
        } ?: return CheckResult.Error("Manifiesto sin un artifact 'default' válido")

        if (!manifest.artifact.url.startsWith("https://") &&
            !(com.termux.BuildConfig.DEBUG && manifest.artifact.url.startsWith("http://"))
        ) {
            return CheckResult.Error("La URL del APK debe ser HTTPS: ${manifest.artifact.url}")
        }

        return if (manifest.versionCode > com.termux.BuildConfig.VERSION_CODE) {
            CheckResult.UpdateAvailable(manifest)
        } else {
            CheckResult.UpToDate
        }
    }

    private fun parseManifest(body: String): UpdateManifest? {
        val root = JSONObject(body)
        val artifacts = root.optJSONObject("artifacts") ?: return null
        val default = artifacts.optJSONObject("default") ?: return null
        val url = default.optString("url", "")
        val sha256 = default.optString("sha256", "")
        if (url.isBlank() || sha256.isBlank()) return null
        return UpdateManifest(
            versionCode = root.optInt("versionCode", -1),
            versionName = root.optString("versionName", "?"),
            notes = root.optString("notes", ""),
            artifact = UpdateArtifact(url, sha256.lowercase(), default.optLong("sizeBytes", -1))
        )
    }

    private fun httpGetText(url: String): String? {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.instanceFollowRedirects = true
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Descarga en streaming a un archivo `.part`, verifica SHA-256 contra [artifact] y SOLO
     * entonces lo renombra al nombre final — si el hash no coincide, el `.part` se borra y se
     * reporta error, nunca se deja un archivo con hash inválido en el destino final.
     * [onProgress] se llama en el mismo thread que [download] (no hace hop a UI thread — el
     * caller es responsable, mismo patrón que el resto de callbacks de este paquete).
     */
    fun download(
        context: Context,
        artifact: UpdateArtifact,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> }
    ): DownloadResult {
        val destDir = File(context.filesDir, "updates").apply { mkdirs() }
        val partFile = File(destDir, "$UPDATE_APK_NAME.part")
        val finalFile = File(destDir, UPDATE_APK_NAME)

        val connection = try {
            URL(artifact.url).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            return DownloadResult(false, error = e.message ?: "URL de descarga inválida")
        }
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = DOWNLOAD_READ_TIMEOUT_MS
        connection.instanceFollowRedirects = true

        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                return DownloadResult(false, error = "HTTP ${connection.responseCode} descargando el APK")
            }
            val total = if (artifact.sizeBytes > 0) artifact.sizeBytes else connection.contentLengthLong
            val digest = MessageDigest.getInstance("SHA-256")
            var downloaded = 0L
            connection.inputStream.use { input ->
                partFile.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        downloaded += read
                        onProgress(downloaded, total)
                    }
                }
            }
            val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
            if (!actualHash.equals(artifact.sha256, ignoreCase = true)) {
                partFile.delete()
                return DownloadResult(false, error = "SHA-256 no coincide (esperado ${artifact.sha256}, real $actualHash) — descarga descartada")
            }
            if (finalFile.exists()) finalFile.delete()
            if (!partFile.renameTo(finalFile)) {
                return DownloadResult(false, error = "No se pudo mover el archivo descargado a su destino final")
            }
            return DownloadResult(true, file = finalFile)
        } catch (e: IOException) {
            partFile.delete()
            return DownloadResult(false, error = e.message ?: "Error de red durante la descarga")
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Las 3 verificaciones de seguridad antes de instalar (ver KDoc de la clase para el orden
     * completo del flujo): mismo `packageName`, `versionCode` del archivo == manifiesto Y mayor
     * al instalado, y certificado de firma idéntico (como CONJUNTO de hashes SHA-256, no un solo
     * certificado — un APK puede tener más de un firmante).
     */
    fun verifyApk(context: Context, apkFile: File, manifest: UpdateManifest): VerifyResult {
        val pm = context.packageManager
        val archiveInfo = try {
            getArchivePackageInfo(pm, apkFile.absolutePath)
        } catch (e: Exception) {
            return VerifyResult(false, "No se pudo leer el APK descargado: ${e.message}")
        } ?: return VerifyResult(false, "El archivo descargado no es un APK válido")

        if (archiveInfo.packageName != context.packageName) {
            return VerifyResult(false, "packageName no coincide (${archiveInfo.packageName} != ${context.packageName}) — no es una actualización de Kairos")
        }

        val archiveVersionCode = longVersionCodeOf(archiveInfo)
        if (archiveVersionCode.toInt() != manifest.versionCode) {
            return VerifyResult(false, "versionCode del APK ($archiveVersionCode) no coincide con el del manifiesto (${manifest.versionCode})")
        }
        if (archiveVersionCode <= com.termux.BuildConfig.VERSION_CODE) {
            return VerifyResult(false, "El APK descargado (versionCode $archiveVersionCode) no es más nuevo que el instalado (${com.termux.BuildConfig.VERSION_CODE})")
        }

        val archiveSignatures = try {
            signaturesSha256(signaturesOfArchive(archiveInfo))
        } catch (e: Exception) {
            return VerifyResult(false, "No se pudo leer la firma del APK descargado: ${e.message}")
        }
        val installedSignatures = try {
            signaturesSha256(signaturesOfInstalledApp(pm, context.packageName))
        } catch (e: Exception) {
            return VerifyResult(false, "No se pudo leer la firma de la app instalada: ${e.message}")
        }
        if (archiveSignatures.isEmpty() || archiveSignatures != installedSignatures) {
            return VerifyResult(false, "La firma del APK descargado no coincide con la de la app instalada — se rechaza por seguridad")
        }

        return VerifyResult(true)
    }

    @Suppress("DEPRECATION")
    private fun getArchivePackageInfo(pm: PackageManager, apkPath: String): android.content.pm.PackageInfo? {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        return pm.getPackageArchiveInfo(apkPath, flags)
    }

    @Suppress("DEPRECATION")
    private fun longVersionCodeOf(info: android.content.pm.PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()

    @Suppress("DEPRECATION")
    private fun signaturesOfArchive(info: android.content.pm.PackageInfo): Array<Signature> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = info.signingInfo
            if (signingInfo != null && signingInfo.hasMultipleSigners()) {
                signingInfo.apkContentsSigners
            } else {
                signingInfo?.signingCertificateHistory ?: emptyArray()
            }
        } else {
            info.signatures ?: emptyArray()
        }
    }

    @Suppress("DEPRECATION")
    private fun signaturesOfInstalledApp(pm: PackageManager, packageName: String): Array<Signature> {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        val info = pm.getPackageInfo(packageName, flags)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = info.signingInfo
            if (signingInfo != null && signingInfo.hasMultipleSigners()) {
                signingInfo.apkContentsSigners
            } else {
                signingInfo?.signingCertificateHistory ?: emptyArray()
            }
        } else {
            info.signatures ?: emptyArray()
        }
    }

    private fun signaturesSha256(signatures: Array<Signature>): Set<String> {
        val digest = MessageDigest.getInstance("SHA-256")
        return signatures.map { sig ->
            digest.reset()
            digest.digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
        }.toSet()
    }
}

package com.termux.app.util

import android.content.Context
import android.content.pm.PackageManager
import java.io.File
import java.security.MessageDigest

/**
 * Registro de consentimiento del Alcance B (servidor privilegiado propio tipo Shizuku, ver
 * `docs/arquitectura/PLAN_ADB_USB_GATEWAY_2026-10-31.md` § "diseño técnico real" — construido sin
 * cliente real todavía, por pedido explícito del usuario en esta ronda: "construir igual, sin
 * cliente todavía").
 *
 * Fail-closed por diseño: una app nunca queda autorizada hasta que el usuario confirma
 * explícitamente desde la UI de Kairos (ver [KairosPrivilegedConsentActivity]) — este objeto solo
 * guarda/consulta la decisión YA TOMADA, nunca la toma por su cuenta.
 *
 * Se identifica por (nombre de paquete, huella SHA-256 de la firma del APK) — no solo el nombre,
 * para que una app maliciosa con el mismo `applicationId` pero firmada distinto nunca herede el
 * consentimiento de la app real (hallazgo ya señalado en el diseño: "no solo el nombre, para que
 * una app con el mismo applicationId no herede el permiso de otra firmada distinto").
 *
 * Reusa `~/.android_server_registry` (mismo archivo que [AdbShellBridge]) con el prefijo de
 * clave `privileged.consent.<paquete>=<firma_sha256>` — un registro más, no uno nuevo.
 */
object KairosPrivilegedConsentStore {

    private const val KEY_PREFIX = "privileged.consent."

    // "Solo esta vez" (hallazgo real de docs/referencias/pc-movil/REFERENCIA_SHIZUKU.md: "permiso
    // 'solo esta vez' -- hoy solo consentimiento persistente"). No hay un canal real para que el
    // proceso privilegiado (app_process, corto y sin identidad propia estable entre arranques) le
    // avise al registro "ya terminé, olvidate de este permiso" -- en vez de inventar esa señalización
    // cruzada sin poder probarla de punta a punta (todavía no existe un cliente real, ver KDoc de
    // KairosPrivilegedServerMain), se usa una ventana de tiempo corta: el permiso vale por
    // ONCE_GRANT_WINDOW_MS desde que se concede, después se trata como no autorizado solo, sin que
    // nadie tenga que revocarlo a mano.
    private const val ONCE_PREFIX = "once:"
    private const val ONCE_GRANT_WINDOW_MS = 5 * 60 * 1000L

    private fun registryFile(): File = File(ManagerNativeUtils.home, ".android_server_registry")

    private fun readRegistry(file: File): Map<String, String> =
        (if (file.exists()) file.readLines() else emptyList())
            .mapNotNull { line ->
                val t = line.trim()
                if (t.isEmpty() || t.startsWith("#")) null
                else t.indexOf('=').takeIf { it > 0 }?.let { eq -> t.substring(0, eq).trim() to t.substring(eq + 1).trim() }
            }.toMap()

    /** Huella SHA-256 de la firma real del APK instalado de [packageName] — lo que de verdad
     *  distingue una app de un impostor con el mismo nombre. `null` si el paquete no existe. */
    @JvmStatic
    fun signatureSha256(context: Context, packageName: String): String? {
        return try {
            val pm = context.packageManager
            @Suppress("DEPRECATION")
            val signatures = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                val info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                info.signingInfo?.apkContentsSigners ?: info.signingInfo?.signingCertificateHistory
            } else {
                pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures
            }
            val sig = signatures?.firstOrNull() ?: return null
            val digest = MessageDigest.getInstance("SHA-256").digest(sig.toByteArray())
            digest.joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            null
        }
    }

    /** true si [packageName] ya fue autorizado por el usuario Y su firma instalada sigue siendo
     *  la misma que cuando se autorizó (si cambió — reinstalación con otra firma — no cuenta).
     *  Un consentimiento "solo esta vez" ([authorizeOnce]) autoriza igual mientras siga dentro de
     *  [ONCE_GRANT_WINDOW_MS] desde que se concedió — vencido, se trata como no autorizado sin
     *  que nadie tenga que revocarlo a mano. */
    @JvmStatic
    fun isAuthorized(context: Context, packageName: String): Boolean {
        val currentSig = signatureSha256(context, packageName) ?: return false
        val saved = readRegistry(registryFile())[KEY_PREFIX + packageName] ?: return false
        if (saved.startsWith(ONCE_PREFIX)) {
            val rest = saved.removePrefix(ONCE_PREFIX)
            val sepIdx = rest.indexOf(':')
            if (sepIdx <= 0) return false
            val expiresAt = rest.substring(0, sepIdx).toLongOrNull() ?: return false
            val sig = rest.substring(sepIdx + 1)
            return System.currentTimeMillis() <= expiresAt && sig == currentSig
        }
        return saved == currentSig
    }

    /** Guarda la autorización PERSISTENTE — llamar SOLO después de que el usuario confirmó
     *  explícitamente "Permitir siempre" en el diálogo de consentimiento, nunca antes. */
    @JvmStatic
    fun authorize(context: Context, packageName: String) {
        val sig = signatureSha256(context, packageName) ?: return
        writeConsentValue(packageName, sig)
    }

    /** Guarda la autorización de UNA SOLA VEZ (ver [ONCE_GRANT_WINDOW_MS]) — llamar SOLO después
     *  de que el usuario confirmó explícitamente "Solo esta vez" en el diálogo de consentimiento. */
    @JvmStatic
    fun authorizeOnce(context: Context, packageName: String) {
        val sig = signatureSha256(context, packageName) ?: return
        val expiresAt = System.currentTimeMillis() + ONCE_GRANT_WINDOW_MS
        writeConsentValue(packageName, "$ONCE_PREFIX$expiresAt:$sig")
    }

    private fun writeConsentValue(packageName: String, value: String) {
        RegistryLock.withLock(ManagerNativeUtils.registryLockFile) {
            val file = registryFile()
            val current = readRegistry(file).toMutableMap()
            current[KEY_PREFIX + packageName] = value
            file.writeText(current.entries.joinToString("\n") { "${it.key}=${it.value}" } + "\n")
        }
    }

    /** Revoca — disponible desde Config → Permisos para deshacer una autorización ya dada. */
    @JvmStatic
    fun revoke(packageName: String) {
        RegistryLock.withLock(ManagerNativeUtils.registryLockFile) {
            val file = registryFile()
            val current = readRegistry(file).toMutableMap()
            current.remove(KEY_PREFIX + packageName)
            file.writeText(current.entries.joinToString("\n") { "${it.key}=${it.value}" } + "\n")
        }
    }

    /** Todos los paquetes autorizados ahora mismo — para listarlos en Config → Permisos. */
    @JvmStatic
    fun listAuthorized(): List<String> =
        readRegistry(registryFile()).keys.filter { it.startsWith(KEY_PREFIX) }.map { it.removePrefix(KEY_PREFIX) }
}

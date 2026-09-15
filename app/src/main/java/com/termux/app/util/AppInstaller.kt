package com.termux.app.util

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/**
 * Instala el APK ya verificado (hash + firma, ver [AppUpdater.verifyApk]) usando la API real de
 * Android — puerto de `AndroidAppInstaller.kt` de `techjarves/Mobile-Harness`, ver
 * `docs/referencias/herramientas/AUDITORIA_MOBILE_HARNESS_2026-09-09.md` Categoría 1/3. Camino
 * normal: `PackageInstaller.SessionParams(MODE_FULL_INSTALL)`, pidiendo confirmación explícita
 * del usuario (`setRequireUserAction`) — nunca se instala sin que el usuario vea y acepte el
 * diálogo del sistema. Caso especial de fabricante (dato real de producción del proyecto
 * original, no teórico): en Xiaomi/Redmi/Poco la MIUI personalizada rompe el flujo moderno de
 * `PackageInstaller`, así que se usa el `Intent(ACTION_INSTALL_PACKAGE)` clásico en su lugar.
 */
object AppInstaller {

    /** true si el fabricante es de la familia Xiaomi (Xiaomi/Redmi/Poco) — MIUI personalizada,
     *  ver KDoc de la clase. */
    fun isMiuiDevice(): Boolean {
        val manufacturer = Build.MANUFACTURER.lowercase()
        return manufacturer == "xiaomi" || manufacturer == "redmi" || manufacturer == "poco"
    }

    fun canRequestInstalls(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }

    /** Intent a la pantalla del sistema donde el usuario habilita "Instalar apps desconocidas"
     *  para Kairos — el caller (UI) debe dirigir acá cuando [canRequestInstalls] es false, en
     *  vez de intentar instalar y fallar silenciosamente. */
    fun requestInstallPermissionIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    /**
     * Entrega [apkFile] (ya verificado por [AppUpdater.verifyApk]) al instalador real del
     * sistema. [onStatus] recibe códigos de [PackageInstaller] (`STATUS_PENDING_USER_ACTION`,
     * `STATUS_SUCCESS`, `STATUS_FAILURE`, etc.) — puede llamarse más de una vez (primero
     * `STATUS_PENDING_USER_ACTION` cuando se muestra el diálogo de confirmación del sistema,
     * luego el resultado final una vez que el usuario responde). En el caso MIUI no hay forma
     * confiable de trackear el resultado final (el `Intent` clásico no devuelve un callback) —
     * se reporta `STATUS_PENDING_USER_ACTION` una sola vez y el resto queda en manos de la UI
     * del sistema, igual que hace el proyecto de referencia.
     */
    fun install(context: Context, apkFile: File, onStatus: (status: Int, message: String?) -> Unit) {
        val appContext = context.applicationContext
        if (isMiuiDevice()) {
            try {
                installViaClassicIntent(appContext, apkFile)
                onStatus(PackageInstaller.STATUS_PENDING_USER_ACTION, "MIUI: instalador clásico abierto")
            } catch (e: Exception) {
                onStatus(PackageInstaller.STATUS_FAILURE, e.message)
            }
            return
        }
        installViaSessionApi(appContext, apkFile, onStatus)
    }

    private fun installViaClassicIntent(context: Context, apkFile: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updater", apkFile)
        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private fun installViaSessionApi(context: Context, apkFile: File, onStatus: (Int, String?) -> Unit) {
        try {
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
            }
            val sessionId = installer.createSession(params)
            val session = installer.openSession(sessionId)
            session.use { s ->
                apkFile.inputStream().use { input ->
                    s.openWrite("kairos_update", 0, apkFile.length()).use { out ->
                        input.copyTo(out)
                        s.fsync(out)
                    }
                }
                val action = "${context.packageName}.UPDATE_INSTALL_STATUS.$sessionId"
                registerStatusReceiver(context, action, onStatus)
                val pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
                val pendingIntent = PendingIntent.getBroadcast(
                    context, sessionId, Intent(action).setPackage(context.packageName), pendingIntentFlags
                )
                s.commit(pendingIntent.intentSender)
            }
        } catch (e: Exception) {
            onStatus(PackageInstaller.STATUS_FAILURE, e.message)
        }
    }

    // Receiver dinámico auto-registrado en vez de uno declarado en el manifest — evita agregar
    // una entrada de Manifest solo para este flujo puntual; el proceso de la app sigue vivo
    // durante todo el diálogo de confirmación (la UI de progreso lo mantiene en foreground), así
    // que no hace falta sobrevivir a que el proceso muera para recibir el broadcast del sistema.
    // Se des-registra solo tras el estado final (no tras STATUS_PENDING_USER_ACTION, que puede
    // llegar más de una vez antes del resultado real).
    private fun registerStatusReceiver(context: Context, action: String, onStatus: (Int, String?) -> Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    @Suppress("DEPRECATION")
                    val confirmIntent = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                    confirmIntent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try {
                        if (confirmIntent != null) context.startActivity(confirmIntent)
                    } catch (e: Exception) {
                        // Si Android no puede abrir el diálogo de confirmación, igual se avisa
                        // el status recibido — el caller decide qué mostrar.
                    }
                    onStatus(status, message)
                    return
                }
                try { context.unregisterReceiver(this) } catch (e: Exception) { /* ya des-registrado */ }
                onStatus(status, message)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, IntentFilter(action))
        }
    }
}

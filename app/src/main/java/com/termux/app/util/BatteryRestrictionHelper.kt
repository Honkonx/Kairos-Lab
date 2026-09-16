package com.termux.app.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * Ayuda al usuario a desactivar las restricciones agresivas de batería/autostart que varios
 * fabricantes Android (Samsung, Xiaomi/MIUI, Huawei, OPPO, Vivo, OnePlus, ...) aplican encima
 * del comportamiento estándar de Android — pedido explícito del usuario 2026-08-01 ("descubri
 * que en samsung y otras marcas android mata los procesos").
 *
 * Investigación previa (misma ronda): se revisó `referencia/termux/core-termux-main/` (última versión
 * clonada directo de `DevCoreXOfficial/core-termux`, confirmado que la copia local ya estaba al
 * día — mismo README, misma estructura de `core/`) y no tiene nada de esto (es 100% scripts
 * bash dentro de Termux, sin Activity/APK propio que necesite pedir excepciones de batería).
 * `referencia/termux/termux-kotlin-app-main/.../PhantomProcessUtils.kt` SÍ tiene un utility real, pero
 * cubre exactamente el mismo "phantom process killer" genérico de Android 12+ que Kairos ya
 * maneja desde la ronda 47 (`MonitorFragment.kt`) — nada nuevo ahí. Ningún proyecto de
 * `referencia/` tiene manejo POR FABRICANTE de esto.
 *
 * Los intents específicos de abajo (package + activity real por fabricante) están confirmados
 * contra `judemanutd/AutoStarter` (MIT, https://github.com/judemanutd/AutoStarter,
 * `AutoStartPermissionHelper.kt`) — librería Android real y ampliamente usada específicamente
 * para este problema, no una lista inventada. Mismo criterio de seguridad que
 * `OverlayPermissionHelper.kt` (ronda 47): cada intent se verifica con
 * `queryIntentActivities()` ANTES de lanzarlo — si no existe en el dispositivo (fabricante
 * distinto, versión de ROM sin esa pantalla, etc.) se prueba el siguiente candidato del mismo
 * fabricante y, si ninguno resuelve, se cae al pedido ESTÁNDAR de Android
 * (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, que sí siempre existe) — nunca se deja al
 * usuario sin ninguna acción real.
 */
object BatteryRestrictionHelper {

    // SharedPreferences propio del helper (mismo mecanismo ya usado en el resto de la app —
    // ver ConfigFragment.PREFS_NAME — nunca un mecanismo nuevo de persistencia): guarda si el
    // diálogo explicativo ya se mostró para esta instalación, para no repetirlo cada vez que
    // el usuario vuelva a tocar la acción mientras la restricción de batería sigue activa.
    private const val PREFS_NAME = "kairos_battery_restriction_prefs"
    private const val KEY_EXPLAIN_DIALOG_SHOWN = "explain_dialog_shown"

    /**
     * Punto de entrada único: muestra primero un diálogo breve explicando POR QUÉ Kairos pide
     * esto (solo la primera vez, ver PREFS_NAME arriba) y recién después pide la exención
     * estándar de Android + intenta la pantalla específica del fabricante (autostart/gestor de
     * batería) — las dos cosas son necesarias en fabricantes agresivos, una sola no alcanza.
     * Hallazgo de auditoría de la categoría ia de referencia/ (2026-08-31): antes se lanzaba el intent de
     * Settings directo, sin contexto — el usuario llegaba a una pantalla del sistema sin saber
     * por qué Kairos se la pidió.
     */
    @JvmStatic
    fun requestDisableBatteryRestrictions(activity: Activity) {
        val prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_EXPLAIN_DIALOG_SHOWN, false)) {
            proceedWithBatteryRequest(activity)
            return
        }
        androidx.appcompat.app.AlertDialog.Builder(activity)
            .setTitle(com.termux.R.string.battery_restriction_dialog_title)
            .setMessage(com.termux.R.string.battery_restriction_dialog_message)
            .setPositiveButton(com.termux.R.string.battery_restriction_dialog_continue) { _, _ ->
                prefs.edit().putBoolean(KEY_EXPLAIN_DIALOG_SHOWN, true).apply()
                proceedWithBatteryRequest(activity)
            }
            .setNegativeButton(com.termux.R.string.battery_restriction_dialog_cancel, null)
            .show()
    }

    private fun proceedWithBatteryRequest(activity: Activity) {
        requestIgnoreBatteryOptimizations(activity)
        tryManufacturerSpecificScreen(activity)
    }

    private fun requestIgnoreBatteryOptimizations(activity: Activity) {
        try {
            val pm = activity.getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (pm != null && !pm.isIgnoringBatteryOptimizations(activity.packageName)) {
                val intent = Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${activity.packageName}")
                )
                if (isIntentAvailable(activity, intent)) activity.startActivity(intent)
            }
        } catch (_: Exception) {
            // No crítico — sigue con la pantalla del fabricante igual.
        }
    }

    /** package → lista de activities candidatas (se prueban en orden, la primera que resuelva gana). */
    private data class ManufacturerTarget(val pkg: String, val activities: List<String>)

    private fun candidatesFor(manufacturer: String): List<ManufacturerTarget> = when {
        manufacturer.contains("xiaomi") || manufacturer.contains("redmi") || manufacturer.contains("poco") ->
            listOf(ManufacturerTarget("com.miui.securitycenter", listOf(
                "com.miui.permcenter.autostart.AutoStartManagementActivity"
            )))
        manufacturer.contains("samsung") ->
            listOf(ManufacturerTarget("com.samsung.android.lool", listOf(
                "com.samsung.android.sm.ui.battery.BatteryActivity",
                "com.samsung.android.sm.battery.ui.BatteryActivity",
                "com.samsung.android.sm.battery.ui.usage.CheckableAppListActivity"
            )))
        manufacturer.contains("huawei") || manufacturer.contains("honor") ->
            listOf(ManufacturerTarget("com.huawei.systemmanager", listOf(
                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                "com.huawei.systemmanager.optimize.process.ProtectActivity"
            )))
        manufacturer.contains("oppo") ->
            listOf(
                ManufacturerTarget("com.coloros.safecenter", listOf(
                    "com.coloros.safecenter.permission.startup.StartupAppListActivity",
                    "com.coloros.safecenter.startupapp.StartupAppListActivity"
                )),
                ManufacturerTarget("com.oppo.safe", listOf(
                    "com.oppo.safe.permission.startup.StartupAppListActivity"
                ))
            )
        manufacturer.contains("vivo") ->
            listOf(
                ManufacturerTarget("com.vivo.permissionmanager", listOf(
                    "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
                )),
                ManufacturerTarget("com.iqoo.secure", listOf(
                    "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
                    "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"
                ))
            )
        manufacturer.contains("oneplus") ->
            listOf(ManufacturerTarget("com.oneplus.security", listOf(
                "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"
            )))
        manufacturer.contains("asus") ->
            listOf(ManufacturerTarget("com.asus.mobilemanager", listOf(
                "com.asus.mobilemanager.autostart.AutoStartActivity",
                "com.asus.mobilemanager.powersaver.PowerSaverSettings"
            )))
        else -> emptyList()
    }

    /** true si logró abrir una pantalla específica del fabricante (útil solo para logging/tests, el flujo real no depende del resultado). */
    private fun tryManufacturerSpecificScreen(activity: Activity): Boolean {
        val manufacturer = Build.MANUFACTURER.lowercase()
        for (target in candidatesFor(manufacturer)) {
            for (activityClass in target.activities) {
                val intent = Intent().apply {
                    setClassName(target.pkg, activityClass)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                if (isIntentAvailable(activity, intent)) {
                    return try {
                        activity.startActivity(intent)
                        true
                    } catch (_: Exception) {
                        false
                    }
                }
            }
        }
        return false
    }

    private fun isIntentAvailable(activity: Activity, intent: Intent): Boolean =
        try {
            activity.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).isNotEmpty()
        } catch (_: Exception) {
            false
        }
}

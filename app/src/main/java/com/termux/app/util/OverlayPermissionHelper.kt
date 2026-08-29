package com.termux.app.util

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * Pedido del permiso de overlay ("dibujar sobre otras apps", ver ConfigFragment.kt "Widget
 * flotante") — patrón adoptado de referencia/termux/termux-app-master-x11-submodule/float-ball/
 * (FloatPermissionManager.java + los archivos .java de permission/rom/, hallazgo 2026-08-01,
 * ver docs/referencias/REFERENCIA_TERMUX_APP_X11_SUBMODULE.md).
 *
 * El intent estándar (Settings.ACTION_MANAGE_OVERLAY_PERMISSION, unificado desde Android
 * 6.0/API 23 — Kairos minSdk=24 siempre está por encima de eso) sigue siendo el camino
 * PRIMARIO acá, a diferencia del proyecto de origen (que data de Android 5, antes de la
 * unificación, con ramas separadas por versión de MIUI 5/6/7/8 que ya no aplican). Pero en la
 * práctica MIUI/Xiaomi sigue siendo conocido por routear ese intent a una pantalla confusa o
 * por `Settings.canDrawOverlays()` no reflejar bien el estado real — se agrega el intent
 * específico de MIUI (mismo componente que `MiuiUtils.goToMiuiPermissionActivity_V7/V8` del
 * proyecto de origen) como FALLBACK si el estándar no está disponible, nunca como reemplazo.
 * Huawei/Meizu/360 no se portaron: sus componentes específicos son de ROMs de 2016-2018
 * (Android 5/6), sin evidencia de seguir vigentes en versiones actuales — agregarlos sin poder
 * verificarlos en un dispositivo real arriesgaba un intent roto silencioso, priorizado no
 * hacerlo (ver comentario de la tarea que generó este archivo).
 */
object OverlayPermissionHelper {

    @JvmStatic
    fun requestOverlayPermission(activity: Activity) {
        val standardIntent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${activity.packageName}")
        )
        if (isIntentAvailable(activity, standardIntent)) {
            try {
                activity.startActivity(standardIntent)
                return
            } catch (_: Exception) {
                // Sigue al fallback de abajo.
            }
        }
        if (isMiui() && tryMiuiPermissionEditor(activity)) return
        tryAppDetailsSettings(activity)
    }

    private fun isMiui(): Boolean =
        Build.MANUFACTURER.contains("Xiaomi", ignoreCase = true) ||
            Build.MANUFACTURER.contains("Redmi", ignoreCase = true) ||
            !getSystemProperty("ro.miui.ui.version.name").isNullOrEmpty()

    private fun tryMiuiPermissionEditor(activity: Activity): Boolean {
        val intent = Intent("miui.intent.action.APP_PERM_EDITOR").apply {
            setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.AppPermissionsEditorActivity")
            putExtra("extra_pkgname", activity.packageName)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        if (!isIntentAvailable(activity, intent)) return false
        return try {
            activity.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    // Último recurso, siempre disponible en cualquier Android real — no resuelve el permiso
    // directo, pero deja al usuario en la pantalla de la app desde donde SÍ puede llegar a
    // los permisos manualmente si los 2 intents de arriba fallaron.
    private fun tryAppDetailsSettings(activity: Activity) {
        try {
            val intent = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", activity.packageName, null)
            ).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
            activity.startActivity(intent)
        } catch (_: Exception) {
            // Sin nada más que intentar de este lado — ConfigFragment ya mostró un toast
            // explicando qué hacer antes de llegar acá.
        }
    }

    private fun isIntentAvailable(activity: Activity, intent: Intent): Boolean =
        try {
            activity.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).isNotEmpty()
        } catch (_: Exception) {
            false
        }

    private fun getSystemProperty(name: String): String? = try {
        val process = Runtime.getRuntime().exec(arrayOf("getprop", name))
        process.inputStream.bufferedReader().readLine()
    } catch (_: Exception) {
        null
    }
}

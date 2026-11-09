package com.termux.app.util

import com.termux.shared.termux.TermuxConstants
import java.io.File

/**
 * Paridad de módulo real para el gateway (2026-10-16, ver meta 4-5) — "apk" no
 * está en la lista de módulos protegidos. `runInfo()`/`runDecode()` de `ApkFragment` solo
 * necesitan una RUTA real (no el `Uri` de SAF en sí — eso es solo cómo la app copia el APK a esa
 * ruta desde el selector de archivos de Android) — mismo comando real
 * (`compil-apk-termux info|decode <ruta> [<carpeta_salida>]`). Para el gateway, el camino es: el
 * cliente sube el APK primero con `fs.write` (base64, ya existente) a una ruta dentro del HOME,
 * y después llama a `apk.info`/`apk.decode` con esa misma ruta — sin necesitar ningún selector.
 */
object ApkToolsManager {
    private val runner: CommandRunner = ShellCommandRunner

    fun info(apkPath: File, timeoutSec: Long = 60): PrivilegeResult {
        if (!apkPath.isFile) return PrivilegeResult(false, "no existe ese archivo: ${apkPath.path}", 400)
        val (code, out) = runner.run(listOf("bash", "-c", "compil-apk-termux info ${shellQuote(apkPath.absolutePath)}"), timeoutSec)
        return PrivilegeResult(code == 0, out, if (code == 0) 200 else 500)
    }

    fun decode(apkPath: File, timeoutSec: Long = 300): PrivilegeResult {
        if (!apkPath.isFile) return PrivilegeResult(false, "no existe ese archivo: ${apkPath.path}", 400)
        val baseName = apkPath.name.removeSuffix(".apk").ifBlank { "apk" }
        val outDir = File(TermuxConstants.TERMUX_HOME_DIR_PATH, "apk_decoded/${baseName}_${System.currentTimeMillis()}")
        val (code, out) = runner.run(
            listOf("bash", "-c", "compil-apk-termux decode ${shellQuote(apkPath.absolutePath)} ${shellQuote(outDir.absolutePath)}"),
            timeoutSec
        )
        return PrivilegeResult(code == 0, if (code == 0) outDir.absolutePath else out.takeLast(1800), if (code == 0) 200 else 500)
    }
}

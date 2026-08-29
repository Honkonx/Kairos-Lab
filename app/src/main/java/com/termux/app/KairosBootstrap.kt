package com.termux.app

import android.content.Context
import android.content.pm.PackageManager
import com.termux.shared.termux.TermuxConstants
import java.io.File

object KairosBootstrap {

    private val HOME get() = TermuxConstants.TERMUX_HOME_DIR_PATH

    @JvmStatic fun isReady(): Boolean {
        return File("$HOME/.kairos_ready").exists()
    }

    @JvmStatic fun extractAssets(context: Context) {
        Thread {
            doExtract(context)
        }.start()
    }

    @JvmStatic fun extractAssetsSync(context: Context) {
        doExtract(context)
    }

    private fun versionFile() = File("$HOME/.kairos_extracted_version")

    private fun currentVersion(context: Context): String {
        return try {
            val pkg = context.packageManager.getPackageInfo(context.packageName, 0)
            "${pkg.versionCode}"
        } catch (_: PackageManager.NameNotFoundException) {
            "0"
        }
    }

    private fun isAlreadyExtracted(context: Context): Boolean {
        val stored = versionFile()
        if (!stored.exists() || stored.readText().trim() != currentVersion(context)) return false
        // El marker de versión por sí solo no basta: si una extracción previa falló a
        // mitad de camino (ej. context.assets.list() devolvió vacío en algún dispositivo)
        // igual se escribía el marker, dejando scripts/install/ vacío para siempre.
        val installDir = File(HOME, "scripts/install")
        return installDir.listFiles { f -> f.extension == "sh" }?.isNotEmpty() == true
    }

    private fun doExtract(context: Context) {
        try {
            val scriptsDir = File(HOME, "scripts")
            val installDir = File(scriptsDir, "install")
            installDir.mkdirs()

            if (isAlreadyExtracted(context)) return

            val assetNames = try {
                context.assets.list("scripts") ?: emptyArray()
            } catch (_: Exception) {
                emptyArray()
            }

            for (name in assetNames) {
                when {
                    name == "kairos.sh" -> {
                        extractAsset(context, "scripts/kairos.sh", File(scriptsDir, "kairos.sh"))
                    }
                    name == "kairos_manager.py" -> {
                        // Copy the Python manager script to $HOME/kairos_manager.py
                        extractAsset(context, "scripts/kairos_manager.py", File(HOME, "kairos_manager.py"))
                    }
                    name == "rootfs_package_list.txt" -> {
                        // Lista de paquetes del rootfs embebido (ver tools/rootfs/package_list.txt,
                        // fuente única) — la lee kairos_manager.py cmd_rootfs (verify/check-updates).
                        extractAsset(context, "scripts/rootfs_package_list.txt", File(scriptsDir, "rootfs_package_list.txt"))
                    }
                    name.endsWith(".sh") -> {
                        // Scripts de módulo (ollama.sh, opencode.sh, etc. — sin prefijo
                        // install_, ver .claude/rules/scripts-rule.md). Antes este filtro
                        // pedía startsWith("install_") pero ningún asset real tiene ese
                        // prefijo — el instalador nunca encontraba el script (fix 2026-07-24).
                        extractAsset(context, "scripts/$name", File(installDir, name))
                    }
                    name == "llama-server" -> {
                        // Binario real de llama.cpp (servidor HTTP), no un script — generado
                        // por CMake (ver llama-engine/build.gradle, rama
                        // "llama-server-and-terminal-ux", 2026-08-05, docs/humano/humano71.md).
                        // extractAsset() ya marca +x en cualquier archivo que extrae, sirve
                        // igual para un binario que para un .sh. llamaserver.sh (modulos/) lo
                        // copia desde acá a $PREFIX/bin/ al "instalar" el módulo.
                        extractAsset(context, "scripts/llama-server", File(installDir, "llama-server"))
                    }
                    name.endsWith(".so") -> {
                        // Bibliotecas dinámicas de las que llama-server depende en runtime
                        // (libggml-base.so, libllama.so, libllama-common.so, libmtmd.so,
                        // libllama-server-impl.so — ver CMakeLists.txt, BUILD_SHARED_LIBS=ON,
                        // bug real 2026-08-06, docs/humano/humano83.md: "CANNOT LINK EXECUTABLE"
                        // por faltar estos .so). Se extraen al mismo directorio que llama-server
                        // — modulos/llamaserver.sh los copia de ahí a $PREFIX/lib/ al instalar.
                        extractAsset(context, "scripts/$name", File(installDir, name))
                    }
                }
            }

            versionFile().writeText(currentVersion(context))
        } catch (_: Exception) {
        }
    }

    private fun extractAsset(ctx: Context, assetPath: String, dest: File) {
        try {
            ctx.assets.open(assetPath).use { input ->
                dest.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            dest.setExecutable(true, false)
        } catch (_: Exception) {
        }
    }
}

package com.termux.app.util

import android.content.Context
import java.io.File

/**
 * Comprobación/instalación/actualización de paquetes del rootfs — equivalente 100%
 * Kotlin de `cmd_rootfs` en kairos_manager.py (verify/install-missing/check-updates/
 * update/sync), pedido explícito del usuario para que el flujo de la app no dependa de
 * invocar python3 en absoluto (ver docs/humano/humano12.md — "la extracción del rootfs es
 * pura, con código del apk"). kairos_manager.py sigue teniendo `cmd_rootfs` como
 * comando manual por terminal (`python3 kairos_manager.py rootfs verify`, etc.), pero
 * la app ya no lo llama para esto.
 *
 * "Instalado" se determina leyendo /var/lib/dpkg/status directo (el mismo archivo que
 * usa dpkg/apt internamente) — ES el archivo real de Termux, no una copia ni un
 * registro paralelo, así que siempre está en sync con lo que apt/dpkg realmente saben.
 * Instalar/actualizar sigue llamando a `apt` real por ProcessBuilder (no es Python, es
 * el gestor de paquetes real de Termux — ver docstring de RootfsInstaller.kt sobre por
 * qué esto es intencional y no un at all-a-mano como hace ver/MiceWine-Application-master/).
 */
object RootfsPackageChecker {

    private const val ASSET_PACKAGE_LIST = "scripts/rootfs_package_list.txt"
    private const val DPKG_STATUS_PATH = "/data/data/com.termux/files/usr/var/lib/dpkg/status"

    data class VerifyResult(val installed: List<String>, val missing: List<String>)
    data class SyncResult(val installedNow: List<String>, val updatedNow: List<String>, val message: String)

    @JvmStatic
    fun packageList(context: Context): List<String> {
        return try {
            context.assets.open(ASSET_PACKAGE_LIST).bufferedReader().useLines { lines ->
                lines.map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
                    .toList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Nombres de paquete con "Status: install ok installed" en /var/lib/dpkg/status —
     * el mismo archivo real que dpkg/apt mantienen, no un registro propio paralelo. */
    private fun installedPackageNames(): Set<String> {
        val statusFile = File(DPKG_STATUS_PATH)
        if (!statusFile.exists()) return emptySet()

        val installed = mutableSetOf<String>()
        var currentPackage: String? = null
        var currentStatus: String? = null

        statusFile.forEachLine { rawLine ->
            when {
                rawLine.isBlank() -> {
                    if (currentPackage != null && currentStatus == "install ok installed") {
                        installed.add(currentPackage!!)
                    }
                    currentPackage = null
                    currentStatus = null
                }
                rawLine.startsWith("Package:") -> currentPackage = rawLine.removePrefix("Package:").trim()
                rawLine.startsWith("Status:") -> currentStatus = rawLine.removePrefix("Status:").trim()
            }
        }
        // El archivo no siempre termina con línea en blanco — el último bloque se
        // pierde si no se cierra acá también.
        if (currentPackage != null && currentStatus == "install ok installed") {
            installed.add(currentPackage!!)
        }
        return installed
    }

    @JvmStatic
    fun verify(context: Context): VerifyResult {
        val packages = packageList(context)
        val installedNames = installedPackageNames()
        val installed = packages.filter { it in installedNames }
        val missing = packages.filter { it !in installedNames }
        return VerifyResult(installed, missing)
    }

    // Ruta absoluta de "apt" en vez de nombre relativo — bug real confirmado esta sesión
    // (ver docs/humano/humano62.md/humano63.md): "Cannot run program apt" pasó en
    // RootfsInstaller.installDebs() pese a que ya usaba applyTermuxEnv() correctamente.
    private fun runApt(vararg args: String, timeoutMs: Long = 900_000): Pair<Boolean, String> {
        val cmd = mutableListOf(TERMUX_APT_PATH)
        cmd.addAll(args)
        val pb = ProcessBuilder(cmd)
        pb.applyTermuxEnv()
        pb.redirectErrorStream(true)
        val process = pb.start()
        val finished = process.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            return false to "timeout"
        }
        val output = process.inputStream.bufferedReader().readText()
        return (process.exitValue() == 0) to output
    }

    @JvmStatic
    fun installMissing(missing: List<String>): Boolean {
        if (missing.isEmpty()) return true
        val args = mutableListOf("install", "-y",
            "-o", "Dpkg::Options::=--force-confdef", "-o", "Dpkg::Options::=--force-confold")
        args.addAll(missing)
        return runApt(*args.toTypedArray()).first
    }

    /** "apt list --upgradable" real — mismo comando que correría el usuario a mano,
     * solo parseado acá en vez de en un script Python. Formato de línea:
     * "pkgname/repo version arch [upgradable from: vieja]". */
    @JvmStatic
    fun checkUpdates(): List<String> {
        runApt("update", "-y", timeoutMs = 60_000)
        val (_, output) = runApt("list", "--upgradable", timeoutMs = 30_000)
        return output.lineSequence()
            .filter { it.contains("/") && !it.startsWith("Listing", ignoreCase = true) }
            .mapNotNull { line -> line.substringBefore("/").trim().takeIf { it.isNotEmpty() } }
            .toList()
    }

    @JvmStatic
    fun update(targets: List<String>): Boolean {
        if (targets.isEmpty()) return true
        val args = mutableListOf("install", "-y", "--only-upgrade")
        args.addAll(targets)
        return runApt(*args.toTypedArray()).first
    }

    /** Combinado verify -> install-missing -> check-updates -> update, pensado para un
     * único botón con spinner (wizard o Ajustes) — equivalente a `cmd_rootfs sync` de
     * kairos_manager.py, pero sin invocar python3 en absoluto. */
    @JvmStatic
    fun sync(context: Context, onProgress: (String) -> Unit): SyncResult {
        onProgress("Verificando paquetes…")
        val (_, missing) = verify(context)

        val installedNow = if (missing.isNotEmpty()) {
            onProgress("Instalando ${missing.size} paquetes faltantes…")
            if (!installMissing(missing)) {
                return SyncResult(emptyList(), emptyList(), "Falló instalar paquetes faltantes")
            }
            missing
        } else {
            emptyList()
        }

        onProgress("Buscando actualizaciones…")
        val upgradable = checkUpdates()

        val updatedNow = if (upgradable.isNotEmpty()) {
            onProgress("Actualizando ${upgradable.size} paquetes…")
            if (update(upgradable)) upgradable else emptyList()
        } else {
            emptyList()
        }

        return SyncResult(
            installedNow, updatedNow,
            "${installedNow.size} instalados, ${updatedNow.size} actualizados"
        )
    }
}

package com.termux.app.util

import java.io.File

/**
 * Paridad de módulo real para el gateway (2026-10-16, ver meta 4-5) — solo
 * LECTURA esta ronda (binarios disponibles + imágenes ya creadas, mismos chequeos reales que
 * `QemuFragment.refreshStatus()`/`listImages()`). Arrancar una VM de verdad (`bootVm*()`) queda
 * afuera a propósito: consume RAM/CPU real del dispositivo de forma sostenida y necesita más
 * cuidado de parámetros (RAM, arquitectura, VNC) del que da tiempo a esta ronda — mismo criterio
 * ya aplicado a Restic/credenciales de Remote.
 */
object QemuManager {
    private val runner: CommandRunner = ShellCommandRunner
    private fun imagesDir(): File = File(ManagerNativeUtils.home, "qemu_images")

    fun status(): Map<String, Boolean> = mapOf(
        "user_mode_available" to (runner.run(listOf("sh", "-c", "command -v qemu-x86_64 || command -v qemu-arm"), 10).first == 0),
        "system_aarch64_available" to (runner.run(listOf("sh", "-c", "command -v qemu-system-aarch64"), 10).first == 0),
        "system_x86_available" to (runner.run(listOf("sh", "-c", "command -v qemu-system-x86_64"), 10).first == 0),
    )

    fun images(): List<Map<String, Any>> =
        imagesDir().listFiles { f -> f.isFile && f.extension in listOf("qcow2", "img", "iso", "raw") }
            ?.sortedBy { it.name }
            ?.map { mapOf("name" to it.name, "size_bytes" to it.length()) }
            ?: emptyList()
}

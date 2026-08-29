package com.termux.app.util

/**
 * Helper centralizado para acceso root opcional/oportunista — MVP implementado 2026-08-25 a
 * partir de la investigación real en `docs/arquitectura/INVESTIGACION_MODO_ROOT_2026-08-25.md`
 * (luz verde explícita del usuario). Mismo patrón que ya usaba en solitario
 * `MonitorFragment.disablePhantomProcessKiller()` (único uso real de `su -c` en toda la app
 * antes de este archivo) — se centraliza acá para que cualquier otro módulo lo reuse sin
 * duplicar la detección ni el manejo de proceso.
 *
 * Reglas del patrón, deliberadas (ver la investigación para el porqué de cada una):
 * - Nunca bloqueante: sin root, todo cae a `false`/fallback silencioso — ningún caller debe
 *   asumir que hay root.
 * - Sin `applyTermuxEnv()`: "su" (si existe) vive en el PATH del root manager del sistema
 *   (Magisk/KernelSU/lo que sea), no en `$PREFIX/bin` de Termux — restringir el PATH lo dejaría
 *   sin encontrar el binario.
 * - No asume Magisk ni KernelSU específicamente — solo prueba `su -c` genérico, funciona con
 *   cualquier framework de root real detrás.
 * - Acceso cross-app (`/data/data/<otraApp>`) queda explícitamente FUERA de este helper — ver
 *   la investigación, es la línea que separa debugging legítimo de spyware, decisión aparte.
 */
object RootAccess {

    @Volatile
    private var cachedHasRoot: Boolean? = null

    /**
     * Detección real, cacheada en memoria (no cambia durante la vida del proceso — un
     * dispositivo no se rootea/desrootea en caliente mientras la app corre). Mismo one-liner ya
     * usado en `modulos/docker.sh` (`command -v su && su -c "id"`), portado a `ProcessBuilder`.
     */
    fun hasRoot(): Boolean {
        cachedHasRoot?.let { return it }
        val result = try {
            val pb = ProcessBuilder("su", "-c", "id")
            pb.redirectErrorStream(true)
            val process = pb.start()
            val out = process.inputStream.bufferedReader().readText()
            val exited = process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
            exited && process.exitValue() == 0 && out.contains("uid=")
        } catch (_: Exception) {
            false
        }
        cachedHasRoot = result
        return result
    }

    data class RootResult(val ok: Boolean, val stdout: String, val stderr: String)

    /**
     * Corre un comando vía `su -c` — oportunista, nunca bloquea el flujo del caller: si no hay
     * root o el comando falla, devuelve `ok=false` con el detalle real en `stderr`, nunca lanza.
     * Mismo timeout/patrón de captura que `MonitorFragment.disablePhantomProcessKiller()`.
     */
    fun runAsRoot(command: String, timeoutSeconds: Long = 10): RootResult {
        if (!hasRoot()) return RootResult(false, "", "sin acceso root")
        return try {
            val pb = ProcessBuilder("su", "-c", command)
            val process = pb.start()
            val out = process.inputStream.bufferedReader().readText()
            val err = process.errorStream.bufferedReader().readText()
            val exited = process.waitFor(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)
            if (!exited) {
                process.destroyForcibly()
                RootResult(false, out, "timeout")
            } else {
                RootResult(process.exitValue() == 0, out, err)
            }
        } catch (e: Exception) {
            RootResult(false, "", e.message ?: "error desconocido")
        }
    }
}

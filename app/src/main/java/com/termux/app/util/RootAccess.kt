package com.termux.app.util

import android.util.Log
import java.util.concurrent.TimeUnit

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

    private const val LOG_TAG = "RootAccess"

    @Volatile
    private var cachedHasRoot: Boolean? = null

    /**
     * Detección real, cacheada en memoria (no cambia durante la vida del proceso — un
     * dispositivo no se rootea/desrootea en caliente mientras la app corre). Mismo one-liner ya
     * usado en `modulos/docker.sh` (`command -v su && su -c "id"`), portado a `ProcessBuilder`.
     *
     * Bug real corregido 2026-09-14: un timeout (el gestor de root
     * mostrando su diálogo de confirmación mientras el usuario todavía no respondió) se
     * cacheaba como `false` para siempre, sin distinguirlo de "el usuario denegó" — un usuario
     * que otorgaba el permiso DESPUÉS de ese primer timeout seguía viendo "sin root" en Kairos
     * hasta reiniciar el proceso completo de la app. Ahora solo se cachea un resultado
     * DEFINITIVO (el proceso `su` terminó, con el exit code que sea) — un timeout deja
     * `cachedHasRoot` sin tocar, así que la próxima llamada vuelve a intentar.
     */
    fun hasRoot(): Boolean {
        cachedHasRoot?.let { return it }
        val result = try {
            val pb = ProcessBuilder("su", "-c", "id")
            pb.redirectErrorStream(true)
            val process = pb.start()
            val out = process.inputStream.bufferedReader().readText()
            val exited = process.waitFor(3, TimeUnit.SECONDS)
            if (!exited) {
                process.destroyForcibly()
                Log.w(LOG_TAG, "hasRoot(): timeout esperando 'su -c id' — ¿diálogo del " +
                    "gestor de root sin responder todavía? No se cachea, se reintenta en la " +
                    "próxima llamada (usar invalidateCache() para forzarlo antes).")
                return false
            }
            process.exitValue() == 0 && out.contains("uid=")
        } catch (e: Exception) {
            // A diferencia del timeout de arriba, esto SÍ es un resultado definitivo — el
            // proceso ni siquiera pudo arrancar (típicamente "su" no existe en el PATH del
            // gestor de root, o no hay ningún gestor de root instalado), no una ambigüedad de
            // "puede que el usuario todavía no haya respondido".
            Log.w(LOG_TAG, "hasRoot(): excepción real al intentar 'su -c id': ${e.message}")
            false
        }
        cachedHasRoot = result
        return result
    }

    /**
     * Fuerza un nuevo chequeo en la próxima llamada a [hasRoot], descartando el resultado
     * cacheado — para un botón "Reintentar" en la UI (ej. Config) cuando el usuario acaba de
     * otorgarle permiso a Kairos en su gestor de root y `hasRoot()` seguía devolviendo `false`
     * de una llamada anterior.
     */
    fun invalidateCache() {
        cachedHasRoot = null
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

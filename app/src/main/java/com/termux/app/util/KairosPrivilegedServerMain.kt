package com.termux.app.util

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Looper

/**
 * Starter real del Alcance B (`app_process` + esta clase, mismo patrón confirmado en el código
 * fuente real de ShizukuPlus — `referencia/pc-movil/ShizukuPlus/app-process/.../NewProcess.java`,
 * Apache-2.0, con crédito — ver `.claude/rules/kairos-reference-fragment-extraction.md`). Se
 * arranca con `adb shell app_process -Djava.class.path=<ruta.apk> /system/bin
 * com.termux.app.util.KairosPrivilegedServerMain --package <paquete-cliente>`, o el equivalente
 * con `su -c`. Corre con la identidad shell/root de quien lo lanzó — NUNCA la identidad normal
 * de Kairos.
 *
 * **Primer incremento, sin cliente real todavía** (pedido explícito del usuario, 2026-10-31,
 *: "construir igual, sin cliente todavía"): el Binder se publica vía
 * `Bundle.putBinder()` dentro de un broadcast Intent dirigido al paquete que lo pidió — NUNCA
 * `ServiceManager.addService()` (eso exige ser `system_server`, un proceso shell/root normal no
 * puede, confirmado leyendo el código real de ShizukuPlus: ninguna de sus piezas reales usa
 * `addService`, todas usan este mecanismo de broadcast+Bundle). Sin una app cliente real
 * instalada para completar el otro lado del handshake, este archivo no se pudo probar de punta
 * a punta esta ronda — queda listo para cuando exista una.
 */
object KairosPrivilegedServerMain {

    const val ACTION_DELIVER_BINDER = "com.termux.kairos.privileged.DELIVER_BINDER"
    const val EXTRA_BINDER = "binder"

    @JvmStatic
    fun main(args: Array<String>) {
        val clientPackage = args.asList().let { list ->
            val idx = list.indexOf("--package")
            if (idx >= 0 && idx + 1 < list.size) list[idx + 1] else null
        }
        if (clientPackage.isNullOrBlank()) {
            System.err.println("Uso: KairosPrivilegedServerMain --package <paquete-cliente>")
            return
        }

        if (Looper.getMainLooper() == null) Looper.prepareMainLooper()
        val systemContext = systemContextViaReflection() ?: run {
            System.err.println("No se pudo obtener un Context de sistema (ActivityThread.systemMain() por reflexión falló)")
            return
        }

        val service = KairosPrivilegedService(systemContext)
        val bundle = Bundle().apply { putBinder(EXTRA_BINDER, service.asBinder()) }
        val intent = Intent(ACTION_DELIVER_BINDER).apply {
            setPackage(clientPackage)
            putExtras(bundle)
        }
        systemContext.sendBroadcast(intent)

        // Looper.loop() mantiene vivo el pool de hilos de Binder — sin esto el proceso terminaría
        // apenas main() retorna, y el Binder recién publicado dejaría de responder.
        Looper.loop()
    }

    /** `ActivityThread.systemMain().getSystemContext()` por reflexión — API `@hide`, no se puede
     *  importar `android.app.ActivityThread` directo contra el SDK público. Mismo valor que
     *  obtendría un import directo si Kairos compilara contra un SDK con APIs ocultas
     *  habilitadas (no es el caso). */
    private fun systemContextViaReflection(): Context? = try {
        val activityThreadClass = Class.forName("android.app.ActivityThread")
        val activityThread = activityThreadClass.getMethod("systemMain").invoke(null)
        activityThreadClass.getMethod("getSystemContext").invoke(activityThread) as Context
    } catch (_: Exception) {
        null
    }
}

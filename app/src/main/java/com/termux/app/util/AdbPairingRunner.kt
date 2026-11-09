package com.termux.app.util

import android.content.Context

/**
 * Lógica real de emparejar+conectar ADB inalámbrico, compartida entre [ConfigFragment]
 * (diálogo normal) y [com.termux.app.AdbPairingOverlayService] (ventana flotante) — antes vivía
 * duplicada solo dentro de `ConfigFragment.runAdbPairing`. Ver ``: el pedido
 * del usuario fue que el código de emparejamiento se pueda escribir también desde una ventana
 * flotante (sin tener que volver a Kairos mientras se mira Ajustes → Depuración inalámbrica), no
 * solo desde el diálogo en pantalla completa de Permisos.
 *
 * No toca `settings`/`device_config` (eso es [PhantomProcessKillerHelper.runFix], un flujo
 * distinto para el killer de procesos fantasma) — esto es solo pair+connect genérico, reusable
 * por cualquier [AdbLocalProvider].
 */
object AdbPairingRunner {

    data class Result(val ok: Boolean, val message: String, val detail: String)

    /**
     * Corre en un hilo de fondo propio — [onDone] se invoca desde ESE hilo, quien llame debe
     * volver al hilo principal antes de tocar la UI. Mantiene el snapshot de
     * [PermissionsNotifier] visible durante toda la operación y lo limpia al terminar (éxito o
     * error), igual que el diálogo normal.
     */
    @JvmStatic
    fun pairAndConnect(
        context: Context,
        pairingPort: String,
        code: String,
        connectPort: String,
        onDone: (Result) -> Unit
    ) {
        Thread {
            PermissionsNotifier.showPairingSnapshot(context, pairingPort, code, connectPort)
            val u = ManagerNativeUtils
            val (installRc, _, installErr) = u.runShell("pkg install -y android-tools", 60)
            if (installRc != 0) {
                PermissionsNotifier.clear(context)
                onDone(Result(false, "No se pudo instalar android-tools", installErr.takeLast(200)))
                return@Thread
            }
            u.runShell("adb pair 127.0.0.1:$pairingPort $code", 20)
            // Hallazgo real (2026-10-31): antes había que leer y tipear el
            // "IP address & Port" persistente de Ajustes → Depuración inalámbrica a mano, 3
            // campos en total — pedido explícito del usuario: 2 alcanza, igual que Shizuku en
            // Android 15+ detecta el puerto solo con activar la depuración y tocar un botón.
            // Se reusa la MISMA técnica ya probada y en producción de
            // PhantomProcessKillerHelper.runAutoDetectFix() (adoptada de referencia/
            // ciberseguridad/i-Haklab-master/phantom-ps, ver docs/referencias/REFERENCIA_
            // IHAKLAB.md) en vez de mDNS (sin evidencia de que el adb de Termux lo soporte de
            // verdad sobre loopback) — nmap contra el rango efímero + probar `adb connect`
            // contra cada puerto abierto hasta que uno acepte.
            var resolvedPort = connectPort
            var connectOut = ""
            var connectErr = ""
            if (resolvedPort.isBlank()) {
                if (!PhantomProcessKillerHelper.isNmapAvailable()) {
                    PhantomProcessKillerHelper.installNmap { }
                }
                val (_, scanOut, _) = u.runShell("nmap -sT -p 10000-65535 --open -T4 127.0.0.1", 30)
                val candidatePorts = Regex("(\\d+)/tcp\\s+open").findAll(scanOut).map { it.groupValues[1] }.toList()
                for (candidate in candidatePorts) {
                    val (_, out, err) = u.runShell("adb connect 127.0.0.1:$candidate", 10)
                    // Hallazgo real de revisión automática (2026-10-31, "untrusted local
                    // endpoint accepted as adbd"): un puerto abierto cualquiera en el rango
                    // efímero puede responder "connected to" sin ser de verdad el adbd de
                    // Depuración inalámbrica (cualquier otro proceso en ese puerto que acepte
                    // el protocolo ADB-TLS igual lo haría) — se confirma de verdad pidiendo la
                    // identidad real vía `adb shell id`: tiene que devolver uid=2000(shell)
                    // (shell real) o uid=0(root) — nunca otra cosa.
                    if (out.contains("connected to", ignoreCase = true)) {
                        val (_, idOut, _) = u.runShell("adb -s 127.0.0.1:$candidate shell id", 10)
                        if (idOut.contains("uid=2000") || idOut.contains("uid=0(")) {
                            resolvedPort = candidate
                            connectOut = out
                            connectErr = err
                            break
                        }
                        u.runShell("adb disconnect 127.0.0.1:$candidate", 5)
                    }
                }
            } else {
                val (_, out, err) = u.runShell("adb connect 127.0.0.1:$resolvedPort", 20)
                connectOut = out
                connectErr = err
            }
            if (resolvedPort.isBlank()) {
                PermissionsNotifier.clear(context)
                onDone(Result(false, "No se pudo detectar el puerto de conexión automáticamente", "El escaneo no encontró ningún puerto que aceptara la conexión — ingresá el puerto a mano (Ajustes → Depuración inalámbrica → IP y puerto)"))
                return@Thread
            }
            // El serial real tras `adb connect` es "127.0.0.1:<connectPort>", nunca el
            // "localhost:5555" de ejemplo del constructor sin argumentos (bug real encontrado
            // 2026-10-26,) — connectPort ya se conoce acá, se usa directo.
            val ok = AdbLocalProvider(serial = "127.0.0.1:$resolvedPort").isAvailable()
            PermissionsNotifier.clear(context)
            if (ok) {
                PermissionsNotifier.saveSession(context, pairingPort, resolvedPort)
                onDone(Result(true, "Emparejado y conectado", ""))
            } else {
                onDone(Result(false, "No se pudo confirmar la conexión", (connectOut + "\n" + connectErr).trim().takeLast(200)))
            }
        }.start()
    }

}

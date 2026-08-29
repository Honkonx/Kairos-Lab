package com.termux.app.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import com.termux.shared.termux.TermuxConstants

/**
 * Ruta "sin PC" para desactivar el phantom process killer de Android 12+ cuando el
 * dispositivo no está rooteado — pedido explícito del usuario, ronda 51 (ver docs/humano/humano43.md
 * y docs/humano/humano44.md). Confirmado con 3 fuentes independientes (droidwin.com +
 * referencia/termux/termux-oracle-main/docs/android/wireless-debugging.md +
 * referencia/interfaz/openclaw-android-main/docs/disable-phantom-process-killer.md) que Android 11+
 * permite emparejar ADB completamente desde el propio dispositivo vía "Depuración
 * inalámbrica" — sin cable, sin PC, sin Shizuku. La limitación dura (también confirmada): el
 * puerto de emparejamiento, el código de 6 dígitos y el puerto de conexión SOLO Android los
 * muestra en sus propias pantallas nativas de Ajustes — no hay API pública para leerlos por
 * código, el usuario siempre tiene que transcribirlos a mano una vez (ver el diálogo guiado de
 * 3 pasos en MonitorFragment.kt).
 *
 * Los 2 comandos `device_config` de [buildFixCommands] son refuerzo documentado en
 * referencia/emuladores/XoDos2-main/phantom.md — algunos dispositivos necesitan repetir el fix completo
 * después de reiniciar, por eso el botón en Monitor queda siempre disponible para volver a
 * correrlo (no se implementa reintento automático, alcanza con que sea fácil de repetir).
 *
 * [runAutoDetectFix] es una tercera vía, BETA, pedida explícitamente por el usuario en la
 * ronda 52 (ver docs/humano/humano44.md): en vez de pedir el puerto de conexión a mano, lo detecta
 * con `nmap` — pieza adoptada de `referencia/ciberseguridad/i-Haklab-master/phantom-ps`.
 *
 * [BACKGROUND_SURVIVAL_COMMANDS] (ronda 53, ver docs/humano/humano46.md) suma 4 comandos más a la
 * MISMA sesión ADB que ya abren [runFix]/[runAutoDetectFix] — pieza adoptada de
 * `ver/fix-termux-limits-main/fix-termux-limits.sh` (MIT). Atacan el mismo problema de fondo
 * (Android matando procesos de Termux en segundo plano) por otros 3 mecanismos que el
 * phantom process killer no cubre (Doze/App Standby, appops, estado "inactivo"), y como el
 * usuario ya pasó por el único paso manual (emparejar), correrlos en la misma sesión no
 * cuesta ningún paso extra.
 */
object PhantomProcessKillerHelper {

    const val PHANTOM_SETTING_KEY = "settings_enable_monitor_phantom_procs"
    // "device_config get/put" toma namespace y key como 2 argumentos separados por espacio
    // (no "namespace/key" con barra) — confirmado contra el uso real en buildFixCommands()/
    // runAutoDetectFix() y en ver/fix-termux-limits-main/fix-termux-limits.sh.
    private const val MAX_PHANTOM_PROCESSES_NAMESPACE = "activity_manager"
    private const val MAX_PHANTOM_PROCESSES_FLAG = "max_phantom_processes"

    /**
     * Comandos de refuerzo — pieza adoptada de `ver/fix-termux-limits-main/fix-termux-limits.sh`
     * (MIT). Corren DESPUÉS de los `device_config` de [buildFixCommands] y ANTES de
     * `adb disconnect` (ver [verifyAndDisconnect]) — atacan el mismo problema (Android
     * matando procesos de Termux en segundo plano) por 3 vías que el phantom process killer
     * no cubre: exclusión de Doze/App Standby vía ADB (sin el diálogo del sistema que
     * BatteryRestrictionHelper.kt sí necesita), permisos de `appops` independientes de los
     * declarados en el manifest, y sacar a la app del estado "inactivo" de inmediato.
     * `TermuxConstants.TERMUX_PACKAGE_NAME` es literalmente "com.termux" — el paquete real de
     * Kairos (mismo `sharedUserId` que Termux original), confirmado con `app/build.gradle`
     * antes de portar el script — se usa la constante compartida en vez de repetir el string.
     */
    private val BACKGROUND_SURVIVAL_COMMANDS = listOf(
        "adb shell \"dumpsys deviceidle whitelist +${TermuxConstants.TERMUX_PACKAGE_NAME}\"",
        "adb shell \"cmd appops set ${TermuxConstants.TERMUX_PACKAGE_NAME} RUN_IN_BACKGROUND allow\"",
        "adb shell \"cmd appops set ${TermuxConstants.TERMUX_PACKAGE_NAME} WAKE_LOCK allow\"",
        "adb shell \"am set-inactive ${TermuxConstants.TERMUX_PACKAGE_NAME} false\""
    )

    /**
     * Abre Opciones de desarrollador. No existe una constante pública documentada para saltar
     * directo a "Depuración inalámbrica" en todos los fabricantes — abrir Opciones de
     * desarrollador y que el usuario toque "Depuración inalámbrica" desde ahí es lo correcto y
     * seguro (mismo criterio de intent-con-fallback que OverlayPermissionHelper.kt /
     * BatteryRestrictionHelper.kt: verificar con queryIntentActivities() antes de lanzar).
     *
     * `FLAG_ACTIVITY_NEW_TASK` es obligatorio acá (bug real reportado, ver docs/humano/humano56.md):
     * sin este flag, Ajustes se abre DENTRO de la misma tarea/back-stack de Kairos — para
     * escribir el código de emparejamiento el usuario tiene que "retroceder" varias pantallas
     * de Ajustes, y ese retroceso puede volver a Kairos y perder el diálogo/los campos ya
     * completados. Con el flag, Ajustes abre como tarea propia (igual que ya hacían
     * `tryGeneralSettings()` acá abajo y los intents de fabricante en
     * `BatteryRestrictionHelper.kt`) — el usuario puede ir y volver con Recientes sin tocar el
     * estado de Kairos.
     */
    @JvmStatic
    fun openDeveloperOptions(activity: Activity) {
        val devSettingsIntent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        if (isIntentAvailable(activity, devSettingsIntent)) {
            try {
                activity.startActivity(devSettingsIntent)
                return
            } catch (_: Exception) {
                // Sigue al fallback de abajo.
            }
        }
        tryGeneralSettings(activity)
    }

    private fun tryGeneralSettings(activity: Activity) {
        try {
            val intent = Intent(Settings.ACTION_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            activity.startActivity(intent)
        } catch (_: Exception) {
            // Sin nada más que intentar de este lado.
        }
    }

    @JvmStatic
    fun isDeveloperOptionsEnabled(context: Context): Boolean =
        try {
            Settings.Global.getInt(
                context.contentResolver,
                Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,
                0
            ) != 0
        } catch (_: Exception) {
            false
        }

    /**
     * Compartido por MonitorFragment (diagnóstico) y WizardPhantomProcessFragment (paso
     * dedicado del wizard, ronda 2026-08-04 — ver docs/humano53.md/humano54.md): si Opciones de
     * desarrollador todavía no están activas, primero explica cómo activarlas (7 toques en
     * "Número de compilación") antes de dejar seguir a cualquiera de los 2 flujos automáticos
     * (guiado o beta). Antes vivía duplicado como método privado en MonitorFragment —
     * promovido acá cuando el wizard pasó a necesitar el mismo gate, para no repetir el mismo
     * diálogo en 2 archivos.
     */
    @JvmStatic
    fun ensureDeveloperOptionsThen(activity: Activity, onReady: () -> Unit) {
        if (!isDeveloperOptionsEnabled(activity)) {
            androidx.appcompat.app.AlertDialog.Builder(activity)
                .setTitle("Activá Opciones de desarrollador primero")
                .setMessage(
                    "Andá a Ajustes > Acerca del teléfono (o Información del dispositivo) y " +
                        "tocá 7 veces seguidas \"Número de compilación\" — vas a ver un aviso " +
                        "de que ya sos desarrollador. Después volvé acá y tocá de nuevo la " +
                        "misma opción."
                )
                .setPositiveButton("Abrir Ajustes") { _, _ -> openDeveloperOptions(activity) }
                .setNegativeButton("Cerrar", null)
                .show()
            return
        }
        onReady()
    }

    /**
     * Tutorial 100% manual — compartido por MonitorFragment y WizardPhantomProcessFragment
     * (antes duplicado en los 2 archivos). Reescrito (ver docs/humano/humano56.md, bug real
     * reportado con captura de pantalla): el usuario mostró que su dispositivo tiene un toggle
     * NATIVO en Opciones de desarrollador — "Desactivar restricciones de procesos secundarios"
     * — que hace exactamente lo mismo que todo el flujo ADB, con un solo toque, sin puertos ni
     * códigos, presente en muchas marcas/modelos. Antes este tutorial solo mostraba los
     * comandos ADB (el camino más lento) como si fuera la única vía manual — ahora el toggle
     * nativo es la PRIMERA recomendación, los comandos ADB quedan como respaldo para
     * dispositivos que no lo tengan.
     */
    @JvmStatic
    fun showManualTutorialDialog(activity: Activity) {
        val commandsText = buildFixCommands(
            "<puerto_emparejamiento>", "<código_6_dígitos>", "<puerto_conexión>"
        ).joinToString("\n")
        val message = "MÁS RÁPIDO (muchas marcas y modelos, sin ADB):\n\n" +
            "1. Abrí Opciones de desarrollador.\n" +
            "2. Buscá una opción con un nombre parecido a \"Desactivar restricciones de " +
            "procesos secundarios\" (a veces aparece como \"Restricciones de procesos en " +
            "segundo plano\" u otro nombre según la marca) y activala.\n\n" +
            "Listo — no hace falta ADB, puertos ni códigos.\n\n" +
            "SI TU DISPOSITIVO NO TIENE ESA OPCIÓN, alternativa por ADB (Depuración " +
            "inalámbrica):\n\n" +
            "1. Si no tenés Opciones de desarrollador activas: Ajustes > Acerca del " +
            "teléfono > tocá 7 veces \"Número de compilación\".\n\n" +
            "2. Ajustes > Opciones de desarrollador > activá \"Depuración inalámbrica\".\n\n" +
            "3. Tocá \"Depuración inalámbrica\" para entrar y, dentro, tocá \"Vincular " +
            "dispositivo con código de emparejamiento\" — vas a ver un puerto y un código " +
            "de 6 dígitos.\n\n" +
            "4. Anotá también el puerto de conexión que aparece arriba de la pantalla " +
            "principal de Depuración inalámbrica, junto a la IP — es distinto del puerto " +
            "de emparejamiento.\n\n" +
            "5. Abrí una terminal (dentro de Kairos o Termux normal) y corré, en orden, " +
            "reemplazando los 3 valores entre <>:\n\n$commandsText\n\n" +
            "6. Si el último \"settings get\" devuelve \"false\", el fix quedó aplicado."
        androidx.appcompat.app.AlertDialog.Builder(activity)
            .setTitle("Desactivar restricciones de procesos")
            .setMessage(message)
            .setNeutralButton("Abrir Opciones de desarrollador") { _, _ -> openDeveloperOptions(activity) }
            .setPositiveButton("Copiar comandos ADB") { _, _ ->
                val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("comandos phantom killer", commandsText))
                android.widget.Toast.makeText(activity, "Comandos copiados", android.widget.Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    /**
     * Lista de comandos en orden — usada tanto por [runFix] (ejecución real) como por el
     * diálogo de tutorial manual de MonitorFragment.kt (texto copiable, con placeholders
     * "<puerto_emparejamiento>"/"<código_6_dígitos>"/"<puerto_conexión>" cuando el usuario
     * todavía no completó los campos).
     *
     * `pkg update -y` primero (bug real reportado, ver docs/humano/humano61.md: "no se pudo
     * instalar nmap") — en un bootstrap de Termux recién extraído, el índice local de
     * paquetes todavía no está poblado; `pkg install <lo que sea>` sin actualizar antes puede
     * fallar con "Unable to locate package" aunque el paquete exista de verdad en los repos.
     * Mismo problema (y mismo fix) ya confirmado en `EntornoNative.kt` (ver docs/humano/humano57.md).
     */
    @JvmStatic
    fun buildFixCommands(pairingPort: String, code: String, connectPort: String): List<String> = listOf(
        "pkg update -y",
        "pkg install -y android-tools",
        "adb pair 127.0.0.1:$pairingPort $code",
        "adb connect 127.0.0.1:$connectPort",
        "adb shell \"settings put global $PHANTOM_SETTING_KEY false\"",
        "adb shell \"device_config set_sync_disabled_for_tests persistent\"",
        "adb shell \"device_config put activity_manager max_phantom_processes 2147483647\"",
        "adb disconnect"
    )

    /**
     * Corre [buildFixCommands] en orden, vía el mismo patrón de ProcessBuilder que ya usa
     * ModuleController (bash -c por comando, applyTermuxEnv() para resolver "adb"/"pkg" desde
     * $PREFIX/bin). No confía en el exit code de "adb shell settings put" para reportar éxito
     * — antes de desconectar, vuelve a leer el valor real con "settings get" y solo reporta
     * éxito si la respuesta es literalmente "false" (mismo criterio anti-checkpoint-falso que
     * decodeExitSignal()/isTermuxBinaryAvailable() en ProcessBuilderExt.kt).
     *
     * [onOutput]/[onDone] se invocan desde el hilo en background que crea esta función — quien
     * llame debe volver al hilo principal antes de tocar la UI.
     */
    @JvmStatic
    fun runFix(
        pairingPort: String,
        code: String,
        connectPort: String,
        onOutput: (String) -> Unit,
        onDone: (success: Boolean) -> Unit
    ) {
        Thread {
            // dropLast(1): el último comando de buildFixCommands() es "adb disconnect" —
            // verifyAndDisconnect() ya lo corre después de leer el resultado real, no antes.
            val setupCommands = buildFixCommands(pairingPort, code, connectPort).dropLast(1)
            for (command in setupCommands) {
                onOutput("$ $command")
                val (ok, output) = runShellCommand(command)
                if (output.isNotBlank()) onOutput(output)
                if (!ok) onOutput("[AVISO] El comando anterior devolvió error — se sigue con el resto igual.")
            }
            onDone(verifyAndDisconnect(onOutput))
        }.start()
    }

    /**
     * Vía BETA — pieza adoptada de `referencia/ciberseguridad/i-Haklab-master/.deb/.../phantom-ps` (ver
     * docs/referencias/REFERENCIA_IHAKLAB.md, "Actualización 2026-08-03"): en vez de pedirle al usuario
     * que lea y transcriba el puerto de conexión (el dato manual más propenso a error de los
     * 3, porque cambia cada vez que se activa Wi-Fi/depuración), lo detecta solo con `nmap`
     * contra la IP local del propio teléfono, probando `adb connect` contra cada puerto
     * abierto encontrado hasta que uno acepte la conexión — reduce el dato manual real a solo
     * puerto de emparejamiento + código. Marcada "beta" porque el rango de escaneo
     * (10000-65535) puede tardar varios segundos y, a diferencia de la vía guiada normal, no
     * hay ninguna fuente que confirme que el puerto real de Depuración inalámbrica esté
     * siempre en ese rango en todos los fabricantes.
     *
     * Requiere el paquete `nmap` — [installNmap] debe llamarse ANTES de este flujo (no acá
     * adentro), para que la instalación se muestre como un paso propio ("instalando paquete
     * necesario") en vez de quedar escondida dentro de este log.
     */
    @JvmStatic
    fun runAutoDetectFix(
        pairingPort: String,
        code: String,
        onOutput: (String) -> Unit,
        onDone: (success: Boolean) -> Unit
    ) {
        Thread {
            for (command in listOf("pkg update -y", "pkg install -y android-tools", "adb pair 127.0.0.1:$pairingPort $code")) {
                onOutput("$ $command")
                val (ok, output) = runShellCommand(command)
                if (output.isNotBlank()) onOutput(output)
                if (!ok) onOutput("[AVISO] El comando anterior devolvió error — se sigue con el resto igual.")
            }

            val scanCommand = "nmap -sT -p 10000-65535 --open -T4 127.0.0.1"
            onOutput("$ $scanCommand")
            val (_, scanOutput) = runShellCommand(scanCommand)
            onOutput(scanOutput)
            val candidatePorts = Regex("(\\d+)/tcp\\s+open").findAll(scanOutput).map { it.groupValues[1] }.toList()
            if (candidatePorts.isEmpty()) {
                onOutput("[ERROR] nmap no encontró ningún puerto abierto — confirmá que Depuración inalámbrica siga activa e intentá de nuevo.")
                onDone(false)
                return@Thread
            }

            var connected = false
            for (port in candidatePorts) {
                val connectCommand = "adb connect 127.0.0.1:$port"
                onOutput("$ $connectCommand")
                val (_, connectOutput) = runShellCommand(connectCommand)
                onOutput(connectOutput)
                if (connectOutput.contains("connected to", ignoreCase = true)) {
                    connected = true
                    break
                }
            }
            if (!connected) {
                onOutput("[ERROR] Ninguno de los ${candidatePorts.size} puertos detectados aceptó la conexión ADB — probá la opción guiada normal (puerto manual).")
                onDone(false)
                return@Thread
            }

            for (command in listOf(
                "adb shell \"settings put global $PHANTOM_SETTING_KEY false\"",
                "adb shell \"device_config set_sync_disabled_for_tests persistent\"",
                "adb shell \"device_config put activity_manager max_phantom_processes 2147483647\""
            )) {
                onOutput("$ $command")
                val (_, output) = runShellCommand(command)
                if (output.isNotBlank()) onOutput(output)
            }

            onDone(verifyAndDisconnect(onOutput))
        }.start()
    }

    @JvmStatic
    fun isNmapAvailable(): Boolean = isTermuxBinaryAvailable("nmap")

    /**
     * Instala `nmap` (paquete real de Termux, requerido por [runAutoDetectFix]) si todavía no
     * está — se llama ANTES de mostrar el paso 1 de la vía beta, para que el usuario vea
     * "instalando paquete necesario" como su propio paso en vez de que quede escondido dentro
     * del log de ejecución del paso final.
     *
     * `pkg update -y` primero — bug real reportado ("no se pudo instalar nmap", ver
     * docs/humano/humano61.md): en un bootstrap recién extraído el índice de paquetes local
     * todavía no está poblado, así que `pkg install nmap` puede fallar con "Unable to locate
     * package" sin que el paquete tenga nada de malo. `pkg update` no debería fallar nunca en
     * un dispositivo con red — pero si falla igual (mirror caído), no corta el flujo, se
     * intenta instalar igual por si el índice ya estaba bien de una corrida anterior.
     */
    @JvmStatic
    fun installNmap(onOutput: (String) -> Unit): Boolean {
        if (isNmapAvailable()) return true
        onOutput("$ pkg update -y")
        val (_, updateOutput) = runShellCommand("pkg update -y")
        if (updateOutput.isNotBlank()) onOutput(updateOutput)
        onOutput("$ pkg install -y nmap")
        val (ok, output) = runShellCommand("pkg install -y nmap")
        if (output.isNotBlank()) onOutput(output)
        return ok && isNmapAvailable()
    }

    /**
     * Compartido por [runFix] y [runAutoDetectFix]: corre [BACKGROUND_SURVIVAL_COMMANDS],
     * confirma el resultado real de AMBOS ajustes (`settings_enable_monitor_phantom_procs` Y
     * `max_phantom_processes` — antes solo se verificaba el primero, hueco real señalado por
     * `ver/fix-termux-limits-main`, que sí verifica el segundo) antes de reportar éxito, y
     * cierra la sesión ADB. Nunca confía en el exit code de los comandos "put"/"set" —
     * siempre relee el valor real con "get".
     */
    private fun verifyAndDisconnect(onOutput: (String) -> Unit): Boolean {
        for (command in BACKGROUND_SURVIVAL_COMMANDS) {
            onOutput("$ $command")
            val (ok, output) = runShellCommand(command)
            if (output.isNotBlank()) onOutput(output)
            if (!ok) onOutput("[AVISO] El comando anterior devolvió error — se sigue con el resto igual.")
        }

        val verifySettingCommand = "adb shell \"settings get global $PHANTOM_SETTING_KEY\""
        onOutput("$ $verifySettingCommand")
        val (_, settingOutput) = runShellCommand(verifySettingCommand)
        onOutput(settingOutput)
        val settingVerified = settingOutput.trim() == "false"

        val verifyLimitCommand = "adb shell \"device_config get $MAX_PHANTOM_PROCESSES_NAMESPACE $MAX_PHANTOM_PROCESSES_FLAG\""
        onOutput("$ $verifyLimitCommand")
        val (_, limitOutput) = runShellCommand(verifyLimitCommand)
        onOutput(limitOutput)
        val limitVerified = limitOutput.trim() == "2147483647"

        onOutput("$ adb disconnect")
        runShellCommand("adb disconnect")
        return settingVerified && limitVerified
    }

    // Timeout + destroyForcibly() — bug real (auditoría 2026-08-13): waitFor() sin
    // timeout en comandos `adb`, mismo patrón ya corregido en ManagerNativeUtils/
    // TunnelManager/RemoteManager. Corre en un hilo de fondo propio (no bloquea UI
    // directo), pero sin esto el callback onDone de runFix()/runAutoDetectFix() nunca se
    // dispara si "adb connect"/"adb pair" se cuelga — el diálogo de progreso del
    // wizard/Monitor queda esperando para siempre. 25s: generoso para que adb
    // pair/connect completen en una red lenta, sin dejar la sesión colgada.
    private const val SHELL_COMMAND_TIMEOUT_MS = 25_000L

    private fun runShellCommand(command: String): Pair<Boolean, String> = try {
        val pb = ProcessBuilder(TERMUX_BASH_PATH, "-c", command)
        pb.applyTermuxEnv()
        pb.redirectErrorStream(true)
        val process = pb.start()
        val finished = process.waitFor(SHELL_COMMAND_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            false to "timeout"
        } else {
            val output = process.inputStream.bufferedReader().readText()
            (process.exitValue() == 0) to output.trim()
        }
    } catch (e: Exception) {
        false to (e.message ?: "error desconocido")
    }

    private fun isIntentAvailable(activity: Activity, intent: Intent): Boolean =
        try {
            activity.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).isNotEmpty()
        } catch (_: Exception) {
            false
        }
}

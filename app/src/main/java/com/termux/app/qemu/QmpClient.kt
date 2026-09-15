package com.termux.app.qemu

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStream

/**
 * Cliente QMP (QEMU Machine Protocol) — protocolo real de control en caliente de QEMU
 * (documentado en qemu.org/docs/interop/qmp-spec, `docs/interop/qmp-spec.txt` del propio
 * proyecto QEMU), implementado desde cero — mismo criterio que `VncClient.kt` (sin librería de
 * terceros, protocolo simple de una línea JSON por mensaje).
 *
 * Hallazgo real de auditoría (`docs/mini-pc/AUDITORIA_COMUNICACION_2026-09-08.md` hallazgo #2,
 * fuente: `Vectras-VM-Emu-Android` `QmpClient.java`/`QmpSender.java`): antes Kairos solo podía
 * arrancar/matar el proceso QEMU entero (sesión tmux completa) — sin control en caliente
 * (apagado graceful, pausar/reanudar, captura de framebuffer, cambio de medios). `modulos/qemu.sh`
 * (`run_vm.sh`, v1.4.0) ahora arranca QEMU con `-qmp unix:<ruta>,server,nowait` en AMBOS modos de
 * boot (console y vnc) — este cliente conecta a ese socket.
 *
 * Conexión por **socket unix** (`android.net.LocalSocket` + `Namespace.FILESYSTEM`), no TCP —
 * mismo criterio de seguridad que el VNC de QEMU (ver `VncClient.kt`: "Allow connections only
 * from localhost using localsocket without a password", `StartVM.getDisplayParams()` de
 * Vectras-VM-Emu-Android). Kairos corre en el mismo proceso/UID que el rootfs de Termux (ver
 * CLAUDE.md "Communication: Direct Java method calls"), así que el archivo de socket que crea
 * QEMU dentro de `$HOME/scripts/qemu/` es directamente accesible acá.
 *
 * Alcance real (honesto, sin prometer de más — mismo criterio que `modulos/qemu.sh`): implementa
 * los comandos QMP de mayor valor real para Kairos — `system_powerdown` (apagado graceful),
 * `stop`/`cont` (pausar/reanudar sin perder estado), `screendump` (captura de framebuffer a
 * `.ppm` sin decodificar el stream VNC), `query-block` + `eject`/`blockdev-change-medium`
 * (hot-swap de medios — cambiar un ISO/imagen sin reiniciar la VM, la funcionalidad de mayor
 * valor del hallazgo original). No implementa `migrate`/snapshots ni el catálogo completo de
 * comandos QMP — fuera de alcance de esta ronda, quedan como mejora futura documentada.
 *
 * Uso: cada acción abre su propia conexión corta (connect → comando → close) en vez de mantener
 * una sesión QMP persistente durante todo el ciclo de vida del Fragment — los comandos de esta
 * clase son acciones puntuales de UI (un tap en un botón), no el hot path continuo de mouse/
 * teclado que sí justificaría una conexión persistente (contra lo que documenta el comentario
 * real de Emu-Android sobre backoff agresivo, citado en la auditoría — ese caso es distinto,
 * QMP ahí controla el propio input). Todos los métodos son SÍNCRONOS (bloquean el hilo que los
 * llama, igual que `ManagerNativeUtils.runShell`) — el caller es responsable de correrlos fuera
 * del hilo principal.
 */
class QmpClient(private val socketPath: String) {

    data class BlockDevice(val device: String, val filePath: String?, val removable: Boolean)

    companion object {
        private const val TAG = "QmpClient"

        // Round-trip real esperado (comando local, mismo dispositivo) — unos pocos ms. 5s es
        // generoso para no fallar por una VM momentáneamente ocupada en el momento exacto del
        // comando, sin quedar colgado indefinidamente si el proceso QEMU murió a mitad de una
        // respuesta (socket queda abierto por el lado del kernel pero sin nadie que escriba).
        private const val READ_TIMEOUT_MS = 5000

        /** true si el archivo de socket existe — proxy barato de "¿hay una VM corriendo con QMP
         * habilitado ahora mismo?" sin necesidad de conectar (mismo patrón que
         * `MainVNCActivity.java` de Vectras, `FileUtils.isFileExists(qmpSocketPath)`, citado en
         * la auditoría como "liveness barato"). No confirma que QEMU siga vivo del otro lado
         * (un crash puede dejar el archivo huérfano, ver `rm -f` real en `run_vm.sh` antes de
         * cada boot) — solo evita intentar conectar cuando NUNCA se arrancó ninguna VM. */
        fun socketExists(path: String): Boolean = File(path).exists()
    }

    private var socket: LocalSocket? = null
    private var reader: BufferedReader? = null
    private var writer: OutputStream? = null

    val isConnected: Boolean get() = socket?.isConnected == true

    /**
     * Conecta y negocia capacidades. QEMU manda un banner de saludo JSON apenas se conecta un
     * cliente QMP (`{"QMP": {"version": {...}, "capabilities": []}}`) y, hasta que no se manda
     * `qmp_capabilities`, el servidor rechaza cualquier otro comando (protocolo real, no una
     * particularidad de Kairos — ver qmp-spec.txt §2.2/§2.3). Devuelve true solo si TODO el
     * handshake tuvo éxito.
     */
    fun connect(): Boolean {
        return try {
            val sock = LocalSocket()
            sock.connect(LocalSocketAddress(socketPath, LocalSocketAddress.Namespace.FILESYSTEM))
            sock.soTimeout = READ_TIMEOUT_MS
            socket = sock
            reader = BufferedReader(InputStreamReader(sock.inputStream, Charsets.UTF_8))
            writer = sock.outputStream
            if (readResponseLine() == null) { close(); return false } // banner — se descarta el contenido
            if (sendRaw("qmp_capabilities") == null) { close(); return false }
            true
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo conectar/negociar QMP en $socketPath", e)
            close()
            false
        }
    }

    fun close() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null; reader = null; writer = null
    }

    // ── Lectura — salta eventos asíncronos (QEMU puede empujar "STOP"/"RESET"/etc. en
    // cualquier momento, no solo como respuesta directa a un comando nuestro) hasta encontrar
    // una línea que sea de verdad la respuesta al comando ("return"/"error") o el banner inicial
    // (que no tiene "event" tampoco, así que también se devuelve tal cual). ────────────────────
    private fun readResponseLine(): JSONObject? {
        val r = reader ?: return null
        while (true) {
            val line = r.readLine() ?: return null
            if (line.isBlank()) continue
            val obj = try { JSONObject(line) } catch (_: Exception) { continue }
            if (obj.has("event")) continue
            return obj
        }
    }

    /** Manda un comando y devuelve la respuesta CRUDA completa (con "return"/"error" adentro, tal
     * cual la mandó QEMU) — null si falló la escritura/lectura o el servidor devolvió "error"
     * (logueado con el motivo real antes de devolver null). El caller extrae el valor de
     * "return" con la forma que corresponda (objeto, array, string — varía por comando). */
    @Synchronized
    private fun sendRaw(execute: String, arguments: JSONObject? = null): JSONObject? {
        val w = writer ?: return null
        return try {
            val cmd = JSONObject().put("execute", execute)
            if (arguments != null) cmd.put("arguments", arguments)
            w.write((cmd.toString() + "\n").toByteArray(Charsets.UTF_8))
            w.flush()
            val resp = readResponseLine() ?: return null
            if (resp.has("error")) {
                Log.w(TAG, "QMP '$execute' devolvió error: ${resp.optJSONObject("error")}")
                null
            } else {
                resp
            }
        } catch (e: Exception) {
            Log.w(TAG, "QMP '$execute' falló", e)
            null
        }
    }

    // ── Comandos reales expuestos ─────────────────────────────────────────────────────────────

    /** Apagado graceful (ACPI powerdown, equivalente a apretar el botón de power del guest) —
     * distinto de matar el proceso QEMU entero (lo que ya hacía `QemuFragment.stopVm()`): le da
     * al guest la chance de cerrar servicios/journal limpio antes de terminar. */
    fun systemPowerdown(): Boolean = sendRaw("system_powerdown") != null

    /** Pausa la VM (todas las vCPUs) sin matar el proceso — el estado completo queda en RAM,
     * `cont()` la reanuda exactamente donde quedó. */
    fun stop(): Boolean = sendRaw("stop") != null

    /** Reanuda una VM pausada con [stop]. */
    fun cont(): Boolean = sendRaw("cont") != null

    /** Captura el framebuffer actual a un archivo `.ppm` en disco — directo desde QEMU, sin pasar
     * por el stream RFB del visor VNC (ni requiere que el visor esté abierto). [filename] debe
     * ser una ruta absoluta escribible por el propio proceso QEMU (mismo `$HOME` que Kairos). */
    fun screendump(filename: String): Boolean =
        sendRaw("screendump", JSONObject().put("filename", filename)) != null

    /**
     * Enumera los block devices reales de la VM en curso — `query-block` devuelve el "device"
     * (identificador legacy real que QEMU asignó, sea el `id=` explícito de `-drive` o uno
     * autogenerado como `ide0-cd0`/`ide0-hd0` para discos sin id explícito) y, si hay un medio
     * insertado, su ruta de archivo actual. Deliberadamente NO se asume ningún id fijo (ej.
     * "hd0") — `modulos/qemu.sh` solo pone `id=hd0` explícito en la rama aarch64 (ver
     * `run_vm.sh`, PROTEGIDO — no se tocó esa lógica para no arriesgar el boot x86_64/q35 que ya
     * funciona); consultar en vivo vía QMP es lo que permite que el hot-swap funcione en AMBAS
     * arquitecturas sin depender de ese detalle interno del script.
     */
    fun queryBlockDevices(): List<BlockDevice> {
        val resp = sendRaw("query-block") ?: return emptyList()
        val arr: JSONArray = resp.optJSONArray("return") ?: return emptyList()
        val list = mutableListOf<BlockDevice>()
        for (i in 0 until arr.length()) {
            val entry = arr.optJSONObject(i) ?: continue
            val device = entry.optString("device")
            if (device.isBlank()) continue // sin "device" legacy (solo -blockdev puro) — fuera de alcance de este cliente
            val inserted = entry.optJSONObject("inserted")
            val filePath = inserted?.optString("file")?.ifBlank { null }
            list.add(BlockDevice(device, filePath, entry.optBoolean("removable", false)))
        }
        return list
    }

    /** Expulsa el medio de [device] (sin insertar uno nuevo) — QAPI real: `eject`
     * (`qapi/block.json`), argumento `device` (no `id`, para que coincida con lo que devuelve
     * [queryBlockDevices]). [force] fuerza la expulsión aunque el guest tenga el medio en uso. */
    fun ejectMedia(device: String, force: Boolean = false): Boolean =
        sendRaw("eject", JSONObject().put("device", device).put("force", force)) != null

    /**
     * Cambia el medio de [device] por el archivo en [newFilePath] — hot-swap real sin reiniciar
     * la VM, la funcionalidad de mayor valor del hallazgo original de la auditoría. QAPI real:
     * `blockdev-change-medium` (reemplazo moderno del viejo comando `change`, que QEMU fue
     * deprecando/removiendo para este uso — ver qapi/block.json, `*format`/`*device` opcionales).
     * No hace falta expulsar antes con [ejectMedia] — `blockdev-change-medium` ya maneja el ciclo
     * expulsar+insertar internamente si el drive es removible y tenía algo insertado.
     */
    fun changeMedia(device: String, newFilePath: String, format: String? = null): Boolean {
        val args = JSONObject().put("device", device).put("filename", newFilePath)
        if (!format.isNullOrBlank()) args.put("format", format)
        return sendRaw("blockdev-change-medium", args) != null
    }
}

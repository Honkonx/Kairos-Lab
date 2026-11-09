package com.termux.app.util

import android.content.Context
import com.termux.shared.termux.TermuxConstants
import java.io.File

/**
 * Arranque y control del lado Android de un espejo/control de pantalla propio de Kairos.
 *
 * Patrón adaptado (con crédito, Apache-2.0) de scrcpy (Genymobile) — `referencia/pc-movil/scrcpy/app/src/server.c`
 * líneas 208-253 (`execute_server`): el cliente de escritorio hace `adb push` del jar a
 * `/data/local/tmp/scrcpy-server.jar` y lo ejecuta con
 * `adb -s <serial> shell CLASSPATH=<jar> app_process / com.genymobile.scrcpy.Server <version> <opciones>` — es
 * decir, un proceso Java lanzado con identidad `shell` (uid 2000). Kairos no tiene `adb push` del lado del
 * cliente: el jar REAL de scrcpy (sin modificar, `app/src/main/assets/screen/kairos-screen-server.jar`, ver
 * `NOTICE.md` ahí mismo) viene empaquetado en el APK y se extrae al HOME de Termux; lo que ejecuta ese jar es
 * un `PrivilegeProvider` con identidad shell (`PrivilegeProviders.kt`: `AdbLocalProvider`/`ShizukuRishProvider`),
 * nunca la app con su propio uid (sin esa identidad, `app_process` no puede usar las APIs ocultas que scrcpy
 * necesita para capturar pantalla).
 *
 * **Confirmado contra un dispositivo real (2026-10-13)**: el jar real corre, se conecta al socket
 * abstracto Unix `scrcpy_<scid>` que este mismo gateway escucha (`internal/screen/local.go`, sin red ni ADB,
 * todo en el mismo proceso Linux del teléfono), y un frame de video real llegó por `/v1/screen/ws` hasta un
 * cliente WebSocket — ver `docs/homelab/PANTALLA_ANDROID_PROPIA_2026-10-10.md` para el procedimiento completo
 * de la prueba. Sigue siendo un cimiento: decodificar el video en el panel (WebCodecs) y el ciclo de vida
 * completo desde la UI nativa (no solo por `screen.start`/`screen.stop` del gateway) faltan todavía.
 *
 * Ver también `docs/referencias/pc-movil/REFERENCIA_scrcpy.md` (auditoría de licencia y riesgos).
 */
class ScreenMirrorManager(
    private val privilege: PrivilegeRegistry,
    private val gatewayDir: File,
    private val home: String = TermuxConstants.TERMUX_HOME_DIR_PATH,
    /** Si el jar del servidor ya está extraído en el dispositivo. Inyectable para pruebas; la app real revisa un File. */
    private val jarExists: () -> Boolean = { File(home, "$JAR_SUBDIR/$JAR_NAME").isFile },
) {

    /** Resultado de preguntar si el mecanismo está listo. [provider] es el proveedor que se usaría, si hay uno. */
    data class Status(val ready: Boolean, val reason: String, val provider: String? = null)

    /**
     * [ready] = true solo significa "existe un proveedor con identidad shell Y el jar del servidor está en el
     * dispositivo, así que [start] se podría ejecutar" — NO significa que YA esté corriendo (ver [isRunning]).
     */
    fun status(): Status {
        val candidate = privilege.status().firstOrNull { it.available && it.id in SHELL_LEVEL_PROVIDERS }
            ?: return Status(false, "ningún proveedor con identidad shell disponible (adb inalámbrico o Shizuku)")
        if (!jarExists()) {
            return Status(false, "falta el servidor (kairos-screen-server.jar) en el dispositivo — extraelo desde assets/screen/", candidate.id)
        }
        return Status(true, "listo para iniciar", candidate.id)
    }

    /** Extrae `assets/screen/kairos-screen-server.jar` (y su LICENSE) a `$HOME/.kairos_screen/` si no está ya. */
    fun ensureJarExtracted(context: Context): Boolean {
        val dir = File(home, JAR_SUBDIR).apply { mkdirs() }
        val jar = File(dir, JAR_NAME)
        if (jar.isFile && jar.length() > 0) return true
        return try {
            context.assets.open("screen/kairos-screen-server.jar").use { input ->
                jar.outputStream().use { output -> input.copyTo(output) }
            }
            context.assets.open("screen/LICENSE-scrcpy.txt").use { input ->
                File(dir, "LICENSE-scrcpy.txt").outputStream().use { output -> input.copyTo(output) }
            }
            true
        } catch (_: Exception) {
            jar.delete()
            false
        }
    }

    /** El scid que `native/gateway` ya escribió en `<dir>/screen.scid` (8 dígitos hex) — null si el gateway no corrió todavía o el archivo no tiene ese formato exacto. */
    fun readScid(): String? = File(gatewayDir, "screen.scid").takeIf { it.isFile }?.readText()?.trim()?.takeIf { SCID_PATTERN.matches(it) }

    /**
     * Le dice a `internal/screen/local.go` (Go) qué conexiones esperar después de la de video en
     * ESTE arranque — mismo archivo que lee [readExpect] del lado Go (`ExpectFileName`). `control`
     * siempre está en la lista desde esta ronda (ver [start]); `audio` solo si el caller la pidió.
     * No falla [start] si no se puede escribir (ver el catch) — degradar a "Go asume solo video"
     * es preferible a bloquear el arranque entero por un problema de I/O menor.
     */
    private fun writeExpectFile(audio: Boolean) {
        val channels = buildList {
            if (audio) add("audio")
            add("control")
        }
        runCatching { File(gatewayDir, "screen.expect").writeText(channels.joinToString(",")) }
    }

    /** True si ya hay un proceso del servidor corriendo (`pgrep` por el nombre de la clase). */
    fun isRunning(): PrivilegeResult {
        val candidate = privilege.status().firstOrNull { it.available && it.id in SHELL_LEVEL_PROVIDERS }
            ?: return PrivilegeResult(false, "ningún proveedor con identidad shell disponible", 503)
        return privilege.run(candidate.id, "pgrep -f $SERVER_CLASS", 10)
    }

    /**
     * Arranca el servidor real EN SEGUNDO PLANO (`sh -c '... & '`, el comando vuelve enseguida: el servidor queda
     * corriendo aunque la llamada al proveedor ya haya terminado — mismo patrón confirmado a mano en el
     * dispositivo real). [maxSize]/[bitRate]/[maxFps] acotan el costo (CPU, batería, ancho de banda si hay túnel).
     *
     * [audio]/[videoCodec]/[audioCodec]/[orientation] (configurabilidad pedida por el usuario) — se
     * validan contra los valores REALES que acepta `Options.parse` del servidor (ver `VideoCodec`/`AudioCodec`
     * java enums y `Options.parseCaptureOrientation`, `referencia/pc-movil/scrcpy/server/.../Options.java`
     * líneas 361-374 y 674-693) ANTES de armar el comando: un nombre inválido ahí hace que el servidor explote
     * con `IllegalArgumentException` y `start()` nunca lo sabría (corre en segundo plano, `>/dev/null`).
     */
    fun start(
        maxSize: Int = 1280,
        bitRate: Int = 4_000_000,
        maxFps: Int = 15,
        audio: Boolean = false,
        videoCodec: String? = null,
        audioCodec: String? = null,
        orientation: String? = null,
    ): PrivilegeResult {
        val candidate = privilege.status().firstOrNull { it.available && it.id in SHELL_LEVEL_PROVIDERS }
            ?: return PrivilegeResult(false, "ningún proveedor con identidad shell disponible", 503)
        if (!jarExists()) return PrivilegeResult(false, "falta el servidor en el dispositivo", 404)
        val scid = readScid() ?: return PrivilegeResult(false, "el gateway no escribió screen.scid todavía (¿está corriendo?)", 404)
        if (videoCodec != null && videoCodec !in VIDEO_CODECS) return PrivilegeResult(false, "códec de video no soportado: $videoCodec", 400)
        if (audioCodec != null && audioCodec !in AUDIO_CODECS) return PrivilegeResult(false, "códec de audio no soportado: $audioCodec", 400)
        if (orientation != null && !ORIENTATION_PATTERN.matches(orientation)) return PrivilegeResult(false, "orientación inválida: $orientation", 400)
        val deviceJarPath = "${File(home, JAR_SUBDIR).path}/$JAR_NAME"
        // Acotado defensivamente: un valor absurdo (negativo o gigante) no debe poder pedirle al encoder algo
        // que lo cuelgue o agote batería — sigue aplicando aunque ahora screen.config SÍ puede pasar valores
        // reales (antes solo entraban por el default de la firma, nunca por fuera).
        val size = maxSize.coerceIn(128, 3840)
        val rate = bitRate.coerceIn(100_000, 50_000_000)
        val fps = maxFps.coerceIn(1, 60)
        // control=true SIEMPRE (2026-10-16,: control táctil real desde el
        // panel) — DesktopConnection.open() del servidor real abre una SEGUNDA conexión al mismo
        // socket cuando control=true (video, luego [audio], luego control, en ese orden exacto —
        // confirmado leyendo DesktopConnection.java). internal/screen/local.go (Go) necesita saber
        // de antemano cuántas conexiones más esperar después de la de video — por eso se escribe
        // screen.expect en el <dir> del gateway ANTES de lanzar el proceso real, nunca después (si
        // el archivo llegara tarde, Go podría interpretar la conexión de control como un segundo
        // video y pisar la fuente real a mitad de ciclo).
        writeExpectFile(audio)
        val args = mutableListOf(
            "scid=$scid", "log_level=info", "video=true", "audio=$audio", "control=true", "cleanup=false",
            "send_device_meta=true", "max_size=$size", "video_bit_rate=$rate", "max_fps=$fps",
        )
        videoCodec?.let { args += "video_codec=$it" }
        // audio_codec solo tiene sentido si audio=true — mandarlo con audio=false no rompe nada del lado del
        // servidor, pero no agrega valor y complica leer el comando armado al depurar.
        if (audio) audioCodec?.let { args += "audio_codec=$it" }
        orientation?.let { args += "capture_orientation=$it" }
        val cmd = buildServerCommand(deviceJarPath = deviceJarPath, args = args)
        return privilege.run(candidate.id, "sh -c '$cmd >/dev/null 2>&1 &'", 15)
    }

    /** Corta el servidor (`pkill` por el nombre de la clase, igual patrón que otros módulos de Kairos). */
    fun stop(): PrivilegeResult {
        val candidate = privilege.status().firstOrNull { it.available && it.id in SHELL_LEVEL_PROVIDERS }
            ?: return PrivilegeResult(false, "ningún proveedor con identidad shell disponible", 503)
        return privilege.run(candidate.id, "pkill -f $SERVER_CLASS", 10)
    }

    /** Un códec de video/audio real que el `MediaCodecList` del dispositivo soporta, con su encoder. */
    data class CodecEncoder(val codec: String, val encoder: String, val hwType: String)

    /** Una pantalla real del dispositivo (`DisplayManager`), con su id y resolución. */
    data class DisplayEntry(val id: Int, val size: String)

    data class CodecsInfo(
        val ok: Boolean,
        val reason: String = "",
        val videoEncoders: List<CodecEncoder> = emptyList(),
        val audioEncoders: List<CodecEncoder> = emptyList(),
        val displays: List<DisplayEntry> = emptyList(),
    )

    /**
     * Lanza el servidor real EN PRIMER PLANO (sin `&`, se espera a que termine) en modo `list_encoders`/
     * `list_displays` — NO arranca ningún espejo: `Options.getList()` (Options.java línea 282-284) hace que
     * `Server.main` solo imprima y `return` sin llamar a `scrcpy(options)` (Server.java líneas 247-270). No
     * necesita `scid` (default `-1`, "sin sesión") porque nunca abre el socket de pantalla.
     *
     * La salida real se imprime con `Ln.i(...)` directo al stdout real del proceso (`Ln.java`:
     * `disableSystemStreams()` solo anula `System.out`, pero `Ln` escribe por un `PrintStream` propio atado al
     * file descriptor de salida — confirmado leyendo `referencia/pc-movil/scrcpy/server/.../util/Ln.java`), así
     * que [PrivilegeRegistry.run] (que corre todo con `sh -c`) la captura igual que cualquier otro comando.
     */
    fun detectCodecs(): CodecsInfo {
        val candidate = privilege.status().firstOrNull { it.available && it.id in SHELL_LEVEL_PROVIDERS }
            ?: return CodecsInfo(false, "ningún proveedor con identidad shell disponible")
        if (!jarExists()) return CodecsInfo(false, "falta el servidor en el dispositivo")
        val deviceJarPath = "${File(home, JAR_SUBDIR).path}/$JAR_NAME"
        val cmd = buildServerCommand(
            deviceJarPath = deviceJarPath,
            args = listOf("log_level=info", "list_encoders=true", "list_displays=true", "cleanup=true"),
        )
        val r = privilege.run(candidate.id, cmd, 30)
        if (!r.ok) return CodecsInfo(false, r.output.take(500).ifBlank { "el servidor no devolvió nada (exit ${r.exitCode})" })
        return parseCodecsOutput(r.output).copy(ok = true)
    }

    /**
     * Arma el comando real que lanza el servidor, adaptado de `execute_server()` (server.c líneas 218-253):
     * `CLASSPATH=<jar> app_process / <clase> <version> <args...>`. Pura composición de texto.
     */
    fun buildServerCommand(
        deviceJarPath: String = DEFAULT_DEVICE_JAR_PATH,
        serverClass: String = SERVER_CLASS,
        version: String = SERVER_VERSION,
        args: List<String> = emptyList(),
    ): String {
        val parts = mutableListOf("CLASSPATH=$deviceJarPath", "app_process", "/", serverClass, version)
        parts += args
        return parts.joinToString(" ")
    }

    companion object {
        const val JAR_SUBDIR = ".kairos_screen"
        const val JAR_NAME = "kairos-screen-server.jar"
        const val DEFAULT_DEVICE_JAR_PATH = "/data/local/tmp/kairos-screen-server.jar"
        const val SERVER_CLASS = "com.genymobile.scrcpy.Server"

        /** Versión exacta que espera `Options.parse` (debe coincidir con la del jar compilado, ver NOTICE.md). */
        const val SERVER_VERSION = "5.0.1"

        /** Formato real de `screen.DeriveScid`/`%08x` del lado Go — 8 dígitos hexadecimales en minúscula. */
        val SCID_PATTERN = Regex("[0-9a-f]{8}")

        /** Proveedores con identidad shell (uid 2000) o superior — "app" (uid de la propia Kairos) nunca alcanza. */
        val SHELL_LEVEL_PROVIDERS = setOf("adb", "shizuku", "root")

        /** Nombres reales de `VideoCodec`/`AudioCodec` (enums Java, ver cita en [detectCodecs]) — los únicos que `Options.parse` acepta sin lanzar `IllegalArgumentException`. */
        val VIDEO_CODECS = setOf("h264", "h265", "av1", "vp8", "vp9")
        val AUDIO_CODECS = setOf("opus", "aac", "flac", "raw")

        /**
         * Formato real de `capture_orientation` (`Options.parseCaptureOrientation`): un valor de `Orientation`
         * (`0`/`90`/`180`/`270`/`flip0`/`flip90`/`flip180`/`flip270`), opcionalmente con un `@` adelante para
         * "bloquear" esa orientación (o solo `@` para bloquear en la orientación inicial del dispositivo).
         */
        val ORIENTATION_PATTERN = Regex("@|@?(0|90|180|270|flip0|flip90|flip180|flip270)")

        /**
         * `--video-codec=NOMBRE --video-encoder=NOMBRE ... (hw|sw|hybrid)` — una línea de
         * `LogUtils.buildVideoEncoderListMessage()`. El tipo `(hw|sw|hybrid)` solo existe desde Android 10
         * (`Build.VERSION.SDK_INT >= API_29`, ver cita en [detectCodecs]) — opcional en el regex para no perder
         * la línea entera en un dispositivo más viejo (minSdk de Kairos es 26).
         */
        private val ENCODER_LINE = Regex("--(video|audio)-codec=(\\S+)\\s+--\\1-encoder=(\\S+)(?:\\s*\\(([a-z]+)\\))?")

        /** `--display-id=N    (WxH)` — una línea de `LogUtils.buildDisplayListMessage()`. */
        private val DISPLAY_LINE = Regex("--display-id=(\\d+)\\s*\\(([^)]*)\\)")

        /** Puro (sin I/O): separado de [detectCodecs] para poder razonar sobre el parseo sin un dispositivo real. */
        fun parseCodecsOutput(output: String): CodecsInfo {
            val video = mutableListOf<CodecEncoder>()
            val audio = mutableListOf<CodecEncoder>()
            val displays = mutableListOf<DisplayEntry>()
            output.lineSequence().forEach { line ->
                ENCODER_LINE.find(line)?.let { m ->
                    val (type, codec, encoder, hw) = m.destructured
                    val entry = CodecEncoder(codec, encoder, hw)
                    if (type == "video") video += entry else audio += entry
                }
                DISPLAY_LINE.find(line)?.let { m ->
                    val (id, size) = m.destructured
                    displays += DisplayEntry(id.toIntOrNull() ?: -1, size.trim())
                }
            }
            return CodecsInfo(ok = true, videoEncoders = video, audioEncoders = audio, displays = displays)
        }

        /**
         * Puerto TCP de respaldo (`KAIROS_GATEWAY_SCREEN_SOURCE_ADDR`/`KAIROS_GATEWAY_SCREEN_TCP=1` del lado Go) —
         * YA NO es el mecanismo por defecto: el gateway escucha un socket abstracto Unix local
         * (`scrcpy_<scid>`, derivado del secreto, ver `internal/screen/local.go`), confirmado funcionando contra
         * un dispositivo real sin red ni ADB. Este puerto queda para una fuente remota futura que
         * SÍ hable el protocolo propio de Kairos (no el de scrcpy) — debe coincidir con `defaultScreenSourceAddr`
         * en `native/gateway/cmd/kairos-gateway/main.go` si se usa.
         */
        const val DEFAULT_RELAY_PORT = 8974
    }
}

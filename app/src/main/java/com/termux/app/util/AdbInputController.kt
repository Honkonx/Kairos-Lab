package com.termux.app.util

import org.json.JSONObject

/**
 * Un toque, gesto de arrastre o tecla a enviar al dispositivo. Nunca lleva ejecución adentro —
 * [AdbInputController] decide con qué [PrivilegeProvider] correrlo.
 */
sealed class InputGesture {
    data class Tap(val x: Int, val y: Int) : InputGesture()
    data class Swipe(val x1: Int, val y1: Int, val x2: Int, val y2: Int, val durationMs: Int = AdbInputController.DEFAULT_SWIPE_MS) : InputGesture()
    data class Key(val keyCode: Int) : InputGesture()
}

/**
 * Control de ENTRADA remota vía `adb shell input` — camino alternativo y más simple al futuro servidor de video
 * propio de Kairos (ver `ScreenMirrorManager.kt`), independiente del transporte de pantalla (`ScreenStreamProtocol.kt`,
 * el `screen.*` WebSocket del gateway): esto no manda ni recibe video, solo arma y ejecuta el comando de texto
 * que `adb shell` ya entiende. Útil mientras el transporte real de video no esté listo, y como vía de respaldo
 * después (`docs/arquitectura/briefs_2026-10-10/BRIEF_androidscreencast_adopcion.md`).
 *
 * Mapeo inspirado (solución propia, con crédito, Apache-2.0 © Yurii Serhiichuk) en androidscreencast — ver
 * `docs/referencias/pc-movil/REFERENCIA_androidscreencast.md` y, en el código real:
 *   `referencia/pc-movil/androidscreencast/src/main/java/com/github/xsavikx/androidscreencast/api/command/TapCommand.java`
 *   (línea 8 de `getCommandPart()`: devuelve `"tap x y"`) y `Command.java` (`INPUT = "input"`).
 * Ese proyecto arma el mismo texto `input tap x y` pero lo ejecuta con `ddmlib` (`device.executeShellCommand`);
 * acá se arma el mismo comando pero se ejecuta con [PrivilegeRegistry]/[AdbLocalProvider] (`PrivilegeProviders.kt`),
 * que Kairos ya tiene y no depende de ddmlib. La tabla de keycodes NO se copia de su `InputKeyEvent.java` — son
 * datos públicos de `android.view.KeyEvent`, regenerados acá a partir de la documentación oficial de Android
 * (ver la nota de la auditoría: "mejor regenerarlos de la doc oficial").
 */
class AdbInputController(
    private val registry: PrivilegeRegistry,
    private val providerId: String = "adb",
) {
    /** Arma el comando y lo ejecuta con el proveedor configurado. `null` de [commandFor] = gesto inválido. */
    fun send(gesture: InputGesture): PrivilegeResult {
        val cmd = commandFor(gesture) ?: return PrivilegeResult(false, "gesto inválido", 400)
        return registry.run(providerId, cmd)
    }

    companion object {
        /** `adb shell input swipe` sin duración explícita usa un valor por defecto propio de Android; fijamos uno
         *  razonable acá para que el comando sea siempre el mismo string, reproducible en las pruebas. */
        const val DEFAULT_SWIPE_MS = 300

        /** Límite generoso de coordenada — ninguna pantalla real supera esto; evita que un valor absurdo
         *  (negativo, o un número gigante producto de un bug del llamador) llegue a `adb shell`. */
        const val MAX_COORD = 20000

        /** `android.view.KeyEvent` llega hasta unos pocos cientos de códigos reales; este techo solo descarta
         *  basura evidente (negativos, valores de varios dígitos de más) antes de pasarlo a `input keyevent`. */
        const val MAX_KEYCODE = 999

        /** Subconjunto de `android.view.KeyEvent.KEYCODE_*` — nombres en minúscula sin el prefijo `KEYCODE_`,
         *  valores tomados de la documentación pública de Android (no de ningún proyecto de referencia). */
        val KEY_NAMES: Map<String, Int> = mapOf(
            "home" to 3, "back" to 4, "call" to 5, "endcall" to 6,
            "dpad_up" to 19, "dpad_down" to 20, "dpad_left" to 21, "dpad_right" to 22, "dpad_center" to 23,
            "volume_up" to 24, "volume_down" to 25, "power" to 26, "camera" to 27, "clear" to 28,
            "enter" to 66, "del" to 67, "tab" to 61, "space" to 62,
            "media_play_pause" to 85, "media_stop" to 86, "media_next" to 87, "media_previous" to 88,
            "menu" to 82, "notification" to 83, "search" to 84,
            "page_up" to 92, "page_down" to 93, "escape" to 111, "forward_del" to 112,
            "brightness_down" to 220, "brightness_up" to 221, "sleep" to 223, "wakeup" to 224,
            "app_switch" to 187,
        )

        /** Acepta un nombre conocido (`KEY_NAMES`) o un código numérico ya resuelto (ej. `"66"`). */
        fun keyCodeOf(key: String): Int? = KEY_NAMES[key.lowercase()] ?: key.toIntOrNull()

        private fun validCoord(v: Int) = v in 0..MAX_COORD
        private fun validKeyCode(v: Int) = v in 1..MAX_KEYCODE

        /** Arma el texto exacto para `adb shell` — pura composición de string, sin ejecutar nada. */
        fun commandFor(gesture: InputGesture): String? = when (gesture) {
            is InputGesture.Tap ->
                if (validCoord(gesture.x) && validCoord(gesture.y)) "input tap ${gesture.x} ${gesture.y}" else null
            is InputGesture.Swipe ->
                if (validCoord(gesture.x1) && validCoord(gesture.y1) && validCoord(gesture.x2) && validCoord(gesture.y2) && gesture.durationMs in 0..60_000)
                    "input swipe ${gesture.x1} ${gesture.y1} ${gesture.x2} ${gesture.y2} ${gesture.durationMs}"
                else null
            is InputGesture.Key ->
                if (validKeyCode(gesture.keyCode)) "input keyevent ${gesture.keyCode}" else null
        }

        /**
         * Traduce los parámetros ya validados por patrón del gateway (`screen.input`, strings en [params]) a un
         * [InputGesture]. `null` = tipo desconocido o faltan/están mal los campos requeridos para ese tipo —
         * el llamador responde 400, nunca intenta adivinar un valor.
         */
        fun parse(params: JSONObject): InputGesture? {
            fun int(name: String) = params.optString(name).toIntOrNull()
            return when (params.optString("type")) {
                "tap" -> {
                    val x = int("x") ?: return null
                    val y = int("y") ?: return null
                    InputGesture.Tap(x, y)
                }
                "swipe" -> {
                    val x1 = int("x1") ?: return null
                    val y1 = int("y1") ?: return null
                    val x2 = int("x2") ?: return null
                    val y2 = int("y2") ?: return null
                    val duration = int("duration_ms") ?: DEFAULT_SWIPE_MS
                    InputGesture.Swipe(x1, y1, x2, y2, duration)
                }
                "key" -> {
                    val raw = params.optString("key").takeIf { it.isNotBlank() } ?: return null
                    val code = keyCodeOf(raw) ?: return null
                    InputGesture.Key(code)
                }
                else -> null
            }
        }
    }
}

package com.termux.app.util

/**
 * Validación pura (sin dependencias de Android) de los 3 campos que la pantalla de Permisos
 * pide para emparejar ADB inalámbrico: puerto de emparejamiento, código de 6 dígitos que
 * muestra Ajustes > Depuración inalámbrica, y puerto de conexión. Separado de
 * `ConfigFragment`/`PermissionsNotifier` para poder probarlo con JUnit puro (sin Robolectric) —
 * ver `app/src/test/java/com/termux/app/util/PermissionsValidationTest.kt`.
 */
object PermissionsValidation {
    private val DIGITS_ONLY = Regex("^\\d+$")

    /** Puerto TCP válido: solo dígitos, en el rango 1-65535. */
    fun isValidPort(value: String): Boolean {
        val trimmed = value.trim()
        if (!DIGITS_ONLY.matches(trimmed)) return false
        val port = trimmed.toIntOrNull() ?: return false
        return port in 1..65535
    }

    /** Código de emparejamiento: siempre 6 dígitos (formato real de Android, confirmado en el
     * diálogo guiado ya existente de MonitorFragment — este validador es más estricto que el
     * `^\d+$` que usaba ese diálogo, porque acá se valida UN campo con una forma conocida y
     * fija, no 3 campos con formas distintas). */
    fun isValidPairingCode(value: String): Boolean {
        val trimmed = value.trim()
        return DIGITS_ONLY.matches(trimmed) && trimmed.length == 6
    }

    /** true solo si los 3 campos del emparejamiento son válidos a la vez. */
    // connectPort pasó a ser OPCIONAL (2026-10-31): si se deja vacío,
    // AdbPairingRunner intenta detectarlo solo vía `adb mdns services` — pedido explícito del
    // usuario de no pedir 3 campos cuando 2 (puerto de emparejamiento + código) alcanzan.
    fun isValidPairingSession(pairingPort: String, code: String, connectPort: String): Boolean =
        isValidPort(pairingPort) && isValidPairingCode(code) && (connectPort.isBlank() || isValidPort(connectPort))
}

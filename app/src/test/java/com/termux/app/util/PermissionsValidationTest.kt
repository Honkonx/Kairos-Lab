package com.termux.app.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionsValidationTest {

    @Test
    fun acceptsPortsInRange() {
        assertTrue(PermissionsValidation.isValidPort("1"))
        assertTrue(PermissionsValidation.isValidPort("37123"))
        assertTrue(PermissionsValidation.isValidPort("65535"))
    }

    @Test
    fun rejectsOutOfRangeOrNonNumericPorts() {
        assertFalse(PermissionsValidation.isValidPort("0"))
        assertFalse(PermissionsValidation.isValidPort("65536"))
        assertFalse(PermissionsValidation.isValidPort(""))
        assertFalse(PermissionsValidation.isValidPort("abc"))
        assertFalse(PermissionsValidation.isValidPort("12 34"))
        assertFalse(PermissionsValidation.isValidPort("-5"))
    }

    @Test
    fun acceptsOnlySixDigitPairingCodes() {
        assertTrue(PermissionsValidation.isValidPairingCode("123456"))
        assertFalse(PermissionsValidation.isValidPairingCode("12345"))
        assertFalse(PermissionsValidation.isValidPairingCode("1234567"))
        assertFalse(PermissionsValidation.isValidPairingCode("12345a"))
        assertFalse(PermissionsValidation.isValidPairingCode(""))
    }

    @Test
    fun wholeSessionIsValidOnlyWhenAllThreeFieldsAre() {
        assertTrue(PermissionsValidation.isValidPairingSession("37123", "123456", "41234"))
        assertFalse(PermissionsValidation.isValidPairingSession("", "123456", "41234"))
        assertFalse(PermissionsValidation.isValidPairingSession("37123", "12345", "41234"))
        assertFalse(PermissionsValidation.isValidPairingSession("37123", "123456", "0"))
    }

    @Test
    fun connectPortIsOptionalSinceAutoDetectWasAdded() {
        // 2026-10-31: AdbPairingRunner detecta el puerto de conexión solo
        // (nmap + probar adb connect) cuando se deja vacío — el campo sigue siendo un escape
        // hatch, pero deja de ser obligatorio.
        assertTrue(PermissionsValidation.isValidPairingSession("37123", "123456", ""))
        // Si SÍ se escribe algo, tiene que seguir siendo un puerto válido (no un valor cualquiera).
        assertFalse(PermissionsValidation.isValidPairingSession("37123", "123456", "0"))
    }
}

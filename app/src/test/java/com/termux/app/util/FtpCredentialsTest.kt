package com.termux.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FtpCredentialsTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun notConfiguredUntilSetSucceeds() {
        val creds = FtpCredentials(tmp.newFolder("c1"))
        assertFalse(creds.configured)
        assertNull(creds.username)
        assertFalse(creds.matches("kairos", "cualquiera"))
    }

    @Test
    fun setThenMatchesOnlyTheRealPassword() {
        val creds = FtpCredentials(tmp.newFolder("c2"))
        assertTrue(creds.set("kairos", "unaClaveReal"))
        assertTrue(creds.configured)
        assertEquals("kairos", creds.username)
        assertTrue(creds.matches("kairos", "unaClaveReal"))
        assertFalse(creds.matches("kairos", "otraClave"))
        assertFalse(creds.matches("otroUsuario", "unaClaveReal"))
    }

    @Test
    fun rejectsShortOrOversizedPasswords() {
        // Revisión de seguridad real: el mínimo subió de 6 a 10 caracteres junto con PBKDF2 (210 000
        // iteraciones) — un mínimo de 6 facilitaba demasiado la fuerza bruta aunque el hash fuera lento.
        val creds = FtpCredentials(tmp.newFolder("c3"))
        assertFalse(creds.set("kairos", "corta"))
        assertFalse(creds.configured)
        assertFalse(creds.set("kairos", "x".repeat(200)))
        assertFalse("9 caracteres sigue siendo corto", creds.set("kairos", "123456789"))
        assertTrue("10 caracteres es el mínimo aceptado", creds.set("kairos", "1234567890"))
    }

    @Test
    fun rejectsInvalidUsernames() {
        val creds = FtpCredentials(tmp.newFolder("c4"))
        assertFalse(creds.set("usuario con espacios", "unaClaveReal"))
        assertFalse(creds.set("", "unaClaveReal"))
        assertTrue(creds.set("usuario_valido.1-2", "unaClaveReal"))
    }

    @Test
    fun replacingCredentialsDropsTheOldPassword() {
        val creds = FtpCredentials(tmp.newFolder("c5"))
        creds.set("kairos", "primeraClave")
        assertTrue(creds.set("kairos", "segundaClave"))
        assertFalse(creds.matches("kairos", "primeraClave"))
        assertTrue(creds.matches("kairos", "segundaClave"))
    }

    @Test
    fun neverStoresPasswordInPlainText() {
        val dir = tmp.newFolder("c6")
        val creds = FtpCredentials(dir)
        creds.set("kairos", "contraseñaSecreta123")
        dir.walkTopDown().filter { it.isFile }.forEach { f ->
            assertFalse("archivo ${f.name} filtró la contraseña en texto plano", f.readText().contains("contraseñaSecreta123"))
        }
    }

    @Test
    fun clearRemovesConfiguration() {
        val creds = FtpCredentials(tmp.newFolder("c7"))
        creds.set("kairos", "unaClaveReal")
        creds.clear()
        assertFalse(creds.configured)
        assertNull(creds.username)
    }
}

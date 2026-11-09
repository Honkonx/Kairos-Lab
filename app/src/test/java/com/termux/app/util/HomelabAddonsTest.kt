package com.termux.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class HomelabAddonsTest {
    @Test
    fun resticInputValidationRejectsMissingOrRelativeValues() {
        assertNotNull(HomelabAddons.validateResticInput("", "pw"))
        assertNotNull(HomelabAddons.validateResticInput("relativo/repo", "pw"))
        assertNotNull(HomelabAddons.validateResticInput("/data/repo", ""))
        assertNull(HomelabAddons.validateResticInput("/data/repo", "pw"))
    }

    @Test
    fun resticRepoExistsDependsOnTheConfigFile() {
        val dir = Files.createTempDirectory("kairos-restic-test").toFile()
        try {
            assertFalse(HomelabAddons.resticRepoExists(dir.absolutePath))
            File(dir, "config").writeText("x")
            assertTrue(HomelabAddons.resticRepoExists(dir.absolutePath))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun resticOperationsFailCleanlyWhenResticIsNotInstalled() {
        // En la JVM del host no existe /data/data/com.termux/.../restic: la operación debe fallar
        // con un mensaje, sin lanzar excepción ni intentar ejecutar nada.
        val dir = Files.createTempDirectory("kairos-restic-test2").toFile()
        try {
            File(dir, "config").writeText("x")
            val result = HomelabAddons.resticSnapshots(dir.absolutePath, "pw")
            assertFalse(result.ok)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun syncthingListensOnLoopbackOnly() {
        val command = HomelabAddons.SYNCTHING.service!!.command
        assertTrue(command.contains("--gui-address=127.0.0.1:8384"))
        assertEquals("http://127.0.0.1:8384", HomelabAddons.SYNCTHING_URL)
    }

    @Test
    fun lastLinesKeepsTheTailAndCapsLength() {
        assertEquals("b | c | d", AddonEngine.lastLines("a\nb\nc\nd"))
        assertTrue(AddonEngine.lastLines("x".repeat(1000)).length <= 300)
    }

    @Test
    fun syncthingCredentialsAreValidatedBeforeTouchingTheConfig() {
        assertNull(HomelabAddons.validateSyncthingCredentials("lisa", "12345678"))
        assertNotNull(HomelabAddons.validateSyncthingCredentials("ab", "12345678"))
        assertNotNull(HomelabAddons.validateSyncthingCredentials("lisa; rm", "12345678"))
        assertNotNull(HomelabAddons.validateSyncthingCredentials("lisa", "corta"))
        assertNotNull(HomelabAddons.validateSyncthingCredentials("lisa", "12345678\nX"))
    }
}


package com.termux.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GatewayFilesTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun secretsAreCreatedOnceAndStayStable() {
        val dir = File(tmp.root, "gw")
        val a = GatewayFiles(dir)
        val admin = a.adminSecret
        assertEquals(64, admin.length)
        assertTrue(admin.all { it in "0123456789abcdef" })
        assertNotEquals(admin, a.appSecret)
        // Otro objeto sobre la misma carpeta (reinicio de la app) lee los mismos secretos.
        val b = GatewayFiles(dir)
        assertEquals(admin, b.adminSecret)
        assertEquals(a.appSecret, b.appSecret)
    }

    @Test
    fun accessDefaultsToLocalhostAndPersists() {
        val files = GatewayFiles(File(tmp.root, "gw2"))
        assertEquals(GatewayAccess.LOCALHOST, files.access)
        files.access = GatewayAccess.LAN
        assertEquals(GatewayAccess.LAN, GatewayFiles(File(tmp.root, "gw2")).access)
        assertEquals("0.0.0.0", GatewayAccess.LAN.bindHost)
        assertEquals("127.0.0.1", GatewayAccess.LOCALHOST.bindHost)
    }

    @Test
    fun unknownAccessIdFallsBackToTheSafeOne() {
        assertEquals(GatewayAccess.LOCALHOST, GatewayAccess.fromId("cualquier-cosa"))
        assertEquals(GatewayAccess.LOCALHOST, GatewayAccess.fromId(null))
    }

    @Test
    fun secretFilesAreOwnerOnlyWhereTheOsAllowsIt() {
        val files = GatewayFiles(File(tmp.root, "gw3"))
        files.adminSecret
        val f = File(files.dir, "admin.secret")
        assertTrue(f.canRead())
        // En Windows (donde corren estas pruebas) los bits POSIX no se aplican igual; en Android sí.
        if (System.getProperty("os.name").orEmpty().lowercase().contains("windows").not()) {
            assertTrue(!f.toPath().let { java.nio.file.Files.getPosixFilePermissions(it) }.any { it.name.startsWith("GROUP") || it.name.startsWith("OTHERS") })
        }
    }
}

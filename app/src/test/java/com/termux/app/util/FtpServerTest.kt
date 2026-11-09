package com.termux.app.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket
import java.nio.charset.StandardCharsets

/**
 * Prueba de extremo a extremo real contra sockets reales en localhost (mismo patrón que
 * `GatewayAdminClientTest` — sin Robolectric, [FtpServer]/[FtpCredentials]/[HomeFiles] no tocan
 * ninguna clase de Android). Confirma el protocolo FTP mínimo (login, PASV, LIST, STOR, RETR,
 * DELE, MKD, CWD) de punta a punta, no solo pieza por pieza.
 */
class FtpServerTest {
    @get:Rule val tmp = TemporaryFolder()

    @After
    fun tearDown() {
        FtpServer.stop()
    }

    private class FtpClient(port: Int) {
        private val socket = Socket(InetAddress.getByName("127.0.0.1"), port).apply { soTimeout = 5000 }
        private val out: OutputStream = socket.getOutputStream()
        private val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))

        fun readLine(): String = reader.readLine() ?: throw IllegalStateException("conexión cerrada")

        fun send(cmd: String): String {
            out.write((cmd + "\r\n").toByteArray(StandardCharsets.UTF_8)); out.flush()
            return readLine()
        }

        /** Abre la conexión de datos anunciada por un `PASV` ya mandado. */
        fun openData(pasvReply: String): Socket {
            val nums = pasvReply.substringAfter('(').substringBefore(')').split(',').map { it.toInt() }
            val ip = "${nums[0]}.${nums[1]}.${nums[2]}.${nums[3]}"
            val port = (nums[4] shl 8) or nums[5]
            return Socket(InetAddress.getByName(ip), port).apply { soTimeout = 5000 }
        }

        fun close() { socket.close() }
    }

    private fun startedServer(home: java.io.File, user: String = "kairos", pass: String = "unaClaveReal"): Int {
        val creds = FtpCredentials(tmp.newFolder("creds_${System.nanoTime()}"))
        assertTrue(creds.set(user, pass))
        val port = FtpServer.start(home, emptyList(), creds, preferred = 0)
        assertTrue("el servidor no pudo abrir ningún puerto", port != 0)
        return port
    }

    @Test
    fun rejectsCommandsBeforeLogin() {
        val port = startedServer(tmp.newFolder("home1"))
        val c = FtpClient(port)
        assertTrue(c.readLine().startsWith("220"))
        assertTrue(c.send("PWD").startsWith("530"))
        c.close()
    }

    @Test
    fun rejectsWrongPassword() {
        val port = startedServer(tmp.newFolder("home2"))
        val c = FtpClient(port)
        c.readLine()
        assertTrue(c.send("USER kairos").startsWith("331"))
        assertTrue(c.send("PASS claveMala").startsWith("530"))
        assertTrue(c.send("PWD").startsWith("530"))
        c.close()
    }

    @Test
    fun closesTheConnectionAfterTooManyWrongPasswords() {
        // Revisión de seguridad real: antes nada frenaba a alguien probando contraseñas una tras otra en
        // la misma sesión — ahora, pasado el límite, el servidor corta la conexión de control.
        val port = startedServer(tmp.newFolder("home_bf"))
        val c = FtpClient(port)
        c.readLine()
        repeat(3) {
            assertTrue(c.send("USER kairos").startsWith("331"))
            assertTrue(c.send("PASS claveMala").startsWith("530"))
        }
        // Pasado el límite el servidor ya cerró el socket de control — cualquier comando nuevo falla
        // (la lectura da EOF/excepción, nunca una respuesta "530" normal como antes).
        var closed = false
        try {
            c.send("PWD")
        } catch (_: Exception) {
            closed = true
        }
        assertTrue("el servidor debió cortar la conexión tras demasiados intentos fallidos", closed)
        c.close()
    }

    @Test
    fun loginNavigateListAndTransferRoundTrip() {
        val home = tmp.newFolder("home3")
        java.io.File(home, "ya_existia.txt").writeText("contenido viejo")
        val port = startedServer(home)
        val c = FtpClient(port)
        c.readLine()
        assertTrue(c.send("USER kairos").startsWith("331"))
        assertTrue(c.send("PASS unaClaveReal").startsWith("230"))
        assertEquals("257 \"/\" es el directorio actual", c.send("PWD"))

        // LIST del root ve el archivo preexistente.
        var reply = c.send("PASV"); assertTrue(reply.startsWith("227"))
        var data = c.openData(reply)
        assertTrue(c.send("LIST").startsWith("150"))
        val listing = data.getInputStream().bufferedReader(StandardCharsets.UTF_8).readText()
        data.close()
        assertTrue(c.readLine().startsWith("226"))
        assertTrue(listing.contains("ya_existia.txt"))

        // MKD + CWD a la carpeta nueva.
        assertTrue(c.send("MKD sub").startsWith("257"))
        assertTrue(c.send("CWD sub").startsWith("250"))
        assertEquals("257 \"/sub\" es el directorio actual", c.send("PWD"))

        // STOR sube "nuevo.txt" dentro de "sub" (cwd actual).
        reply = c.send("PASV"); assertTrue(reply.startsWith("227"))
        data = c.openData(reply)
        assertTrue(c.send("STOR nuevo.txt").startsWith("150"))
        data.getOutputStream().write("hola ftp".toByteArray(StandardCharsets.UTF_8))
        data.close()
        assertTrue(c.readLine().startsWith("226"))
        assertEquals("hola ftp", java.io.File(home, "sub/nuevo.txt").readText())

        // CDUP vuelve a la raíz; SIZE confirma la ruta "sub/nuevo.txt" desde ahí.
        assertTrue(c.send("CDUP").startsWith("250"))
        assertEquals("257 \"/\" es el directorio actual", c.send("PWD"))
        assertEquals("213 8", c.send("SIZE sub/nuevo.txt"))

        c.send("QUIT")
        c.close()
    }

    @Test
    fun storeThenRetrieveRoundTripMatchesBytes() {
        val home = tmp.newFolder("home4")
        val port = startedServer(home)
        val c = FtpClient(port)
        c.readLine()
        c.send("USER kairos"); c.send("PASS unaClaveReal")

        var reply = c.send("PASV")
        var data = c.openData(reply)
        assertTrue(c.send("STOR archivo.bin").startsWith("150"))
        val payload = ByteArray(5000) { (it % 256).toByte() }
        data.getOutputStream().write(payload)
        data.close()
        assertTrue(c.readLine().startsWith("226"))

        reply = c.send("PASV")
        data = c.openData(reply)
        assertTrue(c.send("RETR archivo.bin").startsWith("150"))
        val received = data.getInputStream().readBytes()
        data.close()
        assertTrue(c.readLine().startsWith("226"))
        assertTrue(payload.contentEquals(received))
        c.close()
    }

    @Test
    fun deleteRemovesTheFile() {
        val home = tmp.newFolder("home5")
        java.io.File(home, "borrame.txt").writeText("x")
        val port = startedServer(home)
        val c = FtpClient(port)
        c.readLine()
        c.send("USER kairos"); c.send("PASS unaClaveReal")
        assertTrue(c.send("DELE borrame.txt").startsWith("250"))
        assertFalse(java.io.File(home, "borrame.txt").exists())
        c.close()
    }

    @Test
    fun pathTraversalIsRejectedJustLikeHomeFiles() {
        val home = tmp.newFolder("home6")
        val outside = tmp.newFolder("fuera")
        java.io.File(outside, "secreto.txt").writeText("nunca deberías ver esto")
        val port = startedServer(home)
        val c = FtpClient(port)
        c.readLine()
        c.send("USER kairos"); c.send("PASS unaClaveReal")
        assertTrue(c.send("CWD ../fuera").startsWith("550"))
        assertTrue(c.send("SIZE ../fuera/secreto.txt").startsWith("550"))
        c.close()
    }

    @Test
    fun startFailsWithoutConfiguredCredentials() {
        val creds = FtpCredentials(tmp.newFolder("sin_configurar"))
        val port = FtpServer.start(tmp.newFolder("home7"), emptyList(), creds, preferred = 0)
        assertEquals(0, port)
        assertFalse(FtpServer.isRunning)
    }
}

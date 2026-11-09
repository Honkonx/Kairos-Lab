package com.termux.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GatewayDistroOpsTest {
    @get:Rule val tmp = TemporaryFolder()

    private class Recorder(val reply: (List<String>) -> Pair<Int, String> = { 0 to "" }) : CommandRunner {
        val calls = mutableListOf<List<String>>()
        override fun run(argv: List<String>, timeoutSec: Long): Pair<Int, String> { calls += argv; return reply(argv) }
    }

    @Test
    fun parsesProotDistroList() {
        val text = """
            Supported distributions:

              * Alpine Linux

                Alias: alpine
                Installed: no
                Comment: ligera

              * Debian (stable)

                Alias: debian
                Installed: yes
        """.trimIndent()
        val list = DistroManager.parseDistros(text)
        assertEquals(listOf("alpine", "debian"), list.map { it.alias })
        assertEquals(listOf(false, true), list.map { it.installed })
    }

    @Test
    fun openAndInstallRejectBadAliasesAndUseTmuxSessions() {
        val r = Recorder()
        val d = DistroManager(r, SessionManager(r), "/bin-x")
        assertEquals(400, d.open("../x; rm").exitCode)
        assertEquals(400, d.install("A B").exitCode)
        assertTrue(r.calls.isEmpty())
        assertTrue(d.open("debian").ok)
        val argv = r.calls.single()
        assertEquals("kx-d-debian", argv[argv.indexOf("-s") + 1])
        assertEquals("/bin-x/proot-distro login debian", argv.last())
    }

    @Test
    fun parsesUdockerImagesAndRejectsBadImageNames() {
        val out = "REPOSITORY\nubuntu:22.04   .\nalpine:latest  .\n"
        assertEquals(listOf("ubuntu:22.04", "alpine:latest"), DistroManager.parseImages(out).map { it.name })
        val r = Recorder()
        val d = DistroManager(r, SessionManager(r), "/b")
        assertEquals(400, d.openImage("x; reboot").exitCode)
        assertTrue(d.openImage("library/alpine:latest").ok)
        assertEquals("/b/udocker run --rm -it library/alpine:latest", r.calls.single().last())
    }

    @Test
    fun runUsesSharedHomeByDefaultAndIsolatedOnlyWhenSandboxed() {
        val r = Recorder()
        val d = DistroManager(r, SessionManager(r), "/bin-x")
        assertTrue(d.run("debian", "echo hola").ok)
        val normalArgv = r.calls.single()
        assertTrue("sin sandbox debe compartir home/tmp", normalArgv.containsAll(listOf("--shared-tmp", "--shared-home")))
        assertFalse("sin sandbox nunca debe pedir --isolated", normalArgv.contains("--isolated"))

        r.calls.clear()
        assertTrue(d.run("debian", "echo hola", sandbox = true).ok)
        val sandboxArgv = r.calls.single()
        assertTrue("con sandbox=true debe pedir --isolated", sandboxArgv.contains("--isolated"))
        assertFalse("con sandbox=true nunca debe compartir home/tmp", sandboxArgv.containsAll(listOf("--shared-tmp", "--shared-home")))
    }

    @Test
    fun fileManagerCreatesMovesAndDeletesSafely() {
        val home = tmp.newFolder("fm")
        val f = HomeFiles(home)
        assertTrue(f.mkdir("a/b").isSuccess)
        assertTrue(File(home, "a/b").isDirectory)
        File(home, "a/x.txt").writeText("x")
        assertTrue(f.rename("a/x.txt", "a/b/y.txt").isSuccess)
        assertTrue(File(home, "a/b/y.txt").isFile)
        assertTrue("no pisa un destino existente", f.rename("a/b/y.txt", "a/b").isFailure)
        assertTrue("carpeta con contenido no se borra", f.delete("a").isFailure)
        assertTrue(f.delete("a/b/y.txt").isSuccess)
        assertTrue(f.delete("a/b").isSuccess)
        assertFalse(File(home, "a/b").exists())
        assertTrue(f.delete("").isFailure)
        assertTrue(f.rename("a", "../fuera").isFailure)
        assertTrue(f.mkdir("../fuera").isFailure)
    }
}

package com.termux.app.util

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

// Robolectric: org.json.JSONObject es un stub vacío en la JVM del host (mismo motivo que TorManagerTest).
@RunWith(RobolectricTestRunner::class)
class ModuleDebInstallerTest {

    @Test
    fun parseReportReadsWhatInspectPrints() {
        val report = ModuleDebInstaller.parseReport(JSONObject("""
            {"ok":true,"id":"claude","version":"2.1.251","schema":2,"files_count":2,
             "declared_paths":["/a","/b"],
             "requires":[{"id":"glibc_ld","kind":"package","packages":["glibc-repo","glibc-runner"],"install_hint":"pkg install -y glibc-repo","status":"missing"},
                         {"id":"x","kind":"other","packages":[],"install_hint":"echo","status":"unknown"}],
             "runs_commands":{"verify_cmd":true,"patch_cmd":false}}
        """))
        assertTrue(report.ok)
        assertEquals("claude", report.id)
        assertEquals(2, report.filesCount)
        assertEquals(listOf("glibc-repo", "glibc-runner"), report.requires[0].packages)
        assertEquals(2, report.missing.size)
        assertTrue(report.runsVerifyCmd)
        assertFalse(report.runsPatchCmd)
    }

    @Test
    fun parseReportKeepsRejectionReasons() {
        val report = ModuleDebInstaller.parseReport(JSONObject("""{"ok":false,"reasons":["falta DEBIAN/SHA256SUMS.txt"]}"""))
        assertFalse(report.ok)
        assertEquals(listOf("falta DEBIAN/SHA256SUMS.txt"), report.reasons)
    }

    @Test
    fun presentRequirementsAreNotListedAsMissing() {
        val report = ModuleDebInstaller.parseReport(JSONObject("""
            {"ok":true,"id":"x","requires":[{"id":"a","kind":"package","packages":["p"],"status":"present"}]}
        """))
        assertTrue(report.missing.isEmpty())
    }

    @Test
    fun sha256OfMatchesKnownVector() {
        val f = File.createTempFile("sha", ".txt")
        try {
            f.writeText("abc")
            assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", ModuleDebInstaller.sha256Of(f))
        } finally {
            f.delete()
        }
    }

    @Test
    fun inspectOfMissingFileIsRejectedWithoutRunningAnything() {
        val report = ModuleDebInstaller.inspect(File("/no/existe/paquete.deb"))
        assertFalse(report.ok)
        assertTrue(report.reasons.single().contains("no existe"))
    }
}

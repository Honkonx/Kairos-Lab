package com.termux.app.util

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cableado de `storage.*`/`ftp.*` en [GatewayBridgeCore] — mismo estilo que [GatewayControlOpsTest]
 * (fakes puros, sin Android), separado de la lógica real de [AppStorageOps] (Android-only,
 * `StorageManager`, sin cubrir por pruebas unitarias en esta ronda — ver `FtpServerTest`/
 * `FtpCredentialsTest` para la parte que sí corre en la JVM del host).
 */
class GatewayStorageFtpBridgeTest {

    private class NoModules : GatewayModuleOps {
        override fun list() = emptyList<ModuleSummary>()
        override fun status(id: String): ModuleSummary? = null
        override fun start(id: String) = ModuleOpResult(true)
        override fun stop(id: String) = ModuleOpResult(true)
        override fun install(id: String) = ModuleOpResult(true)
        override fun uninstall(id: String) = ModuleOpResult(true)
    }

    private class FakeStorage(private val vols: List<StorageVolumeInfo>) : GatewayStorageOps {
        override fun volumes() = vols
        override fun listDir(volumeId: String, relative: String): Result<List<FileEntry>> {
            if (volumeId != "primary") return Result.failure(IllegalArgumentException("volumen no encontrado"))
            return Result.success(listOf(FileEntry("foto.jpg", false, 100)))
        }
    }

    private class FakeFtp : GatewayFtpOps {
        var running = false
        var configured = false
        var username: String? = null
        override fun status() = FtpStatusInfo(running, if (running) 2121 else 0, configured, username)
        override fun start(): FtpOpResult {
            if (!configured) return FtpOpResult(false, "sin credenciales")
            running = true; return FtpOpResult(true)
        }
        override fun stop(): FtpOpResult { running = false; return FtpOpResult(true) }
        override fun setCredentials(username: String, password: String): FtpOpResult {
            if (password.length < 6) return FtpOpResult(false, "contraseña corta")
            this.username = username; configured = true; return FtpOpResult(true)
        }
    }

    private fun call(c: GatewayBridgeCore, target: String, params: JSONObject = JSONObject()): Pair<Int, JSONObject> {
        val (code, body) = c.handle("s", JSONObject().put("target", target).put("params", params).toString())
        return code to JSONObject(body)
    }

    @Test
    fun storageTargetsFailClearlyWhenStorageIsOff() {
        val c = GatewayBridgeCore("s", NoModules())
        assertEquals(404, call(c, "storage.volumes").first)
    }

    @Test
    fun ftpTargetsFailClearlyWhenFtpIsOff() {
        val c = GatewayBridgeCore("s", NoModules())
        assertEquals(404, call(c, "ftp.status").first)
    }

    @Test
    fun storageVolumesReportsAccessibilityAndListDirStaysScopedToTheVolume() {
        val vols = listOf(
            StorageVolumeInfo("primary", "Almacenamiento interno", true, false, "mounted", "/sdcard", true),
            StorageVolumeInfo("1234-5678", "Tarjeta SD", false, true, "mounted", null, false),
        )
        val c = GatewayBridgeCore("s", NoModules(), storage = FakeStorage(vols))
        val (code, body) = call(c, "storage.volumes")
        assertEquals(200, code)
        val list = body.getJSONArray("volumes")
        assertEquals(2, list.length())
        assertTrue(list.getJSONObject(0).getBoolean("accessible"))
        assertFalse(list.getJSONObject(1).getBoolean("accessible"))

        val (dirCode, dirBody) = call(c, "storage.list_dir", JSONObject().put("volume_id", "primary").put("path", ""))
        assertEquals(200, dirCode)
        assertEquals("foto.jpg", dirBody.getJSONArray("entries").getJSONObject(0).getString("name"))

        assertEquals(400, call(c, "storage.list_dir", JSONObject().put("volume_id", "no existe ni mal formado!")).first)
    }

    @Test
    fun ftpLifecycleThroughTheBridge() {
        val c = GatewayBridgeCore("s", NoModules(), ftp = FakeFtp())
        // ftp.start sin credenciales configuradas debe fallar (500, ok=false) antes de configurarlas.
        val (code, body) = call(c, "ftp.start")
        assertEquals(500, code)
        assertFalse(body.getBoolean("ok"))

        assertEquals(400, call(c, "ftp.set_credentials", JSONObject().put("username", "").put("password", "")).first)

        val (setCode, setBody) = call(c, "ftp.set_credentials", JSONObject().put("username", "kairos").put("password", "unaClaveReal"))
        assertEquals(200, setCode)
        assertTrue(setBody.getBoolean("ok"))

        val started = call(c, "ftp.start")
        assertEquals(200, started.first)

        val status = call(c, "ftp.status").second
        assertTrue(status.getBoolean("running"))
        assertEquals("kairos", status.getString("username"))

        val stopped = call(c, "ftp.stop")
        assertEquals(200, stopped.first)
        assertFalse(call(c, "ftp.status").second.getBoolean("running"))
    }
}

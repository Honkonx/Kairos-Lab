package com.termux.app

import com.termux.app.data.ModuleCatalog
import com.termux.app.model.ModuleInfo
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.lang.reflect.Method

/**
 * Cubre `ModuleController.kt` — la lógica de dispatch/estado más crítica del proyecto (7+ tablas
 * `when (moduleId)` que traducen un id de `modules.json` a script/sesión tmux/proceso/puerto
 * reales) — confirmada SIN ningún test hasta esta ronda (auditoría 2026-09-15), pese a ser
 * código Kotlin puro (determinista, id -> valor) que no necesita dispositivo/emulador real.
 *
 * `ModuleController` es un `object` con estas tablas declaradas `private fun` — no se puede
 * tocar el archivo en esta ronda (otro agente lo edita en paralelo para un fix de puertos), así
 * que este test las invoca por reflexión en vez de pedir que se hagan `internal`/públicas. Esto
 * es deliberado: el objetivo NO es fijar un valor "correcto" a mano por id (eso duplicaría la
 * tabla real y quedaría desactualizado con el primer módulo nuevo) — es hacer que el test FALLE
 * si una tabla queda desincronizada con las otras, o con `modules.json`, recorriendo los ids
 * reales del catálogo (fixture real, no una lista hardcodeada) para que el test se mantenga solo
 * cuando se agregue un módulo nuevo.
 *
 * Usa `RobolectricTestRunner` únicamente porque `org.json.JSONArray`/`JSONObject` (usados por
 * `ModuleCatalog.parse()`, la misma función de parseo real que usa el resto de la app) son
 * clases de `android.jar` — bajo JUnit puro, sin el shadow real de Robolectric, cualquier
 * llamada a `org.json.*` lanza `RuntimeException("Stub!")`. No se necesita ningún dispositivo o
 * emulador: Robolectric corre 100% en la JVM. El resto del test (reflexión sobre
 * `ModuleController`, lectura de los scripts reales de `modulos/` del disco) no depende de
 * Robolectric en sí.
 */
@RunWith(RobolectricTestRunner::class)
class ModuleControllerTest {

    companion object {
        private lateinit var modules: List<ModuleInfo>
        private lateinit var modulosDir: File

        private val moduleControllerClass = Class.forName("com.termux.app.ModuleController")
        private val moduleControllerInstance = moduleControllerClass.getField("INSTANCE").get(null)

        @JvmStatic
        @BeforeClass
        fun loadFixtures() {
            val root = repoRoot()
            val modulesJsonFile = File(root, "app/src/main/assets/modules.json")
            assertTrue("No se encontró ${modulesJsonFile.absolutePath}", modulesJsonFile.exists())
            val json = modulesJsonFile.readText()
            modules = ModuleCatalog.parse(JSONArray(json), context = null)
            assertTrue("modules.json parseó 0 módulos — fixture rota", modules.isNotEmpty())

            modulosDir = File(root, "modulos")
            assertTrue("No se encontró ${modulosDir.absolutePath}", modulosDir.isDirectory)
        }

        // Busca la raíz del repo subiendo desde el cwd real de la corrida de Gradle (que puede
        // ser el propio repo o el módulo `app/`, según cómo se invoque el test runner) hasta
        // encontrar `settings.gradle` — marcador único de la raíz real del proyecto.
        private fun repoRoot(): File {
            var dir = File(System.getProperty("user.dir") ?: ".").absoluteFile
            while (true) {
                if (File(dir, "settings.gradle").exists()) return dir
                dir = dir.parentFile
                    ?: throw IllegalStateException("No se encontró settings.gradle subiendo desde el cwd del test")
            }
        }

        private fun privateMethod(name: String): Method =
            moduleControllerClass.getDeclaredMethod(name, String::class.java).apply { isAccessible = true }

        private fun startScript(id: String): String? = privateMethod("getModuleStartScript").invoke(moduleControllerInstance, id) as String?
        private fun stopScript(id: String): String? = privateMethod("getModuleStopInfo").invoke(moduleControllerInstance, id) as String?
        private fun tmuxSession(id: String): String? = privateMethod("getTmuxSession").invoke(moduleControllerInstance, id) as String?
        private fun processName(id: String): String? = privateMethod("getProcessName").invoke(moduleControllerInstance, id) as String?
        private fun port(id: String): Int? = privateMethod("getModulePort").invoke(moduleControllerInstance, id) as Int?
        private fun legacyScriptFile(id: String): String = privateMethod("legacyScriptFileFallback").invoke(moduleControllerInstance, id) as String
    }

    // === Consistencia cruzada entre las tablas de ModuleController, por cada id real de modules.json ===

    @Test
    fun `getModuleStartScript y getModuleStopInfo estan siempre definidos juntos`() {
        for (m in modules) {
            val hasStart = startScript(m.id) != null
            val hasStop = stopScript(m.id) != null
            assertEquals(
                "${m.id}: getModuleStartScript()!=null (${hasStart}) no coincide con getModuleStopInfo()!=null (${hasStop}) — " +
                    "un módulo con script de arranque siempre debe tener también uno de detención, y viceversa",
                hasStart,
                hasStop
            )
        }
    }

    @Test
    fun `todo modulo con start script tiene puerto definido, y viceversa`() {
        for (m in modules) {
            val hasStart = startScript(m.id) != null
            val hasPort = port(m.id) != null
            assertEquals(
                "${m.id}: getModuleStartScript()!=null (${hasStart}) no coincide con getModulePort()!=null (${hasPort}) — " +
                    "waitForPortOpen() en startModule() necesita un puerto para CUALQUIER módulo con script de arranque",
                hasStart,
                hasPort
            )
        }
    }

    @Test
    fun `todo modulo con sesion tmux tiene start script`() {
        for (m in modules) {
            if (tmuxSession(m.id) != null) {
                assertNotNull("${m.id}: tiene sesión tmux (${tmuxSession(m.id)}) pero getModuleStartScript() es null", startScript(m.id))
            }
        }
    }

    @Test
    fun `todo modulo con processName tiene start script`() {
        for (m in modules) {
            if (processName(m.id) != null) {
                assertNotNull("${m.id}: tiene processName (${processName(m.id)}) pero getModuleStartScript() es null", startScript(m.id))
            }
        }
    }

    // === modules.json hasSwitch:true -> ModuleController debe saber arrancarlo/pararlo/verificarlo ===
    //
    // Deliberadamente NO se prueba el "viceversa" (hasSwitch:false => debe ser null en
    // ModuleController) — hay contraejemplos reales e intencionales en el propio código:
    // "opencode" y "db" tienen hasSwitch:false en modules.json (no aparecen con un switch en la
    // pantalla Módulos) pero SÍ tienen entradas completas en ModuleController (arrancan/paran vía
    // otras pantallas — MonitorFragment, OpenCode con terminalCommand, etc.), y "cactus" tiene su
    // propio switch dentro de CactusFragment.kt en vez del switch genérico de modules.json (ver
    // comentario real en ModuleController.kt sobre getModuleStartScript("cactus")). Forzar esa
    // dirección produciría 3 falsos positivos sobre diseño intencional, no bugs reales.
    @Test
    fun `modulos con hasSwitch=true tienen start, stop y puerto definidos en ModuleController`() {
        for (m in modules) {
            if (!m.hasSwitch) continue
            assertNotNull("${m.id}: hasSwitch=true en modules.json pero getModuleStartScript()==null", startScript(m.id))
            assertNotNull("${m.id}: hasSwitch=true en modules.json pero getModuleStopInfo()==null", stopScript(m.id))
            assertNotNull("${m.id}: hasSwitch=true en modules.json pero getModulePort()==null", port(m.id))
        }
    }

    // === Puerto: modules.json vs ModuleController — el hallazgo real que este test suite debe atrapar ===

    @Test
    fun `el puerto de ModuleController coincide con el de modules_json cuando modules_json trae uno`() {
        val mismatches = mutableListOf<String>()
        for (m in modules) {
            val jsonPort = m.port.trim().toIntOrNull() ?: continue
            val kotlinPort = port(m.id)
            if (kotlinPort != jsonPort) {
                mismatches += "${m.id}: modules.json port=$jsonPort, ModuleController.getModulePort()=$kotlinPort"
            }
        }
        if (mismatches.isNotEmpty()) {
            fail("Puertos desincronizados entre modules.json y ModuleController.getModulePort():\n" + mismatches.joinToString("\n"))
        }
    }

    // === legacyScriptFileFallback() vs el campo "script" real de modules.json ===

    @Test
    fun `legacyScriptFileFallback coincide con el campo script de modules_json para ids con script no vacio`() {
        val mismatches = mutableListOf<String>()
        for (m in modules) {
            if (m.script.isBlank()) continue // módulos contenedor (languages/packages) — sin instalador propio, a propósito.
            val fallback = legacyScriptFile(m.id)
            if (fallback != m.script) {
                mismatches += "${m.id}: modules.json script=\"${m.script}\", legacyScriptFileFallback()=\"$fallback\""
            }
        }
        if (mismatches.isNotEmpty()) {
            fail(
                "legacyScriptFileFallback() desincronizado del campo \"script\" real de modules.json " +
                    "(agregar el caso especial en ModuleController.legacyScriptFileFallback() si el nombre no sigue " +
                    "la convención \"<id>.sh\"):\n" + mismatches.joinToString("\n")
            )
        }
    }

    // === Contrato de modules.json en sí (independiente de ModuleController) ===

    @Test
    fun `0 ids duplicados en modules_json`() {
        val counts = modules.groupingBy { it.id }.eachCount()
        val duplicated = counts.filterValues { it > 1 }
        assertTrue("IDs duplicados en modules.json: $duplicated", duplicated.isEmpty())
    }

    @Test
    fun `cada script no vacio referenciado en modules_json existe como archivo real en modulos`() {
        val missing = mutableListOf<String>()
        for (m in modules) {
            if (m.script.isBlank()) continue
            val scriptFile = File(modulosDir, m.script)
            if (!scriptFile.isFile) {
                missing += "${m.id}: modulos/${m.script} no existe (${scriptFile.absolutePath})"
            }
        }
        if (missing.isNotEmpty()) {
            fail("Scripts referenciados por modules.json que no existen en modulos/:\n" + missing.joinToString("\n"))
        }
    }

    @Test
    fun `cada modulo con hasSwitch=true tiene al menos una entrada real en ModuleController`() {
        for (m in modules) {
            if (!m.hasSwitch) continue
            val wired = startScript(m.id) != null ||
                stopScript(m.id) != null ||
                tmuxSession(m.id) != null ||
                processName(m.id) != null ||
                port(m.id) != null
            assertTrue(
                "${m.id}: hasSwitch=true en modules.json pero ninguna tabla de ModuleController lo reconoce " +
                    "(getModuleStartScript/getModuleStopInfo/getTmuxSession/getProcessName/getModulePort devuelven todas null)",
                wired
            )
        }
    }

    // === Módulos desconocidos (ids inventados) deben devolver null en todas las tablas, no crashear ===

    @Test
    fun `un moduleId inexistente devuelve null en todas las tablas, sin excepcion`() {
        val fakeId = "id_que_no_existe_en_modules_json____test"
        assertNull(startScript(fakeId))
        assertNull(stopScript(fakeId))
        assertNull(tmuxSession(fakeId))
        assertNull(processName(fakeId))
        assertNull(port(fakeId))
    }
}

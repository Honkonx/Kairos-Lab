package com.termux.app.util

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeInputProvider(override val id: String, private val available: Boolean, val ran: MutableList<String> = mutableListOf()) : PrivilegeProvider {
    override val title = id
    override fun isAvailable() = available
    override fun run(command: String, timeoutSec: Long): PrivilegeResult {
        ran += command
        return PrivilegeResult(true, "")
    }
}

class AdbInputControllerTest {

    // --- commandFor: construcción pura del texto ---

    @Test
    fun tapBuildsInputTap() {
        assertEquals("input tap 100 200", AdbInputController.commandFor(InputGesture.Tap(100, 200)))
    }

    @Test
    fun swipeBuildsInputSwipeWithDefaultDuration() {
        assertEquals(
            "input swipe 10 20 30 40 ${AdbInputController.DEFAULT_SWIPE_MS}",
            AdbInputController.commandFor(InputGesture.Swipe(10, 20, 30, 40)),
        )
    }

    @Test
    fun swipeAcceptsCustomDuration() {
        assertEquals("input swipe 0 0 1 1 500", AdbInputController.commandFor(InputGesture.Swipe(0, 0, 1, 1, 500)))
    }

    @Test
    fun keyBuildsInputKeyevent() {
        assertEquals("input keyevent 4", AdbInputController.commandFor(InputGesture.Key(4)))
    }

    @Test
    fun tapRejectsNegativeOrOutOfRangeCoordinates() {
        assertNull(AdbInputController.commandFor(InputGesture.Tap(-1, 10)))
        assertNull(AdbInputController.commandFor(InputGesture.Tap(10, AdbInputController.MAX_COORD + 1)))
    }

    @Test
    fun swipeRejectsBadDuration() {
        assertNull(AdbInputController.commandFor(InputGesture.Swipe(0, 0, 1, 1, -1)))
        assertNull(AdbInputController.commandFor(InputGesture.Swipe(0, 0, 1, 1, 60_001)))
    }

    @Test
    fun keyRejectsZeroNegativeOrTooLarge() {
        assertNull(AdbInputController.commandFor(InputGesture.Key(0)))
        assertNull(AdbInputController.commandFor(InputGesture.Key(-5)))
        assertNull(AdbInputController.commandFor(InputGesture.Key(AdbInputController.MAX_KEYCODE + 1)))
    }

    // --- tabla de keycodes ---

    @Test
    fun keyCodeOfResolvesKnownNamesCaseInsensitive() {
        assertEquals(4, AdbInputController.keyCodeOf("BACK"))
        assertEquals(3, AdbInputController.keyCodeOf("home"))
        assertEquals(66, AdbInputController.keyCodeOf("Enter"))
    }

    @Test
    fun keyCodeOfAcceptsRawNumericCode() {
        assertEquals(187, AdbInputController.keyCodeOf("187"))
    }

    @Test
    fun keyCodeOfReturnsNullForUnknownName() {
        assertNull(AdbInputController.keyCodeOf("not_a_real_key"))
    }

    // --- parse: de los params string del gateway a un InputGesture ---

    @Test
    fun parseTap() {
        val params = JSONObject().put("type", "tap").put("x", "5").put("y", "6")
        val gesture = AdbInputController.parse(params)
        assertTrue(gesture is InputGesture.Tap)
        assertEquals(InputGesture.Tap(5, 6), gesture)
    }

    @Test
    fun parseSwipeWithoutDurationUsesDefault() {
        val params = JSONObject().put("type", "swipe").put("x1", "0").put("y1", "0").put("x2", "1").put("y2", "1")
        val gesture = AdbInputController.parse(params) as InputGesture.Swipe
        assertEquals(AdbInputController.DEFAULT_SWIPE_MS, gesture.durationMs)
    }

    @Test
    fun parseKeyByName() {
        val params = JSONObject().put("type", "key").put("key", "back")
        assertEquals(InputGesture.Key(4), AdbInputController.parse(params))
    }

    @Test
    fun parseMissingFieldsReturnsNull() {
        assertNull(AdbInputController.parse(JSONObject().put("type", "tap").put("x", "5")))
        assertNull(AdbInputController.parse(JSONObject().put("type", "key")))
    }

    @Test
    fun parseUnknownTypeReturnsNull() {
        assertNull(AdbInputController.parse(JSONObject().put("type", "bogus")))
    }

    // --- send: delega en el PrivilegeRegistry/proveedor, nunca ejecuta directo ---

    @Test
    fun sendRunsTheBuiltCommandOnTheConfiguredProvider() {
        val provider = FakeInputProvider("adb", true)
        val registry = PrivilegeRegistry(listOf(provider))
        val result = AdbInputController(registry).send(InputGesture.Tap(1, 2))
        assertTrue(result.ok)
        assertEquals(listOf("input tap 1 2"), provider.ran)
    }

    @Test
    fun sendFailsCleanlyOnInvalidGestureWithoutTouchingTheProvider() {
        val provider = FakeInputProvider("adb", true)
        val registry = PrivilegeRegistry(listOf(provider))
        val result = AdbInputController(registry).send(InputGesture.Key(-1))
        assertFalse(result.ok)
        assertTrue(provider.ran.isEmpty())
    }

    // --- integración con GatewayBridgeCore (target "screen.input") ---

    @Test
    fun bridgeDispatchesScreenInputToTheConfiguredProvider() {
        val provider = FakeInputProvider("adb", true)
        val registry = PrivilegeRegistry(listOf(provider))
        val ops = object : GatewayModuleOps {
            override fun list() = emptyList<ModuleSummary>()
            override fun status(id: String): ModuleSummary? = null
            override fun start(id: String) = ModuleOpResult(false)
            override fun stop(id: String) = ModuleOpResult(false)
            override fun install(id: String) = ModuleOpResult(false)
            override fun uninstall(id: String) = ModuleOpResult(false)
        }
        val core = GatewayBridgeCore("s3cr3t", ops, registry)
        val (code, body) = core.handle("s3cr3t", """{"target":"screen.input","params":{"type":"tap","x":"1","y":"2"}}""")
        assertEquals(200, code)
        assertTrue(JSONObject(body).getBoolean("ok"))
        assertEquals(listOf("input tap 1 2"), provider.ran)
    }

    @Test
    fun bridgeRejectsAnInvalidScreenInputGesture() {
        val registry = PrivilegeRegistry(listOf(FakeInputProvider("adb", true)))
        val ops = object : GatewayModuleOps {
            override fun list() = emptyList<ModuleSummary>()
            override fun status(id: String): ModuleSummary? = null
            override fun start(id: String) = ModuleOpResult(false)
            override fun stop(id: String) = ModuleOpResult(false)
            override fun install(id: String) = ModuleOpResult(false)
            override fun uninstall(id: String) = ModuleOpResult(false)
        }
        val core = GatewayBridgeCore("s3cr3t", ops, registry)
        val (code, _) = core.handle("s3cr3t", """{"target":"screen.input","params":{"type":"bogus"}}""")
        assertEquals(400, code)
    }

    @Test
    fun bridgeWithoutPrivilegeRegistryReturns404() {
        val ops = object : GatewayModuleOps {
            override fun list() = emptyList<ModuleSummary>()
            override fun status(id: String): ModuleSummary? = null
            override fun start(id: String) = ModuleOpResult(false)
            override fun stop(id: String) = ModuleOpResult(false)
            override fun install(id: String) = ModuleOpResult(false)
            override fun uninstall(id: String) = ModuleOpResult(false)
        }
        val core = GatewayBridgeCore("s3cr3t", ops)
        val (code, _) = core.handle("s3cr3t", """{"target":"screen.input","params":{"type":"tap","x":"1","y":"2"}}""")
        assertEquals(404, code)
    }
}

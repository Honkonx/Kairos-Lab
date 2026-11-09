package com.termux.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenStreamProtocolTest {

    @Test
    fun headerRoundTripsAPlainMediaFrame() {
        val frame = ScreenFrameHeader.Frame(pts = 123_456L, config = false, keyFrame = false, size = 2048)
        val bytes = ScreenFrameHeader.encode(frame)
        assertEquals(ScreenFrameHeader.SIZE_BYTES, bytes.size)
        assertEquals(frame, ScreenFrameHeader.decode(bytes))
    }

    @Test
    fun headerRoundTripsAKeyFrame() {
        val frame = ScreenFrameHeader.Frame(pts = 999L, config = false, keyFrame = true, size = 4096)
        val decoded = ScreenFrameHeader.decode(ScreenFrameHeader.encode(frame))
        assertTrue(decoded.keyFrame)
        assertEquals(999L, decoded.pts)
        assertEquals(4096, decoded.size)
    }

    @Test
    fun headerRoundTripsAConfigPacketIgnoringPts() {
        val frame = ScreenFrameHeader.Frame(pts = 777L, config = true, keyFrame = false, size = 16)
        val decoded = ScreenFrameHeader.decode(ScreenFrameHeader.encode(frame))
        assertTrue(decoded.config)
        assertFalse(decoded.keyFrame)
        // El original tampoco preserva el pts en un paquete de config (writeFrameMeta solo escribe PACKET_FLAG_CONFIG).
        assertEquals(0L, decoded.pts)
        assertEquals(16, decoded.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun decodeRejectsTooFewBytes() {
        ScreenFrameHeader.decode(ByteArray(4))
    }

    @Test
    fun readTypeReadsTheFirstUnsignedByte() {
        assertEquals(ScreenControlMessage.TYPE_INJECT_TOUCH_EVENT, ScreenControlMessage.readType(byteArrayOf(2, 0, 0)))
        assertEquals(ScreenControlMessage.TYPE_INJECT_KEYCODE, ScreenControlMessage.readType(byteArrayOf(0)))
    }

    @Test
    fun touchMessageRoundTrips() {
        val encoded = ScreenControlMessage.encodeTouch(
            action = ScreenControlMessage.ACTION_DOWN,
            pointerId = 1L,
            x = 540,
            y = 1200,
            screenWidth = 1080,
            screenHeight = 2400,
            pressure = 1f,
            actionButton = 0,
            buttons = 1,
        )
        assertEquals(ScreenControlMessage.TYPE_INJECT_TOUCH_EVENT, ScreenControlMessage.readType(encoded))
        val decoded = ScreenControlMessage.decodeTouch(encoded)
        assertEquals(ScreenControlMessage.ACTION_DOWN, decoded.action)
        assertEquals(1L, decoded.pointerId)
        assertEquals(540, decoded.x)
        assertEquals(1200, decoded.y)
        assertEquals(1080, decoded.screenWidth)
        assertEquals(2400, decoded.screenHeight)
        assertEquals(1f, decoded.pressure, 0.001f)
        assertEquals(1, decoded.buttons)
    }

    @Test
    fun touchPressureSurvivesAPartialValue() {
        val encoded = ScreenControlMessage.encodeTouch(
            action = ScreenControlMessage.ACTION_MOVE,
            pointerId = 0L,
            x = 0,
            y = 0,
            screenWidth = 100,
            screenHeight = 100,
            pressure = 0.5f,
        )
        val decoded = ScreenControlMessage.decodeTouch(encoded)
        assertEquals(0.5f, decoded.pressure, 0.01f)
    }
}

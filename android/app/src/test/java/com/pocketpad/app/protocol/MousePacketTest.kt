package com.pocketpad.app.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class MousePacketTest {

    @Test
    fun `encode writes exact wire format`() {
        // Golden bytes pinned to PROTOCOL.md v1 — identical to the C# test
        // (MousePacketTests.Encode_WritesExactWireFormat).
        val bytes = MousePacket.encode(
            seq = 0x0201,
            s = MouseState(buttons = MouseButtons.RIGHT, dx = -2, dy = 0x1122, wheel = -1),
        )

        assertArrayEquals(
            byteArrayOf(
                0x4D, 0x01,                    // magic 'M', version 1
                0x01, 0x02,                    // seq LE
                0x02,                          // buttons: Right
                0x00,                          // reserved
                0xFE.toByte(), 0xFF.toByte(),  // dx = -2 LE
                0x22, 0x11,                    // dy LE
                0xFF.toByte(),                 // wheel = -1
                0x00,                          // reserved
            ),
            bytes,
        )
    }

    @Test
    fun `undefined button bits are masked off`() {
        val bytes = MousePacket.encode(seq = 0, s = MouseState(buttons = 0xFF))
        assertEquals(0b111, bytes[4].toInt() and 0xFF)
    }

    @Test
    fun `oversized deltas clamp instead of wrapping`() {
        val bytes = MousePacket.encode(seq = 0, s = MouseState(dx = 999_999, wheel = 999))
        // dx clamps to 32767 = 0x7FFF LE
        assertEquals(0xFF, bytes[6].toInt() and 0xFF)
        assertEquals(0x7F, bytes[7].toInt() and 0xFF)
        assertEquals(127, bytes[10].toInt())
    }

    @Test
    fun `consumed clears deltas but keeps buttons`() {
        val s = MouseState(buttons = MouseButtons.LEFT, dx = 5, dy = -5, wheel = 2).consumed()
        assertEquals(MouseButtons.LEFT, s.buttons)
        assertEquals(0, s.dx)
        assertEquals(0, s.dy)
        assertEquals(0, s.wheel)
    }
}

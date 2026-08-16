package com.pocketpad.app.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class StatePacketTest {

    @Test
    fun `encode writes exact wire format`() {
        // Golden bytes pinned to PROTOCOL.md v1 — identical to the C# test
        // (StatePacketTests.Encode_WritesExactWireFormat). If one changes, both must.
        val bytes = StatePacket.encode(
            seq = 0x0201,
            s = PadState(
                buttons = Buttons.A or Buttons.Y, // 0x0009
                dpad = Dpad.RIGHT,                // 3
                lx = 0x1122, ly = -2, rx = 0, ry = 0x7FFF,
            ),
            player = 2,
        )

        assertArrayEquals(
            byteArrayOf(
                0x50, 0x01,                    // magic 'P', version 1
                0x01, 0x02,                    // seq LE
                0x09, 0x00,                    // buttons LE
                0x03,                          // dpad Right
                0x02,                          // player 2
                0x22, 0x11,                    // lx LE
                0xFE.toByte(), 0xFF.toByte(),  // ly = -2 LE
                0x00, 0x00,                    // rx
                0xFF.toByte(), 0x7F,           // ry = 32767 LE
            ),
            bytes,
        )
    }

    @Test
    fun `seq wraps at u16`() {
        val bytes = StatePacket.encode(seq = 0x1_0001, s = PadState())
        assertEquals(0x01, bytes[2].toInt() and 0xFF)
        assertEquals(0x00, bytes[3].toInt() and 0xFF)
    }

    @Test
    fun `dpad combines directions and cancels opposites`() {
        assertEquals(Dpad.NEUTRAL, Dpad.from(up = false, down = false, left = false, right = false))
        assertEquals(Dpad.NEUTRAL, Dpad.from(up = true, down = true, left = false, right = false))
        assertEquals(Dpad.UP_RIGHT, Dpad.from(up = true, down = false, left = false, right = true))
        assertEquals(Dpad.DOWN_LEFT, Dpad.from(up = false, down = true, left = true, right = false))
        assertEquals(Dpad.LEFT, Dpad.from(up = true, down = true, left = true, right = false))
    }
}

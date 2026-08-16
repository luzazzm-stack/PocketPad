package com.pocketpad.app.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Button bits of the 16-byte state packet (PROTOCOL.md v1). */
object Buttons {
    const val A = 1 shl 0
    const val B = 1 shl 1
    const val X = 1 shl 2
    const val Y = 1 shl 3
    const val LB = 1 shl 4
    const val RB = 1 shl 5
    const val BACK = 1 shl 6
    const val START = 1 shl 7
    const val L3 = 1 shl 8
    const val R3 = 1 shl 9
    const val LT = 1 shl 10
    const val RT = 1 shl 11
}

/** D-pad direction, clockwise from Up; 0 = neutral (PROTOCOL.md v1). */
enum class Dpad(val wire: Int) {
    NEUTRAL(0), UP(1), UP_RIGHT(2), RIGHT(3), DOWN_RIGHT(4),
    DOWN(5), DOWN_LEFT(6), LEFT(7), UP_LEFT(8);

    companion object {
        /** Combine 4 pressed directions into the wire enum (opposites cancel). */
        fun from(up: Boolean, down: Boolean, left: Boolean, right: Boolean): Dpad {
            val v = (if (up) 1 else 0) - (if (down) 1 else 0)
            val h = (if (right) 1 else 0) - (if (left) 1 else 0)
            return when {
                v > 0 && h > 0 -> UP_RIGHT
                v > 0 && h < 0 -> UP_LEFT
                v < 0 && h > 0 -> DOWN_RIGHT
                v < 0 && h < 0 -> DOWN_LEFT
                v > 0 -> UP
                v < 0 -> DOWN
                h > 0 -> RIGHT
                h < 0 -> LEFT
                else -> NEUTRAL
            }
        }
    }
}

/** Full controller state; immutable snapshot taken by the sender loop. */
data class PadState(
    val buttons: Int = 0,
    val dpad: Dpad = Dpad.NEUTRAL,
    val lx: Short = 0,
    val ly: Short = 0,
    val rx: Short = 0,
    val ry: Short = 0,
)

/** Encoder for the 16-byte state packet. Little-endian, per PROTOCOL.md v1. */
object StatePacket {
    const val SIZE = 16
    const val MAGIC: Byte = 0x50 // 'P'
    const val VERSION: Byte = 0x01

    fun encode(seq: Int, s: PadState, dest: ByteArray = ByteArray(SIZE)): ByteArray {
        require(dest.size >= SIZE)
        ByteBuffer.wrap(dest).order(ByteOrder.LITTLE_ENDIAN)
            .put(MAGIC)
            .put(VERSION)
            .putShort(seq.toShort())            // u16 on the wire
            .putShort(s.buttons.toShort())      // u16 bitmask
            .put(s.dpad.wire.toByte())
            .put(0)                             // reserved
            .putShort(s.lx)
            .putShort(s.ly)
            .putShort(s.rx)
            .putShort(s.ry)
        return dest
    }
}

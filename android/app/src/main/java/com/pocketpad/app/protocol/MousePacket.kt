package com.pocketpad.app.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Mouse button bits of the 12-byte trackpad packet (PROTOCOL.md v1). */
object MouseButtons {
    const val LEFT = 1 shl 0
    const val RIGHT = 1 shl 1
    const val MIDDLE = 1 shl 2
}

/**
 * Trackpad state. [dx]/[dy]/[wheel] accumulate between sends and are zeroed
 * once transmitted — they are deltas, so re-sending would keep moving the cursor.
 */
data class MouseState(
    val buttons: Int = 0,
    val dx: Int = 0,
    val dy: Int = 0,
    val wheel: Int = 0,
) {
    /** Clear the deltas, keep the held buttons. */
    fun consumed() = copy(dx = 0, dy = 0, wheel = 0)
}

/** Encoder for the 12-byte trackpad packet. Little-endian, per PROTOCOL.md v1. */
object MousePacket {
    const val SIZE = 12
    const val MAGIC: Byte = 0x4D // 'M'
    const val VERSION: Byte = 0x01

    fun encode(seq: Int, s: MouseState, dest: ByteArray = ByteArray(SIZE)): ByteArray {
        require(dest.size >= SIZE)
        ByteBuffer.wrap(dest).order(ByteOrder.LITTLE_ENDIAN)
            .put(MAGIC)
            .put(VERSION)
            .putShort(seq.toShort())
            .put((s.buttons and 0b111).toByte())
            .put(0)
            .putShort(s.dx.coerceIn(-32768, 32767).toShort())
            .putShort(s.dy.coerceIn(-32768, 32767).toShort())
            .put(s.wheel.coerceIn(-128, 127).toByte())
            .put(0)
        return dest
    }
}

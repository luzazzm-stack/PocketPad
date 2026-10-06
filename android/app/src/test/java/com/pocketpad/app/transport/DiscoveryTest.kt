package com.pocketpad.app.transport

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryTest {

    @Test
    fun `encode writes exact wire format`() {
        // Golden bytes pinned to PROTOCOL.md "Discovery" — identical to the C# test
        // (DiscoveryTests.Encode_WritesExactWireFormat).
        val fp = Discovery.fingerprint("ed8df5da")
        assertArrayEquals(byteArrayOf(0xBF.toByte(), 0xA9.toByte(), 0x53, 0x81.toByte()), fp)

        assertArrayEquals(
            byteArrayOf(0x44, 0x01, 0x00, 0x00, 0xBF.toByte(), 0xA9.toByte(), 0x53, 0x81.toByte()),
            Discovery.encode(Discovery.KIND_REQUEST, fp),
        )
    }

    @Test
    fun `accepts only replies for its own fingerprint`() {
        val mine = Discovery.fingerprint("ed8df5da")
        val other = Discovery.fingerprint("00000000")

        val reply = Discovery.encode(Discovery.KIND_REPLY, mine)
        assertTrue(Discovery.isReplyFor(reply, reply.size, mine))
        assertFalse(Discovery.isReplyFor(reply, reply.size, other))
        val request = Discovery.encode(Discovery.KIND_REQUEST, mine)
        assertFalse(Discovery.isReplyFor(request, request.size, mine))
    }
}

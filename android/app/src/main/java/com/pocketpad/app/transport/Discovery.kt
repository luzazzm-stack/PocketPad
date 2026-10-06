package com.pocketpad.app.transport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.security.MessageDigest

/**
 * LAN discovery (PROTOCOL.md "Discovery"): find the PC that holds [token]
 * when its saved address no longer answers — Wi-Fi handed it a new IP, or it
 * was last seen on another network. Only the fingerprint goes on the wire.
 */
object Discovery {
    const val MAGIC: Byte = 0x44 // 'D'
    const val VERSION: Byte = 0x01
    const val KIND_REQUEST: Byte = 0x00
    const val KIND_REPLY: Byte = 0x01
    const val SIZE = 8

    /** First 4 bytes of SHA-256 of the UTF-8 pairing code. */
    fun fingerprint(token: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8)).copyOf(4)

    fun encode(kind: Byte, fp: ByteArray): ByteArray =
        byteArrayOf(MAGIC, VERSION, kind, 0, fp[0], fp[1], fp[2], fp[3])

    fun isReplyFor(buf: ByteArray, len: Int, fp: ByteArray): Boolean =
        len == SIZE && buf[0] == MAGIC && buf[1] == VERSION && buf[2] == KIND_REPLY &&
            buf[4] == fp[0] && buf[5] == fp[1] && buf[6] == fp[2] && buf[7] == fp[3]

    /**
     * Broadcast for up to [timeoutMs] and return the address of the PC that
     * answers for [token], or null. Re-sends every few hundred ms because a
     * single broadcast is easily lost on busy Wi-Fi.
     */
    suspend fun find(token: String, udpPort: Int = 46821, timeoutMs: Long = 2500): String? =
        withContext(Dispatchers.IO) {
            val fp = fingerprint(token.trim())
            val request = encode(KIND_REQUEST, fp)
            val targets = broadcastTargets()
            runCatching {
                DatagramSocket().use { socket ->
                    socket.broadcast = true
                    socket.soTimeout = 300
                    val buf = ByteArray(64)
                    val deadline = System.currentTimeMillis() + timeoutMs
                    while (System.currentTimeMillis() < deadline) {
                        for (t in targets) {
                            runCatching { socket.send(DatagramPacket(request, request.size, t, udpPort)) }
                        }
                        val sliceEnd = minOf(deadline, System.currentTimeMillis() + 300)
                        while (System.currentTimeMillis() < sliceEnd) {
                            val p = DatagramPacket(buf, buf.size)
                            try {
                                socket.receive(p)
                            } catch (_: SocketTimeoutException) {
                                break
                            }
                            if (isReplyFor(p.data, p.length, fp)) return@runCatching p.address.hostAddress
                        }
                    }
                    null
                }
            }.getOrNull()
        }

    /**
     * Global broadcast plus every interface's own subnet broadcast: on a
     * phone hotspot the global one may leave through mobile data instead.
     */
    private fun broadcastTargets(): List<InetAddress> {
        val out = linkedSetOf<InetAddress>(InetAddress.getByName("255.255.255.255"))
        runCatching {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback) continue
                for (ia in nif.interfaceAddresses) {
                    if (ia.address is Inet4Address) ia.broadcast?.let { out += it }
                }
            }
        }
        return out.toList()
    }
}

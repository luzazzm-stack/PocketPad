package com.pocketpad.app.transport

import com.pocketpad.app.protocol.Buttons
import com.pocketpad.app.protocol.Dpad
import com.pocketpad.app.protocol.MouseButtons
import com.pocketpad.app.protocol.MousePacket
import com.pocketpad.app.protocol.PadState
import com.pocketpad.app.protocol.StatePacket
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The real [PadConnection] driven against loopback sockets — a stand-in PC.
 *
 * The pacer's own tests prove the pacing arithmetic; these prove it is
 * actually WIRED to the wire. Every bug this class exists to catch has the
 * same shape: a press that the UI registered and the phone drew, but no
 * packet ever carried. That is invisible to a unit test of either half.
 */
class PadConnectionTest {

    private lateinit var tcp: ServerSocket
    private lateinit var udp: DatagramSocket
    private lateinit var conn: PadConnection
    private val connected = CountDownLatch(1)

    @Before
    fun setUp() {
        tcp = ServerSocket(0)
        udp = DatagramSocket(0)
        udp.soTimeout = 4000

        // Stand-in PC: accept, answer the hello with a welcome naming our UDP
        // port, then hold the socket open so the sender keeps running.
        thread(isDaemon = true) {
            runCatching {
                val s = tcp.accept()
                val reader = s.getInputStream().bufferedReader()
                reader.readLine() // hello
                val w = s.getOutputStream().bufferedWriter()
                w.write("""{"t":"welcome","v":1,"udp":${udp.localPort},"player":0}""")
                w.write("\n")
                w.flush()
                while (s.isConnected && !s.isClosed) {
                    if (reader.readLine() == null) break
                }
            }
        }

        conn = PadConnection("127.0.0.1", "tok", "test-phone", tcpPort = tcp.localPort)
        conn.start { if (it is PadConnection.Event.Connected) connected.countDown() }
        assertTrue("never connected", connected.await(5, TimeUnit.SECONDS))
    }

    @After
    fun tearDown() {
        runCatching { udp.close() }
        runCatching { tcp.close() }
    }

    /** Collect pad packets for [ms], decoded as (buttons, dpad). */
    private fun collectPad(ms: Long): List<Pair<Int, Dpad>> = collect(ms) { buf ->
        if (buf[0] != StatePacket.MAGIC) null
        else {
            val b = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
            val buttons = b.getShort(4).toInt() and 0xFFFF
            val dpad = Dpad.entries.first { it.wire == buf[6].toInt() }
            buttons to dpad
        }
    }

    /** Collect trackpad packets for [ms], decoded as a button mask. */
    private fun collectMouse(ms: Long): List<Int> = collect(ms) { buf ->
        if (buf[0] != MousePacket.MAGIC) null else buf[4].toInt() and 0xFF
    }

    private fun <T : Any> collect(ms: Long, decode: (ByteArray) -> T?): List<T> {
        val out = mutableListOf<T>()
        val deadline = System.nanoTime() + ms * 1_000_000
        val p = DatagramPacket(ByteArray(64), 64)
        while (System.nanoTime() < deadline) {
            try {
                udp.receive(p)
            } catch (_: SocketTimeoutException) {
                break
            }
            decode(p.data.copyOf(p.length))?.let { out.add(it) }
        }
        return out
    }

    /** Number of times [bit] goes from absent to present across the trace. */
    private fun List<Int>.pressCount(bit: Int): Int {
        var n = 0
        var down = false
        for (m in this) {
            val now = m and bit != 0
            if (now && !down) n++
            down = now
        }
        return n
    }

    @Test
    fun `a tap far shorter than the send period still reaches the wire`() {
        // Down and up back to back, as a stalled UI thread delivers them.
        conn.setPadState(PadState(buttons = Buttons.A))
        conn.setPadState(PadState())
        val trace = collectPad(300).map { it.first }
        assertEquals(1, trace.pressCount(Buttons.A))
        assertEquals(0, trace.last())
    }

    @Test
    fun `three fast taps arrive as three presses`() {
        repeat(3) {
            conn.setPadState(PadState(buttons = Buttons.A))
            conn.setPadState(PadState())
        }
        val trace = collectPad(500).map { it.first }
        assertEquals(3, trace.pressCount(Buttons.A))
    }

    @Test
    fun `a d-pad roll does not delay the button that follows it`() {
        conn.setPadState(PadState(dpad = Dpad.LEFT))
        conn.setPadState(PadState(dpad = Dpad.DOWN_LEFT))
        conn.setPadState(PadState(dpad = Dpad.DOWN))
        conn.setPadState(PadState(buttons = Buttons.A, dpad = Dpad.DOWN))
        val trace = collectPad(400)
        val firstButton = trace.indexOfFirst { it.first and Buttons.A != 0 }
        val firstDir = trace.indexOfFirst { it.second != Dpad.NEUTRAL }
        assertTrue("no A packet at all", firstButton >= 0)
        // The punch must not wait for the motion to drain — the whole reason
        // controls pace independently.
        assertTrue("A lagged the roll by $firstButton vs $firstDir packets",
            firstButton <= firstDir + 1)
    }

    @Test
    fun `sticks keep flowing while a button is paced`() {
        conn.setPadState(PadState(buttons = Buttons.A, lx = 12000))
        conn.setPadState(PadState(buttons = Buttons.A, lx = 24000))
        val trace = collectPad(200)
        assertTrue(trace.isNotEmpty())
        assertTrue("button never went out", trace.any { it.first and Buttons.A != 0 })
    }

    @Test
    fun `leaving the pad releases everything held`() {
        conn.setPadState(PadState(buttons = Buttons.RT, dpad = Dpad.UP))
        collectPad(120) // let it settle as held
        conn.releasePad()
        val after = collectPad(200)
        assertEquals(0 to Dpad.NEUTRAL, after.last())
    }

    @Test
    fun `a trackpad tap reaches the wire`() {
        conn.setMode(PadConnection.Mode.MOUSE)
        conn.clickMouse(MouseButtons.LEFT)
        val trace = collectMouse(300)
        assertEquals(1, trace.pressCount(MouseButtons.LEFT))
        assertEquals(0, trace.last())
    }

    @Test
    fun `a double click stays two clicks`() {
        conn.setMode(PadConnection.Mode.MOUSE)
        conn.clickMouse(MouseButtons.LEFT)
        conn.clickMouse(MouseButtons.LEFT)
        val trace = collectMouse(400)
        // Opening a game from the desktop depends on this one.
        assertEquals(2, trace.pressCount(MouseButtons.LEFT))
    }

    @Test
    fun `a held mouse button stays down until the finger lifts`() {
        conn.setMode(PadConnection.Mode.MOUSE)
        conn.setMouseButton(MouseButtons.LEFT, true)
        val held = collectMouse(200)
        assertTrue("never pressed", held.any { it and MouseButtons.LEFT != 0 })
        assertEquals(MouseButtons.LEFT, held.last()) // a drag must not self-release
        conn.setMouseButton(MouseButtons.LEFT, false)
        assertEquals(0, collectMouse(200).last())
    }
}

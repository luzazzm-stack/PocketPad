package com.pocketpad.app.transport

import com.pocketpad.app.protocol.Dpad
import com.pocketpad.app.protocol.MousePacket
import com.pocketpad.app.protocol.MouseState
import com.pocketpad.app.protocol.PadState
import com.pocketpad.app.protocol.StatePacket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Wi-Fi / hotspot transport (PROTOCOL.md v1):
 * TCP handshake + ping on [tcpPort], then 16-byte UDP state packets at 125 Hz.
 *
 * USB reuses this class unchanged — `adb reverse` makes host "127.0.0.1".
 */
class PadConnection(
    private val host: String,
    private val token: String,
    private val deviceName: String,
    private val tcpPort: Int = 46822,
) {
    sealed interface Event {
        data object Connected : Event
        data class Rejected(val reason: String) : Event
        data class Latency(val rttMs: Long) : Event
        data class Disconnected(val error: String?) : Event
    }

    /** Which kind of packet the sender is currently emitting. */
    enum class Mode { PAD, MOUSE }

    /** Latest full pad state. Private so every write goes through [setPadState]. */
    private val state = AtomicReference(PadState())

    /** Current send mode; flip with [setMode] so the handover stays clean. */
    val mode = AtomicReference(Mode.PAD)

    private val mouse = AtomicReference(MouseState())

    /**
     * Paces pad button and d-pad edges onto the wire. The sender samples
     * [state] for the sticks (latest wins on a continuous axis), but buttons
     * and d-pad go through this so a burst of taps comes out as the same
     * number of distinct, game-visible presses — see [EdgePacer].
     */
    private val pacer = EdgePacer()

    /**
     * The same pacing for mouse buttons, which need it just as badly: a
     * double-click to launch a game is two taps inside a few tens of ms, and
     * the PC injects a click per received packet with no retransmit.
     */
    private val mousePacer = EdgePacer()

    /** Mouse buttons held by a finger, as opposed to tapped. */
    private val mouseHeld = AtomicInteger(0)

    fun setMode(m: Mode) {
        // Drop everything held so nothing sticks down across the switch.
        state.set(PadState())
        mouse.set(MouseState())
        mouseHeld.set(0)
        pacer.releaseAll()
        mousePacer.releaseAll()
        mode.set(m)
    }

    /** Publish the pad state. Button and d-pad edges are paced, never lost. */
    fun setPadState(s: PadState) {
        state.set(s)
        pacer.offer(s.buttons, s.dpad)
    }

    /**
     * Release every pad control on the wire immediately.
     *
     * Leaving the pad screen must not leave a button held: the sender keeps
     * transmitting whatever the pacer last emitted, and the PC only drops
     * everything on TCP disconnect — which opening Settings is not.
     */
    fun releasePad() {
        state.set(PadState())
        pacer.releaseAll()
    }

    /** Accumulate pointer movement (pixels) until the next packet goes out. */
    fun moveMouse(dx: Int, dy: Int) {
        mouse.getAndUpdate { it.copy(dx = it.dx + dx, dy = it.dy + dy) }
    }

    /** Accumulate wheel notches (+up). */
    fun scrollMouse(notches: Int) {
        mouse.getAndUpdate { it.copy(wheel = it.wheel + notches) }
    }

    /** A button held down by a finger; the finger's release ends it. */
    fun setMouseButton(bit: Int, pressed: Boolean) {
        val held = mouseHeld.updateAndGet {
            if (pressed) it or bit else it and bit.inv()
        }
        mousePacer.offer(held, Dpad.NEUTRAL)
    }

    /**
     * A tap: one press and its release, queued as a pair. The pacer holds the
     * press on the wire long enough for the PC to see it, so a tap shorter
     * than the send period still lands — and two fast taps stay two clicks.
     */
    fun clickMouse(bit: Int) {
        val held = mouseHeld.get()
        mousePacer.offer(held or bit, Dpad.NEUTRAL)
        mousePacer.offer(held, Dpad.NEUTRAL)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    /** Connect and run until [close] or failure. Events land on [onEvent] (IO thread). */
    fun start(onEvent: (Event) -> Unit) {
        job = scope.launch {
            var socket: Socket? = null
            var udp: DatagramSocket? = null
            try {
                // ---- TCP handshake ----
                val s = Socket().also { socket = it }
                s.tcpNoDelay = true
                s.connect(InetSocketAddress(host, tcpPort), 4000)
                val reader = s.getInputStream().bufferedReader(Charsets.UTF_8)
                val writer = s.getOutputStream().bufferedWriter(Charsets.UTF_8)
                val writeLock = Any() // ping loop + pong handler both write

                writer.sendJson {
                    put("t", "hello"); put("v", 1)
                    put("name", deviceName); put("token", token)
                }
                val reply = JSONObject(reader.readLine() ?: throw java.io.IOException("closed"))
                if (reply.getString("t") != "welcome") {
                    onEvent(Event.Rejected(reply.optString("reason", "unknown")))
                    return@launch
                }
                val udpPort = reply.optInt("udp", 46821)
                val player = reply.optInt("player", 0) // our slot: stamped into every packet
                onEvent(Event.Connected)

                // ---- UDP sender: 125 Hz ----
                val ds = DatagramSocket().also { udp = it }
                val addr = InetAddress.getByName(host)
                val padBuf = ByteArray(StatePacket.SIZE)
                val mouseBuf = ByteArray(MousePacket.SIZE)
                val senderJob = launch {
                    var seq = 0
                    var lastMode = Mode.PAD

                    fun send(b: ByteArray) {
                        ds.send(DatagramPacket(b, b.size, addr, udpPort))
                        seq = (seq + 1) and 0xFFFF
                    }

                    while (isActive) {
                        val m = mode.get()
                        if (m != lastMode) {
                            // Emit one neutral packet of the mode we're leaving so the
                            // PC releases whatever was held (PROTOCOL.md, mode switch).
                            if (m == Mode.MOUSE) {
                                send(StatePacket.encode(seq, PadState(), player, padBuf))
                            } else {
                                send(MousePacket.encode(seq, MouseState(), player, mouseBuf))
                            }
                            lastMode = m
                        }

                        if (m == Mode.PAD) {
                            // Sticks from the latest state; buttons and d-pad
                            // from the pacer, which replays every edge in
                            // order and holds each one long enough to be seen.
                            val snap = state.get()
                            val (btns, dp) = pacer.tick()
                            send(StatePacket.encode(
                                seq, snap.copy(buttons = btns, dpad = dp), player, padBuf))
                        } else {
                            // Deltas are consumed: take them and zero them
                            // atomically. Buttons come from the pacer, which
                            // holds each click long enough to survive the trip.
                            val snapshot = mouse.getAndUpdate { it.consumed() }
                            val (btns, _) = mousePacer.tick()
                            send(MousePacket.encode(
                                seq, snapshot.copy(buttons = btns), player, mouseBuf))
                        }
                        delay(EdgePacer.SEND_PERIOD_MS)
                    }
                }

                // ---- ping loop (1 s) + pong reader for the latency meter ----
                val pingJob = launch {
                    var id = 0L
                    while (isActive) {
                        synchronized(writeLock) {
                            writer.sendJson {
                                put("t", "ping"); put("id", id)
                                put("ts", System.nanoTime() / 1_000_000)
                            }
                        }
                        id++
                        delay(1000)
                    }
                }
                try {
                    while (isActive) {
                        val line = reader.readLine() ?: break
                        val msg = JSONObject(line)
                        when (msg.optString("t")) {
                            "pong" -> {
                                val rtt = System.nanoTime() / 1_000_000 - msg.getLong("ts")
                                onEvent(Event.Latency(rtt))
                                // Report it back so the PC app can show the same number.
                                synchronized(writeLock) {
                                    writer.sendJson { put("t", "lat"); put("ms", rtt) }
                                }
                            }
                            "bye" -> break
                        }
                    }
                } finally {
                    senderJob.cancelAndJoin()
                    pingJob.cancelAndJoin()
                }
                onEvent(Event.Disconnected(null))
            } catch (e: Exception) {
                if (isActive) onEvent(Event.Disconnected(e.message ?: e.javaClass.simpleName))
            } finally {
                udp?.close()
                socket?.close()
            }
        }
    }

    suspend fun close() {
        job?.cancelAndJoin()
        withContext(Dispatchers.IO) { /* sockets closed in finally */ }
    }

    private fun BufferedWriter.sendJson(build: JSONObject.() -> Unit) {
        write(JSONObject().apply(build).toString())
        write("\n")
        flush()
    }
}

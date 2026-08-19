package com.pocketpad.app.transport

import com.pocketpad.app.protocol.Buttons
import com.pocketpad.app.protocol.Dpad
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pacer is what turns a burst of touch edges into presses a game can see.
 * These rules were bought with a real bug — three fast taps arriving as one
 * press — so they are pinned: every edge survives, in order, and each one
 * holds the wire for at least MIN_HOLD_TICKS packets.
 */
class EdgePacerTest {

    private val hold = EdgePacer.MIN_HOLD_TICKS

    /** Run [n] ticks, returning each tick's button mask. */
    private fun EdgePacer.run(n: Int): List<Int> = List(n) { tick().first }

    /** Count press pulses (0 -> nonzero transitions) of [bit] in a mask trace. */
    private fun List<Int>.pulsesOf(bit: Int): Int {
        var pulses = 0
        var down = false
        for (m in this) {
            val now = m and bit != 0
            if (now && !down) pulses++
            down = now
        }
        return pulses
    }

    @Test
    fun `a tap between two ticks still becomes a full press`() {
        val p = EdgePacer()
        p.offer(Buttons.A, Dpad.NEUTRAL)
        p.offer(0, Dpad.NEUTRAL) // finger already up before the sender looked
        val trace = p.run(hold * 2)
        assertEquals(1, trace.pulsesOf(Buttons.A))
        // The press occupies exactly the minimum hold, then releases.
        assertEquals(List(hold) { Buttons.A } + List(hold) { 0 }, trace)
    }

    @Test
    fun `three batched taps come out as three presses`() {
        val p = EdgePacer()
        repeat(3) {
            p.offer(Buttons.A, Dpad.NEUTRAL)
            p.offer(0, Dpad.NEUTRAL)
        }
        val trace = p.run(hold * 8)
        assertEquals(3, trace.pulsesOf(Buttons.A))
        assertEquals(0, trace.last()) // and it settles released
    }

    @Test
    fun `a chord forming across two events is not delayed`() {
        val p = EdgePacer()
        p.offer(Buttons.A, Dpad.NEUTRAL)
        assertEquals(Buttons.A, p.tick().first)
        p.offer(Buttons.A or Buttons.X, Dpad.NEUTRAL)
        // X was long released, so it joins on the very next tick.
        assertEquals(Buttons.A or Buttons.X, p.tick().first)
    }

    @Test
    fun `edges of different buttons never block each other`() {
        val p = EdgePacer()
        p.offer(Buttons.A, Dpad.NEUTRAL)
        p.tick()
        // B taps while A's press is still fresh: B's edge is its own control.
        p.offer(Buttons.A or Buttons.B, Dpad.NEUTRAL)
        assertEquals(Buttons.A or Buttons.B, p.tick().first)
    }

    @Test
    fun `a steady hold passes through and releases without extra latency`() {
        val p = EdgePacer()
        p.offer(Buttons.RT, Dpad.NEUTRAL)
        assertEquals(List(hold * 4) { Buttons.RT }, p.run(hold * 4))
        p.offer(0, Dpad.NEUTRAL)
        assertEquals(0, p.tick().first) // hold long satisfied: instant release
    }

    @Test
    fun `dpad double tap survives batching`() {
        val p = EdgePacer()
        repeat(2) {
            p.offer(0, Dpad.RIGHT)
            p.offer(0, Dpad.NEUTRAL)
        }
        val dirs = List(hold * 6) { p.tick().second }
        var pulses = 0
        var down = false
        for (d in dirs) {
            val now = d == Dpad.RIGHT
            if (now && !down) pulses++
            down = now
        }
        assertEquals(2, pulses) // a Tekken f,f dash stays a dash
        assertEquals(Dpad.NEUTRAL, dirs.last())
    }

    @Test
    fun `every press spans the minimum hold`() {
        val p = EdgePacer()
        repeat(4) {
            p.offer(Buttons.Y, Dpad.NEUTRAL)
            p.offer(0, Dpad.NEUTRAL)
        }
        val trace = p.run(hold * 12)
        var runLen = 0
        for (m in trace) {
            if (m and Buttons.Y != 0) runLen++
            else {
                if (runLen > 0) assertTrue("press of $runLen < $hold ticks", runLen >= hold)
                runLen = 0
            }
        }
    }

    @Test
    fun `reset drops the queue and the wire state at once`() {
        val p = EdgePacer()
        p.offer(Buttons.A, Dpad.UP)
        p.tick()
        p.offer(Buttons.B, Dpad.DOWN)
        p.reset()
        assertEquals(0 to Dpad.NEUTRAL, p.tick())
    }

    @Test
    fun `overflow keeps newest edges and still converges on the offered state`() {
        val p = EdgePacer()
        repeat(500) { i ->
            p.offer(if (i % 2 == 0) Buttons.A else 0, Dpad.NEUTRAL)
        }
        p.offer(Buttons.START, Dpad.NEUTRAL)
        val trace = p.run(hold * 70)
        assertEquals(Buttons.START, trace.last()) // never wedges, ends current
    }
}

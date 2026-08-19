package com.pocketpad.app.transport

import com.pocketpad.app.protocol.Buttons
import com.pocketpad.app.protocol.Dpad
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pacer is what turns a burst of touch edges into presses a game can see.
 * These rules were bought with real bugs — three fast taps arriving as one
 * press, then a d-pad roll holding the punch behind it — so they are pinned:
 * every edge survives, in order per control, each holds the wire long enough
 * to be seen, and no control can delay another.
 */
class EdgePacerTest {

    private val hold = EdgePacer.MIN_HOLD_TICKS

    /** Run [n] ticks, returning each tick's button mask. */
    private fun EdgePacer.runMasks(n: Int): List<Int> = List(n) { tick().first }

    /** Run [n] ticks, returning each tick's direction. */
    private fun EdgePacer.runDirs(n: Int): List<Dpad> = List(n) { tick().second }

    /** Lengths of each contiguous run where [hit] holds — every press, in order. */
    private fun <T> List<T>.pressRuns(hit: (T) -> Boolean): List<Int> {
        val runs = mutableListOf<Int>()
        var len = 0
        for (v in this) {
            if (hit(v)) len++
            else if (len > 0) { runs.add(len); len = 0 }
        }
        if (len > 0) runs.add(len) // a press still open at the last tick counts
        return runs
    }

    private fun List<Int>.pressesOf(bit: Int) = pressRuns { it and bit != 0 }

    @Test
    fun `hold ticks cover at least one 60 Hz frame`() {
        // The whole point of the constant: if a tick period or hold budget is
        // ever retuned, this is what catches a hold that no longer spans a
        // frame a game can poll.
        assertTrue(EdgePacer.MIN_HOLD_TICKS * EdgePacer.SEND_PERIOD_MS >= 17)
        assertEquals(EdgePacer.MIN_HOLD_MS, 24L)
    }

    @Test
    fun `a tap between two ticks still becomes a full press`() {
        val p = EdgePacer()
        p.offer(Buttons.A, Dpad.NEUTRAL)
        p.offer(0, Dpad.NEUTRAL) // finger already up before the sender looked
        val trace = p.runMasks(hold * 2)
        assertEquals(listOf(hold), trace.pressesOf(Buttons.A))
        assertEquals(List(hold) { Buttons.A } + List(hold) { 0 }, trace)
    }

    @Test
    fun `three batched taps come out as three presses, each held`() {
        val p = EdgePacer()
        repeat(3) {
            p.offer(Buttons.A, Dpad.NEUTRAL)
            p.offer(0, Dpad.NEUTRAL)
        }
        val trace = p.runMasks(hold * 8)
        assertEquals(listOf(hold, hold, hold), trace.pressesOf(Buttons.A))
        assertEquals(0, trace.last()) // and it settles released
    }

    @Test
    fun `every press of a long mash is transmitted and long enough`() {
        val taps = 6
        val p = EdgePacer()
        repeat(taps) {
            p.offer(Buttons.Y, Dpad.NEUTRAL)
            p.offer(0, Dpad.NEUTRAL)
        }
        val presses = p.runMasks(hold * (taps * 2 + 2)).pressesOf(Buttons.Y)
        // Count first: an earlier version of this test only measured the
        // presses it happened to find, so a pacer that silently ate half of
        // them passed.
        assertEquals(taps, presses.size)
        presses.forEach { assertTrue("press of $it < $hold ticks", it >= hold) }
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
    fun `a fresh press never delays a different button`() {
        val p = EdgePacer()
        p.offer(Buttons.A, Dpad.NEUTRAL)
        p.tick() // A is now maximally fresh
        p.offer(Buttons.A or Buttons.B, Dpad.NEUTRAL)
        assertEquals(Buttons.A or Buttons.B, p.tick().first)
    }

    @Test
    fun `a d-pad roll does not hold up the punch behind it`() {
        // The regression that per-control pacing exists for: a quarter-circle
        // batched into one gap, then a button. Under one shared FIFO the
        // button waited for every direction to drain.
        val p = EdgePacer()
        p.offer(0, Dpad.LEFT)
        p.offer(0, Dpad.DOWN_LEFT)
        p.offer(0, Dpad.DOWN)
        p.offer(Buttons.A, Dpad.DOWN)
        assertEquals(Buttons.A, p.tick().first) // same tick as the first direction
    }

    @Test
    fun `a steady hold passes through and releases without extra latency`() {
        val p = EdgePacer()
        p.offer(Buttons.RT, Dpad.NEUTRAL)
        assertEquals(List(hold * 4) { Buttons.RT }, p.runMasks(hold * 4))
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
        val dirs = p.runDirs(hold * 6)
        assertEquals(listOf(hold, hold), dirs.pressRuns { it == Dpad.RIGHT })
        assertEquals(Dpad.NEUTRAL, dirs.last()) // a Tekken f,f dash stays a dash
    }

    @Test
    fun `releaseAll drops the queue and the wire state at once`() {
        val p = EdgePacer()
        p.offer(Buttons.A, Dpad.UP)
        p.tick()
        p.offer(Buttons.B, Dpad.DOWN)
        p.releaseAll()
        assertEquals(0 to Dpad.NEUTRAL, p.tick())
    }

    @Test
    fun `a press right after releaseAll cannot cut the neutral short`() {
        val p = EdgePacer()
        p.offer(Buttons.A, Dpad.NEUTRAL)
        p.tick()
        p.releaseAll()
        p.offer(Buttons.A, Dpad.NEUTRAL) // finger back down immediately
        val trace = p.runMasks(hold * 2)
        // The forced neutral is an edge like any other and keeps its hold, so
        // the PC cannot see a 1-packet release it might miss entirely.
        assertEquals(List(hold) { 0 } + List(hold) { Buttons.A }, trace)
    }

    @Test
    fun `a saturated button still ends where the finger left it`() {
        // Overflow must drop presses in whole press+release pairs. Dropping a
        // single edge flips the parity and the button sticks down forever.
        val p = EdgePacer()
        repeat(200) {
            p.offer(Buttons.A, Dpad.NEUTRAL)
            p.offer(0, Dpad.NEUTRAL)
        }
        val trace = p.runMasks(hold * 500)
        assertEquals(0, trace.last())
        // Whatever survived, each survivor is a real, full-length press.
        trace.pressesOf(Buttons.A).forEach { assertTrue(it >= hold) }
    }

    @Test
    fun `a saturated button ending held stays held`() {
        val p = EdgePacer()
        repeat(200) {
            p.offer(Buttons.A, Dpad.NEUTRAL)
            p.offer(0, Dpad.NEUTRAL)
        }
        p.offer(Buttons.A, Dpad.NEUTRAL) // and this time the finger stays down
        assertEquals(Buttons.A, p.runMasks(hold * 500).last())
    }

    @Test
    fun `a flooded d-pad still converges on the direction actually held`() {
        val p = EdgePacer()
        val ring = listOf(Dpad.UP, Dpad.UP_RIGHT, Dpad.RIGHT, Dpad.DOWN_RIGHT)
        repeat(200) { p.offer(0, ring[it % ring.size]) }
        p.offer(0, Dpad.LEFT)
        assertEquals(Dpad.LEFT, p.runDirs(hold * 200).last())
    }

    @Test
    fun `backlog on one control never leaks into another`() {
        val p = EdgePacer()
        repeat(200) { p.offer(0, if (it % 2 == 0) Dpad.LEFT else Dpad.RIGHT) }
        // The d-pad is now deeply backed up; a button must not notice.
        p.offer(Buttons.START, Dpad.RIGHT)
        assertEquals(Buttons.START, p.tick().first)
    }
}

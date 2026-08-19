package com.pocketpad.app.ui

import androidx.compose.ui.geometry.Offset
import com.pocketpad.app.protocol.Dpad
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * The cross's angle-to-direction mapping, including the hysteresis that keeps
 * a resting thumb from chattering between two directions — which used to flood
 * the wire with direction changes no player asked for.
 */
class DpadDirectionTest {

    private val size = 200f

    /** A touch [deg] clockwise from Up, at 40% of the control's width out. */
    private fun at(deg: Float): Offset {
        val rad = Math.toRadians(deg.toDouble())
        val r = size * 0.4f
        return Offset(size / 2f + (r * sin(rad)).toFloat(), size / 2f - (r * cos(rad)).toFloat())
    }

    private fun dirAt(deg: Float, previous: Dpad = Dpad.NEUTRAL) =
        dpadDirAt(at(deg), size, previous)

    @Test
    fun `each direction owns the middle of its window`() {
        assertEquals(Dpad.UP, dirAt(0f))
        assertEquals(Dpad.UP_RIGHT, dirAt(45f))
        assertEquals(Dpad.RIGHT, dirAt(90f))
        assertEquals(Dpad.DOWN_RIGHT, dirAt(135f))
        assertEquals(Dpad.DOWN, dirAt(180f))
        assertEquals(Dpad.DOWN_LEFT, dirAt(225f))
        assertEquals(Dpad.LEFT, dirAt(270f))
        assertEquals(Dpad.UP_LEFT, dirAt(315f))
    }

    @Test
    fun `up keeps its full window across the zero wrap`() {
        // UP is the only direction whose window wraps, so it is the only one
        // where an off-by-one in the range check hides.
        assertEquals(Dpad.UP, dirAt(331f))
        assertEquals(Dpad.UP, dirAt(359f))
        assertEquals(Dpad.UP, dirAt(29f))
        assertEquals(Dpad.UP_LEFT, dirAt(329f))
        assertEquals(Dpad.UP_RIGHT, dirAt(31f))
    }

    @Test
    fun `a thumb just past a boundary keeps the direction it is holding`() {
        // 31 degrees is inside UP_RIGHT's window, but a thumb holding UP that
        // drifts there is jitter, not a re-aim.
        assertEquals(Dpad.UP, dirAt(31f, previous = Dpad.UP))
        assertEquals(Dpad.UP, dirAt(37f, previous = Dpad.UP))
        // Past the margin it is a real change.
        assertEquals(Dpad.UP_RIGHT, dirAt(39f, previous = Dpad.UP))
    }

    @Test
    fun `hysteresis holds across the zero wrap too`() {
        assertEquals(Dpad.UP, dirAt(325f, previous = Dpad.UP))
        assertEquals(Dpad.UP_LEFT, dirAt(321f, previous = Dpad.UP))
    }

    @Test
    fun `hysteresis never blocks a deliberate move`() {
        // Every direction must still be reachable from every other one.
        val cardinals = listOf(0f to Dpad.UP, 90f to Dpad.RIGHT, 180f to Dpad.DOWN, 270f to Dpad.LEFT)
        for ((deg, expected) in cardinals) {
            for (previous in Dpad.entries) {
                assertEquals("aiming $deg while holding $previous", expected, dirAt(deg, previous))
            }
        }
    }

    @Test
    fun `the centre is neutral, and leaving it needs more travel than returning`() {
        val c = Offset(size / 2f, size / 2f)
        assertEquals(Dpad.NEUTRAL, dpadDirAt(c, size, Dpad.NEUTRAL))

        // 12% out: past the 10% return threshold but short of the 14% needed
        // to leave the centre, so it depends on what is already held.
        val edge = Offset(size / 2f, size / 2f - size * 0.12f)
        assertEquals(Dpad.NEUTRAL, dpadDirAt(edge, size, Dpad.NEUTRAL))
        assertEquals(Dpad.UP, dpadDirAt(edge, size, Dpad.UP))
    }

    @Test
    fun `an unmeasured control never reports a direction`() {
        assertEquals(Dpad.NEUTRAL, dpadDirAt(Offset(50f, 50f), 0f, Dpad.NEUTRAL))
    }
}

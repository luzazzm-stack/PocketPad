package com.pocketpad.app.transport

import com.pocketpad.app.protocol.Dpad

/**
 * Feeds button and d-pad changes onto the fixed-rate wire so that no press is
 * ever lost, none is too short for a game to see, and no control can delay
 * another.
 *
 * The sender used to sample the latest state each tick with a press *bitmask*
 * latched so a tap shorter than the send period still went out. Two ways that
 * lost input:
 *  - the latch is a bitmask, so three quick taps of the same button between
 *    two ticks OR into one bit and came out as ONE press;
 *  - a latched press rode exactly one packet — one send period of virtual-pad
 *    time, shorter than a 60 Hz game's input frame, so the game could poll
 *    right past it, and a single lost datagram erased it completely.
 * Both were easy to hit whenever the UI thread stalled and delivered a burst
 * of touch events back to back.
 *
 * So each control paces itself INDEPENDENTLY: a control that changed on the
 * wire holds its new value for [MIN_HOLD_TICKS] before it may change again,
 * and meanwhile every other control is free to move. Consequences:
 *  - N taps always come out as N distinct presses, in order;
 *  - every press and every release spans at least [MIN_HOLD_MS] of wire time —
 *    one 60 Hz game frame, and it survives a lost packet;
 *  - a d-pad roll never delays a button. An earlier revision of this class ran
 *    one shared queue in strict order, which made a quarter-circle motion
 *    hold the punch behind it — 24 ms per direction the thumb passed through;
 *  - input slower than [MIN_HOLD_MS] per edge — all normal play — paces
 *    exactly as the old sampler did, with zero added latency.
 *
 * Sticks are deliberately not paced: for a continuous axis the latest value is
 * the right one, and queuing per-move updates would only add aim lag.
 *
 * Thread-safe: the UI thread calls [offer], the sender loop calls [tick].
 */
class EdgePacer {

    companion object {
        /**
         * How often the sender emits a packet. Lives here, next to the hold
         * arithmetic that depends on it, rather than as a bare literal in the
         * send loop: [MIN_HOLD_TICKS] is only 24 ms because a tick is 8 ms, so
         * a send-rate change made in isolation would silently retune the
         * guarantee — and every test here, counting ticks, would still pass.
         */
        const val SEND_PERIOD_MS = 8L

        /**
         * Shortest press or release the wire may carry: one 60 Hz game frame
         * (16.7 ms) plus room for one lost datagram.
         */
        const val MIN_HOLD_MS = 24L

        /** [MIN_HOLD_MS] rounded up to whole ticks. */
        const val MIN_HOLD_TICKS: Int =
            ((MIN_HOLD_MS + SEND_PERIOD_MS - 1) / SEND_PERIOD_MS).toInt()

        /** Wire buttons field is a u16. */
        private const val BUTTON_COUNT = 16

        /**
         * Backlog caps. Each control drains one edge per [MIN_HOLD_TICKS],
         * i.e. ~41 edges a second, so no human can overrun a single button:
         * the fastest mashing is ~10 taps (20 edges) a second. These bound the
         * damage if something else does — 12 edges is 6 buffered taps, ~288 ms
         * of button backlog, and 8 queued directions is ~192 ms of d-pad.
         */
        private const val MAX_PENDING_EDGES = 12
        private const val MAX_PENDING_DIRS = 8

        /** Age of a change that has not been transmitted yet; see [releaseAll]. */
        private const val FRESH = -1
    }

    private val lock = Any()

    // ---- buttons ----
    // A single bit's edges strictly alternate — press, release, press — so a
    // COUNT of pending transitions is a complete queue, with no allocation and
    // no way for two buttons to queue behind each other.
    private val pendingEdges = IntArray(BUTTON_COUNT)
    private val buttonAge = IntArray(BUTTON_COUNT) { MIN_HOLD_TICKS }
    private var wireButtons = 0
    private var queuedButtons = 0

    // ---- d-pad ----
    // Nine states rather than two, so this one needs a real queue.
    private val pendingDirs = ArrayDeque<Dpad>()
    private var dpadAge = MIN_HOLD_TICKS
    private var wireDpad = Dpad.NEUTRAL
    private var queuedDpad = Dpad.NEUTRAL

    /** Producer (UI thread): the pad state changed. */
    fun offer(buttons: Int, dpad: Dpad) {
        synchronized(lock) {
            val changed = buttons xor queuedButtons
            if (changed != 0) {
                for (i in 0 until BUTTON_COUNT) {
                    if (changed and (1 shl i) == 0) continue
                    pendingEdges[i]++
                    // Overflow drops a press and its release TOGETHER. Dropping
                    // a single edge would flip this bit's parity, and parity is
                    // the whole representation: the queued state would become
                    // unreachable and the button could stick down for good.
                    if (pendingEdges[i] > MAX_PENDING_EDGES) pendingEdges[i] -= 2
                }
                queuedButtons = buttons
            }
            if (dpad != queuedDpad) {
                pendingDirs.addLast(dpad)
                // The newest direction is the one the thumb is actually on, and
                // the tail is what the wire converges to, so the oldest
                // transient of an overlong roll is the one to lose.
                if (pendingDirs.size > MAX_PENDING_DIRS) pendingDirs.removeFirst()
                queuedDpad = dpad
            }
        }
    }

    /**
     * Everything released, on the wire, now — mode switch or leaving the pad.
     *
     * Ages reset to freshly-changed, not to the cap: this neutral is itself an
     * edge, and it gets the same minimum hold as any other, so a press
     * arriving right behind it cannot cut it to a single packet.
     *
     * [FRESH] rather than 0 because an edge applied inside [tick] goes out on
     * that same packet at age 0, while this one is only transmitted on the
     * NEXT tick — after that tick's increment. Starting one lower lines the
     * two paths up.
     */
    fun releaseAll() {
        synchronized(lock) {
            pendingEdges.fill(0)
            pendingDirs.clear()
            wireButtons = 0
            queuedButtons = 0
            wireDpad = Dpad.NEUTRAL
            queuedDpad = Dpad.NEUTRAL
            buttonAge.fill(FRESH)
            dpadAge = FRESH
        }
    }

    /** Consumer (sender loop), once per tick: what this packet carries. */
    fun tick(): Pair<Int, Dpad> {
        synchronized(lock) {
            for (i in 0 until BUTTON_COUNT) {
                if (buttonAge[i] < MIN_HOLD_TICKS) buttonAge[i]++
            }
            if (dpadAge < MIN_HOLD_TICKS) dpadAge++

            for (i in 0 until BUTTON_COUNT) {
                if (pendingEdges[i] > 0 && buttonAge[i] >= MIN_HOLD_TICKS) {
                    wireButtons = wireButtons xor (1 shl i)
                    pendingEdges[i]--
                    buttonAge[i] = 0
                }
            }
            if (pendingDirs.isNotEmpty() && dpadAge >= MIN_HOLD_TICKS) {
                wireDpad = pendingDirs.removeFirst()
                dpadAge = 0
            }
            return wireButtons to wireDpad
        }
    }
}

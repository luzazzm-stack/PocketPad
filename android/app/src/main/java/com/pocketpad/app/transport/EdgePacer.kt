package com.pocketpad.app.transport

import com.pocketpad.app.protocol.Dpad

/**
 * Feeds button/d-pad changes onto the 125 Hz wire so that no press is ever
 * lost, in order, and every press is long enough to be seen.
 *
 * The sender used to sample the latest state each tick, with a press *bitmask*
 * latched so a tap shorter than the 8 ms gap still went out. Two ways that
 * still lost input:
 *  - the latch is a bitmask, so three quick taps of the same button between
 *    two ticks OR into one bit and come out as ONE press;
 *  - a latched press rides exactly one packet — 8 ms of virtual-pad time,
 *    shorter than a 60 Hz game's input frame, so the game can poll right past
 *    it; one lost UDP datagram erases it completely.
 * Both were easy to hit whenever the UI thread stalled and delivered a burst
 * of touch events back to back.
 *
 * This replaces the latches with an ordered queue of (buttons, dpad) states.
 * Each tick the pacer advances through the queue, but a control that just
 * changed on the wire holds its new value for at least [MIN_HOLD_TICKS] before
 * it may change again. Consequences:
 *  - N taps always come out as N distinct presses, in order;
 *  - every press and every release spans >= 24 ms of wire time — at least one
 *    60 Hz game frame, and it survives a lost packet;
 *  - a change that touches only *other* controls applies immediately, so a
 *    chord forming across two events (A, then A+B) is not delayed;
 *  - input slower than 24 ms per edge — all normal play — paces exactly as the
 *    old sampler did, with zero added latency.
 *
 * Sticks are deliberately not queued: for a continuous axis the latest value
 * is the right one, and queuing per-move updates would bury button edges.
 */
class EdgePacer(private val minHoldTicks: Int = MIN_HOLD_TICKS) {

    companion object {
        /** 3 ticks x 8 ms = 24 ms: one 60 Hz game frame, plus one lost packet. */
        const val MIN_HOLD_TICKS = 3

        /**
         * ~0.5 s of backlog at one entry per tick. More means input arrived
         * faster than any finger can — drop the oldest, keep the newest, and
         * the queue still converges on the latest state because every entry
         * is an absolute state, not a delta.
         */
        private const val MAX_QUEUE = 64
    }

    private class Edge(val buttons: Int, val dpad: Dpad)

    private val queue = ArrayDeque<Edge>()
    private var lastQueuedButtons = 0
    private var lastQueuedDpad = Dpad.NEUTRAL

    // What the wire currently shows, and how many ticks each control has
    // shown it (capped at minHoldTicks; only "old enough" matters).
    private var wireButtons = 0
    private var wireDpad = Dpad.NEUTRAL
    private val buttonAge = IntArray(16) { minHoldTicks }
    private var dpadAge = minHoldTicks

    /** Producer (UI thread): the pad state changed. Never blocks meaningfully. */
    fun offer(buttons: Int, dpad: Dpad) {
        synchronized(queue) {
            if (buttons == lastQueuedButtons && dpad == lastQueuedDpad) return
            if (queue.size >= MAX_QUEUE) queue.removeFirst()
            queue.addLast(Edge(buttons, dpad))
            lastQueuedButtons = buttons
            lastQueuedDpad = dpad
        }
    }

    /** Everything released, immediately — mode switch or teardown. */
    fun reset() {
        synchronized(queue) {
            queue.clear()
            lastQueuedButtons = 0
            lastQueuedDpad = Dpad.NEUTRAL
            wireButtons = 0
            wireDpad = Dpad.NEUTRAL
            buttonAge.fill(minHoldTicks)
            dpadAge = minHoldTicks
        }
    }

    /** Consumer (sender loop), once per 8 ms tick: what this packet carries. */
    fun tick(): Pair<Int, Dpad> {
        synchronized(queue) {
            for (i in buttonAge.indices) if (buttonAge[i] < minHoldTicks) buttonAge[i]++
            if (dpadAge < minHoldTicks) dpadAge++

            // Drain every queued state whose changes are all old enough. Two
            // entries landing in the same gap collapse (A, then A+B, goes out
            // as A+B in one packet — the game sees the chord simultaneously),
            // but an entry that would cut short a fresh edge waits its turn,
            // and everything behind it waits too: order is the contract.
            while (true) {
                val next = queue.firstOrNull() ?: break
                if (!mayApply(next)) break
                queue.removeFirst()
                val changed = wireButtons xor next.buttons
                for (i in buttonAge.indices) {
                    if (changed and (1 shl i) != 0) buttonAge[i] = 0
                }
                if (next.dpad != wireDpad) dpadAge = 0
                wireButtons = next.buttons
                wireDpad = next.dpad
            }
            return wireButtons to wireDpad
        }
    }

    private fun mayApply(next: Edge): Boolean {
        val changed = wireButtons xor next.buttons
        for (i in buttonAge.indices) {
            if (changed and (1 shl i) != 0 && buttonAge[i] < minHoldTicks) return false
        }
        return next.dpad == wireDpad || dpadAge >= minHoldTicks
    }
}

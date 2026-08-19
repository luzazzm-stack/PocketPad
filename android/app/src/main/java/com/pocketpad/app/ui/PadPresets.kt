package com.pocketpad.app.ui

import com.pocketpad.app.settings.ElementLayout
import com.pocketpad.app.settings.PadElement
import com.pocketpad.app.settings.PadLayout

/**
 * A control at its default size, shown or hidden.
 *
 * Never build these with a bare `ElementLayout(visible = ...)`: that carries
 * ElementLayout's own `scale = 1f` and quietly overrides DEFAULT_SCALE, so a
 * stick hidden by a preset came back at 148 dp instead of 130 dp when the
 * player switched it on again — straight into the face cluster, where
 * overButton() then refuses it a drag.
 */
private fun shown(e: PadElement) = PadLayout.defaultFor(e).copy(visible = true)
private fun hidden(e: PadElement) = PadLayout.defaultFor(e).copy(visible = false)

/**
 * Ready-made pads, one per kind of game.
 *
 * Which controls a title actually uses varies more than any single arrangement
 * can serve: a fighting game wants a cross and big buttons and no sticks at
 * all, a shooter is unplayable without two. Rather than make every player
 * discover that and drag twelve controls themselves, each preset is a complete
 * layout — positions, sizes and visibility — that they can then adjust.
 *
 * A preset REPLACES the whole layout, so anything it does not name falls back
 * to [PadLayout.defaultFor]: every control visible at its default size except
 * the two stick clicks.
 */
enum class PadPreset(val title: String, val blurb: String) {
    /** Fighting games and emulators: the cross, the four buttons, nothing else. */
    CLASSIC("Layout 1", "D-pad · no sticks"),

    /** The all-rounder — a real controller's set. Shooters and 3D games. */
    BOTH_STICKS("Layout 2", "D-pad + both sticks"),

    /** Platformers and side-on games: analog movement, no camera to steer. */
    LEFT_STICK("Layout 3", "Left stick · big buttons"),

    /** Everything the app has, stick clicks included. */
    EVERYTHING("Layout 4", "Every control"),
}

fun presetLayout(p: PadPreset): PadLayout = when (p) {

    // No sticks, so the cross and the buttons take the room they free up and
    // sit lower, where thumbs rest.
    PadPreset.CLASSIC -> PadLayout(
        mapOf(
            PadElement.DPAD to ElementLayout(y = 45f, scale = 0.90f),
            PadElement.FACE to ElementLayout(y = 40f, scale = 1.00f),
            PadElement.LSTICK to hidden(PadElement.LSTICK),
            PadElement.RSTICK to hidden(PadElement.RSTICK),
            PadElement.L3 to hidden(PadElement.L3),
            PadElement.R3 to hidden(PadElement.R3),
        )
    )

    // Exactly the defaults: cross top-left, move stick below it, buttons
    // top-right, look stick below them.
    PadPreset.BOTH_STICKS -> PadLayout()

    // Analog movement without a camera stick frees the whole right side, so
    // the face buttons grow and centre there.
    PadPreset.LEFT_STICK -> PadLayout(
        mapOf(
            PadElement.FACE to ElementLayout(y = 35f, scale = 1.10f),
            PadElement.RSTICK to hidden(PadElement.RSTICK),
            PadElement.L3 to hidden(PadElement.L3),
            PadElement.R3 to hidden(PadElement.R3),
        )
    )

    // Defaults plus the two stick clicks, which sit just above BACK/START.
    PadPreset.EVERYTHING -> PadLayout(
        mapOf(
            PadElement.L3 to shown(PadElement.L3),
            PadElement.R3 to shown(PadElement.R3),
        )
    )
}

package com.pocketpad.app.ui

import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pocketpad.app.settings.ElementLayout
import com.pocketpad.app.settings.PadElement

/**
 * Where a control sits before the player moves it, and how far it may travel.
 *
 * Shared by [GamepadScreen] and [LayoutEditScreen] so the editor always shows a
 * control exactly where play will put it — the two cannot drift apart.
 */
data class ElementSpec(
    val label: String,
    val align: Alignment,
    val padStart: Dp = 0.dp,
    val padEnd: Dp = 0.dp,
    val padTop: Dp = 0.dp,
    val padBottom: Dp = 0.dp,
    /** Offset baked in before the player's own. */
    val baseX: Dp = 0.dp,
    val baseY: Dp = 0.dp,
    /** Travel limits in dp, keeping a control from being dragged off screen. */
    val xRange: ClosedFloatingPointRange<Float>,
    val yRange: ClosedFloatingPointRange<Float>,
)

/**
 * Default placement of every control, and the editor's tab order.
 *
 * The arrangement follows what console-style touch pads settle on: sticks low
 * where the thumbs rest, d-pad and face buttons above them, shoulders along the
 * top edge. Both sticks are present because 3D games need one to move and one
 * to look; the d-pad sits alongside the left stick rather than replacing it,
 * since a hotbar has to be reachable while walking.
 */
val PAD_SPECS: Map<PadElement, ElementSpec> = linkedMapOf(
    PadElement.LSTICK to ElementSpec(
        "Left stick (move)", Alignment.CenterStart, padStart = 26.dp, baseY = 58.dp,
        xRange = -20f..470f, yRange = -150f..90f,
    ),
    PadElement.RSTICK to ElementSpec(
        "Right stick (look)", Alignment.CenterEnd, padEnd = 26.dp, baseY = 58.dp,
        xRange = -470f..20f, yRange = -150f..90f,
    ),
    PadElement.DPAD to ElementSpec(
        "D-pad", Alignment.CenterStart, padStart = 30.dp, baseY = (-72).dp,
        xRange = -24f..470f, yRange = -80f..170f,
    ),
    PadElement.FACE to ElementSpec(
        "Buttons", Alignment.CenterEnd, padEnd = 26.dp, baseY = (-64).dp,
        xRange = -470f..24f, yRange = -70f..170f,
    ),
    PadElement.LB to ElementSpec(
        "LB", Alignment.TopStart, padStart = 24.dp, padTop = 10.dp,
        xRange = -16f..560f, yRange = -6f..320f,
    ),
    PadElement.LT to ElementSpec(
        "LT", Alignment.TopStart, padStart = 118.dp, padTop = 10.dp,
        xRange = -110f..560f, yRange = -6f..320f,
    ),
    PadElement.RB to ElementSpec(
        "RB", Alignment.TopEnd, padEnd = 24.dp, padTop = 10.dp,
        xRange = -560f..16f, yRange = -6f..320f,
    ),
    PadElement.RT to ElementSpec(
        "RT", Alignment.TopEnd, padEnd = 118.dp, padTop = 10.dp,
        xRange = -560f..110f, yRange = -6f..320f,
    ),
    // Stick clicks. Sprint and melee in most shooters, so they sit near the
    // thumbs rather than with the menu buttons.
    PadElement.L3 to ElementSpec(
        "L3 (sprint)", Alignment.BottomStart, padStart = 26.dp, padBottom = 14.dp,
        xRange = -20f..520f, yRange = -300f..10f,
    ),
    PadElement.R3 to ElementSpec(
        "R3 (melee)", Alignment.BottomEnd, padEnd = 26.dp, padBottom = 14.dp,
        xRange = -520f..20f, yRange = -300f..10f,
    ),
    PadElement.BACK to ElementSpec(
        "BACK", Alignment.BottomCenter, padBottom = 14.dp, baseX = (-56).dp,
        xRange = -320f..320f, yRange = -300f..10f,
    ),
    PadElement.START to ElementSpec(
        "START", Alignment.BottomCenter, padBottom = 14.dp, baseX = 56.dp,
        xRange = -320f..320f, yRange = -300f..10f,
    ),
)

fun specOf(e: PadElement): ElementSpec = PAD_SPECS.getValue(e)

/** Apply a control's default padding plus the player's saved offset. */
fun Modifier.placeElement(spec: ElementSpec, l: ElementLayout): Modifier = this
    .padding(start = spec.padStart, end = spec.padEnd, top = spec.padTop, bottom = spec.padBottom)
    .offset(spec.baseX + l.x.dp, spec.baseY + l.y.dp)

/** Move a control by a drag delta in dp, clamped to its travel limits. */
fun ElementLayout.dragged(spec: ElementSpec, dx: Float, dy: Float): ElementLayout = copy(
    x = (x + dx).coerceIn(spec.xRange),
    y = (y + dy).coerceIn(spec.yRange),
)

/** Base size of each control before its scale is applied. */
val STICK_BASE = 148.dp
val DPAD_BASE = 186.dp
val FACE_BUTTON_BASE = 62.dp

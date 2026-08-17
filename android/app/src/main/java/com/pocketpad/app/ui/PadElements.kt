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
    /** Offset baked in before the player's own — BACK/START sit either side of centre. */
    val baseX: Dp = 0.dp,
    /** Travel limits in dp, keeping a control from being dragged off screen. */
    val xRange: ClosedFloatingPointRange<Float>,
    val yRange: ClosedFloatingPointRange<Float>,
)

/** Default placement of every control. Order here is the editor's tab order. */
val PAD_SPECS: Map<PadElement, ElementSpec> = linkedMapOf(
    PadElement.DPAD to ElementSpec(
        "Left stick", Alignment.CenterStart, padStart = 30.dp,
        xRange = -24f..430f, yRange = -110f..110f,
    ),
    PadElement.FACE to ElementSpec(
        "Buttons", Alignment.CenterEnd, padEnd = 30.dp,
        xRange = -430f..24f, yRange = -110f..110f,
    ),
    PadElement.LB to ElementSpec(
        "LB", Alignment.TopStart, padStart = 24.dp, padTop = 12.dp,
        xRange = -16f..520f, yRange = -8f..300f,
    ),
    PadElement.LT to ElementSpec(
        "LT", Alignment.TopStart, padStart = 120.dp, padTop = 12.dp,
        xRange = -112f..520f, yRange = -8f..300f,
    ),
    PadElement.RB to ElementSpec(
        "RB", Alignment.TopEnd, padEnd = 24.dp, padTop = 12.dp,
        xRange = -520f..16f, yRange = -8f..300f,
    ),
    PadElement.RT to ElementSpec(
        "RT", Alignment.TopEnd, padEnd = 120.dp, padTop = 12.dp,
        xRange = -520f..112f, yRange = -8f..300f,
    ),
    // BACK and START sit wide enough apart to leave room for the d-pad/stick
    // toggle between them.
    PadElement.BACK to ElementSpec(
        "BACK", Alignment.BottomCenter, padBottom = 16.dp, baseX = (-100).dp,
        xRange = -300f..300f, yRange = -280f..12f,
    ),
    PadElement.START to ElementSpec(
        "START", Alignment.BottomCenter, padBottom = 16.dp, baseX = 100.dp,
        xRange = -300f..300f, yRange = -280f..12f,
    ),
    // Centred between BACK and START; padBottom keeps its shorter body's
    // midline level with theirs.
    PadElement.TOGGLE to ElementSpec(
        "D-pad / stick", Alignment.BottomCenter, padBottom = 22.dp,
        xRange = -300f..300f, yRange = -280f..12f,
    ),
)

fun specOf(e: PadElement): ElementSpec = PAD_SPECS.getValue(e)

/** Apply a control's default padding plus the player's saved offset. */
fun Modifier.placeElement(spec: ElementSpec, l: ElementLayout): Modifier = this
    .padding(start = spec.padStart, end = spec.padEnd, top = spec.padTop, bottom = spec.padBottom)
    .offset(spec.baseX + l.x.dp, l.y.dp)

/** Move a control by a drag delta in dp, clamped to its travel limits. */
fun ElementLayout.dragged(spec: ElementSpec, dx: Float, dy: Float): ElementLayout = copy(
    x = (x + dx).coerceIn(spec.xRange),
    y = (y + dy).coerceIn(spec.yRange),
)

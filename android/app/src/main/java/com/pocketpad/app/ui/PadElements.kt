package com.pocketpad.app.ui

import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.absolutePadding
import androidx.compose.ui.AbsoluteAlignment
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
 *
 * Placement is deliberately ABSOLUTE (left/right, [AbsoluteAlignment],
 * [absoluteOffset]) rather than start/end. The manifest sets
 * `supportsRtl="true"`, and with direction-aware primitives an Arabic or Hebrew
 * locale mirrors the whole pad — the left stick lands under the right thumb
 * while its label and the PC-side mapping still say "L". A physical controller
 * layout must not follow text direction.
 */
data class ElementSpec(
    val label: String,
    val align: Alignment,
    val padLeft: Dp = 0.dp,
    val padRight: Dp = 0.dp,
    val padTop: Dp = 0.dp,
    val padBottom: Dp = 0.dp,
    /** Offset baked in before the player's own. */
    val baseX: Dp = 0.dp,
    /** Travel limits in dp, keeping a control from being dragged off screen. */
    val xRange: ClosedFloatingPointRange<Float>,
    val yRange: ClosedFloatingPointRange<Float>,
)

/**
 * Default placement of every control, and the editor's tab order.
 *
 * The vertical budget is set by the SHORTEST landscape phone this supports:
 * a 1080p device at density 3.0 is 360 dp tall, and minSdk 23 reaches 320 dp
 * hdpi hardware. Every column below is sized to fit 360 dp with the top button
 * row and a gap, so nothing overlaps on a small screen:
 *
 *   right: 54 top pad + 140 face + 12 gap + 130 stick + 24 bottom pad = 360
 *   left:  60 top pad + 108 cross + 38 gap + 130 stick + 24 bottom pad = 360
 *
 * Travel limits are similarly bounded so a control can never be dragged
 * entirely off a ~640 dp-wide landscape screen, which would leave no touch
 * target to drag it back.
 */
val PAD_SPECS: Map<PadElement, ElementSpec> = linkedMapOf(
    // Left column: cross up top under LB, stick low where the thumb rests.
    PadElement.LSTICK to ElementSpec(
        "Left stick (move)", AbsoluteAlignment.BottomLeft, padLeft = 40.dp, padBottom = 24.dp,
        xRange = -32f..380f, yRange = -170f..18f,
    ),
    PadElement.DPAD to ElementSpec(
        "D-pad", AbsoluteAlignment.TopLeft, padLeft = 34.dp, padTop = 60.dp,
        xRange = -26f..380f, yRange = -52f..190f,
    ),
    // Right column mirrors it.
    PadElement.RSTICK to ElementSpec(
        "Right stick (look)", AbsoluteAlignment.BottomRight, padRight = 40.dp, padBottom = 24.dp,
        xRange = -380f..32f, yRange = -170f..18f,
    ),
    PadElement.FACE to ElementSpec(
        "Buttons", AbsoluteAlignment.TopRight, padRight = 28.dp, padTop = 54.dp,
        xRange = -380f..26f, yRange = -46f..190f,
    ),
    // Shoulders, triggers and stick clicks along the top edge. L3/R3 live here
    // rather than at the bottom because the editor's bar covers the bottom
    // strip, and they ship hidden — a control you cannot see or tap is a
    // control you can never switch back on.
    PadElement.LB to ElementSpec(
        "LB", AbsoluteAlignment.TopLeft, padLeft = 24.dp, padTop = 10.dp,
        xRange = -16f..400f, yRange = -6f..250f,
    ),
    PadElement.LT to ElementSpec(
        "LT", AbsoluteAlignment.TopLeft, padLeft = 120.dp, padTop = 10.dp,
        xRange = -112f..400f, yRange = -6f..250f,
    ),
    PadElement.L3 to ElementSpec(
        "L3 (stick click)", AbsoluteAlignment.TopLeft, padLeft = 208.dp, padTop = 12.dp,
        xRange = -200f..400f, yRange = -8f..250f,
    ),
    PadElement.RB to ElementSpec(
        "RB", AbsoluteAlignment.TopRight, padRight = 24.dp, padTop = 10.dp,
        xRange = -400f..16f, yRange = -6f..250f,
    ),
    PadElement.RT to ElementSpec(
        "RT", AbsoluteAlignment.TopRight, padRight = 120.dp, padTop = 10.dp,
        xRange = -400f..112f, yRange = -6f..250f,
    ),
    PadElement.R3 to ElementSpec(
        "R3 (stick click)", AbsoluteAlignment.TopRight, padRight = 208.dp, padTop = 12.dp,
        xRange = -400f..200f, yRange = -8f..250f,
    ),
    // Menu pair on the bottom centre, between the two sticks.
    PadElement.BACK to ElementSpec(
        "BACK", Alignment.BottomCenter, padBottom = 14.dp, baseX = (-56).dp,
        xRange = -240f..240f, yRange = -250f..10f,
    ),
    PadElement.START to ElementSpec(
        "START", Alignment.BottomCenter, padBottom = 14.dp, baseX = 56.dp,
        xRange = -240f..240f, yRange = -250f..10f,
    ),
)

fun specOf(e: PadElement): ElementSpec = PAD_SPECS.getValue(e)

/**
 * Clamp a stored offset back inside the control's current travel limits.
 *
 * [dragged] only enforces the range while a finger is moving, so a value saved
 * under wider limits — an older build, a narrowed range, a hand-edited prefs
 * blob — would otherwise load verbatim and could place the control off screen,
 * where there is no touch target left to drag it back.
 */
fun ElementLayout.clampedTo(spec: ElementSpec): ElementLayout {
    val cx = x.coerceIn(spec.xRange)
    val cy = y.coerceIn(spec.yRange)
    return if (cx == x && cy == y) this else copy(x = cx, y = cy)
}

/** Apply a control's default padding plus the player's saved offset. */
fun Modifier.placeElement(spec: ElementSpec, l: ElementLayout): Modifier = this
    .absolutePadding(
        left = spec.padLeft,
        right = spec.padRight,
        top = spec.padTop,
        bottom = spec.padBottom,
    )
    .absoluteOffset(spec.baseX + l.x.dp, l.y.dp)

/** Move a control by a drag delta in dp, clamped to its travel limits. */
fun ElementLayout.dragged(spec: ElementSpec, dx: Float, dy: Float): ElementLayout = copy(
    x = (x + dx).coerceIn(spec.xRange),
    y = (y + dy).coerceIn(spec.yRange),
)

/** Base size of each control before its scale is applied. */
val STICK_BASE = 148.dp
val DPAD_BASE = 186.dp
val FACE_BUTTON_BASE = 62.dp

/** Size and shape of the rectangular controls, shared by play and the editor. */
data class ButtonMetrics(val width: Dp, val height: Dp, val pill: Boolean)

private val BUTTON_METRICS = mapOf(
    PadElement.LB to ButtonMetrics(88.dp, 40.dp, pill = false),
    PadElement.RB to ButtonMetrics(88.dp, 40.dp, pill = false),
    PadElement.LT to ButtonMetrics(78.dp, 40.dp, pill = false),
    PadElement.RT to ButtonMetrics(78.dp, 40.dp, pill = false),
    PadElement.L3 to ButtonMetrics(72.dp, 34.dp, pill = true),
    PadElement.R3 to ButtonMetrics(72.dp, 34.dp, pill = true),
    PadElement.BACK to ButtonMetrics(86.dp, 34.dp, pill = true),
    PadElement.START to ButtonMetrics(86.dp, 34.dp, pill = true),
)

/** Null for the sticks, cross and face cluster, which are not plain buttons. */
fun buttonMetricsOf(e: PadElement): ButtonMetrics? = BUTTON_METRICS[e]

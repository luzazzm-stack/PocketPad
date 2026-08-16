package com.pocketpad.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketpad.app.haptics.LocalHaptics
import com.pocketpad.app.protocol.Buttons
import com.pocketpad.app.protocol.Dpad
import com.pocketpad.app.protocol.PadState
import com.pocketpad.app.settings.AppSettings
import com.pocketpad.app.transport.PadConnection
import kotlin.math.atan2
import kotlin.math.hypot

/** Every control the unified input core can press. */
enum class PadControl { LB, RB, Y, B, A, X, BACK, START }

private fun maskOf(set: Set<PadControl>): Int {
    var m = 0
    for (c in set) m = m or when (c) {
        PadControl.LB -> Buttons.LB
        PadControl.RB -> Buttons.RB
        PadControl.Y -> Buttons.Y
        PadControl.B -> Buttons.B
        PadControl.A -> Buttons.A
        PadControl.X -> Buttons.X
        PadControl.BACK -> Buttons.BACK
        PadControl.START -> Buttons.START
    }
    return m
}

/**
 * Direction from a position inside the d-pad square. Cardinals get 60°
 * windows and diagonals 30°: thumbs aim straight far more often than
 * diagonally, so "up" must not slip into a side direction.
 */
private fun dpadDirAt(local: Offset, sizePx: Float): Dpad {
    val c = sizePx / 2f
    val dx = local.x - c
    val dy = local.y - c
    if (hypot(dx, dy) < sizePx * 0.14f) return Dpad.NEUTRAL
    var deg = Math.toDegrees(atan2(dx, -dy).toDouble())
    if (deg < 0) deg += 360.0
    return when {
        deg >= 330 || deg < 30 -> Dpad.UP
        deg < 60 -> Dpad.UP_RIGHT
        deg < 120 -> Dpad.RIGHT
        deg < 150 -> Dpad.DOWN_RIGHT
        deg < 210 -> Dpad.DOWN
        deg < 240 -> Dpad.DOWN_LEFT
        deg < 300 -> Dpad.LEFT
        else -> Dpad.UP_LEFT
    }
}

/**
 * The gamepad.
 *
 * ONE input core handles every finger for the whole screen instead of
 * per-button gesture handlers. Each frame, every active pointer is hit-tested
 * against every control with a finger-sized tolerance, which makes three
 * things work that per-button handlers can't do:
 *  - any number of simultaneous fingers, reliably;
 *  - a single finger landing between two buttons presses BOTH (Tekken's
 *    two-button moves with one thumb);
 *  - a finger that starts on the d-pad stays captured by it even when it
 *    rolls past the edge mid-dash.
 */
@Composable
fun GamepadScreen(
    connection: PadConnection,
    latencyMs: Long?,
    settings: AppSettings,
    onSwitchToMouse: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val hf = LocalHaptics.current
    val lay = settings.layout

    // Control zones in root-window coordinates, reported by the visuals.
    val zones = remember { mutableStateMapOf<PadControl, Rect>() }
    var dpadZone by remember { mutableStateOf(Rect.Zero) }
    var boxOrigin by remember { mutableStateOf(Offset.Zero) }

    // What the input core decided this frame (visuals render from this).
    var pressed by remember { mutableStateOf(emptySet<PadControl>()) }
    var dpad by remember { mutableStateOf(Dpad.NEUTRAL) }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .onGloballyPositioned { boxOrigin = it.positionInRoot() }
            .pointerInput(settings.haptics) {
                val slopPx = 14.dp.toPx()      // finger-sized forgiveness on buttons
                val dpadSlopPx = 22.dp.toPx()  // extra forgiving for entering the cross
                var dpadPointer: PointerId? = null

                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val down = event.changes.filter { it.pressed }

                        // ---- d-pad: capture semantics ----
                        // The pointer that lands on the cross OWNS it until
                        // lift-off, wherever it rolls. Others never steal it.
                        if (dpadPointer == null || down.none { it.id == dpadPointer }) {
                            dpadPointer = down.firstOrNull {
                                dpadZone.inflate(dpadSlopPx).contains(boxOrigin + it.position)
                            }?.id
                        }
                        val dpadTouch = down.firstOrNull { it.id == dpadPointer }
                        val newDpad = dpadTouch?.let {
                            dpadDirAt(boxOrigin + it.position - dpadZone.topLeft, dpadZone.width)
                        } ?: Dpad.NEUTRAL

                        // ---- buttons: every finger vs every zone ----
                        // A touch within (radius + slop) of a circle presses it,
                        // so one finger between two buttons presses both.
                        val newPressed = buildSet {
                            for ((ctl, rect) in zones) {
                                val isRound = ctl in FACE_CONTROLS
                                val hit = down.any { ch ->
                                    if (ch.id == dpadPointer) return@any false
                                    val p = boxOrigin + ch.position
                                    if (isRound) {
                                        (p - rect.center).getDistance() <= rect.width / 2f + slopPx
                                    } else {
                                        rect.inflate(slopPx).contains(p)
                                    }
                                }
                                if (hit) add(ctl)
                            }
                        }

                        if (newPressed != pressed || newDpad != dpad) {
                            if (settings.haptics &&
                                ((newPressed - pressed).isNotEmpty() ||
                                    (newDpad != dpad && newDpad != Dpad.NEUTRAL))
                            ) hf.tick()
                            pressed = newPressed
                            dpad = newDpad
                            connection.state.set(
                                PadState(buttons = maskOf(newPressed), dpad = newDpad)
                            )
                        }
                    }
                }
            }
    ) {
        // ---- top bar: mouse | latency | settings ----
        Row(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ModeChip(label = "Mouse", icon = ModeIcon.Mouse, onClick = onSwitchToMouse)
            Text(
                text = latencyMs?.let { "$it ms" } ?: "— ms",
                color = if ((latencyMs ?: 99) < 20) Color(0xFF9ECE6A) else Color(0xFFE0AF68),
                style = MaterialTheme.typography.labelMedium,
            )
            GearChip(onClick = onOpenSettings)
        }

        // ---- shoulders ----
        DepthButton("LB", pressed = PadControl.LB in pressed,
            Modifier
                .align(Alignment.TopStart)
                .padding(start = 24.dp, top = 12.dp)
                .size(88.dp, 40.dp)
                .onGloballyPositioned { zones[PadControl.LB] = it.boundsInRoot() },
            RoundedCornerShape(11.dp))
        DepthButton("RB", pressed = PadControl.RB in pressed,
            Modifier
                .align(Alignment.TopEnd)
                .padding(end = 24.dp, top = 12.dp)
                .size(88.dp, 40.dp)
                .onGloballyPositioned { zones[PadControl.RB] = it.boundsInRoot() },
            RoundedCornerShape(11.dp))

        // ---- d-pad cross ----
        DpadCross(
            size = 186.dp * lay.dpadScale,
            current = dpad,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 30.dp)
                .offset(lay.dpadX.dp, lay.dpadY.dp)
                .onGloballyPositioned { dpadZone = it.boundsInRoot() },
        )

        // ---- face cluster ----
        FaceCluster(
            buttonSize = 62.dp * lay.faceScale,
            pressed = pressed,
            onZone = { ctl, rect -> zones[ctl] = rect },
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 30.dp)
                .offset(lay.faceX.dp, lay.faceY.dp),
        )

        // ---- back / start ----
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            DepthButton("BACK", pressed = PadControl.BACK in pressed,
                Modifier
                    .size(86.dp, 36.dp)
                    .onGloballyPositioned { zones[PadControl.BACK] = it.boundsInRoot() },
                RoundedCornerShape(50))
            DepthButton("START", pressed = PadControl.START in pressed,
                Modifier
                    .size(86.dp, 36.dp)
                    .onGloballyPositioned { zones[PadControl.START] = it.boundsInRoot() },
                RoundedCornerShape(50))
        }
    }
}

private val FACE_CONTROLS = setOf(PadControl.A, PadControl.B, PadControl.X, PadControl.Y)

// ============================== d-pad ==============================

/** Visual-only d-pad cross; the screen's input core feeds [current]. */
@Composable
fun DpadCross(
    size: Dp,
    current: Dpad,
    modifier: Modifier = Modifier,
) {
    Box(modifier.size(size)) {
        Canvas(Modifier.fillMaxSize()) {
            val w = this.size.width
            val arm = w * 0.335f
            val r = arm * 0.26f
            val active = Color(0xFF3D4569)
            val idle1 = Color(0xFF2A2E3F)
            val idle2 = Color(0xFF222534)

            fun armBrush(hot: Boolean) =
                if (hot) Brush.verticalGradient(listOf(active, Color(0xFF31374F)))
                else Brush.verticalGradient(listOf(idle1, idle2))

            val vHot = current in listOf(Dpad.UP, Dpad.UP_LEFT, Dpad.UP_RIGHT,
                Dpad.DOWN, Dpad.DOWN_LEFT, Dpad.DOWN_RIGHT)
            val hHot = current in listOf(Dpad.LEFT, Dpad.UP_LEFT, Dpad.DOWN_LEFT,
                Dpad.RIGHT, Dpad.UP_RIGHT, Dpad.DOWN_RIGHT)

            drawRoundRect(
                brush = armBrush(vHot),
                topLeft = Offset((w - arm) / 2f, 0f),
                size = Size(arm, w),
                cornerRadius = CornerRadius(r, r),
            )
            drawRoundRect(
                brush = armBrush(hHot),
                topLeft = Offset(0f, (w - arm) / 2f),
                size = Size(w, arm),
                cornerRadius = CornerRadius(r, r),
            )
            drawCircle(Color(0xFF191B26), radius = w * 0.075f, center = Offset(w / 2f, w / 2f))

            val ac = Color(0xFF7C86AC)
            val a = w * 0.055f
            fun tri(cx: Float, cy: Float, rot: Int) {
                val p = androidx.compose.ui.graphics.Path()
                when (rot) {
                    0 -> { p.moveTo(cx, cy - a); p.lineTo(cx - a, cy + a); p.lineTo(cx + a, cy + a) }
                    1 -> { p.moveTo(cx, cy + a); p.lineTo(cx - a, cy - a); p.lineTo(cx + a, cy - a) }
                    2 -> { p.moveTo(cx - a, cy); p.lineTo(cx + a, cy - a); p.lineTo(cx + a, cy + a) }
                    3 -> { p.moveTo(cx + a, cy); p.lineTo(cx - a, cy - a); p.lineTo(cx - a, cy + a) }
                }
                p.close()
                drawPath(p, ac)
            }
            tri(w / 2f, w * 0.115f, 0)
            tri(w / 2f, w * 0.885f, 1)
            tri(w * 0.115f, w / 2f, 2)
            tri(w * 0.885f, w / 2f, 3)
        }
    }
}

// ============================== face buttons ==============================

/** Visual-only face diamond; reports each button's zone via [onZone]. */
@Composable
fun FaceCluster(
    buttonSize: Dp,
    pressed: Set<PadControl> = emptySet(),
    modifier: Modifier = Modifier,
    onZone: ((PadControl, Rect) -> Unit)? = null,
) {
    Box(modifier.size(buttonSize * 3)) {
        FaceButton("Y", buttonSize, Color(0xFFEBC183), Color(0xFFD9A053),
            PadControl.Y in pressed,
            Modifier.align(Alignment.TopCenter)
                .onGloballyPositioned { onZone?.invoke(PadControl.Y, it.boundsInRoot()) })
        FaceButton("A", buttonSize, Color(0xFFB1DD80), Color(0xFF8CBE58),
            PadControl.A in pressed,
            Modifier.align(Alignment.BottomCenter)
                .onGloballyPositioned { onZone?.invoke(PadControl.A, it.boundsInRoot()) })
        FaceButton("X", buttonSize, Color(0xFF95BCFF), Color(0xFF6E96EC),
            PadControl.X in pressed,
            Modifier.align(Alignment.CenterStart)
                .onGloballyPositioned { onZone?.invoke(PadControl.X, it.boundsInRoot()) })
        FaceButton("B", buttonSize, Color(0xFFFA8FA5), Color(0xFFEE607C),
            PadControl.B in pressed,
            Modifier.align(Alignment.CenterEnd)
                .onGloballyPositioned { onZone?.invoke(PadControl.B, it.boundsInRoot()) })
    }
}

@Composable
private fun FaceButton(
    label: String,
    size: Dp,
    top: Color,
    bottom: Color,
    pressed: Boolean,
    modifier: Modifier,
) {
    Box(
        modifier
            .size(size)
            .scale(if (pressed) 0.93f else 1f)
            .shadow(if (pressed) 2.dp else 7.dp, CircleShape, clip = false)
            .clip(CircleShape)
            .background(Brush.verticalGradient(listOf(top, bottom))),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color(0xFF14151D), fontWeight = FontWeight.Black, fontSize = 19.sp)
    }
}

// ============================== small controls ==============================

@Composable
private fun DepthButton(
    label: String,
    pressed: Boolean,
    modifier: Modifier,
    shape: Shape,
) {
    Box(
        modifier
            .scale(if (pressed) 0.95f else 1f)
            .shadow(if (pressed) 1.dp else 4.dp, shape, clip = false)
            .clip(shape)
            .background(if (pressed) Color(0xFF3D4569) else Color(0xFF262A3A)),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color(0xFF98A2C0), fontWeight = FontWeight.Bold, fontSize = 12.sp)
    }
}

@Composable
private fun GearChip(onClick: () -> Unit) {
    Box(
        Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(Color(0xFF232634))
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitPointerEvent().changes.firstOrNull { it.changedToDown() }
                        if (down != null) {
                            down.consume()
                            onClick()
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text("⚙", color = Color(0xFF98A2C0), fontSize = 15.sp)
    }
}

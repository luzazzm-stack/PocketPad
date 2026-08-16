package com.pocketpad.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import com.pocketpad.app.haptics.LocalHaptics
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketpad.app.protocol.Buttons
import com.pocketpad.app.protocol.Dpad
import com.pocketpad.app.protocol.PadState
import com.pocketpad.app.settings.AppSettings
import com.pocketpad.app.transport.PadConnection
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * The gamepad. Left thumb: one continuous d-pad cross (position, not four
 * buttons — diagonals come free). Right thumb: four bevelled face buttons.
 * Cluster positions/sizes come from [AppSettings.layout] (Settings → layout).
 */
@Composable
fun GamepadScreen(
    connection: PadConnection,
    latencyMs: Long?,
    settings: AppSettings,
    onSwitchToMouse: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    var buttons by remember { mutableStateOf(0) }
    var dpad by remember { mutableStateOf(Dpad.NEUTRAL) }

    fun push() = connection.state.set(PadState(buttons = buttons, dpad = dpad))
    fun setButton(bit: Int, pressed: Boolean) {
        buttons = if (pressed) buttons or bit else buttons and bit.inv()
        push()
    }

    val lay = settings.layout

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
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
        ShoulderButton("LB", Modifier.align(Alignment.TopStart).padding(start = 24.dp, top = 12.dp),
            settings.haptics) { setButton(Buttons.LB, it) }
        ShoulderButton("RB", Modifier.align(Alignment.TopEnd).padding(end = 24.dp, top = 12.dp),
            settings.haptics) { setButton(Buttons.RB, it) }

        // ---- d-pad cross ----
        DpadCross(
            size = 186.dp * lay.dpadScale,
            haptics = settings.haptics,
            current = dpad,
            onDirection = { dpad = it; push() },
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 30.dp)
                .offset(lay.dpadX.dp, lay.dpadY.dp),
        )

        // ---- face cluster ----
        FaceCluster(
            buttonSize = 62.dp * lay.faceScale,
            haptics = settings.haptics,
            onButton = ::setButton,
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
            PillButton("BACK", settings.haptics) { setButton(Buttons.BACK, it) }
            PillButton("START", settings.haptics) { setButton(Buttons.START, it) }
        }
    }
}

// ============================== d-pad ==============================

/**
 * One touch surface: press anywhere on the cross, direction follows the thumb.
 * 8 sectors of 45°; a centre deadzone returns to neutral without lifting.
 */
@Composable
fun DpadCross(
    size: Dp,
    haptics: Boolean,
    current: Dpad,
    onDirection: (Dpad) -> Unit,
    modifier: Modifier = Modifier,
    interactive: Boolean = true,
) {
    val hf = LocalHaptics.current

    Box(
        modifier
            .size(size)
            .let { m ->
                if (!interactive) m else m.pointerInput(Unit) {
                    awaitEachGesture {
                        val bounds = this.size
                        fun dirAt(p: Offset): Dpad {
                            val cx = bounds.width / 2f
                            val cy = bounds.height / 2f
                            val dx = p.x - cx
                            val dy = p.y - cy
                            if (hypot(dx, dy) < bounds.width * 0.14f) return Dpad.NEUTRAL
                            // atan2 with screen y-down; sector 0 = Up, clockwise
                            var deg = Math.toDegrees(atan2(dx, -dy).toDouble())
                            if (deg < 0) deg += 360.0
                            val sector = ((deg + 22.5) / 45.0).toInt() % 8
                            return Dpad.entries[sector + 1] // entries[1..8] = UP..UP_LEFT
                        }

                        var last = Dpad.NEUTRAL
                        fun update(d: Dpad) {
                            if (d != last) {
                                last = d
                                if (haptics && d != Dpad.NEUTRAL)
                                    hf.tick()
                                onDirection(d)
                            }
                        }

                        val down = awaitFirstDown()
                        update(dirAt(down.position))
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!ch.pressed) break
                            update(dirAt(ch.position))
                        }
                        update(Dpad.NEUTRAL)
                    }
                }
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = this.size.width
            val arm = w * 0.335f       // arm thickness
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

            // vertical arm
            drawRoundRect(
                brush = armBrush(vHot),
                topLeft = Offset((w - arm) / 2f, 0f),
                size = Size(arm, w),
                cornerRadius = CornerRadius(r, r),
            )
            // horizontal arm
            drawRoundRect(
                brush = armBrush(hHot),
                topLeft = Offset(0f, (w - arm) / 2f),
                size = Size(w, arm),
                cornerRadius = CornerRadius(r, r),
            )
            // centre pip
            drawCircle(Color(0xFF191B26), radius = w * 0.075f, center = Offset(w / 2f, w / 2f))

            // arrows
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
            tri(w / 2f, w * 0.115f, 0)  // up
            tri(w / 2f, w * 0.885f, 1)  // down
            tri(w * 0.115f, w / 2f, 2)  // left
            tri(w * 0.885f, w / 2f, 3)  // right
        }
    }
}

// ============================== face buttons ==============================

@Composable
fun FaceCluster(
    buttonSize: Dp,
    haptics: Boolean,
    onButton: (Int, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    interactive: Boolean = true,
) {
    Box(modifier.size(buttonSize * 3)) {
        FaceButton("Y", buttonSize, Color(0xFFEBC183), Color(0xFFD9A053),
            Modifier.align(Alignment.TopCenter), haptics, interactive) { onButton(Buttons.Y, it) }
        FaceButton("A", buttonSize, Color(0xFFB1DD80), Color(0xFF8CBE58),
            Modifier.align(Alignment.BottomCenter), haptics, interactive) { onButton(Buttons.A, it) }
        FaceButton("X", buttonSize, Color(0xFF95BCFF), Color(0xFF6E96EC),
            Modifier.align(Alignment.CenterStart), haptics, interactive) { onButton(Buttons.X, it) }
        FaceButton("B", buttonSize, Color(0xFFFA8FA5), Color(0xFFEE607C),
            Modifier.align(Alignment.CenterEnd), haptics, interactive) { onButton(Buttons.B, it) }
    }
}

@Composable
private fun FaceButton(
    label: String,
    size: Dp,
    top: Color,
    bottom: Color,
    modifier: Modifier,
    haptics: Boolean,
    interactive: Boolean,
    onChange: (Boolean) -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    val hf = LocalHaptics.current

    Box(
        modifier
            .size(size)
            .scale(if (pressed) 0.93f else 1f)
            .shadow(if (pressed) 2.dp else 7.dp, CircleShape, clip = false)
            .clip(CircleShape)
            .background(Brush.verticalGradient(listOf(top, bottom)))
            .let { m ->
                if (!interactive) m else m.pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown()
                        pressed = true
                        if (haptics) hf.tick()
                        onChange(true)
                        waitForUpOrCancellation()
                        pressed = false
                        onChange(false)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color(0xFF14151D), fontWeight = FontWeight.Black, fontSize = 19.sp)
    }
}

// ============================== small controls ==============================

@Composable
private fun ShoulderButton(
    label: String,
    modifier: Modifier,
    haptics: Boolean,
    onChange: (Boolean) -> Unit,
) = DepthButton(label, modifier.size(88.dp, 40.dp), RoundedCornerShape(11.dp), haptics, onChange)

@Composable
private fun PillButton(
    label: String,
    haptics: Boolean,
    onChange: (Boolean) -> Unit,
) = DepthButton(label, Modifier.size(86.dp, 36.dp), RoundedCornerShape(50), haptics, onChange)

@Composable
private fun DepthButton(
    label: String,
    modifier: Modifier,
    shape: Shape,
    haptics: Boolean,
    onChange: (Boolean) -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    val hf = LocalHaptics.current

    Box(
        modifier
            .scale(if (pressed) 0.95f else 1f)
            .shadow(if (pressed) 1.dp else 4.dp, shape, clip = false)
            .clip(shape)
            .background(
                if (pressed) Color(0xFF3D4569)
                else Color(0xFF262A3A)
            )
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    pressed = true
                    if (haptics) hf.tick()
                    onChange(true)
                    waitForUpOrCancellation()
                    pressed = false
                    onChange(false)
                }
            },
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
                awaitEachGesture {
                    awaitFirstDown()
                    val up = waitForUpOrCancellation()
                    if (up != null) onClick()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text("⚙", color = Color(0xFF98A2C0), fontSize = 15.sp)
    }
}

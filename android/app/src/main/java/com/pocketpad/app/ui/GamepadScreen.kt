package com.pocketpad.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerId
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
import com.pocketpad.app.settings.PadElement
import com.pocketpad.app.settings.PadLayout
import com.pocketpad.app.transport.PadConnection
import kotlin.math.atan2
import kotlin.math.hypot

/** Every control the unified input core can press. */
enum class PadControl { LB, RB, LT, RT, Y, B, A, X, L3, R3, BACK, START }

/** The layout element each pressable control belongs to, for visibility. */
internal fun elementOf(c: PadControl): PadElement = when (c) {
    PadControl.LB -> PadElement.LB
    PadControl.RB -> PadElement.RB
    PadControl.LT -> PadElement.LT
    PadControl.RT -> PadElement.RT
    PadControl.L3 -> PadElement.L3
    PadControl.R3 -> PadElement.R3
    PadControl.BACK -> PadElement.BACK
    PadControl.START -> PadElement.START
    PadControl.A, PadControl.B, PadControl.X, PadControl.Y -> PadElement.FACE
}

private fun maskOf(set: Set<PadControl>): Int {
    var m = 0
    for (c in set) m = m or when (c) {
        PadControl.LB -> Buttons.LB
        PadControl.RB -> Buttons.RB
        PadControl.LT -> Buttons.LT
        PadControl.RT -> Buttons.RT
        PadControl.Y -> Buttons.Y
        PadControl.B -> Buttons.B
        PadControl.A -> Buttons.A
        PadControl.X -> Buttons.X
        PadControl.L3 -> Buttons.L3
        PadControl.R3 -> Buttons.R3
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
    // Before the first layout pass the zone is Rect.Zero. The deadzone test
    // below compares against sizePx * 0.14, which no non-negative hypot can be
    // under at size 0, so without this guard an early touch would fall through
    // to a real direction.
    if (sizePx <= 0f) return Dpad.NEUTRAL
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

/** Knob radius as a fraction of the stick's radius; the rest is travel. */
private const val STICK_KNOB_FRACTION = 0.40f

/** Ignore the first tenth of travel so a resting thumb reads as centred. */
private const val STICK_DEADZONE = 0.10f

/**
 * Stick deflection for a touch, as -1..1 per axis in screen space (y grows
 * downward; the caller flips it for the wire). The travel radius matches what
 * [AnalogStick] draws, so the knob sits under the thumb rather than lagging it.
 */
private fun stickVecAt(local: Offset, sizePx: Float): Offset {
    if (sizePx <= 0f) return Offset.Zero
    val c = sizePx / 2f
    val travel = c * (1f - STICK_KNOB_FRACTION)
    if (travel <= 0f) return Offset.Zero
    val dx = local.x - c
    val dy = local.y - c
    val len = hypot(dx, dy)
    if (len < 1e-3f) return Offset.Zero
    val mag = (len / travel).coerceAtMost(1f)
    if (mag < STICK_DEADZONE) return Offset.Zero
    return Offset(dx / len * mag, dy / len * mag)
}

private fun axis(v: Float): Short = (v * 32767f).toInt().coerceIn(-32767, 32767).toShort()

/**
 * The gamepad.
 *
 * ONE input core handles every finger for the whole screen instead of
 * per-control gesture handlers. Each frame every active pointer is hit-tested
 * against every control with a finger-sized tolerance, which makes four things
 * work that per-control handlers can't:
 *  - any number of simultaneous fingers, reliably;
 *  - a single finger landing between two buttons presses BOTH (Tekken's
 *    two-button moves with one thumb);
 *  - a finger that starts on a stick or the cross stays captured by it even
 *    when it rolls past the edge mid-dash;
 *  - both sticks driven at once — move and look together, which is what makes
 *    a 3D game playable at all.
 *
 * Every control's position, size and visibility comes from
 * [AppSettings.layout], so the layout editor can move or hide any of them.
 */
@Composable
fun GamepadScreen(
    connection: PadConnection,
    latencyMs: Long?,
    settings: AppSettings,
    onSwitchToMouse: () -> Unit,
    onOpenSettings: () -> Unit,
    onToggleRightStick: () -> Unit,
) {
    val hf = LocalHaptics.current
    val lay = settings.layout
    val lookGain = settings.lookSensitivity

    // Control zones in root-window coordinates, reported by the visuals.
    val zones = remember { mutableStateMapOf<PadControl, Rect>() }
    var dpadZone by remember { mutableStateOf(Rect.Zero) }
    var lStickZone by remember { mutableStateOf(Rect.Zero) }
    var rStickZone by remember { mutableStateOf(Rect.Zero) }
    var boxOrigin by remember { mutableStateOf(Offset.Zero) }

    // What the input core decided this frame (visuals render from this).
    var pressed by remember { mutableStateOf(emptySet<PadControl>()) }
    var dpad by remember { mutableStateOf(Dpad.NEUTRAL) }
    var lStick by remember { mutableStateOf(Offset.Zero) }
    var rStick by remember { mutableStateOf(Offset.Zero) }

    // A hidden control is not composed, so its onGloballyPositioned never fires
    // again and its old rectangle would stay hit-testable. Drop every cached
    // zone whenever the layout changes; the visible ones report again at once.
    LaunchedEffect(lay) {
        zones.clear()
        if (!lay.of(PadElement.DPAD).visible) dpadZone = Rect.Zero
        if (!lay.of(PadElement.LSTICK).visible) lStickZone = Rect.Zero
        if (!lay.of(PadElement.RSTICK).visible) rStickZone = Rect.Zero
    }

    // Leaving the pad (gear tap, mouse mode, back) cancels the input core
    // mid-press while the sender loop keeps transmitting the last state at
    // 125 Hz, which reads on the PC as a jammed button. Hand over an explicit
    // neutral on the way out.
    DisposableEffect(connection) {
        onDispose {
            pressed = emptySet()
            dpad = Dpad.NEUTRAL
            lStick = Offset.Zero
            rStick = Offset.Zero
            connection.setPadState(PadState())
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .onGloballyPositioned { boxOrigin = it.positionInRoot() }
            .pointerInput(settings.haptics, lookGain) {
                val rectSlopPx = 14.dp.toPx()   // forgiveness on the fixed-size controls
                val analogSlopPx = 20.dp.toPx() // forgiveness entering a stick or the cross
                var dpadPointer: PointerId? = null
                var lStickPointer: PointerId? = null
                var rStickPointer: PointerId? = null

                // A touch sitting on a real button belongs to that button and
                // never to a stick or the cross. Controls can be dragged on top
                // of one another, and a captured pointer is excluded from the
                // button pass below — so without this an overlapped button
                // becomes unpressable. A stick has area to spare; a swallowed
                // button has none.
                fun overButton(p: Offset): Boolean = zones.any { (ctl, rect) ->
                    if (ctl in FACE_CONTROLS) {
                        (p - rect.center).getDistance() <= rect.width / 2f
                    } else {
                        rect.contains(p)
                    }
                }

                // A restart — haptics toggled, sensitivity changed, or
                // ACTION_CANCEL from a notification-shade pull — drops every
                // held finger without a matching release, so start from an
                // explicit neutral rather than inheriting stale values.
                pressed = emptySet()
                dpad = Dpad.NEUTRAL
                lStick = Offset.Zero
                rStick = Offset.Zero
                connection.setPadState(PadState())

                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val down = event.changes.filter { it.pressed }

                        // ---- analog captures ----
                        // Whichever pointer lands on a stick or the cross OWNS
                        // it until lift-off, wherever it rolls. Three separate
                        // captures let both sticks and the cross run at once.
                        if (dpadPointer != null && down.none { it.id == dpadPointer }) dpadPointer = null
                        if (lStickPointer != null && down.none { it.id == lStickPointer }) lStickPointer = null
                        if (rStickPointer != null && down.none { it.id == rStickPointer }) rStickPointer = null

                        fun taken(id: PointerId) =
                            id == dpadPointer || id == lStickPointer || id == rStickPointer

                        fun claimFor(zone: Rect): PointerId? = down.firstOrNull { ch ->
                            val p = boxOrigin + ch.position
                            !taken(ch.id) &&
                                zone.width > 0f &&
                                zone.inflate(analogSlopPx).contains(p) &&
                                !overButton(p)
                        }?.id

                        if (lStickPointer == null) lStickPointer = claimFor(lStickZone)
                        if (rStickPointer == null) rStickPointer = claimFor(rStickZone)
                        if (dpadPointer == null) dpadPointer = claimFor(dpadZone)

                        val newDpad = down.firstOrNull { it.id == dpadPointer }?.let {
                            dpadDirAt(boxOrigin + it.position - dpadZone.topLeft, dpadZone.width)
                        } ?: Dpad.NEUTRAL
                        val newL = down.firstOrNull { it.id == lStickPointer }?.let {
                            stickVecAt(boxOrigin + it.position - lStickZone.topLeft, lStickZone.width)
                        } ?: Offset.Zero
                        val newR = down.firstOrNull { it.id == rStickPointer }?.let {
                            stickVecAt(boxOrigin + it.position - rStickZone.topLeft, rStickZone.width)
                        } ?: Offset.Zero

                        // ---- buttons: every finger vs every zone ----
                        // A touch within (radius + slop) of a circle presses it,
                        // so one finger between two buttons presses both. The
                        // face slop is a fraction of the button rather than a
                        // constant, so that overlap survives the size slider;
                        // the fixed-size controls keep a constant slop.
                        val newPressed = buildSet {
                            for ((ctl, rect) in zones) {
                                val isRound = ctl in FACE_CONTROLS
                                val hit = down.any { ch ->
                                    if (taken(ch.id)) return@any false
                                    val p = boxOrigin + ch.position
                                    if (isRound) {
                                        (p - rect.center).getDistance() <=
                                            rect.width * (0.5f + FACE_SLOP_FRACTION)
                                    } else {
                                        rect.inflate(rectSlopPx).contains(p)
                                    }
                                }
                                if (hit) add(ctl)
                            }
                        }

                        if (newPressed != pressed || newDpad != dpad ||
                            newL != lStick || newR != rStick
                        ) {
                            // Buzz on a new press or a new direction only —
                            // never on stick travel, which changes constantly.
                            if (settings.haptics &&
                                ((newPressed - pressed).isNotEmpty() ||
                                    (newDpad != dpad && newDpad != Dpad.NEUTRAL))
                            ) hf.tick()
                            pressed = newPressed
                            dpad = newDpad
                            lStick = newL
                            rStick = newR
                            connection.setPadState(
                                PadState(
                                    buttons = maskOf(newPressed),
                                    dpad = newDpad,
                                    // screen y grows down, thumbstick up
                                    lx = axis(newL.x),
                                    ly = axis(-newL.y),
                                    rx = axis(newR.x * lookGain),
                                    ry = axis(-newR.y * lookGain),
                                )
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
                .padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ModeChip(label = "Mouse", icon = ModeIcon.Mouse, onClick = onSwitchToMouse)
            Text(
                text = latencyMs?.let { "$it ms" } ?: "— ms",
                color = if ((latencyMs ?: 99) < 20) Color(0xFF9ECE6A) else Color(0xFFE0AF68),
                style = MaterialTheme.typography.labelMedium,
            )
            // On the pad, not in Settings: which games want a look stick
            // changes game to game, so this has to be one tap away mid-session.
            StickChip(
                on = lay.of(PadElement.RSTICK).visible,
                onToggle = onToggleRightStick,
            )
            GearChip(onClick = onOpenSettings)
        }

        // ---- shoulders, triggers, stick clicks, menu ----
        // Drawn in PAD_SPECS order, the same order the editor iterates, so two
        // overlapping controls stack the same way on both screens — otherwise
        // the one that answers a touch differs between tuning and playing.
        PAD_SPECS.keys.forEach { element ->
            val ctl = controlOf(element) ?: return@forEach
            PlacedButton(ctl, element, lay, pressed) { zones[ctl] = it }
        }

        // ---- d-pad ----
        val dpadSpec = specOf(PadElement.DPAD)
        val dpadL = lay.of(PadElement.DPAD).clampedTo(dpadSpec)
        if (dpadL.visible) {
            DpadCross(
                size = DPAD_BASE * dpadL.scale,
                current = dpad,
                modifier = Modifier
                    .align(dpadSpec.align)
                    .placeElement(dpadSpec, dpadL)
                    .onGloballyPositioned { dpadZone = it.boundsInRoot() },
            )
        }

        // ---- left stick: movement ----
        val lSpec = specOf(PadElement.LSTICK)
        val lL = lay.of(PadElement.LSTICK).clampedTo(lSpec)
        if (lL.visible) {
            AnalogStick(
                size = STICK_BASE * lL.scale,
                knob = lStick,
                label = "L",
                modifier = Modifier
                    .align(lSpec.align)
                    .placeElement(lSpec, lL)
                    .onGloballyPositioned { lStickZone = it.boundsInRoot() },
            )
        }

        // ---- right stick: camera and aim ----
        val rSpec = specOf(PadElement.RSTICK)
        val rL = lay.of(PadElement.RSTICK).clampedTo(rSpec)
        if (rL.visible) {
            AnalogStick(
                size = STICK_BASE * rL.scale,
                knob = rStick,
                label = "R",
                modifier = Modifier
                    .align(rSpec.align)
                    .placeElement(rSpec, rL)
                    .onGloballyPositioned { rStickZone = it.boundsInRoot() },
            )
        }

        // ---- face cluster ----
        val faceSpec = specOf(PadElement.FACE)
        val faceL = lay.of(PadElement.FACE).clampedTo(faceSpec)
        if (faceL.visible) {
            FaceCluster(
                buttonSize = FACE_BUTTON_BASE * faceL.scale,
                pressed = pressed,
                onZone = { ctl, rect -> zones[ctl] = rect },
                modifier = Modifier
                    .align(faceSpec.align)
                    .placeElement(faceSpec, faceL),
            )
        }
    }
}

internal val FACE_CONTROLS = setOf(PadControl.A, PadControl.B, PadControl.X, PadControl.Y)

/**
 * How far past its drawn edge a face button still answers, as a fraction of the
 * button's width — so it tracks the size slider instead of being a fixed dp
 * that only suits one setting.
 *
 * The four buttons form a diamond with adjacent centres `width * sqrt(2)` apart,
 * so two hit circles of radius `width * (0.5 + f)` overlap only while
 * `f > (sqrt(2) - 1) / 2 ≈ 0.207` — that overlap lens is what lets one thumb
 * press both buttons for Tekken's two-button moves. The diamond's centre sits
 * `width` from every button, so staying under `f = 0.5` keeps a middle touch
 * from pressing all four at once. 0.28 sits between the two: a lens about
 * 0.15 of a button wide, at every size.
 */
internal const val FACE_SLOP_FRACTION = 0.28f

/** The pressable control a layout element carries, if it is a plain button. */
private fun controlOf(e: PadElement): PadControl? = when (e) {
    PadElement.LB -> PadControl.LB
    PadElement.RB -> PadControl.RB
    PadElement.LT -> PadControl.LT
    PadElement.RT -> PadControl.RT
    PadElement.L3 -> PadControl.L3
    PadElement.R3 -> PadControl.R3
    PadElement.BACK -> PadControl.BACK
    PadElement.START -> PadControl.START
    PadElement.DPAD, PadElement.LSTICK, PadElement.RSTICK, PadElement.FACE -> null
}

/** A rectangular control placed, sized and shown per the saved layout. */
@Composable
private fun BoxScope.PlacedButton(
    ctl: PadControl,
    element: PadElement,
    lay: PadLayout,
    pressed: Set<PadControl>,
    onZone: (Rect) -> Unit,
) {
    val spec = specOf(element)
    val l = lay.of(element).clampedTo(spec)
    if (!l.visible) return
    val m = buttonMetricsOf(element) ?: return
    DepthButton(
        element.name,
        pressed = ctl in pressed,
        Modifier
            .align(spec.align)
            .placeElement(spec, l)
            .size(m.width * l.scale, m.height * l.scale)
            .onGloballyPositioned { onZone(it.boundsInRoot()) },
        if (m.pill) RoundedCornerShape(50) else RoundedCornerShape(11.dp),
    )
}

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

            // Each arm lights on its own. Drawing the cross as two full-length
            // bars lit the opposite arm too — pressing UP also lit DOWN.
            val upHot = current == Dpad.UP || current == Dpad.UP_LEFT || current == Dpad.UP_RIGHT
            val downHot = current == Dpad.DOWN || current == Dpad.DOWN_LEFT || current == Dpad.DOWN_RIGHT
            val leftHot = current == Dpad.LEFT || current == Dpad.UP_LEFT || current == Dpad.DOWN_LEFT
            val rightHot = current == Dpad.RIGHT || current == Dpad.UP_RIGHT || current == Dpad.DOWN_RIGHT

            val half = w / 2f
            val side = (w - arm) / 2f
            // Arms run from their outer edge to the far side of the hub, which
            // is then painted over them — that hides the rounded inner corners
            // and every overlap, so the cross still reads as one piece.
            val armLen = half + arm / 2f

            drawRoundRect(armBrush(upHot), Offset(side, 0f),
                Size(arm, armLen), CornerRadius(r, r))
            drawRoundRect(armBrush(downHot), Offset(side, w - armLen),
                Size(arm, armLen), CornerRadius(r, r))
            drawRoundRect(armBrush(leftHot), Offset(0f, side),
                Size(armLen, arm), CornerRadius(r, r))
            drawRoundRect(armBrush(rightHot), Offset(w - armLen, side),
                Size(armLen, arm), CornerRadius(r, r))

            // Neutral hub, so no direction bleeds across the centre.
            drawRoundRect(
                brush = Brush.verticalGradient(listOf(idle1, idle2)),
                topLeft = Offset(side, side),
                size = Size(arm, arm),
                cornerRadius = CornerRadius(r * 0.5f, r * 0.5f),
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

// ============================== analog sticks ==============================

/**
 * Visual-only analog stick; the screen's input core feeds [knob] as -1..1 per
 * axis in screen space. Drawn travel matches [stickVecAt] so the knob tracks
 * the thumb exactly. [label] marks which stick it is at a glance.
 */
@Composable
fun AnalogStick(
    size: Dp,
    knob: Offset = Offset.Zero,
    label: String? = null,
    modifier: Modifier = Modifier,
) {
    Box(modifier.size(size)) {
        Canvas(Modifier.fillMaxSize()) {
            val w = this.size.width
            val r = w / 2f
            val c = Offset(r, r)

            drawCircle(Color(0xFF1E212D), radius = r, center = c)
            drawCircle(Color(0xFF262A3A), radius = r * 0.93f, center = c)
            drawCircle(
                Color(0xFF31354A),
                radius = r * 0.64f,
                center = c,
                style = Stroke(width = w * 0.011f),
            )

            val kr = r * STICK_KNOB_FRACTION
            val travel = r - kr
            val kc = Offset(r + knob.x * travel, r + knob.y * travel)
            drawCircle(Color(0xFF20232F), radius = kr * 1.05f, center = kc)
            drawCircle(
                Brush.verticalGradient(
                    listOf(Color(0xFF464F78), Color(0xFF313753)),
                    startY = kc.y - kr,
                    endY = kc.y + kr,
                ),
                radius = kr,
                center = kc,
            )
            drawCircle(Color(0xFF7C86AC), radius = kr * 0.26f, center = kc)
        }
        if (label != null) {
            Text(
                label,
                color = Color(0xFF3E4560),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 6.dp),
            )
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
internal fun DepthButton(
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

/**
 * Turns the look stick on and off from the pad itself.
 *
 * Visibility also lives in Settings and the layout editor, but neither is
 * reachable without leaving the game — and whether a title wants a right stick
 * is exactly the kind of thing you discover once you are already playing.
 */
@Composable
private fun StickChip(on: Boolean, onToggle: () -> Unit) {
    Box(
        Modifier
            .size(74.dp, 26.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(if (on) Color(0xFF2C3557) else Color(0xFF23262F))
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        // Release-inside, like the gear: a thumb brushing the
                        // top bar must not flip a control away mid-fight.
                        val down = awaitFirstDown()
                        down.consume()
                        val up = waitForUpOrCancellation() ?: continue
                        up.consume()
                        val p = up.position
                        if (p.x >= 0f && p.y >= 0f &&
                            p.x <= size.width && p.y <= size.height
                        ) onToggle()
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (on) "STICK ON" else "STICK OFF",
            color = if (on) Color(0xFF9DB4F0) else Color(0xFF5C6480),
            fontWeight = FontWeight.Bold,
            fontSize = 9.sp,
        )
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
                        // Fire on release inside the chip, not on touch-down: a
                        // thumb brushing the top bar mid-game would otherwise
                        // swap the pad for Settings with no way to back out.
                        val down = awaitFirstDown()
                        down.consume()
                        val up = waitForUpOrCancellation() ?: continue
                        up.consume()
                        val p = up.position
                        if (p.x >= 0f && p.y >= 0f &&
                            p.x <= size.width && p.y <= size.height
                        ) onClick()
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text("⚙", color = Color(0xFF98A2C0), fontSize = 15.sp)
    }
}

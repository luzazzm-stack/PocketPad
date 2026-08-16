package com.pocketpad.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import com.pocketpad.app.protocol.Buttons
import com.pocketpad.app.protocol.Dpad
import com.pocketpad.app.protocol.PadState
import com.pocketpad.app.transport.PadConnection

/**
 * Phase-1 layout: d-pad cross (left), A/B/X/Y diamond (right), Start (center).
 * Every press/release recomputes the full PadState snapshot for the 125 Hz sender.
 */
@Composable
fun GamepadScreen(connection: PadConnection, latencyMs: Long?) {
    // Single source of truth for what's held down right now.
    var buttons by remember { mutableStateOf(0) }
    var up by remember { mutableStateOf(false) }
    var down by remember { mutableStateOf(false) }
    var left by remember { mutableStateOf(false) }
    var right by remember { mutableStateOf(false) }

    fun push() {
        connection.state.set(
            PadState(buttons = buttons, dpad = Dpad.from(up, down, left, right))
        )
    }
    fun setButton(bit: Int, pressed: Boolean) {
        buttons = if (pressed) buttons or bit else buttons and bit.inv()
        push()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Text(
            text = latencyMs?.let { "$it ms" } ?: "— ms",
            color = if ((latencyMs ?: 99) < 20) Color(0xFF9ECE6A) else Color(0xFFE0AF68),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 8.dp),
        )

        // ---- D-pad cross, left side ----
        // Cluster is sized 3x the button so the offset children stay in bounds.
        val dSize = 64.dp
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .padding(start = 24.dp)
                .size(dSize * 3)
        ) {
            PadButton("▲", dSize, RoundedCornerShape(12.dp),
                Modifier.align(Alignment.TopCenter),
                onChange = { up = it; push() })
            PadButton("▼", dSize, RoundedCornerShape(12.dp),
                Modifier.align(Alignment.BottomCenter),
                onChange = { down = it; push() })
            PadButton("◀", dSize, RoundedCornerShape(12.dp),
                Modifier.align(Alignment.CenterStart),
                onChange = { left = it; push() })
            PadButton("▶", dSize, RoundedCornerShape(12.dp),
                Modifier.align(Alignment.CenterEnd),
                onChange = { right = it; push() })
        }

        // ---- Face buttons diamond, right side ----
        val fSize = 68.dp
        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 24.dp)
                .size(fSize * 3)
        ) {
            FaceButton("Y", fSize, Color(0xFFE0AF68),
                Modifier.align(Alignment.TopCenter),
                onChange = { setButton(Buttons.Y, it) })
            FaceButton("A", fSize, Color(0xFF9ECE6A),
                Modifier.align(Alignment.BottomCenter),
                onChange = { setButton(Buttons.A, it) })
            FaceButton("X", fSize, Color(0xFF7AA2F7),
                Modifier.align(Alignment.CenterStart),
                onChange = { setButton(Buttons.X, it) })
            FaceButton("B", fSize, Color(0xFFF7768E),
                Modifier.align(Alignment.CenterEnd),
                onChange = { setButton(Buttons.B, it) })
        }

        // ---- Shoulders, top corners (Tekken: throws / Rage Art) ----
        PadButton("LB", width = 92.dp, height = 44.dp,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 24.dp, top = 12.dp),
            onChange = { setButton(Buttons.LB, it) })
        PadButton("RB", width = 92.dp, height = 44.dp,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = 24.dp, top = 12.dp),
            onChange = { setButton(Buttons.RB, it) })

        // ---- Back / Start, bottom-center ----
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            PadButton("BACK", width = 88.dp, height = 40.dp,
                shape = RoundedCornerShape(20.dp),
                onChange = { setButton(Buttons.BACK, it) })
            PadButton("START", width = 88.dp, height = 40.dp,
                shape = RoundedCornerShape(20.dp),
                onChange = { setButton(Buttons.START, it) })
        }
    }
}

@Composable
private fun FaceButton(
    label: String,
    size: Dp,
    tint: Color,
    modifier: Modifier = Modifier,
    onChange: (Boolean) -> Unit,
) = PadButton(label, size, CircleShape, modifier, tint, onChange)

@Composable
private fun PadButton(
    label: String,
    size: Dp,
    shape: Shape,
    modifier: Modifier = Modifier,
    tint: Color = Color(0xFF565F89),
    onChange: (Boolean) -> Unit,
) = PadButton(label, size, size, shape, modifier, tint, onChange)

@Composable
private fun PadButton(
    label: String,
    width: Dp,
    height: Dp,
    shape: Shape,
    modifier: Modifier = Modifier,
    tint: Color = Color(0xFF565F89),
    onChange: (Boolean) -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current

    Box(
        modifier
            .size(width, height)
            .clip(shape)
            .background(if (pressed) tint else tint.copy(alpha = 0.35f))
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    pressed = true
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onChange(true)
                    waitForUpOrCancellation()
                    pressed = false
                    onChange(false)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (pressed) Color(0xFF1A1B26) else Color.White,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

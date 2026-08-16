package com.pocketpad.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import com.pocketpad.app.haptics.LocalHaptics
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketpad.app.protocol.MouseButtons
import com.pocketpad.app.settings.AppSettings
import com.pocketpad.app.transport.PadConnection
import kotlin.math.abs
import kotlin.math.roundToInt

/** Pixels of vertical travel per wheel notch on the scroll strip. */
private const val SCROLL_STEP = 28f

/**
 * Trackpad mode: drag anywhere to move the cursor, tap to click, plus explicit
 * click buttons and a scroll strip. Used to drive Windows itself — launching a
 * game, closing a dialog — where a gamepad can't help.
 */
@Composable
fun TrackpadScreen(
    connection: PadConnection,
    latencyMs: Long?,
    settings: AppSettings,
    onSwitchToPad: () -> Unit,
) {
    val sensitivity = settings.mouseSensitivity
    val haptics = LocalHaptics.current

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // ---- header ----
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ModeChip(label = "Gamepad", icon = ModeIcon.Gamepad, onClick = onSwitchToPad)
            Text(
                "TRACKPAD",
                color = Color(0xFF7AA2F7),
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                latencyMs?.let { "$it ms" } ?: "— ms",
                color = if ((latencyMs ?: 99) < 20) Color(0xFF9ECE6A) else Color(0xFFE0AF68),
                style = MaterialTheme.typography.labelMedium,
            )
        }

        Row(
            Modifier
                .fillMaxWidth()
                .weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // ---- the pad surface ----
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF24283B))
                    .pointerInput(Unit) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            connection.moveMouse(
                                (drag.x * sensitivity).roundToInt(),
                                (drag.y * sensitivity).roundToInt(),
                            )
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures {
                            haptics.tick()
                            connection.setMouseButton(MouseButtons.LEFT, true)
                            connection.setMouseButton(MouseButtons.LEFT, false)
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "drag to move  ·  tap to click",
                    color = Color(0xFF565F89),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            // ---- scroll strip ----
            Box(
                Modifier
                    .width(58.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF1F2335))
                    .pointerInput(Unit) {
                        var carry = 0f
                        detectDragGestures(
                            onDragEnd = { carry = 0f },
                        ) { change, drag ->
                            change.consume()
                            carry += drag.y
                            while (abs(carry) >= SCROLL_STEP) {
                                // screen +y is down; wheel +1 is up
                                val dir = if (carry > 0) -1 else 1
                                connection.scrollMouse(dir)
                                carry -= dir * -SCROLL_STEP
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "S\nC\nR\nO\nL\nL",
                    color = Color(0xFF565F89),
                    style = MaterialTheme.typography.labelSmall,
                    lineHeight = 13.sp,
                )
            }
        }

        // ---- click buttons ----
        Row(
            Modifier
                .fillMaxWidth()
                .height(58.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ClickButton("LEFT CLICK", Modifier.weight(2f), MouseButtons.LEFT, connection)
            ClickButton("MIDDLE", Modifier.weight(1f), MouseButtons.MIDDLE, connection)
            ClickButton("RIGHT CLICK", Modifier.weight(2f), MouseButtons.RIGHT, connection)
        }
    }
}

@Composable
private fun ClickButton(
    label: String,
    modifier: Modifier,
    bit: Int,
    connection: PadConnection,
) {
    var pressed by remember { mutableStateOf(false) }
    val haptics = LocalHaptics.current

    Box(
        modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(12.dp))
            .background(if (pressed) Color(0xFF7AA2F7) else Color(0xFF2F3549))
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    pressed = true
                    haptics.tick()
                    connection.setMouseButton(bit, true)
                    waitForUpOrCancellation()
                    pressed = false
                    connection.setMouseButton(bit, false)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (pressed) Color(0xFF1A1B26) else Color.White,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

package com.pocketpad.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketpad.app.protocol.Dpad
import com.pocketpad.app.settings.PadLayout

/**
 * Layout customizer: the two clusters render exactly as they do in play,
 * but dragging moves them and the sliders resize them. Nothing is sent to
 * the PC from this screen.
 */
@Composable
fun LayoutEditScreen(
    initial: PadLayout,
    onSave: (PadLayout) -> Unit,
    onCancel: () -> Unit,
) {
    var lay by remember { mutableStateOf(initial) }
    val density = LocalDensity.current

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Text(
            "Drag a cluster to move it",
            color = Color(0xFF6B7392),
            fontSize = 12.sp,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp),
        )

        // ---- draggable d-pad ----
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .padding(start = 30.dp)
                .offset(lay.dpadX.dp, lay.dpadY.dp)
                .pointerInput(Unit) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        with(density) {
                            lay = lay.copy(
                                dpadX = (lay.dpadX + drag.x.toDp().value).coerceIn(-20f, 200f),
                                dpadY = (lay.dpadY + drag.y.toDp().value).coerceIn(-90f, 90f),
                            )
                        }
                    }
                }
                .border(1.5.dp, Color(0xFF7AA2F7), RoundedCornerShape(20.dp))
                .padding(6.dp)
        ) {
            DpadCross(
                size = 186.dp * lay.dpadScale,
                haptics = false,
                current = Dpad.NEUTRAL,
                onDirection = {},
                interactive = false,
            )
        }

        // ---- draggable face cluster ----
        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 30.dp)
                .offset(lay.faceX.dp, lay.faceY.dp)
                .pointerInput(Unit) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        with(density) {
                            lay = lay.copy(
                                faceX = (lay.faceX + drag.x.toDp().value).coerceIn(-200f, 20f),
                                faceY = (lay.faceY + drag.y.toDp().value).coerceIn(-90f, 90f),
                            )
                        }
                    }
                }
                .border(1.5.dp, Color(0xFF7AA2F7), RoundedCornerShape(20.dp))
                .padding(6.dp)
        ) {
            FaceCluster(
                buttonSize = 62.dp * lay.faceScale,
                haptics = false,
                onButton = { _, _ -> },
                interactive = false,
            )
        }

        // ---- bottom bar: sliders + actions ----
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color(0xE61B1D26))
                .padding(horizontal = 34.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("D-pad size", color = Color(0xFF98A2C0), fontSize = 11.sp,
                    modifier = Modifier.width(88.dp))
                Slider(
                    value = lay.dpadScale,
                    onValueChange = { lay = lay.copy(dpadScale = it) },
                    valueRange = 0.7f..1.5f,
                    colors = sliderColors(),
                    modifier = Modifier.weight(1f),
                )
                Text("Buttons size", color = Color(0xFF98A2C0), fontSize = 11.sp,
                    modifier = Modifier.width(96.dp).padding(start = 16.dp))
                Slider(
                    value = lay.faceScale,
                    onValueChange = { lay = lay.copy(faceScale = it) },
                    valueRange = 0.7f..1.5f,
                    colors = sliderColors(),
                    modifier = Modifier.weight(1f),
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                BarButton("Save layout", Color(0xFF7AA2F7), Color(0xFF14151D),
                    Modifier.weight(1f)) { onSave(lay) }
                BarButton("Reset", Color(0xFF2A2E3F), Color(0xFF98A2C0),
                    Modifier.width(90.dp)) { lay = PadLayout() }
                BarButton("Cancel", Color(0xFF2A2E3F), Color(0xFF98A2C0),
                    Modifier.width(90.dp)) { onCancel() }
            }
        }
    }
}

@Composable
private fun sliderColors() = SliderDefaults.colors(
    thumbColor = Color(0xFF7AA2F7),
    activeTrackColor = Color(0xFF7AA2F7),
    inactiveTrackColor = Color(0xFF31354A),
)

@Composable
private fun BarButton(
    label: String,
    bg: Color,
    fg: Color,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = fg, fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
    }
}

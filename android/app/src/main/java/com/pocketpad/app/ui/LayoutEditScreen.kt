package com.pocketpad.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketpad.app.protocol.Dpad
import com.pocketpad.app.settings.PadElement
import com.pocketpad.app.settings.PadLayout

/**
 * Layout customizer: every control renders exactly as it does in play, and all
 * of them can be dragged. Tapping one selects it so the size slider applies to
 * it — one slider for whichever control is selected, rather than eight.
 * Nothing is sent to the PC from this screen.
 */
@Composable
fun LayoutEditScreen(
    initial: PadLayout,
    onSave: (PadLayout) -> Unit,
    onCancel: () -> Unit,
) {
    var lay by remember { mutableStateOf(initial) }
    var selected by remember { mutableStateOf(PadElement.DPAD) }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Text(
            "Drag any control to move it · tap one to select, then resize it below",
            color = Color(0xFF6B7392),
            fontSize = 12.sp,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 10.dp),
        )

        PAD_SPECS.keys.forEach { element ->
            val l = lay.of(element)
            Editable(
                element = element,
                lay = lay,
                selected = selected == element,
                onSelect = { selected = element },
                onDrag = { dx, dy ->
                    lay = lay.with(element) { it.dragged(specOf(element), dx, dy) }
                },
            ) {
                when (element) {
                    PadElement.DPAD ->
                        if (lay.stickMode) AnalogStick(size = 186.dp * l.scale)
                        else DpadCross(size = 186.dp * l.scale, current = Dpad.NEUTRAL)

                    PadElement.FACE -> FaceCluster(buttonSize = 62.dp * l.scale)

                    // Shown as a plain chip here: in the editor it is something
                    // to place, not something to switch.
                    PadElement.TOGGLE -> DepthButton(
                        if (lay.stickMode) "STICK" else "D-PAD",
                        pressed = false,
                        Modifier.size(64.dp * l.scale, 24.dp * l.scale),
                        RoundedCornerShape(12.dp),
                    )

                    else -> {
                        val w = when (element) {
                            PadElement.LB, PadElement.RB -> 88.dp
                            PadElement.LT, PadElement.RT -> 78.dp
                            else -> 86.dp
                        }
                        val h = if (element == PadElement.BACK || element == PadElement.START) 36.dp else 40.dp
                        val shape =
                            if (element == PadElement.BACK || element == PadElement.START)
                                RoundedCornerShape(50) else RoundedCornerShape(11.dp)
                        DepthButton(
                            specOf(element).label,
                            pressed = false,
                            Modifier.size(w * l.scale, h * l.scale),
                            shape,
                        )
                    }
                }
            }
        }

        // ---- bottom bar: size of the selected control + actions ----
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color(0xE61B1D26))
                .padding(horizontal = 34.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${specOf(selected).label} size",
                    color = Color(0xFF7AA2F7),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(120.dp),
                )
                Slider(
                    value = lay.of(selected).scale,
                    onValueChange = { v -> lay = lay.with(selected) { it.copy(scale = v) } },
                    valueRange = PadLayout.MIN_SCALE..PadLayout.MAX_SCALE,
                    colors = sliderColors(),
                    modifier = Modifier.weight(1f),
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                BarButton(
                    if (lay.stickMode) "Left: STICK" else "Left: D-PAD",
                    Color(0xFF2C3557), Color(0xFF9DB4F0), Modifier.width(120.dp),
                ) { lay = lay.copy(stickMode = !lay.stickMode) }
                BarButton("Save layout", Color(0xFF7AA2F7), Color(0xFF14151D),
                    Modifier.weight(1f)) { onSave(lay) }
                BarButton("Reset", Color(0xFF2A2E3F), Color(0xFF98A2C0),
                    Modifier.width(84.dp)) { lay = PadLayout(stickMode = lay.stickMode) }
                BarButton("Cancel", Color(0xFF2A2E3F), Color(0xFF98A2C0),
                    Modifier.width(84.dp)) { onCancel() }
            }
        }
    }
}

/**
 * Wraps one control so it can be dragged and selected. Touch-down selects —
 * this screen sends nothing to the PC, so there is no cost to acting early,
 * and it makes the slider follow whatever the thumb just grabbed.
 */
@Composable
private fun BoxScope.Editable(
    element: PadElement,
    lay: PadLayout,
    selected: Boolean,
    onSelect: () -> Unit,
    onDrag: (dx: Float, dy: Float) -> Unit,
    content: @Composable () -> Unit,
) {
    val spec = specOf(element)
    Box(
        Modifier
            .align(spec.align)
            .placeElement(spec, lay.of(element))
            .pointerInput(element) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    onSelect()
                    drag(down.id) { change ->
                        // Read the delta BEFORE consuming: positionChange()
                        // reports Offset.Zero once a change is consumed, so
                        // consuming first made every drag a no-op.
                        val d = change.positionChange()
                        change.consume()
                        onDrag(d.x.toDp().value, d.y.toDp().value)
                    }
                }
            }
            .border(
                if (selected) 2.dp else 1.dp,
                if (selected) Color(0xFF7AA2F7) else Color(0xFF343A54),
                RoundedCornerShape(14.dp),
            )
            .padding(5.dp)
    ) { content() }
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

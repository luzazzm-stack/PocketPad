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
import androidx.compose.ui.draw.alpha
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
 * of them can be dragged.
 *
 * Selection is by tapping a control OR by the ‹ › arrows in the bar. The arrows
 * are not a convenience — the bar covers the bottom strip of the screen, and
 * controls that ship hidden (L3/R3) or sit low (the sticks) would otherwise be
 * unselectable, leaving no way to switch them back on.
 *
 * Hidden controls stay on screen here, dimmed, for the same reason. Nothing is
 * sent to the PC from this screen.
 */
@Composable
fun LayoutEditScreen(
    initial: PadLayout,
    onSave: (PadLayout) -> Unit,
    onCancel: () -> Unit,
) {
    var lay by remember { mutableStateOf(initial) }
    val order = remember { PAD_SPECS.keys.toList() }
    var selected by remember { mutableStateOf(PadElement.LSTICK) }
    val sel = lay.of(selected)

    fun step(delta: Int) {
        val i = order.indexOf(selected)
        selected = order[((i + delta) % order.size + order.size) % order.size]
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Presets live at the top, clear of the bottom bar, because they are a
        // "start from here" action rather than a per-control one.
        Column(
            Modifier.align(Alignment.TopCenter).padding(top = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                PadPreset.entries.forEachIndexed { i, preset ->
                    PresetChip(
                        number = i + 1,
                        blurb = preset.blurb,
                    ) { lay = presetLayout(preset) }
                }
            }
            Text(
                "pick a preset, or drag any control · ‹ › selects · then resize or hide",
                color = Color(0xFF565F89),
                fontSize = 9.5.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
        }

        order.forEach { element ->
            val l = lay.of(element).clampedTo(specOf(element))
            Editable(
                element = element,
                l = l,
                selected = selected == element,
                onSelect = { selected = element },
                onDrag = { dx, dy ->
                    lay = lay.with(element) { it.dragged(specOf(element), dx, dy) }
                },
            ) {
                when (element) {
                    PadElement.DPAD ->
                        DpadCross(size = DPAD_BASE * l.scale, current = Dpad.NEUTRAL)

                    PadElement.LSTICK -> AnalogStick(size = STICK_BASE * l.scale, label = "L")
                    PadElement.RSTICK -> AnalogStick(size = STICK_BASE * l.scale, label = "R")

                    PadElement.FACE -> FaceCluster(buttonSize = FACE_BUTTON_BASE * l.scale)

                    else -> {
                        // Metrics come from the shared table, so a size change
                        // cannot land in play but not here.
                        val m = buttonMetricsOf(element)!!
                        DepthButton(
                            element.name,
                            pressed = false,
                            Modifier.size(m.width * l.scale, m.height * l.scale),
                            if (m.pill) RoundedCornerShape(50) else RoundedCornerShape(11.dp),
                        )
                    }
                }
            }
        }

        // ---- bottom bar: the selected control's size and visibility ----
        // Kept to one compact row plus buttons, and translucent, because it
        // covers whatever is behind it on a short screen.
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color(0xD91B1D26))
                .padding(horizontal = 20.dp, vertical = 5.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StepArrow("‹") { step(-1) }
                Text(
                    specOf(selected).label + if (sel.visible) "" else "  (hidden)",
                    color = if (sel.visible) Color(0xFF7AA2F7) else Color(0xFF6B7392),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(150.dp).padding(horizontal = 6.dp),
                )
                StepArrow("›") { step(1) }
                Slider(
                    value = sel.scale,
                    onValueChange = { v -> lay = lay.with(selected) { it.copy(scale = v) } },
                    valueRange = PadLayout.MIN_SCALE..PadLayout.MAX_SCALE,
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFF7AA2F7),
                        activeTrackColor = Color(0xFF7AA2F7),
                        inactiveTrackColor = Color(0xFF31354A),
                    ),
                    modifier = Modifier.weight(1f).padding(start = 10.dp),
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 1.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BarButton(
                    if (sel.visible) "Hide this" else "Show this",
                    if (sel.visible) Color(0xFF3A2E3E) else Color(0xFF2C3557),
                    if (sel.visible) Color(0xFFE59BB0) else Color(0xFF9DB4F0),
                    Modifier.width(100.dp),
                ) { lay = lay.with(selected) { it.copy(visible = !it.visible) } }
                BarButton("Save layout", Color(0xFF7AA2F7), Color(0xFF14151D),
                    Modifier.weight(1f)) { onSave(lay) }
                BarButton("Reset all", Color(0xFF2A2E3F), Color(0xFF98A2C0),
                    Modifier.width(84.dp)) { lay = PadLayout() }
                BarButton("Cancel", Color(0xFF2A2E3F), Color(0xFF98A2C0),
                    Modifier.width(78.dp)) { onCancel() }
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
    l: com.pocketpad.app.settings.ElementLayout,
    selected: Boolean,
    onSelect: () -> Unit,
    onDrag: (dx: Float, dy: Float) -> Unit,
    content: @Composable () -> Unit,
) {
    val spec = specOf(element)
    Box(
        Modifier
            .align(spec.align)
            .placeElement(spec, l)
            // Hidden controls stay visible here, faded, so they can be selected
            // and switched back on. Hiding them outright would strand them.
            .alpha(if (l.visible) 1f else 0.3f)
            .pointerInput(element) {
                awaitEachGesture {
                    val down = awaitFirstDown(
                        requireUnconsumed = false,
                        pass = PointerEventPass.Initial,
                    )
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
            .padding(4.dp)
    ) { content() }
}

/** One preset button: big number, small description under it. */
@Composable
private fun PresetChip(number: Int, blurb: String, onClick: () -> Unit) {
    Column(
        Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(Color(0xFF262A3A))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Layout $number",
            color = Color(0xFF9DB4F0),
            fontWeight = FontWeight.Bold,
            fontSize = 10.5.sp,
        )
        Text(blurb, color = Color(0xFF6B7392), fontSize = 8.sp)
    }
}

@Composable
private fun StepArrow(glyph: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(30.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF2A2E3F))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, color = Color(0xFF9DB4F0), fontWeight = FontWeight.Bold, fontSize = 15.sp)
    }
}

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
            .padding(vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = fg, fontWeight = FontWeight.Bold, fontSize = 12.sp)
    }
}

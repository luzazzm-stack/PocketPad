package com.pocketpad.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp

/** A small QR-code glyph for the scan button: three finder squares + data dots. */
@Composable
fun QrIcon(size: Dp, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val u = this.size.width / 24f
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.8f * u)

        fun finder(x: Float, y: Float) {
            drawRoundRect(
                color = color,
                topLeft = Offset(x * u, y * u),
                size = Size(8f * u, 8f * u),
                cornerRadius = CornerRadius(1.6f * u, 1.6f * u),
                style = stroke,
            )
            drawRoundRect(
                color = color,
                topLeft = Offset((x + 2.8f) * u, (y + 2.8f) * u),
                size = Size(2.4f * u, 2.4f * u),
                cornerRadius = CornerRadius(0.6f * u, 0.6f * u),
            )
        }
        finder(1f, 1f)      // top-left
        finder(15f, 1f)     // top-right
        finder(1f, 15f)     // bottom-left

        // data dots in the free quadrant
        val dots = listOf(13f to 13f, 17f to 13f, 21f to 15f, 15f to 17f, 19f to 19f, 13f to 21f, 21f to 21f)
        for ((dx, dy) in dots) {
            drawRoundRect(
                color = color,
                topLeft = Offset(dx * u, dy * u),
                size = Size(2.2f * u, 2.2f * u),
                cornerRadius = CornerRadius(0.5f * u, 0.5f * u),
            )
        }
    }
}

/** The Control Cross app mark, drawn at any size (matches the launcher icon). */
@Composable
fun PadIcon(size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val u = this.size.width / 108f
        drawRoundRect(
            color = Color(0xFF1E2130),
            cornerRadius = CornerRadius(24 * u, 24 * u),
        )
        val grey = Color(0xFFC6CEE8)
        drawRoundRect(
            color = grey,
            topLeft = Offset(30 * u, 38 * u),
            size = Size(11.5f * u, 32 * u),
            cornerRadius = CornerRadius(4 * u, 4 * u),
        )
        drawRoundRect(
            color = grey,
            topLeft = Offset(20.25f * u, 47.75f * u),
            size = Size(31 * u, 12.5f * u),
            cornerRadius = CornerRadius(4 * u, 4 * u),
        )
        drawCircle(Color(0xFFE0AF68), 6.4f * u, Offset(76 * u, 42 * u))
        drawCircle(Color(0xFFF7768E), 6.4f * u, Offset(88 * u, 54 * u))
        drawCircle(Color(0xFF9ECE6A), 6.4f * u, Offset(76 * u, 66 * u))
        drawCircle(Color(0xFF8FB4FF), 6.4f * u, Offset(64 * u, 54 * u))
    }
}

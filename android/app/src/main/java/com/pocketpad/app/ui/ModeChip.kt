package com.pocketpad.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class ModeIcon { Mouse, Gamepad }

/**
 * Small pill that swaps the phone between gamepad and trackpad.
 * Icons are drawn rather than shipped as assets — two shapes isn't worth a
 * dependency, and vectors stay crisp at any density.
 */
@Composable
fun ModeChip(
    label: String,
    icon: ModeIcon,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(Color(0xFF2F3549))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Canvas(Modifier.size(15.dp)) {
            when (icon) {
                ModeIcon.Mouse -> drawMouse()
                ModeIcon.Gamepad -> drawGamepad()
            }
        }
        Text(
            label,
            color = Color(0xFFC0CAF5),
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp,
        )
    }
}

private fun DrawScope.drawMouse() {
    val c = Color(0xFFC0CAF5)
    val w = size.width * 0.66f
    val h = size.height
    val left = (size.width - w) / 2f
    val stroke = Stroke(width = size.width * 0.1f)
    // body
    drawRoundRect(
        color = c,
        topLeft = Offset(left, 0f),
        size = Size(w, h),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(w / 2f, w / 2f),
        style = stroke,
    )
    // scroll wheel
    drawLine(
        color = c,
        start = Offset(size.width / 2f, h * 0.16f),
        end = Offset(size.width / 2f, h * 0.38f),
        strokeWidth = size.width * 0.1f,
    )
}

private fun DrawScope.drawGamepad() {
    val c = Color(0xFFC0CAF5)
    val h = size.height * 0.62f
    val top = (size.height - h) / 2f
    drawRoundRect(
        color = c,
        topLeft = Offset(0f, top),
        size = Size(size.width, h),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(h / 2.4f, h / 2.4f),
        style = Stroke(width = size.width * 0.11f),
    )
    val cy = size.height / 2f
    // d-pad
    drawLine(c, Offset(size.width * 0.2f, cy), Offset(size.width * 0.38f, cy), size.width * 0.1f)
    drawLine(c, Offset(size.width * 0.29f, cy - h * 0.16f), Offset(size.width * 0.29f, cy + h * 0.16f), size.width * 0.1f)
    // buttons
    drawCircle(c, radius = size.width * 0.065f, center = Offset(size.width * 0.72f, cy - h * 0.13f))
    drawCircle(c, radius = size.width * 0.065f, center = Offset(size.width * 0.72f, cy + h * 0.13f))
}

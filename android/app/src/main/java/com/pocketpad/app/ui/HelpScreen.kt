package com.pocketpad.app.ui

import android.annotation.SuppressLint
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * The bundled user manual (assets/manual.html, copied from docs/ at build
 * time) rendered in a WebView. Fully offline — every image is embedded.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun HelpScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ModeChip(label = "Back", icon = ModeIcon.Gamepad, onClick = onBack)
            Text(
                "HOW TO USE",
                color = Color(0xFF7AA2F7),
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
                fontSize = 13.sp,
                modifier = Modifier.padding(start = 16.dp),
            )
        }
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = false // manual is static HTML
                    setBackgroundColor(0xFF16171F.toInt())
                    loadUrl("file:///android_asset/manual.html")
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )
    }
}

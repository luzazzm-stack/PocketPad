package com.pocketpad.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Box
import com.pocketpad.app.haptics.LocalHaptics
import com.pocketpad.app.settings.AppSettings

/** Settings: layout customizer entry, haptics, trackpad speed. */
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onChange: (AppSettings) -> Unit,
    onEditLayout: () -> Unit,
    onHelp: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 40.dp, vertical = 14.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ModeChip(label = "Back", icon = ModeIcon.Gamepad, onClick = onBack)
            Text(
                "SETTINGS",
                color = Color(0xFF7AA2F7),
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
                fontSize = 13.sp,
                modifier = Modifier.padding(start = 16.dp),
            )
        }

        SettingCard(
            title = "Customize layout",
            subtitle = "Drag the controls where your thumbs want them, and resize them",
            trailing = { Text("›", color = Color(0xFF6B7392), fontSize = 22.sp) },
            onClick = onEditLayout,
        )

        SettingCard(
            title = "Vibrate on press",
            subtitle = "A small tick every time a button registers",
            trailing = {
                Switch(
                    checked = settings.haptics,
                    onCheckedChange = { onChange(settings.copy(haptics = it)) },
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = Color(0xFF7AA2F7),
                        checkedThumbColor = Color(0xFF14151D),
                    ),
                )
            },
            below = if (!settings.haptics) null else {
                {
                    val haptics = LocalHaptics.current
                    Row(
                        Modifier.fillMaxWidth().padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Slider(
                            value = settings.hapticPercent.toFloat(),
                            onValueChange = {
                                onChange(settings.copy(hapticPercent = it.toInt()))
                            },
                            onValueChangeFinished = {
                                // preview the power you just chose
                                haptics.percent = settings.hapticPercent
                                haptics.tick()
                            },
                            valueRange = 0f..100f,
                            colors = SliderDefaults.colors(
                                thumbColor = Color(0xFF7AA2F7),
                                activeTrackColor = Color(0xFF7AA2F7),
                                inactiveTrackColor = Color(0xFF31354A),
                            ),
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "${settings.hapticPercent}%",
                            color = Color(0xFF98A2C0),
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(start = 12.dp).width(44.dp),
                        )
                    }
                }
            },
        )

        SettingCard(
            title = "How to use PocketPad",
            subtitle = "The full guide — connecting, the buttons, mouse mode, fixes",
            trailing = { Text("?", color = Color(0xFF7AA2F7), fontSize = 20.sp,
                fontWeight = FontWeight.Bold) },
            onClick = onHelp,
        )

        SettingCard(
            title = "Mouse speed",
            subtitle = "How fast the pointer moves in mouse mode",
            trailing = {},
            below = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("slow", color = Color(0xFF4E5470), fontSize = 10.sp)
                    Slider(
                        value = settings.mouseSensitivity,
                        onValueChange = { onChange(settings.copy(mouseSensitivity = it)) },
                        valueRange = 0.5f..3f,
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFF7AA2F7),
                            activeTrackColor = Color(0xFF7AA2F7),
                            inactiveTrackColor = Color(0xFF31354A),
                        ),
                        modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                    )
                    Text("fast", color = Color(0xFF4E5470), fontSize = 10.sp)
                }
            },
        )
    }
}

@Composable
private fun SettingCard(
    title: String,
    subtitle: String,
    trailing: @Composable () -> Unit,
    onClick: (() -> Unit)? = null,
    below: (@Composable () -> Unit)? = null,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(Color(0xFF1B1D26))
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 18.dp, vertical = 13.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = Color(0xFFC6CEE8), fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp)
                Text(subtitle, color = Color(0xFF6B7392), fontSize = 11.5.sp,
                    modifier = Modifier.padding(top = 2.dp))
            }
            Box(Modifier.width(70.dp), contentAlignment = Alignment.CenterEnd) { trailing() }
        }
        below?.invoke()
    }
}

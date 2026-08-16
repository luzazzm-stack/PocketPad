package com.pocketpad.app

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.pocketpad.app.transport.PadConnection
import com.pocketpad.app.ui.GamepadScreen
import com.pocketpad.app.ui.TrackpadScreen

private val PadColors = darkColorScheme(
    primary = Color(0xFF7AA2F7),
    background = Color(0xFF1A1B26),
    surface = Color(0xFF24283B),
)

sealed interface UiState {
    data class Idle(val error: String? = null) : UiState
    data object Connecting : UiState
    data class Playing(
        val connection: PadConnection,
        val latencyMs: Long?,
        val mode: PadConnection.Mode = PadConnection.Mode.PAD,
    ) : UiState
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            MaterialTheme(colorScheme = PadColors) {
                // Immersive fullscreen while playing
                val view = LocalView.current
                WindowCompat.getInsetsController(window, view).apply {
                    hide(WindowInsetsCompat.Type.systemBars())
                    systemBarsBehavior =
                        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }

                var ui by remember { mutableStateOf<UiState>(UiState.Idle()) }

                when (val s = ui) {
                    is UiState.Idle -> ConnectScreen(s.error) { host, token ->
                        ui = UiState.Connecting
                        val name = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
                        val conn = PadConnection(host.trim(), token.trim(), name)
                        conn.start { event ->
                            runOnUiThread {
                                ui = when (event) {
                                    is PadConnection.Event.Connected ->
                                        UiState.Playing(conn, null)
                                    is PadConnection.Event.Latency ->
                                        (ui as? UiState.Playing)?.copy(latencyMs = event.rttMs) ?: ui
                                    is PadConnection.Event.Rejected ->
                                        UiState.Idle("Rejected: ${event.reason}")
                                    is PadConnection.Event.Disconnected ->
                                        UiState.Idle(event.error?.let { "Connection lost: $it" })
                                }
                            }
                        }
                    }
                    is UiState.Connecting -> Centered { Text("Connecting…") }
                    is UiState.Playing -> {
                        fun switchTo(m: PadConnection.Mode) {
                            s.connection.setMode(m)
                            ui = s.copy(mode = m)
                        }
                        when (s.mode) {
                            PadConnection.Mode.PAD -> GamepadScreen(
                                connection = s.connection,
                                latencyMs = s.latencyMs,
                                onSwitchToMouse = { switchTo(PadConnection.Mode.MOUSE) },
                            )
                            PadConnection.Mode.MOUSE -> TrackpadScreen(
                                connection = s.connection,
                                latencyMs = s.latencyMs,
                                onSwitchToPad = { switchTo(PadConnection.Mode.PAD) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}

@Composable
private fun ConnectScreen(error: String?, onConnect: (host: String, token: String) -> Unit) {
    var host by rememberSaveable { mutableStateOf("192.168.") }
    var token by rememberSaveable { mutableStateOf("") }

    Centered {
        Text("PocketPad", style = MaterialTheme.typography.headlineLarge)
        Text(
            "Start PocketPad Link on your PC, then enter what it shows",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
        )
        OutlinedTextField(
            value = host,
            onValueChange = { host = it },
            label = { Text("PC address (host=…)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(0.5f),
        )
        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            label = { Text("Code (token=…)") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth(0.5f)
                .padding(top = 8.dp),
        )
        Button(
            onClick = { onConnect(host, token) },
            enabled = host.isNotBlank() && token.isNotBlank(),
            modifier = Modifier.padding(top = 16.dp),
        ) { Text("Connect") }

        if (error != null) {
            Text(
                error,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

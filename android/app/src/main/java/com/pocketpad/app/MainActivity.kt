package com.pocketpad.app

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.pocketpad.app.settings.AppSettings
import com.pocketpad.app.settings.SettingsStore
import com.pocketpad.app.transport.PadConnection
import com.pocketpad.app.ui.GamepadScreen
import com.pocketpad.app.ui.HelpScreen
import com.pocketpad.app.ui.LayoutEditScreen
import com.pocketpad.app.ui.PadIcon
import com.pocketpad.app.ui.QrIcon
import com.pocketpad.app.ui.SettingsScreen
import com.pocketpad.app.ui.TrackpadScreen
import androidx.activity.compose.rememberLauncherForActivityResult

private val PadColors = darkColorScheme(
    primary = Color(0xFF7AA2F7),
    background = Color(0xFF16161E),
    surface = Color(0xFF1B1D26),
)

enum class Screen { PLAY, SETTINGS, EDIT_LAYOUT }

sealed interface UiState {
    data class Idle(val error: String? = null) : UiState
    data object Connecting : UiState
    data class Playing(
        val connection: PadConnection,
        val latencyMs: Long?,
        val mode: PadConnection.Mode = PadConnection.Mode.PAD,
        val screen: Screen = Screen.PLAY,
    ) : UiState
}

/** Turn wire-level failures into instructions a person can follow. */
private fun friendlyError(kind: String, detail: String?): String = when {
    kind == "rejected" && detail == "token" ->
        "That code has expired. Read the new one off the PC window and try again."
    kind == "rejected" && detail == "busy" ->
        "Another phone is already connected to this PC."
    kind == "rejected" && detail == "version" ->
        "The PC app is a different version. Update both apps."
    kind == "failed" ->
        "Couldn't find the PC. Check both are on the same Wi-Fi, and re-read the address."
    else -> "Connection lost. Open the PC app and connect again."
}

class MainActivity : ComponentActivity() {

    private val prefs by lazy { getSharedPreferences("pocketpad", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            MaterialTheme(colorScheme = PadColors) {
                val view = LocalView.current
                WindowCompat.getInsetsController(window, view).apply {
                    hide(WindowInsetsCompat.Type.systemBars())
                    systemBarsBehavior =
                        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }

                var ui by remember { mutableStateOf<UiState>(UiState.Idle()) }
                var showHelp by remember { mutableStateOf(false) }
                val store = remember { SettingsStore(prefs) }
                var settings by remember { mutableStateOf(store.load()) }

                fun updateSettings(s: AppSettings) {
                    settings = s
                    store.save(s)
                }

                fun connect(host: String, token: String) {
                    ui = UiState.Connecting
                    prefs.edit().putString("host", host).putString("token", token).apply()
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
                                    UiState.Idle(friendlyError("rejected", event.reason))
                                is PadConnection.Event.Disconnected ->
                                    if (ui is UiState.Connecting)
                                        UiState.Idle(friendlyError("failed", event.error))
                                    else
                                        UiState.Idle(event.error?.let { friendlyError("lost", it) })
                            }
                        }
                    }
                }

                if (showHelp) {
                    HelpScreen(onBack = { showHelp = false })
                    return@MaterialTheme
                }

                when (val s = ui) {
                    is UiState.Idle -> ConnectScreen(
                        error = s.error,
                        lastHost = prefs.getString("host", "") ?: "",
                        lastToken = prefs.getString("token", "") ?: "",
                        onConnect = ::connect,
                        onHelp = { showHelp = true },
                    )
                    is UiState.Connecting -> Box(
                        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
                        contentAlignment = Alignment.Center,
                    ) { Text("Connecting…", color = Color(0xFF98A2C0)) }
                    is UiState.Playing -> {
                        fun switchTo(m: PadConnection.Mode) {
                            s.connection.setMode(m)
                            ui = s.copy(mode = m)
                        }
                        when (s.screen) {
                            Screen.SETTINGS -> SettingsScreen(
                                settings = settings,
                                onChange = ::updateSettings,
                                onEditLayout = { ui = s.copy(screen = Screen.EDIT_LAYOUT) },
                                onHelp = { showHelp = true },
                                onBack = { ui = s.copy(screen = Screen.PLAY) },
                            )
                            Screen.EDIT_LAYOUT -> LayoutEditScreen(
                                initial = settings.layout,
                                onSave = { newLayout ->
                                    updateSettings(settings.copy(layout = newLayout))
                                    ui = s.copy(screen = Screen.PLAY)
                                },
                                onCancel = { ui = s.copy(screen = Screen.SETTINGS) },
                            )
                            Screen.PLAY -> when (s.mode) {
                                PadConnection.Mode.PAD -> GamepadScreen(
                                    connection = s.connection,
                                    latencyMs = s.latencyMs,
                                    settings = settings,
                                    onSwitchToMouse = { switchTo(PadConnection.Mode.MOUSE) },
                                    onOpenSettings = { ui = s.copy(screen = Screen.SETTINGS) },
                                )
                                PadConnection.Mode.MOUSE -> TrackpadScreen(
                                    connection = s.connection,
                                    latencyMs = s.latencyMs,
                                    settings = settings,
                                    onSwitchToPad = { switchTo(PadConnection.Mode.PAD) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectScreen(
    error: String?,
    lastHost: String,
    lastToken: String,
    onConnect: (host: String, token: String) -> Unit,
    onHelp: () -> Unit,
) {
    var host by rememberSaveable { mutableStateOf(lastHost) }
    var token by rememberSaveable { mutableStateOf(lastToken) }

    // QR pairing: pocketpad://pair?host=…&tcp=…&token=…
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        val text = result.contents ?: return@rememberLauncherForActivityResult
        runCatching {
            val uri = Uri.parse(text)
            if (uri.scheme == "pocketpad") {
                val h = uri.getQueryParameter("host")
                val t = uri.getQueryParameter("token")
                if (!h.isNullOrBlank() && !t.isNullOrBlank()) {
                    host = h; token = t
                    onConnect(h, t)
                }
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Help lives on this screen too — it's where people get stuck.
        Text(
            "?",
            color = Color(0xFF7AA2F7),
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 14.dp, end = 22.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(Color(0xFF232634))
                .clickable(onClick = onHelp)
                .padding(horizontal = 13.dp, vertical = 4.dp),
        )

    Row(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 30.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // ---- left: identity + what to do ----
        Column(Modifier.width(230.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PadIcon(size = 36.dp)
                Text(
                    "PocketPad",
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp,
                    color = Color(0xFFC6CEE8),
                    modifier = Modifier.padding(start = 10.dp),
                )
            }
            Text(
                "On your PC, open PocketPad for PC — then scan its QR code, or copy the two values it shows into these boxes.",
                color = Color(0xFF6B7392),
                fontSize = 12.sp,
                lineHeight = 18.sp,
                modifier = Modifier.padding(top = 10.dp),
            )
        }

        // ---- right: the form ----
        Column(
            Modifier
                .weight(1f)
                .padding(start = 26.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            OutlinedTextField(
                value = host,
                onValueChange = { host = it },
                label = { Text("PC address") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color(0xFFE0AF68),
                    unfocusedTextColor = Color(0xFFE0AF68),
                    focusedBorderColor = Color(0xFF7AA2F7),
                    unfocusedBorderColor = Color(0xFF31354A),
                    focusedLabelColor = Color(0xFF7AA2F7),
                    unfocusedLabelColor = Color(0xFF6B7392),
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text("Code") },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color(0xFFF787B7),
                    unfocusedTextColor = Color(0xFFF787B7),
                    focusedBorderColor = Color(0xFF7AA2F7),
                    unfocusedBorderColor = Color(0xFF31354A),
                    focusedLabelColor = Color(0xFF7AA2F7),
                    unfocusedLabelColor = Color(0xFF6B7392),
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Button(
                    onClick = { onConnect(host, token) },
                    enabled = host.isNotBlank() && token.isNotBlank(),
                    shape = RoundedCornerShape(11.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF7AA2F7),
                        contentColor = Color(0xFF14151D),
                    ),
                    modifier = Modifier.weight(1f).height(46.dp),
                ) { Text("Connect", fontWeight = FontWeight.Bold) }

                OutlinedButton(
                    onClick = {
                        scanner.launch(ScanOptions().apply {
                            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                            setPrompt("Point at the QR code on your PC screen")
                            setBeepEnabled(false)
                            setOrientationLocked(false)
                        })
                    },
                    shape = RoundedCornerShape(11.dp),
                    modifier = Modifier.size(46.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                ) { QrIcon(size = 22.dp, color = Color(0xFF98A2C0)) }
            }
            Text(
                if (lastHost.isNotBlank()) "remembered your last PC — just tap Connect"
                else "scans or types — either works",
                color = Color(0xFF4E5470),
                fontSize = 10.5.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            if (error != null) {
                Text(
                    error,
                    color = Color(0xFFF7768E),
                    fontSize = 12.5.sp,
                    lineHeight = 17.sp,
                )
            }
        }
    }
    }
}

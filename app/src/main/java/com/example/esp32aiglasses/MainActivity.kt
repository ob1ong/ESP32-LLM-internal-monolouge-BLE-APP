package com.example.esp32aiglasses

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.Locale
import kotlin.math.roundToInt

private val AppDarkColors = darkColorScheme(
    background = Color.Black,
    surface = Color.Black,
    primary = Color.White,
    onPrimary = Color.Black,
    secondary = Color.White,
    onSecondary = Color.Black,
    onBackground = Color.White,
    onSurface = Color.White
)

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNeededPermissions()

        setContent {
            MaterialTheme(colorScheme = AppDarkColors) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppScreen()
                }
            }
        }
    }

    @Suppress("InlinedApi")
    private fun requestNeededPermissions() {
        val perms = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = perms.filter { permission ->
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }
}

@Composable
fun AppScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val bleManager = remember { MonologueService.ble(context.applicationContext) }
    val voicePreview = remember { QueueingTtsHelper(context.applicationContext) }
    val scrollState = rememberScrollState()

    var apiKey by remember { mutableStateOf("") }
    var wifiSsid by remember { mutableStateOf("") }
    var wifiPassword by remember { mutableStateOf("") }
    var captureUrl by MonologueService.captureUrl
    val prefs = remember { context.getSharedPreferences("monologue_settings", Context.MODE_PRIVATE) }
    var prompt by remember { mutableStateOf(prefs.getString("prompt", "").orEmpty()) }
    var captureInterval by remember { mutableStateOf(prefs.getInt("interval", 1).coerceIn(0, 60)) }
    var responseWords by remember { mutableStateOf(prefs.getInt("words", 25).coerceIn(10, 100)) }
    var maxAhead by remember { mutableStateOf(prefs.getInt("ahead", 2).coerceIn(0, 5)) }
    var speechSpeed by remember { mutableStateOf(prefs.getFloat("speed", 1f).coerceIn(0.5f, 2f)) }
    var speechPitch by remember { mutableStateOf(prefs.getFloat("pitch", 1f).coerceIn(0.5f, 2f)) }
    var memoryCount by remember { mutableStateOf(prefs.getInt("memory", 0).coerceIn(0, 10)) }
    var onlyChanged by remember { mutableStateOf(prefs.getBoolean("only_changed", false)) }
    var discardOutdated by remember { mutableStateOf(prefs.getBoolean("discard_outdated", false)) }
    var voiceName by remember { mutableStateOf(prefs.getString("voice", "").orEmpty()) }
    var voices by remember { mutableStateOf(emptyList<String>()) }
    var voiceMenuOpen by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Not connected") }
    val running by MonologueService.running
    val lastResponse by MonologueService.lastResponse
    val currentSpokenText by MonologueService.currentSpeech

    val sessionStatus by MonologueService.status
    DisposableEffect(Unit) {
        voicePreview.setOnReady { voices = voicePreview.availableVoiceNames() }
        onDispose { voicePreview.shutdown() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(scrollState)
            .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            "ESP32 Monologue",
            style = MaterialTheme.typography.titleLarge,
            color = Color.White
        )

        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = { Text("OpenAI API Key", color = Color.White) },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
            colors = darkTextFieldColors()
        )

        OutlinedTextField(
            value = wifiSsid,
            onValueChange = { wifiSsid = it },
            label = { Text("Wi-Fi SSID", color = Color.White) },
            modifier = Modifier.fillMaxWidth(),
            colors = darkTextFieldColors()
        )

        OutlinedTextField(
            value = wifiPassword,
            onValueChange = { wifiPassword = it },
            label = { Text("Wi-Fi Password", color = Color.White) },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
            colors = darkTextFieldColors()
        )

        OutlinedTextField(
            value = prompt,
            onValueChange = { prompt = it; prefs.edit().putString("prompt", it).apply() },
            enabled = !running,
            placeholder = { Text("Optional custom instructions") },
            label = { Text("Prompt", color = Color.White) },
            modifier = Modifier.fillMaxWidth(),
            colors = darkTextFieldColors(),
            keyboardOptions = KeyboardOptions.Default
        )

        Text("Customisation", style = MaterialTheme.typography.titleMedium)
        Text("Stop the session to change these settings.", style = MaterialTheme.typography.bodySmall)
        IntegerSetting("Capture interval (seconds after each cycle)", captureInterval, 0, 60, 1, !running) {
            captureInterval = it; prefs.edit().putInt("interval", it).apply()
        }
        IntegerSetting("Response length (target words)", responseWords, 10, 100, 5, !running) {
            responseWords = it; prefs.edit().putInt("words", it).apply()
        }
        IntegerSetting("Responses loaded in advance", maxAhead, 0, 5, 1, !running) {
            maxAhead = it; prefs.edit().putInt("ahead", it).apply()
        }
        Text("0 waits for speech to finish; higher values buffer upcoming responses.", style = MaterialTheme.typography.bodySmall)
        IntegerSetting("Context memory (previous responses)", memoryCount, 0, 10, 1, !running) {
            memoryCount = it; prefs.edit().putInt("memory", it).apply()
        }
        Text("Speech speed: ${String.format(Locale.US, "%.1f", speechSpeed)}×")
        Slider(value = speechSpeed, onValueChange = {
            speechSpeed = it; prefs.edit().putFloat("speed", it).apply()
        }, valueRange = 0.5f..2f, steps = 14, enabled = !running)
        Text("Speech pitch: ${String.format(Locale.US, "%.1f", speechPitch)}×")
        Slider(value = speechPitch, onValueChange = {
            speechPitch = it; prefs.edit().putFloat("pitch", it).apply()
        }, valueRange = 0.5f..2f, steps = 14, enabled = !running)
        Button(onClick = { voiceMenuOpen = true }, enabled = !running && voices.isNotEmpty(), modifier = Modifier.fillMaxWidth(), colors = whiteButtonColors()) {
            Text(if (voiceName.isBlank()) "Voice: system default" else "Voice: $voiceName")
        }
        DropdownMenu(expanded = voiceMenuOpen, onDismissRequest = { voiceMenuOpen = false }) {
            (listOf("") + voices).forEach { name ->
                DropdownMenuItem(text = { Text(name.ifBlank { "System default" }) }, onClick = {
                    voiceName = name; prefs.edit().putString("voice", name).apply(); voiceMenuOpen = false
                })
            }
        }
        ToggleSetting("Only respond to visual changes", onlyChanged, !running) {
            onlyChanged = it; prefs.edit().putBoolean("only_changed", it).apply()
        }
        ToggleSetting("Interrupt outdated speech on visual change", discardOutdated, !running) {
            discardOutdated = it; prefs.edit().putBoolean("discard_outdated", it).apply()
        }
        Text("Experimental image-change detection; lighting and head motion can trigger it. Checks occur when a capture is permitted by the queue.", style = MaterialTheme.typography.bodySmall)
        Button(onClick = {
            captureInterval = 1; responseWords = 25; maxAhead = 2; memoryCount = 0
            speechSpeed = 1f; speechPitch = 1f; voiceName = ""; prompt = ""
            onlyChanged = false; discardOutdated = false
            prefs.edit().clear().apply()
        }, enabled = !running, modifier = Modifier.fillMaxWidth(), colors = whiteButtonColors()) { Text("Reset customisation") }

        Text(
            text = if (captureUrl.isBlank()) {
                "Capture URL: not available yet"
            } else {
                "Capture URL: $captureUrl"
            },
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium
        )

        Text("Status: $status", color = Color.White)
        Text("Session: $sessionStatus", color = Color.White)

        Button(
            onClick = {
                scope.launch {
                    try {
                        status = "Scanning..."
                        withTimeout(15_000L) {
                            bleManager.scanAndConnect("ESP32-Glasses")
                        }
                        delay(800)
                        bleManager.clearStatusMessages()
                        status = "Connected to ESP32"
                    } catch (e: Exception) {
                        status = "Connect failed: ${e.message}"
                    }
                }
            },
            enabled = !running,
            modifier = Modifier.fillMaxWidth(),
            colors = whiteButtonColors()
        ) {
            Text("Connect ESP32")
        }

        Button(
            onClick = {
                scope.launch {
                    try {
                        if (wifiSsid.isBlank()) {
                            status = "Enter Wi-Fi SSID"
                            return@launch
                        }

                        bleManager.clearStatusMessages()
                        delay(300)

                        status = "Sending Wi-Fi to ESP32..."
                        bleManager.sendCommand("SET_WIFI:${wifiSsid}|${wifiPassword}")

                        var finalUrl: String? = null
                        var gotFailure = false

                        repeat(25) {
                            val reply = try {
                                withTimeout(1500L) {
                                    bleManager.waitForStatusMessage()
                                }
                            } catch (_: Exception) {
                                bleManager.readStatusValue()
                            }

                            status = "ESP32: $reply"

                            if (reply.startsWith("WIFI_CONNECTED:")) {
                                finalUrl = reply.removePrefix("WIFI_CONNECTED:").trim()
                                return@repeat
                            }

                            if (reply.startsWith("WIFI_IP:")) {
                                val ip = reply.removePrefix("WIFI_IP:").trim()
                                finalUrl = "http://$ip/capture"
                            }

                            if (reply == "WIFI_FAILED") {
                                gotFailure = true
                                return@repeat
                            }
                        }

                        if (finalUrl != null) {
                            captureUrl = finalUrl
                            status = "Capture URL ready"
                        } else if (gotFailure) {
                            status = "Wi-Fi connection failed"
                        } else {
                            status = "Wi-Fi setup timed out"
                        }
                    } catch (e: Exception) {
                        status = "Wi-Fi setup failed: ${e.message}"
                    }
                }
            },
            enabled = !running,
            modifier = Modifier.fillMaxWidth(),
            colors = whiteButtonColors()
        ) {
            Text("Set Up ESP32 Wi-Fi")
        }

        Button(
            onClick = {
                scope.launch {
                    try {
                        bleManager.clearStatusMessages()
                        MonologueService.stop(context)
                        bleManager.sendCommand("SLEEP")
                        status = "Sleep command sent"
                    } catch (e: Exception) {
                        status = "Sleep failed: ${e.message}"
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            colors = whiteButtonColors()
        ) {
            Text("Sleep Glasses")
        }

        Button(
            onClick = {
                if (running) return@Button
                if (apiKey.isBlank()) {
                    status = "Enter API key first"
                    return@Button
                }
                if (captureUrl.isBlank()) {
                    status = "No capture URL yet"
                    return@Button
                }
                try {
                    MonologueService.start(context, SessionConfig(
                        apiKey.trim(), captureUrl, prompt, captureInterval,
                        responseWords, maxAhead, speechSpeed, speechPitch,
                        memoryCount, onlyChanged, discardOutdated, voiceName
                    ))
                    status = "Session requested"
                } catch (e: Exception) {
                    status = "Start failed: ${e.message}"
                }
            },
            modifier = Modifier.fillMaxWidth(),
            colors = whiteButtonColors()
        ) {
            Text("Start")
        }

        Button(
            onClick = {
                MonologueService.stop(context)
                status = "Stopped"
            },
            modifier = Modifier.fillMaxWidth(),
            colors = whiteButtonColors()
        ) {
            Text("Stop")
        }

        Button(
            onClick = {
                MonologueService.stop(context)
                bleManager.disconnect()
                captureUrl = ""
                status = "Disconnected"
            },
            modifier = Modifier.fillMaxWidth(),
            colors = whiteButtonColors()
        ) {
            Text("Disconnect")
        }

        Text(
            text = if (currentSpokenText.isBlank()) {
                "Currently speaking: nothing yet"
            } else {
                "Currently speaking: $currentSpokenText"
            },
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
        )

        Text(
            text = if (lastResponse.isBlank()) {
                "Last response: nothing yet"
            } else {
                "Last response: $lastResponse"
            },
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp)
        )
    }
}

@Composable
private fun IntegerSetting(label: String, value: Int, min: Int, max: Int, step: Int, enabled: Boolean, onChange: (Int) -> Unit) {
    Text("$label: $value")
    Slider(value = value.toFloat(), onValueChange = { onChange((min + ((it - min) / step).roundToInt() * step).coerceIn(min, max)) },
        valueRange = min.toFloat()..max.toFloat(), steps = (max - min) / step - 1, enabled = enabled)
}

@Composable
private fun ToggleSetting(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun darkTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    focusedBorderColor = Color.White,
    unfocusedBorderColor = Color.White,
    focusedLabelColor = Color.White,
    unfocusedLabelColor = Color.White,
    cursorColor = Color.White,
    focusedContainerColor = Color.Black,
    unfocusedContainerColor = Color.Black
)

@Composable
private fun whiteButtonColors() = ButtonDefaults.buttonColors(
    containerColor = Color.White,
    contentColor = Color.Black
)

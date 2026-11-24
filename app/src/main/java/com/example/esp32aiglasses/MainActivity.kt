package com.example.esp32aiglasses

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
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
    val bleManager = remember { BleManager(context) }
    val openAiClient = remember { OpenAiClient() }
    val ttsSpeaker = remember { QueueingTtsHelper(context) }
    val scrollState = rememberScrollState()

    var apiKey by remember { mutableStateOf("") }
    var wifiSsid by remember { mutableStateOf("") }
    var wifiPassword by remember { mutableStateOf("") }
    var captureUrl by remember { mutableStateOf("") }
    val prefs = remember { context.getSharedPreferences("monologue_settings", Context.MODE_PRIVATE) }
    var prompt by remember { mutableStateOf(prefs.getString("prompt", "").orEmpty()) }
    var captureInterval by remember { mutableStateOf(prefs.getInt("interval", 1).coerceIn(1, 60)) }
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
    var sessionJob by remember { mutableStateOf<Job?>(null) }
    var status by remember { mutableStateOf("Not connected") }
    var running by remember { mutableStateOf(false) }
    var lastResponse by remember { mutableStateOf("") }
    var currentSpokenText by remember { mutableStateOf("") }

    DisposableEffect(Unit) {
        ttsSpeaker.setOnCurrentSpeechChanged { spoken ->
            currentSpokenText = spoken
        }

        ttsSpeaker.setOnReady { voices = ttsSpeaker.availableVoiceNames() }
        onDispose {
            sessionJob?.cancel()
            ttsSpeaker.shutdown()
            bleManager.disconnect()
        }
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
        IntegerSetting("Capture interval (seconds after each cycle)", captureInterval, 1, 60, 1, !running) {
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
                        sessionJob?.cancel()
                        ttsSpeaker.stopSpeaking()
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
                if (running || sessionJob?.isActive == true) return@Button

                if (apiKey.isBlank()) {
                    status = "Enter API key first"
                    return@Button
                }

                if (captureUrl.isBlank()) {
                    status = "No capture URL yet"
                    return@Button
                }

                running = true
                status = "Starting..."
                lastResponse = ""

                sessionJob = scope.launch {
                    try {
                        if (!ttsSpeaker.awaitReady()) {
                            status = "TTS failed to initialize"
                            return@launch
                        }
                        ttsSpeaker.configure(speechSpeed, speechPitch, voiceName)
                        val history = ArrayDeque<String>()
                        var previousScene: IntArray? = null
                        while (isActive && running) {
                            status = "Waiting for speech backlog..."
                            ttsSpeaker.waitForSlot(maxAhead)
                            ensureActive()
                            status = "Fetching image..."
                            val imageBytes = withContext(Dispatchers.IO) { fetchImage(captureUrl) }
                            val processedImageBytes = withContext(Dispatchers.Default) {
                                rotateRightAndFlipVerticalAxis(imageBytes)
                            }
                            if (onlyChanged || discardOutdated) {
                                val scene = withContext(Dispatchers.Default) { sceneSignature(processedImageBytes) }
                                val prior = previousScene
                                val changed = prior == null || sceneDifference(prior, scene) >= 20.0
                                if (onlyChanged && !changed) {
                                    status = "Scene unchanged"
                                    delay(captureInterval * 1000L)
                                    continue
                                }
                                if (discardOutdated && prior != null && changed) {
                                    ttsSpeaker.stopSpeaking()
                                    history.clear()
                                }
                                previousScene = scene
                            }
                            status = "Analyzing image..."
                            val instruction = buildAnalysisPrompt(prompt, responseWords, history.toList())
                            val text = withContext(Dispatchers.IO) {
                                openAiClient.analyzeImage(apiKey.trim(), instruction, processedImageBytes, responseWords)
                            }
                            ensureActive()
                            lastResponse = text
                            if (memoryCount > 0) {
                                history.addLast(text)
                                while (history.size > memoryCount) history.removeFirst()
                            }
                            status = "Speaking..."
                            ttsSpeaker.speakQueued(text)
                            delay(captureInterval * 1000L)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        status = "Loop failed: ${e.message}"
                    } finally {
                        ttsSpeaker.stopSpeaking()
                        running = false
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            colors = whiteButtonColors()
        ) {
            Text("Start")
        }

        Button(
            onClick = {
                sessionJob?.cancel()
                ttsSpeaker.stopSpeaking()
                status = "Stopped"
            },
            modifier = Modifier.fillMaxWidth(),
            colors = whiteButtonColors()
        ) {
            Text("Stop")
        }

        Button(
            onClick = {
                sessionJob?.cancel()
                ttsSpeaker.stopSpeaking()
                bleManager.disconnect()
                captureUrl = ""
                currentSpokenText = ""
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

private fun fetchImage(urlString: String): ByteArray {
    val url = URL(urlString)
    val connection = (url.openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 10000
        readTimeout = 15000
        doInput = true
    }

    try {
        connection.connect()
        if (connection.responseCode !in 200..299) {
            throw Exception("HTTP ${connection.responseCode}")
        }
        connection.inputStream.use { input ->
            val buffer = ByteArray(8192)
            val output = ByteArrayOutputStream()
            while (true) {
                val count = input.read(buffer)
                if (count == -1) break
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        }
    } finally {
        connection.disconnect()
    }
}

private fun rotateRightAndFlipVerticalAxis(imageBytes: ByteArray): ByteArray {
    val original = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
        ?: throw IllegalStateException("Failed to decode image")

    val matrix = Matrix().apply {
        postRotate(90f)
        postScale(-1f, 1f)
    }

    val transformed = Bitmap.createBitmap(
        original,
        0,
        0,
        original.width,
        original.height,
        matrix,
        true
    )

    val output = ByteArrayOutputStream()
    transformed.compress(Bitmap.CompressFormat.JPEG, 95, output)

    if (transformed != original) {
        original.recycle()
    }
    transformed.recycle()

    return output.toByteArray()
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

private fun sceneSignature(bytes: ByteArray): IntArray {
    val original = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("Invalid image")
    val small = Bitmap.createScaledBitmap(original, 16, 16, true)
    val pixels = IntArray(256)
    small.getPixels(pixels, 0, 16, 0, 0, 16, 16)
    if (small != original) small.recycle()
    original.recycle()
    return pixels.map { pixel ->
        (android.graphics.Color.red(pixel) * 299 + android.graphics.Color.green(pixel) * 587 + android.graphics.Color.blue(pixel) * 114) / 1000
    }.toIntArray()
}

private class QueueingTtsHelper(context: Context) {
    private val ready = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val tts: TextToSpeech
    private var initialized = false
    private var closed = false
    private var defaultVoice: android.speech.tts.Voice? = null
    private val utteranceTexts = ConcurrentHashMap<String, String>()
    private var onCurrentSpeechChanged: ((String) -> Unit)? = null
    private var onReady: (() -> Unit)? = null
    private var currentId: String? = null

    init {
        tts = TextToSpeech(context) { status ->
            // Post so the TTS field is assigned before handling the callback.
            mainHandler.post { finishInit(status) }
        }
    }

    private fun finishInit(status: Int) {
        if (closed) return
        ready.set(status == TextToSpeech.SUCCESS)
        if (ready.get()) {
            tts.language = Locale.getDefault()
            defaultVoice = tts.voice
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    mainHandler.post {
                        if (utteranceId != null && utteranceTexts.containsKey(utteranceId)) {
                            currentId = utteranceId
                            onCurrentSpeechChanged?.invoke(utteranceTexts[utteranceId].orEmpty())
                        }
                    }
                }
                override fun onDone(utteranceId: String?) { finishSpeech(utteranceId) }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { finishSpeech(utteranceId) }
                override fun onError(utteranceId: String?, errorCode: Int) { finishSpeech(utteranceId) }
                override fun onStop(utteranceId: String?, interrupted: Boolean) { finishSpeech(utteranceId) }
            })
        }
        initialized = true
        if (ready.get()) onReady?.invoke()
    }

    private fun finishSpeech(id: String?) {
        mainHandler.post {
            if (id != null && utteranceTexts.remove(id) != null && currentId == id) {
                currentId = null
                onCurrentSpeechChanged?.invoke("")
            }
        }
    }

    fun setOnCurrentSpeechChanged(listener: (String) -> Unit) { onCurrentSpeechChanged = listener }
    fun setOnReady(listener: () -> Unit) {
        onReady = listener
        if (ready.get()) listener()
    }
    fun availableVoiceNames(): List<String> = if (ready.get()) tts.voices.orEmpty().map { it.name }.sorted() else emptyList()

    suspend fun awaitReady(): Boolean {
        repeat(50) {
            if (initialized) return ready.get()
            delay(100L)
        }
        return ready.get()
    }

    fun configure(speed: Float, pitch: Float, voiceName: String) {
        tts.setSpeechRate(speed)
        tts.setPitch(pitch)
        val selected = tts.voices.orEmpty().firstOrNull { it.name == voiceName } ?: defaultVoice
        if (selected != null) tts.voice = selected
    }

    fun speakQueued(text: String) {
        if (!ready.get() || closed) return
        val id = UUID.randomUUID().toString()
        utteranceTexts[id] = text
        if (tts.speak(text, TextToSpeech.QUEUE_ADD, null, id) == TextToSpeech.ERROR) {
            utteranceTexts.remove(id)
            error("Text-to-speech rejected the response")
        }
    }

    suspend fun waitForSlot(maxPendingAhead: Int) {
        while (!hasResponseSlot(utteranceTexts.size, maxPendingAhead)) delay(100L)
    }

    fun stopSpeaking() {
        utteranceTexts.clear()
        currentId = null
        if (!closed && ready.get()) tts.stop()
        onCurrentSpeechChanged?.invoke("")
    }

    fun shutdown() {
        stopSpeaking()
        closed = true
        ready.set(false)
        tts.shutdown()
        onReady = null
        onCurrentSpeechChanged = null
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
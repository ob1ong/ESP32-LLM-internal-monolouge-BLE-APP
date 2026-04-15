package com.example.esp32aiglasses

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Build
import android.os.Bundle
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
import java.util.concurrent.atomic.AtomicInteger

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
    var prompt by remember {
        mutableStateOf(
            "Respond as if you were my internal monologue in the first person. One or two concrete sentences only. Don't be repetitive between responses. Don't start with I."
        )
    }
    var status by remember { mutableStateOf("Not connected") }
    var running by remember { mutableStateOf(false) }
    var lastResponse by remember { mutableStateOf("") }
    var currentSpokenText by remember { mutableStateOf("") }

    DisposableEffect(Unit) {
        ttsSpeaker.setOnCurrentSpeechChanged { spoken ->
            currentSpokenText = spoken
        }

        onDispose {
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
            onValueChange = { prompt = it },
            label = { Text("Prompt", color = Color.White) },
            modifier = Modifier.fillMaxWidth(),
            colors = darkTextFieldColors(),
            keyboardOptions = KeyboardOptions.Default
        )

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

                running = true
                status = "Starting..."
                lastResponse = ""

                scope.launch {
                    val speakerReady = ttsSpeaker.awaitReady()
                    if (!speakerReady) {
                        status = "TTS failed to initialize"
                        running = false
                        return@launch
                    }

                    val loopAlive = AtomicBoolean(true)
                    val maxPendingAhead = 2

                    try {
                        while (isActive && loopAlive.get() && running) {
                            try {
                                status = "Waiting for speech backlog..."
                                ttsSpeaker.waitUntilBelow(maxPendingAhead)

                                status = "Fetching image..."
                                val imageBytes = withContext(Dispatchers.IO) {
                                    fetchImage(captureUrl)
                                }

                                val processedImageBytes = withContext(Dispatchers.Default) {
                                    rotateRightAndFlipVerticalAxis(imageBytes)
                                }

                                status = "Analyzing image..."
                                val text = withContext(Dispatchers.IO) {
                                    openAiClient.analyzeImage(
                                        apiKey = apiKey.trim(),
                                        prompt = prompt.trim(),
                                        imageBytes = processedImageBytes
                                    )
                                }

                                lastResponse = text
                                status = "Speaking..."
                                ttsSpeaker.speakQueued(text)

                                delay(1000L)
                            } catch (e: Exception) {
                                status = "Loop failed: ${e.message}"
                                loopAlive.set(false)
                            }
                        }
                    } finally {
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
                running = false
                status = "Stopping..."
            },
            modifier = Modifier.fillMaxWidth(),
            colors = whiteButtonColors()
        ) {
            Text("Stop")
        }

        Button(
            onClick = {
                running = false
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

private class QueueingTtsHelper(context: Context) {
    private val ready = AtomicBoolean(false)
    private val tts: TextToSpeech

    @Volatile
    private var initialized = false

    private val pendingUtterances = AtomicInteger(0)
    private val utteranceTexts = ConcurrentHashMap<String, String>()
    private var onCurrentSpeechChanged: ((String) -> Unit)? = null

    init {
        tts = TextToSpeech(context) { status ->
            ready.set(status == TextToSpeech.SUCCESS)
            if (ready.get()) {
                tts.language = Locale.getDefault()
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        if (utteranceId != null) {
                            val text = utteranceTexts[utteranceId] ?: ""
                            onCurrentSpeechChanged?.invoke(text)
                        }
                    }

                    override fun onDone(utteranceId: String?) {
                        if (utteranceId != null) {
                            utteranceTexts.remove(utteranceId)
                        }
                        pendingUtterances.updateAndGet { current ->
                            if (current > 0) current - 1 else 0
                        }
                        if (pendingUtterances.get() == 0) {
                            onCurrentSpeechChanged?.invoke("")
                        }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        if (utteranceId != null) {
                            utteranceTexts.remove(utteranceId)
                        }
                        pendingUtterances.updateAndGet { current ->
                            if (current > 0) current - 1 else 0
                        }
                        if (pendingUtterances.get() == 0) {
                            onCurrentSpeechChanged?.invoke("")
                        }
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        if (utteranceId != null) {
                            utteranceTexts.remove(utteranceId)
                        }
                        pendingUtterances.updateAndGet { current ->
                            if (current > 0) current - 1 else 0
                        }
                        if (pendingUtterances.get() == 0) {
                            onCurrentSpeechChanged?.invoke("")
                        }
                    }
                })
            }
            initialized = true
        }
    }

    fun setOnCurrentSpeechChanged(listener: (String) -> Unit) {
        onCurrentSpeechChanged = listener
    }

    suspend fun awaitReady(): Boolean {
        repeat(50) {
            if (initialized) return ready.get()
            delay(100L)
        }
        return ready.get()
    }

    fun speakQueued(text: String) {
        if (!ready.get()) return
        val utteranceId = UUID.randomUUID().toString()
        utteranceTexts[utteranceId] = text
        pendingUtterances.incrementAndGet()
        tts.speak(
            text,
            TextToSpeech.QUEUE_ADD,
            null,
            utteranceId
        )
    }

    suspend fun waitUntilBelow(maxPendingAhead: Int) {
        val maxTotalPending = maxPendingAhead + 1
        while (pendingUtterances.get() > maxTotalPending) {
            delay(250L)
        }
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
        pendingUtterances.set(0)
        utteranceTexts.clear()
        onCurrentSpeechChanged?.invoke("")
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
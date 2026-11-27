package com.example.esp32aiglasses

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

internal data class SessionConfig(
    val apiKey: String, val captureUrl: String, val prompt: String,
    val captureInterval: Int, val responseWords: Int, val maxAhead: Int,
    val speechSpeed: Float, val speechPitch: Float, val memoryCount: Int,
    val onlyChanged: Boolean, val discardOutdated: Boolean, val voiceName: String, val imageRotation: Int = 90, val imageMirror: Boolean = true
)

class MonologueService : Service() {
    companion object {
        private const val CHANNEL = "monologue_session"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_START = "com.example.esp32aiglasses.START"
        private const val ACTION_STOP = "com.example.esp32aiglasses.STOP"
        private var pending: SessionConfig? = null
        private var bleManager: BleManager? = null
        internal val running = mutableStateOf(false)
        internal val status = mutableStateOf("Stopped")
        internal val lastResponse = mutableStateOf("")
        internal val currentSpeech = mutableStateOf("")
        internal val captureUrl = mutableStateOf("")

        // Main-thread access only. Credentials are not written to disk.
        internal fun ble(context: Context): BleManager =
            bleManager ?: BleManager(context.applicationContext).also { bleManager = it }

        internal fun start(context: Context, config: SessionConfig) {
            if (running.value) return
            pending = config
            running.value = true
            status.value = "Starting..."
            lastResponse.value = ""
            try {
                ContextCompat.startForegroundService(context,
                    Intent(context, MonologueService::class.java).setAction(ACTION_START))
            } catch (e: Exception) {
                pending = null
                running.value = false
                status.value = "Start failed: ${e.message}"
                throw e
            }
        }

        internal fun stop(context: Context) {
            pending = null
            status.value = "Stopped"
            if (!context.stopService(Intent(context, MonologueService::class.java))) {
                running.value = false
                currentSpeech.value = ""
            }
            // onDestroy releases resources for an active service.
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var speaker: QueueingTtsHelper? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val client = OpenAiClient()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            status.value = "Stopped"
            stopSelf()
            return START_NOT_STICKY
        }
        if (job != null) return START_NOT_STICKY
        val config = pending
        pending = null
        if (config == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL,
                "Monologue session", NotificationManager.IMPORTANCE_LOW))
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            val power = getSystemService(PowerManager::class.java)
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Monologue:Session").apply {
                setReferenceCounted(false)
                acquire()
            }
            val tts = QueueingTtsHelper(applicationContext)
            speaker = tts
            tts.setOnCurrentSpeechChanged { currentSpeech.value = it }
            job = scope.launch { runSession(config, tts) }
        } catch (e: Exception) {
            status.value = "Service failed: ${e.message}"
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1,
            Intent(this, MonologueService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Monologue is running")
            .setContentText("Camera analysis and speech active")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    private suspend fun runSession(c: SessionConfig, ttsSpeaker: QueueingTtsHelper) {
        try {
            if (!ttsSpeaker.awaitReady()) error("TTS failed to initialize")
            ttsSpeaker.configure(c.speechSpeed, c.speechPitch, c.voiceName)
            val history = ArrayDeque<String>()
            var previousScene: IntArray? = null
            while (currentCoroutineContext().isActive) {
                status.value = "Waiting for speech backlog..."
                ttsSpeaker.waitForSlot(c.maxAhead)
                currentCoroutineContext().ensureActive()
                status.value = "Fetching image..."
                val imageBytes = withContext(Dispatchers.IO) { fetchImage(c.captureUrl) }
                val processed = withContext(Dispatchers.Default) { transformCameraImage(imageBytes, c.imageRotation, c.imageMirror) }
                if (c.onlyChanged || c.discardOutdated) {
                    val scene = withContext(Dispatchers.Default) { sceneSignature(processed) }
                    val prior = previousScene
                    val changed = prior == null || sceneDifference(prior, scene) >= 20.0
                    if (c.onlyChanged && !changed) {
                        status.value = "Scene unchanged"
                        delay(c.captureInterval * 1000L)
                        continue
                    }
                    if (c.discardOutdated && prior != null && changed) {
                        ttsSpeaker.stopSpeaking()
                        history.clear()
                    }
                    previousScene = scene
                }
                status.value = "Analyzing image..."
                val instruction = buildAnalysisPrompt(c.prompt, c.responseWords, history.toList())
                val text = withContext(Dispatchers.IO) {
                    client.analyzeImage(c.apiKey, instruction, processed, c.responseWords)
                }
                currentCoroutineContext().ensureActive()
                lastResponse.value = text
                if (c.memoryCount > 0) {
                    history.addLast(text)
                    while (history.size > c.memoryCount) history.removeFirst()
                }
                status.value = "Speaking..."
                ttsSpeaker.speakQueued(text)
                delay(c.captureInterval * 1000L)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            status.value = "Loop failed: ${e.message}"
            stopSelf()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        client.cancelAll()
        speaker?.shutdown()
        speaker = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        currentSpeech.value = ""
        running.value = false
        if (!status.value.startsWith("Loop failed") && !status.value.startsWith("Service failed")) {
            status.value = "Stopped"
        }
        super.onDestroy()
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

private fun transformCameraImage(imageBytes: ByteArray, rotation: Int, mirror: Boolean): ByteArray {
    val original = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
        ?: throw IllegalStateException("Failed to decode image")

    val matrix = Matrix().apply {
        postRotate(rotation.toFloat())
        if (mirror) postScale(-1f, 1f)
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

internal class QueueingTtsHelper(context: Context) {
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


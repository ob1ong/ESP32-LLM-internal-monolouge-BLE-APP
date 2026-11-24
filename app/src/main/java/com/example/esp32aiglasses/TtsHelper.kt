package com.example.esp32aiglasses

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import java.util.Locale
import java.util.UUID

class TtsHelper(context: Context) {
    private var ready: Boolean = false
    private var closed = false
    private val tts: TextToSpeech

    init {
        tts = TextToSpeech(context) { status: Int ->
            Handler(Looper.getMainLooper()).post { finishInit(status) }
        }
    }

    private fun finishInit(status: Int) {
        if (closed) return
        ready = status == TextToSpeech.SUCCESS
        if (ready) tts.language = Locale.getDefault()
    }

    fun speak(text: String) {
        if (!ready) return
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString())
    }

    fun shutdown() {
        closed = true
        ready = false
        tts.stop()
        tts.shutdown()
    }
}
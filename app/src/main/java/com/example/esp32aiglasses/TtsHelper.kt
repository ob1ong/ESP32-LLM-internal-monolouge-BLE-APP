package com.example.esp32aiglasses

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale
import java.util.UUID

class TtsHelper(context: Context) {
    private var ready: Boolean = false
    private val tts: TextToSpeech

    init {
        tts = TextToSpeech(context) { status: Int ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                tts.language = Locale.getDefault()
            }
        }
    }

    fun speak(text: String) {
        if (!ready) return
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString())
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
    }
}
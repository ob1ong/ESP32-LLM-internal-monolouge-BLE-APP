package com.example.esp32aiglasses

import android.app.Service
import android.content.Intent
import android.os.IBinder

class MonologueService : Service() {
    companion object {
        var apiKey: String = ""
        var prompt: String = ""
        var intervalSeconds: Int = 5
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
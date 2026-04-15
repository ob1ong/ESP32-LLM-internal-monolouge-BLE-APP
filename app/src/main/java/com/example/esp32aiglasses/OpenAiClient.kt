package com.example.esp32aiglasses

import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class OpenAiClient {
    private val client = OkHttpClient()

    fun analyzeImage(apiKey: String, prompt: String, imageBytes: ByteArray): String {
        val base64Image = Base64.encodeToString(imageBytes, Base64.NO_WRAP)

        val bodyJson = JSONObject().apply {
            put("model", "gpt-4.1-mini")
            put("max_output_tokens", 80)
            put(
                "input",
                JSONArray().put(
                    JSONObject().apply {
                        put("role", "user")
                        put(
                            "content",
                            JSONArray()
                                .put(
                                    JSONObject().apply {
                                        put("type", "input_text")
                                        put("text", prompt)
                                    }
                                )
                                .put(
                                    JSONObject().apply {
                                        put("type", "input_image")
                                        put("image_url", "data:image/jpeg;base64,$base64Image")
                                    }
                                )
                        )
                    }
                )
            )
        }

        val request = Request.Builder()
            .url("https://api.openai.com/v1/responses")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()

            if (!response.isSuccessful) {
                throw IllegalStateException("OpenAI error ${response.code}: $raw")
            }

            val json = JSONObject(raw)

            if (json.has("output_text")) {
                val text = json.optString("output_text", "")
                if (text.isNotBlank()) return text
            }

            val output = json.optJSONArray("output")
            if (output != null) {
                for (i in 0 until output.length()) {
                    val item = output.optJSONObject(i) ?: continue
                    val content = item.optJSONArray("content") ?: continue

                    for (j in 0 until content.length()) {
                        val c = content.optJSONObject(j) ?: continue
                        val type = c.optString("type")

                        if (type == "output_text") {
                            val text = c.optString("text", "")
                            if (text.isNotBlank()) return text
                        }

                        if (c.has("text")) {
                            val text = c.optString("text", "")
                            if (text.isNotBlank()) return text
                        }
                    }
                }
            }

            return "I can't think of anything right now."
        }
    }
}
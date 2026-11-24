package com.example.esp32aiglasses

import kotlin.math.abs

// Pending includes the response currently playing. Reserve a slot before fetching.
internal fun hasResponseSlot(pending: Int, ahead: Int): Boolean = pending < ahead.coerceIn(0, 5) + 1

internal fun responseTokenLimit(words: Int): Int = (words.coerceIn(10, 100) * 2 + 30)

internal fun buildAnalysisPrompt(custom: String, words: Int, history: List<String>): String = buildString {
    append(custom.trim().ifBlank { "Describe the visible scene concisely and concretely." })
    append(" Aim for about ${words.coerceIn(10, 100)} words or fewer. Do not invent details.")
    if (history.isNotEmpty()) {
        append("\nPrevious observations (context only; may be outdated):\n")
        history.takeLast(10).forEach { append("- ").append(it).append('\n') }
        append("Focus on the current image and avoid unnecessary repetition.")
    }
}

internal fun sceneDifference(a: IntArray, b: IntArray): Double {
    require(a.size == b.size && a.isNotEmpty())
    return a.indices.sumOf { abs(a[it] - b[it]) }.toDouble() / a.size
}

package com.example.esp32aiglasses

import org.junit.Assert.*
import org.junit.Test

class ResponseSettingsTest {
    @Test fun queueLimits() {
        for (ahead in 0..5) {
            assertTrue(hasResponseSlot(ahead, ahead))
            assertFalse(hasResponseSlot(ahead + 1, ahead))
        }
    }
    @Test fun promptAndTokenLimits() {
        assertTrue(buildAnalysisPrompt("", 25, emptyList()).contains("Describe the visible scene"))
        assertTrue(buildAnalysisPrompt("Read signs", 50, listOf("A shop")).contains("A shop"))
        assertEquals(80, responseTokenLimit(25))
        assertTrue(responseTokenLimit(100) > responseTokenLimit(10))
    }
    @Test fun sceneChanges() {
        assertEquals(0.0, sceneDifference(intArrayOf(10, 20), intArrayOf(10, 20)), 0.001)
        assertEquals(25.0, sceneDifference(intArrayOf(10, 20), intArrayOf(30, 50)), 0.001)
    }
}

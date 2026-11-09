package com.example.suppressor.persistence

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SuppressionStateTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `serialization and deserialization roundtrip preserves all fields`() {
        val original = SuppressionState(
            isSuppressing = true,
            activeRecordingCount = 2,
            activePackages = setOf("com.whatsapp", "com.snapchat.android"),
            originalAudioVolume = 6,
            originalVibrationIntensity = 75,
            timestamp = 1731110000000L
        )

        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<SuppressionState>(encoded)

        assertEquals(original.isSuppressing, decoded.isSuppressing)
        assertEquals(original.activeRecordingCount, decoded.activeRecordingCount)
        assertEquals(original.activePackages, decoded.activePackages)
        assertEquals(original.originalAudioVolume, decoded.originalAudioVolume)
        assertEquals(original.originalVibrationIntensity, decoded.originalVibrationIntensity)
        assertEquals(original.timestamp, decoded.timestamp)
    }

    @Test
    fun `SuppressionState companion constants are defined correctly`() {
        assertEquals("suppression_state", SuppressionState.PREFS_NAME)
        assertEquals("state_json", SuppressionState.KEY_STATE)
        assertEquals("original_audio_volume", SuppressionState.KEY_ORIGINAL_AUDIO)
        assertEquals("original_vibration_intensity", SuppressionState.KEY_ORIGINAL_VIBRATION)
    }
}

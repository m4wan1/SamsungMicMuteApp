package com.example.suppressor.persistence

import kotlinx.serialization.Serializable

/**
 * Persisted state for crash/reboot recovery.
 *
 * This state is saved to SharedPreferences and backed up via BackupManager
 * to survive app updates, device reboots, and cross-device restore.
 */
@Serializable
data class SuppressionState(
    /** Whether suppression is currently active */
    val isSuppressing: Boolean,

    /** Number of active recording configurations */
    val activeRecordingCount: Int,

    /** Package names of apps currently recording */
    val activePackages: Set<String>,

    /** Original STREAM_NOTIFICATION volume before suppression */
    val originalAudioVolume: Int,

    /** Original SEM_VIBRATION_NOTIFICATION_INTENSITY before suppression */
    val originalVibrationIntensity: Int,

    /** Timestamp of last state update (for debugging/expiry) */
    val timestamp: Long
) {
    companion object {
        const val PREFS_NAME = "suppression_state"
        const val KEY_STATE = "state_json"
        const val KEY_ORIGINAL_AUDIO = "original_audio_volume"
        const val KEY_ORIGINAL_VIBRATION = "original_vibration_intensity"
        const val KEY_BASELINE_AUDIO = "baseline_audio_volume"
        const val KEY_BASELINE_VIBRATION = "baseline_vibration_intensity"
    }
}
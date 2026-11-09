package com.example.suppressor.persistence

import android.app.backup.BackupManager
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Manages persistence of suppression state for crash/reboot recovery.
 *
 * Uses SharedPreferences for primary storage and BackupManager for cloud backup.
 * State is serialized as JSON using kotlinx.serialization.
 */
open class PersistenceManager(private val context: Context) {

    companion object {
        private const val TAG = "PersistenceManager"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(SuppressionState.PREFS_NAME, Context.MODE_PRIVATE)

    private val json = Json { ignoreUnknownKeys = true }

    private val backupManager = BackupManager(context)

    /**
     * Save the full suppression state.
     * Triggers backup to cloud.
     */
    open fun saveState(state: SuppressionState) {
        try {
            val jsonString = json.encodeToString(state)
            prefs.edit()
                .putString(SuppressionState.KEY_STATE, jsonString)
                .apply()
            backupManager.dataChanged()
            Log.d(TAG, "Saved suppression state: suppressing=${state.isSuppressing}, count=${state.activeRecordingCount}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save suppression state", e)
        }
    }

    /**
     * Load the full suppression state.
     * Returns null if no state exists or parsing fails.
     */
    open fun loadState(): SuppressionState? {
        val jsonString = prefs.getString(SuppressionState.KEY_STATE, null)
        return jsonString?.let {
            try {
                json.decodeFromString<SuppressionState>(it)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse suppression state, clearing", e)
                clearSuppressionState()
                null
            }
        }
    }

    /**
     * Save just the original values (volume and vibration intensity).
     * Called immediately when suppression starts, before any mutation.
     * Critical for crash recovery - these values must be persisted BEFORE muting.
     */
    open fun saveOriginalValues(audioVolume: Int, vibrationIntensity: Int) {
        try {
            val editor = prefs.edit()
                .putInt(SuppressionState.KEY_ORIGINAL_AUDIO, audioVolume)
                .putInt(SuppressionState.KEY_ORIGINAL_VIBRATION, vibrationIntensity)

            // Save non-zero baselines to protect against stuck 0 state
            if (audioVolume > 0) {
                editor.putInt(SuppressionState.KEY_BASELINE_AUDIO, audioVolume)
            }
            if (vibrationIntensity > 0) {
                editor.putInt(SuppressionState.KEY_BASELINE_VIBRATION, vibrationIntensity)
            }

            editor.apply()
            backupManager.dataChanged()
            Log.d(TAG, "Saved original values: audio=$audioVolume, vibration=$vibrationIntensity")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save original values", e)
        }
    }

    /**
     * Load the original values.
     * Returns Pair(audioVolume, vibrationIntensity) or (-1, -1) if not set.
     */
    open fun loadOriginalValues(): Pair<Int, Int> {
        val audio = prefs.getInt(SuppressionState.KEY_ORIGINAL_AUDIO, -1)
        val vibration = prefs.getInt(SuppressionState.KEY_ORIGINAL_VIBRATION, -1)
        return audio to vibration
    }

    /**
     * Load healthy non-zero baseline values if the phone was stuck at 0.
     */
    open fun loadBaselineValues(): Pair<Int, Int> {
        val audio = prefs.getInt(SuppressionState.KEY_BASELINE_AUDIO, -1)
        val vibration = prefs.getInt(SuppressionState.KEY_BASELINE_VIBRATION, -1)
        return audio to vibration
    }

    /**
     * Clear suppression state (but keep original values for potential restore).
     * Called when restoration completes successfully.
     */
    open fun clearSuppressionState() {
        try {
            prefs.edit()
                .remove(SuppressionState.KEY_STATE)
                .apply()
            backupManager.dataChanged()
            Log.d(TAG, "Cleared suppression state")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear suppression state", e)
        }
    }

    /**
     * Clear all persisted data including original values.
     * Called on explicit user reset or when service determines state is stale.
     */
    open fun clearAll() {
        try {
            prefs.edit().clear().apply()
            backupManager.dataChanged()
            Log.d(TAG, "Cleared all persisted data")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear all data", e)
        }
    }

    /**
     * Check if there's a valid persisted suppression state.
     */
    open fun hasPersistedState(): Boolean {
        return prefs.contains(SuppressionState.KEY_STATE)
    }

    /**
     * Get the timestamp of the last state save.
     * Useful for detecting stale state (e.g., after long downtime).
     */
    open fun getLastStateTimestamp(): Long {
        val state = loadState()
        return state?.timestamp ?: 0
    }
}
package com.example.suppressor.vibration

import android.content.Context
import android.provider.Settings
import com.example.suppressor.debug.DebugLogManager
import java.util.concurrent.ConcurrentHashMap
import kotlin.ranges.IntRange

/**
 * One UI 8 implementation of VibrationSettingsController using Samsung's system settings.
 *
 * Mutes ALL known Samsung notification vibration keys across One UI versions:
 * - SEM_VIBRATION_NOTIFICATION_INTENSITY / sem_vibration_notification_intensity
 * - VIB_NOTIFICATION_MAGNITUDE / vib_notification_magnitude
 * - vibrate_notification
 * - notification_vibration_intensity
 * - vibrate_on (fallback)
 *
 * Captures an exact snapshot map of all keys when entering suppression,
 * sets all to 0, and restores each exact value upon exiting suppression.
 */
class OneUI8VibrationSettingsController : VibrationSettingsController {

    companion object {
        private const val TAG = "OneUI8VibCtrl"

        val PRIMARY_KEYS = listOf(
            "SEM_VIBRATION_NOTIFICATION_INTENSITY",
            "sem_vibration_notification_intensity",
            "VIB_NOTIFICATION_MAGNITUDE",
            "vib_notification_magnitude",
            "vibrate_notification",
            "notification_vibration_intensity",
            "notification_vibration_sep_index"
        )

        const val FALLBACK_VIBRATE_ON_KEY = "vibrate_on"
    }

    override val settingKey: String = "sem_vibration_notification_intensity"
    override val displayName: String = "Samsung Multi-Key Vibration Controller"

    // Snapshot of original key values before muting
    private val savedSnapshotMap = ConcurrentHashMap<String, Int>()

    /**
     * Snapshot existing Samsung vibration keys once before muting.
     */
    fun snapshotSettings(context: Context): Map<String, Int> {
        val cr = context.contentResolver
        var foundAnyIntensity = false

        for (key in PRIMARY_KEYS) {
            try {
                val current = Settings.System.getInt(cr, key, -1)
                if (current >= 0) {
                    savedSnapshotMap[key] = current
                    if (current > 0) foundAnyIntensity = true
                }
            } catch (e: Exception) {
                DebugLogManager.log(TAG, "Error querying key $key: ${e.message}")
            }
        }

        // If no notification intensity key exists or all are 0, snapshot vibrate_on as fallback
        if (!foundAnyIntensity) {
            try {
                val vibOn = Settings.System.getInt(cr, FALLBACK_VIBRATE_ON_KEY, -1)
                if (vibOn >= 0) {
                    savedSnapshotMap[FALLBACK_VIBRATE_ON_KEY] = vibOn
                }
            } catch (e: Exception) {
                DebugLogManager.log(TAG, "Error querying $FALLBACK_VIBRATE_ON_KEY: ${e.message}")
            }
        }

        DebugLogManager.log(TAG, "Captured vibration snapshot: $savedSnapshotMap")
        return savedSnapshotMap
    }

    override fun getCurrentIntensity(context: Context): Int {
        val cr = context.contentResolver
        for (key in PRIMARY_KEYS) {
            try {
                val v = Settings.System.getInt(cr, key, -1)
                if (v >= 0) return v
            } catch (ignored: Exception) {
            }
        }
        return -1
    }

    /**
     * Live query of all active keys for the Live Inspector.
     */
    fun getLiveKeyValues(context: Context): Map<String, Int> {
        val cr = context.contentResolver
        val result = mutableMapOf<String, Int>()
        val allKeys = PRIMARY_KEYS + FALLBACK_VIBRATE_ON_KEY
        for (key in allKeys) {
            try {
                val v = Settings.System.getInt(cr, key, -1)
                if (v != -1) {
                    result[key] = v
                }
            } catch (ignored: Exception) {
            }
        }
        return result
    }

    override fun setIntensity(context: Context, intensity: Int): Boolean {
        val cr = context.contentResolver
        if (!Settings.System.canWrite(context)) {
            DebugLogManager.log(TAG, "WRITE_SETTINGS permission missing! Cannot mute vibration.")
            return false
        }

        if (intensity == 0) {
            // Snapshot original values first if not already done
            if (savedSnapshotMap.isEmpty()) {
                snapshotSettings(context)
            }

            var anySuccess = false
            // Target all keys that exist in snapshot or all primary keys
            val targetKeys = (savedSnapshotMap.keys + PRIMARY_KEYS).toSet()
            for (key in targetKeys) {
                try {
                    val current = Settings.System.getInt(cr, key, -1)
                    if (current != -1) {
                        val success = Settings.System.putInt(cr, key, 0)
                        val readBack = Settings.System.getInt(cr, key, -1)
                        DebugLogManager.log(TAG, "Mute key '$key': putInt(0)=$success, readBack=$readBack")
                        if (success || readBack == 0) anySuccess = true
                    }
                } catch (e: Exception) {
                    DebugLogManager.log(TAG, "Failed to mute key '$key': ${e.message}")
                }
            }

            // Fallback: If no intensity keys were set, set vibrate_on = 0
            if (!anySuccess && savedSnapshotMap.containsKey(FALLBACK_VIBRATE_ON_KEY)) {
                try {
                    val success = Settings.System.putInt(cr, FALLBACK_VIBRATE_ON_KEY, 0)
                    val readBack = Settings.System.getInt(cr, FALLBACK_VIBRATE_ON_KEY, -1)
                    DebugLogManager.log(TAG, "Fallback mute '$FALLBACK_VIBRATE_ON_KEY': putInt(0)=$success, readBack=$readBack")
                    if (success || readBack == 0) anySuccess = true
                } catch (e: Exception) {
                    DebugLogManager.log(TAG, "Failed to mute fallback '$FALLBACK_VIBRATE_ON_KEY': ${e.message}")
                }
            }

            return anySuccess
        } else {
            return restoreIntensity(context, intensity)
        }
    }

    override fun restoreIntensity(context: Context, originalIntensity: Int): Boolean {
        val cr = context.contentResolver
        if (!Settings.System.canWrite(context)) {
            DebugLogManager.log(TAG, "WRITE_SETTINGS permission missing! Cannot restore vibration.")
            return false
        }

        var anySuccess = false

        if (savedSnapshotMap.isNotEmpty()) {
            for ((key, originalValue) in savedSnapshotMap) {
                try {
                    val success = Settings.System.putInt(cr, key, originalValue)
                    val readBack = Settings.System.getInt(cr, key, -1)
                    DebugLogManager.log(TAG, "Restore key '$key' to $originalValue: putInt=$success, readBack=$readBack")
                    if (success || readBack == originalValue) anySuccess = true
                } catch (e: Exception) {
                    DebugLogManager.log(TAG, "Failed to restore key '$key': ${e.message}")
                }
            }
            savedSnapshotMap.clear()
        } else {
            // Restore using default/provided originalIntensity
            val safeIntensity = if (originalIntensity > 0) originalIntensity else 3
            for (key in PRIMARY_KEYS) {
                try {
                    val current = Settings.System.getInt(cr, key, -1)
                    if (current != -1) {
                        val success = Settings.System.putInt(cr, key, safeIntensity)
                        val readBack = Settings.System.getInt(cr, key, -1)
                        DebugLogManager.log(TAG, "Restore fallback key '$key' to $safeIntensity: putInt=$success, readBack=$readBack")
                        if (success || readBack == safeIntensity) anySuccess = true
                    }
                } catch (e: Exception) {
                    DebugLogManager.log(TAG, "Failed fallback restore key '$key': ${e.message}")
                }
            }
        }

        return anySuccess
    }

    override fun isSupported(context: Context): Boolean {
        return try {
            val hasPermission = Settings.System.canWrite(context)
            hasPermission && getCurrentIntensity(context) >= 0
        } catch (e: Exception) {
            false
        }
    }

    override fun getFallback(): VibrationSettingsController? = null

    override fun getIntensityRange(): IntRange = 0..100
}

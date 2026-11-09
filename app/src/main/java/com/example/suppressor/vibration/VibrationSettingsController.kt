package com.example.suppressor.vibration

import android.content.Context
import kotlin.ranges.IntRange

/**
 * Interface for controlling vibration intensity via system settings.
 *
 * Primary implementation: OneUI8VibrationSettingsController (uses SEM_VIBRATION_NOTIFICATION_INTENSITY via Settings.System)
 * Fallback 1: NotificationListenerVibrationController (cancels vibration in onNotificationPosted)
 * Fallback 2: AdbSecureSettingsVibrationController (uses ADB shell to write secure settings)
 */
interface VibrationSettingsController {

    /** Settings.System key for this vibration intensity (e.g., "sem_vibration_notification_intensity") */
    val settingKey: String

    /** Human-readable name for logging/debugging */
    val displayName: String

    /**
     * Get current intensity value from Settings.System.
     * @return Current intensity, or -1 if unavailable/not supported
     */
    fun getCurrentIntensity(context: Context): Int

    /**
     * Set intensity value in Settings.System.
     * @param context Application context
     * @param intensity Target intensity (will be clamped to valid range)
     * @return true if successful, false otherwise
     */
    fun setIntensity(context: Context, intensity: Int): Boolean

    /**
     * Restore intensity to a specific value.
     * @param context Application context
     * @param originalIntensity Previously cached original intensity
     * @return true if successful, false otherwise
     */
    fun restoreIntensity(context: Context, originalIntensity: Int): Boolean

    /**
     * Check if this controller is supported on current device/OS.
     * Should verify setting existence and permission state (WRITE_SETTINGS).
     */
    fun isSupported(context: Context): Boolean

    /**
     * Get fallback controller if this one fails, or null if none.
     * Enables fallback chain: Primary → Fallback 1 → Fallback 2 → null
     */
    fun getFallback(): VibrationSettingsController?

    /**
     * Valid intensity range (min, max) for validation/clamping.
     */
    fun getIntensityRange(): IntRange

    /**
     * Optional: Configure fallback controller.
     * Appends the fallback to the end of the existing fallback chain.
     */
    fun withFallback(fallback: VibrationSettingsController): VibrationSettingsController {
        val existingFallback = getFallback()
        val chained = if (existingFallback != null) {
            existingFallback.withFallback(fallback)
        } else {
            fallback
        }
        return object : VibrationSettingsController by this {
            override fun getFallback(): VibrationSettingsController? = chained
        }
    }
}
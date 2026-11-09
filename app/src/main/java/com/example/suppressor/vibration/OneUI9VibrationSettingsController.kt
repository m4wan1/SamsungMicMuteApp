package com.example.suppressor.vibration

import android.content.Context
import android.os.Build
import android.util.Log

/**
 * Forward-compatibility stub for One UI 9 (Android 16 / API 36+).
 *
 * One UI 9 may introduce changes to vibration intensity keys in Settings.System
 * (e.g. moving them to Settings.Secure or introducing new VibrationEffect APIs).
 * This controller serves as the entry point for One UI 9 adaptations,
 * falling back to OneUI8VibrationSettingsController.
 */
class OneUI9VibrationSettingsController : VibrationSettingsController {

    companion object {
        private const val TAG = "OneUI9VibrationCtrl"
        // Potential future key or alternative system setting for One UI 9
        private const val SETTING_KEY_V9 = "sem_vibration_notification_intensity"
    }

    private val baseController = OneUI8VibrationSettingsController()

    override val settingKey: String = SETTING_KEY_V9

    override val displayName: String = "One UI 9 SEM_VIBRATION_NOTIFICATION_INTENSITY (Forward-Compatible)"

    override fun getCurrentIntensity(context: Context): Int {
        return baseController.getCurrentIntensity(context)
    }

    override fun setIntensity(context: Context, intensity: Int): Boolean {
        Log.d(TAG, "Setting intensity on One UI 9 vibration controller: $intensity")
        return baseController.setIntensity(context, intensity)
    }

    override fun restoreIntensity(context: Context, originalIntensity: Int): Boolean {
        Log.d(TAG, "Restoring intensity on One UI 9 vibration controller: $originalIntensity")
        return baseController.restoreIntensity(context, originalIntensity)
    }

    override fun isSupported(context: Context): Boolean {
        val isApiSupported = Build.VERSION.SDK_INT >= 36
        return isApiSupported && baseController.isSupported(context)
    }

    override fun getFallback(): VibrationSettingsController? = baseController

    override fun getIntensityRange(): IntRange = baseController.getIntensityRange()
}

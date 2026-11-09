package com.example.suppressor.vibration

import android.content.Context
import android.util.Log
import kotlin.ranges.IntRange

/**
 * Last-resort fallback VibrationSettingsController using ADB shell commands.
 *
 * Uses `settings put secure` via Runtime.exec() to modify secure settings.
 * This REQUIRES the app to have been granted WRITE_SECURE_SETTINGS via ADB:
 *   adb shell appops set <package> WRITE_SECURE_SETTINGS allow
 *
 * This is NOT available to regular Play Store apps - only for:
 * - Development/testing via ADB
 * - Enterprise/MDM deployed apps
 * - System apps
 *
 * Setting key: Attempts "sem_vibration_notification_intensity" in secure settings first,
 * then falls back to system settings via shell.
 */
class AdbSecureSettingsVibrationController : VibrationSettingsController {

    companion object {
        private const val TAG = "AdbSecureVibrationCtrl"
        private const val SETTING_KEY = "sem_vibration_notification_intensity"
    }

    override val settingKey = SETTING_KEY
    override val displayName = "ADB Secure Settings Fallback"

    override fun getCurrentIntensity(context: Context): Int {
        return try {
            // Try secure settings first via shell
            val result = Runtime.getRuntime().exec("settings get secure $settingKey")
                .inputStream.bufferedReader().readText().trim()
            val value = result.toIntOrNull()
            if (value != null) return value

            // Fallback to system settings via shell
            val result2 = Runtime.getRuntime().exec("settings get system $settingKey")
                .inputStream.bufferedReader().readText().trim()
            result2.toIntOrNull() ?: -1
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get vibration intensity via ADB", e)
            -1
        }
    }

    override fun setIntensity(context: Context, intensity: Int): Boolean {
        return try {
            val clamped = intensity.coerceIn(getIntensityRange())

            // Try secure settings first
            var exitCode = Runtime.getRuntime().exec("settings put secure $settingKey $clamped").waitFor()
            if (exitCode == 0) {
                Log.i(TAG, "Set secure $settingKey to $clamped via ADB")
                return true
            }

            // Fallback to system settings
            exitCode = Runtime.getRuntime().exec("settings put system $settingKey $clamped").waitFor()
            if (exitCode == 0) {
                Log.i(TAG, "Set system $settingKey to $clamped via ADB")
                return true
            }

            Log.w(TAG, "ADB settings put failed for both secure and system (exit code: $exitCode)")
            false
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set vibration intensity via ADB", e)
            false
        }
    }

    override fun restoreIntensity(context: Context, originalIntensity: Int): Boolean {
        return setIntensity(context, originalIntensity)
    }

    override fun isSupported(context: Context): Boolean {
        // Check if ADB granted WRITE_SECURE_SETTINGS by trying to read a secure setting
        return try {
            val exitCode = Runtime.getRuntime().exec("settings get secure $settingKey").waitFor()
            exitCode == 0
        } catch (e: Exception) {
            false
        }
    }

    override fun getFallback(): VibrationSettingsController? = null // End of fallback chain

    override fun getIntensityRange(): IntRange = 0..100
}
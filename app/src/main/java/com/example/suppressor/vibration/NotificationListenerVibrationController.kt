package com.example.suppressor.vibration

import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlin.ranges.IntRange

/**
 * Fallback VibrationSettingsController using NotificationListenerService.
 *
 * When system settings vibration control fails, this controller cancels vibration
 * per-notification in onNotificationPosted by clearing the vibration pattern.
 *
 * Requires: NotificationListenerService enabled by user (Settings > Apps > Special Access > Notification Access)
 * Works on Android 4.3+ (API 18+)
 *
 * This is a "soft" suppression - it doesn't change system settings but prevents
 * vibration at the notification level. Call vibration and alarm vibration are
 * typically not affected as they use different notification channels.
 */
class NotificationListenerVibrationController : VibrationSettingsController {

    companion object {
        private const val TAG = "NLSVibrationController"
        private const val SETTING_KEY = "notification_listener_vibration_cancel"
    }

    override val settingKey = SETTING_KEY
    override val displayName = "NotificationListener Vibration Cancel"

    // Reference to the active NotificationListenerService
    // Set by SuppressionNotificationListener when it starts
    private var nlsService: NotificationListenerService? = null

    var suppressNotificationVibration = false
        private set

    override fun getCurrentIntensity(context: Context): Int {
        // Return 1 (active) if suppression is enabled, 0 if not
        return if (suppressNotificationVibration) 1 else 0
    }

    override fun setIntensity(context: Context, intensity: Int): Boolean {
        return try {
            when (intensity) {
                0 -> {
                    // Enable vibration suppression
                    suppressNotificationVibration = true
                    Log.i(TAG, "Notification vibration suppression ENABLED via NLS")
                }
                else -> {
                    // Disable vibration suppression (restore)
                    suppressNotificationVibration = false
                    Log.i(TAG, "Notification vibration suppression DISABLED via NLS")
                }
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set NLS vibration suppression", e)
            false
        }
    }

    override fun restoreIntensity(context: Context, originalIntensity: Int): Boolean {
        return setIntensity(context, originalIntensity)
    }

    override fun isSupported(context: Context): Boolean {
        // Check if NotificationListenerService is enabled for this package
        return try {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            )
            enabled?.contains(context.packageName) == true
        } catch (e: Exception) {
            Log.w(TAG, "isSupported check failed", e)
            false
        }
    }

    override fun getFallback(): VibrationSettingsController? = null // Configured externally

    override fun getIntensityRange(): IntRange = 0..1

    /**
     * Called by SuppressionNotificationListener.onNotificationPosted
     * to cancel vibration for the posted notification.
     */
    fun onNotificationPosted(sbn: StatusBarNotification): Boolean {
        if (!suppressNotificationVibration) return false

        val notification = sbn.notification
        var modified = false

        // Clear vibration pattern
        if (notification.vibrate != null) {
            notification.vibrate = null
            modified = true
        }

        // Remove DEFAULT_VIBRATE flag
        if (notification.defaults and android.app.Notification.DEFAULT_VIBRATE != 0) {
            notification.defaults = notification.defaults and android.app.Notification.DEFAULT_VIBRATE.inv()
            modified = true
        }

        // Note: In V2, notifications must NEVER be snoozed, canceled, or delayed.
        // We do not call cancelNotification(), cancelAllNotifications(), or snoozeNotification().
        // Notifications are posted in real-time; silent delivery is handled at the system level.

        // Best effort: log and return
        if (modified) {
            Log.d(TAG, "Cleared vibration for notification: ${sbn.packageName}/${sbn.tag}")
        }

        return modified
    }

    /**
     * Set the NotificationListenerService reference.
     * Called by SuppressionNotificationListener.onListenerConnected()
     */
    fun setNotificationListenerService(service: NotificationListenerService?) {
        nlsService = service
        Log.d(TAG, "NotificationListenerService reference ${if (service != null) "set" else "cleared"}")
    }
}
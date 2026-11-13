package com.example.suppressor.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Process
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.example.suppressor.RecordingSuppressor
import com.example.suppressor.SuppressionApplication
import com.example.suppressor.debug.DebugLogManager
import com.example.suppressor.vibration.NotificationListenerVibrationController

/**
 * NotificationListenerService - PRIMARY background host for notification monitoring.
 *
 * Runs persistently with system priority. Registers AudioRecordingCallback on a dedicated
 * HandlerThread to guarantee real-time mic detection on Samsung One UI even when the app is minimized.
 *
 * In V2:
 * Notifications are delivered and displayed on screen in real-time without snoozing, delaying,
 * or blocking. The silent experience relies exclusively on Priority 1 controllers
 * (OneUI8AudioStreamController setting STREAM_NOTIFICATION to 0, and
 * OneUI8VibrationSettingsController setting sem_vibration_notification_intensity to 0).
 */
class SuppressionNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "SuppressionNLS"

        /**
         * Check if notification listener access is granted.
         */
        fun isNotificationListenerEnabled(context: Context): Boolean {
            return try {
                val enabled = android.provider.Settings.Secure.getString(
                    context.contentResolver,
                    "enabled_notification_listeners"
                )
                enabled?.contains(context.packageName) == true
            } catch (e: Exception) {
                DebugLogManager.log(TAG, "Failed to check notification listener status: ${e.message}")
                false
            }
        }
    }

    private var vibrationController: NotificationListenerVibrationController? = null
    private var recordingSuppressor: RecordingSuppressor? = null
    private var monitorThread: HandlerThread? = null
    private var foregroundService: SuppressionForegroundService? = null
    private var serviceConnection: ServiceConnection? = null

    override fun onCreate() {
        super.onCreate()
        DebugLogManager.log(TAG, "SuppressionNotificationListener onCreate")

        val app = application as? SuppressionApplication
        recordingSuppressor = app?.recordingSuppressor
        vibrationController = app?.nlsVibrationController
        vibrationController?.setNotificationListenerService(this)

        // Register AudioRecordingCallback directly on a dedicated HandlerThread
        val thread = HandlerThread("MicMonitorThread-NLS", Process.THREAD_PRIORITY_URGENT_AUDIO).apply { start() }
        monitorThread = thread
        val handler = Handler(thread.looper)

        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        recordingSuppressor?.let { suppressor ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                audioManager.registerAudioRecordingCallback(suppressor, handler)
                DebugLogManager.log(TAG, "AudioRecordingCallback registered in NLS on MicMonitorThread-NLS")
            }
        }

        // Bind to foreground service
        bindToForegroundService()
    }

    override fun onDestroy() {
        super.onDestroy()
        DebugLogManager.log(TAG, "SuppressionNotificationListener onDestroy")

        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        recordingSuppressor?.let { suppressor ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    audioManager.unregisterAudioRecordingCallback(suppressor)
                } catch (ignored: Exception) {
                }
            }
        }

        monitorThread?.quitSafely()
        monitorThread = null
        vibrationController?.setNotificationListenerService(null)

        unbindFromForegroundService()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        DebugLogManager.log(TAG, "NotificationListenerService connected")

        // Ensure foreground service is running
        try {
            val serviceIntent = Intent(this, SuppressionForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (e: Exception) {
            DebugLogManager.log(TAG, "Failed to start foreground service from NLS: ${e.message}")
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        DebugLogManager.log(TAG, "NotificationListenerService disconnected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        super.onNotificationPosted(sbn)
        // V2: Notifications are NOT snoozed, delayed, or canceled.
        // They post and display normally in real-time.
        // Silent behavior relies exclusively on Priority 1 controllers (volume 0 & vibration intensity 0).
        vibrationController?.onNotificationPosted(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        super.onNotificationRemoved(sbn)
    }

    private fun bindToForegroundService() {
        val intent = Intent(this, SuppressionForegroundService::class.java)
        serviceConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                val binder = service as SuppressionForegroundService.LocalBinder
                foregroundService = binder.getService()
                vibrationController = foregroundService?.getNLSVibrationController()
                vibrationController?.setNotificationListenerService(this@SuppressionNotificationListener)
                DebugLogManager.log(TAG, "Connected to foreground service from NLS")
            }

            override fun onServiceDisconnected(name: ComponentName) {
                DebugLogManager.log(TAG, "Foreground service disconnected")
                foregroundService = null
            }
        }

        try {
            bindService(intent, serviceConnection!!, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            DebugLogManager.log(TAG, "Failed to bind to foreground service: ${e.message}")
        }
    }

    private fun unbindFromForegroundService() {
        serviceConnection?.let {
            try {
                unbindService(it)
            } catch (e: Exception) {
                DebugLogManager.log(TAG, "Error unbinding from foreground service: ${e.message}")
            }
            serviceConnection = null
        }
        foregroundService = null
    }
}

package com.example.suppressor.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Process
import androidx.core.app.NotificationCompat
import com.example.suppressor.R
import com.example.suppressor.RecordingSuppressor
import com.example.suppressor.SuppressionApplication
import com.example.suppressor.audio.NotificationChannelAudioController
import com.example.suppressor.audio.OneUI8AudioStreamController
import com.example.suppressor.debug.DebugLogManager
import com.example.suppressor.persistence.PersistenceManager
import com.example.suppressor.ui.MainActivity
import com.example.suppressor.vibration.AdbSecureSettingsVibrationController
import com.example.suppressor.vibration.NotificationListenerVibrationController
import com.example.suppressor.vibration.OneUI8VibrationSettingsController

/**
 * Foreground Service for background microphone monitoring and notification suppression.
 *
 * Runs persistently with START_STICKY:
 * 1. Registers AudioRecordingCallback on a dedicated high-priority thread.
 * 2. Runs a 500ms ticker detecting both activeRecordingConfigurations AND communication audio modes
 *    (MODE_IN_COMMUNICATION, MODE_IN_CALL, MODE_CALL_REDIRECT) for VoIP/social apps.
 */
class SuppressionForegroundService : Service() {

    companion object {
        private const val TAG = "SuppressionFgService"
        const val CHANNEL_ID = "suppression_monitor_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.example.suppressor.START_SERVICE"
        const val ACTION_STOP = "com.example.suppressor.STOP_SERVICE"
        const val ACTION_FORCE_RESTORE = "com.example.suppressor.FORCE_RESTORE"
    }

    private var recordingSuppressor: RecordingSuppressor? = null
    private var notificationManager: NotificationManager? = null
    private var nlsVibrationController: NotificationListenerVibrationController? = null
    private var monitorThread: HandlerThread? = null
    private var monitorHandler: Handler? = null
    private val binder = LocalBinder()

    // 500ms Polling + Audio Mode Backup Detector
    private val micTickerRunnable = object : Runnable {
        override fun run() {
            try {
                pollMicrophoneState()
            } catch (e: Exception) {
                DebugLogManager.log(TAG, "Ticker poll exception: ${e.message}")
            }
            monitorHandler?.postDelayed(this, 500L)
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): SuppressionForegroundService = this@SuppressionForegroundService
        fun getRecordingSuppressor(): RecordingSuppressor? = recordingSuppressor
    }

    fun getRecordingSuppressor(): RecordingSuppressor? = recordingSuppressor
    fun getNLSVibrationController(): NotificationListenerVibrationController? = nlsVibrationController

    override fun onCreate() {
        super.onCreate()
        DebugLogManager.log(TAG, "SuppressionForegroundService onCreate")

        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()

        val app = application as? SuppressionApplication
        recordingSuppressor = app?.recordingSuppressor ?: run {
            val audioController = OneUI8AudioStreamController()
                .withFallback(NotificationChannelAudioController())
            val nlsVib = NotificationListenerVibrationController()
            nlsVibrationController = nlsVib
            val vibrationController = OneUI8VibrationSettingsController()
                .withFallback(nlsVib)
                .withFallback(AdbSecureSettingsVibrationController())
            val persistenceManager = PersistenceManager(this)
            RecordingSuppressor(this, audioController, vibrationController, persistenceManager)
        }
        nlsVibrationController = app?.nlsVibrationController ?: nlsVibrationController

        // Register AudioRecordingCallback on a dedicated high-priority HandlerThread
        val thread = HandlerThread("MicMonitorThread", Process.THREAD_PRIORITY_URGENT_AUDIO).apply { start() }
        monitorThread = thread
        val handler = Handler(thread.looper)
        monitorHandler = handler

        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            audioManager.registerAudioRecordingCallback(
                recordingSuppressor!!,
                handler
            )
            DebugLogManager.log(TAG, "AudioRecordingCallback registered on MicMonitorThread")
        }

        // Start 500ms background polling ticker
        handler.post(micTickerRunnable)
        DebugLogManager.log(TAG, "500ms Backup Mic Detection Ticker started")

        // Recover from persisted state
        recordingSuppressor?.initializeFromPersistedState()

        // Start foreground with persistent notification
        startForegroundService()
    }

    /**
     * Polling method called every 500ms by micTickerRunnable on MicMonitorThread.
     */
    private fun pollMicrophoneState() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val configs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                audioManager.activeRecordingConfigurations
            } catch (e: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }

        val mode = audioManager.mode
        val isCommMode = mode == AudioManager.MODE_IN_COMMUNICATION ||
                mode == AudioManager.MODE_IN_CALL ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && mode == AudioManager.MODE_CALL_REDIRECT)

        val isMicActive = configs.isNotEmpty() || isCommMode

        recordingSuppressor?.updateDetectionState(
            isMicActive = isMicActive,
            configs = configs,
            source = "Ticker_500ms",
            mode = mode
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        DebugLogManager.log(TAG, "onStartCommand: action=$action, flags=$flags, startId=$startId")

        when (action) {
            ACTION_FORCE_RESTORE -> {
                recordingSuppressor?.forceRestore()
            }
            ACTION_STOP -> {
                stopSelf()
            }
            ACTION_START -> {
                recordingSuppressor?.initializeFromPersistedState()
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        DebugLogManager.log(TAG, "SuppressionForegroundService onDestroy")

        monitorHandler?.removeCallbacks(micTickerRunnable)

        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        recordingSuppressor?.let { suppressor ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    audioManager.unregisterAudioRecordingCallback(suppressor)
                    DebugLogManager.log(TAG, "AudioRecordingCallback unregistered")
                } catch (ignored: Exception) {
                }
            }
            suppressor.forceRestore()
        }

        monitorThread?.quitSafely()
        monitorThread = null
        monitorHandler = null

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return binder
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Microphone Monitoring",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Monitors microphone usage to suppress notification sounds/vibration"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
                setSound(null, null)
            }
            notificationManager?.createNotificationChannel(channel)
        }
    }

    private fun startForegroundService() {
        val activityIntent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, activityIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val restoreIntent = Intent(this, SuppressionForegroundService::class.java)
            .setAction(ACTION_FORCE_RESTORE)
        val restorePendingIntent = PendingIntent.getService(
            this, 1, restoreIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Notification Suppression Active")
            .setContentText("Monitoring microphone usage")
            .setSmallIcon(R.drawable.ic_mic_monitor)
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_restore, "Force Restore", restorePendingIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setOnlyAlertOnce(true)
            .build()

        val foregroundType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }

        startForeground(NOTIFICATION_ID, notification, foregroundType)
        DebugLogManager.log(TAG, "Foreground service started with type: $foregroundType")
    }

    fun updateNotification(suppressing: Boolean, activePackages: Set<String>) {
        val text = if (suppressing) {
            "Suppressing notifications for: ${activePackages.joinToString(", ")}"
        } else {
            "Monitoring microphone usage"
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(if (suppressing) "Suppressing Notifications" else "Notification Suppression Active")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_mic_monitor)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        notificationManager?.notify(NOTIFICATION_ID, notification)
    }
}

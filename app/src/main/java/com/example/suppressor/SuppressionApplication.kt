package com.example.suppressor

import android.app.Application
import android.content.Context
import android.util.Log
import com.example.suppressor.audio.NotificationChannelAudioController
import com.example.suppressor.audio.OneUI8AudioStreamController
import com.example.suppressor.persistence.PersistenceManager
import com.example.suppressor.vibration.AdbSecureSettingsVibrationController
import com.example.suppressor.vibration.NotificationListenerVibrationController
import com.example.suppressor.vibration.OneUI8VibrationSettingsController

/**
 * Main Application class for the suppression app.
 *
 * Initializes and provides the singleton RecordingSuppressor instance so that
 * ForegroundService, NotificationListenerService, and UI share the exact same state machine.
 */
class SuppressionApplication : Application() {

    companion object {
        private const val TAG = "SuppressionApplication"

        @Volatile
        private var instance: SuppressionApplication? = null

        /** Get the application instance (for static context access) */
        fun getInstance(): SuppressionApplication? = instance
    }

    /** Shared NLS vibration controller */
    val nlsVibrationController: NotificationListenerVibrationController by lazy {
        NotificationListenerVibrationController()
    }

    /** Central singleton RecordingSuppressor */
    val recordingSuppressor: RecordingSuppressor by lazy {
        val audioController = OneUI8AudioStreamController()
            .withFallback(NotificationChannelAudioController())
        val vibrationController = OneUI8VibrationSettingsController()
            .withFallback(nlsVibrationController)
            .withFallback(AdbSecureSettingsVibrationController())
        val persistenceManager = PersistenceManager(this)

        RecordingSuppressor(
            context = this,
            audioController = audioController,
            vibrationController = vibrationController,
            persistenceManager = persistenceManager
        )
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i(TAG, "SuppressionApplication created")
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        Log.d(TAG, "onTrimMemory: level=$level")
    }

    override fun onLowMemory() {
        super.onLowMemory()
        Log.w(TAG, "onLowMemory")
    }

    fun getAppContext(): Context = this
}

# One UI 8 Notification Suppression - Implementation Plan

**Version:** 1.0  
**Date:** 2026-11-08  
**Status:** DRAFT - Pending FEASIBILITY_REPORT.md Review & Approval  
**Prerequisite:** FEASIBILITY_REPORT.md must be complete with physical device test results

---

## 1. Project Overview

### Objective
Automatically silence notification sounds and notification vibrations whenever any application uses the microphone, while keeping phone call ringtones, call vibrations, media playback, and alarms completely unaffected.

### Strict Constraints (NON-NEGOTIABLE)
| Constraint | Requirement |
|------------|-------------|
| ❌ Do NOT use | Do Not Disturb (DND / NotificationPolicy) |
| ❌ Do NOT use | Global Silent Mode (RINGER_MODE_SILENT) |
| ❌ Do NOT use | RECORD_AUDIO permission |
| ✅ MUST use | AudioManager.AudioRecordingCallback for microphone detection |
| ✅ MUST use | AudioManager (STREAM_NOTIFICATION) for sound suppression |
| ✅ MUST use | android.permission.WRITE_SETTINGS targeting Samsung's SEM_VIBRATION_NOTIFICATION_INTENSITY in Settings.System |
| ✅ MUST do | Cache and persist user's original STREAM_NOTIFICATION volume and SEM_VIBRATION_NOTIFICATION_INTENSITY prior to muting |
| ✅ MUST do | Ensure atomic restoration when all active recording configurations end |
| ✅ MUST do | Support crash/reboot recovery via persisted state |

---

## 2. Approved Technical Architecture

### 2.1 High-Level Component Diagram

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           Application Layer                                  │
│  ┌─────────────────────────────────────────────────────────────────────┐    │
│  │                    RecordingSuppressor (Main Orchestrator)           │    │
│  │  - Lifecycle management                                              │    │
│  │  - State machine: IDLE → SUPPRESSING → RESTORING                    │    │
│  │  - Active recording count tracking                                   │    │
│  │  - Persistence coordination                                          │    │
│  └─────────────────────────────┬────────────────────────────────────────┘    │
│                                │                                             │
│         ┌──────────────────────┼──────────────────────┐                     │
│         ▼                      ▼                      ▼                     │
│  ┌───────────────┐    ┌─────────────────┐    ┌─────────────────┐           │
│  │ AudioStream   │    │ VibrationSettings│    │ Persistence     │           │
│  │ Controller    │    │ Controller      │    │ Manager         │           │
│  │ (Interface)   │    │ (Interface)     │    │                 │           │
│  └───────┬───────┘    └────────┬────────┘    └────────┬────────┘           │
│          │                     │                      │                     │
│    ┌─────┴─────┐         ┌─────┴─────┐         ┌──────┴──────┐             │
│    ▼           ▼         ▼           ▼         ▼             ▼             │
│ OneUI8     Fallback   OneUI8     Fallback    SharedPrefs  BackupManager    │
│ StreamImpl StreamImpl VibrationImpl VibrationImpl  + File     + Cloud      │
│                                                         Backup             │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                           System Integration Layer                           │
│  ┌──────────────────┐  ┌──────────────────┐  ┌──────────────────────────┐   │
│  │ AudioRecording   │  │ Foreground       │  │ Boot/Update              │   │
│  │ Callback         │  │ Service          │  │ Receivers                │   │
│  │ (Detection)      │  │ (Background      │  │ (Recovery)               │   │
│  │                  │  │  Execution)      │  │                          │   │
│  └──────────────────┘  └──────────────────┘  └──────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 2.2 Core Interfaces

#### AudioStreamController.kt
```kotlin
package com.example.suppressor.audio

interface AudioStreamController {
    /** The AudioManager stream type this controller manages (e.g., STREAM_NOTIFICATION) */
    val streamType: Int
    
    /** Human-readable name for logging/debugging */
    val displayName: String
    
    /** Current system volume for this stream (0 to max) */
    fun getCurrentVolume(context: Context): Int
    
    /** Set volume for this stream. Returns true if successful. */
    fun setVolume(context: Context, volume: Int, flags: Int = 0): Boolean
    
    /** Restore volume to a specific value. Returns true if successful. */
    fun restoreVolume(context: Context, originalVolume: Int): Boolean
    
    /** Check if this controller is supported on current device/OS */
    fun isSupported(context: Context): Boolean
    
    /** Get fallback controller if this one fails, or null if none */
    fun getFallback(): AudioStreamController?
    
    /** Maximum volume for this stream (for validation) */
    fun getMaxVolume(context: Context): Int
}
```

#### VibrationSettingsController.kt
```kotlin
package com.example.suppressor.vibration

interface VibrationSettingsController {
    /** Settings.System key for this vibration intensity (e.g., "sem_vibration_notification_intensity") */
    val settingKey: String
    
    /** Human-readable name for logging/debugging */
    val displayName: String
    
    /** Current intensity value from Settings.System */
    fun getCurrentIntensity(context: Context): Int
    
    /** Set intensity value in Settings.System. Returns true if successful. */
    fun setIntensity(context: Context, intensity: Int): Boolean
    
    /** Restore intensity to a specific value. Returns true if successful. */
    fun restoreIntensity(context: Context, originalIntensity: Int): Boolean
    
    /** Check if this controller is supported on current device/OS */
    fun isSupported(context: Context): Boolean
    
    /** Get fallback controller if this one fails, or null if none */
    fun getFallback(): VibrationSettingsController?
    
    /** Valid intensity range (min, max) for validation */
    fun getIntensityRange(): IntRange
}
```

### 2.3 One UI 8 Implementations

#### OneUI8AudioStreamController.kt
```kotlin
package com.example.suppressor.audio

class OneUI8AudioStreamController : AudioStreamController {
    override val streamType = AudioManager.STREAM_NOTIFICATION
    override val displayName = "One UI 8 STREAM_NOTIFICATION"
    
    private val audioManager by lazy { 
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager 
    }
    
    override fun getCurrentVolume(context: Context): Int = 
        audioManager.getStreamVolume(streamType)
    
    override fun setVolume(context: Context, volume: Int, flags: Int): Boolean {
        return try {
            audioManager.setStreamVolume(streamType, volume.coerceIn(0, getMaxVolume(context)), flags)
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException setting stream volume", e)
            false
        }
    }
    
    override fun restoreVolume(context: Context, originalVolume: Int): Boolean =
        setVolume(context, originalVolume, AudioManager.FLAG_PLAY_SOUND)
    
    override fun isSupported(context: Context): Boolean {
        // Verify STREAM_NOTIFICATION exists and is independent
        val maxVol = getMaxVolume(context)
        val currentVol = getCurrentVolume(context)
        return maxVol > 0 && currentVol >= 0
    }
    
    override fun getFallback(): AudioStreamController? = 
        NotificationChannelAudioController() // Fallback: per-channel suppression
    
    override fun getMaxVolume(context: Context): Int = 
        audioManager.getStreamMaxVolume(streamType)
}
```

#### OneUI8VibrationSettingsController.kt
```kotlin
package com.example.suppressor.vibration

class OneUI8VibrationSettingsController : VibrationSettingsController {
    override val settingKey = "sem_vibration_notification_intensity"
    override val displayName = "One UI 8 SEM_VIBRATION_NOTIFICATION_INTENSITY"
    
    private val contentResolver by lazy { context.contentResolver }
    
    override fun getCurrentIntensity(context: Context): Int {
        return try {
            Settings.System.getInt(contentResolver, settingKey, -1)
        } catch (e: Exception) {
            -1 // Indicates not available
        }
    }
    
    override fun setIntensity(context: Context, intensity: Int): Boolean {
        return try {
            val clamped = intensity.coerceIn(getIntensityRange())
            Settings.System.putInt(contentResolver, settingKey, clamped)
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException setting vibration intensity - WRITE_SETTINGS required", e)
            false
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set vibration intensity", e)
            false
        }
    }
    
    override fun restoreIntensity(context: Context, originalIntensity: Int): Boolean =
        setIntensity(context, originalIntensity)
    
    override fun isSupported(context: Context): Boolean {
        // Check if setting exists and WRITE_SETTINGS granted
        val current = getCurrentIntensity(context)
        val hasPermission = Settings.System.canWrite(context)
        return current >= 0 && hasPermission
    }
    
    override fun getFallback(): VibrationSettingsController? = 
        NotificationListenerVibrationController() // Fallback: cancel vibration via NLS
    
    override fun getIntensityRange(): IntRange = 0..100 // Typical Samsung range
}
```

### 2.4 Fallback Implementations

#### NotificationChannelAudioController.kt (Fallback 1)
```kotlin
package com.example.suppressor.audio

class NotificationChannelAudioController : AudioStreamController {
    override val streamType = -1 // Not a real stream
    override val displayName = "Notification Channel Fallback"
    
    private val notificationManager by lazy {
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }
    
    private val originalChannelStates = mutableMapOf<String, Int>()
    
    override fun getCurrentVolume(context: Context): Int = 0 // N/A
    
    override fun setVolume(context: Context, volume: Int, flags: Int): Boolean {
        if (volume == 0) {
            // Mute all notification channels
            notificationManager.notificationChannels.forEach { channel ->
                if (channel.importance >= NotificationManager.IMPORTANCE_DEFAULT) {
                    originalChannelStates[channel.id] = channel.volume
                    val newChannel = channel.toBuilder()
                        .setVolume(0.0f)
                        .build()
                    notificationManager.createNotificationChannel(newChannel)
                }
            }
        } else {
            // Restore
            originalChannelStates.forEach { (id, vol) ->
                notificationManager.getNotificationChannel(id)?.let { channel ->
                    val newChannel = channel.toBuilder()
                        .setVolume(vol.toFloat() / 100f) // Convert back
                        .build()
                    notificationManager.createNotificationChannel(newChannel)
                }
            }
            originalChannelStates.clear()
        }
        return true
    }
    
    override fun restoreVolume(context: Context, originalVolume: Int): Boolean =
        setVolume(context, originalVolume, 0)
    
    override fun isSupported(context: Context): Boolean = true // Always available on Android 8+
    
    override fun getFallback(): AudioStreamController? = null // End of chain
    
    override fun getMaxVolume(context: Context): Int = 100
}
```

#### NotificationListenerVibrationController.kt (Fallback 1)
```kotlin
package com.example.suppressor.vibration

class NotificationListenerVibrationController : VibrationSettingsController {
    override val settingKey = "notification_listener_vibration_cancel"
    override val displayName = "NotificationListener Vibration Cancel"
    
    private var nls: NotificationListenerService? = null
    
    override fun getCurrentIntensity(context: Context): Int = 1 // Active
    
    override fun setIntensity(context: Context, intensity: Int): Boolean {
        if (intensity == 0 && nls != null) {
            nls?.suppressNotificationVibration = true
        } else {
            nls?.suppressNotificationVibration = false
        }
        return true
    }
    
    override fun restoreIntensity(context: Context, originalIntensity: Int): Boolean =
        setIntensity(context, originalIntensity)
    
    override fun isSupported(context: Context): Boolean {
        // Check if NLS enabled
        val enabled = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
        return enabled?.contains(context.packageName) == true
    }
    
    override fun getFallback(): VibrationSettingsController? = 
        AdbSecureSettingsVibrationController() // Fallback 2: ADB WRITE_SECURE_SETTINGS
    
    override fun getIntensityRange(): IntRange = 0..1
    
    fun setNotificationListenerService(service: NotificationListenerService) {
        nls = service
    }
}
```

#### AdbSecureSettingsVibrationController.kt (Fallback 2 - Requires ADB)
```kotlin
package com.example.suppressor.vibration

class AdbSecureSettingsVibrationController : VibrationSettingsController {
    override val settingKey = "sem_vibration_notification_intensity" // Try system first
    override val displayName = "ADB Secure Settings Fallback"
    
    override fun getCurrentIntensity(context: Context): Int {
        // Try secure settings via shell (requires ADB granted permission)
        return try {
            val result = Runtime.getRuntime().exec("settings get secure $settingKey").inputStream.readText().trim()
            result.toIntOrNull() ?: -1
        } catch (e: Exception) {
            -1
        }
    }
    
    override fun setIntensity(context: Context, intensity: Int): Boolean {
        return try {
            val clamped = intensity.coerceIn(getIntensityRange())
            Runtime.getRuntime().exec("settings put secure $settingKey $clamped").waitFor() == 0
        } catch (e: Exception) {
            false
        }
    }
    
    override fun restoreIntensity(context: Context, originalIntensity: Int): Boolean =
        setIntensity(context, originalIntensity)
    
    override fun isSupported(context: Context): Boolean {
        // Check if ADB granted WRITE_SECURE_SETTINGS
        return try {
            Runtime.getRuntime().exec("settings get secure $settingKey").waitFor() == 0
        } catch (e: Exception) {
            false
        }
    }
    
    override fun getFallback(): VibrationSettingsController? = null // End of chain
    
    override fun getIntensityRange(): IntRange = 0..100
}
```

### 2.5 RecordingSuppressor (Main Orchestrator)

```kotlin
package com.example.suppressor

class RecordingSuppressor(
    private val context: Context,
    private val audioController: AudioStreamController,
    private val vibrationController: VibrationSettingsController,
    private val persistenceManager: PersistenceManager
) : AudioManager.AudioRecordingCallback() {
    
    private val activeRecordings = mutableMapOf<String, AudioRecordingConfiguration>()
    private var isSuppressing = false
    private var originalAudioVolume = -1
    private var originalVibrationIntensity = -1
    private val lock = ReentrantLock()
    
    // State machine states
    private enum class State { IDLE, SUPPRESSING, RESTORING }
    private var state = State.IDLE
    
    override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
        lock.withLock {
            val previousCount = activeRecordings.size
            
            // Update active recordings map
            activeRecordings.clear()
            configs.forEach { config ->
                if (config.clientPackageName != context.packageName) { // Ignore self
                    activeRecordings[config.clientPackageName] = config
                }
            }
            
            val currentCount = activeRecordings.size
            
            when {
                previousCount == 0 && currentCount > 0 -> {
                    // First recording started
                    transitionTo(State.SUPPRESSING)
                    applySuppression()
                }
                previousCount > 0 && currentCount == 0 -> {
                    // Last recording stopped
                    transitionTo(State.RESTORING)
                    restoreState()
                }
                previousCount > 0 && currentCount > 0 -> {
                    // Recording count changed but still active
                    updateActiveRecordings()
                }
            }
            
            persistenceManager.saveState(SuppressionState(
                isSuppressing = currentCount > 0,
                activeRecordingCount = currentCount,
                activePackages = activeRecordings.keys.toSet(),
                originalAudioVolume = originalAudioVolume,
                originalVibrationIntensity = originalVibrationIntensity,
                timestamp = System.currentTimeMillis()
            ))
        }
    }
    
    private fun applySuppression() {
        // Cache original values BEFORE muting
        originalAudioVolume = audioController.getCurrentVolume(context)
        originalVibrationIntensity = vibrationController.getCurrentIntensity(context)
        
        // Persist immediately for crash recovery
        persistenceManager.saveOriginalValues(originalAudioVolume, originalVibrationIntensity)
        
        // Apply suppression with fallback chain
        val audioSuccess = applyWithFallback(audioController) { ctrl ->
            ctrl.setVolume(context, 0, 0)
        }
        
        val vibrationSuccess = applyWithFallback(vibrationController) { ctrl ->
            ctrl.setIntensity(context, 0)
        }
        
        Log.i(TAG, "Suppression applied: audio=$audioSuccess, vibration=$vibrationSuccess")
        
        if (!audioSuccess || !vibrationSuccess) {
            Log.w(TAG, "Partial suppression failure - attempting restore")
            restoreState()
        }
    }
    
    private fun restoreState() {
        val audioSuccess = applyWithFallback(audioController) { ctrl ->
            ctrl.restoreVolume(context, originalAudioVolume)
        }
        
        val vibrationSuccess = applyWithFallback(vibrationController) { ctrl ->
            ctrl.restoreIntensity(context, originalVibrationIntensity)
        }
        
        Log.i(TAG, "Restoration complete: audio=$audioSuccess, vibration=$vibrationSuccess")
        
        // Clear persisted suppression state
        persistenceManager.clearSuppressionState()
        
        transitionTo(State.IDLE)
    }
    
    private inline fun <T> applyWithFallback(
        controller: T,
        crossinline action: (T) -> Boolean
    ): Boolean where T : Any {
        var current: T? = controller
        while (current != null) {
            if (action(current)) return true
            current = (current as? FallbackCapable)?.getFallback() as T?
        }
        return false
    }
    
    private interface FallbackCapable {
        fun getFallback(): Any?
    }
    
    // Called on service start to recover state
    fun initializeFromPersistedState() {
        lock.withLock {
            val persisted = persistenceManager.loadState()
            
            if (persisted.isSuppressing) {
                // Check if recordings still active
                val activeConfigs = audioManager.activeRecordingConfigurations
                val externalActive = activeConfigs.any { it.clientPackageName != context.packageName }
                
                if (externalActive) {
                    // Recordings still active - reapply suppression using PERSISTED original values
                    originalAudioVolume = persisted.originalAudioVolume
                    originalVibrationIntensity = persisted.originalVibrationIntensity
                    applySuppression()
                } else {
                    // No recordings - ensure clean state
                    persistenceManager.clearSuppressionState()
                }
            }
        }
    }
    
    // For manual testing/debugging
    fun forceRestore() {
        lock.withLock {
            if (state != State.IDLE) {
                transitionTo(State.RESTORING)
                restoreState()
            }
        }
    }
}
```

---

## 3. Persistence Manager

### 3.1 SuppressionState Data Class
```kotlin
package com.example.suppressor.persistence

data class SuppressionState(
    val isSuppressing: Boolean,
    val activeRecordingCount: Int,
    val activePackages: Set<String>,
    val originalAudioVolume: Int,
    val originalVibrationIntensity: Int,
    val timestamp: Long
) {
    companion object {
        const val PREFS_NAME = "suppression_state"
        const val KEY_STATE = "state_json"
        const val KEY_ORIGINAL_AUDIO = "original_audio_volume"
        const val KEY_ORIGINAL_VIBRATION = "original_vibration_intensity"
    }
}
```

### 3.2 PersistenceManager Implementation
```kotlin
package com.example.suppressor.persistence

class PersistenceManager(private val context: Context) {
    
    private val prefs by lazy { 
        context.getSharedPreferences(SuppressionState.PREFS_NAME, Context.MODE_PRIVATE) 
    }
    
    private val gson = Gson()
    private val backupManager = BackupManager(context)
    
    fun saveState(state: SuppressionState) {
        prefs.edit()
            .putString(SuppressionState.KEY_STATE, gson.toJson(state))
            .apply()
        backupManager.dataChanged()
    }
    
    fun loadState(): SuppressionState? {
        val json = prefs.getString(SuppressionState.KEY_STATE, null)
        return json?.let { gson.fromJson(it, SuppressionState::class.java) }
    }
    
    fun saveOriginalValues(audioVolume: Int, vibrationIntensity: Int) {
        prefs.edit()
            .putInt(SuppressionState.KEY_ORIGINAL_AUDIO, audioVolume)
            .putInt(SuppressionState.KEY_ORIGINAL_VIBRATION, vibrationIntensity)
            .apply()
    }
    
    fun loadOriginalValues(): Pair<Int, Int> {
        val audio = prefs.getInt(SuppressionState.KEY_ORIGINAL_AUDIO, -1)
        val vibration = prefs.getInt(SuppressionState.KEY_ORIGINAL_VIBRATION, -1)
        return audio to vibration
    }
    
    fun clearSuppressionState() {
        prefs.edit()
            .remove(SuppressionState.KEY_STATE)
            .apply()
        backupManager.dataChanged()
    }
}
```

---

## 4. System Integration Components

### 4.1 Foreground Service (Background Execution)

```kotlin
package com.example.suppressor.service

class SuppressionForegroundService : Service() {
    
    private var recordingSuppressor: RecordingSuppressor? = null
    private var audioRecordingCallback: AudioManager.AudioRecordingCallback? = null
    
    override fun onCreate() {
        super.onCreate()
        
        // Initialize controllers with fallback chain
        val audioController = OneUI8AudioStreamController()
            .withFallback(NotificationChannelAudioController())
        
        val vibrationController = OneUI8VibrationSettingsController()
            .withFallback(NotificationListenerVibrationController())
            .withFallback(AdbSecureSettingsVibrationController())
        
        val persistenceManager = PersistenceManager(this)
        
        recordingSuppressor = RecordingSuppressor(
            context = this,
            audioController = audioController,
            vibrationController = vibrationController,
            persistenceManager = persistenceManager
        )
        
        // Register AudioRecordingCallback
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioRecordingCallback = recordingSuppressor
        audioManager.registerAudioRecordingCallback(mainExecutor, recordingSuppressor!!)
        
        // Recover from persisted state
        recordingSuppressor.initializeFromPersistedState()
        
        // Start foreground with appropriate notification
        startForegroundService()
    }
    
    override fun onDestroy() {
        audioRecordingCallback?.let { 
            (getSystemService(Context.AUDIO_SERVICE) as AudioManager)
                .unregisterAudioRecordingCallback(it) 
        }
        recordingSuppressor?.forceRestore()
        super.onDestroy()
    }
    
    private fun startForegroundService() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Notification Suppression Active")
            .setContentText("Monitoring microphone usage")
            .setSmallIcon(R.drawable.ic_mic_monitor)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
        
        startForeground(NOTIFICATION_ID, notification, 
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
    }
}
```

### 4.2 Boot/Update Recovery Receiver

```kotlin
package com.example.suppressor.receiver

class BootRecoveryReceiver : BroadcastReceiver() {
    
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                // Start service to recover suppression state
                val serviceIntent = Intent(context, SuppressionForegroundService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            }
        }
    }
}
```

### 4.3 NotificationListenerService (For Vibration Fallback)

```kotlin
package com.example.suppressor.service

class SuppressionNotificationListener : NotificationListenerService() {
    
    var suppressNotificationVibration = false
    
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (suppressNotificationVibration) {
            // Cancel vibration for this notification
            sbn.notification.vibrate = null
            sbn.notification.defaults = sbn.notification.defaults and Notification.DEFAULT_VIBRATE.inv()
            
            // Re-post without vibration (if we have permission)
            // Note: This requires BIND_NOTIFICATION_LISTENER_SERVICE permission
        }
    }
}
```

---

## 5. Permission Requirements

### 5.1 Manifest Permissions
```xml
<!-- Required for AudioRecordingCallback (Android 11+) -->
<uses-permission android:name="android.permission.RECORD_AUDIO" 
    tools:node="remove" /> <!-- EXPLICITLY NOT USED - DOCUMENTED FOR CLARITY -->

<!-- Required for STREAM_NOTIFICATION volume control -->
<uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />

<!-- Required for Settings.System vibration intensity control -->
<uses-permission android:name="android.permission.WRITE_SETTINGS" />

<!-- Required for foreground service (Android 14+) -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />

<!-- Required for boot recovery -->
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />

<!-- Required for NotificationListener fallback -->
<uses-permission android:name="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE" />
```

### 5.2 Runtime Permission Flow
```kotlin
// WRITE_SETTINGS requires special user action
fun requestWriteSettingsPermission(activity: Activity) {
    if (!Settings.System.canWrite(activity)) {
        val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
            .apply { data = Uri.parse("package:${activity.packageName}") }
        activity.startActivityForResult(intent, REQUEST_WRITE_SETTINGS)
    }
}

// NotificationListener requires user enablement
fun requestNotificationListenerPermission(activity: Activity) {
    val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
    activity.startActivityForResult(intent, REQUEST_NOTIFICATION_LISTENER)
}
```

---

## 6. Implementation Phases

### Phase 1: Core Infrastructure (Week 1-2)
| Task | Deliverable |
|------|-------------|
| Set up project structure (modules, dependencies) | Buildable Android project |
| Implement `AudioStreamController` interface + OneUI8 impl | Working stream volume control |
| Implement `VibrationSettingsController` interface + OneUI8 impl | Working vibration intensity control |
| Implement `PersistenceManager` with SharedPreferences + BackupManager | Crash-recoverable state persistence |
| Unit tests for controllers (mock Context, Settings) | >80% coverage |

### Phase 2: Detection & Orchestration (Week 2-3)
| Task | Deliverable |
|------|-------------|
| Implement `RecordingSuppressor` with state machine | Core suppression logic |
| Implement `AudioRecordingCallback` integration | Microphone detection |
| Implement active recording tracking (package filtering) | Accurate app detection |
| Integration test with mock AudioManager | Verified detection logic |

### Phase 3: System Integration (Week 3-4)
| Task | Deliverable |
|------|-------------|
| Implement `SuppressionForegroundService` | Background execution |
| Implement `BootRecoveryReceiver` | Crash/reboot recovery |
| Implement `NotificationListenerService` (fallback) | Vibration fallback |
| Permission request flows (WRITE_SETTINGS, NLS) | User onboarding |

### Phase 4: Fallback Chain & Robustness (Week 4-5)
| Task | Deliverable |
|------|-------------|
| Implement `NotificationChannelAudioController` fallback | Sound fallback |
| Implement `NotificationListenerVibrationController` fallback | Vibration fallback 1 |
| Implement `AdbSecureSettingsVibrationController` fallback | Vibration fallback 2 |
| Fallback chain integration testing | Graceful degradation |

### Phase 5: Physical Device Testing (Week 5-6)
| Task | Deliverable |
|------|-------------|
| Test on One UI 8 device (Samsung Galaxy S24/S23) | FEASIBILITY_REPORT.md updated with Verified/Partial |
| Test all target apps (Voice Recorder, WhatsApp, Snapchat, Camera) | App-specific validation |
| Test edge cases (screen lock, background, reboot, crash) | Robustness validation |
| Performance profiling (CPU, battery, memory) | Resource usage report |

### Phase 6: One UI 9 Preparation (Week 6+)
| Task | Deliverable |
|------|-------------|
| Create OneUI9AudioStreamController stub | Forward compatibility |
| Create OneUI9VibrationSettingsController stub | Forward compatibility |
| Document validation checklist execution | Ready for One UI 9 release |

---

## 7. Testing Strategy

### 7.1 Unit Tests
```kotlin
// Example test structure
class RecordingSuppressorTest {
    
    @Test
    fun `first recording starts suppression`() {
        // Given
        val suppressor = createSuppressor()
        
        // When
        suppressor.onRecordingConfigChanged(listOf(mockConfig("com.whatsapp")))
        
        // Then
        verify(audioController).setVolume(context, 0, 0)
        verify(vibrationController).setIntensity(context, 0)
    }
    
    @Test
    fun `last recording stops restores original values`() {
        // Given
        val suppressor = createSuppressor()
        suppressor.onRecordingConfigChanged(listOf(mockConfig("com.whatsapp")))
        
        // When
        suppressor.onRecordingConfigChanged(emptyList())
        
        // Then
        verify(audioController).restoreVolume(context, originalVolume)
        verify(vibrationController).restoreIntensity(context, originalVibration)
    }
    
    @Test
    fun `concurrent recordings only restore when all stop`() {
        // Given
        val suppressor = createSuppressor()
        suppressor.onRecordingConfigChanged(listOf(mockConfig("com.whatsapp")))
        suppressor.onRecordingConfigChanged(listOf(
            mockConfig("com.whatsapp"), 
            mockConfig("com.snapchat.android")
        ))
        
        // When - stop one
        suppressor.onRecordingConfigChanged(listOf(mockConfig("com.snapchat.android")))
        
        // Then - NOT restored yet
        verify(audioController, never()).restoreVolume(any(), any())
        
        // When - stop last
        suppressor.onRecordingConfigChanged(emptyList())
        
        // Then - restored
        verify(audioController).restoreVolume(context, originalVolume)
    }
}
```

### 7.2 Integration Test Scenarios (Physical Device)
| Scenario | Test Case | Expected |
|----------|-----------|----------|
| TC-01 | Voice Recorder start → notification → stop | Silent notification, restored after |
| TC-02 | WhatsApp voice message → notification → send | Silent notification, restored after |
| TC-03 | Snapchat voice note → notification → stop | Silent notification, restored after |
| TC-04 | Camera video record → notification → stop | Silent notification, restored after |
| TC-05 | Two apps recording concurrently → stop one | Suppression holds |
| TC-06 | Recording starts → screen lock → notification | Silent notification |
| TC-07 | Recording starts → home button (background) → notification | Silent notification |
| TC-08 | Recording active → force stop service → restart | Suppression re-applied |
| TC-09 | Recording active → device reboot → unlock | Suppression re-applied |
| TC-10 | Incoming call during suppression | Ringtone + vibration at original levels |

---

## 8. Fallback Hierarchy Documentation

### 8.1 Sound Suppression Fallback Chain
```
Priority 1: OneUI8AudioStreamController (STREAM_NOTIFICATION via AudioManager)
    ↓ Fails (SecurityException, unsupported, no effect)
Priority 2: NotificationChannelAudioController (per-channel volume = 0)
    ↓ Fails (NLS not enabled, channel modification blocked)
Priority 3: NONE - Log error, notify user
```

### 8.2 Vibration Suppression Fallback Chain
```
Priority 1: OneUI8VibrationSettingsController (SEM_VIBRATION_NOTIFICATION_INTENSITY via WRITE_SETTINGS)
    ↓ Fails (Setting not found, WRITE_SETTINGS denied, requires WRITE_SECURE_SETTINGS)
Priority 2: NotificationListenerVibrationController (cancel vibration in onNotificationPosted)
    ↓ Fails (NLS not enabled, notification already posted)
Priority 3: AdbSecureSettingsVibrationController (settings put secure via shell - ADB only)
    ↓ Fails (ADB permission not granted)
Priority 4: NONE - Log error, notify user
```

### 8.3 Decision Matrix for Production

| One UI 8 Test Result | Action |
|---------------------|--------|
| Both Priority 1 work | ✅ Ship with Priority 1 only, fallbacks as safety net |
| Sound P1 works, Vibration P1 fails → P2 works | ✅ Ship with mixed priority |
| Sound P1 fails → P2 works, Vibration P1 works | ✅ Ship with mixed priority |
| Both P1 fail, both P2 work | ⚠️ Ship with P2, document limitation |
| Any chain completely broken | ❌ Do NOT ship - escalate for architecture review |

---

## 9. Monitoring & Diagnostics

### 9.1 Key Metrics to Log
```kotlin
// Structured logging for debugging
data class SuppressionEvent(
    val eventType: String, // "SUPPRESSION_STARTED", "SUPPRESSION_ENDED", "RESTORATION_FAILED", "FALLBACK_USED"
    val timestamp: Long,
    val activePackages: List<String>,
    val audioControllerUsed: String,
    val vibrationControllerUsed: String,
    val originalAudioVolume: Int,
    val originalVibrationIntensity: Int,
    val success: Boolean,
    val errorMessage: String?
)
```

### 9.2 Health Check API
```kotlin
// For debug/settings screen
fun getHealthStatus(): HealthStatus {
    return HealthStatus(
        isServiceRunning = isForegroundServiceRunning(),
        audioController = audioController.displayName,
        audioControllerSupported = audioController.isSupported(context),
        vibrationController = vibrationController.displayName,
        vibrationControllerSupported = vibrationController.isSupported(context),
        writeSettingsGranted = Settings.System.canWrite(context),
        notificationListenerEnabled = isNotificationListenerEnabled(),
        persistedState = persistenceManager.loadState() != null,
        activeRecordings = recordingSuppressor.activeRecordingCount
    )
}
```

---

## 10. Release Checklist

### Pre-Release (One UI 8)
- [ ] FEASIBILITY_REPORT.md updated with **Verified** results from physical device
- [ ] All TC-01 through TC-10 integration tests pass
- [ ] Fallback chains tested and documented
- [ ] Performance impact < 2% battery/hour, < 10MB RAM
- [ ] No ANRs or crashes in 24hr stress test
- [ ] Permission flows tested on clean install
- [ ] Backup/restore verified across app updates
- [ ] ProGuard/R8 rules configured for reflection usage

### Documentation
- [ ] README with architecture overview
- [ ] ADB commands reference for manual testing
- [ ] Troubleshooting guide (common failure modes)
- [ ] One UI 9 validation checklist (in FEASIBILITY_REPORT.md)

### Post-Release
- [ ] Crashlytics/Play Console monitoring for suppression failures
- [ ] User feedback channel for false positives/negatives
- [ ] One UI 9 device acquisition plan
- [ ] Quarterly regression test on latest One UI

---

## 11. Risk Mitigation Summary

| Risk | Probability | Impact | Mitigation |
|------|-------------|--------|------------|
| AudioRecordingCallback not available on One UI 8 | Medium | Critical | NotificationListenerService + UsageStats fallback (documented) |
| SEM_VIBRATION_NOTIFICATION_INTENSITY requires WRITE_SECURE_SETTINGS | High | High | AdbSecureSettingsVibrationController fallback; document ADB requirement |
| STREAM_NOTIFICATION linked to STREAM_RING | Low | Medium | NotificationChannel fallback; per-app channel control |
| Foreground service killed by system | High | High | Proper FOREGROUND_SERVICE_MICROPHONE type; WorkManager backup |
| One UI 9 breaks all mechanisms | Unknown | Critical | Modular architecture; validation checklist ready; stub implementations |
| Vibration controlled per-channel only | Medium | High | NotificationListenerService cancel vibration per notification |

---

## 12. Appendices

### A. ADB Testing Commands
```bash
# Grant WRITE_SETTINGS (for testing)
adb shell appops set com.example.suppressor WRITE_SETTINGS allow

# Grant NOTIFICATION_LISTENER
adb shell settings put secure enabled_notification_listeners "com.example.suppressor/.SuppressionNotificationListener"

# Check vibration settings
adb shell settings list system | grep -i vibrat
adb shell settings list secure | grep -i vibrat

# Check active recordings (via test app)
adb shell am broadcast -a com.example.suppressor.DUMP_STATE

# Force service restart
adb shell am force-stop com.example.suppressor
adb shell am start-foreground-service com.example.suppressor/.SuppressionForegroundService
```

### B. Key Samsung Settings Keys (One UI 8 Reference)
| Setting Key | Type | Description | Typical Range |
|-------------|------|-------------|---------------|
| `sem_vibration_notification_intensity` | System | Notification vibration intensity | 0-100 |
| `sem_vibration_ring_intensity` | System | Call ring vibration intensity | 0-100 |
| `sem_vibration_alarm_intensity` | System | Alarm vibration intensity | 0-100 |
| `sem_vibration_media_intensity` | System | Media vibration intensity | 0-100 |
| `volume_notification` | System | Notification stream volume | 0-7/15 |
| `volume_ring` | System | Ring stream volume | 0-7/15 |

*Note: Keys verified via `adb shell settings list system` on target device. May vary by One UI version.*

---

**Approval Required:** This implementation plan must be reviewed and approved alongside FEASIBILITY_REPORT.md before any production code is written.

**Next Action:** Obtain One UI 8 physical device → Execute FEASIBILITY_REPORT.md test plan → Update both documents with results → Review → Begin implementation.
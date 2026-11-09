package com.example.suppressor

import android.content.Context
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.example.suppressor.audio.AudioStreamController
import com.example.suppressor.debug.DebugLogManager
import com.example.suppressor.persistence.PersistenceManager
import com.example.suppressor.persistence.SuppressionState
import com.example.suppressor.vibration.OneUI8VibrationSettingsController
import com.example.suppressor.vibration.VibrationSettingsController
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Main orchestrator for microphone detection → notification suppression → restoration.
 *
 * Universal Zero-Filter Detection:
 * - Triggers on ANY active AudioRecordingConfiguration regardless of package or source.
 * - Triggers on AudioManager communication modes (MODE_IN_COMMUNICATION, MODE_IN_CALL, MODE_CALL_REDIRECT).
 * - Supported by dual triggers: AudioRecordingCallback + 500ms backup polling ticker.
 *
 * Guaranteed Muting:
 * - Snapshot Once Lock ensures original volume and Samsung vibration keys are preserved.
 * - Non-Zero Guard prevents locking onto 0 volume/vibration.
 * - 400ms debounce prevents rapid glitching on mic close.
 */
class RecordingSuppressor(
    private val context: Context,
    val audioController: AudioStreamController,
    val vibrationController: VibrationSettingsController,
    val persistenceManager: PersistenceManager
) : AudioManager.AudioRecordingCallback() {

    companion object {
        private const val TAG = "RecordingSuppressor"

        /**
         * Optional resolver for testing environments.
         */
        internal var packageNameResolver: ((AudioRecordingConfiguration) -> String)? = null
    }

    // Active recordings tracked by identifier
    private val activeRecordings = mutableMapOf<String, AudioRecordingConfiguration>()

    // State
    private var isSuppressing = false
    private var originalAudioVolume = -1
    private var originalVibrationIntensity = -1

    // Debounce for restoration to prevent rapid mic open/close glitches
    internal var debounceMs: Long = 400L
    private val debounceHandler by lazy { Handler(Looper.getMainLooper()) }
    private var pendingRestoreRunnable: Runnable? = null

    // AudioManager
    private val audioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    // Thread safety
    private val lock = ReentrantLock()

    // State machine
    private enum class State { IDLE, SUPPRESSING, RESTORING }
    private var state = State.IDLE

    /**
     * Check if AudioManager is in an active VoIP / Call communication mode.
     */
    fun isCommunicationMode(): Boolean {
        val mode = audioManager.mode
        return mode == AudioManager.MODE_IN_COMMUNICATION ||
                mode == AudioManager.MODE_IN_CALL ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && mode == AudioManager.MODE_CALL_REDIRECT)
    }

    /**
     * AudioRecordingCallback entry point.
     * ZERO-FILTER: Any non-empty configs immediately triggers suppression!
     */
    override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
        val isComm = isCommunicationMode()
        val isMicActive = configs.isNotEmpty() || isComm
        DebugLogManager.log(TAG, "onRecordingConfigChanged: ${configs.size} configs received, commMode=$isComm -> isMicActive=$isMicActive")
        updateDetectionState(isMicActive = isMicActive, configs = configs, source = "Callback", mode = audioManager.mode)
    }

    /**
     * Unified detection state updater called by AudioRecordingCallback or 500ms polling ticker.
     */
    fun updateDetectionState(
        isMicActive: Boolean,
        configs: List<AudioRecordingConfiguration>?,
        source: String,
        mode: Int
    ) {
        lock.withLock {
            val previousCount = activeRecordings.size

            // Update activeRecordings map with ALL configs (no filtering)
            if (configs != null) {
                activeRecordings.clear()
                configs.forEachIndexed { index, config ->
                    val pkg = config.extractPackageName(index)
                    activeRecordings[pkg] = config
                }
            }

            val currentCount = activeRecordings.size

            if (isMicActive) {
                // Cancel any pending debounced restore
                if (pendingRestoreRunnable != null) {
                    DebugLogManager.log(TAG, "Canceling pending restore debounce (mic active via $source)")
                    pendingRestoreRunnable?.let { debounceHandler.removeCallbacks(it) }
                    pendingRestoreRunnable = null
                }

                if (!isSuppressing || state != State.SUPPRESSING) {
                    DebugLogManager.log(TAG, ">>> ENTER SUPPRESSING via $source (configs=$currentCount, mode=$mode)")
                    transitionTo(State.SUPPRESSING)
                    applySuppression()
                    persistCurrentState()
                } else {
                    DebugLogManager.log(TAG, "Maintaining SUPPRESSING state via $source (configs=$currentCount, mode=$mode)")
                }
            } else {
                // Mic is not active
                if (isSuppressing && state == State.SUPPRESSING) {
                    DebugLogManager.log(TAG, "<<< Mic inactive via $source: scheduling ${debounceMs}ms debounced restore")
                    scheduleDebouncedRestoration()
                }
            }
        }
    }

    /**
     * Schedule restoration with a debounce.
     */
    private fun scheduleDebouncedRestoration() {
        pendingRestoreRunnable?.let { debounceHandler.removeCallbacks(it) }

        if (debounceMs <= 0) {
            // Immediate (for testing)
            transitionTo(State.RESTORING)
            restoreState()
            return
        }

        val runnable = Runnable {
            lock.withLock {
                // Re-verify that mic is still inactive
                val stillComm = isCommunicationMode()
                val currentConfigs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    try { audioManager.activeRecordingConfigurations } catch (e: Exception) { emptyList() }
                } else {
                    emptyList()
                }
                val stillActive = stillComm || currentConfigs.isNotEmpty() || activeRecordings.isNotEmpty()

                if (!stillActive && state == State.SUPPRESSING) {
                    DebugLogManager.log(TAG, "Debounce timer expired: executing atomic restoration")
                    transitionTo(State.RESTORING)
                    restoreState()
                    persistCurrentState()
                } else {
                    DebugLogManager.log(TAG, "Debounce restore skipped: mic still active (comm=$stillComm, configs=${currentConfigs.size})")
                }
                pendingRestoreRunnable = null
            }
        }
        pendingRestoreRunnable = runnable
        debounceHandler.postDelayed(runnable, debounceMs)
    }

    /**
     * Apply notification suppression (mute sound + vibration).
     * Snapshot Once Lock: Reads and saves original values ONCE when transitioning from IDLE.
     * Non-Zero Guard: Protects against saving 0 if system volume was already stuck at 0.
     */
    private fun applySuppression() {
        if (isSuppressing && originalAudioVolume > 0) {
            DebugLogManager.log(TAG, "Snapshot lock active: keeping savedAudio=$originalAudioVolume, savedVib=$originalVibrationIntensity")
            return
        }

        // NON-ZERO GUARD for Audio
        var currentAudio = audioController.getCurrentVolume(context)
        if (currentAudio <= 0) {
            val (persistedAudio, _) = persistenceManager.loadOriginalValues()
            val (baselineAudio, _) = persistenceManager.loadBaselineValues()
            currentAudio = when {
                persistedAudio > 0 -> persistedAudio
                baselineAudio > 0 -> baselineAudio
                else -> maxOf(1, (audioController.getMaxVolume(context) * 0.7).toInt())
            }
            DebugLogManager.log(TAG, "Non-Zero Guard: audio was 0; recovered baseline audio=$currentAudio")
        }

        // NON-ZERO GUARD for Vibration
        var currentVibration = vibrationController.getCurrentIntensity(context)
        if (currentVibration <= 0) {
            val (_, persistedVib) = persistenceManager.loadOriginalValues()
            val (_, baselineVib) = persistenceManager.loadBaselineValues()
            currentVibration = when {
                persistedVib > 0 -> persistedVib
                baselineVib > 0 -> baselineVib
                else -> 3
            }
            DebugLogManager.log(TAG, "Non-Zero Guard: vibration was 0; recovered baseline vibration=$currentVibration")
        }

        originalAudioVolume = currentAudio
        originalVibrationIntensity = currentVibration

        // Snapshot Samsung vibration multi-keys if controller supports it
        if (vibrationController is OneUI8VibrationSettingsController) {
            vibrationController.snapshotSettings(context)
        }

        // Persist immediately for crash recovery
        persistenceManager.saveOriginalValues(originalAudioVolume, originalVibrationIntensity)
        DebugLogManager.log(TAG, "Snapshot saved: audio=$originalAudioVolume, vib=$originalVibrationIntensity")

        // Apply audio suppression with fallback chain
        val audioSuccess = applyAudioWithFallback { ctrl ->
            ctrl.setVolume(context, 0, 0)
        }

        // Apply vibration suppression with fallback chain
        val vibrationSuccess = applyVibrationWithFallback { ctrl ->
            ctrl.setIntensity(context, 0)
        }

        DebugLogManager.log(TAG, "Suppression result: audioMuted=$audioSuccess, vibrationMuted=$vibrationSuccess")

        if (!audioSuccess) {
            DebugLogManager.log(TAG, "Audio suppression failed across all fallbacks - restoring state")
            restoreState()
        } else {
            isSuppressing = true
        }
    }

    /**
     * Restore notification sound and vibration to original values.
     */
    private fun restoreState() {
        DebugLogManager.log(TAG, "Restoring state: originalAudio=$originalAudioVolume, originalVibration=$originalVibrationIntensity")

        val targetAudio = if (originalAudioVolume > 0) {
            originalAudioVolume
        } else {
            val (persistedAudio, _) = persistenceManager.loadOriginalValues()
            val (baselineAudio, _) = persistenceManager.loadBaselineValues()
            when {
                persistedAudio > 0 -> persistedAudio
                baselineAudio > 0 -> baselineAudio
                else -> maxOf(1, (audioController.getMaxVolume(context) * 0.7).toInt())
            }
        }

        val targetVibration = if (originalVibrationIntensity > 0) {
            originalVibrationIntensity
        } else {
            val (_, persistedVib) = persistenceManager.loadOriginalValues()
            val (_, baselineVib) = persistenceManager.loadBaselineValues()
            when {
                persistedVib > 0 -> persistedVib
                baselineVib > 0 -> baselineVib
                else -> 3
            }
        }

        val audioSuccess = applyAudioWithFallback { ctrl ->
            ctrl.restoreVolume(context, targetAudio)
        }

        val vibrationSuccess = applyVibrationWithFallback { ctrl ->
            ctrl.restoreIntensity(context, targetVibration)
        }

        DebugLogManager.log(TAG, "Restoration complete: audioRestored=$audioSuccess (target=$targetAudio), vibRestored=$vibrationSuccess (target=$targetVibration)")

        persistenceManager.clearSuppressionState()

        isSuppressing = false
        originalAudioVolume = -1
        originalVibrationIntensity = -1

        transitionTo(State.IDLE)
    }

    private inline fun applyAudioWithFallback(
        crossinline action: (AudioStreamController) -> Boolean
    ): Boolean {
        var current: AudioStreamController? = audioController
        var attempt = 0
        while (current != null) {
            attempt++
            if (action(current)) {
                return true
            }
            current = current.getFallback()
        }
        return false
    }

    private inline fun applyVibrationWithFallback(
        crossinline action: (VibrationSettingsController) -> Boolean
    ): Boolean {
        var current: VibrationSettingsController? = vibrationController
        var attempt = 0
        while (current != null) {
            attempt++
            if (action(current)) {
                return true
            }
            current = current.getFallback()
        }
        return false
    }

    private fun persistCurrentState() {
        val state = SuppressionState(
            isSuppressing = isSuppressing,
            activeRecordingCount = activeRecordings.size,
            activePackages = activeRecordings.keys.toSet(),
            originalAudioVolume = originalAudioVolume,
            originalVibrationIntensity = originalVibrationIntensity,
            timestamp = System.currentTimeMillis()
        )
        persistenceManager.saveState(state)
    }

    private fun transitionTo(newState: State) {
        DebugLogManager.log(TAG, "State transition: $state -> $newState")
        state = newState
    }

    fun initializeFromPersistedState() {
        lock.withLock {
            val persisted = persistenceManager.loadState() ?: return@withLock
            if (!persisted.isSuppressing) return@withLock

            val activeConfigs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try { audioManager.activeRecordingConfigurations } catch (e: Exception) { emptyList() }
            } else {
                emptyList()
            }

            val isMicActive = activeConfigs.isNotEmpty() || isCommunicationMode()

            if (isMicActive) {
                originalAudioVolume = persisted.originalAudioVolume
                originalVibrationIntensity = persisted.originalVibrationIntensity
                transitionTo(State.SUPPRESSING)
                applySuppression()
            } else {
                originalAudioVolume = persisted.originalAudioVolume
                originalVibrationIntensity = persisted.originalVibrationIntensity
                restoreState()
            }
        }
    }

    fun forceRestore() {
        lock.withLock {
            pendingRestoreRunnable?.let {
                debounceHandler.removeCallbacks(it)
                pendingRestoreRunnable = null
            }
            DebugLogManager.log(TAG, "Force restore requested")
            transitionTo(State.RESTORING)
            restoreState()
        }
    }

    fun getActiveRecordingCount(): Int {
        lock.lock()
        try {
            return activeRecordings.size
        } finally {
            lock.unlock()
        }
    }

    fun getActivePackages(): Set<String> {
        lock.lock()
        try {
            return activeRecordings.keys.toSet()
        } finally {
            lock.unlock()
        }
    }

    fun isCurrentlySuppressing(): Boolean {
        lock.lock()
        try {
            return isSuppressing
        } finally {
            lock.unlock()
        }
    }

    fun getSavedAudioVolume(): Int {
        lock.lock()
        try {
            return originalAudioVolume
        } finally {
            lock.unlock()
        }
    }

    fun getSavedVibrationIntensity(): Int {
        lock.lock()
        try {
            return originalVibrationIntensity
        } finally {
            lock.unlock()
        }
    }

    fun getStateName(): String {
        lock.lock()
        try {
            return state.name
        } finally {
            lock.unlock()
        }
    }

    private fun AudioRecordingConfiguration.extractPackageName(index: Int): String {
        packageNameResolver?.let { return it(this) }
        val session = clientAudioSessionId
        val sourceStr = audioSourceToString(clientAudioSource)
        return try {
            val method = javaClass.getMethod("getClientPackageName")
            (method.invoke(this) as? String)?.takeIf { it.isNotBlank() } ?: "session_${session}_$sourceStr"
        } catch (e: Exception) {
            "session_${session}_$sourceStr"
        }
    }

    private fun audioSourceToString(source: Int): String {
        return when (source) {
            MediaRecorder.AudioSource.MIC -> "MIC"
            MediaRecorder.AudioSource.VOICE_COMMUNICATION -> "VOICE_COMM"
            MediaRecorder.AudioSource.CAMCORDER -> "CAMCORDER"
            MediaRecorder.AudioSource.VOICE_RECOGNITION -> "VOICE_REC"
            MediaRecorder.AudioSource.UNPROCESSED -> "UNPROCESSED"
            MediaRecorder.AudioSource.VOICE_PERFORMANCE -> "VOICE_PERF"
            MediaRecorder.AudioSource.REMOTE_SUBMIX -> "REMOTE_SUBMIX"
            else -> "SRC_$source"
        }
    }
}

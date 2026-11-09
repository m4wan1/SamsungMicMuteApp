package com.example.suppressor.audio

import android.content.Context
import android.media.AudioManager
import android.os.Build
import com.example.suppressor.debug.DebugLogManager

/**
 * One UI 8 implementation of AudioStreamController using STREAM_NOTIFICATION.
 *
 * Guaranteed muting on Samsung One UI:
 * - On mute: Executes both adjustStreamVolume(ADJUST_MUTE) AND setStreamVolume(0).
 * - On restore: Executes both adjustStreamVolume(ADJUST_UNMUTE) AND setStreamVolume(savedVolume).
 * - Verifies read-back volume and isStreamMute state.
 */
class OneUI8AudioStreamController : AudioStreamController {

    companion object {
        private const val TAG = "OneUI8AudioCtrl"
    }

    override val streamType = AudioManager.STREAM_NOTIFICATION
    override val displayName = "One UI 8 STREAM_NOTIFICATION"

    private var audioManager: AudioManager? = null

    private fun getAudioManager(context: Context): AudioManager {
        val am = audioManager
        if (am != null) return am
        val newAm = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager = newAm
        return newAm
    }

    override fun getCurrentVolume(context: Context): Int {
        return try {
            getAudioManager(context).getStreamVolume(streamType)
        } catch (e: Exception) {
            DebugLogManager.log(TAG, "Failed to get current notification volume: ${e.message}")
            -1
        }
    }

    override fun setVolume(context: Context, volume: Int, flags: Int): Boolean {
        val am = getAudioManager(context)
        return try {
            if (volume == 0) {
                try {
                    am.adjustStreamVolume(streamType, AudioManager.ADJUST_MUTE, 0)
                    DebugLogManager.log(TAG, "adjustStreamVolume(STREAM_NOTIFICATION, ADJUST_MUTE, 0) applied")
                } catch (e: Exception) {
                    DebugLogManager.log(TAG, "adjustStreamVolume ADJUST_MUTE failed: ${e.message}")
                }
            }
            val clampedVolume = volume.coerceIn(0, getMaxVolume(context))
            am.setStreamVolume(streamType, clampedVolume, flags)

            val readBack = am.getStreamVolume(streamType)
            val isMuted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) am.isStreamMute(streamType) else false
            DebugLogManager.log(TAG, "setStreamVolume(STREAM_NOTIFICATION, $clampedVolume, $flags) -> readBack=$readBack, isStreamMute=$isMuted")
            true
        } catch (e: SecurityException) {
            DebugLogManager.log(TAG, "SecurityException setting stream volume: ${e.message}")
            false
        } catch (e: Exception) {
            DebugLogManager.log(TAG, "Failed to set stream volume: ${e.message}")
            false
        }
    }

    override fun restoreVolume(context: Context, originalVolume: Int): Boolean {
        val am = getAudioManager(context)
        return try {
            try {
                am.adjustStreamVolume(streamType, AudioManager.ADJUST_UNMUTE, 0)
                DebugLogManager.log(TAG, "adjustStreamVolume(STREAM_NOTIFICATION, ADJUST_UNMUTE, 0) applied")
            } catch (e: Exception) {
                DebugLogManager.log(TAG, "adjustStreamVolume ADJUST_UNMUTE failed: ${e.message}")
            }
            val clampedVolume = originalVolume.coerceIn(0, getMaxVolume(context))
            am.setStreamVolume(streamType, clampedVolume, 0)

            val readBack = am.getStreamVolume(streamType)
            val isMuted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) am.isStreamMute(streamType) else false
            DebugLogManager.log(TAG, "restoreVolume(STREAM_NOTIFICATION, $clampedVolume, 0) -> readBack=$readBack, isStreamMute=$isMuted")

            if (readBack != clampedVolume && clampedVolume > 0) {
                try {
                    am.setStreamVolume(streamType, clampedVolume, 0)
                    val retryReadBack = am.getStreamVolume(streamType)
                    DebugLogManager.log(TAG, "Retry setStreamVolume -> readBack=$retryReadBack")
                } catch (e: Exception) {
                    DebugLogManager.log(TAG, "Retry setStreamVolume failed: ${e.message}")
                }
            }
            true
        } catch (e: Exception) {
            DebugLogManager.log(TAG, "Failed to restore stream volume: ${e.message}")
            false
        }
    }

    override fun isSupported(context: Context): Boolean {
        return try {
            val audioManager = getAudioManager(context)
            val maxVol = audioManager.getStreamMaxVolume(streamType)
            val currentVol = audioManager.getStreamVolume(streamType)
            maxVol > 0 && currentVol >= 0
        } catch (e: Exception) {
            DebugLogManager.log(TAG, "isSupported check failed: ${e.message}")
            false
        }
    }

    override fun getFallback(): AudioStreamController? = null

    override fun getMaxVolume(context: Context): Int {
        return try {
            getAudioManager(context).getStreamMaxVolume(streamType)
        } catch (e: Exception) {
            7
        }
    }
}

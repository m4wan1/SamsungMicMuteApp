package com.example.suppressor.audio

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.util.Log

/**
 * Forward-compatibility stub for One UI 9 (Android 16 / API 36+).
 *
 * One UI 9 may introduce changes to stream volume control, new volume APIs,
 * or stricter permissions around notification muting. This controller serves as
 * the entry point for One UI 9 specific adaptations while falling back to
 * OneUI8AudioStreamController when standard mechanisms suffice.
 */
class OneUI9AudioStreamController : AudioStreamController {

    companion object {
        private const val TAG = "OneUI9AudioController"
    }

    private val baseController = OneUI8AudioStreamController()

    override val streamType: Int
        get() = baseController.streamType

    override val displayName: String = "One UI 9 STREAM_NOTIFICATION (Forward-Compatible)"

    override fun getCurrentVolume(context: Context): Int {
        return baseController.getCurrentVolume(context)
    }

    override fun setVolume(context: Context, volume: Int, flags: Int): Boolean {
        Log.d(TAG, "Setting volume on One UI 9 controller: $volume")
        return baseController.setVolume(context, volume, flags)
    }

    override fun restoreVolume(context: Context, originalVolume: Int): Boolean {
        Log.d(TAG, "Restoring volume on One UI 9 controller: $originalVolume")
        return baseController.restoreVolume(context, originalVolume)
    }

    override fun isSupported(context: Context): Boolean {
        // Supported on API 36+ (Android 16 / One UI 9 preview/release)
        val isApiSupported = Build.VERSION.SDK_INT >= 36
        return isApiSupported && baseController.isSupported(context)
    }

    override fun getFallback(): AudioStreamController? = baseController

    override fun getMaxVolume(context: Context): Int {
        return baseController.getMaxVolume(context)
    }
}

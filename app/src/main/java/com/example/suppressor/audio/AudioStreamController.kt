package com.example.suppressor.audio

import android.content.Context

/**
 * Interface for controlling audio stream volume.
 *
 * This abstraction allows different implementations for different Android versions
 * and fallback mechanisms when primary methods fail.
 *
 * Primary implementation: OneUI8AudioStreamController (uses STREAM_NOTIFICATION via AudioManager)
 * Fallback: NotificationChannelAudioController (uses per-channel volume control)
 */
interface AudioStreamController {

    /** The AudioManager stream type this controller manages (e.g., STREAM_NOTIFICATION) */
    val streamType: Int

    /** Human-readable name for logging/debugging */
    val displayName: String

    /**
     * Get current system volume for this stream (0 to max).
     * @return Current volume level, or -1 if unavailable
     */
    fun getCurrentVolume(context: Context): Int

    /**
     * Set volume for this stream.
     * @param context Application context
     * @param volume Target volume level (will be clamped to valid range)
     * @param flags AudioManager flags (e.g., FLAG_PLAY_SOUND, FLAG_SHOW_UI)
     * @return true if successful, false otherwise
     */
    fun setVolume(context: Context, volume: Int, flags: Int): Boolean

    /**
     * Restore volume to a specific value.
     * @param context Application context
     * @param originalVolume Previously cached original volume
     * @return true if successful, false otherwise
     */
    fun restoreVolume(context: Context, originalVolume: Int): Boolean

    /**
     * Check if this controller is supported on current device/OS.
     * Should verify both API availability and permission state.
     */
    fun isSupported(context: Context): Boolean

    /**
     * Get fallback controller if this one fails, or null if none.
     * Enables fallback chain: Primary → Fallback 1 → Fallback 2 → null
     */
    fun getFallback(): AudioStreamController?

    /**
     * Maximum volume for this stream (for validation/clamping).
     */
    fun getMaxVolume(context: Context): Int

    /**
     * Optional: Configure fallback controller.
     * Appends the fallback to the end of the existing fallback chain.
     */
    fun withFallback(fallback: AudioStreamController): AudioStreamController {
        val existingFallback = getFallback()
        val chained = if (existingFallback != null) {
            existingFallback.withFallback(fallback)
        } else {
            fallback
        }
        return object : AudioStreamController by this {
            override fun getFallback(): AudioStreamController? = chained
        }
    }
}
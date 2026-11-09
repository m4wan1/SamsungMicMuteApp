package com.example.suppressor.audio

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.net.Uri
import android.util.Log

/**
 * Fallback AudioStreamController using per-notification-channel volume/sound control.
 *
 * When STREAM_NOTIFICATION control fails or is unavailable, this controller
 * mutes notification channels by removing their sound configuration.
 *
 * Works on Android 8.0+ (API 26+)
 *
 * Original channel sound URIs and audio attributes are cached and restored when suppression ends.
 */
class NotificationChannelAudioController : AudioStreamController {

    companion object {
        private const val TAG = "ChannelAudioController"
    }

    override val streamType = -1 // Not a real AudioManager stream
    override val displayName = "Notification Channel Fallback"

    private var notificationManager: NotificationManager? = null

    // Cache: channelId -> Pair(soundUri, audioAttributes)
    private val originalChannelSounds = mutableMapOf<String, Pair<Uri?, AudioAttributes?>>()

    private fun getNotificationManager(context: Context): NotificationManager {
        return notificationManager ?: (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .also { notificationManager = it }
    }

    override fun getCurrentVolume(context: Context): Int {
        // Return 0 if channels are currently muted, 1 otherwise
        return if (originalChannelSounds.isNotEmpty()) 0 else 1
    }

    override fun setVolume(context: Context, volume: Int, flags: Int): Boolean {
        return try {
            val nm = getNotificationManager(context)
            val channels = nm.notificationChannels ?: return false

            if (volume == 0) {
                // MUTE: Cache original sound and set sound to null
                originalChannelSounds.clear()
                for (channel in channels) {
                    if (channel.importance >= NotificationManager.IMPORTANCE_DEFAULT && channel.sound != null) {
                        originalChannelSounds[channel.id] = Pair(channel.sound, channel.audioAttributes)
                        channel.setSound(null, null)
                        nm.createNotificationChannel(channel)
                        Log.d(TAG, "Muted sound for channel: ${channel.id}")
                    }
                }
                Log.i(TAG, "Muted sound on ${originalChannelSounds.size} notification channels")
            } else {
                // RESTORE: Restore original sound
                for ((channelId, soundConfig) in originalChannelSounds) {
                    nm.getNotificationChannel(channelId)?.let { channel ->
                        channel.setSound(soundConfig.first, soundConfig.second)
                        nm.createNotificationChannel(channel)
                        Log.d(TAG, "Restored sound for channel: $channelId")
                    }
                }
                val restoredCount = originalChannelSounds.size
                originalChannelSounds.clear()
                Log.i(TAG, "Restored sound on $restoredCount notification channels")
            }
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException modifying notification channels", e)
            false
        } catch (e: Exception) {
            Log.w(TAG, "Failed to modify notification channels", e)
            false
        }
    }

    override fun restoreVolume(context: Context, originalVolume: Int): Boolean {
        return setVolume(context, 1, 0)
    }

    override fun isSupported(context: Context): Boolean {
        return try {
            val nm = getNotificationManager(context)
            nm.notificationChannels.isNotEmpty()
        } catch (e: Exception) {
            false
        }
    }

    override fun getFallback(): AudioStreamController? = null // End of fallback chain

    override fun getMaxVolume(context: Context): Int = 1
}

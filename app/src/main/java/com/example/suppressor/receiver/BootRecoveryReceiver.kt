package com.example.suppressor.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.suppressor.service.SuppressionForegroundService

/**
 * BroadcastReceiver for boot/update recovery.
 *
 * Restarts the suppression services after:
 * - Device boot completed (BOOT_COMPLETED)
 * - Direct boot completed (LOCKED_BOOT_COMPLETED) - for credential-encrypted storage
 * - App update/replacement (MY_PACKAGE_REPLACED)
 *
 * This ensures the microphone monitoring and notification suppression
 * persist across reboots and app updates without user intervention.
 */
class BootRecoveryReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootRecoveryReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.i(TAG, "Received broadcast: $action")

        when (action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                Log.i(TAG, "BOOT_COMPLETED - Starting suppression services")
                startSuppressionServices(context)
            }
            Intent.ACTION_LOCKED_BOOT_COMPLETED -> {
                Log.i(TAG, "LOCKED_BOOT_COMPLETED - Starting suppression services (direct boot)")
                startSuppressionServices(context)
            }
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Log.i(TAG, "MY_PACKAGE_REPLACED - App updated, restarting suppression services")
                startSuppressionServices(context)
            }
            else -> {
                Log.w(TAG, "Unknown action received: $action")
            }
        }
    }

    /**
     * Start the suppression foreground service which will:
     * 1. Register AudioRecordingCallback for microphone detection
     * 2. Initialize controllers with fallback chains
     * 3. Recover persisted suppression state if recordings were active
     */
    private fun startSuppressionServices(context: Context) {
        try {
            val serviceIntent = Intent(context, SuppressionForegroundService::class.java)
            serviceIntent.action = SuppressionForegroundService.ACTION_START

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }

            Log.i(TAG, "SuppressionForegroundService start requested")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start suppression services", e)
        }
    }
}
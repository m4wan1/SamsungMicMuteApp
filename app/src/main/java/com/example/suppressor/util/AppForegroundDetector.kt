package com.example.suppressor.util

import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process
import com.example.suppressor.debug.DebugLogManager

/**
 * Utility to identify the current foreground application package.
 *
 * Utilizes:
 * 1. UsageStatsManager (when PACKAGE_USAGE_STATS permission is granted).
 * 2. ActivityManager running processes fallback.
 */
object AppForegroundDetector {
    private const val TAG = "AppForegroundDetector"

    /**
     * Checks if PACKAGE_USAGE_STATS permission is granted by the user in Settings.
     */
    fun hasUsageStatsPermission(context: Context): Boolean {
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
            } else {
                appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
            }
            mode == AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Resolves the current active foreground application package name.
     */
    fun getForegroundApp(context: Context): String? {
        // Method 1: UsageStatsManager queryEvents (most reliable on Android 10-16)
        if (hasUsageStatsPermission(context)) {
            try {
                val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                if (usm != null) {
                    val time = System.currentTimeMillis()
                    val events = usm.queryEvents(time - 10_000, time)
                    val event = UsageEvents.Event()
                    var lastForeground: String? = null
                    while (events.hasNextEvent()) {
                        events.getNextEvent(event)
                        if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
                            event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND
                        ) {
                            val pkg = event.packageName
                            if (!pkg.isNullOrBlank() && pkg != context.packageName) {
                                lastForeground = pkg
                            }
                        }
                    }
                    if (!lastForeground.isNullOrBlank()) {
                        return lastForeground
                    }
                }
            } catch (e: Exception) {
                DebugLogManager.log(TAG, "UsageStats query exception: ${e.message}")
            }
        }

        // Method 2: ActivityManager runningAppProcesses fallback
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val processes = am?.runningAppProcesses
            if (processes != null) {
                for (proc in processes) {
                    if (proc.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
                        val pkg = proc.pkgList?.firstOrNull { it != context.packageName }
                        if (!pkg.isNullOrBlank()) {
                            return pkg
                        }
                    }
                }
            }
        } catch (e: Exception) {
            DebugLogManager.log(TAG, "ActivityManager running processes exception: ${e.message}")
        }

        return null
    }
}

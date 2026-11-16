package com.example.suppressor.debug

import android.content.ContentResolver
import android.content.Context
import android.provider.Settings
import android.util.Log
import com.example.suppressor.SuppressionApplication
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * On-Demand Debug Log Manager for Samsung Mic Mute Suppressor.
 *
 * Requirements:
 * 1. Default disabled: No continuous logging or disk writes while disabled.
 * 2. When enabled by user: logs write to a file in cacheDir up to 5MB (with rotation).
 * 3. Auto-disables after 24 hours to preserve device performance and storage.
 * 4. Thread-safe log rotation and file management for export/sharing/bug reporting.
 */
object DebugLogManager {

    private const val TAG = "DebugLogManager"
    private const val PREFS_NAME = "debug_settings"
    private const val KEY_DEBUG_ENABLED = "debug_logging_enabled"
    private const val KEY_DEBUG_ENABLED_AT = "debug_enabled_at"

    private const val LOG_FILE_NAME = "suppressor_debug.log"
    private const val MAX_LOGS_MEMORY = 200
    private const val MAX_FILE_SIZE_BYTES = 5 * 1024 * 1024L // 5 MB max
    private const val AUTO_DISABLE_DURATION_MS = 24 * 60 * 60 * 1000L // 24 hours

    @Volatile
    private var appContext: Context? = null

    private val logQueue = ArrayDeque<String>(MAX_LOGS_MEMORY)
    private val _logsFlow = MutableStateFlow<List<String>>(emptyList())
    val logsFlow: StateFlow<List<String>> = _logsFlow.asStateFlow()

    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /**
     * Initialize with application context.
     */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun getContext(context: Context? = null): Context? {
        return context?.applicationContext ?: appContext ?: SuppressionApplication.getInstance()
    }

    /**
     * Checks if debug logging is enabled.
     * Automatically turns off and returns false if 24 hours have elapsed since activation.
     */
    fun isEnabled(context: Context? = null): Boolean {
        val ctx = getContext(context) ?: return false
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean(KEY_DEBUG_ENABLED, false)
        if (!enabled) return false

        val enabledAt = prefs.getLong(KEY_DEBUG_ENABLED_AT, 0L)
        val now = System.currentTimeMillis()
        if (enabledAt > 0 && (now - enabledAt > AUTO_DISABLE_DURATION_MS)) {
            // Auto-disable after 24 hours
            prefs.edit()
                .putBoolean(KEY_DEBUG_ENABLED, false)
                .putLong(KEY_DEBUG_ENABLED_AT, 0L)
                .apply()
            synchronized(this) {
                logQueue.clear()
                _logsFlow.value = emptyList()
            }
            Log.i(TAG, "⏰ Debug logging automatically disabled after 24 hours")
            return false
        }
        return true
    }

    /**
     * Enable or disable debug logging on-demand.
     */
    fun setEnabled(enabled: Boolean, context: Context? = null) {
        val ctx = getContext(context) ?: return
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()

        prefs.edit()
            .putBoolean(KEY_DEBUG_ENABLED, enabled)
            .putLong(KEY_DEBUG_ENABLED_AT, if (enabled) now else 0L)
            .apply()

        synchronized(this) {
            if (enabled) {
                logQueue.clear()
                val startEntry = "[${dateFormat.format(Date())}] [Debug] 🟢 Debug mode activated (auto-expires in 24h)"
                logQueue.addLast(startEntry)
                _logsFlow.value = logQueue.toList()
                writeToFile(startEntry)
            } else {
                logQueue.clear()
                _logsFlow.value = emptyList()
            }
        }

        Log.i(TAG, if (enabled) "Debug logging ENABLED" else "Debug logging DISABLED")
    }

    /**
     * Log an event.
     * If debug mode is disabled, this returns immediately without touching disk or memory.
     */
    @Synchronized
    fun log(tag: String, message: String) {
        if (!isEnabled()) {
            // Fast exit: absolutely no disk/file writing or memory allocation when disabled
            return
        }

        val timestamp = dateFormat.format(Date())
        val entry = "[$timestamp] [$tag] $message"
        Log.d(tag, message)

        if (logQueue.size >= MAX_LOGS_MEMORY) {
            logQueue.removeFirst()
        }
        logQueue.addLast(entry)
        _logsFlow.value = logQueue.toList()

        writeToFile(entry)
    }

    /**
     * Clear all in-memory logs and delete the cache log file.
     */
    @Synchronized
    fun clear(context: Context? = null) {
        logQueue.clear()
        _logsFlow.value = emptyList()
        deleteLogFile(context)
        if (isEnabled(context)) {
            val entry = "[${dateFormat.format(Date())}] [Debug] 🗑️ Logs cleared by user"
            logQueue.addLast(entry)
            _logsFlow.value = logQueue.toList()
            writeToFile(entry)
        }
    }

    /**
     * Get the log file in cacheDir.
     */
    fun getLogFile(context: Context? = null): File? {
        val ctx = getContext(context) ?: return null
        return File(ctx.cacheDir, LOG_FILE_NAME)
    }

    /**
     * Returns true if the debug log file exists and contains data.
     */
    fun hasLogFile(context: Context? = null): Boolean {
        val file = getLogFile(context) ?: return false
        return file.exists() && file.length() > 0
    }

    /**
     * Deletes the log file from cacheDir.
     */
    fun deleteLogFile(context: Context? = null): Boolean {
        val file = getLogFile(context) ?: return false
        return try {
            if (file.exists()) {
                file.delete()
            } else {
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete log file: ${e.message}")
            false
        }
    }

    /**
     * Returns the log file for sharing/exporting if it exists and is not empty.
     */
    fun getLogFileForExport(context: Context? = null): File? {
        val file = getLogFile(context) ?: return null
        return if (file.exists() && file.length() > 0) file else null
    }

    /**
     * Returns human-readable file size for the log file (e.g. "1.2 MB" or "450 KB").
     */
    fun getLogFileSizeFormatted(context: Context? = null): String {
        val file = getLogFile(context) ?: return "0 B"
        if (!file.exists()) return "0 B"
        val bytes = file.length()
        return when {
            bytes >= 1024 * 1024 -> String.format(Locale.US, "%.2f MB", bytes.toDouble() / (1024 * 1024))
            bytes >= 1024 -> String.format(Locale.US, "%.1f KB", bytes.toDouble() / 1024)
            else -> "$bytes B"
        }
    }

    /**
     * Writes an entry to the log file in cacheDir with rotation if exceeding 5MB.
     */
    private fun writeToFile(entry: String) {
        val file = getLogFile() ?: return
        try {
            if (file.exists() && file.length() >= MAX_FILE_SIZE_BYTES) {
                rotateLogFile(file)
            }
            FileWriter(file, true).use { fw ->
                fw.append(entry).append("\n")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error writing to debug log file: ${e.message}")
        }
    }

    /**
     * Trims the oldest half of lines when the file hits the 5MB ceiling.
     */
    private fun rotateLogFile(file: File) {
        try {
            val lines = file.readLines()
            val keepCount = lines.size / 2
            val keptLines = lines.takeLast(keepCount)
            file.writeText("[--- Log rotated: older entries purged to maintain < 5MB limit ---]\n" +
                    keptLines.joinToString("\n") + "\n")
            Log.i(TAG, "Log file rotated successfully (kept latest $keepCount lines)")
        } catch (e: Exception) {
            Log.e(TAG, "Log rotation fallback: ${e.message}")
            file.writeText("[--- Log truncated after exceeding 5MB limit ---]\n")
        }
    }

    /**
     * Get all logs as a single string.
     */
    @Synchronized
    fun getAllLogsText(): String {
        return if (logQueue.isNotEmpty()) {
            logQueue.joinToString("\n")
        } else {
            val file = getLogFile()
            if (file != null && file.exists()) {
                try {
                    file.readText()
                } catch (e: Exception) {
                    "(Error reading log file: ${e.message})"
                }
            } else {
                "No active debug logs. Enable Debug Mode to start recording."
            }
        }
    }

    /**
     * Dumps all vibration and notification settings from Settings.System.
     * Checks both cursor scan and comprehensive list of known Samsung / Android keys.
     */
    fun dumpSamsungVibrationKeys(contentResolver: ContentResolver): String {
        val sb = StringBuilder()
        sb.append("=== SAMSUNG VIBRATION & NOTIFICATION KEYS DUMP ===\n")

        val knownKeys = listOf(
            "SEM_VIBRATION_NOTIFICATION_INTENSITY",
            "sem_vibration_notification_intensity",
            "VIB_NOTIFICATION_MAGNITUDE",
            "vib_notification_magnitude",
            "vibrate_notification",
            "VIBRATE_NOTIFICATION",
            "notification_vibration_intensity",
            "NOTIFICATION_VIBRATION_INTENSITY",
            "notification_vibration_sep_index",
            "sem_vibration_ring_intensity",
            "sem_vibration_system_intensity",
            "vibrate_on",
            "vibrate_when_ringing",
            "haptic_feedback_enabled",
            "mode_ringer",
            "volume_notification",
            "volume_notification_speaker",
            "notification_sound"
        )

        val foundKeys = mutableMapOf<String, String>()

        // 1. Try Cursor query for all matching vib / noti keys if permitted
        try {
            val cursor = contentResolver.query(
                Settings.System.CONTENT_URI,
                arrayOf("name", "value"),
                null,
                null,
                null
            )
            cursor?.use {
                val nameIdx = it.getColumnIndex("name")
                val valIdx = it.getColumnIndex("value")
                while (it.moveToNext()) {
                    val name = if (nameIdx >= 0) it.getString(nameIdx) else null
                    val value = if (valIdx >= 0) it.getString(valIdx) else null
                    if (name != null) {
                        val lower = name.lowercase(Locale.US)
                        if (lower.contains("vib") || lower.contains("noti")) {
                            foundKeys[name] = value ?: "(null)"
                        }
                    }
                }
            }
        } catch (e: Exception) {
            sb.append("Cursor scan unavailable: ${e.message}\n")
        }

        // 2. Explicit direct lookup for all known Samsung keys
        for (key in knownKeys) {
            if (!foundKeys.containsKey(key)) {
                try {
                    val intVal = Settings.System.getInt(contentResolver, key, -999)
                    if (intVal != -999) {
                        foundKeys[key] = intVal.toString()
                    } else {
                        val strVal = Settings.System.getString(contentResolver, key)
                        if (strVal != null) {
                            foundKeys[key] = strVal
                        }
                    }
                } catch (ignored: Exception) {
                }
            }
        }

        if (foundKeys.isEmpty()) {
            sb.append("No matching vibration/notification keys found in Settings.System\n")
        } else {
            foundKeys.toSortedMap().forEach { (k, v) ->
                sb.append("  • $k = $v\n")
            }
        }
        sb.append("==================================================")
        val result = sb.toString()
        log("DumpVibKeys", result)
        return result
    }
}

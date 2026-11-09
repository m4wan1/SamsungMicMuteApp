package com.example.suppressor.debug

import android.content.ContentResolver
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * Singleton in-memory debug log manager.
 * Stores up to MAX_LOGS timestamped log entries and publishes them via StateFlow.
 */
object DebugLogManager {

    private const val TAG = "DebugLogManager"
    private const val MAX_LOGS = 200

    private val logQueue = ArrayDeque<String>(MAX_LOGS)
    private val _logsFlow = MutableStateFlow<List<String>>(emptyList())
    val logsFlow: StateFlow<List<String>> = _logsFlow.asStateFlow()

    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun log(tag: String, message: String) {
        val timestamp = dateFormat.format(Date())
        val entry = "[$timestamp] [$tag] $message"
        Log.d(tag, message)

        if (logQueue.size >= MAX_LOGS) {
            logQueue.removeFirst()
        }
        logQueue.addLast(entry)
        _logsFlow.value = logQueue.toList()
    }

    @Synchronized
    fun clear() {
        logQueue.clear()
        _logsFlow.value = emptyList()
        log("Debug", "Logs cleared by user")
    }

    @Synchronized
    fun getAllLogsText(): String {
        return logQueue.joinToString("\n")
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

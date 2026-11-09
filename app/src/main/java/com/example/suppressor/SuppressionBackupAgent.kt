package com.example.suppressor

import android.app.backup.BackupAgent
import android.app.backup.BackupDataInput
import android.app.backup.BackupDataOutput
import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Log

/**
 * Backup Agent for cross-device restore of suppression state.
 *
 * This agent handles backup and restore of the suppression state
 * (original audio volume, vibration intensity, active state)
 * via Android's BackupManager and cloud backup (Google Drive).
 *
 * The actual data is stored in SharedPreferences (suppression_state.xml)
 * which is included in backup_rules.xml and data_extraction_rules.xml.
 *
 * This agent provides custom backup/restore logic if needed beyond
 * the default SharedPreferences backup.
 */
class SuppressionBackupAgent : BackupAgent() {

    companion object {
        private const val TAG = "SuppressionBackupAgent"
        private const val STATE_KEY = "suppression_state_backup"
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "SuppressionBackupAgent created")
    }

    /**
     * Called when the system wants to back up data.
     * We write the suppression state to the backup dataset.
     */
    override fun onBackup(oldState: ParcelFileDescriptor?, data: BackupDataOutput?, newState: ParcelFileDescriptor?) {
        Log.i(TAG, "onBackup called")

        val prefs = getSharedPreferences("suppression_state", Context.MODE_PRIVATE)
        val jsonString = prefs.getString("state_json", null)

        if (jsonString != null) {
            try {
                val bytes = jsonString.toByteArray()
                data?.writeEntityHeader(STATE_KEY, bytes.size)
                data?.writeEntityData(bytes, bytes.size)
                Log.d(TAG, "Backed up suppression state: ${jsonString.length} chars")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write backup data", e)
            }
        } else {
            Log.d(TAG, "No suppression state to backup")
        }
    }

    /**
     * Called when the system restores data from backup.
     * We read the suppression state from the backup dataset.
     */
    override fun onRestore(data: BackupDataInput?, appVersionCode: Int, newState: ParcelFileDescriptor?) {
        Log.i(TAG, "onRestore called, appVersionCode=$appVersionCode")
        if (data == null) return

        while (data.readNextHeader()) {
            val key = data.key
            val dataSize = data.dataSize
            if (key == STATE_KEY) {
                val bytes = ByteArray(dataSize)
                try {
                    data.readEntityData(bytes, 0, dataSize)
                    val jsonString = String(bytes)

                    // Restore to SharedPreferences
                    val prefs = getSharedPreferences("suppression_state", Context.MODE_PRIVATE)
                    prefs.edit()
                        .putString("state_json", jsonString)
                        .apply()

                    Log.i(TAG, "Restored suppression state from backup: ${jsonString.length} chars")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to restore backup data", e)
                }
            }
        }
    }

    /**
     * Called when the system wants to do a full backup (legacy).
     */
    override fun onFullBackup(data: android.app.backup.FullBackupDataOutput?) {
        super.onFullBackup(data)
        Log.d(TAG, "onFullBackup called")
    }
}
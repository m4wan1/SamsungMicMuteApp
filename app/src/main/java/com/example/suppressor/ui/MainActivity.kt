package com.example.suppressor.ui

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.AudioManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Message
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import com.example.suppressor.R
import com.example.suppressor.RecordingSuppressor
import com.example.suppressor.SuppressionApplication
import com.example.suppressor.debug.DebugLogManager
import com.example.suppressor.persistence.PersistenceManager
import com.example.suppressor.service.SuppressionForegroundService
import com.example.suppressor.service.SuppressionNotificationListener
import com.example.suppressor.vibration.OneUI8VibrationSettingsController
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Main Activity with Live In-App Debugger Console (Version 2).
 *
 * Card 1: Live Status Banner (IDLE vs ACTIVE Mic Muted)
 * Warning Card: Custom In-App Vibration Disclaimer
 * Card 2: Saved Audio & Vibration Snapshot
 * Card 3: Required Permissions & Battery Setup
 * Card 4: Quick Actions (Restart Monitor, Force Restore)
 * Card 5: Live In-App Debugger & Real-Time Inspector
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_WRITE_SETTINGS = 1001
    }

    // Card 1: Live Status Banner
    private lateinit var containerStatusBadge: LinearLayout
    private lateinit var tvStatusBadge: TextView
    private lateinit var tvActiveRecordings: TextView
    private lateinit var tvActivePackages: TextView

    // Warning Card: In-App Vibration Disclaimer
    private lateinit var tvDisclaimer: TextView

    // Card 2: Snapshot
    private lateinit var tvVolumeSnapshot: TextView
    private lateinit var tvVibrationSnapshot: TextView

    // Card 3: Permissions & Battery
    private lateinit var tvStatusWriteSettings: TextView
    private lateinit var btnWriteSettings: Button
    private lateinit var tvStatusNotificationListener: TextView
    private lateinit var btnNotificationListener: Button
    private lateinit var tvStatusBatteryOptimization: TextView
    private lateinit var btnBatteryOptimization: Button

    // Card 4: Quick Actions
    private lateinit var btnRestartService: Button
    private lateinit var btnForceRestore: Button

    // Support Developer & Language
    private lateinit var btnPaypalDonate: Button
    private lateinit var btnChangeLanguage: View

    // Card 5: Live In-App Debugger
    private lateinit var tvDebugInspector: TextView
    private lateinit var tvDebugConsole: TextView
    private lateinit var scrollDebugConsole: NestedScrollView
    private lateinit var btnCopyDebugLogs: Button
    private lateinit var btnClearDebugLogs: Button
    private lateinit var btnDumpVibKeys: Button

    // Suppression & Service bindings
    private var recordingSuppressor: RecordingSuppressor? = null
    private var persistenceManager: PersistenceManager? = null
    private var serviceConnection: ServiceConnection? = null
    private var isBound = false

    // Periodic UI update jobs
    private var updateJob: Job? = null
    private var logCollectJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        initPersistenceManager()
        setupClickListeners()

        DebugLogManager.log(TAG, "MainActivity created")
        startAndBindForegroundService()
    }

    override fun onResume() {
        super.onResume()
        updateUI()

        // Real-time 500ms UI & Inspector refresh
        updateJob?.cancel()
        updateJob = lifecycleScope.launch {
            while (isActive) {
                updateUI()
                updateLiveInspector()
                kotlinx.coroutines.delay(500)
            }
        }

        // Live Log stream observer
        logCollectJob?.cancel()
        logCollectJob = lifecycleScope.launch {
            DebugLogManager.logsFlow.collect { logs ->
                val fullText = if (logs.isEmpty()) "Waiting for events..." else logs.joinToString("\n")
                tvDebugConsole.text = fullText
                scrollDebugConsole.post {
                    scrollDebugConsole.fullScroll(View.FOCUS_DOWN)
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        updateJob?.cancel()
        updateJob = null
        logCollectJob?.cancel()
        logCollectJob = null
    }

    override fun onDestroy() {
        super.onDestroy()
        updateJob?.cancel()
        logCollectJob?.cancel()
        unbindFromForegroundService()
    }

    private fun initViews() {
        // Card 1
        containerStatusBadge = findViewById(R.id.container_status_badge)
        tvStatusBadge = findViewById(R.id.tv_status_badge)
        tvActiveRecordings = findViewById(R.id.tv_active_recordings)
        tvActivePackages = findViewById(R.id.tv_active_packages)

        // Warning Card: In-App Vibration Disclaimer
        tvDisclaimer = findViewById(R.id.tv_disclaimer)
        tvDisclaimer.text = getString(R.string.disclaimer_custom_vibrations)

        // Card 2
        tvVolumeSnapshot = findViewById(R.id.tv_volume_snapshot)
        tvVibrationSnapshot = findViewById(R.id.tv_vibration_snapshot)

        // Card 3
        tvStatusWriteSettings = findViewById(R.id.tv_status_write_settings)
        btnWriteSettings = findViewById(R.id.btn_write_settings)
        tvStatusNotificationListener = findViewById(R.id.tv_status_notification_listener)
        btnNotificationListener = findViewById(R.id.btn_notification_listener)
        tvStatusBatteryOptimization = findViewById(R.id.tv_status_battery_optimization)
        btnBatteryOptimization = findViewById(R.id.btn_battery_optimization)

        // Card 4
        btnRestartService = findViewById(R.id.btn_restart_service)
        btnForceRestore = findViewById(R.id.btn_force_restore)

        // Support Developer & Language
        btnPaypalDonate = findViewById(R.id.btn_paypal_donate)
        btnChangeLanguage = findViewById(R.id.btn_change_language)

        // Card 5: Debugger
        tvDebugInspector = findViewById(R.id.tv_debug_inspector)
        tvDebugConsole = findViewById(R.id.tv_debug_console)
        scrollDebugConsole = findViewById(R.id.scroll_debug_console)
        btnCopyDebugLogs = findViewById(R.id.btn_copy_debug_logs)
        btnClearDebugLogs = findViewById(R.id.btn_clear_debug_logs)
        btnDumpVibKeys = findViewById(R.id.btn_dump_vib_keys)
    }

    private fun initPersistenceManager() {
        persistenceManager = PersistenceManager(this)
    }

    private fun setupClickListeners() {
        btnWriteSettings.setOnClickListener { requestWriteSettingsPermission() }
        btnNotificationListener.setOnClickListener { openNotificationListenerSettings() }
        btnBatteryOptimization.setOnClickListener { requestBatteryOptimizationExemption() }
        btnRestartService.setOnClickListener { restartMonitorService() }
        btnForceRestore.setOnClickListener { forceRestore() }

        btnPaypalDonate.setOnClickListener { openPayPalDonationBottomSheet() }
        btnChangeLanguage.setOnClickListener { showLanguagePickerDialog() }

        btnCopyDebugLogs.setOnClickListener { copyDebugLogsToClipboard() }
        btnClearDebugLogs.setOnClickListener { DebugLogManager.clear() }
        btnDumpVibKeys.setOnClickListener { dumpSamsungVibrationKeys() }
    }

    private fun getSuppressor(): RecordingSuppressor? {
        return recordingSuppressor ?: (application as? SuppressionApplication)?.recordingSuppressor
    }

    private fun startAndBindForegroundService() {
        val intent = Intent(this, SuppressionForegroundService::class.java).apply {
            action = SuppressionForegroundService.ACTION_START
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            DebugLogManager.log(TAG, "Failed to start foreground service: ${e.message}")
        }
        bindToForegroundService()
    }

    private fun bindToForegroundService() {
        if (isBound) return

        val intent = Intent(this, SuppressionForegroundService::class.java)
        serviceConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                val binder = service as SuppressionForegroundService.LocalBinder
                recordingSuppressor = binder.getRecordingSuppressor()
                isBound = true
                DebugLogManager.log(TAG, "MainActivity bound to SuppressionForegroundService")
                updateUI()
            }

            override fun onServiceDisconnected(name: ComponentName) {
                recordingSuppressor = null
                isBound = false
                DebugLogManager.log(TAG, "MainActivity disconnected from SuppressionForegroundService")
                updateUI()
            }
        }

        try {
            bindService(intent, serviceConnection!!, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            DebugLogManager.log(TAG, "Failed to bind to foreground service: ${e.message}")
        }
    }

    private fun unbindFromForegroundService() {
        if (isBound) {
            serviceConnection?.let {
                try {
                    unbindService(it)
                } catch (e: Exception) {
                    DebugLogManager.log(TAG, "Error unbinding foreground service: ${e.message}")
                }
                serviceConnection = null
            }
            isBound = false
        }
    }

    private fun updateUI() {
        val suppressor = getSuppressor()
        updateCard1Status(suppressor)
        updateCard2Snapshot(suppressor)
        updateCard3Permissions()
    }

    /**
     * Card 1: Live Status Banner
     */
    private fun updateCard1Status(suppressor: RecordingSuppressor?) {
        val isSuppressing = suppressor?.isCurrentlySuppressing() == true
        val activeCount = suppressor?.getActiveRecordingCount() ?: 0
        val activePackages = suppressor?.getActivePackages() ?: emptySet()

        if (isSuppressing) {
            containerStatusBadge.setBackgroundResource(R.drawable.bg_status_badge_active)
            tvStatusBadge.text = getString(R.string.status_active)
            tvStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.status_active))
            tvActiveRecordings.text = if (activeCount > 0) {
                "$activeCount active recording session(s)"
            } else {
                "Microphone or Call stream active"
            }
        } else {
            containerStatusBadge.setBackgroundResource(R.drawable.bg_status_badge)
            tvStatusBadge.text = getString(R.string.status_idle)
            tvStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.status_idle))
            tvActiveRecordings.text = getString(R.string.label_no_active_recordings)
        }

        if (activePackages.isNotEmpty()) {
            tvActivePackages.visibility = View.VISIBLE
            tvActivePackages.text = getString(R.string.label_microphone_detected, activePackages.joinToString(", "))
        } else {
            tvActivePackages.visibility = View.GONE
        }
    }

    /**
     * Card 2: Saved Audio & Vibration Snapshot
     */
    private fun updateCard2Snapshot(suppressor: RecordingSuppressor?) {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_NOTIFICATION)
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_NOTIFICATION)
        val savedVolume = suppressor?.getSavedAudioVolume() ?: -1

        val isSuppressing = suppressor?.isCurrentlySuppressing() == true

        if (isSuppressing && savedVolume > 0) {
            tvVolumeSnapshot.text = "Saved Volume: $savedVolume / $maxVolume  |  Current: $currentVolume (Muted)"
        } else if (savedVolume > 0) {
            tvVolumeSnapshot.text = "Saved Volume: $savedVolume / $maxVolume  |  Current: $currentVolume / $maxVolume"
        } else {
            val baseline = persistenceManager?.loadBaselineValues()?.first ?: -1
            val baselineStr = if (baseline > 0) " (Baseline: $baseline)" else ""
            tvVolumeSnapshot.text = "Saved Volume: Ready$baselineStr  |  Current: $currentVolume / $maxVolume"
        }

        // Vibration
        val currentVib = try {
            val v = Settings.System.getInt(contentResolver, "SEM_VIBRATION_NOTIFICATION_INTENSITY", -1)
            if (v == -1) Settings.System.getInt(contentResolver, "sem_vibration_notification_intensity", 3) else v
        } catch (e: Exception) {
            3
        }
        val savedVib = suppressor?.getSavedVibrationIntensity() ?: -1

        if (isSuppressing && savedVib > 0) {
            tvVibrationSnapshot.text = "Saved Vibration: $savedVib / 5  |  Current: $currentVib (Muted)"
        } else if (savedVib > 0) {
            tvVibrationSnapshot.text = "Saved Vibration: $savedVib / 5  |  Current: $currentVib / 5"
        } else {
            val baselineVib = persistenceManager?.loadBaselineValues()?.second ?: -1
            val baselineVibStr = if (baselineVib > 0) " (Baseline: $baselineVib)" else ""
            tvVibrationSnapshot.text = "Saved Vibration: Ready$baselineVibStr  |  Current: $currentVib / 5"
        }
    }

    /**
     * Card 3: Permissions & Battery Setup
     */
    private fun updateCard3Permissions() {
        val hasWriteSettings = Settings.System.canWrite(this)
        tvStatusWriteSettings.text = if (hasWriteSettings) getString(R.string.status_granted) else getString(R.string.status_not_granted)
        tvStatusWriteSettings.setTextColor(ContextCompat.getColor(this, if (hasWriteSettings) R.color.status_active else R.color.status_error))
        btnWriteSettings.isEnabled = !hasWriteSettings
        btnWriteSettings.text = if (hasWriteSettings) getString(R.string.status_granted) else getString(R.string.button_grant)

        val hasNotificationListener = SuppressionNotificationListener.isNotificationListenerEnabled(this)
        tvStatusNotificationListener.text = if (hasNotificationListener) getString(R.string.status_granted) else getString(R.string.status_not_granted)
        tvStatusNotificationListener.setTextColor(ContextCompat.getColor(this, if (hasNotificationListener) R.color.status_active else R.color.status_error))
        btnNotificationListener.isEnabled = !hasNotificationListener
        btnNotificationListener.text = if (hasNotificationListener) getString(R.string.status_granted) else getString(R.string.button_grant)

        val isUnrestricted = isBatteryOptimizationIgnored()
        tvStatusBatteryOptimization.text = if (isUnrestricted) getString(R.string.status_unrestricted) else getString(R.string.status_restricted)
        tvStatusBatteryOptimization.setTextColor(ContextCompat.getColor(this, if (isUnrestricted) R.color.status_active else R.color.status_error))
        btnBatteryOptimization.isEnabled = !isUnrestricted
        btnBatteryOptimization.text = if (isUnrestricted) getString(R.string.status_granted) else getString(R.string.button_grant)
    }

    /**
     * Card 5: Real-Time Inspector Panel refreshed every 500ms
     */
    private fun updateLiveInspector() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // 1. Active Recording Configs
        val configs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try { audioManager.activeRecordingConfigurations } catch (e: Exception) { emptyList() }
        } else {
            emptyList()
        }

        val configDetails = if (configs.isEmpty()) {
            "0 (None active)"
        } else {
            val details = configs.joinToString("; ") { cfg ->
                val sourceName = audioSourceToString(cfg.clientAudioSource)
                "Src=$sourceName, Session=${cfg.clientAudioSessionId}"
            }
            "${configs.size} active [$details]"
        }

        // 2. Audio Mode
        val modeStr = audioModeToString(audioManager.mode)

        // 3. Live STREAM_NOTIFICATION Volume & Mute
        val curVol = audioManager.getStreamVolume(AudioManager.STREAM_NOTIFICATION)
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_NOTIFICATION)
        val isMuted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            audioManager.isStreamMute(AudioManager.STREAM_NOTIFICATION)
        } else {
            false
        }

        // 4. Live Samsung Vibration Keys
        val cr = contentResolver
        val semInt = Settings.System.getInt(cr, "SEM_VIBRATION_NOTIFICATION_INTENSITY", -1)
        val semLower = Settings.System.getInt(cr, "sem_vibration_notification_intensity", -1)
        val vibMag = Settings.System.getInt(cr, "VIB_NOTIFICATION_MAGNITUDE", -1)
        val vibNoti = Settings.System.getInt(cr, "vibrate_notification", -1)
        val notiInt = Settings.System.getInt(cr, "notification_vibration_intensity", -1)
        val vibOn = Settings.System.getInt(cr, "vibrate_on", -1)

        val inspectorText = buildString {
            append("● Mic Configs: ").append(configDetails).append("\n")
            append("● Audio Mode:  ").append(modeStr).append("\n")
            append("● STREAM_NOTI: ").append("$curVol / $maxVol (isStreamMute=$isMuted)").append("\n")
            append("● Samsung Vibration Keys:\n")
            append("   SEM_VIB_NOTI_INTENSITY: $semInt (sem_lower=$semLower)\n")
            append("   VIB_NOTIFICATION_MAG:   $vibMag\n")
            append("   vibrate_notification:   $vibNoti\n")
            append("   noti_vibration_intensity: $notiInt\n")
            append("   vibrate_on:             $vibOn")
        }

        tvDebugInspector.text = inspectorText
    }

    private fun audioModeToString(mode: Int): String {
        return when (mode) {
            AudioManager.MODE_NORMAL -> "MODE_NORMAL (0)"
            AudioManager.MODE_RINGTONE -> "MODE_RINGTONE (1)"
            AudioManager.MODE_IN_CALL -> "MODE_IN_CALL (2)"
            AudioManager.MODE_IN_COMMUNICATION -> "MODE_IN_COMMUNICATION (3) [COMM ACTIVE]"
            4 -> "MODE_CALL_REDIRECT (4) [COMM ACTIVE]"
            5 -> "MODE_CALL_SCREENING (5)"
            else -> "MODE_$mode"
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

    private fun copyDebugLogsToClipboard() {
        val logs = DebugLogManager.getAllLogsText()
        val vibKeys = DebugLogManager.dumpSamsungVibrationKeys(contentResolver)
        val fullDump = "=== LIVE INSPECTOR STATE ===\n" +
                tvDebugInspector.text.toString() + "\n\n" +
                vibKeys + "\n\n" +
                "=== RECENT DEBUG LOGS ===\n" +
                logs

        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("SamsungMicMute Debug Dump", fullDump)
        clipboard.setPrimaryClip(clip)

        Toast.makeText(this, "Debug logs & Samsung keys copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    private fun dumpSamsungVibrationKeys() {
        DebugLogManager.dumpSamsungVibrationKeys(contentResolver)
        Toast.makeText(this, "Samsung vibration keys dumped into log console", Toast.LENGTH_SHORT).show()
    }

    private fun isBatteryOptimizationIgnored(): Boolean {
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        return powerManager?.isIgnoringBatteryOptimizations(packageName) == true
    }

    private fun requestBatteryOptimizationExemption() {
        if (isBatteryOptimizationIgnored()) {
            Toast.makeText(this, R.string.toast_battery_unrestricted, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (e: Exception) {
            DebugLogManager.log(TAG, "ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS failed: ${e.message}")
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e2: Exception) {
                Toast.makeText(this, "Could not open battery settings", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun requestWriteSettingsPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.System.canWrite(this)) {
                val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName"))
                startActivityForResult(intent, REQUEST_WRITE_SETTINGS)
            } else {
                Toast.makeText(this, R.string.toast_write_settings_granted, Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_WRITE_SETTINGS) {
            if (Settings.System.canWrite(this)) {
                Toast.makeText(this, R.string.toast_write_settings_granted, Toast.LENGTH_SHORT).show()
                DebugLogManager.log(TAG, "WRITE_SETTINGS permission granted")
            } else {
                Toast.makeText(this, R.string.toast_write_settings_denied, Toast.LENGTH_SHORT).show()
                DebugLogManager.log(TAG, "WRITE_SETTINGS permission denied")
            }
            updateUI()
        }
    }

    private fun openNotificationListenerSettings() {
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        startActivity(intent)
    }

    private fun restartMonitorService() {
        val intent = Intent(this, SuppressionForegroundService::class.java).apply {
            action = SuppressionForegroundService.ACTION_START
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            bindToForegroundService()
            Toast.makeText(this, R.string.toast_service_restarted, Toast.LENGTH_SHORT).show()
            DebugLogManager.log(TAG, "Foreground monitor restarted by user")
        } catch (e: Exception) {
            DebugLogManager.log(TAG, "Failed to restart service: ${e.message}")
            Toast.makeText(this, "Failed to restart: ${e.message}", Toast.LENGTH_SHORT).show()
        }
        updateUI()
    }

    private fun forceRestore() {
        val suppressor = getSuppressor()
        suppressor?.forceRestore()

        val intent = Intent(this, SuppressionForegroundService::class.java).apply {
            action = SuppressionForegroundService.ACTION_FORCE_RESTORE
        }
        try {
            startService(intent)
        } catch (e: Exception) {
            DebugLogManager.log(TAG, "Failed to send ACTION_FORCE_RESTORE: ${e.message}")
        }

        Toast.makeText(this, R.string.toast_suppression_restored, Toast.LENGTH_SHORT).show()
        updateUI()
    }

    /**
     * Opens an in-app BottomSheetDialog containing the official PayPal Hosted Button SDK.
     * Keeps the entire payment process inside the app without opening an external browser.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun openPayPalDonationBottomSheet() {
        val bottomSheet = BottomSheetDialog(this)
        val sheetView = layoutInflater.inflate(R.layout.bottom_sheet_paypal, null)
        bottomSheet.setContentView(sheetView)

        val btnClose = sheetView.findViewById<ImageButton>(R.id.btn_close_paypal)
        val progressBar = sheetView.findViewById<LinearProgressIndicator>(R.id.progress_paypal_loading)
        val webView = sheetView.findViewById<WebView>(R.id.webview_paypal)

        btnClose.setOnClickListener {
            bottomSheet.dismiss()
        }

        // Configure WebView settings for PayPal JS SDK
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString = userAgentString + " Mobile SamsungMicMuteApp/3.0"
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                progressBar.visibility = View.GONE
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                // Keep all PayPal URLs inside this WebView
                return false
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress >= 100) {
                    progressBar.visibility = View.GONE
                } else {
                    progressBar.visibility = View.VISIBLE
                    progressBar.progress = newProgress
                }
            }

            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?
            ): Boolean {
                // Handle popup checkout windows inside an in-app dialog
                val popupWebView = WebView(this@MainActivity).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.setSupportMultipleWindows(true)
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(v: WebView?, req: WebResourceRequest?): Boolean {
                            return false
                        }
                    }
                }

                val popupDialog = Dialog(this@MainActivity).apply {
                    setContentView(popupWebView)
                    window?.setLayout(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }

                popupWebView.webChromeClient = object : WebChromeClient() {
                    override fun onCloseWindow(window: WebView?) {
                        popupDialog.dismiss()
                    }
                }

                val transport = resultMsg?.obj as? WebView.WebViewTransport
                transport?.webView = popupWebView
                resultMsg?.sendToTarget()
                popupDialog.show()
                return true
            }
        }

        try {
            val htmlContent = assets.open("paypal_donate.html").bufferedReader().use { it.readText() }
            webView.loadDataWithBaseURL("https://www.paypal.com", htmlContent, "text/html", "UTF-8", null)
            DebugLogManager.log(TAG, "Loaded in-app PayPal donation sheet")
        } catch (e: Exception) {
            DebugLogManager.log(TAG, "Failed to load PayPal HTML: ${e.message}")
            Toast.makeText(this, "Failed to load PayPal: ${e.message}", Toast.LENGTH_SHORT).show()
        }

        bottomSheet.setOnDismissListener {
            webView.stopLoading()
            webView.destroy()
        }

        bottomSheet.show()
    }

    /**
     * Shows a dialog allowing the user to select their preferred language from 11 options.
     * Uses AndroidX AppCompatDelegate to immediately apply the chosen locale.
     */
    private fun showLanguagePickerDialog() {
        val languages = arrayOf(
            "🇸🇦 العربية (Arabic)",
            "🇺🇸 English",
            "🇪🇸 Español (Spanish)",
            "🇫🇷 Français (French)",
            "🇩🇪 Deutsch (German)",
            "🇷🇺 Русский (Russian)",
            "🇧🇷 Português (Portuguese)",
            "🇹🇷 Türkçe (Turkish)",
            "🇰🇷 한국어 (Korean)",
            "🇯🇵 日本語 (Japanese)",
            "🇨🇳 简体中文 (Chinese)"
        )
        val languageTags = arrayOf("ar", "en", "es", "fr", "de", "ru", "pt", "tr", "ko", "ja", "zh")

        AlertDialog.Builder(this)
            .setTitle(R.string.title_select_language)
            .setItems(languages) { _, which ->
                val selectedTag = languageTags[which]
                AppCompatDelegate.setApplicationLocales(
                    LocaleListCompat.forLanguageTags(selectedTag)
                )
                Toast.makeText(this, R.string.toast_language_changed, Toast.LENGTH_SHORT).show()
                DebugLogManager.log(TAG, "Language switched to: $selectedTag")
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}

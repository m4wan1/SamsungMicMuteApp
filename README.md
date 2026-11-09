# SamsungMicMuteApp - One UI 8/9 Notification Suppressor

Automatic notification sound and vibration suppression during active microphone recording on Samsung One UI 8 & 9 (Android 15/16).

---

## 1. Project Objective & Core Constraints

Silences all notification sounds and vibrations whenever any application uses the microphone (e.g. WhatsApp voice notes, Voice Recorder, Snapchat, Camera video recording, phone calls), while keeping ringtones, alarm audio, media playback, and call vibrations completely unaffected.

### Strict Architectural Constraints
- ❌ **No `RECORD_AUDIO` permission** — Zero eavesdropping risk; detection relies strictly on `AudioManager.AudioRecordingCallback`.
- ❌ **No Do Not Disturb (DND / NotificationPolicy)** — Preserves user's custom DND rules and priorities.
- ❌ **No Global Silent Mode (`RINGER_MODE_SILENT`)** — Ringtone and alarm streams remain fully active.
- ✅ **STREAM_NOTIFICATION isolation** — Uses `AudioManager.setStreamVolume` targeting notification stream only.
- ✅ **Samsung Vibration Intensity Control** — Uses `Settings.System` targeting `sem_vibration_notification_intensity` via `WRITE_SETTINGS`.
- ✅ **Atomic State Caching & Restoration** — Caches and persists user's original volume and vibration levels before any mutation.
- ✅ **Crash & Reboot Recovery** — Persisted state ensures automatic recovery on reboot (`BOOT_COMPLETED`) or unexpected termination.

---

## 2. Technical Architecture

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           Application Layer                                  │
│  ┌─────────────────────────────────────────────────────────────────────┐    │
│  │                    RecordingSuppressor (Main Orchestrator)           │    │
│  │  - Lifecycle management                                              │    │
│  │  - State machine: IDLE → SUPPRESSING → RESTORING                    │    │
│  │  - Active recording tracking (ignores own package)                  │    │
│  │  - Persistence coordination & crash recovery                        │    │
│  └─────────────────────────────┬────────────────────────────────────────┘    │
│                                │                                             │
│         ┌──────────────────────┼──────────────────────┐                     │
│         ▼                      ▼                      ▼                     │
│  ┌───────────────┐    ┌─────────────────┐    ┌─────────────────┐           │
│  │ AudioStream   │    │ VibrationSettings│    │ Persistence     │           │
│  │ Controller    │    │ Controller      │    │ Manager         │           │
│  └───────┬───────┘    └────────┬────────┘    └────────┬────────┘           │
│          │                     │                      │                     │
│    ┌─────┴─────┐         ┌─────┴─────┐         ┌──────┴──────┐             │
│    ▼           ▼         ▼           ▼         ▼             ▼             │
│ OneUI8/9   Fallback   OneUI8/9   Fallback    SharedPrefs  BackupManager    │
│ (STREAM_   (Channel   (Settings. (NLS/ADB)   + File       + Cloud          │
│  NOTIF)     Sound)     System)                                             │
└────────────────────────────────┬────────────────────────────────────────────┘
                                 │
                                 ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                           System Integration Layer                           │
│  ┌──────────────────┐  ┌──────────────────┐  ┌──────────────────────────┐   │
│  │ AudioRecording   │  │ Foreground       │  │ Boot/Update              │   │
│  │ Callback         │  │ Service          │  │ Receivers                │   │
│  │ (API 30+)        │  │ (specialUse FGS) │  │ (BOOT_COMPLETED)         │   │
│  └──────────────────┘  └──────────────────┘  └──────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 3. Fallback Chains

### Sound Suppression Hierarchy
1. **Priority 1 (`OneUI8AudioStreamController` / `OneUI9AudioStreamController`)**: System-level `AudioManager.STREAM_NOTIFICATION` volume control.
2. **Priority 2 (`NotificationChannelAudioController`)**: Per-channel notification sound suppression via `NotificationManager`.

### Vibration Suppression Hierarchy
1. **Priority 1 (`OneUI8VibrationSettingsController` / `OneUI9VibrationSettingsController`)**: Samsung `Settings.System.putInt("sem_vibration_notification_intensity", 0)` via `WRITE_SETTINGS`.
2. **Priority 2 (`NotificationListenerVibrationController`)**: Real-time notification vibration pattern suppression via `NotificationListenerService`.
3. **Priority 3 (`AdbSecureSettingsVibrationController`)**: Secure settings execution via shell (development / enterprise environments).

---

## 4. Project Structure

```
SamsungMicMuteApp/
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── java/com/example/suppressor/
│       │   │   ├── RecordingSuppressor.kt
│       │   │   ├── SuppressionApplication.kt
│       │   │   ├── SuppressionBackupAgent.kt
│       │   │   ├── audio/
│       │   │   │   ├── AudioStreamController.kt
│       │   │   │   ├── OneUI8AudioStreamController.kt
│       │   │   │   ├── OneUI9AudioStreamController.kt
│       │   │   │   └── NotificationChannelAudioController.kt
│       │   │   ├── vibration/
│       │   │   │   ├── VibrationSettingsController.kt
│       │   │   │   ├── OneUI8VibrationSettingsController.kt
│       │   │   │   ├── OneUI9VibrationSettingsController.kt
│       │   │   │   ├── NotificationListenerVibrationController.kt
│       │   │   │   └── AdbSecureSettingsVibrationController.kt
│       │   │   ├── persistence/
│       │   │   │   ├── PersistenceManager.kt
│       │   │   │   └── SuppressionState.kt
│       │   │   ├── receiver/
│       │   │   │   └── BootRecoveryReceiver.kt
│       │   │   ├── service/
│       │   │   │   ├── SuppressionForegroundService.kt
│       │   │   │   └── SuppressionNotificationListener.kt
│       │   │   └── ui/
│       │   │       └── MainActivity.kt
│       │   └── res/
│       └── test/java/com/example/suppressor/
│           ├── RecordingSuppressorTest.kt
│           ├── FallbackChainTest.kt
│           ├── audio/OneUI8AudioStreamControllerTest.kt
│           ├── vibration/OneUI8VibrationSettingsControllerTest.kt
│           └── persistence/SuppressionStateTest.kt
├── docs/
│   ├── FEASIBILITY_REPORT.md
│   └── plans/
│       └── IMPLEMENTATION_PLAN.md
├── build.gradle.kts
├── settings.gradle.kts
└── gradle.properties
```

---

## 5. Build & Setup Instructions

### Prerequisites
- JDK 17 (bundled in Android Studio at `/Applications/Android Studio.app/Contents/jbr/Contents/Home`)
- Android SDK (API 36 / Android 16 compileSdk)

### Local Configuration
Create `local.properties` with your Android SDK path:
```properties
sdk.dir=/Users/<username>/Library/Android/sdk
```

### Automated Physical Device Testing (Phase 5)
Connect your Samsung device via USB (with USB Debugging enabled) and run:
```bash
./scripts/test_device.sh
```
This script automatically:
1. Detects the connected Samsung device and One UI / Android version.
2. Installs the compiled APK (`app/build/outputs/apk/debug/app-debug.apk`).
3. Grants `WRITE_SETTINGS` and enables `NotificationListenerService`.
4. Inspects existing `sem_vibration_*` system settings keys.
5. Launches `MainActivity` and streams real-time suppression logcat events.

### Running Unit Tests (20/20 Passing)
```bash
./gradlew test
```

### Building Debug APK
```bash
./gradlew assembleDebug
# Generated APK: app/build/outputs/apk/debug/app-debug.apk
```

---

## 6. Permissions & ADB Quick Reference

### Granting Permissions via ADB
```bash
# Grant WRITE_SETTINGS (for vibration intensity control)
adb shell appops set com.example.suppressor WRITE_SETTINGS allow

# Grant Notification Listener access (for vibration fallback)
adb shell settings put secure enabled_notification_listeners "com.example.suppressor/com.example.suppressor.service.SuppressionNotificationListener"

# Verify Samsung vibration settings keys
adb shell settings list system | grep -i vibrat
adb shell settings list secure | grep -i vibrat

# Query active audio recordings
adb shell dumpsys media.audio_flinger
```

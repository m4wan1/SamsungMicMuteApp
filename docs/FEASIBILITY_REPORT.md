# One UI 8 Physical Device Testing - Feasibility Report

**Version:** 1.0  
**Date:** 2026-11-08  
**Status:** DRAFT - Pending Physical Device Testing  
**Test Device Target:** Samsung Galaxy S24/S23 series running One UI 8 (Android 15)

---

## Executive Summary

This report documents the feasibility of implementing automatic notification sound and vibration suppression during microphone usage on Samsung One UI 8 devices, without using `RECORD_AUDIO` permission, Do Not Disturb, or global Silent Mode. The solution leverages `AudioManager.AudioRecordingCallback` for microphone detection and `AudioManager` + `WRITE_SETTINGS` for selective stream muting.

---

## Compatibility Matrix

| Feature | One UI 8 | One UI 9 |
|---------|----------|----------|
| Microphone detection without RECORD_AUDIO | **Unknown** (requires physical testing) | **Unknown** (not yet released) |
| Independent STREAM_NOTIFICATION muting | **Unknown** (requires physical testing) | **Unknown** (not yet released) |
| Samsung SEM_VIBRATION_NOTIFICATION_INTENSITY muting (via WRITE_SETTINGS) | **Unknown** (requires physical testing) | **Unknown** (not yet released) |
| Incoming call ringtone & vibration preservation | **Unknown** (requires physical testing) | **Unknown** (not yet released) |
| Background service reliability (FGS / NotificationListener) | **Unknown** (requires physical testing) | **Unknown** (not yet released) |
| Automatic state & intensity restoration | **Unknown** (requires physical testing) | **Unknown** (not yet released) |

> **⚠️ IMPORTANT:** All One UI 8 entries are marked **Unknown** because they require actual physical device testing. No claims of "Verified" or "Partial" can be made without empirical evidence from a Samsung device running One UI 8. One UI 9 compatibility is **not yet verified** as the OS is not publicly released.

---

## Test Plan Overview

### Target Applications for Testing

| Application | Test Category | AudioSource Expected |
|-------------|---------------|---------------------|
| Samsung Voice Recorder | System App | MIC / VOICE_RECOGNITION |
| Snapchat | Third-party Social | VOICE_COMMUNICATION / MIC |
| WhatsApp Voice Messages | Third-party Communication | VOICE_COMMUNICATION |
| Camera Video Recording | System App | CAMCORDER |
| Google Assistant / Bixby | Voice Assistant | VOICE_RECOGNITION / MIC |
| Phone App (VoIP) | System/Third-party | VOICE_COMMUNICATION |
| Discord / Telegram Voice | Third-party Communication | VOICE_COMMUNICATION |
| Screen Recording (Built-in) | System Feature | MIC / CAMCORDER |

---

## Detailed Test Cases

### 1. Microphone Detection Without RECORD_AUDIO

**Objective:** Verify `AudioManager.AudioRecordingCallback` reliably detects active recording sessions across all test applications without requiring `RECORD_AUDIO` permission.

**Test Steps:**
1. Install test application with `AudioRecordingCallback` registered
2. Grant `NOTIFICATION_LISTENER_SERVICE` permission (if needed for vibration)
3. **Do NOT grant** `RECORD_AUDIO` permission
4. Launch each target application
5. Start recording/voice message in each app
6. Observe callback invocation: `onRecordingConfigChanged(List<AudioRecordingConfiguration>)`
7. Verify callback provides:
   - `AudioRecordingConfiguration.getAudioSource()` (MIC, VOICE_COMMUNICATION, CAMCORDER, VOICE_RECOGNITION)
   - `AudioRecordingConfiguration.getClientPackageName()`
   - `AudioRecordingConfiguration.isClientActive()`

**Expected Results:**
- Callback fires within 500ms of recording start
- Callback fires on recording stop
- Correct `AudioSource` reported per application
- Package name identification works for all test apps

**One UI 8 Specific Risks:**
- Samsung may restrict callback to system apps only
- Callback may require `CAPTURE_AUDIO_HOTWORD` or similar privileged permission
- Background app recording may not trigger callback due to battery optimization

---

### 2. Callback Reliability Across AudioSource Types

**Objective:** Verify consistent detection across all `AudioSource` types used by target applications.

| AudioSource | Test Applications | Expected Behavior |
|-------------|-------------------|-------------------|
| `MIC` | Voice Recorder, Screen Record | Detected reliably |
| `VOICE_COMMUNICATION` | WhatsApp, Snapchat, Discord, Phone VoIP | Detected reliably |
| `CAMCORDER` | Camera Video, Screen Record (with audio) | Detected reliably |
| `VOICE_RECOGNITION` | Google Assistant, Bixby | Detected reliably |
| `UNPROCESSED` | Rare, but possible | Should be detected |
| `VOICE_PERFORMANCE` | Karaoke apps | Should be detected |

**Test Matrix:**
- [ ] Start recording → verify callback → stop recording → verify callback
- [ ] Rapid start/stop cycles (10x) → no missed callbacks
- [ ] Concurrent recordings (two apps) → both detected
- [ ] App killed during recording → cleanup callback received

---

### 3. Notification Sound Suppression (STREAM_NOTIFICATION)

**Objective:** Verify independent muting of `STREAM_NOTIFICATION` without affecting other streams.

**Test Steps:**
1. Record user's current `STREAM_NOTIFICATION` volume via `AudioManager.getStreamVolume(STREAM_NOTIFICATION)`
2. Set `STREAM_NOTIFICATION` volume to 0 via `AudioManager.setStreamVolume(STREAM_NOTIFICATION, 0, 0)`
3. Trigger notification (send test SMS, email, or use `NotificationManager.notify()`)
4. Verify: **No sound plays** for notification
5. Verify: **Media playback continues** (play music/video)
6. Verify: **Alarm sounds** (set near-future alarm)
7. Verify: **Call ringtone plays** (simulate incoming call)
8. Restore original volume
9. Verify: Notification sound returns to original level

**One UI 8 Specific Risks:**
- Samsung may link `STREAM_NOTIFICATION` to `STREAM_SYSTEM` or `STREAM_RING`
- Volume panel UI may not reflect programmatic changes immediately
- `FLAG_SHOW_UI` / `FLAG_PLAY_SOUND` flags may behave differently

**Verification Commands (ADB):**
```bash
# Check current stream volumes
adb shell media volume --show

# Check notification stream specifically
adb shell settings get system volume_notification

# Monitor volume changes in real-time
adb shell dumpsys audio | grep -A5 "STREAM_NOTIFICATION"
```

---

### 4. Notification Vibration Suppression (SEM_VIBRATION_NOTIFICATION_INTENSITY)

**Objective:** Verify suppression of notification vibration via Samsung's `SEM_VIBRATION_NOTIFICATION_INTENSITY` setting using `WRITE_SETTINGS` permission.

**Prerequisites:**
- `android.permission.WRITE_SETTINGS` granted (user must enable via Settings > Apps > Special Access > Modify System Settings)
- Device must be Samsung (One UI)

**Test Steps:**
1. Read current value: `Settings.System.getInt(contentResolver, "sem_vibration_notification_intensity")`
   - Expected range: 0-100 (or 0-3 on some versions)
2. Cache original value persistently (SharedPreferences + BackupManager)
3. Set to 0: `Settings.System.putInt(contentResolver, "sem_vibration_notification_intensity", 0)`
4. Trigger notification with vibration pattern
5. Verify: **No vibration** for notification
6. Verify: **Call vibration still works** (simulate incoming call)
6. Verify: **Alarm vibration still works**
7. Restore original value
8. Verify: Notification vibration returns

**ADB Verification:**
```bash
# Check current vibration intensity setting
adb shell settings get system sem_vibration_notification_intensity

# List all vibration-related settings
adb shell settings list system | grep -i vibrat

# Check if setting exists on device
adb shell settings get system sem_vibration_notification_intensity 2>&1
```

**One UI 8 Specific Risks:**
- Setting key may have changed (e.g., `sem_vibration_noti_intensity`, `notification_vibration_intensity`)
- May require `WRITE_SECURE_SETTINGS` (ADB-only) on newer firmware
- Vibration may be controlled per-notification-channel instead of globally
- Samsung may have moved to `VibrationEffect` API bypassing system setting

---

### 5. Incoming Call Ringtone & Vibration Preservation

**Objective:** Ensure `STREAM_RING` and `SEM_VIBRATION_RING_INTENSITY` remain completely unaffected.

**Test Steps:**
1. Record original `STREAM_RING` volume
2. Record original `sem_vibration_ring_intensity` (if exists)
3. Apply notification suppression (steps 3-4 above)
4. Simulate incoming call:
   - Use `TelecomManager` or second phone to call test device
   - Or use `adb shell am broadcast -a android.intent.action.PHONE_STATE --es state RINGING`
5. Verify: **Ringtone plays at original volume**
6. Verify: **Call vibration plays at original intensity**
7. Verify: No interference with call audio routing
8. Answer call → verify in-call audio unaffected
9. End call → verify restoration still holds

**ADB Verification:**
```bash
# Check ring stream volume
adb shell settings get system volume_ring

# Check ring vibration intensity
adb shell settings get system sem_vibration_ring_intensity
adb shell settings list system | grep -i ring
```

---

### 6. Automatic State Restoration

**Objective:** Verify exact restoration of user's previous notification volume and vibration intensity after all recording sessions end, including crash/reboot recovery.

**Test Scenarios:**

| Scenario | Test Method | Expected Outcome |
|----------|-------------|------------------|
| Normal recording stop | Stop recording in app | Volume/vibration restored to exact original values |
| App killed during recording | `adb shell am kill <package>` | Restoration via `onRecordingConfigChanged` (empty list) |
| Service crash | Force stop test service | Restoration via persisted state on restart |
| Device reboot | Reboot device during recording | Restoration on boot via `BOOT_COMPLETED` receiver |
| Screen lock during recording | Lock screen while recording | Recording continues, suppression holds |
| App backgrounded | Home button during recording | Suppression holds, restoration on stop |
| Multiple concurrent apps | Start 2+ recording apps | Suppression holds until LAST app stops |

**Persistence Mechanism:**
- Primary: `SharedPreferences` with `MODE_PRIVATE`
- Backup: `BackupManager` for cross-device restore
- Boot recovery: `BroadcastReceiver` for `BOOT_COMPLETED` + `ACTION_LOCKED_BOOT_COMPLETED`
- State schema:
  ```json
  {
    "originalNotificationVolume": 7,
    "originalVibrationIntensity": 50,
    "suppressionActive": false,
    "activeRecordingCount": 0,
    "lastKnownRecordingPackages": ["com.whatsapp", "com.snapchat.android"],
    "timestamp": 1731000000000
  }
  ```

---

### 7. Screen Lock Behavior

**Objective:** Verify suppression persists and functions correctly when screen is locked.

**Test Steps:**
1. Start recording in any test app
2. Verify suppression active (notification silent, no vibration)
3. Lock screen (power button)
4. Wait 30 seconds
5. Trigger notification (from another device or ADB)
6. Verify: **No sound, no vibration**
7. Unlock screen
8. Verify: Suppression still active
9. Stop recording
10. Verify: Restoration works post-unlock

**One UI 8 Risks:**
- Doze mode / App Standby may kill background service
- `AudioRecordingCallback` may not fire when screen off
- Foreground service required with `FOREGROUND_SERVICE_MICROPHONE` type

---

### 8. Background Application Behavior

**Objective:** Verify detection and suppression work when recording app is in background.

**Test Steps:**
1. Start recording in WhatsApp/Snapchat voice message
2. Press Home (app goes to background)
3. Verify: Recording continues (check app UI or notification)
4. Trigger notification
5. Verify: **Suppression active**
6. Return to app, stop recording
7. Verify: **Restoration works**

**Android 15 / One UI 8 Considerations:**
- Background recording requires `FOREGROUND_SERVICE_MICROPHONE` (Android 14+)
- App may show "Recording in progress" notification
- `AudioRecordingCallback` should still fire for background apps
- Battery optimization may restrict background execution

---

### 9. Service Restart Behavior

**Objective:** Verify suppression state survives service restart (crash, update, system kill).

**Test Steps:**
1. Start recording, verify suppression active
2. Force-stop test service: `adb shell am force-stop <test_package>`
3. Verify: Suppression **may be lost** (this is expected gap)
4. Restart test service (launch app)
5. Verify: Service detects active recording via `AudioRecordingCallback` on register
6. Verify: Suppression **re-applied** if recording still active
6. Stop recording
7. Verify: Restoration uses **persisted original values** (not current system values)

**Critical Implementation Requirement:**
- On service start: Query `AudioManager.getActiveRecordingConfigurations()`
- If any active → immediately apply suppression using **persisted** original values
- Do NOT read current system values (they may already be muted)

---

## One UI 9 Forward Compatibility Analysis

### Current Status: **NOT VERIFIED**

One UI 9 (expected Android 16 / Q4 2026) is not publicly released. No API documentation, developer previews, or device firmware available for testing.

### Potential Framework Changes That Could Impact Implementation

| Area | Potential Change | Impact | Mitigation |
|------|-----------------|--------|------------|
| AudioRecordingCallback | Deprecated or restricted to privileged apps | Breaks detection | Fallback to NotificationListenerService + UsageStats |
| WRITE_SETTINGS | Further restricted (requires ADB) | Breaks vibration control | Fallback to NotificationListenerService channel suppression |
| SEM_* Settings | Keys renamed, moved to secure settings, or removed | Breaks vibration control | Dynamic key discovery + fallback hierarchy |
| Stream Architecture | STREAM_NOTIFICATION merged/removed | Breaks sound muting | Use AudioAttributes + per-notification-channel control |
| Vibration API | Unified VibrationEffect API bypassing system settings | Breaks vibration control | NotificationListenerService cancel vibration |
| Foreground Service Types | New restrictions on MICROPHONE type | Breaks background detection | Use companion app / WorkManager |
| Privacy Indicators | Enhanced recording indicators | May affect callback timing | Monitor indicator APIs |

### Modular Controller Architecture

To support future API changes and fallback mechanisms, the implementation **must** use a modular controller architecture:

```
┌─────────────────────────────────────────────────────────────┐
│                    RecordingSuppressor                      │
│  (Orchestrates detection → suppression → restoration)       │
└─────────────────────┬───────────────────────────────────────┘
                      │
        ┌─────────────┴─────────────┐
        ▼                           ▼
┌───────────────┐           ┌───────────────────┐
│AudioStream    │           │VibrationSettings  │
│Controller     │           │Controller         │
│               │           │                   │
│- getVolume()  │           │- getIntensity()   │
│- setVolume()  │           │- setIntensity()   │
│- restore()    │           │- restore()        │
│- supports()   │           │- supports()       │
└───────┬───────┘           └─────────┬─────────┘
        │                             │
        ▼                             ▼
┌───────────────┐           ┌───────────────────┐
│OneUI8Stream   │           │OneUI8Vibration    │
│Impl           │           │Impl               │
│               │           │                   │
│- STREAM_NOTIF │           │- SEM_VIBRATION_   │
│  _VOLUME      │           │  NOTIFICATION_    │
│- AudioManager │           │  INTENSITY        │
└───────────────┘           │- Settings.System  │
                            └───────────────────┘
                                    
        Future: OneUI9StreamImpl, OneUI9VibrationImpl
        Fallback: NotificationListenerStreamImpl, NotificationListenerVibrationImpl
```

**Interface Contracts:**

```kotlin
// AudioStreamController.kt
interface AudioStreamController {
    val streamType: Int
    fun getCurrentVolume(): Int
    fun setVolume(volume: Int): Boolean
    fun restoreVolume(originalVolume: Int): Boolean
    fun isSupported(): Boolean
    fun getFallback(): AudioStreamController?
}

// VibrationSettingsController.kt
interface VibrationSettingsController {
    val settingKey: String
    fun getCurrentIntensity(): Int
    fun setIntensity(intensity: Int): Boolean
    fun restoreIntensity(originalIntensity: Int): Boolean
    fun isSupported(): Boolean
    fun getFallback(): VibrationSettingsController?
}
```

---

## One UI 9 Future Validation Checklist

When One UI 9 becomes available (physical device or emulator), execute this checklist:

### Pre-Testing Setup
- [ ] Obtain One UI 9 device (Galaxy S25 series or later)
- [ ] Document Android version, One UI version, security patch level
- [ ] Capture `adb shell settings list system` output (full)
- [ ] Capture `adb shell settings list secure` output (full)
- [ ] Capture `adb shell dumpsys audio` output

### AudioRecordingCallback Validation
- [ ] Register callback without RECORD_AUDIO
- [ ] Test with Samsung Voice Recorder
- [ ] Test with WhatsApp voice message
- [ ] Test with Camera video
- [ ] Test with Snapchat
- [ ] Test with Google Assistant
- [ ] Test background recording
- [ ] Test screen-off recording
- [ ] Verify callback latency < 500ms
- [ ] Verify all AudioSource types reported correctly

### Stream Volume Validation
- [ ] Enumerate all AudioManager stream types
- [ ] Verify STREAM_NOTIFICATION exists and is independent
- [ ] Verify STREAM_RING independent
- [ ] Verify STREAM_ALARM independent
- [ ] Verify STREAM_MUSIC independent
- [ ] Test setStreamVolume(STREAM_NOTIFICATION, 0) suppresses notifications
- [ ] Test setStreamVolume(STREAM_NOTIFICATION, 0) does NOT affect ring/alarm/media

### Samsung Vibration Settings Validation
- [ ] Search for vibration settings: `adb shell settings list system | grep -i vibrat`
- [ ] Search secure settings: `adb shell settings list secure | grep -i vibrat`
- [ ] Identify notification vibration intensity key
- [ ] Identify ring vibration intensity key
- [ ] Test WRITE_SETTINGS permission grant flow
- [ ] Test setting notification vibration to 0
- [ ] Verify ring vibration unaffected
- [ ] Test restoration

### Permission & Policy Validation
- [ ] Test WRITE_SETTINGS grant persistence across reboot
- [ ] Test FOREGROUND_SERVICE_MICROPHONE requirement
- [ ] Test NOTIFICATION_LISTENER_SERVICE capability
- [ ] Check for new "Modify Secure Settings" permission
- [ ] Verify AudioRecordingCallback not restricted to privileged apps

### Integration Validation
- [ ] Full end-to-end: recording starts → suppression → recording stops → restoration
- [ ] Concurrent recordings (2+ apps)
- [ ] Crash/restart recovery
- [ ] Reboot recovery
- [ ] Screen lock/unlock cycles
- [ ] Background/foreground transitions

### Regression Testing
- [ ] All One UI 8 test cases pass on One UI 9
- [ ] No behavioral regressions detected
- [ ] Performance: CPU/memory/battery impact acceptable

---

## Evidence Requirements for Verification

Every compatibility claim in the matrix **must** be supported by one of:

| Evidence Type | Example |
|---------------|---------|
| Official Documentation | Samsung Developer docs, Android API reference |
| System Settings Inspection | `adb shell settings list system` output showing key exists |
| Source Code Evidence | AOSP / Samsung kernel source showing implementation |
| Physical Device Testing | Video/logcat from actual device showing behavior |

**No claim shall be marked "Verified" or "Partial" without physical device testing evidence.**

---

## Risk Assessment

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| AudioRecordingCallback unavailable on One UI 8 | Medium | High | Fallback to NotificationListenerService + UsageStatsManager |
| SEM_VIBRATION_NOTIFICATION_INTENSITY requires WRITE_SECURE_SETTINGS | High | High | Document fallback hierarchy; use ADB-granted permission for testing |
| STREAM_NOTIFICATION linked to STREAM_RING on Samsung | Low | Medium | Test independence; use per-channel notification control if needed |
| Background service killed by system | High | High | Foreground service with proper type; WorkManager backup |
| One UI 9 breaks all current mechanisms | Unknown | Critical | Modular architecture; validation checklist ready |
| Vibration controlled per-channel not globally | Medium | High | NotificationListenerService cancel vibration per notification |

---

## Next Steps

1. **Obtain One UI 8 physical device** (Galaxy S24/S23 on One UI 8)
2. **Execute test plan** systematically, documenting results with logcat/video
3. **Update FEASIBILITY_REPORT.md** with actual Verified/Partial/Unknown results
4. **Create IMPLEMENTATION_PLAN.md** based on verified capabilities
5. **Review and approve** both documents before production implementation
6. **Prepare One UI 9 validation** for when devices become available

---

## Appendix: ADB Command Reference

```bash
# Audio recording configurations
adb shell dumpsys media.audio_flinger | grep -A20 "AudioRecording"

# Active recording configurations (requires shell permission)
adb shell cmd media_session dispatch 0 0 0 0 0  # Not applicable

# Better: Use test app to call AudioManager.getActiveRecordingConfigurations()

# Volume settings
adb shell settings list system | grep volume
adb shell settings list system | grep -i notif
adb shell settings list system | grep -i vibrat

# Secure settings (may require root/ADB)
adb shell settings list secure | grep -i vibrat

# Audio policy
adb shell dumpsys audio_policy

# Notification listener status
adb shell settings get secure enabled_notification_listeners

# Battery optimization
adb shell dumpsys deviceidle | grep -A5 -B5 <package>

# Foreground services
adb shell dumpsys activity services <package>
```

---

*Document prepared for One UI 8 Physical Device Testing Program. All compatibility claims pending empirical validation.*
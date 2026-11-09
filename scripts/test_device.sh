#!/usr/bin/env bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
APK_PATH="$PROJECT_ROOT/app/build/outputs/apk/debug/app-debug.apk"
PACKAGE_NAME="com.example.suppressor"
NLS_COMPONENT="$PACKAGE_NAME/$PACKAGE_NAME.service.SuppressionNotificationListener"

echo "=========================================================="
echo " Samsung Notification Suppressor - Physical Device Testing"
echo "=========================================================="

# Check ADB
if ! command -v adb &> /dev/null; then
    echo "❌ Error: adb not found. Please ensure Android platform-tools is installed."
    exit 1
fi

# Check connected device
DEVICE_COUNT=$(adb devices | grep -v "List" | grep "device$" | wc -l | tr -d ' ')
if [ "$DEVICE_COUNT" -eq "0" ]; then
    echo "⚠️  No authorized device found via ADB."
    echo "👉 Please connect your Samsung device via USB, enable USB Debugging, and accept the prompt."
    echo "   Checking again..."
    adb wait-for-device
fi

DEVICE_MODEL=$(adb shell getprop ro.product.model | tr -d '\r')
ONE_UI_VERSION=$(adb shell getprop ro.build.version.oneui 2>/dev/null | tr -d '\r')
ANDROID_VERSION=$(adb shell getprop ro.build.version.release | tr -d '\r')

echo "📱 Connected Device: $DEVICE_MODEL"
echo "🤖 Android Version : $ANDROID_VERSION"
if [ -n "$ONE_UI_VERSION" ]; then
    echo "🎨 One UI Version  : $ONE_UI_VERSION"
fi
echo "----------------------------------------------------------"

# 1. Install APK
echo "📦 Installing APK ($APK_PATH)..."
if [ ! -f "$APK_PATH" ]; then
    echo "⚠️  APK not found. Building debug APK first..."
    cd "$PROJECT_ROOT"
    JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleDebug
fi

adb install -r "$APK_PATH"
echo "✅ APK successfully installed."

# 2. Grant Special Permissions
echo "🔑 Configuring test permissions..."
adb shell appops set "$PACKAGE_NAME" WRITE_SETTINGS allow
echo "   - WRITE_SETTINGS granted."

# Append NLS if not already enabled
CURRENT_NLS=$(adb shell settings get secure enabled_notification_listeners | tr -d '\r')
if [[ "$CURRENT_NLS" != *"$NLS_COMPONENT"* ]]; then
    NEW_NLS="${CURRENT_NLS:+${CURRENT_NLS}:}$NLS_COMPONENT"
    adb shell settings put secure enabled_notification_listeners "$NEW_NLS"
    echo "   - NotificationListenerService enabled."
else
    echo "   - NotificationListenerService already enabled."
fi

# 3. Inspect Samsung Vibration Keys
echo "----------------------------------------------------------"
echo "🔍 Samsung System Vibration Settings:"
adb shell settings list system | grep -i "sem_vibration" || echo "   (No sem_vibration keys found in system settings)"
echo "----------------------------------------------------------"

# 4. Launch App
echo "🚀 Launching application..."
adb shell am start -n "$PACKAGE_NAME/$PACKAGE_NAME.ui.MainActivity"

# 5. Start live monitoring
echo "👀 Monitoring suppression events (Press Ctrl+C to stop)..."
echo "----------------------------------------------------------"
adb logcat -c
adb logcat -v time -s RecordingSuppressor SuppressionFgService SuppressionNLS OneUI8AudioController OneUI8VibrationCtrl

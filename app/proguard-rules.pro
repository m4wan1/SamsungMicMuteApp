# Samsung Mic Mute App - ProGuard Rules

# Keep Kotlin serialization
-keepclassmembers class * {
    @kotlinx.serialization.Serializable *;
}

# Keep NotificationListenerService
-keep class com.example.suppressor.service.SuppressionNotificationListener {
    *;
}

# Keep BroadcastReceivers
-keep class com.example.suppressor.receiver.BootRecoveryReceiver {
    public <init>();
    public void onReceive(android.content.Context, android.content.Intent);
}

# Keep Service
-keep class com.example.suppressor.service.SuppressionForegroundService {
    public <init>();
    public void onCreate();
    public int onStartCommand(android.content.Intent, int, int);
    public void onDestroy();
    public android.os.IBinder onBind(android.content.Intent);
}

# Keep RecordingSuppressor
-keep class com.example.suppressor.RecordingSuppressor {
    *;
}

# Keep Controllers
-keep class com.example.suppressor.audio.** { *; }
-keep class com.example.suppressor.vibration.** { *; }
-keep class com.example.suppressor.persistence.** { *; }

# Keep data classes for serialization
-keep class com.example.suppressor.persistence.SuppressionState { *; }

# Keep MainActivity
-keep class com.example.suppressor.ui.MainActivity { *; }

# Suppress warnings for reflection usage
-dontwarn kotlinx.serialization.**
-dontwarn android.media.AudioManager$AudioRecordingCallback

# Keep Application and BackupAgent
-keep class com.example.suppressor.SuppressionApplication { *; }
-keep class com.example.suppressor.SuppressionBackupAgent { *; }

# Keep Debug Manager & FileProvider
-keep class com.example.suppressor.debug.DebugLogManager { *; }
-keep class androidx.core.content.FileProvider { *; }

# Keep Samsung One UI reflection vibration keys
-keepclassmembers class * {
    public static final java.lang.String SEM_VIBRATION_*;
    public static final java.lang.String sem_vibration_*;
}

# Keep JavaScript Interface for WebView
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep annotations
-keepattributes *Annotation*,EnclosingMethod,Signature,InnerClasses,SourceFile,LineNumberTable
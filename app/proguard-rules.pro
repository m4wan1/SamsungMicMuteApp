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

# Keep annotations
-keepattributes *Annotation*,EnclosingMethod,Signature,InnerClasses
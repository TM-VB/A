# TDLib JNI & Models
-keep class org.drinkless.tdlib.** { *; }
-keepclassmembers class org.drinkless.tdlib.** { *; }
-keep class org.drinkless.tdlib.TdApi$* { *; }

# Room Database
-keep class androidx.room.** { *; }
-dontwarn androidx.room.paging.**
-keepclassmembers class androidx.room.** { *; }

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# Lifecycle
-keepclassmembers class androidx.lifecycle.** {
    public *;
}

# Compose
-keepclassmembers class androidx.compose.** { *; }

# Android
-keepclassmembers class android.os.** {
    public *;
}
-keepclassmembers class android.content.** {
    public *;
}

# General
-dontwarn java.lang.invoke.StringConcatFactory
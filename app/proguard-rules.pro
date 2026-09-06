# Keep attributes for debugging and annotations
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# Room Database keep rules
-keep class androidx.room.RoomDatabase
-keep class * extends androidx.room.RoomDatabase
-keep class * extends androidx.room.migration.Migration
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }
-dontwarn androidx.room.paging.**

# Domain and Data Models (entities, enums, converter targets)
-keep class com.example.domain.model.** { *; }
-keep class com.example.data.local.** { *; }

# yausername and youtube-dl / yt-dlp internal mapper models and bridge
-keep class com.yausername.youtubedl_android.mapper.** { *; }
-keep class com.yausername.youtubedl_android.** { *; }
-keep class com.yausername.ffmpeg.** {
    public static *;
    public *;
    private java.io.File binDir;
}

# OkHttp rules
-dontwarn okhttp3.**
-dontwarn okio.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# Moshi rules
-keepattributes *JavascriptInterface*
-keep class com.squareup.moshi.** { *; }
-keep interface com.squareup.moshi.** { *; }
-keepclassmembers class * {
    @com.squareup.moshi.* <fields>;
    @com.squareup.moshi.* <methods>;
}

# Media3 / ExoPlayer
-keep class androidx.media3.** { *; }


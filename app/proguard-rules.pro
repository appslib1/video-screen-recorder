# Preserve stack traces for crash reports
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ---- App data classes (used by enums with valueOf reflection) ----
-keepclassmembers enum com.screen_recorder.screen_video_recorder.data.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ---- Glide ----
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class * extends com.bumptech.glide.module.AppGlideModule {
    <init>(...);
}
-keep public enum com.bumptech.glide.load.ImageHeaderParser$** {
    **[] $VALUES;
    public *;
}
-keep class com.bumptech.glide.load.data.ParcelFileDescriptorRewinder$InternalRewinder {
    *** rewind();
}

# ---- Google Mobile Ads (AdMob) ----
# Google Play Services ships consumer rules; keep basic reflection targets.
-keep class com.google.android.gms.ads.** { *; }
-keep class com.google.android.gms.ads.identifier.** { *; }
-dontwarn com.google.android.gms.ads.**

# ---- Material Components ----
# Already ships consumer rules; no custom config needed.

# ---- Room / WorkManager ----
# WorkManager (pulled in transitively by play-services-ads) auto-inits via
# WorkManagerInitializer, which uses Room. Room.getGeneratedImplementation()
# loads WorkDatabase_Impl by reflection; R8 strips it without these rules.
-keep class * extends androidx.room.RoomDatabase {
    <init>();
}
-keep class androidx.work.impl.WorkDatabase_Impl { *; }
-keep class androidx.room.paging.** { *; }
-dontwarn androidx.work.**

# ---- ViewBinding ----
-keep class com.screen_recorder.screen_video_recorder.databinding.** { *; }

# ---- Services, Receivers, Activities declared in manifest (R8 keeps automatically) ----

# ---- Suppress harmless warnings ----
-dontwarn javax.annotation.**
-dontwarn org.jetbrains.annotations.**

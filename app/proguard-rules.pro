# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Room entities/DAOs: generated *Impl code references them directly, so R8 full mode already
# keeps what's used; keep names only for readable stack traces / DB debugging.
-keepnames class com.noti.logger.data.**

# WorkManager instantiates workers reflectively from the name stored in the WorkSpec. androidx.work's
# consumer rules keep ListenableWorker subclasses, but keep their constructors explicitly anyway -
# cheap insurance against a rule change in the library.
-keep class com.noti.logger.work.** {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# ---- kotlinx.serialization ----
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
# Keep generated serializers for our @Serializable payload models
-keepclassmembers class com.noti.logger.upload.** {
    *** Companion;
}
-keepclasseswithmembers class com.noti.logger.upload.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.noti.logger.upload.**$$serializer { *; }

# ---- Tink / androidx.security.crypto (EncryptedSharedPreferences) ----
# Tink loads key-type managers reflectively; keep them to avoid keyset failures under R8.
-keep class com.google.crypto.tink.** { *; }
-keep class com.google.crypto.tink.shaded.protobuf.** { *; }
-dontwarn com.google.crypto.tink.**

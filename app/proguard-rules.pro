# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Keep Room entities
-keep class com.noti.logger.data.** { *; }

# Keep WorkManager workers
-keep class com.noti.logger.work.** { *; }

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

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class ru.secondbrain.app.**$$serializer { *; }
-keepclassmembers class ru.secondbrain.app.** { *** Companion; }
-keepclasseswithmembers class ru.secondbrain.app.** { kotlinx.serialization.KSerializer serializer(...); }

# SQLCipher (JNI)
-keep class net.zetetic.database.** { *; }
-keep class net.zetetic.database.sqlcipher.** { *; }

# OkHttp
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Room
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

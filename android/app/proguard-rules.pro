# --- kotlinx.serialization (wire payloads) ---
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.ara.**$$serializer { *; }
-keepclassmembers class com.ara.** { *** Companion; }
-keepclasseswithmembers class com.ara.** { kotlinx.serialization.KSerializer serializer(...); }

# --- WebRTC (JNI callbacks) ---
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# --- SQLCipher ---
-keep class net.zetetic.database.** { *; }
-dontwarn net.zetetic.database.**

# --- BouncyCastle ---
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# --- Retrofit / OkHttp ---
-dontwarn okhttp3.**
-dontwarn retrofit2.**
-keepattributes Signature, Exceptions
-keepclasseswithmembers class * { @retrofit2.http.* <methods>; }

# --- zxing ---
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**

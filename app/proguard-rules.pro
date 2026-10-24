# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Keep Android classes
-keep class android.app.** { *; }
-keep class android.view.** { *; }
-keep class android.widget.** { *; }
-keep class androidx.** { *; }

# Keep Kotlin Coroutines
-keep class kotlinx.coroutines.** { *; }

# Keep OkHttp
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-dontwarn okhttp3.internal_platform.Platform
-dontwarn okhttp3.internal.concurrent.TaskRunner
-dontwarn org.codehaus.mirrored.**

# Keep JSON
-keep class org.json.** { *; }

# Keep generated classes
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingClass
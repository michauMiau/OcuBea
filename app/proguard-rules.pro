# OcuBea ProGuard Rules

# Keep NanoHttpd classes
-keep class fi.iki.elonen.** { *; }

# Keep CameraX APIs
-keep class androidx.camera.** { *; }

# Keep MediaCodec related code
-dontwarn android.media.MediaCodec**

# Keep kotlinx.coroutines
-keepnames class kotlinx.coroutines.** { *; }

# Reflection and JNI.

# The app resolves encoder names as strings and hands them to MediaCodec, so the
# codec classes themselves are looked up rather than linked. R8 cannot see that.
-keep class android.media.MediaCodec$* { *; }
-keep class android.media.MediaCodecInfo$* { *; }
-keep class android.media.MediaFormat { *; }

# ViewBinding inflate() is called reflectively from generated code.
-keep class * implements androidx.viewbinding.ViewBinding { *; }

# Service and receiver entry points are named in the manifest as strings.
-keep class com.ocubea.service.** { *; }
-keep class com.ocubea.BootReceiver { *; }

# ONVIF and RTSP build WSDL and SOAP bodies by string concatenation, and the
# handlers dispatch on request path. Nothing here is reflected, but the class
# names appear in /status.json output, so keep them readable for bug reports.
-keepnames class com.ocubea.onvif.** { *; }
-keepnames class com.ocubea.server.** { *; }

# Keep enum values: the app reports them by name in status.json and compares
# them by name in tests.
-keepclassmembers enum com.ocubea.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Keep the Kotlin intrinsics coroutines rely on.
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-dontwarn kotlinx.coroutines.**

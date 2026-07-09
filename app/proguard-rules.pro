# OcuBea ProGuard Rules

# Keep NanoHttpd classes
-keep class fi.iki.elonen.** { *; }

# Keep CameraX APIs
-keep class androidx.camera.** { *; }

# Keep MediaCodec related code
-dontwarn android.media.MediaCodec**

# Keep kotlinx.coroutines
-keepnames class kotlinx.coroutines.** { *; }

# General rules
-dontoptimize
-dontobfuscate
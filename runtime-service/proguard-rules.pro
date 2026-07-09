# ============================================================================
# EdgeLM runtime — R8 keep rules for release builds.
# The two things R8 must not touch: the JNI bridge and the AIDL/Binder contract.
# ============================================================================

# --- JNI bridge -------------------------------------------------------------
# The native lib (libedgelm.so) resolves these by their fully-qualified names
# (Java_ai_edgelm_service_NativeBridge_loadModel, ...). Renaming or removing the
# class or its native methods breaks the link at runtime. The C++ also calls
# back into TokenSink.onChunk()/isCancelled() by name.
-keep class ai.edgelm.service.NativeBridge { *; }
-keep interface ai.edgelm.service.NativeBridge$TokenSink { *; }
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# --- AIDL / Binder contract -------------------------------------------------
# Stub/Proxy and the streaming callback are used across process boundaries;
# keep the whole contract package to be safe.
-keep class ai.edgelm.contract.** { *; }

# --- App entry points -------------------------------------------------------
# Manifest components are kept by AGP automatically, but keep the runtime
# package (services/activities referenced via Intents & foreground-service APIs).
-keep class ai.edgelm.service.** { *; }
-keep class ai.edgelm.runtime.** { *; }

# --- Third-party ------------------------------------------------------------
# NanoHTTPD is only started in debug builds, but is still compiled in; silence
# R8 warnings about its optional deps.
-dontwarn fi.iki.elonen.**
-dontwarn kotlinx.coroutines.**

# Keep source line numbers for readable crash reports, but hide the file name.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

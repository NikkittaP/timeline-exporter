# =============================================================================
# Timeline Exporter — R8 / ProGuard configuration for release builds.
#
# AGP automatically includes proguard-android-optimize.txt (Android defaults)
# AND the consumer-proguard files shipped inside each AndroidX / Compose /
# kotlinx artifact. So we ONLY need to add rules for libraries that don't
# ship their own, or for our own code if we ever do reflection on it.
#
# The two we need to handle by hand: kotlinx.serialization (its rules are
# distributed in docs, not embedded) and MapLibre Native (reflective JNI
# bindings).
# =============================================================================


# -----------------------------------------------------------------------------
# Stack-trace readability — keep line numbers so user-reported crash stacks
# are usable, but obfuscate the source file name (default obfuscator behavior).
# -----------------------------------------------------------------------------
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile


# -----------------------------------------------------------------------------
# kotlinx.serialization — rules straight from upstream
# (https://github.com/Kotlin/kotlinx.serialization/blob/master/rules/common.pro)
#
# Used by parser/TimelineModels.kt @Serializable data classes. Without these,
# R8 strips the generated $Companion serializers and Json.decodeFromString
# crashes at runtime with a SerializationException about a missing serializer.
# -----------------------------------------------------------------------------
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault

# Keep `Companion` object fields of serializable classes.
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}

# Keep `serializer()` on companion objects (both default and named).
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep `INSTANCE.serializer()` of serializable objects.
-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# Suppress notes that mostly fire on the library's own internals.
-dontnote kotlinx.serialization.**
-dontwarn kotlinx.serialization.internal.ClassValueReferences
-dontwarn kotlinx.serialization.json.**


# -----------------------------------------------------------------------------
# MapLibre Native Android — aggressive keep-all for the SDK package.
#
# MapLibre uses JNI extensively. Native code expects specific Java class /
# method / field names that R8 would otherwise rename or strip, causing
# UnsatisfiedLinkError or silent rendering failures on first map use.
#
# Keeping the whole package is the upstream-recommended approach (see
# https://maplibre.org/maplibre-native/android/api/). The size cost is small
# because most of MapLibre's weight is in the native .so files which R8
# doesn't touch anyway.
# -----------------------------------------------------------------------------
-keep class org.maplibre.android.** { *; }
-keep interface org.maplibre.android.** { *; }
-keep class org.maplibre.geojson.** { *; }

# Legacy Mapbox package — some classes still under com.mapbox.* in older
# transitive deps. Harmless if absent.
-keep class com.mapbox.** { *; }
-dontwarn com.mapbox.**

-dontwarn org.maplibre.**


# -----------------------------------------------------------------------------
# Compose, AndroidX, Kotlin stdlib, coroutines — handled by their own
# consumer-proguard files via AGP. No manual rules needed here.
# -----------------------------------------------------------------------------

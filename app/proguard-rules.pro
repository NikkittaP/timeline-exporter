# =============================================================================
# Timeline Exporter — R8 / ProGuard configuration for release builds.
#
# AGP automatically includes proguard-android-optimize.txt (Android defaults)
# AND the consumer-proguard files shipped inside each AndroidX / Compose /
# kotlinx artifact. So we ONLY need to add rules for libraries that don't
# ship their own, or for our own code if we ever do reflection on it.
#
# The one we need to handle by hand: MapLibre Native (reflective JNI
# bindings). jackson-core is a pure streaming JSON parser with no runtime
# reflection, so it needs no keep rules — only a defensive -dontwarn.
# =============================================================================


# -----------------------------------------------------------------------------
# Stack-trace readability — keep line numbers so user-reported crash stacks
# are usable, but obfuscate the source file name (default obfuscator behavior).
# -----------------------------------------------------------------------------
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile


# -----------------------------------------------------------------------------
# Jackson (jackson-core) — streaming JSON parser used by parser/TimelineParser.
# Pure pull-parser, no reflection, ships no consumer rules. Defensive -dontwarn
# in case R8 sees optional references it can't resolve.
# -----------------------------------------------------------------------------
-dontwarn com.fasterxml.jackson.**


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

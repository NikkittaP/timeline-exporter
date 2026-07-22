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
# Play Billing Library 9.1.0 — no manual rules needed.
#
# Checked during the PBL 7 -> 9 migration (v1.4.0): the billing / billing-ktx
# AARs ship their own consumer-proguard rules, which cover the AIDL service
# interfaces and the classes Play Services calls back into. The types added in
# PBL 8/9 that we touch (QueryProductDetailsResult, UnfetchedProduct) are plain
# result holders we only read from Kotlin — no reflection, no JNI, nothing for
# R8 to break. Do not add keep rules for com.android.billingclient.** unless a
# release build actually misbehaves.
# -----------------------------------------------------------------------------


# -----------------------------------------------------------------------------
# Compose, AndroidX, Kotlin stdlib, coroutines — handled by their own
# consumer-proguard files via AGP. No manual rules needed here.
# -----------------------------------------------------------------------------

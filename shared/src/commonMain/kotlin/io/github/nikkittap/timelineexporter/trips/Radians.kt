package io.github.nikkittap.timelineexporter.trips

/**
 * Degrees to radians, as the platform's own maths library does it.
 *
 * An expect rather than `deg * PI / 180` because `Math.toRadians` is not the
 * same expression on every Android: newer runtimes multiply by a constant,
 * older ones divide by 180 and multiply by PI, and the two differ in the last
 * bit for some inputs. Keeping the JVM call keeps trip distances exactly what
 * the app computed before the core moved to common code.
 *
 * The iOS actual multiplies by the constant 0.017453292519943295, which is
 * `Math.toRadians` as JDK 9+ (and current ART) writes it. Old ART builds
 * (Android 8, the Mi 5s) divide by 180 and multiply by PI instead, so iOS can
 * differ from those devices in the last bit for some inputs. That is
 * intentional: iOS follows the current JVM, not the oldest Android.
 */
internal expect fun toRadians(degrees: Double): Double

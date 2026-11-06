package io.github.nikkittap.timelineexporter.trips

/** `Math.toRadians` as JDK 9+ writes it. */
internal actual fun toRadians(degrees: Double): Double = degrees * DEGREES_TO_RADIANS

private const val DEGREES_TO_RADIANS = 0.017453292519943295

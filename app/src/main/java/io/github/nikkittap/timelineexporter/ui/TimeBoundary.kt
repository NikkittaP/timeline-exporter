package io.github.nikkittap.timelineexporter.ui

import kotlin.time.toJavaInstant
import kotlin.time.toKotlinInstant

// The shared core speaks kotlin.time and kotlinx-datetime; the screens keep
// java.time, which is what the platform pickers, formatters and saved state
// use. These convert a range at that border - the same instants either way.

internal fun ClosedRange<kotlin.time.Instant>.toJavaRange(): ClosedRange<java.time.Instant> =
    start.toJavaInstant()..endInclusive.toJavaInstant()

internal fun ClosedRange<java.time.Instant>.toKotlinRange(): ClosedRange<kotlin.time.Instant> =
    start.toKotlinInstant()..endInclusive.toKotlinInstant()

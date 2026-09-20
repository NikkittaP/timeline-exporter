package io.github.nikkittap.timelineexporter.ui

import java.time.Duration

/**
 * Everything the app knows about its history of asking this user for a tip.
 *
 * Deliberately a plain data class with no Android in it: when to ask is a
 * policy question with a single correct answer per input, and keeping it out
 * of [TipPromptState] is what lets the rules be tested on the JVM instead of
 * verified by hand on a device with a doctored clock.
 *
 * All three fields default to the value SharedPreferences hands back for a key
 * that was never written, so a fresh install needs no migration or seeding.
 */
data class TipPromptRecord(
    /** Exports that produced a file or opened a share sheet, over all time. */
    val successfulExports: Int = 0,
    /** When the user last chose "Not now", or 0 if they never have. */
    val dismissedAtMillis: Long = 0L,
    /** When the user last tipped, or 0 if they never have. */
    val tippedAtMillis: Long = 0L,
)

/**
 * Not on the first export. The first one is the user finding out whether the
 * app works at all; asking then is asking a stranger. The second is someone
 * who came back, which is the earliest point the app has demonstrably been
 * useful more than once.
 */
private const val MIN_SUCCESSFUL_EXPORTS = 2

/** After a "Not now", a month of silence. */
private val DECLINE_COOLDOWN: Duration = Duration.ofDays(30)

/**
 * After a tip, half a year. Long enough that no one who has already paid feels
 * asked again for the same app, short enough that a user still exporting a
 * year later gets the chance to tip a second time if they want to.
 */
private val TIP_COOLDOWN: Duration = Duration.ofDays(180)

/**
 * Whether the tip plaque may take the post-export slot on this export.
 *
 * [nowMillis] is passed in rather than read from the clock so the caller —
 * and the tests — decide what "now" means.
 *
 * Note what a zero timestamp does here: `nowMillis - 0` is the current epoch
 * millis, which exceeds every cooldown, so "never dismissed" and "never
 * tipped" fall out of the arithmetic without a special case. The comparison is
 * also one-sided on purpose. A timestamp in the future means the device clock
 * was set forward and then back; the subtraction goes negative, no cooldown
 * has elapsed, and the prompt stays down. The cost of that reading is one ask
 * the user never sees, which is the right way to be wrong.
 */
fun shouldOfferTip(record: TipPromptRecord, nowMillis: Long): Boolean {
    if (record.successfulExports < MIN_SUCCESSFUL_EXPORTS) return false
    if (nowMillis - record.dismissedAtMillis < DECLINE_COOLDOWN.toMillis()) return false
    if (nowMillis - record.tippedAtMillis < TIP_COOLDOWN.toMillis()) return false
    return true
}

/**
 * Whether the export the user is about to run would earn the ask.
 *
 * Play Billing needs a service connection and a product query before it can
 * name a price, and a plaque that appears with a spinner where three prices
 * belong is worse than no plaque. So the app looks one export ahead: the
 * moment a file is loaded by someone whose next export qualifies, it warms the
 * connection up, and by the time the export finishes the prices are in hand.
 */
fun wouldOfferTipAfterNextExport(record: TipPromptRecord, nowMillis: Long): Boolean =
    shouldOfferTip(record.copy(successfulExports = record.successfulExports + 1), nowMillis)

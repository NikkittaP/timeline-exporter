package io.github.nikkittap.timelineexporter.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration

/**
 * When the app is allowed to ask for a tip.
 *
 * The rules exist to keep one ask from becoming a nag: never on the first
 * export, never twice inside a month after the user said no, and not for
 * half a year after they have already paid. Everything here is expressed in
 * absolute milliseconds so the test does not depend on the day it runs.
 */
class TipPromptPolicyTest {

    /** An arbitrary fixed "now": 2026-09-20T12:00:00Z. */
    private val now = 1_790_337_600_000L

    private fun daysAgo(days: Long): Long = now - Duration.ofDays(days).toMillis()

    @Test
    fun `no ask before the second successful export`() {
        assertFalse(shouldOfferTip(TipPromptRecord(successfulExports = 0), now))
        assertFalse(shouldOfferTip(TipPromptRecord(successfulExports = 1), now))
    }

    @Test
    fun `asks on the second successful export`() {
        assertTrue(shouldOfferTip(TipPromptRecord(successfulExports = 2), now))
    }

    @Test
    fun `keeps asking on later exports while nothing suppresses it`() {
        assertTrue(shouldOfferTip(TipPromptRecord(successfulExports = 17), now))
    }

    @Test
    fun `stays quiet for thirty days after the user declines`() {
        val declined = TipPromptRecord(successfulExports = 5, dismissedAtMillis = daysAgo(29))
        assertFalse(shouldOfferTip(declined, now))
    }

    @Test
    fun `asks again once thirty days have passed since the decline`() {
        val declined = TipPromptRecord(successfulExports = 5, dismissedAtMillis = daysAgo(30))
        assertTrue(shouldOfferTip(declined, now))
    }

    @Test
    fun `stays quiet for half a year after a tip`() {
        val tipped = TipPromptRecord(successfulExports = 40, tippedAtMillis = daysAgo(179))
        assertFalse(shouldOfferTip(tipped, now))
    }

    @Test
    fun `asks again once half a year has passed since the tip`() {
        val tipped = TipPromptRecord(successfulExports = 40, tippedAtMillis = daysAgo(180))
        assertTrue(shouldOfferTip(tipped, now))
    }

    @Test
    fun `a tip outranks an old decline`() {
        // Declined long ago, paid recently: the payment is what should hold
        // the prompt back, not the expired decline.
        val record = TipPromptRecord(
            successfulExports = 9,
            dismissedAtMillis = daysAgo(200),
            tippedAtMillis = daysAgo(10),
        )
        assertFalse(shouldOfferTip(record, now))
    }

    @Test
    fun `a clock moved backwards leaves the prompt suppressed`() {
        // Timestamps in the future can only come from a device clock that has
        // since been set back. Treating the gap as unelapsed is the safe read:
        // the worst case is one ask the user does not get.
        val future = TipPromptRecord(
            successfulExports = 5,
            dismissedAtMillis = now + Duration.ofDays(3).toMillis(),
        )
        assertFalse(shouldOfferTip(future, now))
    }

    @Test
    fun `an untouched record has never been dismissed or tipped`() {
        // Zero is what SharedPreferences returns for a key that was never
        // written, and it must not read as "dismissed at the epoch" in a way
        // that changes the answer.
        assertTrue(shouldOfferTip(TipPromptRecord(successfulExports = 2), now))
    }

    @Test
    fun `the export after the first one is the one that will earn the ask`() {
        // Drives the Play Billing warm-up: prices have to be fetched before
        // the plaque appears, which means deciding one export early.
        assertFalse(wouldOfferTipAfterNextExport(TipPromptRecord(successfulExports = 0), now))
        assertTrue(wouldOfferTipAfterNextExport(TipPromptRecord(successfulExports = 1), now))
    }

    @Test
    fun `no warm-up while a cooldown is still running`() {
        val declined = TipPromptRecord(successfulExports = 5, dismissedAtMillis = daysAgo(3))
        assertFalse(wouldOfferTipAfterNextExport(declined, now))
    }
}

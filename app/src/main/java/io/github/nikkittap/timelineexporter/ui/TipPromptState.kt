package io.github.nikkittap.timelineexporter.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * The persisted half of the tip prompt: how many exports this user has
 * finished, and when they last said no or last paid.
 *
 * Storage is [SharedPreferences], same as the language preference and the
 * place labels — three numbers do not need a database. It has to be persistent
 * rather than per-session because the whole point of the second-export rule is
 * that the two exports are usually days apart: someone who exports today and
 * again next week is exactly who the prompt is for, and a counter living in
 * memory would never reach two.
 *
 * What clears it: uninstalling, or "Clear data" in Android settings. It does
 * not travel to a new phone unless the user has Android Backup on, in which
 * case they simply start over and see the ask on their second export there.
 *
 * All decisions live in [shouldOfferTip]; this class only reads, writes and
 * hands the numbers over.
 */
@Stable
class TipPromptState internal constructor(private val prefs: SharedPreferences) {

    /**
     * Snapshot state, so recording an export or a decline recomposes the
     * plaque that reads it without anything else having to notice.
     */
    var record: TipPromptRecord by mutableStateOf(prefs.readRecord())
        private set

    /** Count an export that produced a file or opened a share sheet. */
    fun recordSuccessfulExport() {
        update(record.copy(successfulExports = record.successfulExports + 1))
    }

    /** The user chose "Not now". Start the quiet month. */
    fun recordDeclined(nowMillis: Long = System.currentTimeMillis()) {
        update(record.copy(dismissedAtMillis = nowMillis))
    }

    /**
     * The user tipped. Start the quiet half-year.
     *
     * Also clears the decline: a payment supersedes an earlier no, and leaving
     * both would only mean the longer of the two cooldowns wins anyway.
     */
    fun recordTipped(nowMillis: Long = System.currentTimeMillis()) {
        update(record.copy(tippedAtMillis = nowMillis, dismissedAtMillis = 0L))
    }

    /**
     * `apply()` rather than `commit()`: this runs on the main thread the frame
     * an export finishes, and three integers are not worth blocking on. A write
     * lost to a process kill in the milliseconds after costs the user one
     * uncounted export.
     */
    private fun update(next: TipPromptRecord) {
        record = next
        prefs.edit()
            .putInt(KEY_EXPORTS, next.successfulExports)
            .putLong(KEY_DISMISSED_AT, next.dismissedAtMillis)
            .putLong(KEY_TIPPED_AT, next.tippedAtMillis)
            .apply()
    }
}

private const val TIP_PROMPT_PREFS = "tip_prompt"
private const val KEY_EXPORTS = "successful_exports"
private const val KEY_DISMISSED_AT = "dismissed_at"
private const val KEY_TIPPED_AT = "tipped_at"

/** Defaults match [TipPromptRecord]'s, so a first run needs no seeding. */
private fun SharedPreferences.readRecord() = TipPromptRecord(
    successfulExports = getInt(KEY_EXPORTS, 0),
    dismissedAtMillis = getLong(KEY_DISMISSED_AT, 0L),
    tippedAtMillis = getLong(KEY_TIPPED_AT, 0L),
)

/**
 * The tip history for this process, read once and kept for the composition.
 *
 * Keyed on the application context so it survives configuration changes, the
 * same way [rememberPlaceLabels] is.
 */
@Composable
fun rememberTipPrompt(): TipPromptState {
    val context = LocalContext.current.applicationContext
    return remember(context) {
        TipPromptState(context.getSharedPreferences(TIP_PROMPT_PREFS, Context.MODE_PRIVATE))
    }
}

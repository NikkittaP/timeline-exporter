package io.github.nikkittap.timelineexporter.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.nikkittap.timelineexporter.BuildConfig
import io.github.nikkittap.timelineexporter.R
import io.github.nikkittap.timelineexporter.parser.TimelineFormat
import java.util.Locale

/** Where feedback goes. Both channels are offered; the user picks. */
private const val FEEDBACK_EMAIL = "nikitapetrovapps@gmail.com"
private const val GITHUB_NEW_ISSUE_URL =
    "https://github.com/NikkittaP/timeline-exporter/issues/new"

/**
 * What the app knows about the file currently loaded, used to pre-fill the
 * diagnostics block. Null fields simply drop out of the text — the dialog is
 * reachable before any file has been parsed.
 */
data class FeedbackContext(
    val format: TimelineFormat? = null,
    val pointCount: Int? = null,
)

/**
 * One checkbox in the feedback ballot.
 *
 * [labelRes] is what the user sees, translated with the app locale.
 * [outgoing] is what gets written into the issue / email — deliberately a
 * hard-coded English string, so a request filed by a Japanese or Turkish user
 * still arrives in a form the maintainer can read and de-duplicate.
 */
private class FeedbackOption(val labelRes: Int, val outgoing: String)

private val FEEDBACK_OPTIONS = listOf(
    FeedbackOption(
        R.string.feedback_option_activity_filter,
        "Filter by movement type (cycling / walking / driving only)",
    ),
    FeedbackOption(
        R.string.feedback_option_file_per_trip,
        "One file per trip instead of a single large file",
    ),
    FeedbackOption(
        R.string.feedback_option_elevation_speed,
        "Elevation and speed in GPX",
    ),
    FeedbackOption(
        R.string.feedback_option_places,
        "Export visited places, not only the track",
    ),
    FeedbackOption(
        R.string.feedback_option_stats,
        "Statistics screen (km per transport type, top places)",
    ),
    FeedbackOption(
        R.string.feedback_option_merge,
        "Merge several Timeline files",
    ),
    FeedbackOption(
        R.string.feedback_option_photo_geotag,
        "Geotag photos by timestamp",
    ),
)

/**
 * Feedback ballot: a list of concrete candidate features plus a free-text
 * field, sent through GitHub Issues or email — whichever the user prefers.
 *
 * Nothing is transmitted by the app itself. Both buttons hand a pre-filled
 * draft to another app (browser / mail client), where the user can still edit
 * or abandon it. The diagnostics block is shown in an editable field for the
 * same reason: the app promises zero telemetry, so the user has to see exactly
 * what they are about to send.
 */
@Composable
fun FeedbackDialog(
    feedbackContext: FeedbackContext,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current

    // Snapshot-backed so ticking a box recomposes; saveable so an orientation
    // change doesn't silently wipe the ballot the user just filled in.
    val selected = rememberSaveable(
        saver = listSaver<SnapshotStateList<Int>, Int>(
            save = { it.toList() },
            restore = { it.toMutableStateList() },
        ),
    ) { mutableStateListOf<Int>() }
    var otherText by rememberSaveable { mutableStateOf("") }

    val defaultDiagnostics = remember(feedbackContext) { buildDiagnostics(feedbackContext) }
    var diagnostics by rememberSaveable(defaultDiagnostics) { mutableStateOf(defaultDiagnostics) }

    // Both send buttons stay disabled until there is something to say —
    // an empty issue helps nobody and costs the user a round trip.
    val hasContent = selected.isNotEmpty() || otherText.isNotBlank()

    val subject = stringResource(R.string.feedback_message_subject)

    fun body(): String = buildBody(
        context = context,
        selected = selected,
        otherText = otherText,
        diagnostics = diagnostics,
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.feedback_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(R.string.feedback_intro),
                    style = MaterialTheme.typography.bodySmall,
                )

                FEEDBACK_OPTIONS.forEachIndexed { index, option ->
                    val isChecked = index in selected
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Checkbox(
                            checked = isChecked,
                            onCheckedChange = { checked ->
                                if (checked) selected.add(index) else selected.remove(index)
                            },
                        )
                        Text(
                            text = stringResource(option.labelRes),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                OutlinedTextField(
                    value = otherText,
                    onValueChange = { otherText = it },
                    label = { Text(stringResource(R.string.feedback_other_label)) },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                Text(
                    stringResource(R.string.feedback_diagnostics_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = diagnostics,
                    onValueChange = { diagnostics = it },
                    label = { Text(stringResource(R.string.feedback_diagnostics_label)) },
                    textStyle = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth(),
                )

                // Email first on purpose: it works for everyone, while filing
                // a GitHub issue requires an account and bounces anyone
                // without one through a signup wall.
                FilledTonalButton(
                    onClick = { sendFeedbackEmail(context, subject, body()) },
                    enabled = hasContent,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.feedback_send_email))
                }
                FilledTonalButton(
                    onClick = { openGitHubIssue(context, subject, body()) },
                    enabled = hasContent,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.feedback_send_github))
                }
                Text(
                    stringResource(R.string.feedback_channel_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.feedback_dismiss)) }
        },
    )
}

// ---------- message assembly ----------

/**
 * The diagnostics line-up. English and machine-ish on purpose: it travels to
 * an issue tracker, not to the user. Values that aren't known yet (no file
 * loaded) are simply omitted.
 */
private fun buildDiagnostics(ctx: FeedbackContext): String = buildString {
    append("App: ").append(BuildConfig.VERSION_NAME)
        .append(" (").append(BuildConfig.VERSION_CODE).append(")\n")
    append("Device: ").append(Build.MODEL)
        .append(" · Android ").append(Build.VERSION.RELEASE)
        .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
    append("Locale: ").append(Locale.getDefault().toLanguageTag())
    if (ctx.format != null) {
        append("\nFile format: ").append(ctx.format.name)
    }
    if (ctx.pointCount != null) {
        append("\nPoints: ").append(ctx.pointCount)
    }
}

private fun buildBody(
    context: Context,
    selected: List<Int>,
    otherText: String,
    diagnostics: String,
): String = buildString {
    val chosen = FEEDBACK_OPTIONS.filterIndexed { index, _ -> index in selected }
    if (chosen.isNotEmpty()) {
        append(context.getString(R.string.feedback_body_requested)).append('\n')
        chosen.forEach { append("- ").append(it.outgoing).append('\n') }
    }
    if (otherText.isNotBlank()) {
        if (isNotEmpty()) append('\n')
        append(context.getString(R.string.feedback_body_notes)).append('\n')
        append(otherText.trim()).append('\n')
    }
    if (diagnostics.isNotBlank()) {
        if (isNotEmpty()) append('\n')
        append("---\n").append(diagnostics.trim()).append('\n')
    }
}

// ---------- outgoing intents ----------

/**
 * Opens GitHub's "new issue" form with title and body pre-filled through query
 * parameters. Uri.Builder percent-encodes both, so newlines and non-Latin text
 * survive the trip.
 */
private fun openGitHubIssue(context: Context, title: String, body: String) {
    val uri = Uri.parse(GITHUB_NEW_ISSUE_URL).buildUpon()
        .appendQueryParameter("title", title)
        .appendQueryParameter("body", body)
        .build()
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(
            context,
            context.getString(R.string.feedback_toast_no_browser),
            Toast.LENGTH_LONG,
        ).show()
    }
}

/**
 * ACTION_SENDTO with a mailto: URI, so only mail apps are offered (unlike
 * ACTION_SEND, which pulls in every share target on the device).
 *
 * Subject and body are carried **in the URI**, not only in EXTRA_SUBJECT /
 * EXTRA_TEXT. Several mail clients (Gmail among them) compose the draft from
 * the mailto URI alone and drop the extras — which is exactly what internal
 * testing 8 showed: the recipient arrived (it comes from the URI) while the
 * subject and body were empty. The extras stay on as a fallback for clients
 * that read them and ignore the query string; both paths carry identical text,
 * so whichever one the client honours, the draft comes out the same.
 *
 * `Uri.encode` rather than `URLEncoder`: the former percent-encodes a space as
 * `%20`, the latter as `+`, and a mail client pastes that `+` into the draft
 * literally. Newlines become `%0A`, which is what keeps the diagnostics block
 * on its own lines.
 *
 * `mailto:` is an opaque URI, so `buildUpon().appendQueryParameter()` — the
 * approach used for the GitHub URL above — silently produces the wrong thing
 * here; the query has to be assembled by hand.
 *
 * EXTRA_EMAIL is gone: with ACTION_SENDTO the recipient is defined by the URI,
 * and a client that merges both ends up with the address twice in To:.
 */
private fun sendFeedbackEmail(context: Context, subject: String, body: String) {
    val mailto = buildString {
        // "@" is whitelisted: RFC 6068 wants a literal one in the address, and
        // the recipient is the one part that already worked — encoding it to
        // %40 would be a fine way to break it while fixing something else.
        append("mailto:").append(Uri.encode(FEEDBACK_EMAIL, "@"))
        append("?subject=").append(Uri.encode(subject))
        append("&body=").append(Uri.encode(body))
    }
    val intent = Intent(Intent.ACTION_SENDTO, Uri.parse(mailto)).apply {
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, body)
    }
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(
            context,
            context.getString(R.string.feedback_toast_no_email),
            Toast.LENGTH_LONG,
        ).show()
    }
}

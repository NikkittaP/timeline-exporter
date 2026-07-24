package io.github.nikkittap.timelineexporter.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.nikkittap.timelineexporter.R

private const val MAPS_PACKAGE = "com.google.android.apps.maps"

/**
 * Onboarding help shown from the TopAppBar action and from the Idle hint.
 *
 * All user-facing strings are sourced from res/values/strings.xml so the
 * dialog translates with the device locale.
 */
@Composable
fun HelpDialog(
    onDismiss: () -> Unit,
    onFeedbackClick: () -> Unit = {},
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.help_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(R.string.help_intro),
                    style = MaterialTheme.typography.bodySmall,
                )

                Text(
                    stringResource(R.string.help_method_settings_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                Step("1.", stringResource(R.string.help_method_settings_step1))
                Step("2.", stringResource(R.string.help_method_settings_step2))
                Step("3.", stringResource(R.string.help_method_settings_step3))
                Step("4.", stringResource(R.string.help_method_settings_step4))
                Step("5.", stringResource(R.string.help_method_settings_step5))
                Step("6.", stringResource(R.string.help_method_settings_step6))
                FilledTonalButton(
                    onClick = { openLocationSettings(context) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.help_method_settings_button))
                }

                Text(
                    stringResource(R.string.help_method_maps_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                Step("1.", stringResource(R.string.help_method_maps_step1))
                Step("2.", stringResource(R.string.help_method_maps_step2))
                Step("3.", stringResource(R.string.help_method_maps_step3))
                Step("4.", stringResource(R.string.help_method_maps_step4))
                Step("5.", stringResource(R.string.help_method_maps_step5))
                Step("6.", stringResource(R.string.help_method_maps_step6))
                FilledTonalButton(
                    onClick = { openGoogleMaps(context) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.help_method_maps_button))
                }

                Text(
                    stringResource(R.string.help_import_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                Step("•", stringResource(R.string.help_import_step1))
                Step("•", stringResource(R.string.help_import_step2))

                Text(
                    stringResource(R.string.help_notes_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    stringResource(R.string.help_note_file_location),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(R.string.help_note_eu_csv),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(R.string.help_note_slow_first),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(R.string.help_note_csv_local_time),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // Always-available second entry point to the feedback ballot —
                // permanent, but out of the way.
                TextButton(
                    onClick = onFeedbackClick,
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Text(stringResource(R.string.feedback_help_button))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.help_dismiss)) }
        }
    )
}

@Composable
private fun Step(marker: String, text: String) {
    androidx.compose.foundation.layout.Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(marker, style = MaterialTheme.typography.bodySmall)
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

private fun openLocationSettings(context: Context) {
    try {
        context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(
            context,
            context.getString(R.string.help_toast_no_location_settings),
            Toast.LENGTH_LONG,
        ).show()
    }
}

private fun openGoogleMaps(context: Context) {
    val launchIntent = context.packageManager.getLaunchIntentForPackage(MAPS_PACKAGE)
    if (launchIntent == null) {
        Toast.makeText(
            context,
            context.getString(R.string.help_toast_no_maps_installed),
            Toast.LENGTH_LONG,
        ).show()
        return
    }
    try {
        context.startActivity(launchIntent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(
            context,
            context.getString(R.string.help_toast_no_maps_launch),
            Toast.LENGTH_LONG,
        ).show()
    }
}

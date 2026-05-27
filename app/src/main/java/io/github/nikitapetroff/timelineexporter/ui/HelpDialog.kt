package io.github.nikitapetroff.timelineexporter.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.unit.dp

private const val MAPS_PACKAGE = "com.google.android.apps.maps"

/**
 * Onboarding help shown from the TopAppBar action and from the Idle hint.
 *
 * Two shortcut buttons jump directly to:
 *  - the system Location settings screen (Settings.ACTION_LOCATION_SOURCE_SETTINGS),
 *    landing one tap away from the Timeline sub-screen.
 *  - the Google Maps app's main screen, from where the user navigates to
 *    Your Timeline.
 *
 * Neither shortcut can go deeper — Google doesn't expose intent actions for
 * the Timeline sub-screen inside Location services, nor for "Your Timeline"
 * inside Maps.
 */
@Composable
fun HelpDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Getting your Timeline data") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Since late 2024 Google stores Timeline only on your device — " +
                            "there's no Google Takeout option and no third-party app " +
                            "can read it directly. You export it once from system " +
                            "Settings, then bring the file here.",
                    style = MaterialTheme.typography.bodySmall,
                )

                Text(
                    "Export from Android Settings (recommended)",
                    style = MaterialTheme.typography.titleSmall,
                )
                Step("1.", "Open the Android Settings app")
                Step("2.", "Tap Location → Location services")
                Step("3.", "Tap Timeline")
                Step("4.", "Tap ‘Export Timeline data’")
                Step("5.", "Authenticate when prompted")
                Step("6.", "Choose where to save the file")
                FilledTonalButton(
                    onClick = { openLocationSettings(context) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Open Location settings →")
                }

                Text(
                    "Alternative: export from Google Maps",
                    style = MaterialTheme.typography.titleSmall,
                )
                Step("1.", "Open Google Maps")
                Step("2.", "Tap your profile picture (top right)")
                Step("3.", "Choose ‘Your Timeline’")
                Step("4.", "Tap the ⋯ menu (top right)")
                Step("5.", "Tap ‘Location and privacy settings’")
                Step("6.", "Tap ‘Export Timeline data’")
                FilledTonalButton(
                    onClick = { openGoogleMaps(context) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Open Google Maps →")
                }

                Text(
                    "Bring the file into this app",
                    style = MaterialTheme.typography.titleSmall,
                )
                Step("•", "Open your Files app, find Timeline.json, tap Share → ‘Timeline Exporter’, or")
                Step("•", "Tap ‘Pick Timeline.json’ above and browse to the file.")

                Text(
                    "Notes",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    "• The exported file is named Timeline.json. It lands wherever " +
                            "you chose during export — some Android versions restrict " +
                            "access to certain folders (e.g. Downloads), so if this app " +
                            "can't read it, try moving it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "• EU users may receive a CSV instead of JSON for privacy-law " +
                            "reasons. This version of the app only handles JSON.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "• The first export can take a minute or two if you have years " +
                            "of location history.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Got it") }
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

/**
 * Launch the system Location settings screen. The Timeline sub-screen is
 * one tap deeper — Android doesn't expose an action constant for it.
 */
private fun openLocationSettings(context: Context) {
    try {
        context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(
            context,
            "Could not open Location settings on this device.",
            Toast.LENGTH_LONG,
        ).show()
    }
}

/**
 * Launch the Google Maps app to its main screen. Returns null + shows a
 * toast if Maps is not installed (handle Play-less devices gracefully).
 */
private fun openGoogleMaps(context: Context) {
    val launchIntent = context.packageManager.getLaunchIntentForPackage(MAPS_PACKAGE)
    if (launchIntent == null) {
        Toast.makeText(
            context,
            "Google Maps is not installed on this device.",
            Toast.LENGTH_LONG,
        ).show()
        return
    }
    try {
        context.startActivity(launchIntent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "Could not open Google Maps.", Toast.LENGTH_LONG).show()
    }
}

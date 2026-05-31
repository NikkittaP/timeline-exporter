package io.github.nikkittap.timelineexporter.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.nikkittap.timelineexporter.R

@Composable
fun TipJarDialog(
    onDismiss: () -> Unit,
    viewModel: TipJarViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // LocalContext is the hosting Activity (ComponentActivity from MainActivity).
    // Required by Play Billing's launchBillingFlow.
    val activity = LocalContext.current as ComponentActivity

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tipjar_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(R.string.tipjar_blurb),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.size(4.dp))
                when (val s = state) {
                    TipJarState.Connecting -> ConnectingRow()
                    is TipJarState.Loaded -> ProductButtons(
                        products = s.products,
                        onPick = { id -> viewModel.launchPurchase(activity, id) },
                    )
                    TipJarState.Processing -> ConnectingRow(
                        label = stringResource(R.string.tipjar_processing),
                    )
                    TipJarState.ThankYou -> ThankYouBlock(
                        onRetry = { viewModel.resetToLoaded() },
                    )
                    TipJarState.Unavailable -> Text(
                        text = stringResource(R.string.tipjar_unavailable),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    is TipJarState.Error -> ErrorBlock(
                        technicalMessage = s.technicalMessage,
                        onRetry = { viewModel.connect() },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.tipjar_close))
            }
        }
    )
}

@Composable
private fun ConnectingRow(label: String = stringResource(R.string.tipjar_connecting)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.size(12.dp))
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ProductButtons(products: List<TipProduct>, onPick: (String) -> Unit) {
    if (products.isEmpty()) {
        Text(
            stringResource(R.string.tipjar_no_products),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        products.forEach { product ->
            Button(
                onClick = { onPick(product.id) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("${product.name} — ${product.formattedPrice}")
            }
        }
    }
}

@Composable
private fun ThankYouBlock(onRetry: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.tipjar_thank_you),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            stringResource(R.string.tipjar_thank_you_detail),
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.tipjar_tip_again))
        }
    }
}

@Composable
private fun ErrorBlock(technicalMessage: String?, onRetry: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.tipjar_error),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        if (technicalMessage != null) {
            Text(
                text = technicalMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedButton(
            onClick = onRetry,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        ) {
            Text(stringResource(R.string.tipjar_retry))
        }
    }
}

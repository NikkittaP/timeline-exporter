package io.github.nikitapetroff.timelineexporter.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.nikitapetroff.timelineexporter.parser.ParsedTimeline

@Composable
fun MainScreen(
    modifier: Modifier = Modifier,
    viewModel: TimelineViewModel = viewModel(),
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // OpenDocument launches the system file picker and gives us back a URI.
    // The URI grants us one-shot read access; no permission required.
    val pickFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let { viewModel.onFileSelected(it, context.contentResolver) }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Button(onClick = { pickFileLauncher.launch(arrayOf("*/*")) }) {
            Text("Pick Timeline.json")
        }

        // Exhaustive when over our sealed UI state.
        when (val state = uiState) {
            TimelineUiState.Idle -> Text(
                "Pick a Google Maps Timeline JSON file to parse.",
                style = MaterialTheme.typography.bodyMedium,
            )

            TimelineUiState.Loading -> LoadingRow()

            is TimelineUiState.Loaded -> ResultDisplay(state.result)

            is TimelineUiState.Error -> Text(
                text = state.message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun LoadingRow() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(12.dp))
        Text("Parsing…")
    }
}

@Composable
private fun ResultDisplay(result: ParsedTimeline) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "Parsed successfully",
            style = MaterialTheme.typography.titleMedium,
        )
        Text("Total segments: ${result.totalSegments}")
        Text("  • Path: ${result.pathSegments}")
        Text("  • Visit: ${result.visitSegments}")
        Text("  • Activity: ${result.activitySegments}")
        Text("Path points: ${result.pathPoints.size}")
        if (result.pathPoints.isNotEmpty()) {
            val first = result.pathPoints.first()
            val last = result.pathPoints.last()
            Spacer(modifier = Modifier.size(8.dp))
            Text(
                text = "First point",
                style = MaterialTheme.typography.titleSmall,
            )
            Text("${first.timeUtc}")
            Text("lat=${first.latitude}, lon=${first.longitude}")
            Spacer(modifier = Modifier.size(8.dp))
            Text(
                text = "Last point",
                style = MaterialTheme.typography.titleSmall,
            )
            Text("${last.timeUtc}")
            Text("lat=${last.latitude}, lon=${last.longitude}")
        }
    }
}

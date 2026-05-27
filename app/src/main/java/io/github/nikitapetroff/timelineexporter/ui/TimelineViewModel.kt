package io.github.nikitapetroff.timelineexporter.ui

import android.content.ContentResolver
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.nikitapetroff.timelineexporter.parser.ParsedTimeline
import io.github.nikitapetroff.timelineexporter.parser.ParserStage
import io.github.nikitapetroff.timelineexporter.parser.parseTimeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Discriminated union of every state the screen can be in.
 */
sealed interface TimelineUiState {
    data object Idle : TimelineUiState

    /**
     * In-progress. [progress] is null when we can't measure the stage
     * (renders as an indeterminate bar) or a 0..1 fraction otherwise.
     */
    data class Loading(
        val stageLabel: String,
        val detailLabel: String? = null,
        val progress: Float? = null,
    ) : TimelineUiState

    data class Loaded(val result: ParsedTimeline) : TimelineUiState

    data class Error(val message: String) : TimelineUiState
}

class TimelineViewModel : ViewModel() {

    private val _uiState = MutableStateFlow<TimelineUiState>(TimelineUiState.Idle)
    val uiState: StateFlow<TimelineUiState> = _uiState.asStateFlow()

    fun onFileSelected(uri: Uri, contentResolver: ContentResolver) {
        _uiState.value = TimelineUiState.Loading(stageLabel = "Opening file…")

        viewModelScope.launch {
            val newState = withContext(Dispatchers.IO) {
                try {
                    val totalBytes = queryFileSize(uri, contentResolver)

                    val json = readUriAsString(uri, contentResolver) { bytesRead ->
                        _uiState.value = TimelineUiState.Loading(
                            stageLabel = "Reading file",
                            detailLabel = formatBytesProgress(bytesRead, totalBytes),
                            progress = totalBytes
                                ?.takeIf { it > 0 }
                                ?.let { bytesRead.toFloat() / it },
                        )
                    }

                    val result = parseTimeline(json) { stage ->
                        _uiState.value = stage.toLoadingState()
                    }
                    TimelineUiState.Loaded(result)
                } catch (e: Exception) {
                    TimelineUiState.Error(
                        "Could not parse this file as a Google Timeline export.\n" +
                                "${e::class.simpleName}: ${e.message ?: "(no message)"}"
                    )
                }
            }
            _uiState.value = newState
        }
    }
}

// ---------- private helpers ----------

private fun ParserStage.toLoadingState(): TimelineUiState.Loading = when (this) {
    ParserStage.DecodingJson -> TimelineUiState.Loading(
        stageLabel = "Decoding JSON",
        detailLabel = "Large files can take a few seconds…",
        progress = null,
    )
    is ParserStage.ExtractingSegments -> TimelineUiState.Loading(
        stageLabel = "Extracting GPS points",
        detailLabel = "${formatGrouped(done)} / ${formatGrouped(total)} segments",
        progress = if (total > 0) done.toFloat() / total else null,
    )
    ParserStage.Sorting -> TimelineUiState.Loading(
        stageLabel = "Sorting points by time",
        detailLabel = null,
        progress = null,
    )
}

/**
 * Ask the content provider for the file's size in bytes. Not all providers
 * answer (returns null) — we degrade to indeterminate progress in that case.
 */
private fun queryFileSize(uri: Uri, resolver: ContentResolver): Long? = try {
    resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
        if (idx >= 0 && cursor.moveToFirst() && !cursor.isNull(idx)) cursor.getLong(idx) else null
    }
} catch (_: Exception) {
    null
}

/**
 * Read the URI's bytes to a String in 64 KB chunks, reporting progress no
 * more than once every 50 ms. Throttling matters: a 60 MB read produces
 * ~1000 chunks; without throttling we'd hammer the StateFlow.
 */
private fun readUriAsString(
    uri: Uri,
    resolver: ContentResolver,
    onProgress: (bytesRead: Long) -> Unit,
): String {
    val stream = resolver.openInputStream(uri)
        ?: throw IOException("Could not open file: $uri")
    return stream.use { input ->
        val buffer = ByteArray(64 * 1024)
        val out = ByteArrayOutputStream()
        var totalRead = 0L
        var lastReportMs = 0L
        while (true) {
            val n = input.read(buffer)
            if (n == -1) break
            out.write(buffer, 0, n)
            totalRead += n
            val now = SystemClock.uptimeMillis()
            if (now - lastReportMs >= 50L) {
                onProgress(totalRead)
                lastReportMs = now
            }
        }
        onProgress(totalRead) // final 100% tick
        out.toString(Charsets.UTF_8)
    }
}

private fun formatBytesProgress(bytesRead: Long, totalBytes: Long?): String =
    if (totalBytes != null) "${formatMb(bytesRead)} / ${formatMb(totalBytes)}"
    else formatMb(bytesRead)

private fun formatMb(bytes: Long): String = "%.1f MB".format(bytes / 1_048_576.0)

private fun formatGrouped(n: Int): String = "%,d".format(n)

package io.github.nikitapetroff.timelineexporter.ui

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.nikitapetroff.timelineexporter.parser.ParsedTimeline
import io.github.nikitapetroff.timelineexporter.parser.parseTimeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Discriminated union of every state the screen can be in.
 * `sealed interface` = the compiler knows the full set of subclasses, so
 * `when (state) { ... }` is checked to be exhaustive.
 */
sealed interface TimelineUiState {
    /** Initial state — no file picked yet. */
    data object Idle : TimelineUiState

    /** A parse is running. */
    data object Loading : TimelineUiState

    /** Parse finished successfully. */
    data class Loaded(val result: ParsedTimeline) : TimelineUiState

    /** Parse failed. Message is shown to the user. */
    data class Error(val message: String) : TimelineUiState
}

class TimelineViewModel : ViewModel() {

    // Private mutable state holder, exposed read-only to UI as a plain StateFlow.
    private val _uiState = MutableStateFlow<TimelineUiState>(TimelineUiState.Idle)
    val uiState: StateFlow<TimelineUiState> = _uiState.asStateFlow()

    /**
     * Called by the UI when the user has picked a file in the system file picker.
     * Reads the file's bytes and parses them off the main thread; updates uiState
     * as it goes. Safe to call repeatedly — each call replaces any previous result.
     */
    fun onFileSelected(uri: Uri, contentResolver: ContentResolver) {
        _uiState.value = TimelineUiState.Loading

        // viewModelScope is a CoroutineScope tied to this ViewModel's lifetime —
        // any in-flight work is automatically cancelled if the VM is destroyed.
        viewModelScope.launch {
            val newState = withContext(Dispatchers.IO) {
                try {
                    val json = contentResolver.openInputStream(uri)?.use { stream ->
                        stream.bufferedReader().readText()
                    } ?: throw IOException("Could not open file: $uri")

                    val result = parseTimeline(json)
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

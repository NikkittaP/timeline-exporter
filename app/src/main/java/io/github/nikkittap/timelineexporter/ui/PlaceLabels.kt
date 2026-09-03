package io.github.nikkittap.timelineexporter.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * The user's own names for places, keyed by [PlaceEntry.key].
 *
 * Why this exists: a phone takeout identifies places but does not name them.
 * Measured on the reference export, 271 of 282 distinct places carry
 * `semanticType` UNKNOWN and no name anywhere in the file, so a Places list
 * built from Google's data alone is mostly rows of coordinates. Resolving them
 * would mean a reverse-geocoding request per place — sending the user's
 * location history to a third party is exactly what this app exists to avoid —
 * so instead the user names the handful they care about, once, and the app
 * remembers. The 55% of visits concentrated in the top eight places means a
 * few minutes of typing labels most of what anyone actually sees.
 *
 * Storage is [SharedPreferences], one entry per place, same as the language
 * preference. Not a database: the realistic upper bound is a few dozen short
 * strings, and Room would add a schema, a migration story and a coroutine
 * boundary to store what fits in a single XML file.
 *
 * Deliberately in the UI layer rather than the ViewModel. Reading it needs a
 * `Context`, and the ViewModel is kept free of Android environment for the
 * same reason it takes a `File` instead of one when writing a share — the UI
 * is already where every Context-dependent concern in this app lives.
 *
 * Labels are keyed on the Google place ID, so they survive re-importing a
 * newer export of the same Timeline: the file changes, the place IDs do not.
 */
@Stable
class PlaceLabelState internal constructor(private val prefs: SharedPreferences) {

    // A snapshot map, so a rename recomposes the row that shows it without any
    // reload of the file or re-aggregation of the places.
    private val labels = mutableStateMapOf<String, String>()

    init {
        for ((k, v) in prefs.all) {
            if (v is String && v.isNotBlank()) labels[k] = v
        }
    }

    /** The user's name for this place, or null if they haven't given it one. */
    operator fun get(key: String): String? = labels[key]

    /**
     * Name a place, or forget the name when [label] is null or blank.
     *
     * `apply()` rather than `commit()`: this is called from a dialog's confirm
     * button on the main thread, and a few bytes of XML are not worth blocking
     * a frame for. A write lost to a process kill in the milliseconds after
     * costs the user one retyped label.
     */
    fun set(key: String, label: String?) {
        val trimmed = label?.trim()
        if (trimmed.isNullOrEmpty()) {
            labels.remove(key)
            prefs.edit().remove(key).apply()
        } else {
            labels[key] = trimmed
            prefs.edit().putString(key, trimmed).apply()
        }
    }
}

private const val PLACE_LABEL_PREFS = "place_labels"

/**
 * The label store for this process, loaded once and kept for the composition.
 *
 * Keyed on the application context so it survives configuration changes; the
 * underlying SharedPreferences instance is itself a per-process singleton, so
 * two of these would in any case see the same data.
 */
@Composable
fun rememberPlaceLabels(): PlaceLabelState {
    val context = LocalContext.current.applicationContext
    return remember(context) {
        PlaceLabelState(
            context.getSharedPreferences(PLACE_LABEL_PREFS, Context.MODE_PRIVATE)
        )
    }
}

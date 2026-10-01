package io.github.nikkittap.timelineexporter.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.nikkittap.timelineexporter.filter.TimelineFilter
import io.github.nikkittap.timelineexporter.parser.MovementGroup
import io.github.nikkittap.timelineexporter.parser.TimeOfDayWindow
import java.time.Instant

/**
 * The choices that carry over from one export to the next: the filters in
 * step 2, the containers switch under Places, Save / Send in step 4 and the
 * map's Color by day.
 *
 * Asked for on the feedback ballot ("Remember my filters and format for the
 * next export", FB-016, FB-019). Before this every file opened on a blank
 * filter, so someone exporting their walks every month re-ticked the same
 * movement groups every month. The format itself is not remembered: each
 * format button saves straight away, so there is no selected format to keep.
 *
 * The Track / Places switch is not here on purpose either. A new file opens
 * on its track, the way step 3 has always opened, rather than on a places
 * list the user has not seen yet.
 */
data class RememberedSettings(
    val dateRange: ClosedRange<Instant>? = null,
    val movements: Set<MovementGroup>? = null,
    val movingOnly: Boolean = false,
    val dropRepeatedPoints: Boolean = false,
    val timeOfDay: TimeOfDayWindow? = null,
    val includeContainers: Boolean = false,
    val shareInstead: Boolean = false,
    val colorByDay: Boolean = false,
) {
    /**
     * The filter to open a file with whose points span [dataFirst]..[dataLast].
     *
     * The date range comes back only if it overlaps that span. The common case
     * is a fresh export of the same Timeline, which still covers the remembered
     * dates. A range that misses the file entirely would open it on zero
     * points, which reads as a broken file rather than as yesterday's filter,
     * so it is dropped. Null bounds mean the file has no points, and then
     * there is nothing for a range to overlap.
     *
     * An empty movement set is not restored as one. It matches nothing, and
     * the only way to save it was to untick every group, at which point export
     * was already disabled; opening the next file that way would help no one.
     */
    fun filterFor(dataFirst: Instant?, dataLast: Instant?): TimelineFilter {
        val range = dateRange?.takeIf {
            dataFirst != null && dataLast != null &&
                it.start <= dataLast && it.endInclusive >= dataFirst
        }
        return TimelineFilter(
            dateRange = range,
            movements = movements?.takeIf { it.isNotEmpty() && it != MovementGroup.entries.toSet() },
            movingOnly = movingOnly,
            dropRepeatedPoints = dropRepeatedPoints,
            timeOfDay = timeOfDay?.takeIf { !it.isWholeDay },
        )
    }

    /** These settings with the filter part replaced by [filter]. */
    fun withFilter(filter: TimelineFilter): RememberedSettings = copy(
        dateRange = filter.dateRange,
        movements = filter.movements,
        movingOnly = filter.movingOnly,
        dropRepeatedPoints = filter.dropRepeatedPoints,
        timeOfDay = filter.timeOfDay,
    )
}

/**
 * Where [RememberedSettings] live between sessions.
 *
 * An interface so the ViewModel can be built without an Android Context, the
 * same reason it takes a `File` rather than a Context for shares.
 */
interface SettingsStore {
    fun load(): RememberedSettings
    fun save(settings: RememberedSettings)

    /** Remembers for the life of the object only. The ViewModel's default. */
    class InMemory(private var settings: RememberedSettings = RememberedSettings()) : SettingsStore {
        override fun load() = settings
        override fun save(settings: RememberedSettings) {
            this.settings = settings
        }
    }
}

/**
 * [SettingsStore] in SharedPreferences, next to the language and the place
 * labels and for the same reason: a handful of values, read once at start.
 *
 * Every field has its own key, and a missing key is the field's default. A
 * key that will not parse, such as a movement group a later version renamed,
 * is skipped rather than failing the load: losing one remembered choice is
 * better than losing all of them.
 */
class PrefsSettingsStore(private val prefs: SharedPreferences) : SettingsStore {

    override fun load(): RememberedSettings {
        val from = prefs.getString(KEY_RANGE_FROM, null)?.let(::parseInstant)
        val to = prefs.getString(KEY_RANGE_TO, null)?.let(::parseInstant)
        val window = if (prefs.contains(KEY_TIME_FROM) && prefs.contains(KEY_TIME_TO)) {
            runCatching {
                TimeOfDayWindow(prefs.getInt(KEY_TIME_FROM, 0), prefs.getInt(KEY_TIME_TO, 0))
            }.getOrNull()
        } else {
            null
        }
        return RememberedSettings(
            dateRange = if (from != null && to != null && from <= to) from..to else null,
            movements = prefs.getStringSet(KEY_MOVEMENTS, null)
                ?.mapNotNull { name -> MovementGroup.entries.firstOrNull { it.name == name } }
                ?.toSet(),
            movingOnly = prefs.getBoolean(KEY_MOVING_ONLY, false),
            dropRepeatedPoints = prefs.getBoolean(KEY_DROP_REPEATED, false),
            timeOfDay = window,
            includeContainers = prefs.getBoolean(KEY_CONTAINERS, false),
            shareInstead = prefs.getBoolean(KEY_SHARE, false),
            colorByDay = prefs.getBoolean(KEY_COLOR_BY_DAY, false),
        )
    }

    /** `apply()`: a few bytes of XML are not worth blocking a frame for. */
    override fun save(settings: RememberedSettings) {
        prefs.edit().apply {
            val range = settings.dateRange
            if (range == null) {
                remove(KEY_RANGE_FROM)
                remove(KEY_RANGE_TO)
            } else {
                putString(KEY_RANGE_FROM, range.start.toString())
                putString(KEY_RANGE_TO, range.endInclusive.toString())
            }
            val movements = settings.movements
            if (movements == null) remove(KEY_MOVEMENTS) else putStringSet(KEY_MOVEMENTS, movements.map { it.name }.toSet())
            val window = settings.timeOfDay
            if (window == null) {
                remove(KEY_TIME_FROM)
                remove(KEY_TIME_TO)
            } else {
                putInt(KEY_TIME_FROM, window.startMinute)
                putInt(KEY_TIME_TO, window.endMinute)
            }
            putBoolean(KEY_MOVING_ONLY, settings.movingOnly)
            putBoolean(KEY_DROP_REPEATED, settings.dropRepeatedPoints)
            putBoolean(KEY_CONTAINERS, settings.includeContainers)
            putBoolean(KEY_SHARE, settings.shareInstead)
            putBoolean(KEY_COLOR_BY_DAY, settings.colorByDay)
        }.apply()
    }

    private fun parseInstant(s: String): Instant? = runCatching { Instant.parse(s) }.getOrNull()

    private companion object {
        const val KEY_RANGE_FROM = "range_from"
        const val KEY_RANGE_TO = "range_to"
        const val KEY_MOVEMENTS = "movements"
        const val KEY_MOVING_ONLY = "moving_only"
        const val KEY_DROP_REPEATED = "drop_repeated"
        const val KEY_TIME_FROM = "time_from"
        const val KEY_TIME_TO = "time_to"
        const val KEY_CONTAINERS = "include_containers"
        const val KEY_SHARE = "share_instead"
        const val KEY_COLOR_BY_DAY = "color_by_day"
    }
}

private const val SETTINGS_PREFS = "remembered_settings"

/**
 * Builds [TimelineViewModel] with the SharedPreferences store.
 *
 * Both MainActivity's `by viewModels` and the composables' `viewModel()` must
 * pass this. Whichever asks first creates the activity's one instance, and if
 * that were the default factory the app would silently remember nothing.
 */
val TimelineViewModelFactory: ViewModelProvider.Factory = viewModelFactory {
    initializer {
        val app = checkNotNull(this[APPLICATION_KEY])
        TimelineViewModel(PrefsSettingsStore(app.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)))
    }
}

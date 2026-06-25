package io.github.nikkittap.timelineexporter

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * Lightweight in-app language override.
 *
 * Stores the user's chosen BCP-47 language tag in SharedPreferences and applies
 * it by wrapping the Activity's base context with a localized [Configuration].
 * An empty tag means "follow the system language". This works on every
 * supported API level (no AppCompat dependency required) and coexists with the
 * Android 13+ per-app language picker: when the user keeps "system", the
 * platform setting still wins.
 */
object LocaleManager {
    private const val PREFS = "settings"
    private const val KEY_LANG = "app_language"

    /** The chosen language tag, or "" to follow the system. */
    fun getPersistedTag(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LANG, "") ?: ""

    fun setLanguage(context: Context, tag: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LANG, tag)
            .apply()
    }

    /**
     * Wrap [base] so its resources resolve in the chosen language. Returns
     * [base] unchanged when "follow system" is selected. Call from
     * Activity.attachBaseContext.
     */
    fun wrap(base: Context): Context {
        val tag = getPersistedTag(base)
        if (tag.isEmpty()) return base
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return base.createConfigurationContext(config)
    }
}

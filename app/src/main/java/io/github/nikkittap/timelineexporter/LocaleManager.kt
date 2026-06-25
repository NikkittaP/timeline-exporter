package io.github.nikkittap.timelineexporter

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * In-app language override that works on every supported API level.
 *
 * Two mechanisms, picked by OS version:
 *
 *  - **API 33+ (Android 13+):** the platform owns per-app locales. We set the
 *    choice through [android.app.LocaleManager.setApplicationLocales]; the
 *    system persists it, resolves resources, and recreates the activity itself.
 *    A manual [Configuration] override in `attachBaseContext` does NOT survive
 *    here — the system re-applies the stored per-app locale after
 *    `attachBaseContext`, reverting it (very visible on OEM skins like Samsung
 *    One UI, less so on stock emulator images). The declared
 *    `@xml/locales_config` is what lets this list show in Settings too.
 *
 *  - **API 29–32:** no per-app locale service, so we persist the BCP-47 tag in
 *    SharedPreferences and apply it by wrapping the Activity base context.
 *
 * In both cases the chosen tag is mirrored into SharedPreferences so the
 * language picker can show the current selection deterministically. An empty
 * tag means "follow the system language".
 */
object LocaleManager {
    private const val PREFS = "settings"
    private const val KEY_LANG = "app_language"

    private fun isPlatformLocaleApi() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /** The chosen language tag, or "" to follow the system. */
    fun getPersistedTag(context: Context): String {
        if (isPlatformLocaleApi()) {
            val lm = context.getSystemService(android.app.LocaleManager::class.java)
            val locales = lm?.applicationLocales
            if (locales != null && !locales.isEmpty) return locales[0].toLanguageTag()
            return ""
        }
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LANG, "") ?: ""
    }

    fun setLanguage(context: Context, tag: String) {
        // Mirror into prefs regardless so the picker's current-selection is exact.
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LANG, tag)
            .apply()

        if (isPlatformLocaleApi()) {
            val lm = context.getSystemService(android.app.LocaleManager::class.java)
            lm?.applicationLocales =
                if (tag.isEmpty()) LocaleList.getEmptyLocaleList()
                else LocaleList.forLanguageTags(tag)
        }
    }

    /**
     * Wrap [base] so its resources resolve in the chosen language on API 29–32.
     * On API 33+ the platform applies the per-app locale itself, so we return
     * [base] unchanged. Call from Activity.attachBaseContext.
     */
    fun wrap(base: Context): Context {
        if (isPlatformLocaleApi()) return base
        val tag = base.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LANG, "") ?: ""
        if (tag.isEmpty()) return base
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return base.createConfigurationContext(config)
    }
}

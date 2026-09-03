package io.github.nikkittap.timelineexporter

import androidx.core.content.FileProvider

/**
 * The screenshot test's own FileProvider.
 *
 * It has to be a distinct class from the app's provider: the manifest merger
 * keys `<provider>` elements by `android:name`, so two providers both declared
 * as `androidx.core.content.FileProvider` — the shipping one in the main
 * manifest and this debug-only one — collide on `android:authorities` and the
 * debug merge fails. Subclassing gives this one its own key; the behaviour is
 * inherited unchanged, and it is compiled into debug builds only.
 */
class ScreengrabFileProvider : FileProvider()

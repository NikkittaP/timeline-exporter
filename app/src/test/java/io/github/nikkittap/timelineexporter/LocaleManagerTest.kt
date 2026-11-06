package io.github.nikkittap.timelineexporter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class LocaleManagerTest {
    @Test
    fun emptyTagFollowsTheSystem() {
        assertNull(LocaleManager.localeForTag(""))
    }

    @Test
    fun tagsMapToTheirLocale() {
        assertEquals(Locale.ENGLISH, LocaleManager.localeForTag("en"))
        assertEquals(Locale.forLanguageTag("pt-BR"), LocaleManager.localeForTag("pt-BR"))
        assertEquals("zh-Hant", LocaleManager.localeForTag("zh-Hant")!!.toLanguageTag())
    }
}

package com.actionmental

import com.actionmental.data.UserSettings
import com.actionmental.ui.i18n.AppTranslations
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AppTranslationsTest {
    @Test
    fun existingSettingsDefaultToChinese() {
        val settings = Json.decodeFromString<UserSettings>("""{"theme":"SYSTEM"}""")

        assertEquals(UserSettings.Language.CHINESE, settings.language)
    }

    @Test
    fun bilingualLabelsRenderAsOneLanguage() {
        assertEquals(
            "语言",
            AppTranslations.translate("语言 · LANGUAGE", UserSettings.Language.CHINESE),
        )
        assertEquals(
            "Language",
            AppTranslations.translate("语言 · LANGUAGE", UserSettings.Language.ENGLISH),
        )
    }

    @Test
    fun dynamicDashboardTextIsFullyEnglish() {
        val translated = AppTranslations.translate(
            "当前前台 · FOREGROUND RULE",
            UserSettings.Language.ENGLISH,
        )

        assertEquals("CURRENT FOREGROUND", translated)
        assertFalse(translated.contains(Regex("[\\p{IsHan}]")))
    }
}

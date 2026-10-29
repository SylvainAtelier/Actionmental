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
    fun translationDefaultsMigrateExistingSettingsWithoutCredentials() {
        val settings = Json.decodeFromString<UserSettings>("""{"theme":"SYSTEM"}""")
        assertEquals("MiniMax-M2.7", settings.translation.model)
        assertEquals(3, settings.translation.languages.size)
        assertEquals("zh-Hans", settings.translation.lastTargetId)
        val encoded = Json.encodeToString(UserSettings.serializer(), settings)
        assertFalse(encoded.contains("apiKey", ignoreCase = true))
        assertFalse(encoded.contains("token", ignoreCase = true))
    }

    @Test
    fun translationFeedbackHasEnglishRecoveryInstructions() {
        com.actionmental.core.translate.TranslationFailure.entries.forEach { kind ->
            val message = com.actionmental.ui.translationFailureMessage(com.actionmental.core.translate.TranslationException(kind))
            assertFalse(AppTranslations.translate(message, UserSettings.Language.ENGLISH).contains(Regex("[\\p{IsHan}]")))
        }
        listOf("翻译", "译文已生成，但历史保存失败", "连接成功", "Token 已保存", "剪贴记录", "语言配置", "操作",
            "翻译历史", "仅保存在本机", "提示", "翻译当前选中的记录", "请先选择一条剪贴记录").forEach {
            assertFalse(AppTranslations.translate(it, UserSettings.Language.ENGLISH).contains(Regex("[\\p{IsHan}]")))
        }
    }

    @Test
    fun speechEntryAndInsertionFeedbackHaveEnglishRecoveryInstructions() {
        listOf(
            "语音转文字",
            "已复制转写文本",
            "已插入转写文本",
            "未能写入原输入框，文本已保留；请打开语音转文字后复制",
            "已上屏",
            "语音输入",
            "语音输入原文",
            "未能打开翻译，文本已保留；请打开语音转文字后复制",
            "润色", "摘要", "改写", "处理", "处理方式", "指令", "结果", "正在处理…", "输入结果", "重新生成",
            "润色 / 摘要…", "例如：改成正式的邮件语气", "先写下要怎样处理原文", "点击「%s」获取结果",
            "正在请求 · 切换处理方式将取消，不会自动重发",
            "翻译与润色", "翻译、润色、摘要与改写", "点击处理时才联网 · 点复制时才写入剪贴板", "输入或粘贴要处理的文字",
        ).forEach { message ->
            assertEquals(message, AppTranslations.translate(message, UserSettings.Language.CHINESE))
            assertFalse(AppTranslations.translate(message, UserSettings.Language.ENGLISH).contains(Regex("[\\p{IsHan}]")))
        }
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

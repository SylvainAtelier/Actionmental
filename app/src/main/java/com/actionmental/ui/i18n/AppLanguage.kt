package com.actionmental.ui.i18n

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import com.actionmental.data.UserSettings

val LocalAppLanguage = staticCompositionLocalOf { UserSettings.Language.CHINESE }

val appLanguage: UserSettings.Language
    @Composable
    @ReadOnlyComposable
    get() = LocalAppLanguage.current

@Composable
@ReadOnlyComposable
fun uiText(chinese: String, english: String): String =
    if (appLanguage == UserSettings.Language.CHINESE) chinese else english

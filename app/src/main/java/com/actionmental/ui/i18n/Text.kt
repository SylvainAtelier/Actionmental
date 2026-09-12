package com.actionmental.ui.i18n

import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow

/** Applies the configured app language to every user-facing Compose text node. */
@Composable
fun Text(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    maxLines: Int = Int.MAX_VALUE,
    style: TextStyle = LocalTextStyle.current,
) {
    androidx.compose.material3.Text(
        text = localize(text),
        modifier = modifier,
        color = color,
        overflow = overflow,
        maxLines = maxLines,
        style = style,
    )
}

@Composable
@ReadOnlyComposable
fun localize(text: String): String = AppTranslations.translate(text, appLanguage)

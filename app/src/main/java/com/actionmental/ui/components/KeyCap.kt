package com.actionmental.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import com.actionmental.ui.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.actionmental.core.key.KeyCombo
import com.actionmental.ui.theme.AmShape
import com.actionmental.ui.theme.AmType
import com.actionmental.ui.theme.amColors

/**
 * 键帽。列表 / 录制 / 检测 / 冲突四处必须是同一个组件（设计规范 5.1）。
 *
 * @param main 主键用朱红面，修饰键用常规面。
 * @param pressed 按下态：阴影归零 + 下移 2dp。
 */
@Composable
fun KeyCap(
    text: String,
    modifier: Modifier = Modifier,
    main: Boolean = false,
    pressed: Boolean = false,
    style: TextStyle = AmType.keycap,
    padding: PaddingValues = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
) {
    val c = amColors
    val shape = RoundedCornerShape(AmShape.key)
    val face = if (main) c.accent else c.keycapFace
    val edge = if (main) c.accent else c.keycapLine
    val shadow = if (main) c.accentInk else c.keycapShadow
    val ink = if (main) Color.White else c.ink

    val drop by animateDpAsState(if (pressed) 2.dp else 0.dp, label = "keycap-drop")
    val shadowOffset: Dp = if (pressed) 0.dp else 2.dp

    Box(
        modifier = modifier
            .offset(y = drop)
            .hardShadow(shadow, shadowOffset, AmShape.key)
            .background(face, shape)
            .border(1.dp, edge, shape)
            .padding(padding),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = style, color = ink, maxLines = 1)
    }
}

/** 一整个组合键：修饰键在前，主键朱红。 */
@Composable
fun KeyComboRow(
    combo: KeyCombo,
    modifier: Modifier = Modifier,
    large: Boolean = false,
    pressed: Boolean = false,
) {
    KeyTokensRow(combo.tokens(), modifier, large, pressed)
}

/** 直接用字符串序列渲染（实时检测里会出现「只按了 Ctrl」这种没有主键的中间态）。 */
@Composable
fun KeyTokensRow(
    tokens: List<String>,
    modifier: Modifier = Modifier,
    large: Boolean = false,
    pressed: Boolean = false,
    highlightLast: Boolean = true,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(if (large) 8.dp else 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tokens.forEachIndexed { index, token ->
            val isMain = highlightLast && index == tokens.lastIndex
            KeyCap(
                text = token,
                main = isMain,
                pressed = pressed,
                style = if (large) AmType.keycapLarge else AmType.keycap,
                padding = if (large) PaddingValues(horizontal = 16.dp, vertical = 10.dp)
                else PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            )
            if (large && index != tokens.lastIndex) {
                Text("+", style = AmType.keycapLarge, color = amColors.inkFaint)
            }
        }
    }
}

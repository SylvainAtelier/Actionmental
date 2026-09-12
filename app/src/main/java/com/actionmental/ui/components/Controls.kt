package com.actionmental.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import com.actionmental.ui.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.actionmental.ui.theme.AmShape
import com.actionmental.ui.theme.AmSpace
import com.actionmental.ui.theme.AmType
import com.actionmental.ui.theme.amColors

/** 主按钮：3px 硬阴影，按下归零。 */
@Composable
fun AmPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
) {
    val c = amColors
    val shape = RoundedCornerShape(AmShape.key + 2.dp)
    val face = when {
        !enabled -> c.surfaceSunken
        destructive -> c.accent
        else -> c.ink
    }
    val ink = when {
        !enabled -> c.inkFaint
        else -> if (destructive) Color.White else c.bgScreen
    }
    Box(
        modifier = modifier
            .defaultMinSize(minHeight = AmSpace.rowMin)
            .hardShadow(if (enabled) (if (destructive) c.accentInk else c.shadowSolid) else Color.Transparent, 3.dp, AmShape.key + 2.dp)
            .background(face, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = AmSpace.s4, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = AmType.body, color = ink)
    }
}

/** 次级按钮：描边、无填充。 */
@Composable
fun AmSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    accent: Boolean = false,
) {
    val c = amColors
    val shape = RoundedCornerShape(AmShape.key + 2.dp)
    Box(
        modifier = modifier
            .defaultMinSize(minHeight = AmSpace.rowMin)
            .background(c.surface, shape)
            .border(BorderStroke(1.dp, if (accent) c.accentLine else c.line), shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = AmSpace.s3, vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = AmType.body,
            color = if (!enabled) c.inkFaint else if (accent) c.accent else c.inkMid,
        )
    }
}

/** 胶囊筛选 chip。 */
@Composable
fun AmChip(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val c = amColors
    val shape = RoundedCornerShape(AmShape.chip)
    val face = when {
        !enabled -> c.surfaceSunken
        selected -> c.ink
        else -> c.surface
    }
    Box(
        modifier = modifier
            .background(face, shape)
            .border(1.dp, if (selected && enabled) c.ink else c.line, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text,
            style = AmType.secondary,
            color = when {
                !enabled -> c.inkFaint
                selected -> c.bgScreen
                else -> c.inkMid
            },
        )
    }
}

/** 通用的「标题 + 副标题 + 右侧」列表行，列表行高恒 >= 44dp。 */
@Composable
fun AmRow(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val c = amColors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = AmSpace.rowMin)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AmSpace.s2),
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f)) {
            Text(title, style = AmType.body, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(subtitle, style = AmType.data, color = c.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        trailing?.invoke()
    }
}

/**
 * 分段控件：同一个页面里的几段内容，一次只显示一段。
 * 比多开几个导航入口更省心 —— 相关的东西留在一起，切换不丢上下文。
 */
@Composable
fun AmSegmented(
    options: List<String>,
    selectedIndex: Int,
    modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit,
) {
    val c = amColors
    val shape = RoundedCornerShape(AmShape.key + 2.dp)
    Row(
        modifier
            .fillMaxWidth()
            .background(c.surfaceSunken, shape)
            .border(1.dp, c.line, shape)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEachIndexed { index, option ->
            val selected = index == selectedIndex
            Box(
                Modifier
                    .weight(1f)
                    .background(if (selected) c.surface else Color.Transparent, shape)
                    .border(1.dp, if (selected) c.line else Color.Transparent, shape)
                    .clickable { onSelect(index) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    option,
                    style = AmType.body,
                    color = if (selected) c.ink else c.inkMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
fun AmSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, enabled: Boolean = true) {
    val c = amColors
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = c.surface,
            checkedTrackColor = c.ink,
            uncheckedThumbColor = c.inkFaint,
            uncheckedTrackColor = c.surfaceSunken,
            uncheckedBorderColor = c.line,
        ),
    )
}

@Composable
fun IconBox(icon: ImageVector, tint: Color = amColors.inkMid, background: Color = amColors.surfaceSunken) {
    Box(
        modifier = Modifier
            .background(background, RoundedCornerShape(AmShape.iconBox))
            .padding(7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.padding(0.dp))
    }
}

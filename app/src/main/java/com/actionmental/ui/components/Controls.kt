package com.actionmental.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import com.actionmental.ui.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.actionmental.ui.i18n.localize
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.actionmental.ui.theme.AmShape
import com.actionmental.ui.theme.AmSpace
import com.actionmental.ui.theme.AmType
import com.actionmental.ui.theme.amColors

/**
 * 按下的深度：硬阴影归零、按钮面下移到阴影的位置 —— 和键帽「按下去」是同一个动作（设计规范 4）。
 * 按下要快、松开稍慢，手指离开前就能看到反馈。
 */
@Composable
private fun pressDepth(source: MutableInteractionSource, rest: Dp): Dp {
    val pressed by source.collectIsPressedAsState()
    val depth by animateDpAsState(
        targetValue = if (pressed) rest else 0.dp,
        animationSpec = tween(if (pressed) 50 else 140),
        label = "press-depth",
    )
    return depth
}

/** 主按钮：3px 硬阴影，按下压平。 */
@Composable
fun AmPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
    icon: ImageVector? = null,
) {
    val c = amColors
    val shape = RoundedCornerShape(AmShape.key + 2.dp)
    val face = when {
        !enabled -> c.surfaceSunken
        destructive -> MaterialTheme.colorScheme.error
        else -> c.ink
    }
    val ink = when {
        !enabled -> c.inkFaint
        else -> if (destructive) MaterialTheme.colorScheme.onError else c.bgScreen
    }
    val source = remember { MutableInteractionSource() }
    val rest = 3.dp
    val depth = pressDepth(source, rest)
    Box(
        modifier = modifier
            .defaultMinSize(minHeight = AmSpace.rowMin)
            .offset(y = depth)
            .hardShadow(
                if (enabled) (if (destructive) c.accentInk else c.shadowSolid) else Color.Transparent,
                rest - depth,
                AmShape.key + 2.dp,
            )
            .clip(shape)
            .background(face)
            .clickable(
                interactionSource = source,
                indication = LocalIndication.current,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = AmSpace.s4, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        ButtonContent(text, icon, ink)
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
    icon: ImageVector? = null,
) {
    val c = amColors
    val shape = RoundedCornerShape(AmShape.key + 2.dp)
    Box(
        modifier = modifier
            .defaultMinSize(minHeight = AmSpace.rowMin)
            .clip(shape)
            .background(c.surface)
            .border(BorderStroke(1.dp, if (accent && enabled) c.accentLine else c.line), shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = AmSpace.s3, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        ButtonContent(text, icon, if (!enabled) c.inkFaint else if (accent) c.accent else c.inkMid)
    }
}

@Composable
private fun ButtonContent(text: String, icon: ImageVector?, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = AmType.body, color = color, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

/** 胶囊筛选 chip：看上去 36dp 高，可点区域补足到 48dp。 */
@Composable
fun AmChip(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /** 文字前面的小标记（状态点之类）。 */
    leading: (@Composable () -> Unit)? = null,
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
            .minimumInteractiveComponentSize()
            .defaultMinSize(minHeight = 36.dp)
            .clip(shape)
            .background(face)
            .border(1.dp, if (selected && enabled) c.ink else c.line, shape)
            .selectable(selected = selected, enabled = enabled, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (leading != null) {
                leading()
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text,
                style = AmType.secondary,
                color = when {
                    !enabled -> c.inkFaint
                    selected -> c.bgScreen
                    else -> c.inkMid
                },
                maxLines = 1,
            )
        }
    }
}

/** 通用的「标题 + 副标题 + 右侧」列表行，列表行高恒 >= 48dp。 */
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
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AmSpace.s2),
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f)) {
            Text(title, style = AmType.body, color = c.ink)
            if (subtitle != null) {
                Text(subtitle, style = AmType.data, color = c.inkMuted)
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
            .selectableGroup()
            .background(c.surfaceSunken, shape)
            .border(1.dp, c.line, shape)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEachIndexed { index, option ->
            val selected = index == selectedIndex
            Box(
                Modifier
                    .weight(1f)
                    .defaultMinSize(minHeight = AmSpace.rowMin)
                    .clip(shape)
                    .background(if (selected) c.surface else Color.Transparent)
                    .border(1.dp, if (selected) c.line else Color.Transparent, shape)
                    .selectable(selected = selected, role = Role.Tab) { onSelect(index) }
                    .padding(horizontal = 6.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    option,
                    style = AmType.body,
                    color = if (selected) c.ink else c.inkMuted,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}

@Composable
fun AmSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    label: String,
    modifier: Modifier = Modifier,
) {
    val c = amColors
    val accessibleLabel = localize(label)
    Switch(
        modifier = modifier.semantics { contentDescription = accessibleLabel },
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = c.surface,
            checkedTrackColor = c.ink,
            uncheckedThumbColor = c.inkMuted,
            uncheckedTrackColor = c.surfaceSunken,
            uncheckedBorderColor = c.inkFaint,
        ),
    )
}

@Composable
fun IconBox(icon: ImageVector, tint: Color = amColors.inkMid, background: Color = amColors.surfaceSunken) {
    Box(
        modifier = Modifier
            .background(background, RoundedCornerShape(AmShape.iconBox))
            .padding(8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
    }
}

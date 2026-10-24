package com.actionmental.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.actionmental.ui.i18n.Text
import com.actionmental.ui.theme.AmShape
import com.actionmental.ui.theme.AmSpace
import com.actionmental.ui.theme.AmType
import com.actionmental.ui.theme.amColors
import androidx.compose.material3.Text as RawText

/**
 * 设置页只用三档字：条目标题 [AmType.body]、说明 [AmType.secondary]、分组标签 [AmType.label]。
 * 输入框、下拉菜单、按钮里的字也压到同一档 —— Material 默认的 16sp 输入字会比旁边的标题还大。
 */
@Composable
fun ProvideSettingsTypography(content: @Composable () -> Unit) {
    val base = MaterialTheme.typography
    MaterialTheme(
        typography = base.copy(
            bodyLarge = AmType.body.copy(fontWeight = FontWeight.Normal),
            bodyMedium = AmType.body,
            bodySmall = AmType.secondary,
            labelLarge = AmType.body,
        ),
        content = content,
    )
}

/** 一组设置：卡片 + 分组标签，组内条目之间用 [SettingDivider] 分开。 */
@Composable
fun SettingsSection(
    title: String,
    modifier: Modifier = Modifier,
    alert: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    AmCard(modifier.fillMaxWidth(), alert = alert) {
        AmLabel(title, Modifier.padding(bottom = 4.dp).semantics { heading() })
        content()
    }
}

@Composable
fun SettingDivider() {
    HorizontalDivider(Modifier.padding(vertical = 4.dp), color = amColors.lineSoft)
}

/**
 * 标题 + 说明 + 右侧控件的一行。
 *
 * 右侧放按钮这种比开关宽的东西时传 [stackBelow]：可用宽度不足它（按字号放大折算）时，
 * 控件落到文字下面，文字不会被挤成一字一行。
 */
@Composable
fun SettingItem(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    titleColor: Color = Color.Unspecified,
    descriptionMaxLines: Int = Int.MAX_VALUE,
    /** 用户自己写的内容（语言名、提示词）不能走界面翻译表。 */
    translate: Boolean = true,
    stackBelow: Dp = 0.dp,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
        val stacked = trailing != null && maxWidth < stackBelow * fontScale
        val text: @Composable (Modifier) -> Unit = { textModifier ->
            SettingText(title, description, titleColor, descriptionMaxLines, translate, textModifier)
        }
        if (stacked) {
            Column(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(AmSpace.s1),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AmSpace.s1)) {
                    leading?.invoke()
                    text(Modifier.weight(1f))
                }
                trailing?.invoke()
            }
        } else {
            Row(
                Modifier.fillMaxWidth().defaultMinSize(minHeight = AmSpace.rowMin).padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AmSpace.s2),
            ) {
                if (leading != null) {
                    Row(
                        Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(AmSpace.s1),
                    ) {
                        leading()
                        text(Modifier.weight(1f))
                    }
                } else {
                    text(Modifier.weight(1f))
                }
                trailing?.invoke()
            }
        }
    }
}

/** 整行都可点的开关：手指不必瞄准那颗小开关。 */
@Composable
fun SettingSwitch(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    titleColor: Color = Color.Unspecified,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
) {
    SettingItem(
        title = title,
        description = description,
        titleColor = titleColor,
        leading = leading,
        modifier = modifier
            .clip(RoundedCornerShape(AmShape.key))
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange),
        trailing = { AmSwitch(checked, onCheckedChange = null, enabled = enabled, label = title) },
    )
}

/** 标题 + 说明，控件整块放在下面（分段选择、色板、输入框）。 */
@Composable
fun SettingBlock(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(AmSpace.s1),
    ) {
        SettingText(title, description, Color.Unspecified, Int.MAX_VALUE, true, Modifier.fillMaxWidth())
        content()
    }
}

/** 段落说明，和条目说明同一档字。 */
@Composable
fun SettingNote(text: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified) {
    Text(
        text,
        style = AmType.secondary,
        color = if (color == Color.Unspecified) amColors.inkMuted else color,
        modifier = modifier,
    )
}

/** 长说明默认收起：需要时再看，不把开关挤到屏幕外。 */
@Composable
fun SettingDetails(label: String, content: @Composable ColumnScope.() -> Unit) {
    val c = amColors
    var open by rememberSaveable { mutableStateOf(false) }
    val angle by animateFloatAsState(if (open) 180f else 0f, label = "details-chevron")
    Row(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = AmSpace.rowMin)
            .clip(RoundedCornerShape(AmShape.key))
            .toggleable(value = open, role = Role.Button) { open = it }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = AmType.body, color = c.inkMid, modifier = Modifier.weight(1f))
        Icon(Icons.Outlined.ExpandMore, contentDescription = null, tint = c.inkMid,
            modifier = Modifier.size(20.dp).rotate(angle))
    }
    AnimatedVisibility(open) {
        Column(Modifier.padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
    }
}

@Composable
private fun SettingText(
    title: String,
    description: String?,
    titleColor: Color,
    descriptionMaxLines: Int,
    translate: Boolean,
    modifier: Modifier,
) {
    val c = amColors
    val titleInk = if (titleColor == Color.Unspecified) c.ink else titleColor
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (translate) {
            Text(title, style = AmType.body, color = titleInk)
        } else {
            RawText(title, style = AmType.body, color = titleInk)
        }
        if (description != null) {
            if (translate) {
                Text(description, style = AmType.secondary, color = c.inkMuted,
                    maxLines = descriptionMaxLines, overflow = TextOverflow.Ellipsis)
            } else {
                RawText(description, style = AmType.secondary, color = c.inkMuted,
                    maxLines = descriptionMaxLines, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

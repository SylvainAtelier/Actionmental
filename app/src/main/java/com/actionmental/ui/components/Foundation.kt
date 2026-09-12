package com.actionmental.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import com.actionmental.ui.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.actionmental.ui.theme.AmShape
import com.actionmental.ui.theme.AmSpace
import com.actionmental.ui.theme.AmType
import com.actionmental.ui.theme.amColors

/**
 * 2px 实心硬阴影（设计规范 4：不用模糊阴影，海拔靠一条实边表达）。
 */
fun Modifier.hardShadow(color: Color, offsetY: Dp, corner: Dp): Modifier = this.drawBehind {
    val radius = corner.toPx()
    translate(top = offsetY.toPx()) {
        drawRoundRect(
            color = color,
            size = Size(size.width, size.height),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius),
        )
    }
}

@Composable
fun AmCard(
    modifier: Modifier = Modifier,
    alert: Boolean = false,
    padding: Dp = AmSpace.card,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = amColors
    val shape = RoundedCornerShape(AmShape.card)
    Column(
        modifier = modifier
            .hardShadow(if (alert) c.accentLine else c.shadowSolid, 2.dp, AmShape.card)
            .background(if (alert) c.accentBg else c.surface, shape)
            .border(1.dp, if (alert) c.accentLine else c.line, shape)
            .padding(padding),
        content = content,
    )
}

/** 状态点：7px 圆 + 3px 同色 16% 光环。 */
@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(13.dp)
            .drawBehind {
                val center = Offset(size.width / 2, size.height / 2)
                drawCircle(color.copy(alpha = 0.16f), radius = 6.5.dp.toPx(), center = center)
                drawCircle(color, radius = 3.5.dp.toPx(), center = center)
            }
    )
}

/** 全大写技术标签。 */
@Composable
fun AmLabel(text: String, modifier: Modifier = Modifier, color: Color = amColors.inkMuted) {
    Text(text.uppercase(), style = AmType.label, color = color, modifier = modifier)
}

@Composable
fun SectionHeader(title: String, technical: String? = null, trailing: (@Composable RowScope.() -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = AmSpace.s1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = AmType.cardTitle, color = amColors.ink)
        Box(Modifier.weight(1f))
        trailing?.invoke(this)
    }
}

/** 键名 ←→ 值，1px 虚线分隔的数据行。 */
@Composable
fun DataRow(key: String, value: String, valueColor: Color? = null) {
    val c = amColors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 26.dp)
            .drawWithContent {
                drawContent()
                val y = size.height - 0.5f
                var x = 0f
                val dash = 3.dp.toPx()
                while (x < size.width) {
                    drawLine(
                        color = Color(0x33888888),
                        start = Offset(x, y),
                        end = Offset(x + dash, y),
                        strokeWidth = 1f,
                    )
                    x += dash * 2
                }
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(key, style = AmType.data, color = c.inkMuted)
        Text(
            value,
            style = AmType.data,
            color = valueColor ?: c.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = AmSpace.s2),
        )
    }
}

package com.actionmental.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import com.actionmental.ui.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.actionmental.ui.theme.AmType
import com.actionmental.ui.theme.amColors

/** 状态严重程度 → 颜色语义（设计规范 2）。 */
enum class StatusTone { OK, WARN, ALERT, IDLE }

@Composable
fun StatusTone.color(): Color = when (this) {
    StatusTone.OK -> amColors.ok
    StatusTone.WARN -> amColors.warn
    StatusTone.ALERT -> amColors.accent
    StatusTone.IDLE -> amColors.inkFaint
}

/**
 * 状态卡：状态点 + 中文名 + 结论 + 等宽技术值。
 * 首页 2×2 用它，Shizuku 页与引导页复用同一个组件，避免同一状态有两种长相。
 */
@Composable
fun StatusCard(
    name: String,
    conclusion: String,
    technical: String,
    tone: StatusTone,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val c = amColors
    AmCard(
        modifier = modifier.then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            StatusDot(tone.color())
            Text(name, style = AmType.secondary, color = c.inkMuted)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            conclusion,
            style = AmType.cardTitle,
            color = if (tone == StatusTone.ALERT) c.accent else c.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(3.dp))
        Text(
            technical,
            style = AmType.data,
            color = c.inkFaint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 1.dp),
        )
    }
}

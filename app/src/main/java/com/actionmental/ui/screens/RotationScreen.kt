package com.actionmental.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.actionmental.core.rotation.Panel
import com.actionmental.core.rotation.RotationMode
import com.actionmental.core.rotation.RotationTier
import com.actionmental.core.rotation.ScreenProfile
import com.actionmental.core.rotation.ordered
import com.actionmental.ui.AppViewModel
import com.actionmental.ui.components.AmCard
import com.actionmental.ui.components.AmLabel
import com.actionmental.ui.components.AmSecondaryButton
import com.actionmental.ui.components.AmSegmented
import com.actionmental.ui.components.AmSwitch
import com.actionmental.ui.components.DataRow
import com.actionmental.ui.components.StatusDot
import com.actionmental.ui.i18n.Text
import com.actionmental.ui.theme.AmShape
import com.actionmental.ui.theme.AmSpace
import com.actionmental.ui.theme.AmType
import com.actionmental.ui.theme.amColors
import java.text.SimpleDateFormat
import java.util.Locale

private val stamp = SimpleDateFormat("HH:mm:ss", Locale.US)

/**
 * 屏幕方向（PRD 7 / 8 / 9）。
 *
 * 转屏相关的三件事——设定方向、按应用自动切换、排查为什么没转——原本是三个平级入口，
 * 但它们从来都是同一件事的三个阶段，所以合并成一个页面下的三段。
 * 页面上出现的每一个值都来自 RotationController 的真实查询，包括失败时的 UNKNOWN。
 */
@Composable
fun RotationScreen(vm: AppViewModel, modifier: Modifier = Modifier) {
    var tab by rememberSaveable { mutableIntStateOf(0) }

    Column(modifier.fillMaxSize()) {
        Column(Modifier.padding(start = AmSpace.screen, end = AmSpace.screen, top = AmSpace.screen)) {
            ScreenTitle("屏幕方向", "ROTATION · 真实系统状态") {
                if (tab == 0) AmSecondaryButton("重新读取", vm::refreshRotation)
            }
            Spacer(Modifier.height(AmSpace.s2))
            AmSegmented(
                options = listOf("方向", "应用规则", "诊断"),
                selectedIndex = tab,
                onSelect = { tab = it },
            )
        }
        when (tab) {
            0 -> RotationControlSection(vm, Modifier.weight(1f))
            1 -> AppRulesScreen(vm, Modifier.weight(1f), embedded = true)
            else -> DiagnosticsScreen(vm, Modifier.weight(1f), embedded = true)
        }
    }
}

@Composable
private fun RotationControlSection(vm: AppViewModel, modifier: Modifier = Modifier) {
    val c = amColors
    val state by vm.rotationState.collectAsStateWithLifecycle()
    val access by vm.settingsAccess.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val here by vm.currentPanel.collectAsStateWithLifecycle()
    val screens = settings.screens.ordered()
    // 选中的屏：-1 表示跟着正在用的那块走，点过选屏器才固定下来
    var picked by rememberSaveable { mutableIntStateOf(-1) }
    val selected = screens.getOrNull(picked) ?: here?.let { panel -> screens.firstOrNull { it.panel.sameScreen(panel) } }
    val selectedName = selected?.let { screenName(screens.indexOf(it)) }
    // 选中的是另一块屏（不是正在用的）：控件只改它记住的方向，切过去时生效；否则对着系统事实
    val other = selected?.takeUnless { profile -> here?.let(profile.panel::sameScreen) == true }
    val shown = other?.mode ?: state.mode
    val writable = other != null || state.writable
    val pick: (RotationMode) -> Unit = { mode ->
        if (other == null) vm.setRotation(mode) else vm.setScreenMode(other.panel, mode)
    }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(AmSpace.screen),
        verticalArrangement = Arrangement.spacedBy(AmSpace.s2),
    ) {
        if (vm.foldable) ScreenPicker(screens, selected, here, onPick = { picked = it })

        if (other == null) {
            AmCard(Modifier.fillMaxWidth(), alert = state.mode.isForced) {
                AmLabel("当前 CURRENT")
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(
                        when {
                            !state.available -> c.warn
                            state.mode.isForced -> c.accent
                            else -> c.ok
                        }
                    )
                    Text(
                        state.mode.label,
                        style = AmType.pageTitle,
                        color = if (state.mode.isForced) c.accent else c.ink,
                    )
                }
                Text(
                    state.failure ?: (
                        "已在 " + stamp.format(state.verifiedAtMs) +
                            if (state.tier == RotationTier.SHELL) " 由 WindowManager 校验" else " 回读系统设置"
                        ),
                    style = AmType.data,
                    color = c.inkFaint,
                )
            }

            if (state.tier != RotationTier.SHELL) {
                TierCard(state.tier, access.system, vm::openWriteSettings)
            }
        } else {
            AmCard(Modifier.fillMaxWidth()) {
                AmLabel(selectedName + " · 记住的方向")
                Spacer(Modifier.height(4.dp))
                Text(shown.label, style = AmType.pageTitle, color = c.ink)
                Text("切换到这块屏时生效", style = AmType.data, color = c.inkFaint)
            }
        }

        AmLabel("强制角度 · FORCED ANGLE")
        // 四个角度按 0° → 90° → 180° → 270° 顺时针排，位置本身就是提示；放得下就排成一行
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val readable = maxWidth.value / LocalDensity.current.fontScale.coerceAtLeast(1f)
            val perRow = if (readable >= ONE_ROW_ANGLES_DP) 4 else 2
            Column(verticalArrangement = Arrangement.spacedBy(AmSpace.s1)) {
                RotationMode.forced.chunked(perRow).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AmSpace.s1)) {
                        row.forEach { AngleTile(it, shown, writable, Modifier.weight(1f), pick) }
                    }
                }
            }
        }

        AmCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("恢复系统默认旋转", style = AmType.body, color = c.ink)
                    Text("NORMAL · 交还 Android 旋转策略", style = AmType.data, color = c.inkFaint)
                }
                AmSecondaryButton(
                    "恢复",
                    onClick = { pick(RotationMode.NORMAL) },
                    enabled = writable,
                )
            }
        }

        if (selected != null && selectedName != null) {
            val label = selectedName + " · 套用应用规则"
            AmCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(label, style = AmType.body, color = c.ink)
                        Text(
                            if (selected.appRules) "前台应用有规则时以规则为准" else "所有应用都用上面的方向",
                            style = AmType.data,
                            color = c.inkFaint,
                        )
                    }
                    AmSwitch(
                        selected.appRules,
                        onCheckedChange = { on -> vm.setScreenAppRules(selected.panel, on) },
                        label = label,
                    )
                }
            }
        }

        AmCard(Modifier.fillMaxWidth()) {
            AmLabel("系统原始值 · SYSTEM VALUES")
            Spacer(Modifier.height(4.dp))
            DataRow("user_rotation", state.userRotation?.toString() ?: "—")
            DataRow("accelerometer_rotation", state.accelerometerRotation?.toString() ?: "—")
            DataRow("ignore_app_request", state.ignoreAppRequest?.toString() ?: "—")
            DataRow("fixed_to_user_rotation", state.fixedToUserRotation?.toString() ?: "—")
            DataRow("写入级别 tier", state.tier.technical)
            Spacer(Modifier.height(6.dp))
            Text(
                "写入方式：" + state.tier.label + "。两个旋转设置不需要权限就读得到；读不到会显示 UNKNOWN，不会伪装为已关闭。",
                style = AmType.secondary,
                color = c.inkMid,
            )
        }
    }
}

/**
 * 没有 Shizuku 时说清楚：现在是哪一级在起作用、做得到什么、还差什么。
 *
 * 「强制方向不灵」最常见的原因不是坏了，而是退到了只能锁定的那一级 ——
 * 应用自己声明了竖屏，锁定就压不住它。这张卡片把这件事摆在角度砖上面。
 */
@Composable
private fun TierCard(tier: RotationTier, systemWritable: Boolean, onGrant: () -> Unit) {
    val c = amColors
    AmCard(Modifier.fillMaxWidth(), alert = !tier.writable) {
        Text(
            if (tier.writable) "降级运行：" + tier.label else "旋转控制不可用",
            style = AmType.body,
            color = c.accent,
        )
        Text(
            when (tier) {
                RotationTier.OVERLAY ->
                    "Shizuku 不可用，改由无障碍悬浮层强制方向：压得住应用自带的方向，但部分大屏 / 折叠屏会忽略它，转不过去时会如实提示。多屏下发需要 Shizuku。"
                RotationTier.SETTINGS ->
                    "Shizuku 与无障碍服务都不可用，只能改系统设置锁定角度：应用自己声明了方向时以应用为准。"
                else ->
                    "Shizuku 不可用、无障碍服务未连接，且未授予「修改系统设置」权限。快捷键与按键检测不受影响。"
            },
            style = AmType.secondary,
            color = c.inkMid,
        )
        if (!systemWritable) {
            Spacer(Modifier.height(AmSpace.s1))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "授予「修改系统设置」后，自动旋转开关与 0° 锁定也能照常写入。",
                    style = AmType.secondary,
                    color = c.inkMid,
                    modifier = Modifier.weight(1f),
                )
                AmSecondaryButton("去授权", onGrant, accent = true)
            }
        }
    }
}

/** 角度砖：角度是主角，方向名是注解。 */
@Composable
private fun AngleTile(
    mode: RotationMode,
    current: RotationMode,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onSelect: (RotationMode) -> Unit,
) {
    val c = amColors
    val active = mode == current
    val shape = RoundedCornerShape(AmShape.card)
    Column(
        modifier
            .background(if (active) c.accentBg else c.surface, shape)
            .border(1.dp, if (active) c.accentLine else c.line, shape)
            .clickable(enabled = enabled) { onSelect(mode) }
            .padding(AmSpace.card),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(if (active) c.accent else c.inkFaint)
            Text(
                mode.angle,
                style = AmType.pageTitle,
                color = when {
                    !enabled -> c.inkFaint
                    active -> c.accent
                    else -> c.ink
                },
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            mode.shape,
            style = AmType.secondary,
            color = if (enabled) c.inkMid else c.inkFaint,
        )
    }
}

/** 角度砖排成一行所需的正文宽度（按字体缩放折算后）。 */
private const val ONE_ROW_ANGLES_DP = 480f

/** 按面积从大到小：最大的是内屏，其余是外屏。 */
private fun screenName(index: Int): String = when (index) {
    0 -> "内屏"
    1 -> "外屏"
    else -> "外屏 " + index
}

/**
 * 选屏器：按真实长宽比画出每块屏，正在用的那块标「使用中」。
 *
 * 下面的角度、恢复与规则开关都作用于选中的屏 —— 先选屏、再改设置，和桌面系统的多显示器设置同一个心智。
 * 选中态是墨色粗边，不只靠颜色。只认得一块屏时说清楚怎样让它认出另一块。
 */
@Composable
private fun ScreenPicker(screens: List<ScreenProfile>, selected: ScreenProfile?, here: Panel?, onPick: (Int) -> Unit) {
    val c = amColors
    AmLabel("屏幕 · SCREENS")
    if (screens.size < 2) {
        Text("合上或展开一次，就能分别设置两块屏的方向。", style = AmType.secondary, color = c.inkMid)
        return
    }
    Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(AmSpace.s1)) {
        screens.forEachIndexed { index, screen ->
            val isSelected = selected?.panel == screen.panel
            AmCard(
                Modifier.weight(1f).semantics { this.selected = isSelected },
                padding = AmSpace.s2,
                selected = isSelected,
                onClick = { onPick(index) },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PanelGlyph(screen.panel)
                    Spacer(Modifier.width(AmSpace.s2))
                    Column {
                        Text(screenName(index), style = AmType.body, color = c.ink)
                        Text(
                            listOfNotNull(screen.inches?.let { "$it\u2033" }, "${screen.panel.short}×${screen.panel.long}")
                                .joinToString(" · "),
                            style = AmType.data,
                            color = c.inkFaint,
                        )
                        if (here != null && screen.panel.sameScreen(here)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                StatusDot(c.ok)
                                Text("使用中", style = AmType.data, color = c.inkMid)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 屏幕轮廓：按竖着拿的样子、真实长宽比画，长边固定 36dp。 */
@Composable
private fun PanelGlyph(panel: Panel) {
    val long = 36f
    Box(
        Modifier
            .size((panel.short * long / panel.long).dp, long.dp)
            .border(2.dp, amColors.inkMid, RoundedCornerShape(4.dp)),
    )
}

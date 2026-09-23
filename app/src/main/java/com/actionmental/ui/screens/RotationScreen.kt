package com.actionmental.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.actionmental.core.rotation.RotationMode
import com.actionmental.core.rotation.RotationTier
import com.actionmental.ui.AppViewModel
import com.actionmental.ui.components.AmCard
import com.actionmental.ui.components.AmLabel
import com.actionmental.ui.components.AmSecondaryButton
import com.actionmental.ui.components.AmSegmented
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
    val writable = state.writable

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(AmSpace.screen),
        verticalArrangement = Arrangement.spacedBy(AmSpace.s2),
    ) {
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

        AmLabel("强制角度 · FORCED ANGLE")
        // 四个角度按 0° → 90° → 180° → 270° 顺时针排，位置本身就是提示
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AmSpace.s1)) {
            AngleTile(RotationMode.FORCE_PORTRAIT, state.mode, writable, Modifier.weight(1f), vm::setRotation)
            AngleTile(RotationMode.FORCE_LANDSCAPE, state.mode, writable, Modifier.weight(1f), vm::setRotation)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AmSpace.s1)) {
            AngleTile(RotationMode.FORCE_REVERSE_PORTRAIT, state.mode, writable, Modifier.weight(1f), vm::setRotation)
            AngleTile(RotationMode.FORCE_REVERSE_LANDSCAPE, state.mode, writable, Modifier.weight(1f), vm::setRotation)
        }

        AmCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("恢复系统默认旋转", style = AmType.body, color = c.ink)
                    Text("NORMAL · 交还 Android 旋转策略", style = AmType.data, color = c.inkFaint)
                }
                AmSecondaryButton(
                    "恢复",
                    onClick = { vm.setRotation(RotationMode.NORMAL) },
                    enabled = writable,
                )
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

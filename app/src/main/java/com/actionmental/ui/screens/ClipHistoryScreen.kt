package com.actionmental.ui.screens

import android.content.ClipboardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.produceState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import com.actionmental.ui.components.AmChip
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.Text as RawText
import com.actionmental.core.clip.ClipEntry
import com.actionmental.core.clip.ClipPolicy
import com.actionmental.ui.AppViewModel
import com.actionmental.ui.components.AmCard
import com.actionmental.ui.components.AmLabel
import com.actionmental.ui.components.AmSecondaryButton
import com.actionmental.ui.components.AmSwitch
import com.actionmental.ui.components.StatusDot
import com.actionmental.ui.i18n.Text
import com.actionmental.ui.i18n.localize
import com.actionmental.ui.theme.AmShape
import com.actionmental.ui.theme.AmSpace
import com.actionmental.ui.theme.AmType
import com.actionmental.ui.theme.amColors

/**
 * 剪贴板历史：开关、说明、可搜索的列表。
 *
 * 平时用的是快捷键唤出的悬浮面板；这一页负责「要不要记、记了什么、怎么删」。
 */
@Composable
fun ClipHistoryScreen(vm: AppViewModel, modifier: Modifier = Modifier) {
    val c = amColors
    val context = LocalContext.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val watching by vm.clipWatching.collectAsStateWithLifecycle()
    val clips by vm.clips.collectAsStateWithLifecycle()
    val query by vm.clipQuery.collectAsStateWithLifecycle()
    val clipLimit by vm.clipLimit.collectAsStateWithLifecycle()
    val focused = LocalWindowInfo.current.isWindowFocused
    var opened by remember { mutableStateOf<ClipEntry?>(null) }

    // 没有监听时，这一页拿着焦点的那一刻是普通应用唯一读得到剪贴板的时候：把当前这一条补上。
    // 有监听就不读 —— 多读一次只会多弹一次系统的「已读取剪贴板」提示。
    LaunchedEffect(focused, settings.clipHistory, watching) {
        if (!focused || !settings.clipHistory || watching) return@LaunchedEffect
        val text = runCatching {
            context.getSystemService(ClipboardManager::class.java)
                .primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
        }.getOrNull()
        vm.offerForegroundClip(text)
    }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(AmSpace.screen),
        verticalArrangement = Arrangement.spacedBy(AmSpace.s2),
    ) {
        item { ScreenTitle("剪贴板历史", "CLIPBOARD") }

        item {
            AmCard(Modifier.fillMaxWidth()) {
                AmLabel("记录 · CAPTURE")
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(
                        when {
                            !settings.clipHistory -> c.inkFaint
                            watching -> c.ok
                            else -> c.warn
                        },
                    )
                    Column(Modifier.weight(1f)) {
                        Text("记录剪贴板历史", style = AmType.body, color = c.ink)
                        Text(
                            when {
                                !settings.clipHistory -> "未开启 · 不读取也不保存任何剪贴板内容"
                                watching -> "记录中 · 经 Shizuku 监听，每次复制都会收下"
                                status.shizuku.usable -> "已开启 · 正在连接特权服务"
                                else -> "已开启 · Shizuku " + status.shizuku.conclusion + "，只收本应用复制的内容和打开此页时的剪贴板"
                            },
                            style = AmType.data,
                            color = c.inkFaint,
                        )
                    }
                    AmSwitch(settings.clipHistory, onCheckedChange = vm::setClipHistory)
                }
                Spacer(Modifier.height(AmSpace.s2))
                Text(
                    "在快捷键里添加「剪贴板 → 剪贴板历史」动作，就能在任意应用里唤出面板：" +
                        "直接打字搜索（中文可用全拼或首字母，如 zw 找「中文」），↑↓ 选择，" +
                        "Enter 写进当前输入框，Ctrl+1…9 直选，Ctrl+P 置顶，Del 删除，Esc 关闭。",
                    style = AmType.secondary,
                    color = c.inkMid,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "只存在本机，不进系统备份。被来源应用标成敏感的内容（如密码管理器复制的密码）不记录。" +
                        "部分系统每次记录时会提示「Shell 已读取剪贴板」，可在系统隐私设置里关掉剪贴板访问提示。",
                    style = AmType.secondary,
                    color = c.inkFaint,
                )
            }
        }

        item {
            StorageCard(
                capacity = settings.clipHistoryCapacity,
                days = settings.clipHistoryRetentionDays,
                onCapacity = vm::setClipCapacity,
                onDays = vm::setClipRetention,
            )
        }

        if (settings.clipHistoryExcluded.isNotEmpty()) {
            item {
                AmCard(Modifier.fillMaxWidth()) {
                    AmLabel("不记录的应用 · EXCLUDED")
                    settings.clipHistoryExcluded.sorted().forEach { pkg ->
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(vm.appLabel(pkg), style = AmType.body, color = c.ink)
                                Text(pkg, style = AmType.data, color = c.inkFaint)
                            }
                            AmSecondaryButton("恢复记录", onClick = { vm.includeClipSource(pkg) })
                        }
                    }
                }
            }
        }

        item {
            OutlinedTextField(
                value = query,
                onValueChange = vm::setClipQuery,
                singleLine = true,
                placeholder = { Text("搜索历史…", style = AmType.secondary, color = c.inkFaint) },
                leadingIcon = { Icon(Icons.Filled.Search, null, tint = c.inkFaint) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(AmShape.key + 2.dp),
            )
        }

        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RawText(
                    clips.size.toString() + " " + localize("条"),
                    style = AmType.data,
                    color = c.inkFaint,
                    modifier = Modifier.weight(1f),
                )
                AmSecondaryButton("清空未置顶", vm::clearClips, accent = true)
            }
        }

        if (clips.isEmpty()) {
            item {
                Text(
                    if (query.isNotBlank()) "没有匹配的记录" else "还没有记录",
                    style = AmType.secondary,
                    color = c.inkFaint,
                )
            }
        }

        items(clips, key = { it.id }) { entry ->
            ClipCard(entry, vm, onOpen = { opened = entry })
        }

        // 懒加载：这一项被排进屏幕（列表滚到底）时才要下一页
        if (clips.isNotEmpty() && clips.size >= clipLimit) {
            item(key = "more") {
                LaunchedEffect(clips.size) { vm.loadMoreClips() }
                Text(
                    "正在加载更多…",
                    style = AmType.data,
                    color = c.inkFaint,
                    modifier = Modifier.fillMaxWidth().padding(vertical = AmSpace.s2),
                )
            }
        }
    }

    // 列表刷新后用最新的那一份（置顶状态等），条目没了就关掉
    opened?.let { picked ->
        val current = clips.firstOrNull { it.id == picked.id } ?: picked
        ClipDetailSheet(current, vm, ownPackage = context.packageName, onDismiss = { opened = null })
    }
}

/**
 * 保存：最多留多少条、多久没用过就删。两样都只管未置顶的，置顶的永远留着。
 *
 * 条数的默认值与档位是设备实测定的（见 ClipPolicy.DEFAULT_CAPACITY）；「不限」照样能选，
 * 一天几十次复制要好几年才到五万条。天数按「多久没用过」算，一直在用的条目不会因为老而被删。
 */
@Composable
private fun StorageCard(capacity: Int, days: Int, onCapacity: (Int) -> Unit, onDays: (Int) -> Unit) {
    val c = amColors
    var custom by remember { mutableStateOf("") }
    val parsed = ClipPolicy.parseRetentionDays(custom)

    AmCard(Modifier.fillMaxWidth()) {
        AmLabel("保存 · STORAGE")
        Spacer(Modifier.height(6.dp))
        Text("最多保留", style = AmType.body, color = c.ink)
        Spacer(Modifier.height(4.dp))
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ClipPolicy.CAPACITY_PRESETS.forEach { preset ->
                AmChip(
                    if (preset == 0) localize("不限") else "%,d".format(preset) + " " + localize("条"),
                    selected = capacity == preset,
                ) { onCapacity(preset) }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            if (capacity <= 0) "不限条数 · 超过五万条后，搜冷门词会慢到看得出来"
            else "超出时删掉最久没用过的；一万条以内搜索都在 20ms 内",
            style = AmType.data,
            color = c.inkFaint,
        )

        Spacer(Modifier.height(AmSpace.s3))
        Text("自动清理", style = AmType.body, color = c.ink)
        Spacer(Modifier.height(4.dp))
        RawText(
            if (days <= 0) localize("不自动清理")
            else localize("未置顶的条目超过 ") + days + " " + localize("天没用过就自动删除，置顶的不受影响"),
            style = AmType.data,
            color = c.inkFaint,
        )
        Spacer(Modifier.height(AmSpace.s2))
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ClipPolicy.RETENTION_PRESETS.forEach { preset ->
                AmChip(
                    if (preset == 0) localize("不清理") else preset.toString() + " " + localize("天"),
                    selected = days == preset,
                ) { onDays(preset) }
            }
            // 自己填过的天数不在预设里：单独亮一枚，让人看得出现在生效的是哪个
            if (days > 0 && days !in ClipPolicy.RETENTION_PRESETS) {
                AmChip(days.toString() + " " + localize("天"), selected = true) {}
            }
        }
        Spacer(Modifier.height(AmSpace.s2))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = custom,
                onValueChange = { input -> custom = input.filter(Char::isDigit).take(4) },
                singleLine = true,
                placeholder = { Text("自定义天数", style = AmType.secondary, color = c.inkFaint) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = custom.isNotEmpty() && parsed == null,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(AmShape.key + 2.dp),
            )
            Spacer(Modifier.width(AmSpace.s2))
            AmSecondaryButton("设定", onClick = {
                parsed?.let {
                    onDays(it)
                    custom = ""
                }
            }, enabled = parsed != null)
        }
        if (custom.isNotEmpty() && parsed == null) {
            Spacer(Modifier.height(4.dp))
            RawText(
                localize("请输入 1 到 ") + ClipPolicy.MAX_RETENTION_DAYS + " " + localize("之间的天数"),
                style = AmType.data,
                color = c.warn,
            )
        }
    }
}

/** 来源 · 多久以前 · 用过几次 · 字数。卡片与详情共用。 */
@Composable
private fun metaLine(entry: ClipEntry, vm: AppViewModel): String = listOfNotNull(
    if (entry.pinned) "★ " + localize("置顶") else null,
    entry.sourcePackage?.let(vm::appLabel),
    localize(ClipPolicy.age(System.currentTimeMillis(), entry.lastUsedAtMs)),
    if (entry.useCount > 1) "×" + entry.useCount else null,
    "%,d".format(entry.length) + " " + localize("个字符"),
).joinToString(" · ")

/**
 * 列表里的一条：点整张卡看全文。
 *
 * 卡片上只留最常用的两个动作；删除和「不记录此应用」收进详情，免得滑动列表时误触。
 */
@Composable
private fun ClipCard(entry: ClipEntry, vm: AppViewModel, onOpen: () -> Unit) {
    val c = amColors
    AmCard(Modifier.fillMaxWidth().clip(RoundedCornerShape(AmShape.card)).clickable(onClick = onOpen)) {
        // 用户的原文不能过界面翻译：中文模式下翻译层会把不含汉字的「 · 」分段整段丢掉
        RawText(
            ClipPolicy.preview(entry.text, 400),
            style = AmType.body,
            color = c.ink,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            RawText(
                metaLine(entry, vm),
                style = AmType.data,
                color = if (entry.pinned) c.accent else c.inkFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(AmSpace.s2))
            AmSecondaryButton("复制", onClick = { vm.copyClip(entry) })
            Spacer(Modifier.width(6.dp))
            AmSecondaryButton(if (entry.pinned) "取消置顶" else "置顶", onClick = { vm.toggleClipPin(entry) })
        }
    }
}

/**
 * 详情：全文 + 全部动作。
 *
 * 底部弹层而不是新页面：看完一划就回到原来的滚动位置。Material 3 的弹层在宽屏上
 * 自己收窄到 640dp，手机上占满宽度，不用分别处理。正文切段交给懒加载列表，
 * 64K 字符的长文也是边滚边排版；可以长按选择其中一段。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClipDetailSheet(entry: ClipEntry, vm: AppViewModel, ownPackage: String, onDismiss: () -> Unit) {
    val c = amColors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val full by produceState<String?>(if (entry.complete) entry.text else null, entry.id) {
        value = vm.clipText(entry)
    }
    val maxBody = (LocalConfiguration.current.screenHeightDp * 0.62f).dp

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = c.surface) {
        Column(Modifier.fillMaxWidth().padding(horizontal = AmSpace.screen).padding(bottom = AmSpace.s4)) {
            AmLabel("内容 · CONTENT")
            Spacer(Modifier.height(4.dp))
            RawText(metaLine(entry, vm), style = AmType.data, color = c.inkFaint)
            Spacer(Modifier.height(AmSpace.s2))

            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxBody)
                    .background(c.surfaceSunken, RoundedCornerShape(AmShape.key + 2.dp))
                    .padding(horizontal = AmSpace.s3, vertical = AmSpace.s2),
            ) {
                val text = full
                if (text == null) {
                    Text("正在读取…", style = AmType.secondary, color = c.inkFaint)
                } else {
                    val parts = remember(text) { ClipPolicy.chunks(text) }
                    SelectionContainer {
                        LazyColumn {
                            items(parts.size) { i ->
                                RawText(parts[i], style = DetailText, color = c.ink)
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(AmSpace.s3))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                AmSecondaryButton("复制", onClick = { vm.copyClip(entry) })
                AmSecondaryButton(if (entry.pinned) "取消置顶" else "置顶", onClick = { vm.toggleClipPin(entry) })
                Spacer(Modifier.weight(1f))
                AmSecondaryButton("删除", onClick = {
                    vm.deleteClip(entry)
                    onDismiss()
                }, accent = true)
            }
            val source = entry.sourcePackage
            if (source != null && source != ownPackage) {
                Spacer(Modifier.height(AmSpace.s2))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RawText(
                        localize("来源 · ") + vm.appLabel(source),
                        style = AmType.data,
                        color = c.inkFaint,
                        modifier = Modifier.weight(1f),
                    )
                    AmSecondaryButton("不记录此应用", onClick = {
                        vm.excludeClipSource(source)
                        onDismiss()
                    })
                }
            }
        }
    }
}

/** 读全文用的字号：比列表里的正文大一档、常规字重、行距放宽，长段落不累眼。 */
private val DetailText = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 22.sp)

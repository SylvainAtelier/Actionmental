package com.actionmental.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.actionmental.core.translate.TranslationLanguage
import com.actionmental.core.translate.TranslationSettings
import com.actionmental.ui.components.AmPrimaryButton
import com.actionmental.ui.components.AmSecondaryButton
import com.actionmental.ui.components.SettingBlock
import com.actionmental.ui.components.SettingDivider
import com.actionmental.ui.components.SettingItem
import com.actionmental.ui.components.SettingNote
import com.actionmental.ui.components.SettingsSection
import com.actionmental.ui.components.amFocusRing
import com.actionmental.ui.i18n.Text
import com.actionmental.ui.i18n.localize
import com.actionmental.ui.theme.AmShape
import com.actionmental.ui.theme.AmSpace
import com.actionmental.ui.theme.AmType
import com.actionmental.ui.theme.amColors
import java.util.UUID
import androidx.compose.material3.Text as RawText

/** Secrets never enter this state; only their presence and sanitized operation results do. */
data class TranslationSettingsUiState(
    val settings: TranslationSettings = TranslationSettings(),
    val tokenConfigured: Boolean = false,
    val tokenSaving: Boolean = false,
    val connectionTesting: Boolean = false,
    val connectionMessage: String? = null,
    val connectionFailed: Boolean = false,
    val tokenMessage: String? = null,
    val tokenFailed: Boolean = false,
    val models: List<String> = emptyList(),
    val modelsLoading: Boolean = false,
    val modelsMessage: String? = null,
    val modelsFailed: Boolean = false,
    val modelsUnsupported: Boolean = false,
    /** Bumped whenever a model fetch finishes, so the picker can open exactly once per request. */
    val modelsFetchCount: Int = 0,
)

data class TranslationSettingsActions(
    val onSaveToken: (String) -> Unit,
    val onClearToken: () -> Unit,
    val onModelChange: (String) -> Unit,
    val onLanguagesChange: (List<TranslationLanguage>) -> Unit,
    val onTestConnection: () -> Unit,
    val onLoadModels: (String?) -> Unit,
)

/** Stateless persistence boundary: the caller owns encrypted storage, networking and errors. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TranslationSettings(
    state: TranslationSettingsUiState,
    actions: TranslationSettingsActions,
    modifier: Modifier = Modifier,
) {
    val c = amColors
    // Intentionally not saveable: never persist a token draft in saved instance state.
    var token by remember { mutableStateOf("") }
    var tokenVisible by remember { mutableStateOf(false) }
    var modelsMenuOpen by remember { mutableStateOf(false) }
    // Fetch count at the moment the user asked for models; the picker opens once a newer result lands.
    var openAfterFetch by remember { mutableStateOf<Int?>(null) }
    var modelSaved by remember { mutableStateOf(false) }
    val loadModels by rememberUpdatedState(actions.onLoadModels)
    DisposableEffect(Unit) {
        onDispose { loadModels("") }
    }
    LaunchedEffect(state.modelsFetchCount) {
        val requestedAt = openAfterFetch ?: return@LaunchedEffect
        if (state.modelsFetchCount > requestedAt) {
            openAfterFetch = null
            modelsMenuOpen = state.models.isNotEmpty()
        }
    }
    var clearTokenConfirmation by remember { mutableStateOf(false) }
    var editorOpen by remember { mutableStateOf(false) }
    var editingLanguage by remember { mutableStateOf<TranslationLanguage?>(null) }
    var deletingLanguage by remember { mutableStateOf<TranslationLanguage?>(null) }
    val busy = state.tokenSaving || state.connectionTesting
    val canFetchModels = !busy && !state.modelsLoading && (token.isNotBlank() || state.tokenConfigured)
    val fetchModels = {
        modelSaved = false
        openAfterFetch = state.modelsFetchCount
        loadModels(token.takeIf { it.isNotBlank() })
    }

    SettingsSection("翻译 · TRANSLATION", modifier) {
        SettingNote("仅在你主动翻译或测试连接时访问 MiniMax 中国站，不会发送剪贴板内容。")
        SettingDivider()

        SettingBlock(
            "MiniMax Token",
            description = if (state.tokenConfigured) "已配置 · 本机加密保存，不包含在配置导出或备份中"
            else "未配置 · 填写 MiniMax 中国站 API Token 后即可翻译",
        ) {
            OutlinedTextField(
                value = token,
                onValueChange = {
                    token = it
                    // Models fetched with another token no longer describe this one.
                    if (state.models.isNotEmpty() || state.modelsLoading) loadModels("")
                    modelsMenuOpen = false
                    openAfterFetch = null
                },
                placeholder = {
                    Text(if (state.tokenConfigured) "输入新 Token 以替换" else "粘贴 API Token")
                },
                singleLine = true,
                enabled = !busy,
                textStyle = AmType.body,
                visualTransformation = if (tokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                trailingIcon = {
                    IconButton(onClick = { tokenVisible = !tokenVisible }) {
                        Icon(
                            if (tokenVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            contentDescription = localize(if (tokenVisible) "隐藏 Token" else "显示 Token"),
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            // 保存与测试紧贴输入框：填完、存好、马上验证，手指不用离开这一块
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AmPrimaryButton(
                    if (state.tokenSaving) "正在保存…" else "保存",
                    icon = Icons.Outlined.Check,
                    onClick = {
                        val pending = token.trim()
                        token = ""
                        tokenVisible = false
                        actions.onSaveToken(pending)
                    },
                    enabled = token.isNotBlank() && !busy,
                )
                AmSecondaryButton(
                    if (state.connectionTesting) "正在测试…" else "测试连接",
                    icon = Icons.Outlined.NetworkCheck,
                    onClick = actions.onTestConnection,
                    enabled = state.tokenConfigured && token.isBlank() && !busy,
                )
                if (state.tokenConfigured) {
                    AmSecondaryButton(
                        "移除",
                        icon = Icons.Outlined.DeleteOutline,
                        onClick = { clearTokenConfirmation = true },
                        enabled = !busy,
                        accent = true,
                    )
                }
            }
            when {
                state.tokenSaving -> Progress("正在保存 Token…")
                state.connectionTesting -> Progress("正在连接 MiniMax 中国站…")
                state.connectionMessage != null -> Feedback(state.connectionMessage, state.connectionFailed)
                state.tokenMessage != null -> Feedback(state.tokenMessage, state.tokenFailed)
                state.tokenConfigured && token.isNotBlank() -> SettingNote("先保存新 Token，再测试连接。")
            }
        }
        SettingDivider()

        SettingBlock("翻译模型", description = "获取模型后从列表中选择，选定即保存") {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val picker: @Composable (Modifier) -> Unit = { pickerModifier ->
                    ModelPicker(
                        current = state.settings.model,
                        models = state.models,
                        expanded = modelsMenuOpen,
                        enabled = state.models.isNotEmpty() || canFetchModels,
                        onOpen = { if (state.models.isNotEmpty()) modelsMenuOpen = true else fetchModels() },
                        onDismiss = { modelsMenuOpen = false },
                        onSelect = { selectedModel ->
                            modelsMenuOpen = false
                            modelSaved = true
                            if (selectedModel != state.settings.model) actions.onModelChange(selectedModel)
                        },
                        modifier = pickerModifier,
                    )
                }
                val fetchButton: @Composable () -> Unit = {
                    AmSecondaryButton(
                        if (state.modelsLoading) "正在获取…" else "获取模型",
                        icon = Icons.Outlined.Refresh,
                        onClick = fetchModels,
                        enabled = canFetchModels,
                    )
                }
                if (maxWidth < 320.dp) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        picker(Modifier.fillMaxWidth())
                        fetchButton()
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        picker(Modifier.weight(1f))
                        fetchButton()
                    }
                }
            }
            when {
                state.modelsLoading -> Progress("正在获取模型…")
                state.modelsMessage != null -> Feedback(state.modelsMessage, state.modelsFailed)
                modelSaved -> Feedback("模型已保存", false)
                !state.tokenConfigured && token.isBlank() -> SettingNote("请先填写或保存 Token 再获取模型。")
                state.models.isNotEmpty() && token.isNotBlank() ->
                    SettingNote("使用当前填写的 Token 获取，翻译前需保存 Token。")
            }
        }
        SettingDivider()

        SettingBlock("目标语言", description = "按这里的顺序显示在翻译面板；语言名称与翻译提示可分别编辑。") {
            val languages = state.settings.languages
            languages.forEachIndexed { index, language ->
                key(language.id) {
                    if (index > 0) SettingDivider()
                    SettingItem(
                        title = language.name,
                        description = language.prompt,
                        descriptionMaxLines = 2,
                        translate = false,
                        stackBelow = 440.dp,
                        trailing = {
                            Row {
                                IconButton(onClick = { editingLanguage = language; editorOpen = true }) {
                                    Icon(Icons.Outlined.Edit, localize("编辑目标语言") + ": " + language.name, tint = c.ink)
                                }
                                IconButton(onClick = {
                                    val ordered = languages.toMutableList()
                                    ordered[index] = ordered[index - 1].also { ordered[index - 1] = language }
                                    actions.onLanguagesChange(ordered)
                                }, enabled = index > 0) {
                                    Icon(Icons.Outlined.ArrowUpward, localize("上移目标语言") + ": " + language.name)
                                }
                                IconButton(onClick = {
                                    val ordered = languages.toMutableList()
                                    ordered[index] = ordered[index + 1].also { ordered[index + 1] = language }
                                    actions.onLanguagesChange(ordered)
                                }, enabled = index < languages.lastIndex) {
                                    Icon(Icons.Outlined.ArrowDownward, localize("下移目标语言") + ": " + language.name)
                                }
                                IconButton(onClick = { deletingLanguage = language }, enabled = languages.size > 1) {
                                    Icon(Icons.Outlined.DeleteOutline, localize("删除目标语言") + ": " + language.name)
                                }
                            }
                        },
                    )
                }
            }
            AmSecondaryButton(
                "添加目标语言",
                icon = Icons.Outlined.Add,
                onClick = { editingLanguage = null; editorOpen = true },
                enabled = languages.size < 30,
            )
            if (languages.size <= 1) {
                SettingNote("至少保留一种目标语言。")
            }
            if (languages.size >= 30) {
                SettingNote("最多添加 30 种目标语言；删除一项后可继续添加。")
            }
        }
    }

    if (clearTokenConfirmation) {
        AlertDialog(
            onDismissRequest = { clearTokenConfirmation = false },
            title = { Text("移除 Token？") },
            text = { Text("移除后无法翻译；目标语言与模型设置会保留。") },
            confirmButton = {
                AmPrimaryButton("移除 Token", onClick = { clearTokenConfirmation = false; actions.onClearToken() }, destructive = true)
            },
            dismissButton = { AmSecondaryButton("取消", onClick = { clearTokenConfirmation = false }) },
        )
    }
    deletingLanguage?.let { language ->
        AlertDialog(
            onDismissRequest = { deletingLanguage = null },
            title = { Text("删除目标语言？") },
            text = { RawText(language.name, color = c.ink) },
            confirmButton = {
                AmPrimaryButton("删除", onClick = {
                    actions.onLanguagesChange(state.settings.languages.filterNot { it.id == language.id })
                    deletingLanguage = null
                }, destructive = true, enabled = state.settings.languages.size > 1)
            },
            dismissButton = { AmSecondaryButton("取消", onClick = { deletingLanguage = null }) },
        )
    }
    if (editorOpen) {
        key(editingLanguage?.id) {
            TranslationLanguageEditor(editingLanguage, onDismiss = { editorOpen = false }, onSave = { language ->
                val languages = state.settings.languages
                actions.onLanguagesChange(
                    if (editingLanguage == null) languages + language
                    else languages.map { if (it.id == language.id) language else it },
                )
                editorOpen = false
            })
        }
    }
}

/**
 * 只读下拉框：显示已保存的模型，点开从服务器返回的列表里挑，挑中即保存。
 * 列表还没取回来时点它，等同于点「获取模型」。
 */
@Composable
private fun ModelPicker(
    current: String,
    models: List<String>,
    expanded: Boolean,
    enabled: Boolean,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = amColors
    val shape = RoundedCornerShape(AmShape.key + 2.dp)
    val angle = if (expanded) 180f else 0f
    val pickerLabel = localize("翻译模型")
    BoxWithConstraints(modifier) {
        val menuWidth = maxWidth
        Row(
            Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = AmSpace.rowMin)
                .clip(shape)
                .background(c.surfaceSunken)
                .border(1.dp, if (expanded) c.ink else c.line, shape)
                .amFocusRing(shape)
                .clickable(enabled = enabled, role = Role.DropdownList, onClick = onOpen)
                .semantics {
                    contentDescription = pickerLabel
                    stateDescription = current
                }
                .padding(start = AmSpace.s3, end = AmSpace.s2, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RawText(
                current,
                style = AmType.body,
                color = if (enabled) c.ink else c.inkFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(Icons.Outlined.ExpandMore, contentDescription = null, tint = c.inkMid,
                modifier = Modifier.size(20.dp).rotate(angle))
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = onDismiss,
            modifier = Modifier.width(menuWidth).heightIn(max = 320.dp),
        ) {
            models.forEach { model ->
                val chosen = model == current
                DropdownMenuItem(
                    text = {
                        RawText(model, style = AmType.body, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    trailingIcon = if (chosen) {
                        { Icon(Icons.Outlined.Check, contentDescription = null, tint = c.ink) }
                    } else {
                        null
                    },
                    modifier = Modifier.semantics { selected = chosen },
                    onClick = { onSelect(model) },
                )
            }
        }
    }
}

@Composable
private fun Progress(message: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = amColors.inkMid)
        Feedback(message, false)
    }
}

@Composable
private fun Feedback(message: String, failed: Boolean) {
    Text(message, style = AmType.secondary,
        color = if (failed) MaterialTheme.colorScheme.error else amColors.inkMid,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
}

@Composable
private fun TranslationLanguageEditor(
    language: TranslationLanguage?,
    onDismiss: () -> Unit,
    onSave: (TranslationLanguage) -> Unit,
) {
    val c = amColors
    val id = rememberSaveable { language?.id ?: UUID.randomUUID().toString() }
    var name by rememberSaveable { mutableStateOf(language?.name.orEmpty()) }
    var prompt by rememberSaveable { mutableStateOf(language?.prompt.orEmpty()) }
    val nameFocus = remember { FocusRequester() }
    val promptFocus = remember { FocusRequester() }
    var submitted by remember { mutableStateOf(false) }
    var discardConfirmation by remember { mutableStateOf(false) }
    val validName = name.trim().isNotEmpty() && name.trim().length <= 80
    val validPrompt = prompt.trim().isNotEmpty() && prompt.trim().length <= 2000
    val dirty = name != language?.name.orEmpty() || prompt != language?.prompt.orEmpty()
    val dismiss = { if (dirty) discardConfirmation = true else onDismiss() }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(if (language == null) "添加目标语言" else "编辑目标语言", style = AmType.cardTitle) },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.55f).dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, label = { Text("语言名称") },
                    supportingText = { Text(if (submitted && !validName) "请输入 1–80 个字符的语言名称。" else "显示在语言选择器中，例如：日语（敬语）。") },
                    isError = submitted && !validName, singleLine = true, textStyle = AmType.body,
                    modifier = Modifier.fillMaxWidth().focusRequester(nameFocus),
                )
                OutlinedTextField(
                    value = prompt, onValueChange = { prompt = it }, label = { Text("翻译提示") },
                    supportingText = { Text(if (submitted && !validPrompt) "请输入 1–2000 个字符的翻译提示。" else "描述目标语言、语气或地区，例如：译为自然、礼貌的日语。") },
                    isError = submitted && !validPrompt, minLines = 3, maxLines = 7, textStyle = AmType.body,
                    modifier = Modifier.fillMaxWidth().focusRequester(promptFocus),
                )
                Text("稳定 ID · 编辑名称或排序不会改变", style = AmType.secondary, color = c.inkMuted)
                RawText(id, style = AmType.data, color = c.inkMuted)
            }
        },
        confirmButton = {
            AmPrimaryButton("保存", onClick = {
                submitted = true
                when {
                    !validName -> nameFocus.requestFocus()
                    !validPrompt -> promptFocus.requestFocus()
                    else -> onSave(TranslationLanguage(id, name.trim(), prompt.trim()))
                }
            })
        },
        dismissButton = { AmSecondaryButton("取消", onClick = dismiss) },
    )
    if (discardConfirmation) {
        AlertDialog(
            onDismissRequest = { discardConfirmation = false },
            title = { Text("放弃未保存的修改？", style = AmType.cardTitle) },
            text = { Text("返回后，本次输入的语言名称和翻译提示不会保存。") },
            confirmButton = { AmPrimaryButton("放弃修改", onClick = onDismiss, destructive = true) },
            dismissButton = { AmSecondaryButton("继续编辑", onClick = { discardConfirmation = false }) },
        )
    }
}

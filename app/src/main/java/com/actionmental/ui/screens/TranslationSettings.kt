package com.actionmental.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.actionmental.core.translate.TranslationLanguage
import com.actionmental.core.translate.TranslationSettings
import com.actionmental.ui.components.AmCard
import com.actionmental.ui.components.AmChip
import com.actionmental.ui.components.AmLabel
import com.actionmental.ui.components.AmPrimaryButton
import com.actionmental.ui.components.AmSecondaryButton
import com.actionmental.ui.i18n.Text
import com.actionmental.ui.i18n.localize
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
)

data class TranslationSettingsActions(
    val onSaveToken: (String) -> Unit,
    val onClearToken: () -> Unit,
    val onModelChange: (String) -> Unit,
    val onLanguagesChange: (List<TranslationLanguage>) -> Unit,
    val onTestConnection: () -> Unit,
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
    var clearTokenConfirmation by remember { mutableStateOf(false) }
    var model by rememberSaveable(state.settings.model) { mutableStateOf(state.settings.model) }
    var editorOpen by remember { mutableStateOf(false) }
    var editingLanguage by remember { mutableStateOf<TranslationLanguage?>(null) }
    var deletingLanguage by remember { mutableStateOf<TranslationLanguage?>(null) }
    val busy = state.tokenSaving || state.connectionTesting
    val modelValid = model.trim().isNotEmpty() && model.trim().length <= 128 &&
        model.none { it.isWhitespace() || it.isISOControl() }

    AmCard(modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(AmSpace.s2)) {
            AmLabel("翻译 · TRANSLATION", modifier = Modifier.semantics { heading() })
            Text(
                "仅在你主动翻译时，将选定文本与目标语言提示发送至 MiniMax 中国站。连接测试也会访问服务，不会发送剪贴板内容。",
                style = AmType.secondary,
                color = c.inkMid,
            )
            Text("MiniMax CN Token", style = AmType.body, color = c.ink)
            Text(
                if (state.tokenConfigured) "已配置 · 本机加密保存，不包含在配置导出或备份中"
                else "未配置 · 填写 MiniMax 中国站 API Token 后即可翻译",
                style = AmType.secondary,
                color = c.inkMid,
            )
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text(if (state.tokenConfigured) "替换 Token" else "API Token") },
                supportingText = { Text("不会回显已保存的 Token；留空不会修改。") },
                singleLine = true,
                enabled = !busy,
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
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AmSecondaryButton(
                    if (state.tokenSaving) "正在保存…" else "保存 Token",
                    onClick = {
                        val pending = token.trim()
                        token = ""
                        tokenVisible = false
                        actions.onSaveToken(pending)
                    },
                    enabled = token.isNotBlank() && !busy,
                )
                if (state.tokenConfigured) {
                    AmSecondaryButton("移除 Token", onClick = { clearTokenConfirmation = true }, enabled = !busy)
                }
            }
            state.tokenMessage?.let { TranslationFeedback(it, state.tokenFailed) }

            HorizontalDivider(color = c.line)
            Text("翻译模型", style = AmType.body, color = c.ink)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("MiniMax-M2.7", "MiniMax-M2.5").forEach { preset ->
                    AmChip(preset, selected = model == preset, enabled = !busy) {
                        model = preset
                        actions.onModelChange(preset)
                    }
                }
            }
            OutlinedTextField(
                value = model,
                onValueChange = { model = it },
                label = { Text("模型 ID") },
                supportingText = { Text("可填写服务支持的模型 ID，最多 128 个字符，不含空格。") },
                singleLine = true,
                enabled = !busy,
                isError = !modelValid,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth(),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AmSecondaryButton("保存模型", onClick = { actions.onModelChange(model.trim()) },
                    enabled = !busy && modelValid && model.trim() != state.settings.model)
                AmSecondaryButton(
                    if (state.connectionTesting) "正在测试…" else "测试连接",
                    onClick = actions.onTestConnection,
                    enabled = state.tokenConfigured && !busy && model == state.settings.model && token.isBlank(),
                )
            }
            if (!state.tokenConfigured) {
                Text("请先保存 Token，再测试连接。", style = AmType.secondary, color = c.inkMid)
            } else if (model != state.settings.model || token.isNotBlank()) {
                Text("测试使用已保存的配置，请先保存修改。", style = AmType.secondary, color = c.inkMid)
            }
            if (state.connectionTesting) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = c.ink)
                    TranslationFeedback("正在连接 MiniMax 中国站…", false)
                }
            } else {
                state.connectionMessage?.let { TranslationFeedback(it, state.connectionFailed) }
            }

            HorizontalDivider(color = c.line)
            Text("目标语言", style = AmType.body, color = c.ink, modifier = Modifier.semantics { heading() })
            Text("按这里的顺序显示在翻译面板；语言名称与翻译提示可分别编辑。", style = AmType.secondary, color = c.inkMid)
            state.settings.languages.forEachIndexed { index, language ->
                key(language.id) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        RawText(language.name, style = AmType.body, color = c.ink)
                        RawText(language.prompt, style = AmType.secondary, color = c.inkMid, maxLines = 3,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            IconButton(onClick = { editingLanguage = language; editorOpen = true }, modifier = Modifier.size(48.dp)) {
                                Icon(Icons.Outlined.Edit, localize("编辑目标语言") + ": " + language.name, tint = c.ink)
                            }
                            IconButton(onClick = {
                                val ordered = state.settings.languages.toMutableList()
                                ordered[index] = ordered[index - 1].also { ordered[index - 1] = language }
                                actions.onLanguagesChange(ordered)
                            }, enabled = index > 0, modifier = Modifier.size(48.dp)) {
                                Icon(Icons.Outlined.ArrowUpward, localize("上移目标语言") + ": " + language.name)
                            }
                            IconButton(onClick = {
                                val ordered = state.settings.languages.toMutableList()
                                ordered[index] = ordered[index + 1].also { ordered[index + 1] = language }
                                actions.onLanguagesChange(ordered)
                            }, enabled = index < state.settings.languages.lastIndex, modifier = Modifier.size(48.dp)) {
                                Icon(Icons.Outlined.ArrowDownward, localize("下移目标语言") + ": " + language.name)
                            }
                            IconButton(onClick = { deletingLanguage = language }, enabled = state.settings.languages.size > 1,
                                modifier = Modifier.size(48.dp)) {
                                Icon(Icons.Outlined.DeleteOutline, localize("删除目标语言") + ": " + language.name)
                            }
                        }
                    }
                }
            }
            if (state.settings.languages.size <= 1) {
                Text("至少保留一种目标语言。", style = AmType.secondary, color = c.inkMid)
            }
            AmSecondaryButton("添加目标语言", onClick = { editingLanguage = null; editorOpen = true },
                enabled = state.settings.languages.size < 30)
            if (state.settings.languages.size >= 30) {
                Text("最多添加 30 种目标语言；删除一项后可继续添加。", style = AmType.secondary, color = c.inkMid)
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

@Composable
private fun TranslationFeedback(message: String, failed: Boolean) {
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
        title = { Text(if (language == null) "添加目标语言" else "编辑目标语言") },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.55f).dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, label = { Text("语言名称") },
                    supportingText = { Text(if (submitted && !validName) "请输入 1–80 个字符的语言名称。" else "显示在语言选择器中，例如：日语（敬语）。") },
                    isError = submitted && !validName, singleLine = true,
                    modifier = Modifier.fillMaxWidth().focusRequester(nameFocus),
                )
                OutlinedTextField(
                    value = prompt, onValueChange = { prompt = it }, label = { Text("翻译提示") },
                    supportingText = { Text(if (submitted && !validPrompt) "请输入 1–2000 个字符的翻译提示。" else "描述目标语言、语气或地区，例如：译为自然、礼貌的日语。") },
                    isError = submitted && !validPrompt, minLines = 3, maxLines = 7,
                    modifier = Modifier.fillMaxWidth().focusRequester(promptFocus),
                )
                Text("稳定 ID · 编辑名称或排序不会改变", style = AmType.data, color = c.inkMid)
                RawText(id, style = AmType.data, color = c.inkMid)
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
            title = { Text("放弃未保存的修改？") },
            text = { Text("返回后，本次输入的语言名称和翻译提示不会保存。") },
            confirmButton = { AmPrimaryButton("放弃修改", onClick = onDismiss, destructive = true) },
            dismissButton = { AmSecondaryButton("继续编辑", onClick = { discardConfirmation = false }) },
        )
    }
}

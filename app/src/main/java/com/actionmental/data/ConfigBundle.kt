package com.actionmental.data

import com.actionmental.core.remap.KeyRemap
import com.actionmental.core.shortcut.Shortcut
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * 导出／导入的整份配置。
 *
 * 快捷键与键位映射是同一套「我把键盘调成了这样」，分成两份导出就等于让用户
 * 记住要复制两次剪贴板 —— 换机时少复制一次的后果是键位映射静默丢失。
 *
 * 两个列表都可空，而且 null 与空列表含义不同：null 表示这份 JSON 里没有这一节，
 * 导入时**跳过**它；空列表表示导出时那张表确实是空的，导入时照实清空。
 * 早期版本导出的是裸的快捷键数组，[ConfigTransfer.parse] 把它读成 remaps = null，
 * 于是拿旧备份恢复快捷键时不会连带清掉现有的映射。
 */
@Serializable
data class ConfigBundle(
    val version: Int = VERSION,
    val shortcuts: List<Shortcut>? = null,
    val remaps: List<KeyRemap>? = null,
) {
    companion object {
        const val VERSION = 1
    }
}

/** 导入了多少条，供提示语用。 */
data class ImportSummary(val shortcuts: Int, val remaps: Int)

/**
 * 配置的进出口。
 *
 * 放在仓库外面：它跨两张表，而两个仓库都不该知道另一张表的存在。
 * 写入仍然全部走各自仓库的 replace 路径，唯一性约束因此照旧生效。
 */
class ConfigTransfer(
    private val appStore: AppStore,
    private val shortcutRepository: ShortcutRepository,
    private val keyRemapRepository: KeyRemapRepository,
) {

    fun export(): String = appStore.json.encodeToString(
        ConfigBundle.serializer(),
        ConfigBundle(
            shortcuts = shortcutRepository.shortcuts.value,
            remaps = keyRemapRepository.remaps.value,
        ),
    )

    suspend fun import(raw: String): Result<ImportSummary> = runCatching {
        val bundle = parse(raw)
        ImportSummary(
            shortcuts = bundle.shortcuts?.let { shortcutRepository.importAll(it) } ?: 0,
            remaps = bundle.remaps?.let { keyRemapRepository.importAll(it) } ?: 0,
        )
    }

    private fun parse(raw: String): ConfigBundle {
        val text = raw.trim()
        // 以 [ 开头的是旧格式：那时导出的就是快捷键数组本身
        return if (text.startsWith("[")) {
            ConfigBundle(
                shortcuts = appStore.json.decodeFromString(ListSerializer(Shortcut.serializer()), text),
            )
        } else {
            appStore.json.decodeFromString(ConfigBundle.serializer(), text)
        }
    }
}

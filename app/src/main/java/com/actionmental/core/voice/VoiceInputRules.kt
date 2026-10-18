package com.actionmental.core.voice

import java.util.Locale

/**
 * 语音输入动作里与 Android 无关的两条判断：键盘上哪一颗是语音键、哪个输入法是语音输入法。
 *
 * 各家输入法的语音键没有统一的标识，只能按无障碍描述 / 文字 / 视图 id 里的字眼认；
 * 认法收在这里，平台层只负责把节点的三个字段递进来。
 */
object VoiceInputRules {

    /** 子类型的 mode 为它时，这个输入法提供语音输入（AOSP 约定，Google 语音输入就是这样声明的）。 */
    const val VOICE_SUBTYPE_MODE = "voice"

    /** 整串就是这些字眼时最可信。 */
    private val exact = setOf(
        "语音", "语音输入", "语音键", "麦克风", "按住说话", "点击说话", "说话",
        "voice", "voice input", "voice typing", "mic", "microphone", "dictation", "speak",
        "use voice typing", "start voice input", "語音輸入", "語音",
    )

    /** 串里含这些字眼也算，但排在整串匹配之后。 */
    private val partial = listOf(
        "语音输入", "语音", "麦克风", "说话", "听写", "語音",
        "voice input", "voice typing", "voice", "microphone", "dictat", "speak",
    )

    /** 含这些字眼的是语音键旁边的设置、语言、翻译等入口，不是语音键本身。 */
    private val excluded = listOf(
        "设置", "語言", "语言", "翻译", "表情", "皮肤", "关闭语音", "退出语音",
        "setting", "language", "translat", "emoji",
    )

    /** 视图 id 里常见的写法：`mic_key`、`key_pos_voice`、`btn_voice`。 */
    private val idPattern = Regex("(^|[_/:.])(mic|voice|speech|dictation)([_.]|$)")

    /** 单独成词的 mic，避开 microsoft 一类。 */
    private val micWord = Regex("(^|[^a-z])mic([^a-z]|$)")

    /**
     * 一个节点像不像语音键：0 表示不是，越大越像。
     *
     * 描述比文字可信（键盘按键的图标只有描述），整串比包含可信，视图 id 只做兜底。
     */
    fun score(text: CharSequence?, description: CharSequence?, viewId: String?): Int {
        val desc = normalize(description)
        val label = normalize(text)
        if (excluded.any { desc.contains(it) || label.contains(it) }) return 0

        var best = 0
        if (desc in exact) best = maxOf(best, 6)
        if (label in exact) best = maxOf(best, 5)
        if (partial.any { desc.contains(it) } || micWord.containsMatchIn(desc)) best = maxOf(best, 4)
        if (partial.any { label.contains(it) } || micWord.containsMatchIn(label)) best = maxOf(best, 3)
        val id = viewId?.lowercase(Locale.ROOT)?.substringAfter(":id/")
        if (id != null && idPattern.containsMatchIn("_" + id + "_")) best = maxOf(best, 2)
        return best
    }

    private fun normalize(value: CharSequence?): String =
        value?.toString()?.trim()?.lowercase(Locale.ROOT)?.trimEnd('.', '。', '…') ?: ""

    /** 已启用的一个输入法，只留挑选要用的几样。 */
    data class Ime(val id: String, val label: String, val subtypeModes: List<String>) {
        val hasVoice: Boolean get() = subtypeModes.any { it.equals(VOICE_SUBTYPE_MODE, ignoreCase = true) }

        /** 只有语音子类型：Google 语音输入这类，切过去就开始听。 */
        val voiceOnly: Boolean
            get() = subtypeModes.isNotEmpty() && subtypeModes.all { it.equals(VOICE_SUBTYPE_MODE, ignoreCase = true) }
    }

    /**
     * 该切到哪一个语音输入法。当前输入法本身不算 —— 切到自己什么也不会发生。
     * 专职的语音输入法优先：兼带语音子类型的普通键盘切过去不一定直接开始听。
     */
    fun pickVoiceIme(enabled: List<Ime>, current: String?): Ime? =
        enabled.filter { it.hasVoice && it.id != current }
            .sortedByDescending { it.voiceOnly }
            .firstOrNull()

    /** 当前是不是停在一个专职语音输入法上 —— 是的话再按一次就该切回去。 */
    fun isOnVoiceIme(enabled: List<Ime>, current: String?): Boolean =
        current != null && enabled.any { it.id == current && it.voiceOnly }

    /**
     * 从语音输入法切回去时用哪一个：优先记下的那个，它不在已启用列表里了就挑第一个不是语音专职的。
     */
    fun pickReturnIme(enabled: List<Ime>, current: String?, remembered: String?): Ime? =
        enabled.firstOrNull { it.id == remembered && it.id != current }
            ?: enabled.firstOrNull { !it.voiceOnly && it.id != current }
}

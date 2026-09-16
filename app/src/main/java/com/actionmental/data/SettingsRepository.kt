package com.actionmental.data

import com.actionmental.core.rotation.RotationMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.Serializable

@Serializable
data class UserSettings(
    val theme: Theme = Theme.SYSTEM,
    val language: Language = Language.CHINESE,
    val showRawKeyCodes: Boolean = true,
    val onboardingDone: Boolean = false,
    /** 用户表达的全局旋转意图，进程重启后用它重建，而不是沿用旧的系统缓存值（PRD 28）。 */
    val globalRotationMode: RotationMode = RotationMode.NORMAL,
    val appRulesEnabled: Boolean = true,
    /**
     * 屏幕常亮的意图。
     *
     * 唤醒锁随进程消失，所以重启后必须靠它重建 —— 否则用户上次开着的常亮
     * 会在某次后台回收之后悄悄失效，而界面上什么都不会说。
     */
    val screenAwake: Boolean = false,
    /**
     * 无障碍开关被 ROM 关掉后自动写回。
     *
     * 默认关闭：这是应用主动改系统设置，必须由用户明确同意一次。
     */
    val autoHealAccessibility: Boolean = false,
    /**
     * 用户是否曾经真的开启过无障碍服务。
     *
     * 自愈的前提。没有它，自愈就成了「替一个从未授权的用户打开无障碍」——
     * 那是越权，不是恢复。
     */
    val accessibilityEverEnabled: Boolean = false,
    /**
     * 常驻前台服务。
     *
     * 默认关闭：它换来的是通知栏里一条撤不掉的通知，那是用户必须自己同意一次的代价。
     * 开启后进程不再算作后台，`bgLimit_level_thermal` 那类清理挑不到它 ——
     * 这是应用层唯一能改变「被系统杀掉」这个结论的开关。
     */
    val keepAlive: Boolean = false,
    /**
     * 全局暂停。
     *
     * 开着的时候这个应用什么都不做：不收按键、不认前台应用、不写旋转、
     * 不采体征、不自愈、不常驻前台服务。唯一的例外是屏幕常亮：它不依赖键盘，照 [screenAwake] 保持。进程还在（无障碍服务由系统绑定，
     * 应用自己摘不掉），但它是一个空转的壳 —— 这是不去系统设置里关掉无障碍、
     * 就能把占用降到最低的唯一办法。
     *
     * 不持久化「暂停期间各项功能原本是什么样」：恢复时一律按 [screenAwake]、
     * [globalRotationMode]、[keepAlive] 这些既有意图重建，它们本来就是真相。
     */
    val paused: Boolean = false,
    /**
     * 没有实体键盘时自动暂停。
     *
     * 这个应用存在的前提就是有一块键盘接着。键盘一拔，快捷键、键位映射、按键监视
     * 全都失去对象，剩下的只有一个还在被系统唤醒的进程 —— 而它恰恰是靠无障碍服务
     * 常驻的，用户自己关不掉。这个开关把「没有键盘」这件事直接接到 [paused] 上。
     *
     * 默认关闭：它会连旋转规则一起停掉，而旋转规则本来不需要键盘（屏幕常亮不受暂停影响）。
     * 这个取舍必须由用户自己做一次。
     */
    val autoPauseWithoutKeyboard: Boolean = false,
    /**
     * 已经写进日志的最后一条系统退出记录的时间戳。
     *
     * 系统保留的是一份历史列表，不去重就会每次启动都把同样几条崩溃重刷一遍。
     */
    val lastReportedExitMs: Long = 0L,
) {
    enum class Theme(val label: String) { LIGHT("浅色"), DARK("深色"), SYSTEM("跟随系统") }
    enum class Language { CHINESE, ENGLISH }
}

class SettingsRepository(
    private val appStore: AppStore,
    scope: CoroutineScope,
) {
    private companion object {
        const val KEY = "settings"
    }

    private val _loaded = MutableStateFlow(false)

    /**
     * 盘上的设置有没有读出来。
     *
     * [settings] 在这之前给的是默认值，而默认值里 onboardingDone = false ——
     * 界面若不等这一下，每次冷启动都会先闪一下引导页。
     */
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    val settings: StateFlow<UserSettings> = appStore.stringFlow(KEY, "{}")
        .map { raw ->
            runCatching { appStore.json.decodeFromString(UserSettings.serializer(), raw) }
                .getOrDefault(UserSettings())
        }
        .onEach { _loaded.value = true }
        .stateIn(scope, SharingStarted.Eagerly, UserSettings())

    /**
     * 直接从盘上读一次。
     *
     * [settings] 是 Eagerly 的 StateFlow，进程刚起来时它的 value 还是默认值 ——
     * 开机自愈恰好跑在那个窗口里，把「用户没开自愈」和「还没读出来」搞混就会静默不作为。
     */
    suspend fun load(): UserSettings = appStore.stringFlow(KEY, "{}").first().let { raw ->
        runCatching { appStore.json.decodeFromString(UserSettings.serializer(), raw) }
            .getOrDefault(UserSettings())
    }

    /** 原子读改写：基于盘上的当前值，而不是内存里可能还没填充的 [settings]。 */
    suspend fun update(transform: (UserSettings) -> UserSettings) {
        appStore.updateJson(KEY, UserSettings.serializer(), UserSettings(), transform)
    }
}

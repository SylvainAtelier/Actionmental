package com.actionmental.core.action

import android.view.KeyEvent
import com.actionmental.core.rotation.RotationMode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 动作分类，只用于 UI 筛选与配色。 */
enum class ActionCategory(val label: String, val code: String) {
    ROTATION("旋转", "ROTATION"),
    APP("应用", "APP"),
    WEB("网页", "WEB"),
    SYSTEM("系统", "SYSTEM"),
    AWAKE("屏幕常亮", "AWAKE"),
    MEDIA("媒体", "MEDIA"),
    VOLUME("音量", "VOLUME"),
    DEBUG("调试", "DEBUG"),
    SHELL("Shell", "SHELL"),
}

/**
 * 快捷键可以执行的动作。
 *
 * 扩展一种动作 = 增加一个子类 + 在 ActionExecutor 里加一个分支，
 * 键盘链路（管线 / 匹配器 / 仓库）完全不需要改动（PRD 39）。
 */
@Serializable
sealed interface Action {
    val category: ActionCategory
    val label: String
    val technical: String

    /** 是否需要 Shizuku 特权。UI 据此显示「不可用」而不是静默失败。 */
    val requiresPrivilege: Boolean get() = false

    /** 列表与编辑页的第二行：动作的具体目标，没有就返回 null。 */
    val detail: String? get() = null

    @Serializable
    @SerialName("nav")
    data class Navigation(val target: Target) : Action {
        enum class Target(val label: String, val code: String) {
            BACK("返回", "NAV_BACK"),
            HOME("主页", "NAV_HOME"),
            RECENTS("最近任务", "NAV_RECENTS"),
            NOTIFICATIONS("通知栏", "NAV_NOTIFICATIONS"),
            QUICK_SETTINGS("快捷设置", "NAV_QUICK_SETTINGS"),
            LOCK_SCREEN("锁屏", "NAV_LOCK"),
        }

        override val category get() = ActionCategory.SYSTEM
        override val label get() = target.label
        override val technical get() = target.code
    }

    @Serializable
    @SerialName("media")
    data class Media(val target: Target) : Action {
        enum class Target(val label: String, val code: String, val keyCode: Int) {
            PLAY_PAUSE("播放 / 暂停", "MEDIA_PLAY_PAUSE", KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE),
            NEXT("下一曲", "MEDIA_NEXT", KeyEvent.KEYCODE_MEDIA_NEXT),
            PREVIOUS("上一曲", "MEDIA_PREVIOUS", KeyEvent.KEYCODE_MEDIA_PREVIOUS),
        }

        override val category get() = ActionCategory.MEDIA
        override val label get() = target.label
        override val technical get() = target.code
    }

    @Serializable
    @SerialName("volume")
    data class Volume(val target: Target) : Action {
        enum class Target(val label: String, val code: String) {
            UP("音量增加", "VOLUME_UP"),
            DOWN("音量降低", "VOLUME_DOWN"),
            MUTE("静音切换", "VOLUME_MUTE"),
        }

        override val category get() = ActionCategory.VOLUME
        override val label get() = target.label
        override val technical get() = target.code
    }

    /**
     * 启动应用。
     *
     * [activity] 为空表示走系统的默认启动入口；指定后直达该 Activity，
     * 未导出的 Activity 由 ActionExecutor 退回 Shizuku 的 `am start` 执行。
     */
    @Serializable
    @SerialName("launch")
    data class LaunchApp(
        val packageName: String,
        val activity: String? = null,
        val appLabel: String = packageName,
        val activityLabel: String = "",
    ) : Action {
        val isDefaultEntry: Boolean get() = activity.isNullOrBlank()

        override val category get() = ActionCategory.APP
        override val label get() = "启动应用 · " + appLabel
        override val technical
            get() = "LAUNCH_APP · " + packageName + (activity?.let { "/" + it.substringAfterLast('.') } ?: "")
        override val detail
            get() = if (isDefaultEntry) appLabel
            else appLabel + " · " + activityLabel.ifBlank { activity!!.substringAfterLast('.') }
    }

    /** 打开网页链接。交给系统默认浏览器，不申请网络权限。 */
    @Serializable
    @SerialName("url")
    data class OpenUrl(val url: String, val title: String = "") : Action {
        override val category get() = ActionCategory.WEB
        override val label get() = "打开链接 · " + title.ifBlank { url }
        override val technical get() = "OPEN_URL"
        override val detail get() = url
    }

    @Serializable
    @SerialName("rotation")
    data class Rotation(val op: Op, val mode: RotationMode = RotationMode.NORMAL) : Action {
        enum class Op { SET, RESTORE, TOGGLE_LANDSCAPE, CYCLE }

        override val category get() = ActionCategory.ROTATION

        // 不再标成必需 Shizuku：没有它时降级到无障碍悬浮层 / 系统设置，
        // 快捷键能触发就意味着无障碍服务在，悬浮层那一级总是够得着
        override val label
            get() = when (op) {
                Op.SET -> "切换 · " + mode.label
                Op.RESTORE -> "恢复系统默认旋转"
                Op.TOGGLE_LANDSCAPE -> "切换强制横屏"
                Op.CYCLE -> "循环旋转模式"
            }
        override val technical
            get() = when (op) {
                Op.SET -> "ROTATION_TOGGLE · " + mode.technical
                Op.RESTORE -> "ROTATION_RESTORE"
                Op.TOGGLE_LANDSCAPE -> "ROTATION_TOGGLE_LANDSCAPE"
                Op.CYCLE -> "ROTATION_CYCLE"
            }
        override val detail
            get() = when (op) {
                Op.SET -> "再次按下回到自动旋转关闭"
                Op.RESTORE -> "交还 Android 旋转策略"
                Op.TOGGLE_LANDSCAPE -> "90° 横屏 ←→ 系统默认"
                Op.CYCLE -> "系统默认 → 90° → 0° → 系统默认"
            }
    }

    /**
     * 屏幕常亮。
     *
     * 和旋转一样只表达意图，真实状态由 ScreenAwakeController 回读；
     * 它走的是普通的 PowerManager 唤醒锁，所以不需要 Shizuku。
     */
    @Serializable
    @SerialName("awake")
    data class Awake(val op: Op) : Action {
        enum class Op(val label: String, val code: String, val hint: String) {
            ON("开启屏幕常亮", "AWAKE_ON", "屏幕不再自动熄灭"),
            OFF("关闭屏幕常亮", "AWAKE_OFF", "交还系统的自动熄屏"),
            TOGGLE("切换屏幕常亮", "AWAKE_TOGGLE", "按当前真实状态取反"),
        }

        override val category get() = ActionCategory.AWAKE
        override val label get() = op.label
        override val technical get() = op.code
        override val detail get() = op.hint
    }

    /**
     * 把无线调试的连接地址复制到剪贴板，省得每次去开发者选项里抄。
     *
     * IP 取 Wi-Fi 网卡，任何时候都读得到；端口在 `service.adb.tls.port` 里，
     * 普通应用多半读不到，这时退回 Shizuku 的 getprop —— 所以不标必需特权。
     */
    @Serializable
    @SerialName("adb_wifi")
    data class WirelessDebug(val target: Target) : Action {
        enum class Target(val label: String, val code: String, val hint: String) {
            ADDRESS("复制无线调试地址", "ADB_WIFI_ADDRESS", "IP:端口，可直接 adb connect"),
            IP("复制本机 IP", "ADB_WIFI_IP", "Wi-Fi 网卡的 IPv4 地址"),
            PORT("复制无线调试端口", "ADB_WIFI_PORT", "只复制端口号"),
        }

        override val category get() = ActionCategory.DEBUG
        override val label get() = target.label
        override val technical get() = target.code
        override val detail get() = target.hint
    }

    /** 高级动作：必须由用户逐条明确配置命令内容（PRD 26）。 */
    @Serializable
    @SerialName("shell")
    data class Shell(val command: String, val title: String = "") : Action {
        override val category get() = ActionCategory.SHELL
        override val requiresPrivilege get() = true
        override val label get() = "Shell · " + title.ifBlank { command }
        override val technical get() = command
        override val detail get() = command
    }
}

/**
 * 动作选择器的目录。
 *
 * 分组即二级菜单的一级项：一级只有七条，二级才是具体动作，
 * 于是「有哪些能力」和「具体选哪个」在视觉上彻底分开。
 * [Group.direct] 的三条没有固定清单（要选应用、填链接、写命令），点一级就进各自的编辑流程。
 */
object ActionCatalog {

    data class Group(
        val id: String,
        val label: String,
        val technical: String,
        val hint: String,
        val actions: List<Action> = emptyList(),
        val direct: Boolean = false,
    )

    /** 屏幕方向：四个角度 + 三个切换动作，全部收在一个二级菜单里。 */
    val rotation: List<Action> = buildList {
        RotationMode.forced.forEach { add(Action.Rotation(Action.Rotation.Op.SET, it)) }
        add(Action.Rotation(Action.Rotation.Op.TOGGLE_LANDSCAPE))
        add(Action.Rotation(Action.Rotation.Op.CYCLE))
        add(Action.Rotation(Action.Rotation.Op.RESTORE))
    }

    /** 屏幕常亮：开、关、切换。三条都不需要特权。 */
    val awake: List<Action> = Action.Awake.Op.entries.map { Action.Awake(it) }

    const val GROUP_APP = "app"
    const val GROUP_URL = "url"
    const val GROUP_SHELL = "shell"

    val groups: List<Group> = listOf(
        Group("rotation", "屏幕方向", "ROTATION", "角度、切换与循环", rotation),
        Group("awake", "屏幕常亮", "KEEP AWAKE", "让屏幕不自动熄灭", awake),
        Group(
            "system", "系统导航", "SYSTEM", "返回、主页、通知栏…",
            Action.Navigation.Target.entries.map { Action.Navigation(it) },
        ),
        Group(
            "media", "媒体", "MEDIA", "播放控制",
            Action.Media.Target.entries.map { Action.Media(it) },
        ),
        Group(
            "volume", "音量", "VOLUME", "加、减、静音",
            Action.Volume.Target.entries.map { Action.Volume(it) },
        ),
        Group(
            "adb_wifi", "无线调试", "WIRELESS DEBUG", "复制 IP、端口到剪贴板",
            Action.WirelessDebug.Target.entries.map { Action.WirelessDebug(it) },
        ),
        Group(GROUP_APP, "启动应用", "LAUNCH APP", "先选应用，再选入口", direct = true),
        Group(GROUP_URL, "打开链接", "OPEN URL", "用默认浏览器打开网址", direct = true),
        Group(GROUP_SHELL, "Shell 命令", "SHELL", "逐条明确配置，不做通用包装", direct = true),
    )

    /** 有固定清单的内置动作，引导页与测试用。 */
    val builtIn: List<Action> = groups.flatMap { it.actions }
}

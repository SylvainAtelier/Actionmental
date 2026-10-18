package com.actionmental.core.action

import android.view.KeyEvent
import com.actionmental.core.key.KeyCombo
import com.actionmental.core.rotation.RotationMode
import com.actionmental.core.sync.SyncConfig
import com.actionmental.core.sync.SyncDirection
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
    CLIPBOARD("剪贴板", "CLIPBOARD"),
    SHELL("Shell", "SHELL"),
    TERMUX("Termux", "TERMUX"),
    SYNC("同步", "SYNC"),
    INPUT("输入", "INPUT"),
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

    /**
     * 让 adbd 在一个固定的 TCP 端口上监听，等同电脑上的 `adb tcpip <端口>`；
     * [enable] 为假时等同 `adb usb`，把这个端口收回去。
     *
     * 无线调试的端口每次开关都会变，远程脚本没法写死；这个端口不变，`adb connect IP:端口` 一直有效，
     * 直到重启手机。shell 身份改不了 adbd 的端口属性，所以平时是本应用自己当一次 adb 客户端，
     * 经无线调试向 adbd 发同一条请求 —— 首次要配对一次，由 Shizuku 代为完成；Shizuku 以 root 运行时直接改属性。
     * 不标必需特权：配对过之后，没有 Shizuku 也能执行。
     */
    @Serializable
    @SerialName("adb_tcpip")
    data class AdbTcpip(
        val port: Int = DEFAULT_PORT,
        val enable: Boolean = true,
        /** 成功后把 `IP:端口` 写进剪贴板，好直接贴到电脑上的 adb connect 后面。 */
        val copyAddress: Boolean = true,
    ) : Action {
        override val category get() = ActionCategory.DEBUG
        // 端口跟着「端口」二字：界面在中文下会丢掉 ` · ` 后面不含汉字的那一段
        override val label get() = if (enable) "开启 ADB 网络调试 · 端口 " + port else "关闭 ADB 网络调试"
        override val technical get() = if (enable) "ADB_TCPIP · " + port else "ADB_USB"
        override val detail
            get() = if (enable) "adb connect <IP>:" + port else "收回 TCP 端口 · 等同 adb usb"

        companion object {
            const val DEFAULT_PORT = 6677

            /** 1024 以下是特权端口，adbd 以 shell 身份绑不上。 */
            val PORT_RANGE = 1024..65535

            /** 输入框里的原文 → 端口；不是数字或越界就是 null。 */
            fun parsePort(raw: String): Int? = raw.trim().toIntOrNull()?.takeIf { it in PORT_RANGE }
        }
    }

    /**
     * 打开 / 关闭剪贴板历史面板。
     *
     * 面板浮在当前应用上面但不抢焦点，选中的条目经无障碍输入通道直接写进原来的输入框。
     * 记录本身要 Shizuku（只有 shell 身份能在后台读剪贴板），面板不要：没有 Shizuku 时
     * 照样能翻看已有的、置顶的条目。
     */
    @Serializable
    @SerialName("clip_history")
    data object ClipboardHistory : Action {
        override val category get() = ActionCategory.CLIPBOARD
        override val label get() = "剪贴板历史"
        override val technical get() = "CLIPBOARD_HISTORY"
        override val detail get() = "搜索、置顶、选中即输入"
    }

    /**
     * 唤起输入法的语音输入。
     *
     * Gboard、微信、搜狗这类把语音做在键盘里的，去输入法窗口里找语音键点下去；
     * Google 语音输入这类专职输入法，切过去就开始听，再按一次切回原来的输入法。
     * [Mode.AUTO] 先点键、点不到再切。都不需要 Shizuku（Android 10 上切输入法除外）。
     */
    @Serializable
    @SerialName("voice_input")
    data class VoiceInput(val mode: Mode = Mode.AUTO) : Action {
        enum class Mode(val label: String, val code: String, val hint: String) {
            AUTO("语音输入", "VOICE_INPUT", "先点输入法的语音键，没有就切到语音输入法"),
            KEYBOARD("点按输入法语音键", "VOICE_INPUT_KEY", "Gboard、微信、搜狗、讯飞等键盘上的麦克风"),
            VOICE_IME("切换语音输入法", "VOICE_INPUT_IME", "如 Google 语音输入 · 再按一次切回原输入法"),
        }

        override val category get() = ActionCategory.INPUT
        override val label get() = mode.label
        override val technical get() = mode.code
        override val detail get() = mode.hint
    }

    /**
     * 以键盘的身份发出一个组合键，修饰键、主键逐颗按下再倒序抬起，和手按的一样。
     *
     * 主要给输入法的快捷键用：Gboard 接实体键盘时 Alt + D 开语音，换成别的键按就得经这里转发。
     * 发出去的键沿用触发它的那把键盘的设备号 —— 输入法的快捷键只认实体键盘。需要 Shizuku。
     */
    @Serializable
    @SerialName("send_key")
    data class SendKey(val combo: KeyCombo, val title: String = "") : Action {
        override val category get() = ActionCategory.INPUT
        override val label get() = title.ifBlank { "发送 " + combo }
        override val technical get() = "SEND_KEY"
        override val requiresPrivilege get() = true
        override val detail get() = combo.toString() + " · 以实体键盘的身份发出"

        companion object {
            /** Gboard 接实体键盘时的语音输入快捷键。 */
            val GBOARD_VOICE = SendKey(KeyCombo(KeyEvent.KEYCODE_D, KeyCombo.MOD_ALT), "Gboard 语音输入")
        }
    }

    /**
     * 在 Termux 里执行一条命令（经 Termux 的 RUN_COMMAND 接口）。
     *
     * 和 [Shell] 是两回事：那条走 Shizuku、以 shell 用户身份跑，进不了 Termux 的私有目录；
     * 这条交给 Termux 自己执行，`pkg` 装的工具、`~/` 下的脚本都能用，也不需要 Shizuku。
     * [background] 为真时静默执行，跑完把输出回传、发一条结果通知；
     * 为假时在 Termux 里新开一个终端会话，结果就在屏幕上，不再发通知。
     */
    @Serializable
    @SerialName("termux")
    data class Termux(
        val command: String,
        val title: String = "",
        val background: Boolean = true,
    ) : Action {
        val displayName: String get() = title.ifBlank { command }

        override val category get() = ActionCategory.TERMUX
        override val label get() = "Termux · " + displayName
        override val technical get() = if (background) "TERMUX_RUN" else "TERMUX_SESSION"
        override val detail get() = command
    }

    /**
     * 本地文件夹与 NAS（SMB）共享目录的同步。
     *
     * 整份配置就在动作里，没有独立的「同步任务」实体：快捷键就是任务。
     * 方向、同名文件怎么处理、要不要同步删除都是这条动作自己的参数；密码不在这里，见 SyncCredentials。
     */
    @Serializable
    @SerialName("sync")
    data class FolderSync(val config: SyncConfig) : Action {
        override val category get() = ActionCategory.SYNC
        override val label get() = "同步 · " + config.displayName
        override val technical get() = config.direction.code
        override val detail
            get() = config.localPath.trimEnd('/').substringAfterLast('/') + " " + when (config.direction) {
                SyncDirection.UPLOAD -> "→"
                SyncDirection.DOWNLOAD -> "←"
                SyncDirection.BOTH -> "⇄"
            } + " " + config.remoteLabel
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
    const val GROUP_TERMUX = "termux"
    const val GROUP_SYNC = "sync"
    const val GROUP_ADB_TCPIP = "adb_tcpip"
    const val GROUP_SEND_KEY = "send_key"

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
        Group(GROUP_ADB_TCPIP, "ADB 网络调试", "ADB TCPIP", "固定端口，电脑上 adb connect 直连", direct = true),
        Group("clipboard", "剪贴板", "CLIPBOARD", "历史记录面板", listOf(Action.ClipboardHistory)),
        Group(
            "voice_input", "语音输入", "VOICE INPUT", "唤起 Gboard、微信等输入法的语音",
            Action.VoiceInput.Mode.entries.map { Action.VoiceInput(it) } + Action.SendKey.GBOARD_VOICE,
        ),
        Group(GROUP_SEND_KEY, "发送组合键", "SEND KEYS", "转发成另一个组合键，如 Gboard 的 Alt + D", direct = true),
        Group(GROUP_APP, "启动应用", "LAUNCH APP", "先选应用，再选入口", direct = true),
        Group(GROUP_URL, "打开链接", "OPEN URL", "用默认浏览器打开网址", direct = true),
        Group(GROUP_SYNC, "文件夹同步", "FOLDER SYNC", "本地文件夹与 NAS（SMB）互相同步", direct = true),
        Group(GROUP_TERMUX, "Termux 命令", "TERMUX", "在 Termux 里执行，完成后通知结果", direct = true),
        Group(GROUP_SHELL, "Shell 命令", "SHELL", "逐条明确配置，不做通用包装", direct = true),
    )

    /** 有固定清单的内置动作，引导页与测试用。 */
    val builtIn: List<Action> = groups.flatMap { it.actions }
}

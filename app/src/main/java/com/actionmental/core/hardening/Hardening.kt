package com.actionmental.core.hardening

import kotlinx.serialization.Serializable

/**
 * 后台加固的一项。
 *
 * 命令写在模型里而不是藏在实现里，是因为这三条会实实在在改动系统状态 ——
 * 用户有权在按下按钮之前看见「到底要执行什么、为什么」（PRD 25 的同一条精神）。
 */
enum class HardeningStep(val title: String, val technical: String, val why: String) {
    DOZE_WHITELIST(
        "Doze 白名单",
        "dumpsys deviceidle whitelist",
        "待机后系统会冻结后台协程，按键注入队列会跟着停摆。",
    ),
    RUN_IN_BACKGROUND(
        "后台运行许可",
        "appops RUN_IN_BACKGROUND",
        "被限制时进程还在，却拿不到 CPU，表现为「按键有时不响应」。",
    ),
    RUN_ANY_IN_BACKGROUND(
        "后台任意运行许可",
        "appops RUN_ANY_IN_BACKGROUND",
        "部分 ROM 只认这一条，要和上一条一起给。",
    ),
}

/**
 * 加固项的真实系统状态。
 *
 * 三个字段都是可空的三态：null 表示「还没查」或「查不到」，
 * 不能和 false（查到了、确实没生效）混为一谈 —— 这和 [com.actionmental.core.rotation.RotationState]
 * 区分「不可用」与「已关闭」是同一个理由。
 */
data class HardeningState(
    val checkedAtMs: Long = 0L,
    val dozeWhitelisted: Boolean? = null,
    val runInBackground: Boolean? = null,
    val runAnyInBackground: Boolean? = null,
) {
    val checked: Boolean get() = checkedAtMs > 0L

    fun valueOf(step: HardeningStep): Boolean? = when (step) {
        HardeningStep.DOZE_WHITELIST -> dozeWhitelisted
        HardeningStep.RUN_IN_BACKGROUND -> runInBackground
        HardeningStep.RUN_ANY_IN_BACKGROUND -> runAnyInBackground
    }

    /** 三项全部确认生效。任何一项是 null 都不算数。 */
    val hardened: Boolean
        get() = dozeWhitelisted == true && runInBackground == true && runAnyInBackground == true
}

/**
 * 一条加固 / 自愈留痕。
 *
 * 这个日志和 [com.actionmental.core.diag.ShellLog] 的分工是刻意的：
 * ShellLog 是内存环形缓冲，回答「刚才那条命令回了什么」；
 * 这里落盘，回答「三天前是谁把无障碍开关又打开了」—— 后者必须跨进程重启存在，
 * 因为自愈恰恰发生在用户不看屏幕的时候。
 */
@Serializable
data class HardeningRecord(
    val id: Long,
    val timestampMs: Long,
    val kind: Kind,
    /** 步骤名。存中文源串，显示时统一过 i18n，避免把语言固化进历史。 */
    val step: String,
    val ok: Boolean,
    val detail: String = "",
) {
    @Serializable
    enum class Kind(val label: String) {
        HARDEN("后台加固"),
        HEAL("无障碍自愈"),
    }
}

/** 留痕出口。核心层只认这个，落盘实现在 data 层。 */
fun interface HardeningJournal {
    suspend fun append(kind: HardeningRecord.Kind, step: String, ok: Boolean, detail: String)
}

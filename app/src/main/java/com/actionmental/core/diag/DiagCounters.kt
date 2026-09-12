package com.actionmental.core.diag

import java.util.concurrent.atomic.AtomicLong

/**
 * 「这一段时间里，进程到底忙了些什么」。
 *
 * 每一件事单独记一条日志是不行的：切几次应用就是上百条窗口事件，打一行字就是
 * 几十次按键 —— 写日志本身会变成新的开销，而这个应用恰恰是被温控杀掉的。
 * 所以现场只做一次原子自增（纳秒级），攒到体征采样那一刻一并写出去。
 *
 * 这几个数回答的都是同一个问题的不同侧面：一次「被杀」之前，是谁在烧 CPU。
 */
class DiagCounters {

    /** 特权 shell 调用。每一次都要在特权进程里 fork 一个 `sh`，是最贵的一项。 */
    val shell = AtomicLong(0)

    /** 前台窗口变化。切应用的频率。 */
    val windowChange = AtomicLong(0)

    /** 经过管线的实体按键事件（不含内容，只有次数）。 */
    val keyEvent = AtomicLong(0)

    /** 真正执行掉的动作。 */
    val action = AtomicLong(0)

    /** 旋转真的写下去的次数 —— 与「被要求设置旋转」的次数分开，去重效果就看这个。 */
    val rotationWrite = AtomicLong(0)

    /** 因为「系统已经是这个模式」而被省掉的旋转写入。 */
    val rotationSkipped = AtomicLong(0)

    /** 这一段里有没有发生过什么。只看不取，用来决定下一次采样隔多久。 */
    fun pending(): Boolean =
        shell.get() > 0L || windowChange.get() > 0L || keyEvent.get() > 0L || action.get() > 0L

    /** 取走并清零。写一行日志就是一个统计周期。 */
    fun drain(): Snapshot = Snapshot(
        shell = shell.getAndSet(0),
        windowChange = windowChange.getAndSet(0),
        keyEvent = keyEvent.getAndSet(0),
        action = action.getAndSet(0),
        rotationWrite = rotationWrite.getAndSet(0),
        rotationSkipped = rotationSkipped.getAndSet(0),
    )

    data class Snapshot(
        val shell: Long,
        val windowChange: Long,
        val keyEvent: Long,
        val action: Long,
        val rotationWrite: Long,
        val rotationSkipped: Long,
    ) {
        val idle: Boolean
            get() = shell == 0L && windowChange == 0L && keyEvent == 0L &&
                action == 0L && rotationWrite == 0L

        /** 一行摘要。零的项不打印，免得把一行日志撑成一串零。 */
        fun format(): String {
            val parts = buildList {
                if (shell > 0) add("shell=" + shell)
                if (windowChange > 0) add("切窗口=" + windowChange)
                if (keyEvent > 0) add("按键=" + keyEvent)
                if (action > 0) add("动作=" + action)
                if (rotationWrite > 0) add("旋转写入=" + rotationWrite)
                if (rotationSkipped > 0) add("旋转免写=" + rotationSkipped)
            }
            return if (parts.isEmpty()) "无活动" else parts.joinToString(" ")
        }
    }
}

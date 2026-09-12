package com.actionmental.core.diag

import android.content.Context
import android.os.PowerManager
import java.util.concurrent.Executor

/**
 * 机身温控档位。
 *
 * 这个应用被杀掉的每一次，系统写下的死因都是同一句：
 * `bgLimit_level_thermal_10` —— 温控升到 10 级之后的清理。也就是说，
 * 「什么时候会死」这件事的自变量根本不在应用里，而在这块屏幕的温度上。
 * 在此之前，日志里从来没有过这个量：只看得到死亡，看不到升温。
 *
 * 监听是系统回调驱动的，不轮询，档位不变时一次都不会醒。
 */
class ThermalWatch(
    context: Context,
    private val log: EventLog,
    /**
     * 档位变化时叫一声。
     *
     * 「机器开始热了」是这个进程一生中最该瘦下来的时刻 —— 厂商的清理策略正是在
     * 这个区间开始挑目标，而挑的时候看的是当下的样子，不是平均值。
     */
    private val onChanged: (level: Int) -> Unit = {},
) {
    private val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager

    @Volatile
    var status: Int = PowerManager.THERMAL_STATUS_NONE
        private set

    /** 档位的中文名。写进日志的是它，不是一个裸数字。 */
    val statusLabel: String get() = labelOf(status)

    private val listener = PowerManager.OnThermalStatusChangedListener { level ->
        val previous = status
        status = level
        if (level == previous) return@OnThermalStatusChangedListener
        val message = "温控档位 · " + labelOf(previous) + " → " + labelOf(level)
        // 中等以上就是会开始收拾进程的区间，必须比普通信息更显眼
        if (level >= PowerManager.THERMAL_STATUS_MODERATE) {
            log.warn(TAG, message, "level=" + level + " · 系统在这个区间开始限制后台与前台服务")
        } else {
            log.info(TAG, message, "level=" + level)
        }
        // 先记录升温，再让上层去应对：日志读下来才是「先热，后瘦」这个因果顺序
        onChanged(level)
    }

    fun start(executor: Executor) {
        val pm = power ?: run {
            log.debug(TAG, "系统不提供温控状态")
            return
        }
        runCatching {
            status = pm.currentThermalStatus
            pm.addThermalStatusListener(executor, listener)
        }.onFailure {
            log.warn(TAG, "温控监听注册失败", it.message.orEmpty())
        }
        log.info(TAG, "温控档位 · " + statusLabel, "level=" + status)
    }

    private companion object {
        const val TAG = "thermal"

        fun labelOf(level: Int): String = when (level) {
            PowerManager.THERMAL_STATUS_NONE -> "正常"
            PowerManager.THERMAL_STATUS_LIGHT -> "轻度"
            PowerManager.THERMAL_STATUS_MODERATE -> "中等"
            PowerManager.THERMAL_STATUS_SEVERE -> "严重"
            PowerManager.THERMAL_STATUS_CRITICAL -> "危急"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "紧急"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "即将关机"
            else -> "未知(" + level + ")"
        }
    }
}

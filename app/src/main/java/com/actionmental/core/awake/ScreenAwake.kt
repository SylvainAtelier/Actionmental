package com.actionmental.core.awake

import com.actionmental.core.action.ActionResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 屏幕常亮的系统事实。
 *
 * 和旋转一样，[on] 从来不是「我点过开关」，而是每次写入之后重新问系统拿回来的值。
 */
data class ScreenAwakeState(
    val on: Boolean = false,
    /** 这台设备上拿不拿得到屏幕唤醒锁。拿不到就显示「不可用」，而不是「已关闭」。 */
    val supported: Boolean = true,
    val failure: String? = null,
    val verifiedAtMs: Long = 0L,
) {
    val available: Boolean get() = supported && failure == null

    val label: String get() = when {
        !supported -> "不可用"
        on -> "常亮中"
        else -> "系统自动熄屏"
    }

    val technical: String get() = if (on) "WAKE_LOCK · HELD" else "WAKE_LOCK · RELEASED"
}

/**
 * 唤醒锁的开关本体。
 *
 * 抽成接口只为一件事：控制器的「意图 / 事实」逻辑要能脱离 Android 单测。
 */
interface ScreenAwakeSwitch {

    /** 这台设备能否申请屏幕唤醒锁。 */
    val supported: Boolean

    fun acquire(): Result<Unit>

    fun release(): Result<Unit>

    /** 系统事实：锁现在是不是真的握在手里。 */
    fun isHeld(): Boolean
}

/**
 * 全应用唯一的屏幕常亮入口。
 *
 * 沿用 [com.actionmental.core.rotation.RotationController] 的那条规矩：
 * 意图（用户开没开）持久化在设置里，事实（锁握没握住）每次都回读，两者分开存。
 * 于是「点了但系统没给」会如实显示成失败，而不是界面上亮着、屏幕照样熄。
 *
 * 常亮靠的是进程活着 —— 无障碍服务由系统绑定，这个进程本来就常驻，
 * 所以这里不需要额外的前台服务，也不占任何特权（PRD 3.4：能用普通 API 就不上 Shizuku）。
 */
class ScreenAwakeController(
    private val switch: ScreenAwakeSwitch,
    private val persistIntent: suspend (Boolean) -> Unit,
) {
    private val mutex = Mutex()

    private val _state = MutableStateFlow(
        ScreenAwakeState(
            on = switch.isHeld(),
            supported = switch.supported,
            failure = if (switch.supported) null else "系统未提供屏幕唤醒锁",
        )
    )
    val state: StateFlow<ScreenAwakeState> = _state.asStateFlow()

    /** 用户 / 快捷键明确设成某个值，会被记住，进程重启后由 [restore] 重建。 */
    suspend fun set(on: Boolean): ActionResult {
        persistIntent(on)
        return apply(on)
    }

    /** 快捷键的「切换」。基于回读到的事实取反，不看本地 boolean。 */
    suspend fun toggle(): ActionResult = set(!refresh().on)

    /**
     * 进程重启后按用户上一次的意图重建，但不再写一遍设置。
     *
     * 只在意图为 true 时才有事可做：默认状态本来就是没有锁。
     */
    suspend fun restore(on: Boolean): ActionResult =
        if (on) apply(true) else ActionResult.OK

    /** 只读事实，不做任何写入。 */
    fun refresh(): ScreenAwakeState = readState().also { _state.value = it }

    /** 进程退出前把锁交回去，别让它靠 GC。 */
    fun releaseQuietly() {
        runCatching { switch.release() }
        _state.value = readState()
    }

    private suspend fun apply(on: Boolean): ActionResult = mutex.withLock {
        if (!switch.supported) {
            _state.value = ScreenAwakeState(
                on = false,
                supported = false,
                failure = "系统未提供屏幕唤醒锁",
                verifiedAtMs = System.currentTimeMillis(),
            )
            return@withLock ActionResult.Failed(ActionResult.Reason.UNSUPPORTED, "SCREEN_DIM_WAKE_LOCK")
        }

        val outcome = if (on) switch.acquire() else switch.release()

        // 写完必须回读：界面、磁贴、快捷键提示看到的都是刚查回来的同一份事实
        val fact = readState()
        _state.value = fact

        outcome.fold(
            onSuccess = {
                when {
                    fact.on == on && on -> ActionResult.Ok("屏幕常亮已开启")
                    fact.on == on -> ActionResult.Ok("屏幕常亮已关闭")
                    else -> ActionResult.Failed(
                        ActionResult.Reason.EXECUTION_FAILED,
                        "系统没有接受常亮请求",
                    )
                }
            },
            onFailure = {
                ActionResult.Failed(ActionResult.Reason.EXECUTION_FAILED, it.message.orEmpty())
            },
        )
    }

    private fun readState(): ScreenAwakeState = ScreenAwakeState(
        on = switch.supported && runCatching { switch.isHeld() }.getOrDefault(false),
        supported = switch.supported,
        failure = if (switch.supported) null else "系统未提供屏幕唤醒锁",
        verifiedAtMs = System.currentTimeMillis(),
    )
}

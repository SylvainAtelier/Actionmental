package com.actionmental.core.key

import com.actionmental.platform.PrivilegedBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 把一个组合键发回系统。键位映射唯一的输出口。
 *
 * 三条约束决定了这个形状：
 *  - 按键线程绝不能阻塞，所以入口是 [enqueue]，不是挂起函数；
 *  - 注入必须保序，所以消费端只有一个协程，不做并发；
 *  - 积压时宁可丢最旧的：迟到的按键比丢掉更糟。
 *
 * 失败不静默（PRD 25）：最近一次失败留在 [lastError] 里，映射页直接显示。
 */
class KeyInjector(
    scope: CoroutineScope,
    private val backend: () -> PrivilegedBackend,
    capacity: Int = 32,
) {
    /** [KeyCombo] 加一个「只发半边」的意图。null 表示按下 + 抬起成对发出。 */
    private data class Emission(val combo: KeyCombo, val down: Boolean?)

    private val queue = Channel<Emission>(capacity, BufferOverflow.DROP_OLDEST)

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _injected = MutableStateFlow(0L)

    /** 累计成功注入次数，映射页用它证明「链路确实通了」。 */
    val injected: StateFlow<Long> = _injected.asStateFlow()

    init {
        scope.launch(Dispatchers.IO) {
            for (emission in queue) send(emission)
        }
    }

    /** 成对发出一次按下 + 抬起。 */
    fun enqueue(combo: KeyCombo) {
        queue.trySend(Emission(combo, null))
    }

    /**
     * 只发半边，用于把一颗键整个替换掉：源键按下就发目标键按下，源键抬起才发抬起。
     * 修饰键必须这样发 —— 按住的那段时间正是它起作用的地方。
     */
    fun enqueueState(combo: KeyCombo, down: Boolean) {
        queue.trySend(Emission(combo, down))
    }

    /** 自检：直接注入一次并等结果，用于「链路是否可用」的一次性确认。 */
    suspend fun inject(combo: KeyCombo): Result<Unit> = send(Emission(combo, null))

    private suspend fun send(emission: Emission): Result<Unit> {
        val combo = emission.combo
        val result = when (val down = emission.down) {
            null -> backend().injectKey(combo.keyCode, combo.metaState())
            else -> backend().injectKeyState(combo.keyCode, combo.metaState(), down)
        }
        result.fold(
            onSuccess = {
                _lastError.value = null
                _injected.value = _injected.value + 1
            },
            onFailure = { _lastError.value = it.message ?: it.javaClass.simpleName },
        )
        return result
    }

    fun clearError() {
        _lastError.value = null
    }
}

package com.actionmental.core.key

import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

/**
 * 全应用唯一的实体键盘事件入口（PRD 3.1 / 35.3）。
 *
 * 归一化、按键状态跟踪、录制模式三件事在这里合成一个小状态机，
 * 而不是三个互相持有引用的对象——它们本来就共享同一份状态，拆开只会制造同步问题。
 *
 * 调用方只有一个：AccessibilityService。管线自身不做数据库访问，也不执行动作，
 * 只输出「这是一个标准化组合键」，由 [onTrigger] 决定后续。
 */
class KeyPipeline(
    private val traceCapacity: Int = 200,
) {
    /** 由 AppGraph 注入：返回 true 表示该组合键已匹配并消费。运行在按键线程，必须非阻塞。 */
    var onTrigger: ((KeyCombo, KeyboardDevice) -> Boolean)? = null

    /**
     * 第二个消费者：键位映射。只在没有快捷键命中时问它。
     *
     * 顺序是刻意的 —— 快捷键优先能保证「映射出来的键」不会再被当成快捷键触发，
     * 于是不存在 A→B→C 这种链式展开，也就不可能绕成环。
     *
     * 两个组合键都给出去：original 是手指真按下的，effective 是修饰位改写之后的。
     * 两者不同就说明有修饰键映射在起作用，即使没有任何绑定也得把 effective 发出去。
     */
    var onRemap: ((original: KeyCombo, effective: KeyCombo, device: KeyboardDevice) -> Boolean)? = null

    /**
     * 修饰位改写。由 AppGraph 注入，管线自己不知道「映射」是什么。
     * 入参是原组合与当前按住的所有键，返回系统应该看到的组合。
     */
    var rewriteCombo: ((KeyCombo, Set<Int>) -> KeyCombo)? = null

    /**
     * 整颗替换。
     *
     * 被映射的键连按下、连发、抬起一起被拦下，换成目标键的同一半事件 ——
     * 于是「短按左 Shift 切输入法」这种由**原键**触发的行为也一并消失：
     * 前台应用与输入法根本收不到那颗键。
     *
     * 返回 true 表示这颗键归映射管了。运行在按键线程，必须非阻塞。
     */
    var onReplacedKey: ((keyCode: Int, modifiers: Int, down: Boolean, device: KeyboardDevice) -> Boolean)? = null

    private val _snapshot = MutableStateFlow(KeyPressSnapshot())
    val snapshot: StateFlow<KeyPressSnapshot> = _snapshot.asStateFlow()

    private val _traces = MutableStateFlow<List<KeyTrace>>(emptyList())
    val traces: StateFlow<List<KeyTrace>> = _traces.asStateFlow()

    private val _recording = MutableStateFlow<Recording?>(null)
    val recording: StateFlow<Recording?> = _recording.asStateFlow()

    private val _lastDevice = MutableStateFlow(KeyboardDevice.UNKNOWN)
    val lastDevice: StateFlow<KeyboardDevice> = _lastDevice.asStateFlow()

    /**
     * 是否记录逐键事件流。默认关闭，只有按键监视页打开时才打开。
     *
     * 每一颗非修饰键的按下都会走到这里，正常打字也不例外 —— 常开的话，
     * 每敲一个字都要复制一遍上限 200 条的列表并唤醒一次 StateFlow，
     * 而这一切都发生在 onKeyEvent 所在的主线程上。按键回调慢下来，
     * 系统就会判定服务无响应并解绑它，症状正是「用着用着就未连接了」。
     * 匹配、映射失败这类低频事件不受这个开关约束，始终记录。
     */
    private val _tracing = MutableStateFlow(false)
    val tracing: StateFlow<Boolean> = _tracing.asStateFlow()

    fun setTracing(on: Boolean) {
        _tracing.value = on
        if (!on) _traces.value = emptyList()
    }

    /**
     * 全局暂停时的短路开关。
     *
     * 闸门放在管线的入口而不是各个回调里：按键事件只有这一条路进来，
     * 在这里返回 false 就保证了暂停期间没有任何一颗键被匹配、被改写、被拦下 ——
     * 用户按什么，前台应用就收到什么。
     *
     * @Volatile 是必须的：写它的是界面线程，读它的是按键回调所在的服务主线程。
     */
    @Volatile
    private var paused = false

    private val _pausedState = MutableStateFlow(false)
    val pausedState: StateFlow<Boolean> = _pausedState.asStateFlow()

    fun setPaused(on: Boolean) {
        if (paused == on) return
        paused = on
        _pausedState.value = on
        // 暂停那一刻手指可能正按着某颗被拦下的键。不清掉，恢复之后那颗键会一直「按着」
        if (on) reset()
    }

    /**
     * deviceId → 设备信息的缓存。
     *
     * [InputDevice.getDevice] 是一次到 system_server 的 binder 调用，
     * 原来每个按键事件都要走一趟。设备增删改时由 [invalidateDevices] 清掉即可。
     */
    private val deviceCache = java.util.concurrent.ConcurrentHashMap<Int, KeyboardDevice>()

    /** 键盘插拔或配置变化时调用，让下一次按键重新查一遍设备。 */
    fun invalidateDevices() = deviceCache.clear()

    private val traceIds = AtomicLong(0)
    private val consumedKeyCodes = mutableSetOf<Int>()

    /** 正在等待「轻点」判定的修饰键。只在按键线程上读写。 */
    private var tapCandidate: Int? = null

    /** 已经被映射拦下、正按着的键。长按重复时要接着补发，否则映射键没有连发。 */
    private val activeRemaps = mutableMapOf<Int, Pair<KeyCombo, KeyCombo>>()

    /** 正被整颗替换、还按着的键。抬起时要把目标键的抬起补上，否则目标键会卡住。 */
    private val replacedKeys = mutableSetOf<Int>()

    /** 录制中的临时结果。录制期间禁止执行任何已绑定动作（PRD 4.3）。 */
    data class Recording(val combo: KeyCombo? = null, val device: KeyboardDevice? = null)

    fun startRecording() {
        _recording.value = Recording()
    }

    fun cancelRecording() {
        _recording.value = null
    }

    /**
     * AccessibilityService 的唯一转发点。
     * @return true 表示事件被消费，不再下发给前台应用。
     */
    fun dispatch(event: KeyEvent): Boolean {
        if (paused) return false
        return dispatch(normalize(event))
    }

    internal fun dispatch(normalized: NormalizedKeyEvent): Boolean {
        if (paused) return false
        // 自己注入的按键原样放行：既不触发快捷键，也不再被映射改写一次
        if (normalized.virtual) {
            appendTrace(normalized)
            return false
        }

        _lastDevice.value = normalized.device
        updateSnapshot(normalized)
        appendTrace(normalized)

        if (normalized.keyCode in consumedKeyCodes) {
            if (!normalized.down) {
                consumedKeyCodes -= normalized.keyCode
                activeRemaps -= normalized.keyCode
            } else {
                // 长按连发：动作只该触发一次，映射却要跟着重复
                activeRemaps[normalized.keyCode]?.let { (original, effective) ->
                    onRemap?.invoke(original, effective, normalized.device)
                }
            }
            return true
        }

        // 整颗被替换掉的键：按下、连发、抬起统统换成目标键的同一半事件
        if (normalized.keyCode in replacedKeys) {
            if (!normalized.down) replacedKeys -= normalized.keyCode
            onReplacedKey?.invoke(
                normalized.keyCode,
                normalized.otherModifiers,
                normalized.down,
                normalized.device,
            )
            return true
        }
        if (normalized.down && normalized.repeatCount == 0) {
            val replaced = onReplacedKey?.invoke(
                normalized.keyCode,
                normalized.otherModifiers,
                true,
                normalized.device,
            ) ?: false
            if (replaced) {
                replacedKeys += normalized.keyCode
                tapCandidate = null
                return true
            }
        }

        // 普通主键，以及「已经压着别的修饰键」的修饰键：按下即触发并拦截
        val direct = normalized.asTrigger() ?: normalized.asModifierComboTrigger()
        if (direct != null) {
            tapCandidate = null
            return fire(direct, normalized, intercept = true)
        }

        if (normalized.isModifier) return dispatchModifier(normalized)

        // 长按重复、抬起等其余事件：只要有别的键动过，轻点就不再成立
        if (normalized.down) tapCandidate = null
        return false
    }

    /**
     * 修饰键单独绑定（Ctrl / Alt / Shift / Meta，左右分开）。
     *
     * 按下时无法判断用户接下来要不要组合，所以触发推迟到抬起，
     * 并且要求整个按下期间没有第二颗键参与 —— 这就是「轻点」。
     * 事件本身照常放行：按下已经交给了前台应用，再把抬起吞掉会让它以为修饰键卡住了。
     */
    private fun dispatchModifier(e: NormalizedKeyEvent): Boolean {
        if (e.down) {
            val alone = e.repeatCount == 0 && _snapshot.value.pressedKeyCodes.none { it != e.keyCode }
            tapCandidate = if (alone) e.keyCode else null
            return false
        }

        val tapped = tapCandidate == e.keyCode
        tapCandidate = null
        if (tapped) e.asTapTrigger()?.let { fire(it, e, intercept = false) }
        return false
    }

    /** @param intercept 命中后是否吞掉这颗键的事件序列。轻点绑定一律放行。 */
    private fun fire(combo: KeyCombo, e: NormalizedKeyEvent, intercept: Boolean): Boolean {
        val rec = _recording.value
        if (rec != null) {
            // 录制模式：吞掉事件，绝不触发原有绑定。录到的是手指真按下的组合，不做改写
            _recording.value = rec.copy(combo = combo, device = e.device)
            if (!intercept) return false
            consumedKeyCodes += e.keyCode
            return true
        }

        // 修饰键映射先生效：快捷键按改写后的组合匹配，
        // 于是「左 Shift 映射成 Alt」之后，Alt + X 的快捷键真的能被 左Shift + X 按出来
        val effective = rewriteCombo?.invoke(combo, _snapshot.value.pressedKeyCodes) ?: combo

        val matched = onTrigger?.invoke(effective, e.device) ?: false
        val remapped = !matched && (onRemap?.invoke(combo, effective, e.device) ?: false)
        if (!matched && !remapped) {
            // 没绑定的键是绝大多数：打字时每一个字母都走到这里。
            // 开关判断必须在这一行之外 —— 参数要先算出来才轮得到 trace() 去看开关，
            // 而 effective.toString() 与这个模板串是两次分配，就发生在按键回调的主线程上。
            if (_tracing.value) trace(KeyTrace.Kind.UNBOUND, effective.toString(), "sc " + e.scanCode)
            return false
        }
        if (!intercept) return false
        consumedKeyCodes += e.keyCode
        if (remapped) activeRemaps[e.keyCode] = combo to effective
        return true
    }

    fun clearTraces() {
        _traces.value = emptyList()
    }

    /**
     * 事件流里的一条。同样受 [tracing] 约束。
     *
     * 匹配与执行结果每次触发都会来两条，而每一条都要复制一遍上限 200 的列表。
     * 连按快捷键切换应用时这条路径被反复走，开销全落在按键回调的主线程上。
     * 真正需要留痕的失败已经进了事件日志，那一份是落盘的，也不看这个开关。
     */
    fun trace(kind: KeyTrace.Kind, text: String, detail: String = "") {
        if (!_tracing.value) return
        push(KeyTrace(traceIds.incrementAndGet(), System.currentTimeMillis(), kind, text, detail))
    }

    // --- 内部 ---------------------------------------------------------------

    private fun normalize(event: KeyEvent): NormalizedKeyEvent = NormalizedKeyEvent(
        keyCode = event.keyCode,
        scanCode = event.scanCode,
        metaState = event.metaState,
        down = event.action == KeyEvent.ACTION_DOWN,
        repeatCount = event.repeatCount,
        eventTimeMs = event.eventTime,
        // 走缓存。[deviceCache] 本来就是为这一行建的，之前却被绕开了：
        // 打字时每一颗字母键都在往 system_server 发一次 binder 调用查同一台键盘。
        device = deviceOf(event.deviceId),
        virtual = event.deviceId == KeyCharacterMap.VIRTUAL_KEYBOARD,
    )

    private fun deviceOf(deviceId: Int): KeyboardDevice =
        deviceCache.getOrPut(deviceId) { KeyboardDevice.from(InputDevice.getDevice(deviceId)) }

    private fun updateSnapshot(e: NormalizedKeyEvent) {
        _snapshot.update { prev ->
            val keys = prev.pressedKeyCodes.toMutableSet()
            if (e.down) keys += e.keyCode else keys -= e.keyCode
            // 修饰键状态以系统 metaState 为准；抬起最后一个键时强制清空，
            // 避免键盘热拔插导致的「幽灵按下」（PRD 28）。
            val mods = if (keys.isEmpty()) 0 else e.modifiers
            KeyPressSnapshot(keys, mods, e)
        }
    }

    private fun appendTrace(e: NormalizedKeyEvent) {
        if (!_tracing.value) return
        if (traceCapacity == 0 || e.repeatCount > 0) return // 长按不刷屏；容量为 0 时完全关闭日志
        push(
            KeyTrace(
                id = traceIds.incrementAndGet(),
                timestampMs = System.currentTimeMillis(),
                kind = if (e.down) KeyTrace.Kind.DOWN else KeyTrace.Kind.UP,
                text = e.rawKeyName,
                detail = "sc ${e.scanCode}",
            )
        )
    }

    private fun push(t: KeyTrace) {
        _traces.update { list ->
            val next = list + t
            if (next.size > traceCapacity) next.takeLast(traceCapacity) else next
        }
    }

    /**
     * 释放所有按下状态：键盘断开或服务重启时调用。
     *
     * 被替换掉的键要显式补一次抬起 —— 拔掉键盘时目标修饰键正按着的话，
     * 系统会一直以为它按着，整台设备的输入都会变样。
     */
    fun reset() {
        replacedKeys.forEach { keyCode ->
            onReplacedKey?.invoke(keyCode, 0, false, KeyboardDevice.UNKNOWN)
        }
        replacedKeys.clear()
        _snapshot.value = KeyPressSnapshot()
        deviceCache.clear()
        consumedKeyCodes.clear()
        activeRemaps.clear()
        tapCandidate = null
    }
}

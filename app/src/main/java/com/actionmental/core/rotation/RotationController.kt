package com.actionmental.core.rotation

import com.actionmental.core.action.ActionResult
import com.actionmental.platform.PrivilegedBackend
import com.actionmental.platform.ShellResult
import com.actionmental.platform.SettingsReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal fun rotationWritePlan(mode: RotationMode): List<String>? = when (mode) {
    RotationMode.NORMAL -> listOf(
        "cmd window set-ignore-orientation-request false",
        "cmd window set-fix-to-user-rotation default",
        "settings put system accelerometer_rotation 1",
    )
    RotationMode.CUSTOM -> listOf(
        "cmd window set-ignore-orientation-request false",
        "cmd window set-fix-to-user-rotation default",
        "cmd window user-rotation lock 0",
    )
    else -> mode.surfaceRotation?.let { rotation ->
        listOf(
            "cmd window set-ignore-orientation-request true",
            "cmd window set-fix-to-user-rotation enabled",
            "settings put system accelerometer_rotation 0",
            "settings put system user_rotation " + rotation,
        )
    }
}

/** 批量执行时的分隔标记。挑一串不可能出现在 `cmd window` 输出里的字面量。 */
private const val BATCH_MARK = "__AM_RC__"

/** 每条命令后面跟一行「标记 + 退出码」，回来就能按标记把输出切回一条一条。 */
internal fun batchScript(commands: List<String>): String =
    commands.joinToString("\n") { it + "\necho \"" + BATCH_MARK + "\"\$?" }

/**
 * 把 [batchScript] 的输出切回逐条结果。
 *
 * 标记比命令少说明 shell 中途没了 —— 缺的那些一律按失败处理，
 * 绝不因为「没看到失败」就当成成功。
 */
internal fun parseBatch(count: Int, output: String): List<ShellResult> {
    val results = ArrayList<ShellResult>(count)
    val buffer = StringBuilder()
    for (line in output.lineSequence()) {
        if (line.startsWith(BATCH_MARK)) {
            val code = line.removePrefix(BATCH_MARK).trim().toIntOrNull() ?: -1
            results += ShellResult(code, buffer.toString().trim())
            buffer.setLength(0)
        } else {
            buffer.append(line).append('\n')
        }
    }
    while (results.size < count) {
        results += ShellResult(-1, buffer.toString().trim().ifEmpty { "命令未执行" })
        buffer.setLength(0)
    }
    return results.take(count)
}

/**
 * 全应用唯一的屏幕旋转入口（PRD 21 / 35.6）。
 *
 * 关键设计：**没有本地状态机**。
 * 每次写入之后都重新读一遍系统真实值，[state] 里的东西永远是刚刚从系统查回来的，
 * 磁贴、界面、快捷键执行后的提示共用同一份（PRD 3.2 / 12）。
 *
 * 「意图」与「事实」分开：
 *   - [globalMode] 是用户表达的全局意图（持久化，进程重启后恢复）
 *   - [override]   是应用级规则临时压上去的意图（不持久化）
 *   - [state]      是系统事实
 */
class RotationController(
    private val scope: CoroutineScope,
    private val backend: () -> PrivilegedBackend,
    private val settings: SettingsReader,
    private val persistGlobalMode: suspend (RotationMode) -> Unit,
    /** 真的把命令写下去了。 */
    private val onWrite: () -> Unit = {},
    /** 因为系统已经是这个模式而整条写入被省掉了。去重省下多少，只看得见这一对。 */
    private val onSkipped: () -> Unit = {},
) {
    private val mutex = Mutex()

    private val _state = MutableStateFlow(RotationState.unknown("尚未查询"))
    val state: StateFlow<RotationState> = _state.asStateFlow()

    @Volatile
    var globalMode: RotationMode = RotationMode.NORMAL
        private set

    @Volatile
    private var override: RotationMode? = null

    /**
     * 全局暂停：一道写入闸门。
     *
     * 暂停（手动或拔掉键盘自动暂停）期间**一条旋转命令都不下发**：进暂停那一刻不写，
     * 应用规则压 override 不写，快捷键 / 磁贴 / 界面 / 多屏下发一律拒绝。
     * 系统旋转停在什么样就是什么样，用户在快捷设置里自己拨也不会被改回去。
     * [globalMode] 与 [override] 照常记录，[setPaused] 传 false 时按它们重新算一次。
     *
     * 初值是 true：进程被拉起时还不知道盘上是不是暂停着，而规则管理器的第一次发射、
     * 以及尚未从盘上恢复的 [globalMode]（默认 NORMAL）都会立刻触发一次写入 ——
     * 暂停期间进程每被系统杀掉重启一次，就把系统自动旋转写回去一次。
     * 由 [com.actionmental.AppGraph] 在暂停状态落定后放开。
     */
    @Volatile
    private var paused = true

    /**
     * 进 / 出暂停。进入时只关闸不写；退出时按当前意图写一次，回读走 [applyEffective]。
     */
    suspend fun setPaused(on: Boolean): ActionResult {
        if (paused == on) return ActionResult.OK
        if (on) {
            // 拿锁再关闸：正在进行的那一次写入写完，之后的一律挡在门外
            mutex.withLock { paused = true }
            return ActionResult.OK
        }
        paused = false
        return applyEffective()
    }

    private fun pausedResult(): ActionResult =
        ActionResult.Failed(ActionResult.Reason.UNSUPPORTED, "已暂停 · 不修改屏幕旋转")

    /**
     * 最后一次观察到的系统真实模式。
     *
     * 它的唯一用途是回答「还需要写吗」。前台应用每换一次，规则管理器就会压一次
     * override —— 而其中绝大多数换来换去，最终生效的模式压根没变。原来每一次都要
     * fork 三到四个 shell 去写一遍已经成立的事实，再 fork 两个去回读：快速切几次应用
     * 就是几十个进程。设备上那三次死亡全是 `bgLimit_level_thermal`（温控），
     * 白烧的 CPU 直接记在自己账上。
     *
     * null 表示「不知道」，那时一律先读一次再决定。
     */
    @Volatile
    private var appliedMode: RotationMode? = null

    /** [appliedMode] 是什么时候看到的。过了 [CACHE_TTL_MS] 就不再当真。 */
    @Volatile
    private var appliedAtMs: Long = 0L

    /**
     * 缓存作废：系统旋转设置被别人改了。
     *
     * 用户在快捷设置里拨一下自动旋转，系统就不再是我们上次看到的样子 ——
     * 继续照着旧结论「已经是这个模式了，不用写」，应用规则就会静默失效。
     * 由 [com.actionmental.AppGraph] 用 ContentObserver 驱动，不轮询。
     */
    fun invalidateObservedState() {
        appliedMode = null
    }

    fun restoreGlobalMode(mode: RotationMode) {
        globalMode = mode
    }

    /** 只读一次真实状态，不做任何写入。 */
    suspend fun refresh(): RotationState = mutex.withLock { readState() }.also { publish(it) }

    /** 每一次读到真实状态都同时更新「还需不需要写」的依据。 */
    private fun publish(state: RotationState): RotationState {
        _state.value = state
        appliedMode = state.mode.takeIf { it != RotationMode.UNKNOWN }
        appliedAtMs = System.currentTimeMillis()
        return state
    }

    /** 还能不能相信上一次看到的模式。 */
    private fun cachedMode(): RotationMode? =
        appliedMode?.takeIf { System.currentTimeMillis() - appliedAtMs < CACHE_TTL_MS }

    fun refreshAsync() {
        scope.launch { refresh() }
    }

    /** 用户 / 快捷键 / 磁贴设置全局模式。 */
    suspend fun setGlobal(mode: RotationMode): ActionResult {
        if (paused) return pausedResult()
        globalMode = mode
        persistGlobalMode(mode)
        return applyEffective()
    }

    /** 应用级规则压入的临时模式；传 null 表示离开受控应用。暂停期间只记录，不写入。 */
    suspend fun setOverride(mode: RotationMode?): ActionResult {
        override = mode
        return applyEffective()
    }

    /** 磁贴与快捷键的「切换强制横屏」。基于真实状态判断，而不是本地 boolean。 */
    suspend fun toggleLandscape(): ActionResult {
        if (paused) return pausedResult()
        val current = refresh()
        if (!current.available) return ActionResult.Failed(unavailableReason(), current.failure.orEmpty())
        return setGlobal(
            if (current.mode == RotationMode.FORCE_LANDSCAPE) RotationMode.NORMAL
            else RotationMode.FORCE_LANDSCAPE
        )
    }

    /** 快捷键切换指定方向；再次触发当前方向时撤销强制方向并回到竖屏锁定。 */
    suspend fun toggle(mode: RotationMode): ActionResult {
        if (paused) return pausedResult()
        val current = refresh()
        if (!current.available) return ActionResult.Failed(unavailableReason(), current.failure.orEmpty())
        return setGlobal(RotationMode.toggleTarget(current.mode, mode))
    }

    /** 循环：默认 → 横屏 → 竖屏 → 默认。 */
    suspend fun cycle(): ActionResult {
        if (paused) return pausedResult()
        val current = refresh()
        if (!current.available) return ActionResult.Failed(unavailableReason(), current.failure.orEmpty())
        val next = when (current.mode) {
            RotationMode.NORMAL -> RotationMode.FORCE_LANDSCAPE
            RotationMode.FORCE_LANDSCAPE -> RotationMode.FORCE_PORTRAIT
            else -> RotationMode.NORMAL
        }
        return setGlobal(next)
    }

    private fun effectiveMode(): RotationMode = override ?: globalMode

    private suspend fun applyEffective(): ActionResult = mutex.withLock {
        // 闸门在锁里再判一次：排队等锁的写入可能是在暂停落地之前发起的
        if (paused) return@withLock ActionResult.OK
        val target = effectiveMode()
        val availability = backend().availability()
        if (availability is ActionResult.Failed) {
            _state.value = RotationState.unknown(availability.reason.message)
            appliedMode = null
            return@withLock availability
        }

        // 不知道系统现在是什么样就先读一次。一次读（一个 shell）换掉一次白写
        // （三到四条命令 + 一次回读），而「压上来的 override 其实没改变结果」
        // 恰恰是最常见的情形：每切一次应用都会走到这里。
        val current = cachedMode() ?: publish(readState()).mode
        if (current == target) {
            onSkipped()
            return@withLock ActionResult.OK
        }

        onWrite()
        val result = write(target)
        // 写完必须回读，UI 与磁贴显示的一律是系统事实
        publish(readState())
        result
    }

    private suspend fun write(mode: RotationMode): ActionResult {
        val b = backend()
        val plan = rotationWritePlan(mode)
            ?: return ActionResult.Failed(ActionResult.Reason.UNSUPPORTED, mode.technical)

        val results = execBatch(b, plan)
            ?: return ActionResult.Failed(ActionResult.Reason.EXECUTION_FAILED, "特权通道不可用")

        var softFailure: String? = null
        plan.forEachIndexed { index, command ->
            val shell = results.getOrNull(index) ?: return@forEachIndexed
            if (!shell.ok) {
                // fix-to-user-rotation 在部分 OEM 上不存在，属于降级而不是彻底失败
                if (command.contains("fix-to-user-rotation")) softFailure = shell.output.trim()
                else return ActionResult.Failed(ActionResult.Reason.EXECUTION_FAILED, shell.output.trim())
            }
        }
        return if (softFailure == null) ActionResult.OK
        else ActionResult.Ok("已应用，但 fixed_to_user_rotation 不受支持 · " + softFailure)
    }

    /** 解析真实系统状态（PRD 21 · RotationStateProvider）。 */
    private suspend fun readState(): RotationState {
        val b = backend()
        val availability = b.availability()
        if (availability is ActionResult.Failed) return RotationState.unknown(availability.reason.message)

        val userRotation = settings.systemInt("user_rotation")
        val accelerometer = settings.systemInt("accelerometer_rotation")
        // 两条查询合成一次 shell。两次 exec 就是两个 `sh` 加两个 `cmd`，
        // 而这段代码在旋转页每十几秒、以及每次写入之后都要跑一遍。
        val probes = execBatch(
            b,
            listOf(
                "cmd window get-ignore-orientation-request",
                "cmd window get-fix-to-user-rotation",
            ),
        )
        val ignore = probes?.getOrNull(0)
            ?.takeIf { it.ok }
            ?.output
            ?.let { it.contains("true", ignoreCase = true) }
        val fixed = probes?.getOrNull(1)
            ?.takeIf { it.ok }
            ?.output
            ?.let { out -> out.filter { ch -> ch.isDigit() }.toIntOrNull() }

        val mode = when {
            userRotation == null || accelerometer == null -> RotationMode.UNKNOWN
            accelerometer != 0 -> RotationMode.NORMAL
            ignore == true -> RotationMode.fromSurfaceRotation(userRotation)
            else -> RotationMode.CUSTOM
        }

        return RotationState(
            mode = mode,
            userRotation = userRotation,
            accelerometerRotation = accelerometer,
            ignoreAppRequest = ignore,
            fixedToUserRotation = fixed,
            verifiedAtMs = System.currentTimeMillis(),
            failure = if (mode == RotationMode.UNKNOWN) "系统旋转设置读取失败" else null,
        )
    }

    /**
     * 折叠屏与外接屏：`ignore-orientation-request` 是**按屏幕**存的，
     * 不带 `-d` 的命令只作用于 display 0。内外屏若是两块独立 display，
     * 只对默认屏设过的强制在另一块屏上根本不存在 —— 这里把当前生效模式推到每一块屏上。
     */
    suspend fun spreadToAllDisplays(): ActionResult = mutex.withLock {
        if (paused) return@withLock pausedResult()
        val b = backend()
        val availability = b.availability()
        if (availability is ActionResult.Failed) return@withLock availability

        val ids = displayIds(b)
        if (ids.isEmpty()) {
            return@withLock ActionResult.Failed(ActionResult.Reason.EXECUTION_FAILED, "未能枚举屏幕")
        }

        val value = if (effectiveMode().isForced) "true" else "false"
        val failed = mutableListOf<String>()
        for (id in ids) {
            val shell = b.exec("cmd window set-ignore-orientation-request -d " + id + " " + value).getOrNull()
            if (shell?.ok != true) failed += "display " + id + ": " + (shell?.output?.trim() ?: "执行失败")
        }
        publish(readState())
        if (failed.isEmpty()) ActionResult.Ok("已对 " + ids.size + " 块屏幕下发 ignore=" + value)
        else ActionResult.Failed(ActionResult.Reason.EXECUTION_FAILED, failed.joinToString(" · "))
    }

    private suspend fun displayIds(b: PrivilegedBackend): List<Int> =
        b.exec("dumpsys window displays | grep -oE 'mDisplayId=[0-9]+'").getOrNull()
            ?.takeIf { it.ok }
            ?.output
            ?.let { out ->
                Regex("""mDisplayId=(\d+)""").findAll(out)
                    .map { it.groupValues[1].toInt() }
                    .distinct()
                    .toList()
            }
            ?: emptyList()

    /**
     * 一次 shell 跑完一串命令，仍然拿得到每条的退出码与输出。
     *
     * 逐条 exec 的代价不是命令本身，是每一条都要在特权进程里 fork 一个 `sh` ——
     * 一次旋转写入原来是三到四个，加上回读的两个。合成一次之后只剩一个。
     *
     * 与逐条执行的唯一差别：前面的命令失败不再中断后面的。调用方照样按顺序
     * 逐条判定退出码，第一条硬失败仍然是最终结论。
     *
     * @return null 表示这一次 shell 整个没跑起来（特权通道断了）。
     */
    private suspend fun execBatch(
        b: PrivilegedBackend,
        commands: List<String>,
    ): List<ShellResult>? {
        if (commands.isEmpty()) return emptyList()
        val raw = b.exec(batchScript(commands)).getOrNull() ?: return null
        return parseBatch(commands.size, raw.output)
    }

    private companion object {
        /**
         * 缓存的系统模式能信多久。
         *
         * ContentObserver 已经盖住了 `accelerometer_rotation` / `user_rotation`
         * 被别人改动的情形；这条时限只是兜底，防的是 `ignore-orientation-request`
         * 这类没有 URI 可以观察的开关被另一个特权应用动过。
         */
        const val CACHE_TTL_MS = 30_000L
    }

    private fun unavailableReason(): ActionResult.Reason =
        (backend().availability() as? ActionResult.Failed)?.reason ?: ActionResult.Reason.UNSUPPORTED
}

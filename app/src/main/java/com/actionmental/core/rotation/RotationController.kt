package com.actionmental.core.rotation

import com.actionmental.core.action.ActionResult
import com.actionmental.platform.PrivilegedBackend
import com.actionmental.platform.ShellResult
import com.actionmental.platform.SystemIntSource
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
    private val settings: SystemIntSource,
    private val persistGlobalMode: suspend (RotationMode) -> Unit,
    /**
     * Shizuku 不在时的降级出口（悬浮层 + 系统设置）。null 表示只有 shell 这一级。
     *
     * 分级是按次挑的，不是开机定死：Shizuku 随时会掉、无障碍服务随时会重绑，
     * 每一次写入都按那一刻最强的一级去做（见 [tier]）。
     */
    private val local: LocalRotationBackend? = null,
    /** 真的把命令写下去了。 */
    private val onWrite: () -> Unit = {},
    /** 因为系统已经是这个模式而整条写入被省掉了。去重省下多少，只看得见这一对。 */
    private val onSkipped: () -> Unit = {},
    /**
     * 每一次真的写下去都报一条：从什么到什么、走哪一级、结果如何。
     *
     * 只在写入时叫，不在去重跳过时叫 —— 后者每切一次应用就有一次。
     * 「这个应用为什么没转」的现场，缺的往往就是「那一刻到底写没写、写了什么」。
     */
    private val onWritten: (from: RotationMode?, to: RotationMode, tier: RotationTier, result: ActionResult) -> Unit =
        { _, _, _, _ -> },
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
     * 暂停（手动或拔掉键盘自动暂停）落地那一刻，至多写一次「解除强制 + 锁定 0° 竖屏」
     * （见 [setPaused] 的 lockPortrait），之后**一条旋转命令都不下发**：应用规则压 override
     * 不写，快捷键 / 磁贴 / 界面 / 多屏下发一律拒绝。用户在快捷设置里自己拨也不会被改回去。
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
     * 进 / 出暂停。
     *
     * 进入：拿锁关闸（正在进行的那一次写入写完，之后的一律挡在门外）；
     * [lockPortrait] 为 true 时在同一把锁里写一次 0° 竖屏 —— 关闸与这次写入之间
     * 不会被任何别的写入插队。闸门初值就是关着的，所以这里不能因为「已经是 true」提前返回。
     *
     * 退出：按当前意图写一次，回读走 [applyEffective]。
     */
    suspend fun setPaused(on: Boolean, lockPortrait: Boolean = false): ActionResult {
        if (on) return mutex.withLock {
            paused = true
            if (lockPortrait) {
                lockPortraitLocked()
            } else {
                // 悬浮层是「还在管屏幕」最直接的那一种形式，暂停时无论如何都撤掉。
                // 它只是一个窗口，撤掉不写任何系统设置。
                local?.forceOverlay(null)
                ActionResult.OK
            }
        }
        if (!paused) return ActionResult.OK
        paused = false
        return applyEffective()
    }

    /**
     * 解除一切强制，把屏幕锁在 0° 竖屏。
     *
     * 用的是 [RotationMode.CUSTOM] 那份写入计划（ignore-orientation-request=false、
     * fix-to-user-rotation 复位、`user-rotation lock 0`），而不是 FORCE_PORTRAIT：
     * 暂停的意思是「这个应用不再管屏幕」，留一个强制竖屏等于还在管。
     * 只锁不强制时，自带方向要求的应用照样能转过去，用户在快捷设置里也拨得动。
     */
    private suspend fun lockPortraitLocked(): ActionResult {
        val tier = tier()
        if (!tier.writable) {
            local?.forceOverlay(null)
            return unavailable()
        }
        onWrite()
        val result = write(RotationMode.CUSTOM, tier)
        publish(readState())
        return result
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

    /**
     * 写入通道变了（Shizuku 连上 / 掉了，无障碍服务重绑了），按当前意图重新落一次。
     *
     * 悬浮层跟着服务实例走：服务一重绑，旧窗口连同它压着的方向一起作废，
     * 不重新落一次，强制方向就在用户不知情时悄悄没了。暂停期间照样被闸门挡住。
     */
    fun reapplyAsync() {
        invalidateObservedState()
        scope.launch { applyEffective() }
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
        if (!current.writable) return notWritable(current)
        val target = if (current.mode == RotationMode.FORCE_LANDSCAPE) RotationMode.NORMAL
        else RotationMode.FORCE_LANDSCAPE
        return withTransition(current.mode, target, setGlobal(target))
    }

    /** 快捷键切换指定方向；再次触发当前方向时撤销强制方向并回到竖屏锁定。 */
    suspend fun toggle(mode: RotationMode): ActionResult {
        if (paused) return pausedResult()
        val current = refresh()
        if (!current.writable) return notWritable(current)
        val target = RotationMode.toggleTarget(current.mode, mode)
        return withTransition(current.mode, target, setGlobal(target))
    }

    /**
     * 成功结果里写明「从哪到哪」。
     *
     * 切换类快捷键的标签永远是同一句（「切换 · 270° 反向横屏」），事件日志里只看得到
     * 按了几下，看不出每一下是转过去还是转回来 —— 连按三下的那几段就没法判断是不是没生效。
     */
    private fun withTransition(from: RotationMode, to: RotationMode, result: ActionResult): ActionResult =
        if (result is ActionResult.Ok) {
            ActionResult.Ok(from.label + " → " + to.label + if (result.detail.isBlank()) "" else " · " + result.detail)
        } else {
            result
        }

    /** 循环：默认 → 横屏 → 竖屏 → 默认。 */
    suspend fun cycle(): ActionResult {
        if (paused) return pausedResult()
        val current = refresh()
        if (!current.writable) return notWritable(current)
        val next = when (current.mode) {
            RotationMode.NORMAL -> RotationMode.FORCE_LANDSCAPE
            RotationMode.FORCE_LANDSCAPE -> RotationMode.FORCE_PORTRAIT
            else -> RotationMode.NORMAL
        }
        return setGlobal(next)
    }

    private fun effectiveMode(): RotationMode = override ?: globalMode

    /** 此刻是否在强制方向（且没暂停）。给「系统设置被别人改了」那一路判断要不要去查现场。 */
    val forcing: Boolean get() = !paused && effectiveMode().isForced

    /** 强制意图要求的 Surface.ROTATION_*；不在强制时为 null。 */
    val forcedRotation: Int? get() = if (forcing) effectiveMode().surfaceRotation else null

    private suspend fun applyEffective(): ActionResult = mutex.withLock {
        // 闸门在锁里再判一次：排队等锁的写入可能是在暂停落地之前发起的
        if (paused) return@withLock ActionResult.OK
        val target = effectiveMode()
        val tier = tier()
        if (!tier.writable) {
            // 读不需要特权：写不了也照样把系统事实交给界面，而不是一句「未知」
            publish(readState())
            return@withLock unavailable()
        }

        // 不知道系统现在是什么样就先读一次。一次读（一个 shell）换掉一次白写
        // （三到四条命令 + 一次回读），而「压上来的 override 其实没改变结果」
        // 恰恰是最常见的情形：每切一次应用都会走到这里。
        val current = cachedMode() ?: publish(readState()).mode
        if (satisfied(current, target, tier)) {
            onSkipped()
            return@withLock ActionResult.OK
        }

        onWrite()
        val result = write(target, tier)
        // 写完必须回读，UI 与磁贴显示的一律是系统事实
        publish(readState())
        onWritten(current, target, tier, result)
        result
    }

    /**
     * 前台应用换了：强制模式下按意图补写一次（系统已经是这个样子时照旧省掉）。
     *
     * 强制期间系统的锁定角度会被别人悄悄改掉：某些应用的竖屏请求压不住（OEM 按应用放行）,
     * 屏幕被它拉回 0° 的那一刻，SystemUI 的 RotationButtonController 会把 user_rotation
     * 跟着改成 0。离开那个应用之后整机就停在竖屏 —— 而规则流只在规则变化时才写，
     * override 从 null 到 null 不算变化，强制横屏就再也回不来了。
     *
     * 只管强制模式：「系统默认 / 锁定」下用户在快捷设置里拨的开关是用户自己的意思，
     * 切个应用就改回去等于和用户抢开关。
     */
    suspend fun reassertForced(): ActionResult {
        if (!forcing) return ActionResult.OK
        return applyEffective()
    }

    /**
     * 系统现在的样子是否已经满足目标。
     *
     * 降级到只有悬浮层、又写不了系统设置时，「系统默认」与「自动旋转关闭」之间的差别
     * 根本不归我们管 —— 能做的只有「不再强制」。否则每切一次应用都会去撤一个
     * 本来就不存在的悬浮层，然后回读到一个永远对不上的模式。
     */
    private fun satisfied(current: RotationMode, target: RotationMode, tier: RotationTier): Boolean {
        if (current == target) return true
        if (tier == RotationTier.SHELL || local?.settingsWritable() == true) return false
        return !current.isForced && !target.isForced && current != RotationMode.UNKNOWN
    }

    /**
     * 此刻能用的最强一级。
     *
     * 每次现挑：Shizuku 与无障碍服务都可能在两次写入之间来去。
     */
    private fun tier(): RotationTier {
        if (backend().availability().succeeded) return RotationTier.SHELL
        val l = local ?: return RotationTier.NONE
        return when {
            l.overlayAvailable() -> RotationTier.OVERLAY
            l.settingsWritable() -> RotationTier.SETTINGS
            else -> RotationTier.NONE
        }
    }

    /** 一级都写不了时的失败：原因里既有 Shizuku 的，也有两条降级路各缺什么。 */
    private fun unavailable(): ActionResult {
        val reason = (backend().availability() as? ActionResult.Failed)?.reason
            ?: ActionResult.Reason.UNSUPPORTED
        return ActionResult.Failed(reason, "无障碍服务未连接，且未授予「修改系统设置」权限")
    }

    /**
     * 降级到只写系统设置时，我们锁上的那个角度。
     *
     * 那一级写下去的只是「自动旋转关 + user_rotation」，读回来与「用户自己关掉自动旋转」
     * 一模一样。不记这一笔，切换类快捷键就永远看不出自己已经转过去了、没法再按一下转回来。
     * 它不是本地状态机：回读时仍要与系统里的 user_rotation 对上才算数。
     */
    @Volatile
    private var settingsLocked: Int? = null

    private suspend fun write(mode: RotationMode, tier: RotationTier): ActionResult =
        if (tier == RotationTier.SHELL) writeShell(mode) else writeLocal(mode, tier)

    /**
     * 不经 shell 的写入。
     *
     * 强制角度两条腿一起走：能写系统设置就先把 user_rotation 锁过去（系统里残留着
     * 以前经 Shizuku 设下的 fix-to-user-rotation 时，这一步本身就能把屏幕转过去），
     * 再挂悬浮层去压住应用自带的方向。
     */
    private suspend fun writeLocal(mode: RotationMode, tier: RotationTier): ActionResult {
        val l = local ?: return unavailable()
        val canWriteSettings = l.settingsWritable()
        return when (mode) {
            RotationMode.NORMAL, RotationMode.CUSTOM -> {
                settingsLocked = null
                l.forceOverlay(null)
                when {
                    canWriteSettings -> l.writeSettings(
                        autoRotate = mode == RotationMode.NORMAL,
                        userRotation = if (mode == RotationMode.CUSTOM) android.view.Surface.ROTATION_0 else null,
                    ).fold(
                        onSuccess = { ActionResult.OK },
                        onFailure = { ActionResult.Failed(ActionResult.Reason.EXECUTION_FAILED, it.message.orEmpty()) },
                    )
                    mode == RotationMode.NORMAL -> ActionResult.Ok("已撤销强制方向")
                    else -> ActionResult.Ok("已撤销强制方向 · 锁定 0° 需要「修改系统设置」权限")
                }
            }

            else -> {
                val rotation = mode.surfaceRotation
                    ?: return ActionResult.Failed(ActionResult.Reason.UNSUPPORTED, mode.technical)
                val settingsWrite = if (canWriteSettings) l.writeSettings(false, rotation) else null

                if (tier == RotationTier.SETTINGS) {
                    val failure = settingsWrite?.exceptionOrNull()
                    if (settingsWrite == null || failure != null) {
                        return ActionResult.Failed(ActionResult.Reason.EXECUTION_FAILED, failure?.message.orEmpty())
                    }
                    settingsLocked = rotation
                    return ActionResult.Ok("已锁定 " + mode.label + " · 应用自带的方向要求仍然优先")
                }

                settingsLocked = null
                when (l.forceOverlay(rotation)) {
                    OverlayOutcome.ADOPTED, OverlayOutcome.REMOVED -> ActionResult.Ok(RotationTier.OVERLAY.label)
                    // 悬浮层被忽略：锁定写下去了就退到那一级，并把「压不住应用」说清楚
                    OverlayOutcome.IGNORED -> if (settingsWrite?.isSuccess == true) {
                        settingsLocked = rotation
                        ActionResult.Ok(
                            "系统忽略了悬浮层的方向要求（大屏 / 折叠屏上常见），已退回锁定 " + mode.label +
                                " · 应用自带的方向要求仍然优先",
                        )
                    } else {
                        ActionResult.Failed(
                            ActionResult.Reason.UNSUPPORTED,
                            "系统忽略了悬浮层的方向要求（大屏 / 折叠屏上常见），屏幕没有转到 " + mode.label,
                        )
                    }
                    OverlayOutcome.FAILED -> ActionResult.Failed(
                        ActionResult.Reason.EXECUTION_FAILED,
                        "强制方向的悬浮层挂不上",
                    )
                }
            }
        }
    }

    private suspend fun writeShell(mode: RotationMode): ActionResult {
        // 降级时挂上的悬浮层要先撤掉：它压在所有窗口之上，留着会和 shell 的写入各说各话
        settingsLocked = null
        local?.forceOverlay(null)
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
        val tier = tier()
        if (tier != RotationTier.SHELL) return readLocalState(tier)
        val b = backend()

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
            tier = RotationTier.SHELL,
        )
    }

    /**
     * 没有 shell 时的回读。
     *
     * 两个旋转设置是公开的 system 表，读不需要任何权限 —— 原来 Shizuku 一掉就整块报「未知」，
     * 连「自动旋转是开是关」这种手边的事实都不给。强制与否看悬浮层是否真的挂在当前服务上，
     * 以及只写设置那一级锁下的角度是否还在系统里。
     */
    private fun readLocalState(tier: RotationTier): RotationState {
        val userRotation = settings.systemInt("user_rotation")
        val accelerometer = settings.systemInt("accelerometer_rotation")
        val overlay = local?.overlayRotation()
        val locked = settingsLocked
        val mode = when {
            overlay != null -> RotationMode.fromSurfaceRotation(overlay)
            userRotation == null || accelerometer == null -> RotationMode.UNKNOWN
            accelerometer != 0 -> RotationMode.NORMAL
            locked != null && userRotation == locked -> RotationMode.fromSurfaceRotation(userRotation)
            else -> RotationMode.CUSTOM
        }
        return RotationState(
            mode = mode,
            userRotation = userRotation,
            accelerometerRotation = accelerometer,
            ignoreAppRequest = null,
            fixedToUserRotation = null,
            verifiedAtMs = System.currentTimeMillis(),
            failure = if (mode == RotationMode.UNKNOWN) "系统旋转设置读取失败" else null,
            tier = tier,
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
        // 按屏幕设 ignore-orientation-request 只有 shell 做得到，这里没有降级
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

    /** 切换类动作在写不了时的失败。意图不落盘：没写下去的「转过去」不该在下次启动时冒出来。 */
    private fun notWritable(current: RotationState): ActionResult =
        if (current.available) unavailable()
        else ActionResult.Failed(
            (backend().availability() as? ActionResult.Failed)?.reason ?: ActionResult.Reason.UNSUPPORTED,
            current.failure.orEmpty(),
        )
}

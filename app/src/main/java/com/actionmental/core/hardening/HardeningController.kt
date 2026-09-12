package com.actionmental.core.hardening

import com.actionmental.core.action.ActionResult
import com.actionmental.platform.PrivilegedBackend
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 后台加固（P1）与无障碍自愈（P2）的唯一执行者。
 *
 * 两件事放在一起，是因为它们回答的是同一个问题：「设定完之后，这套东西还活着吗」。
 * 加固让进程不被省电策略掐死，自愈让被 ROM 关掉的开关自己回来 ——
 * 都只有 shell 身份做得到，也都必须留痕，否则用户永远不知道系统被改了什么。
 *
 * 这里不碰 Shizuku，也不碰设置项：能不能做由 [backend] 的可用性决定，
 * 要不要做由调用方决定。
 */
class HardeningController(
    private val packageName: String,
    /** 「包名/服务全限定名」，写回 enabled_accessibility_services 的那一串。 */
    private val accessibilityComponent: String,
    private val backend: () -> PrivilegedBackend,
    private val journal: HardeningJournal,
    private val now: () -> Long = System::currentTimeMillis,
) {
    companion object {
        /** 两次自动自愈之间的最小间隔。开关写回去又被 ROM 抹掉时，不能变成死循环。 */
        const val AUTO_HEAL_COOLDOWN_MS = 60_000L

        /** 连续失败到这个次数就停手，等用户手动重试。 */
        const val AUTO_HEAL_MAX_FAILURES = 3

        /**
         * 两次自动重绑之间的最小间隔。
         *
         * 比自愈的冷却长：重绑会短暂摘掉服务，万一系统这次也不肯绑回来，
         * 频繁重试等于让键盘反复失灵，比多等一会儿糟得多。
         */
        const val REBIND_COOLDOWN_MS = 180_000L

        /**
         * 上一次重绑连设置都没写进去时的重试间隔。
         *
         * 那种失败没碰过服务，所以不需要长冷却；留这么一点只是防抖，
         * 免得上游几个触发点（亮屏、解除暂停、进程重启）挤在一起时连着试好几遍。
         */
        const val REBIND_RETRY_MS = 15_000L

        private const val SECURE = "secure"
        private const val ENABLED_SERVICES = "enabled_accessibility_services"
        private const val ACCESSIBILITY_ENABLED = "accessibility_enabled"
    }

    private val _state = MutableStateFlow(HardeningState())
    val state: StateFlow<HardeningState> = _state.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private var lastAutoHealMs = 0L
    private var lastRebindMs = 0L
    private var lastRebindSucceeded = false
    private var consecutiveFailures = 0

    /** 自动自愈已经连续失败到放弃，界面据此提示「需要手动处理」。 */
    val autoHealExhausted: Boolean get() = consecutiveFailures >= AUTO_HEAL_MAX_FAILURES

    // --- P1 · 后台加固 ---------------------------------------------------------

    /** 只读检查三项加固的真实状态，不改动任何东西。 */
    suspend fun verify(): HardeningState {
        val b = backend()
        if (!b.availability().succeeded) {
            _state.value = HardeningState()
            return _state.value
        }
        val next = HardeningState(
            checkedAtMs = now(),
            dozeWhitelisted = readDozeWhitelisted(b),
            runInBackground = readAppOp(b, "RUN_IN_BACKGROUND"),
            runAnyInBackground = readAppOp(b, "RUN_ANY_IN_BACKGROUND"),
        )
        _state.value = next
        return next
    }

    /**
     * 逐条施加加固，每一条都留痕，最后重新只读确认一次。
     *
     * 不因为某一条失败就中断：三条互相独立，能拿到几条算几条，
     * 半成功比「一条报错就整体放弃」对用户有用得多。
     */
    suspend fun harden(): ActionResult {
        val b = backend()
        b.availability().let { if (!it.succeeded) return it }

        _busy.value = true
        try {
            var failures = 0
            HardeningStep.entries.forEach { step ->
                val result = b.exec(commandOf(step))
                val ok = result.getOrNull()?.ok == true
                if (!ok) failures++
                journal.append(
                    HardeningRecord.Kind.HARDEN,
                    step.title,
                    ok,
                    result.fold(
                        onSuccess = {
                            if (it.ok) it.output.trim()
                            else "exit " + it.exitCode + " · " + it.output.trim()
                        },
                        onFailure = { it.message.orEmpty() },
                    ),
                )
            }
            val verified = verify()
            return when {
                verified.hardened -> ActionResult.Ok("三项加固已生效")
                failures == 0 -> ActionResult.Failed(
                    ActionResult.Reason.UNSUPPORTED,
                    "命令都执行了，但复查没通过，可能被 ROM 拦下",
                )
                else -> ActionResult.Failed(
                    ActionResult.Reason.EXECUTION_FAILED,
                    failures.toString() + " 项未成功",
                )
            }
        } finally {
            _busy.value = false
        }
    }

    private fun commandOf(step: HardeningStep): String = when (step) {
        HardeningStep.DOZE_WHITELIST -> "dumpsys deviceidle whitelist +" + packageName
        HardeningStep.RUN_IN_BACKGROUND -> "cmd appops set " + packageName + " RUN_IN_BACKGROUND allow"
        HardeningStep.RUN_ANY_IN_BACKGROUND -> "cmd appops set " + packageName + " RUN_ANY_IN_BACKGROUND allow"
    }

    private suspend fun readDozeWhitelisted(b: PrivilegedBackend): Boolean? {
        val result = b.exec("dumpsys deviceidle whitelist").getOrNull() ?: return null
        if (!result.ok) return null
        return parseDozeWhitelist(result.output, packageName)
    }

    private suspend fun readAppOp(b: PrivilegedBackend, op: String): Boolean? {
        val result = b.exec("cmd appops get " + packageName + " " + op).getOrNull() ?: return null
        if (!result.ok) return null
        return parseAppOp(result.output)
    }

    // --- P2 · 无障碍自愈 -------------------------------------------------------

    /**
     * 把自己写回 enabled_accessibility_services。
     *
     * 只做增量追加，绝不整串覆盖 —— 那一行里还有别人的服务，覆盖等于把用户其它的
     * 无障碍应用一并关掉，代价远大于本功能的收益。
     *
     * 「没动手」和「动手了但失败」必须分开返回：前者是常态（冷却中、开关本来就在），
     * 不该留痕也不该弹提示；后者一定要让用户看见。
     *
     * @param trigger 谁发起的。自动与开机受冷却和连续失败次数约束，手动不受限并重置计数；
     *   三者在历史里分别留名，事后才分得清「是开机掉的还是用着用着掉的」。
     */
    suspend fun healAccessibility(trigger: HealTrigger): HealOutcome {
        val auto = trigger != HealTrigger.MANUAL
        if (auto) {
            if (consecutiveFailures >= AUTO_HEAL_MAX_FAILURES) {
                return HealOutcome.Skipped("已连续失败，等待手动重试")
            }
            if (lastAutoHealMs > 0L && now() - lastAutoHealMs < AUTO_HEAL_COOLDOWN_MS) {
                return HealOutcome.Skipped("冷却中")
            }
            lastAutoHealMs = now()
        } else {
            consecutiveFailures = 0
        }

        val b = backend()
        b.availability().let { if (!it.succeeded) return HealOutcome.Attempted(record(trigger, it)) }

        _busy.value = true
        try {
            val current = b.getSetting(SECURE, ENABLED_SERVICES)
            val listed = containsComponent(current, accessibilityComponent)
            val masterOn = b.getSetting(SECURE, ACCESSIBILITY_ENABLED)?.trim() == "1"

            if (listed && masterOn) {
                consecutiveFailures = 0
                // 什么都没改，就不该占一条历史，也不该弹提示
                return HealOutcome.Skipped("监听服务本来就是开的")
            }

            if (!listed) {
                val write = b.putSetting(
                    SECURE,
                    ENABLED_SERVICES,
                    appendComponent(current, accessibilityComponent),
                )
                if (write.isFailure) return HealOutcome.Attempted(record(trigger, writeFailed(write)))
            }

            // 总开关是 0 时系统一个服务都不绑：列表写对了也照样连不上，
            // 而且界面会停在「已授权但未连接」。这一步的失败必须报出来，
            // 报成功等于让用户以为修好了，实际还得手动去设置里关掉再打开。
            if (!masterOn) {
                val write = b.putSetting(SECURE, ACCESSIBILITY_ENABLED, "1")
                if (write.isFailure) return HealOutcome.Attempted(record(trigger, writeFailed(write)))
            }

            val what = when {
                !listed && !masterOn -> "已写回监听服务，并打开无障碍总开关"
                !listed -> "已把监听服务重新写回系统设置"
                else -> "监听服务在列表里但总开关是关的，已重新打开总开关"
            }
            return HealOutcome.Attempted(record(trigger, ActionResult.Ok(what)))
        } finally {
            _busy.value = false
        }
    }

    /**
     * 强制系统重新绑定服务：把组件从列表里摘掉，再原样写回去。
     *
     * 对付的是「列表在、总开关也在，服务实例就是没连上」——
     * 进程被 ROM 杀掉后 AccessibilityManagerService 没有重绑，
     * 系统设置里读到的一切都正常，所以 [healAccessibility] 认为无事可做。
     * 这一摘一写正是用户手动「关掉再打开」的等价动作，只是不用离开应用。
     */
    /**
     * 自动重绑：与手动同一条路径，但受冷却约束。
     *
     * 「掉线」在熄屏唤醒后是常态，不能每醒一次就摘一次服务。
     */
    suspend fun rebindAccessibilityAuto(): HealOutcome {
        val since = now() - lastRebindMs
        val cooldown = if (lastRebindSucceeded) REBIND_COOLDOWN_MS else REBIND_RETRY_MS
        if (lastRebindMs > 0L && since < cooldown) {
            return HealOutcome.Skipped("重绑冷却中 · 还差 " + ((cooldown - since) / 1000L) + "s")
        }
        lastRebindMs = now()
        val result = rebindAccessibility()
        // 冷却分两档，取决于这一次到底动没动服务。
        //
        // 长冷却防的是「摘了又装、系统就是不肯绑回来」变成死循环——那种反复会让键盘
        // 一次次失灵，比多等一会儿糟得多。但写都没写进去的失败（Shizuku 掉线、
        // 命令被 ROM 拒掉）根本没碰服务，让它也吃满三分钟，等于一次抖动就把接下来
        // 每一个补救时机都堵死：亮屏、解除暂停、进程重启统统撞在冷却上，
        // 而这些时机本来就稀疏，错过就只能等用户自己去设置里关掉再打开。
        lastRebindSucceeded = result.succeeded
        return HealOutcome.Attempted(result)
    }

    suspend fun rebindAccessibility(): ActionResult {
        val b = backend()
        b.availability().let { if (!it.succeeded) return record(HealTrigger.REBIND, it) }

        _busy.value = true
        try {
            val current = b.getSetting(SECURE, ENABLED_SERVICES)
            val without = removeComponent(current, accessibilityComponent)

            val off = b.putSetting(SECURE, ENABLED_SERVICES, without)
            if (off.isFailure) return record(HealTrigger.REBIND, writeFailed(off))

            val on = b.putSetting(
                SECURE,
                ENABLED_SERVICES,
                appendComponent(without, accessibilityComponent),
            )
            if (on.isFailure) {
                // 摘掉了却没写回来，服务就真的被关了 —— 这比原来的状态更糟，必须说清楚
                return record(
                    HealTrigger.REBIND,
                    ActionResult.Failed(
                        ActionResult.Reason.EXECUTION_FAILED,
                        "已摘除但写回失败，请到系统设置里手动打开 · " +
                            on.exceptionOrNull()?.message.orEmpty(),
                    ),
                )
            }

            val master = b.putSetting(SECURE, ACCESSIBILITY_ENABLED, "1")
            if (master.isFailure) return record(HealTrigger.REBIND, writeFailed(master))

            return record(HealTrigger.REBIND, ActionResult.Ok("已请求系统重新绑定监听服务"))
        } finally {
            _busy.value = false
        }
    }

    private fun writeFailed(result: Result<*>): ActionResult = ActionResult.Failed(
        ActionResult.Reason.EXECUTION_FAILED,
        result.exceptionOrNull()?.message.orEmpty(),
    )

    private suspend fun record(trigger: HealTrigger, result: ActionResult): ActionResult {
        if (result.succeeded) consecutiveFailures = 0 else consecutiveFailures++
        journal.append(HardeningRecord.Kind.HEAL, trigger.label, result.succeeded, result.message)
        return result
    }

    /** 手动重试前清掉退避状态。 */
    fun resetAutoHealBackoff() {
        consecutiveFailures = 0
        lastAutoHealMs = 0L
        lastRebindMs = 0L
        lastRebindSucceeded = false
    }
}

/** 谁发起了这次自愈。历史里按它区分，事后才看得出问题出在哪个环节。 */
enum class HealTrigger(val label: String) {
    MANUAL("手动恢复监听服务"),
    REBIND("强制重新绑定服务"),
    AUTO("自动恢复监听服务"),
    BOOT("开机后恢复监听服务"),
}

/** 一次自愈的结局。[Skipped] 表示压根没改动系统，不留痕、不打扰。 */
sealed interface HealOutcome {
    data class Skipped(val why: String) : HealOutcome
    data class Attempted(val result: ActionResult) : HealOutcome

    val message: String
        get() = when (this) {
            is Skipped -> why
            is Attempted -> result.message
        }

    val succeeded: Boolean get() = this is Attempted && result.succeeded
}

// --- 纯函数部分：可直接单测，不需要设备 ------------------------------------------

/**
 * deviceidle 每行形如 `user,com.foo,10123`。
 * 只做包名精确匹配，避免前缀相同的两个包互相误判。
 */
fun parseDozeWhitelist(output: String, packageName: String): Boolean =
    output.lineSequence().any { line -> line.split(',').any { it.trim() == packageName } }

/**
 * appops 的输出没有稳定格式，各版本分别回过 `RUN_IN_BACKGROUND: allow`、`allow`、`No operations.`。
 * 只有明确写着拒绝才算没生效 —— 这个 op 的系统默认是 allow，
 * 把「查不到」当成没生效会让界面长期误报。
 */
fun parseAppOp(output: String): Boolean {
    val text = output.lowercase()
    return !(text.contains("deny") || text.contains("ignore"))
}

/** 把 `pkg/.Foo` 这类简写补全成全名再统一大小写，才能和系统里那一串可靠比较。 */
fun normalizeComponent(raw: String): String {
    val trimmed = raw.trim()
    val pkg = trimmed.substringBefore('/')
    val cls = trimmed.substringAfter('/', "")
    val full = if (cls.startsWith(".")) pkg + cls else cls
    return (pkg + "/" + full).lowercase()
}

fun containsComponent(raw: String?, component: String): Boolean {
    val expected = normalizeComponent(component)
    return raw.orEmpty().split(':').any { it.isNotBlank() && normalizeComponent(it) == expected }
}

/** 从那一串里摘掉指定组件，其余原样保留。 */
fun removeComponent(raw: String?, component: String): String {
    val expected = normalizeComponent(component)
    return raw.orEmpty().split(':')
        .filter { it.isNotBlank() && normalizeComponent(it) != expected }
        .joinToString(":")
}

/** 追加而不覆盖；已经在里面就原样返回。 */
fun appendComponent(raw: String?, component: String): String {
    val existing = raw.orEmpty().split(':').filter { it.isNotBlank() }
    if (containsComponent(raw, component)) return existing.joinToString(":")
    return (existing + component).joinToString(":")
}

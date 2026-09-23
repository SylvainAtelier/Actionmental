package com.actionmental.core.rotation

import com.actionmental.core.action.ActionResult
import com.actionmental.platform.PrivilegedBackend
import kotlinx.serialization.Serializable

/**
 * 「压住自带方向」名单里的一个应用。
 *
 * 和 [AppRotationRule] 回答的不是同一个问题：规则说「进这个应用时切到哪个方向」，
 * 这里说「这个应用要服从强制方向」。系统对个别应用网开一面（ColorOS 平板上的红果短剧），
 * 全局忽略开关拿不下它，只能对它单独施加兼容覆盖。
 */
@Serializable
data class OrientationCompatTarget(
    val packageName: String,
    val appLabel: String,
    val enabled: Boolean = true,
    val createdAt: Long = 0L,
)

/** 一次核对里对某个包做了什么。 */
data class CompatSyncStep(val packageName: String, val action: String, val ok: Boolean, val output: String)

/**
 * 让名单上的覆盖一直在。
 *
 * 只施加 [CHANGE] 这一条：它只在显示屏开着忽略方向请求时生效（也就是强制方向期间），
 * 「系统默认」下对应用没有任何影响，适合常驻。第二级手段里其余几条（最小宽高比、
 * NOSENSOR 之类）在不强制时也会改应用的布局或方向，不适合挂在名单上一直开着。
 *
 * 覆盖存在 system_server 里，重启、应用更新之后是否还在因版本而异，所以不假设它在：
 * 每次核对都先读一遍 `dumpsys platform_compat`，缺了才补，一次读一个 shell。
 * 施加后要等目标应用的新界面起来才生效 —— 不在这里结束它，那是用户手上正在用的东西。
 */
class OrientationCompatKeeper(private val backend: () -> PrivilegedBackend) {

    /**
     * 按名单核对一次。
     *
     * 启用的缺覆盖就补；停用的若还挂着就撤。名单以外的包一概不碰 ——
     * 诊断页的「应用级兼容覆盖」按钮施加的那些不归名单管。
     *
     * @return null 表示特权通道不可用，这次什么都没做。
     */
    suspend fun sync(targets: List<OrientationCompatTarget>): List<CompatSyncStep>? {
        if (targets.isEmpty()) return emptyList()
        val b = backend()
        if (b.availability() is ActionResult.Failed) return null
        val dump = b.exec("dumpsys platform_compat | grep 'name=$CHANGE;'").getOrNull() ?: return null
        val active = parseOverridden(dump.output)

        val steps = mutableListOf<CompatSyncStep>()
        for (target in targets) {
            val on = target.packageName in active
            when {
                target.enabled && !on -> steps += run(b, target.packageName, "施加", "am compat enable $CHANGE " + target.packageName)
                !target.enabled && on -> steps += run(b, target.packageName, "撤销", "am compat reset $CHANGE " + target.packageName)
            }
        }
        return steps
    }

    /** 从名单里删掉时撤掉覆盖。只撤这一条，不碰这个包上别的覆盖。 */
    suspend fun release(packageName: String): ActionResult {
        val b = backend()
        val availability = b.availability()
        if (availability is ActionResult.Failed) return availability
        val step = run(b, packageName, "撤销", "am compat reset $CHANGE $packageName")
        return if (step.ok) ActionResult.OK
        else ActionResult.Failed(ActionResult.Reason.EXECUTION_FAILED, step.output)
    }

    private suspend fun run(b: PrivilegedBackend, pkg: String, action: String, command: String): CompatSyncStep {
        val shell = b.exec(command).getOrNull()
        val output = shell?.output?.trim().orEmpty().ifEmpty { if (shell == null) "命令未能执行" else "（无输出）" }
        // am compat 对不认识的 change 也可能退出 0，只打一行 Unknown
        val ok = shell?.ok == true && !output.contains("Unknown", ignoreCase = true)
        return CompatSyncStep(pkg, action, ok, output)
    }

    companion object {
        const val CHANGE = "OVERRIDE_ANY_ORIENTATION_TO_USER"

        /**
         * 取出覆盖为 true 的包名。
         *
         * `ChangeId(310816437; name=OVERRIDE_ANY_ORIENTATION_TO_USER; disabled;
         *  packageOverrides={com.phoenix.read=true, com.foo=false}; rawOverrides={…}; overridable)`
         */
        fun parseOverridden(dump: String): Set<String> {
            val line = dump.lineSequence().firstOrNull { it.contains("name=$CHANGE;") } ?: return emptySet()
            val body = Regex("""packageOverrides=\{([^}]*)\}""").find(line)?.groupValues?.get(1) ?: return emptySet()
            return body.split(',')
                .map { it.trim() }
                .filter { it.endsWith("=true") }
                .map { it.removeSuffix("=true") }
                .toSet()
        }
    }
}

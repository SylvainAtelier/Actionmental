package com.actionmental.core.rotation

import com.actionmental.core.action.ActionResult
import com.actionmental.platform.PrivilegedBackend

/**
 * 全局 `ignore-orientation-request` 拿不下某个应用时的第二级手段。
 *
 * 全局开关只解决「应用请求了竖屏」这一层；应用若同时声明了不可调整大小，
 * 或系统对它启用了兼容模式，画面依旧不会重排。这里逐条施加 Android 的
 * per-app compat override 来拆掉剩下的几层。
 *
 * 三个必须让用户知道的性质：
 *   1. override 存活于 system_server 内存中，**重启设备后全部失效**；
 *   2. 需要目标进程重启才生效，因此提供 [forceStop]；
 *   3. 每条 change id 的可用性随 Android 版本与 OEM 而变，
 *      所以逐条执行、逐条如实报告，绝不因为一条失败就谎称整体失败或成功。
 */
class AppOverrideController(private val backend: () -> PrivilegedBackend) {

    /** 一条覆盖项：技术名 + 它拆掉的是哪一层。 */
    data class Override(val changeId: String, val purpose: String)

    data class Outcome(val override: Override, val applied: Boolean, val output: String)

    data class Report(val packageName: String, val outcomes: List<Outcome>) {
        val appliedCount: Int get() = outcomes.count { it.applied }
        val summary: String
            get() = "已施加 " + appliedCount + "/" + outcomes.size + " 项覆盖"
    }

    companion object {
        /**
         * 顺序即依赖顺序：先改写方向，再解除尺寸限制，最后放开显示 API 沙箱。
         * 名称取自 AOSP ActivityInfo 中的 @ChangeId 常量。
         */
        val LANDSCAPE_SET = listOf(
            // 排第一、也是最管用的一条：显示屏开着忽略方向请求时，把应用请求的任何方向
            // 改写成 USER。部分 OEM（ColorOS 平板上的红果短剧）对个别应用照样采纳竖屏请求，
            // 全局开关形同虚设，只有它压得住。忽略开关关着时它不起作用，留着无副作用。
            Override("OVERRIDE_ANY_ORIENTATION_TO_USER", "忽略开关打开时，把应用请求的任何方向改写成 USER"),
            Override("OVERRIDE_ANY_ORIENTATION", "允许系统覆盖该应用声明的任何方向"),
            Override("OVERRIDE_UNDEFINED_ORIENTATION_TO_NOSENSOR", "未声明方向时按 nosensor 处理"),
            Override("FORCE_RESIZE_APP", "解除不可调整大小，绕开尺寸兼容模式的黑边"),
            Override("OVERRIDE_MIN_ASPECT_RATIO", "打开最小宽高比覆盖的总开关"),
            Override("OVERRIDE_MIN_ASPECT_RATIO_LARGE", "把画面拉到接近全屏的宽高比"),
            Override("NEVER_SANDBOX_DISPLAY_APIS", "让应用读到真实屏幕尺寸而非沙箱尺寸"),
        )
    }

    /** 逐条施加，返回每一条的真实结果。 */
    suspend fun apply(packageName: String, set: List<Override> = LANDSCAPE_SET): Result<Report> {
        val b = backend()
        val availability = b.availability()
        if (availability is ActionResult.Failed) {
            return Result.failure(IllegalStateException(availability.reason.message))
        }
        val outcomes = set.map { item ->
            val shell = b.exec("am compat enable " + item.changeId + " " + packageName).getOrNull()
            Outcome(
                override = item,
                applied = shell?.ok == true && !shell.output.contains("Unknown", ignoreCase = true),
                output = shell?.output?.trim().orEmpty().ifEmpty { "（无输出）" },
            )
        }
        return Result.success(Report(packageName, outcomes))
    }

    /** 撤销这个包上的全部覆盖。 */
    suspend fun reset(packageName: String): ActionResult {
        val shell = backend().exec("am compat reset-all " + packageName).getOrNull()
            ?: return ActionResult.Failed(ActionResult.Reason.EXECUTION_FAILED, "命令未能执行")
        return if (shell.ok) ActionResult.Ok("已撤销 " + packageName + " 的兼容覆盖")
        else ActionResult.Failed(ActionResult.Reason.EXECUTION_FAILED, shell.output.trim())
    }

    /** 覆盖只在进程重启后生效，所以这一步是必要的，而不是可选的收尾。 */
    suspend fun forceStop(packageName: String): ActionResult {
        val shell = backend().exec("am force-stop " + packageName).getOrNull()
            ?: return ActionResult.Failed(ActionResult.Reason.EXECUTION_FAILED, "命令未能执行")
        return if (shell.ok) ActionResult.Ok("已结束 " + packageName + "，重新打开即可生效")
        else ActionResult.Failed(ActionResult.Reason.EXECUTION_FAILED, shell.output.trim())
    }
}

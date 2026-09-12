package com.actionmental.core.diag

import com.actionmental.core.action.ActionResult
import com.actionmental.platform.PrivilegedBackend

/** 一条检查结论。UNKNOWN 表示「没查出来」，永远不能被折叠成 OK。 */
enum class Verdict(val label: String) {
    OK("正常"),
    WARN("可能受影响"),
    BLOCKER("这就是没转的原因"),
    UNKNOWN("未能判定"),
}

data class DiagnosisFinding(
    val title: String,
    val verdict: Verdict,
    val detail: String,
    val advice: String = "",
)

data class DiagnosisReport(
    val packageName: String?,
    val findings: List<DiagnosisFinding>,
    val timestampMs: Long,
) {
    val blocked: Boolean get() = findings.any { it.verdict == Verdict.BLOCKER }
}

/**
 * 回答一个具体问题：全局强制已经打开了，为什么**这个**应用还是竖着的。
 *
 * 只跑只读命令，不改任何系统状态。每条命令的原始输出都会经 [ShellLog] 落到界面上，
 * 解析失败时给 UNKNOWN 而不是猜 —— 判断可以错，日志原文不会错。
 */
class RotationDiagnostics(private val backend: () -> PrivilegedBackend) {

    suspend fun run(packageName: String?): DiagnosisReport {
        val b = backend()
        val availability = b.availability()
        if (availability is ActionResult.Failed) {
            return DiagnosisReport(
                packageName = packageName,
                findings = listOf(
                    DiagnosisFinding(
                        "特权后端",
                        Verdict.BLOCKER,
                        availability.reason.message,
                        "先在「Shizuku 与磁贴」页恢复授权，诊断需要 shell 才能读取窗口状态。",
                    )
                ),
                timestampMs = System.currentTimeMillis(),
            )
        }

        val findings = mutableListOf<DiagnosisFinding>()
        findings += checkGlobalIgnore(b)
        findings += checkDisplays(b)
        if (packageName != null) {
            findings += checkRequestedOrientation(b, packageName)
            findings += checkResizeability(b, packageName)
            findings += checkCompatOverrides(b, packageName)
        } else {
            findings += DiagnosisFinding(
                "前台应用",
                Verdict.UNKNOWN,
                "没有拿到前台包名",
                "无障碍服务未连接时读不到前台应用，只能做全局层面的检查。",
            )
        }
        return DiagnosisReport(packageName, findings, System.currentTimeMillis())
    }

    private suspend fun checkGlobalIgnore(b: PrivilegedBackend): DiagnosisFinding {
        val out = text(b, "cmd window get-ignore-orientation-request")
            ?: return DiagnosisFinding(
                "忽略应用方向请求（默认屏幕）",
                Verdict.UNKNOWN,
                "命令执行失败",
                "部分 OEM 把 config_ignoreOrientationRequestDisabled 设为 true，整个开关不存在。",
            )
        return when {
            out.contains("true", ignoreCase = true) -> DiagnosisFinding(
                "忽略应用方向请求（默认屏幕）", Verdict.OK, out,
                )
            else -> DiagnosisFinding(
                "忽略应用方向请求（默认屏幕）", Verdict.BLOCKER, out,
                "开关没生效。若刚设置过就回读为 false，说明系统拒绝了这次写入（多见于 OEM 定制固件）。",
            )
        }
    }

    /**
     * 折叠屏的关键一步：ignore-orientation-request 是**按屏幕**存的。
     * 内外屏若是两个 display，只对 display 0 设过的强制在另一块屏上并不存在。
     */
    private suspend fun checkDisplays(b: PrivilegedBackend): DiagnosisFinding {
        val out = text(b, "dumpsys window displays | grep -iE 'Display: mDisplayId|mIgnoreOrientationRequest|init=[0-9]'")
            ?: return DiagnosisFinding("屏幕列表", Verdict.UNKNOWN, "dumpsys window displays 读取失败")

        val ids = Regex("""mDisplayId=(\d+)""").findAll(out).map { it.groupValues[1] }.toList()
        val ignores = Regex("""mIgnoreOrientationRequest=(\w+)""").findAll(out).map { it.groupValues[1] }.toList()
        val offenders = ignores.count { it.equals("false", ignoreCase = true) }

        return when {
            ids.isEmpty() -> DiagnosisFinding("屏幕列表", Verdict.UNKNOWN, out.take(400))
            ids.size == 1 -> DiagnosisFinding(
                "屏幕列表", Verdict.OK, "只有 display " + ids.first() + " · ignore=" + ignores.joinToString(),
            )
            offenders > 0 -> DiagnosisFinding(
                "屏幕列表（多屏）", Verdict.BLOCKER,
                "display " + ids.joinToString() + " · ignore=" + ignores.joinToString(),
                "这台设备有多块屏幕，其中 " + offenders + " 块没有开启忽略方向请求。" +
                    "用下面的「对所有屏幕下发」把设置推到每一块屏上。",
            )
            else -> DiagnosisFinding(
                "屏幕列表（多屏）", Verdict.OK,
                "display " + ids.joinToString() + " 全部 ignore=true",
            )
        }
    }

    private suspend fun checkRequestedOrientation(b: PrivilegedBackend, pkg: String): DiagnosisFinding {
        val out = text(b, "dumpsys window visible-apps | grep -iE 'mActivityRecord|screenOrientation|mOrientation|mLastReportedConfiguration' | head -40")
        if (out.isNullOrBlank()) {
            return DiagnosisFinding(
                "应用请求的方向", Verdict.UNKNOWN, "未能从 dumpsys window 读到方向字段",
                "不同 Android 版本字段名不一致，请直接看下方日志原文。",
            )
        }
        val requested = Regex("""screenOrientation=(\S+)""").find(out)?.groupValues?.get(1)
        return when {
            requested == null -> DiagnosisFinding("应用请求的方向", Verdict.UNKNOWN, out.take(400))
            requested.contains("PORTRAIT", ignoreCase = true) -> DiagnosisFinding(
                "应用请求的方向", Verdict.WARN, "screenOrientation=" + requested,
                "应用主动锁了竖屏。全局忽略开关生效时这一项不致命，仍然转不动则说明开关在本机被架空。",
            )
            else -> DiagnosisFinding("应用请求的方向", Verdict.OK, "screenOrientation=" + requested)
        }
    }

    /**
     * 第二层拦截：应用声明 resizeableActivity=false 时，方向能转过去，
     * 画面却停在尺寸兼容模式里加黑边 —— 看起来就像「没有横屏」。
     */
    private suspend fun checkResizeability(b: PrivilegedBackend, pkg: String): DiagnosisFinding {
        val out = text(b, "dumpsys package " + pkg + " | grep -iE 'resizeMode|AspectRatio|flags=.*RESIZEABLE' | head -10")
        if (out.isNullOrBlank()) {
            return DiagnosisFinding("是否可调整大小", Verdict.UNKNOWN, "dumpsys package 未返回 resizeMode")
        }
        return if (out.contains("UNRESIZEABLE", ignoreCase = true)) {
            DiagnosisFinding(
                "是否可调整大小", Verdict.BLOCKER, out.take(300),
                "应用声明了不可调整大小，会进入尺寸兼容模式（横过来但仍是竖屏画面加黑边）。" +
                    "需要下面的「应用级兼容覆盖」把 FORCE_RESIZE_APP 一并打开。",
            )
        } else {
            DiagnosisFinding("是否可调整大小", Verdict.OK, out.take(300))
        }
    }

    private suspend fun checkCompatOverrides(b: PrivilegedBackend, pkg: String): DiagnosisFinding {
        val out = text(b, "dumpsys platform_compat | grep -i " + pkg + " | head -20")
        return if (out.isNullOrBlank()) {
            DiagnosisFinding(
                "已生效的兼容覆盖", Verdict.WARN, "该应用没有任何 compat override",
                "全局开关拿不下它时，用下面的「应用级兼容覆盖」逐条施加。",
            )
        } else {
            DiagnosisFinding("已生效的兼容覆盖", Verdict.OK, out.take(500))
        }
    }

    private suspend fun text(b: PrivilegedBackend, command: String): String? =
        b.exec(command).getOrNull()?.takeIf { it.ok }?.output?.trim()?.takeIf { it.isNotEmpty() }
}

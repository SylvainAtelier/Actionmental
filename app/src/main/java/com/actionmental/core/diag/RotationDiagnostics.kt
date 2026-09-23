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
 * 默认屏幕此刻的方向由谁决定，取自 `dumpsys window displays`。
 *
 * 角度一律是 Surface.ROTATION_* 的 0..3。解析不到的字段是 null，不猜。
 */
data class OrientationProbe(
    /** 方向来源的包名；来源是系统窗口（通知栏等）时为 null。 */
    val sourcePackage: String?,
    val requested: String?,
    val ignore: Boolean?,
    val rotation: Int?,
    val userRotation: Int?,
    val locked: Boolean,
    val raw: String,
) {
    /**
     * 忽略开关开着，屏幕却不在该在的角度上：有人的方向请求被系统放行了。
     *
     * [expected] 是强制意图要求的角度。不能只拿锁定角度比 —— 屏幕被拉到 0° 后
     * SystemUI 会把锁定角度也改成 0，两者又对上了，现场就被抹平了。
     * 不知道意图时才退回用锁定角度比。
     */
    fun pulledAway(expected: Int?): Boolean {
        val target = expected ?: userRotation?.takeIf { locked } ?: return false
        return ignore == true && rotation != null && rotation != target
    }

    val summary: String
        get() = "来源=" + (sourcePackage ?: "系统窗口") + " · 请求=" + (requested ?: "?") +
            " · ignore=" + (ignore ?: "?") + " · 实际=" + angle(rotation) + " · 锁定=" +
            (if (locked) angle(userRotation) else "自动")

    companion object {
        fun parse(out: String): OrientationProbe {
            val source = Regex("""deepestLastOrientationSource=(.+)""").find(out)?.groupValues?.get(1)
            return OrientationProbe(
                // ActivityRecord{46049331 u0 com.phoenix.read/com.dragon…Activity t31796}
                sourcePackage = source?.let { Regex("""u\d+ ([\w.]+)/""").find(it)?.groupValues?.get(1) },
                requested = Regex("""mCurrentAppOrientation=SCREEN_ORIENTATION_(\w+)""").find(out)?.groupValues?.get(1),
                ignore = Regex("""ignoreOrientationRequest=(true|false)""").find(out)?.groupValues?.get(1)?.toBoolean(),
                rotation = Regex("""mRotation=(\d)""").find(out)?.groupValues?.get(1)?.toIntOrNull(),
                userRotation = Regex("""mUserRotation=ROTATION_(\d+)""").find(out)?.groupValues?.get(1)
                    ?.toIntOrNull()?.div(90),
                locked = out.contains("USER_ROTATION_LOCKED"),
                raw = out,
            )
        }

        private fun angle(rotation: Int?): String = if (rotation == null) "?" else (rotation * 90).toString() + "°"
    }
}

/**
 * 回答一个具体问题：全局强制已经打开了，为什么**这个**应用还是竖着的。
 *
 * 只跑只读命令，不改任何系统状态。每条命令的原始输出都会经 [ShellLog] 落到界面上，
 * 解析失败时给 UNKNOWN 而不是猜 —— 判断可以错，日志原文不会错。
 */
class RotationDiagnostics(private val backend: () -> PrivilegedBackend) {

    /** @param expectedRotation 强制意图要求的角度（Surface.ROTATION_*），不在强制时传 null。 */
    suspend fun run(packageName: String?, expectedRotation: Int? = null): DiagnosisReport {
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
            findings += checkOrientationSource(b, packageName, expectedRotation)
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

    /**
     * 最直接的一问：忽略开关开着，屏幕方向却仍然是这个应用说了算吗。
     *
     * ColorOS 平板上实测过：红果短剧的竖屏请求在 ignore=true、锁定 270° 时照样把屏幕拉回 0°，
     * 而抖音同样的竖屏请求就被压住了 —— 系统按应用放行，没有任何公开开关能看到这份名单。
     * 能看到的只有结果：显示屏的方向来源就是这个应用，且实际角度不是强制意图要的那个。
     */
    private suspend fun checkOrientationSource(b: PrivilegedBackend, pkg: String, expectedRotation: Int?): DiagnosisFinding {
        val title = "系统是否仍采纳该应用的方向"
        val probe = probeOrientation(b)
            ?: return DiagnosisFinding(title, Verdict.UNKNOWN, "dumpsys window displays 读取失败")
        return when {
            probe.ignore == null || probe.rotation == null -> DiagnosisFinding(title, Verdict.UNKNOWN, probe.raw.take(400))
            probe.ignore != true -> DiagnosisFinding(
                title, Verdict.OK, probe.summary, "忽略开关没开（未处于强制方向），应用自带方向优先是预期行为。",
            )
            probe.sourcePackage == pkg && probe.pulledAway(expectedRotation) -> DiagnosisFinding(
                title, Verdict.BLOCKER, probe.summary,
                "系统对这个应用网开一面：忽略开关打开，屏幕仍被它的方向请求拉走。" +
                    "用下面的「应用级兼容覆盖」（含 OVERRIDE_ANY_ORIENTATION_TO_USER）后结束并重开该应用。",
            )
            probe.sourcePackage != pkg -> DiagnosisFinding(
                title, Verdict.UNKNOWN, probe.summary,
                "此刻主导方向的不是它（诊断页本身就在前台）。事件日志里「方向被应用拉走」那一条记着当时的现场。",
            )
            else -> DiagnosisFinding(title, Verdict.OK, probe.summary)
        }
    }

    /** 读一次「此刻屏幕方向由谁决定」。null 表示 shell 不可用或 dumpsys 没回来。 */
    suspend fun probeOrientation(): OrientationProbe? {
        val b = backend()
        if (b.availability() is ActionResult.Failed) return null
        return probeOrientation(b)
    }

    private suspend fun probeOrientation(b: PrivilegedBackend): OrientationProbe? = text(
        b,
        "dumpsys window displays | grep -E 'deepestLastOrientationSource|ignoreOrientationRequest=|mCurrentAppOrientation|mRotation=|mUserRotationMode' | head -12",
    )?.let(OrientationProbe::parse)

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

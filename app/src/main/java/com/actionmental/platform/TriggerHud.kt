package com.actionmental.platform

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.actionmental.core.action.TriggerFeedback

/**
 * 快捷键触发提示：屏幕顶部居中的一枚深色胶囊。
 *
 * 和旋转、常亮那两层一样挂 `TYPE_ACCESSIBILITY_OVERLAY`，不需要悬浮窗权限；
 * 不可触、不可聚焦，所以它压在任何应用上面都不会吃掉一次点击或一颗按键。
 * 放在顶部是因为键盘用户的视线在屏幕上半部分，底部又有系统自己的复制预览，两者不打架。
 *
 * 连按时原地换字、重新计时，不排队也不叠加 —— 屏幕上永远只有最新的那一条。
 * 胶囊固定用深色：它浮在别人的界面上，跟着本应用主题走反而在浅色页面上看不清。
 *
 * 后台 Termux 命令在同一枚胶囊上走完全程：运行中转圈计时，结果回来原地展开成结果卡
 * （见 [TriggerFeedback]）。结果卡宽度取「屏宽减两侧留白」与 [CARD_MAX_DP] 的较小者：
 * 折叠外屏上自动收窄，平板上不横跨整屏。宽度每次显示时按当时的窗口量，转屏后也对。
 */
class TriggerHud(
    private val serviceProvider: () -> AccessibilityService?,
) {

    private val main = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { onHold() }
    private val tickRunnable = object : Runnable {
        override fun run() {
            metaView.text = TriggerFeedback.seconds(SystemClock.elapsedRealtime() - pendingSince)
            main.postDelayed(this, TICK_MS)
        }
    }

    /** 以下只在主线程读写。 */
    private var root: LinearLayout? = null
    private var owner: AccessibilityService? = null
    private var params: WindowManager.LayoutParams? = null
    private var current: TriggerFeedback? = null
    private var pendingSince = 0L
    private lateinit var icon: ImageView
    private lateinit var dot: View
    private lateinit var spinner: ProgressBar
    private lateinit var titleView: TextView
    private lateinit var valueView: TextView
    private lateinit var checkView: TextView
    private lateinit var metaView: TextView
    private lateinit var outputView: TextView

    fun show(feedback: TriggerFeedback) {
        main.post { runCatching { render(feedback) } }
    }

    private fun render(feedback: TriggerFeedback) {
        val service = serviceProvider() ?: return
        val view = ensureAttached(service) ?: return

        val appIcon = feedback.packageName?.let { pkg ->
            runCatching { service.packageManager.getApplicationIcon(pkg) }.getOrNull()
        }
        icon.setImageDrawable(appIcon)
        icon.visibility = if (appIcon != null) View.VISIBLE else View.GONE
        // 失败时用一个小红点而不是整块变红：出错是少数情况，不值得吓人一跳
        dot.visibility = if (feedback.failed) View.VISIBLE else View.GONE
        spinner.visibility = if (feedback.pending) View.VISIBLE else View.GONE
        titleView.text = feedback.title
        valueView.text = feedback.value.orEmpty()
        valueView.visibility = if (feedback.value != null) View.VISIBLE else View.GONE
        valueView.setTextColor(if (feedback.failed) INK_MID else INK)
        checkView.visibility = if (feedback.succeeded) View.VISIBLE else View.GONE
        metaView.text = feedback.meta.orEmpty()
        metaView.visibility = if (feedback.pending || !feedback.meta.isNullOrEmpty()) View.VISIBLE else View.GONE
        outputView.text = feedback.output.orEmpty()
        outputView.visibility = if (feedback.output != null) View.VISIBLE else View.GONE
        outputView.setTextColor(if (feedback.failed) INK_ERR else INK)
        // 计时只在屏幕上跳，不进读屏：每 100ms 播报一次秒数谁都受不了
        view.contentDescription = listOfNotNull(feedback.title, feedback.value, feedback.meta, feedback.output)
            .filter { it.isNotEmpty() }
            .joinToString(" ")
        layout(service, view, card = feedback.output != null)

        main.removeCallbacks(hideRunnable)
        main.removeCallbacks(tickRunnable)
        current = feedback
        if (feedback.pending) {
            pendingSince = SystemClock.elapsedRealtime()
            tickRunnable.run()
        }
        view.animate().cancel()
        if (view.visibility != View.VISIBLE || view.alpha < 1f) {
            // 从上方轻落 6dp：位移小到不像「弹窗」，只让眼睛知道它刚出现
            view.visibility = View.VISIBLE
            if (view.alpha == 0f) view.translationY = -dp(service, 6f)
            view.animate().alpha(1f).translationY(0f)
                .setDuration(IN_MS).setInterpolator(DecelerateInterpolator()).start()
        }
        main.postDelayed(hideRunnable, feedback.holdMs)
    }

    /** 停留到点：有下一条就原地换过去（运行太久 → 「仍在运行」），没有就淡出。 */
    private fun onHold() {
        val next = current?.then
        if (next != null) runCatching { render(next) } else fadeOut()
    }

    /**
     * 胶囊包住内容；结果卡定宽，输出块才排得开。
     * 宽度变了才更新窗口，同一种样子连按时不重排。
     */
    private fun layout(service: AccessibilityService, view: View, card: Boolean) {
        val wm = service.getSystemService(WindowManager::class.java) ?: return
        val lp = params ?: return
        val screen = windowWidth(wm, service)
        val width = if (card) {
            minOf(screen - dp(service, CARD_MARGIN_DP * 2).toInt(), dp(service, CARD_MAX_DP).toInt())
        } else {
            WindowManager.LayoutParams.WRAP_CONTENT
        }
        // 胶囊里两段文字各不过半屏，截断交给各自的省略号；结果卡里标题吃掉剩下的宽度，结论顶到右边
        val half = (screen * 0.5f).toInt()
        titleView.maxWidth = if (card) Int.MAX_VALUE else half
        valueView.maxWidth = half
        titleView.layoutParams = (titleView.layoutParams as LinearLayout.LayoutParams).apply {
            this.width = if (card) 0 else LinearLayout.LayoutParams.WRAP_CONTENT
            weight = if (card) 1f else 0f
        }
        if (lp.width != width) {
            lp.width = width
            runCatching { wm.updateViewLayout(view, lp) }
        }
    }

    private fun fadeOut() {
        main.removeCallbacks(tickRunnable)
        current = null
        val view = root ?: return
        view.animate().alpha(0f)
            .setDuration(OUT_MS).setInterpolator(AccelerateInterpolator())
            .withEndAction { if (view.alpha == 0f) view.visibility = View.INVISIBLE }
            .start()
    }

    /** 窗口跟着服务实例走：实例换了（服务重绑）就在新实例上重建，旧的一并摘掉。 */
    private fun ensureAttached(service: AccessibilityService): View? {
        val current = root
        if (current != null && owner === service && current.parent != null) return current
        detach()

        val wm = service.getSystemService(WindowManager::class.java) ?: return null
        val view = build(service)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            // 状态栏下方留一点空：贴着状态栏会被当成系统通知，离太远又要额外移动视线
            y = statusBarHeight(wm) + dp(service, 10f).toInt()
            windowAnimations = 0
            title = TAG
        }
        return runCatching { wm.addView(view, params) }
            .onSuccess {
                root = view
                owner = service
                this.params = params
            }
            .map { view }
            .getOrNull()
    }

    private fun build(context: Context): LinearLayout {
        val padH = dp(context, 14f).toInt()
        val padV = dp(context, 8f).toInt()
        val gap = dp(context, 8f).toInt()

        icon = ImageView(context).apply {
            val size = dp(context, 20f).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = gap }
        }
        dot = View(context).apply {
            val size = dp(context, 6f).toInt()
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(ACCENT)
            }
            layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = gap }
        }
        // 系统关了动画时它自己就不转，减弱动态效果不用另外判断
        spinner = ProgressBar(context, null, android.R.attr.progressBarStyleSmall).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(INK)
            val size = dp(context, 14f).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = gap }
        }
        titleView = TextView(context).apply {
            setTextColor(INK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        valueView = TextView(context).apply {
            setTextColor(INK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            typeface = Typeface.MONOSPACE
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.MIDDLE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = gap }
        }
        // 结论：对勾 + 耗时，或退出码。数字等宽，计时跳动时宽度不抖
        checkView = TextView(context).apply {
            text = "✓"
            setTextColor(OK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = dp(context, 12f).toInt() }
        }
        metaView = TextView(context).apply {
            setTextColor(INK_MID)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.MONOSPACE
            isSingleLine = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = gap / 2 }
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(icon)
            addView(dot)
            addView(spinner)
            addView(titleView)
            addView(valueView)
            addView(checkView)
            addView(metaView)
        }
        // 输出不折行、超宽的部分裁掉：像终端一样一行是一行，末尾那几行永远都在
        outputView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.MONOSPACE
            setLineSpacing(0f, 1.2f)
            setHorizontallyScrolling(true)
            maxLines = TriggerFeedback.OUTPUT_LINES
            val padX = dp(context, 10f).toInt()
            val padY = dp(context, 8f).toInt()
            setPadding(padX, padY, padX, padY)
            background = GradientDrawable().apply {
                cornerRadius = dp(context, 10f)
                setColor(WELL)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = gap }
            visibility = View.GONE
        }
        // 读屏只念根节点上拼好的那一句；子视图各自再念一遍就重复了，计时还会每 100ms 报一次
        listOf(spinner, titleView, valueView, checkView, metaView, outputView).forEach {
            it.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(padH, padV, padH, padV)
            minimumHeight = dp(context, 36f).toInt()
            background = GradientDrawable().apply {
                cornerRadius = dp(context, 18f)
                setColor(SURFACE)
                setStroke(dp(context, 1f).toInt(), LINE)
            }
            elevation = dp(context, 6f)
            addView(
                header,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(outputView)
            alpha = 0f
            visibility = View.INVISIBLE
            // 读屏用户也得知道发生了什么，礼貌地播报，不打断正在读的内容
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
    }

    private fun detach() {
        val view = root ?: return
        val service = owner
        root = null
        owner = null
        params = null
        main.removeCallbacks(hideRunnable)
        main.removeCallbacks(tickRunnable)
        runCatching { service?.getSystemService(WindowManager::class.java)?.removeViewImmediate(view) }
    }

    /** 读真实的窗口边衬：横屏、隐藏状态栏的沉浸模式下它会变，资源里那个常量不会。 */
    private fun statusBarHeight(wm: WindowManager): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return 0
        return runCatching {
            wm.currentWindowMetrics.windowInsets
                .getInsetsIgnoringVisibility(WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout())
                .top
        }.getOrDefault(0)
    }

    /** 当前窗口的真实宽度：转屏、折叠屏内外屏切换后跟着变，displayMetrics 不一定跟得上。 */
    private fun windowWidth(wm: WindowManager, context: Context): Int {
        val fallback = context.resources.displayMetrics.widthPixels
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return fallback
        return runCatching { wm.currentWindowMetrics.bounds.width() }.getOrDefault(fallback)
    }

    private fun dp(context: Context, value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics)

    private companion object {
        /** 出现在 `dumpsys window` 里。 */
        const val TAG = "Actionmental:hud"

        const val IN_MS = 160L
        const val OUT_MS = 220L
        const val TICK_MS = 100L

        const val CARD_MARGIN_DP = 12f
        const val CARD_MAX_DP = 560f

        // 取自设计令牌的深色一档（design-tokens.css）
        val SURFACE = Color.parseColor("#F0201F1C")
        val LINE = Color.parseColor("#302E29")
        val INK = Color.parseColor("#F2EEE6")
        val INK_MID = Color.parseColor("#A29A8E")
        val ACCENT = Color.parseColor("#E4653F")
        val OK = Color.parseColor("#7BC59A")

        /** 失败输出用的暖色：比正文淡一档，又不至于红得刺眼。 */
        val INK_ERR = Color.parseColor("#F1C9BC")

        /** 输出块底色：在卡片上再压暗一层，像一小块终端。 */
        val WELL = Color.parseColor("#40000000")
    }
}

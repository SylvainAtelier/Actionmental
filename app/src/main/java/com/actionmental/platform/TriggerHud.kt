package com.actionmental.platform

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
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
 */
class TriggerHud(
    private val serviceProvider: () -> AccessibilityService?,
) {

    private val main = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { fadeOut() }

    /** 以下只在主线程读写。 */
    private var root: LinearLayout? = null
    private var owner: AccessibilityService? = null
    private lateinit var icon: ImageView
    private lateinit var dot: View
    private lateinit var titleView: TextView
    private lateinit var valueView: TextView

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
        titleView.text = feedback.title
        valueView.text = feedback.value.orEmpty()
        valueView.visibility = if (feedback.value != null) View.VISIBLE else View.GONE
        valueView.setTextColor(if (feedback.failed) INK_MID else INK)
        view.contentDescription = listOfNotNull(feedback.title, feedback.value).joinToString(" ")

        main.removeCallbacks(hideRunnable)
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

    private fun fadeOut() {
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
            }
            .map { view }
            .getOrNull()
    }

    private fun build(context: Context): LinearLayout {
        val padH = dp(context, 14f).toInt()
        val padV = dp(context, 8f).toInt()
        val gap = dp(context, 8f).toInt()
        val screenWidth = context.resources.displayMetrics.widthPixels

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
        titleView = TextView(context).apply {
            setTextColor(INK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
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

        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(padH, padV, padH, padV)
            minimumHeight = dp(context, 36f).toInt()
            background = GradientDrawable().apply {
                cornerRadius = dp(context, 18f)
                setColor(SURFACE)
                setStroke(dp(context, 1f).toInt(), LINE)
            }
            elevation = dp(context, 6f)
            addView(icon)
            addView(dot)
            addView(titleView)
            addView(valueView)
            // 再长的应用名也不该横跨整屏，截断交给两段文字各自的省略号
            titleView.maxWidth = (screenWidth * 0.5f).toInt()
            valueView.maxWidth = (screenWidth * 0.5f).toInt()
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
        main.removeCallbacks(hideRunnable)
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

    private fun dp(context: Context, value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics)

    private companion object {
        /** 出现在 `dumpsys window` 里。 */
        const val TAG = "Actionmental:hud"

        const val IN_MS = 160L
        const val OUT_MS = 220L

        // 取自设计令牌的深色一档（design-tokens.css）
        val SURFACE = Color.parseColor("#F0201F1C")
        val LINE = Color.parseColor("#302E29")
        val INK = Color.parseColor("#F2EEE6")
        val INK_MID = Color.parseColor("#A29A8E")
        val ACCENT = Color.parseColor("#E4653F")
    }
}

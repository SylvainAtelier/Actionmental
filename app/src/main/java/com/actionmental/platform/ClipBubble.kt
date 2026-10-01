package com.actionmental.platform

import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import com.actionmental.R
import com.actionmental.core.clip.BubbleGeometry
import com.actionmental.ui.theme.DarkAmColors
import com.actionmental.ui.theme.LightAmColors
import kotlin.math.abs

/**
 * 常驻屏幕边缘的剪贴板按钮：点一下唤出剪贴板面板，拖动换位置，松手吸到最近的一侧。
 *
 * 和面板一样挂 `TYPE_ACCESSIBILITY_OVERLAY`，不需要悬浮窗权限；并且**不可聚焦** ——
 * 点它不会把焦点从正在打字的输入框上抢走，于是「点按钮 → 点一条」就能直接写进原来的框里，
 * 这是不接键盘时最顺手的一条路。
 *
 * 平时半透明退到一边，手指碰到才变实：它常年占着屏幕一角，不该比内容更抢眼。
 * 除 [show] / [hide] 外全在主线程。
 */
class ClipBubble(
    private val serviceProvider: () -> AccessibilityService?,
    /** 上次放在哪：右侧与否、竖直比例。 */
    private val position: () -> Pair<Boolean, Float>,
    private val onTap: () -> Unit,
    /** 拖完松手：记下新位置。 */
    private val onMoved: (onRight: Boolean, fraction: Float) -> Unit,
    private val dark: () -> Boolean,
    private val description: () -> String,
    private val onError: (what: String, error: Throwable) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())

    /** 以下只在主线程读写。 */
    private var view: BubbleView? = null
    private var owner: AccessibilityService? = null

    fun show() = main.post {
        guarded("显示剪贴板按钮") {
            val service = serviceProvider() ?: return@guarded
            // 服务实例换过（重新绑定）：旧窗口跟着旧实例作废，在新实例上重挂
            if (view != null && owner === service) return@guarded
            detach()
            attach(service)
        }
    }

    fun hide() = main.post { guarded("隐藏剪贴板按钮") { detach() } }

    private inline fun guarded(what: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            onError(what, t)
            runCatching { detach() }
        }
    }

    private fun attach(service: AccessibilityService) {
        val wm = service.getSystemService(WindowManager::class.java) ?: return
        val bubble = BubbleView(service, wm)
        val (right, fraction) = position()
        val params = WindowManager.LayoutParams(
            bubble.touchSize,
            bubble.touchSize,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            windowAnimations = 0
            title = TAG
        }
        bubble.params = params
        bubble.dock(right, fraction)
        wm.addView(bubble, params)
        view = bubble
        owner = service
        bubble.idleSoon()
    }

    private fun detach() {
        val current = view ?: return
        view = null
        current.release()
        runCatching { owner?.getSystemService(WindowManager::class.java)?.removeViewImmediate(current) }
        owner = null
    }

    /**
     * 按钮本体。窗口比圆大一圈（48dp 触控区包着 44dp 的圆），手指不用对得那么准。
     *
     * 拖动时窗口用左上角坐标跟手；松手吸边后改成「贴左 / 贴右 + 边距」的相对定位，
     * 这样转屏后不用重算横坐标，自然还贴在同一侧。
     */
    private inner class BubbleView(context: Context, private val wm: WindowManager) : FrameLayout(context) {
        val touchSize = dp(48f)
        private val margin = dp(6f)
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        lateinit var params: WindowManager.LayoutParams

        private var onRight = true
        private var fraction = 0.6f
        private var downRawX = 0f
        private var downRawY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false
        private var snap: ValueAnimator? = null
        private val fade = Runnable { animate().alpha(IDLE_ALPHA).setDuration(FADE_MS).start() }

        private val circle = GradientDrawable().apply { shape = GradientDrawable.OVAL }
        private val icon = ImageView(context).apply {
            setImageResource(R.drawable.ic_clip_bubble)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            val pad = dp(11f)
            setPadding(pad, pad, pad, pad)
            // 触控落在外圈的窗口上，圆跟着它的按下态走，水波纹才出得来
            isDuplicateParentStateEnabled = true
        }

        init {
            val size = dp(44f)
            addView(icon, LayoutParams(size, size, Gravity.CENTER))
            icon.elevation = dp(6f).toFloat()
            isClickable = true
            setOnClickListener { onTap() }
            contentDescription = description()
            applyTheme()
        }

        /** 跟着主题换色：浅色是白底橙图标，深色是深底橙图标，在任何背景上都有一圈细边托住。 */
        private fun applyTheme() {
            val c = if (dark()) DarkAmColors else LightAmColors
            circle.setColor(c.surface.toArgb())
            circle.setStroke(dp(1f), c.line.toArgb())
            val ripple = ColorUtils.setAlphaComponent(c.accent.toArgb(), 0x40)
            icon.background = RippleDrawable(ColorStateList.valueOf(ripple), circle, null)
            icon.imageTintList = ColorStateList.valueOf(c.accent.toArgb())
        }

        fun dock(right: Boolean, y: Float) {
            onRight = right
            fraction = y
            val (_, h, insets) = screen()
            params.gravity = Gravity.TOP or if (right) Gravity.END else Gravity.START
            params.x = margin + if (right) insets[2] else insets[0]
            params.y = BubbleGeometry.yOf(fraction, h, touchSize, insets[1], insets[3])
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    removeCallbacks(fade)
                    animate().cancel()
                    alpha = 1f
                    snap?.cancel()
                    downRawX = event.rawX
                    downRawY = event.rawY
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) {
                        dragging = true
                        beginDrag()
                        // 拖起来了就不是点击：给默认处理补一个 CANCEL，按下态、水波纹、待触发的点击一并撤掉
                        val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                        super.onTouchEvent(cancel)
                        cancel.recycle()
                    }
                    if (dragging) {
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        relayout()
                        return true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        dragging = false
                        settle()
                        idleSoon()
                        return true
                    }
                    idleSoon()
                }
            }
            return super.onTouchEvent(event)
        }

        /** 拖动期间换成绝对的左上角坐标，跟手最直接。 */
        private fun beginDrag() {
            val (w, _, _) = screen()
            val left = if (onRight) w - params.x - touchSize else params.x
            params.gravity = Gravity.TOP or Gravity.START
            params.x = left
            startX = left
            startY = params.y
        }

        /** 松手：离哪边近就滑过去贴住，并记下位置。 */
        private fun settle() {
            val (w, h, insets) = screen()
            val right = BubbleGeometry.snapToRight(params.x + touchSize / 2, w)
            val y = BubbleGeometry.fractionOf(params.y, h, touchSize, insets[1], insets[3])
            val targetY = BubbleGeometry.yOf(y, h, touchSize, insets[1], insets[3])
            val targetX = if (right) w - touchSize - margin - insets[2] else margin + insets[0]
            val fromX = params.x
            val fromY = params.y
            snap = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = SNAP_MS
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    val t = it.animatedFraction
                    params.x = (fromX + (targetX - fromX) * t).toInt()
                    params.y = (fromY + (targetY - fromY) * t).toInt()
                    relayout()
                }
                doOnEnd {
                    dock(right, y)
                    relayout()
                    onMoved(right, y)
                }
                start()
            }
        }

        fun idleSoon() {
            removeCallbacks(fade)
            postDelayed(fade, IDLE_DELAY_MS)
        }

        fun release() {
            removeCallbacks(fade)
            snap?.cancel()
        }

        /** 转屏、分屏、切深浅色：按记下的比例重新落位，并换色。 */
        override fun onConfigurationChanged(newConfig: Configuration) {
            super.onConfigurationChanged(newConfig)
            applyTheme()
            if (!dragging) {
                dock(onRight, fraction)
                relayout()
            }
        }

        private fun relayout() {
            if (isAttachedToWindow) runCatching { wm.updateViewLayout(this, params) }
        }

        /** 屏幕宽、高与四边要让开的系统栏（左、上、右、下）。 */
        private fun screen(): Triple<Int, Int, IntArray> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val metrics = wm.currentWindowMetrics
                val i = metrics.windowInsets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
                )
                return Triple(metrics.bounds.width(), metrics.bounds.height(), intArrayOf(i.left, i.top, i.right, i.bottom))
            }
            val dm = context.resources.displayMetrics
            return Triple(dm.widthPixels, dm.heightPixels, intArrayOf(0, dp(24f), 0, dp(48f)))
        }

        private fun dp(value: Float): Int =
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics).toInt()
    }

    private inline fun ValueAnimator.doOnEnd(crossinline block: () -> Unit) {
        addListener(object : android.animation.AnimatorListenerAdapter() {
            private var cancelled = false
            override fun onAnimationCancel(animation: android.animation.Animator) {
                cancelled = true
            }

            override fun onAnimationEnd(animation: android.animation.Animator) {
                if (!cancelled) block()
            }
        })
    }

    private companion object {
        /** 出现在 `dumpsys window` 里。 */
        const val TAG = "Actionmental:clipbubble"
        const val IDLE_ALPHA = 0.55f
        const val IDLE_DELAY_MS = 2_500L
        const val FADE_MS = 300L
        const val SNAP_MS = 220L
    }
}

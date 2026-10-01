package com.actionmental.platform

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import com.actionmental.core.action.ActionResult
import com.actionmental.core.clip.ClipEntry
import com.actionmental.core.clip.ClipPanelState
import com.actionmental.core.clip.ClipPolicy
import com.actionmental.core.clip.PanelEffect
import com.actionmental.core.clip.PanelKey
import com.actionmental.core.clip.PanelKeys
import com.actionmental.core.key.KeyPipeline
import com.actionmental.core.key.NormalizedKeyEvent
import com.actionmental.data.ClipHistoryStore
import com.actionmental.ui.theme.AmColors
import com.actionmental.ui.theme.DarkAmColors
import com.actionmental.ui.theme.LightAmColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 剪贴板历史面板：快捷键唤出，浮在当前应用上面。
 *
 * 窗口和触发提示一样挂 `TYPE_ACCESSIBILITY_OVERLAY`，并且**不可聚焦** —— 原来那个输入框的
 * 焦点和输入连接一直留着，选中之后直接经无障碍输入通道上屏，不用等焦点回来、也没有闪烁。
 * 不可聚焦也意味着收不到按键，所以按键改由 [KeyPipeline.modal] 在无障碍服务那一层截下来交给面板。
 *
 * 版式跟着窗口走（见 [PanelRoot]）：宽度随屏宽、封顶 600dp；高度随内容、到可用空间为止，
 * 再多就在列表里滚。列表是 ListView：只为看得见的那几行建视图并循环复用，数据按页懒加载，
 * 每行只拿预览 —— 几万条历史时滑动也只动十来个视图、几十条数据。
 *
 * 状态机在 [ClipPanelState]，这里只管窗口、绘制和把效果落地。除 [toggle] / [dismiss] 外全在主线程。
 */
class ClipPanel(
    private val serviceProvider: () -> AccessibilityService?,
    private val pipeline: KeyPipeline,
    private val store: ClipHistoryStore,
    private val scope: CoroutineScope,
    /** 记录开着没有。没开时空列表要说清楚为什么是空的。 */
    private val capturing: () -> Boolean,
    /** 这颗键是不是唤出面板的那个快捷键：再按一次就是关上。 */
    private val isToggle: (NormalizedKeyEvent) -> Boolean,
    private val onInsert: (ClipEntry) -> Unit,
    /** 删除 / 置顶改过库之后通知一声，让去重忘掉最近那一条。 */
    private val onEdited: () -> Unit,
    private val translate: (String) -> String,
    /** 此刻该用深色还是浅色。每次打开时问一次：面板开着的那几秒里主题不会变。 */
    private val dark: () -> Boolean,
    /** 面板内部出错时记日志，而不是让异常把进程带走。 */
    private val onError: (what: String, error: Throwable) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var open = false

    /** 以下只在主线程读写。 */
    private var root: PanelRoot? = null
    private var owner: AccessibilityService? = null
    private var palette = Palette.of(dark = true)
    private var state = ClipPanelState()
    private var generation = 0L

    /** 当前要了多少条；滚到底附近再加一页。 */
    private var limit = ClipPanelState.PAGE_SIZE
    private var hasMore = false
    private var loadingMore = false

    private val labels = HashMap<String, String>()
    private val adapter = RowAdapter()
    private lateinit var listView: ListView
    private lateinit var queryView: TextView
    private lateinit var countView: TextView
    private lateinit var emptyView: TextView

    /** 快捷键入口。在协程里调用，窗口操作一律投递到主线程。 */
    fun toggle(): ActionResult {
        val service = serviceProvider() ?: return ActionResult.Failed(ActionResult.Reason.ACCESSIBILITY_OFF)
        // 无障碍悬浮层压得住锁屏：锁着的时候把历史摊出来，等于把剪贴板交给捡到手机的人
        if (service.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true) {
            return ActionResult.Failed(ActionResult.Reason.UNSUPPORTED, "锁屏时不显示剪贴板历史")
        }
        main.post { guarded("打开 / 关闭剪贴板面板") { if (root != null) close() else show(service) } }
        return ActionResult.Ok(if (open) "关闭剪贴板历史" else "打开剪贴板历史")
    }

    /** 熄屏、服务断开时调用。 */
    fun dismiss() {
        if (open) main.post { guarded("收起剪贴板面板") { close() } }
    }

    /**
     * 面板跑在无障碍服务进程的主线程上：这里抛出去的异常会带走整个进程，
     * 按键监听也跟着断掉。出了错只收起面板、记一笔，代价最多是这一次没打开。
     */
    private inline fun guarded(what: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            onError(what, t)
            runCatching { close() }
        }
    }

    private fun show(service: AccessibilityService) {
        val wm = service.getSystemService(WindowManager::class.java) ?: return
        palette = Palette.of(dark())
        lastLoadedQuery = ""
        state = ClipPanelState()
        limit = ClipPanelState.PAGE_SIZE
        hasMore = false
        loadingMore = false
        val view = build(service)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            windowAnimations = 0
            title = TAG
        }
        if (runCatching { wm.addView(view, params) }.isFailure) return
        root = view
        owner = service
        open = true
        pipeline.modal = ::onKey
        render()
        load(state.query, limit)
    }

    private fun close() {
        pipeline.modal = null
        open = false
        generation++
        val view = root ?: return
        root = null
        runCatching { owner?.getSystemService(WindowManager::class.java)?.removeViewImmediate(view) }
        owner = null
        labels.clear()
    }

    // --- 按键 ----------------------------------------------------------------

    /** 按键线程（即主线程）上被管线调用。 */
    private fun onKey(e: NormalizedKeyEvent, modifiers: Int, typed: Char?): Boolean {
        if (root == null || owner !== serviceProvider()) {
            close()
            return false
        }
        // 修饰键放行：它们只是状态，吞掉反而会让底下的应用以为 Ctrl 一直按着
        if (e.isModifier) return false
        // 自己吞掉的按下，抬起由管线负责拦；别人的抬起原样放行
        if (!e.down) return false
        if (e.repeatCount == 0 && isToggle(e)) {
            close()
            return true
        }
        val key = PanelKeys.map(e.keyCode, modifiers, typed) ?: return true
        // Ctrl + 数字选的是角标上那个数：从第一条完整可见的开始数
        val absolute = if (key is PanelKey.Pick) {
            if (key.index >= visibleBadges()) return true
            PanelKey.Pick(badgeBase() + key.index)
        } else {
            key
        }
        val (next, effect) = state.reduce(absolute)
        state = next
        apply(effect)
        return true
    }

    private fun apply(effect: PanelEffect) {
        when (effect) {
            PanelEffect.None -> Unit
            PanelEffect.Render -> {
                adapter.notifyDataSetChanged()
                updateCount()
                revealSelected()
                maybeLoadMore(state.selected)
            }
            PanelEffect.Close -> close()
            is PanelEffect.Requery -> {
                renderQuery()
                load(effect.query, ClipPanelState.PAGE_SIZE)
            }
            is PanelEffect.Insert -> {
                close()
                onInsert(effect.entry)
            }
            is PanelEffect.TogglePin -> edit { store.setPinned(effect.entry.id, !effect.entry.pinned) }
            is PanelEffect.Delete -> edit { store.delete(effect.entry.id) }
        }
    }

    private fun edit(block: suspend () -> Unit) {
        scope.launch {
            block()
            onEdited()
            main.post { if (root != null) load(state.query, limit) }
        }
    }

    // --- 数据 ----------------------------------------------------------------

    /**
     * 查前 [count] 条。翻页就是把 [count] 加一页再查一次：
     * 列表只带预览，几百条也就几毫秒；比游标分页少一套状态，删改之后也不会错位。
     */
    private fun load(query: String, count: Int) {
        val gen = ++generation
        scope.launch {
            val rows = runCatching { store.search(query, count) }.getOrDefault(emptyList())
            main.post {
                // 打字快过查询时，旧结果到得晚也不能盖掉新结果
                if (gen != generation || root == null) return@post
                guarded("刷新剪贴板列表") {
                    limit = count
                    hasMore = rows.size >= count
                    loadingMore = false
                    val firstPage = state.entries.isEmpty() || query != lastLoadedQuery
                    lastLoadedQuery = query
                    state = state.withEntries(rows)
                    render()
                    // 换了搜索词回到顶上；翻页、删改保持原位
                    // 空列表不能 setSelection：ListView 会不查越界直接 getItemId(0)
                    if (firstPage) { if (state.entries.isNotEmpty()) listView.setSelection(0) } else revealSelected()
                }
            }
        }
    }

    private var lastLoadedQuery = ""

    /** 光标或滚动位置离末尾不远了，就要下一页。 */
    private fun maybeLoadMore(position: Int) {
        if (!hasMore || loadingMore) return
        if (position < state.entries.size - ClipPanelState.PREFETCH) return
        loadingMore = true
        // getView 里触发时正在布局，挪到下一帧再改数据
        main.post { if (root != null) guarded("加载下一页") { load(state.query, limit + ClipPanelState.PAGE_SIZE) } }
    }

    // --- 绘制 ----------------------------------------------------------------

    private fun render() {
        renderQuery()
        updateCount()
        adapter.notifyDataSetChanged()
        val empty = state.entries.isEmpty()
        emptyView.visibility = if (empty) View.VISIBLE else View.GONE
        listView.visibility = if (empty) View.GONE else View.VISIBLE
        emptyView.text = translate(
            when {
                state.query.isNotEmpty() -> "没有匹配的记录"
                !capturing() -> "还没有开启记录 · 在 Actionmental 的「剪贴板历史」页打开"
                else -> "还没有记录 · 复制点什么试试"
            },
        )
    }

    private fun renderQuery() {
        queryView.text = state.query.ifEmpty { translate("输入即搜索 · 支持拼音与首字母") }
        queryView.setTextColor(if (state.query.isEmpty()) palette.faint else palette.ink)
    }

    private fun updateCount() {
        val size = state.entries.size
        countView.text = if (size == 0) "" else (state.selected + 1).toString() + " / " + size + if (hasMore) "+" else ""
    }

    /** 键盘挪动光标后让它露出来：近的平滑滚过去，远的（PageDown 连按）直接跳。 */
    private fun revealSelected() {
        val target = state.selected
        if (listView.childCount == 0 || target !in state.entries.indices) return
        val first = listView.firstVisiblePosition
        val last = listView.lastVisiblePosition
        if (target in (first + 1) until last) return
        if (abs(target - first) > JUMP_THRESHOLD) listView.setSelection(target)
        else listView.smoothScrollToPosition(target)
    }

    /** 第一条完整可见的位置：顶上那条被裁掉一大半时不给它编号。 */
    private fun badgeBase(): Int {
        val first = listView.firstVisiblePosition
        val top = listView.getChildAt(0) ?: return first
        return if (top.top < listView.paddingTop - top.height / 3) first + 1 else first
    }

    /** 屏幕上编了号的有几条（至多 9 条，对应 Ctrl + 1…9）。 */
    private fun visibleBadges(): Int =
        (listView.lastVisiblePosition - badgeBase() + 1).coerceIn(0, MAX_BADGES)

    /** 滚动之后只改看得见的那十来个角标，不重新绑定整行。 */
    private fun refreshBadges() {
        val base = badgeBase()
        for (i in 0 until listView.childCount) {
            val holder = listView.getChildAt(i).tag as? RowHolder ?: continue
            bindBadge(holder, listView.firstVisiblePosition + i, base)
        }
    }

    private fun bindBadge(holder: RowHolder, position: Int, base: Int) {
        val number = position - base + 1
        val selected = position == state.selected
        holder.badge.visibility = if (number in 1..MAX_BADGES) View.VISIBLE else View.INVISIBLE
        holder.badge.text = number.toString()
        holder.badge.setTextColor(if (selected) palette.onAccent else palette.mid)
        holder.badgeBg.setColor(if (selected) palette.accent else palette.field)
    }

    private inner class RowAdapter : BaseAdapter() {
        override fun getCount() = state.entries.size
        override fun getItem(position: Int) = state.entries[position]
        override fun getItemId(position: Int) = state.entries[position].id
        override fun hasStableIds() = true

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: createRow(parent.context)
            val holder = view.tag as RowHolder
            val entry = state.entries[position]
            val selected = position == state.selected

            holder.preview.text = ClipPolicy.preview(entry.text)
            holder.meta.text = listOfNotNull(
                if (entry.pinned) "★ " + translate("置顶") else null,
                entry.sourcePackage?.let { labelOf(parent.context, it) },
                translate(ClipPolicy.age(System.currentTimeMillis(), entry.lastUsedAtMs)),
            ).joinToString(" · ")
            holder.meta.setTextColor(if (entry.pinned) palette.accent else palette.mid)
            holder.background.setColor(if (selected) palette.selected else Color.TRANSPARENT)
            holder.bar.visibility = if (selected) View.VISIBLE else View.INVISIBLE
            bindBadge(holder, position, badgeBase())

            maybeLoadMore(position)
            return view
        }
    }

    private class RowHolder(
        val bar: View,
        val badge: TextView,
        val badgeBg: GradientDrawable,
        val preview: TextView,
        val meta: TextView,
        val background: GradientDrawable,
    )

    /** 一行：左侧强调条 + 序号角标 + 两行预览与来源。至少 52dp 高，手指点得准。 */
    private fun createRow(context: Context): View {
        val bar = View(context).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(context, 2f)
                setColor(palette.accent)
            }
            layoutParams = LinearLayout.LayoutParams(dp(context, 3f).toInt(), ViewGroup.LayoutParams.MATCH_PARENT)
                .apply { marginEnd = dp(context, 9f).toInt() }
        }
        val badgeBg = GradientDrawable().apply { shape = GradientDrawable.OVAL }
        val badge = TextView(context).apply {
            background = badgeBg
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            val size = dp(context, 22f).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = dp(context, 11f).toInt() }
        }
        val preview = TextView(context).apply {
            setTextColor(palette.ink)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(0f, 1.1f)
        }
        val meta = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(context, 2f).toInt(), 0, 0)
        }
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(preview)
            addView(meta)
        }
        val background = GradientDrawable().apply { cornerRadius = dp(context, 12f) }
        val mask = GradientDrawable().apply {
            cornerRadius = dp(context, 12f)
            setColor(Color.WHITE)
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(context, 52f).toInt()
            val padV = dp(context, 8f).toInt()
            setPadding(dp(context, 4f).toInt(), padV, dp(context, 12f).toInt(), padV)
            this.background = RippleDrawable(ColorStateList.valueOf(palette.ripple), background, mask)
            addView(bar)
            addView(badge)
            addView(body)
            tag = RowHolder(bar, badge, badgeBg, preview, meta, background)
        }
    }

    private fun labelOf(context: Context, pkg: String): String = labels.getOrPut(pkg) {
        runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
    }

    private fun build(context: Context): PanelRoot {
        val pad = dp(context, 14f).toInt()

        val title = TextView(context).apply {
            text = translate("剪贴板历史")
            setTextColor(palette.ink)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        countView = TextView(context).apply {
            setTextColor(palette.faint)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.MONOSPACE
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(context, 4f).toInt(), 0, dp(context, 4f).toInt(), 0)
            addView(title)
            addView(countView)
        }
        queryView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.MONOSPACE
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.START
            val padV = dp(context, 9f).toInt()
            setPadding(dp(context, 12f).toInt(), padV, dp(context, 12f).toInt(), padV)
            background = GradientDrawable().apply {
                cornerRadius = dp(context, 12f)
                setColor(palette.field)
                setStroke(dp(context, 1f).toInt(), palette.line)
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(context, 10f).toInt(); bottomMargin = dp(context, 8f).toInt() }
        }
        // 先赋值、再配置：setOnScrollListener 会当场回调一次 onScroll，
        // 回调里要用到 listView —— 写在 `listView = ListView().apply { … }` 里时字段还没赋上，
        // lateinit 当场抛异常，整个无障碍进程跟着崩掉（真机上唤出面板即闪退就是这个）。
        listView = ListView(context)
        listView.apply {
            adapter = this@ClipPanel.adapter
            divider = null
            dividerHeight = dp(context, 2f).toInt()
            selector = GradientDrawable().apply { setColor(Color.TRANSPARENT) }
            cacheColorHint = Color.TRANSPARENT
            isVerticalScrollBarEnabled = true
            isVerticalFadingEdgeEnabled = true
            setFadingEdgeLength(dp(context, 16f).toInt())
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            // 高度随内容、到卡片能给的为止：weight 让它在放不下时收缩成可滚动的区域
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnItemClickListener { _, _, position, _ ->
                state = state.copy(selected = position)
                state.current?.let { apply(PanelEffect.Insert(it)) }
            }
            setOnItemLongClickListener { _, _, position, _ ->
                state.entries.getOrNull(position)?.let { apply(PanelEffect.TogglePin(it)) }
                true
            }
            setOnScrollListener(object : AbsListView.OnScrollListener {
                private var lastFirst = -1
                override fun onScrollStateChanged(view: AbsListView, scrollState: Int) = Unit
                override fun onScroll(view: AbsListView, first: Int, visible: Int, total: Int) {
                    if (first != lastFirst) {
                        lastFirst = first
                        refreshBadges()
                    }
                    if (visible > 0) maybeLoadMore(first + visible)
                }
            })
        }
        emptyView = TextView(context).apply {
            setTextColor(palette.mid)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            val padV = dp(context, 24f).toInt()
            setPadding(pad, padV, pad, padV)
        }
        val hint = TextView(context).apply {
            setTextColor(palette.faint)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            maxLines = 2
            setPadding(dp(context, 4f).toInt(), 0, dp(context, 4f).toInt(), 0)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(context, 8f).toInt() }
        }
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad - dp(context, 4f).toInt(), pad, pad - dp(context, 4f).toInt(), pad - dp(context, 2f).toInt())
            background = GradientDrawable().apply {
                cornerRadius = dp(context, 20f)
                setColor(palette.surface)
                setStroke(dp(context, 1f).toInt(), palette.line)
            }
            elevation = dp(context, 12f)
            // 吃掉卡片上的点击，免得穿到背景上把面板关了
            isClickable = true
            addView(header)
            addView(queryView)
            addView(listView)
            addView(emptyView)
            addView(hint)
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL }
        }
        return PanelRoot(context, card, hint).apply {
            setBackgroundColor(palette.backdrop)
            // 点卡片外面关闭：面板是模态的，底下的应用此刻本来就点不到
            setOnClickListener { close() }
            addView(card)
            contentDescription = translate("剪贴板历史")
        }
    }

    /**
     * 面板的根：每次测量时按当前窗口重算卡片的宽度与上下留白，转屏、分屏、小窗都自动跟上。
     *
     * - 宽：两侧至少留 12dp（宽屏 24dp），封顶 600dp —— 一行再宽，眼睛就要左右扫了；
     * - 上：状态栏下方再留屏高的 8%（8～64dp），落在视线最先到的位置，也不贴着状态栏；
     * - 下：避开导航栏再留 16dp，卡片到这里为止，列表在卡片里滚；
     * - 按键提示随宽度换繁简两版，窄窗口不折成三行。
     */
    private inner class PanelRoot(
        context: Context,
        private val card: View,
        private val hint: TextView,
    ) : FrameLayout(context) {

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = MeasureSpec.getSize(widthMeasureSpec)
            val height = MeasureSpec.getSize(heightMeasureSpec)
            val insets = systemInsets()
            val gutter = dp(context, if (px2dp(width) < 480) 12f else 24f).toInt()
            val usable = width - insets[0] - insets[2] - 2 * gutter
            val lp = card.layoutParams as LayoutParams
            lp.width = minOf(usable, dp(context, 600f).toInt())
            lp.leftMargin = insets[0]
            lp.rightMargin = insets[2]
            lp.topMargin = insets[1] + (height * 0.08f).roundToInt()
                .coerceIn(dp(context, 8f).toInt(), dp(context, 64f).toInt())
            lp.bottomMargin = insets[3] + dp(context, 16f).toInt()

            val wanted = translate(
                if (px2dp(lp.width) < 420) "↑↓ 选择 · Enter 输入 · Ctrl+1…9 直选 · Esc 关闭"
                else "↑↓ 选择 · Enter 输入 · Ctrl+1…9 直选 · Ctrl+P 置顶 · Del 删除 · Esc 关闭",
            )
            if (hint.text.toString() != wanted) hint.text = wanted
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }

        /** 左、上、右、下。拿不到窗口边衬时上方按 24dp 估。 */
        private fun systemInsets(): IntArray {
            val insets = rootWindowInsets ?: return intArrayOf(0, dp(context, 24f).toInt(), 0, 0)
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                intArrayOf(i.left, i.top, i.right, i.bottom)
            } else {
                @Suppress("DEPRECATION")
                intArrayOf(
                    insets.systemWindowInsetLeft,
                    insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight,
                    insets.systemWindowInsetBottom,
                )
            }
        }

        private fun px2dp(px: Int): Float = px / context.resources.displayMetrics.density
    }

    private fun dp(context: Context, value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics)

    private companion object {
        /** 出现在 `dumpsys window` 里。 */
        const val TAG = "Actionmental:clips"

        /** 角标最多编到 9，对应 Ctrl + 1…9。 */
        const val MAX_BADGES = 9

        /** 光标离可见区超过这么多条就直接跳，不做平滑滚动。 */
        const val JUMP_THRESHOLD = 12
    }

    /**
     * 面板的配色，取应用自己的设计令牌（[LightAmColors] / [DarkAmColors]），不另起一套色值。
     *
     * 当前项要一眼认得出，靠的是三样东西叠在一起，而不是一块淡色：
     * 强调色调过的底（浅色 14%、深色 24%，深色底上同样的透明度显得更暗，所以加量）、
     * 左侧 3dp 的强调条、实心强调色的序号角标。三者任一种在色弱或强光下失效，另外两种仍然看得出。
     * 「淡」一档用 inkMuted 而不是 inkFaint：浅色下 inkFaint 落在白底上几乎看不见。
     */
    private class Palette(c: AmColors, dark: Boolean) {
        val surface = c.surface.toArgb()
        val field = c.surfaceSunken.toArgb()
        val line = c.line.toArgb()
        val ink = c.ink.toArgb()
        val mid = c.inkMid.toArgb()
        val faint = c.inkMuted.toArgb()
        val accent = c.accent.toArgb()
        val onAccent = Color.WHITE
        val selected = ColorUtils.blendARGB(surface, accent, if (dark) 0.24f else 0.14f)
        val ripple = ColorUtils.setAlphaComponent(accent, if (dark) 0x40 else 0x2E)

        /** 背后的遮罩：浅色下压得轻一点，否则整屏发灰。 */
        val backdrop = if (dark) 0x73000000 else 0x33000000

        companion object {
            private val light by lazy { Palette(LightAmColors, dark = false) }
            private val darkPalette by lazy { Palette(DarkAmColors, dark = true) }

            fun of(dark: Boolean): Palette = if (dark) darkPalette else light
        }
    }
}

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
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import com.actionmental.core.action.ActionResult
import com.actionmental.core.clip.ClipEntry
import com.actionmental.core.clip.ClipPanelState
import com.actionmental.core.clip.ClipPolicy
import com.actionmental.core.clip.PanelEffect
import com.actionmental.core.clip.PanelKey
import com.actionmental.core.clip.PanelKeys
import com.actionmental.core.clip.PickKeys
import com.actionmental.core.key.KeyPipeline
import com.actionmental.core.key.NormalizedKeyEvent
import com.actionmental.data.ClipHistoryStore
import com.actionmental.data.UserSettings
import com.actionmental.ui.theme.AmColors
import com.actionmental.ui.theme.amColorsFor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 剪贴板历史面板：快捷键唤出，浮在当前应用上面。
 *
 * 窗口和触发提示一样挂 `TYPE_ACCESSIBILITY_OVERLAY`。它**可聚焦但不碰输入法**（ALT_FOCUSABLE_IM）：
 * 拿焦点是为了收到导航栏返回键与返回手势 —— 系统只把它们发给有焦点的窗口；不碰输入法是为了
 * 让原来那个输入框的输入连接尽量留着，选中之后经无障碍输入通道上屏。
 * 实体键盘的按键仍由 [KeyPipeline.modal] 在无障碍服务那一层截下来交给面板，比窗口分发更早。
 *
 * 顶上三格快捷操作：放用户挑的三条快捷键，点一下关面板、执行它。空格子点开就是选单。
 *
 * 版式跟着窗口走（见 [PanelRoot]）：卡片居中、封顶 640dp 宽，四周留足白；列表在卡片里滚。列表是 ListView：只为看得见的那几行建视图并循环复用，数据按页懒加载，
 * 每行只拿预览 —— 几万条历史时滑动也只动十来个视图、几十条数据。
 *
 * 置顶与最近分开摆：宽窗口（卡片 ≥ [SPLIT_MIN_DP]）左右两栏各自滚动；窄窗口是同一列表里的两段，
 * 置顶的压成单行，多了收起成前 [PIN_PEEK] 条。←→ 在两段之间跳。没有置顶时与以前一样只有一串。
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
    /** 直选用哪组键。每次打开时问一次，和 [dark] 一样：面板开着的那几秒里不会变。 */
    private val pickKeys: () -> PickKeys,
    /** 这颗键是不是唤出面板的那个快捷键：再按一次就是关上。 */
    private val isToggle: (NormalizedKeyEvent) -> Boolean,
    private val onInsert: (ClipEntry) -> Unit,
    /** 长按菜单里的「复制到剪贴板」：只写系统剪贴板，不上屏。 */
    private val onCopy: (ClipEntry) -> Unit,
    /** 删除 / 置顶改过库之后通知一声，让去重忘掉最近那一条。 */
    private val onEdited: () -> Unit,
    private val translate: (String) -> String,
    /** 此刻该用深色还是浅色。每次打开时问一次：面板开着的那几秒里主题不会变。 */
    private val dark: () -> Boolean,
    /** 强调色。和 [dark] 一样每次打开时问一次。 */
    private val accent: () -> UserSettings.Accent,
    /** 面板内部出错时记日志，而不是让异常把进程带走。 */
    private val onError: (what: String, error: Throwable) -> Unit,
    /** 顶上三格快捷操作此刻放的是什么，空格子是 null。每次打开时问一次。 */
    private val quickSlots: () -> List<QuickAction?> = { emptyList() },
    /** 能放进格子里的快捷键：已配置、由按键触发的那些。打开选单时才问。 */
    private val quickCandidates: () -> List<QuickAction> = { emptyList() },
    /** 第 slot 格换成 id 那条快捷键；null 是清空这一格。 */
    private val onAssignQuick: (slot: Int, id: String?) -> Unit = { _, _ -> },
    /** 点了一格：面板已经关上，执行那条快捷键。 */
    private val onRunQuick: (id: String) -> Unit = {},
    /** 窗口加上去之前调用：面板要抢焦点，输入连接可能被收走，桥得先记下此刻的状态。 */
    private val onShown: () -> Unit = {},
) {
    /** 一格快捷操作。[glyph] 是图标中央那一个字，[combo] 是触发它的组合键写法。 */
    data class QuickAction(val id: String, val name: String, val glyph: String, val combo: String)

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var open = false

    /** 以下只在主线程读写。 */
    private var root: PanelRoot? = null
    private var owner: AccessibilityService? = null
    private var palette = Palette.of(dark = true, UserSettings.Accent.TERRACOTTA)
    private var pick = PickKeys.CTRL_DIGITS
    private var state = ClipPanelState()
    private var generation = 0L

    /** 当前要了多少条；滚到底附近再加一页。 */
    private var limit = ClipPanelState.PAGE_SIZE
    private var hasMore = false
    private var loadingMore = false

    private val labels = HashMap<String, String>()

    /** 最近一次查回来的两段：置顶的（全部）与最近的（按页）。界面上实际列出的由 [refreshData] 拼出来。 */
    private var allPins: List<ClipEntry> = emptyList()
    private var recents: List<ClipEntry> = emptyList()

    /**
     * 有没有置顶的条目，按最近一次不带搜索词的查询算。决定宽窗口要不要分两栏：
     * 搜索时置顶段可能一条不剩，版式不该跟着字一个个敲进去来回跳。跨次打开保留，下一次打开第一帧就是对的版式。
     */
    private var hasPins = false

    /** 两栏并排（宽窗口且有置顶）；否则置顶作为一段排在同一列表的最上面。由 [PanelRoot.onMeasure] 定。 */
    private var columns = false

    /** 单列时置顶段收起成前 [PIN_PEEK] 条；展开靠点「展开」那一行，或按 ← 跳进置顶段。每次打开都从收起开始。 */
    private var pinsExpanded = false

    /**
     * 两个列表各自的行。非负数是 [ClipPanelState.entries] 里的下标，负数是分段标题等非条目行（ROW_*）。
     * 用 IntArray 而不是对象列表：每次刷新只分配一个数组，滚动时取行不分配。
     */
    private var pinRows = IntArray(0)
    private var mainRows = IntArray(0)
    private val pinAdapter = RowAdapter { pinRows }
    private val mainAdapter = RowAdapter { mainRows }

    /** 角标 1…9 依次对应哪一条（entries 下标）。读的顺序：先左栏、再右栏，各自从上往下。 */
    private var badgeOrder = IntArray(MAX_BADGES)
    private var badgeScratch = IntArray(MAX_BADGES)
    private var badgeCount = 0

    private lateinit var listView: ListView
    private lateinit var pinList: ListView
    private lateinit var pinColumn: View
    private lateinit var pinTitle: TextView
    private lateinit var recentTitle: TextView
    private lateinit var columnGap: View
    private lateinit var lists: View
    private lateinit var queryView: TextView
    private lateinit var countView: TextView
    private lateinit var emptyView: TextView

    /** 长按菜单那一层：开着时盖满整个面板，点它外面只收菜单、不关面板。 */
    private var menuLayer: View? = null

    /** 三格快捷操作此刻的内容，长度恒为 [QUICK_SLOTS]。 */
    private var quick: List<QuickAction?> = List(QUICK_SLOTS) { null }
    private lateinit var quickBar: LinearLayout

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
        palette = Palette.of(dark(), accent())
        pick = pickKeys()
        lastLoadedQuery = ""
        state = ClipPanelState()
        limit = ClipPanelState.PAGE_SIZE
        hasMore = false
        loadingMore = false
        allPins = emptyList()
        recents = emptyList()
        pinsExpanded = false
        badgeCount = 0
        quick = quickSlots().let { slots -> List(QUICK_SLOTS) { slots.getOrNull(it) } }
        val view = build(service)
        // 可聚焦：系统的返回键 / 返回手势只发给有焦点的窗口，不可聚焦的面板永远收不到，
        // 返回会落到底下的应用上。ALT_FOCUSABLE_IM 让这个窗口不碰输入法：输入法不会因为它收起，
        // 输入连接在多数系统上也继续留在原来的输入框上（没留住的，上屏前由桥等它接回来）。
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            windowAnimations = 0
            title = TAG
        }
        onShown()
        if (runCatching { wm.addView(view, params) }.isFailure) return
        view.playEnter()
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
        menuLayer = null
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
        // 菜单开着时 Esc / 返回只收菜单；别的键先收菜单再照常处理，键盘操作不会被一个菜单卡住
        if (menuLayer != null) {
            dismissMenu()
            if (e.keyCode == KeyEvent.KEYCODE_ESCAPE || e.keyCode == KeyEvent.KEYCODE_BACK) return true
        }
        if (e.repeatCount == 0 && isToggle(e)) {
            close()
            return true
        }
        val key = PanelKeys.map(e.keyCode, modifiers, typed, pick) ?: return true
        // 直选键选的是角标上那个数：从第一条完整可见的开始数
        val absolute = if (key is PanelKey.Pick) {
            if (key.index >= badgeCount) return true
            PanelKey.Pick(badgeOrder[key.index])
        } else {
            key
        }
        // 单列且置顶段收起时：← 先展开再跳进去，才跳得到被收起的那些；→ 先跳出来再收起，光标不落进收起的部分
        if (key == PanelKey.Left) setPinsExpanded(true)
        val (next, effect) = state.reduce(absolute)
        state = next
        apply(effect)
        if (key == PanelKey.Right) setPinsExpanded(false)
        return true
    }

    /** 单列时展开 / 收起置顶段，光标跟着原来那一条。两栏时置顶栏自己能滚，不收起。 */
    private fun setPinsExpanded(expanded: Boolean) {
        if (pinsExpanded == expanded || columns || !pinsCollapsible()) return
        pinsExpanded = expanded
        refreshData(follow = true)
        revealSelected()
    }

    /** 置顶多到值得收起：单列、没在搜索（搜索结果要全摆出来）、收起后至少藏得住两条。 */
    private fun pinsCollapsible(): Boolean = !columns && state.query.isEmpty() && allPins.size > PIN_PEEK + 1

    private fun apply(effect: PanelEffect) {
        when (effect) {
            PanelEffect.None -> Unit
            PanelEffect.Render -> {
                refreshSelection()
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
            is PanelEffect.Copy -> {
                close()
                onCopy(effect.entry)
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
            // 两段分开查：置顶的一次取完（封顶 PIN_LIMIT），最近的按页取 —— 置顶再多也不占「最近」的页
            val pins = runCatching { store.search(query, PIN_LIMIT, pinned = true) }.getOrDefault(emptyList())
            val rows = runCatching { store.search(query, count, pinned = false) }.getOrDefault(emptyList())
            main.post {
                // 打字快过查询时，旧结果到得晚也不能盖掉新结果
                if (gen != generation || root == null) return@post
                guarded("刷新剪贴板列表") {
                    limit = count
                    hasMore = rows.size >= count
                    loadingMore = false
                    val firstPage = state.entries.isEmpty() || query != lastLoadedQuery
                    lastLoadedQuery = query
                    allPins = pins
                    recents = rows
                    if (query.isEmpty() && hasPins != pins.isNotEmpty()) {
                        hasPins = pins.isNotEmpty()
                        // 版式由 PanelRoot 按新的 hasPins 重新决定
                        root?.requestLayout()
                    }
                    refreshData(follow = !firstPage)
                    // 换了搜索词回到顶上；翻页、删改保持原位（光标跟着原来那条）
                    // 空列表不能 setSelection：ListView 会不查越界直接 getItemId(0)
                    if (firstPage) {
                        if (mainRows.isNotEmpty()) listView.setSelection(0)
                        if (pinRows.isNotEmpty()) pinList.setSelection(0)
                    } else {
                        revealSelected()
                    }
                }
            }
        }
    }

    /**
     * 由两段拼出界面上列出的条目和两个列表各自的行，再重画。
     * 单列：[置顶标题, 置顶…, (展开/收起), 最近标题, 最近…]，没有置顶时就是一串最近，不加标题；
     * 两栏：左栏是置顶，右栏是最近，标题是列表外面固定的两行字。
     */
    private fun refreshData(follow: Boolean) {
        val collapsed = pinsCollapsible() && !pinsExpanded
        val shownPins = if (collapsed) allPins.subList(0, PIN_PEEK) else allPins
        state = state.copy(columns = columns).withEntries(if (recents.isEmpty()) shownPins else shownPins + recents, follow)
        val pinCount = shownPins.size
        val total = state.entries.size
        if (columns) {
            pinRows = if (pinCount == 0) intArrayOf(ROW_EMPTY_PINS) else IntArray(pinCount) { it }
            mainRows = if (total == pinCount) intArrayOf(ROW_EMPTY_RECENT) else IntArray(total - pinCount) { pinCount + it }
        } else {
            pinRows = IntArray(0)
            val toggle = pinsCollapsible()
            val headed = pinCount > 0
            val rows = IntArray(total + (if (headed) 1 else 0) + (if (toggle) 1 else 0) + (if (headed && total > pinCount) 1 else 0))
            var at = 0
            if (headed) rows[at++] = ROW_PIN_HEADER
            for (i in 0 until pinCount) rows[at++] = i
            if (toggle) rows[at++] = ROW_TOGGLE
            if (headed && total > pinCount) rows[at++] = ROW_RECENT_HEADER
            for (i in pinCount until total) rows[at++] = i
            mainRows = rows
        }
        render()
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
        pinAdapter.notifyDataSetChanged()
        mainAdapter.notifyDataSetChanged()
        val empty = state.entries.isEmpty()
        emptyView.visibility = if (empty) View.VISIBLE else View.GONE
        lists.visibility = if (empty) View.GONE else View.VISIBLE
        emptyView.text = emptyText()
        pinTitle.text = sectionTitle("置顶", allPins.size)
    }

    private fun emptyText(): String = translate(
        when {
            state.query.isNotEmpty() -> "没有匹配的记录"
            !capturing() -> "还没有开启记录 · 在 Actionmental 的「剪贴板历史」页打开"
            else -> "还没有记录 · 复制点什么试试"
        },
    )

    /** 「置顶  12」：数字和标题之间两个空格，比「·」轻。 */
    private fun sectionTitle(label: String, count: Int): String =
        translate(label) + if (count > 0) "  $count" else ""

    /** 两栏版式：置顶栏与栏间的空隙跟着显隐，右栏顶上加一行「最近」与左栏对齐。 */
    private fun applyColumns() {
        val v = if (columns) View.VISIBLE else View.GONE
        pinColumn.visibility = v
        columnGap.visibility = v
        recentTitle.visibility = v
    }

    private fun renderQuery() {
        queryView.text = state.query.ifEmpty { translate("输入即搜索 · 支持拼音与首字母") }
        queryView.setTextColor(if (state.query.isEmpty()) palette.faint else palette.ink)
    }

    private fun updateCount() {
        val size = state.entries.size
        countView.text = if (size == 0) "" else (state.selected + 1).toString() + " / " + size + if (hasMore) "+" else ""
    }

    /** 第 [index] 条在哪个列表的第几行。 */
    private fun locate(index: Int): Pair<ListView, Int>? {
        if (columns && index < state.pinned) return pinList to index
        val row = if (columns) index - state.pinned else mainRows.indexOf(index)
        return if (row in mainRows.indices) listView to row else null
    }

    /**
     * 键盘挪动光标后让它露出来：近的平滑滚过去，远的（PageDown 连按）直接跳。
     * 往上滚到一段的第一条时连上面的分段标题一起露出来，不然看不出自己进了哪一段。
     */
    private fun revealSelected() {
        val target = state.selected
        if (target !in state.entries.indices) return
        val (list, at) = locate(target) ?: return
        if (list.childCount == 0) return
        val rows = if (list === pinList) pinRows else mainRows
        val first = list.firstVisiblePosition
        val last = list.lastVisiblePosition
        if (at in (first + 1) until last) return
        val row = if (at <= first && (rows.getOrNull(at - 1) ?: 0) < 0) at - 1 else at
        if (abs(row - first) > JUMP_THRESHOLD) list.setSelection(row) else list.smoothScrollToPosition(row)
    }

    /**
     * 重新编角标：看得见、没被裁掉一大截的条目依次编 1…9，先左栏、再右栏。编号真变了才重画角标。
     * 滚动时每帧都会调到，所以只走屏幕上那十来行、不分配。
     */
    private fun refreshBadges() {
        val next = badgeScratch
        var n = 0
        if (columns) n = collectBadges(pinList, pinRows, next, n)
        n = collectBadges(listView, mainRows, next, n)
        if (n == badgeCount && sameBadges(next, n)) return
        badgeScratch = badgeOrder
        badgeOrder = next
        badgeCount = n
        if (columns) rebindBadges(pinList)
        rebindBadges(listView)
    }

    private fun sameBadges(next: IntArray, n: Int): Boolean {
        for (i in 0 until n) if (next[i] != badgeOrder[i]) return false
        return true
    }

    private fun collectBadges(list: ListView, rows: IntArray, out: IntArray, from: Int): Int {
        var n = from
        val first = list.firstVisiblePosition
        val top = list.paddingTop
        val bottom = list.height - list.paddingBottom
        for (i in 0 until list.childCount) {
            if (n >= MAX_BADGES) break
            val row = rows.getOrNull(first + i) ?: break
            if (row < 0) continue
            val child = list.getChildAt(i)
            // 顶上、底下被裁掉一大半的不编号：看不清的那条不该占一个数
            if (child.top < top - child.height / 3 || child.bottom > bottom + child.height / 3) continue
            out[n++] = row
        }
        return n
    }

    private fun rebindBadges(list: ListView) {
        for (i in 0 until list.childCount) {
            val holder = list.getChildAt(i).tag as? RowHolder ?: continue
            bindBadge(holder)
        }
    }

    /** 第 [index] 条的角标是几（1 起）；没编号是 0。 */
    private fun numberOf(index: Int): Int {
        for (i in 0 until badgeCount) if (badgeOrder[i] == index) return i + 1
        return 0
    }

    private fun bindBadge(holder: RowHolder) {
        val number = numberOf(holder.index)
        val selected = holder.index == state.selected
        holder.badge.visibility = if (number > 0) View.VISIBLE else View.INVISIBLE
        // 没编号的行照样占同样宽的位：写成「10」会把这一行的正文挤得和别的行对不齐
        holder.badge.text = pick.badge(if (number > 0) number else 1)
        holder.badge.setTextColor(if (selected) palette.onAccent else palette.mid)
        holder.badgeBg.setColor(if (selected) palette.accent else palette.field)
    }

    /** 一个列表的适配器。行从 [rows] 取（见 [pinRows]），条目、分段标题、展开行三种视图各自复用。 */
    private inner class RowAdapter(private val rows: () -> IntArray) : BaseAdapter() {
        override fun getCount() = rows().size
        override fun getItem(position: Int): Any? = state.entries.getOrNull(rows()[position])

        // 条目的 id 都是正数，非条目行用自己的负数常量，两者不会撞
        override fun getItemId(position: Int): Long = rows()[position].let { if (it >= 0) state.entries[it].id else it.toLong() }
        override fun hasStableIds() = true
        override fun getViewTypeCount() = 3
        override fun getItemViewType(position: Int) = rows()[position].let {
            if (it >= 0) TYPE_ENTRY else if (it == ROW_TOGGLE) TYPE_TOGGLE else TYPE_LABEL
        }
        override fun areAllItemsEnabled() = false
        override fun isEnabled(position: Int) = rows()[position].let { it >= 0 || it == ROW_TOGGLE }

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = rows()[position]
            return when {
                row >= 0 -> entryView(row, convertView, parent)
                row == ROW_TOGGLE -> toggleView(convertView, parent.context)
                else -> labelView(row, convertView, parent.context)
            }
        }
    }

    private fun entryView(index: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: createRow(parent.context)
        val holder = view.tag as RowHolder
        val entry = state.entries[index]
        holder.index = index
        holder.preview.text = ClipPolicy.preview(entry.text)
        if (entry.pinned) {
            // 置顶的待在自己那一段里，不必再写「置顶」；它们是反复用的固定内容，认得出正文就够了。
            // 单列时压成一行，把高度让给最近的；两栏时左栏自己滚，给两行
            holder.preview.maxLines = if (columns) 2 else 1
            holder.meta.visibility = View.GONE
            view.minimumHeight = dp(view.context, 44f).toInt()
        } else {
            holder.preview.maxLines = 2
            holder.meta.visibility = View.VISIBLE
            holder.meta.text = listOfNotNull(
                entry.sourcePackage?.let { labelOf(parent.context, it) },
                translate(ClipPolicy.age(System.currentTimeMillis(), entry.lastUsedAtMs)),
            ).joinToString(" · ")
            view.minimumHeight = dp(view.context, 52f).toInt()
        }
        bindSelection(holder)
        maybeLoadMore(index)
        return view
    }

    /** 分段标题（单列时的「置顶」「最近」），或两栏里某一栏空着时的那句说明。 */
    private fun labelView(row: Int, convertView: View?, context: Context): View {
        val view = convertView as? TextView ?: TextView(context).apply {
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }
        val empty = row == ROW_EMPTY_PINS || row == ROW_EMPTY_RECENT
        view.text = when (row) {
            ROW_PIN_HEADER -> sectionTitle("置顶", allPins.size)
            ROW_RECENT_HEADER -> translate("最近")
            ROW_EMPTY_PINS -> translate("没有匹配的置顶")
            else -> emptyText()
        }
        view.setTextColor(if (empty) palette.faint else palette.mid)
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (empty) 12.5f else 11.5f)
        view.typeface = if (empty) Typeface.DEFAULT else Typeface.DEFAULT_BOLD
        view.gravity = if (empty) Gravity.CENTER else Gravity.START or Gravity.CENTER_VERTICAL
        // 和条目的序号角标左缘对齐；「最近」上面多留一点，两段之间隔得开
        val top = dp(context, if (empty) 20f else if (row == ROW_RECENT_HEADER) 14f else 6f).toInt()
        val bottom = dp(context, if (empty) 20f else 4f).toInt()
        view.setPadding(dp(context, 16f).toInt(), top, dp(context, 12f).toInt(), bottom)
        return view
    }

    /** 单列时置顶段末尾那一行：「展开其余 n 条」/「收起置顶」。一整行都能点，至少 44dp 高。 */
    private fun toggleView(convertView: View?, context: Context): View {
        val view = convertView as? TextView ?: TextView(context).apply {
            setTextColor(palette.accent)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.DEFAULT_BOLD
            isSingleLine = true
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            minHeight = dp(context, 44f).toInt()
            setPadding(dp(context, 16f).toInt(), 0, dp(context, 12f).toInt(), 0)
        }
        view.text = if (pinsExpanded) {
            translate("收起置顶") + "  ▴"
        } else {
            translate("展开其余 %d 条").format(allPins.size - PIN_PEEK) + "  ▾"
        }
        return view
    }

    private fun bindSelection(holder: RowHolder) {
        val selected = holder.index == state.selected
        holder.background.setColor(if (selected) palette.selected else Color.TRANSPARENT)
        holder.bar.visibility = if (selected) View.VISIBLE else View.INVISIBLE
        bindBadge(holder)
    }

    /**
     * 光标挪动只改看得见那几行的高亮，不 notifyDataSetChanged：
     * 后者会让 ListView 重新布局，正在进行的平滑滚动被打断、整页跳一下。
     */
    private fun refreshSelection() {
        if (columns) rebindSelection(pinList)
        rebindSelection(listView)
    }

    private fun rebindSelection(list: ListView) {
        for (i in 0 until list.childCount) {
            val holder = list.getChildAt(i).tag as? RowHolder ?: continue
            bindSelection(holder)
        }
    }

    private class RowHolder(
        val bar: View,
        val badge: TextView,
        val badgeBg: GradientDrawable,
        val preview: TextView,
        val meta: TextView,
        val background: GradientDrawable,
    ) {
        /** 这一行此刻画的是第几条（entries 下标）。 */
        var index = -1
    }

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
        // 胶囊而不是正圆：一位数时宽高相等就是圆，「F1」这种两位的自然撑宽，不挤不裁
        val badgeBg = GradientDrawable().apply { cornerRadius = dp(context, 11f) }
        val badge = TextView(context).apply {
            background = badgeBg
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            includeFontPadding = false
            val size = dp(context, 22f).toInt()
            minWidth = size
            setPadding(dp(context, 5f).toInt(), 0, dp(context, 5f).toInt(), 0)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, size)
                .apply { marginEnd = dp(context, 11f).toInt() }
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

    /** 弹出菜单里的一项。[trailing] 画在右侧（组合键之类）；[enabled] 为 false 时只是一行说明。 */
    private class MenuEntry(
        val label: String,
        val trailing: String? = null,
        val checked: Boolean = false,
        val enabled: Boolean = true,
        val onClick: () -> Unit = {},
    )

    /** 长按一行：置顶 / 取消置顶、复制到剪贴板。菜单贴着那一行的右缘。 */
    private fun showMenu(row: View, entry: ClipEntry) {
        showPopup(
            row,
            listOf(
                MenuEntry(translate(if (entry.pinned) "取消置顶" else "置顶这一条")) { apply(PanelEffect.TogglePin(entry)) },
                MenuEntry(translate("复制到剪贴板")) { apply(PanelEffect.Copy(entry)) },
            ),
            alignEnd = true,
        )
    }

    /**
     * 在面板自己的窗口里弹一个小菜单，而不是 PopupMenu：挂在浮层下面的子窗口在一些 ROM 上
     * 弹不出来或拿不到触摸。下方放得下就放下方，否则翻到上方；太长就在菜单里滚。
     */
    private fun showPopup(anchor: View, entries: List<MenuEntry>, alignEnd: Boolean, title: String? = null, minWidthDp: Float = 168f) {
        val host = root ?: return
        dismissMenu()
        val context = host.context
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val padV = dp(context, 6f).toInt()
            setPadding(0, padV, 0, padV)
            title?.let {
                addView(TextView(context).apply {
                    text = it
                    setTextColor(palette.faint)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                    setPadding(dp(context, 16f).toInt(), dp(context, 6f).toInt(), dp(context, 16f).toInt(), dp(context, 6f).toInt())
                })
            }
            entries.forEach { addView(menuItem(context, it)) }
        }
        val menu = ScrollView(context).apply {
            isVerticalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            background = GradientDrawable().apply {
                cornerRadius = dp(context, 16f)
                setColor(palette.surface)
                setStroke(dp(context, 1f).toInt(), palette.line)
            }
            clipToOutline = true
            elevation = dp(context, 16f)
            isClickable = true
            addView(column)
        }
        val layer = FrameLayout(context).apply {
            // 兄弟视图按 Z 排绘制和分发触摸：这一层不抬过卡片，菜单就画在卡片底下、点也点不到。
            // 层本身没有背景，抬高不会投出整屏的影子
            elevation = dp(context, 40f)
            // 点菜单外面只收菜单：面板还开着，用户多半是要接着挑
            setOnClickListener { dismissMenu() }
            addView(menu, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        host.addView(layer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        menuLayer = layer

        val edge = dp(context, 12f).toInt()
        val maxWidth = (host.width - edge * 2).coerceAtLeast(0)
        column.measure(
            View.MeasureSpec.makeMeasureSpec(maxWidth, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val width = maxOf(column.measuredWidth, dp(context, minWidthDp).toInt()).coerceAtMost(maxWidth)
        val hostAt = IntArray(2).also(host::getLocationInWindow)
        val anchorAt = IntArray(2).also(anchor::getLocationInWindow)
        val anchorLeft = anchorAt[0] - hostAt[0]
        val anchorTop = anchorAt[1] - hostAt[1]
        val gap = dp(context, 6f).toInt()
        val below = anchorTop + anchor.height + gap
        val spaceBelow = host.height - edge - below
        val spaceAbove = anchorTop - gap - edge
        val wanted = column.measuredHeight
        val placeBelow = wanted <= spaceBelow || spaceBelow >= spaceAbove
        val height = wanted.coerceAtMost(maxOf(if (placeBelow) spaceBelow else spaceAbove, dp(context, 56f).toInt()))
        val top = if (placeBelow) below else (anchorTop - gap - height).coerceAtLeast(edge)
        val left = (if (alignEnd) anchorLeft + anchor.width - width - dp(context, 8f).toInt() else anchorLeft)
            .coerceIn(edge, (host.width - width - edge).coerceAtLeast(edge))
        (menu.layoutParams as FrameLayout.LayoutParams).apply {
            this.width = width
            this.height = height
            leftMargin = left
            topMargin = top
        }
        menu.alpha = 0f
        menu.scaleX = 0.96f
        menu.scaleY = 0.96f
        menu.pivotX = if (alignEnd) width.toFloat() else 0f
        menu.pivotY = if (placeBelow) 0f else height.toFloat()
        menu.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(140).setInterpolator(DecelerateInterpolator()).start()
    }

    private fun dismissMenu() {
        val layer = menuLayer ?: return
        menuLayer = null
        (layer.parent as? ViewGroup)?.removeView(layer)
    }

    private fun menuItem(context: Context, entry: MenuEntry): View {
        val label = TextView(context).apply {
            text = entry.label
            setTextColor(if (!entry.enabled) palette.faint else if (entry.checked) palette.accent else palette.ink)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (entry.enabled) 14f else 12.5f)
            if (entry.checked) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isSingleLine = entry.enabled
            ellipsize = if (entry.enabled) TextUtils.TruncateAt.END else null
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(context, 44f).toInt()
            val padV = dp(context, if (entry.enabled) 0f else 8f).toInt()
            setPadding(dp(context, 16f).toInt(), padV, dp(context, 16f).toInt(), padV)
            addView(label)
            entry.trailing?.takeIf { it.isNotBlank() }?.let {
                addView(TextView(context).apply {
                    text = it
                    setTextColor(palette.faint)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                    typeface = Typeface.MONOSPACE
                    isSingleLine = true
                    setPadding(dp(context, 16f).toInt(), 0, 0, 0)
                })
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            if (entry.enabled) {
                val mask = GradientDrawable().apply { setColor(Color.WHITE) }
                background = RippleDrawable(ColorStateList.valueOf(palette.ripple), null, mask)
                setOnClickListener {
                    guarded("面板菜单") {
                        dismissMenu()
                        entry.onClick()
                    }
                }
            }
        }
    }

    // --- 快捷操作 --------------------------------------------------------------

    /** 顶上那一排三格。每格的内容由 [bindQuick] 填，换了内容只重填那一格。 */
    private fun buildQuickBar(context: Context): LinearLayout {
        quickBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(context, 14f).toInt() }
        }
        repeat(QUICK_SLOTS) { slot ->
            val tile = FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, dp(context, 56f).toInt(), 1f)
                    .apply { if (slot > 0) marginStart = dp(context, 10f).toInt() }
                setOnClickListener { guarded("快捷操作") { onQuickTap(slot, this) } }
                setOnLongClickListener {
                    guarded("快捷操作") { onQuickLongPress(slot, this) }
                    true
                }
            }
            quickBar.addView(tile)
            bindQuick(slot)
        }
        return quickBar
    }

    private fun bindQuick(slot: Int) {
        val tile = quickBar.getChildAt(slot) as? FrameLayout ?: return
        val context = tile.context
        tile.removeAllViews()
        val action = quick.getOrNull(slot)
        val radius = dp(context, 16f)
        val mask = GradientDrawable().apply { cornerRadius = radius; setColor(Color.WHITE) }
        if (action == null) {
            // 空格子：虚线框 + 一个加号，告诉人这里能放东西，又不抢列表的戏
            val frame = GradientDrawable().apply {
                cornerRadius = radius
                setColor(Color.TRANSPARENT)
                setStroke(dp(context, 1.2f).toInt(), palette.line, dp(context, 5f), dp(context, 4f))
            }
            tile.background = RippleDrawable(ColorStateList.valueOf(palette.ripple), frame, mask)
            tile.contentDescription = translate("添加快捷操作")
            tile.addView(
                TextView(context).apply {
                    text = "＋  " + translate("添加快捷操作")
                    setTextColor(palette.faint)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                    isSingleLine = true
                    ellipsize = TextUtils.TruncateAt.END
                    gravity = Gravity.CENTER
                    setPadding(dp(context, 8f).toInt(), 0, dp(context, 8f).toInt(), 0)
                },
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
            )
            return
        }
        val fill = GradientDrawable().apply {
            cornerRadius = radius
            setColor(palette.field)
            setStroke(dp(context, 1f).toInt(), palette.line)
        }
        tile.background = RippleDrawable(ColorStateList.valueOf(palette.ripple), fill, mask)
        tile.contentDescription = action.name
        val glyphSize = dp(context, 30f).toInt()
        val glyph = TextView(context).apply {
            text = action.glyph
            setTextColor(palette.accent)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            includeFontPadding = false
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(palette.accentSoft)
            }
            layoutParams = LinearLayout.LayoutParams(glyphSize, glyphSize).apply { marginEnd = dp(context, 10f).toInt() }
        }
        val name = TextView(context).apply {
            text = action.name
            setTextColor(palette.ink)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }
        val combo = TextView(context).apply {
            text = action.combo
            setTextColor(palette.faint)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            typeface = Typeface.MONOSPACE
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            visibility = if (action.combo.isBlank()) View.GONE else View.VISIBLE
        }
        val text = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(name)
            addView(combo)
        }
        tile.addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(context, 10f).toInt(), 0, dp(context, 10f).toInt(), 0)
                addView(glyph)
                addView(text)
            },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
    }

    /** 有内容就关面板、执行；空格子就弹选单。 */
    private fun onQuickTap(slot: Int, tile: View) {
        dismissMenu()
        val action = quick.getOrNull(slot) ?: return showQuickPicker(slot, tile)
        close()
        onRunQuick(action.id)
    }

    /** 长按有内容的格子：更换或移除。空格子长按与点按一样。 */
    private fun onQuickLongPress(slot: Int, tile: View) {
        if (quick.getOrNull(slot) == null) return showQuickPicker(slot, tile)
        showPopup(
            tile,
            listOf(
                MenuEntry(translate("更换快捷操作")) { showQuickPicker(slot, tile) },
                MenuEntry(translate("移除")) { assignQuick(slot, null) },
            ),
            alignEnd = slot == QUICK_SLOTS - 1,
        )
    }

    private fun showQuickPicker(slot: Int, tile: View) {
        val current = quick.getOrNull(slot)?.id
        val candidates = quickCandidates()
        val entries = if (candidates.isEmpty()) {
            listOf(MenuEntry(translate("还没有快捷键 · 先在 Actionmental 的「快捷键」页添加"), enabled = false))
        } else {
            candidates.map { action ->
                MenuEntry(action.name, trailing = action.combo, checked = action.id == current) { assignQuick(slot, action) }
            }
        }
        showPopup(
            tile,
            entries,
            alignEnd = slot == QUICK_SLOTS - 1,
            title = translate("选择放进这一格的快捷键"),
            minWidthDp = 240f,
        )
    }

    private fun assignQuick(slot: Int, action: QuickAction?) {
        quick = quick.toMutableList().also { it[slot] = action }
        bindQuick(slot)
        onAssignQuick(slot, action?.id)
    }

    private fun labelOf(context: Context, pkg: String): String = labels.getOrPut(pkg) {
        runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
    }

    /** 两个列表一样的配置。点、长按、滚动都先经 [rows] 把行翻成条目下标，标题行点不着。 */
    private fun configureList(list: ListView, adapter: RowAdapter, rows: () -> IntArray) {
        val context = list.context
        list.apply {
            // 光标由面板自己画，ListView 不该再有一套焦点 / 选中项
            isFocusable = false
            this.adapter = adapter
            divider = null
            dividerHeight = dp(context, 2f).toInt()
            selector = GradientDrawable().apply { setColor(Color.TRANSPARENT) }
            cacheColorHint = Color.TRANSPARENT
            isVerticalScrollBarEnabled = true
            isVerticalFadingEdgeEnabled = true
            setFadingEdgeLength(dp(context, 16f).toInt())
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            setOnItemClickListener { _, _, position, _ ->
                val row = rows().getOrNull(position) ?: return@setOnItemClickListener
                if (row == ROW_TOGGLE) return@setOnItemClickListener setPinsExpanded(!pinsExpanded)
                val entry = state.entries.getOrNull(row) ?: return@setOnItemClickListener
                state = state.copy(selected = row)
                apply(PanelEffect.Insert(entry))
            }
            setOnItemLongClickListener { _, view, position, _ ->
                val row = rows().getOrNull(position) ?: -1
                state.entries.getOrNull(row)?.let { entry ->
                    state = state.copy(selected = row)
                    apply(PanelEffect.Render)
                    showMenu(view, entry)
                }
                true
            }
            setOnScrollListener(object : AbsListView.OnScrollListener {
                override fun onScrollStateChanged(view: AbsListView, scrollState: Int) = Unit
                override fun onScroll(view: AbsListView, first: Int, visible: Int, total: Int) {
                    // 布局完也会回调到这里：新数据、换版式之后角标都在这一处重编
                    refreshBadges()
                    if (visible > 0 && list === listView) {
                        maybeLoadMore(rows().getOrNull(first + visible - 1)?.takeIf { it >= 0 } ?: state.entries.size)
                    }
                }
            })
        }
    }

    private fun build(context: Context): PanelRoot {
        val pad = dp(context, 18f).toInt()

        val title = TextView(context).apply {
            text = translate("剪贴板历史")
            setTextColor(palette.ink)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
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
            val padV = dp(context, 10f).toInt()
            setPadding(dp(context, 12f).toInt(), padV, dp(context, 12f).toInt(), padV)
            background = GradientDrawable().apply {
                cornerRadius = dp(context, 14f)
                setColor(palette.field)
                setStroke(dp(context, 1f).toInt(), palette.line)
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(context, 12f).toInt(); bottomMargin = dp(context, 10f).toInt() }
        }
        // 先赋值、再配置：setOnScrollListener 会当场回调一次 onScroll，
        // 回调里要用到两个列表 —— 写在 `listView = ListView().apply { … }` 里时字段还没赋上，
        // lateinit 当场抛异常，整个无障碍进程跟着崩掉（真机上唤出面板即闪退就是这个）。
        listView = TouchModeListView(context)
        pinList = TouchModeListView(context)
        configureList(listView, mainAdapter) { mainRows }
        configureList(pinList, pinAdapter) { pinRows }

        fun columnTitle() = TextView(context).apply {
            setTextColor(palette.mid)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Typeface.DEFAULT_BOLD
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(context, 16f).toInt(), dp(context, 2f).toInt(), dp(context, 12f).toInt(), dp(context, 6f).toInt())
        }
        pinTitle = columnTitle()
        recentTitle = columnTitle().apply { text = translate("最近") }
        fun column(title: TextView, list: ListView, weight: Float) = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(title)
            addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight)
        }
        // 左栏窄一些（2 : 3）：置顶多是短的固定内容，最近的才需要宽度读两行预览
        pinColumn = column(pinTitle, pinList, 2f)
        columnGap = View(context).apply {
            setBackgroundColor(palette.line)
            layoutParams = LinearLayout.LayoutParams(dp(context, 1f).toInt(), ViewGroup.LayoutParams.MATCH_PARENT)
                .apply { marginStart = dp(context, 8f).toInt(); marginEnd = dp(context, 8f).toInt() }
        }
        lists = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(pinColumn)
            addView(columnGap)
            addView(column(recentTitle, listView, 3f))
            // 占住卡片剩下的高度：列表在里面滚，条目少时下面空着，卡片的边框不随内容跳
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        applyColumns()
        emptyView = TextView(context).apply {
            setTextColor(palette.mid)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            val padV = dp(context, 24f).toInt()
            setPadding(pad, padV, pad, padV)
            // 卡片铺满时由它占住列表让出的空间，提示行留在卡片底部
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
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
                cornerRadius = dp(context, 24f)
                setColor(palette.surface)
                setStroke(dp(context, 1f).toInt(), palette.line)
            }
            elevation = dp(context, 24f)
            // 吃掉卡片上的点击，免得穿到背景上把面板关了
            isClickable = true
            addView(header)
            addView(buildQuickBar(context))
            addView(queryView)
            addView(lists)
            addView(emptyView)
            addView(hint)
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
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
     * 面板的根：每次测量时按当前窗口重算卡片四周的留白，转屏、分屏、小窗都自动跟上。
     *
     * - 卡片浮在避开系统栏之后的区域正中，四周留足白：基础留白按短边取（不到 480dp 时 24dp，
     *   否则 48dp），上下再按可用高度的 8% 取大的那个；宽度封顶 [MAX_CARD_WIDTH_DP]，宽屏上不摊成一条；
     * - 列表在卡片里滚，条目少时下面空着，卡片的边框不随内容跳；
     * - 按键提示随宽度换繁简两版，窄窗口不折成三行。
     *
     * 返回键也在这里收：返回手势 / 导航栏返回发给有焦点的窗口，Android 13+ 走 OnBackInvokedCallback
     * （targetSdk 36 起不再派发 KEYCODE_BACK），更早的系统走按键分发。两条路都只关一次。
     */
    private inner class PanelRoot(
        context: Context,
        private val card: View,
        private val hint: TextView,
    ) : FrameLayout(context) {

        private val backHook: Any? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) BackHook { onBack() } else null

        /** 返回：菜单开着先收菜单，否则关面板。 */
        private fun onBack() = guarded("返回键关闭剪贴板面板") {
            if (menuLayer != null) dismissMenu() else if (root === this) close()
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) (backHook as? BackHook)?.register(this)
        }

        override fun onDetachedFromWindow() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) (backHook as? BackHook)?.unregister(this)
            super.onDetachedFromWindow()
        }

        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_ESCAPE) {
                if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) onBack()
                return true
            }
            return super.dispatchKeyEvent(event)
        }

        /** 打开时的一下：遮罩淡入，卡片从略小、略低的地方浮上来。 */
        fun playEnter() {
            alpha = 0f
            animate().alpha(1f).setDuration(160).start()
            card.scaleX = 0.97f
            card.scaleY = 0.97f
            card.translationY = dp(context, 12f)
            card.animate().scaleX(1f).scaleY(1f).translationY(0f)
                .setDuration(220).setInterpolator(DecelerateInterpolator(1.6f)).start()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = MeasureSpec.getSize(widthMeasureSpec)
            val height = MeasureSpec.getSize(heightMeasureSpec)
            val insets = systemInsets()
            // 留白按短边取：横屏时也是同一个数，四周看起来一样宽
            val gutter = dp(context, if (px2dp(minOf(width, height)) < 480) 24f else 48f).toInt()
            val availWidth = width - insets[0] - insets[2]
            val availHeight = height - insets[1] - insets[3]
            // 有置顶、且去掉留白后放得下两栏才分栏：横屏手机、平板、展开的折叠屏；竖屏手机与副屏仍是单列
            val split = hasPins && px2dp(availWidth - gutter * 2) >= SPLIT_MIN_DP
            if (split != columns) {
                // 测量子视图之前换好：本趟测量就按新版式来，不闪一帧旧的
                columns = split
                applyColumns()
                refreshData(follow = true)
            }
            val maxCard = if (split) MAX_SPLIT_CARD_WIDTH_DP else MAX_CARD_WIDTH_DP
            val side = maxOf(gutter, (availWidth - dp(context, maxCard).toInt()) / 2)
            val vertical = maxOf(gutter, (availHeight * 0.08f).toInt())
            val lp = card.layoutParams as LayoutParams
            lp.leftMargin = insets[0] + side
            lp.topMargin = insets[1] + vertical
            lp.rightMargin = insets[2] + side
            lp.bottomMargin = insets[3] + vertical
            val cardWidth = width - lp.leftMargin - lp.rightMargin

            val wanted = translate(
                when {
                    px2dp(cardWidth) < 420 -> "↑↓ 选择 · Enter 输入 · Ctrl+1…9 直选 · Esc 关闭"
                    // 有置顶时多一句 ←→：两段之间怎么跳，不看提示猜不到
                    hasPins -> "↑↓ 选择 · ←→ 置顶 / 最近 · Enter 输入 · Ctrl+1…9 直选 · Ctrl+C 复制 · Ctrl+P 置顶 · Del 删除 · Esc 关闭"
                    else -> "↑↓ 选择 · Enter 输入 · Ctrl+1…9 直选 · Ctrl+C 复制 · Ctrl+P 置顶 · Del 删除 · Esc 关闭"
                },
            ).replace(PickKeys.TEMPLATE, pick.hint)
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

        /** 角标最多编到 9，对应直选键的 1…9。 */
        const val MAX_BADGES = 9

        /** 光标离可见区超过这么多条就直接跳，不做平滑滚动。 */
        const val JUMP_THRESHOLD = 12

        /** 顶上快捷操作的格数。 */
        const val QUICK_SLOTS = 3

        /** 卡片最宽多少 dp：再宽一行字就长得读不过来了。 */
        const val MAX_CARD_WIDTH_DP = 640f

        /** 分两栏时卡片最宽多少 dp：左栏 2 份、右栏 3 份，右栏仍不超过单列时的宽度。 */
        const val MAX_SPLIT_CARD_WIDTH_DP = 960f

        /** 卡片能有多宽（dp）时才分两栏：再窄右栏的两行预览就读不下一句话了。 */
        const val SPLIT_MIN_DP = 600f

        /** 单列时置顶段收起后露出几条。 */
        const val PIN_PEEK = 3

        /** 置顶的一次最多取多少条：置顶是手挑的，几百条已经远超实际用法。 */
        const val PIN_LIMIT = 500

        /** 列表里的非条目行，见 [pinRows]。负数，和条目下标不撞。 */
        const val ROW_PIN_HEADER = -1
        const val ROW_RECENT_HEADER = -2
        const val ROW_TOGGLE = -3
        const val ROW_EMPTY_PINS = -4
        const val ROW_EMPTY_RECENT = -5

        const val TYPE_ENTRY = 0
        const val TYPE_LABEL = 1
        const val TYPE_TOGGLE = 2
    }

    /**
     * 面板的配色，取应用自己的设计令牌（[amColorsFor]，跟着用户选的强调色），不另起一套色值。
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

        /** 快捷操作图标的底：强调色淡淡一层。 */
        val accentSoft = ColorUtils.blendARGB(surface, accent, if (dark) 0.26f else 0.14f)
        val selected = ColorUtils.blendARGB(surface, accent, if (dark) 0.24f else 0.14f)
        val ripple = ColorUtils.setAlphaComponent(accent, if (dark) 0x40 else 0x2E)

        /** 背后的遮罩：浅色下压得轻一点，否则整屏发灰。 */
        val backdrop = if (dark) 0x73000000 else 0x33000000

        companion object {
            fun of(dark: Boolean, accent: UserSettings.Accent): Palette = Palette(amColorsFor(dark, accent), dark)
        }
    }
}

/**
 * 始终按触摸模式工作的 ListView。
 *
 * 面板窗口可聚焦，用户又多半刚在别的应用里用实体键盘打过字，窗口常常处在非触摸模式。
 * 非触摸模式下 ListView 会把 setSelection 当成「选中项」记下来，之后每次布局（数据刷新、
 * 翻页加载）都把视野拉回那一项：方向键往下滚一点又被弹回顶上，来回闪、滑不下去。
 * 光标由面板自己画，ListView 只负责滚动，所以让它永远走触摸模式那条路：setSelection 只是定位。
 */
private class TouchModeListView(context: Context) : ListView(context) {
    override fun isInTouchMode(): Boolean = true
}

/**
 * 面板窗口的返回回调。单独成类，免得低版本系统在加载 [ClipPanel] 时碰到 Android 13 才有的类型。
 * PRIORITY_OVERLAY：浮层之上的返回先归它，不落到同窗口别的回调上。
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class BackHook(private val onBack: () -> Unit) {
    private val callback = OnBackInvokedCallback { onBack() }

    fun register(view: View) {
        view.findOnBackInvokedDispatcher()
            ?.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_OVERLAY, callback)
    }

    fun unregister(view: View) {
        view.findOnBackInvokedDispatcher()?.unregisterOnBackInvokedCallback(callback)
    }
}

package com.actionmental.platform

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.provider.Settings
import android.net.Uri
import android.content.Intent
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.animation.ValueAnimator
import kotlinx.coroutines.CancellationException
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.actionmental.core.action.ActionResult
import com.actionmental.core.clip.ClipEntry
import com.actionmental.core.clip.ClipPanelState
import com.actionmental.core.clip.ClipPolicy
import com.actionmental.core.clip.PanelEffect
import com.actionmental.core.clip.PanelKey
import com.actionmental.core.clip.PanelGeometry
import com.actionmental.core.clip.PanelKeys
import com.actionmental.core.clip.PickKeys
import com.actionmental.core.clip.TranslationPanelKey
import com.actionmental.core.clip.TranslationPanelKeys
import com.actionmental.core.key.KeyCombo
import com.actionmental.core.key.KeyPipeline
import com.actionmental.core.key.NormalizedKeyEvent
import com.actionmental.data.ClipHistoryStore
import com.actionmental.data.UserSettings
import com.actionmental.ui.theme.AmColors
import com.actionmental.ui.theme.amColorsFor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import com.actionmental.core.translate.TranslationSettings
import com.actionmental.core.translate.TranslationRequest
import com.actionmental.core.translate.TextAction
import com.actionmental.core.translate.TranslationResult
import com.actionmental.core.translate.TranslationHistoryEntry
import kotlin.math.abs

/**
 * 剪贴板历史面板：快捷键唤出，浮在当前应用上面。
 *
 * 窗口和触发提示一样挂 `TYPE_ACCESSIBILITY_OVERLAY`。它**可聚焦但不碰输入法**（ALT_FOCUSABLE_IM）：
 * 拿焦点是为了收到导航栏返回键与返回手势 —— 系统只把它们发给有焦点的窗口；不碰输入法是为了
 * 让原来那个输入框的输入连接尽量留着，选中之后经无障碍输入通道上屏。
 * 实体键盘的按键仍由 [KeyPipeline.modal] 在无障碍服务那一层截下来交给面板，比窗口分发更早；
 * 编辑页、翻译页里面板不认的键再交回管线，快捷键与键位映射照常生效。
 *
 * 顶上三格快捷操作：放用户挑的三条快捷键，点一下关面板、执行它。空格子点开就是选单。
 *
 * 版式跟着窗口走（见 [PanelRoot]）：卡片居中、封顶 640dp 宽，四周留足白；列表在卡片里滚。列表是 ListView：只为看得见的那几行建视图并循环复用，数据按页懒加载，
 * 每行只拿预览 —— 几万条历史时滑动也只动十来个视图、几十条数据。
 *
 * 置顶与最近分开摆：宽窗口（放得下 [PanelGeometry.SPLIT_MIN_DP] 宽的卡片，只看窗口宽度、不看转向）左右两栏各自滚动；窄窗口是同一列表里的两段，
 * 置顶的压成单行，多了收起成前 [PIN_PEEK] 条。←→ 在两段之间跳。没有置顶时与以前一样只有一串。
 *
 * 长按一行（或 Ctrl+E）可以改它的正文：卡片原地换成编辑页（见 [Editor]）。只有这时窗口才临时接输入法，
 * 改完还回去 —— 列表模式下照旧不碰输入法。
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
    /** 编辑页的输入框用完、撤掉了：它占过输入连接，桥要等原来的输入框重新接上再上屏。 */
    private val onInputReturned: () -> Unit = {},
    private val translationSettings: () -> TranslationSettings = { TranslationSettings() },
    private val onTranslate: suspend (TranslationRequest, Boolean) -> TranslationResult = { _, _ -> error("Translation is not configured") },
    private val translationHistory: suspend () -> List<TranslationHistoryEntry> = { emptyList() },
    private val onDeleteTranslation: suspend (Long) -> Unit = {},
    private val onClearTranslations: suspend () -> Unit = {},
    private val onTranslationTarget: (String) -> Unit = {},
    private val onInsertTranslation: suspend (String) -> Boolean = { false },
    /** Writes the result to the clipboard; true only when it landed. */
    private val onCopyTranslation: suspend (String) -> Boolean = { false },
    private val translationConfigured: () -> Boolean = { true },
    private val onOpenTranslationSettings: () -> Unit = {},
    /** null hides every speech entry: the page needs a recognizer and its preferences. */
    private val speech: SpeechServices? = null,
    /** A field was focused when the panel opened and the system can write into it. */
    private val canInsertText: () -> Boolean = { false },
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
    private lateinit var translationSwitch: Button
    private lateinit var emptyView: TextView

    /** 长按菜单那一层：开着时盖满整个面板，点它外面只收菜单、不关面板。 */
    private var menuLayer: View? = null

    /** 窗口参数留着：进出编辑页要改输入法相关的那两项。 */
    private var params: WindowManager.LayoutParams? = null

    /** 卡片与列表模式下的那几块；编辑页打开时它们收起，卡片里只剩编辑页。 */
    private lateinit var card: LinearLayout
    private lateinit var browseViews: List<View>

    /** 正在编辑的那一条；null 是列表模式。 */
    private var editor: Editor? = null
    private var editorRequest = 0L
    private var translationPage: ClipTranslatePage? = null
    private var speechPage: ClipSpeechPage? = null
    /** The last transcript survives closing the panel, so a mistaken close never loses dictation. */
    private var speechDraft = ""
    private val inDetail get() = editor != null || translationPage != null || speechPage != null
    private var translationInputBorrowed = false
    /** Keep one local draft while switching modes; closing the panel releases it. */
    private var translationDraft: ClipTranslatePage? = null
    private var translationJob: Job? = null
    private var translationRequest = 0L
    private var historyRequest = 0L

    /** Shared detail navigation keeps browsing state and editor IME behavior intact. */
    private fun showDetail(view: View) {
        browseViews.forEach { it.visibility = View.GONE }
        card.addView(view)
    }

    private fun removeDetail(view: View) {
        card.removeView(view)
        browseViews.forEach { it.visibility = View.VISIBLE }
        render()
        root?.post { if (!inDetail) revealSelected() }
    }

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
        main.post { guarded("打开 / 关闭剪贴板面板") { if (editor != null) requestExitEditor(closePanel = true) else if (root != null) close() else show(service) } }
        return ActionResult.Ok(if (open) "关闭剪贴板历史" else "打开剪贴板历史")
    }

    /**
     * The speech shortcut. Closed panel: open straight onto the speech page and start listening.
     * Speech page up: do its next step (finish, transcribe, deliver). Elsewhere in the panel: switch to
     * the speech page, carrying the translation source along when that is where the user was.
     */
    fun speechShortcut(): ActionResult {
        val service = serviceProvider() ?: return ActionResult.Failed(ActionResult.Reason.ACCESSIBILITY_OFF)
        if (speech == null) return ActionResult.Failed(ActionResult.Reason.UNSUPPORTED)
        if (service.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true) {
            return ActionResult.Failed(ActionResult.Reason.UNSUPPORTED, "锁屏时无法录音，请先解锁")
        }
        main.post {
            guarded("Speech shortcut") {
                val page = speechPage
                when {
                    root == null -> { show(service); openSpeech(null, autoStart = true) }
                    page != null -> page.advance()
                    // An unsaved clip edit is never dropped for a recording: surface its discard prompt instead.
                    editor?.dirty == true -> requestExitEditor(closePanel = false)
                    else -> {
                        if (editor != null) leaveEditor()
                        val source = translationPage?.source
                        if (translationPage != null) leaveTranslation()
                        openSpeech(source, autoStart = true)
                    }
                }
            }
        }
        return ActionResult.Ok("语音转文字")
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
        this.params = params
        root = view
        owner = service
        open = true
        pipeline.modal = ::onKey
        render()
        load(state.query, limit)
    }

    private fun close() {
        translationPage?.let(::returnTranslationInput)
        speechPage?.let { page ->
            returnSpeechInput(page)
            page.dispose()
        }
        speechPage = null
        pipeline.modal = null
        open = false
        generation++
        editorRequest++
        translationRequest++
        historyRequest++
        translationJob?.cancel()
        translationJob = null
        translationPage = null
        translationDraft = null
        menuLayer = null
        // 编辑页随窗口一起撤掉，输入法跟着收起；它占过的输入连接要交还
        if (editor != null) {
            editor = null
            onInputReturned()
        }
        val view = root ?: return
        root = null
        params = null
        runCatching { owner?.getSystemService(WindowManager::class.java)?.removeViewImmediate(view) }
        owner = null
        labels.clear()
    }

    // --- 按键 ----------------------------------------------------------------

    /** 按键线程（即主线程）上被管线调用。 */
    private fun onKey(e: NormalizedKeyEvent, modifiers: Int, typed: Char?): Boolean? {
        if (root == null || owner !== serviceProvider()) {
            close()
            return false
        }
        // 编辑页、翻译页是输入框：面板不认的键交回管线，快捷键与键位映射照常生效，和在普通应用里打字一样。
        // 列表页的字符是搜索输入，不认的键仍原样放行
        val unclaimed: Boolean? = if (inDetail) null else false
        // 修饰键放行：它们只是状态，吞掉反而会让底下的应用以为 Ctrl 一直按着
        if (e.isModifier) return unclaimed
        // 自己吞掉的按下，抬起由管线负责拦；别人的抬起原样放行
        if (!e.down) return unclaimed
        // 菜单开着时 Esc / 返回只收菜单；别的键先收菜单再照常处理，键盘操作不会被一个菜单卡住
        if (menuLayer != null) {
            when (e.keyCode) {
                KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_SPACE -> return false
            }
            dismissMenu()
            if (e.keyCode == KeyEvent.KEYCODE_ESCAPE || e.keyCode == KeyEvent.KEYCODE_BACK) return true
        }
        editor?.let { return onEditorKey(it, e, modifiers) }
        speechPage?.let { page -> return onSpeechKey(page, e, modifiers) }
        translationPage?.let { page ->
            if (e.repeatCount == 0 && isToggle(e)) { close(); return true }
            val key = TranslationPanelKeys.map(e.keyCode, modifiers, pick)
            if (page.editing && !page.inserting) {
                when (key) {
                    TranslationPanelKey.Escape -> if (e.repeatCount == 0) escapeTranslation()
                    TranslationPanelKey.BackToList -> if (e.repeatCount == 0) leaveTranslation()
                    TranslationPanelKey.Confirm -> if (e.repeatCount == 0) confirmTranslation(page)
                    TranslationPanelKey.Retry -> if (e.repeatCount == 0) requestTranslation(page, true)
                    else -> return null
                }
                return true
            }
            if (page.inserting && key != TranslationPanelKey.Escape && key != TranslationPanelKey.BackToList) return true
            when (key) {
                TranslationPanelKey.Escape -> if (e.repeatCount == 0) escapeTranslation()
                TranslationPanelKey.BackToList -> if (e.repeatCount == 0) leaveTranslation()
                TranslationPanelKey.PreviousTarget -> moveTranslationTarget(page, -1)
                TranslationPanelKey.NextTarget -> moveTranslationTarget(page, 1)
                is TranslationPanelKey.PickTarget -> page.languages.getOrNull(key.index)?.let { selectTranslationTarget(page, it) }
                TranslationPanelKey.Retry -> if (e.repeatCount == 0) requestTranslation(page, true)
                TranslationPanelKey.Confirm -> if (e.repeatCount == 0) confirmTranslation(page)
                null -> return null
            }
            return true
        }
        if (e.repeatCount == 0 && isToggle(e)) {
            close()
            return true
        }
        val key = PanelKeys.map(e.keyCode, modifiers, typed, pick) ?: return true
        if (key == PanelKey.Translate && state.current == null) {
            if (e.repeatCount == 0) openTranslation()
            return true
        }
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
            is PanelEffect.Edit -> openEditor(effect.entry)
            is PanelEffect.Translate -> openTranslation()
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
        // 编辑页开着时列表那几块一直收着，后台查回来的结果不该把它们翻出来
        if (!inDetail) {
            emptyView.visibility = if (empty) View.VISIBLE else View.GONE
            lists.visibility = if (empty) View.GONE else View.VISIBLE
        }
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
        translationSwitch.isEnabled = true
        translationSwitch.alpha = 1f
        translationSwitch.contentDescription = translate("翻译、润色、摘要与改写")
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
        /** 会丢东西的那一项（放弃修改）：用强调色写，和别的项分开。 */
        val danger: Boolean = false,
        val onClick: () -> Unit = {},
    )

    /**
     * 长按一行：编辑、置顶 / 取消置顶、复制到剪贴板。菜单贴着那一行的右缘。
     * 右侧写上对应的组合键：用触屏点开过一次菜单，下次就知道键盘上怎么按。
     */
    private fun showMenu(row: View, entry: ClipEntry) {
        showPopup(
            row,
            listOf(
                MenuEntry(translate("编辑"), trailing = "Ctrl+E") { apply(PanelEffect.Edit(entry)) },
                MenuEntry(translate("翻译"), trailing = "Ctrl+T") { apply(PanelEffect.Translate(entry)) },
                MenuEntry(translate("润色 / 摘要…")) { processClip(entry) },
                MenuEntry(translate(if (entry.pinned) "取消置顶" else "置顶这一条"), trailing = "Ctrl+P") {
                    apply(PanelEffect.TogglePin(entry))
                },
                MenuEntry(translate("复制到剪贴板"), trailing = "Ctrl+C") { apply(PanelEffect.Copy(entry)) },
            ),
            alignEnd = true,
            minWidthDp = 200f,
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
        if (ValueAnimator.areAnimatorsEnabled()) {
            menu.alpha = 0f
            menu.scaleX = 0.96f
            menu.scaleY = 0.96f
            menu.pivotX = if (alignEnd) width.toFloat() else 0f
            menu.pivotY = if (placeBelow) 0f else height.toFloat()
            menu.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(140).setInterpolator(DecelerateInterpolator()).start()
        }
        column.post {
            if (menuLayer === layer) {
                (0 until column.childCount).map { column.getChildAt(it) }.firstOrNull { it.isFocusable }?.requestFocus()
            }
        }
    }

    private fun dismissMenu() {
        val layer = menuLayer ?: return
        menuLayer = null
        (layer.parent as? ViewGroup)?.removeView(layer)
        editor?.field?.requestFocus()
    }

    private fun menuItem(context: Context, entry: MenuEntry): View {
        val label = TextView(context).apply {
            text = entry.label
            setTextColor(if (!entry.enabled) palette.faint else if (entry.danger) palette.error else if (entry.checked) palette.emphasis else palette.ink)
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
                isFocusable = true
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

    // --- Translation ----------------------------------------------------------

    /** The source always starts empty: the user types or pastes what to translate, never a clip picked for them. */
    private fun openTranslation() {
        if (inDetail) return
        dismissMenu()
        val host = root ?: return
        guarded("Open translation detail") {
            val cached = translationDraft
            if (cached != null) {
                translationPage = cached
                cached.refresh()
                showDetail(cached.view)
                focusTranslationSource(cached)
                if (cached.historyExpanded) loadTranslationHistory(cached)
                return@guarded
            }
            val settings = translationSettings()
            val page = ClipTranslatePage(host.context, "", settings.languages,
                settings.languages.firstOrNull { it.id == settings.lastTargetId } ?: settings.languages.firstOrNull(),
                pageColors(), translate, pageButton(host.context),
                translate("原文可编辑 · Enter 换行 · Ctrl+Enter 翻译或输入 · Ctrl+R 重新翻译 · Ctrl+T 返回列表 · Esc 先收键盘再返回").replace(PickKeys.TEMPLATE, pick.hint),
                ::leaveTranslation, { translationPage?.let(::confirmTranslation) }, {
                    translationPage?.let { current ->
                        if (current.loading) cancelTranslationRequest(current)
                        else requestTranslation(current, true)
                    }
                }, { translationPage?.let(::copyTranslation) }, { translationPage?.let { current -> returnTranslationInput(current); loadTranslationHistory(current) } }, {
                    close()
                    onOpenTranslationSettings()
                }, onSpeech = if (speech == null) null else ({
                    translationPage?.let { current ->
                        val source = current.source
                        leaveTranslation()
                        openSpeech(source, autoStart = true)
                    }
                }))
            translationPage = page
            translationDraft = page
            page.onSourceChanged = { value ->
                if (translationPage === page && !page.inserting && value != page.source) {
                    cancelTranslation()
                    page.state.setSource(value)
                    page.error = null
                    page.refresh()
                }
            }
            page.onSourceFocus = { field ->
                if (translationPage === page) {
                    if (field != null && !page.inserting) {
                        translationInputBorrowed = true
                        setWindowIme(true)
                        showKeyboard(field)
                    } else if (field == null) returnTranslationInput(page)
                }
            }
            page.onAction = { action -> selectTextAction(page, action) }
            page.onInstructionChanged = {
                if (translationPage === page && page.action == TextAction.CUSTOM && !page.inserting) {
                    cancelTranslation()
                    page.state.selectTarget(page.targetFor(TextAction.CUSTOM))
                    page.error = null
                    page.refresh()
                }
            }
            page.languagePicker = { anchor ->
                showPopup(anchor, page.languages.map { language ->
                    MenuEntry(language.name, checked = page.target?.id == language.id) {
                        selectTranslationTarget(page, language)
                    }
                }, false)
            }
            page.onLanguage = { language -> selectTranslationTarget(page, language) }
            showDetail(page.view)
            focusTranslationSource(page)
            if (!translationConfigured()) {
                page.error = translate("尚未配置翻译密钥，请前往翻译设置；打开设置将离开当前输入目标")
                page.refresh()
            }
        }
    }

    // --- Speech ---------------------------------------------------------------

    private fun pageColors() = ClipTranslatePage.Colors(
        ink = palette.ink, mid = palette.mid, muted = palette.faint, surface = palette.surface,
        field = palette.field, line = palette.line, error = palette.error, accent = palette.accent,
        accentInk = palette.emphasis, ripple = palette.ripple, dark = palette.dark,
    )

    private fun pageButton(context: Context): (String, ClipTranslatePage.ButtonStyle, () -> Unit) -> Button = { label, style, click ->
        actionButton(context, label, when (style) {
            ClipTranslatePage.ButtonStyle.TEXT -> Tone.TEXT
            ClipTranslatePage.ButtonStyle.OUTLINE -> Tone.OUTLINE
            ClipTranslatePage.ButtonStyle.PRIMARY -> Tone.PRIMARY
        }, click)
    }

    /**
     * The speech page replaces the list inside the same card, like translation. [translationSource]
     * non-null means it was opened from the translation page, which is where the words then go.
     */
    private fun openSpeech(translationSource: String?, autoStart: Boolean) {
        val services = speech ?: return
        if (inDetail) return
        dismissMenu()
        val host = root ?: return
        guarded("Open speech page") {
            val route = if (translationSource != null) ClipSpeechPage.Route.TRANSLATE else ClipSpeechPage.Route.INSERT
            // Decided once: the field captured when the panel opened does not change while it is up.
            val insertable = canInsertText()
            var self: ClipSpeechPage? = null
            val page = ClipSpeechPage(host.context, pageColors(), translate, pageButton(host.context), services,
                object : ClipSpeechPage.Host {
                    override val canInsert get() = insertable
                    override fun back() = leaveSpeech()
                    override fun insert(text: String) { self?.let { insertSpeech(it, text) } }
                    override fun toTranslation(text: String) = speechToTranslation(text)
                    override fun copy(text: String) = copySpeech(text)
                    override fun runCommand(command: SpeechCommand) {
                        close()
                        onRunQuick(command.shortcutId)
                    }
                    override fun openSettings() {
                        close()
                        onOpenTranslationSettings()
                    }
                    override fun openAppSettings() {
                        val context = owner ?: return
                        close()
                        runCatching {
                            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", context.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }.onFailure { onError("Open app permission settings", it) }
                    }
                    override fun requestMicPermission(done: (Boolean) -> Unit) = requestMic(done)
                    override fun onDraftFocus(field: EditText?) {
                        val current = self ?: return
                        if (speechPage !== current) return
                        if (field != null && !current.inserting) {
                            translationInputBorrowed = true
                            setWindowIme(true)
                            showKeyboard(field)
                        } else if (field == null) returnSpeechInput(current)
                    }
                    override fun onDraftChanged(text: String) { speechDraft = text }
                }, route, speechDraft)
            self = page
            speechPage = page
            showDetail(page.view)
            page.view.requestFocus()
            page.start(autoStart)
        }
    }

    private fun leaveSpeech() {
        val page = speechPage ?: return
        returnSpeechInput(page)
        page.dispose()
        speechPage = null
        restoreTranslationWindow()
        removeDetail(page.view)
    }

    /** Esc / back / a tap outside: the keyboard first, then the step in progress, then the page. */
    private fun escapeSpeech() {
        val page = speechPage ?: return
        if (translationInputBorrowed && imeVisible()) {
            hideKeyboard(page.draftField)
            return
        }
        returnSpeechInput(page)
        page.escape()
    }

    private fun returnSpeechInput(page: ClipSpeechPage) {
        if (!translationInputBorrowed) return
        translationInputBorrowed = false
        hideKeyboard(page.draftField)
        if (page.editing) page.view.requestFocus()
        page.draftField.clearFocus()
        setWindowIme(false)
        onInputReturned()
    }

    /** Enter / Space do the page's next step; unclaimed keys go on to shortcuts, including speech's own. */
    private fun onSpeechKey(page: ClipSpeechPage, e: NormalizedKeyEvent, modifiers: Int): Boolean? {
        if (e.repeatCount == 0 && isToggle(e)) { close(); return true }
        val ctrl = modifiers and KeyCombo.MOD_CTRL != 0
        val enter = e.keyCode == KeyEvent.KEYCODE_ENTER || e.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
        if (e.keyCode == KeyEvent.KEYCODE_ESCAPE || e.keyCode == KeyEvent.KEYCODE_BACK) {
            if (e.repeatCount == 0) escapeSpeech()
            return true
        }
        if (page.editing) {
            if (!(ctrl && enter)) return null
            if (e.repeatCount == 0) { returnSpeechInput(page); page.advance() }
            return true
        }
        if (enter || (e.keyCode == KeyEvent.KEYCODE_SPACE && modifiers == 0)) {
            if (e.repeatCount == 0) page.advance()
            return true
        }
        return null
    }

    /** Step aside exactly as translation insertion does, so the original field takes focus back. */
    private fun insertSpeech(page: ClipSpeechPage, text: String) {
        val host = root ?: return
        val lp = params ?: return
        if (page.inserting || speechPage !== page) return
        returnSpeechInput(page)
        page.inserting = true
        host.visibility = View.INVISIBLE
        lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        owner?.getSystemService(WindowManager::class.java)?.updateViewLayout(host, lp)
        host.postDelayed({
            if (root !== host || speechPage !== page) return@postDelayed
            scope.launch {
                val success = try { onInsertTranslation(text) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { onError("Insert transcript", error); false }
                main.post {
                    if (root !== host || speechPage !== page) return@post
                    if (success) {
                        speechDraft = ""
                        close()
                    } else {
                        restoreTranslationWindow()
                        page.inserting = false
                        page.showError(translate("未能写入原输入框，文字已保留；请回到原应用聚焦后重试，或复制"))
                    }
                }
            }
        }, 80L)
    }

    /** Append to whatever the translation page already holds; nothing typed there is replaced. */
    private fun speechToTranslation(text: String) {
        leaveSpeech()
        openTranslation()
        val page = translationPage ?: return
        val base = page.source.trimEnd()
        page.sourceField.setText(if (base.isEmpty()) text else base + "\n" + text)
        page.sourceField.setSelection(page.sourceField.length())
        speechDraft = ""
    }

    private fun copySpeech(text: String) {
        val host = root ?: return
        scope.launch {
            val copied = try { onCopyTranslation(text) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { onError("Copy transcript", error); false }
            main.post {
                if (root !== host) return@post
                Toast.makeText(host.context, translate(if (copied) "已复制到剪贴板" else "复制失败"), Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** The system prompt would sit under this overlay, so the panel steps aside until it is answered. */
    private fun requestMic(done: (Boolean) -> Unit) {
        val host = root ?: return done(false)
        val lp = params ?: return done(false)
        host.visibility = View.INVISIBLE
        lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        owner?.getSystemService(WindowManager::class.java)?.updateViewLayout(host, lp)
        val started = runCatching {
            MicPermissionActivity.request(host.context) { granted ->
                main.post {
                    if (root === host) restoreTranslationWindow()
                    done(granted)
                }
            }
        }
        if (started.isFailure) {
            onError("Request microphone permission", started.exceptionOrNull()!!)
            restoreTranslationWindow()
            done(false)
        }
    }

    /** Explicitly chosen from a clip's menu: unlike plain translation, the clip itself becomes the source. */
    private fun processClip(entry: ClipEntry) {
        if (inDetail) return
        dismissMenu()
        val host = root ?: return
        val request = ++editorRequest
        scope.launch {
            // The list holds a preview only; the source must be the whole clip.
            val full = try { store.text(entry.id) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { onError("Read clip for text action", error); null }
            main.post {
                if (root !== host || request != editorRequest || inDetail) return@post
                if (full == null) {
                    Toast.makeText(host.context, translate("无法读取完整内容，请重新打开后重试"), Toast.LENGTH_LONG).show()
                    return@post
                }
                guarded("Open text action for clip") {
                    openTranslation()
                    val page = translationPage ?: return@guarded
                    page.sourceField.setText(full)
                    if (page.action == TextAction.TRANSLATE) selectTextAction(page, TextAction.POLISH)
                    page.confirm.requestFocus()
                }
            }
        }
    }

    /** Opening lands in the source with the keyboard up; [ClipTranslatePage.onSourceFocus] borrows the input. */
    private fun focusTranslationSource(page: ClipTranslatePage) {
        // Hidden behind the phone-sized history list the field cannot take focus.
        if (!page.sourceField.requestFocus()) page.confirm.requestFocus()
    }

    private fun cancelTranslation() {
        translationRequest++
        translationJob?.cancel()
        translationJob = null
        translationPage?.state?.cancel()
    }

    private fun cancelTranslationRequest(page: ClipTranslatePage) {
        cancelTranslation()
        page.error = translate(if (page.result != null) "重译未完成，保留上次译文" else "请求已取消，可重新翻译")
        page.refresh()
    }

    private fun selectTextAction(page: ClipTranslatePage, action: TextAction) {
        if (page.inserting || page.action == action) return
        val target = page.targetFor(action)
        cancelTranslation()
        page.selectAction(action)
        page.state.selectTarget(target)
        page.error = null
        page.refresh()
        if (action == TextAction.CUSTOM && target == null) page.instructionField.requestFocus() else page.confirm.requestFocus()
    }

    private fun selectTranslationTarget(page: ClipTranslatePage, language: com.actionmental.core.translate.TranslationLanguage) {
        if (page.inserting || (page.action == TextAction.TRANSLATE && page.target?.id == language.id)) return
        cancelTranslation()
        page.selectAction(TextAction.TRANSLATE)
        page.state.selectTarget(language)
        page.error = null
        onTranslationTarget(language.id)
        page.refresh()
        page.confirm.requestFocus()
    }

    private fun moveTranslationTarget(page: ClipTranslatePage, delta: Int) {
        // Outside translation the arrows step through the actions instead of the languages.
        if (page.action != TextAction.TRANSLATE) {
            val actions = TextAction.entries
            selectTextAction(page, actions[Math.floorMod(actions.indexOf(page.action) + delta, actions.size)])
            return
        }
        if (page.languages.isEmpty()) return
        val current = page.languages.indexOfFirst { it.id == page.target?.id }
        val next = Math.floorMod(current + delta, page.languages.size)
        selectTranslationTarget(page, page.languages[next])
    }

    private fun returnTranslationInput(page: ClipTranslatePage) {
        if (!translationInputBorrowed) return
        translationInputBorrowed = false
        hideKeyboard(page.sourceField)
        if (page.editing) page.view.requestFocus()
        page.sourceField.clearFocus()
        page.instructionField.clearFocus()
        setWindowIme(false)
        onInputReturned()
    }

    private fun escapeTranslation() {
        val page = translationPage ?: return
        if (translationInputBorrowed && imeVisible()) {
            hideKeyboard(page.sourceField)
            return
        }
        returnTranslationInput(page)
        when {
            page.returnFromHistory() -> page.confirm.requestFocus()
            page.loading -> cancelTranslationRequest(page)
            else -> leaveTranslation()
        }
    }

    private fun leaveTranslation() {
        val page = translationPage ?: return
        returnTranslationInput(page)
        cancelTranslation()
        historyRequest++
        dismissMenu()
        translationPage = null
        restoreTranslationWindow()
        removeDetail(page.view)
    }

    private fun confirmTranslation(page: ClipTranslatePage) {
        if (page.loading || page.inserting) return
        if (page.result != null) insertTranslation(page) else requestTranslation(page, false)
    }

    private fun requestTranslation(page: ClipTranslatePage, force: Boolean) {
        if (translationPage !== page || page.loading || page.inserting || page.source.isBlank()) return
        returnTranslationInput(page)
        if (page.source.length > TranslationRequest.MAX_SOURCE) {
            page.error = translate("原文超过 8000 字符，请先编辑缩短后再翻译")
            page.refresh()
            return
        }
        if (!translationConfigured()) {
            page.error = translate("尚未配置翻译密钥，请前往翻译设置；打开设置将离开当前输入目标")
            page.refresh()
            return
        }
        val target = page.target ?: return
        cancelTranslation()
        val token = translationRequest
        val request = TranslationRequest(page.source, target, translationSettings().model)
        val pageToken = page.state.beginRequest()
        page.error = null
        page.refresh()
        translationJob = scope.launch {
            val result = try { Result.success(onTranslate(request, force)) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { Result.failure(error) }
            main.post {
                if (translationPage !== page || token != translationRequest || root == null) return@post
                if (!page.state.finishRequest(pageToken, result.map { it.text })) return@post
                result.fold({ translated ->
                    if (translated.text.isBlank()) page.error = translate("翻译返回为空，请重新翻译")
                }, { error ->
                    onError("Translate clip", error)
                    page.error = translate(com.actionmental.ui.translationFailureMessage(error)) +
                        if (page.result != null) "\n" + translate("重译未完成，保留上次译文") else ""
                })
                page.refresh()
                if (page.historyExpanded) loadTranslationHistory(page)
            }
        }
    }

    private fun copyTranslation(page: ClipTranslatePage) {
        val text = page.result ?: return
        if (page.inserting || page.loading) return
        val host = root ?: return
        scope.launch {
            val copied = try { onCopyTranslation(text) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { onError("Copy translation", error); false }
            main.post {
                if (root !== host) return@post
                Toast.makeText(host.context, translate(if (copied) "已复制到剪贴板" else "复制失败"), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun insertTranslation(page: ClipTranslatePage) {
        val text = page.result ?: return
        val host = root ?: return
        val lp = params ?: return
        if (page.inserting || page.loading) return
        returnTranslationInput(page)
        page.inserting = true
        page.error = null
        page.refresh()
        val token = ++translationRequest
        host.visibility = View.INVISIBLE
        lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        owner?.getSystemService(WindowManager::class.java)?.updateViewLayout(host, lp)
        host.postDelayed({
            if (root !== host || translationPage !== page || token != translationRequest) return@postDelayed
            translationJob = scope.launch {
                val success = try { onInsertTranslation(text) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { onError("Insert translation", error); false }
                main.post {
                    if (root !== host || translationPage !== page || token != translationRequest) return@post
                    if (success) close() else {
                        restoreTranslationWindow()
                        page.inserting = false
                        page.error = translate("未能写入原输入框，请回到原应用聚焦后重试；译文已保留")
                        page.refresh()
                        page.confirm.requestFocus()
                    }
                }
            }
        }, 80L)
    }

    private fun restoreTranslationWindow() {
        val host = root ?: return
        val lp = params ?: return
        lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        owner?.getSystemService(WindowManager::class.java)?.updateViewLayout(host, lp)
        host.visibility = View.VISIBLE
    }

    private fun loadTranslationHistory(page: ClipTranslatePage) {
        if (!page.historyExpanded || translationPage !== page) return
        val token = ++historyRequest
        scope.launch {
            val result = try { Result.success(translationHistory()) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { Result.failure(error) }
            main.post {
                if (translationPage !== page || token != historyRequest || !page.historyExpanded) return@post
                page.history.removeAllViews()
                result.fold({ entries ->
                    if (entries.isEmpty()) page.history.addView(TextView(card.context).apply { text = translate("暂无本地翻译历史"); setTextColor(palette.faint) })
                    entries.forEach { entry -> page.history.addView(translationHistoryRow(page, entry)) }
                    if (entries.isNotEmpty()) page.history.addView(actionButton(card.context, translate("清空本地翻译历史"), Tone.TEXT) {
                        showPopup(page.history, listOf(MenuEntry(translate("确认清空本地翻译历史"), danger = true) {
                            mutateTranslationHistory(page) { onClearTranslations() }
                        }), false)
                    })
                }, { error ->
                    onError("Read translation history", error)
                    page.error = translate("无法读取本地翻译历史，请重试")
                    page.history.addView(actionButton(card.context, page.error!!, Tone.TEXT) { loadTranslationHistory(page) })
                    page.refresh()
                })
            }
        }
    }

    private fun translationHistoryRow(page: ClipTranslatePage, entry: TranslationHistoryEntry): View {
        val context = card.context
        val time = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
            .format(java.util.Date(entry.createdAtMs))
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            minimumHeight = dp(context, 72f).toInt()
            // Same inner padding and corner as the translation page's boxes, so the history reads as part of its grid
            setPadding(dp(context, 14f).toInt(), dp(context, 10f).toInt(), dp(context, 8f).toInt(), dp(context, 10f).toInt())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            background = RippleDrawable(ColorStateList.valueOf(palette.ripple), null,
                GradientDrawable().apply { cornerRadius = dp(context, 14f); setColor(Color.WHITE) })
            isFocusable = true
            contentDescription = entry.targetName + " · " + time + "\n" + ClipPolicy.preview(entry.result) + "\n" + ClipPolicy.preview(entry.source)
            fun line(value: String, size: Float, color: Int, lines: Int) = TextView(context).apply {
                text = value
                setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
                setTextColor(color)
                maxLines = lines
                ellipsize = TextUtils.TruncateAt.END
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                setPadding(0, dp(context, 2f).toInt(), 0, dp(context, 2f).toInt())
            }
            addView(line(entry.targetName + " · " + time, 12f, palette.emphasis, 2))
            addView(line(ClipPolicy.preview(entry.result), 14f, palette.ink, 2))
            addView(line(ClipPolicy.preview(entry.source), 12f, palette.faint, 1))
            setOnClickListener {
                guarded("Restore translation history") {
                    if (!page.inserting) { cancelTranslation(); page.restore(entry); page.confirm.requestFocus() }
                }
            }
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(body)
            addView(actionButton(context, translate("删除"), Tone.TEXT) {
                if (!page.inserting) mutateTranslationHistory(page) { onDeleteTranslation(entry.id) }
            }.apply { contentDescription = translate("删除") + " · " + entry.targetName })
            background = GradientDrawable().apply {
                cornerRadius = dp(context, 14f)
                setColor(palette.field)
                setStroke(dp(context, 1f).toInt().coerceAtLeast(1), palette.line)
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(context, 8f).toInt() }
        }
    }

    private fun mutateTranslationHistory(page: ClipTranslatePage, block: suspend () -> Unit) {
        scope.launch {
            try { block() } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                onError("Change translation history", error)
                main.post { if (translationPage === page) { page.error = translate("历史操作失败，请重试"); page.refresh() } }
                return@launch
            }
            main.post { if (translationPage === page) loadTranslationHistory(page) }
        }
    }

    // --- 编辑 ----------------------------------------------------------------

    /**
     * 编辑页：卡片原地换成一个大输入框，顶上写着改的是哪一条，右下三个按钮从轻到重排开 ——
     * 取消（纯文字）、保存并输入（描边）、保存（实心，最右，拇指和视线最后落到的地方）。
     *
     * - Narrow windows show two full-width actions; the header back button remains the cancel route.
     * - 没改过时「保存」是灰的，正文空了两个保存都灰：删一条走长按菜单 / Del，不靠清空正文；
     * - 改过还没保存时，返回、Esc、点卡片外面都先问一句，一次手滑不会丢掉一段修改。
     */
    private inner class Editor(
        val entry: ClipEntry,
        val original: String,
        val view: View,
        val field: EditText,
        val status: TextView,
        val keys: TextView,
        val actions: LinearLayout,
        val buttons: List<Button>,
        val save: Button,
        val saveInsert: Button,
        val feedback: TextView,
        val back: Button,
    ) {
        var saving = false
        var failure: String? = null
        val text: String get() = this.field.text.toString()
        val dirty: Boolean get() = text != original

        private var narrow: Boolean? = null

        /** 按卡片宽度（dp）换版式。在测量里调用：只改布局参数的字段，不 requestLayout。 */
        fun fit(widthDp: Float) {
            val next = widthDp / field.resources.configuration.fontScale < EDITOR_NARROW_DP
            if (next == narrow) return
            narrow = next
            keys.visibility = if (next) View.GONE else View.VISIBLE
            buttons.first().visibility = if (next) View.GONE else View.VISIBLE
            (actions.layoutParams as LinearLayout.LayoutParams).apply {
                width = if (next) 0 else ViewGroup.LayoutParams.WRAP_CONTENT
                weight = if (next) 1f else 0f
            }
            buttons.forEachIndexed { i, button ->
                (button.layoutParams as LinearLayout.LayoutParams).apply {
                    width = if (next) 0 else ViewGroup.LayoutParams.WRAP_CONTENT
                    weight = if (next) 1f else 0f
                    marginStart = if (i == 0 || (next && i == 1)) 0 else dp(button.context, 8f).toInt()
                }
            }
        }

        /** 字数、是否改过、两个保存键能不能按。每敲一个字调一次。 */
        fun refresh() {
            val value = text
            val blank = value.isBlank()
            val count = value.codePointCount(0, value.length).toString() + " " + translate("个字符")
            status.text = when {
                blank -> translate("内容为空")
                dirty -> translate("已修改") + " · " + count
                else -> count
            }
            // 颜色之外字面上也写着「已修改」「内容为空」：不单靠颜色传达状态
            status.setTextColor(if (blank) palette.error else palette.faint)
            enable(save, dirty && !blank && !saving)
            enable(saveInsert, !blank && !saving)
            buttons.first().isEnabled = !saving
            back.isEnabled = !saving
            field.isEnabled = !saving
            save.text = translate(if (saving) "正在保存…" else "保存")
            feedback.text = failure ?: translate(if (blank) "内容不能为空，请输入后保存" else "仅更新这条记录，不会修改系统剪贴板")
            feedback.setTextColor(if (failure != null || blank) palette.error else palette.faint)
            feedback.visibility = if (saving) View.GONE else View.VISIBLE
        }

        private fun enable(button: Button, enabled: Boolean) {
            button.isEnabled = enabled
            button.alpha = if (enabled) 1f else 0.38f
        }
    }

    /** 先取全文（列表里只有预览）再换成编辑页。几毫秒的事，不必另画加载态。 */
    private fun openEditor(entry: ClipEntry) {
        if (inDetail) return
        dismissMenu()
        val host = root ?: return
        val request = ++editorRequest
        scope.launch {
            val result = try {
                Result.success(store.text(entry.id) ?: throw NoSuchElementException("Clip no longer exists"))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Result.failure(error)
            }
            main.post {
                if (root !== host || request != editorRequest || inDetail) return@post
                result.onSuccess { full -> guarded("打开编辑页") { showEditor(entry, full) } }
                    .onFailure {
                        onError("读取编辑正文", it)
                        Toast.makeText(host.context, translate("无法读取完整内容，请重新打开后重试"), Toast.LENGTH_LONG).show()
                    }
            }
        }
    }

    private fun showEditor(entry: ClipEntry, text: String) {
        val ed = buildEditor(card.context, entry, text)
        editor = ed
        showDetail(ed.view)
        ed.refresh()
        ed.field.setSelection(ed.field.length())
        // 先让输入框拿到焦点再改窗口：窗口变成可接输入法时，系统按此刻的焦点去起输入连接
        ed.field.requestFocus()
        setWindowIme(true)
        showKeyboard(ed.field)
        if (ValueAnimator.areAnimatorsEnabled()) {
            ed.view.alpha = 0f
            ed.view.translationY = dp(card.context, 8f)
            ed.view.animate().alpha(1f).translationY(0f).setDuration(180).setInterpolator(DecelerateInterpolator()).start()
        }
        ViewCompat.setAccessibilityPaneTitle(ed.view, translate("编辑记录"))
    }

    /** 回到列表。光标还在刚才那一条上，改过的话它已经换了新的预览。 */
    private fun leaveEditor() {
        val ed = editor ?: return
        dismissMenu()
        editor = null
        ed.field.context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(ed.field.windowToken, 0)
        ed.field.clearFocus()
        removeDetail(ed.view)
        setWindowIme(false)
        onInputReturned()
        lists.alpha = 0f
        lists.animate().alpha(1f).setDuration(120).start()
        root?.post { if (editor == null) revealSelected() }
    }

    /** 想离开编辑页：没改过直接走，改过了先问。[closePanel] 是连面板一起关（再按一次唤出键）。 */
    private fun requestExitEditor(closePanel: Boolean) {
        val ed = editor ?: return
        if (ed.saving) return
        val leave = { if (closePanel) close() else leaveEditor() }
        if (!ed.dirty) return leave()
        showPopup(
            ed.actions,
            listOf(
                MenuEntry(translate("继续编辑")) { ed.field.requestFocus() },
                MenuEntry(translate("放弃修改"), danger = true) { leave() },
            ),
            alignEnd = true,
            title = translate("放弃这次修改？"),
            minWidthDp = 200f,
        )
    }

    /**
     * 保存。[insert] 为 true 时存完接着写进原来的输入框，和在列表里按 Enter 是同一条路。
     * 没改过的「保存并输入」就是直接输入，不碰库。
     */
    private fun saveEditor(insert: Boolean) {
        val ed = editor ?: return
        if (ed.saving) return
        val text = ed.text
        if (text.isBlank()) return
        val changed = ed.dirty
        if (!changed && !insert) return
        val entry = ed.entry
        val host = root ?: return
        ed.saving = true
        ed.failure = null
        ed.refresh()
        scope.launch {
            val result = try {
                if (changed) store.updateText(entry.id, text)
                Result.success(Unit)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Result.failure(error)
            }
            main.post {
                if (changed && result.isSuccess) onEdited()
                // A dismissed or replaced overlay must never insert into another app later.
                if (root !== host || editor !== ed) return@post
                ed.saving = false
                result.fold(
                    onSuccess = {
                        if (insert) {
                            close()
                            onInsert(entry.copy(text = text, length = text.codePointCount(0, text.length)))
                        } else {
                            leaveEditor()
                            load(state.query, limit)
                            Toast.makeText(host.context, translate("修改已保存"), Toast.LENGTH_SHORT).show()
                        }
                    },
                    onFailure = {
                        onError("保存编辑", it)
                        ed.failure = translate("保存失败，修改仍保留，请重试")
                        ed.refresh()
                        ed.field.requestFocus()
                    },
                )
            }
        }
    }

    /**
     * 编辑页开着时的实体键盘。只认几颗命令键，其余交回管线走快捷键与映射，最后落到输入框 ——
     * 打字、方向键、Ctrl+A / C / V 这些都归输入框自己。
     */
    private fun onEditorKey(ed: Editor, e: NormalizedKeyEvent, modifiers: Int): Boolean? {
        val ctrl = modifiers and KeyCombo.MOD_CTRL != 0
        val shift = modifiers and KeyCombo.MOD_SHIFT != 0
        val enter = e.keyCode == KeyEvent.KEYCODE_ENTER || e.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
        when {
            e.keyCode == KeyEvent.KEYCODE_ESCAPE -> requestExitEditor(closePanel = false)
            // 导航栏返回：键盘弹着时先收键盘，和普通应用里一样；再按一次才退回列表
            e.keyCode == KeyEvent.KEYCODE_BACK -> if (imeVisible()) hideKeyboard(ed.field) else requestExitEditor(closePanel = false)
            e.repeatCount == 0 && isToggle(e) -> requestExitEditor(closePanel = true)
            ctrl && enter -> saveEditor(insert = shift)
            ctrl && e.keyCode == KeyEvent.KEYCODE_S -> saveEditor(insert = false)
            else -> return null
        }
        return true
    }

    /**
     * 窗口接不接输入法。列表模式不接（ALT_FOCUSABLE_IM，见 [show]），编辑页要接：
     * 窗口有焦点时这一位一变，系统就按当前焦点所在的输入框重新起输入连接。
     */
    private fun setWindowIme(on: Boolean) {
        val view = root ?: return
        val lp = params ?: return
        lp.flags = if (on) {
            lp.flags and WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM.inv()
        } else {
            lp.flags or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
        }
        lp.softInputMode = if (on) {
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
        } else {
            WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED
        }
        runCatching { owner?.getSystemService(WindowManager::class.java)?.updateViewLayout(view, lp) }
            .onFailure { onError("切换面板输入法", it) }
    }

    /** 窗口刚改成可接输入法，输入连接下一帧才起来：晚一点再叫键盘。接着实体键盘时系统自己不弹，不用管。 */
    private fun showKeyboard(field: EditText) {
        field.postDelayed({
            val translationField = translationPage?.let { it.sourceField === field || it.instructionField === field } == true ||
                speechPage?.draftField === field
            if (editor?.field !== field && (!translationField || !translationInputBorrowed || !field.hasFocus())) return@postDelayed
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) field.windowInsetsController?.show(WindowInsets.Type.ime())
            field.context.getSystemService(InputMethodManager::class.java)?.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
        }, KEYBOARD_DELAY_MS)
    }

    private fun hideKeyboard(field: EditText) {
        field.context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(field.windowToken, 0)
    }

    private fun imeVisible(): Boolean = root?.let {
        ViewCompat.getRootWindowInsets(it)?.isVisible(WindowInsetsCompat.Type.ime())
    } == true

    private fun buildEditor(context: Context, entry: ClipEntry, text: String): Editor {
        val back = Button(context).apply {
            this.text = "←"
            setTextColor(palette.ink)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            isAllCaps = false
            stateListAnimator = null
            includeFontPadding = false
            setPadding(0, 0, 0, 0)
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            contentDescription = translate("返回列表")
            val mask = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
            background = RippleDrawable(ColorStateList.valueOf(palette.ripple), null, mask)
            val size = dp(context, 48f).toInt()
            // 往左让一点：箭头的笔画和下面输入框的左缘对齐，触控区仍是完整的 44dp
            layoutParams = LinearLayout.LayoutParams(size, size).apply { marginStart = -dp(context, 10f).toInt() }
            setOnClickListener { guarded("编辑页返回") { requestExitEditor(closePanel = false) } }
        }
        val title = TextView(context).apply {
            this.text = translate("编辑记录")
            setTextColor(palette.ink)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }
        // 改的是哪一条：来源、多久前用过、是不是置顶的。和列表那一行的第二行一个写法，认得出来
        val meta = TextView(context).apply {
            this.text = listOfNotNull(
                entry.sourcePackage?.let { labelOf(context, it) },
                translate(ClipPolicy.age(System.currentTimeMillis(), entry.lastUsedAtMs)),
                if (entry.pinned) translate("置顶") else null,
            ).joinToString(" · ")
            setTextColor(palette.faint)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }
        val titles = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 2f).toInt(), 0, dp(context, 12f).toInt(), 0)
            addView(title)
            addView(meta)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val status = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.MONOSPACE
            isSingleLine = true
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, dp(context, 4f).toInt(), 0)
            addView(back)
            addView(titles)
        }

        val fieldId = View.generateViewId()
        val fieldLabel = TextView(context).apply {
            this.text = translate("内容")
            labelFor = fieldId
            setTextColor(palette.mid)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val fieldHeading = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(context, 8f).toInt(), 0, 0)
            addView(fieldLabel)
            addView(status)
        }
        val feedback = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            setPadding(0, 0, 0, dp(context, 8f).toInt())
        }
        val radius = dp(context, 14f)
        val field = EditText(context).apply {
            id = fieldId
            contentDescription = translate("内容")
            setText(text)
            hint = translate("在这里输入内容")
            setTextColor(palette.ink)
            setHintTextColor(palette.faint)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setLineSpacing(0f, 1.35f)
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            setSaveEnabled(false)
            // 横屏时输入法默认全屏接管、把面板整个盖住：要看着原文改，不要全屏
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            filters = arrayOf(InputFilter.LengthFilter(ClipPolicy.MAX_CHARS))
            setHorizontallyScrolling(false)
            isVerticalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            highlightColor = ColorUtils.setAlphaComponent(palette.accent, 0x55)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                textCursorDrawable = GradientDrawable().apply {
                    setColor(palette.accent)
                    setSize(dp(context, 2f).toInt(), dp(context, 20f).toInt())
                }
            }
            val padH = dp(context, 14f).toInt()
            val padV = dp(context, 12f).toInt()
            setPadding(padH, padV, padH, padV)
            // 聚焦时描边换成强调色、加粗半个 dp：看得出光标在这里，又不像报错那样扎眼
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
                    cornerRadius = radius
                    setColor(palette.field)
                    setStroke(dp(context, 1.5f).toInt(), palette.emphasis)
                })
                addState(intArrayOf(), GradientDrawable().apply {
                    cornerRadius = radius
                    setColor(palette.field)
                    setStroke(dp(context, 1f).toInt(), palette.line)
                })
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 96f).toInt(), 1f)
                .apply { topMargin = dp(context, 8f).toInt(); bottomMargin = dp(context, 8f).toInt() }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    editor?.refresh()
                }
            })
        }

        val keys = TextView(context).apply {
            this.text = translate("Ctrl+Enter 保存 · Ctrl+Shift+Enter 保存并输入 · Esc 取消")
            setTextColor(palette.faint)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            maxLines = 2
            setPadding(dp(context, 4f).toInt(), 0, dp(context, 12f).toInt(), 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val cancel = actionButton(context, translate("取消"), Tone.TEXT) { requestExitEditor(closePanel = false) }
        val saveInsert = actionButton(context, translate("保存并输入"), Tone.OUTLINE) { saveEditor(insert = true) }
        val save = actionButton(context, translate("保存"), Tone.PRIMARY) { saveEditor(insert = false) }
        val buttons = listOf(cancel, saveInsert, save)
        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            buttons.forEach(::addView)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val footer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(keys)
            addView(actions)
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(header)
            addView(fieldHeading)
            addView(field)
            addView(feedback)
            addView(footer)
        }
        // Keep a usable field on short landscape/IME windows; let the whole form scroll only when needed.
        val view = ScrollView(context).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = true
            addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        return Editor(entry, text, view, field, status, keys, actions, buttons, save, saveInsert, feedback, back)
    }

    /** 按钮的三档轻重：纯文字 < 描边 < 实心。 */
    private enum class Tone { TEXT, OUTLINE, PRIMARY }

    /** Editor actions have a 48dp minimum target and a 3dp hard shadow on the primary button. */
    private fun actionButton(context: Context, label: String, tone: Tone, onClick: () -> Unit): Button = Button(context).apply {
        text = label
        isAllCaps = false
        stateListAnimator = null
        isSingleLine = false
        maxLines = 2
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        typeface = if (tone == Tone.PRIMARY) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        val height = dp(context, 48f).toInt()
        val lift = dp(context, 3f).toInt()
        minWidth = dp(context, 64f).toInt()
        minimumWidth = minWidth
        minHeight = height
        minimumHeight = height
        val padH = dp(context, 16f).toInt()
        // 实心那档的面比按钮矮 3dp（下面是阴影），字跟着面居中
        setPadding(padH, 0, padH, if (tone == Tone.PRIMARY) lift else 0)
        val radius = dp(context, 12f)
        val mask = GradientDrawable().apply { cornerRadius = radius; setColor(Color.WHITE) }
        when (tone) {
            Tone.TEXT -> {
                setTextColor(palette.mid)
                background = RippleDrawable(ColorStateList.valueOf(palette.ripple), null, mask)
            }
            Tone.OUTLINE -> {
                setTextColor(palette.ink)
                val face = GradientDrawable().apply {
                    cornerRadius = radius
                    setColor(palette.surface)
                    setStroke(dp(context, 1f).toInt(), palette.line)
                }
                background = RippleDrawable(ColorStateList.valueOf(palette.ripple), face, mask)
            }
            Tone.PRIMARY -> {
                setTextColor(palette.onPrimary)
                val shadow = GradientDrawable().apply { cornerRadius = radius; setColor(palette.shadow) }
                val face = GradientDrawable().apply { cornerRadius = radius; setColor(palette.primary) }
                val layers = LayerDrawable(arrayOf(shadow, face)).apply {
                    setLayerInset(0, 0, lift, 0, 0)
                    setLayerInset(1, 0, 0, 0, lift)
                }
                background = RippleDrawable(
                    ColorStateList.valueOf(ColorUtils.setAlphaComponent(palette.onPrimary, 0x33)),
                    layers,
                    mask,
                )
            }
        }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        setOnClickListener { guarded("编辑页按钮") { onClick() } }
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
        // Named for both jobs so polishing and summarizing are found without opening the page first.
        translationSwitch = actionButton(context, translate("翻译与润色"), Tone.OUTLINE) {
            openTranslation()
        }.apply {
            tooltipText = "Ctrl+T"
            contentDescription = translate("翻译、润色、摘要与改写")
            val glyph = context.getDrawable(com.actionmental.R.drawable.ic_text_action)?.mutate()
            glyph?.setTint(palette.ink)
            setCompoundDrawablesRelativeWithIntrinsicBounds(glyph, null, null, null)
            compoundDrawablePadding = dp(context, 8f).toInt()
        }
        val speechButton = actionButton(context, translate("语音转文字"), Tone.OUTLINE) {
            openSpeech(null, autoStart = true)
        }.apply {
            visibility = if (speech == null) View.GONE else View.VISIBLE
            val mic = context.getDrawable(com.actionmental.R.drawable.ic_speech_microphone)?.mutate()
            mic?.setTint(palette.ink)
            setCompoundDrawablesRelativeWithIntrinsicBounds(mic, null, null, null)
            compoundDrawablePadding = dp(context, 8f).toInt()
        }
        val tools = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(translationSwitch)
            addView(speechButton, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(context, 8f).toInt() })
        }
        val titles = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(title.apply { layoutParams = LinearLayout.LayoutParams(-1, -2) })
            addView(countView)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val header = object : LinearLayout(context) {
            private var layoutMode = -1
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                // Text scaling participates in the breakpoint, keeping labels discoverable.
                val readableWidth = MeasureSpec.getSize(widthMeasureSpec) /
                    resources.displayMetrics.density / resources.configuration.fontScale
                val stacked = readableWidth < 480f
                val compact = readableWidth < 260f
                val mode = if (compact) 2 else if (stacked) 1 else 0
                if (layoutMode == mode) {
                    super.onMeasure(widthMeasureSpec, heightMeasureSpec)
                    return
                }
                layoutMode = mode
                orientation = if (stacked) VERTICAL else HORIZONTAL
                titles.layoutParams = LinearLayout.LayoutParams(if (stacked) -1 else 0, -2, if (stacked) 0f else 1f)
                tools.orientation = if (compact) VERTICAL else HORIZONTAL
                tools.layoutParams = LinearLayout.LayoutParams(if (stacked) -1 else -2, -2).apply {
                    topMargin = if (stacked) dp(context, 12f).toInt() else 0
                    marginStart = if (stacked) 0 else dp(context, 12f).toInt()
                }
                listOf(translationSwitch, speechButton).forEachIndexed { index, button ->
                    button.layoutParams = LinearLayout.LayoutParams(
                        if (compact) -1 else if (stacked) 0 else -2, -2,
                        if (stacked && !compact) 1f else 0f,
                    ).apply {
                        marginStart = if (index > 0 && !compact) dp(context, 8f).toInt() else 0
                        topMargin = if (index > 0 && compact) dp(context, 8f).toInt() else 0
                    }
                }
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            }
        }.apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(context, 4f).toInt(), 0, dp(context, 4f).toInt(), 0)
            addView(titles)
            addView(tools)
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
        val quickBar = buildQuickBar(context)
        card = LinearLayout(context).apply {
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
            addView(quickBar)
            addView(queryView)
            addView(lists)
            addView(emptyView)
            addView(hint)
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        browseViews = listOf(header, quickBar, queryView, lists, emptyView, hint)
        return PanelRoot(context, card, hint).apply {
            setBackgroundColor(palette.backdrop)
            // 点卡片外面关闭：面板是模态的，底下的应用此刻本来就点不到。
            // 编辑页开着时只当是「返回」：手一滑点到外面，不该把没保存的修改一起关掉
            setOnClickListener {
                guarded("点面板外面") {
                    when {
                        editor != null -> requestExitEditor(closePanel = false)
                        speechPage != null -> escapeSpeech()
                        translationPage != null -> leaveTranslation()
                        else -> close()
                    }
                }
            }
            addView(card)
            contentDescription = translate("剪贴板历史")
        }
    }

    /**
     * 面板的根：每次测量时按当前窗口重算卡片四周的留白，转屏、分屏、小窗都自动跟上。
     *
     * - 卡片浮在避开系统栏之后的区域正中，四周留足白：基础留白按短边取（不到 480dp 时 24dp，
     *   否则 48dp，分栏时左右可收到 24dp），上下再按可用高度的 8% 取大的那个；宽度封顶 [MAX_CARD_WIDTH_DP]，宽屏上不摊成一条；
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

        /** 返回：菜单开着先收菜单，编辑页开着先退回列表，否则关面板。 */
        private fun onBack() = guarded("返回键关闭剪贴板面板") {
            when {
                menuLayer != null -> dismissMenu()
                editor != null -> {
                    val ed = editor ?: return@guarded
                    if (imeVisible()) hideKeyboard(ed.field) else requestExitEditor(closePanel = false)
                }
                speechPage != null -> escapeSpeech()
                translationPage != null -> escapeTranslation()
                root === this -> close()
            }
        }

        /** 输入法弹出、收起都会换边衬：卡片跟着重新排，编辑框始终露在键盘上面。 */
        override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
            requestLayout()
            return super.onApplyWindowInsets(insets)
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
            val gutterDp = PanelGeometry.gutter(px2dp(width), px2dp(height))
            val gutter = dp(context, gutterDp).toInt()
            val availWidth = width - insets[0] - insets[2]
            val availHeight = height - insets[1] - insets[3]
            // 有置顶、且窗口放得下两栏才分栏：只看窗口宽度，不看转向 —— 横屏手机、平板竖放、展开的折叠屏、
            // 够宽的分屏都分；竖屏手机、副屏与窄小窗仍是单列
            val split = hasPins && PanelGeometry.columns(px2dp(availWidth))
            if (split != columns) {
                // 测量子视图之前换好：本趟测量就按新版式来，不闪一帧旧的
                columns = split
                applyColumns()
                refreshData(follow = true)
            }
            val maxCard = if (split || translationPage != null || speechPage != null) MAX_SPLIT_CARD_WIDTH_DP else MAX_CARD_WIDTH_DP
            val side = dp(context, PanelGeometry.side(px2dp(availWidth), gutterDp, maxCard, split)).toInt()
            // 键盘弹出来时高度很金贵（横屏手机上只剩一百多 dp）：上下的留白收到最小，让给编辑框
            val vertical = if ((editor != null || translationInputBorrowed) && insets[4] > 0) {
                dp(context, 12f).toInt()
            } else {
                maxOf(gutter, (availHeight * 0.08f).toInt())
            }
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
            editor?.fit(px2dp(cardWidth))
            translationPage?.fit(px2dp(cardWidth))
            speechPage?.fit(px2dp(cardWidth))
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }

        /**
         * 左、上、右、下，第五个是输入法比系统栏多占了多高（没弹出是 0）。拿不到窗口边衬时上方按 24dp 估。
         *
         * 底边取系统栏与输入法里高的那个：窗口被输入法顶小了的系统上输入法边衬是 0，
         * 没顶小的（Android 11 起多半如此）由这里让出键盘的高度，两种都不会让两次。
         */
        private fun systemInsets(): IntArray {
            val insets = rootWindowInsets ?: return intArrayOf(0, dp(context, 24f).toInt(), 0, 0, 0)
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                val ime = insets.getInsets(WindowInsets.Type.ime()).bottom
                intArrayOf(i.left, i.top, i.right, maxOf(i.bottom, ime), (ime - i.bottom).coerceAtLeast(0))
            } else {
                @Suppress("DEPRECATION")
                val bottom = insets.systemWindowInsetBottom
                @Suppress("DEPRECATION")
                intArrayOf(
                    insets.systemWindowInsetLeft,
                    insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight,
                    bottom,
                    // 旧系统的底边衬里已经含着输入法；比导航栏高出一截就当它弹出来了
                    if ((editor != null || translationInputBorrowed) && bottom > dp(context, 80f)) bottom else 0,
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
        const val EDITOR_NARROW_DP = 560f
        const val KEYBOARD_DELAY_MS = 100L

        /** 卡片最宽多少 dp：再宽一行字就长得读不过来了。 */
        const val MAX_CARD_WIDTH_DP = 640f

        /** 分两栏时卡片最宽多少 dp：左栏 2 份、右栏 3 份，右栏仍不超过单列时的宽度。 */
        const val MAX_SPLIT_CARD_WIDTH_DP = 960f

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
    private class Palette(c: AmColors, val dark: Boolean) {
        val surface = c.surface.toArgb()
        val field = c.surfaceSunken.toArgb()
        val line = c.line.toArgb()
        val ink = c.ink.toArgb()
        val mid = c.inkMid.toArgb()
        val faint = c.inkMuted.toArgb()
        val accent = c.accent.toArgb()
        val emphasis = c.accentInk.toArgb()
        val error = amColorsFor(dark, UserSettings.Accent.TERRACOTTA).accentInk.toArgb()
        val onAccent = Color.WHITE

        /** 主按钮与应用里的 AmPrimaryButton 一致：墨色面、底色字、3dp 硬阴影。两种主题下对比度都在 12:1 以上。 */
        val primary = ink
        val onPrimary = c.bgScreen.toArgb()
        val shadow = c.shadowSolid.toArgb()

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

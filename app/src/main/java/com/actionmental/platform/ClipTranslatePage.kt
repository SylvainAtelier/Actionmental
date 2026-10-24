package com.actionmental.platform

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import com.actionmental.core.clip.ClipTranslationState
import com.actionmental.core.translate.TranslationHistoryEntry
import com.actionmental.core.translate.TranslationLanguage
import com.actionmental.core.translate.TranslationRequest

/**
 * Editable draft detail. Opening, editing or changing language never makes a request.
 *
 * Everything sits on one grid so the page stays orderly from a phone to an unfolded foldable:
 * - header (48dp): back arrow, title with the privacy promise, then compact History / Settings;
 * - language bar: "译成" plus one-tap chips, full width, above both panes;
 * - two panes with the same anatomy: a 48dp label row, then a box with the same radius and padding.
 *   Side by side the boxes share one height, so tops, first lines and bottoms all line up;
 *   the request status lives at the foot of the result box instead of floating between rows;
 * - footer: side by side its cells mirror the panes (key hints under the source, actions exactly
 *   under the result); stacked, actions fill the width and hints follow.
 * Every colour comes from [Colors], which the panel derives from the app theme and accent.
 */
internal class ClipTranslatePage(
    context: Context,
    source: String,
    val languages: List<TranslationLanguage>,
    initialTarget: TranslationLanguage?,
    private val colors: Colors,
    private val tr: (String) -> String,
    button: (String, ButtonStyle, () -> Unit) -> Button,
    private val keyHint: String,
    private val onBack: () -> Unit,
    onConfirm: () -> Unit,
    onRetryOrCancel: () -> Unit,
    onHistory: () -> Unit,
    onSettings: () -> Unit,
) {
    enum class ButtonStyle { TEXT, OUTLINE, PRIMARY }

    data class Colors(
        val ink: Int,
        val mid: Int,
        val muted: Int,
        val surface: Int,
        val field: Int,
        val line: Int,
        val error: Int,
        val accent: Int,
        val accentInk: Int,
        val ripple: Int,
        val dark: Boolean,
    )

    val state = ClipTranslationState(source, initialTarget)
    val source: String get() = state.source
    val target: TranslationLanguage? get() = state.target
    val result: String? get() = state.result
    val loading: Boolean get() = state.loading
    var inserting: Boolean
        get() = state.inserting
        set(value) { state.inserting = value }
    var error: String? = null
    var historyExpanded = false
        private set
    var onSourceChanged: ((String) -> Unit)? = null
    var onSourceFocus: ((Boolean) -> Unit)? = null
    /** Chosen from a chip (few languages) or from the popup that [languagePicker] opens (many). */
    var onLanguage: ((TranslationLanguage) -> Unit)? = null
    var languagePicker: ((View) -> Unit)? = null

    private var narrow = true
    private var widthDp = 0f
    private var layoutKey: Triple<Boolean, Boolean, Boolean>? = null
    private val useChips = languages.size in 1..MAX_CHIPS

    val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, 0, 0, dp(context, 8))
    }
    val view = ScrollView(context).apply {
        isFillViewport = true
        isFocusableInTouchMode = true
        isVerticalScrollBarEnabled = false
        addView(content)
        layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
    }

    // --- Header ---------------------------------------------------------------

    private val back = Button(context).apply {
        text = "←"
        setTextColor(colors.ink)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        isAllCaps = false
        stateListAnimator = null
        includeFontPadding = false
        setPadding(0, 0, 0, 0)
        minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
        background = RippleDrawable(ColorStateList.valueOf(colors.ripple), null,
            GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) })
        val size = dp(context, 48)
        // Pull left so the arrow stroke lines up with the grid edge; the target stays 48dp.
        layoutParams = LinearLayout.LayoutParams(size, size).apply { marginStart = -dp(context, 12) }
        setOnClickListener { if (!returnFromHistory()) onBack() }
    }
    private val title = TextView(context).apply {
        setTextColor(colors.ink)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        typeface = Typeface.DEFAULT_BOLD
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
        ViewCompat.setAccessibilityHeading(this, true)
    }
    private val subtitle = TextView(context).apply {
        setTextColor(colors.muted)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
    }
    private val historyButton = compact(button(tr("历史记录"), ButtonStyle.TEXT) {
        historyExpanded = !historyExpanded
        fitWorkspace()
        onHistory()
    })
    private val settingsButton = compact(button(tr("设置"), ButtonStyle.TEXT, onSettings))
        .apply { contentDescription = tr("翻译设置 · 将离开当前输入目标") }
    private val header = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(context, 48)
        addView(back)
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, dp(context, 8), 0)
            addView(title)
            addView(subtitle)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(historyButton, LinearLayout.LayoutParams(-2, -2))
        // Compact text buttons carry 8dp of padding: pulling them out by as much puts the glyphs on the grid edge.
        addView(settingsButton, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = -dp(context, 8) })
    }

    // --- Language bar ----------------------------------------------------------

    private val chips = FlowRow(context, dp(context, 8))
    private val languageButton = button("", ButtonStyle.OUTLINE) {}.apply {
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        minWidth = dp(context, 160)
        minimumWidth = minWidth
        setOnClickListener { languagePicker?.invoke(this) }
    }
    private val languageBar = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        // Top, not centre: when chips wrap the label stays level with the first row.
        gravity = Gravity.TOP
        addView(label(context, tr("译成")).apply {
            gravity = Gravity.CENTER_VERTICAL
            minHeight = dp(context, 48)
            minimumHeight = minHeight
            setPadding(0, 0, dp(context, 12), 0)
        })
        if (useChips) addView(chips, LinearLayout.LayoutParams(0, -2, 1f))
        else addView(languageButton, LinearLayout.LayoutParams(-2, -2))
    }

    // --- Source ---------------------------------------------------------------

    private val sourceId = View.generateViewId()
    private val sourceLabel = label(context, tr("原文")).apply { labelFor = sourceId }
    private val sourceCount = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        typeface = Typeface.MONOSPACE
        setPadding(dp(context, 8), 0, dp(context, 4), 0)
    }
    private val clear = compact(button(tr("清空"), ButtonStyle.TEXT) {
        sourceField.setText("")
        sourceField.requestFocus()
    }).apply { contentDescription = tr("清空原文") }

    @SuppressLint("ClickableViewAccessibility")
    val sourceField = EditText(context).apply {
        id = sourceId
        setText(source)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, BODY_SP)
        setTextColor(colors.ink)
        setHintTextColor(colors.muted)
        hint = tr("输入或粘贴要翻译的原文")
        contentDescription = tr("原文")
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or EditorInfo.IME_ACTION_NONE
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        setSaveEnabled(false)
        gravity = Gravity.TOP or Gravity.START
        minLines = 3
        setLineSpacing(0f, BODY_SPACING)
        setHorizontallyScrolling(false)
        isVerticalScrollBarEnabled = true
        overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        highlightColor = ColorUtils.setAlphaComponent(colors.accent, 0x55)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            textCursorDrawable = GradientDrawable().apply {
                setColor(this@ClipTranslatePage.colors.accent)
                setSize(dp(context, 2), dp(context, 20))
            }
        }
        // Same insets as the result box, so both first lines start at the same point.
        setPadding(dp(context, BOX_PAD_H), dp(context, BOX_PAD_V), dp(context, BOX_PAD_H), dp(context, BOX_PAD_V))
        background = fieldBackground()
        // Long sources scroll inside the field; keep the page from stealing that drag.
        setOnTouchListener { v, e ->
            if (v.canScrollVertically(1) || v.canScrollVertically(-1)) {
                val active = e.actionMasked != MotionEvent.ACTION_UP && e.actionMasked != MotionEvent.ACTION_CANCEL
                v.parent?.requestDisallowInterceptTouchEvent(active)
            }
            false
        }
        setOnFocusChangeListener { _, focused -> onSourceFocus?.invoke(focused) }
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val value = s?.toString().orEmpty()
                if (value != state.source) onSourceChanged?.invoke(value)
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
    }
    private val sourcePane = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(headRow(context).apply {
            addView(sourceLabel, LinearLayout.LayoutParams(0, -2, 1f))
            addView(sourceCount)
            addView(clear, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = -dp(context, 8) })
        }, LinearLayout.LayoutParams(-1, -2))
        addView(sourceField)
    }

    // --- Result ---------------------------------------------------------------

    private val resultLabel = label(context, tr("译文")).apply {
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
    }
    private val resultText = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, BODY_SP)
        setLineSpacing(0f, BODY_SPACING)
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    /** Pushes the status to the foot of the box when the panes share one height. */
    private val resultSpacer = View(context)
    private val statusMark = View(context)
    private val statusText = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setLineSpacing(0f, 1.3f)
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private val status = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        visibility = View.GONE
        // A short bar in the tone colour; the words carry the meaning, the bar only groups them.
        addView(statusMark, LinearLayout.LayoutParams(dp(context, 3), -1).apply { marginEnd = dp(context, 10) })
        addView(statusText, LinearLayout.LayoutParams(0, -2, 1f))
    }
    private val resultBox = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(context, BOX_PAD_H), dp(context, BOX_PAD_V), dp(context, BOX_PAD_H), dp(context, BOX_PAD_V))
        addView(resultText, LinearLayout.LayoutParams(-1, -2))
        addView(resultSpacer)
        addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(context, 12) })
    }
    private val resultPane = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(headRow(context).apply { addView(resultLabel, LinearLayout.LayoutParams(0, -2, 1f)) },
            LinearLayout.LayoutParams(-1, -2))
        addView(resultBox)
    }

    // --- Footer ---------------------------------------------------------------

    val confirm = button(tr("翻译"), ButtonStyle.PRIMARY, onConfirm)
    private val retryOrCancel = button(tr("重新翻译"), ButtonStyle.OUTLINE, onRetryOrCancel)
    private val actions = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(retryOrCancel, LinearLayout.LayoutParams(0, -2, 1f))
        addView(confirm, LinearLayout.LayoutParams(0, -2, 2f).apply { marginStart = dp(context, 8) })
    }
    private val keys = TextView(context).apply {
        text = keyHint
        setTextColor(colors.muted)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setLineSpacing(0f, 1.3f)
    }
    /** Holds the hints; side by side it keeps its column even when the hints are hidden. */
    private val footerLead = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        addView(keys, LinearLayout.LayoutParams(-1, -2))
    }
    private val footer = LinearLayout(context)

    private val panes = LinearLayout(context)
    val draft = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(languageBar, LinearLayout.LayoutParams(-1, -2))
        addView(panes, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(context, 4) })
        addView(footer, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(context, 16) })
    }

    // --- History --------------------------------------------------------------

    // ClipPanel replaces only this list; the side-column heading lives outside it.
    val history = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    /** Same 48dp row as the language bar, so the two columns start on one line. */
    private val historyHeading = headRow(context).apply {
        addView(label(context, tr("翻译历史")).apply { ViewCompat.setAccessibilityHeading(this, true) },
            LinearLayout.LayoutParams(0, -2, 1f))
        addView(TextView(context).apply {
            text = tr("仅保存在本机")
            setTextColor(colors.muted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        })
    }
    private val historySection = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        addView(historyHeading, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(context, 4) })
        addView(history, LinearLayout.LayoutParams(-1, -2))
    }
    /** Hairline between the draft and the history column. */
    private val columnRule = View(context).apply {
        setBackgroundColor(colors.line)
        visibility = View.GONE
    }
    private val workspace = LinearLayout(context).apply {
        addView(draft)
        addView(columnRule)
        addView(historySection)
    }

    init {
        panes.addView(sourcePane)
        panes.addView(resultPane)
        content.addView(header, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        content.addView(workspace, LinearLayout.LayoutParams(-1, -2))
        if (useChips) languages.forEach { chips.addView(chip(context, it)) }
        ViewCompat.setAccessibilityPaneTitle(view, tr("翻译"))
        fitWorkspace()
        refresh()
    }

    fun fit(widthDp: Float) {
        val next = widthDp / view.resources.configuration.fontScale < 720f
        if (next == narrow && this.widthDp == widthDp) return
        this.widthDp = widthDp
        narrow = next
        fitWorkspace()
    }

    private fun fitWorkspace() {
        val scaledWidth = widthDp / view.resources.configuration.fontScale
        val sideHistory = !narrow && historyExpanded
        val available = if (sideHistory) scaledWidth * 2f / 3f else scaledWidth
        val stacked = available < 560f
        val nextKey = Triple(narrow, historyExpanded, stacked)
        // fit() is called during measurement: never reassign unchanged layout params.
        if (layoutKey != nextKey) {
            layoutKey = nextKey
            val gap = dp(GAP)
            workspace.orientation = if (narrow) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            draft.visibility = if (narrow && historyExpanded) View.GONE else View.VISIBLE
            historySection.visibility = if (historyExpanded) View.VISIBLE else View.GONE
            // On a phone the history replaces the draft and the header already names it.
            historyHeading.visibility = if (narrow) View.GONE else View.VISIBLE
            columnRule.visibility = if (sideHistory) View.VISIBLE else View.GONE
            draft.layoutParams = LinearLayout.LayoutParams(if (sideHistory) 0 else -1, -2, if (sideHistory) 2f else 0f)
            columnRule.layoutParams = LinearLayout.LayoutParams(dp(1).coerceAtLeast(1), -1)
                .apply { marginStart = gap; marginEnd = gap }
            historySection.layoutParams = LinearLayout.LayoutParams(if (narrow) -1 else 0, -2, if (narrow) 0f else 1f)

            // Side by side both panes take the taller one's height (match_parent inside a
            // wrap_content row), and each box fills what its 48dp label row leaves over.
            panes.orientation = if (stacked) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            sourcePane.layoutParams = LinearLayout.LayoutParams(if (stacked) -1 else 0, if (stacked) -2 else -1,
                if (stacked) 0f else 1f)
            resultPane.layoutParams = LinearLayout.LayoutParams(if (stacked) -1 else 0, if (stacked) -2 else -1,
                if (stacked) 0f else 1f).apply { if (stacked) topMargin = dp(8) else marginStart = gap }
            sourceField.layoutParams = LinearLayout.LayoutParams(-1, if (stacked) -2 else 0, if (stacked) 0f else 1f)
            resultBox.layoutParams = LinearLayout.LayoutParams(-1, if (stacked) -2 else 0, if (stacked) 0f else 1f)
            resultSpacer.layoutParams = LinearLayout.LayoutParams(-1, 0, if (stacked) 0f else 1f)
            if (stacked) {
                sourceField.minLines = 3
                resultBox.minimumHeight = dp(STACKED_BOX_DP)
            } else {
                sourceField.minHeight = dp(SIDE_BOX_DP)
                resultBox.minimumHeight = dp(SIDE_BOX_DP)
            }
            // Side by side the field may grow; stacked it stays short so the result is in view.
            val lines = if (stacked) 6 else 12
            if (sourceField.maxLines != lines) sourceField.maxLines = lines

            // Side by side the footer mirrors the panes: hints under the source, actions under the result.
            footer.removeAllViews()
            if (stacked) {
                footer.orientation = LinearLayout.VERTICAL
                footer.addView(actions, LinearLayout.LayoutParams(-1, -2))
                footer.addView(footerLead, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
            } else {
                footer.orientation = LinearLayout.HORIZONTAL
                footer.gravity = Gravity.CENTER_VERTICAL
                footer.addView(footerLead, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = gap })
                footer.addView(actions, LinearLayout.LayoutParams(0, -2, 1f))
            }
            fitHeader()
        }
        val hardware = hasHardwareKeyboard()
        val keysVisibility = if (hardware && keyHint.isNotBlank()) View.VISIBLE else View.GONE
        if (keys.visibility != keysVisibility) keys.visibility = keysVisibility
    }

    private fun fitHeader() {
        val inHistory = narrow && historyExpanded
        title.text = tr(if (inHistory) "翻译历史" else "翻译")
        subtitle.text = tr(if (inHistory) "仅保存在本机" else "点击翻译时才联网 · 不写入剪贴板")
        back.contentDescription = tr(if (inHistory) "返回翻译详情" else "返回列表")
        historyButton.visibility = if (inHistory) View.GONE else View.VISIBLE
        historyButton.text = tr(if (historyExpanded) "收起历史记录" else "历史记录")
    }

    private fun hasHardwareKeyboard(): Boolean {
        val config = view.resources.configuration
        return config.keyboard == Configuration.KEYBOARD_QWERTY &&
            config.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO
    }

    fun returnFromHistory(): Boolean {
        if (!narrow || !historyExpanded) return false
        historyExpanded = false
        fitWorkspace()
        return true
    }

    fun restore(entry: TranslationHistoryEntry) {
        state.restore(entry, languages)
        error = null
        if (narrow) historyExpanded = false
        fitWorkspace()
        refresh()
    }

    fun refresh() {
        if (sourceField.text.toString() != source) sourceField.setText(source)
        sourceField.isEnabled = !inserting
        sourceField.alpha = if (inserting) 0.6f else 1f
        enable(clear, !inserting && source.isNotEmpty())
        val length = source.length
        val limit = TranslationRequest.MAX_SOURCE
        sourceCount.visibility = if (length >= limit * 4 / 5) View.VISIBLE else View.GONE
        sourceCount.text = "$length/$limit"
        sourceCount.setTextColor(if (length > limit) colors.error else colors.muted)

        for (i in 0 until chips.childCount) {
            val chip = chips.getChildAt(i) as TextView
            val selected = (chip.tag as? String) == target?.id
            chip.isSelected = selected
            // Selected chips also read bold: colour alone never carries the state.
            chip.typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            chip.isEnabled = !inserting
            chip.alpha = if (inserting) 0.5f else 1f
        }
        languageButton.text = (target?.name ?: tr("请先配置目标语言")) + "  ▾"
        enable(languageButton, !inserting && languages.isNotEmpty())

        resultLabel.text = target?.let { tr("译文") + " · " + it.name } ?: tr("译文")
        resultText.text = result ?: tr(when {
            loading -> "正在翻译…"
            languages.isEmpty() -> "请先配置目标语言"
            else -> "点击「翻译」获取译文"
        })
        resultText.setTextColor(if (result != null) colors.ink else colors.muted)
        val tint = if (result != null) (if (colors.dark) 0.16f else 0.08f) else (if (colors.dark) 0.08f else 0.04f)
        resultBox.background = card(
            ColorUtils.blendARGB(colors.surface, colors.accent, tint),
            ColorUtils.blendARGB(colors.line, colors.accent, if (result != null || loading) 0.7f else 0.35f),
        )

        val message = error ?: when {
            inserting -> tr("正在输入…")
            loading -> tr("正在请求 · 切换语言将取消，不会自动重发")
            else -> null
        }
        status.visibility = if (message == null) View.GONE else View.VISIBLE
        if (message != null) {
            val tone = if (error != null) colors.error else colors.accentInk
            statusText.text = message
            statusText.setTextColor(tone)
            statusMark.background = GradientDrawable().apply { cornerRadius = dp(2).toFloat(); setColor(tone) }
        }

        confirm.text = tr(when { inserting -> "正在输入…"; loading -> "正在翻译…"; result != null -> "输入译文"; else -> "翻译" })
        enable(confirm, !loading && !inserting && target != null && source.isNotBlank(), disabledAlpha = 0.38f)
        // Before the first result a second "translate" button would only repeat the primary one.
        retryOrCancel.visibility = if (result != null || loading) View.VISIBLE else View.GONE
        (confirm.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
            val start = if (retryOrCancel.visibility == View.VISIBLE) dp(8) else 0
            if (lp.marginStart != start) { lp.marginStart = start; confirm.layoutParams = lp }
        }
        retryOrCancel.text = tr(if (loading) "取消请求" else "重新翻译")
        enable(retryOrCancel, !inserting && target != null && source.isNotBlank())
        enable(historyButton, !inserting)
        enable(settingsButton, !inserting)
        enable(back, !inserting)
    }

    private fun enable(view: View, enabled: Boolean, disabledAlpha: Float = 0.5f) {
        view.isEnabled = enabled
        view.alpha = if (enabled) 1f else disabledAlpha
    }

    /** Text buttons in label rows: 8dp side padding so a -8dp margin lands their glyphs on the grid edge. */
    private fun <T : Button> compact(button: T): T = button.apply {
        val side = dp(8)
        setPadding(side, paddingTop, side, paddingBottom)
        minWidth = dp(48)
        minimumWidth = minWidth
    }

    /** A 48dp label row; every section starts with one so labels share a baseline across columns. */
    private fun headRow(context: Context) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(context, 48)
    }

    /** A 48dp-tall target whose visible pill is 36dp: compact to look at, easy to hit. */
    private fun chip(context: Context, language: TranslationLanguage) = TextView(context).apply {
        tag = language.id
        text = language.name
        contentDescription = tr("译成") + " " + language.name
        gravity = Gravity.CENTER
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        isClickable = true
        isFocusable = true
        minHeight = dp(context, 48)
        minimumHeight = minHeight
        minWidth = dp(context, 56)
        minimumWidth = minWidth
        val inset = dp(context, 6)
        setPadding(dp(context, 14), inset, dp(context, 14), inset)
        val radius = dp(context, 18).toFloat()
        fun pill(fill: Int, stroke: Int, width: Int) = InsetDrawable(GradientDrawable().apply {
            cornerRadius = radius
            setColor(fill)
            setStroke(width, stroke)
        }, 0, inset, 0, inset)
        val on = pill(ColorUtils.blendARGB(colors.surface, colors.accent, if (colors.dark) 0.28f else 0.16f),
            colors.accent, dp(context, 1).coerceAtLeast(1) * 2)
        val off = pill(colors.surface, colors.line, dp(context, 1).coerceAtLeast(1))
        val face = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_selected), on)
            addState(intArrayOf(), off)
        }
        background = RippleDrawable(ColorStateList.valueOf(colors.ripple), face,
            InsetDrawable(GradientDrawable().apply { cornerRadius = radius; setColor(Color.WHITE) }, 0, inset, 0, inset))
        setTextColor(ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_selected), intArrayOf()),
            intArrayOf(colors.accentInk, colors.ink),
        ))
        setOnClickListener { if (!isSelected) onLanguage?.invoke(language) }
        layoutParams = ViewGroup.LayoutParams(-2, -2)
    }

    private fun fieldBackground(): Drawable {
        val radius = dp(view.context, BOX_RADIUS).toFloat()
        fun face(stroke: Int, width: Int) = GradientDrawable().apply {
            cornerRadius = radius
            setColor(this@ClipTranslatePage.colors.field)
            setStroke(width, stroke)
        }
        val hair = dp(view.context, 1).coerceAtLeast(1)
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), face(colors.accent, hair * 2))
            addState(intArrayOf(), face(colors.line, hair))
        }
    }

    private fun label(context: Context, value: String) = TextView(context).apply {
        text = value
        setTextColor(colors.mid)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private fun card(fill: Int, stroke: Int) = GradientDrawable().apply {
        cornerRadius = dp(BOX_RADIUS).toFloat()
        setColor(fill)
        setStroke(dp(1).coerceAtLeast(1), stroke)
    }

    private fun dp(value: Int) = dp(view.context, value)
    private fun dp(context: Context, value: Int) = (context.resources.displayMetrics.density * value).toInt()

    private companion object {
        /** Up to this many languages show as chips; more open a popup instead of wrapping into a wall. */
        const val MAX_CHIPS = 6

        /** Grid tokens: column gutter, box corner and inner padding are shared by every box on the page. */
        const val GAP = 16
        const val BOX_RADIUS = 14
        const val BOX_PAD_H = 14
        const val BOX_PAD_V = 12

        /** Source and result use one type size, so their lines run level side by side. */
        const val BODY_SP = 16f
        const val BODY_SPACING = 1.4f

        /** Box floor: side by side tall enough to read as a pair; stacked short so the result stays in view. */
        const val SIDE_BOX_DP = 176
        const val STACKED_BOX_DP = 96
    }
}

/** Left-to-right wrapping row (mirrored for RTL). Children are measured at their natural size. */
private class FlowRow(context: Context, private val gap: Int) : ViewGroup(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val mode = MeasureSpec.getMode(widthMeasureSpec)
        val limit = if (mode == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE else MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        val childSpec = MeasureSpec.makeMeasureSpec(limit.coerceAtLeast(0), if (limit == Int.MAX_VALUE) MeasureSpec.UNSPECIFIED else MeasureSpec.AT_MOST)
        var x = 0
        var y = 0
        var row = 0
        var widest = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            child.measure(childSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            if (x > 0 && x + child.measuredWidth > limit) {
                y += row
                x = 0
                row = 0
            }
            x += child.measuredWidth
            widest = maxOf(widest, x)
            x += gap
            row = maxOf(row, child.measuredHeight)
        }
        val width = if (mode == MeasureSpec.EXACTLY) MeasureSpec.getSize(widthMeasureSpec) else widest + paddingLeft + paddingRight
        setMeasuredDimension(resolveSize(width, widthMeasureSpec), resolveSize(y + row + paddingTop + paddingBottom, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val limit = r - l - paddingLeft - paddingRight
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        var x = 0
        var y = paddingTop
        var row = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            if (x > 0 && x + child.measuredWidth > limit) {
                y += row
                x = 0
                row = 0
            }
            val left = if (rtl) r - l - paddingRight - x - child.measuredWidth else paddingLeft + x
            child.layout(left, y, left + child.measuredWidth, y + child.measuredHeight)
            x += child.measuredWidth + gap
            row = maxOf(row, child.measuredHeight)
        }
    }
}

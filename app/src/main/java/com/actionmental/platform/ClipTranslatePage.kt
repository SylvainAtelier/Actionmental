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
import android.widget.Space
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import com.actionmental.core.clip.ClipTranslationState
import com.actionmental.core.translate.TranslationHistoryEntry
import com.actionmental.core.translate.TranslationLanguage
import com.actionmental.core.translate.TranslationRequest
import com.actionmental.core.translate.TextAction

/**
 * Editable draft detail. Opening, editing or changing language never makes a request.
 *
 * Everything sits on one grid so the page stays orderly from a phone to an unfolded foldable:
 * - header (48dp): back arrow, title with the privacy promise, then compact History / Settings;
 * - action bar: translate, polish, summarize, rewrite or a custom instruction, all through one request path;
 * - language bar (translate only) or instruction field (custom only), full width, above both panes;
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
    onCopy: () -> Unit,
    onHistory: () -> Unit,
    onSettings: () -> Unit,
    onSpeech: (() -> Unit)? = null,
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
    /** Fires with the field that took input focus, or null once neither editable field holds it. */
    var onSourceFocus: ((EditText?) -> Unit)? = null
    /** The chosen action; [state] holds the request target derived from it. */
    var action: TextAction = TextAction.of(initialTarget?.id)
        private set
    var onAction: ((TextAction) -> Unit)? = null
    var onInstructionChanged: ((String) -> Unit)? = null
    /** The language to return to when switching back to translation. */
    private var lastLanguage: TranslationLanguage? = initialTarget?.takeIf { action == TextAction.TRANSLATE }
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
    private val scroll = ScrollView(context).apply {
        isFillViewport = true
        isFocusableInTouchMode = true
        isVerticalScrollBarEnabled = false
        addView(content)
    }

    /** Pinned bar under the scroll area; holds the actions on narrow windows, where the thumb rests. */
    private val bottomBar = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        addView(View(context).apply { setBackgroundColor(colors.line) },
            LinearLayout.LayoutParams(-1, dp(context, 1).coerceAtLeast(1)).apply { bottomMargin = dp(context, 12) })
    }

    /**
     * Header and actions stay pinned; only the draft scrolls. A tall source on a short, wide window
     * (an unfolded foldable in landscape) used to push the only "translate" button out of view.
     */
    val view = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        // Taking focus back from the source must land here, not bounce into the first focusable child.
        isFocusableInTouchMode = true
        descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
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
    /** Wide windows: the actions close the header row, top right, after the quieter text buttons. */
    private val headerActions = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        visibility = View.GONE
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

    // --- Action bar -----------------------------------------------------------

    private val actionChips = FlowRow(context, dp(context, 8))
    private val actionBar = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.TOP
        addView(label(context, tr("处理")).apply {
            gravity = Gravity.CENTER_VERTICAL
            minHeight = dp(context, 48)
            minimumHeight = minHeight
            setPadding(0, 0, dp(context, 12), 0)
        })
        addView(actionChips, LinearLayout.LayoutParams(0, -2, 1f))
    }

    private val instructionId = View.generateViewId()
    val instructionField = EditText(context).apply {
        id = instructionId
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setTextColor(colors.ink)
        setHintTextColor(colors.muted)
        hint = tr("例如：改成正式的邮件语气")
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        setSaveEnabled(false)
        maxLines = 3
        filters = arrayOf(android.text.InputFilter.LengthFilter(TextAction.MAX_INSTRUCTION))
        setPadding(dp(context, BOX_PAD_H), dp(context, 10), dp(context, BOX_PAD_H), dp(context, 10))
        background = fieldBackground(context)
        setOnFocusChangeListener { _, focused -> focusChanged(this, focused) }
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                onInstructionChanged?.invoke(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
    }
    private val instructionPane = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(headRow(context).apply {
            addView(label(context, tr("指令")).apply { labelFor = instructionId }, LinearLayout.LayoutParams(0, -2, 1f))
        }, LinearLayout.LayoutParams(-1, -2))
        addView(instructionField, LinearLayout.LayoutParams(-1, -2))
    }

    /** The target [next] would use; remembers the language when leaving translation. */
    fun targetFor(next: TextAction): TranslationLanguage? {
        if (action == TextAction.TRANSLATE) target?.let { lastLanguage = it }
        return if (next == TextAction.TRANSLATE) lastLanguage ?: languages.firstOrNull()
        else next.target(tr(next.label), instructionField.text.toString())
    }

    fun selectAction(next: TextAction) {
        action = next
        // The instruction field disappears with the custom action; its focus must not linger there.
        if (next != TextAction.CUSTOM && instructionField.hasFocus()) view.requestFocus()
    }

    private fun focusChanged(field: EditText, focused: Boolean) {
        if (focused) onSourceFocus?.invoke(field)
        // Moving between the two fields must not hand the input back in between.
        else field.post { if (!sourceField.hasFocus() && !instructionField.hasFocus()) onSourceFocus?.invoke(null) }
    }

    /** True while either editable field has focus: typed keys belong to it, not to page shortcuts. */
    val editing: Boolean get() = sourceField.hasFocus() || instructionField.hasFocus()

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
    /** Dictate into the source: the panel steps aside for the recording page, then returns here filled. */
    private val dictate = onSpeech?.let { action ->
        compact(button(tr("语音输入"), ButtonStyle.TEXT, action)).apply {
            contentDescription = tr("语音输入原文")
            context.getDrawable(com.actionmental.R.drawable.ic_speech_microphone)?.mutate()?.let { mic ->
                mic.setTint(colors.ink)
                setCompoundDrawablesRelativeWithIntrinsicBounds(mic, null, null, null)
                compoundDrawablePadding = dp(context, 4)
            }
        }
    }

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
        background = fieldBackground(context)
        // Long sources scroll inside the field; keep the page from stealing that drag.
        setOnTouchListener { v, e ->
            if (v.canScrollVertically(1) || v.canScrollVertically(-1)) {
                val active = e.actionMasked != MotionEvent.ACTION_UP && e.actionMasked != MotionEvent.ACTION_CANCEL
                v.parent?.requestDisallowInterceptTouchEvent(active)
            }
            false
        }
        setOnFocusChangeListener { _, focused -> focusChanged(this, focused) }
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
            dictate?.let { addView(it, LinearLayout.LayoutParams(-2, -2)) }
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
    private val copy = button(tr("复制"), ButtonStyle.OUTLINE, onCopy)
    private val actions = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(retryOrCancel)
        addView(copy)
        addView(confirm)
    }
    /** Where the actions sit right now; null before the first fit. */
    private var actionsInHeader: Boolean? = null
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
        addView(actionBar, LinearLayout.LayoutParams(-1, -2))
        addView(languageBar, LinearLayout.LayoutParams(-1, -2))
        addView(instructionPane, LinearLayout.LayoutParams(-1, -2))
        addView(panes, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(context, 4) })
        addView(footer, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(context, 16) })
    }

    // --- History --------------------------------------------------------------

    // ClipPanel replaces only this list; the side-column heading lives outside it.
    val history = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    /** Same 48dp row as the language bar, so the two columns start on one line. */
    private val historyHeading = headRow(context).apply {
        addView(label(context, tr("历史记录")).apply { ViewCompat.setAccessibilityHeading(this, true) },
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
        header.addView(headerActions, LinearLayout.LayoutParams(-2, -2))
        content.addView(workspace, LinearLayout.LayoutParams(-1, -2))
        view.addView(header, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        view.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        view.addView(bottomBar, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        if (useChips) languages.forEach { language ->
            chips.addView(chip(context, language.id, language.name, tr("译成") + " " + language.name) { onLanguage?.invoke(language) })
        }
        TextAction.entries.forEach { item ->
            val name = tr(item.label)
            actionChips.addView(chip(context, item.id, name, tr("处理方式") + " " + name) { onAction?.invoke(item) })
        }
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

            // Key hints scroll with the draft; side by side they sit under the source column only.
            footer.removeAllViews()
            footer.orientation = LinearLayout.HORIZONTAL
            footer.addView(footerLead, LinearLayout.LayoutParams(0, -2, 1f))
            if (!stacked) footer.addView(Space(view.context), LinearLayout.LayoutParams(0, 0, 1f).apply { marginStart = gap })
            placeActions(inHeader = !narrow)
            fitHeader()
        }
        val hardware = hasHardwareKeyboard()
        val keysVisibility = if (hardware && keyHint.isNotBlank()) View.VISIBLE else View.GONE
        if (keys.visibility != keysVisibility) keys.visibility = keysVisibility
    }

    /**
     * Wide windows put the actions top right, in the pinned header: in reach of the eye that just
     * read the result, and never below the fold. Narrow windows pin them to the bottom bar, in the
     * thumb zone, with the primary button taking two thirds of the width.
     */
    private fun placeActions(inHeader: Boolean) {
        if (actionsInHeader != inHeader) {
            actionsInHeader = inHeader
            (actions.parent as? ViewGroup)?.removeView(actions)
            if (inHeader) {
                retryOrCancel.layoutParams = LinearLayout.LayoutParams(-2, -2)
                copy.layoutParams = LinearLayout.LayoutParams(-2, -2)
                confirm.layoutParams = LinearLayout.LayoutParams(-2, -2)
                confirm.minWidth = dp(112)
                confirm.minimumWidth = confirm.minWidth
                headerActions.addView(actions, LinearLayout.LayoutParams(-2, -2))
            } else {
                retryOrCancel.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                copy.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                confirm.layoutParams = LinearLayout.LayoutParams(0, -2, 2f)
                bottomBar.addView(actions, LinearLayout.LayoutParams(-1, -2))
            }
            // The settings glyph meets the grid edge only when nothing follows it.
            (settingsButton.layoutParams as LinearLayout.LayoutParams).marginEnd = if (inHeader) dp(4) else -dp(8)
            settingsButton.requestLayout()
            refresh()
        }
        headerActions.visibility = if (inHeader) View.VISIBLE else View.GONE
        // On a phone the history list replaces the draft; there is nothing to act on.
        bottomBar.visibility = if (!inHeader && !historyExpanded) View.VISIBLE else View.GONE
    }

    /** The title names the current action, so the mode is never only implied by a highlighted chip. */
    private fun fitHeader() {
        val inHistory = narrow && historyExpanded
        val translating = action == TextAction.TRANSLATE
        setIfChanged(title, if (inHistory) tr("历史记录") else tr(action.label))
        setIfChanged(subtitle, tr(when {
            inHistory -> "仅保存在本机"
            translating -> "点击翻译时才联网 · 点复制时才写入剪贴板"
            else -> "点击处理时才联网 · 点复制时才写入剪贴板"
        }))
        setIfChanged(sourceField, tr(if (translating) "输入或粘贴要翻译的原文" else "输入或粘贴要处理的文字"), hint = true)
        ViewCompat.setAccessibilityPaneTitle(view, tr(action.label))
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
        action = TextAction.of(entry.targetId)
        if (action == TextAction.TRANSLATE) lastLanguage = target
        if (action == TextAction.CUSTOM) {
            val listener = onInstructionChanged
            onInstructionChanged = null
            instructionField.setText(target?.prompt.orEmpty())
            onInstructionChanged = listener
        }
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

        val translating = action == TextAction.TRANSLATE
        markChips(chips, target?.id)
        markChips(actionChips, action.id)
        val languageVisibility = if (translating) View.VISIBLE else View.GONE
        if (languageBar.visibility != languageVisibility) languageBar.visibility = languageVisibility
        val instructionVisibility = if (action == TextAction.CUSTOM) View.VISIBLE else View.GONE
        if (instructionPane.visibility != instructionVisibility) instructionPane.visibility = instructionVisibility
        instructionField.isEnabled = !inserting
        val verb = tr(action.label)
        fitHeader()
        languageButton.text = (target?.name ?: tr("请先配置目标语言")) + "  ▾"
        enable(languageButton, !inserting && languages.isNotEmpty())

        resultLabel.text = if (translating) target?.let { tr("译文") + " · " + it.name } ?: tr("译文") else tr("结果") + " · " + verb
        resultText.text = result ?: when {
            !translating && loading -> tr("正在处理…")
            !translating && target == null -> tr("先写下要怎样处理原文")
            !translating -> tr("点击「%s」获取结果").format(verb)
            loading -> tr("正在翻译…")
            languages.isEmpty() -> tr("请先配置目标语言")
            else -> tr("点击「翻译」获取译文")
        }
        resultText.setTextColor(if (result != null) colors.ink else colors.muted)
        val tint = if (result != null) (if (colors.dark) 0.16f else 0.08f) else (if (colors.dark) 0.08f else 0.04f)
        resultBox.background = card(
            ColorUtils.blendARGB(colors.surface, colors.accent, tint),
            ColorUtils.blendARGB(colors.line, colors.accent, if (result != null || loading) 0.7f else 0.35f),
        )

        val message = error ?: when {
            inserting -> tr("正在输入…")
            loading -> tr(if (translating) "正在请求 · 切换语言将取消，不会自动重发" else "正在请求 · 切换处理方式将取消，不会自动重发")
            else -> null
        }
        status.visibility = if (message == null) View.GONE else View.VISIBLE
        if (message != null) {
            val tone = if (error != null) colors.error else colors.accentInk
            statusText.text = message
            statusText.setTextColor(tone)
            statusMark.background = GradientDrawable().apply { cornerRadius = dp(2).toFloat(); setColor(tone) }
        }

        confirm.text = if (translating) tr(when { inserting -> "正在输入…"; loading -> "正在翻译…"; result != null -> "输入译文"; else -> "翻译" })
            else when { inserting -> tr("正在输入…"); loading -> tr("正在处理…"); result != null -> tr("输入结果"); else -> verb }
        enable(confirm, !loading && !inserting && target != null && source.isNotBlank(), disabledAlpha = 0.38f)
        // Before the first result a second "translate" button would only repeat the primary one.
        retryOrCancel.visibility = if (result != null || loading) View.VISIBLE else View.GONE
        // Copying a stale result while a retry is in flight would hand out the old text as if new.
        copy.visibility = if (result != null && !loading) View.VISIBLE else View.GONE
        gapBefore(copy, retryOrCancel.visibility == View.VISIBLE)
        gapBefore(confirm, retryOrCancel.visibility == View.VISIBLE || copy.visibility == View.VISIBLE)
        retryOrCancel.text = tr(if (loading) "取消请求" else if (translating) "重新翻译" else "重新生成")
        enable(retryOrCancel, !inserting && target != null && source.isNotBlank())
        enable(copy, !inserting)
        enable(historyButton, !inserting)
        enable(settingsButton, !inserting)
        enable(back, !inserting)
    }

    /** Text and hint changes request a layout; refresh runs per keystroke, so skip identical values. */
    private fun setIfChanged(view: TextView, value: String, hint: Boolean = false) {
        if (hint) { if (view.hint?.toString() != value) view.hint = value }
        else if (view.text.toString() != value) view.text = value
    }

    private fun gapBefore(view: View, gap: Boolean) {
        val lp = view.layoutParams as? LinearLayout.LayoutParams ?: return
        val start = if (gap) dp(8) else 0
        if (lp.marginStart != start) { lp.marginStart = start; view.layoutParams = lp }
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
    /** Selected chips also read bold: colour alone never carries the state. */
    private fun markChips(row: ViewGroup, selectedId: String?) {
        for (i in 0 until row.childCount) {
            val chip = row.getChildAt(i) as TextView
            val selected = (chip.tag as? String) == selectedId
            chip.isSelected = selected
            chip.typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            chip.isEnabled = !inserting
            chip.alpha = if (inserting) 0.5f else 1f
        }
    }

    private fun chip(context: Context, id: String, name: String, description: String, onPick: () -> Unit) = TextView(context).apply {
        tag = id
        text = name
        contentDescription = description
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
        setOnClickListener { if (!isSelected) onPick() }
        layoutParams = ViewGroup.LayoutParams(-2, -2)
    }

    private fun fieldBackground(context: Context): Drawable {
        val radius = dp(context, BOX_RADIUS).toFloat()
        fun face(stroke: Int, width: Int) = GradientDrawable().apply {
            cornerRadius = radius
            setColor(this@ClipTranslatePage.colors.field)
            setStroke(width, stroke)
        }
        val hair = dp(context, 1).coerceAtLeast(1)
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

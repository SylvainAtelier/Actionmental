package com.actionmental.platform

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import com.actionmental.core.clip.ClipTranslationState
import com.actionmental.core.translate.TranslationHistoryEntry
import com.actionmental.core.translate.TranslationLanguage

/** Editable draft detail. Opening, editing or changing language never makes a request. */
internal class ClipTranslatePage(
    context: Context,
    source: String,
    val languages: List<TranslationLanguage>,
    initialTarget: TranslationLanguage?,
    private val colors: Colors,
    private val tr: (String) -> String,
    button: (String, Boolean, () -> Unit) -> Button,
    onBack: () -> Unit,
    onConfirm: () -> Unit,
    onRetryOrCancel: () -> Unit,
    onHistory: () -> Unit,
) {
    data class Colors(
        val ink: Int,
        val muted: Int,
        val field: Int,
        val line: Int,
        val error: Int,
        val accent: Int = ink,
        val surface: Int = field,
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
    private var sourceExpanded = false
    private var narrow = true
    private var widthDp = 0f
    private var layoutKey: Triple<Boolean, Boolean, Boolean>? = null
    private var headerStacked: Boolean? = null
    val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(context, 4), 0, dp(context, 16))
    }
    val view = ScrollView(context).apply {
        isFillViewport = true
        isFocusableInTouchMode = true
        addView(content)
        layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
    }
    private val workspace = LinearLayout(context)
    private val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
    private val back = button(tr("剪贴记录"), false, onBack).apply { styleButton(this) }
    private val pageTitle = heading(context, tr("翻译"), 22f)
    val draft = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    var onSourceChanged: ((String) -> Unit)? = null
    var onSourceFocus: ((Boolean) -> Unit)? = null
    val sourceField = EditText(context).apply {
        setText(source)
        setTextSize(16f)
        setTextColor(colors.ink)
        setHintTextColor(colors.muted)
        hint = tr("输入或粘贴要翻译的原文")
        contentDescription = tr("原文")
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or EditorInfo.IME_ACTION_NONE
        gravity = Gravity.TOP or Gravity.START
        minLines = 3
        minimumHeight = dp(context, 96)
        setLineSpacing(0f, 1.35f)
        setPadding(dp(context, 8), dp(context, 8), dp(context, 8), dp(context, 8))
        backgroundTintList = ColorStateList.valueOf(colors.accent)
        setOnFocusChangeListener { _, focused ->
            fitSource()
            onSourceFocus?.invoke(focused)
        }
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val value = s?.toString().orEmpty()
                if (value != state.source) onSourceChanged?.invoke(value)
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
    }
    private val resultText = text(context, "", 18f)
    private val sourceToggle = button(tr("展开原文"), false) {
        sourceExpanded = !sourceExpanded
        fitSource()
    }.apply { styleButton(this) }
    private val newBlank = button(tr("新建空白翻译"), false) {
        sourceField.setText("")
        sourceField.requestFocus()
    }.apply { styleButton(this) }
    private val feedback = text(context, "", 13f).apply {
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        setPadding(dp(12), dp(12), dp(12), dp(12))
    }
    private val panes = LinearLayout(context)
    private val languageButton = button("", false) {}.apply {
        styleButton(this)
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
    }
    val confirm = button(tr("翻译"), true, onConfirm).apply { styleButton(this, primary = true) }
    private val retryOrCancel = button(tr("重新翻译"), false, onRetryOrCancel).apply { styleButton(this) }
    private val actions = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val historyButton = button(tr("本地翻译历史 · 展开"), false) {
        historyExpanded = !historyExpanded
        fitWorkspace()
        onHistory()
    }.apply { styleButton(this) }
    // ClipPanel replaces only this list; the section heading lives outside it.
    val history = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
    private val historySection = section(context, tr("翻译历史")).apply {
        visibility = View.GONE
        addView(text(context, tr("仅保存在本机"), 13f))
        addView(history, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
    }
    private val resultPane: LinearLayout
    var languagePicker: ((View) -> Unit)? = null

    init {
        header.addView(pageTitle)
        header.addView(back)
        val choices = section(context, tr("语言配置")).apply {
            addView(text(context, tr("目标语言 · 仅确认后联网"), 13f))
            addView(languageButton, LinearLayout.LayoutParams(-1, -2))
        }
        languageButton.setOnClickListener { languagePicker?.invoke(languageButton) }
        val sourcePane = pane(context, tr("原文"), sourceField).apply {
            addView(sourceToggle, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            addView(newBlank, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
        resultPane = pane(context, tr("译文"), resultText, emphasized = true)
        panes.addView(sourcePane)
        panes.addView(resultPane)
        val actionSection = section(context, tr("操作")).apply {
            addView(feedback, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
            actions.addView(confirm)
            actions.addView(retryOrCancel)
            addView(actions, LinearLayout.LayoutParams(-1, -2))
        }
        draft.addView(choices)
        draft.addView(panes, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        draft.addView(actionSection, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        workspace.addView(draft)
        workspace.addView(historySection)
        content.addView(header, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
        content.addView(historyButton, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
        content.addView(workspace, LinearLayout.LayoutParams(-1, -2))
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
        val stackHeader = scaledWidth < 320f
        if (headerStacked != stackHeader) {
            headerStacked = stackHeader
            header.orientation = if (stackHeader) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            pageTitle.layoutParams = LinearLayout.LayoutParams(if (stackHeader) -1 else 0, -2, if (stackHeader) 0f else 1f)
            back.layoutParams = LinearLayout.LayoutParams(if (stackHeader) -1 else 0, -2, if (stackHeader) 0f else 1f)
                .apply { if (stackHeader) topMargin = dp(8) else marginStart = dp(16) }
        }
        val available = if (!narrow && historyExpanded) scaledWidth * 2f / 3f else scaledWidth
        val stacked = available < 560f
        val nextKey = Triple(narrow, historyExpanded, stacked)
        // fit() is called during measurement: never reassign unchanged layout params.
        if (layoutKey != nextKey) {
            layoutKey = nextKey
            workspace.orientation = if (narrow) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            draft.visibility = if (narrow && historyExpanded) View.GONE else View.VISIBLE
            historySection.visibility = if (historyExpanded) View.VISIBLE else View.GONE
            history.visibility = if (historyExpanded) View.VISIBLE else View.GONE
            draft.layoutParams = LinearLayout.LayoutParams(if (narrow || !historyExpanded) -1 else 0, -2,
                if (!narrow && historyExpanded) 2f else 0f)
            historySection.layoutParams = LinearLayout.LayoutParams(if (narrow) -1 else 0, -2, if (narrow) 0f else 1f)
                .apply { if (!narrow) marginStart = dp(16) }
            historyButton.text = tr(when {
                !historyExpanded -> "本地翻译历史 · 展开"
                narrow -> "返回翻译详情"
                else -> "本地翻译历史 · 收起"
            })
            panes.orientation = if (stacked) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            actions.orientation = if (stacked) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            for (i in 0 until panes.childCount) {
                panes.getChildAt(i).layoutParams = LinearLayout.LayoutParams(if (stacked) -1 else 0, -2, if (stacked) 0f else 1f)
                    .apply { if (i > 0) { if (stacked) topMargin = dp(12) else marginStart = dp(12) } }
            }
            for (i in 0 until actions.childCount) {
                actions.getChildAt(i).layoutParams = LinearLayout.LayoutParams(if (stacked) -1 else 0, -2,
                    if (stacked) 0f else if (i == 0) 2f else 1f)
                    .apply { if (i > 0) { if (stacked) topMargin = dp(8) else marginStart = dp(8) } }
            }
        }
        fitSource()
    }

    private fun fitSource() {
        val maxLines = if (narrow && !sourceExpanded && !sourceField.hasFocus()) 3 else Int.MAX_VALUE
        if (sourceField.maxLines != maxLines) sourceField.maxLines = maxLines
        sourceToggle.visibility = if (narrow) View.VISIBLE else View.GONE
        val label = tr(if (sourceExpanded) "收起原文" else "展开原文")
        if (sourceToggle.text.toString() != label) sourceToggle.text = label
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
        sourceExpanded = false
        if (narrow) historyExpanded = false
        fitWorkspace()
        refresh()
    }

    fun refresh() {
        if (sourceField.text.toString() != source) sourceField.setText(source)
        sourceField.isEnabled = !inserting
        newBlank.isEnabled = !inserting
        newBlank.alpha = if (newBlank.isEnabled) 1f else 0.5f
        fitSource()
        languageButton.text = target?.name ?: tr("请先配置目标语言")
        languageButton.isEnabled = !inserting && languages.isNotEmpty()
        languageButton.alpha = if (languageButton.isEnabled) 1f else 0.5f
        resultText.text = result ?: tr(if (loading) "正在翻译…" else "确认目标语言后，按 Ctrl+Enter 或点击翻译")
        resultText.setTextColor(if (result != null) colors.ink else colors.muted)
        resultPane.background = card(
            ColorUtils.blendARGB(colors.surface, colors.accent, if (result != null) 0.10f else 0.04f),
            ColorUtils.blendARGB(colors.line, colors.accent, if (result != null) 0.65f else 0.35f),
        )
        val detail = error ?: tr(when {
            inserting -> "正在输入…"
            loading -> "正在请求 · 切换语言将取消，不会自动重发"
            result != null -> "Ctrl+Enter 输入译文 · Enter 换行 · 不会写入剪贴板"
            source.isBlank() -> "请先输入原文，再确认翻译"
            else -> "仅在确认后发送原文；翻译和上屏不会写入剪贴板"
        })
        feedback.text = if (error != null) "${tr("提示")}\n$detail" else detail
        val statusColor = when { error != null -> colors.error; loading || inserting -> colors.accent; else -> colors.muted }
        feedback.setTextColor(statusColor)
        feedback.background = card(
            ColorUtils.blendARGB(colors.surface, statusColor, 0.06f),
            ColorUtils.blendARGB(colors.line, statusColor, 0.30f),
        )
        confirm.text = tr(when { inserting -> "正在输入…"; loading -> "正在翻译…"; result != null -> "输入译文"; else -> "翻译" })
        confirm.isEnabled = !loading && !inserting && target != null && source.isNotBlank()
        confirm.alpha = if (confirm.isEnabled) 1f else 0.38f
        retryOrCancel.text = tr(if (loading) "取消请求" else "重新翻译")
        retryOrCancel.isEnabled = !inserting && target != null && source.isNotBlank()
        retryOrCancel.alpha = if (retryOrCancel.isEnabled) 1f else 0.5f
        historyButton.isEnabled = !inserting
        historyButton.alpha = if (historyButton.isEnabled) 1f else 0.5f
        back.isEnabled = !inserting
        back.alpha = if (back.isEnabled) 1f else 0.5f
    }

    private fun pane(context: Context, label: String, body: TextView, emphasized: Boolean = false) =
        section(context, label, emphasized).apply {
            addView(body, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        }

    private fun section(context: Context, label: String, emphasized: Boolean = false) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(12), dp(16), dp(16))
        background = card(if (emphasized) colors.surface else colors.field, if (emphasized) colors.accent else colors.line)
        addView(heading(context, label, 14f).apply { if (emphasized) setTextColor(colors.accent) })
        layoutParams = LinearLayout.LayoutParams(-1, -2)
    }

    private fun heading(context: Context, label: String, size: Float) = text(context, label, size).apply {
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(colors.ink)
        ViewCompat.setAccessibilityHeading(this, true)
    }

    private fun styleButton(target: Button, primary: Boolean = false) = target.apply {
        minHeight = dp(48)
        minimumHeight = dp(48)
        minWidth = dp(48)
        minimumWidth = dp(48)
        isAllCaps = false
        setSingleLine(false)
        maxLines = Int.MAX_VALUE
        ellipsize = null
        setHorizontallyScrolling(false)
        setPadding(dp(16), dp(12), dp(16), dp(12))
        if (!primary) {
            backgroundTintList = null
            setTextColor(colors.ink)
            background = RippleDrawable(
                ColorStateList.valueOf(ColorUtils.setAlphaComponent(colors.accent, 32)),
                card(colors.surface, colors.line), null,
            )
        }
    }

    private fun card(fill: Int, stroke: Int) = GradientDrawable().apply {
        cornerRadius = dp(12).toFloat()
        setColor(fill)
        setStroke(dp(1).coerceAtLeast(1), stroke)
    }

    private fun text(context: Context, value: String, size: Float) = TextView(context).apply {
        text = value
        setTextSize(size)
        setTextColor(if (size < 15) colors.muted else colors.ink)
        setLineSpacing(0f, 1.35f)
        setPadding(0, dp(4), 0, dp(4))
    }
    private fun dp(value: Int) = dp(view.context, value)
    private fun dp(context: Context, value: Int) = (context.resources.displayMetrics.density * value).toInt()
}

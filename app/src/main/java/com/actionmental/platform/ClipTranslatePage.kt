package com.actionmental.platform

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import com.actionmental.core.clip.ClipTranslationState
import com.actionmental.core.translate.TranslationHistoryEntry
import com.actionmental.core.translate.TranslationLanguage

/** Read-only translation detail. Opening or changing language never makes a request. */
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
    data class Colors(val ink: Int, val muted: Int, val field: Int, val line: Int, val error: Int)
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
    val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    val view = ScrollView(context).apply {
        isFillViewport = true
        addView(content)
        layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
    }
    private val workspace = LinearLayout(context)
    val draft = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val sourceText = text(context, source, 16f)
    private val resultText = text(context, "", 16f)
    private val sourceToggle = button(tr("展开原文"), false) {
        sourceExpanded = !sourceExpanded
        fitSource()
    }
    private val feedback = text(context, "", 12f).apply {
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private val panes = LinearLayout(context)
    private val languageButton = button("", false) {}
    val confirm = button(tr("翻译"), true, onConfirm)
    private val retryOrCancel = button(tr("重新翻译"), false, onRetryOrCancel)
    private val historyButton = button(tr("本地翻译历史 · 展开"), false) {
        historyExpanded = !historyExpanded
        fitWorkspace()
        onHistory()
    }
    val history = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
    var languagePicker: ((View) -> Unit)? = null

    init {
        val header = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(button(tr("返回列表"), false, onBack))
            addView(text(context, tr("翻译记录"), 15f).apply {
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            })
        }
        val choices = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(context, tr("目标语言 · 仅确认后联网"), 12f))
            addView(languageButton)
        }
        languageButton.setOnClickListener { languagePicker?.invoke(languageButton) }
        val sourcePane = pane(context, tr("原文"), sourceText).apply { addView(sourceToggle) }
        panes.addView(sourcePane)
        panes.addView(pane(context, tr("译文"), resultText))
        draft.addView(choices)
        draft.addView(panes)
        draft.addView(feedback)
        draft.addView(confirm, LinearLayout.LayoutParams(-1, -2))
        draft.addView(retryOrCancel, LinearLayout.LayoutParams(-1, -2))
        workspace.addView(draft)
        workspace.addView(history)
        content.addView(header)
        content.addView(historyButton, LinearLayout.LayoutParams(-1, -2))
        content.addView(workspace)
        ViewCompat.setAccessibilityPaneTitle(view, tr("翻译记录"))
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
        workspace.orientation = if (narrow) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        draft.visibility = if (narrow && historyExpanded) View.GONE else View.VISIBLE
        history.visibility = if (historyExpanded) View.VISIBLE else View.GONE
        draft.layoutParams = LinearLayout.LayoutParams(if (narrow || !historyExpanded) -1 else 0, -2,
            if (!narrow && historyExpanded) 2f else 0f)
        history.layoutParams = LinearLayout.LayoutParams(if (narrow) -1 else 0, -2, if (narrow) 0f else 1f)
            .apply { if (!narrow) marginStart = dp(16) }
        historyButton.text = tr(when {
            !historyExpanded -> "本地翻译历史 · 展开"
            narrow -> "返回翻译详情"
            else -> "本地翻译历史 · 收起"
        })
        val available = if (!narrow && historyExpanded) widthDp * 2f / 3f else widthDp
        val stacked = available / view.resources.configuration.fontScale < 560f
        panes.orientation = if (stacked) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        for (i in 0 until panes.childCount) {
            panes.getChildAt(i).layoutParams = LinearLayout.LayoutParams(if (stacked) -1 else 0, -2, if (stacked) 0f else 1f)
                .apply { if (i > 0) { if (stacked) topMargin = dp(8) else marginStart = dp(12) } }
        }
        fitSource()
    }

    private fun fitSource() {
        sourceText.maxLines = if (narrow && !sourceExpanded) 3 else Int.MAX_VALUE
        sourceText.ellipsize = if (narrow && !sourceExpanded) TextUtils.TruncateAt.END else null
        sourceToggle.visibility = if (narrow) View.VISIBLE else View.GONE
        sourceToggle.text = tr(if (sourceExpanded) "收起原文" else "展开原文")
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
        sourceText.text = source
        fitSource()
        languageButton.text = target?.name ?: tr("请先配置目标语言")
        languageButton.isEnabled = !inserting && languages.isNotEmpty()
        resultText.text = result ?: tr(if (loading) "正在翻译…" else "确认目标语言后，按 Enter 或点击翻译")
        feedback.text = error ?: tr(when {
            inserting -> "正在输入…"
            loading -> "正在请求 · 切换语言将取消，不会自动重发"
            result != null -> "Enter 直接输入 · 不会写入剪贴板"
            else -> "仅发送原文进行翻译，不读取或修改系统剪贴板"
        })
        feedback.setTextColor(if (error != null) colors.error else colors.muted)
        confirm.text = tr(when { inserting -> "正在输入…"; loading -> "正在翻译…"; result != null -> "输入译文"; else -> "翻译" })
        confirm.isEnabled = !loading && !inserting && target != null
        confirm.alpha = if (confirm.isEnabled) 1f else 0.38f
        retryOrCancel.text = tr(if (loading) "取消请求" else "重新翻译")
        retryOrCancel.isEnabled = !inserting && target != null
        historyButton.isEnabled = !inserting
    }

    private fun pane(context: Context, label: String, body: TextView) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(12), dp(12), dp(12))
        background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(this@ClipTranslatePage.colors.field)
            setStroke(dp(1), this@ClipTranslatePage.colors.line)
        }
        addView(text(context, label, 12f))
        addView(body)
        layoutParams = LinearLayout.LayoutParams(-1, -2)
    }

    private fun text(context: Context, value: String, size: Float) = TextView(context).apply {
        text = value
        setTextSize(size)
        setTextColor(if (size < 15) colors.muted else colors.ink)
        setLineSpacing(0f, 1.25f)
        setPadding(0, dp(8), 0, dp(8))
    }
    private fun dp(value: Int) = (view.resources.displayMetrics.density * value).toInt()
}

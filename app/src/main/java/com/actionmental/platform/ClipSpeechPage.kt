package com.actionmental.platform

import android.Manifest
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.core.content.edit
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** A shortcut a spoken request matched; it only runs after the user confirms it. */
data class SpeechCommand(val shortcutId: String, val name: String, val combo: String)

/** Choices that outlive one session; tiny, so read on demand from one preferences file. */
class SpeechPreferences(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("speech", Context.MODE_PRIVATE)

    /** Deliver a transcript without a review step. */
    var autoDeliver: Boolean
        get() = prefs.getBoolean("auto_deliver", true)
        set(value) = prefs.edit { putBoolean("auto_deliver", value) }

    /** Tidy transcripts with the text model; off by default, since it is a second request. */
    var tidy: Boolean
        get() = prefs.getBoolean("tidy", false)
        set(value) = prefs.edit { putBoolean("tidy", value) }

    /** Dictation or command, so the page opens the way it was last used. */
    var commandMode: Boolean
        get() = prefs.getBoolean("command_mode", false)
        set(value) = prefs.edit { putBoolean("command_mode", value) }
}

/** What the speech page needs from the rest of the app; all of it outlives any one panel. */
class SpeechServices(
    val recognizer: MiniMaxSpeechRecognizer,
    val preferences: SpeechPreferences,
    val tidy: suspend (String) -> String?,
    val matchCommand: suspend (String) -> SpeechCommand?,
    val hasCommands: () -> Boolean,
    val scope: CoroutineScope,
)

/**
 * The speech-to-text page inside the clipboard panel: record, transcribe, then insert into the field
 * focused before the panel opened, fill the translation source, or run a confirmed shortcut.
 *
 * Nothing leaves the device until the user finishes a take, and leaving the page discards the audio.
 * Every state is told by words and shape as well as colour: a square while recording, a turning arc
 * while working, a check when done, "!" on errors.
 */
internal class ClipSpeechPage(
    context: Context,
    private val colors: ClipTranslatePage.Colors,
    private val tr: (String) -> String,
    button: (String, ClipTranslatePage.ButtonStyle, () -> Unit) -> Button,
    private val services: SpeechServices,
    private val host: Host,
    /** Where a finished transcript goes when the page was opened for the translation source. */
    val route: Route,
    initialDraft: String,
) {
    enum class Route { INSERT, TRANSLATE }

    interface Host {
        /** A captured input field exists and the system can write into it. */
        val canInsert: Boolean
        fun back()
        fun insert(text: String)
        fun toTranslation(text: String)
        fun copy(text: String)
        fun runCommand(command: SpeechCommand)
        fun openSettings()
        fun openAppSettings()
        fun requestMicPermission(done: (Boolean) -> Unit)
        /** The draft field took (non-null) or released input focus. */
        fun onDraftFocus(field: EditText?)
        fun onDraftChanged(text: String)
    }

    private enum class Phase { IDLE, RECORDING, SAVING, READY, WORKING, DONE, CONFIRM }

    private val appContext = context.applicationContext
    /** Same as [colors]; named apart so GradientDrawable's own colors property cannot shadow it. */
    private val palette get() = colors
    private val prefs = services.preferences
    private var recorder: WavSpeechRecorder? = null
    private var recording = false
    private var finalizing = false
    private var busy = false
    private var seconds = 0
    private var waited = 0
    private var notice = ""
    private var failed = false
    private var audioFile: File? = null
    /** null until the credential check has run off the main thread. */
    private var hasToken: Boolean? = null
    private var autoDeliver = prefs.autoDeliver
    private var tidy = prefs.tidy
    private var commandMode = prefs.commandMode
    private var hasCommands = true
    private var command: SpeechCommand? = null
    /** What was heard for the last command attempt, shown so a wrong match is obvious. */
    private var heard = ""
    /** Names the request in flight: transcription, tidying or command matching. */
    private var work = ""
    private var uploadWhenReady = false
    private var discardWhenReady = false
    private var permissionBlocked = false
    private var request: Job? = null
    private var generation = 0L
    private var startedAt = 0L
    private var workStartedAt = 0L
    private var autoStartPending = false
    private var disposed = false
    var draft: String = initialDraft
        private set
    /** Set by the panel while it has stepped aside to write into the original field. */
    var inserting = false
        set(value) { field = value; refresh() }

    private val commanding get() = commandMode && route == Route.INSERT
    private val insertable get() = route == Route.INSERT && host.canInsert
    val editing: Boolean get() = draftField.hasFocus()

    // --- Views -------------------------------------------------------------------

    private fun dp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, appContext.resources.displayMetrics)
    private fun dp(value: Int) = dp(value.toFloat()).toInt()

    val view = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        isFocusableInTouchMode = true
        descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
        layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
    }

    private val back = Button(context).apply {
        text = "←"
        setTextColor(colors.ink)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        isAllCaps = false
        stateListAnimator = null
        includeFontPadding = false
        setPadding(0, 0, 0, 0)
        minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
        background = android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(colors.ripple), null,
            GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(android.graphics.Color.WHITE) })
        contentDescription = tr("返回列表")
        layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = -dp(12) }
        setOnClickListener { escape() }
    }
    private val title = TextView(context).apply {
        setTextColor(colors.ink)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        typeface = Typeface.DEFAULT_BOLD
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
        ViewCompat.setAccessibilityHeading(this, true)
    }
    /** States where the words will land before anything is said. */
    private val subtitle = TextView(context).apply {
        setTextColor(colors.mid)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
    }
    private val header = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(48)
        addView(back)
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(title)
            addView(subtitle)
        }, LinearLayout.LayoutParams(0, -2, 1f))
    }

    // Steps: numbered badges; done ones carry a check, so progress never rests on colour alone.
    private val stepBadges = List(3) { TextView(context).apply {
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        typeface = Typeface.DEFAULT_BOLD
        includeFontPadding = false
    } }
    private val stepLabels = List(3) { TextView(context).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f) } }
    private val stepLinks = List(2) { View(context) }
    private val steps = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        for (i in 0..2) {
            addView(stepBadges[i], LinearLayout.LayoutParams(dp(24), dp(24)))
            addView(stepLabels[i], LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) })
            if (i < 2) addView(stepLinks[i], LinearLayout.LayoutParams(dp(18), dp(2)).apply { marginStart = dp(8); marginEnd = dp(8) })
        }
        for (child in 0 until childCount) getChildAt(child).importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private val orb = OrbView(context)
    private val wave = WaveView(context)
    private val headline = TextView(context).apply {
        setTextColor(colors.ink)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
    }
    private val clock = TextView(context).apply {
        setTextColor(colors.ink)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        typeface = Typeface.MONOSPACE
        gravity = Gravity.CENTER
    }
    private val timeBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = WavSpeechRecorder.MAX_SECONDS
        isIndeterminate = false
        progressTintList = ColorStateList.valueOf(colors.accent)
        progressBackgroundTintList = ColorStateList.valueOf(colors.line)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val hint = TextView(context).apply {
        setTextColor(colors.mid)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        gravity = Gravity.CENTER
        setLineSpacing(0f, 1.25f)
    }
    private val countdown = TextView(context).apply {
        setTextColor(colors.error)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
    }
    private val status = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        addView(headline, LinearLayout.LayoutParams(-1, -2))
        addView(clock, LinearLayout.LayoutParams(-1, -2))
        addView(timeBar, LinearLayout.LayoutParams(dp(200), dp(6)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(6); bottomMargin = dp(6) })
        addView(countdown, LinearLayout.LayoutParams(-1, -2))
        addView(hint, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
    }
    private val guide = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        addView(steps, LinearLayout.LayoutParams(-2, dp(32)))
        addView(orb, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(8) })
        addView(wave, LinearLayout.LayoutParams(dp(240), dp(32)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(4) })
        addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
    }

    // Mode: dictation writes text, a command runs a shortcut. Chosen before speaking.
    private lateinit var dictateChip: TextView
    private lateinit var commandChip: TextView
    private val modeRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        dictateChip = chip(context) { chooseCommandMode(false) }
        commandChip = chip(context) { chooseCommandMode(true) }
        addView(dictateChip, LinearLayout.LayoutParams(0, dp(48), 1f))
        addView(commandChip, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(8) })
    }

    private val noticeBadge = TextView(context).apply {
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        typeface = Typeface.DEFAULT_BOLD
        includeFontPadding = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val noticeText = TextView(context).apply {
        setTextColor(colors.ink)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setLineSpacing(0f, 1.25f)
    }
    private val noticeBox = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(10), dp(12), dp(10))
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        addView(noticeBadge, LinearLayout.LayoutParams(dp(24), dp(24)))
        addView(noticeText, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
    }
    private val settingsButton = button(tr("前往语音设置"), ClipTranslatePage.ButtonStyle.OUTLINE) { host.openSettings() }
    private val permissionButton = button(tr("打开应用权限设置"), ClipTranslatePage.ButtonStyle.OUTLINE) { host.openAppSettings() }

    private val heardText = TextView(context).apply {
        setTextColor(colors.mid)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
    }
    private val commandName = TextView(context).apply {
        setTextColor(colors.ink)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        typeface = Typeface.DEFAULT_BOLD
    }
    private val commandBox = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = box(colors.field, colors.accent, 2)
        addView(heardText)
        addView(commandName, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
    }

    private val autoRow = toggleRow(context) { saveAutoDeliver(it) }
    private val tidyRow = toggleRow(context) { saveTidy(it) }

    private val draftLabel = TextView(context).apply {
        setTextColor(colors.mid)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        typeface = Typeface.DEFAULT_BOLD
    }

    @SuppressLint("ClickableViewAccessibility")
    val draftField = EditText(context).apply {
        id = View.generateViewId()
        setText(initialDraft)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setTextColor(colors.ink)
        setHintTextColor(colors.muted)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or EditorInfo.IME_ACTION_NONE
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        isSaveEnabled = false
        gravity = Gravity.TOP or Gravity.START
        minLines = 3
        maxLines = 8
        setLineSpacing(0f, 1.3f)
        setHorizontallyScrolling(false)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), box(colors.field, colors.accent, 2))
            addState(intArrayOf(), box(colors.field, colors.line, 1))
        }
        setOnFocusChangeListener { field, focused -> host.onDraftFocus(if (focused) field as EditText else null) }
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val value = s?.toString().orEmpty()
                if (value != draft) {
                    draft = value
                    host.onDraftChanged(value)
                    refresh()
                }
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
    }
    private val draftNote = TextView(context).apply {
        setTextColor(colors.mid)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
    }
    private val privacy = TextView(context).apply {
        setTextColor(colors.muted)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setLineSpacing(0f, 1.3f)
        text = tr("仅在点击「完成」或「转写」后上传录音至 MiniMax 中国站，可能计费；离开此页立即停止并删除临时录音，完全静音的录音不会上传。")
    }

    private val details = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        fun gap() = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }
        addView(modeRow, LinearLayout.LayoutParams(-1, -2))
        addView(noticeBox, gap())
        addView(settingsButton, gap())
        addView(permissionButton, gap())
        addView(commandBox, gap())
        addView(autoRow.row, gap())
        addView(tidyRow.row, LinearLayout.LayoutParams(-1, -2))
        addView(draftLabel, gap())
        addView(draftField, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        addView(draftNote, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        addView(privacy, gap())
    }

    private val body = LinearLayout(context)
    private val scroll = ScrollView(context).apply {
        isFillViewport = true
        isVerticalScrollBarEnabled = false
        addView(body)
    }

    // Actions sit pinned in the thumb zone; the primary one names the next step and where the words go.
    private val startButton = button(tr("开始录音"), ClipTranslatePage.ButtonStyle.PRIMARY) { begin() }
    private val cancelButton = button(tr("取消"), ClipTranslatePage.ButtonStyle.OUTLINE) { cancelRecording() }
    private val finishButton = button("", ClipTranslatePage.ButtonStyle.PRIMARY) { stopRecording(true) }
    private val savingButton = button(tr("正在保存录音…"), ClipTranslatePage.ButtonStyle.OUTLINE) {}
    private val rerecordButton = button(tr("重新录音"), ClipTranslatePage.ButtonStyle.OUTLINE) { rerecord() }
    private val transcribeButton = button(tr("转写"), ClipTranslatePage.ButtonStyle.PRIMARY) { transcribe() }
    private val cancelWorkButton = button(tr("取消请求"), ClipTranslatePage.ButtonStyle.OUTLINE) { cancelRequest() }
    private val copyButton = button(tr("复制"), ClipTranslatePage.ButtonStyle.OUTLINE) { if (draft.isNotBlank()) host.copy(draft) }
    private val translateQuiet = button(tr("翻译"), ClipTranslatePage.ButtonStyle.OUTLINE) { deliver(Route.TRANSLATE) }
    private val translateLead = button(tr("填入翻译"), ClipTranslatePage.ButtonStyle.PRIMARY) { deliver(Route.TRANSLATE) }
    private val insertButton = button(tr("上屏"), ClipTranslatePage.ButtonStyle.PRIMARY) { deliver(Route.INSERT) }
    private val retellButton = button(tr("重说"), ClipTranslatePage.ButtonStyle.OUTLINE) { command = null; heard = ""; begin() }
    private val dropCommandButton = button(tr("取消"), ClipTranslatePage.ButtonStyle.OUTLINE) { command = null; heard = ""; report(""); refresh() }
    private val runButton = button(tr("执行"), ClipTranslatePage.ButtonStyle.PRIMARY) { runCommand() }
    private val actions = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private var shownActions: List<Pair<Button, Float>> = emptyList()
    private val bottomBar = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(View(context).apply { setBackgroundColor(colors.line) },
            LinearLayout.LayoutParams(-1, dp(1).coerceAtLeast(1)).apply { bottomMargin = dp(12) })
        addView(actions, LinearLayout.LayoutParams(-1, -2))
    }

    private var wide: Boolean? = null

    init {
        view.addView(header, LinearLayout.LayoutParams(-1, -2))
        view.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        view.addView(bottomBar, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        ViewCompat.setAccessibilityPaneTitle(view, tr("语音转文字"))
        fit(360f)
        refresh()
        services.scope.launch(Dispatchers.Main.immediate) {
            val ready = withContext(Dispatchers.IO) {
                // Only this feature's older temporary files are eligible for cleanup.
                val now = System.currentTimeMillis() - 1_000
                appContext.cacheDir.listFiles { file ->
                    file.isFile && file.name.startsWith("speech-") && file.name.endsWith(".wav") && file.lastModified() < now
                }?.forEach { runCatching { it.delete() } }
                hasCommands = services.hasCommands()
                runCatching { services.recognizer.hasToken() }.getOrDefault(false)
            }
            if (disposed) return@launch
            hasToken = ready
            refresh()
            maybeAutoStart()
        }
    }

    /** The panel tap or shortcut is the intent to speak: start once nothing stands in the way. */
    fun start(autoStart: Boolean) {
        autoStartPending = autoStart
        maybeAutoStart()
    }

    private fun maybeAutoStart() {
        if (!autoStartPending || hasToken != true || disposed) return
        autoStartPending = false
        // A kept dictation draft waits for its destination; command mode never uses it.
        if (if (commanding) hasCommands else draft.isBlank()) begin()
    }

    /** Side by side above 600dp of readable width; the guide and the result then share one glance. */
    fun fit(widthDp: Float) {
        val next = widthDp / appContext.resources.configuration.fontScale >= 600f
        if (next == wide) return
        wide = next
        body.removeAllViews()
        body.orientation = if (next) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        if (next) {
            body.addView(guide, LinearLayout.LayoutParams(0, -2, 1f).apply { topMargin = dp(8) })
            body.addView(details, LinearLayout.LayoutParams(0, -2, 1.2f).apply { marginStart = dp(24); topMargin = dp(8) })
        } else {
            body.addView(guide, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
            body.addView(details, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        }
        orb.core = dp(if (next) 104f else 112f)
        orb.requestLayout()
    }

    // --- Flow --------------------------------------------------------------------

    private fun phase(): Phase = when {
        recording -> Phase.RECORDING
        finalizing -> Phase.SAVING
        busy -> Phase.WORKING
        audioFile != null -> Phase.READY
        command != null -> Phase.CONFIRM
        // A dictation draft is not this mode's result: command mode starts clean.
        commanding -> Phase.IDLE
        draft.isNotBlank() -> Phase.DONE
        else -> Phase.IDLE
    }

    /** One key, one meaning: do what the primary button would do right now. */
    fun advance() {
        if (inserting) return
        when (phase()) {
            Phase.RECORDING -> stopRecording(true)
            Phase.READY -> transcribe()
            Phase.IDLE -> begin()
            Phase.CONFIRM -> runCommand()
            Phase.DONE -> when {
                route == Route.TRANSLATE -> deliver(Route.TRANSLATE)
                insertable -> deliver(Route.INSERT)
                else -> Unit
            }
            Phase.SAVING, Phase.WORKING -> Unit
        }
    }

    /** Esc / back: undo the step in progress first; only an idle page leaves. */
    fun escape() {
        when {
            inserting -> Unit
            recording -> cancelRecording()
            busy -> cancelRequest()
            command != null -> { command = null; heard = ""; report(""); refresh() }
            else -> host.back()
        }
    }

    private fun report(message: String, error: Boolean = false) {
        notice = message
        failed = error
    }

    fun showError(message: String) {
        report(message, error = true)
        refresh()
    }

    private fun haptic(confirm: Boolean) {
        view.performHapticFeedback(if (confirm && Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM
            else HapticFeedbackConstants.VIRTUAL_KEY)
    }

    private fun begin() {
        if (recording || finalizing || busy || disposed || inserting) return
        if (hasToken != true) { report(tr("尚未配置语音密钥，请前往语音设置。"), error = true); refresh(); return }
        if (commanding && !hasCommands) { refresh(); return }
        if (appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startRecording()
            return
        }
        host.requestMicPermission { granted ->
            if (disposed) return@requestMicPermission
            permissionBlocked = !granted
            if (granted) startRecording()
            else { report(tr("需要麦克风权限才能录音；可在应用权限设置中允许。"), error = true); refresh() }
        }
    }

    private fun startRecording() {
        if (disposed || recording || finalizing || busy) return
        discardWhenReady = false
        clearAudio()
        report("")
        seconds = 0
        var candidate: File? = null
        try {
            candidate = File.createTempFile("speech-", ".wav", appContext.cacheDir)
            val capture = WavSpeechRecorder(candidate) { result -> onCaptured(result) }
            recorder = capture
            capture.start()
            recording = true
            permissionBlocked = false
            haptic(false)
            startedAt = SystemClock.elapsedRealtime()
            wave.clear()
        } catch (_: Exception) {
            recorder?.close()
            recorder = null
            candidate?.delete()
            report(tr("无法启动录音，请检查麦克风权限或是否被其他应用占用。"), error = true)
        }
        refresh()
    }

    private fun onCaptured(result: Result<File>) {
        recorder = null
        recording = false
        finalizing = false
        if (disposed) { result.getOrNull()?.delete(); return }
        if (discardWhenReady) {
            result.getOrNull()?.delete()
            discardWhenReady = false
            uploadWhenReady = false
            seconds = 0
            if (!failed) report(tr("录音已取消并删除。"))
            refresh()
            return
        }
        result.fold(onSuccess = { file ->
            audioFile = file
            if (uploadWhenReady) transcribe()
            else report(tr("录音已停止，尚未上传。点「转写」后才会联网。"))
        }, onFailure = { error ->
            report(tr(if (error is SilentAudioException) "没有检测到声音，未上传。请靠近麦克风后重新录音。"
                else "录音失败或没有录到声音，请检查麦克风后重新录音。"), error = true)
        })
        uploadWhenReady = false
        refresh()
    }

    private fun stopRecording(upload: Boolean) {
        uploadWhenReady = upload
        if (!recording) return
        recording = false
        finalizing = true
        recorder?.stop()
        if (upload) haptic(false)
        refresh()
    }

    private fun cancelRecording() {
        discardWhenReady = true
        stopRecording(false)
    }

    private fun rerecord() {
        command = null
        heard = ""
        clearAudio()
        begin()
    }

    private fun transcribe() {
        val file = audioFile ?: return
        if (busy || disposed) return
        if (hasToken != true) { report(tr("尚未配置语音密钥，请前往语音设置。"), error = true); refresh(); return }
        report("")
        busy = true
        command = null
        work = tr("正在转写…")
        workStartedAt = SystemClock.elapsedRealtime()
        waited = 0
        val token = ++generation
        val asCommand = commanding
        request = services.scope.launch(Dispatchers.Main.immediate) {
            try {
                val raw = services.recognizer.transcribe(file)
                if (token != generation || disposed) return@launch
                clearAudio()
                if (asCommand) {
                    heard = raw
                    work = tr("正在匹配快捷操作…")
                    refresh()
                    val attempt = runCatching { services.matchCommand(raw) }
                        .onFailure { if (it is CancellationException) throw it }
                    if (token != generation || disposed) return@launch
                    command = attempt.getOrNull()
                    when {
                        command != null -> haptic(true)
                        // Matching uses the translation key and model; say so instead of blaming the wording.
                        attempt.isFailure -> report(tr("指令匹配失败：需要可用的翻译密钥和网络，请重新录音。"), error = true)
                        else -> report(tr("没有匹配到快捷操作，可换个说法重新录音。"), error = true)
                    }
                    return@launch
                }
                var text = raw
                if (tidy) {
                    work = tr("正在整理口述…")
                    refresh()
                    val tidied = services.tidy(raw)
                    if (token != generation || disposed) return@launch
                    if (tidied != null) text = tidied else report(tr("整理未完成，已使用原始转写。"))
                }
                setDraft(text)
                haptic(true)
                busy = false
                when {
                    route == Route.TRANSLATE -> deliver(Route.TRANSLATE)
                    autoDeliver && insertable -> deliver(Route.INSERT)
                    notice.isEmpty() -> report(tr(if (insertable) "转写完成，可编辑后上屏、翻译或复制。" else "转写完成，可编辑后复制或填入翻译。"))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (token == generation && !disposed) report(speechError(error), error = true)
            } finally {
                if (token == generation && !disposed) {
                    busy = false
                    refresh()
                }
            }
        }
        refresh()
    }

    private fun setDraft(text: String) {
        draft = text
        if (draftField.text.toString() != text) {
            draftField.setText(text)
            draftField.setSelection(draftField.length())
        }
        host.onDraftChanged(text)
    }

    private fun deliver(target: Route) {
        val text = draft
        if (text.isBlank() || inserting) return
        if (target == Route.TRANSLATE) host.toTranslation(text) else host.insert(text)
    }

    private fun runCommand() {
        val match = command ?: return
        host.runCommand(match)
    }

    private fun speechError(error: Exception): String {
        val reason = (error as? SpeechRecognitionException)?.reason
        return tr(when (reason) {
            SpeechRecognitionFailure.MISSING_TOKEN -> "尚未配置语音密钥，请前往语音设置。"
            SpeechRecognitionFailure.AUTH, SpeechRecognitionFailure.INVALID_TOKEN -> "API Key 无效或已失效，请在语音设置中更换后重试。"
            SpeechRecognitionFailure.INSUFFICIENT_BALANCE -> "语音账户余额不足，请充值后重试。"
            SpeechRecognitionFailure.INVALID_FILE -> "录音失败或没有录到声音，请检查麦克风后重新录音。"
            SpeechRecognitionFailure.RATE_LIMIT -> "请求过于频繁，请稍后重试。"
            SpeechRecognitionFailure.FILE_TOO_LARGE -> "录音超过服务限额，请缩短后重新录音。"
            SpeechRecognitionFailure.EMPTY_RESPONSE -> "没有识别到文字，可重试或重新录音。"
            SpeechRecognitionFailure.TIMEOUT -> "连接超时，录音仍保留，可重试。"
            SpeechRecognitionFailure.NETWORK -> "网络连接失败，检查网络后重试。"
            else -> "转写失败，录音仍保留，可重试或重新录音。"
        })
    }

    private fun cancelRequest() {
        generation++
        request?.cancel()
        request = null
        busy = false
        report(tr(if (audioFile != null) "请求已取消；录音保留，重试可能再次计费。" else "已取消。"))
        refresh()
    }

    private fun clearAudio() {
        audioFile?.delete()
        audioFile = null
    }

    private fun chooseCommandMode(value: Boolean) {
        // The mode only decides what happens after transcription, so it may change mid-take.
        if (value == commandMode || finalizing || busy || inserting) return
        commandMode = value
        command = null
        heard = ""
        report("")
        services.scope.launch(Dispatchers.IO) { prefs.commandMode = value }
        refresh()
    }

    private fun saveTidy(value: Boolean) {
        tidy = value
        services.scope.launch(Dispatchers.IO) { prefs.tidy = value }
        refresh()
    }

    private fun saveAutoDeliver(value: Boolean) {
        autoDeliver = value
        services.scope.launch(Dispatchers.IO) { prefs.autoDeliver = value }
        refresh()
    }

    /** Stops the microphone and any request and deletes temporary audio; the page is unusable after. */
    fun dispose() {
        if (disposed) return
        disposed = true
        generation++
        request?.cancel()
        uploadWhenReady = false
        recorder?.close()
        recorder = null
        recording = false
        clearAudio()
        view.removeCallbacks(frame)
    }

    // --- Rendering ---------------------------------------------------------------

    private val reduceMotion get() = !ValueAnimator.areAnimatorsEnabled()
    private var frameRunning = false
    private var smoothed = 0f
    private var lastPush = 0L
    private var lastSilenceCheck = 0L
    private var arc = 0f
    private val audioManager by lazy { appContext.getSystemService(AudioManager::class.java) }

    /**
     * One frame loop drives the halo, the wave, the arc and both clocks, and runs only while
     * something moves; text changes at most once a second, so a frame is a redraw, not a relayout.
     */
    private val frame: Runnable = object : Runnable {
        override fun run() {
            frameRunning = false
            if (disposed) return
            val now = SystemClock.elapsedRealtime()
            val capture = recorder
            if (recording && capture != null) {
                val target = capture.level
                // Fast attack, slow release: the halo jumps with a syllable and settles calmly.
                smoothed += (target - smoothed) * if (target > smoothed) 0.5f else 0.12f
                if (now - lastPush >= 60) { lastPush = now; wave.push(smoothed) }
                val elapsed = ((now - startedAt) / 1000).toInt().coerceAtMost(WavSpeechRecorder.MAX_SECONDS)
                if (elapsed != seconds) { seconds = elapsed; renderClock() }
                if (elapsed >= WavSpeechRecorder.MAX_SECONDS) { stopRecording(false); return }
                if (now - startedAt > 600 && now - lastSilenceCheck > 500) {
                    lastSilenceCheck = now
                    if (capture.silenced(audioManager)) {
                        report(tr("麦克风被系统暂停（隐私开关已关闭或被其他应用占用），本次录音未保存。"), error = true)
                        cancelRecording()
                        return
                    }
                }
            } else smoothed = 0f
            if (busy) {
                val elapsed = ((now - workStartedAt) / 1000).toInt()
                if (elapsed != waited) { waited = elapsed; renderClock() }
            }
            if (!reduceMotion) arc = (arc + 6f) % 360f
            orb.level = smoothed
            orb.angle = arc
            orb.invalidate()
            wave.invalidate()
            schedule()
        }
    }

    private fun schedule() {
        if (frameRunning || disposed || !(recording || busy || finalizing)) return
        frameRunning = true
        view.postOnAnimation(frame)
    }

    private fun renderClock() {
        val phase = phase()
        when (phase) {
            Phase.RECORDING -> {
                setText(clock, "%02d:%02d / 02:00".format(seconds / 60, seconds % 60))
                timeBar.progress = seconds
                val left = WavSpeechRecorder.MAX_SECONDS - seconds
                setText(countdown, if (left <= 20) tr("还剩 %d 秒，到时自动停止").format(left) else "")
            }
            Phase.WORKING -> setText(clock, tr("已等待 %d 秒").format(waited))
            else -> Unit
        }
    }

    fun refresh() {
        if (disposed) return
        val phase = phase()
        val insertTarget = insertable
        setText(title, tr(if (commanding) "语音指令" else "语音转文字"))
        setText(subtitle, tr("结果去向：") + tr(when {
            commanding -> "执行匹配的快捷操作（先确认）"
            route == Route.TRANSLATE -> "填入翻译原文"
            insertTarget -> if (autoDeliver) "直接上屏到原输入框" else "预览后上屏到原输入框"
            else -> "预览后复制或翻译"
        }))

        // Steps
        val active = when (phase) {
            Phase.IDLE, Phase.RECORDING, Phase.SAVING -> 0
            Phase.READY, Phase.WORKING -> 1
            Phase.DONE, Phase.CONFIRM -> 2
        }
        val labels = listOf(tr("录音"), tr(if (commanding) "识别" else "转写"), tr(when {
            commanding -> "确认"
            route == Route.TRANSLATE -> "翻译"
            insertTarget -> "上屏"
            else -> "编辑"
        }))
        for (i in 0..2) {
            val done = i < active
            val current = i == active
            setText(stepBadges[i], if (done) "✓" else "${i + 1}")
            stepBadges[i].setTextColor(if (done || current) onAccent else colors.muted)
            stepBadges[i].background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (done || current) palette.accent else android.graphics.Color.TRANSPARENT)
                setStroke(dp(if (current) 3 else 1).coerceAtLeast(1), if (current) palette.accentInk else palette.line)
            }
            setText(stepLabels[i], labels[i])
            stepLabels[i].setTextColor(if (current) colors.ink else colors.muted)
            stepLabels[i].typeface = if (current) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            if (i < 2) stepLinks[i].setBackgroundColor(if (done) colors.accent else colors.line)
        }
        steps.contentDescription = tr("第 %d 步，共 3 步：%s").format(active + 1, labels[active])

        // Orb
        val ready = hasToken == true && !inserting && (!commanding || hasCommands)
        orb.phase = phase
        orb.failed = failed
        orb.isEnabled = ready
        orb.tappable = ready && (phase == Phase.IDLE || phase == Phase.RECORDING || phase == Phase.READY || phase == Phase.CONFIRM)
        orb.contentDescription = tr(when (phase) {
            Phase.RECORDING -> "完成录音"
            Phase.READY -> "转写录音"
            Phase.CONFIRM -> "执行快捷操作"
            else -> "开始录音"
        })
        orb.isClickable = orb.tappable
        orb.isFocusable = orb.tappable
        orb.invalidate()
        wave.active = phase == Phase.RECORDING
        wave.invalidate()

        // Status
        val match = command
        setText(headline, when (phase) {
            Phase.IDLE -> when {
                hasToken == null -> tr("正在准备…")
                hasToken == false -> tr("先设置语音密钥")
                commanding && !hasCommands -> tr("还没有可执行的快捷操作")
                commanding -> tr("点击麦克风说出指令")
                else -> tr("点击麦克风开始说话")
            }
            Phase.RECORDING -> tr("正在聆听…")
            Phase.SAVING -> tr("正在保存录音…")
            Phase.READY -> tr("录音待转写")
            Phase.WORKING -> work
            Phase.DONE -> tr("转写完成")
            Phase.CONFIRM -> tr("确认执行？")
        })
        setText(hint, when (phase) {
            Phase.IDLE -> tr(if (commanding) "例如「打开计算器」「横屏」；匹配后先请你确认" else "最长 2 分钟；点击完成后才上传")
            Phase.RECORDING -> tr(when {
                commanding -> "说完点「完成」，匹配后会先请你确认"
                route == Route.TRANSLATE -> "说完点「完成」，文字会填入翻译原文"
                insertTarget && autoDeliver -> "说完点「完成」，文字会直接上屏"
                else -> "说完点「完成」，先预览再决定去向"
            })
            Phase.READY -> tr("点「转写」上传；不满意可重新录音")
            Phase.DONE -> tr("可编辑后选择去向")
            Phase.CONFIRM -> tr("按 Enter 或点「执行」运行，「重说」重新录音")
            else -> ""
        })
        visible(clock, phase == Phase.RECORDING || phase == Phase.WORKING)
        visible(timeBar, phase == Phase.RECORDING)
        visible(countdown, phase == Phase.RECORDING && countdown.text.isNotEmpty())
        visible(hint, hint.text.isNotEmpty())
        renderClock()

        // Details
        visible(modeRow, route == Route.INSERT)
        styleChip(dictateChip, tr("听写"), !commandMode, enabled = !busy && !finalizing && !inserting)
        styleChip(commandChip, tr("指令"), commandMode, enabled = !busy && !finalizing && !inserting)
        val message = when {
            notice.isNotBlank() -> notice
            commanding && !hasCommands -> tr("还没有可用的快捷操作。先在「快捷键」中添加，再用语音执行。")
            else -> ""
        }
        val error = failed || (notice.isBlank() && message.isNotEmpty())
        visible(noticeBox, message.isNotEmpty())
        if (message.isNotEmpty()) {
            setText(noticeText, message)
            setText(noticeBadge, if (error) "!" else "i")
            noticeBadge.setTextColor(if (error) onError else colors.surface)
            noticeBadge.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (error) palette.error else palette.mid)
            }
            noticeBox.background = box(colors.field, if (error) colors.error else colors.line, if (error) 2 else 1)
        }
        visible(settingsButton, hasToken == false)
        visible(permissionButton, permissionBlocked)
        visible(commandBox, phase == Phase.CONFIRM && match != null)
        if (match != null) {
            setText(heardText, tr("听到：") + "「" + heard + "」")
            setText(commandName, tr("将执行：") + match.name + "  ·  " + match.combo)
        }
        val dictating = !commanding
        visible(autoRow.row, dictating && insertTarget)
        autoRow.bind(tr("转写后直接上屏"), tr(if (autoDeliver) "完成即写入原输入框，失败时保留文字" else "先预览，再手动上屏、翻译或复制"),
            autoDeliver, enabled = !inserting)
        visible(tidyRow.row, dictating)
        tidyRow.bind(tr("整理口述"), tr("去掉语气词与重复，补上标点；会多发一次文本请求"), tidy, enabled = !busy && !inserting)
        val showDraft = dictating && (draft.isNotBlank() || phase == Phase.DONE)
        visible(draftLabel, showDraft)
        visible(draftField, showDraft)
        setText(draftLabel, tr("识别结果（可编辑）"))
        draftField.isEnabled = !busy && !finalizing && !recording && !inserting
        val note = when {
            !showDraft || route == Route.TRANSLATE -> ""
            Build.VERSION.SDK_INT < 33 -> tr("直接上屏需要 Android 13 或更高版本；可复制后手动粘贴，或填入翻译。")
            !host.canInsert -> tr("打开面板时没有聚焦的输入框；可复制后粘贴，或填入翻译。")
            else -> ""
        }
        setText(draftNote, note)
        visible(draftNote, note.isNotEmpty())

        renderActions(phase, ready, insertTarget)
        schedule()
    }

    private fun renderActions(phase: Phase, ready: Boolean, insertTarget: Boolean) {
        finishButton.text = tr(when {
            commanding -> "完成并识别"
            route == Route.TRANSLATE -> "完成并填入翻译"
            insertTarget && autoDeliver -> "完成并上屏"
            else -> "完成并转写"
        })
        val next: List<Pair<Button, Float>> = when (phase) {
            Phase.IDLE -> listOf(startButton to 1f)
            Phase.RECORDING -> listOf(cancelButton to 1f, finishButton to 2f)
            Phase.SAVING -> listOf(savingButton to 1f)
            Phase.READY -> listOf(rerecordButton to 1f, transcribeButton to 2f)
            Phase.WORKING -> listOf(cancelWorkButton to 1f)
            Phase.CONFIRM -> listOf(retellButton to 1f, dropCommandButton to 1f, runButton to 1.6f)
            Phase.DONE -> when {
                route == Route.TRANSLATE -> listOf(rerecordButton to 1f, copyButton to 1f, translateLead to 1.6f)
                insertTarget -> listOf(rerecordButton to 1f, copyButton to 1f, translateQuiet to 1f, insertButton to 1.4f)
                else -> listOf(rerecordButton to 1f, copyButton to 1f, translateLead to 1.6f)
            }
        }
        if (next != shownActions) {
            shownActions = next
            actions.removeAllViews()
            next.forEachIndexed { index, (button, weight) ->
                (button.parent as? ViewGroup)?.removeView(button)
                actions.addView(button, LinearLayout.LayoutParams(0, -2, weight).apply { if (index > 0) marginStart = dp(8) })
            }
        }
        startButton.isEnabled = ready
        transcribeButton.isEnabled = hasToken == true && !inserting
        savingButton.isEnabled = false
        listOf(rerecordButton, copyButton, translateQuiet, translateLead, insertButton, runButton, retellButton, dropCommandButton)
            .forEach { it.isEnabled = !inserting }
        next.forEach { (button, _) -> button.alpha = if (button.isEnabled) 1f else 0.45f }
    }

    // --- Small view helpers ------------------------------------------------------------

    private val onAccent get() = if (ColorUtils.calculateLuminance(colors.accent) > 0.179) android.graphics.Color.BLACK else android.graphics.Color.WHITE
    private val onError get() = if (ColorUtils.calculateLuminance(colors.error) > 0.179) android.graphics.Color.BLACK else android.graphics.Color.WHITE

    private fun setText(view: TextView, value: String) {
        if (view.text.toString() != value) view.text = value
    }

    private fun visible(view: View, show: Boolean) {
        val value = if (show) View.VISIBLE else View.GONE
        if (view.visibility != value) view.visibility = value
    }

    private fun box(fill: Int, stroke: Int, width: Int) = GradientDrawable().apply {
        cornerRadius = dp(12f)
        setColor(fill)
        setStroke(dp(width).coerceAtLeast(1), stroke)
    }

    private fun chip(context: Context, onClick: () -> Unit) = TextView(context).apply {
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    /** Selected chips carry a check and bold text as well as the tint, readable without colour. */
    private fun styleChip(chip: TextView, label: String, selected: Boolean, enabled: Boolean) {
        setText(chip, if (selected) "✓  $label" else label)
        chip.isSelected = selected
        chip.isEnabled = enabled
        chip.alpha = if (enabled) 1f else 0.5f
        chip.typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        chip.setTextColor(colors.ink)
        val fill = if (selected) ColorUtils.blendARGB(colors.surface, colors.accent, if (colors.dark) 0.26f else 0.16f) else colors.surface
        chip.background = android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(colors.ripple),
            box(fill, if (selected) colors.accent else colors.line, if (selected) 2 else 1), null)
        chip.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = android.widget.RadioButton::class.java.name
                info.isCheckable = true
                info.isChecked = selected
            }
        }
    }

    private inner class ToggleRow(val row: LinearLayout, val title: TextView, val desc: TextView, val toggle: Switch) {
        fun bind(label: String, detail: String, checked: Boolean, enabled: Boolean) {
            setText(title, label)
            setText(desc, detail)
            if (toggle.isChecked != checked) toggle.isChecked = checked
            row.isEnabled = enabled
            toggle.isEnabled = enabled
            row.alpha = if (enabled) 1f else 0.5f
        }
    }

    private fun toggleRow(context: Context, onChange: (Boolean) -> Unit): ToggleRow {
        val title = TextView(context).apply {
            setTextColor(colors.ink)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
        }
        val desc = TextView(context).apply {
            setTextColor(colors.mid)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        val toggle = Switch(context).apply {
            thumbTintList = ColorStateList(states, intArrayOf(colors.accent, colors.surface))
            trackTintList = ColorStateList(states, intArrayOf(ColorUtils.setAlphaComponent(colors.accent, 0x80), colors.line))
            // The row is the control; the switch only shows its state.
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56)
            isClickable = true
            isFocusable = true
            background = android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(colors.ripple), null,
                GradientDrawable().apply { cornerRadius = dp(12f); setColor(android.graphics.Color.WHITE) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(title)
                addView(desc)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(toggle, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(12) })
        }
        val holder = ToggleRow(row, title, desc, toggle)
        row.setOnClickListener { if (row.isEnabled) onChange(!toggle.isChecked) }
        row.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = Switch::class.java.name
                info.isCheckable = true
                info.isChecked = toggle.isChecked
            }
        }
        return holder
    }

    // --- Drawing ---------------------------------------------------------------------

    /**
     * The microphone itself is the main control. Recording fills it with the accent and shows a square
     * stop glyph, with a halo that follows the voice; working shows a turning arc; done shows a check.
     */
    private inner class OrbView(context: Context) : View(context) {
        var core = dp(112f)
        var level = 0f
        var angle = 0f
        var phase = Phase.IDLE
        var failed = false
        var tappable = false
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
        private val oval = RectF()
        private val mic = context.getDrawable(com.actionmental.R.drawable.ic_speech_microphone)?.mutate()

        init {
            setOnClickListener { if (tappable) { if (phase == Phase.CONFIRM) runCommand() else advance() } }
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val size = (core * 1.5f).toInt()
            setMeasuredDimension(size, size)
        }

        override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(info)
            info.className = Button::class.java.name
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val radius = core / 2f
            val room = width / 2f - radius
            paint.style = Paint.Style.FILL
            if (phase == Phase.RECORDING) {
                paint.color = ColorUtils.setAlphaComponent(colors.accent, 0x24)
                canvas.drawCircle(cx, cy, radius + room * level, paint)
                paint.color = ColorUtils.setAlphaComponent(colors.accent, 0x38)
                canvas.drawCircle(cx, cy, radius + room * level * 0.55f, paint)
            }
            if (phase == Phase.WORKING || phase == Phase.SAVING) {
                val stroke = dp(4f)
                val ring = radius + stroke * 2
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = stroke
                paint.strokeCap = Paint.Cap.ROUND
                paint.color = colors.line
                canvas.drawCircle(cx, cy, ring, paint)
                paint.color = colors.accent
                oval.set(cx - ring, cy - ring, cx + ring, cy + ring)
                // Reduced motion: a still, nearly closed arc still reads as "in progress".
                if (reduceMotion) canvas.drawArc(oval, -90f, 300f, false, paint)
                else canvas.drawArc(oval, angle - 90f, 100f, false, paint)
            }
            val filled = phase == Phase.RECORDING
            paint.style = Paint.Style.FILL
            paint.color = if (filled) colors.accent else colors.surface
            canvas.drawCircle(cx, cy, radius, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(2f)
            paint.color = if (filled) colors.accent else if (isEnabled) colors.accentInk else colors.line
            canvas.drawCircle(cx, cy, radius - dp(1f), paint)
            paint.style = Paint.Style.FILL
            when {
                filled -> {
                    val half = core * 0.13f
                    paint.color = onAccent
                    oval.set(cx - half, cy - half, cx + half, cy + half)
                    canvas.drawRoundRect(oval, dp(6f), dp(6f), paint)
                }
                phase == Phase.DONE || phase == Phase.CONFIRM -> glyph(canvas, "✓", colors.ink, cx, cy)
                failed && phase == Phase.IDLE || failed && phase == Phase.READY -> glyph(canvas, "!", colors.error, cx, cy)
                mic != null -> {
                    val half = (core * 0.2f).toInt()
                    mic.setTint(if (isEnabled) colors.ink else colors.muted)
                    mic.setBounds((cx - half).toInt(), (cy - half).toInt(), (cx + half).toInt(), (cy + half).toInt())
                    mic.draw(canvas)
                }
            }
        }

        private fun glyph(canvas: Canvas, value: String, color: Int, cx: Float, cy: Float) {
            text.color = color
            text.textSize = core * 0.36f
            canvas.drawText(value, cx, cy - (text.descent() + text.ascent()) / 2, text)
        }
    }

    /** A rolling level history; a flat line at rest reads as "not listening". */
    private inner class WaveView(context: Context) : View(context) {
        private val values = FloatArray(32)
        private var head = 0
        var active = false
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()

        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

        fun push(value: Float) {
            values[head % values.size] = value
            head++
        }

        fun clear() {
            values.fill(0f)
            head = 0
        }

        override fun onDraw(canvas: Canvas) {
            val gap = dp(3f)
            val bar = (width - gap * (values.size - 1)) / values.size
            val min = dp(3f)
            paint.color = if (active) colors.accent else colors.line
            for (i in values.indices) {
                val value = if (active) values[(head + i) % values.size] else 0f
                val h = (height * value).coerceAtLeast(min)
                val x = i * (bar + gap)
                rect.set(x, (height - h) / 2, x + bar, (height + h) / 2)
                canvas.drawRoundRect(rect, bar / 2, bar / 2, paint)
            }
        }
    }
}

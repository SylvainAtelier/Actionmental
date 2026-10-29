package com.actionmental.platform

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.net.toUri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.actionmental.AppGraph
import com.actionmental.AppGraph.SpeechRoute
import com.actionmental.R
import com.actionmental.data.UserSettings
import com.actionmental.ui.theme.ActionmentalTheme
import com.actionmental.ui.theme.AmType
import com.actionmental.ui.theme.LocalAmColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** A visible, opt-in recording session; leaving the screen never starts an upload. */
class SpeechCaptureActivity : ComponentActivity() {
    companion object {
        /** Set by the shortcut when the page is already up: advance to the next step instead of reopening. */
        const val EXTRA_TOGGLE = "com.actionmental.speech.TOGGLE"
    }

    /** CONFIRM: a voice command was matched and waits for the user's go-ahead; nothing runs on its own. */
    private enum class Phase { IDLE, RECORDING, SAVING, READY, TRANSCRIBING, DONE, CONFIRM }

    private val graph by lazy { AppGraph.get(this) }
    private val recognizer by lazy { MiniMaxSpeechRecognizer(this) }
    private var recorder: WavSpeechRecorder? = null
    private var recording by mutableStateOf(false)
    private var finalizing by mutableStateOf(false)
    private var busy by mutableStateOf(false)
    private var seconds by mutableIntStateOf(0)
    private var notice by mutableStateOf("")
    private var failed by mutableStateOf(false)
    private var audioFile by mutableStateOf<File?>(null)
    /** null until the credential check has run off the main thread. */
    private var hasToken by mutableStateOf<Boolean?>(null)
    private var autoDeliver by mutableStateOf(true)
    private var tidy by mutableStateOf(false)
    private var commandMode by mutableStateOf(false)
    private var hasCommands by mutableStateOf(true)
    private var command by mutableStateOf<AppGraph.SpeechCommand?>(null)
    /** What was heard for the last command attempt, shown so a wrong match is obvious. */
    private var heard by mutableStateOf("")
    /** Names the request in flight: transcription, tidying or command matching. */
    private var work by mutableStateOf("")
    private var uploadWhenReady = false
    private var discardWhenReady = false
    private var request: Job? = null
    private var timer: Job? = null
    private var startedAt = 0L
    private var transcribeStartedAt by mutableLongStateOf(0L)
    private var english = false
    private var closing = false
    private var requestGeneration = 0L
    private var autoStartPending = false
    private var permissionBlocked by mutableStateOf(false)
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionBlocked = !granted && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
        if (granted && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) startRecording()
        else report(tr("需要麦克风权限；请允许后重新点击开始录音。"), error = true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val sessionStarted = System.currentTimeMillis()
        // The panel tap is the intent to dictate: start as soon as nothing stands in the way.
        autoStartPending = savedInstanceState == null
        lifecycleScope.launch {
            val ready = withContext(Dispatchers.IO) {
                // Only this feature's older temporary files are eligible for cleanup.
                cacheDir.listFiles { file ->
                    file.isFile && file.name.startsWith("speech-") && file.name.endsWith(".wav") && file.lastModified() < sessionStarted
                }?.forEach { runCatching { it.delete() } }
                autoDeliver = graph.speechAutoDeliver
                tidy = graph.speechTidy
                commandMode = graph.speechCommandMode
                hasCommands = graph.hasVoiceCommands
                runCatching { recognizer.hasToken() }.getOrDefault(false)
            }
            hasToken = ready
            maybeAutoStart()
        }
        setContent {
            val settings by graph.settingsRepository.settings.collectAsStateWithLifecycle()
            val draft by graph.speechDraft.collectAsStateWithLifecycle()
            val route by graph.speechRoute.collectAsStateWithLifecycle()
            val canInsert by graph.speechCanInsert.collectAsStateWithLifecycle()
            english = settings.language == UserSettings.Language.ENGLISH
            ActionmentalTheme(settings.theme, settings.language, settings.accent) {
                LevelSampler { CaptureScreen(draft, route, canInsert && Build.VERSION.SDK_INT >= 33) }
            }
        }
    }

    private fun maybeAutoStart() {
        if (!autoStartPending || hasToken != true || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        autoStartPending = false
        if (graph.speechDraft.value.isBlank()) begin()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_TOGGLE, false)) advance()
    }

    /** One shortcut, one meaning: do the step the primary button would do right now. */
    private fun advance() {
        val draft = graph.speechDraft.value
        when (phase(draft)) {
            Phase.RECORDING -> stopRecording(true)
            Phase.READY -> transcribe()
            Phase.IDLE -> if (hasToken == true) begin()
            Phase.CONFIRM -> runCommand()
            Phase.DONE -> when {
                graph.speechRoute.value == SpeechRoute.TRANSLATE -> deliver(draft, SpeechRoute.TRANSLATE)
                graph.speechCanInsert.value && Build.VERSION.SDK_INT >= 33 -> deliver(draft, SpeechRoute.INSERT)
                else -> Unit
            }
            Phase.SAVING, Phase.TRANSCRIBING -> Unit
        }
    }

    private fun tr(text: String): String = if (english) speechEnglish[text] ?: text else text

    private fun report(message: String, error: Boolean = false) {
        notice = message
        failed = error
    }

    private fun haptic(confirm: Boolean) {
        window.decorView.performHapticFeedback(
            if (confirm && Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY)
    }

    private fun begin() {
        if (recording || finalizing || busy) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startRecording()
        } else permission.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun startRecording() {
        if (closing || recording || finalizing || busy || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        discardWhenReady = false
        clearAudio()
        report("")
        seconds = 0
        var candidate: File? = null
        try {
            candidate = File.createTempFile("speech-", ".wav", cacheDir)
            val capture = WavSpeechRecorder(candidate) { result ->
                recorder = null
                recording = false
                finalizing = false
                timer?.cancel()
                if (closing || isDestroyed) { result.getOrNull()?.delete(); return@WavSpeechRecorder }
                if (discardWhenReady) {
                    result.getOrNull()?.delete()
                    discardWhenReady = false
                    uploadWhenReady = false
                    seconds = 0
                    report(tr("录音已取消并删除。"))
                    return@WavSpeechRecorder
                }
                result.fold(onSuccess = { file ->
                    audioFile = file
                    if (uploadWhenReady && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) transcribe()
                    else report(tr("录音已停止，尚未上传。点击转写后才会联网。"))
                }, onFailure = { error ->
                    report(tr(if (error is SilentAudioException) "没有检测到声音，未上传。请靠近麦克风后重新录音。"
                        else "录音失败或没有录到声音，请检查麦克风后重新录音。"), error = true)
                })
                uploadWhenReady = false
            }
            recorder = capture
            capture.start()
            recording = true
            haptic(false)
            startedAt = SystemClock.elapsedRealtime()
            timer = lifecycleScope.launch {
                while (recording) {
                    seconds = ((SystemClock.elapsedRealtime() - startedAt) / 1000).toInt().coerceAtMost(WavSpeechRecorder.MAX_SECONDS)
                    if (seconds >= WavSpeechRecorder.MAX_SECONDS) { stopRecording(false); break }
                    delay(250)
                }
            }
        } catch (_: Exception) {
            recorder?.close()
            recorder = null
            candidate?.delete()
            report(tr("无法启动录音，请检查麦克风权限或是否被其他应用占用。"), error = true)
        }
    }

    private fun stopRecording(upload: Boolean) {
        uploadWhenReady = upload
        if (!recording) return
        recording = false
        finalizing = true
        timer?.cancel()
        recorder?.stop()
        if (upload) haptic(false)
    }

    private fun cancelRecording() {
        discardWhenReady = true
        stopRecording(false)
    }

    private fun transcribe() {
        val file = audioFile ?: return
        if (busy || closing) return
        if (hasToken != true) { report(tr("请先保存语音 API Key。"), error = true); return }
        report("")
        busy = true
        command = null
        work = tr("正在转写…")
        transcribeStartedAt = SystemClock.elapsedRealtime()
        val generation = ++requestGeneration
        val route = graph.speechRoute.value
        val commanding = commandActive(route)
        request = lifecycleScope.launch {
            try {
                val raw = recognizer.transcribe(file)
                if (generation != requestGeneration || closing) return@launch
                clearAudio()
                if (commanding) {
                    heard = raw
                    work = tr("正在匹配快捷操作…")
                    val attempt = runCatching { graph.matchVoiceCommand(raw) }
                        .onFailure { if (it is CancellationException) throw it }
                    if (generation != requestGeneration || closing) return@launch
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
                    val tidied = graph.tidyDictation(raw)
                    if (generation != requestGeneration || closing) return@launch
                    if (tidied != null) text = tidied else report(tr("整理未完成，已使用原始转写。"))
                }
                graph.updateSpeechDraft(text)
                haptic(true)
                val insertable = graph.speechCanInsert.value && Build.VERSION.SDK_INT >= 33
                when {
                    route == SpeechRoute.TRANSLATE -> deliver(text, SpeechRoute.TRANSLATE)
                    autoDeliver && insertable -> deliver(text, SpeechRoute.INSERT)
                    notice.isEmpty() -> report(tr("转写完成，可编辑后上屏、翻译或复制。"))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (generation == requestGeneration) report(speechError(error), error = true)
            } finally {
                if (generation == requestGeneration) busy = false
            }
        }
    }

    /** Commands only make sense for the app underneath, never for the translation source. */
    private fun commandActive(route: SpeechRoute) = commandMode && route == SpeechRoute.INSERT

    private fun runCommand() {
        val match = command ?: return
        leave()
        graph.runVoiceCommand(match.shortcutId)
    }

    private fun chooseCommandMode(value: Boolean) {
        // The mode only decides what happens after transcription, so it may change mid-take.
        if (value == commandMode || finalizing || busy) return
        commandMode = value
        command = null
        heard = ""
        report("")
        lifecycleScope.launch(Dispatchers.IO) { graph.speechCommandMode = value }
    }

    private fun saveTidy(value: Boolean) {
        tidy = value
        lifecycleScope.launch(Dispatchers.IO) { graph.speechTidy = value }
    }

    private fun deliver(text: String, route: SpeechRoute) {
        if (text.isBlank()) return
        leave()
        if (route == SpeechRoute.TRANSLATE) graph.sendSpeechToTranslation(text) else graph.insertSpeechText(text)
    }

    private fun speechError(error: Exception): String {
        val reason = (error as? SpeechRecognitionException)?.reason?.name
        return tr(when (reason) {
            "MISSING_TOKEN" -> "请先保存语音 API Key。"
            "AUTH", "INVALID_TOKEN" -> "API Key 无效或已失效，请更换后重试。"
            "INSUFFICIENT_BALANCE" -> "语音账户余额不足，请充值后重试。"
            "INVALID_FILE" -> "录音失败或没有录到声音，请检查麦克风后重新录音。"
            "RATE_LIMIT" -> "请求过于频繁，请稍后重试。"
            "FILE_TOO_LARGE" -> "录音超过服务限额，请缩短后重新录音。"
            "EMPTY_RESPONSE" -> "没有识别到文字，可重试或重新录音。"
            "TIMEOUT" -> "连接超时，录音仍保留，可重试。"
            "NETWORK" -> "网络连接失败，检查网络后重试。"
            else -> "转写失败，录音仍保留，可重试或重新录音。"
        })
    }

    private fun cancelRequest() {
        requestGeneration++
        request?.cancel()
        request = null
        busy = false
        report(tr("请求已取消；录音保留，重试可能再次计费。"))
    }

    private fun clearAudio() {
        audioFile?.delete()
        audioFile = null
    }

    private fun leave() {
        closing = true
        request?.cancel()
        uploadWhenReady = false
        recorder?.close()
        recorder = null
        clearAudio()
        finish()
    }

    override fun onStart() {
        super.onStart()
        graph.markSpeechCaptureVisible(this, true)
        maybeAutoStart()
    }

    override fun onStop() {
        graph.markSpeechCaptureVisible(this, false)
        uploadWhenReady = false
        if (recording) stopRecording(false)
        if (busy) cancelRequest()
        super.onStop()
    }

    override fun onDestroy() {
        graph.markSpeechCaptureVisible(this, false)
        closing = true
        timer?.cancel()
        request?.cancel()
        recorder?.close()
        clearAudio()
        super.onDestroy()
    }

    /** Hardware keyboards: Enter or Space finishes, Esc discards; the draft field is disabled meanwhile. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (recording && event.repeatCount == 0) when (keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_SPACE -> { stopRecording(true); return true }
            KeyEvent.KEYCODE_ESCAPE -> { cancelRecording(); return true }
        }
        if (command != null && !busy && event.repeatCount == 0 &&
            (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)) { runCommand(); return true }
        return super.onKeyDown(keyCode, event)
    }

    private fun phase(draft: String): Phase = when {
        recording -> Phase.RECORDING
        finalizing -> Phase.SAVING
        busy -> Phase.TRANSCRIBING
        audioFile != null -> Phase.READY
        command != null -> Phase.CONFIRM
        // A dictation draft is not this mode's result: command mode starts clean.
        commandActive(graph.speechRoute.value) -> Phase.IDLE
        draft.isNotBlank() -> Phase.DONE
        else -> Phase.IDLE
    }

    // --- UI ----------------------------------------------------------------------

    @Composable
    private fun CaptureScreen(draft: String, route: SpeechRoute, canInsert: Boolean) {
        val colors = LocalAmColors.current
        val phase = phase(draft)
        var keysOpen by remember { mutableStateOf(false) }
        LaunchedEffect(hasToken) { if (hasToken == false) keysOpen = true }
        var confirmRerecord by remember { mutableStateOf(false) }
        var confirmLeave by remember { mutableStateOf(false) }
        var savingKey by remember { mutableStateOf(false) }
        val occupied = phase == Phase.RECORDING || phase == Phase.SAVING || phase == Phase.TRANSCRIBING || savingKey
        val commanding = commandActive(route)
        val autoInsert = route == SpeechRoute.INSERT && autoDeliver && canInsert && !commanding
        fun back() {
            if (occupied || audioFile != null) confirmLeave = true else leave()
        }
        BackHandler { back() }
        val destination = when {
            commanding -> tr("执行匹配的快捷操作（先确认）")
            route == SpeechRoute.TRANSLATE -> tr("填入翻译原文")
            canInsert -> tr("上屏到原输入框")
            else -> tr("预览后复制")
        }

        Surface(color = colors.bgScreen, modifier = Modifier.fillMaxSize()) {
            BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                // Side by side on tablets, unfolded foldables and phones in landscape.
                val twoPane = maxWidth >= 600.dp || (maxWidth > maxHeight && maxHeight < 480.dp)
                val orbSize = if (maxHeight < 480.dp) 88.dp else 120.dp
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Header(tr(if (commanding) "语音指令" else "语音转文字"), destination, ::back)
                    // Intent is chosen before speaking: dictation writes text, a command runs a shortcut.
                    // Recording starts on open; the mode stays switchable until the take is sent.
                    if (route == SpeechRoute.INSERT) ModeSwitch(enabled = !savingKey &&
                        (phase == Phase.IDLE || phase == Phase.RECORDING || phase == Phase.READY || phase == Phase.DONE))
                    val guide: @Composable ColumnScope.() -> Unit = {
                        Steps(phase, route, canInsert, commanding)
                        Spacer(Modifier.height(if (twoPane) 8.dp else 16.dp))
                        Orb(phase, orbSize, enabled = hasToken == true && !savingKey && (!commanding || hasCommands)) {
                            when (phase) {
                                Phase.IDLE -> begin()
                                Phase.RECORDING -> stopRecording(true)
                                Phase.READY -> transcribe()
                                else -> Unit
                            }
                        }
                        Waveform(phase)
                        StatusLine(phase, route, autoInsert, commanding)
                    }
                    val details: @Composable ColumnScope.() -> Unit = {
                        if (notice.isNotBlank()) Notice(notice, failed)
                        if (permissionBlocked) {
                            Text(tr("权限已被拒绝：请在系统设置 → 权限 → 麦克风中允许，然后返回点击开始录音。"), color = colors.inkMid, style = AmType.body)
                            OutlinedButton(onClick = {
                                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri()))
                            }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(tr("打开应用权限设置")) }
                        }
                        if (commanding) {
                            if (!hasCommands) Notice(tr("还没有可用的快捷操作。先在「快捷键」中添加，再用语音执行。"), true)
                            else Text(tr("说出想做的事，例如「打开微信」或「切换横屏」。只会从你已设置的快捷操作中选择，执行前需要确认。"),
                                color = colors.inkMid, style = AmType.body)
                        } else {
                            TidyRow(enabled = !occupied)
                            if (route == SpeechRoute.INSERT && canInsert) AutoDeliverRow(enabled = !occupied)
                        }
                        if (!commanding && draft.isNotBlank() && phase != Phase.RECORDING) {
                            OutlinedTextField(
                                value = draft, onValueChange = graph::updateSpeechDraft,
                                label = { Text(tr("识别结果（可编辑）")) },
                                modifier = Modifier.fillMaxWidth(), minLines = 3, enabled = !occupied,
                                textStyle = MaterialTheme.typography.bodyLarge,
                            )
                            if (route == SpeechRoute.INSERT && !canInsert) Text(tr(
                                if (Build.VERSION.SDK_INT < 33) "直接上屏需要 Android 13 或更高版本；可复制后手动粘贴，或填入翻译。"
                                else "没有可用的原输入框；可复制后粘贴，或填入翻译。"), color = colors.inkMid, style = AmType.body)
                        }
                        KeySection(keysOpen, { keysOpen = !keysOpen }, occupied, savingKey) { savingKey = it }
                        Privacy()
                    }
                    val scroll = rememberScrollState()
                    if (twoPane) {
                        Row(Modifier.weight(1f).widthIn(max = 1040.dp).fillMaxWidth().padding(horizontal = 24.dp),
                            horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally, content = guide)
                            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(scroll).padding(vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp), content = details)
                        }
                    } else {
                        Column(Modifier.weight(1f).widthIn(max = 640.dp).fillMaxWidth().verticalScroll(scroll).padding(horizontal = 20.dp, vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            guide()
                            details()
                        }
                    }
                    ActionBar(phase, draft, route, canInsert, autoInsert, commanding, savingKey, maxWidth = if (twoPane) 1040.dp else 640.dp,
                        onRerecord = { confirmRerecord = true })
                }
            }
        }
        if (confirmRerecord) AlertDialog(onDismissRequest = { confirmRerecord = false },
            title = { Text(tr("重新录音？")) }, text = { Text(tr("将删除现有临时录音；文字草稿保留，新的转写成功后才替换。")) },
            confirmButton = { TextButton(onClick = { confirmRerecord = false; begin() }, modifier = Modifier.heightIn(min = 48.dp)) { Text(tr("开始录音")) } },
            dismissButton = { TextButton(onClick = { confirmRerecord = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text(tr("返回")) } })
        if (confirmLeave) AlertDialog(onDismissRequest = { confirmLeave = false },
            title = { Text(tr("关闭语音页？")) }, text = { Text(tr("录音和请求将取消，临时音频将删除，文字草稿仍保留。已发出的请求可能计费。")) },
            confirmButton = { TextButton(onClick = { confirmLeave = false; leave() }, modifier = Modifier.heightIn(min = 48.dp)) { Text(tr("关闭")) } },
            dismissButton = { TextButton(onClick = { confirmLeave = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text(tr("返回")) } })
    }

    @Composable
    private fun ModeSwitch(enabled: Boolean) {
        val options = listOf(false to tr("听写"), true to tr("指令"))
        SingleChoiceSegmentedButtonRow(Modifier.widthIn(max = 360.dp).fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
            options.forEachIndexed { index, (value, label) ->
                SegmentedButton(selected = commandMode == value, onClick = { chooseCommandMode(value) }, enabled = enabled,
                    shape = SegmentedButtonDefaults.itemShape(index, options.size), modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(label)
                }
            }
        }
    }

    @Composable
    private fun Header(title: String, destination: String, onClose: () -> Unit) {
        val colors = LocalAmColors.current
        Row(Modifier.widthIn(max = 1040.dp).fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = AmType.pageTitle, color = colors.ink, modifier = Modifier.semantics { heading() })
                // States where the words will land before anything is said.
                Text(tr("结果去向：") + destination, style = AmType.body, color = colors.inkMid)
            }
            TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp)) { Text(tr("关闭")) }
        }
    }

    /** Three numbered steps; done steps carry a check, so progress never depends on colour alone. */
    @Composable
    private fun Steps(phase: Phase, route: SpeechRoute, canInsert: Boolean, commanding: Boolean) {
        val colors = LocalAmColors.current
        val active = when (phase) {
            Phase.IDLE, Phase.RECORDING, Phase.SAVING -> 0
            Phase.READY, Phase.TRANSCRIBING -> 1
            Phase.DONE, Phase.CONFIRM -> 2
        }
        val last = when {
            commanding -> tr("确认")
            route == SpeechRoute.TRANSLATE -> tr("翻译")
            canInsert -> tr("上屏")
            else -> tr("编辑")
        }
        val labels = listOf(tr("录音"), tr(if (commanding) "识别" else "转写"), last)
        val summary = tr("第 %d 步，共 3 步：%s").format(active + 1, labels[active])
        Row(Modifier.padding(top = 4.dp).clearAndSetSemantics { contentDescription = summary },
            verticalAlignment = Alignment.CenterVertically) {
            labels.forEachIndexed { index, label ->
                val done = index < active
                val current = index == active
                val filled = done || current
                Box(Modifier.size(26.dp)
                    .then(if (current) Modifier.border(2.dp, colors.accent, CircleShape).padding(3.dp) else Modifier)
                    .clip(CircleShape)
                    .background(if (filled) colors.accent else Color.Transparent)
                    .then(if (filled) Modifier else Modifier.border(1.5.dp, colors.line, CircleShape)),
                    contentAlignment = Alignment.Center) {
                    Text(if (done) "✓" else "${index + 1}", fontSize = if (current) 11.sp else 12.sp, fontWeight = FontWeight.Bold,
                        color = if (filled) colors.onAccent else colors.inkMuted)
                }
                Text(label, Modifier.padding(start = 6.dp), style = AmType.body,
                    fontWeight = if (current) FontWeight.Bold else FontWeight.Medium,
                    color = if (current) colors.ink else colors.inkMuted)
                if (index < labels.lastIndex) Box(Modifier.padding(horizontal = 8.dp).width(20.dp).height(2.dp)
                    .background(if (done) colors.accent else colors.line))
            }
        }
    }

    /**
     * The microphone itself is the main control. While recording a halo follows the voice level;
     * while transcribing an arc turns. Level reads happen in the draw phase only, so a frame costs a redraw, not a recomposition.
     */
    @Composable
    private fun Orb(phase: Phase, core: Dp, enabled: Boolean, onClick: () -> Unit) {
        val colors = LocalAmColors.current
        val level = LocalLevel.current
        val reduceMotion = remember {
            Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }
        val turning = phase == Phase.TRANSCRIBING || phase == Phase.SAVING
        val angle = if (turning && !reduceMotion) {
            rememberInfiniteTransition(label = "orb").animateFloat(0f, 360f,
                infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart), label = "arc")
        } else null
        val tappable = enabled && (phase == Phase.IDLE || phase == Phase.RECORDING || phase == Phase.READY)
        val description = when (phase) {
            Phase.RECORDING -> tr("完成录音")
            Phase.READY -> tr("转写录音")
            else -> tr("开始录音")
        }
        val outer = core * 1.5f
        Box(Modifier.size(outer), contentAlignment = Alignment.Center) {
            Canvas(Modifier.matchParentSize()) {
                val radius = core.toPx() / 2
                if (phase == Phase.RECORDING) {
                    val l = level.floatValue
                    drawCircle(colors.accent.copy(alpha = 0.14f), radius + (size.minDimension / 2 - radius) * l)
                    drawCircle(colors.accent.copy(alpha = 0.22f), radius + (size.minDimension / 2 - radius) * l * 0.55f)
                }
                if (turning) {
                    val stroke = 4.dp.toPx()
                    val inset = radius + stroke * 2
                    val topLeft = Offset(center.x - inset, center.y - inset)
                    drawCircle(colors.line, inset, style = Stroke(stroke))
                    rotate(angle?.value ?: 0f) {
                        drawArc(colors.accent, -90f, if (angle == null) 300f else 100f, false, topLeft,
                            Size(inset * 2, inset * 2), style = Stroke(stroke, cap = StrokeCap.Round))
                    }
                }
            }
            val recordingNow = phase == Phase.RECORDING
            Box(Modifier.size(core).clip(CircleShape)
                .background(if (recordingNow) colors.accent else colors.surface)
                .border(2.dp, if (recordingNow) colors.accent else colors.accentLine, CircleShape)
                .then(if (tappable) Modifier.clickable(role = Role.Button, onClickLabel = description, onClick = onClick) else Modifier)
                .semantics { contentDescription = description },
                contentAlignment = Alignment.Center) {
                when {
                    // A square "stop" glyph: recording is told by shape, not by the accent hue.
                    recordingNow -> Box(Modifier.size(core * 0.26f).clip(RoundedCornerShape(6.dp)).background(colors.onAccent))
                    phase == Phase.DONE || phase == Phase.CONFIRM -> Text("✓", fontSize = 40.sp, fontWeight = FontWeight.Bold, color = colors.ink)
                    failed && phase != Phase.TRANSCRIBING -> Text("!", fontSize = 40.sp, fontWeight = FontWeight.Bold, color = colors.warn)
                    else -> Icon(painterResource(R.drawable.ic_speech_microphone), null,
                        Modifier.size(core * 0.36f), tint = if (enabled) colors.ink else colors.inkFaint)
                }
            }
        }
    }

    /** A rolling level history; a flat dotted line at rest reads as "not listening". */
    @Composable
    private fun Waveform(phase: Phase) {
        val colors = LocalAmColors.current
        val history = LocalHistory.current
        Canvas(Modifier.padding(top = 8.dp).widthIn(max = 280.dp).fillMaxWidth().height(36.dp).clearAndSetSemantics {}) {
            val bars = history.values
            val gap = 3.dp.toPx()
            val width = (size.width - gap * (bars.size - 1)) / bars.size
            val active = phase == Phase.RECORDING
            for (i in bars.indices) {
                // Reading head here re-draws (never recomposes) when a new sample lands.
                val value = if (active) bars[(history.head.intValue + i) % bars.size] else 0f
                val height = (size.height * value).coerceAtLeast(3.dp.toPx())
                drawRoundRect(if (active) colors.accent else colors.line,
                    Offset(i * (width + gap), (size.height - height) / 2), Size(width, height), CornerRadius(width / 2))
            }
        }
    }

    @Composable
    private fun StatusLine(phase: Phase, route: SpeechRoute, autoInsert: Boolean, commanding: Boolean) {
        val colors = LocalAmColors.current
        val match = command
        val headline = when (phase) {
            Phase.IDLE -> if (hasToken == false) tr("先设置语音密钥") else tr(if (commanding) "点击麦克风说出指令" else "点击麦克风开始说话")
            Phase.RECORDING -> tr("正在聆听…")
            Phase.SAVING -> tr("正在保存录音…")
            Phase.READY -> tr("录音待转写")
            Phase.TRANSCRIBING -> work.ifEmpty { tr("正在转写…") }
            Phase.DONE -> tr("转写完成")
            Phase.CONFIRM -> tr("将执行：") + (match?.name ?: "")
        }
        val hint = when (phase) {
            Phase.IDLE -> tr("最长 2 分钟；点击完成后才上传")
            Phase.CONFIRM -> tr("听到：「%s」").format(heard.take(80)) + (match?.combo?.let { " · $it" } ?: "")
            Phase.RECORDING -> when {
                commanding -> tr("说完点「完成」，匹配后会先请你确认")
                route == SpeechRoute.TRANSLATE -> tr("说完点「完成」，文字会填入翻译原文")
                autoInsert -> tr("说完点「完成」，文字会直接上屏")
                else -> tr("说完点「完成」，先预览再决定去向")
            }
            Phase.SAVING -> ""
            Phase.READY -> tr("点「转写」上传；不满意可重新录音")
            Phase.TRANSCRIBING -> ""
            Phase.DONE -> tr("可编辑后选择去向")
        }
        Column(Modifier.padding(top = 4.dp).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text(headline, style = AmType.cardTitle, color = colors.ink)
            when (phase) {
                Phase.RECORDING -> RecordClock()
                Phase.TRANSCRIBING -> WaitClock()
                else -> Unit
            }
            if (hint.isNotEmpty()) Text(hint, style = AmType.body, color = colors.inkMid)
        }
    }

    /** Isolated so the per-second tick recomposes only these few nodes. */
    @Composable
    private fun RecordClock() {
        val colors = LocalAmColors.current
        val left = WavSpeechRecorder.MAX_SECONDS - seconds
        Text("%02d:%02d / 02:00".format(seconds / 60, seconds % 60), fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.titleLarge, color = colors.ink)
        LinearProgressIndicator(progress = { seconds / WavSpeechRecorder.MAX_SECONDS.toFloat() },
            modifier = Modifier.padding(vertical = 6.dp).widthIn(max = 200.dp).fillMaxWidth(),
            color = colors.accent, trackColor = colors.line)
        if (left <= 20) Text(tr("还剩 %d 秒，到时自动停止").format(left), style = AmType.body, color = colors.warn,
            fontWeight = FontWeight.Bold)
    }

    @Composable
    private fun WaitClock() {
        val colors = LocalAmColors.current
        var waited by remember { mutableIntStateOf(0) }
        LaunchedEffect(transcribeStartedAt) {
            while (true) {
                waited = ((SystemClock.elapsedRealtime() - transcribeStartedAt) / 1000).toInt()
                delay(1000)
            }
        }
        Text(tr("已等待 %d 秒").format(waited), style = AmType.body, color = colors.inkMid, fontFamily = FontFamily.Monospace)
    }

    /** Errors get a "!" badge and informational notes an "i": the glyph carries meaning, colour only groups. */
    @Composable
    private fun Notice(message: String, error: Boolean) {
        val colors = LocalAmColors.current
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.surfaceSunken)
            .border(1.dp, if (error) colors.warn else colors.line, RoundedCornerShape(12.dp)).padding(12.dp)
            .semantics(mergeDescendants = true) { liveRegion = if (error) LiveRegionMode.Assertive else LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(24.dp).clip(CircleShape).background(if (error) colors.warn else colors.inkMuted),
                contentAlignment = Alignment.Center) {
                Text(if (error) "!" else "i", color = if (error) com.actionmental.ui.theme.contrastingForeground(colors.warn) else colors.surface,
                    fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
            Text(message, Modifier.padding(start = 12.dp), style = AmType.body, color = colors.ink)
        }
    }

    @Composable
    private fun TidyRow(enabled: Boolean) {
        val colors = LocalAmColors.current
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .toggleable(value = tidy, enabled = enabled, role = Role.Switch, onValueChange = ::saveTidy),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(tr("整理口述"), style = AmType.cardTitle, color = colors.ink)
                Text(tr("去掉口头禅、补标点和分段；会多一次文本请求，失败时用原始转写"), style = AmType.body, color = colors.inkMid)
            }
            Switch(checked = tidy, onCheckedChange = null, enabled = enabled)
        }
    }

    @Composable
    private fun AutoDeliverRow(enabled: Boolean) {
        val colors = LocalAmColors.current
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .toggleable(value = autoDeliver, enabled = enabled, role = Role.Switch, onValueChange = ::saveAutoDeliver),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(tr("转写后直接上屏"), style = AmType.cardTitle, color = colors.ink)
                Text(tr(if (autoDeliver) "完成即写入原输入框，失败时保留草稿" else "先预览，再手动上屏、翻译或复制"),
                    style = AmType.body, color = colors.inkMid)
            }
            Switch(checked = autoDeliver, onCheckedChange = null, enabled = enabled)
        }
    }

    private fun saveAutoDeliver(value: Boolean) {
        autoDeliver = value
        lifecycleScope.launch(Dispatchers.IO) { graph.speechAutoDeliver = value }
    }

    /** Pinned in the thumb zone; the primary action is always the next step, named after where the words go. */
    @Composable
    private fun ActionBar(
        phase: Phase, draft: String, route: SpeechRoute, canInsert: Boolean, autoInsert: Boolean, commanding: Boolean,
        savingKey: Boolean, maxWidth: Dp, onRerecord: () -> Unit,
    ) {
        val colors = LocalAmColors.current
        Surface(color = colors.surface, modifier = Modifier.fillMaxWidth()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                HorizontalDivider(color = colors.line)
                Row(Modifier.widthIn(max = maxWidth).fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    val tall = Modifier.heightIn(min = 52.dp)
                    when (phase) {
                        Phase.IDLE -> Button(onClick = ::begin, enabled = hasToken == true && !savingKey && (!commanding || hasCommands),
                            modifier = tall.weight(1f)) { Text(tr("开始录音")) }
                        Phase.RECORDING -> {
                            OutlinedButton(onClick = ::cancelRecording, modifier = tall.weight(1f)) { Text(tr("取消")) }
                            Button(onClick = { stopRecording(true) }, modifier = tall.weight(2f)) {
                                Text(tr(when {
                                    commanding -> "完成并识别"
                                    route == SpeechRoute.TRANSLATE -> "完成并填入翻译"
                                    autoInsert -> "完成并上屏"
                                    else -> "完成并转写"
                                }))
                            }
                        }
                        Phase.SAVING -> Button(onClick = {}, enabled = false, modifier = tall.weight(1f)) { Text(tr("正在保存录音…")) }
                        Phase.READY -> {
                            OutlinedButton(onClick = onRerecord, modifier = tall.weight(1f)) { Text(tr("重新录音")) }
                            Button(onClick = ::transcribe, enabled = hasToken == true && !savingKey, modifier = tall.weight(2f)) { Text(tr("转写")) }
                        }
                        Phase.TRANSCRIBING -> OutlinedButton(onClick = ::cancelRequest, modifier = tall.weight(1f)) { Text(tr("取消")) }
                        Phase.CONFIRM -> {
                            OutlinedButton(onClick = { command = null; heard = ""; begin() }, modifier = tall.weight(1f)) { Text(tr("重说")) }
                            OutlinedButton(onClick = { command = null; heard = "" }, modifier = tall.weight(1f)) { Text(tr("取消")) }
                            Button(onClick = ::runCommand, modifier = tall.weight(1.6f)) { Text(tr("执行"), maxLines = 1) }
                        }
                        Phase.DONE -> {
                            // The destination this page was opened for leads; the others stay one tap away.
                            val insertFirst = route == SpeechRoute.INSERT && canInsert
                            OutlinedButton(onClick = onRerecord, enabled = !savingKey, modifier = tall.weight(1f),
                                contentPadding = PaddingValues(horizontal = 8.dp)) { Text(tr("重录"), maxLines = 1) }
                            OutlinedButton(onClick = { graph.copySpeechText(draft) }, enabled = !savingKey, modifier = tall.weight(1f),
                                contentPadding = PaddingValues(horizontal = 8.dp)) { Text(tr("复制"), maxLines = 1) }
                            if (insertFirst) {
                                OutlinedButton(onClick = { deliver(draft, SpeechRoute.TRANSLATE) }, enabled = !savingKey,
                                    modifier = tall.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp)) { Text(tr("翻译"), maxLines = 1) }
                                Button(onClick = { deliver(draft, SpeechRoute.INSERT) }, enabled = !savingKey,
                                    modifier = tall.weight(1.4f)) { Text(tr("上屏"), maxLines = 1) }
                            } else Button(onClick = { deliver(draft, SpeechRoute.TRANSLATE) }, enabled = !savingKey,
                                modifier = tall.weight(1.6f)) { Text(tr("填入翻译"), maxLines = 1) }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun KeySection(open: Boolean, toggle: () -> Unit, occupied: Boolean, saving: Boolean, setSaving: (Boolean) -> Unit) {
        val colors = LocalAmColors.current
        var token by remember { mutableStateOf("") }
        var showToken by remember { mutableStateOf(false) }
        TextButton(onClick = toggle, enabled = !occupied || saving, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(tr(if (open) "收起语音密钥" else "语音 API Key") + if (hasToken == false) tr("（未设置）") else "")
        }
        if (!open) return
        Text(tr("专用语音密钥加密保存在本机；未设置时回退使用翻译密钥。订阅套餐密钥可能不支持语音，请使用可调用语音 API 的密钥。"),
            color = colors.inkMid, style = AmType.body)
        OutlinedTextField(value = token, onValueChange = { token = it }, singleLine = true,
            enabled = !occupied, label = { Text(tr("MiniMax 语音 API Key")) },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                TextButton(onClick = { showToken = !showToken }, enabled = !occupied) { Text(tr(if (showToken) "隐藏" else "显示")) }
            })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            fun keyAction(work: () -> Unit, done: String, failure: String) {
                setSaving(true)
                lifecycleScope.launch {
                    try {
                        hasToken = withContext(Dispatchers.IO) { work(); recognizer.hasToken() }
                        token = ""
                        report(done)
                    } catch (cancelled: CancellationException) { throw cancelled
                    } catch (_: Exception) { report(failure, error = true)
                    } finally { setSaving(false) }
                }
            }
            Button(onClick = {
                val value = token
                keyAction({ recognizer.saveToken(value) }, tr("密钥已加密保存。"), tr("密钥保存失败，请检查格式后重试。"))
            }, enabled = token.isNotBlank() && !occupied, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text(tr("保存密钥")) }
            OutlinedButton(onClick = {
                keyAction({ recognizer.clearToken() }, tr("专用语音密钥已移除；若有翻译密钥，将回退使用。"), tr("密钥操作失败，请重试。"))
            }, enabled = !occupied, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text(tr("移除专用密钥")) }
        }
        if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
    }

    @Composable
    private fun Privacy() {
        val colors = LocalAmColors.current
        var open by remember { mutableStateOf(false) }
        TextButton(onClick = { open = !open }, modifier = Modifier.heightIn(min = 48.dp)) { Text(tr("隐私与费用说明")) }
        if (open) {
            Text(tr("仅在点击「完成」或「转写」后，音频才发送至 MiniMax 中国站，可能产生 API 费用。离开页面立即停止录音，不会自动上传；关闭后删除临时录音。完全静音的录音不会上传。"),
                style = AmType.body, color = colors.inkMid)
            Text(tr("不在后台录音；转写文字草稿仅保留在本进程内存。上屏不会自动复制。"), style = AmType.body, color = colors.inkMid)
        }
    }

    /** Per-frame level sampling, alive only while recording; everything else reads it at draw time. */
    @Composable
    private fun LevelSampler(content: @Composable () -> Unit) {
        val level = remember { mutableFloatStateOf(0f) }
        val history = remember { LevelHistory() }
        LaunchedEffect(recording) {
            if (!recording) { level.floatValue = 0f; return@LaunchedEffect }
            history.values.fill(0f)
            var lastPush = 0L
            while (true) withFrameMillis { now ->
                val target = recorder?.level ?: 0f
                val current = level.floatValue
                // Fast attack, slow release: the halo jumps with a syllable and settles calmly.
                level.floatValue = current + (target - current) * if (target > current) 0.5f else 0.12f
                if (now - lastPush >= 60) {
                    lastPush = now
                    history.values[history.head.intValue % history.values.size] = level.floatValue
                    history.head.intValue++
                }
            }
        }
        CompositionLocalProvider(LocalLevel provides level, LocalHistory provides history, content = content)
    }

}

/** Rolling level samples; [head] is the next write slot, so reading from it yields oldest first. */
private class LevelHistory {
    val values = FloatArray(32)
    val head = mutableIntStateOf(0)
}

private val LocalLevel = staticCompositionLocalOf { mutableFloatStateOf(0f) }
private val LocalHistory = staticCompositionLocalOf { LevelHistory() }

private val speechEnglish = mapOf(
    "语音指令" to "Voice command", "听写" to "Dictate", "指令" to "Command", "执行匹配的快捷操作（先确认）" to "a matching shortcut, after you confirm",
    "确认" to "Confirm", "识别" to "Recognize", "点击麦克风说出指令" to "Tap the microphone and say a command",
    "将执行：" to "Will run: ", "听到：「%s」" to "Heard: “%s”", "说完点「完成」，匹配后会先请你确认" to "Select Finish when done; you confirm before anything runs",
    "完成并识别" to "Finish and match", "重说" to "Say again", "执行" to "Run",
    "正在匹配快捷操作…" to "Matching a shortcut…", "正在整理口述…" to "Tidying the transcript…",
    "没有匹配到快捷操作，可换个说法重新录音。" to "No shortcut matched. Try saying it another way.",
    "指令匹配失败：需要可用的翻译密钥和网络，请重新录音。" to "Could not match the command. It needs a working translation key and network; record again.",
    "整理未完成，已使用原始转写。" to "Tidying did not finish; the raw transcript is used.",
    "还没有可用的快捷操作。先在「快捷键」中添加，再用语音执行。" to "No shortcuts yet. Add some under Shortcuts, then run them by voice.",
    "说出想做的事，例如「打开微信」或「切换横屏」。只会从你已设置的快捷操作中选择，执行前需要确认。" to "Say what you want, such as “open WeChat” or “rotate to landscape”. Only your own shortcuts can be chosen, and you confirm before one runs.",
    "整理口述" to "Tidy dictation", "去掉口头禅、补标点和分段；会多一次文本请求，失败时用原始转写" to "Removes filler words and adds punctuation and paragraphs. Makes one more text request; the raw transcript is kept if it fails.",
    "语音转文字" to "Speech to text", "关闭" to "Close", "结果去向：" to "Result goes to: ",
    "填入翻译原文" to "translation source", "上屏到原输入框" to "the original input field", "预览后复制" to "preview, then copy",
    "录音" to "Record", "转写" to "Transcribe", "翻译" to "Translate", "上屏" to "Insert", "编辑" to "Edit",
    "第 %d 步，共 3 步：%s" to "Step %d of 3: %s",
    "完成录音" to "Finish recording", "转写录音" to "Transcribe recording", "开始录音" to "Start recording",
    "先设置语音密钥" to "Set a speech key first", "点击麦克风开始说话" to "Tap the microphone and speak",
    "正在聆听…" to "Listening…", "正在保存录音…" to "Saving recording…", "录音待转写" to "Recording ready",
    "正在转写…" to "Transcribing…", "转写完成" to "Transcript ready",
    "最长 2 分钟；点击完成后才上传" to "Up to 2 minutes; uploads only after you finish",
    "说完点「完成」，文字会填入翻译原文" to "Select Finish when done; the text fills the translation source",
    "说完点「完成」，文字会直接上屏" to "Select Finish when done; the text is inserted directly",
    "说完点「完成」，先预览再决定去向" to "Select Finish when done; preview before choosing where it goes",
    "点「转写」上传；不满意可重新录音" to "Select Transcribe to upload, or record again",
    "可编辑后选择去向" to "Edit if needed, then choose where it goes",
    "还剩 %d 秒，到时自动停止" to "%d seconds left before recording stops",
    "已等待 %d 秒" to "Waited %d s",
    "转写后直接上屏" to "Insert right after transcribing",
    "完成即写入原输入框，失败时保留草稿" to "Writes into the original field; the draft is kept if that fails",
    "先预览，再手动上屏、翻译或复制" to "Preview first, then insert, translate or copy",
    "识别结果（可编辑）" to "Transcript (editable)",
    "直接上屏需要 Android 13 或更高版本；可复制后手动粘贴，或填入翻译。" to "Direct insertion needs Android 13 or later. Copy and paste manually, or send to translation.",
    "没有可用的原输入框；可复制后粘贴，或填入翻译。" to "No original input field is available. Copy and paste, or send to translation.",
    "取消" to "Cancel", "完成并填入翻译" to "Finish and translate", "完成并上屏" to "Finish and insert", "完成并转写" to "Finish and transcribe",
    "重新录音" to "Record again", "取消转写" to "Cancel transcription", "重录" to "Redo", "复制" to "Copy", "填入翻译" to "Translate",
    "收起语音密钥" to "Hide speech key", "语音 API Key" to "Speech API key", "（未设置）" to " (not set)",
    "专用语音密钥加密保存在本机；未设置时回退使用翻译密钥。订阅套餐密钥可能不支持语音，请使用可调用语音 API 的密钥。" to "The dedicated speech key is encrypted on this device. When absent, the translation key is used. Subscription keys may not support speech; use a key with speech API access.",
    "MiniMax 语音 API Key" to "MiniMax speech API key", "隐藏" to "Hide", "显示" to "Show", "保存密钥" to "Save key", "移除专用密钥" to "Remove key",
    "密钥已加密保存。" to "Key saved with encryption.", "密钥保存失败，请检查格式后重试。" to "Could not save the key. Check its format and retry.",
    "专用语音密钥已移除；若有翻译密钥，将回退使用。" to "Dedicated speech key removed. The translation key will be used if available.", "密钥操作失败，请重试。" to "Key operation failed. Retry.",
    "隐私与费用说明" to "Privacy and charges",
    "仅在点击「完成」或「转写」后，音频才发送至 MiniMax 中国站，可能产生 API 费用。离开页面立即停止录音，不会自动上传；关闭后删除临时录音。完全静音的录音不会上传。" to "Audio is sent to MiniMax China only after you select Finish or Transcribe, and API charges may apply. Leaving stops recording without uploading; closing deletes temporary audio. Silent recordings are never uploaded.",
    "不在后台录音；转写文字草稿仅保留在本进程内存。上屏不会自动复制。" to "No background recording. Drafts stay in process memory only. Inserting never copies automatically.",
    "权限已被拒绝：请在系统设置 → 权限 → 麦克风中允许，然后返回点击开始录音。" to "Access denied. Open system settings → Permissions → Microphone, allow access, then return and select Start recording.",
    "打开应用权限设置" to "Open app permission settings",
    "重新录音？" to "Record again?", "将删除现有临时录音；文字草稿保留，新的转写成功后才替换。" to "Temporary audio will be deleted. The text draft stays until a new transcription succeeds.",
    "返回" to "Back", "关闭语音页？" to "Close speech capture?", "录音和请求将取消，临时音频将删除，文字草稿仍保留。已发出的请求可能计费。" to "Recording and requests will stop. Temporary audio will be deleted; the text draft is kept. Sent requests may still incur charges.",
    "需要麦克风权限；请允许后重新点击开始录音。" to "Microphone access is required. Allow it, then select Start recording again.",
    "录音已停止，尚未上传。点击转写后才会联网。" to "Recording stopped without uploading. Select Transcribe to send it.",
    "没有检测到声音，未上传。请靠近麦克风后重新录音。" to "No sound was detected, so nothing was uploaded. Move closer to the microphone and record again.",
    "录音失败或没有录到声音，请检查麦克风后重新录音。" to "Recording failed or captured no sound. Check the microphone and record again.",
    "无法启动录音，请检查麦克风权限或是否被其他应用占用。" to "Could not start recording. Check microphone permission and whether another app is using it.",
    "请先保存语音 API Key。" to "Save a speech API key first.", "转写完成，可编辑后上屏、翻译或复制。" to "Transcript ready. Edit it, then insert, translate or copy.",
    "API Key 无效或已失效，请更换后重试。" to "The API key is invalid or expired. Replace it and retry.", "语音账户余额不足，请充值后重试。" to "Insufficient speech account balance. Top up and retry.",
    "请求过于频繁，请稍后重试。" to "Too many requests. Retry later.", "录音超过服务限额，请缩短后重新录音。" to "Recording exceeds the service limit. Record a shorter clip.",
    "没有识别到文字，可重试或重新录音。" to "No text was recognized. Retry or record again.", "连接超时，录音仍保留，可重试。" to "Connection timed out. Audio is kept for retry.",
    "网络连接失败，检查网络后重试。" to "Connection failed. Check your network and retry.", "转写失败，录音仍保留，可重试或重新录音。" to "Transcription failed. Audio is kept; retry or record again.",
    "请求已取消；录音保留，重试可能再次计费。" to "Request cancelled. Audio is kept; retrying may incur another charge.", "录音已取消并删除。" to "Recording cancelled and deleted.",
)

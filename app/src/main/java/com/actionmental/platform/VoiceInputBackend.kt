package com.actionmental.platform

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodManager
import com.actionmental.core.action.Action
import com.actionmental.core.action.ActionResult
import com.actionmental.core.voice.VoiceInputRules
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * 用快捷键唤起输入法的语音输入。
 *
 * 两条路，各管一类输入法：
 * - 点语音键：在输入法窗口里按字眼找麦克风那一颗，点下去。Gboard、微信、搜狗、讯飞都是这样 ——
 *   它们的语音是键盘里的一个面板，没有独立的输入法可切。只读输入法这一个窗口，别的窗口一概不碰。
 * - 切语音输入法：Google 语音输入这类专职输入法，切过去就开始听，说完自己切回来。
 *
 * 接实体键盘时系统默认不弹软键盘，语音键根本不在屏幕上；这时临时让软键盘无视实体键盘弹出来，
 * 输入结束或前台换了应用就交还系统。
 */
class VoiceInputBackend(
    private val context: Context,
    private val accessibility: AccessibilityBridge,
    private val scope: CoroutineScope,
    private val privileged: () -> PrivilegedBackend,
) {

    private val imm get() = context.getSystemService(InputMethodManager::class.java)

    /** 切到语音输入法之前用的那一个，再按一次切回它。只在内存里，重启后退回第一个普通输入法。 */
    @Volatile
    private var returnIme: String? = null

    /** 临时压过实体键盘的软键盘显示模式，等着交还。 */
    private var forcedShow: Job? = null

    suspend fun trigger(mode: Action.VoiceInput.Mode): ActionResult = withContext(Dispatchers.Main) {
        val service = accessibility.boundService()
            ?: return@withContext ActionResult.Failed(ActionResult.Reason.ACCESSIBILITY_OFF)
        when (mode) {
            Action.VoiceInput.Mode.KEYBOARD -> tapVoiceKey(service)
            Action.VoiceInput.Mode.VOICE_IME -> switchVoiceIme(service)
            Action.VoiceInput.Mode.AUTO -> {
                // 已经停在语音输入法上：这一下是「切回去」，不该再去找键盘上的键
                if (VoiceInputRules.isOnVoiceIme(enabledImes(), currentIme())) return@withContext switchVoiceIme(service)
                val tapped = tapVoiceKey(service)
                val fallback = tapped is ActionResult.Failed && tapped.reason in FALLBACK_REASONS
                if (!fallback) return@withContext tapped
                val switched = switchVoiceIme(service)
                // 两条都没走通时报键盘那一条：它说的是用户眼前的情况，「没有语音输入法」只是备选也没有
                if (switched is ActionResult.Failed && switched.reason == ActionResult.Reason.NO_VOICE_IME) tapped else switched
            }
        }
    }

    // --- 点语音键 --------------------------------------------------------------

    private suspend fun tapVoiceKey(service: AccessibilityService): ActionResult {
        val window = imeWindow(service) ?: run {
            showKeyboard(service)?.let { return it }
            imeWindow(service) ?: return ActionResult.Failed(ActionResult.Reason.KEYBOARD_HIDDEN)
        }
        // 键盘刚弹出来时节点树可能还没填好，给它几次机会
        var key: AccessibilityNodeInfo? = null
        for (attempt in 0 until KEY_LOOKUP_TRIES) {
            if (attempt > 0) delay(POLL_MS)
            val root = (if (attempt == 0) window else imeWindow(service))?.root ?: continue
            key = withContext(Dispatchers.Default) { findVoiceKey(root) }
            if (key != null) break
        }
        val target = key ?: return ActionResult.Failed(
            ActionResult.Reason.VOICE_KEY_NOT_FOUND,
            imePackage(window).orEmpty(),
        )
        return if (click(service, target)) ActionResult.Ok("已点按语音键")
        else ActionResult.Failed(ActionResult.Reason.EXECUTION_FAILED, "语音键没有响应点按")
    }

    private fun imeWindow(service: AccessibilityService): AccessibilityWindowInfo? =
        runCatching { service.windows }.getOrNull()
            ?.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }

    private fun imePackage(window: AccessibilityWindowInfo): String? =
        runCatching { window.root?.packageName?.toString() }.getOrNull()

    /**
     * 把键盘叫出来。先点一下获得焦点的输入框（多数应用这样就会弹），
     * 还不出来就是实体键盘压着，临时让软键盘无视它。返回 null 表示可以继续往下找。
     */
    private suspend fun showKeyboard(service: AccessibilityService): ActionResult? {
        val focus = runCatching { service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()
        if (focus == null || !focus.isEditable) return ActionResult.Failed(ActionResult.Reason.NO_INPUT_FIELD)
        focus.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (awaitImeWindow(service, SHOW_WAIT_MS)) return null

        forceSoftKeyboard(service)
        return if (awaitImeWindow(service, FORCED_SHOW_WAIT_MS)) null
        else ActionResult.Failed(ActionResult.Reason.KEYBOARD_HIDDEN)
    }

    private suspend fun awaitImeWindow(service: AccessibilityService, timeoutMs: Long): Boolean =
        withTimeoutOrNull(timeoutMs) {
            while (imeWindow(service) == null) delay(POLL_MS)
            true
        } ?: false

    /**
     * 让软键盘在接着实体键盘时也弹出来，并挂一个交还的看守：
     * 输入结束（Android 13+ 才收得到）、前台换了应用、或者到点了，任一发生就交还系统。
     */
    private fun forceSoftKeyboard(service: AccessibilityService) {
        val controller = service.softKeyboardController
        if (!controller.setShowMode(AccessibilityService.SHOW_MODE_IGNORE_HARD_KEYBOARD)) return
        forcedShow?.cancel()
        forcedShow = scope.launch {
            // 键盘弹出来时窗口事件可能报的是输入法自己的包名，那不算换了应用
            val imePackages = enabledImes().map { it.id.substringBefore('/') }.toSet()
            withTimeoutOrNull(FORCED_SHOW_MAX_MS) {
                merge(
                    accessibility.inputLive.drop(1).filter { !it },
                    accessibility.foregroundPackage.drop(1).filter { it != null && it !in imePackages },
                ).first()
            }
            withContext(Dispatchers.Main) {
                runCatching {
                    accessibility.boundService()?.softKeyboardController
                        ?.setShowMode(AccessibilityService.SHOW_MODE_AUTO)
                }
            }
        }
    }

    /** 广度优先，在可见的节点里挑最像语音键的一颗。 */
    private fun findVoiceKey(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestScore = 0
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_NODES) {
            val node = queue.removeFirst()
            visited++
            if (node.isVisibleToUser) {
                val score = VoiceInputRules.score(node.text, node.contentDescription, node.viewIdResourceName)
                if (score > bestScore) {
                    best = node
                    bestScore = score
                }
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::add)
        }
        return best
    }

    /**
     * 点下去。手势优先：键盘一定认真实的触摸，而不少键盘的虚拟节点对 ACTION_CLICK 只是回个 true。
     * 手势发不出去再退到节点点按，最后是 Shizuku 的 input tap。
     */
    private suspend fun click(service: AccessibilityService, node: AccessibilityNodeInfo): Boolean {
        val bounds = Rect().also(node::getBoundsInScreen)
        if (!bounds.isEmpty && tap(service, bounds.exactCenterX(), bounds.exactCenterY())) return true

        var target: AccessibilityNodeInfo? = node
        repeat(CLICK_ANCESTOR_DEPTH) {
            val current = target ?: return@repeat
            if (current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            target = current.parent
        }

        if (bounds.isEmpty) return false
        val backend = privileged()
        if (backend.availability() is ActionResult.Failed) return false
        return backend.exec("input tap " + bounds.centerX() + " " + bounds.centerY())
            .getOrNull()?.ok == true
    }

    private suspend fun tap(service: AccessibilityService, x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, TAP_MS))
            .build()
        return withTimeoutOrNull(GESTURE_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val callback = object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(false)
                    }
                }
                val sent = runCatching { service.dispatchGesture(gesture, callback, null) }.getOrDefault(false)
                if (!sent && cont.isActive) cont.resume(false)
            }
        } ?: false
    }

    // --- 切语音输入法 ----------------------------------------------------------

    private suspend fun switchVoiceIme(service: AccessibilityService): ActionResult {
        val enabled = enabledImes()
        val current = currentIme()

        if (VoiceInputRules.isOnVoiceIme(enabled, current)) {
            val back = VoiceInputRules.pickReturnIme(enabled, current, returnIme)
                ?: return ActionResult.Failed(ActionResult.Reason.TARGET_NOT_FOUND, "没有可切回的输入法")
            return switchTo(service, back.id).let { result ->
                if (result is ActionResult.Ok) ActionResult.Ok("已切回 " + back.label) else result
            }
        }

        val voice = VoiceInputRules.pickVoiceIme(enabled, current)
            ?: return ActionResult.Failed(ActionResult.Reason.NO_VOICE_IME)
        val result = switchTo(service, voice.id)
        if (result !is ActionResult.Ok) return result
        returnIme = current
        // 没有键盘就听不了：语音输入法的窗口也得弹得出来
        if (imeWindow(service) == null) {
            val focus = runCatching { service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()
            if (focus != null && focus.isEditable && !awaitImeWindow(service, SHOW_WAIT_MS)) {
                forceSoftKeyboard(service)
            }
        }
        return ActionResult.Ok("已切到 " + voice.label)
    }

    /** 无障碍自己能切就不劳 Shizuku（Android 11+）；老系统或被拒时退到 `ime set`。 */
    private suspend fun switchTo(service: AccessibilityService, imeId: String): ActionResult {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            runCatching { service.softKeyboardController.switchToInputMethod(imeId) }.getOrDefault(false)
        ) {
            return ActionResult.OK
        }
        val backend = privileged()
        val availability = backend.availability()
        if (availability is ActionResult.Failed) {
            return ActionResult.Failed(availability.reason, "切换输入法需要 Android 11 或 Shizuku")
        }
        return backend.exec("ime set '" + imeId.replace("'", "") + "'").fold(
            onSuccess = {
                if (it.ok) ActionResult.OK
                else ActionResult.Failed(ActionResult.Reason.EXECUTION_FAILED, it.output.trim().take(200))
            },
            onFailure = { ActionResult.Failed(ActionResult.Reason.EXECUTION_FAILED, it.message.orEmpty()) },
        )
    }

    private fun currentIme(): String? =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)

    private fun enabledImes(): List<VoiceInputRules.Ime> {
        val manager = imm ?: return emptyList()
        val pm = context.packageManager
        return runCatching {
            manager.enabledInputMethodList.map { info ->
                val modes = buildList {
                    for (i in 0 until info.subtypeCount) add(info.getSubtypeAt(i).mode.orEmpty())
                }
                VoiceInputRules.Ime(info.id, info.loadLabel(pm).toString(), modes)
            }
        }.getOrDefault(emptyList())
    }

    private companion object {
        /** 这几种失败说的是「键盘这条路不通」，自动模式可以改走语音输入法。 */
        val FALLBACK_REASONS = setOf(
            ActionResult.Reason.KEYBOARD_HIDDEN,
            ActionResult.Reason.VOICE_KEY_NOT_FOUND,
        )

        const val POLL_MS = 80L
        const val SHOW_WAIT_MS = 700L
        const val FORCED_SHOW_WAIT_MS = 1_200L
        const val KEY_LOOKUP_TRIES = 4
        const val MAX_NODES = 1_500
        const val CLICK_ANCESTOR_DEPTH = 3
        const val TAP_MS = 60L
        const val GESTURE_TIMEOUT_MS = 1_000L

        /** 看守最多守这么久：Android 13 以下收不到输入结束，靠它兜底交还。 */
        const val FORCED_SHOW_MAX_MS = 10 * 60_000L
    }
}

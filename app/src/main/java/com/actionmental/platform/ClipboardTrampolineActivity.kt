package com.actionmental.platform

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 后台写剪贴板的跳板。
 *
 * ColorOS 等 ROM 把 WRITE_CLIPBOARD 默认设成 foreground：进程在后台时 setPrimaryClip
 * 被系统静默丢弃，既不抛异常也没有返回值，调用方会以为写成功了。
 * 窗口拿到焦点的那一刻进程一定是前台，写入必然放行；写完立即退出，回到用户原来的应用。
 */
class ClipboardTrampolineActivity : Activity() {

    private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        }
        // 迟迟拿不到焦点（锁屏、分屏另一侧占着焦点）时不赖着不走，调用方按超时记为失败。
        window.decorView.postDelayed({ if (!isFinishing) finish() }, FOCUS_TIMEOUT_MS)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || done) return
        done = true
        val text = intent.getStringExtra(EXTRA_TEXT)
        val ok = text != null && runCatching {
            getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText(intent.getStringExtra(EXTRA_LABEL), text))
            true
        }.getOrDefault(false)
        pending.remove(intent.getLongExtra(EXTRA_ID, -1L))?.complete(ok)
        finish()
    }

    @Suppress("DEPRECATION")
    override fun finish() {
        super.finish()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) overridePendingTransition(0, 0)
    }

    companion object {
        private const val EXTRA_ID = "id"
        private const val EXTRA_TEXT = "text"
        private const val EXTRA_LABEL = "label"
        private const val FOCUS_TIMEOUT_MS = 1_500L
        private const val RESULT_TIMEOUT_MS = 2_000L

        private val pending = ConcurrentHashMap<Long, CompletableDeferred<Boolean>>()
        private val nextId = AtomicLong()

        /** 拉起跳板写入；真正写进去才返回 true，起不来或超时都算失败。 */
        suspend fun write(context: Context, label: String, text: String): Boolean {
            val id = nextId.incrementAndGet()
            val result = CompletableDeferred<Boolean>()
            pending[id] = result
            try {
                val intent = Intent(context, ClipboardTrampolineActivity::class.java)
                    .putExtra(EXTRA_ID, id)
                    .putExtra(EXTRA_TEXT, text)
                    .putExtra(EXTRA_LABEL, label)
                    .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_NO_ANIMATION or
                            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS,
                    )
                if (runCatching { context.startActivity(intent) }.isFailure) return false
                return withTimeoutOrNull(RESULT_TIMEOUT_MS) { result.await() } ?: false
            } finally {
                pending.remove(id)
            }
        }
    }
}

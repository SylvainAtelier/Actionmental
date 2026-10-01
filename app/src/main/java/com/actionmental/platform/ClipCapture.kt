package com.actionmental.platform

import com.actionmental.core.clip.ClipRecorder
import com.actionmental.platform.shizuku.IClipSink
import com.actionmental.platform.shizuku.ShizukuManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 剪贴板历史的钩子：在特权进程里挂一个系统剪贴板监听，每次复制由系统回调推过来。
 *
 * 不轮询、不持锁、不另起进程 —— 特权进程本来就为按键注入常驻着，多挂一个监听，
 * 代价是每次复制一次 binder 调用。没有 Shizuku 时这里什么都不做，
 * 历史只收应用自己复制的内容和打开历史页时补读的那一条。
 *
 * 补读（[catchUp]）兜的是两个空档：锁屏期间系统对谁都返回空，解锁时读一次；
 * 特权服务断开重连之间的复制收不到回调，连上时读一次。重复的由 [ClipRecorder] 去重。
 */
class ClipCapture(
    private val scope: CoroutineScope,
    private val shizuku: ShizukuManager,
    private val recorder: ClipRecorder,
    private val onEvent: (warn: Boolean, message: String, detail: String) -> Unit,
) {
    private val lock = Mutex()

    private val _watching = MutableStateFlow(false)

    /** 监听此刻挂没挂着。历史页据此告诉用户「正在记录」还是「只在打开时补读」。 */
    val watchingState: StateFlow<Boolean> = _watching.asStateFlow()

    private var watching: Boolean
        get() = _watching.value
        set(value) {
            _watching.value = value
        }

    private val sink = object : IClipSink.Stub() {
        // binder 线程上回调：只投递，不在这里做 IO
        override fun onClip(text: String?, sensitive: Boolean) {
            scope.launch { recorder.offer(text, sensitive) }
        }
    }

    /** 按「开关开着且特权服务连着」挂上或摘下监听。重复调用无副作用。 */
    suspend fun sync(enabled: Boolean, serviceBound: Boolean) = lock.withLock {
        when {
            enabled && serviceBound && !watching -> {
                shizuku.watchClipboard(sink)
                    .onSuccess {
                        watching = true
                        onEvent(false, "剪贴板监听已挂上", "")
                    }
                    .onFailure { onEvent(true, "剪贴板监听挂不上", it.message.orEmpty()) }
                if (watching) catchUpLocked()
            }
            // 服务断了，监听随特权进程一起没了，只记状态
            !serviceBound -> watching = false
            !enabled && watching -> {
                shizuku.unwatchClipboard()
                watching = false
                onEvent(false, "剪贴板监听已摘下", "")
            }
        }
    }

    suspend fun catchUp() = lock.withLock { if (watching) catchUpLocked() }

    private suspend fun catchUpLocked() {
        // 补读时前台早就不是复制它的那个应用了，来源记为未知，免得张冠李戴
        shizuku.readClipboard().getOrNull()?.let { recorder.offer(it, sensitive = false, source = null) }
    }
}

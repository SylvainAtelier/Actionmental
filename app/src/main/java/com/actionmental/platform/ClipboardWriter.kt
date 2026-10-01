package com.actionmental.platform

import android.app.AppOpsManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Process
import com.actionmental.platform.shizuku.ShizukuManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 写剪贴板的唯一出口。
 *
 * ColorOS 把所有应用的 WRITE_CLIPBOARD 默认设成 foreground，快捷键又总在后台触发：
 * 这时 setPrimaryClip 会被系统静默丢弃，不抛异常也没有返回值。所以分三级：
 * 1. 此刻放行就直接写；
 * 2. 特权服务连着就以 shell 身份代写（shell 的 WRITE_CLIPBOARD 默认 allow，不必改任何系统设置）；
 * 3. 都不行时借透明跳板 Activity 进一下前台再写，见 [ClipboardTrampolineActivity]。
 */
class ClipboardWriter(
    private val context: Context,
    private val shizuku: ShizukuManager,
) {

    /**
     * 此刻直接写会不会被放行。
     *
     * unsafeCheckOpNoThrow 返回的是按当前进程状态折算后的模式，后台时会是 IGNORED。
     * 查不了就当放行，退回直接写。
     */
    fun writableDirectly(): Boolean = runCatching {
        context.getSystemService(AppOpsManager::class.java)
            .unsafeCheckOpNoThrow(OPSTR_WRITE_CLIPBOARD, Process.myUid(), context.packageName) ==
            AppOpsManager.MODE_ALLOWED
    }.getOrDefault(true)

    /** 写进去了才返回 true。 */
    suspend fun write(label: String, text: String): Boolean {
        if (writableDirectly()) {
            return withContext(Dispatchers.Main) {
                runCatching {
                    context.getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText(label, text))
                    true
                }.getOrDefault(false)
            }
        }
        if (shizuku.clipboardReady() && shizuku.writeClipboard(text).isSuccess) return true
        return ClipboardTrampolineActivity.write(context, label, text)
    }

    private companion object {
        /** AppOpsManager.OPSTR_WRITE_CLIPBOARD 是隐藏常量，按字面量写。 */
        const val OPSTR_WRITE_CLIPBOARD = "android:write_clipboard"
    }
}

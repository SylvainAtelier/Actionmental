package com.actionmental.platform

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.actionmental.AppGraph
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 桌面按钮的落点：全透明、无动画，执行完就退，用户只看到动作本身的结果。
 *
 * 动作跑在 AppGraph 的作用域里，这里只是在它跑完之前把窗口留在前台 ——
 * 进程从后台启动别的应用、写剪贴板都要「有一个看得见的窗口」才放行，
 * 键盘那条路靠的是无障碍服务，桌面这条路靠的就是这个窗口。
 * 不导出：桌面是以本应用的身份打开它的，别的应用无从借它执行用户的命令。
 */
class ShortcutLaunchActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        }
        // 重建（旋转、改深色）时不再执行第二遍
        if (savedInstanceState != null) {
            finish()
            return
        }
        val id = intent.getStringExtra(DesktopShortcuts.EXTRA_SHORTCUT_ID)
        val job = AppGraph.get(this).runFromDesktop(id)
        lifecycleScope.launch {
            withTimeoutOrNull(HOLD_MS) { job.join() }
            finish()
        }
    }

    @Suppress("DEPRECATION")
    override fun finish() {
        super.finish()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) overridePendingTransition(0, 0)
    }

    private companion object {
        /** 慢动作（Shell、解冻启动）不让透明窗口一直挡着触摸；到点先退，动作照常跑完。 */
        const val HOLD_MS = 3_000L
    }
}

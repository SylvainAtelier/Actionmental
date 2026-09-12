package com.actionmental.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.actionmental.AppGraph
import com.actionmental.ui.theme.ActionmentalTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val vm: AppViewModel = viewModel()
            val settings by vm.settings.collectAsStateWithLifecycle()
            ActionmentalTheme(theme = settings.theme, language = settings.language) {
                AppRoot(vm)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        AppGraph.get(this).onUiStarted()
    }

    /**
     * 界面看不见了，就把只有界面用得上的缓存交回去。
     *
     * 进程不会跟着结束 —— 它还要以无障碍服务的身份继续收按键，而「活着的时候占多少」
     * 直接决定它在厂商后台清理名单上的位置。这里不等系统的 onTrimMemory：
     * 那条回调什么时候来、来不来都不由应用决定，onStop 却是确定发生的。
     */
    override fun onStop() {
        super.onStop()
        AppGraph.get(this).onUiStopped()
    }
}

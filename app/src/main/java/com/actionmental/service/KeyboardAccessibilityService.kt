package com.actionmental.service

import android.accessibilityservice.AccessibilityService
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.actionmental.AppGraph

/**
 * 实体键盘事件采集器（PRD 21 · KeyboardEventSource / 35.1）。
 *
 * 这个类刻意保持极薄：只做三件事——登记自己、转发按键、上报前台应用。
 * 匹配、执行、持久化一律不在这里，未来加宏 / 长按 / 序列都不需要改动它。
 */
class KeyboardAccessibilityService : AccessibilityService() {

    private val graph by lazy { AppGraph.get(this) }

    override fun onServiceConnected() {
        super.onServiceConnected()
        graph.eventLog.info("service", "onServiceConnected")
        graph.accessibility.attach(this)
        // 键盘监听挂在对象图上，跟着进程活，不随服务的绑定来回注册
        graph.refreshKeyboards()
    }

    /**
     * 按键回调里的异常绝不能逃出去。
     *
     * 这个回调跑在服务进程的主线程上：抛一次异常，进程就没了，系统随即解绑服务，
     * 而用户看到的只是「已授权但未连接」，还得自己去设置里关掉再打开。
     * 一次按键处理失败的代价，最多只应该是这一颗键没生效。
     *
     * 出错时返回 false 而不是 true：拦下一颗处理失败的键等于让它彻底消失，
     * 用户既没有原键也没有动作，还完全看不出原因（PRD 25）。
     */
    override fun onKeyEvent(event: KeyEvent): Boolean = try {
        graph.counters.keyEvent.incrementAndGet()
        graph.pipeline.dispatch(event)
    } catch (t: Throwable) {
        graph.eventLog.error("key", "按键处理异常 · keyCode " + event.keyCode, t)
        graph.pipeline.reset()
        false
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        try {
            // 暂停期间不认前台应用。事件照收（300ms 合并窗口，代价可忽略），
            // 换来的是不去改写 serviceInfo —— 那正是「解除暂停后按键不灵」的根源
            if (graph.paused.value) return
            if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                graph.counters.windowChange.incrementAndGet()
                graph.accessibility.onForegroundChanged(event.packageName?.toString())
            }
        } catch (t: Throwable) {
            graph.eventLog.error("service", "前台变化处理异常", t)
        }
    }

    override fun onInterrupt() = graph.eventLog.warn("service", "onInterrupt")

    override fun onDestroy() {
        // onUnbind 不保证被调用；连接状态必须在这里也放掉，否则会卡在「已连接」误报
        graph.eventLog.warn("service", "onDestroy · 服务实例被销毁")
        graph.accessibility.detach()
        super.onDestroy()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        graph.eventLog.warn("service", "onUnbind · 系统解绑了监听服务")
        graph.pipeline.reset()
        graph.accessibility.detach()
        return super.onUnbind(intent)
    }
}

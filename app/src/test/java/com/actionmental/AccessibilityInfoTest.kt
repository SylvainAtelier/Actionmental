package com.actionmental

import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityEvent
import com.actionmental.platform.serviceInfoLooksSuppressed
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 暂停 / 恢复链上唯一一个不需要设备就能验证的判断。
 *
 * 它挡的是这么一条路：暂停期间服务掉过线又连回来，重连那一刻读到的配置已经是
 * 被压制过的那份；如果把它当成清单声明存下来，解除暂停时就会把「什么都不收、
 * 也不要按键」原样写回去 —— 服务一路显示已连接，按键却一颗都不来，
 * 用户只能去系统设置里关掉再打开。长时间暂停恰恰最容易凑齐这个时序。
 */
class AccessibilityInfoTest {

    private val filterKeys = AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS

    @Test
    fun `压制过的配置必须被认出来`() {
        // 暂停做的两件事：清零 eventTypes、摘掉按键过滤标志
        assertTrue(serviceInfoLooksSuppressed(eventTypes = 0, flags = 0))
        assertTrue(serviceInfoLooksSuppressed(eventTypes = 0, flags = filterKeys))
    }

    @Test
    fun `只摘掉按键过滤也算压制`() {
        // 只丢了按键过滤标志同样是坏的：窗口事件照收，唯独按键永远到不了 ——
        // 从外面看与「服务掉线」一模一样，而这正是最难查的那一种
        assertFalse(
            serviceInfoLooksSuppressed(
                eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                flags = filterKeys,
            ),
        )
        assertTrue(
            serviceInfoLooksSuppressed(
                eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                flags = 0,
            ),
        )
    }

    @Test
    fun `清单声明的那份不该被当成压制`() {
        assertFalse(
            serviceInfoLooksSuppressed(
                eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                flags = filterKeys or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS,
            ),
        )
    }
}

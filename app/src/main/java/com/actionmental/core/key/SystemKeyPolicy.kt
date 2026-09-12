package com.actionmental.core.key

/**
 * 系统抢在无障碍之前处理掉的按键。
 *
 * 事件的顺序是固定的，而且这一段不在我们手上：
 * InputDispatcher 收到按键 → `interceptKeyBeforeQueueing`（系统手势在这里就执行了）
 * → 无障碍输入过滤器（我们的 onKeyEvent） → 前台应用 / 输入法。
 *
 * 所以「拦截」在这里只有一个确切含义：**不再往下发给前台应用与输入法**。
 * 已经在上一段跑掉的系统动作，返回 true 也收不回来。
 *
 * Android 15 起 Meta 组合键由 KeyGestureController 统一接管（各家 ROM 还会加自己的），
 * 于是 Meta + X 会出现「我们的动作执行了，系统的动作也执行了」这种双响。
 * 实测（ColorOS 16 / Android 16）：Meta + A 触发系统长截屏的同时，onKeyEvent 照常收到事件。
 *
 * 这个对象不改变任何行为，只负责把这条边界说清楚 —— 静默的双响最难排查（PRD 25）。
 */
object SystemKeyPolicy {

    /** 一条给用户看的说明；null 表示这个组合键没有已知的系统抢占。 */
    fun reservation(combo: KeyCombo?): String? {
        if (combo == null) return null
        val isLoneMeta = combo.isModifierKey &&
            KeyCombo.selfModifierMask(combo.keyCode) == KeyCombo.MOD_META &&
            combo.modifiers == 0
        return when {
            isLoneMeta -> LONE_META
            combo.meta -> META_COMBO
            else -> null
        }
    }

    /** 用了 Meta 修饰位，或者主键本身就是 Meta。 */
    fun usesMeta(combo: KeyCombo?): Boolean = reservation(combo) != null

    const val META_COMBO: String =
        "Android 15 起 Meta 组合键由系统在事件入队时接管，比无障碍服务更早。" +
            "这里仍然能匹配并拦下它（前台应用与输入法收不到），" +
            "但系统自己那份动作（截屏、最近任务、搜索…）已经发生，应用阻止不了。" +
            "要完全避开，请改用 Ctrl / Alt / Shift 组合。"

    const val LONE_META: String =
        "单独一颗 Meta 只能轻点触发，而且事件照常放行 —— 系统的 Meta 快捷键菜单仍会弹出。" +
            "想要一颗干净的触发键，建议换成 Caps 或功能键。"
}

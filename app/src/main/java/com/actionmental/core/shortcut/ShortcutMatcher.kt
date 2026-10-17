package com.actionmental.core.shortcut

import com.actionmental.core.key.KeyCombo
import com.actionmental.core.key.KeyboardDevice

/**
 * 快捷键匹配器。
 *
 * 按键线程上运行，所以它只读一份不可变的内存索引；数据库/DataStore 变化时
 * 由仓库整体换掉索引引用（PRD 27：不得在按键路径上做 IO）。
 */
class ShortcutMatcher {

    @Volatile
    private var index: Map<KeyCombo, List<Shortcut>> = emptyMap()

    fun update(shortcuts: List<Shortcut>) {
        index = shortcuts.filter { it.enabled }.groupBy { it.combo }
    }

    /**
     * @param foregroundPackage 当前前台应用包名，可空（未知时按全局处理）。
     * @return 命中的快捷键；作用域越具体优先级越高。
     */
    fun match(combo: KeyCombo, device: KeyboardDevice, foregroundPackage: String?): Shortcut? {
        val candidates = index[combo] ?: return null
        // 一趟扫描挑最具体的，不建中间列表：这条路径跑在按键回调的主线程上，
        // 每个命中的组合键都会走一遍，连按时少分配一次就少一次回收
        var best: Shortcut? = null
        var bestScore = Int.MAX_VALUE
        for (candidate in candidates) {
            if (!candidate.deviceScope.matches(device.descriptor) || !candidate.appScope.matches(foregroundPackage)) continue
            val score = specificity(candidate)
            if (score < bestScore) {
                best = candidate
                bestScore = score
            }
        }
        return best
    }

    /** 数值越小越具体：应用限定 > 设备限定 > 全局。 */
    private fun specificity(s: Shortcut): Int {
        var score = 100
        if (s.appScope.mode != AppScope.Mode.GLOBAL) score -= 50
        if (!s.deviceScope.isAll) score -= 20
        return score
    }
}

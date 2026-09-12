package com.actionmental.core.shortcut

/** 冲突检测结果：新绑定 [incoming] 与已存在的 [existing] 落在同一作用域。 */
data class ShortcutConflict(
    val existing: Shortcut,
    val incoming: Shortcut,
)

/** 保存结果。冲突时必须交给用户决定，不允许静默覆盖（PRD 4.6 / 15）。 */
sealed interface SaveOutcome {
    data class Saved(val shortcut: Shortcut) : SaveOutcome
    data class Conflict(val conflict: ShortcutConflict) : SaveOutcome
}

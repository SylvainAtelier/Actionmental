package com.actionmental.core.rotation

import com.actionmental.data.RotationRuleRepository
import com.actionmental.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * 前台应用 → 旋转规则（PRD 9 / 21）。
 *
 * 它不自己记「上一次设成了什么」，而是把规则表达为一个 override 压给 [RotationController]，
 * 离开受控应用时把 override 撤掉，控制器自然回到用户的全局意图 —— 因此不会出现
 * 「永远保持最后一次设置」的问题。
 */
class AppRotationRuleManager(
    private val scope: CoroutineScope,
    private val rules: RotationRuleRepository,
    private val settings: SettingsRepository,
    private val controller: RotationController,
    foregroundPackage: StateFlow<String?>,
) {
    private val _activeRule = MutableStateFlow<AppRotationRule?>(null)
    val activeRule: StateFlow<AppRotationRule?> = _activeRule.asStateFlow()

    init {
        scope.launch {
            combine(
                foregroundPackage,
                rules.rules,
                settings.settings,
            ) { pkg, list, config ->
                // 暂停期间规则一律不成立：前台包在这时本来也不再更新，
                // 但设置刚被切到暂停的那一刻，这条流会再发一次 —— 必须让它算出 null，
                // 把已经压着的 override 撤掉。
                if (config.paused || !config.appRulesEnabled || pkg == null) null
                else list.firstOrNull { it.packageName == pkg && it.enabled }
            }
                .distinctUntilChanged()
                .collect { rule ->
                    _activeRule.value = rule
                    // 规则里的「系统默认」也是一次明确的 override：
                    // 即使全局是强制横屏，进入这个应用也应该交还系统旋转策略。
                    controller.setOverride(rule?.rotationMode)
                }
        }
    }
}

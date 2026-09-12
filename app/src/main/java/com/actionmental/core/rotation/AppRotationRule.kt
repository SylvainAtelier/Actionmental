package com.actionmental.core.rotation

import kotlinx.serialization.Serializable

/** 针对某个应用的方向规则（PRD 9）。 */
@Serializable
data class AppRotationRule(
    val id: String,
    val packageName: String,
    val appLabel: String,
    val rotationMode: RotationMode,
    val enabled: Boolean = true,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
)

package com.actionmental.core.key

import android.view.InputDevice
import kotlinx.serialization.Serializable

/**
 * 输入设备快照。
 *
 * [descriptor] 是稳定标识（PRD 17）；[id] 每次连接都会变，只用于展示与调试，
 * 绝不作为持久化绑定键。
 */
@Serializable
data class KeyboardDevice(
    val id: Int,
    val name: String,
    val descriptor: String,
    val vendorId: Int = 0,
    val productId: Int = 0,
    val sources: Int = 0,
    val external: Boolean = true,
) {
    val vendorHex: String get() = "0x%04X".format(vendorId)
    val productHex: String get() = "0x%04X".format(productId)
    val shortDescriptor: String
        get() = if (descriptor.length > 12) descriptor.take(4) + "…" + descriptor.takeLast(4) else descriptor

    val transport: String
        get() = if (external) "external" else "builtin"

    companion object {
        val UNKNOWN = KeyboardDevice(-1, "未知设备", "", external = false)

        fun from(device: InputDevice?): KeyboardDevice {
            if (device == null) return UNKNOWN
            return KeyboardDevice(
                id = device.id,
                name = device.name ?: "未命名键盘",
                descriptor = device.descriptor ?: "",
                vendorId = device.vendorId,
                productId = device.productId,
                sources = device.sources,
                // isExternal 是 @hide API，这里用公开的 isVirtual 反推，够用且不碰隐藏接口
                external = !device.isVirtual,
            )
        }
    }
}

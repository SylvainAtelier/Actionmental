package com.actionmental.platform.shizuku

import android.content.ClipData
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * 运行在特权进程（shell 身份）里的剪贴板通道。
 *
 * 普通应用从 Android 10 起只有拿着焦点时才读得到剪贴板，监听回调也一样被拦；
 * shell 持有 READ_CLIPBOARD_IN_BACKGROUND，后台、熄屏都能收到回调并读出内容。
 * 这是剪贴板历史唯一不靠轮询、也不抢焦点的钩子。
 *
 * IClipboard 是隐藏接口，各版本参数不同（Android 10 只有 pkg + userId，
 * 13 加了 attributionTag，14 又加了 deviceId），所以按参数类型填：
 * 第一个 String 是调用方包名、其余 String 为 null；第一个 int 是 userId、其余 int 为 0。
 * 锁屏时系统对任何人都返回 null，这一点由主进程在解锁时补读一次兜住。
 */
internal class ShellClipboard {

    private val service: Any by lazy {
        val binder = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java)
            .invoke(null, "clipboard") as IBinder
        Class.forName("android.content.IClipboard\$Stub")
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, binder)!!
    }

    private fun method(name: String): Method =
        service.javaClass.methods.first { it.name == name }

    private val getClip by lazy { method("getPrimaryClip") }
    private val setClip by lazy { method("setPrimaryClip") }
    private val addListener by lazy { method("addPrimaryClipChangedListener") }
    private val removeListener by lazy { method("removePrimaryClipChangedListener") }

    /** 以 [first] 打头，其余参数按类型填。 */
    private fun args(m: Method, userId: Int, first: Any? = null): Array<Any?> {
        var strings = 0
        var ints = 0
        return m.parameterTypes.mapIndexed { index, type ->
            when {
                index == 0 && first != null -> first
                type == String::class.java -> if (strings++ == 0) CALLING_PACKAGE else null
                type == Int::class.javaPrimitiveType -> if (ints++ == 0) userId else 0
                type == Boolean::class.javaPrimitiveType -> false
                else -> null
            }
        }.toTypedArray()
    }

    fun read(userId: Int): ClipData? = getClip.invoke(service, *args(getClip, userId)) as ClipData?

    fun write(text: String, userId: Int) {
        setClip.invoke(service, *args(setClip, userId, ClipData.newPlainText("actionmental", text)))
    }

    private var listener: Any? = null

    /** 换监听：先摘掉旧的，同一时刻只挂一个。 */
    @Synchronized
    fun watch(userId: Int, onChanged: () -> Unit) {
        unwatch(userId)
        val binder = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code == INTERFACE_TRANSACTION) {
                    reply?.writeString(LISTENER_DESCRIPTOR)
                    return true
                }
                if (code != FIRST_CALL_TRANSACTION) return super.onTransact(code, data, reply, flags)
                runCatching(onChanged)
                return true
            }
        }
        // 监听接口同样是隐藏的，编译期拿不到；用动态代理冒充，系统那边只认 asBinder() 交出去的 binder
        val type = Class.forName(LISTENER_DESCRIPTOR)
        val proxy = Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { self, m, a ->
            when (m.name) {
                "asBinder" -> binder
                "hashCode" -> System.identityHashCode(self)
                "equals" -> self === a?.getOrNull(0)
                "toString" -> "ActionmentalClipListener"
                else -> null
            }
        }
        addListener.invoke(service, *args(addListener, userId, proxy))
        listener = proxy
        watchingUser = userId
    }

    private var watchingUser = 0

    @Synchronized
    fun unwatch(userId: Int = watchingUser) {
        val current = listener ?: return
        listener = null
        runCatching { removeListener.invoke(service, *args(removeListener, userId, current)) }
    }

    companion object {
        /** shell 的包名。READ_CLIPBOARD_IN_BACKGROUND 是按包名授予的，root 身份下照样认这个名字。 */
        const val CALLING_PACKAGE = "com.android.shell"
        private const val LISTENER_DESCRIPTOR = "android.content.IOnPrimaryClipChangedListener"

        /** Android 13 起的 ClipDescription.EXTRA_IS_SENSITIVE，按字面量写以兼容更早的编译目标。 */
        private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"

        /** 传给主进程前的上限：binder 单次事务总共 1MB，留足余量。 */
        private const val MAX_TRANSFER_CHARS = 128 * 1024

        /** 取出纯文本；非文本（图片、URI）返回 null。 */
        fun textOf(clip: ClipData?): String? {
            if (clip == null || clip.itemCount == 0) return null
            val text = clip.getItemAt(0).text?.toString() ?: return null
            return if (text.length > MAX_TRANSFER_CHARS) text.substring(0, MAX_TRANSFER_CHARS) else text
        }

        fun isSensitive(clip: ClipData?): Boolean =
            clip?.description?.extras?.getBoolean(EXTRA_IS_SENSITIVE, false) ?: false
    }
}

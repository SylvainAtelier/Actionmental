package com.actionmental.platform.shizuku

import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import java.io.BufferedReader

/**
 * 运行在 Shizuku 授予的 shell 进程中的特权服务。
 *
 * 只提供三种受控能力：执行一条命令、注入一次按键、以 shell 身份读写与监听剪贴板。
 * 业务逻辑一律不放在这里（PRD 21 · PrivilegedService）。
 * [exec] 的返回值统一编码为 "exitCode\n输出"，避免再定义一套 AIDL 数据类型。
 */
class PrivilegedUserService : IPrivilegedService.Stub() {

    private companion object {
        /** InputManager.INJECT_INPUT_EVENT_MODE_ASYNC：发出去就返回，不等窗口处理完。 */
        const val MODE_ASYNC = 0
    }

    /** 解析一次就固定下来：按键注入在按键路径上，不能每次都做反射查找。 */
    private val injector: ((KeyEvent) -> Boolean)? by lazy { resolveInjector() }

    /** 按住中的键的按下时刻。抬起事件要带上同一个 downTime，长按判定才对。 */
    private val downTimes = java.util.concurrent.ConcurrentHashMap<Int, Long>()

    override fun destroy() {
        System.exit(0)
    }

    override fun exec(command: String): String = try {
        val process = ProcessBuilder("sh", "-c", command)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use(BufferedReader::readText)
        val code = process.waitFor()
        code.toString() + "\n" + output.trim()
    } catch (t: Throwable) {
        "-1\n" + (t.message ?: t.javaClass.simpleName)
    }

    /**
     * 注入一次按下 + 抬起。
     *
     * 优先走 InputManager 的注入接口（一次 binder 调用，毫秒级）；
     * 拿不到时退回 `input keyevent`——它每次都要起一个新的虚拟机，慢得能感觉到，
     * 所以只作为兜底。
     */
    override fun injectKey(keyCode: Int, metaState: Int): String {
        val inject = injector ?: return execInput(keyCode)
        return try {
            val now = SystemClock.uptimeMillis()
            val ok = inject(event(now, KeyEvent.ACTION_DOWN, keyCode, metaState)) &&
                inject(event(now, KeyEvent.ACTION_UP, keyCode, metaState))
            if (ok) "" else "系统拒绝了这次注入"
        } catch (t: Throwable) {
            t.message ?: t.javaClass.simpleName
        }
    }

    /**
     * 只发半边。
     *
     * 把一颗修饰键整个替换掉时，目标键必须真的「按住」——
     * 按下与抬起之间要能夹住别的按键，所以不能像 [injectKey] 那样成对发出。
     * `input keyevent` 做不到这件事，因此这条路没有命令兜底，拿不到注入接口就如实报错。
     */
    override fun injectKeyState(keyCode: Int, metaState: Int, down: Boolean): String {
        val inject = injector ?: return "系统不提供按键注入接口，无法保持按住状态"
        return try {
            val downTime = if (down) {
                SystemClock.uptimeMillis().also { downTimes[keyCode] = it }
            } else {
                downTimes.remove(keyCode) ?: SystemClock.uptimeMillis()
            }
            val action = if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP
            if (inject(event(downTime, action, keyCode, metaState))) "" else "系统拒绝了这次注入"
        } catch (t: Throwable) {
            t.message ?: t.javaClass.simpleName
        }
    }

    /**
     * 整串注入：组合键发给输入法时用（见 KeyChord）。每颗键的抬起带它自己按下时的 downTime。
     *
     * 设备号沿用触发它的那把实体键盘；拿不到时调用方传虚拟键盘。
     * 没有注入接口时退到 `input keycombination`（Android 13+），它只能以虚拟键盘的身份发。
     */
    override fun injectSequence(deviceId: Int, keyCodes: IntArray, downs: IntArray, metaStates: IntArray): String {
        if (keyCodes.size != downs.size || keyCodes.size != metaStates.size) return "按键序列不完整"
        val inject = injector ?: return execCombination(keyCodes, downs)
        return try {
            val pressedAt = HashMap<Int, Long>()
            for (i in keyCodes.indices) {
                val down = downs[i] != 0
                val now = SystemClock.uptimeMillis()
                val downTime = if (down) now.also { pressedAt[keyCodes[i]] = it } else pressedAt.remove(keyCodes[i]) ?: now
                val action = if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP
                val event = event(downTime, action, keyCodes[i], metaStates[i], deviceId)
                if (!inject(event)) return "系统拒绝了这次注入"
            }
            ""
        } catch (t: Throwable) {
            t.message ?: t.javaClass.simpleName
        }
    }

    private fun execCombination(keyCodes: IntArray, downs: IntArray): String {
        val pressed = keyCodes.filterIndexed { i, _ -> downs[i] != 0 }
        val result = exec("input keycombination " + pressed.joinToString(" "))
        val code = result.substringBefore('\n').trim().toIntOrNull()
        return if (code == 0) "" else result.substringAfter('\n').trim().ifEmpty { "input keycombination 失败" }
    }

    // --- 剪贴板 ------------------------------------------------------------
    //
    // 内容一律不进 exec 那条路，也不写任何日志：剪贴板里是用户的私人文本。

    private val clipboard by lazy { ShellClipboard() }

    override fun watchClipboard(sink: IClipSink, userId: Int): String = try {
        clipboard.watch(userId) {
            val clip = clipboard.read(userId)
            val text = ShellClipboard.textOf(clip) ?: return@watch
            try {
                sink.onClip(text, ShellClipboard.isSensitive(clip))
            } catch (_: android.os.DeadObjectException) {
                // 主进程没了：特权进程随后也会被 Shizuku 收掉，先把监听摘下来
                clipboard.unwatch(userId)
            }
        }
        // 主进程死掉时 binder 讣告比上面那次失败的推送来得早
        sink.asBinder().linkToDeath({ clipboard.unwatch(userId) }, 0)
        ""
    } catch (t: Throwable) {
        describe(t)
    }

    override fun unwatchClipboard() {
        runCatching { clipboard.unwatch() }
    }

    override fun readClipboard(userId: Int): String? = runCatching {
        val clip = clipboard.read(userId)
        if (ShellClipboard.isSensitive(clip)) null else ShellClipboard.textOf(clip)
    }.getOrNull()

    override fun writeClipboard(text: String, userId: Int): String = try {
        clipboard.write(text, userId)
        ""
    } catch (t: Throwable) {
        describe(t)
    }

    // --- 无线调试配对 ------------------------------------------------------
    //
    // 只开、关配对这两步：密钥、配对协议、连接都在主进程里做，这里不碰任何密钥材料。
    // IAdbManager 是隐藏接口，但方法名从 Android 11 起没变过；shell 持有 MANAGE_DEBUGGING。

    private val adbManager: Any by lazy {
        val binder = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java)
            .invoke(null, "adb") as android.os.IBinder
        Class.forName("android.debug.IAdbManager\$Stub")
            .getMethod("asInterface", android.os.IBinder::class.java)
            .invoke(null, binder)!!
    }

    override fun startAdbPairing(serviceName: String, password: String): String = try {
        adbManager.javaClass
            .getMethod("enablePairingByQrCode", String::class.java, String::class.java)
            .invoke(adbManager, serviceName, password)
        ""
    } catch (t: Throwable) {
        describe(t)
    }

    override fun stopAdbPairing() {
        runCatching { adbManager.javaClass.getMethod("disablePairing").invoke(adbManager) }
    }

    /** 反射调用的异常包在 InvocationTargetException 里，取出真正的原因。 */
    private fun describe(t: Throwable): String {
        val cause = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
        return cause.message ?: cause.javaClass.simpleName
    }

    private fun event(
        downTime: Long,
        action: Int,
        keyCode: Int,
        metaState: Int,
        deviceId: Int = KeyCharacterMap.VIRTUAL_KEYBOARD,  // 默认标为虚拟键盘，不冒充任何一把实体键盘
    ) = KeyEvent(
        downTime,
        SystemClock.uptimeMillis(),
        action,
        keyCode,
        0,
        metaState,
        deviceId,
        0,
        KeyEvent.FLAG_FROM_SYSTEM,
        InputDevice.SOURCE_KEYBOARD,
    )

    private fun execInput(keyCode: Int): String {
        val result = exec("input keyevent " + keyCode)
        val newline = result.indexOf('\n')
        val code = result.substring(0, newline.coerceAtLeast(0)).trim().toIntOrNull()
        return if (code == 0) "" else result.substringAfter('\n').trim().ifEmpty { "input keyevent 失败" }
    }

    /**
     * Android 14 把 InputManager 的注入入口挪到了 InputManagerGlobal，
     * 14 以前在 InputManager 自己身上。两条都试，都拿不到就返回 null 走命令兜底。
     */
    private fun resolveInjector(): ((KeyEvent) -> Boolean)? {
        for (className in listOf(
            "android.hardware.input.InputManagerGlobal",
            "android.hardware.input.InputManager",
        )) {
            val resolved = runCatching {
                val type = Class.forName(className)
                val instance = type.getMethod("getInstance").invoke(null)!!
                val method = type.getMethod(
                    "injectInputEvent",
                    InputEvent::class.java,
                    Int::class.javaPrimitiveType,
                )
                val call: (KeyEvent) -> Boolean = { event ->
                    method.invoke(instance, event, MODE_ASYNC) as? Boolean ?: false
                }
                call
            }.getOrNull()
            if (resolved != null) return resolved
        }
        return null
    }
}

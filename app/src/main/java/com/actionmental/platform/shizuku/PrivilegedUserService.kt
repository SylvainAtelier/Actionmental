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
 * 只提供两种受控能力：执行一条命令、注入一次按键。业务逻辑一律不放在这里（PRD 21 · PrivilegedService）。
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

    private fun event(downTime: Long, action: Int, keyCode: Int, metaState: Int) = KeyEvent(
        downTime,
        SystemClock.uptimeMillis(),
        action,
        keyCode,
        0,
        metaState,
        KeyCharacterMap.VIRTUAL_KEYBOARD,  // 明确标为虚拟键盘，不冒充任何一把实体键盘
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

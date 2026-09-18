package com.actionmental.core.diag

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 未捕获异常的落地点。
 *
 * 它比 [EventLog] 先存在：处理器必须在对象图构造之前装好，否则图自己在构造时抛异常
 * 就没有任何人记得下来，而那恰好是「打开就崩、日志全空」的样子。
 *
 * 因此这里不依赖任何东西，只认一个目录：拿到异常就地写盘，再交回系统。
 * [EventLog] 建好之后通过 [attach] 接上，同一条异常会同时进到内存日志里。
 */
object CrashSink {

    private const val FILE_NAME = "events.log"
    private val TIME = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var logDir: File? = null

    @Volatile
    private var sink: ((Thread, Throwable) -> Unit)? = null

    @Volatile
    private var installed = false

    /** 在 Application.onCreate 的最开头调用，[filesDir] 是应用私有目录。 */
    fun install(filesDir: File) {
        logDir = File(filesDir, "logs")
        if (installed) return
        installed = true

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // 先落盘：下一行代码就可能永远执行不到了
            runCatching { writeDirect(thread, error) }
            runCatching { sink?.invoke(thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    /** 对象图建好后把 [EventLog] 接上，让异常也进内存日志。 */
    fun attach(handler: (Thread, Throwable) -> Unit) {
        sink = handler
    }

    /**
     * 记一条**没有**杀掉进程的异常。
     *
     * 给比 [EventLog] 更早出事的地方用：异常已经被拦下，但内存日志还不存在。
     */
    fun recordNonFatal(tag: String, error: Throwable) {
        runCatching {
            append(" ERROR [" + tag + "] 后台任务异常，已拦下 · 线程 " + Thread.currentThread().name, error)
        }
    }

    private fun writeDirect(thread: Thread, error: Throwable) {
        append(" ERROR [crash] 进程因未捕获异常终止 · 线程 " + thread.name, error)
    }

    private fun append(head: String, error: Throwable) {
        val dir = logDir ?: return
        dir.mkdirs()
        val body = EventLog.stackTraceOf(error).replace("\n", "\n    ")
        File(dir, FILE_NAME).appendText(TIME.format(Date()) + head + "\n    " + body + "\n")
    }
}

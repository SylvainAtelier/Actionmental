package com.actionmental.core.diag

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/** 一条事件的严重程度。界面按它上色与过滤。 */
enum class LogLevel { DEBUG, INFO, WARN, ERROR }

data class LogEntry(
    val id: Long,
    val timestampMs: Long,
    val level: LogLevel,
    val tag: String,
    val message: String,
    val detail: String = "",
) {
    fun format(): String {
        val time = TIME.format(Date(timestampMs))
        val head = time + " " + level.name.padEnd(5) + " [" + tag + "] " + message
        return if (detail.isBlank()) head else head + "\n    " + detail.replace("\n", "\n    ")
    }

    private companion object {
        val TIME = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    }
}

/**
 * 全应用的事件日志（进程内存 + 落盘）。
 *
 * 和 [ShellLog]、KeyPipeline 的按键流不同，这一条必须落盘：它要回答的问题是
 * 「服务是什么时候没的、没之前发生了什么」，而那一刻进程往往已经死了 ——
 * 只存在内存里的日志会跟着一起消失，等于什么都没记。
 *
 * 写盘走单独一个协程，按键线程只把条目塞进队列就返回；队列满了丢最旧的，
 * 日志再重要也不能拖慢按键。唯一的例外是 [logBlocking]：未捕获异常处理器里
 * 进程即将终止，那一条必须当场写下去。
 *
 * 不记录任何按键内容（PRD 26）：调用方只传 keyCode 这类技术标识，不传文本。
 */
class EventLog(
    scope: CoroutineScope,
    private val dir: File?,
    private val capacity: Int = 500,
    private val maxFileBytes: Long = 256 * 1024,
    /**
     * 每一条同时交给它（平台层接到 logcat）。
     *
     * 盘上那份在应用私有目录里，release 包用 adb 读不到；现场排查时能直接
     * `adb logcat` 看到的只有这一路。放在构造参数里而不是直接调 android.util.Log，
     * 是为了这个类在 JVM 单测里照样能跑。
     */
    private val mirror: ((LogEntry) -> Unit)? = null,
) {
    private val ids = AtomicLong(0)

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    private val queue = Channel<LogEntry>(256, BufferOverflow.DROP_OLDEST)

    private val file: File? = dir?.let { File(it, "events.log") }
    private val previous: File? = dir?.let { File(it, "events.prev.log") }

    private val _writeError = MutableStateFlow<String?>(null)

    /**
     * 最近一次写盘失败的原因。
     *
     * 写盘失败过去是被静默吞掉的 —— 于是「日志是空的」有两种完全不同的含义：
     * 什么都没发生，或者什么都没记下来。界面必须能把这两者分开。
     */
    val writeError: StateFlow<String?> = _writeError.asStateFlow()

    /**
     * 盘上那一份的写入端，一直开着。
     *
     * 过去每一条日志都是 open → stat → write → close 一整套系统调用。
     * 动作日志在连按时一秒钟就有好几条，而这台设备的死因恰恰是
     * 「后台限制 · 温控」—— 白烧掉的系统调用会直接记在自己账上。
     * 句柄留着之后，一条日志只剩一次 write；轮转需要的长度也自己数着，
     * 不必每条都去 stat 一次文件。
     */
    private val ioLock = Any()
    private var sink: OutputStream? = null
    private var sinkBytes = 0L

    init {
        scope.launch(Dispatchers.IO) {
            dir?.mkdirs()
            val batch = ArrayList<LogEntry>(BATCH)
            for (entry in queue) {
                // 队列里已经排着的一起写，最后只 flush 一次
                batch.add(entry)
                while (batch.size < BATCH) batch.add(queue.tryReceive().getOrNull() ?: break)
                runCatching { writeAll(batch) }
                    .onFailure { _writeError.value = it.message ?: it.javaClass.simpleName }
                batch.clear()
            }
        }
    }

    fun debug(tag: String, message: String, detail: String = "") = log(LogLevel.DEBUG, tag, message, detail)
    fun info(tag: String, message: String, detail: String = "") = log(LogLevel.INFO, tag, message, detail)
    fun warn(tag: String, message: String, detail: String = "") = log(LogLevel.WARN, tag, message, detail)
    fun error(tag: String, message: String, detail: String = "") = log(LogLevel.ERROR, tag, message, detail)

    /** 记一次异常，带完整堆栈。排查崩溃时堆栈是唯一有用的东西，不能只留 message。 */
    fun error(tag: String, message: String, throwable: Throwable) =
        log(LogLevel.ERROR, tag, message, stackTraceOf(throwable))

    fun log(level: LogLevel, tag: String, message: String, detail: String = "") {
        val entry = LogEntry(ids.incrementAndGet(), System.currentTimeMillis(), level, tag, message, detail)
        mirror?.invoke(entry)
        _entries.update { list ->
            val next = list + entry
            if (next.size > capacity) next.takeLast(capacity) else next
        }
        // 错误当场写盘。异步队列要等一次线程调度，而崩溃往往就发生在那之前 ——
        // 最该保住的一条恰好是最容易丢的一条。ERROR 频率极低，这点开销换得起。
        if (level == LogLevel.ERROR) writeNow(entry) else queue.trySend(entry)
    }

    /**
     * 同步写盘。只给未捕获异常处理器用 —— 那之后进程就没了，异步队列来不及消费。
     */
    fun logBlocking(level: LogLevel, tag: String, message: String, detail: String) {
        val entry = LogEntry(ids.incrementAndGet(), System.currentTimeMillis(), level, tag, message, detail)
        mirror?.invoke(entry)
        _entries.update { (it + entry).takeLast(capacity) }
        writeNow(entry)
    }

    private fun writeNow(entry: LogEntry) {
        runCatching { writeAll(listOf(entry)) }
            .onFailure { _writeError.value = it.message ?: it.javaClass.simpleName }
    }

    /** 内存里的这一段。进程重启后为空，历史要用 [readPersisted]。 */
    fun export(): String = buildString {
        appendLine("# Actionmental 事件日志 · " + _entries.value.size + " 条")
        _entries.value.forEach { appendLine(it.format()) }
    }

    /**
     * 盘上的全部日志，含本次进程之前的。
     *
     * 「服务掉线」的现场恰好在上一次进程里，所以导出给开发者的必须是这一份。
     */
    fun readPersisted(): String = buildString {
        // 缓冲里可能还压着几条，读之前先落下去，否则导出的日志缺最后一段
        synchronized(ioLock) { runCatching { sink?.flush() } }
        val old = previous?.takeIf { it.exists() }?.runCatching { readText() }?.getOrNull().orEmpty()
        val now = file?.takeIf { it.exists() }?.runCatching { readText() }?.getOrNull().orEmpty()
        if (old.isNotBlank()) append(old)
        append(now)
    }

    fun clear() {
        _entries.value = emptyList()
        synchronized(ioLock) {
            closeSink()
            runCatching { file?.delete(); previous?.delete() }
        }
    }

    /**
     * 只砍内存里那一份，盘上完整保留。
     *
     * 系统喊内存吃紧时，界面上能往回翻的那几百条是纯粹的奢侈品：排查真正要看的
     * 是 [readPersisted]，而它一个字节都不会少。这里让出的每一 KB 都直接
     * 降低自己在后台限制名单上的位置。
     */
    fun trimMemory(keep: Int = 100) {
        _entries.update { if (it.size > keep) it.takeLast(keep) else it }
    }

    private fun writeAll(entries: List<LogEntry>) {
        val target = file ?: return
        synchronized(ioLock) {
            for (entry in entries) {
                var stream = openSink(target)
                // 轮转：留一份上一轮，够覆盖「这次掉线」和「上次掉线」两个现场，又不会无限长
                if (sinkBytes > maxFileBytes) stream = rotate(target)
                val bytes = (entry.format() + LINE_END).toByteArray(Charsets.UTF_8)
                stream.write(bytes)
                sinkBytes += bytes.size
            }
            // flush 过的这几条就已经在内核里了：进程随时被杀都不会连带丢掉
            sink?.flush()
        }
    }

    private fun openSink(target: File): OutputStream = sink ?: run {
        dir?.mkdirs()
        val stream = BufferedOutputStream(FileOutputStream(target, true))
        sinkBytes = target.length()
        sink = stream
        stream
    }

    private fun rotate(target: File): OutputStream {
        closeSink()
        previous?.let { target.copyTo(it, overwrite = true) }
        target.writeText("")
        return openSink(target)
    }

    private fun closeSink() {
        runCatching {
            sink?.flush()
            sink?.close()
        }
        sink = null
        sinkBytes = 0L
    }

    companion object {
        /** 一次落盘最多合并多少条。够把连按带来的一串动作日志收成一次 flush。 */
        private const val BATCH = 64

        private const val LINE_END = "\n"

        fun stackTraceOf(throwable: Throwable): String = StringWriter().also { sw ->
            PrintWriter(sw).use { throwable.printStackTrace(it) }
        }.toString().trim()
    }
}

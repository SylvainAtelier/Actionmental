package com.actionmental.core.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 一次同步的执行者。
 *
 * 两个设计取舍决定了它的性能与内存：
 *
 * 1. **边列边做，不先建整棵树。** 一次只握着一个目录的两份列表，决定出来的任务丢进有界队列，
 *    三个工人并行传输。十万个文件的目录，内存里只有基线表（本来就要有）和几百个在途任务；
 *    也不用等列完整棵树才发出第一个字节。
 * 2. **没变的文件零开销。** 大多数文件两边一致，决定是纯内存比较，不进队列、不碰网络。
 *
 * 取消是一面旗而不是协程取消：传输是阻塞 IO，本来就停不了，旗在每个数据块之间查一次，
 * 于是停得干净，而且被停的那一刻已经做完的部分仍然会记进基线。
 */
class SyncEngine(
    private val local: SyncSide,
    private val remote: SyncSide,
    private val config: SyncConfig,
    private val baseline: Map<String, BaselineEntry>,
    private val onProgress: (SyncProgress) -> Unit = {},
    private val parallelism: Int = 3,
    private val clock: () -> Long = System::currentTimeMillis,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    class Outcome(val report: SyncReport, val baseline: Map<String, BaselineEntry>)

    private class Task(val path: String, val decision: SyncDecision, val l: FileMeta?, val r: FileMeta?) {
        /** 重试时「改名留底」这一步不能再做一遍：原文件早已换了名字。 */
        var renamed = false
    }

    /** 整次同步没法继续，带着人话原因。 */
    private class Abort(message: String) : RuntimeException(message)

    /** 用户取消，或熔断后其余任务不必再做。 */
    private class Stop : RuntimeException() {
        override fun fillInStackTrace(): Throwable = this
    }

    private val cancelled = AtomicBoolean(false)
    @Volatile private var abortReason: String? = null

    fun cancel() = cancelled.set(true)

    // --- 结果累计 -----------------------------------------------------------
    private val uploaded = AtomicInteger()
    private val downloaded = AtomicInteger()
    private val deletedLocal = AtomicInteger()
    private val deletedRemote = AtomicInteger()
    private val conflicts = AtomicInteger()
    private val unchanged = AtomicInteger()
    private val scanned = AtomicInteger()
    private val bytes = AtomicLong()
    private val failures = java.util.Collections.synchronizedList(ArrayList<SyncFailure>())
    private val failedStreak = AtomicInteger()

    // --- 基线 ----------------------------------------------------------------
    /** 本次**确认过**的路径。走完整棵树才能直接当作新基线，见 [finish]。 */
    private val fresh = ConcurrentHashMap<String, BaselineEntry>()
    private val removed = ConcurrentHashMap.newKeySet<String>()
    private val failedDirs = ConcurrentHashMap.newKeySet<String>()
    private val prune = ConcurrentHashMap.newKeySet<Pair<Boolean, String>>()
    private val readyDirs = ConcurrentHashMap.newKeySet<Pair<Boolean, String>>()

    private val lastEmit = AtomicLong(0)
    private var stamp = ""

    suspend fun run(): Outcome = withContext(dispatcher) {
        val started = clock()
        stamp = STAMP.format(Instant.ofEpochMilli(started).atZone(ZoneId.systemDefault()))
        var walked = false
        try {
            prepareRoots()
            val tasks = Channel<Task>(QUEUE)
            coroutineScope {
                repeat(parallelism) { launch { worker(tasks) } }
                try {
                    walk("", hasL = true, hasR = true, out = tasks)
                    walked = true
                } finally {
                    tasks.close()
                }
            }
            if (abortReason == null && !cancelled.get()) prune()
        } catch (e: Abort) {
            abortReason = e.message
        } catch (_: Stop) {
            // 已经记在 cancelled / abortReason 里
        }
        finish(started, complete = walked && abortReason == null && !cancelled.get() && failedDirs.isEmpty())
    }

    private fun finish(started: Long, complete: Boolean): Outcome {
        val merged: Map<String, BaselineEntry> = if (complete) {
            HashMap(fresh)
        } else {
            // 没走完：没碰到的路径沿用旧记录，碰到了的以这次为准，删掉的就是删掉了。
            // 读不出来的目录整棵沿用 —— 不能因为列不出来就忘了它们曾经同步过。
            val out = HashMap<String, BaselineEntry>(baseline)
            removed.forEach { out.remove(it) }
            out.putAll(fresh)
            out
        }
        return Outcome(
            SyncReport(
                uploaded = uploaded.get(),
                downloaded = downloaded.get(),
                deletedLocal = deletedLocal.get(),
                deletedRemote = deletedRemote.get(),
                conflicts = conflicts.get(),
                unchanged = unchanged.get(),
                bytes = bytes.get(),
                failures = synchronized(failures) { failures.toList() },
                durationMs = (clock() - started).coerceAtLeast(0),
                aborted = abortReason,
                cancelled = cancelled.get(),
            ),
            merged,
        )
    }

    // --- 起点 ----------------------------------------------------------------

    /**
     * 动手之前先把两个根立住，并拦下「源端是空的却要同步删除」。
     *
     * 本地根空了多半是没有读取权限或存储没挂好，NAS 根空了多半是共享没挂上 ——
     * 这时候照章办事就是把对面清空。真有人把整个目录清空了，关掉「同步删除」跑一次即可。
     */
    private fun prepareRoots() {
        when (config.direction) {
            SyncDirection.UPLOAD -> {
                if (!local.exists("")) throw Abort("本地文件夹不存在：" + config.localPath)
                if (!remote.exists("")) remote.mkdirs("")
            }
            SyncDirection.DOWNLOAD -> {
                if (!remote.exists("")) throw Abort("NAS 上的目录不存在：" + config.remoteLabel)
                if (!local.exists("")) local.mkdirs("")
            }
            SyncDirection.BOTH -> {
                if (!local.exists("")) local.mkdirs("")
                if (!remote.exists("")) remote.mkdirs("")
            }
        }
    }

    private fun guardEmptyRoot(l: List<SyncEntry>, r: List<SyncEntry>) {
        if (!config.deletes || baseline.isEmpty()) return
        val localEmpty = l.none { !SyncPlanner.ignored(it.name) }
        val remoteEmpty = r.none { !SyncPlanner.ignored(it.name) }
        val risky = when (config.direction) {
            SyncDirection.UPLOAD -> localEmpty
            SyncDirection.DOWNLOAD -> remoteEmpty
            SyncDirection.BOTH -> localEmpty || remoteEmpty
        }
        if (risky) {
            throw Abort(
                (if (localEmpty) "本地文件夹是空的" else "NAS 目录是空的") +
                    "，但之前同步过文件 · 为防误删已停止。确认要清空的话，先关掉「同步删除」再同步一次"
            )
        }
    }

    // --- 遍历：生产者 ----------------------------------------------------------

    private suspend fun walk(dir: String, hasL: Boolean, hasR: Boolean, out: Channel<Task>) {
        stopIfNeeded()
        val lists = try {
            coroutineScope {
                val a = async { if (hasL) local.list(dir) else emptyList() }
                val b = async { if (hasR) remote.list(dir) else emptyList() }
                a.await() to b.await()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 列不出来 ≠ 空：这个目录这次不做任何判断，尤其不做删除
            failedDirs.add(dir)
            fail(dir.ifEmpty { "/" }, e)
            if (dir.isEmpty()) throw Abort("读不了根目录 · " + describe(e))
            return
        }
        if (dir.isEmpty()) guardEmptyRoot(lists.first, lists.second)

        val lm = lists.first.filterNot { SyncPlanner.ignored(it.name) }.associateBy { it.name }
        val rm = lists.second.filterNot { SyncPlanner.ignored(it.name) }.associateBy { it.name }
        val names = LinkedHashSet<String>(lm.size + rm.size).apply { addAll(lm.keys); addAll(rm.keys) }

        for (name in names) {
            stopIfNeeded()
            val le = lm[name]
            val re = rm[name]
            val path = if (dir.isEmpty()) name else "$dir/$name"
            if (scanned.incrementAndGet() % SCAN_EMIT == 0) emit(path, force = false)

            if (le?.isDir == true || re?.isDir == true) {
                if ((le != null && !le.isDir) || (re != null && !re.isDir)) {
                    fail(path, IOException("一边是文件、一边是目录"))
                    continue
                }
                // 单向且不删除时，目的端独有的目录里不会有任何要做的事，不必进去列
                val pointless = !config.deletes &&
                    ((config.direction == SyncDirection.UPLOAD && le == null) ||
                        (config.direction == SyncDirection.DOWNLOAD && re == null))
                if (!pointless) walk(path, le != null, re != null, out)
                continue
            }

            var decision = SyncPlanner.decide(le?.meta, re?.meta, baseline[path], config)
            // 冲突副本是我们为了不丢数据留下的，同步删除不该把它当成「多余的文件」清掉
            if ((decision.act == SyncAct.DELETE_LOCAL || decision.act == SyncAct.DELETE_REMOTE) &&
                CONFLICT_COPY.containsMatchIn(name)
            ) {
                decision = SyncDecision(SyncAct.NONE)
            }

            when (decision.act) {
                SyncAct.NONE -> {
                    if (le != null && re != null) {
                        fresh[path] = BaselineEntry(le.meta, re.meta)
                        unchanged.incrementAndGet()
                    } else {
                        baseline[path]?.let { fresh[path] = it }
                    }
                }
                SyncAct.CONFLICT -> {
                    conflicts.incrementAndGet()
                    baseline[path]?.let { fresh[path] = it }
                }
                else -> out.send(Task(path, decision, le?.meta, re?.meta))
            }
        }
    }

    private fun stopIfNeeded() {
        if (cancelled.get() || abortReason != null) throw Stop()
    }

    // --- 工人：消费者 ----------------------------------------------------------

    private suspend fun worker(tasks: Channel<Task>) {
        val buf = ByteArray(BUFFER)
        for (task in tasks) {
            if (cancelled.get() || abortReason != null) continue // 排空队列，不再动手
            try {
                withRetry { execute(task, buf) }
                failedStreak.set(0)
            } catch (_: Stop) {
                // 取消：当前文件没做完，不记账
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                baseline[task.path]?.let { fresh[task.path] = it }
                fail(task.path, e)
                // 连着失败多半是网络断了，一个个耗完超时只会拖很久
                if (failedStreak.incrementAndGet() >= MAX_STREAK) {
                    abortReason = "连续失败 " + MAX_STREAK + " 次 · 连接可能已断开，已停止"
                }
            }
            emit(task.path, force = true)
        }
    }

    private inline fun <T> withRetry(block: () -> T): T {
        try {
            return block()
        } catch (e: IOException) {
            if (cancelled.get()) throw Stop()
            Thread.sleep(RETRY_DELAY_MS)
            return block()
        }
    }

    private fun execute(task: Task, buf: ByteArray) {
        val path = task.path
        val l = task.l
        val r = task.r
        when (task.decision.act) {
            SyncAct.UPLOAD -> {
                if (task.decision.backupDest && !task.renamed) {
                    backup(remote, path)
                    task.renamed = true
                }
                transfer(local, remote, path, l!!, buf)
                val dst = remote.stat(path) ?: throw IOException("上传后在 NAS 上找不到这个文件")
                ensureUnchanged(local, path, l)
                fresh[path] = BaselineEntry(l, dst)
                uploaded.incrementAndGet()
            }
            SyncAct.DOWNLOAD -> {
                if (task.decision.backupDest && !task.renamed) {
                    backup(local, path)
                    task.renamed = true
                }
                transfer(remote, local, path, r!!, buf)
                val dst = local.stat(path) ?: throw IOException("下载后在本地找不到这个文件")
                ensureUnchanged(remote, path, r)
                fresh[path] = BaselineEntry(dst, r)
                downloaded.incrementAndGet()
            }
            SyncAct.KEEP_BOTH -> keepBoth(task, l!!, r!!, buf)
            SyncAct.DELETE_LOCAL -> {
                local.delete(path)
                removed.add(path)
                fresh.remove(path)
                prune.add(true to parentOf(path))
                deletedLocal.incrementAndGet()
            }
            SyncAct.DELETE_REMOTE -> {
                remote.delete(path)
                removed.add(path)
                fresh.remove(path)
                prune.add(false to parentOf(path))
                deletedRemote.incrementAndGet()
            }
            SyncAct.NONE, SyncAct.CONFLICT -> Unit
        }
    }

    /** 覆盖之前把目的端现有的文件改名留着：这就是「两份都留」。 */
    private fun backup(side: SyncSide, path: String) {
        side.rename(path, SyncPlanner.conflictName(path, stamp))
    }

    /**
     * 双向且两边都改过：远端的版本改名成冲突副本，拉回本地；本地的版本传上去占回原名；
     * 副本也传上去 —— 做完两边都有两份，谁的改动都没丢。
     */
    private fun keepBoth(task: Task, l: FileMeta, r: FileMeta, buf: ByteArray) {
        val path = task.path
        val copy = SyncPlanner.conflictName(path, stamp)
        if (!task.renamed) {
            remote.rename(path, copy)
            task.renamed = true
        }
        transfer(remote, local, copy, r, buf)
        transfer(local, remote, path, l, buf)
        val rNew = remote.stat(path) ?: throw IOException("上传后在 NAS 上找不到这个文件")
        val copyLocal = local.stat(copy) ?: throw IOException("下载后在本地找不到冲突副本")
        val copyRemote = remote.stat(copy) ?: throw IOException("NAS 上找不到冲突副本")
        ensureUnchanged(local, path, l)
        fresh[path] = BaselineEntry(l, rNew)
        fresh[copy] = BaselineEntry(copyLocal, copyRemote)
        conflicts.incrementAndGet()
        uploaded.incrementAndGet()
        downloaded.incrementAndGet()
    }

    private fun ensureUnchanged(side: SyncSide, path: String, expected: FileMeta) {
        val now = side.stat(path)
        if (now == null || !SyncPlanner.same(now, expected)) {
            throw IOException("传输期间文件被改动，下次同步会重新处理")
        }
    }

    private fun transfer(
        from: SyncSide,
        to: SyncSide,
        path: String,
        meta: FileMeta,
        buf: ByteArray,
    ) {
        val toLocal = to === local
        val parent = parentOf(path)
        if (readyDirs.add(toLocal to parent)) {
            try {
                to.mkdirs(parent)
            } catch (e: Exception) {
                readyDirs.remove(toLocal to parent)
                throw e
            }
        }
        from.open(path).use { input ->
            to.create(path).use { sink ->
                while (true) {
                    if (cancelled.get() || abortReason != null) throw Stop()
                    val n = input.read(buf)
                    if (n < 0) break
                    sink.write(buf, 0, n)
                    bytes.addAndGet(n.toLong())
                    emit(path, force = false)
                }
                sink.commit(meta.mtime)
            }
        }
    }

    // --- 收尾 ----------------------------------------------------------------

    /** 删文件之后留下的空目录一并收掉，从最深的开始往上。 */
    private fun prune() {
        val dirs = HashSet<Pair<Boolean, String>>()
        for ((isLocal, start) in prune) {
            var dir = start
            while (dir.isNotEmpty()) {
                dirs.add(isLocal to dir)
                dir = parentOf(dir)
            }
        }
        for ((isLocal, dir) in dirs.sortedByDescending { it.second.length }) {
            runCatching { (if (isLocal) local else remote).rmdir(dir) }
        }
    }

    // --- 杂项 ----------------------------------------------------------------

    private fun fail(path: String, e: Throwable) {
        failures.add(SyncFailure(path, describe(e)))
    }

    private fun describe(e: Throwable): String =
        (e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName).take(160)

    /** 进度最多每 [EMIT_INTERVAL_MS] 报一次；一个文件做完的那一下强制报，计数不能漏。 */
    private fun emit(current: String, force: Boolean) {
        val now = System.nanoTime() / 1_000_000
        val last = lastEmit.get()
        if (!force && now - last < EMIT_INTERVAL_MS) return
        if (!lastEmit.compareAndSet(last, now) && !force) return
        onProgress(
            SyncProgress(
                done = uploaded.get() + downloaded.get() + deletedLocal.get() + deletedRemote.get(),
                bytes = bytes.get(),
                scanned = scanned.get(),
                current = current,
                phase = SyncProgress.Phase.RUNNING,
            )
        )
    }

    private fun parentOf(path: String): String = path.substringBeforeLast('/', "")

    companion object {
        private const val QUEUE = 128
        private const val SCAN_EMIT = 256
        private const val BUFFER = 256 * 1024
        private const val MAX_STREAK = 12
        private const val RETRY_DELAY_MS = 400L
        private const val EMIT_INTERVAL_MS = 400L
        private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
        private val CONFLICT_COPY = Regex(""" \(冲突 \d{8}-\d{6}\)""")
    }
}

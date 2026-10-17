package com.actionmental.core.sync

import java.io.Closeable
import java.io.InputStream

/**
 * 同步的一端：本地目录，或者 NAS 上的一个共享目录。
 *
 * 引擎只认这个接口，所以规划、冲突、删除、基线这些真正容易出错的逻辑
 * 可以拿内存里的假实现在 JVM 上测个遍，不需要手机，也不需要 NAS。
 *
 * 所有路径都相对于这一端的根，用 `/` 分隔，根本身是空串。
 * 方法都是阻塞的，由引擎在 IO 线程上调用；失败一律抛异常，**不要**把失败伪装成「空」——
 * 列目录失败被当成空目录，就会被解读成「对面把文件全删了」。
 */
interface SyncSide {

    fun exists(dir: String): Boolean

    /** 逐级创建，已经存在就什么都不做。 */
    fun mkdirs(dir: String)

    fun list(dir: String): List<SyncEntry>

    fun stat(path: String): FileMeta?

    fun open(path: String): InputStream

    /** 先写到临时文件，[SyncSink.commit] 才换上正式名字；中途出错或没提交就收拾干净。 */
    fun create(path: String): SyncSink

    fun delete(path: String)

    fun rename(from: String, to: String)

    /** 只删空目录；非空或失败返回 false，不抛异常。 */
    fun rmdir(dir: String): Boolean
}

interface SyncSink : Closeable {
    fun write(buf: ByteArray, off: Int, len: Int)

    /** 写完了：把修改时间设成 [mtime]，再原子地换成正式文件。 */
    fun commit(mtime: Long)
}

package com.actionmental.platform.sync

import com.actionmental.core.sync.FileMeta
import com.actionmental.core.sync.SyncEntry
import com.actionmental.core.sync.SyncPlanner
import com.actionmental.core.sync.SyncSide
import com.actionmental.core.sync.SyncSink
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes

/**
 * 手机共享存储里的一个目录。直接走 java.io / NIO，不经 SAF。
 *
 * 要读写 `/storage/emulated/0` 下任意目录需要「所有文件访问」权限，这是单独一次授权；
 * 换来的是：不用逐个文档去 query ContentResolver（每个文件一次跨进程调用），
 * 也能设置修改时间 —— 后者是靠元数据判断「有没有变」的前提。
 */
class LocalSide(private val root: File) : SyncSide {

    private fun file(path: String): File = if (path.isEmpty()) root else File(root, path)

    override fun exists(dir: String): Boolean = file(dir).isDirectory

    override fun mkdirs(dir: String) {
        val f = file(dir)
        if (!f.isDirectory && !f.mkdirs() && !f.isDirectory) throw IOException("无法创建本地目录：" + dir)
    }

    /**
     * 一次 lstat 拿全类型、大小、时间。`File.isDirectory()` + `length()` + `lastModified()` 是三次系统调用，
     * 在共享存储的 FUSE 层上每一次都要绕一圈，几万个文件下差距是整数倍。
     * 符号链接一律跳过：既避免循环，也避免把目录外的东西同步出去。
     */
    override fun list(dir: String): List<SyncEntry> {
        val out = ArrayList<SyncEntry>()
        try {
            Files.newDirectoryStream(file(dir).toPath()).use { stream ->
                for (p in stream) {
                    val a = try {
                        Files.readAttributes(p, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                    } catch (_: IOException) {
                        continue // 列的当口被删了
                    }
                    if (a.isSymbolicLink || a.isOther) continue
                    out.add(
                        SyncEntry(
                            name = p.fileName.toString(),
                            isDir = a.isDirectory,
                            size = if (a.isDirectory) 0 else a.size(),
                            mtime = a.lastModifiedTime().toMillis(),
                        )
                    )
                }
            }
        } catch (e: IOException) {
            throw IOException("无法读取本地目录 " + dir.ifEmpty { "/" } + " · " + (e.message ?: e.javaClass.simpleName), e)
        }
        return out
    }

    override fun stat(path: String): FileMeta? = try {
        val a = Files.readAttributes(file(path).toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        if (a.isRegularFile) FileMeta(a.size(), a.lastModifiedTime().toMillis()) else null
    } catch (_: IOException) {
        null
    }

    override fun open(path: String): InputStream = FileInputStream(file(path))

    override fun create(path: String): SyncSink {
        val target = file(path)
        val part = File(target.parentFile, target.name + SyncPlanner.PART_SUFFIX)
        val out = FileOutputStream(part)
        return object : SyncSink {
            private var committed = false

            override fun write(buf: ByteArray, off: Int, len: Int) = out.write(buf, off, len)

            override fun commit(mtime: Long) {
                out.close()
                part.setLastModified(mtime)
                // 同一目录内的 rename 是原子的，且会替换已有文件：任何时刻看到的都是旧版或新版，不是半个
                Files.move(part.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
                committed = true
            }

            override fun close() {
                if (committed) return
                runCatching { out.close() }
                part.delete()
            }
        }
    }

    override fun delete(path: String) {
        val f = file(path)
        if (!f.delete() && f.exists()) throw IOException("无法删除本地文件：" + path)
    }

    override fun rename(from: String, to: String) {
        Files.move(file(from).toPath(), file(to).toPath())
    }

    override fun rmdir(dir: String): Boolean {
        val f = file(dir)
        return f.isDirectory && f.delete()
    }
}

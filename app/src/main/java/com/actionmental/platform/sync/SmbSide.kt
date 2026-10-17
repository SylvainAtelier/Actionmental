package com.actionmental.platform.sync

import com.actionmental.core.sync.FileMeta
import com.actionmental.core.sync.SyncEntry
import com.actionmental.core.sync.SyncPlanner
import com.actionmental.core.sync.SyncSide
import com.actionmental.core.sync.SyncSink
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msdtyp.FileTime
import com.hierynomus.mserref.NtStatus
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileBasicInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.smbj.share.DiskShare
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.EnumSet

/**
 * NAS 上一个共享里的子目录。多个工人线程共用同一个 [DiskShare]：
 * SMB2 的一条连接本来就能并发多个请求，不需要为每个线程各开一条。
 */
class SmbSide(private val share: DiskShare, root: String) : SyncSide {

    /** 共享内的根，反斜杠分隔，没有首尾分隔符。 */
    private val base = root.replace('/', '\\').trim('\\')

    private fun smb(path: String): String {
        val p = path.replace('/', '\\')
        return when {
            base.isEmpty() -> p
            p.isEmpty() -> base
            else -> base + "\\" + p
        }
    }

    override fun exists(dir: String): Boolean {
        val p = smb(dir)
        return p.isEmpty() || share.folderExists(p)
    }

    override fun mkdirs(dir: String) {
        val full = smb(dir)
        if (full.isEmpty()) return
        var current = ""
        for (segment in full.split('\\')) {
            current = if (current.isEmpty()) segment else current + "\\" + segment
            if (share.folderExists(current)) continue
            try {
                share.mkdir(current)
            } catch (e: SMBApiException) {
                // 另一个工人刚好抢先建了
                if (e.status != NtStatus.STATUS_OBJECT_NAME_COLLISION && !share.folderExists(current)) throw e
            }
        }
    }

    override fun list(dir: String): List<SyncEntry> {
        val out = ArrayList<SyncEntry>()
        for (info in share.list(smb(dir))) {
            val name = info.fileName
            if (name == "." || name == "..") continue
            val attrs = info.fileAttributes
            if (attrs and FileAttributes.FILE_ATTRIBUTE_REPARSE_POINT.value != 0L) continue
            val isDir = attrs and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L
            out.add(
                SyncEntry(
                    name = name,
                    isDir = isDir,
                    size = if (isDir) 0 else info.endOfFile,
                    mtime = info.lastWriteTime.toEpochMillis(),
                )
            )
        }
        return out
    }

    override fun stat(path: String): FileMeta? = try {
        val info = share.getFileInformation(smb(path))
        if (info.basicInformation.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L) null
        else FileMeta(info.standardInformation.endOfFile, info.basicInformation.lastWriteTime.toEpochMillis())
    } catch (e: SMBApiException) {
        if (e.status == NtStatus.STATUS_OBJECT_NAME_NOT_FOUND || e.status == NtStatus.STATUS_OBJECT_PATH_NOT_FOUND) null
        else throw e
    }

    override fun open(path: String): InputStream {
        val file = share.openFile(
            smb(path),
            EnumSet.of(AccessMask.GENERIC_READ),
            EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            EnumSet.noneOf(SMB2CreateOptions::class.java),
        )
        // smbj 的输入流自带预读：连续几个读请求同时在路上，吞吐不被往返延迟卡住
        val stream = try {
            file.inputStream
        } catch (e: Exception) {
            file.close()
            throw e
        }
        return object : InputStream() {
            override fun read(): Int = stream.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int = stream.read(b, off, len)
            override fun close() {
                try {
                    stream.close()
                } finally {
                    file.close()
                }
            }
        }
    }

    override fun create(path: String): SyncSink {
        val target = smb(path)
        val part = target + SyncPlanner.PART_SUFFIX
        val file = share.openFile(
            part,
            // DELETE：提交时要用同一个句柄改名，失败时要用它删掉半成品
            EnumSet.of(AccessMask.GENERIC_WRITE, AccessMask.DELETE),
            EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OVERWRITE_IF,
            EnumSet.noneOf(SMB2CreateOptions::class.java),
        )
        val out: OutputStream = try {
            file.outputStream
        } catch (e: Exception) {
            file.close()
            throw e
        }
        return object : SyncSink {
            private var committed = false

            override fun write(buf: ByteArray, off: Int, len: Int) = out.write(buf, off, len)

            override fun commit(mtime: Long) {
                out.close()
                // 先设时间再改名：时间设在句柄上，句柄关闭时服务端不会再把它刷成「现在」
                file.setFileInformation(
                    FileBasicInformation(
                        FileBasicInformation.DONT_SET,
                        FileBasicInformation.DONT_SET,
                        FileTime.ofEpochMillis(mtime),
                        FileBasicInformation.DONT_SET,
                        0L,
                    )
                )
                file.rename(target, true)
                committed = true
                file.close()
            }

            override fun close() {
                if (committed) return
                runCatching { out.close() }
                runCatching { file.deleteOnClose() }
                runCatching { file.close() }
            }
        }
    }

    override fun delete(path: String) {
        share.rm(smb(path))
    }

    override fun rename(from: String, to: String) {
        val entry = share.open(
            smb(from),
            EnumSet.of(AccessMask.DELETE),
            EnumSet.noneOf(FileAttributes::class.java),
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            EnumSet.noneOf(SMB2CreateOptions::class.java),
        )
        try {
            entry.rename(smb(to), false)
        } finally {
            entry.close()
        }
    }

    override fun rmdir(dir: String): Boolean = try {
        share.rmdir(smb(dir), false)
        true
    } catch (_: SMBApiException) {
        false
    } catch (_: IOException) {
        false
    }
}

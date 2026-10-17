package com.actionmental.core.sync

import java.io.File

/**
 * 基线的落盘格式：一行一个文件，制表符分隔。
 *
 * 不用 JSON：几万行的 JSON 要整个读进内存再解析，这里逐行读逐行建表；
 * 也不用 DataStore，它为小而稀的偏好设计，不该装这种体量的数据。
 */
object SyncBaseline {
    private const val HEADER = "amsync1"

    private fun header(signature: String) = HEADER + " " + signature

    /**
     * 基线只对「这一对目录」有效：[signature] 变了（换了本地目录、换了 NAS 目录）就当没有。
     * 否则换目录之后，旧记录会把新目录里本来就没有的文件解读成「被删掉了」，然后去删对面的。
     */
    fun signature(config: SyncConfig): String =
        listOf(config.localPath, config.host, config.port.toString(), config.share, config.remoteRoot)
            .joinToString("|").hashCode().toString(16)

    fun load(file: File, signature: String): Map<String, BaselineEntry> {
        if (!file.isFile) return emptyMap()
        val out = HashMap<String, BaselineEntry>()
        file.bufferedReader().use { reader ->
            if (reader.readLine() != header(signature)) return emptyMap()
            reader.forEachLine { line ->
                val f = line.split('\t')
                if (f.size != 5) return@forEachLine
                val ls = f[1].toLongOrNull() ?: return@forEachLine
                val lm = f[2].toLongOrNull() ?: return@forEachLine
                val rs = f[3].toLongOrNull() ?: return@forEachLine
                val rm = f[4].toLongOrNull() ?: return@forEachLine
                out[unescape(f[0])] = BaselineEntry(FileMeta(ls, lm), FileMeta(rs, rm))
            }
        }
        return out
    }

    /** 先写临时文件再换名：写到一半断电，留下的仍是上一份完整的基线。 */
    fun save(file: File, signature: String, entries: Map<String, BaselineEntry>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.bufferedWriter().use { w ->
            w.write(header(signature))
            w.write("\n")
            for ((path, e) in entries) {
                w.write(escape(path))
                w.write("\t"); w.write(e.local.size.toString())
                w.write("\t"); w.write(e.local.mtime.toString())
                w.write("\t"); w.write(e.remote.size.toString())
                w.write("\t"); w.write(e.remote.mtime.toString())
                w.write("\n")
            }
        }
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) throw java.io.IOException("基线写入失败")
        }
    }

    private const val BS = '\\'

    private fun escape(s: String): String {
        if (s.none { it == BS || it == '\t' || it == '\n' || it == '\r' }) return s
        return buildString(s.length + 4) {
            for (c in s) when (c) {
                BS -> append(BS).append(BS)
                '\t' -> append(BS).append('t')
                '\n' -> append(BS).append('n')
                '\r' -> append(BS).append('r')
                else -> append(c)
            }
        }
    }

    private fun unescape(s: String): String {
        if (BS !in s) return s
        return buildString(s.length) {
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == BS && i + 1 < s.length) {
                    when (s[i + 1]) {
                        't' -> append('\t')
                        'n' -> append('\n')
                        'r' -> append('\r')
                        else -> append(s[i + 1])
                    }
                    i += 2
                } else {
                    append(c)
                    i++
                }
            }
        }
    }
}

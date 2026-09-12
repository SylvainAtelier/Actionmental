package com.actionmental.platform

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * 把整份配置写进用户自己选的那个文件，以及从那个文件读回来。
 *
 * 走系统的文件选择器（SAF），不是应用私有目录，也不申请存储权限 ——
 * 这两点都是刻意的：
 *
 * - 私有目录跟着卸载一起消失，而「卸载」恰恰是唯一会丢配置的时刻，
 *   备份在那里等于没有备份；
 * - SAF 给的是一个一次性的写入许可，应用从头到尾拿不到任何别的文件，
 *   隐私那一节「不申请存储权限」的承诺因此仍然成立。
 *
 * 剪贴板那条路留着不动：换机时它最快。但剪贴板会被下一次复制冲掉，也过不了
 * 重启，所以「备份」这件事只能靠文件。
 */
class ConfigBackupFile(private val context: Context) {

    /**
     * @return 写进去了多少字节。
     */
    suspend fun write(uri: Uri, content: String): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = content.toByteArray()
            // "wt" 里的 t 是截断。
            //
            // 不截断时，往一个已经存在的备份里写一份更短的 JSON 会在末尾留下上一份的
            // 尾巴 —— 那种文件读回来必定解析失败，而失败的样子像是「导出坏了」。
            // 用户第二次往同一个文件上覆盖备份是最常见的用法，所以这个 t 不能省。
            val stream = context.contentResolver.openOutputStream(uri, "wt")
                ?: error("系统没有给出可写的输出流")
            stream.use {
                it.write(bytes)
                it.flush()
            }
            bytes.size
        }
    }

    suspend fun read(uri: Uri): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val stream = context.contentResolver.openInputStream(uri)
                ?: error("系统没有给出可读的输入流")
            val text = stream.use { it.readBytes().toString(Charsets.UTF_8) }
            if (text.isBlank()) error("这个文件是空的")
            text
        }
    }

    /**
     * 文件选择器里预填的名字。
     *
     * 带上日期和时间：备份这件事天然会做很多次，而同名文件在多数文件管理器里会变成
     * `actionmental-backup(1).json` 这种 —— 事后没人分得清哪一个是新的。
     * 不用 SimpleDateFormat 是为了避开它对 Locale 的依赖：某些区域设置下会写出
     * 非公历的年份，那种文件名排起序来是乱的。
     */
    fun suggestedName(nowMs: Long = System.currentTimeMillis()): String {
        val at = Calendar.getInstance().apply { timeInMillis = nowMs }
        fun pad(value: Int) = if (value < 10) "0" + value else value.toString()
        return "actionmental-" + at.get(Calendar.YEAR) +
            pad(at.get(Calendar.MONTH) + 1) +
            pad(at.get(Calendar.DAY_OF_MONTH)) + "-" +
            pad(at.get(Calendar.HOUR_OF_DAY)) +
            pad(at.get(Calendar.MINUTE)) + ".json"
    }
}

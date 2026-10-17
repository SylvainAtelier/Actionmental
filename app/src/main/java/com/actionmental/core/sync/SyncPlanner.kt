package com.actionmental.core.sync

import kotlin.math.abs

/**
 * 同步决策。纯函数，不碰文件系统，所以每一条分支都能单测。
 *
 * 判断「有没有变」只看大小和修改时间，不读内容：几万个文件的目录靠元数据比一遍是秒级，
 * 读一遍内容是分钟级，还要把每个文件从 NAS 拉下来。代价是修改时间必须可信 ——
 * 所以传输完成后总会把目的端的修改时间设成源端的，见 SyncEngine。
 */
object SyncPlanner {

    /** FAT / exFAT 的时间精度是 2 秒，SMB 服务端也常按秒存：小于它的差异不算变化。 */
    const val MTIME_TOLERANCE_MS = 2_000L

    fun same(a: FileMeta, b: FileMeta): Boolean =
        a.size == b.size && abs(a.mtime - b.mtime) <= MTIME_TOLERANCE_MS

    /**
     * @param l 本地此刻的样子，没有就是 null
     * @param r 远端此刻的样子
     * @param b 上一次成功同步时的样子，从没同步过就是 null
     */
    fun decide(l: FileMeta?, r: FileMeta?, b: BaselineEntry?, config: SyncConfig): SyncDecision {
        if (l == null && r == null) return NONE

        if (l != null && r != null) {
            // 两边各自都和上次一样，就是没动过 —— 即使两边的修改时间互相对不上
            if (b != null && same(l, b.local) && same(r, b.remote)) return NONE
            if (same(l, r)) return NONE
        }

        val localChanged = l != null && (b == null || !same(l, b.local))
        val remoteChanged = r != null && (b == null || !same(r, b.remote))
        val synced = b != null

        return when (config.direction) {
            SyncDirection.UPLOAD -> oneWay(l, r, remoteChanged, config, toRemote = true)
            SyncDirection.DOWNLOAD -> oneWay(r, l, localChanged, config, toRemote = false)
            SyncDirection.BOTH -> twoWay(l, r, b, localChanged, remoteChanged, synced, config)
        }
    }

    /**
     * 单向：[src] 是权威，[dst] 要跟上它。
     *
     * 目的端自上次以来没人动过，就是纯粹的更新，直接覆盖，不去问冲突策略 ——
     * 否则「两份都留」会在每一次正常更新时都造出一份副本。
     */
    private fun oneWay(
        src: FileMeta?,
        dst: FileMeta?,
        dstChanged: Boolean,
        config: SyncConfig,
        toRemote: Boolean,
    ): SyncDecision {
        val push = if (toRemote) SyncAct.UPLOAD else SyncAct.DOWNLOAD
        if (src == null) {
            // 目的端多出来的文件：只有明确选了「同步删除」才动它
            val delete = if (toRemote) SyncAct.DELETE_REMOTE else SyncAct.DELETE_LOCAL
            return if (config.deletes) SyncDecision(delete) else NONE
        }
        if (dst == null || !dstChanged) return SyncDecision(push)

        return when (config.effectiveConflict) {
            SyncConflict.OVERWRITE -> SyncDecision(push)
            SyncConflict.NEWER ->
                if (src.mtime > dst.mtime + MTIME_TOLERANCE_MS) SyncDecision(push) else CONFLICT
            SyncConflict.KEEP_BOTH -> SyncDecision(push, backupDest = true)
            SyncConflict.SKIP -> CONFLICT
        }
    }

    private fun twoWay(
        l: FileMeta?,
        r: FileMeta?,
        b: BaselineEntry?,
        localChanged: Boolean,
        remoteChanged: Boolean,
        synced: Boolean,
        config: SyncConfig,
    ): SyncDecision {
        if (l != null && r != null) {
            return when {
                localChanged && !remoteChanged -> SyncDecision(SyncAct.UPLOAD)
                remoteChanged && !localChanged -> SyncDecision(SyncAct.DOWNLOAD)
                // 两边都动过（或从没同步过而内容不同）：这才是真冲突
                else -> when (config.effectiveConflict) {
                    SyncConflict.KEEP_BOTH -> SyncDecision(SyncAct.KEEP_BOTH)
                    SyncConflict.SKIP -> CONFLICT
                    // 平手归本地：手上这台设备的版本不该被一个说不清的平局覆盖掉
                    else -> if (r.mtime > l.mtime + MTIME_TOLERANCE_MS) SyncDecision(SyncAct.DOWNLOAD)
                    else SyncDecision(SyncAct.UPLOAD)
                }
            }
        }

        // 只有一边有。上次同步过就说明另一边是被删掉的，否则是新增的。
        if (l != null) {
            if (synced && config.deletes && !localChanged) return SyncDecision(SyncAct.DELETE_LOCAL)
            // 删除之后本地又改过：改动比删除值钱，当成新文件传回去
            return SyncDecision(SyncAct.UPLOAD)
        }
        if (synced && config.deletes && !remoteChanged) return SyncDecision(SyncAct.DELETE_REMOTE)
        return SyncDecision(SyncAct.DOWNLOAD)
    }

    private val NONE = SyncDecision(SyncAct.NONE)
    private val CONFLICT = SyncDecision(SyncAct.CONFLICT)

    /** 不参与同步的名字：我们自己的半成品，以及 NAS 自己的内部目录（回收站、缩略图、快照）。 */
    fun ignored(name: String): Boolean =
        name.endsWith(PART_SUFFIX) || name.startsWith("@") || name.startsWith(".@")

    const val PART_SUFFIX = ".amsync.part"

    /** 冲突副本的名字：`a.txt` → `a (冲突 20261018-153012).txt`。 */
    fun conflictName(path: String, stamp: String): String {
        val slash = path.lastIndexOf('/')
        val dir = if (slash >= 0) path.substring(0, slash + 1) else ""
        val name = path.substring(slash + 1)
        val dot = name.lastIndexOf('.')
        return if (dot > 0) dir + name.substring(0, dot) + " (冲突 " + stamp + ")" + name.substring(dot)
        else dir + name + " (冲突 " + stamp + ")"
    }
}

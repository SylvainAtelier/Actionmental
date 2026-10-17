package com.actionmental.core.sync

import kotlinx.serialization.Serializable

/** 同步方向。 */
@Serializable
enum class SyncDirection(val label: String, val code: String) {
    UPLOAD("上传", "SYNC_UP"),
    DOWNLOAD("下载", "SYNC_DOWN"),
    BOTH("双向", "SYNC_BOTH"),
}

/**
 * 两边都有这个文件、内容又不一样时怎么办。
 *
 * [OVERWRITE] 是「以源端为准」，只在单向时有意义；双向没有源端，引擎按 [NEWER] 处理。
 */
@Serializable
enum class SyncConflict(val label: String) {
    NEWER("较新者优先"),
    OVERWRITE("以源端为准"),
    KEEP_BOTH("两份都留"),
    SKIP("跳过不动"),
}

/**
 * 一条同步任务的全部配置。它随动作一起存进 DataStore、进配置导出，所以**不含密码** ——
 * 密码按 [key] 另存在 Keystore 加密的文件里（见 SyncCredentials）。
 *
 * [key] 在创建时生成、之后不变：基线文件与密码都挂在它上面，
 * 改主机、改目录都不该让「上一次同步到哪了」这份记忆作废。
 */
@Serializable
data class SyncConfig(
    val key: String,
    val localPath: String,
    val host: String,
    val share: String,
    val remotePath: String = "",
    val username: String = "",
    val port: Int = 445,
    val direction: SyncDirection = SyncDirection.UPLOAD,
    val deletes: Boolean = false,
    val conflict: SyncConflict = SyncConflict.NEWER,
    val wifiOnly: Boolean = true,
    val title: String = "",
) {
    val displayName: String
        get() = title.ifBlank { localPath.trimEnd('/').substringAfterLast('/') + " ⇄ " + host }

    /** 远端路径的规范化形式：用 `/` 分隔、不带首尾斜杠。 */
    val remoteRoot: String
        get() = remotePath.replace('\\', '/').trim('/')

    val remoteLabel: String
        get() = "//" + host + "/" + share + (remoteRoot.takeIf { it.isNotEmpty() }?.let { "/$it" } ?: "")

    /** 能不能开始跑：缺哪一项一眼看得出。 */
    val complete: Boolean
        get() = localPath.isNotBlank() && host.isNotBlank() && share.isNotBlank()

    /** 双向没有「源端」，[SyncConflict.OVERWRITE] 在那里等同于 [SyncConflict.NEWER]。 */
    val effectiveConflict: SyncConflict
        get() = if (direction == SyncDirection.BOTH && conflict == SyncConflict.OVERWRITE) SyncConflict.NEWER else conflict
}

/** 一个文件的元数据：不读内容，只靠大小和修改时间判断有没有变。 */
data class FileMeta(val size: Long, val mtime: Long)

/** 目录列表里的一项。 */
data class SyncEntry(val name: String, val isDir: Boolean, val size: Long = 0, val mtime: Long = 0) {
    val meta: FileMeta get() = FileMeta(size, mtime)
}

/**
 * 上一次成功同步时两边各自的样子。
 *
 * 两边分开记：SMB 服务端不一定肯保留我们写进去的修改时间，
 * 只记一份的话，每一次对比都会把「服务端自己改的时间」当成「有人动过」。
 */
data class BaselineEntry(val local: FileMeta, val remote: FileMeta)

/** 引擎对单个路径做出的决定。 */
enum class SyncAct {
    /** 两边一致，或者无事可做。 */
    NONE,
    UPLOAD,
    DOWNLOAD,
    DELETE_LOCAL,
    DELETE_REMOTE,

    /** 两边都改过且策略要求保留两份：远端先改名为冲突副本，再各向对方补齐。 */
    KEEP_BOTH,

    /** 有分歧但策略要求不动：只计数，基线不更新，下次还会再报。 */
    CONFLICT,
}

/**
 * [backupDest] 为真时，覆盖之前先把目的端现有的文件改名成冲突副本（单向的「两份都留」）。
 */
data class SyncDecision(val act: SyncAct, val backupDest: Boolean = false)

data class SyncFailure(val path: String, val reason: String)

/** 一次同步的结果。数字都是**已经完成**的，失败的不计入。 */
data class SyncReport(
    val uploaded: Int = 0,
    val downloaded: Int = 0,
    val deletedLocal: Int = 0,
    val deletedRemote: Int = 0,
    val conflicts: Int = 0,
    val unchanged: Int = 0,
    val bytes: Long = 0,
    val failures: List<SyncFailure> = emptyList(),
    val durationMs: Long = 0,
    /** 整次同步没能进行（连不上、目录不在、保护闸门触发）时的原因。 */
    val aborted: String? = null,
    val cancelled: Boolean = false,
) {
    val changed: Int get() = uploaded + downloaded + deletedLocal + deletedRemote

    val succeeded: Boolean get() = aborted == null && !cancelled && failures.isEmpty()
}

/** 进度快照。总数在边走边列的过程中是未知的，所以只报「已经做了多少」。 */
data class SyncProgress(
    val done: Int = 0,
    val bytes: Long = 0,
    val scanned: Int = 0,
    val current: String = "",
    val phase: Phase = Phase.CONNECTING,
) {
    enum class Phase { CONNECTING, RUNNING, FINISHING }
}

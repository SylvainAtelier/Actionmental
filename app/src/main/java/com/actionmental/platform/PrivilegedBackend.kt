package com.actionmental.platform

import com.actionmental.core.action.ActionResult

/** 一次 shell 调用的结果。 */
data class ShellResult(val exitCode: Int, val output: String) {
    val ok: Boolean get() = exitCode == 0
}

/**
 * 特权能力的统一接口。
 *
 * 上层（Action 执行器、RotationController、磁贴）只认这个接口，
 * 因此 UI 与领域层永远看不到 Shizuku（PRD 35.9）。
 */
interface PrivilegedBackend {

    /** 当前是否可用；不可用时给出原因，用于「不可用」而非「已关闭」的显示。 */
    fun availability(): ActionResult

    suspend fun exec(command: String): Result<ShellResult>

    /**
     * 注入一次按键（按下 + 抬起）。键位映射唯一的输出口。
     *
     * 注入内容就是用户的击键，因此这条路径刻意不进 shell 日志 ——
     * 「不记录输入内容」的承诺高于「每一条特权调用都可查」。
     */
    suspend fun injectKey(keyCode: Int, metaState: Int): Result<Unit>

    /** 只注入半边。整颗替换一颗修饰键时，目标键要真的按住。 */
    suspend fun injectKeyState(keyCode: Int, metaState: Int, down: Boolean): Result<Unit>

    /**
     * 读取一个 settings 值。
     *
     * 「读失败」与「这个键没有值」必须分开：自愈与重绑都是读出整串、改一项、再整串写回。
     * 把读失败当成空串，写回去的就只剩自己这一项 —— 用户其它的无障碍服务被一并抹掉。
     *
     * @return 成功时是值（没有值为 null）；特权服务不可用或命令失败时是 failure。
     */
    suspend fun getSetting(namespace: String, key: String): Result<String?>

    suspend fun putSetting(namespace: String, key: String, value: String): Result<Unit>
}

/** Shizuku 不可用时的占位实现：所有调用都失败，并带明确原因。 */
class UnavailableBackend(private val reason: ActionResult.Reason) : PrivilegedBackend {
    private val failure get() = ActionResult.Failed(reason)
    override fun availability(): ActionResult = failure
    override suspend fun exec(command: String) = Result.failure<ShellResult>(IllegalStateException(reason.message))
    override suspend fun injectKey(keyCode: Int, metaState: Int) =
        Result.failure<Unit>(IllegalStateException(reason.message))
    override suspend fun injectKeyState(keyCode: Int, metaState: Int, down: Boolean) =
        Result.failure<Unit>(IllegalStateException(reason.message))
    override suspend fun getSetting(namespace: String, key: String) =
        Result.failure<String?>(IllegalStateException(reason.message))
    override suspend fun putSetting(namespace: String, key: String, value: String) =
        Result.failure<Unit>(IllegalStateException(reason.message))
}

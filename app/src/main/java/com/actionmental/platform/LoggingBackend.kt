package com.actionmental.platform

import com.actionmental.core.action.ActionResult
import com.actionmental.core.diag.ShellLog

/**
 * 把每一次特权调用记进 [ShellLog] 的装饰器。
 *
 * 放在接口这一层而不是 ShizukuManager 内部，是因为记录属于诊断关注点，
 * 不属于「怎么拿到 shell」；同时也保证**没有任何一条命令能绕过日志**——
 * 上层只认 [PrivilegedBackend]，而图里只提供包装过的实例。
 */
class LoggingBackend(
    private val delegate: PrivilegedBackend,
    private val log: ShellLog,
    private val source: String = "app",
    /**
     * 每发生一次特权调用就叫一声。
     *
     * 逐条写日志的那份记录只有界面在看，而「这一分钟里 fork 了多少个 shell」
     * 是判断发热来源的关键量，得攒着跟体征一起写。所以这里只做一次原子自增。
     */
    private val onCall: () -> Unit = {},
) : PrivilegedBackend {

    /** 换一个来源标签，共用同一个 delegate 与日志。 */
    fun tagged(source: String): LoggingBackend = LoggingBackend(delegate, log, source, onCall)

    override fun availability(): ActionResult = delegate.availability()

    override suspend fun exec(command: String): Result<ShellResult> {
        onCall()
        val startedAt = System.nanoTime()
        val result = delegate.exec(command)
        val elapsed = (System.nanoTime() - startedAt) / 1_000_000
        result.fold(
            onSuccess = { log.record(source, command, it.exitCode, it.output, elapsed) },
            // 连不上特权服务也是一条要看见的记录，用 -1 与真实退出码区分开
            onFailure = { log.record(source, command, -1, it.message.orEmpty(), elapsed) },
        )
        return result
    }

    /** 注入的内容就是用户的击键，记进日志等于记录输入 —— 这一条只透传，不记录。 */
    override suspend fun injectKey(keyCode: Int, metaState: Int): Result<Unit> =
        delegate.injectKey(keyCode, metaState)

    override suspend fun injectKeyState(keyCode: Int, metaState: Int, down: Boolean): Result<Unit> =
        delegate.injectKeyState(keyCode, metaState, down)

    override suspend fun getSetting(namespace: String, key: String): Result<String?> =
        delegate.getSetting(namespace, key)

    override suspend fun putSetting(namespace: String, key: String, value: String): Result<Unit> {
        onCall()
        val startedAt = System.nanoTime()
        val result = delegate.putSetting(namespace, key, value)
        val elapsed = (System.nanoTime() - startedAt) / 1_000_000
        log.record(
            source,
            "settings put " + namespace + " " + key + " " + value,
            if (result.isSuccess) 0 else -1,
            result.exceptionOrNull()?.message.orEmpty(),
            elapsed,
        )
        return result
    }
}

package com.actionmental.core.clip

/**
 * 一段剪贴内容要不要记下来、记成什么。所有来源（特权进程的监听、解锁补读、
 * 应用自己复制的 IP）都从这里过，规则只有一份。
 *
 * 依赖全是函数：开关与排除名单来自设置，来源应用来自无障碍服务看到的前台，
 * 落盘交给存储层 —— 于是这一层在单元测试里不需要设备。
 */
class ClipRecorder(
    private val enabled: () -> Boolean,
    private val excluded: () -> Set<String>,
    private val foreground: () -> String?,
    private val store: suspend (text: String, source: String?) -> Unit,
    /** 自动清理天数，0 为不清理。去重要知道上一条是不是已经过期了。 */
    private val retentionDays: () -> Int = { 0 },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val deduper = ClipDeduper()

    /**
     * @param source 来源应用；不传就取此刻的前台应用 —— 复制总是发生在前台。
     * @return 真的记下了才是 true。
     */
    suspend fun offer(raw: String?, sensitive: Boolean, source: String? = foreground()): Boolean {
        if (!enabled()) return false
        val text = ClipPolicy.accept(raw, sensitive) ?: return false
        if (source != null && source in excluded()) return false
        val now = clock()
        if (deduper.isRepeat(ClipPolicy.hash(text), now, ClipPolicy.expiryCutoff(now, retentionDays()))) return false
        store(text, source)
        return true
    }

    /** 历史被删改过：同样的内容再出现一次应该重新记。 */
    fun forget() = deduper.forget()
}

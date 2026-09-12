package com.actionmental.platform

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 应用与 Activity 的内存目录。
 *
 * 选择器必须瞬开，而 PackageManager 的查询在装了几百个应用的设备上要几百毫秒 ——
 * 所以应用清单在进程启动时就在后台预热一次，界面只读缓存；
 * Activity 清单按包懒加载并缓存，一个包只查一次。
 * 两者都是纯缓存，刷新是显式动作，不做定时失效。
 */
class AppCatalog(
    private val packages: PackageBackend,
    private val scope: CoroutineScope,
    /**
     * 一次清单查询的代价：查到几个包、花了多少毫秒。
     *
     * 这是启动路径与「回到前台」路径上最贵的一步（几百个包，逐条 loadLabel），
     * 而它到底有多贵一直没有人量过 —— 排查发热时，没量过的东西等于不存在。
     */
    private val onLoaded: (count: Int, elapsedMs: Long) -> Unit = { _, _ -> },
) {
    private val _apps = MutableStateFlow<List<PackageBackend.InstalledApp>>(emptyList())
    val apps: StateFlow<List<PackageBackend.InstalledApp>> = _apps.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val activities = mutableMapOf<String, List<PackageBackend.ActivityEntry>>()
    private val activityLock = Mutex()

    /** 进程启动后调用一次。已经有数据就不重复查。 */
    fun warmUp() {
        if (_apps.value.isNotEmpty() || _loading.value) return
        scope.launch { refresh() }
    }

    suspend fun refresh() {
        _loading.value = true
        val startedAt = System.currentTimeMillis()
        try {
            val loaded = withContext(Dispatchers.IO) { packages.launchableApps() }
            _apps.value = loaded
            onLoaded(loaded.size, System.currentTimeMillis() - startedAt)
        } finally {
            _loading.value = false
        }
    }

    /** 某个包的 Activity 清单。首次查询走 IO，之后直接命中缓存。 */
    suspend fun activitiesOf(packageName: String): List<PackageBackend.ActivityEntry> {
        activityLock.withLock { activities[packageName] }?.let { return it }
        val loaded = withContext(Dispatchers.IO) { packages.activitiesOf(packageName) }
        activityLock.withLock { activities[packageName] = loaded }
        return loaded
    }

    fun cachedActivities(packageName: String): List<PackageBackend.ActivityEntry>? = activities[packageName]

    /**
     * 放掉全部缓存。
     *
     * 这些数据只有界面用得上：几百个应用的名字与图标信息、逐个包的 Activity 清单。
     * 界面一关，它们就纯粹是占着不走的内存 —— 而进程还要以无障碍服务的身份长期活着，
     * 后台限制策略挑目标时看的正是这个数字。下次要用时重新查一遍就是了。
     */
    fun release() {
        _apps.value = emptyList()
        scope.launch { activityLock.withLock { activities.clear() } }
    }

    fun invalidate() {
        scope.launch {
            activityLock.withLock { activities.clear() }
            refresh()
        }
    }
}

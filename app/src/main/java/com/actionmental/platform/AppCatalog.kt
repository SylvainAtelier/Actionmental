package com.actionmental.platform

import kotlinx.coroutines.CancellationException
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
 * 应用清单只在应用选择器打开时才查（[warmUp]），进程启动、界面回到前台都不碰它：
 * 绝大多数时候界面开着也不会去点选择器。应用名有盘上缓存（[LabelCache]），
 * 所以除了第一次，查一遍只剩一次 queryIntentActivities 的代价。
 * Activity 清单按包懒加载并缓存，一个包只查一次。
 * 两者都是纯缓存：装卸应用只标记过期（[markStale]），下次打开选择器时才重查。
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
    private val onFailed: (Throwable) -> Unit = {},
) {
    private val _apps = MutableStateFlow<List<PackageBackend.InstalledApp>>(emptyList())
    val apps: StateFlow<List<PackageBackend.InstalledApp>> = _apps.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val activities = mutableMapOf<String, List<PackageBackend.ActivityEntry>>()
    private val activityLock = Mutex()

    /** 装卸过应用之后手里的清单就不全了，但没人要看时不必当场重查。 */
    @Volatile
    private var stale = false

    /** 应用选择器打开时调用。手里的清单还新鲜就不重复查。 */
    fun warmUp() {
        if ((_apps.value.isNotEmpty() && !stale) || _loading.value) return
        scope.launch { refresh() }
    }

    fun markStale() {
        stale = true
    }

    suspend fun refresh() {
        _loading.value = true
        stale = false
        val startedAt = System.currentTimeMillis()
        try {
            val loaded = withContext(Dispatchers.IO) { packages.launchableApps() }
            _apps.value = loaded
            onLoaded(loaded.size, System.currentTimeMillis() - startedAt)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 冷启动时 PackageManager 查几百个包可能直接抛出来。清单只是选择器的缓存，
            // 查不到就留着旧的，下次打开选择器 warmUp 会再试 —— 不能为它把进程带走
            stale = true
            onFailed(e)
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

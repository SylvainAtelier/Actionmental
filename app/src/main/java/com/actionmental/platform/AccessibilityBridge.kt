package com.actionmental.platform

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.accessibility.AccessibilityManager
import androidx.annotation.RequiresApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * AccessibilityService 与应用其余部分之间唯一的桥。
 *
 * 服务由系统创建，无法注入依赖；它启动时把自己登记到这里，
 * 其余模块只通过这个桥拿能力，不持有 Service 引用（PRD 35.1）。
 *
 * 这里有三个不同的事实，必须分开：
 * - [enabledInSettings]：用户在系统设置里的开关，是「有没有授权」的唯一真相；
 * - [connected]：系统有没有把服务实例绑到这个进程上；
 * - [isRunningPerSystem]：系统那边**认不认**这个绑定，是「现在真的能不能干活」。
 * 进程被系统回收后重启，开关仍是开的，但实例可能还没绑回来；界面若只看
 * [connected] 就会错报「未开启」（PRD 3.2）。反过来，实例还握在手里、
 * 系统那边却已经不认了，[connected] 又会错报「一切正常」——见 [isRunningPerSystem]。
 */
class AccessibilityBridge(private val context: Context) {

    private var service: AccessibilityService? = null

    private val manager =
        context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _enabledInSettings = MutableStateFlow(false)
    val enabledInSettings: StateFlow<Boolean> = _enabledInSettings.asStateFlow()

    private val _listedInSettings = MutableStateFlow(false)
    val listedInSettings: StateFlow<Boolean> = _listedInSettings.asStateFlow()

    private val _masterSwitchOn = MutableStateFlow(false)
    val masterSwitchOn: StateFlow<Boolean> = _masterSwitchOn.asStateFlow()

    private val _foregroundPackage = MutableStateFlow<String?>(null)
    val foregroundPackage: StateFlow<String?> = _foregroundPackage.asStateFlow()

    private var observer: ContentObserver? = null

    /**
     * 系统的「正在运行的无障碍服务」列表变了。
     *
     * 绑定失效的那一刻，系统这边是有记录的 —— 变的就是这张表。挂上它等于
     * 拿到了一个零成本的失效通知：不用轮询，也不必等到下一次亮屏才发现。
     */
    var onServicesStateChanged: (() -> Unit)? = null

    /**
     * 盯住系统设置里那两个键，变化时立刻刷新。
     *
     * 这替掉了原来每 1.5 秒读一次的轮询。轮询要不停地跨进程查两个值，
     * 而这两个值一天也变不了几次 —— 观察者既更省，也更快：
     * 用户在系统设置里刚拨动开关，界面当场就跟上了，不用等下一轮。
     */
    fun startObserving(serviceClass: Class<*>) {
        if (observer != null) return
        val handler = Handler(Looper.getMainLooper())
        val target = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) = refresh(serviceClass)
        }
        observer = target
        runCatching {
            val resolver = context.contentResolver
            resolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
                false,
                target,
            )
            resolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ACCESSIBILITY_ENABLED),
                false,
                target,
            )
        }

        // 设置里那两个键只说明「授权还在不在」，它们纹丝不动的时候绑定照样会失效。
        // 这个回调补的正是那一段：系统把服务从运行列表里摘掉时会通知一次。
        // Android 13 以下没有这个入口，只能靠亮屏那一次兜底。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            observeServicesState(serviceClass)
        }
        refresh(serviceClass)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun observeServicesState(serviceClass: Class<*>) {
        val am = manager ?: return
        // 不留引用：这个监听和上面的 ContentObserver 一样跟着进程活到最后，
        // 从来不需要注销，而系统那边握着强引用，回收不掉
        runCatching {
            am.addAccessibilityServicesStateChangeListener {
                refresh(serviceClass)
                onServicesStateChanged?.invoke()
            }
        }
    }

    fun attach(service: AccessibilityService) {
        this.service = service
        attachCount++
        _connected.value = true
        refresh(service.javaClass)
    }

    /**
     * 一次掉线只算一次。
     *
     * 系统正常回收服务时会先回调 `onUnbind` 再回调 `onDestroy`，两条路径都得放掉
     * 连接状态（`onUnbind` 不保证被调用，所以两边都不能省），于是这个方法一次掉线
     * 会被调两遍。[_connected] 是 StateFlow，值不变就不再发射，日志里看不出重复 ——
     * 唯独 [detachCount] 会翻倍：盘上留下过「4 连/8 断」这样的记录，
     * 照着它读会得出掉了 8 次线的错误结论。
     *
     * 以手里还有没有服务实例为准：没有实例就说明这一次掉线已经记过了。
     */
    fun detach() {
        val wasAttached = service != null
        service = null
        if (wasAttached) detachCount++
        _connected.value = false
    }

    /**
     * 绑定来回变过几次。
     *
     * 只进诊断摘要：一段时间里绑定掉没掉过、掉了几次，事后没有别的地方看得出来。
     */
    @Volatile
    var attachCount = 0
        private set

    @Volatile
    var detachCount = 0
        private set

    /**
     * 此刻这套东西到底是什么状态，一行说完。
     *
     * 「按键不灵」有好几种成因，在界面上长得一模一样；
     * 这一行把每一种的判据一次性摊开，事后只看日志就分得清。
     */
    fun diagnosticSummary(serviceClass: Class<*>): String = buildString {
        val info = runCatching { service?.serviceInfo }.getOrNull()
        append("实例=").append(if (service != null) "在" else "无")
        append(" 系统认=").append(if (isRunningPerSystem(serviceClass)) "是" else "否")
        append(" 列表=").append(if (isListedInSettings(serviceClass)) "在" else "不在")
        append(" 总开关=").append(if (isMasterSwitchOn()) "开" else "关")
        append(" eventTypes=0x").append(Integer.toHexString(info?.eventTypes ?: 0))
        append(" flags=0x").append(Integer.toHexString(info?.flags ?: 0))
        append(" 绑定=").append(attachCount).append("连/").append(detachCount).append("断")
    }

    fun onForegroundChanged(packageName: String?) {
        if (packageName.isNullOrBlank()) return
        if (packageName == context.packageName) return
        if (_foregroundPackage.value != packageName) _foregroundPackage.value = packageName
    }

    /**
     * 忘掉当前前台应用。
     *
     * 暂停时必须清掉：暂停期间窗口事件被丢弃，这个值会一直停在暂停那一刻的包名，
     * 而恢复之后用户很可能已经在别的应用里了 —— 应用级旋转规则会照着一个过期的
     * 前台包算出结论，且要等到下一次切窗口才纠正。
     */
    fun clearForeground() {
        _foregroundPackage.value = null
    }

    /** 当前绑定着的服务实例，只给需要它当窗口令牌的平台实现用（屏幕常亮的悬浮层）。 */
    fun boundService(): AccessibilityService? = service

    fun performGlobalAction(action: Int): Boolean =
        service?.performGlobalAction(action) ?: false

    /** 重新读一次系统设置，刷新三个开关状态。轮询与生命周期回调都走这里。 */
    fun refresh(serviceClass: Class<*>) {
        val listed = isListedInSettings(serviceClass)
        val master = isMasterSwitchOn()
        _listedInSettings.value = listed
        _masterSwitchOn.value = master
        _enabledInSettings.value = listed && master
    }

    /**
     * 以系统设置为准判断服务是否真的开启，而不是依赖应用内标记（PRD 3.2）。
     *
     * 两个条件缺一不可：服务在列表里，**且** accessibility_enabled 是 1。
     * 只看列表会读出一个假的「已授权」——总开关是 0 时系统压根不绑定任何服务，
     * 于是界面停在「已授权但未连接」，自愈也不会启动，用户只能手动关掉再打开。
     */
    fun isEnabledInSettings(serviceClass: Class<*>): Boolean =
        isListedInSettings(serviceClass) && isMasterSwitchOn()

    /** accessibility_enabled：系统的无障碍总开关。0 表示所有服务都不会被绑定。 */
    fun isMasterSwitchOn(): Boolean =
        Settings.Secure.getInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1

    /** 服务有没有出现在 enabled_accessibility_services 那一串里。 */
    fun isListedInSettings(serviceClass: Class<*>): Boolean {
        val expected = ComponentName(context, serviceClass)
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        while (splitter.hasNext()) {
            if (matches(splitter.next(), expected)) return true
        }
        return false
    }

    /**
     * 系统那边此刻有没有把这个服务当成「正在运行的无障碍服务」。
     *
     * 这是唯一能识破僵尸绑定的判据。[connected] 只说明这个进程里还留着一个
     * Service 对象——熄屏期间被省电策略冻结、或者服务端连接已经作废时，
     * 系统既不会回调 onUnbind 也不会回调 onDestroy，对象照样在手里，
     * 于是应用一路自信「已连接」，按键却一颗都不再送过来。用户看到的
     * 就是「熄屏之后失灵，自动恢复毫无反应，只能去设置里关掉再打开」。
     *
     * 这个列表来自系统的已绑定服务表，跟设置里那一串是两回事：
     * 设置说的是「授权了谁」，这里说的是「现在谁真的连着」。
     */
    fun isRunningPerSystem(serviceClass: Class<*>): Boolean {
        val am = manager ?: return false
        val running = runCatching {
            am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        }.getOrNull() ?: return false
        val expected = ComponentName(context, serviceClass)
        return running.any { matches(it.id, expected) }
    }

    /** 各家 ROM 写进设置 / 服务 id 的可能是全名，也可能是 "pkg/.service.Foo" 短格式。 */
    private fun matches(flattened: String?, expected: ComponentName): Boolean {
        val item = ComponentName.unflattenFromString(flattened ?: return false) ?: return false
        val className = item.className.let {
            if (it.startsWith(".")) item.packageName + it else it
        }
        return item.packageName.equals(expected.packageName, ignoreCase = true) &&
            className.equals(expected.className, ignoreCase = true)
    }
}

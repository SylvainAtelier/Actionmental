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
        // 清单里声明的那一份，暂停结束后要原样写回去。
        // 拿不到就退回系统解析的安装清单 —— 那份永远是干净的声明值。
        captureDeclared(service, "onServiceConnected")
        attachCount++
        _connected.value = true
        refresh(service.javaClass)
        // 服务是被系统重新绑上来的，而暂停的意图存在盘上 —— 重连之后要把它补上，
        // 否则重启一次就等于偷偷恢复了全部功能
        if (eventsSuppressed) applyServiceInfo(suppressed = true)
    }

    /**
     * 一次掉线只算一次。
     *
     * 系统正常回收服务时会先回调 `onUnbind` 再回调 `onDestroy`，两条路径都得放掉
     * 连接状态（`onUnbind` 不保证被调用，所以两边都不能省），于是这个方法一次掉线
     * 会被调两遍。[_connected] 是 StateFlow，值不变就不再发射，日志里看不出重复 ——
     * 唯独 [detachCount] 会翻倍，而它恰好是暂停期间判断「绑定掉没掉过」的唯一依据：
     * 盘上留下过「4 连/8 断」这样的记录，照着它读会得出掉了 8 次线的错误结论。
     *
     * 以手里还有没有服务实例为准：没有实例就说明这一次掉线已经记过了。
     */
    fun detach() {
        val wasAttached = service != null
        service = null
        // 声明快照**不**跟着清掉。
        //
        // 它描述的是清单里那份静态配置，和某一次绑定没有关系；而清掉它恰好会在
        // 最要命的时候留下空手：暂停期间掉一次线，恢复时就没有任何东西可以写回去。
        if (wasAttached) detachCount++
        _connected.value = false
    }

    /**
     * 声明配置里，暂停会动到的那几个字段。
     *
     * 存字段值而不是存那个 [AccessibilityServiceInfo] 对象：`getServiceInfo()` 返回的是
     * 服务内部持有的同一个实例，把它存下来当「原件」，等于存了一个会被后续写入改掉的
     * 引用 —— 恢复时照着它写回去，写回的其实是压制之后的样子。
     */
    private data class DeclaredInfo(
        val eventTypes: Int,
        val flags: Int,
        val feedbackType: Int,
        val notificationTimeout: Long,
        val source: String,
    ) {
        /**
         * 这份快照看上去已经是被压制过的那一份。
         *
         * 绝不能把它当成声明配置存下来：恢复时会原样写回一份「什么都不收、也不要按键」
         * 的配置，而且从此再也回不去 —— 外面看到的就是「服务连着、按键一颗都不来，
         * 只能去系统设置里关掉再打开」。
         */
        val looksSuppressed: Boolean
            get() = serviceInfoLooksSuppressed(eventTypes, flags)

        fun summary(): String = "eventTypes=0x" + Integer.toHexString(eventTypes) +
            " flags=0x" + Integer.toHexString(flags) + " 来源=" + source
    }

    @Volatile
    private var declared: DeclaredInfo? = null

    /**
     * 绑定来回变过几次。
     *
     * 暂停期间没有任何一条路径会去复查绑定（复查本身要挂住协程十几秒，而暂停承诺的
     * 正是连这一下都不做），于是那一整段时间里绑定掉没掉过，事后没有别的地方看得出来。
     * 这两个计数就是那段空白里唯一的线索。
     */
    @Volatile
    var attachCount = 0
        private set

    @Volatile
    var detachCount = 0
        private set

    @Volatile
    private var eventsSuppressed = false

    /** 从当前服务实例抓一份声明快照；看上去已被压制就不要，改用系统解析的安装清单。 */
    private fun captureDeclared(service: AccessibilityService, source: String) {
        val live = runCatching { service.serviceInfo }.getOrNull()?.let {
            DeclaredInfo(it.eventTypes, it.flags, it.feedbackType, it.notificationTimeout, source)
        }
        if (live != null && !live.looksSuppressed) {
            declared = live
            return
        }
        // 手头这份不可信（服务刚连上时 serviceInfo 可能还是 null，或者读回来的已经是
        // 压制过的那一份），退回系统从 xml 解析出来的安装清单 —— 那份不受运行时写入影响
        installedDeclared(service.javaClass)?.let { declared = it }
    }

    /**
     * 从系统的已安装无障碍服务清单里读出这个服务的声明配置。
     *
     * 这是唯一一份**不会**被运行时 `setServiceInfo` 改掉的真值：系统直接从 apk 的
     * xml 解析而来。暂停期间掉过线、或者服务刚连上还读不到配置时，靠它兜底。
     */
    private fun installedDeclared(serviceClass: Class<*>): DeclaredInfo? {
        val am = manager ?: return null
        val expected = ComponentName(context, serviceClass)
        val info = runCatching { am.installedAccessibilityServiceList }.getOrNull()
            ?.firstOrNull { matches(it.id, expected) } ?: return null
        return DeclaredInfo(
            info.eventTypes,
            info.flags,
            info.feedbackType,
            info.notificationTimeout,
            "系统解析的安装清单",
        )
    }

    /**
     * 暂停期间让系统干脆别把事件送过来。
     *
     * 只在应用这一侧丢弃事件，省下的只是几行判断 —— 每一次按键、每一次切窗口
     * 仍然要跨进程唤醒这个进程一次，而这正是它被温控策略挑中的那部分开销。
     * 把 eventTypes 清零、并摘掉 flagRequestFilterKeyEvents 之后，系统压根不会
     * 再为这个服务分发任何东西：暂停时真正省下来的是这个。
     *
     * 服务实例不在（没绑上）时只记下意图，[attach] 时补做。
     */
    fun setEventsSuppressed(on: Boolean): String {
        if (eventsSuppressed == on) return "意图未变（已经是" + (if (on) "压制" else "正常") + "）"
        eventsSuppressed = on
        return applyServiceInfo(on)
    }

    /**
     * 把声明配置再写一遍。
     *
     * 给「解除暂停之后按键过滤没回来」这一种情况用：有些 ROM 不会因为一次
     * `setServiceInfo` 就重算按键过滤链，再写一次常常就成了 —— 而这比摘掉服务
     * 再写回去（那会让键盘真的失灵几秒）便宜得多，所以放在重绑之前试。
     */
    fun reapplyDeclaredInfo(): String = applyServiceInfo(eventsSuppressed)

    /**
     * 改写服务配置，并**读回确认**。
     *
     * 返回一句人话，调用方原样写进日志：这条路径上的每一种失败（没有服务实例、
     * 没有声明配置、写入抛异常、写进去了但系统没认）在外面看都是同一个症状
     * ——「取消暂停之后按键不灵」，不分开记就永远查不出是哪一种。
     */
    private fun applyServiceInfo(suppressed: Boolean): String {
        val target = service ?: return "服务实例不在，只记下意图，等它连回来再补"
        // 恢复这一步绝不能没有声明配置：拿不到就再去系统清单里取一次，
        // 绝不拿「当前配置」顶替 —— 当前那份此刻正是被压制的样子
        if (declared == null) captureDeclared(target, "恢复前补取")
        val snapshot = declared
            ?: return "拿不到声明配置，无法" + (if (suppressed) "压制" else "恢复") + "，只能靠重新绑定"

        val info = runCatching { target.serviceInfo }.getOrNull()
            ?: return "读不到当前配置（服务多半已经失联）"

        val write = runCatching {
            if (suppressed) {
                info.eventTypes = 0
                info.flags = snapshot.flags and
                    AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS.inv()
            } else {
                info.eventTypes = snapshot.eventTypes
                info.flags = snapshot.flags
                info.feedbackType = snapshot.feedbackType
                info.notificationTimeout = snapshot.notificationTimeout
            }
            target.serviceInfo = info
        }
        if (write.isFailure) {
            val error = write.exceptionOrNull()
            return "写入失败 · " + (error?.message ?: error.toString())
        }

        // 写进去不等于生效。读回来对一遍，是唯一能当场分辨「系统认了」和
        // 「系统收下但什么都没做」的办法
        val after = runCatching { target.serviceInfo }.getOrNull()
            ?: return "已写入，但读不回来确认"
        val filtering = after.flags and AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS != 0
        val agreed = if (suppressed) !filtering else filtering
        return buildString {
            append(if (suppressed) "已压制" else "已恢复")
            append(if (agreed) " · 读回一致" else " · 读回不一致（系统没认）")
            append(" · eventTypes=0x").append(Integer.toHexString(after.eventTypes))
            append(" flags=0x").append(Integer.toHexString(after.flags))
            append(" · 声明 ").append(snapshot.summary())
        }
    }

    /**
     * 此刻这套东西到底是什么状态，一行说完。
     *
     * 专给暂停 / 恢复前后的日志用。「取消暂停后激活失败」有五六种成因，而它们
     * 在界面上长得一模一样；这一行把每一种的判据一次性摊开，事后只看日志就分得清。
     */
    fun diagnosticSummary(serviceClass: Class<*>): String = buildString {
        val info = runCatching { service?.serviceInfo }.getOrNull()
        append("实例=").append(if (service != null) "在" else "无")
        append(" 系统认=").append(if (isRunningPerSystem(serviceClass)) "是" else "否")
        append(" 列表=").append(if (isListedInSettings(serviceClass)) "在" else "不在")
        append(" 总开关=").append(if (isMasterSwitchOn()) "开" else "关")
        append(" 按键过滤=").append(if (keyFilteringActive()) "生效" else "未生效")
        append(" eventTypes=0x").append(Integer.toHexString(info?.eventTypes ?: 0))
        append(" flags=0x").append(Integer.toHexString(info?.flags ?: 0))
        append(" 意图=").append(if (eventsSuppressed) "压制" else "正常")
        append(" 声明=").append(declared?.summary() ?: "未知")
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
     * 暂停时必须清掉：暂停期间窗口事件不再送过来，这个值会一直停在暂停那一刻的包名，
     * 而恢复之后用户很可能已经在别的应用里了 —— 应用级旋转规则会照着一个过期的
     * 前台包算出结论，且要等到下一次切窗口才纠正。
     */
    fun clearForeground() {
        _foregroundPackage.value = null
    }

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

    /**
     * 服务眼下这份配置还带不带按键过滤标志。
     *
     * 暂停会临时摘掉它（见 [setEventsSuppressed]），恢复时写回去。但写回去是不是
     * 真的生效由系统说了算：有些 ROM 不会因为一次 setServiceInfo 就重算按键过滤链。
     * 一旦没生效，服务连着、窗口事件也照收，唯独按键永远到不了——从外面看
     * 与「服务掉线」一模一样，只有这里看得出区别。
     */
    fun keyFilteringActive(): Boolean {
        val info = runCatching { service?.serviceInfo }.getOrNull() ?: return false
        return info.flags and AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS != 0
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

/**
 * 这份配置看上去是不是「暂停时压制过的那一份」。
 *
 * 单拎成顶层纯函数是为了能直接单测：这是整条暂停 / 恢复链上最要命的一个判断 ——
 * 判错一次，压制过的配置就会被当成清单声明存下来，恢复时原样写回去，
 * 于是服务一直连着、按键却永远不来，用户只能去系统设置里关掉再打开。
 *
 * 两个条件任一成立就算：压制做的正是「清零 eventTypes」和「摘掉按键过滤标志」。
 */
fun serviceInfoLooksSuppressed(eventTypes: Int, flags: Int): Boolean =
    eventTypes == 0 || flags and AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS == 0

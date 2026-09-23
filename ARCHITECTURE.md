# Actionmental 架构说明

PRD 第 20/21/34 节给出的分层是对的，但直接照搬会得到 30+ 个互相持有引用的小类。
这份实现保留了 PRD 的**全部硬性约束**（第 35 节 15 条），同时在四个地方做了收敛。
下面先讲收敛，再讲分层。

## 一、四处简化

### 1. 三个键盘类合并成一条管线

PRD 拆出 `KeyEventNormalizer` / `KeyStateTracker` / `ShortcutRecorder` 三个模块。
它们共享同一份状态（当前按下了哪些键、当前是不是录制模式），拆开只会制造同步问题。

`core/key/KeyPipeline.kt` 用一个小状态机同时承担这三件事：

```
AccessibilityService.onKeyEvent
        └─> KeyPipeline.dispatch(KeyEvent)      ← 全应用唯一入口
              ├─ normalize()      归一化
              ├─ updateSnapshot() 按下状态
              ├─ appendTrace()    事件流（200 条，仅内存）
              └─ 录制中？ 吞事件并记录 : onTrigger(combo, device)
```

`onTrigger` 是唯一的出口回调，由 `AppGraph` 接上匹配器与执行器。
未来加长按 / 双击 / 组合序列，只需要在这一个 `dispatch` 里扩展，
不需要动 Service、仓库、UI —— 这正是 PRD 39 要的「不重构核心链路」。

### 2. DataStore + kotlinx.serialization，而不是 Room

PRD 第 19 节**建议**结构化数据库。这里没有用，理由是：

- 数据量是几十条，且按键路径要求**零 IO**（PRD 27），所以无论如何都得整表常驻内存；
- Room 会引入 KSP、编译期代码生成、schema 迁移，换来的关系查询能力一条都用不上；
- 唯一性约束不是「数据库能不能做」的问题，而是「写入路径能不能绕过」的问题。

所以约束放在 `ShortcutRepository.save()` 这一个入口上：任何写入（编辑页、引导预设、导入）
都必须经过它，冲突时返回 `SaveOutcome.Conflict`，调用方无法静默覆盖。
唯一性的键是 `Shortcut.scopeKey = (combo, deviceScope, appScope)`，
满足 PRD 35.11 / 35.12，而且比 SQL 唯一索引更能表达「同一作用域」这个概念。

仓库是普通类而非接口的实现细节——真需要关系查询时换掉这一个文件即可，
上层只认 `StateFlow<List<Shortcut>>` 和 `save/delete/setEnabled`。

### 3. 不用 DI 框架

对象图是一棵固定的树，而真正需要拿依赖的两处（`AccessibilityService`、`TileService`）
都由系统实例化。Hilt 在这种场景下要么用 `EntryPoint` 绕一圈，要么什么也不省。

`AppGraph.kt` 是手写组合根，`AppGraph.get(context)` 供系统组件取用。
所有依赖都是显式构造参数，测试时直接换掉即可。

### 4. 旋转控制没有本地状态机

这是整份实现里最关键的一处。PRD 第 3.2/8/11/12 反复强调「状态必须来自真实系统」，
最容易出错的写法是维护一个 `isForced: Boolean` 然后到处同步它。

`RotationController` 的做法是把**意图**和**事实**彻底分开：

| | 含义 | 存在哪里 |
|---|---|---|
| `globalMode` | 用户表达的全局意图 | 持久化（进程重启后重建） |
| `override` | 应用规则临时压上的意图 | 只在内存 |
| `state` | 系统事实 | 每次写入后立刻回读 |

- 写入 = `write(effectiveMode())` 然后**无条件回读**，`state` 里永远是刚查回来的值；
- 读不到 → `RotationState.unknown(reason)`，`mode = UNKNOWN`，
  磁贴显示 `UNAVAILABLE` 而不是「已关闭」（PRD 37 明确要求）；
- 应用规则不记「上一次设成了什么」，只是压 / 撤 `override`，
  撤掉后自然回到 `globalMode` —— 所以不会出现「永远保持最后一次设置」。

磁贴因此可以做到零状态：`onStartListening` 读真实值 → `onClick` 执行 → 再读 → 更新。

## 二、分层与依赖方向

```
ui/            Compose，只读 StateFlow，只调 ViewModel
  └─ AppViewModel / ShortcutEditorViewModel   薄适配层，无业务规则
core/          纯 Kotlin 领域层（key / shortcut / action / rotation / status）
data/          仓库，DataStore 持久化
platform/      Android 能力封装，全部藏在接口后
service/       系统实例化的组件，尽可能薄
```

依赖只向下。几条具体的边界：

- UI 看不到 `Shizuku`，只看到 `PrivilegedBackend` 与 `ShizukuStatus`（PRD 35.9）；
- UI 不执行 shell，`Action.Shell` 也要经过 `ActionExecutor`（PRD 35.8）；
- `AccessibilityService` 只做三件事：登记自己、转发按键、上报前台包名（PRD 35.1）；
- Shizuku 不碰键盘（PRD 35.2）；
- 特权侧 `PrivilegedUserService` 只有一个 `exec(String)`，没有任何业务逻辑。

### 能力分层

同一个动作优先用权限最低的后端（PRD 3.4）：

| 动作 | 后端 |
|---|---|
| 音量 / 媒体 | `AudioManager`（普通 API） |
| 启动应用 | `PackageManager` + `startActivity` |
| 返回 / 主页 / 最近任务 | `AccessibilityService.performGlobalAction` |
| 屏幕常亮 | 无障碍悬浮层 `FLAG_KEEP_SCREEN_ON`，兜底 `PowerManager.WakeLock` |
| 屏幕方向 | Shizuku → 无障碍悬浮层 `screenOrientation` → 系统设置（见下） |
| 键位映射输出 | Shizuku 注入 → 全局动作 / AudioManager / 无障碍输入通道（见下） |
| 无障碍自愈 | `WRITE_SECURE_SETTINGS`（adb 授予一次）→ Shizuku |
| Shell | Shizuku |

### 没有 Shizuku 时

Shizuku 是唯一能「向系统注入任意按键」「执行 `cmd window`」的通道，但它掉线是常态
（重启后要重新拉起、特权服务绑定可能迟迟不到）。所以旋转与映射不再是「有 Shizuku 才有」，
而是按那一刻最强的一级去做，并把实际用的是哪一级摆在界面上：

**旋转（`RotationTier`，每次写入现挑）**

| 级别 | 做法 | 能做到 | 做不到 |
|---|---|---|---|
| `SHELL` | `ignore-orientation-request` + `fix-to-user-rotation` | 压住应用自带方向，多屏下发 | — |
| `OVERLAY` | 无障碍服务挂 1px 窗口，带 `screenOrientation` | 压住应用自带方向 | 显示屏级忽略方向请求时无效（部分大屏 / 折叠屏） |
| `SETTINGS` | 写 `accelerometer_rotation` / `user_rotation` | 锁定角度、开关自动旋转 | 应用自带方向优先 |
| `NONE` | — | 只读 | 写入如实失败，意图不落盘 |

读不需要特权（两个旋转设置是公开的 system 表），所以 Shizuku 不在时状态照样是真值，不再整块报 `UNKNOWN`。
悬浮层下发后要等屏幕真的转过去：90° / 270° 转反了就对调一次重发（`config_reverseDefaultRotation`
读不到），仍然转不过去就撤掉窗口、退到锁定并如实提示 —— 留着它回读会谎报「已强制」。
悬浮层随服务实例作废，服务重绑、Shizuku 来去时 `RotationController.reapplyAsync()` 按意图重新落一次。

**映射（`KeyOutput` / `KeyRouting`）**

按键线程上同步挑路，发不出去就不拦源键（绝不吞键）：

1. Shizuku 已授权**且特权服务已连上** → 注入。只看授权不看绑定，会在绑定迟到时拦下源键再注入失败，按键就哑了；
2. 系统键（返回、主页、最近任务、截屏、锁屏、方向键）→ `performGlobalAction`；
3. 媒体与音量键 → `AudioManager`；
4. 其余的键 → Android 13+ 无障碍服务自带的输入通道（`flagInputMethodEditor`，与用户输入法并存），
   只在输入框获得焦点时有；
5. 都不通 → 放行原键，事件流写明原因。

**自愈**只读写 secure 表：用 adb 授过一次 `WRITE_SECURE_SETTINGS` 就直接写（`SettingsFirstBackend`），
开机那一刻不必等 Shizuku 起来。

屏幕常亮特意没走 Shizuku 改 `screen_off_timeout`：那要改一条全局系统设置并负责改回来，
进程中途被杀就在系统里留下一个用户自己都找不着的超长熄屏时间。
主路径是用无障碍服务挂一个 1px 透明的 `TYPE_ACCESSIBILITY_OVERLAY` 窗口并带上
`FLAG_KEEP_SCREEN_ON`：屏幕唤醒锁在部分 ROM 上对非前台应用会被直接忽略（`isHeld` 为真、屏幕照熄），
而窗口策略不受这个影响。服务未连接时退回 `SCREEN_DIM_WAKE_LOCK`，服务连上后自动升级回悬浮层。
两者都跟着进程走，不需要额外的前台服务。
它同样遵守「意图与事实分开」：意图落在 `UserSettings.screenAwake`（进程重启后重建），
事实是每次写入后回读的「悬浮层是否挂在当前服务上 || `WakeLock.isHeld`」。

全局暂停（手动，或没有键盘时自动）只做三件事：按键原样放行、丢弃窗口事件、
关上旋转写入闸门。关闸前在同一把锁里写一次「解除强制 + `user-rotation lock 0`」，
把屏幕放回 0° 竖屏；`UserSettings.pauseRotationApplied` 保证一段暂停只写这一次，
进程被杀重启后暂停重新落地也不会再扳一次。暂停不改写无障碍服务的 `serviceInfo` ——
那条路在部分 ROM 上恢复后不重算按键过滤链，是「解除暂停后按键不灵」的根源。

常亮与常驻前台服务不受暂停管：常亮不依赖键盘，悬浮层不收事件、不跑定时器；
常驻服务要让进程活到键盘回来，自动暂停才解除得了。

常亮是会一直耗电、又可能被一个快捷键悄悄按开的状态，所以它有三处可见性，
三处读的都是同一个 `ScreenAwakeController.state`：状态中心的开关卡、快捷设置磁贴、
以及常亮期间的常驻通知（`ScreenAwakeNotifier`，IMPORTANCE_LOW，带「关闭常亮」按钮）。
通知只跟真实状态走 —— 它出现在通知栏 = 锁此刻真的握着。

`ActionExecutor.execute()` 是唯一的分发点，返回 `ActionResult`：
`Ok` 或 `Failed(reason, detail)`。**没有一条路径会静默失败**（PRD 25 / 35.14）。

## 三、状态聚合

`AppGraph.status: StateFlow<SystemStatus>` 把无障碍、Shizuku、键盘、旋转、屏幕常亮、
快捷键数量、前台应用、生效规则合成一份。首页、侧栏、引导、Shizuku 页全部订阅它，
没有任何页面自己查询系统（PRD 23）。

## 四、自适应布局（手机 / 平板 / 折叠屏）

只有两个断点，全部由可用宽度驱动：

- `< 600dp` → 底部导航（四个高频入口，其余从状态中心进）
- `>= 600dp` → 左侧栏（带 logo 与底部状态）
- `>= 840dp` → 快捷键列表与编辑器并排

`MainActivity` 声明了 `configChanges`（含 `screenLayout` / `smallestScreenSize`），
折叠、展开、旋转、进入桌面模式都只触发一次重组，不重建 Activity，
录制中的状态和滚动位置都不会丢。

## 五、已知边界

- **IBM Plex 字体未打包**：`AmType` 目前用系统 sans / mono。
  把 ttf 放进 `res/font/` 再改 `AmType.ui/mono` 两行即可，其余不用动。
- **minSdk 29**：`Tile.setSubtitle` 是磁贴三态显示的核心，需要 API 29。
  实际上 `wm set-ignore-orientation-request` 要 Android 12+，
  更低版本会走到 `UNKNOWN` 分支并如实显示不可用。
- **`fixed_to_user_rotation` 在部分 OEM 上不存在**：`RotationController.write()`
  把它当作降级而不是失败，返回 `Ok` 并附带说明（能力矩阵页会显示 PARTIAL）。
- **OEM 会按应用放行方向请求**：ColorOS 16 平板（PKH120）上，`ignore-orientation-request=true`
  压得住抖音，却压不住红果短剧（`com.phoenix.read`）的竖屏请求，没有公开开关能看到这份名单。
  屏幕被拉回 0° 的同时，SystemUI 的 `RotationButtonController` 会把 `user_rotation` 改成 0，
  离开那个应用后强制横屏也跟着丢了。两层处理：
  前台应用每换一次，`RotationController.reassertForced()` 在强制模式下核对并补写（系统没被动过就不写）；
  压住那个应用本身要靠应用级兼容覆盖里的 `OVERRIDE_ANY_ORIENTATION_TO_USER`，施加后结束并重开它。
  强制期间旋转设置一变，事件日志就查一次方向来源，记下「方向被应用拉走 · 包名」。
- **事件日志同步输出到 logcat**（标签 `Actionmental`），release 包也能 `adb logcat -s Actionmental` 现场抓。
  部分机型 logcat 缓冲十几秒就被刷满，要边复现边抓，事后 `-d` 多半已经没了。
- **导入导出走剪贴板**，尚未接 SAF 文件选择器。
- 第二阶段功能（反向方向的快捷键预设、按键统计、多设备绑定 UI）数据结构已预留，
  `DeviceScope` / `AppScope` 都已经是可表达的形态，只差界面。

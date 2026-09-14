<div align="center">
  <img src="assets/logo/lockup-horizontal.svg" width="460" alt="Actionmental">
  <p><strong>Android 实体键盘快捷键与屏幕控制工具。</strong></p>
  <p><a href="README.md">English</a></p>
</div>

Actionmental 可以把 Android 识别到的实体键盘变成系统控制器。它能监听组合键、执行系统动作、映射键位、管理屏幕方向，并以系统回读结果显示真实状态，不用容易失真的本地开关冒充设备状态。

> Actionmental 最初就是为适配 Winmaxle B1 而开发的。当前版本已经由项目作者在 B1 上完整验证并正常运行。输入链路使用 Android 标准实体键盘 API，因此也可用于其他 USB 与蓝牙键盘。

项目无需 Root。键盘监听使用 Android 无障碍服务，高权限功能使用 [Shizuku](https://shizuku.rikka.app/)。

## 功能总览

### 实体键盘快捷键

- 在其他应用位于前台时全局接收实体键盘事件。
- 统一处理 Ctrl、Alt、Shift、Meta 与主键，按下顺序不同仍视为同一组合键。
- 直接按下组合键完成录制，无需逐个选键。
- 保存前检测冲突，并由用户明确决定是否覆盖旧绑定。
- 支持搜索、筛选、复制、新增、编辑、启用、禁用和删除快捷键。复制项默认禁用。
- 匹配索引常驻内存，按键主链路不进行磁盘 I/O。
- 同一输入同时存在快捷键和键位映射时，快捷键优先。
- 可选在没有实体键盘时自动暂停。

数据模型和匹配器已经支持指定设备与前台应用范围。当前编辑器创建的是全局绑定，带作用域的数据结构可被保存和导入，为后续界面支持预留。

### 快捷键可执行动作

| 分类 | 动作 | 执行方式 |
| --- | --- | --- |
| 系统导航 | 返回、主页、最近任务、通知栏、快捷设置、锁屏 | 无障碍服务 |
| 媒体 | 播放或暂停、上一曲、下一曲 | Android 音频 API |
| 音量 | 增大、减小、静音切换 | Android 音频 API |
| 应用 | 启动应用或指定 Activity | Android 应用 API，必要时由 Shizuku 降级处理 |
| 链接 | 交给 Android 中匹配的应用打开 HTTP、HTTPS 或邮件链接 | Android Intent |
| 屏幕方向 | 四种固定方向、横屏切换、模式循环、恢复系统默认 | Shizuku |
| 屏幕常亮 | 开启、关闭或切换唤醒锁 | Android WakeLock |
| Shell | 执行用户逐条明确配置的命令 | Shizuku |

### 键位映射

- 把一个按键或组合键映射成另一个输入。
- 支持新增、编辑、启用、禁用和删除。
- 内置 Caps Lock 转 Escape、Caps Lock 转 Ctrl、Ctrl+H 转 Backspace 等预设。
- 可先运行自检，确认注入链路可用。
- 映射生效时会抑制原按键的按下、重复和抬起事件。

键位注入需要 Shizuku。快捷键优先于同一输入的映射，可避免意外形成映射链。

### 屏幕方向与应用规则

- 恢复 Android 默认旋转策略。
- 强制横屏、反向横屏、竖屏或反向竖屏。
- 切换强制横屏，或循环常用方向模式。
- 指定应用进入前台时自动套用对应方向规则。
- 离开受控应用后恢复当前有效的全局模式。
- 提供诊断命令，把当前忽略应用方向的设置重新应用到检测到的显示器。
- 诊断屏幕没有旋转的原因，并显示相关 Shell 执行结果。

每次写入后都会立即回读系统状态。Shizuku、ROM 或命令无法给出可信结果时，Actionmental 会显示 `UNKNOWN` 或不可用，不会猜测状态。

### 快捷设置磁贴

| 磁贴 | 行为 |
| --- | --- |
| 屏幕常亮 | 切换唤醒锁，并显示回读到的真实状态 |
| 强制横屏 | 在强制横屏和系统默认之间切换 |
| 旋转模式循环 | 按配置顺序循环方向模式 |

Android 13 及以上可在应用内请求添加“强制横屏”磁贴。“屏幕常亮”和“旋转模式循环”仍需从系统快捷设置编辑面板手动拖入。

### 状态、诊断与恢复

- 状态中心汇总无障碍、Shizuku、键盘连接、旋转、屏幕常亮、快捷键数量、前台应用和当前规则。
- 支持手动暂停与恢复。暂停时会停止按键处理、释放屏幕常亮并将屏幕固定为竖屏。
- 按键检测页显示当前组合键、`keyCode`、`scanCode`、按下或抬起、输入设备、匹配结果和近期事件。
- 近期按键记录最多保留 200 条，只在检测页打开期间收集，并且只存在内存中。
- 提供运行日志、Shell 日志、进程退出、温控和进程状态诊断。
- 诊断内容可以复制，本地日志可以清空。
- 可选以前台服务保持进程常驻。
- 可选通过 Shizuku 检查并设置 Doze 白名单与后台 AppOps，每次设置后回读确认。
- 可选在 OEM 清理或重启后尝试恢复无障碍监听，并显示通知与恢复历史。
- 八步首次引导会检查必要能力并安装一组入门快捷键。

### 数据与界面

- 使用 Android DataStore 保存设置、快捷键、映射、加固历史和旋转规则。
- 通过剪贴板以 JSON 导入或导出快捷键与键位映射。
- 通过 Android 文件选择器把快捷键与键位映射备份为 JSON 文件并恢复。
- 应用内可选择英文或简体中文。
- 可选择跟随系统、浅色或深色主题。
- 手机使用底部导航，600 dp 起使用侧栏，840 dp 起使用双栏快捷键编辑器。
- debug 与 release 可以同时安装，包名、应用名、服务名和磁贴名均有区分。

## 能力要求

| 能力 | 要求 | 缺失时的表现 |
| --- | --- | --- |
| 全局快捷键监听 | 开启 Actionmental 键盘无障碍服务 | 无法接收按键或触发快捷键 |
| 媒体、音量、链接、普通应用启动 | Android 普通 API | 只有对应动作会报告失败 |
| 屏幕常亮 | 安装时授予的 `WAKE_LOCK` 权限 | 动作报告不支持或失败 |
| 屏幕方向、键位映射、Shell、部分应用启动 | Shizuku 运行并授权 | 控件仍可见，但明确显示不可用 |
| 屏幕方向命令 | 实际建议 Android 12 及以上 | 更低版本或不兼容 ROM 显示未知 |
| 应用内添加“强制横屏”磁贴 | Android 13 及以上 | 改为手动添加 |

最低系统版本为 Android 10，也就是 API 29。`wm set-ignore-orientation-request` 与 `fixed_to_user_rotation` 在不同厂商 ROM 上实现并不一致，因此旋转行为仍受设备系统影响。

## Winmaxle B1 说明

Winmaxle B1 是本项目的首要目标设备。Actionmental 通过 Android `KeyEvent` 和 `InputDevice` 接收 B1 按键，没有加入设备专用驱动或 HID 解析器。

- 普通键和修饰键组合与其他 Android 实体键盘走同一条处理链路。
- 按键检测页会显示 `keyCode`、`scanCode` 和设备描述，可用于排查特殊键。
- Fn 层或厂商保留键可能先被键盘固件或 Android ROM 消费，应用将无法收到这些事件。
- 新版 Android 中，部分 Meta 组合键可能先触发系统行为。Ctrl、Alt 或 Shift 组合通常更可控。

## 隐私与权限

Actionmental 不申请网络权限，也不会自行传输按键事件、配置或诊断数据。应用启用了 Android 系统备份，因此设备所配置的备份服务可能把 DataStore 内容复制到云端备份。

| 权限或访问能力 | 用途 |
| --- | --- |
| 无障碍服务 | 接收实体键盘事件、读取前台应用包名、执行 Android 全局导航动作 |
| Shizuku API | 执行用户选择的高权限操作 |
| 查询已安装应用 | 填充应用与 Activity 选择器，并解析启动目标 |
| 通知 | 显示屏幕常亮、可选常驻运行与自愈结果 |
| 唤醒锁 | 用户明确开启时阻止屏幕自动熄灭 |
| 开机完成广播 | 重启后恢复用户已启用的后台加固 |
| 前台服务 | 在会主动清理后台服务的 ROM 上可选保持进程常驻 |
| Android 系统备份 | 通过 Android 设置中选择的备份服务备份 DataStore |

Actionmental 处理的是按键码，不是输入文本。近期按键记录只在内存中，进程退出即消失。配置和日志保存在应用私有目录，但用户主动导出、Android 系统备份或设备迁移时会离开该目录。

## 从源码构建

准备 JDK 21、Android SDK 36，以及用于安装验证的 Android 设备。

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
```

Windows 请使用 `gradlew.bat`。debug APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。

### 在 Windows 上部署并验证

```powershell
./scripts/deploy-debug.ps1
./scripts/deploy-debug.ps1 -Launch
./scripts/deploy-debug.ps1 -VerifyOnly
```

部署脚本会构建、安装、比对设备 APK 与本地 APK，并输出无障碍、Shizuku 和实体键盘的真实状态。

提交或发布前运行公开文件审计：

```powershell
./scripts/audit-public-files.ps1
```

发布签名材料统一放在已忽略的 `.local/signing/`。本地签名与 GitHub Actions 发布流程见[签名与发布](docs/签名与发布.md)。

## 架构

```text
AccessibilityService
  -> KeyPipeline
     -> 快捷键匹配 -> ActionExecutor
     -> 键位映射匹配 -> Shizuku 注入按键
```

- `core/` 包含按键、快捷键、动作、映射、旋转、常亮、状态和诊断逻辑。
- `data/` 使用 DataStore 保存设置和用户配置。
- `platform/` 封装 Android 与 Shizuku API。
- `service/` 包含由 Android 创建的服务、广播接收器与磁贴。
- `ui/` 包含 Compose 界面和 ViewModel。

界面统一订阅 `SystemStatus`，不会直接执行 Shell 命令。设计取舍与已知边界见 [ARCHITECTURE.md](ARCHITECTURE.md)。

## 当前边界

- 目前有 JVM 单元测试，还没有 `androidTest` 设备测试套件。
- 设备行为仍取决于 Android 版本、厂商 ROM、键盘固件，以及系统是否把某个按键交给无障碍服务。
- 快捷键引擎支持设备和应用作用域，但当前编辑器只提供全局快捷键创建。
- 设计系统引用了 IBM Plex 字体，但 APK 尚未打包字体文件。

## 许可证

Actionmental 采用 [MIT License](LICENSE)。

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
| 屏幕方向 | 四种固定方向、横屏切换、模式循环、恢复系统默认 | Shizuku；不可用时降级为无障碍悬浮层或系统设置 |
| 屏幕常亮 | 开启、关闭或切换唤醒锁 | Android WakeLock |
| Shell | 执行用户逐条明确配置的命令 | Shizuku |

### 键位映射

- 把一个按键或组合键映射成另一个输入。
- 支持新增、编辑、启用、禁用和删除。
- 内置 Caps Lock 转 Escape、Caps Lock 转 Ctrl、Ctrl+H 转 Backspace 等预设。
- 可先运行自检，确认注入链路可用。
- 映射生效时会抑制原按键的按下、重复和抬起事件。

任意键注入需要 Shizuku。没有 Shizuku 时按目标键分流：系统键（返回、主页、最近任务、截屏、锁屏、方向键）走无障碍全局动作，媒体与音量键走 Android 音频 API，其余键在 Android 13 及以上经无障碍输入通道发给当前输入框；都走不通时放行原键，不会吞键。快捷键优先于同一输入的映射，可避免意外形成映射链。

### 屏幕方向与应用规则

- 恢复 Android 默认旋转策略。
- 强制横屏、反向横屏、竖屏或反向竖屏。
- 切换强制横屏，或循环常用方向模式。
- 指定应用进入前台时自动套用对应方向规则。
- 离开受控应用后恢复当前有效的全局模式。
- 提供诊断命令，把当前忽略应用方向的设置重新应用到检测到的显示器。
- 诊断屏幕没有旋转的原因，并显示相关 Shell 执行结果。

每次写入后都会立即回读系统状态。Shizuku、ROM 或命令无法给出可信结果时，Actionmental 会显示 `UNKNOWN` 或不可用，不会猜测状态。

没有 Shizuku 时，屏幕方向改由无障碍悬浮层强制（仍能压住应用自带的方向，但部分大屏或折叠屏会忽略），再不行则写系统设置锁定角度（应用自带的方向优先）。界面会标明当前用的是哪一级。

### 快捷设置磁贴

| 磁贴 | 行为 |
| --- | --- |
| 屏幕常亮 | 切换唤醒锁，并显示回读到的真实状态 |
| 强制横屏 | 在强制横屏和系统默认之间切换 |
| 旋转模式循环 | 按配置顺序循环方向模式 |

Android 13 及以上可在应用内请求添加“强制横屏”磁贴。“屏幕常亮”和“旋转模式循环”仍需从系统快捷设置编辑面板手动拖入。

### 状态、诊断与恢复

- 状态中心汇总无障碍、Shizuku、键盘连接、旋转、屏幕常亮、快捷键数量、前台应用和当前规则。
- 支持手动暂停与恢复。暂停时会停止按键处理，并把屏幕一次性放回 0° 竖屏（解除强制方向），屏幕常亮不受暂停影响。
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
| 完整强制方向、任意键注入、Shell、部分应用启动 | Shizuku 运行并授权 | 屏幕方向与键位映射降级运行；Shell 与冻结应用启动明确显示不可用 |
| 降级旋转时写自动旋转与锁定角度 | 可选：在系统「修改系统设置」页授权 | 仅靠无障碍悬浮层强制方向 |
| 不依赖 Shizuku 的无障碍自愈 | 可选：`adb shell pm grant <包名> android.permission.WRITE_SECURE_SETTINGS` 一次 | 自愈需要 Shizuku |
| 无 Shizuku 时映射普通键 | Android 13 及以上 | 只覆盖系统键与媒体键 |
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

## 本地命令

以下命令都在仓库根目录执行。Gradle 命令写成 `./gradlew`，在 PowerShell、Git Bash、macOS 和 Linux 下都能用；在 `cmd.exe` 里改用 `gradlew.bat`。`scripts/` 下的脚本是面向 Windows 的 PowerShell 脚本。

| 变体 | 包名 | 启动 Activity | 无障碍服务 |
| --- | --- | --- | --- |
| debug | `com.actionmental.debug` | `com.actionmental.debug/com.actionmental.ui.MainActivity` | `com.actionmental.debug/com.actionmental.service.KeyboardAccessibilityService` |
| release | `com.actionmental` | `com.actionmental/com.actionmental.ui.MainActivity` | `com.actionmental/com.actionmental.service.KeyboardAccessibilityService` |

下面的 adb 示例用的是 debug 包名。操作 release 包时，把 `com.actionmental.debug` 换成 `com.actionmental`。

### 1. 检查环境

需要 JDK 21、带 platform-tools 和 build-tools 的 Android SDK 36，并且 `adb` 在 `PATH` 里。

```powershell
java -version                # 必须是 21
adb version
adb devices                  # 设备状态必须是 device，不能是 unauthorized
```

Gradle 通过 `ANDROID_HOME` 或 `local.properties` 里的 `sdk.dir` 找到 SDK。`local.properties` 是本机文件，已被 Git 忽略：

```properties
sdk.dir=C\:/path/to/Android/Sdk
```

如果 PowerShell 拦截脚本，可以给当前用户放开一次本地脚本，或者只对单次运行绕过策略：

```powershell
Set-ExecutionPolicy -Scope CurrentUser RemoteSigned
powershell -ExecutionPolicy Bypass -File scripts/deploy-debug.ps1
```

### 2. 构建

```powershell
./gradlew :app:assembleDebug                           # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease                         # app/build/outputs/apk/release/app-release.apk
./gradlew :app:assembleRelease "-PversionName=1.2.3"   # versionCode 自动推导：1.2.3 -> 10203
./gradlew clean
```

`assembleRelease` 只有在签名材料存在时才会签名（见第 7 节）。没有签名材料时，Gradle 产出的是未签名的 release APK，既装不上也不能分发。

### 3. 测试与 lint

```powershell
./gradlew :app:testDebugUnitTest
./gradlew :app:testDebugUnitTest --tests "com.actionmental.ActionAndKeyCatalogTest"
./gradlew :app:lintDebug
./gradlew --no-daemon :app:testReleaseUnitTest :app:lintRelease   # CI 发布前跑的同一组检查
```

报告在 `app/build/reports/tests/` 和 `app/build/reports/lint-results-*.html`。

### 4. 安装到设备

推荐用部署脚本。脚本会构建、安装、比对设备上 `base.apk` 与本地 APK 的 SHA-256，并输出无障碍、Shizuku 和实体键盘的真实状态。

```powershell
# debug
./scripts/deploy-debug.ps1                                  # 构建并安装
./scripts/deploy-debug.ps1 -Launch                          # 安装后打开应用
./scripts/deploy-debug.ps1 -SkipBuild                       # 直接安装已构建好的 APK
./scripts/deploy-debug.ps1 -VerifyOnly                      # 不构建不安装，只查看设备状态
./scripts/deploy-debug.ps1 -Device 192.168.1.5:5555         # 连了多台设备时指定一台
./scripts/deploy-debug.ps1 -VersionCode 5                   # 给 Gradle 传指定的 versionCode
./scripts/deploy-debug.ps1 -Adb "C:\path\to\adb.exe"        # 使用不在 PATH 里的 adb

# release：与 GitHub Actions 相同的签名、版本号和 apksigner 校验
./scripts/deploy-release.ps1                                # 版本号取本地最新的 v* tag
./scripts/deploy-release.ps1 -VersionName 1.2.0 -Launch
./scripts/deploy-release.ps1 -VersionName 1.2.0 -VersionCode 10205
./scripts/deploy-release.ps1 -SkipBuild
./scripts/deploy-release.ps1 -VerifyOnly
./scripts/deploy-release.ps1 -Device 192.168.1.5:5555
```

没有签名材料时，`deploy-release.ps1` 拒绝构建。设备上的 versionCode 更高时，脚本会把本次构建的 versionCode 抬到同一档，不会卸载应用。本地 tag 落后于远端时，先运行 `git fetch --tags`。

不用脚本时这样安装：

```powershell
./gradlew :app:installDebug
adb install --no-streaming -r -d app/build/outputs/apk/debug/app-debug.apk
adb install --no-streaming -r app/build/outputs/apk/release/app-release.apk
```

`-d` 允许降级安装，只对可调试的 debug 包有效。`--no-streaming` 用来避开部分 OEM ROM（包括 ColorOS）上的静默安装失败。

无线调试需要先配对再连接：

```powershell
adb pair 192.168.1.5:37000          # 配对端口和配对码见「开发者选项 > 无线调试」
adb connect 192.168.1.5:5555
```

### 5. 用 adb 配置设备

```powershell
# 打开应用
adb shell am start -n com.actionmental.debug/com.actionmental.ui.MainActivity

# 开启键盘无障碍服务
adb shell am start -a android.settings.ACCESSIBILITY_SETTINGS
adb shell settings put secure enabled_accessibility_services com.actionmental.debug/com.actionmental.service.KeyboardAccessibilityService
adb shell settings put secure accessibility_enabled 1
adb shell settings get secure enabled_accessibility_services

# 可选：没有 Shizuku 时启用无障碍自愈（授权一次，重启不丢）
adb shell pm grant com.actionmental.debug android.permission.WRITE_SECURE_SETTINGS

# 通过 adb 启动 Shizuku（需先安装 Shizuku 并打开过一次）
adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh
adb shell pm list packages moe.shizuku.privileged.api

# 确认 Android 识别到了实体键盘
adb shell dumpsys input
```

`settings put secure enabled_accessibility_services` 会覆盖整个列表。如果还开着别的无障碍服务，先读出当前值，再用 `:` 把 Actionmental 那一项接在后面写回去。

### 6. 诊断与清理

```powershell
adb logcat --pid=$(adb shell pidof -s com.actionmental.debug)   # 只看本应用的日志
adb logcat -c                                                    # 清空日志缓冲区
adb shell dumpsys package com.actionmental.debug                 # versionCode、lastUpdateTime、已授予的权限
adb shell am force-stop com.actionmental.debug
adb uninstall com.actionmental.debug                             # 会删除快捷键和键位映射，先在应用里备份
```

日志 tag 以 `Actionmental` 开头，例如 `Actionmental:rotation` 和 `Actionmental:screen-awake`。`$(...)` 写法在 PowerShell 和 bash 里都能用。

### 7. 发布签名

签名材料放在已被忽略的 `.local/signing/` 目录：

```text
.local/signing/
├── actionmental-release.jks
└── keystore.properties
```

```properties
storeFile=actionmental-release.jks
storePassword=<口令>
keyAlias=actionmental
keyPassword=<口令>
```

`storeFile` 写相对路径时按 `.local/signing/` 解析。没有这个文件时，Gradle 从环境变量读取 `ANDROID_KEYSTORE_PATH`、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS` 和 `ANDROID_KEY_PASSWORD`。

```powershell
# 只生成一次，放在仓库目录以外，并离线备份。
# PKCS12 格式下密钥口令恒等于库口令。
keytool -genkeypair -v -keystore actionmental.jks -alias actionmental -keyalg RSA -keysize 4096 -validity 10950 -storetype PKCS12

# 核对别名与口令
keytool -list -v -keystore actionmental.jks -alias actionmental

# 生成单行 base64，填入 GitHub Secret ANDROID_KEYSTORE_BASE64
[Convert]::ToBase64String([IO.File]::ReadAllBytes("actionmental.jks")) | Set-Clipboard   # PowerShell
base64 -w 0 actionmental.jks > actionmental.jks.base64                                     # bash

# 校验已构建 APK 的签名（apksigner 在 SDK 的 build-tools/<版本>/ 下）
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
```

### 8. 发布 Release

Release 由手动触发的 GitHub Actions 工作流 `Release (Manual)` 产出。它根据最新的 `v*` tag 计算下一个版本号，构建并签名 APK，然后发布 Release。用 GitHub CLI 触发：

```powershell
./scripts/audit-public-files.ps1                   # 检查 Git 会发布的文件里有没有密钥和个人路径
./scripts/audit-public-files.ps1 -IncludeIgnored   # 连同已忽略的文件一起检查
gh workflow run release.yml -f bump=patch          # bump：patch | minor | major
gh workflow run release.yml -f bump=minor -f prerelease=true
gh run watch
git fetch --tags
```

需要配置的 Secret 以及签名失败的排查方法见[签名与发布](docs/签名与发布.md)。

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

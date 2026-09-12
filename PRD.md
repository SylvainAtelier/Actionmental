# Android 外接键盘快捷键与屏幕控制工具需求文档

## 1. 项目目标

开发一个 Android 系统增强工具，主要面向使用实体键盘、平板、桌面模式和外接显示器的用户。

应用需要提供以下核心能力：

1. 全局监听实体键盘输入
2. 自定义全局快捷键
3. 实时查看当前键盘输入
4. 查看所有已绑定快捷键
5. 检测快捷键冲突并允许覆盖
6. 执行系统级快捷操作
7. 通过 Shizuku 执行需要 Shell 权限的操作
8. 强制屏幕横屏、竖屏或恢复系统默认旋转
9. 支持针对指定应用设置屏幕方向规则
10. 在 Android 快捷设置栏提供控制磁贴
11. 快捷设置磁贴必须显示真实系统状态
12. 提供清晰的权限、服务和运行状态管理

应用应尽量在不需要 Root 的情况下工作。

主要高权限能力通过 Shizuku 提供。

---

# 2. 产品定位

应用可以视为 Android 平台上的实体键盘快捷键管理器与系统控制工具。

核心定位：

Android Keyboard Shortcut Manager

后续可以逐步扩展为 Android 自动化工具。

第一阶段重点围绕：

实体键盘

快捷键

系统操作

屏幕旋转

Shizuku

快捷设置栏

进行开发。

---

# 3. 基本设计原则

## 3.1 单一事件入口

所有实体键盘事件必须进入统一的键盘事件处理层。

界面中的按键检测、快捷键录制、快捷键匹配和快捷键执行必须使用相同的按键标准化逻辑。

禁止不同模块各自解析 KeyEvent。

---

## 3.2 单一状态来源

系统状态不得仅依赖应用自己保存的状态。

例如强制横屏是否开启，应读取实际系统状态。

快捷设置磁贴、应用界面和快捷键执行完成后的状态显示，都应以真实系统状态为最终依据。

---

## 3.3 UI 与执行逻辑分离

界面不得直接执行系统命令。

所有动作都应经过统一的 Action 执行层。

例如：

打开应用

返回

主页

音量控制

媒体控制

屏幕旋转

Shell 操作

都应抽象为统一的 Action。

---

## 3.4 权限能力分层

普通 Android API 可以完成的操作优先使用普通 API。

需要无障碍能力的操作通过 AccessibilityService 完成。

需要 Shell 权限的操作通过 Shizuku 完成。

不得因为已经使用 Shizuku，而将所有功能全部放入 Shizuku 层。

---

# 4. 功能模块

# 4.1 键盘监听服务

应用需要提供持续运行的实体键盘监听能力。

监听范围：

实体 USB 键盘

蓝牙键盘

Android 可识别的其他实体键盘设备

主要职责：

接收按键事件

维护当前按键状态

识别修饰键

将事件发送至快捷键引擎

将实时输入状态提供给应用界面

支持快捷键录制模式

需要能够识别：

Ctrl

Alt

Shift

Meta

普通键

方向键

功能键

媒体键

Android 系统键

支持 KeyCode。

建议同时保留 ScanCode 和设备信息，为未来高级绑定能力预留空间。

---

# 4.2 实时按键检测

应用必须提供一个实时按键检测界面。

用户按下实体键盘后，应实时显示当前组合。

例如：

Ctrl

Ctrl + Alt

Ctrl + Alt + L

松开后应同步更新状态。

界面建议显示：

当前按下的组合键

最后一个 KeyCode

ScanCode

按键状态

按下或松开

当前输入设备名称

设备标识

当前快捷键是否已经存在绑定

如果存在绑定，需要显示绑定的操作名称。

此页面同时用于调试键盘兼容性。

---

# 4.3 快捷键录制

用户创建或编辑快捷键时，不应要求手动选择每一个按键。

应提供快捷键录制模式。

用户进入录制状态后直接按实体键盘组合。

例如：

Ctrl + Alt + L

系统自动生成对应快捷键组合。

录制期间：

不得执行原有快捷键操作

只记录用户输入

需要明确显示正在录制

用户可以取消录制

用户可以重新录制

录制完成后进入冲突检查流程。

---

# 4.4 快捷键标准化

系统必须将快捷键组合标准化。

例如：

先按 Ctrl 再按 Alt 再按 L

以及

先按 Alt 再按 Ctrl 再按 L

应被识别为同一个快捷键：

Ctrl + Alt + L

快捷键至少由以下信息组成：

主键

Ctrl 状态

Alt 状态

Shift 状态

Meta 状态

未来可以扩展：

设备限制

物理键模式

长按

双击

连续按键

前台应用条件

---

# 4.5 快捷键列表

应用需要提供完整的已绑定快捷键列表。

每一项至少显示：

快捷键组合

操作名称

是否启用

适用设备

适用应用范围

点击后可进入编辑页面。

需要支持：

新增

编辑

启用

禁用

删除

覆盖

搜索

建议支持排序。

建议支持按操作类型筛选。

---

# 4.6 快捷键冲突检测

同一个快捷键不能在同一个作用域中同时绑定多个动作。

当用户录制了已经存在的快捷键时，应立即提示冲突。

提示内容至少包括：

当前快捷键

当前绑定的操作

准备替换的新操作

提供：

取消

编辑原绑定

覆盖

如果用户选择覆盖：

新的动作取代原动作

不得产生重复快捷键记录

原有快捷键记录相关元数据应尽量保留

---

# 4.7 快捷键作用域

第一阶段必须支持：

全局快捷键

建议数据结构预留：

指定键盘设备

指定前台应用

指定应用集合

未来可以支持：

仅桌面模式

仅外接显示器

仅特定输入设备

仅特定时间段

---

# 5. Action 系统

所有快捷键执行内容统一抽象为 Action。

Action 系统应可扩展。

第一阶段建议支持以下 Action 类型。

## 5.1 应用操作

启动指定应用

启动指定 Activity

---

## 5.2 系统导航

返回

主页

最近任务

---

## 5.3 媒体控制

播放暂停

上一曲

下一曲

---

## 5.4 音量控制

音量增加

音量降低

静音切换

---

## 5.5 屏幕方向

强制横屏

强制反向横屏

强制竖屏

强制反向竖屏

恢复系统默认旋转行为

切换强制横屏状态

---

## 5.6 Shell Action

允许执行经过应用授权和明确配置的 Shell 操作。

此功能应作为高级功能。

需要和普通 Action 分开管理。

---

# 6. Shizuku 模块

应用需要集成 Shizuku。

Shizuku 主要负责执行普通应用权限无法完成的系统操作。

Shizuku 模块需要负责：

检测 Shizuku 是否安装

检测 Shizuku 服务是否运行

检测当前授权状态

请求授权

检测当前运行身份

提供统一的特权服务接口

将系统控制功能暴露给上层 Action 系统

Shizuku 不负责键盘事件监听。

键盘监听与 Shizuku 必须解耦。

---

# 7. 屏幕方向控制

应用需要提供完整的屏幕方向控制能力。

支持模式：

系统默认

强制横屏

强制反向横屏

强制竖屏

强制反向竖屏

系统默认代表恢复 Android 自身的旋转策略。

强制模式需要尽量忽略前台应用自身请求的方向。

例如某应用声明只允许竖屏时，在系统允许的情况下，用户仍可以选择强制横屏。

---

# 8. 实际旋转状态检测

应用必须能够查询当前真实旋转状态。

不能仅通过应用本地 Boolean 判断状态。

状态模型至少包含：

当前是否处于用户旋转锁定状态

当前旋转方向

是否启用了固定到用户旋转

是否忽略应用方向请求

最终解析出的应用级 RotationMode

建议 RotationMode：

NORMAL

FORCE_LANDSCAPE

FORCE_REVERSE_LANDSCAPE

FORCE_PORTRAIT

FORCE_REVERSE_PORTRAIT

CUSTOM

UNKNOWN

UNKNOWN 用于：

Shizuku 不可用

查询失败

系统不支持

权限不足

OEM 行为异常

---

# 9. 应用级旋转规则

应用需要支持针对指定 App 设置屏幕方向规则。

例如：

YouTube

强制横屏

微信

系统默认

Moonlight

强制横屏

浏览器

系统默认

规则至少由以下内容组成：

应用包名

应用显示名称

旋转模式

是否启用

系统需要能够检测前台应用变化。

当前台应用切换时，根据匹配规则自动应用对应方向。

离开受控制应用后，需要根据规则恢复正确状态。

不能简单地永远保持最后一次设置。

---

# 10. 快捷设置栏磁贴

应用需要提供 Android Quick Settings 磁贴。

第一阶段至少提供：

强制横屏磁贴

可以进一步扩展：

旋转模式磁贴

快捷键服务磁贴

---

# 11. 强制横屏磁贴

磁贴点击行为：

当前不是强制横屏时，开启强制横屏。

当前已经是强制横屏时，恢复系统默认。

磁贴显示必须基于真实系统状态。

不得仅使用应用 SharedPreferences 判断。

状态建议：

ACTIVE

当前真实状态为强制横屏

INACTIVE

当前真实状态不是强制横屏

UNAVAILABLE

Shizuku 未运行

权限不可用

系统状态无法查询

磁贴可以显示：

名称

状态颜色

图标

副标题

状态描述

示例：

强制横屏

已开启

或者：

强制横屏

已关闭

或者：

强制横屏

Shizuku 不可用

---

# 12. 快捷设置状态同步

以下情况发生后，磁贴状态都必须正确：

用户点击磁贴

用户使用实体键盘快捷键切换旋转

用户在应用主界面切换旋转

用户通过应用级规则改变旋转

系统设置改变

外部 ADB 改变相关状态

其他工具改变相关状态

基本原则：

状态更新事件只用于通知系统重新查询。

最终显示值始终来源于真实系统状态。

---

# 13. 应用主界面

建议首页采用状态中心设计。

主要展示：

快捷键监听服务状态

Shizuku 状态

当前键盘设备

当前屏幕方向模式

已绑定快捷键数量

当前前台应用旋转规则

提供入口：

快捷键

按键检测

屏幕旋转

应用旋转规则

快捷设置磁贴

系统状态

设置

---

# 14. 快捷键页面

建议页面结构：

顶部搜索

快捷键列表

新增按钮

每条快捷键显示：

组合键

动作

启用状态

作用范围

点击进入编辑

长按可以提供：

启用

禁用

复制

删除

---

# 15. 快捷键编辑页面

页面至少包括：

快捷键

重新录制按钮

当前 Action

选择 Action

设备范围

应用范围

是否启用

保存

删除

如果快捷键冲突：

必须阻止静默覆盖

必须显示冲突确认界面

---

# 16. 按键检测页面

页面主要用于：

检测键盘是否正常

查看 Android 接收到的键值

帮助用户创建快捷键

排查特殊键盘兼容性

建议显示：

当前组合

KeyCode

ScanCode

修饰键状态

输入设备名称

设备 ID

Vendor 信息

Product 信息

设备 Descriptor

当前快捷键绑定

---

# 17. 键盘设备支持

系统需要识别输入事件来源设备。

第一阶段快捷键默认作用于：

所有实体键盘

架构需要预留设备级绑定。

未来支持：

所有键盘

指定键盘

多个指定键盘

建议不要仅使用临时 deviceId 作为永久设备绑定标识。

需要设计稳定的设备标识策略。

---

# 18. 数据模型

建议主要实体如下。

## Shortcut

字段建议：

id

keyCode

modifiers

actionType

actionData

label

enabled

deviceScope

appScope

createdAt

updatedAt

---

## AppRotationRule

字段建议：

id

packageName

rotationMode

enabled

createdAt

updatedAt

---

## ActionDefinition

表示可以由快捷键触发的动作。

字段根据 Action 类型决定。

建议采用可扩展结构。

---

# 19. 数据存储

快捷键配置必须持久化。

应用级屏幕方向规则必须持久化。

用户设置必须持久化。

推荐将快捷键和应用规则存储在结构化本地数据库。

简单 UI 设置可以使用轻量配置存储。

数据库必须保证同一作用域中的快捷键唯一性。

---

# 20. 推荐架构

整体推荐采用分层架构。

```text
UI
│
├── Dashboard
├── ShortcutList
├── ShortcutEditor
├── KeyMonitor
├── RotationSettings
├── AppRotationRules
└── Settings

Domain
│
├── Keyboard
├── Shortcut
├── Action
├── Rotation
└── Device

Service
│
├── AccessibilityKeyboardService
├── QuickSettingsTileService
└── ShizukuPrivilegedService

Data
│
├── ShortcutRepository
├── RotationRuleRepository
└── SettingsRepository

Platform
│
├── ShizukuBackend
├── AccessibilityBackend
├── WindowManagerBackend
├── InputDeviceBackend
└── PackageManagerBackend
```

---

# 21. 核心模块职责

## KeyboardEventSource

负责接收 Android 键盘事件。

只负责事件采集。

不得负责数据库操作。

不得负责直接执行动作。

---

## KeyEventNormalizer

负责把 Android 原始键盘事件转换为统一键盘模型。

处理：

主键

修饰键

按下状态

释放状态

重复按键

设备信息

---

## KeyStateTracker

维护当前所有按下按键状态。

用于：

实时按键检测

组合键录制

修饰键状态显示

---

## ShortcutRecorder

负责快捷键录制模式。

录制时阻止正常快捷键执行。

输出标准化 KeyCombination。

---

## ShortcutMatcher

负责根据当前组合键寻找快捷键绑定。

必须考虑：

是否启用

设备作用域

应用作用域

未来可扩展其他条件。

---

## ShortcutRepository

负责快捷键持久化。

提供：

查询

新增

编辑

删除

覆盖

冲突检测

实时监听列表变化

---

## ShortcutExecutor

接收 Shortcut 对应 Action。

根据 Action 类型调用正确执行后端。

不得直接处理键盘事件。

---

## RotationController

整个应用唯一的屏幕旋转控制入口。

提供：

读取真实状态

强制横屏

强制竖屏

反向横屏

反向竖屏

恢复系统默认

切换状态

所有模块必须通过 RotationController 修改旋转状态。

---

## RotationStateProvider

负责查询并解析 Android 当前真实旋转状态。

不得依赖应用缓存值作为最终状态。

---

## AppRotationRuleManager

监听页面前台应用变化。

查询当前应用是否存在旋转规则。

决定是否调用 RotationController。

需要处理进入和退出应用后的状态恢复。

---

## ShizukuManager

负责：

Shizuku 服务连接

权限管理

连接状态

特权服务生命周期

异常处理

---

## PrivilegedService

运行在 Shizuku 授予的特权环境中。

只提供受控的特权能力。

不要将业务逻辑放入此模块。

---

## QuickSettingsTileController

负责 Quick Settings 状态转换。

磁贴打开时读取真实状态。

点击后执行 RotationController。

执行完成后重新读取真实状态。

再更新磁贴。

---

# 22. 推荐事件流

## 普通快捷键执行

```text
实体键盘

KeyboardEventSource

KeyEventNormalizer

ShortcutMatcher

ShortcutRepository

ShortcutExecutor

Action Backend
```

---

## 快捷键录制

```text
实体键盘

KeyboardEventSource

KeyEventNormalizer

ShortcutRecorder

冲突检测

用户确认

ShortcutRepository
```

---

## 快捷设置横屏

```text
Quick Settings Tile

读取真实 RotationState

RotationController

ShizukuBackend

系统 WindowManager

重新读取真实 RotationState

更新 Tile
```

---

## 应用级自动横屏

```text
前台应用变化

AppRotationRuleManager

RotationRuleRepository

RotationController

ShizukuBackend

系统 WindowManager
```

---

# 23. 状态同步

建议内部建立统一状态通知机制。

以下状态应提供可观察数据流：

Shizuku 状态

键盘输入状态

快捷键列表

当前键盘设备

当前旋转状态

当前前台应用

当前应用旋转规则

UI 应基于状态流更新。

避免多个页面自己重复查询和维护独立状态。

---

# 24. 服务状态

首页需要明确显示：

AccessibilityService

开启

关闭

Shizuku

运行

未运行

未授权

当前权限身份

键盘

已连接

未检测到

旋转控制

正常

不可用

异常

---

# 25. 错误处理

系统不得静默失败。

例如以下情况都需要有明确状态：

Shizuku 未安装

Shizuku 未启动

Shizuku 未授权

AccessibilityService 未开启

键盘不存在

系统命令不支持

OEM 修改导致能力不可用

快捷键冲突

目标应用不存在

Action 执行失败

旋转状态无法识别

---

# 26. 安全要求

Shell Action 属于高级功能。

需要避免无提示执行未知命令。

用户创建 Shell Action 时应明确知道执行内容。

应用内部预定义系统动作应使用受控 Action，不要全部包装成自由 Shell。

不要保存不必要的敏感数据。

不需要上传用户键盘输入。

按键检测默认只在本地处理。

应用不得记录普通文本输入历史。

只维护执行快捷键所需的即时按键状态。

---

# 27. 性能要求

键盘输入处理路径必须快速。

不能因为数据库查询或 Shell 调用阻塞键盘事件线程。

快捷键匹配应尽量使用内存缓存。

数据库变化后更新快捷键缓存。

Shell 操作必须异步执行。

快捷设置状态查询不得阻塞 SystemUI。

前台应用检测应避免高频无意义操作。

---

# 28. 可靠性要求

按键按下与释放事件需要正确处理。

快捷键连续触发需要避免因长按产生大量重复执行。

服务重启后快捷键配置需要自动恢复。

应用进程重启后不得依赖旧的旋转状态缓存。

Shizuku 重启后应可以重新连接。

键盘拔出和重新连接后不应导致服务崩溃。

---

# 29. 第一阶段必须完成

P0 功能：

全局实体键盘监听

实时按键检测

快捷键录制

快捷键列表

新增快捷键

编辑快捷键

删除快捷键

冲突检测

覆盖快捷键

启用与禁用快捷键

快捷键本地持久化

Shizuku 集成

Shizuku 状态检测

系统导航 Action

应用启动 Action

媒体控制 Action

音量 Action

强制横屏

强制竖屏

恢复默认旋转

真实旋转状态查询

Quick Settings 强制横屏磁贴

磁贴真实状态同步

基础错误处理

---

# 30. 第二阶段建议功能

P1 功能：

反向横屏

反向竖屏

应用级旋转规则

不同键盘不同快捷键

快捷键搜索

快捷键分类

快捷键导入导出

多个 Quick Settings 磁贴

快捷键使用统计

应用级快捷键范围

快捷键配置备份

高级键盘设备信息

---

# 31. 后续扩展方向

P2 功能：

长按快捷键

双击快捷键

组合序列

宏

多个 Action 连续执行

Action 延迟

条件 Action

前台应用条件

外接显示器条件

桌面模式条件

自定义通知

自定义 Intent

插件 Action

用户脚本

快捷键配置分享

---

# 32. 非目标

第一阶段不需要实现：

完整 AutoHotkey 语言

复杂宏编辑器

云同步

账号系统

Root 专属功能

远程控制

跨设备同步

键盘输入内容记录

按键日志长期保存

---

# 33. 推荐技术方向

推荐：

Kotlin

Jetpack Compose

Room

Flow 或 StateFlow

AccessibilityService

TileService

Shizuku

清晰的 Domain 与 Platform 分层

业务模块尽量通过接口连接 Android 平台能力。

避免 UI、Service、Shizuku 和数据库直接互相调用。

---

# 34. 推荐模块结构

```text
app

core
    keyboard
    shortcut
    action
    rotation
    device

data
    shortcut
    rotation
    settings

platform
    accessibility
    shizuku
    input
    window
    package

service
    keyboard
    tile

ui
    dashboard
    shortcuts
    shortcut_editor
    key_monitor
    rotation
    app_rules
    settings
```

---

# 35. 架构硬性约束

开发 Agent 应遵守以下约束：

1. AccessibilityService 只负责输入与必要的 Accessibility 操作
2. Shizuku 不承担键盘监听
3. 所有快捷键统一经过 KeyEventNormalizer
4. 所有快捷键统一经过 ShortcutMatcher
5. 所有动作统一经过 ShortcutExecutor
6. 所有旋转操作统一经过 RotationController
7. 磁贴不得维护独立旋转状态
8. UI 不直接执行 Shell
9. UI 不直接访问 Shizuku Service
10. 数据库不得存储普通用户键盘输入内容
11. 快捷键冲突必须在 Repository 层可检测
12. Shortcut 组合在相同作用域中必须具有唯一性
13. 系统状态缓存只能作为性能优化，不能作为真实状态来源
14. 特权操作必须有明确的错误结果
15. 所有耗时操作必须异步执行

---

# 36. 第一阶段主要页面

最终至少需要以下页面：

Dashboard

快捷键列表

快捷键编辑

快捷键录制

实时按键检测

屏幕方向控制

Quick Settings 设置

Shizuku 状态

应用设置

如果第一阶段包含应用级旋转规则，再增加：

应用旋转规则列表

应用选择器

规则编辑页面

---

# 37. 验收测试

## 快捷键

连接实体键盘后可以检测输入。

Ctrl + Alt + L 等组合可以被正确识别。

不同按键顺序产生相同组合。

快捷键可以保存。

应用重启后快捷键仍存在。

快捷键可以编辑。

快捷键可以删除。

快捷键可以禁用。

冲突快捷键能够被识别。

用户可以选择覆盖。

覆盖后只有新的动作生效。

录制模式下原快捷键不会被执行。

---

## 动作

快捷键可以执行系统导航。

快捷键可以启动应用。

快捷键可以控制媒体。

快捷键可以控制音量。

Action 执行失败时有错误状态。

---

## Shizuku

Shizuku 未运行时应用不会崩溃。

Shizuku 未授权时能够提示。

授权后可以正常执行特权操作。

Shizuku 重启后应用可以重新恢复连接。

---

## 屏幕旋转

可以强制横屏。

可以强制竖屏。

可以恢复系统默认旋转。

应用重启后能够重新读取真实旋转状态。

如果外部改变旋转设置，应用可以读取最新状态。

---

## Quick Settings

用户可以添加磁贴。

点击磁贴可以开启强制横屏。

再次点击可以恢复默认。

磁贴状态与真实系统状态一致。

通过键盘改变状态后磁贴能够同步。

通过 App 改变状态后磁贴能够同步。

Shizuku 不可用时磁贴显示不可用，而不是错误显示为关闭。

---

## UI

按键检测实时更新。

快捷键列表实时反映数据库变化。

服务状态清晰可见。

错误信息明确。

主要功能不依赖用户理解 Android 内部实现。

---

# 38. 最终产品行为示例

用户连接实体键盘。

应用显示：

键盘已连接。

用户进入按键检测。

按下：

Ctrl + Alt + L

应用显示：

Ctrl + Alt + L

当前未绑定。

用户选择：

添加快捷键。

Action：

强制横屏。

保存。

之后用户在任何应用中按：

Ctrl + Alt + L

系统进入强制横屏。

Quick Settings 中：

强制横屏

显示已开启。

用户再次点击磁贴。

系统恢复默认方向。

磁贴显示：

已关闭。

用户再次进入快捷键页面。

Ctrl + Alt + L 仍显示：

强制横屏。

如果用户重新为 Ctrl + Alt + L 设置其他操作：

系统显示原绑定。

用户选择覆盖后：

新操作生效。

整个过程不需要 Root。

高权限操作通过 Shizuku 完成。

---

# 39. Agent 实现目标

请按照本需求文档设计并实现一个可维护、模块化、可扩展的 Android 应用。

优先保证：

架构清晰

事件处理稳定

状态一致

异常安全

快捷键无冲突

Shizuku 能力隔离

真实系统状态同步

后续容易增加新的 Action 类型

不要为了快速完成第一版而将所有逻辑写入 AccessibilityService、Activity 或单个 ViewModel。

第一阶段的代码结构应能够自然扩展到：

应用级快捷键

设备级快捷键

屏幕方向规则

宏

自定义 Action

更多 Quick Settings Tile

而不需要重构核心键盘事件链路。

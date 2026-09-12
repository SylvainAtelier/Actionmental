# Meta 组合键为什么拦不住

> 结论：**能拦，但拦不干净。** 无障碍服务照常收得到 Meta 组合键，也能把它从前台应用与输入法那里吞掉；
> 但系统自己那份动作在更早的一段就已经执行完了，任何应用都收不回来。

## 事件的顺序

一颗实体按键在 Android 里走的路是固定的，而且关键的一段不在应用手上：

```
InputReader → InputDispatcher.notifyKey
  ├─ 1. WindowManagerPolicy.interceptKeyBeforeQueueing      ← 系统按键手势在这里就执行了
  ├─ 2. AccessibilityInputFilter                            ← 我们的 onKeyEvent 在这里
  ├─ 3. InputMethod / 前台窗口
  └─ 4. WindowManagerPolicy.interceptKeyBeforeDispatching
```

第 2 步返回 `true` 只有一个确切含义：**不再往下发**（第 3、4 步收不到）。
第 1 步已经跑掉的事情，返回什么都收不回来。

Android 15 起，系统按键手势由 `KeyGestureController` 统一接管，Meta 组合键整体归它；
各家 ROM 还会在同一处加自己的一套（ColorOS 是 `KeyGestureControllerExtImpl.interceptOldShortcuts`）。

## 实测（ColorOS 16 / Android 16 / OPPO PKH120）

用 `uinput` 造一台真实路径上的外接键盘，再在 `onKeyEvent` 里打点，可以完整复现：

```
adb push kb.json /data/local/tmp/ && adb shell "uinput - < /data/local/tmp/kb.json"
```

```
KEYLOG_PhoneWindowManager: interceptKeyBeforeQueueing ... KEYCODE_A metaState=META_META_ON|META_META_LEFT_ON
AMKEY   : onKeyEvent kc=29 sc=30 action=0 meta=0x30000 consumed=false      ← 我们确实收到了
KEYLOG_KeyGestureControllerExtlmpl: interceptOldShortcuts, keyGestureType = 10   ← 系统已经动手（长截屏）
```

三点事实：

1. **事件到得了。** Meta + A / Tab / Enter / Space / `/` 全部进 `onKeyEvent`，`metaState` 也带着 `META_META_ON`。
2. **匹配与拦截是有效的。** 绑定 `Meta + Numpad +` → 启动 TeamViewer 实测命中，`consumed=true`，应用被拉起。
3. **系统那份拦不掉。** 同一次按键里 ColorOS 的截屏照样执行，`interceptOldShortcuts` 早于 `AMKEY` 一行。

顺带排除掉的两条路（都不成立）：

- `metaState` 由 InputReader 依物理按键状态算出，**先于**过滤器，所以「吞掉 Meta 自己那颗」不能让系统以为 Meta 没按。
- 设备上 shell 既没有 `MANAGE_KEY_GESTURES`（注册手势处理器）也没有 `DISABLE_INPUT_DEVICE`（禁用键盘），
  Shizuku 这条线拿不到框架级的接管权。

## 现在应用怎么做

`core/key/SystemKeyPolicy.kt` 把这条边界写成了一份提示：快捷键编辑页与映射编辑页在组合键含 Meta 时
直接标出「系统会抢先执行」。行为一点没改 —— 匹配、拦截、映射照旧，只是不再让人对着双响排查半天（PRD 25：不静默失败）。

单独一颗 Meta 还额外差一层：修饰键绑定走的是「轻点」语义，事件一律放行（见 `KeyPipeline.dispatchModifier`），
所以系统的 Meta 快捷键菜单必然还会弹出来。

## 真要彻底拦下来，只剩一条路

用 Shizuku（shell 身份，属于 `input`/`uhid` 组）**独占抓取键盘**：

1. 打开该键盘的 `/dev/input/eventN`，`EVIOCGRAB` 独占 —— 之后 Android 的 InputReader 一个事件都收不到；
2. 用 `/dev/uinput` 建一台虚拟键盘，由应用决定放行哪些、改写哪些，再发回系统。

这样 Meta 组合键在进入框架之前就被我们改掉了，`KeyGestureController` 自然没得可跑。
代价也很实在：进程一死键盘就哑（必须有看门狗与超时自动松开）、热插拔要自己管、
每台键盘都要用户点头授权。这是一块独立的子系统，不是一个补丁。

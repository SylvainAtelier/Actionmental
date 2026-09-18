<div align="center">
  <img src="assets/logo/lockup-horizontal.svg" width="460" alt="Actionmental">
  <p><strong>Physical keyboard shortcuts and screen controls for Android.</strong></p>
  <p><a href="README.zh-CN.md">简体中文</a></p>
</div>

Actionmental turns an Android-recognized physical keyboard into a system controller. It listens for key combinations, runs system actions, remaps keys, manages screen orientation, and reports the observed device state instead of presenting stale local toggles.

> Actionmental was created specifically for the Winmaxle B1. The current version has been tested by the project author and runs correctly on that device. Its input path uses Android's standard physical-keyboard APIs, so it can also work with other USB and Bluetooth keyboards.

No root access is required. Keyboard listening uses an Android Accessibility Service. Privileged features use [Shizuku](https://shizuku.rikka.app/).

## What it can do

### Physical keyboard shortcuts

- Listen for keys globally while other apps are in the foreground.
- Normalize Ctrl, Alt, Shift, Meta, and the main key regardless of press order.
- Record a shortcut by pressing it instead of selecting every key manually.
- Show conflicts before saving and replace an existing binding explicitly.
- Search, filter, duplicate, enable, disable, edit, and delete bindings. Duplicates start disabled.
- Keep shortcut matching in memory so the key path performs no disk I/O.
- Let a shortcut take priority when the same key also has a remap.
- Pause automatically when no physical keyboard is connected, if enabled.

The data model and matcher understand device and foreground-app scopes. The current editor creates global bindings. Scoped data can already be represented and imported for future UI support.

### Available shortcut actions

| Category | Actions | Backend |
| --- | --- | --- |
| System navigation | Back, Home, Recents, Notifications, Quick Settings, Lock screen | Accessibility Service |
| Media | Play or pause, previous track, next track | Android audio APIs |
| Volume | Volume up, volume down, mute toggle | Android audio APIs |
| Apps | Launch an app or a specific activity | Android package APIs, with a Shizuku fallback where needed |
| Links | Open an HTTP, HTTPS, or mail link in the matching Android app | Android intents |
| Screen orientation | Set four forced orientations, toggle landscape, cycle modes, restore Android defaults | Shizuku |
| Screen awake | Turn on, turn off, or toggle a wake lock | Android wake lock |
| Shell | Run a command configured explicitly by the user | Shizuku |

### Key remapping

- Map one key or key combination to another.
- Create, edit, enable, disable, and delete remaps.
- Start from presets such as Caps Lock to Escape, Caps Lock to Ctrl, and Ctrl+H to Backspace.
- Run a self-test before relying on an injected key.
- Suppress the original down, repeat, and up events for a remapped key.

Key injection needs Shizuku. A shortcut wins over a remap for the same input, which prevents accidental remap chains.

### Screen orientation and per-app rules

- Restore Android's normal rotation policy.
- Force landscape, reverse landscape, portrait, or reverse portrait.
- Toggle forced landscape or cycle through commonly used modes.
- Apply a rotation rule when a selected app enters the foreground.
- Restore the effective global mode after leaving a controlled app.
- Offer a diagnostic command that reapplies the current ignore-orientation setting across detected displays.
- Diagnose why rotation did not change and show the relevant shell results.

Every write is followed by a system read. If Shizuku, the ROM, or the command cannot report a trustworthy state, Actionmental shows `UNKNOWN` or unavailable instead of guessing.

### Quick Settings tiles

| Tile | Behavior |
| --- | --- |
| Screen awake | Toggles the wake lock and follows its observed state |
| Force landscape | Switches between forced landscape and the Android default |
| Rotation cycle | Cycles through the configured rotation sequence |

On Android 13 and newer, the app can request the Force Landscape tile. Add the Screen Awake and Rotation Cycle tiles from the system Quick Settings editor.

### Status, diagnostics, and recovery

- A status center for Accessibility, Shizuku, keyboard connection, rotation, screen awake, shortcut count, foreground app, and active rotation rules.
- Manual pause and resume. Pausing stops key processing and sets the screen once to 0° portrait (forced orientation released). Screen awake is not affected by pausing.
- A live key monitor with the current combination, `keyCode`, `scanCode`, key direction, input-device details, match result, and recent events.
- A bounded in-memory trace of up to 200 key events. The monitor enables collection only while it is open.
- Runtime, shell, process-exit, thermal, and process-vitals diagnostics.
- Copyable diagnostic output and clearable local logs.
- Optional process residency through a foreground service.
- Optional Shizuku hardening for the Doze allowlist and background app-operation settings, with readback verification.
- Best-effort Accessibility Service recovery after OEM task killers or a reboot, with visible notifications and an audit history.
- An eight-step onboarding flow that checks the required capabilities and installs starter shortcuts.

### Data and interface

- Store settings, shortcuts, remaps, hardening history, and rotation rules with Android DataStore.
- Export and import shortcut and remap configuration as JSON through the clipboard.
- Back up shortcuts and remaps to a JSON file and restore them through Android's document picker.
- Choose English or Simplified Chinese inside the app.
- Choose the system, light, or dark theme.
- Use a bottom navigation bar on phones, a sidebar from 600 dp, and a two-pane shortcut editor from 840 dp.
- Install debug and release variants side by side. Their package names, app labels, services, and tile labels are distinct.

## Capability requirements

| Capability | Requirement | When unavailable |
| --- | --- | --- |
| Global shortcut listening | Enable the Actionmental keyboard Accessibility Service | Key events and shortcuts do not run |
| Media, volume, links, normal app launches | Standard Android APIs | Only the affected action reports failure |
| Screen awake | `WAKE_LOCK` permission, granted at install time | The action reports unsupported or failed |
| Rotation, key remapping, Shell, privileged app launch | Shizuku running and authorized | Controls remain visible but show unavailable |
| Rotation commands | Android 12 or newer in practice | Older or incompatible ROMs report unknown |
| Add the Force Landscape tile from inside the app | Android 13 or newer | Add it manually |

The minimum Android version is Android 10, API 29. Rotation behavior varies between ROMs because `wm set-ignore-orientation-request` and `fixed_to_user_rotation` are not implemented consistently by every vendor.

## Winmaxle B1 notes

The Winmaxle B1 is the primary target device for this project. Actionmental receives its keys through Android `KeyEvent` and `InputDevice`, without a B1-specific driver or HID parser.

- Ordinary keys and modifier combinations use the same path as other Android physical keyboards.
- `keyCode`, `scanCode`, and device descriptors are visible in the Key Monitor for troubleshooting.
- Fn-layer or vendor-reserved keys may be consumed by keyboard firmware or the Android ROM before an app can see them.
- On recent Android versions, some Meta shortcuts can trigger a system action before Accessibility interception. Ctrl, Alt, or Shift combinations are more predictable.

## Privacy and permissions

Actionmental does not request the Internet permission and does not transmit keyboard events, configuration, or diagnostics itself. Android system backup is enabled, so the device's configured backup provider may copy DataStore content to cloud backup.

| Permission or access | Why it is used |
| --- | --- |
| Accessibility Service | Receive physical key events, observe the foreground package, and run Android global navigation actions |
| Shizuku API | Execute the privileged operations selected by the user |
| Query installed apps | Populate app and activity pickers and resolve launch targets |
| Notifications | Show screen-awake state, optional process residency, and recovery results |
| Wake lock | Keep the screen from turning off when explicitly enabled |
| Boot completed | Restore user-enabled background hardening after reboot |
| Foreground service | Optional process residency on ROMs that aggressively stop background services |
| Android system backup | Back up DataStore through the backup provider selected in Android settings |

Actionmental works with key codes, not typed text. Recent key traces stay in memory and are discarded with the process. Configuration and logs stay in the app's private storage, except for user exports and Android-managed backup or device transfer.

## Build from source

Prerequisites are JDK 21, Android SDK 36, and an Android device for installation checks.

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
```

On Windows, use `gradlew.bat`. The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

### Deploy and verify on Windows

```powershell
./scripts/deploy-debug.ps1
./scripts/deploy-debug.ps1 -Launch
./scripts/deploy-debug.ps1 -VerifyOnly
```

The script builds, installs, compares the installed APK with the local APK, and reports the observed Accessibility, Shizuku, and keyboard states.

Before publishing a change, run the repository hygiene check:

```powershell
./scripts/audit-public-files.ps1
```

Release signing material belongs in the ignored `.local/signing/` directory. See [Signing and release notes](docs/签名与发布.md) for the local and GitHub Actions workflows.

## Architecture

```text
AccessibilityService
  -> KeyPipeline
     -> shortcut matcher -> ActionExecutor
     -> key remap matcher -> Shizuku key injection
```

- `core/` contains key, shortcut, action, remap, rotation, awake, status, and diagnostics logic.
- `data/` persists settings and user configuration with DataStore.
- `platform/` wraps Android and Shizuku APIs.
- `service/` contains Android-created services, receivers, and tiles.
- `ui/` contains the Compose interface and ViewModels.

The UI consumes a shared `SystemStatus` flow and does not execute shell commands directly. See [ARCHITECTURE.md](ARCHITECTURE.md) for design decisions and known boundaries.

## Project status and limitations

- The codebase has JVM unit tests but no instrumented `androidTest` suite.
- Device behavior depends on the Android version, OEM ROM, keyboard firmware, and whether Android exposes a key to Accessibility.
- The shortcut engine supports device and app scopes, but the current editor exposes global shortcut creation only.
- IBM Plex fonts are referenced by the design system but are not bundled in the APK yet.

## License

Actionmental is available under the [MIT License](LICENSE).

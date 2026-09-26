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
| Screen orientation | Set four forced orientations, toggle landscape, cycle modes, restore Android defaults | Shizuku; falls back to an accessibility overlay or system settings |
| Screen awake | Turn on, turn off, or toggle a wake lock | Android wake lock |
| Shell | Run a command configured explicitly by the user | Shizuku |

### Key remapping

- Map one key or key combination to another.
- Create, edit, enable, disable, and delete remaps.
- Start from presets such as Caps Lock to Escape, Caps Lock to Ctrl, and Ctrl+H to Backspace.
- Run a self-test before relying on an injected key.
- Suppress the original down, repeat, and up events for a remapped key.

Injecting any key needs Shizuku. Without it, remaps are routed by target key: system keys (back, home, recents, screenshot, lock, D-pad) use accessibility global actions, media and volume keys use the Android audio APIs, and other keys go to the focused text field through the accessibility input channel on Android 13+. When no route works the original key passes through and is never swallowed. A shortcut wins over a remap for the same input, which prevents accidental remap chains.

### Screen orientation and per-app rules

- Restore Android's normal rotation policy.
- Force landscape, reverse landscape, portrait, or reverse portrait.
- Toggle forced landscape or cycle through commonly used modes.
- Apply a rotation rule when a selected app enters the foreground.
- Restore the effective global mode after leaving a controlled app.
- Offer a diagnostic command that reapplies the current ignore-orientation setting across detected displays.
- Diagnose why rotation did not change and show the relevant shell results.

Every write is followed by a system read. If Shizuku, the ROM, or the command cannot report a trustworthy state, Actionmental shows `UNKNOWN` or unavailable instead of guessing.

Without Shizuku, orientation is forced by an accessibility overlay (it still overrides an app's own orientation, though some tablets and foldables ignore it), and failing that by locking the angle in system settings (an app's own orientation wins). The UI shows which level is in use.

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
| Full forced rotation, injecting any key, Shell, privileged app launch | Shizuku running and authorized | Rotation and remapping run degraded; Shell and frozen-app launch show unavailable |
| Auto-rotate and angle lock while degraded | Optional: grant 'Modify system settings' | Rotation relies on the accessibility overlay only |
| Accessibility self-healing without Shizuku | Optional: `adb shell pm grant <package> android.permission.WRITE_SECURE_SETTINGS` once | Self-healing needs Shizuku |
| Remapping ordinary keys without Shizuku | Android 13+ | Only system and media keys are covered |
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

## Local commands

Every command below runs from the repository root. Gradle commands are shown in the `./gradlew` form, which works in PowerShell, Git Bash, macOS, and Linux. In `cmd.exe`, use `gradlew.bat` instead. The scripts under `scripts/` are PowerShell scripts for Windows.

| Variant | Package | Launcher activity | Accessibility Service |
| --- | --- | --- | --- |
| debug | `com.actionmental.debug` | `com.actionmental.debug/com.actionmental.ui.MainActivity` | `com.actionmental.debug/com.actionmental.service.KeyboardAccessibilityService` |
| release | `com.actionmental` | `com.actionmental/com.actionmental.ui.MainActivity` | `com.actionmental/com.actionmental.service.KeyboardAccessibilityService` |

The adb examples use the debug package. For a release build, replace `com.actionmental.debug` with `com.actionmental`.

### 1. Check the environment

You need JDK 21, Android SDK 36 with platform-tools and build-tools, and `adb` on `PATH`.

```powershell
java -version                # must report 21
adb version
adb devices                  # the device must show as "device", not "unauthorized"
```

Gradle finds the SDK through `ANDROID_HOME` or the `sdk.dir` entry in `local.properties`. That file is machine-specific and ignored by Git:

```properties
sdk.dir=C\:/path/to/Android/Sdk
```

If PowerShell blocks the scripts, allow local scripts for the current user once, or bypass the policy for a single run:

```powershell
Set-ExecutionPolicy -Scope CurrentUser RemoteSigned
powershell -ExecutionPolicy Bypass -File scripts/deploy-debug.ps1
```

### 2. Build

```powershell
./gradlew :app:assembleDebug                           # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease                         # app/build/outputs/apk/release/app-release.apk
./gradlew :app:assembleRelease "-PversionName=1.2.3"   # versionCode is derived: 1.2.3 -> 10203
./gradlew clean
```

`assembleRelease` signs the APK only when signing material exists (see section 7). Without it, Gradle produces an unsigned release APK that cannot be installed or distributed.

### 3. Test and lint

```powershell
./gradlew :app:testDebugUnitTest
./gradlew :app:testDebugUnitTest --tests "com.actionmental.ActionAndKeyCatalogTest"
./gradlew :app:lintDebug
./gradlew --no-daemon :app:testReleaseUnitTest :app:lintRelease   # the checks CI runs before a release
```

Reports are written to `app/build/reports/tests/` and `app/build/reports/lint-results-*.html`.

### 4. Install on a device

The deploy scripts are the recommended path. They build, install, compare the device's `base.apk` SHA-256 with the local APK, and report the observed Accessibility, Shizuku, and keyboard states.

```powershell
# debug
./scripts/deploy-debug.ps1                                  # build and install
./scripts/deploy-debug.ps1 -Launch                          # open the app after installing
./scripts/deploy-debug.ps1 -SkipBuild                       # install the APK that is already built
./scripts/deploy-debug.ps1 -VerifyOnly                      # no build or install, report device state only
./scripts/deploy-debug.ps1 -Device 192.168.1.5:5555         # pick a device when several are connected
./scripts/deploy-debug.ps1 -VersionCode 5                   # pass an explicit versionCode to Gradle
./scripts/deploy-debug.ps1 -Adb "C:\path\to\adb.exe"        # use an adb that is not on PATH

# release, with the same signing, version, and apksigner check as GitHub Actions
./scripts/deploy-release.ps1                                # version comes from the newest local v* tag
./scripts/deploy-release.ps1 -VersionName 1.2.0 -Launch
./scripts/deploy-release.ps1 -VersionName 1.2.0 -VersionCode 10205
./scripts/deploy-release.ps1 -SkipBuild
./scripts/deploy-release.ps1 -VerifyOnly
./scripts/deploy-release.ps1 -Device 192.168.1.5:5555
```

`deploy-release.ps1` refuses to build without signing material. If the device has a higher versionCode, the script raises the new build to that versionCode. It never uninstalls the app. If your local tags are behind the remote, run `git fetch --tags` first.

To install without the scripts:

```powershell
./gradlew :app:installDebug
adb install --no-streaming -r -d app/build/outputs/apk/debug/app-debug.apk
adb install --no-streaming -r app/build/outputs/apk/release/app-release.apk
```

`-d` allows a version downgrade and only works for the debuggable debug build. `--no-streaming` avoids silent install failures on some OEM ROMs, including ColorOS.

For wireless debugging, pair and connect first:

```powershell
adb pair 192.168.1.5:37000          # pairing port and code: Developer options > Wireless debugging
adb connect 192.168.1.5:5555
```

### 5. Set up the device with adb

```powershell
# Open the app
adb shell am start -n com.actionmental.debug/com.actionmental.ui.MainActivity

# Enable the keyboard Accessibility Service
adb shell am start -a android.settings.ACCESSIBILITY_SETTINGS
adb shell settings put secure enabled_accessibility_services com.actionmental.debug/com.actionmental.service.KeyboardAccessibilityService
adb shell settings put secure accessibility_enabled 1
adb shell settings get secure enabled_accessibility_services

# Optional: Accessibility self-healing without Shizuku (grant once, survives reboot)
adb shell pm grant com.actionmental.debug android.permission.WRITE_SECURE_SETTINGS

# Start Shizuku over adb (Shizuku must be installed and opened once)
adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh
adb shell pm list packages moe.shizuku.privileged.api

# Check that Android recognizes the physical keyboard
adb shell dumpsys input
```

`settings put secure enabled_accessibility_services` replaces the whole list. If other Accessibility Services are enabled, read the current value first, then write it back with the Actionmental entry appended after a `:` separator.

### 6. Diagnose and clean up

```powershell
adb logcat --pid=$(adb shell pidof -s com.actionmental.debug)   # this app's log only
adb logcat -c                                                    # clear the log buffer
adb shell dumpsys package com.actionmental.debug                 # versionCode, lastUpdateTime, granted permissions
adb shell am force-stop com.actionmental.debug
adb uninstall com.actionmental.debug                             # deletes shortcuts and remaps, back up in the app first
```

Log tags start with `Actionmental`, for example `Actionmental:rotation` and `Actionmental:screen-awake`. The `$(...)` syntax works in both PowerShell and bash.

### 7. Release signing

Put signing material in the ignored `.local/signing/` directory:

```text
.local/signing/
├── actionmental-release.jks
└── keystore.properties
```

```properties
storeFile=actionmental-release.jks
storePassword=<password>
keyAlias=actionmental
keyPassword=<password>
```

A relative `storeFile` is resolved against `.local/signing/`. If this file is missing, Gradle reads `ANDROID_KEYSTORE_PATH`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, and `ANDROID_KEY_PASSWORD` from the environment.

```powershell
# Generate a keystore once, outside the repository, and back it up offline.
# In a PKCS12 keystore the key password is always the store password.
keytool -genkeypair -v -keystore actionmental.jks -alias actionmental -keyalg RSA -keysize 4096 -validity 10950 -storetype PKCS12

# Check the alias and password
keytool -list -v -keystore actionmental.jks -alias actionmental

# Single-line base64 for the ANDROID_KEYSTORE_BASE64 GitHub secret
[Convert]::ToBase64String([IO.File]::ReadAllBytes("actionmental.jks")) | Set-Clipboard   # PowerShell
base64 -w 0 actionmental.jks > actionmental.jks.base64                                     # bash

# Verify a built APK's signature (apksigner is in the SDK's build-tools/<version>/)
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
```

### 8. Publish a release

Releases come from the manual `Release (Manual)` GitHub Actions workflow. It computes the next version from the newest `v*` tag, builds and signs the APK, then publishes the release. To run it with the GitHub CLI:

```powershell
./scripts/audit-public-files.ps1                   # scan files Git would publish for secrets and personal paths
./scripts/audit-public-files.ps1 -IncludeIgnored   # also scan ignored files
gh workflow run release.yml -f bump=patch          # bump: patch | minor | major
gh workflow run release.yml -f bump=minor -f prerelease=true
gh run watch
git fetch --tags
```

See [Signing and release notes](docs/签名与发布.md) for the required secrets and for troubleshooting signing failures.

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

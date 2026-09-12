<#
.SYNOPSIS
    构建 debug APK 并部署到已连接的 adb 设备，然后回读设备上的 versionCode 确认真的装上去了。

.DESCRIPTION
    与 Themental 的 install-device-icon-pack.ps1 同一套命令：
      adb devices → gradlew.bat :app:assembleDebug → adb install -r -d → 回读设备校验

    debug 的 versionCode 一律不动（默认 1）。早先这里会「现有版本 + 1」，
    结果是设备被顶到只有本脚本才追得上的版本号，`./gradlew :app:installDebug`
    再装就成了 INSTALL_FAILED_VERSION_DOWNGRADE —— 两条安装路径互相打架。

    「装上去的确实是刚构建的那一份」改用比版本号更硬的证据：
    比对设备上 base.apk 与本地 APK 的 SHA-256。取不到哈希时退回 lastUpdateTime 必须变过。
    install 带 -d（允许降级，仅对 debuggable 包有效），这样从任何历史版本号都能装回来。

.PARAMETER Adb
    adb 可执行文件路径，默认走 PATH。

.PARAMETER Device
    adb -s 的设备序列号。接了多台设备时必填。

.PARAMETER VersionCode
    手动指定 versionCode，透传给 Gradle。不填就用工程默认值（1），不再随设备递增。

.PARAMETER SkipBuild
    跳过构建，直接安装 app/build/outputs/apk/debug/ 下已有的 APK。

.PARAMETER Launch
    安装后拉起主界面。

.PARAMETER VerifyOnly
    不构建不安装，只校验设备上装的是哪个版本、无障碍服务开没开。

.EXAMPLE
    ./scripts/deploy-debug.ps1
    ./scripts/deploy-debug.ps1 -Device 192.168.1.5:5555 -Launch
    ./scripts/deploy-debug.ps1 -VerifyOnly
#>
param(
    [string]$Adb = "adb",
    [string]$Device = "",
    [int]$VersionCode = 0,
    [switch]$SkipBuild,
    [switch]$Launch,
    [switch]$VerifyOnly
)

$ErrorActionPreference = 'Stop'

function Invoke-Adb([string[]]$Arguments) {
    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $target = if ($Device) { @('-s', $Device) } else { @() }
        $output = & $Adb @target @Arguments 2>&1 | ForEach-Object { $_.ToString() }
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousPreference
    }
    if ($exitCode -ne 0) {
        throw "adb $($Arguments -join ' ') failed: $output"
    }
    return $output
}

function Get-PackageInfo([string]$Package) {
    # 未安装时 dumpsys 也不报错，匹配不到就是没装。
    $dump = (Invoke-Adb @('shell', 'dumpsys', 'package', $Package)) -join "`n"
    $version = [regex]::Match($dump, 'versionCode=(\d+)')
    $updated = [regex]::Match($dump, 'lastUpdateTime=(.+)')
    return [pscustomobject]@{
        Installed      = $version.Success
        VersionCode    = if ($version.Success) { [int]$version.Groups[1].Value } else { 0 }
        LastUpdateTime = if ($updated.Success) { $updated.Groups[1].Value.Trim() } else { '' }
    }
}

function Get-DeviceApkHash([string]$Package) {
    # 设备上的 base.apk 就是安装进去的那个文件，逐字节相同；对不上就是没装进去。
    $paths = Invoke-Adb @('shell', 'pm', 'path', $Package)
    $base = ($paths | Where-Object { $_ -match 'base\.apk$' } | Select-Object -First 1)
    if (-not $base) { return '' }
    $file = $base -replace '^package:', ''
    $out = Invoke-Adb @('shell', 'sha256sum', $file)
    $match = [regex]::Match(($out -join "`n"), '^([0-9a-f]{64})')
    if ($match.Success) { return $match.Groups[1].Value }
    return ''
}

$root = Split-Path -Parent $PSScriptRoot
$packageName = 'com.actionmental.debug'
$serviceName = "$packageName/com.actionmental.service.KeyboardAccessibilityService"

$devices = Invoke-Adb @('devices')
$connected = ($devices | Select-String -SimpleMatch "`tdevice").Count
if ($connected -eq 0) {
    throw 'No authorized Android device is connected. 先跑 adb devices 确认设备已授权。'
}
if ($connected -gt 1 -and -not $Device) {
    Write-Output $devices
    throw 'Multiple devices connected. 用 -Device <serial> 指定一台。'
}

$before = Get-PackageInfo $packageName

if ($VerifyOnly) {
    if (-not $before.Installed) {
        throw "$packageName is not installed on the connected device."
    }
} else {
    if (-not $SkipBuild) {
        $gradle = Join-Path $root 'gradlew.bat'
        $gradleArgs = @(':app:assembleDebug', '--no-daemon', '--console=plain')
        if ($VersionCode -gt 0) { $gradleArgs += "-PversionCode=$VersionCode" }
        & $gradle @gradleArgs
        if ($LASTEXITCODE -ne 0) { throw 'Gradle build failed.' }
    }

    $apk = Join-Path $root 'app/build/outputs/apk/debug/app-debug.apk'
    if (-not (Test-Path $apk)) { throw "APK not found: $apk" }
    # --no-streaming：部分 OEM（含 ColorOS）的 streaming 安装会在大包上静默失败。
    # -d：允许版本号降级。debug 包本来就 debuggable，且脚本不再抬版本号，
    #     少了它，设备上留着更高版本号时就再也装不回来。
    Invoke-Adb @('install', '--no-streaming', '-r', '-d', $apk) | Write-Output
}

$after = Get-PackageInfo $packageName
if (-not $after.Installed) { throw "$packageName is not installed on the connected device." }
$VersionCode = $after.VersionCode

if (-not $VerifyOnly) {
    # 哈希对得上就是铁证：设备上跑的正是刚构建的那个文件，与版本号无关。
    $localHash = (Get-FileHash -Algorithm SHA256 -Path $apk).Hash.ToLowerInvariant()
    $deviceHash = Get-DeviceApkHash $packageName
    if ($deviceHash) {
        if ($deviceHash -ne $localHash) {
            throw "Device APK sha256 $deviceHash != local $localHash. 安装没有生效。"
        }
    } elseif ($after.LastUpdateTime -eq $before.LastUpdateTime) {
        # 拿不到哈希（部分 ROM 会挡 pm path / sha256sum）时退而求其次
        throw 'lastUpdateTime 没有变化，安装没有生效。'
    }
}

# ── 装完之后把三项能力的真实状态打出来 ────────────────────────────────
# 这个应用只有在无障碍服务开着的时候才收得到按键；装好了但服务没开，
# 表现是「按了没反应」，很容易被当成代码 bug 排查半天。

$enabledServices = (Invoke-Adb @(
    'shell', 'settings', 'get', 'secure', 'enabled_accessibility_services'
)) -join ''
$accessibilityOn = $enabledServices -like "*$serviceName*"

$shizukuInstalled = (Invoke-Adb @('shell', 'pm', 'list', 'packages', 'moe.shizuku.privileged.api')) -join ''

$keyboards = (Invoke-Adb @('shell', 'dumpsys', 'input')) -join "`n"
$keyboardCount = ([regex]::Matches($keyboards, 'KEYBOARD_TYPE_ALPHABETIC')).Count

Write-Output ''
Write-Output "包名        $packageName"
Write-Output "versionCode $VersionCode"
Write-Output "无障碍服务  $(if ($accessibilityOn) { '已开启' } else { '未开启' })"
Write-Output "Shizuku     $(if ($shizukuInstalled) { '已安装' } else { '未安装（旋转控制与磁贴会显示不可用）' })"
Write-Output "实体键盘    $(if ($keyboardCount -gt 0) { "已连接 $keyboardCount" } else { '未检测到' })"

if (-not $accessibilityOn) {
    Write-Output ''
    Write-Output '无障碍服务未开启，快捷键收不到按键。跳到设置页：'
    Write-Output "  $Adb shell am start -a android.settings.ACCESSIBILITY_SETTINGS"
    Write-Output '也可以直接写 secure 设置（需要 WRITE_SECURE_SETTINGS，adb 有）：'
    Write-Output "  $Adb shell settings put secure enabled_accessibility_services $serviceName"
    Write-Output "  $Adb shell settings put secure accessibility_enabled 1"
}

if ($Launch -and -not $VerifyOnly) {
    Invoke-Adb @('shell', 'am', 'start', '-n', "$packageName/com.actionmental.ui.MainActivity") | Write-Output
}

if ($VerifyOnly) {
    Write-Output ''
    Write-Output "Verified $packageName versionCode=$VersionCode."
} else {
    Write-Output ''
    Write-Output "Installed $packageName versionCode=$VersionCode."
}

<#
.SYNOPSIS
    在本地复刻 GitHub Actions 的 release 产物：同一份签名、同一条构建命令、同一道
    apksigner 校验，然后装到已连接的 adb 设备上。

.DESCRIPTION
    与 .github/workflows/release.yml 的 build-android 逐步对齐：
      gradlew.bat :app:assembleRelease -PversionName=…
        → apksigner verify --print-certs
        → adb install --no-streaming -r
        → 比对设备上 base.apk 与本地 APK 的 SHA-256

    唯一的差别是签名材料从哪儿来：CI 从四个 Secret 还原 jks 并写进环境变量，
    本地读 .local/signing/keystore.properties（见 docs/签名与发布.md 第三节）。
    两条路都通向 app/build.gradle.kts 里同一个 signingConfig，出来的签名一致。

    没有签名材料时脚本直接失败，不构建。AGP 在这种情况下会出一个 signingConfig 为
    null 的包 —— 那个包装得上、跑得起来，却和 Release 页面上的不是一回事，
    等发现时往往已经拿它当「和线上一样」调试了半天。

.PARAMETER Adb
    adb 可执行文件路径，默认走 PATH。

.PARAMETER Device
    adb -s 的设备序列号。接了多台设备时必填。

.PARAMETER VersionName
    透传给 Gradle 的 versionName。不填就取本地最新的 v* tag（和 release.yml 同一条
    命令），本地 tag 也没有时才退回 app/build.gradle.kts 的默认值。
    versionCode 跟着它推导，所以只要这一个值对上，出来的包和 Release 页面上那份
    就是同一个。tag 落后于远端时先 git fetch --tags。

.PARAMETER VersionCode
    透传给 Gradle 的 versionCode。正常不需要填 —— 它由 versionName 推导
    （1.2.3 → 10203），本地和 CI 因此天然一致。
    设备上装着更高版本号时脚本会自动抬到那一档，所以这个参数只在你想钉住某个
    具体数字时才用得上。

.PARAMETER SkipBuild
    跳过构建，直接校验并安装 app/build/outputs/apk/release/ 下已有的 APK。

.PARAMETER Launch
    安装后拉起主界面。

.PARAMETER VerifyOnly
    不构建不安装，只看设备上装的是哪个版本、无障碍服务开没开。

.EXAMPLE
    ./scripts/deploy-release.ps1
    ./scripts/deploy-release.ps1 -VersionName 1.2.0 -VersionCode 12 -Launch
    ./scripts/deploy-release.ps1 -VerifyOnly
#>
param(
    [string]$Adb = "adb",
    [string]$Device = "",
    [string]$VersionName = "",
    [int]$VersionCode = 0,
    [switch]$SkipBuild,
    [switch]$Launch,
    [switch]$VerifyOnly
)

$ErrorActionPreference = 'Stop'

function Stop-WithGuidance([string]$Summary, [string]$Guidance) {
    # PowerShell 把异常消息挤成一行显示（还带一段调用栈），多行说明在终端里会糊成
    # 一整团。所以细节先原样打出来保住换行，throw 只留一句一眼能看懂的。
    Write-Host ''
    Write-Host $Guidance
    Write-Host ''
    throw $Summary
}

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

function Get-SdkRoot([string]$Root) {
    foreach ($candidate in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT)) {
        if ($candidate -and (Test-Path $candidate)) { return $candidate }
    }
    # local.properties 里 sdk.dir 的冒号是转义过的（C\:/Users/…）
    $localProps = Join-Path $Root 'local.properties'
    if (Test-Path $localProps) {
        $line = Select-String -Path $localProps -Pattern '^\s*sdk\.dir\s*=\s*(.+)$' | Select-Object -First 1
        if ($line) {
            $dir = $line.Matches[0].Groups[1].Value.Trim() -replace '\\:', ':' -replace '\\\\', '\'
            if (Test-Path $dir) { return $dir }
        }
    }
    throw 'Android SDK not found. 设 ANDROID_HOME，或在 local.properties 里写 sdk.dir。'
}

function Get-Apksigner([string]$SdkRoot) {
    # 装了多个 build-tools 时取版本号最高的那个，和 release.yml 的 sort -V | tail -n1 一致。
    $dir = Join-Path $SdkRoot 'build-tools'
    if (-not (Test-Path $dir)) { throw "找不到 $dir，SDK 里没装 build-tools。" }
    $newest = Get-ChildItem $dir -Directory |
        Sort-Object { try { [version]$_.Name } catch { [version]'0.0.0' } } |
        Select-Object -Last 1
    if (-not $newest) { throw "$dir 下没有任何 build-tools 版本。" }
    $exe = Join-Path $newest.FullName 'apksigner.bat'
    if (-not (Test-Path $exe)) { throw "找不到 $exe。" }
    return $exe
}

function Get-GradleDefaultVersionName([string]$Root) {
    # build.gradle.kts 里那个默认值。抄一份常量到脚本里迟早会和工程对不上，所以直接去读。
    $gradleFile = Join-Path $Root 'app/build.gradle.kts'
    $match = Select-String -Path $gradleFile -Pattern 'gradleProperty\("versionName"\)\.orNull \?: "([^"]+)"' |
        Select-Object -First 1
    if (-not $match) { throw "在 $gradleFile 里找不到 versionName 的默认值。" }
    return $match.Matches[0].Groups[1].Value
}

function Get-LatestTagVersion([string]$Root) {
    # 和 release.yml 的 create-release 同一条命令，取最新那个 v* tag。
    # 用它当默认版本号，本地出来的包才和 Release 页面上最新那份对得上 ——
    # 工程默认值（0.1.0）和线上没有任何关系，拿它部署等于自造一个第三种版本。
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $tags = & git -C $Root tag -l 'v*' --sort=-v:refname 2>$null
    } catch {
        return ''
    } finally {
        $ErrorActionPreference = $previous
    }
    $latest = $tags | Where-Object { $_ -match '^v[0-9]+\.[0-9]+\.[0-9]+$' } | Select-Object -First 1
    if (-not $latest) { return '' }
    return $latest.Substring(1)
}

function Test-HeadIsAtTag([string]$Root, [string]$Tag) {
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $null = & git -C $Root describe --tags --exact-match HEAD 2>$null
        $exact = ($LASTEXITCODE -eq 0)
        if (-not $exact) { return $false }
        $names = & git -C $Root tag --points-at HEAD 2>$null
        return ($names -contains $Tag)
    } catch {
        return $false
    } finally {
        $ErrorActionPreference = $previous
    }
}

function Resolve-VersionName([string]$Root, [string]$Explicit) {
    if ($Explicit) { return $Explicit }

    $tagVersion = Get-LatestTagVersion $Root
    if (-not $tagVersion) {
        $fallback = Get-GradleDefaultVersionName $Root
        Write-Warning "本地没有 v* tag，用 build.gradle.kts 的默认值 $fallback。（git fetch --tags 之后再来会更准。）"
        return $fallback
    }

    if (-not (Test-HeadIsAtTag $Root "v$tagVersion")) {
        # 版本号说这是 1.2.0，代码却是 tag 之后的 main —— 拿它当「线上那一版」排查会走岔。
        Write-Warning "版本号取自最新 tag v$tagVersion，但当前 HEAD 不在那个 tag 上，装进去的代码不是那一版。"
    }
    return $tagVersion
}

function Get-VersionCode([string]$Name) {
    # 和 app/build.gradle.kts 的 versionCodeOf 同一个公式：1.2.3 -> 10203。
    $parts = $Name.Split('.')
    if ($parts.Count -ne 3) { throw "versionName 必须是 MAJOR.MINOR.PATCH 三段数字，收到的是 '$Name'" }
    $numbers = @()
    foreach ($part in $parts) {
        $value = 0
        if (-not [int]::TryParse($part, [ref]$value) -or $value -lt 0) {
            throw "versionName 必须是 MAJOR.MINOR.PATCH 三段数字，收到的是 '$Name'"
        }
        $numbers += $value
    }
    if ($numbers[1] -gt 99 -or $numbers[2] -gt 99) {
        throw "versionName 的 MINOR 与 PATCH 不能超过 99，收到的是 '$Name'"
    }
    return $numbers[0] * 10000 + $numbers[1] * 100 + $numbers[2]
}

function Assert-SigningMaterial([string]$Root) {
    # 和 app/build.gradle.kts 的 secret() 同一套优先级：本地 properties 文件优先，
    # 没有才看环境变量。少了签名材料 AGP 不会报错，只会安静地出一个未签名的包 ——
    # 那是最难发现的一种「构建成功」。
    $signingDir = Join-Path $Root '.local/signing'
    $propsFile = Join-Path $signingDir 'keystore.properties'
    $keys = @('storeFile', 'storePassword', 'keyAlias', 'keyPassword')
    $values = @{}

    if (Test-Path $propsFile) {
        foreach ($line in Get-Content $propsFile) {
            $match = [regex]::Match($line, '^\s*([A-Za-z]+)\s*=\s*(.+?)\s*$')
            if ($match.Success) { $values[$match.Groups[1].Value] = $match.Groups[2].Value }
        }
        $missing = $keys | Where-Object { -not $values[$_] }
        if ($missing) {
            throw "keystore.properties 里缺这几项：$($missing -join ', ')。详见 docs/签名与发布.md 第三节。"
        }
        $store = $values['storeFile']
        # 相对路径以私有签名目录为基准，配置与密钥可以一起备份或迁移。
        if (-not [System.IO.Path]::IsPathRooted($store)) {
            $store = Join-Path $signingDir $store
        }
        if (-not (Test-Path $store)) {
            Stop-WithGuidance "签名用的 keystore 不在 $store。改 keystore.properties 里的 storeFile。" @"
keystore.properties 里的 storeFile 指向 $store，但那儿没有文件。

如果这是照抄 docs 里的示例路径，把它改成你自己那份 jks 的真实位置。
还没有 jks 的话，在仓库目录以外的安全位置生成一份（生成后务必离线备份，
换了密钥已安装的用户就只能卸载重装）：

    keytool -genkeypair -v -keystore actionmental.jks -alias actionmental ``
      -keyalg RSA -keysize 4096 -validity 10950 -storetype PKCS12

注意：要和 GitHub Release 那份包对得上，这里必须是同一个 jks ——
也就是 ANDROID_KEYSTORE_BASE64 那个 Secret 还原出来的那一份。
"@
        }
        return
    }

    $envNames = @('ANDROID_KEYSTORE_PATH', 'ANDROID_KEYSTORE_PASSWORD', 'ANDROID_KEY_ALIAS', 'ANDROID_KEY_PASSWORD')
    $missingEnv = $envNames | Where-Object { -not (Get-Item "env:$_" -ErrorAction SilentlyContinue).Value }
    if ($missingEnv.Count -eq $envNames.Count) {
        Stop-WithGuidance '找不到发布签名材料，拒绝构建。' @'
找不到发布签名材料，拒绝构建（不签名的 release 包和 Release 页面上的不是一回事）。
在 .local/signing/ 下建 keystore.properties，storeFile 可填同目录文件名或绝对路径：

    storeFile=actionmental-release.jks
    storePassword=你的口令
    keyAlias=actionmental
    keyPassword=你的口令

详见 docs/签名与发布.md 第三节。
'@
    }
    if ($missingEnv) {
        throw "签名环境变量缺这几个：$($missingEnv -join ', ')。"
    }
    if (-not (Test-Path $env:ANDROID_KEYSTORE_PATH)) {
        throw "ANDROID_KEYSTORE_PATH 指向 $($env:ANDROID_KEYSTORE_PATH)，但那儿没有文件。"
    }
}

$root = Split-Path -Parent $PSScriptRoot
$packageName = 'com.actionmental'
$serviceName = "$packageName/com.actionmental.service.KeyboardAccessibilityService"
$apk = Join-Path $root 'app/build/outputs/apk/release/app-release.apk'

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
        # 签名材料必须在构建之前就确认到位，而且要查到 jks 文件真的在那儿。
        # 只看 properties 文件在不在是不够的：storeFile 照抄文档里的示例路径
        # 是最常见的开局，而那要等 validateSigningRelease 跑完半分钟才报出来。
        Assert-SigningMaterial $root

        # versionCode 不单独传：app/build.gradle.kts 从 versionName 推导它。
        # 传同一个 versionName，本地和 CI 就得到同一个 versionCode —— 这正是
        # 「与 Release 使用相同版本标识并保持签名兼容」的前提。这里只是把同一个公式算一遍，
        # 好在跑 gradle 之前就看出装不装得进去。
        $effectiveName = Resolve-VersionName $root $VersionName
        if ($VersionCode -gt 0) {
            $targetCode = $VersionCode
        } else {
            $targetCode = Get-VersionCode $effectiveName
        }

        # release 不是 debuggable，adb install -d 对它无效：只有降级装不进去，
        # 版本号相同是允许覆盖的 —— 同一个 versionName 反复部署一直是同号覆盖。
        # 「装上去的确实是刚构建的那一份」不靠版本号证明，靠下面的 SHA-256 比对。
        #
        # 降级不再是失败，而是就地顶上去。
        #
        # 卸载重装是这里唯一真正会丢东西的动作（快捷键、键位映射、加固历史全在应用
        # 私有目录里，跟着卸载一起消失），所以它不该出现在一条日常部署命令的出路里。
        # 版本号在本地只决定「装不装得进去」这一件事，把它抬到设备那一档，
        # 版本号只解决能否覆盖安装，部署验证仍由下面的本地与设备 APK 哈希比较完成。
        if ($before.Installed -and $targetCode -lt $before.VersionCode) {
            Write-Warning @"
设备上是 versionCode=$($before.VersionCode)，本次算出来是 $targetCode（versionName $effectiveName）。
release 包不允许降级，这里改用 $($before.VersionCode) 顶上去（代码仍是当前工作区这一份，绝不卸载）。
"@
            $targetCode = $before.VersionCode
        }
        Write-Output "versionName $effectiveName -> versionCode $targetCode"

        # 解析出来的两个值必须原样传给 Gradle。
        #
        # 原来只在用户显式写了 -VersionName 时才传 -PversionName，于是默认那条路上
        # 上面这段校验算的是 tag 的版本（v0.1.1 → 101），Gradle 却走自己的默认值
        # （0.1.0 → 100）—— 校验说「装得进去」，构建出来的却是个降级包，
        # 报错要等到 adb install 那一步，而且看上去像是校验没做。
        $gradle = Join-Path $root 'gradlew.bat'
        $gradleArgs = @(
            ':app:assembleRelease',
            '--no-daemon',
            '--console=plain',
            "-PversionName=$effectiveName",
            "-PversionCode=$targetCode"
        )
        & $gradle @gradleArgs
        if ($LASTEXITCODE -ne 0) { throw 'Gradle build failed.' }
    }

    if (-not (Test-Path $apk)) { throw "APK not found: $apk" }

    # 和 CI 同一道关卡：apksigner 说得出证书，才算真的签上了。
    $apksigner = Get-Apksigner (Get-SdkRoot $root)
    Write-Output "Using $apksigner"
    & $apksigner verify --print-certs $apk
    if ($LASTEXITCODE -ne 0) { throw "APK 签名校验没过：$apk" }

    # --no-streaming：部分 OEM（含 ColorOS）的 streaming 安装会在大包上静默失败。
    # 不带 -d：release 包不 debuggable，降级本来就不允许，加了也只是把错误换个说法。
    try {
        Invoke-Adb @('install', '--no-streaming', '-r', $apk) | Write-Output
    } catch {
        if ("$_" -match 'INSTALL_FAILED_VERSION_DOWNGRADE') {
            # 走到这里只剩一种可能：-SkipBuild 拿了一个早先构建好的低版本包。
            # 构建路径上的降级已经在上面就地抬平了，不会再到这一步。
            Stop-WithGuidance '现成的 APK 比设备上那一版旧，装不进去。' @"
$apk 的 versionCode 低于设备上的 $($before.VersionCode)，release 包不允许降级。
去掉 -SkipBuild 重新构建即可 —— 脚本会自动把版本号抬到设备那一档，不需要卸载：
    ./scripts/deploy-release.ps1
原始错误：$_
"@
        }
        if ("$_" -match 'INSTALL_FAILED_UPDATE_INCOMPATIBLE|signatures do not match') {
            Stop-WithGuidance '签名和设备上那一份对不上。' @"
签名和设备上那一份对不上。设备上现在装的多半是 GitHub Release 的包，而本地
.local/signing/keystore.properties 指向的不是同一个 jks。

首选：把本地 properties 文件指向签出那份 Release 的同一个 jks，然后重来 ——
这条路不动设备上的数据。

只有确实拿不到那个 jks 时才卸载，而卸载会连快捷键、键位映射一起清掉。
所以先在应用里「设置 → 数据 → 备份到文件」存一份，再：
    $Adb uninstall $packageName
装好之后用「从文件恢复」读回来。

原始错误：$_
"@
        }
        throw
    }
}

$after = Get-PackageInfo $packageName
if (-not $after.Installed) { throw "$packageName is not installed on the connected device." }

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
Write-Output "包名        $packageName (release)"
Write-Output "versionCode $($after.VersionCode)"
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

Write-Output ''
if ($VerifyOnly) {
    Write-Output "Verified $packageName versionCode=$($after.VersionCode)."
} else {
    Write-Output "Installed $packageName versionCode=$($after.VersionCode)."
}

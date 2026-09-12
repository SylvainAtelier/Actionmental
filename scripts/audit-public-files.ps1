<#
.SYNOPSIS
    Checks files that Git would publish for common secrets and machine-specific data.

.DESCRIPTION
    Reports only the file, line number, and rule name. Matching text is never printed.
    Run this before committing or opening a release.
#>
param(
    [switch]$IncludeIgnored
)

$ErrorActionPreference = 'Stop'

$files = @{}
foreach ($relativePath in @(& git -c core.quotepath=false ls-files --cached)) {
    if ($relativePath) { $files[$relativePath] = 'index' }
}
if ($LASTEXITCODE -ne 0) { throw 'git ls-files --cached failed.' }

foreach ($relativePath in @(& git -c core.quotepath=false ls-files --others --exclude-standard)) {
    if ($relativePath -and -not $files.ContainsKey($relativePath)) { $files[$relativePath] = 'worktree' }
}
if ($LASTEXITCODE -ne 0) { throw 'git ls-files --others failed.' }

if ($IncludeIgnored) {
    foreach ($relativePath in @(& git -c core.quotepath=false ls-files --others --ignored --exclude-standard)) {
        if ($relativePath -and -not $files.ContainsKey($relativePath)) { $files[$relativePath] = 'worktree' }
    }
    if ($LASTEXITCODE -ne 0) { throw 'git ls-files --ignored failed.' }
}

$binaryExtensions = @(
    '.7z', '.aab', '.apk', '.bin', '.class', '.dex', '.gif', '.ico', '.jar', '.jpeg',
    '.jpg', '.jks', '.keystore', '.pdf', '.png', '.so', '.webp', '.zip'
)
$sensitiveNames = @(
    '.env', '.env.local', 'google-services.json', 'keystore.properties', 'local.properties'
)
$rules = @(
    [pscustomobject]@{ Name = 'private-key'; Severity = 'HIGH'; Pattern = '-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----' },
    [pscustomobject]@{ Name = 'known-token'; Severity = 'HIGH'; Pattern = '(?:github_pat_[A-Za-z0-9_]{20,}|gh[pousr]_[A-Za-z0-9]{20,}|sk-[A-Za-z0-9]{20,}|AIza[0-9A-Za-z_-]{30,})' },
    [pscustomobject]@{ Name = 'credential-in-url'; Severity = 'HIGH'; Pattern = '[a-z][a-z0-9+.-]*://[^/\s:@]+:[^/\s@]+@' },
    [pscustomobject]@{ Name = 'personal-windows-path'; Severity = 'MEDIUM'; Pattern = '(?i)[A-Z]:[\\/](?:Users|Documents and Settings)[\\/][A-Z0-9._-]{1,64}(?:[\\/]|\b)' },
    [pscustomobject]@{ Name = 'personal-unix-path'; Severity = 'MEDIUM'; Pattern = '(?:/Users/|/home/)[A-Za-z0-9._-]{1,64}(?:/|\b)' },
    [pscustomobject]@{ Name = 'email-address'; Severity = 'MEDIUM'; Pattern = '(?i)\b[A-Z0-9._%+-]+@(?!(?:example\.(?:com|org|net)|example\.test|test|invalid)\b)[A-Z0-9.-]+\.[A-Z]{2,}\b' }
)

$findings = [System.Collections.Generic.List[object]]::new()

foreach ($relativePath in @($files.Keys | Sort-Object)) {
    $leaf = Split-Path -Leaf $relativePath
    $extension = [IO.Path]::GetExtension($relativePath).ToLowerInvariant()
    $isSensitiveName = $sensitiveNames -contains $leaf.ToLowerInvariant() -or
        $extension -in @('.jks', '.keystore', '.p12', '.pfx', '.pem')

    if ($isSensitiveName) {
        $findings.Add([pscustomobject]@{
            Severity = 'HIGH'
            Rule = 'sensitive-filename'
            File = $relativePath
            Line = 0
        })
    }

    if ($binaryExtensions -contains $extension) {
        continue
    }

    try {
        $lines = if ($files[$relativePath] -eq 'index') {
            $indexed = @(& git show ":$relativePath" 2>$null)
            if ($LASTEXITCODE -ne 0) { throw 'cannot read staged content' }
            $indexed
        } else {
            @(Get-Content -LiteralPath $relativePath -ErrorAction Stop)
        }
        $lineNumber = 0
        foreach ($line in $lines) {
            $lineNumber++
            foreach ($rule in $rules) {
                if ($line -match $rule.Pattern) {
                    $findings.Add([pscustomobject]@{
                        Severity = $rule.Severity
                        Rule = $rule.Name
                        File = $relativePath
                        Line = $lineNumber
                    })
                }
            }
        }
    } catch {
        $findings.Add([pscustomobject]@{
            Severity = 'MEDIUM'
            Rule = 'unreadable-file'
            File = $relativePath
            Line = 0
        })
    }
}

Write-Output "Public candidate files checked: $($files.Keys.Count)"
if ($findings.Count -eq 0) {
    Write-Output 'No common secrets or machine-specific identifiers found.'
    exit 0
}

$findings |
    Sort-Object Severity, File, Line, Rule -Unique |
    Format-Table Severity, Rule, File, Line -AutoSize |
    Out-String |
    Write-Output

Write-Error "Public-file audit found $($findings.Count) item(s). Review every location before release."
exit 1

param(
    [string]$Label = 'client-view',
    [ValidateSet('unchanged', 'true', 'false')]
    [string]$Clipping = 'unchanged',
    [ValidateSet('unchanged', 'true', 'false')]
    [string]$Rendering = 'unchanged',
    [string]$InspectPixel = ''
)

if ($Label -notmatch '^[A-Za-z0-9_-]+$') { throw 'Invalid screenshot label' }
if ($InspectPixel -and $InspectPixel -notmatch '^\d+,\d+$') {
    throw 'Inspection pixel must be two nonnegative physical screenshot coordinates'
}
$identity = Get-ClientIdentity
$control = Join-Path $profile '.voxy\terrain\control.properties'
$original = Read-Shared $control
$text = $original
foreach ($setting in @{ clip = $Clipping; render = $Rendering }.GetEnumerator()) {
    if ($setting.Value -eq 'unchanged') { continue }
    $pattern = '(?m)^' + $setting.Key + '\s*=.*$'
    $value = $setting.Key + '=' + $setting.Value
    if ($text -match $pattern) {
        $text = [Text.RegularExpressions.Regex]::Replace($text, $pattern, $value)
    } else {
        $text += "`r`n$value`r`n"
    }
}
if ($text -match '(?m)^screenshot\s*=') {
    $text = [Text.RegularExpressions.Regex]::Replace($text, '(?m)^screenshot\s*=.*$', 'screenshot=' + $Label)
} else {
    $text += "`r`nscreenshot=$Label`r`n"
}
try {
    if ($Clipping -ne 'unchanged' -or $Rendering -ne 'unchanged' -or $InspectPixel) {
        # Screenshot.grab reads the preceding framebuffer inside control().
        # Apply the setting without that action, then capture in a later tick.
        $settingsOnly = [Text.RegularExpressions.Regex]::Replace($text, '(?m)^screenshot\s*=.*\r?\n?', '')
        $settingsOnly = [Text.RegularExpressions.Regex]::Replace($settingsOnly, '(?m)^inspect\s*=.*\r?\n?', '')
        if ($InspectPixel) { $settingsOnly += "`r`ninspect=$InspectPixel`r`n" }
        [IO.File]::WriteAllText($control, $settingsOnly, [Text.UTF8Encoding]::new($false))
        $settingsApplied = [DateTimeOffset]::UtcNow
        Start-Sleep -Seconds 3
        $settledStatus = (Read-Shared (Join-Path $profile '.voxy\terrain\status.json')) | ConvertFrom-Json
        if ($settledStatus.timeMillis -le $settingsApplied.ToUnixTimeMilliseconds()) {
            throw 'Client status did not progress after the screenshot setting changed'
        }
        if ($InspectPixel) {
            $inspectionPath = Join-Path $profile '.voxy\terrain\inspection.json'
            $inspectionText = Read-Shared $inspectionPath
            $inspection = $inspectionText | ConvertFrom-Json
            if ($inspection.timeMillis -le $settingsApplied.ToUnixTimeMilliseconds()) {
                throw 'No new rendered-frame inspection arrived'
            }
            $inspectionTarget = Join-Path $state ($Label + '.inspection.json')
            [IO.File]::WriteAllText($inspectionTarget, $inspectionText, [Text.UTF8Encoding]::new($false))
        }
    }
    $requested = (Get-Date).ToUniversalTime()
    [IO.File]::WriteAllText($control, $text, [Text.UTF8Encoding]::new($false))
    Start-Sleep -Seconds 3
    $image = Get-ChildItem -LiteralPath (Join-Path $profile 'screenshots') -Filter '*.png' |
        Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
    if (-not $image -or $image.LastWriteTimeUtc -lt $requested) {
        throw 'New screenshot has not arrived'
    }
    $target = Join-Path $state ($Label + '.png')
    [IO.File]::Copy($image.FullName, $target, $true)
} finally {
    [IO.File]::WriteAllText($control, $original, [Text.UTF8Encoding]::new($false))
}
[PSCustomObject]@{
    utc = (Get-Date).ToUniversalTime().ToString('o')
    requestedUtc = $requested.ToString('o')
    screenshotUtc = $image.LastWriteTimeUtc.ToString('o')
    sourceName = $image.Name
    minecraftPid = $identity.process.Id
    startUtc = $identity.startUtc
    clientSha256 = $identity.sha256
    clipping = $Clipping
    rendering = $Rendering
    inspectedPixel = $InspectPixel
    inspectionTimeMillis = if ($inspection) { $inspection.timeMillis } else { $null }
    inspectionHits = if ($inspection) { @($inspection.hits).Count } else { $null }
    inspectionSha256 = if ($inspectionTarget) { Hash-Shared $inspectionTarget } else { $null }
    settingsAppliedUtc = if ($settingsApplied) { $settingsApplied.ToString('o') } else { $null }
    settledStatus = $settledStatus
    sha256 = Hash-Shared $target
} | ConvertTo-Json -Depth 6 -Compress

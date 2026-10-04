param([string]$Label = 'cached-zoom')

if ($Label -notmatch '^[A-Za-z0-9_-]+$') { throw 'Invalid screenshot label' }
$identity = Get-ClientIdentity
$control = Join-Path $profile '.voxy\terrain\control.properties'
$statusPath = Join-Path $profile '.voxy\terrain\status.json'
$original = Read-Shared $control
$before = (Read-Shared $statusPath) | ConvertFrom-Json
$samples = [Collections.Generic.List[Object]]::new()
$started = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
try {
    [IO.File]::WriteAllText($control, "network=false`nrender=true`nclip=true`npixels=64`nfov=30`n", [Text.UTF8Encoding]::new($false))
    for ($i = 0; $i -lt 80; $i++) {
        if ($i -eq 12) {
            [IO.File]::WriteAllText($control,
                "network=false`nrender=true`nclip=true`npixels=64`nfov=30`nscreenshot=$Label`n", [Text.UTF8Encoding]::new($false))
        }
        if ($i -eq 30) {
            $image = Get-ChildItem -LiteralPath (Join-Path $profile 'screenshots') -Filter '*.png' |
                Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
            if ([DateTimeOffset]$image.LastWriteTimeUtc -lt [DateTimeOffset]::FromUnixTimeMilliseconds($started)) {
                throw 'No settled zoom screenshot was created'
            }
            [IO.File]::Copy($image.FullName, (Join-Path $state ($Label + '.png')), $true)
        }
        $status = (Read-Shared $statusPath) | ConvertFrom-Json
        $samples.Add([PSCustomObject]@{
            elapsedMs = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() - $started
            status = $status
        })
        Start-Sleep -Milliseconds 500
    }
} finally {
    [IO.File]::WriteAllText($control, $original, [Text.UTF8Encoding]::new($false))
}
[PSCustomObject]@{
    utc = (Get-Date).ToUniversalTime().ToString('o')
    startedMillis = $started
    seconds = 40
    networkDisabled = $true
    fov = 30
    pixelSize = 64
    controlRestored = $true
    minecraftPid = $identity.process.Id
    startUtc = $identity.startUtc
    sha256 = $identity.sha256
    before = $before
    samples = $samples
} | ConvertTo-Json -Depth 6 -Compress

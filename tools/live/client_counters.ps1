param([int]$Seconds = 15)

$identity = Get-ClientIdentity
$path = Join-Path $profile '.voxy\terrain\status.json'
$before = (Read-Shared $path) | ConvertFrom-Json
$first = (Get-Date).ToUniversalTime().ToString('o')
Start-Sleep -Seconds $Seconds
$after = (Read-Shared $path) | ConvertFrom-Json
[PSCustomObject]@{
    utc = (Get-Date).ToUniversalTime().ToString('o')
    firstObservedUtc = $first
    observationOnly = $true
    before = $before
    after = $after
    ready = Read-Shared (Join-Path $state 'ready')
    realProcess = [PSCustomObject]@{
        pid = $identity.process.Id
        startUtc = $identity.startUtc
        sha256 = $identity.sha256
        sameHandleAlive = -not $identity.process.HasExited
    }
} | ConvertTo-Json -Depth 6 -Compress

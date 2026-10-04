param([int]$Seconds = 240)

$original = Get-ClientIdentity
$oldProcess = $original.process
$helpers = @(Get-BackupStatus | ForEach-Object {
    $helper = Get-Process -Id $_.pid
    $null = $helper.Handle
    [PSCustomObject]@{ identity = $_; process = $helper }
})
$output = Join-Path $state 'client-update-observations.jsonl'
$file = [IO.File]::Open($output, [IO.FileMode]::Create, [IO.FileAccess]::Write,
    ([IO.FileShare]::ReadWrite -bor [IO.FileShare]::Delete))
$writer = [IO.StreamWriter]::new($file, [Text.UTF8Encoding]::new($false))
$last = $null
try {
    for ($i = 0; $i -lt $Seconds; $i++) {
        $status = $null
        try {
            $status = (Read-Shared (Join-Path $profile '.voxy\terrain\status.json')) | ConvertFrom-Json
        } catch {}
        $last = [PSCustomObject]@{
            utc = (Get-Date).ToUniversalTime().ToString('o')
            oldMinecraftPid = $oldProcess.Id
            oldMinecraftStartUtc = $original.startUtc
            oldMinecraftSha256 = $original.sha256
            oldMinecraftSameHandleAlive = -not $oldProcess.HasExited
            windows = @(Get-Process -Name java, javaw -ErrorAction SilentlyContinue |
                Where-Object { $_.MainWindowTitle -eq 'Aero smp' } |
                ForEach-Object {
                    [PSCustomObject]@{
                        pid = $_.Id
                        startUtc = $_.StartTime.ToUniversalTime().ToString('o')
                    }
                })
            installedSha = Hash-Shared (Join-Path $profile 'mods\voxy-client-debug.jar')
            starting = Read-Shared (Join-Path $state 'starting')
            ready = Read-Shared (Join-Path $state 'ready')
            status = $status
            helpers = @($helpers | ForEach-Object {
                [PSCustomObject]@{
                    identity = $_.identity
                    sameHandleAlive = -not $_.process.HasExited
                }
            })
        }
        $writer.WriteLine(($last | ConvertTo-Json -Depth 7 -Compress))
        $writer.Flush()
        Start-Sleep -Seconds 1
    }
} finally {
    $writer.Dispose()
}
[PSCustomObject]@{
    utc = (Get-Date).ToUniversalTime().ToString('o')
    file = 'client-update-observations.jsonl'
    observations = $Seconds
    last = $last
} | ConvertTo-Json -Depth 7 -Compress

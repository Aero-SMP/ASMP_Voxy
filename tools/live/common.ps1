$ErrorActionPreference = 'Stop'
$profile = 'C:\Users\USER\AppData\Roaming\ModrinthApp\profiles\Aero SMP (1)'
$state = Join-Path $profile '.voxy\updater'

function Read-Shared($path) {
    try {
        $file = [IO.File]::Open($path, [IO.FileMode]::Open, [IO.FileAccess]::Read,
            ([IO.FileShare]::ReadWrite -bor [IO.FileShare]::Delete))
    } catch {
        return $null
    }
    $reader = [IO.StreamReader]::new($file)
    try { return $reader.ReadToEnd() } finally { $reader.Dispose() }
}

function Hash-Shared($path) {
    try {
        $file = [IO.File]::Open($path, [IO.FileMode]::Open, [IO.FileAccess]::Read,
            ([IO.FileShare]::ReadWrite -bor [IO.FileShare]::Delete))
    } catch {
        return $null
    }
    $sha = [Security.Cryptography.SHA256]::Create()
    try {
        return [BitConverter]::ToString($sha.ComputeHash($file)).Replace('-', '').ToLower()
    } finally {
        $sha.Dispose()
        $file.Dispose()
    }
}

function Get-ClientIdentity {
    $marker = Read-Shared (Join-Path $state 'ready')
    if (-not $marker) { throw 'Testing client has no readiness marker' }
    $identity = $marker.Trim().Split(' ')
    if ($identity.Count -ne 2) { throw 'Invalid testing client readiness marker' }
    $client = Get-Process -Id ([int]$identity[0])
    if ($client.MainWindowTitle -ne 'Aero smp') { throw 'Testing client window differs' }
    $null = $client.Handle
    $installed = Hash-Shared (Join-Path $profile 'mods\voxy-client-debug.jar')
    if ($installed -ne $identity[1]) { throw 'Installed artifact differs from the ready client' }
    return [PSCustomObject]@{
        process = $client
        sha256 = $installed
        startUtc = $client.StartTime.ToUniversalTime().ToString('o')
    }
}

function Get-BackupStatus {
    foreach ($name in @('BackupSSH', 'BackupSSHsecondary')) {
        $directory = Join-Path $env:LOCALAPPDATA ('AeroSMP\' + $name)
        $helperId = [int][IO.File]::ReadAllText((Join-Path $directory 'helper.pid')).Trim()
        $helper = Get-Process -Id $helperId
        [PSCustomObject]@{
            name = $name
            pid = $helperId
            startUtc = $helper.StartTime.ToUniversalTime().ToString('o')
            tunnelPids = @(Get-CimInstance Win32_Process -Filter "ParentProcessId=$helperId AND Name='ssh.exe'" |
                Select-Object -ExpandProperty ProcessId)
        }
    }
}

Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class ClientStatusWindow {
    [DllImport("user32.dll")]
    public static extern IntPtr GetForegroundWindow();
}
'@
$status = (Read-Shared (Join-Path $profile '.voxy\terrain\status.json')) | ConvertFrom-Json
$control = Read-Shared (Join-Path $profile '.voxy\terrain\control.properties')
$windows = @(Get-Process -Name java, javaw -ErrorAction SilentlyContinue |
    Where-Object { $_.MainWindowTitle -eq 'Aero smp' } |
    ForEach-Object {
        [PSCustomObject]@{
            pid = $_.Id
            title = $_.MainWindowTitle
            startUtc = $_.StartTime.ToUniversalTime().ToString('o')
            foreground = $_.MainWindowHandle -eq [ClientStatusWindow]::GetForegroundWindow()
        }
    })
[PSCustomObject]@{
    utc = (Get-Date).ToUniversalTime().ToString('o')
    ready = Read-Shared (Join-Path $state 'ready')
    installedSha = Hash-Shared (Join-Path $profile 'mods\voxy-client-debug.jar')
    control = @($control -split "`n" | Where-Object { $_ -match '^(network|render|clip|pixels|fov)=' })
    windows = $windows
    status = $status
} | ConvertTo-Json -Depth 6 -Compress

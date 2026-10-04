param([string]$Label = 'allocation', [int]$Seconds = 60)

if ($Label -notmatch '^[A-Za-z0-9_-]+$') { throw 'Invalid recording label' }
$identity = Get-ClientIdentity
Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class AllocationWindow {
    [DllImport("user32.dll")]
    public static extern IntPtr GetForegroundWindow();
}
'@
$foreground = [AllocationWindow]::GetForegroundWindow() -eq $identity.process.MainWindowHandle
if (-not $foreground) { throw 'Minecraft is not foreground; allocation recording not started' }
$tools = Join-Path $state 'diagnostic-tools'
Expand-Archive -LiteralPath (Join-Path $state 'diagnostic-tools.zip') -DestinationPath $tools -Force
$java = 'C:\Users\USER\AppData\Roaming\ModrinthApp\meta\java_versions\zulu21.44.17-ca-jre21.0.8-win_x64\bin\java.exe'
$settings = Join-Path $state 'minimal-allocation.jfc'
$recording = Join-Path $state ($Label + '.jfr')
$result = @(& $java --module-path $tools "-Djava.library.path=$tools" -m jdk.jcmd/sun.tools.jcmd.JCmd `
    $identity.process.Id JFR.start name=VoxyAllocation "settings='$settings'" "duration=$($Seconds)s" `
    "filename='$recording'" disk=true dumponexit=true 2>&1 | ForEach-Object { [string]$_ })
if ($LASTEXITCODE -ne 0 -or !(($result -join "`n") -match 'Started recording')) {
    throw ($result -join "`n")
}
[PSCustomObject]@{
    utc = (Get-Date).ToUniversalTime().ToString('o')
    minecraftPid = $identity.process.Id
    startUtc = $identity.startUtc
    sha256 = $identity.sha256
    foreground = $foreground
    recording = $recording
    seconds = $Seconds
    result = $result
} | ConvertTo-Json -Depth 3 -Compress

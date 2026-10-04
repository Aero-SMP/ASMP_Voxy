param([Parameter(Mandatory=$true)][int]$GamePid,
      [Parameter(Mandatory=$true)][string]$ExpectedStart,
      [Parameter(Mandatory=$true)][ValidatePattern('^[A-Za-z0-9_-]+$')][string]$Label)
$ErrorActionPreference='Stop'
$ProgressPreference='SilentlyContinue'
$profile=Join-Path $env:APPDATA 'ModrinthApp\profiles\Aero SMP'
$game=Get-Process -Id $GamePid
if($game.StartTime.ToUniversalTime().ToString('o') -ne $ExpectedStart -or $game.MainWindowTitle -ne 'Aero smp') {
    throw 'Live Minecraft identity changed'
}
Add-Type -AssemblyName System.Drawing
Add-Type @'
using System;
using System.Runtime.InteropServices;
public static class VoxyWindowCapture {
    [StructLayout(LayoutKind.Sequential)]
    public struct Rect { public int Left, Top, Right, Bottom; }
    [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
    [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr handle, out Rect rect);
    [DllImport("user32.dll")] public static extern bool PrintWindow(IntPtr handle, IntPtr target, uint flags);
    [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr handle, out uint process);
}
'@
$null = [VoxyWindowCapture]::SetProcessDPIAware()
$rectangle = [VoxyWindowCapture+Rect]::new()
if (-not [VoxyWindowCapture]::GetWindowRect($game.MainWindowHandle, [ref]$rectangle)) {
    throw 'Cannot read game window bounds'
}
$foreground = [VoxyWindowCapture]::GetForegroundWindow()
$foregroundPid = [uint32]0
$null = [VoxyWindowCapture]::GetWindowThreadProcessId($foreground, [ref]$foregroundPid)
$width = $rectangle.Right - $rectangle.Left
$height = $rectangle.Bottom - $rectangle.Top
if ($width -le 0 -or $height -le 0) { throw 'Game window has no visible area' }

$directory=Join-Path $profile '.voxy-updater\cache-first-live'
[IO.Directory]::CreateDirectory($directory) | Out-Null
$imagePath=Join-Path $directory ($Label+'.png')
if(Test-Path -LiteralPath $imagePath){throw 'Screenshot evidence already exists'}
$bitmap = [Drawing.Bitmap]::new($width, $height)
$graphics = [Drawing.Graphics]::FromImage($bitmap)
try {
    if ($foregroundPid -eq $game.Id) {
        $graphics.CopyFromScreen($rectangle.Left, $rectangle.Top, 0, 0, $bitmap.Size)
        $method = 'CopyFromScreen'
    } else {
        $target = $graphics.GetHdc()
        try {
            if (-not [VoxyWindowCapture]::PrintWindow($game.MainWindowHandle, $target, 2)) {
                throw 'PrintWindow failed; no focus/input fallback attempted'
            }
        } finally { $graphics.ReleaseHdc($target) }
        $method = 'PrintWindow'
    }
    $bitmap.Save($imagePath, [Drawing.Imaging.ImageFormat]::Png)
} finally {
    $graphics.Dispose()
    $bitmap.Dispose()
}

$destination='/home/aerosmp/Desktop/ASMP_Voxy_Cache_First_Updates/project_audit/live_client/'+$Label+'.png'
& scp.exe -q -o BatchMode=yes -o StrictHostKeyChecking=yes -o ConnectTimeout=20 $imagePath ('aerosmp@ssh.aerosmp.com:'+$destination)
if($LASTEXITCODE -ne 0){throw 'Screenshot upload failed'}
[PSCustomObject]@{utc=(Get-Date).ToUniversalTime().ToString('o');pid=$game.Id;startUtc=$ExpectedStart;
    method=$method;width=$width;height=$height;path=$imagePath;serverPath=$destination;
    sha256=(Get-FileHash -LiteralPath $imagePath -Algorithm SHA256).Hash.ToLower()} | ConvertTo-Json -Compress

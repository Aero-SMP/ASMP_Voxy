$identity = Get-ClientIdentity
$minecraft = $identity.process
Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class FrameProfileKeys {
    [DllImport("user32.dll")]
    public static extern bool SetForegroundWindow(IntPtr window);
    [DllImport("user32.dll")]
    public static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")]
    public static extern bool PostMessage(IntPtr window, uint message, IntPtr key, IntPtr data);
    [DllImport("user32.dll")]
    private static extern uint GetWindowThreadProcessId(IntPtr window, out uint process);
    [DllImport("kernel32.dll")]
    private static extern uint GetCurrentThreadId();
    [DllImport("user32.dll")]
    private static extern bool AttachThreadInput(uint thread, uint other, bool attach);
    [DllImport("user32.dll")]
    private static extern bool ShowWindowAsync(IntPtr window, int command);
    [DllImport("user32.dll")]
    private static extern bool IsIconic(IntPtr window);

    public static void FocusWindow(IntPtr window, uint expectedPid) {
        uint targetPid;
        uint target = GetWindowThreadProcessId(window, out targetPid);
        if (target == 0 || targetPid != expectedPid)
            throw new InvalidOperationException("Minecraft window identity changed");
        uint ignored;
        uint foreground = GetWindowThreadProcessId(GetForegroundWindow(), out ignored);
        uint current = GetCurrentThreadId();
        bool attachedForeground = false, attachedTarget = false;
        try {
            if (foreground != 0 && foreground != current)
                attachedForeground = AttachThreadInput(current, foreground, true);
            if (target != current && target != foreground)
                attachedTarget = AttachThreadInput(current, target, true);
            ShowWindowAsync(window, IsIconic(window) ? 9 : 5);
            SetForegroundWindow(window);
        } finally {
            if (attachedTarget) AttachThreadInput(current, target, false);
            if (attachedForeground) AttachThreadInput(current, foreground, false);
        }
    }
}
'@
$activated = (New-Object -ComObject WScript.Shell).AppActivate($minecraft.Id)
[FrameProfileKeys]::FocusWindow($minecraft.MainWindowHandle, $minecraft.Id)
Start-Sleep -Milliseconds 500
$foreground = [FrameProfileKeys]::GetForegroundWindow() -eq $minecraft.MainWindowHandle
if (-not $foreground) { throw 'Minecraft did not become the foreground window; no frame capture started' }
$started = (Get-Date).ToUniversalTime()
$requests = @()
for ($i = 0; $i -lt 4; $i++) {
    if ([FrameProfileKeys]::GetForegroundWindow() -ne $minecraft.MainWindowHandle) {
        throw 'Minecraft lost foreground focus; no further frame capture started'
    }
    [FrameProfileKeys]::PostMessage($minecraft.MainWindowHandle, 0x100, [IntPtr]0x72, [IntPtr]0x003d0001) | Out-Null
    Start-Sleep -Milliseconds 200
    [FrameProfileKeys]::PostMessage($minecraft.MainWindowHandle, 0x100, [IntPtr]0x4c, [IntPtr]0x00260001) | Out-Null
    Start-Sleep -Milliseconds 250
    [FrameProfileKeys]::PostMessage($minecraft.MainWindowHandle, 0x101, [IntPtr]0x4c, [IntPtr](-1071251455)) | Out-Null
    [FrameProfileKeys]::PostMessage($minecraft.MainWindowHandle, 0x101, [IntPtr]0x72, [IntPtr](-1069744127)) | Out-Null
    $requests += (Get-Date).ToUniversalTime().ToString('o')
    Start-Sleep -Seconds 12
}
$reports = @(Get-ChildItem -LiteralPath (Join-Path $profile 'debug\profiling') -Filter '*.zip' -ErrorAction SilentlyContinue |
    Where-Object { $_.LastWriteTimeUtc -ge $started } |
    Select-Object Name, Length, @{Name='utc'; Expression={$_.LastWriteTimeUtc.ToString('o')}})
[PSCustomObject]@{
    utc = (Get-Date).ToUniversalTime().ToString('o')
    minecraftPid = $minecraft.Id
    startUtc = $identity.startUtc
    sha256 = $identity.sha256
    foreground = $foreground
    requests = $requests
    reports = $reports
    method = 'Four separate built-in ten-second client metrics recordings via targeted window key messages'
} | ConvertTo-Json -Depth 4 -Compress

$ErrorActionPreference='Stop'
if ($env:COMPUTERNAME -cne 'GIORKOSPC') {throw 'Wrong PC'}
$p=Join-Path $env:APPDATA 'ModrinthApp\profiles\Aero SMP'
@{utc=[DateTime]::UtcNow.ToString('o');host=$env:COMPUTERNAME;
 game=@(Get-Process javaw -ErrorAction SilentlyContinue|Where-Object {$_.MainWindowTitle -eq 'Aero smp'}|ForEach-Object {@{pid=$_.Id;startUtc=$_.StartTime.ToUniversalTime().ToString('o')}});
 helpers=@(Get-CimInstance Win32_Process|Where-Object {$_.Name -eq 'javaw.exe' -and $_.CommandLine -match 'aerosmp-laptop-backup-ssh.jar'}|ForEach-Object {$_.ProcessId});
 mods=@(Get-ChildItem -LiteralPath (Join-Path $p 'mods') -Filter 'ASMP_voxy*-debug.jar'|ForEach-Object {@{name=$_.Name;sha256=(Get-FileHash -LiteralPath $_.FullName).Hash.ToLowerInvariant()}});
 lease=[IO.File]::ReadAllText((Join-Path $p '.voxy-updater\cache-test-profile.txt'));
 holdExists=(Test-Path -LiteralPath (Join-Path $p '.voxy\debug-hold-regional-transport'));
 policy=(Get-Content -LiteralPath (Join-Path $p 'config\voxy-servers.json') -Raw|ConvertFrom-Json);
 latest=@(Get-Content -LiteralPath (Join-Path $p 'logs\voxy-client-debug.log') -Tail 35)
}|ConvertTo-Json -Depth 10 -Compress

$ErrorActionPreference='Stop'
$ProgressPreference='SilentlyContinue'
if ($env:COMPUTERNAME -cne 'GIORKOSPC') { throw 'Wrong PC' }
$voxyGame=Join-Path $env:APPDATA 'ModrinthApp\profiles\Aero SMP'
$audit=Join-Path $voxyGame '.voxy-updater\cache-first-live\repair257'
if (Test-Path -LiteralPath $audit) {throw 'Existing257 receipt; refusing overwrite'}
$helpers=@(Get-CimInstance Win32_Process | Where-Object {$_.Name -eq 'javaw.exe' -and $_.CommandLine -match 'aerosmp-laptop-backup-ssh.jar'})
if ($helpers.Count -ne 2) {throw 'Two backup SSH helper processes required'}
$games=@(Get-Process javaw -ErrorAction SilentlyContinue | Where-Object {$_.MainWindowTitle -eq 'Aero smp'})
if ($games.Count -ne 1) {throw 'Expected one existing real PC game'}
$clone=Join-Path $voxyGame '.voxy\test-cdce8542-bb60-4a2d-b418-eeffd878ddff'
if ([IO.File]::ReadAllText((Join-Path $clone 'cache-format')) -cne "VXY-NAMES-1`n") {throw 'Owned warm cache absent or wrong format'}
foreach ($path in @($voxyGame,$clone)) {
 $parent=[IO.DirectoryInfo]::new($path)
 while ($null -ne $parent) {
  if (($parent.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {throw 'Linked cache ancestor'}
  $parent=$parent.Parent
 }
}
$null=[IO.Directory]::CreateDirectory($audit)
foreach ($name in @('config\voxy-config.json','config\voxy-servers.json','.voxy-updater\cache-test-profile.txt')) {
 $source=Join-Path $voxyGame $name
 if (Test-Path -LiteralPath $source) { Copy-Item -LiteralPath $source -Destination (Join-Path $audit ([IO.Path]::GetFileName($source))) }
}
@{utc=[DateTime]::UtcNow.ToString('o');host=$env:COMPUTERNAME;profile=$voxyGame;audit=$audit;
 game=@($games|ForEach-Object {@{pid=$_.Id;startUtc=$_.StartTime.ToUniversalTime().ToString('o')}});
 helpers=@($helpers|ForEach-Object {@{pid=$_.ProcessId;command=$_.CommandLine}});
 mods=@(Get-ChildItem -LiteralPath (Join-Path $voxyGame 'mods') -Filter 'ASMP_voxy*-debug.jar'|ForEach-Object {@{name=$_.Name;sha256=(Get-FileHash -LiteralPath $_.FullName).Hash.ToLowerInvariant()}});
 general=(Get-Content -LiteralPath (Join-Path $voxyGame 'config\voxy-config.json') -Raw|ConvertFrom-Json);
 servers=(Get-Content -LiteralPath (Join-Path $voxyGame 'config\voxy-servers.json') -Raw|ConvertFrom-Json);
 clone=$clone;cloneBytes=[long]((Get-ChildItem -LiteralPath $clone -Recurse -File|Measure-Object Length -Sum).Sum);
 lease=[IO.File]::ReadAllText((Join-Path $voxyGame '.voxy-updater\cache-test-profile.txt'));
 holdExists=(Test-Path -LiteralPath (Join-Path $voxyGame '.voxy\debug-hold-regional-transport'));
 diskFree=[IO.DriveInfo]::new([IO.Path]::GetPathRoot($voxyGame)).AvailableFreeSpace
}|ConvertTo-Json -Depth 10 -Compress

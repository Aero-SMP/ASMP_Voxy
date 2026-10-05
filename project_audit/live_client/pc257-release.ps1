$ErrorActionPreference='Stop'
if ($env:COMPUTERNAME -cne 'GIORKOSPC') {throw 'Wrong PC'}
$p=Join-Path $env:APPDATA 'ModrinthApp\profiles\Aero SMP'
$a=Join-Path $p '.voxy-updater\cache-first-live\repair257'
foreach ($name in @('voxy-config.json','voxy-servers.json')) {
 $source=Join-Path $a $name
 if (!(Test-Path -LiteralPath $source)) {throw 'Missing original policy snapshot'}
 Copy-Item -LiteralPath $source -Destination (Join-Path $p ('config\'+$name)) -Force
}
$h=Join-Path $p '.voxy\debug-hold-regional-transport'
if (Test-Path -LiteralPath $h) {Remove-Item -LiteralPath $h}
$lease=([Guid]::NewGuid().ToString()+' off '+[DateTimeOffset]::UtcNow.AddMinutes(15).ToUnixTimeMilliseconds()+' 0')
@{utc=[DateTime]::UtcNow.ToString('o');lease=$lease;restoredSettings=$true}|ConvertTo-Json -Compress

$ErrorActionPreference='Stop'
if ($env:COMPUTERNAME -cne 'GIORKOSPC') {throw 'Wrong PC'}
$p=Join-Path $env:APPDATA 'ModrinthApp\profiles\Aero SMP'
$a=Join-Path $p '.voxy-updater\cache-first-live\repair257'
if (!(Test-Path -LiteralPath $a)) {throw 'Missing preflight snapshot'}
$lease=([Guid]::NewGuid().ToString()+' cdce8542-bb60-4a2d-b418-eeffd878ddff '+[DateTimeOffset]::UtcNow.AddMinutes(15).ToUnixTimeMilliseconds()+' 0')
$f=Join-Path $p '.voxy-updater\cache-test-profile.txt'
[IO.File]::WriteAllText(($f+'.pending'),$lease,[Text.UTF8Encoding]::new($false))
Move-Item -LiteralPath ($f+'.pending') -Destination $f -Force
$h=Join-Path $p '.voxy\debug-hold-regional-transport'
if (Test-Path -LiteralPath $h) {throw 'Preserve pre-existing transport hold'}
[IO.File]::WriteAllText($h,'repair257 bounded offline startup lease')
@{utc=[DateTime]::UtcNow.ToString('o');lease=$lease;hold=$h}|ConvertTo-Json -Compress

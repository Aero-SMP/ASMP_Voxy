$ErrorActionPreference='Stop'
if ($env:COMPUTERNAME -cne 'GIORKOSPC') {throw 'Wrong PC'}
$p=Join-Path $env:APPDATA 'ModrinthApp\profiles\Aero SMP'
$r=Join-Path $p '.voxy\regional-current'
$a=Join-Path $p '.voxy-updater\cache-first-live\repair257\normal-baseline.json'
$files=@(Get-ChildItem -LiteralPath $r -Recurse -File|Sort-Object FullName|ForEach-Object {[pscustomobject]@{path=$_.FullName.Substring($r.Length+1);bytes=$_.Length;sha256=(Get-FileHash -LiteralPath $_.FullName).Hash.ToLowerInvariant()}})
$bytes=[long](($files|ForEach-Object {$_.bytes}|Measure-Object -Sum).Sum)
if (!(Test-Path -LiteralPath $a)) {
 [IO.File]::WriteAllText($a,(ConvertTo-Json -InputObject @{value=$files;Count=$files.Count} -Depth 5),[Text.UTF8Encoding]::new($false))
 $changed=@()
} else {
 $before=(Get-Content -LiteralPath $a -Raw|ConvertFrom-Json).value
 $changed=@(Compare-Object ($before|ForEach-Object {('{0}:{1}:{2}' -f $_.path,$_.bytes,$_.sha256)}) ($files|ForEach-Object {('{0}:{1}:{2}' -f $_.path,$_.bytes,$_.sha256)}) |ForEach-Object {$_.InputObject})
}
@{utc=[DateTime]::UtcNow.ToString('o');files=$files.Count;bytes=$bytes;changed=$changed;preserved=($changed.Count -eq 0)}|ConvertTo-Json -Compress -Depth 5

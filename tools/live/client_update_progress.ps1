$file = Join-Path $state 'client-update-observations.jsonl'
$text = Read-Shared $file
$last = $null
$count = 0
foreach ($line in ($text -split "`n")) {
    if (-not $line.Trim()) { continue }
    try {
        $last = $line | ConvertFrom-Json
        $count++
    } catch {}
}
[PSCustomObject]@{
    utc = (Get-Date).ToUniversalTime().ToString('o')
    observations = $count
    last = $last
} | ConvertTo-Json -Depth 7 -Compress

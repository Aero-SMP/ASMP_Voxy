$text = Read-Shared (Join-Path $state 'command.log')
$lines = @($text -split "`n" | Where-Object {
    $_ -match '(Connection|timed out|refused|unreachable|Permission denied|Could not|Host key|ssh:|file=|sha256=)' -and
    $_ -notmatch '(token|password|PRIVATE KEY|arguments|auth)'
})
[PSCustomObject]@{
    utc = (Get-Date).ToUniversalTime().ToString('o')
    file = 'command.log'
    safeCommandStatus = $lines
} | ConvertTo-Json -Depth 3 -Compress

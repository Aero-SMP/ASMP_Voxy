$addresses = @([Net.Dns]::GetHostAddresses('ssh.aerosmp.com'))
$probes = [Collections.Generic.List[Object]]::new()
foreach ($address in $addresses) {
    $tcp = [Net.Sockets.TcpClient]::new($address.AddressFamily)
    $watch = [Diagnostics.Stopwatch]::StartNew()
    $connected = $false
    $failure = $null
    try {
        $task = $tcp.ConnectAsync($address, 22)
        if (-not $task.Wait(5000)) {
            $failure = 'TCP22 did not connect within the diagnostic interval'
        } else {
            $connected = $tcp.Connected
        }
    } catch {
        $failure = $_.Exception.GetBaseException().Message
    } finally {
        $watch.Stop()
        $tcp.Dispose()
    }
    $probes.Add([PSCustomObject]@{
        address = $address.IPAddressToString
        family = [string]$address.AddressFamily
        connected = $connected
        elapsedMs = $watch.ElapsedMilliseconds
        safeError = $failure
    })
}
[PSCustomObject]@{
    utc = (Get-Date).ToUniversalTime().ToString('o')
    host = 'ssh.aerosmp.com'
    port = 22
    authAttempted = $false
    probes = $probes
} | ConvertTo-Json -Depth 4 -Compress

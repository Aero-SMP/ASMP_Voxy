[PSCustomObject]@{
    utc = (Get-Date).ToUniversalTime().ToString('o')
    helpers = @(Get-BackupStatus)
    minecraftWindows = @(Get-Process -Name java, javaw -ErrorAction SilentlyContinue |
        Where-Object { $_.MainWindowTitle -eq 'Aero smp' } |
        Select-Object Id, MainWindowTitle)
} | ConvertTo-Json -Depth 4 -Compress

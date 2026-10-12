# The One Family screen control agent: same user session as Explorer
# Only 20-second HTTPS owner tickets, never local passwords or owner credentials.
param(
    [ValidateSet('ruben','surface','hub')][string]$Device,
    [switch]$Probe
)
$ErrorActionPreference = 'Stop'
$port = 38492
$screenScript = 'C:\TheOne\Tools\Screen-Control.ps1'
if (-not (Test-Path $screenScript)) { throw 'Existing screen-control script absent' }
$ip = Get-NetIPAddress -AddressFamily IPv4 -ErrorAction Stop |
    Where-Object { $_.IPAddress -like '192.168.178.*' -and $_.AddressState -eq 'Preferred' } |
    Select-Object -First 1 -ExpandProperty IPAddress
if (-not $ip) { throw 'Home LAN not detected' }
if ($Probe) {
    & $screenScript -Action Probe
    Write-Output ("SCREEN_AGENT_PROBE_OK DEVICE=$Device IP=$ip PORT=$port")
    exit 0
}
$listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Parse($ip), $port)
$listener.Start()
try {
    while ($true) {
        $client = $null
        try {
            $client = $listener.AcceptTcpClient()
            $client.ReceiveTimeout = 4000
            $client.SendTimeout = 4000
            $remote = ([Net.IPEndPoint]$client.Client.RemoteEndPoint).Address.ToString()
            $stream = $client.GetStream()
            $reader = [IO.StreamReader]::new($stream, [Text.Encoding]::UTF8, $false, 1024, $true)
            $writer = [IO.StreamWriter]::new($stream, [Text.UTF8Encoding]::new($false), 1024, $true)
            $writer.AutoFlush = $true
            if ($remote -notlike '192.168.178.*') { $writer.WriteLine('ERR AUTH'); continue }
            $line = $reader.ReadLine()
            if (-not $line -or $line.Length -gt 80 -or $line -cnotmatch '^(ON|OFF) ([0-9a-f]{64})$') {
                $writer.WriteLine('ERR REQUEST')
                continue
            }
            $command = $Matches[1]
            $ticket = $Matches[2]
            $postBody = @{ target=$Device; command=$command; ticket=$ticket } | ConvertTo-Json -Compress
            try {
                $result = Invoke-RestMethod -Uri 'https://rubenvanaggelen.com/the-one-remote-api/screen-control.php?action=redeem' -Method Post -ContentType 'application/json' -Body $postBody -TimeoutSec 5 -ErrorAction Stop
                if ($result.ok -ne $true -or $result.accepted -ne $true) {
                    $writer.WriteLine('ERR AUTH')
                    continue
                }
            } catch {
                $writer.WriteLine('ERR AUTH')
                continue
            }
            try {
                $output = & $screenScript -Action $(if ($command -eq 'ON') { 'On' } else { 'Off' }) 2>&1 | Out-String
                if ($output -match 'COMMAND_ACCEPTED') { $writer.WriteLine("OK DISPLAY_$command") }
                else { $writer.WriteLine('ERR DISPLAY') }
            } catch {
                $writer.WriteLine('ERR DISPLAY')
            }
        } catch {
            # Device stays alive; do not log secrets, pending tickets or owner credentials.
        } finally {
            if ($client) { $client.Close() }
        }
    }
} finally {
    $listener.Stop()
}

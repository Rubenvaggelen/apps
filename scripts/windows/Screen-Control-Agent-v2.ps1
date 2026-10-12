# The One Family - screen power relay (no sleep/shutdown)
param(
    [ValidateSet('ruben','surface','hub')][string]$Device,
    [switch]$Probe
)
$ErrorActionPreference = 'Stop'
$existing = 'C:\TheOne\Tools\Screen-Control.ps1'
if (!(Test-Path -LiteralPath $existing)) { throw 'Display command not installed' }
$local = Get-NetIPAddress -AddressFamily IPv4 -ErrorAction Stop |
  Where-Object { $_.IPAddress.StartsWith('192.168.178.') -and $_.AddressState -eq 'Preferred' } |
  Select-Object -First 1 -ExpandProperty IPAddress
if (!$local) { throw 'Home LAN not available' }
$existingSession = (Get-Process -Id $PID).SessionId
$explorerSessions = @(Get-Process -Name explorer -ErrorAction SilentlyContinue | Select-Object -ExpandProperty SessionId)
if ($existingSession -notin $explorerSessions) { throw 'Not running in interactive desktop session' }
if ($Probe) {
  & $existing -Action Probe
  Write-Output "SCREEN_RELAY_PROBE_OK $Device $local"
  exit 0
}
$listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Parse($local),38492)
$listener.Start()
try {
  while ($true) {
    $client = $null
    try {
      $client = $listener.AcceptTcpClient()
      $client.ReceiveTimeout = 5000
      $client.SendTimeout = 5000
      $remote = ([Net.IPEndPoint]$client.Client.RemoteEndPoint).Address.ToString()
      $net = $client.GetStream()
      $reader = [IO.StreamReader]::new($net,[Text.Encoding]::UTF8,$false,1024,$true)
      $writer = [IO.StreamWriter]::new($net,[Text.UTF8Encoding]::new($false),1024,$true)
      $writer.AutoFlush = $true
      if (!$remote.StartsWith('192.168.178.')) {
        $writer.WriteLine('ERR AUTH')
        continue
      }
      $request = $reader.ReadLine()
      if (!$request -or $request.Length -gt 70) {
        $writer.WriteLine('ERR REQUEST')
        continue
      }
      $parts = $request.Split(' ')
      if ($parts.Length -ne 2) {
        $writer.WriteLine('ERR REQUEST')
        continue
      }
      $action = $parts[0]
      $ticket = $parts[1]
      $validChars = '0123456789abcdef'
      $validTicket = ($ticket.Length -eq 64)
      foreach($ch in $ticket.ToCharArray()) {
        if (!$validChars.Contains([string]$ch)) { $validTicket = $false; break }
      }
      if (($action -ne 'ON' -and $action -ne 'OFF') -or !$validTicket) {
        $writer.WriteLine('ERR REQUEST')
        continue
      }
      $json = @{target=$Device;command=$action;ticket=$ticket} | ConvertTo-Json -Compress
      try {
        $reply = Invoke-RestMethod -Uri 'https://rubenvanaggelen.com/the-one-remote-api/screen-control.php?action=redeem' -Method Post -ContentType 'application/json' -Body $json -TimeoutSec 5 -ErrorAction Stop
        if (!$reply.ok -or !$reply.accepted) {
          $writer.WriteLine('ERR AUTH')
          continue
        }
      } catch {
        $writer.WriteLine('ERR AUTH')
        continue
      }
      try {
        $mode = if ($action -eq 'ON') {'On'} else {'Off'}
        $output = (& $existing -Action $mode 2>&1 | Out-String)
        if ($output.Contains('COMMAND_ACCEPTED')) {
          $writer.WriteLine("OK DISPLAY_$action")
        } else {
          $writer.WriteLine('ERR DISPLAY')
        }
      } catch {
        $writer.WriteLine('ERR DISPLAY')
      }
    } catch {
      # Fail closed without logging credentials, tokens or requests.
    } finally {
      if ($null -ne $client) { $client.Close() }
    }
  }
} finally {
  $listener.Stop()
}

# Self-healing remote connection monitor for The One Hub.
# Does not prevent sleep or change power options. Requires existing approved login.
param([switch]$Probe)
$ErrorActionPreference='SilentlyContinue'
$log='C:\TheOne\Remote\remote-health-recovery.log'
$hostPrefix='192.168.178.'
function Get-RemoteAgents {
  @(Get-CimInstance Win32_Process -ErrorAction SilentlyContinue | Where-Object {
    $_.Name -eq 'node.exe' -and $_.CommandLine -match 'desktop-commander' -and
    $_.CommandLine -match 'remote'
  })
}
function Is-Connected($agents) {
  foreach($agent in @($agents)) {
    $tcp=@(Get-NetTCPConnection -OwningProcess $agent.ProcessId -State Established -ErrorAction SilentlyContinue |
      Where-Object {$_.RemotePort -eq 443})
    if($tcp.Count -gt 0){return $true}
  }
  return $false
}
function Has-Network {
  return [bool]@(Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
    Where-Object { $_.IPAddress.StartsWith($hostPrefix) -and $_.AddressState -eq 'Preferred' }).Count
}
function Log-Safe($message) {
  Add-Content -LiteralPath $log -Value ((Get-Date).ToString('o')+' '+$message) -ErrorAction SilentlyContinue
}
$agents=@(Get-RemoteAgents)
$good=Is-Connected $agents
if($Probe){
  Write-Output ('REMOTE_RECOVERY_PROBE AGENTS='+$agents.Count+' HTTPS_CONNECTED='+$good+' NETWORK='+(Has-Network))
  exit 0
}
$mutex=New-Object System.Threading.Mutex($false,'Local\TheOneHubRemoteRecovery')
if(!$mutex.WaitOne(0)){exit 0}
try {
  $failures=0
  $lastRepair=[datetime]::MinValue
  while($true) {
    if(-not (Has-Network)){
      $failures=0
      Start-Sleep -Seconds 45
      continue
    }
    $agents=@(Get-RemoteAgents)
    if(Is-Connected $agents){
      $failures=0
    } else {
      $failures++
      if($failures -ge 3 -and ((Get-Date)-$lastRepair).TotalMinutes -ge 5){
        $lastRepair=Get-Date
        Log-Safe ('HTTPS link unhealthy for >=135sec; restarting only stale remote node agents count='+$agents.Count)
        foreach($agent in $agents) {
          Stop-Process -Id $agent.ProcessId -Force -ErrorAction SilentlyContinue
        }
        # Existing TheOneRemoteWatchdog detects the exited remote CLI and relaunches
        # using the existing authorized device registration. Never reset pairing.
        $failures=0
      }
    }
    Start-Sleep -Seconds 45
  }
} finally {
  $mutex.ReleaseMutex()
  $mutex.Dispose()
}

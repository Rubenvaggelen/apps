# The One Family: leave the Windows SYSTEM awake on AC while allowing screen OFF.
# Avoid Modern Standby suspension of remote apps/Shared Media with display dark.
# Respect user-initiated manual sleep and allow normal battery sleep.
param([switch]$Probe)
$ErrorActionPreference='Stop'
$native=@'
using System;
using System.Runtime.InteropServices;
namespace TheOneIdleGuard {
  public static class Native {
    [StructLayout(LayoutKind.Sequential)]
    public struct PowerInfo {
      public byte ACLineStatus;
      public byte BatteryFlag;
      public byte BatteryLifePercent;
      public byte Reserved;
      public uint BatteryLifeTime;
      public uint BatteryFullLifeTime;
    }
    [DllImport("kernel32.dll",SetLastError=true)]
    public static extern bool GetSystemPowerStatus(out PowerInfo status);
    [DllImport("kernel32.dll",SetLastError=true)]
    public static extern uint SetThreadExecutionState(uint flags);
  }
}
'@
if(-not ('TheOneIdleGuard.Native' -as [type])){Add-Type -TypeDefinition $native}
function Get-PluggedIn {
  $status=New-Object TheOneIdleGuard.Native+PowerInfo
  if(-not [TheOneIdleGuard.Native]::GetSystemPowerStatus([ref]$status)){return $false}
  return ($status.ACLineStatus -eq 1)
}
function Get-Desktop {
  $session=(Get-Process -Id $PID).SessionId
  $ids=@(Get-Process explorer -ErrorAction SilentlyContinue|Select-Object -ExpandProperty SessionId)
  return $session -in $ids
}
function Get-RemoteAgent {
  # Do not prevent sleep without an authorized remote agent.
  $processes=@(Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -eq 'node.exe' -and $_.CommandLine -match 'desktop-commander' -and $_.CommandLine -match 'remote' })
  $rust=Get-Service RustDesk -ErrorAction SilentlyContinue
  return ($processes.Count -gt 0 -or ($rust -and $rust.Status -eq 'Running'))
}
if($Probe) {
 Write-Output ('IDLE_GUARD_PROBE AC='+[bool](Get-PluggedIn)+' INTERACTIVE='+[bool](Get-Desktop)+' REMOTE_AGENT='+[bool](Get-RemoteAgent))
 exit 0
}
$lock=New-Object Threading.Mutex($false,'Local\TheOneRemoteKeepAwakeAC')
if(-not $lock.WaitOne(0)){exit 0}
$active=$false
try{
 while($true){
  $shouldHold=(Get-PluggedIn) -and (Get-Desktop) -and (Get-RemoteAgent)
  if($shouldHold){
    # ES_CONTINUOUS | ES_SYSTEM_REQUIRED; importantly NOT ES_DISPLAY_REQUIRED.
    [void][TheOneIdleGuard.Native]::SetThreadExecutionState([uint32]2147483649)
    $active=$true
  }elseif($active){
    [void][TheOneIdleGuard.Native]::SetThreadExecutionState([uint32]2147483648)
    $active=$false
  }
  Start-Sleep -Seconds 25
 }
}finally{
 [void][TheOneIdleGuard.Native]::SetThreadExecutionState([uint32]2147483648)
 $lock.ReleaseMutex()
 $lock.Dispose()
}

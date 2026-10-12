# The One Family remote power guard v2 for Modern Standby (S0).
# On AC only, keep system processes responsive while allowing the display to power off.
# On battery, close the power request immediately. Manual sleep is always respected.
param([switch]$Probe)
$ErrorActionPreference = 'Stop'
$native=@'
using System;
using System.Runtime.InteropServices;
namespace TheOnePowerGuardV2 {
  [StructLayout(LayoutKind.Sequential,CharSet=CharSet.Unicode)]
  public struct ReasonContext {
    public uint Version;
    public uint Flags;
    [MarshalAs(UnmanagedType.LPWStr)]
    public string SimpleReasonString;
  }
  [StructLayout(LayoutKind.Sequential)]
  public struct PowerStatus {
    public byte ACLineStatus;
    public byte BatteryFlag;
    public byte BatteryLifePercent;
    public byte Reserved;
    public uint BatteryLifeTime;
    public uint BatteryFullLifeTime;
  }
  public static class Native {
    [DllImport("kernel32.dll",CharSet=CharSet.Unicode,SetLastError=true)]
    public static extern IntPtr PowerCreateRequest(ref ReasonContext reason);
    [DllImport("kernel32.dll",SetLastError=true)]
    [return:MarshalAs(UnmanagedType.Bool)]
    public static extern bool PowerSetRequest(IntPtr handle,int requestType);
    [DllImport("kernel32.dll",SetLastError=true)]
    [return:MarshalAs(UnmanagedType.Bool)]
    public static extern bool PowerClearRequest(IntPtr handle,int requestType);
    [DllImport("kernel32.dll",SetLastError=true)]
    [return:MarshalAs(UnmanagedType.Bool)]
    public static extern bool CloseHandle(IntPtr handle);
    [DllImport("kernel32.dll",SetLastError=true)]
    [return:MarshalAs(UnmanagedType.Bool)]
    public static extern bool GetSystemPowerStatus(out PowerStatus status);
  }
}
'@
if(-not ('TheOnePowerGuardV2.Native' -as [type])) { Add-Type -TypeDefinition $native }
function Is-AC {
    $power=New-Object TheOnePowerGuardV2.PowerStatus
    if(-not [TheOnePowerGuardV2.Native]::GetSystemPowerStatus([ref]$power)){ return $false }
    return $power.ACLineStatus -eq 1
}
function Has-Remote {
    $nodes=@(Get-CimInstance Win32_Process -ErrorAction SilentlyContinue | Where-Object {
      $_.Name -eq 'node.exe' -and $_.CommandLine -match 'desktop-commander' -and $_.CommandLine -match 'remote'
    })
    $rust=Get-Service -Name RustDesk -ErrorAction SilentlyContinue
    return ($nodes.Count -gt 0 -or ($rust -and $rust.Status -eq 'Running'))
}
function New-GuardHandle {
    $reason=New-Object TheOnePowerGuardV2.ReasonContext
    $reason.Version=0
    $reason.Flags=1
    $reason.SimpleReasonString='The One Family authorized remote connection and Shared Media on AC power'
    $handle=[TheOnePowerGuardV2.Native]::PowerCreateRequest([ref]$reason)
    if($handle -eq [IntPtr]::Zero -or $handle -eq [IntPtr](-1)){
        throw ('PowerCreateRequest failed: '+[Runtime.InteropServices.Marshal]::GetLastWin32Error())
    }
    return $handle
}
function Start-Guard($handle) {
    $system=[TheOnePowerGuardV2.Native]::PowerSetRequest($handle,1)
    $execution=[TheOnePowerGuardV2.Native]::PowerSetRequest($handle,3)
    if(-not $system -or -not $execution){
        if($system) {[void][TheOnePowerGuardV2.Native]::PowerClearRequest($handle,1)}
        if($execution) {[void][TheOnePowerGuardV2.Native]::PowerClearRequest($handle,3)}
        throw ('PowerSetRequest unavailable: System='+$system+' Execution='+$execution)
    }
}
function Stop-Guard($handle){
    [void][TheOnePowerGuardV2.Native]::PowerClearRequest($handle,3)
    [void][TheOnePowerGuardV2.Native]::PowerClearRequest($handle,1)
}
$handle=New-GuardHandle
if($Probe){
    try{
        Start-Guard $handle
        Write-Output ('POWER_REQUEST_API_OK=True AC='+[bool](Is-AC)+' REMOTE='+[bool](Has-Remote))
        Stop-Guard $handle
    }finally{[void][TheOnePowerGuardV2.Native]::CloseHandle($handle)}
    exit 0
}
$mutex=New-Object Threading.Mutex($false,'Local\TheOnePowerGuardV2')
if(-not $mutex.WaitOne(0)){
   [void][TheOnePowerGuardV2.Native]::CloseHandle($handle)
   exit 0
}
$held=$false
try {
    while($true){
        $desired=(Is-AC) -and (Has-Remote)
        if($desired -and -not $held){
            Start-Guard $handle
            $held=$true
        } elseif(-not $desired -and $held) {
            Stop-Guard $handle
            $held=$false
        }
        Start-Sleep -Seconds 15
    }
} finally {
    if($held){Stop-Guard $handle}
    [void][TheOnePowerGuardV2.Native]::CloseHandle($handle)
    $mutex.ReleaseMutex()
    $mutex.Dispose()
}

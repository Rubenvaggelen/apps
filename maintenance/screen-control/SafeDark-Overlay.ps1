param([ValidateRange(0,3600)][int]$AutoRestoreSeconds=0)
$ErrorActionPreference='Stop'
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
$dir=Join-Path $env:LOCALAPPDATA 'TheOne\SafeDark'
New-Item -ItemType Directory -Path $dir -Force | Out-Null
$state=Join-Path $dir 'active.json'
$stop=Join-Path $dir 'stop.signal'
$lock=New-Object Threading.Mutex($false,'Local\TheOneSafeDarkOverlay')
if(-not $lock.WaitOne(0)){exit 0}
$native=@'
using System;
using System.Runtime.InteropServices;
namespace TheOneSafeDark {
  [StructLayout(LayoutKind.Sequential,CharSet=CharSet.Unicode)]
  public struct ReasonContext {
    public uint Version;
    public uint Flags;
    [MarshalAs(UnmanagedType.LPWStr)]
    public string SimpleReasonString;
  }
  public static class Power {
    [DllImport("kernel32.dll", CharSet=CharSet.Unicode, SetLastError=true)]
    public static extern IntPtr PowerCreateRequest(ref ReasonContext reason);
    [DllImport("kernel32.dll", SetLastError=true)]
    [return:MarshalAs(UnmanagedType.Bool)]
    public static extern bool PowerSetRequest(IntPtr handle, int requestType);
    [DllImport("kernel32.dll", SetLastError=true)]
    [return:MarshalAs(UnmanagedType.Bool)]
    public static extern bool PowerClearRequest(IntPtr handle, int requestType);
    [DllImport("kernel32.dll", SetLastError=true)]
    [return:MarshalAs(UnmanagedType.Bool)]
    public static extern bool CloseHandle(IntPtr handle);
    [StructLayout(LayoutKind.Sequential)]
    public struct PowerStatus {
      public byte ACLineStatus;
      public byte BatteryFlag;
      public byte BatteryLifePercent;
      public byte Reserved;
      public uint BatteryLifeTime;
      public uint BatteryFullLifeTime;
    }
    [DllImport("kernel32.dll", SetLastError=true)]
    [return:MarshalAs(UnmanagedType.Bool)]
    public static extern bool GetSystemPowerStatus(out PowerStatus status);
  }
}
'@
if(-not ('TheOneSafeDark.Power' -as [type])){Add-Type -TypeDefinition $native}
function On-AC {
 $s=New-Object TheOneSafeDark.Power+PowerStatus
 return ([TheOneSafeDark.Power]::GetSystemPowerStatus([ref]$s) -and $s.ACLineStatus -eq 1)
}
$powerHandle=[IntPtr]::Zero
$form=$null
$timer=$null
try{
 if(-not (On-AC)){throw 'SafeDark requires AC: no display-off attempt made'}
 if(Test-Path $stop){Remove-Item $stop -Force}
 $reason=New-Object TheOneSafeDark.ReasonContext
 $reason.Version=0
 $reason.Flags=1
 $reason.SimpleReasonString='The One Family SafeDark (keep logical display on and remote active)'
 $powerHandle=[TheOneSafeDark.Power]::PowerCreateRequest([ref]$reason)
 if($powerHandle -eq [IntPtr]::Zero -or $powerHandle -eq [IntPtr](-1)){
  throw 'Windows rejected the display keep-awake handle'
 }
 # Acquire all three power requests BEFORE showing a dark surface.
 $display=[TheOneSafeDark.Power]::PowerSetRequest($powerHandle,0)
 $system=[TheOneSafeDark.Power]::PowerSetRequest($powerHandle,1)
 $execution=[TheOneSafeDark.Power]::PowerSetRequest($powerHandle,3)
 if(-not ($display -and $system -and $execution)){
  throw ('Keep-display-on request failed '+$display+' '+$system+' '+$execution)
 }
 $form=New-Object System.Windows.Forms.Form
 $form.FormBorderStyle='None'
 $form.BackColor=[Drawing.Color]::Black
 $form.ForeColor=[Drawing.Color]::Black
 $form.ShowInTaskbar=$false
 $form.TopMost=$true
 $form.StartPosition='Manual'
 $form.Bounds=[System.Windows.Forms.SystemInformation]::VirtualScreen
 $form.KeyPreview=$true
 $form.Add_KeyDown({$form.Close()})
 $form.Add_MouseDown({$form.Close()})
 $form.Add_Click({$form.Close()})
 $startTime=Get-Date
 $timer=New-Object System.Windows.Forms.Timer
 $timer.Interval=500
 $timer.Add_Tick({
   if((Test-Path $stop) -or (-not (On-AC)) -or
      ($AutoRestoreSeconds -gt 0 -and ((Get-Date)-$startTime).TotalSeconds -ge $AutoRestoreSeconds)){
       $form.Close()
   }
 })
 $form.Add_Shown({
   @{pid=$PID;since=(Get-Date -Format o);mode='safe-dark';expires_seconds=$AutoRestoreSeconds}|
     ConvertTo-Json|Set-Content -LiteralPath $state -Encoding UTF8
   $timer.Start()
 })
 [System.Windows.Forms.Application]::Run($form)
}finally{
 if($timer){$timer.Stop();$timer.Dispose()}
 if($form){$form.Dispose()}
 if(Test-Path $state){Remove-Item $state -Force -ErrorAction SilentlyContinue}
 if(Test-Path $stop){Remove-Item $stop -Force -ErrorAction SilentlyContinue}
 if($powerHandle -ne [IntPtr]::Zero -and $powerHandle -ne [IntPtr](-1)){
  foreach($t in @(3,1,0)){[void][TheOneSafeDark.Power]::PowerClearRequest($powerHandle,$t)}
  [void][TheOneSafeDark.Power]::CloseHandle($powerHandle)
 }
 $lock.ReleaseMutex()
 $lock.Dispose()
}

param([ValidateSet('Probe','On','Off')][string]$Action='Probe')
$ErrorActionPreference='Stop'
Add-Type -AssemblyName System.Windows.Forms
$session=(Get-Process -Id $PID).SessionId
$explorerSessions=@(Get-Process explorer -ErrorAction SilentlyContinue|Select-Object -ExpandProperty SessionId)
if($session -notin $explorerSessions){throw "Geen interactieve bureaubladsessie (sessie $session)"}
$screens=@([System.Windows.Forms.Screen]::AllScreens)
if($screens.Count -lt 1){throw 'Geen beeldschermen gedetecteerd'}
$native=@'
using System;
using System.Runtime.InteropServices;
namespace OneDisplayV2 {
  public static class Native {
    [StructLayout(LayoutKind.Sequential)]
    public struct Point { public int X; public int Y; }
    [DllImport("user32.dll", SetLastError=true)]
    public static extern IntPtr SendMessageTimeout(IntPtr hwnd,uint msg,IntPtr wp,IntPtr lp,uint flags,uint timeout,out IntPtr result);
    [DllImport("kernel32.dll", SetLastError=true)]
    public static extern uint SetThreadExecutionState(uint flags);
    [DllImport("user32.dll", SetLastError=true)]
    public static extern bool GetCursorPos(out Point position);
    [DllImport("user32.dll", SetLastError=true)]
    public static extern bool SetCursorPos(int x,int y);
    [DllImport("user32.dll", SetLastError=true)]
    public static extern void mouse_event(uint flags,uint dx,uint dy,uint data,UIntPtr extra);
    [DllImport("user32.dll", SetLastError=true)]
    public static extern IntPtr OpenInputDesktop(uint flags,bool inherit,uint desiredAccess);
    [DllImport("user32.dll", SetLastError=true)]
    public static extern bool CloseDesktop(IntPtr desktop);
  }
}
'@
if(-not ('OneDisplayV2.Native' -as [type])){Add-Type -TypeDefinition $native}
$inputDesktop=[OneDisplayV2.Native]::OpenInputDesktop(0,$false,1)
$desktopAvailable=$inputDesktop -ne [IntPtr]::Zero
if($desktopAvailable){[void][OneDisplayV2.Native]::CloseDesktop($inputDesktop)}
if($Action -eq 'Probe'){
 Write-Output ("PROBE_OK SESSION=$session SCREENS=$($screens.Count) INPUT_DESKTOP_ACCESSIBLE=$desktopAvailable")
 exit 0
}
$result=[IntPtr]::Zero
if($Action -eq 'On'){
  # Display-required reset should wake powered-down displays; does not prevent sleep.
  [void][OneDisplayV2.Native]::SetThreadExecutionState([uint32]2)
  $sent=[OneDisplayV2.Native]::SendMessageTimeout([IntPtr]65535,0x0112,[IntPtr]0xF170,[IntPtr](-1),2,1500,[ref]$result)
  # The old zero-delta mouse event did not register as input; use a 1px wiggle instead.
  $point=New-Object OneDisplayV2.Native+Point
  if([OneDisplayV2.Native]::GetCursorPos([ref]$point)){
    [void][OneDisplayV2.Native]::SetCursorPos(($point.X+1),$point.Y)
    [void][OneDisplayV2.Native]::SetCursorPos($point.X,$point.Y)
  }
  [OneDisplayV2.Native]::mouse_event([uint32]1,[uint32]1,[uint32]0,[uint32]0,[UIntPtr]::Zero)
  [OneDisplayV2.Native]::mouse_event([uint32]1,[uint32]4294967295,[uint32]0,[uint32]0,[UIntPtr]::Zero)
  [void][OneDisplayV2.Native]::SetThreadExecutionState([uint32]2)
}else{
  $sent=[OneDisplayV2.Native]::SendMessageTimeout([IntPtr]65535,0x0112,[IntPtr]0xF170,[IntPtr]2,2,1500,[ref]$result)
}
if($sent -eq [IntPtr]::Zero){
 throw ('Schermopdracht niet bevestigd door Windows. Win32Error='+[Runtime.InteropServices.Marshal]::GetLastWin32Error())
}
Write-Output ("COMMAND_ACCEPTED ACTION=$Action SESSION=$session SCREENS=$($screens.Count) INPUT_DESKTOP_ACCESSIBLE=$desktopAvailable PHYSICAL_STATE_NOT_VERIFIED")

Set sh = CreateObject("WScript.Shell")
sh.Run "powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -WindowStyle Hidden -File " & Chr(34) & "C:\TheOne\Remote\RemoteHealthRecovery.ps1" & Chr(34), 0, False

# The One Family: outbound authenticated screen agent
# Owner authorizes in Main. Agent polls HTTPS with a private per-device credential.
param(
 [ValidateSet('ruben','surface','hub')][string]$Device,
 [switch]$Probe
)
$ErrorActionPreference='Stop'
$screen='C:\TheOne\Tools\Screen-Control.ps1'
$keyFile=Join-Path $env:LOCALAPPDATA 'TheOne\screen-control-agent.key'
if(!(Test-Path $screen) -or !(Test-Path $keyFile)){throw 'Agent dependencies absent'}
$agentKey=[IO.File]::ReadAllText($keyFile,[Text.Encoding]::ASCII).Trim()
if($agentKey.Length -ne 64){throw 'Agent credential invalid'}
$ip=Get-NetIPAddress -AddressFamily IPv4 -ErrorAction Stop |
 Where-Object {$_.IPAddress.StartsWith('192.168.178.') -and $_.AddressState -eq 'Preferred'} |
 Select-Object -First 1 -ExpandProperty IPAddress
if(!$ip){throw 'Home network unavailable'}
$session=(Get-Process -Id $PID).SessionId
if($session -notin @(Get-Process explorer -ErrorAction SilentlyContinue | Select-Object -ExpandProperty SessionId)){
 throw 'Must run in interactive desktop session'
}
if($Probe){
 & $screen -Action Probe
 Write-Output ('AUTHENTICATED_AGENT_PROBE_OK '+$Device+' '+$ip)
 exit 0
}
$endpoint='https://rubenvanaggelen.com/the-one-remote-api/screen-control.php?action='
function Remote-Call([string]$operation,[hashtable]$payload){
 $payload['target']=$Device
 $payload['agent_key']=$agentKey
 $json=$payload|ConvertTo-Json -Compress
 return Invoke-RestMethod -Uri ($endpoint+$operation) -Method Post -ContentType 'application/json' -Body $json -TimeoutSec 6 -ErrorAction Stop
}
function Execute-Screen([string]$action,[string]$ticket){
 if($action -ne 'ON' -and $action -ne 'OFF'){return $false}
 if($ticket.Length -ne 64){return $false}
 $valid='0123456789abcdef'
 foreach($c in $ticket.ToCharArray()){if(!$valid.Contains([string]$c)){return $false}}
 try{
  $ok=Remote-Call 'redeem' @{command=$action;ticket=$ticket}
  if(!$ok.ok -or !$ok.accepted){return $false}
 }catch{return $false}
 $status='failed'
 try {
  $mode=if($action -eq 'ON'){'On'}else{'Off'}
  $log=(& $screen -Action $mode 2>&1|Out-String)
  if($log.Contains('COMMAND_ACCEPTED')){$status='done'}
 }catch{}
 try{[void](Remote-Call 'complete' @{command=$action;ticket=$ticket;result=$status})}catch{}
 return ($status -eq 'done')
}
$listener=[Net.Sockets.TcpListener]::new([Net.IPAddress]::Parse($ip),38492)
$listener.Start()
try {
 while($true) {
  if($listener.Pending()) {
   $client=$null
   try {
    $client=$listener.AcceptTcpClient()
    $client.ReceiveTimeout=4000
    $client.SendTimeout=4000
    $remote=([Net.IPEndPoint]$client.Client.RemoteEndPoint).Address.ToString()
    $stream=$client.GetStream()
    $reader=[IO.StreamReader]::new($stream,[Text.Encoding]::UTF8,$false,1024,$true)
    $writer=[IO.StreamWriter]::new($stream,[Text.UTF8Encoding]::new($false),1024,$true)
    $writer.AutoFlush=$true
    if(!$remote.StartsWith('192.168.178.')){$writer.WriteLine('ERR AUTH');continue}
    $request=$reader.ReadLine()
    if(!$request -or $request.Length -gt 70){$writer.WriteLine('ERR REQUEST');continue}
    $parts=$request.Split(' ')
    if($parts.Length -ne 2){$writer.WriteLine('ERR REQUEST');continue}
    $action=$parts[0]
    $ticket=$parts[1]
    if(Execute-Screen $action $ticket){$writer.WriteLine("OK DISPLAY_$action")}
    else{$writer.WriteLine('ERR AUTH')}
   }catch{}
   finally{if($client){$client.Close()}}
  } else {
   try{
    $pending=Remote-Call 'poll' @{}
    if($pending.ok -and $pending.pending){
      [void](Execute-Screen ([string]$pending.command) ([string]$pending.ticket))
    }
   }catch{}
   Start-Sleep -Milliseconds 1300
  }
 }
} finally{$listener.Stop()}

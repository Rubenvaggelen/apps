<?php
declare(strict_types=1);
ini_set('display_errors','0');
header('Cache-Control: private, no-store');
function fail(int $code,string $message): never { http_response_code($code);header('Content-Type: application/json');echo json_encode(['error'=>$message]);exit; }
$configPath=dirname(__DIR__,2).'/.the-one-cinema/config.json';
if(!is_file($configPath))fail(503,'Mediabron niet ingesteld');
$config=json_decode((string)file_get_contents($configPath),true);
if(!is_array($config)||!isset($config['server'],$config['username'],$config['password']))fail(503,'Mediabron niet ingesteld');
$expected=hash_hmac('sha256','the-one-cinema-relay-v1',(string)$config['password']);
$received=$_SERVER['HTTP_X_CINEMA_TOKEN']??'';
if(!is_string($received)||!hash_equals($expected,$received))fail(403,'Geen toegang');
$action=$_GET['action']??'stream';
if($action==='health'){header('Content-Type: application/json');echo json_encode(['ok'=>true,'service'=>'The One Cinema Relay']);exit;}
$id=filter_var($_GET['id']??'',FILTER_VALIDATE_INT,['options'=>['min_range'=>1]]);
$ext=(string)($_GET['ext']??'mp4');
if(!$id||!preg_match('/^[a-zA-Z0-9]{1,8}$/D',$ext)||!in_array($action,['probe','stream'],true))fail(400,'Ongeldige film');
$url=rtrim((string)$config['server'],'/').'/movie/'.rawurlencode((string)$config['username']).'/'.rawurlencode((string)$config['password']).'/'.$id.'.'.$ext;
$ch=curl_init($url);if($ch===false)fail(502,'Videobron niet bereikbaar');
$range=$action==='probe'?'bytes=0-4095':($_SERVER['HTTP_RANGE']??'');
$headers=[];if($range!==''){if(!preg_match('/^bytes=\d*-\d*$/D',$range))fail(400,'Ongeldig bereik');$headers[]='Range: '.$range;}
$status=0;$finalHeaders=[];$bytes='';$sent=false;
curl_setopt_array($ch,[CURLOPT_FOLLOWLOCATION=>true,CURLOPT_MAXREDIRS=>5,CURLOPT_CONNECTTIMEOUT=>15,CURLOPT_TIMEOUT=>$action==='probe'?20:0,CURLOPT_LOW_SPEED_LIMIT=>32,CURLOPT_LOW_SPEED_TIME=>30,CURLOPT_HTTPHEADER=>$headers,CURLOPT_USERAGENT=>'VLC/3.0.21 LibVLC/3.0.21',CURLOPT_PROTOCOLS=>CURLPROTO_HTTP|CURLPROTO_HTTPS,CURLOPT_REDIR_PROTOCOLS=>CURLPROTO_HTTP|CURLPROTO_HTTPS,CURLOPT_HEADERFUNCTION=>function($ch,string $line)use(&$status,&$finalHeaders):int{if(preg_match('/^HTTP\/\S+\s+(\d+)/',$line,$m)){$status=(int)$m[1];$finalHeaders=[];}elseif(str_contains($line,':')){[$key,$value]=explode(':',$line,2);$key=strtolower(trim($key));if(in_array($key,['content-type','content-length','content-range','accept-ranges'],true))$finalHeaders[$key]=trim($value);}return strlen($line);},CURLOPT_WRITEFUNCTION=>function($ch,string $chunk)use(&$status,&$bytes,&$sent,&$finalHeaders,$action,$ext):int{if($status<200||$status>=300)return strlen($chunk);if($action==='probe'){$bytes.=substr($chunk,0,max(0,4096-strlen($bytes)));return strlen($bytes)>=4096?0:strlen($chunk);}if(!$sent){http_response_code($status);header('Content-Type: '.($ext==='mp4'?'video/mp4':($finalHeaders['content-type']??'application/octet-stream')));header('X-Content-Type-Options: nosniff');foreach(['content-length','content-range','accept-ranges']as$key)if(isset($finalHeaders[$key]))header($key.': '.$finalHeaders[$key]);while(ob_get_level()>0)ob_end_clean();$sent=true;}echo $chunk;flush();return connection_aborted()?0:strlen($chunk);}]);
$result=curl_exec($ch);curl_close($ch);
if($action==='probe'){$type=$finalHeaders['content-type']??'';$head=substr($bytes,0,128);$ok=$status>=200&&$status<300&&strlen($bytes)>32&&!str_starts_with(ltrim($head),'<')&&(str_starts_with($type,'video/')||str_contains($head,'ftyp')||str_contains($head,'moov')||ord($bytes[0])===0x47||substr($bytes,0,4)==="\x1a\x45\xdf\xa3");header('Content-Type: application/json');echo json_encode(['ok'=>$ok,'status'=>$status,'bytes'=>strlen($bytes)]);exit;}
if(!$sent)fail(502,'Videobron niet bereikbaar');

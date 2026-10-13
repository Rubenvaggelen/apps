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
$secondary=null;
if($action==='health'){header('Content-Type: application/json');echo json_encode(['ok'=>true,'service'=>'The One Cinema Relay']);exit;}
function apiData(array $config,string $action,string $extra=''): array {
    $url=rtrim((string)$config['server'],'/').'/player_api.php?username='.rawurlencode((string)$config['username']).'&password='.rawurlencode((string)$config['password']).'&action='.$action.$extra;
    $ch=curl_init($url);curl_setopt_array($ch,[CURLOPT_RETURNTRANSFER=>true,CURLOPT_FOLLOWLOCATION=>true,CURLOPT_CONNECTTIMEOUT=>15,CURLOPT_TIMEOUT=>65,CURLOPT_PROTOCOLS=>CURLPROTO_HTTP|CURLPROTO_HTTPS,CURLOPT_REDIR_PROTOCOLS=>CURLPROTO_HTTP|CURLPROTO_HTTPS]);
    $raw=curl_exec($ch);$status=curl_getinfo($ch,CURLINFO_HTTP_CODE);curl_close($ch);
    if($status!==200||!is_string($raw))throw new RuntimeException('Catalog unavailable');
    $data=json_decode($raw,true);unset($raw);if(!is_array($data))throw new RuntimeException('Catalog unavailable');return $data;
}
set_exception_handler(function(Throwable $error):void{fail(502,'Catalogus niet bereikbaar');});
function adultCategory(string $name):bool{return (bool)preg_match('/(?:\bxxx\b|\badult\b|\bporn(?:ography|o)?\b|\berotic(?:a)?\b|18\s*\+|volwassen)/iu',$name);}
if($action==='poster'){
    $id=filter_var($_GET['id']??'',FILTER_VALIDATE_INT,['options'=>['min_range'=>1,'max_range'=>999999999]]);if(!$id)fail(400,'Ongeldige film');
    $data=apiData($config,'get_vod_info','&vod_id='.$id);$poster='';
    foreach([$data['info']??[],$data['movie_data']??[],$data]as$info){if(!is_array($info))continue;foreach(['movie_image','cover','cover_big','poster','stream_icon']as$key){$candidate=$info[$key]??'';if(!is_string($candidate)||$candidate===''||strtolower($candidate)==='null')continue;
        if(str_starts_with($candidate,'//'))$candidate='http:'.$candidate;elseif(str_starts_with($candidate,'/'))$candidate=rtrim((string)$config['server'],'/').$candidate;
        if(preg_match('/^https?:\/\//i',$candidate)){$poster=$candidate;break 2;}
    }}
    header('Content-Type: application/json');echo json_encode(['poster'=>$poster],JSON_UNESCAPED_SLASHES|JSON_INVALID_UTF8_SUBSTITUTE);exit;
}
if($action==='stats'){
    $path=dirname($configPath).'/catalog-stats-v4.json';if(!is_file($path))fail(404,'Catalogus nog niet geladen');header('Content-Type: application/json');readfile($path);exit;
}
if($action==='catalog'&&($_GET['type']??'movies')==='movies'){
    ini_set('memory_limit','768M');set_time_limit(240);
    $cache=dirname($configPath).'/catalog-movies-v4.json';header('Content-Type: application/json');
    if(is_file($cache)&&filemtime($cache)>time()-3600){readfile($cache);exit;}
    $sources=[$config];if(is_array($secondary))$sources[]=$secondary;
    $out=[];$stats=['sources_configured'=>count($sources),'sources_ok'=>0,'source_rows'=>[],'films'=>0,'xxx'=>0];
    $allowed=['name','container_extension','stream_icon','category_id','added','tmdb_id','tmdb'];
    foreach($sources as$index=>$source){
        try{$data=apiData($source,'get_vod_streams');}catch(Throwable $error){$stats['source_rows'][(string)$index]=0;continue;}
        $adults=[];try{foreach(apiData($source,'get_vod_categories')as$category)if(is_array($category)&&adultCategory((string)($category['category_name']??'')))$adults[(string)($category['category_id']??'')]=true;}catch(Throwable $error){}
        $stats['sources_ok']++;$stats['source_rows'][(string)$index]=0;
        foreach($data as$row){
            if(!is_array($row))continue;$id=filter_var($row['stream_id']??0,FILTER_VALIDATE_INT,['options'=>['min_range'=>1,'max_range'=>999999999]]);if(!$id)continue;
            $safe=['stream_id'=>$id+$index*1000000000,'source_id'=>$index];
            foreach($allowed as$key)if(isset($row[$key])&&(is_string($row[$key])||is_numeric($row[$key])))$safe[$key]=$row[$key];
            $safe['is_adult']=((int)($row['is_adult']??0)===1||isset($adults[(string)($row['category_id']??'')])||preg_match('/^\s*(?:\[xxx\]|xxx\s*\||18\s*\+)/iu',(string)($row['name']??'')))?1:0;
            $stats[$safe['is_adult']?'xxx':'films']++;$stats['source_rows'][(string)$index]++;$out[]=$safe;
        }
        unset($data);
    }
    if($stats['sources_ok']===0)fail(502,'Geen filmbron bereikbaar');
    $json=json_encode($out,JSON_UNESCAPED_UNICODE|JSON_INVALID_UTF8_SUBSTITUTE);$tmp=$cache.'.tmp.'.getmypid();
    if(file_put_contents($tmp,$json,LOCK_EX)!==false){chmod($tmp,0600);rename($tmp,$cache);if($stats['sources_ok']<count($sources))touch($cache,time()-3540);}
    $sp=dirname($configPath).'/catalog-stats-v3.json';file_put_contents($sp,json_encode($stats),LOCK_EX);chmod($sp,0600);echo$json;exit;
}
if($action==='catalog'){
    ini_set('memory_limit','512M');$kind=$_GET['type']??'movies';if(!in_array($kind,['movies','series'],true))fail(400,'Ongeldige catalogus');
    $cache=dirname($configPath).'/catalog-'.$kind.'.json';header('Content-Type: application/json');
    if(is_file($cache)&&filemtime($cache)>time()-3600){readfile($cache);exit;}
    $data=apiData($config,$kind==='series'?'get_series':'get_vod_streams');$out=[];
    $allowed=['stream_id','series_id','name','container_extension','stream_icon','cover','category_id','is_adult','added','last_modified'];
    foreach($data as$row){if(!is_array($row))continue;$safe=[];foreach($allowed as$key)if(isset($row[$key])&&(is_string($row[$key])||is_numeric($row[$key])))$safe[$key]=$row[$key];$out[]=$safe;}
    unset($data);$json=json_encode($out,JSON_UNESCAPED_UNICODE|JSON_INVALID_UTF8_SUBSTITUTE);$tmp=$cache.'.tmp.'.getmypid();
    if(file_put_contents($tmp,$json,LOCK_EX)!==false){chmod($tmp,0600);rename($tmp,$cache);}echo $json;exit;
}
if($action==='episodes'){
    ini_set('memory_limit','256M');$series=filter_var($_GET['id']??'',FILTER_VALIDATE_INT,['options'=>['min_range'=>1]]);if(!$series)fail(400,'Ongeldige serie');
    $data=apiData($config,'get_series_info','&series_id='.$series);$out=['episodes'=>[]];
    foreach(($data['episodes']??[])as$season=>$list){if(!is_array($list))continue;$safe=[];foreach($list as$row){if(!is_array($row))continue;$episode=[];foreach(['id','title','episode_num','container_extension']as$key)if(isset($row[$key])&&(is_string($row[$key])||is_numeric($row[$key])))$episode[$key]=$row[$key];$safe[]=$episode;}$out['episodes'][(string)$season]=$safe;}
    $out['episodes']=(object)$out['episodes'];header('Content-Type: application/json');echo json_encode($out,JSON_UNESCAPED_UNICODE|JSON_INVALID_UTF8_SUBSTITUTE);exit;
}

$id=filter_var($_GET['id']??'',FILTER_VALIDATE_INT,['options'=>['min_range'=>1]]);
$ext=(string)($_GET['ext']??'mp4');
if(!$id||!preg_match('/^[a-zA-Z0-9]{1,8}$/D',$ext)||!in_array($action,['probe','stream'],true))fail(400,'Ongeldige film');
$kind=$_GET['type']??'movies';if(!in_array($kind,['movies','series'],true))fail(400,'Ongeldig mediatype');
if($id>=1000000000){if($kind!=='movies'||!is_array($secondary))fail(404,'Filmbron ontbreekt');$id-=1000000000;$config=$secondary;}
$url=rtrim((string)$config['server'],'/').'/'.($kind==='series'?'series':'movie').'/'.rawurlencode((string)$config['username']).'/'.rawurlencode((string)$config['password']).'/'.$id.'.'.$ext;
$ch=curl_init($url);if($ch===false)fail(502,'Videobron niet bereikbaar');
$range=$action==='probe'?'bytes=0-4095':($_SERVER['HTTP_RANGE']??'');
$headers=[];if($range!==''){if(!preg_match('/^bytes=\d*-\d*$/D',$range))fail(400,'Ongeldig bereik');$headers[]='Range: '.$range;}
$status=0;$finalHeaders=[];$bytes='';$sent=false;
curl_setopt_array($ch,[CURLOPT_FOLLOWLOCATION=>true,CURLOPT_MAXREDIRS=>5,CURLOPT_CONNECTTIMEOUT=>15,CURLOPT_TIMEOUT=>$action==='probe'?20:0,CURLOPT_LOW_SPEED_LIMIT=>32,CURLOPT_LOW_SPEED_TIME=>30,CURLOPT_HTTPHEADER=>$headers,CURLOPT_USERAGENT=>'VLC/3.0.21 LibVLC/3.0.21',CURLOPT_PROTOCOLS=>CURLPROTO_HTTP|CURLPROTO_HTTPS,CURLOPT_REDIR_PROTOCOLS=>CURLPROTO_HTTP|CURLPROTO_HTTPS,CURLOPT_HEADERFUNCTION=>function($ch,string $line)use(&$status,&$finalHeaders):int{if(preg_match('/^HTTP\/\S+\s+(\d+)/',$line,$m)){$status=(int)$m[1];$finalHeaders=[];}elseif(str_contains($line,':')){[$key,$value]=explode(':',$line,2);$key=strtolower(trim($key));if(in_array($key,['content-type','content-length','content-range','accept-ranges'],true))$finalHeaders[$key]=trim($value);}return strlen($line);},CURLOPT_WRITEFUNCTION=>function($ch,string $chunk)use(&$status,&$bytes,&$sent,&$finalHeaders,$action,$ext):int{if($status<200||$status>=300)return strlen($chunk);if($action==='probe'){$bytes.=substr($chunk,0,max(0,4096-strlen($bytes)));return strlen($bytes)>=4096?0:strlen($chunk);}if(!$sent){http_response_code($status);header('Content-Type: '.($ext==='mp4'?'video/mp4':($finalHeaders['content-type']??'application/octet-stream')));header('X-Content-Type-Options: nosniff');foreach(['content-length','content-range','accept-ranges']as$key)if(isset($finalHeaders[$key]))header($key.': '.$finalHeaders[$key]);while(ob_get_level()>0)ob_end_clean();$sent=true;}echo $chunk;flush();return connection_aborted()?0:strlen($chunk);}]);
$result=curl_exec($ch);curl_close($ch);
if($action==='probe'){$type=$finalHeaders['content-type']??'';$head=substr($bytes,0,128);$ok=$status>=200&&$status<300&&strlen($bytes)>32&&!str_starts_with(ltrim($head),'<')&&(str_starts_with($type,'video/')||str_contains($head,'ftyp')||str_contains($head,'moov')||ord($bytes[0])===0x47||substr($bytes,0,4)==="\x1a\x45\xdf\xa3");header('Content-Type: application/json');echo json_encode(['ok'=>$ok,'status'=>$status,'bytes'=>strlen($bytes)]);exit;}
if(!$sent)fail(502,'Videobron niet bereikbaar');


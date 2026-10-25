<?php
declare(strict_types=1);
$root='/home/vanawiwj/the-one-private-builds/2026-10-13';
$input=json_decode((string)file_get_contents($argv[1] ?? ''),true,512,JSON_THROW_ON_ERROR);
$approvedPackages=[
 'cinema-phone'=>'com.theone.cinema',
 'cinema-google-tv'=>'com.theone.cinema.tv',
 'series-phone'=>'com.theone.series',
 'series-google-tv'=>'com.theone.series.tv',
 'tv-phone'=>'com.theone.tv.phone',
 'tv-google-tv'=>'com.theone.tv'
];
$id=(string)($input['id']??'');
$apk=(string)($input['apk']??'');
$sha=(string)($input['sha256']??'');
$badging=(string)($input['package_badging']??'');
$version=(string)($input['version_name']??'');
if(!isset($approvedPackages[$id]) ||
 !preg_match('/^[a-zA-Z0-9._-]+\.apk$/D',$apk) ||
 !preg_match('/^[a-f0-9]{64}$/D',$sha) ||
 !preg_match('/^\d+\.\d+\.\d+$/D',$version) ||
 !preg_match("/^package:\s+name='".preg_quote($approvedPackages[$id],'/')."'\s+versionCode='([0-9]+)'\s+versionName='".preg_quote($version,'/')."'/",$badging,$match))exit(2);
$buildCode=(int)$match[1];
if($buildCode<1)exit(2);
$file=$root.'/apks/'.$apk;
if(!is_file($file)||!hash_equals($sha,(string)hash_file('sha256',$file)))exit(3);
$lock=fopen($root.'/manifest.lock','c');
if(!$lock||!flock($lock,LOCK_EX))exit(4);
try{
 $manifestFile=$root.'/manifest.json';
 $raw=(string)file_get_contents($manifestFile);
 $rows=json_decode($raw,true,512,JSON_THROW_ON_ERROR);
 // The Family catalog has grown since the original eight-app manifest.
 // Validate every row and unique identifier instead of assuming a fixed count.
 if(!is_array($rows)||count($rows)<1||count($rows)>64)exit(5);
 $seenIds=[];
 foreach($rows as $check){
  if(!is_array($check)||!isset($check['id'])||!is_string($check['id'])||
     $check['id']===''||isset($seenIds[$check['id']]))exit(5);
  $seenIds[$check['id']]=true;
 }
 $found=false;
 foreach($rows as &$row){
  if(!is_array($row)||($row['id']??null)!==$id)continue;
  if(version_compare((string)($row['version_name']??'0.0.0'),$version,'>'))exit(6);
  foreach(['apk','version_name','size_bytes','sha256','commit','package_badging','created_date'] as $key){
   $row[$key]=$input[$key]??null;
  }
  $row['dev_hub_status']='published';
  $found=true;
  break;
 }
 unset($row);
 if(!$found)exit(7);
 $backup=$root.'/manifest-before-'.$id.'-'.gmdate('Ymd-His').'-'.bin2hex(random_bytes(3)).'.json';
 if(file_put_contents($backup,$raw,LOCK_EX)===false)exit(8);
 chmod($backup,0600);
 $tmp=$manifestFile.'.new-'.$id.'-'.bin2hex(random_bytes(3));
 if(file_put_contents($tmp,json_encode($rows,JSON_PRETTY_PRINT|JSON_UNESCAPED_SLASHES).PHP_EOL,LOCK_EX)===false)exit(9);
 chmod($tmp,0600);
 if(!rename($tmp,$manifestFile))exit(10);
 echo "Private Family build published: $id $version".PHP_EOL;
}finally{
 flock($lock,LOCK_UN);
 fclose($lock);
}

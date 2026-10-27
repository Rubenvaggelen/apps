<?php
$root='/home/vanawiwj/the-one-private-builds/2026-10-13';
$input=json_decode(file_get_contents($argv[1]),true,512,JSON_THROW_ON_ERROR);
$allowed=['cinema-google-tv','series-google-tv','tv-google-tv'];
if(!in_array($input['id'],$allowed,true)||!preg_match('/^[A-Za-z0-9._-]+\.apk$/',$input['apk']))exit(2);
$file=$root.'/apks/'.$input['apk'];
if(!is_file($file)||hash_file('sha256',$file)!==$input['sha256'])exit(3);
$lock=fopen($root.'/manifest.lock','c');if(!$lock||!flock($lock,LOCK_EX))exit(4);
$manifest=$root.'/manifest.json';$original=file_get_contents($manifest);$rows=json_decode($original,true,512,JSON_THROW_ON_ERROR);
$found=false;
foreach($rows as &$row)if($row['id']===$input['id']){
 foreach(['apk','version_name','size_bytes','sha256','commit','package_badging','created_date'] as $key)$row[$key]=$input[$key];
 $row['dev_hub_status']='published';$found=true;break;
}
unset($row);if(!$found||!is_array($rows)||count($rows)<8)exit(5);
$backup=$root.'/manifest-before-'.$input['id'].'-'.gmdate('Ymd-His').'.json';file_put_contents($backup,$original);chmod($backup,0600);
$temp=$root.'/manifest.json.new-'.$input['id'];file_put_contents($temp,json_encode($rows,JSON_PRETTY_PRINT|JSON_UNESCAPED_SLASHES).PHP_EOL);chmod($temp,0600);rename($temp,$manifest);
flock($lock,LOCK_UN);fclose($lock);echo "Verified TV build published: ".$input['id'].PHP_EOL;

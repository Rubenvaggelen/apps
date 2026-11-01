<?php
declare(strict_types=1);
// Validated Cinema Android release: preserves all other Family app manifest entries.
$root='/home/vanawiwj/the-one-private-builds/2026-10-13';
$input=json_decode((string)file_get_contents($argv[1]??''),true,512,JSON_THROW_ON_ERROR);
$id=(string)($input['id']??'');
$file=(string)($input['apk']??'');
$version=(string)($input['version_name']??'');
$sha=(string)($input['sha256']??'');
$badging=(string)($input['package_badging']??'');
if($id!=='cinema-phone'||!preg_match('/^[A-Za-z0-9._-]+\\.apk$/D',$file)||!preg_match('/^[a-f0-9]{64}$/D',$sha)||!preg_match('/^\\d+\\.\\d+\\.\\d+$/D',$version)||!preg_match("/^package:\\s+name='com\\.theone\\.cinema'\\s+versionCode='([0-9]+)'\\s+versionName='".preg_quote($version,'/')."'/",$badging,$match)||(int)$match[1]<1){fwrite(STDERR,"Invalid Cinema build metadata".PHP_EOL);exit(2);}
$apk=$root.'/apks/'.$file;
if(!is_file($apk)||!hash_equals($sha,(string)hash_file('sha256',$apk))){fwrite(STDERR,"Checksum mismatch".PHP_EOL);exit(3);}
$lock=fopen($root.'/manifest.lock','c');
if(!$lock||!flock($lock,LOCK_EX))exit(4);
try{
 $manifest=$root.'/manifest.json';$original=file_get_contents($manifest);
 if($original===false)exit(5);
 $rows=json_decode($original,true,512,JSON_THROW_ON_ERROR);
 if(!is_array($rows)||!array_is_list($rows))exit(5);
 $matches=0;
 foreach($rows as $i=>$row){
  if(!is_array($row)||($row['id']??null)!==$id)continue;
  ++$matches;
  $old=(string)($row['version_name']??'0.0.0');
  if(version_compare($old,$version,'>'))exit(6);
  foreach(['apk','version_name','size_bytes','sha256','commit','package_badging','created_date'] as $key)$rows[$i][$key]=$input[$key]??null;
  $rows[$i]['dev_hub_status']='published';
 }
 if($matches!==1){fwrite(STDERR,"Cinema phone manifest entry missing or duplicate; total entries=".count($rows).PHP_EOL);exit(5);}
 $backup=$root.'/manifest-before-'.$id.'-'.gmdate('Ymd-His').'-'.bin2hex(random_bytes(3)).'.json';
 if(file_put_contents($backup,$original,LOCK_EX)===false)exit(7);
 chmod($backup,0600);
 $tmp=$manifest.'.new-'.$id.'-'.bin2hex(random_bytes(3));
 if(file_put_contents($tmp,json_encode($rows,JSON_PRETTY_PRINT|JSON_UNESCAPED_SLASHES|JSON_THROW_ON_ERROR).PHP_EOL,LOCK_EX)===false)exit(8);
 chmod($tmp,0600);
 if(!rename($tmp,$manifest)){@unlink($tmp);exit(9);}
 echo "Private Cinema Android build published: ".$id." ".$version."; manifest entries=".count($rows).PHP_EOL;
}finally{flock($lock,LOCK_UN);fclose($lock);}

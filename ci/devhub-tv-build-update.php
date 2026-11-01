<?php
declare(strict_types=1);
// Update only the selected verified TV build. Never assume a fixed number of apps.
$root='/home/vanawiwj/the-one-private-builds/2026-10-13';
$input=json_decode((string)file_get_contents($argv[1]??''),true,512,JSON_THROW_ON_ERROR);
$allowed=[
 'cinema-google-tv'=>'com.theone.cinema.tv',
 'series-google-tv'=>'com.theone.series.tv',
 'tv-google-tv'=>'com.theone.tv'
];
$id=(string)($input['id']??'');
$apk=(string)($input['apk']??'');
$sha=(string)($input['sha256']??'');
$badging=(string)($input['package_badging']??'');
$version=(string)($input['version_name']??'');
if(!isset($allowed[$id])||
 !preg_match('/^[A-Za-z0-9._-]+\\.apk$/D',$apk)||
 !preg_match('/^[a-f0-9]{64}$/D',$sha)||
 !preg_match('/^[0-9]+\\.[0-9]+\\.[0-9]+$/D',$version)||
 !preg_match("/^package:\\s+name='".preg_quote($allowed[$id],'/')."'\\s+versionCode='([0-9]+)'\\s+versionName='".preg_quote($version,'/')."'/",$badging,$m)||
 (int)$m[1]<1) {fwrite(STDERR,"Invalid TV build metadata\\n");exit(2);}
$source=$root.'/apks/'.$apk;
if(!is_file($source)||!hash_equals($sha,(string)hash_file('sha256',$source))) {fwrite(STDERR,"APK checksum mismatch\\n");exit(3);}
$lock=fopen($root.'/manifest.lock','c');
if(!$lock||!flock($lock,LOCK_EX)){fwrite(STDERR,"Manifest locked\\n");exit(4);}
try{
 $manifest=$root.'/manifest.json';
 $original=file_get_contents($manifest);
 if($original===false){fwrite(STDERR,"Cannot read manifest\\n");exit(5);}
 $rows=json_decode($original,true,512,JSON_THROW_ON_ERROR);
 if(!is_array($rows)||!array_is_list($rows)){fwrite(STDERR,"Unexpected manifest structure\\n");exit(5);}
 $matchCount=0;
 foreach($rows as $i=>$row){
  if(!is_array($row)||($row['id']??null)!==$id)continue;
  ++$matchCount;
  if($matchCount!==1)break;
  $previous=(string)($row['version_name']??'0.0.0');
  if(preg_match('/^[0-9]+\\.[0-9]+\\.[0-9]+$/D',$previous)&&version_compare($previous,$version,'>')){fwrite(STDERR,"Refusing downgrade\\n");exit(6);}
  foreach(['apk','version_name','size_bytes','sha256','commit','package_badging','created_date'] as $key)$rows[$i][$key]=$input[$key]??null;
  $rows[$i]['dev_hub_status']='published';
 }
 if($matchCount!==1){fwrite(STDERR,"Manifest id ".$id." missing or duplicate; entries=".count($rows)."\\n");exit(5);}
 $backup=$root.'/manifest-before-'.$id.'-'.gmdate('Ymd-His').'-'.bin2hex(random_bytes(3)).'.json';
 if(file_put_contents($backup,$original,LOCK_EX)===false){fwrite(STDERR,"Backup failed\\n");exit(7);}
 chmod($backup,0600);
 $tmp=$manifest.'.new-'.$id.'-'.bin2hex(random_bytes(3));
 $encoded=json_encode($rows,JSON_PRETTY_PRINT|JSON_UNESCAPED_SLASHES|JSON_THROW_ON_ERROR).PHP_EOL;
 if(file_put_contents($tmp,$encoded,LOCK_EX)===false){fwrite(STDERR,"Manifest write failed\\n");exit(8);}
 chmod($tmp,0600);
 if(!rename($tmp,$manifest)){@unlink($tmp);fwrite(STDERR,"Manifest swap failed\\n");exit(9);}
 echo "Published $id $version; all ".count($rows)." manifest entries preserved".PHP_EOL;
} finally {flock($lock,LOCK_UN);fclose($lock);}

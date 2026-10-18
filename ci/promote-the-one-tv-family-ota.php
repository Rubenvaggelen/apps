<?php
/**
 * Promote TWO previously emulator-tested The One TV APKs to the authenticated
 * Family Updates channel. Called only by the owner-authorized GitHub Actions
 * production promotion workflow, never exposed by HTTP.
 *
 * The Family Updates PHP endpoint reads the same private manifest, matching
 * com.theone.tv.phone -> tv-phone and com.theone.tv -> tv-google-tv.
 */
declare(strict_types=1);
if (PHP_SAPI !== 'cli' || count($argv) !== 3) exit(2);
$root='/home/vanawiwj/the-one-private-builds/2026-10-13';
$stage=(string)$argv[1];
$metadata=(string)$argv[2];
$stageReal=realpath($stage);
$rootReal=realpath($root);
$apkDirectory=realpath($root.'/apks');
if (!$stageReal || !$rootReal || !$apkDirectory ||
    dirname($apkDirectory)!==$rootReal || !is_dir($stageReal) || is_link($stage)) exit(3);
$input=json_decode((string)file_get_contents($metadata),true,512,JSON_THROW_ON_ERROR);
if (!is_array($input) || !isset($input['builds']) || !is_array($input['builds'])
    || count($input['builds'])!==2) exit(4);
$approved=[
 'tv-phone'=>['package'=>'com.theone.tv.phone','code'=>120,'name'=>'1.0.20','apk'=>'The-One-TV-Android-v1.0.20.apk'],
 'tv-google-tv'=>['package'=>'com.theone.tv','code'=>122,'name'=>'1.0.22','apk'=>'The-One-TV-Google-TV-v1.0.22.apk']
];
$byId=[];
foreach($input['builds'] as $build){
 if(!is_array($build) || !isset($build['id']))exit(5);
 $id=(string)$build['id'];
 if(isset($byId[$id]) || !isset($approved[$id]))exit(6);
 $expected=$approved[$id];
 foreach(['package','apk'] as $key)
   if(($build[$key]??null)!==$expected[$key])exit(7);
 if(($build['version_name']??null)!==$expected['name'])exit(7);
 if((int)($build['version_code']??0)!==$expected['code'])exit(8);
 if(!preg_match('/^[a-f0-9]{64}$/D',(string)($build['sha256']??'')))exit(9);
 $badge=(string)($build['package_badging']??'');
 $pattern="/^package: name='".preg_quote($expected['package'],'/').
          "' versionCode='".$expected['code'].
          "' versionName='".preg_quote($expected['name'],'/')."'(\\s|$)/";
 if(!preg_match($pattern,$badge))exit(10);
 $file=$stageReal.'/'.$expected['apk'];
 if(!is_file($file) || is_link($file) ||
    hash_file('sha256',$file)!==$build['sha256'] ||
    filesize($file)!==(int)($build['size_bytes']??0))exit(11);
 $byId[$id]=$build;
}
if(count($byId)!==count($approved))exit(12);
$lock=fopen($root.'/manifest.lock','c');
if(!$lock || !flock($lock,LOCK_EX))exit(13);
try{
 $manifestFile=$root.'/manifest.json';
 $original=file_get_contents($manifestFile);
 $rows=json_decode((string)$original,true,512,JSON_THROW_ON_ERROR);
 if(!is_array($rows) || count($rows)<8)exit(14);
 $seen=[];
 foreach($rows as &$row){
   if(!is_array($row))exit(15);
   $id=$row['id']??'';
   if(!isset($byId[$id]))continue;
   if(isset($seen[$id]))exit(16);
   $seen[$id]=true;
   $approvedBuild=$approved[$id];
   $oldVersion=(string)($row['version_name']??'0.0.0');
   if(version_compare($oldVersion,$approvedBuild['name'],'>') || (int)($row['version_code']??0)>$approvedBuild['code'])exit(17);
   if(isset($row['package_badging']) && is_string($row['package_badging'])
      && preg_match("/^package: name='([^']+)'/",$row['package_badging'],$m)
      && $m[1]!==$approvedBuild['package'])exit(18);
   foreach(['apk','version_name','version_code','size_bytes','sha256','commit','package_badging','created_date'] as $key)
     $row[$key]=$byId[$id][$key];
   $row['dev_hub_status']='published';
 }
 unset($row);
 if(count($seen)!==2)exit(19);
 // Never overwrite an existing version with different bytes.
 foreach($approved as $id=>$expected){
   $dest=$apkDirectory.'/'.$expected['apk'];
   if(is_link($dest))exit(20);
   if(is_file($dest)&&hash_file('sha256',$dest)!==$byId[$id]['sha256'])exit(21);
 }
 // Stage all binary files in the private APK directory, then rename.
 foreach($approved as $id=>$expected){
   $dest=$apkDirectory.'/'.$expected['apk'];
   if(is_file($dest))continue;
   $source=$stageReal.'/'.$expected['apk'];
   $temporary=$apkDirectory.'/.family-ota-'.bin2hex(random_bytes(9)).'.new';
   if(!copy($source,$temporary))exit(22);
   chmod($temporary,0600);
   if(hash_file('sha256',$temporary)!==$byId[$id]['sha256'])exit(23);
   if(!rename($temporary,$dest))exit(24);
 }
 $backup=$root.'/manifest-before-tv-ota-'.gmdate('Ymd-His').'-'.bin2hex(random_bytes(4)).'.json';
 if(!file_put_contents($backup,$original,LOCK_EX))exit(25);
 chmod($backup,0600);
 $temporaryManifest=$manifestFile.'.new-tv-ota-'.bin2hex(random_bytes(6));
 $data=json_encode($rows,JSON_PRETTY_PRINT|JSON_UNESCAPED_SLASHES|JSON_THROW_ON_ERROR).PHP_EOL;
 if(!file_put_contents($temporaryManifest,$data,LOCK_EX))exit(26);
 chmod($temporaryManifest,0600);
 if(!rename($temporaryManifest,$manifestFile))exit(27);
 // Independently re-read exactly what the Family Updates service will serve.
 $published=json_decode((string)file_get_contents($manifestFile),true,512,JSON_THROW_ON_ERROR);
 foreach($approved as $id=>$expected){
   $matches=array_values(array_filter($published,static fn($r)=>($r['id']??'')===$id));
   if(count($matches)!==1 || $matches[0]['version_name']!==$expected['name'] ||
      (int)($matches[0]['version_code']??0)!==$expected['code'] ||
      $matches[0]['sha256']!==$byId[$id]['sha256'] ||
      hash_file('sha256',$apkDirectory.'/'.$expected['apk'])!==$byId[$id]['sha256'])exit(28);
   echo 'FAMILY_OTA_PUBLISHED='.$id.' version='.$expected['name'].
        ' code='.$expected['code'].' signed_apk_sha256='.$byId[$id]['sha256'].PHP_EOL;
 }
 echo 'OTHER_FAMILY_MANIFEST_ROWS=preserved'.PHP_EOL;
 echo 'PRODUCTION_MANIFEST_BACKUP=created'.PHP_EOL;
} finally {
 flock($lock,LOCK_UN);fclose($lock);
}

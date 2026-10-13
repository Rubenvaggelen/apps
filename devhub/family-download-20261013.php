<?php
$root='/home/vanawiwj/the-one-private-builds/2026-10-13';
$id=(string)($_GET['app']??'');
$data=json_decode((string)file_get_contents($root.'/manifest.json'),true);
$selected=null;
if(is_array($data))foreach($data as $build)if(($build['id']??'')===$id){$selected=$build;break;}
if(!$selected||!preg_match('/^[A-Za-z0-9._-]+\.apk$/',$selected['apk'])){http_response_code(404);exit('Build niet gevonden');}
$file=$root.'/apks/'.$selected['apk'];
if(!is_file($file)){http_response_code(503);exit('Download tijdelijk niet beschikbaar');}
header('Content-Type: application/vnd.android.package-archive');
header('Content-Disposition: attachment; filename="'.$selected['apk'].'"');
header('Content-Length: '.filesize($file));
header('Cache-Control: private, no-store');
header('X-Content-Type-Options: nosniff');
readfile($file);

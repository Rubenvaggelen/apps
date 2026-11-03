<?php
declare(strict_types=1);
/**
 * Deploy at https://theoneforlive.com/updates/feed.php.
 * Public read-only endpoint. Keep /dev and admin endpoints password protected.
 */
header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: public, max-age=120');
header('X-Content-Type-Options: nosniff');
$apps = [
  'main' => ['prefix'=>'main-v','asset'=>'app-debug.apk'],
  'car' => ['prefix'=>'car-v','asset'=>'carradio-debug.apk'],
  'dj' => ['prefix'=>'dj-v','asset'=>'thedj-debug.apk'],
  'run' => ['prefix'=>'run-v','asset'=>'runcoach-debug.apk'],
];
$app=(string)($_GET['app']??'');
if (!isset($apps[$app])) {
 http_response_code(400);
 echo json_encode(['error'=>'Unknown app']); exit;
}
$cacheFile=sys_get_temp_dir().'/theone-release-cache-v1.json';
$raw=false;
if (is_file($cacheFile) && time()-filemtime($cacheFile)<120) {
 $raw=file_get_contents($cacheFile);
}
if ($raw===false) {
 $ctx=stream_context_create(['http'=>[
  'method'=>'GET','timeout'=>12,'ignore_errors'=>true,
  'header'=>"User-Agent: TheOne-Update-Server\r\nAccept: application/vnd.github+json\r\n"
 ]]);
 $raw=@file_get_contents('https://api.github.com/repos/Rubenvaggelen/apps/releases?per_page=100',false,$ctx);
 if ($raw!==false && json_decode($raw,true)!==null) @file_put_contents($cacheFile,$raw,LOCK_EX);
 else if (is_file($cacheFile)) $raw=@file_get_contents($cacheFile);
}
$releases=is_string($raw)?json_decode($raw,true):null;
if (!is_array($releases) || !array_is_list($releases)) {
 http_response_code(503);echo json_encode(['error'=>'Releases temporarily unavailable']);exit;
}
$prefix=$apps[$app]['prefix'];$assetName=$apps[$app]['asset'];$best=null;
foreach ($releases as $release) {
 if (!is_array($release)||!empty($release['draft'])||!empty($release['prerelease'])) continue;
 $tag=(string)($release['tag_name']??'');
 if (!preg_match('/^'.preg_quote($prefix,'/').'([0-9]+)$/D',$tag,$m)) continue;
 $version=(int)$m[1];
 if ($version<=0 || ($best!==null && $version<=$best['versionCode'])) continue;
 foreach (($release['assets']??[]) as $asset) {
  if (($asset['name']??'')!==$assetName) continue;
  $url=(string)($asset['browser_download_url']??'');
  $expected='https://github.com/Rubenvaggelen/apps/releases/download/'.rawurlencode($tag).'/'.$assetName;
  if ($url!==$expected) continue;
  $best=['app'=>$app,'channel'=>'stable','versionCode'=>$version,'tag'=>$tag,'downloadUrl'=>$url,'publishedAt'=>(string)($release['published_at']??'')];
 }
}
if ($best===null) {
 http_response_code(404);echo json_encode(['error'=>'No approved release available']);exit;
}
echo json_encode($best,JSON_UNESCAPED_SLASHES);

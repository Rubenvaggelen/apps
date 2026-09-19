<?php
session_start();
header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: no-store');
$core='/home/vanawiwj/devhub-core';
require_once $core.'/git.php';
require_once $core.'/files.php';
function respond($data=null,int $status=200):never{http_response_code($status);echo json_encode(['ok'=>$status<400,'data'=>$data],JSON_UNESCAPED_SLASHES|JSON_UNESCAPED_UNICODE);exit;}
function fail(string $message,int $status=400):never{http_response_code($status);echo json_encode(['ok'=>false,'error'=>$message],JSON_UNESCAPED_SLASHES|JSON_UNESCAPED_UNICODE);exit;}
function requireCsrf():void{$sent=$_SERVER['HTTP_X_CSRF_TOKEN']??'';$expected=$_SESSION['csrf']??'';if(!$expected||!$sent||!hash_equals($expected,$sent))fail('Ongeldige beveiligingstoken. Ververs de pagina.',403);}
function bodyJson():array{$raw=file_get_contents('php://input');$data=json_decode($raw?:'{}',true);if(!is_array($data))fail('Ongeldige JSON.');return $data;}
$action=$_GET['action']??'';
try{
 switch($action){
  case 'status': respond(devhubStatus());
  case 'list': $project=(string)($_GET['project']??'');$path=(string)($_GET['path']??'');respond(['project'=>$project,'path'=>$path,'items'=>listProjectFiles($project,$path)]);
  case 'read': $project=(string)($_GET['project']??'');$path=(string)($_GET['path']??'');respond(['project'=>$project,'path'=>$path,'content'=>readProjectFile($project,$path)]);
  case 'save': if($_SERVER['REQUEST_METHOD']!=='POST')fail('POST vereist.',405);requireCsrf();$b=bodyJson();$project=(string)($b['project']??'');$path=(string)($b['path']??'');$content=(string)($b['content']??'');saveProjectFile($project,$path,$content);respond(['saved'=>true]);
  case 'pull': if($_SERVER['REQUEST_METHOD']!=='POST')fail('POST vereist.',405);requireCsrf();if(trim(gitRun('status --short'))!=='')fail('Er zijn lokale wijzigingen. Commit of herstel die eerst.',409);$out=gitRun('pull --ff-only origin main');respond(['output'=>$out?:'Already up to date.']);
  case 'commitpush': if($_SERVER['REQUEST_METHOD']!=='POST')fail('POST vereist.',405);requireCsrf();$b=bodyJson();$message=trim((string)($b['message']??''));if($message===''||mb_strlen($message)>160)fail('Gebruik een commit message van 1–160 tekens.');if(trim(gitRun('status --short'))==='')fail('Er zijn geen wijzigingen om te committen.',409);gitRun('add -A');$commit=gitRun('commit -m '.escapeshellarg($message));if(stripos($commit,'nothing to commit')!==false)fail('Er zijn geen wijzigingen om te committen.',409);$push=gitRun('push origin main');respond(['output'=>trim($commit."\n".$push)]);
  default: fail('Onbekende actie.',404);
 }
}catch(Throwable $e){fail($e->getMessage(),500);}
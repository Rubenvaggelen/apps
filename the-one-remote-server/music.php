<?php
declare(strict_types=1);

header('Cache-Control: no-store');

const PIN_HASH = 'eaf2067e34d6876930d2b304db688ab6b9d5064b7e682f7831297d39fbe01c92';
const TOKEN_TTL = 86400;

$home = dirname((string)($_SERVER['DOCUMENT_ROOT'] ?? __DIR__));
$root = $home . '/the-one-music-cache';
$files = $root . '/files';
$meta = $root . '/meta';
$secretFile = $root . '/secret.key';
$rateFile = $root . '/rate.json';

foreach ([$root, $files, $meta] as $dir) {
    if (!is_dir($dir)) @mkdir($dir, 0700, true);
}
if (!is_file($secretFile)) {
    @file_put_contents($secretFile, bin2hex(random_bytes(32)), LOCK_EX);
    @chmod($secretFile, 0600);
}

function out(int $code, array $body): never {
    http_response_code($code);
    header('Content-Type: application/json; charset=utf-8');
    echo json_encode($body, JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE);
    exit;
}
function read_json(): array {
    $raw = file_get_contents('php://input') ?: '';
    $v = json_decode($raw, true);
    return is_array($v) ? $v : [];
}
function load_json(string $file): array {
    if (!is_file($file)) return [];
    $v = json_decode((string)@file_get_contents($file), true);
    return is_array($v) ? $v : [];
}
function save_json(string $file, array $v): void {
    $tmp = $file . '.tmp.' . bin2hex(random_bytes(4));
    @file_put_contents($tmp, json_encode($v, JSON_PRETTY_PRINT | JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE), LOCK_EX);
    @chmod($tmp, 0600);
    @rename($tmp, $file);
}
function safe_id(string $v): string {
    $v = trim($v);
    if ($v === '' || strlen($v) > 80 || !preg_match('/^[A-Za-z0-9._-]+$/', $v)) out(400, ['ok'=>false,'error'=>'invalid id']);
    return $v;
}
function safe_path(string $v): string {
    $v = ltrim(preg_replace('#/+#', '/', str_replace('\\', '/', trim($v))) ?? '', '/');
    if ($v === '' || str_contains($v, '..') || strlen($v) > 500) out(400, ['ok'=>false,'error'=>'invalid path']);
    return $v;
}
function key_for(string $device, string $stick, string $path): string {
    return hash('sha256', $device . "\n" . $stick . "\n" . $path);
}
function b64u(string $v): string { return rtrim(strtr(base64_encode($v), '+/', '-_'), '='); }
function b64ud(string $v): string|false {
    $v .= str_repeat('=', (4 - strlen($v) % 4) % 4);
    return base64_decode(strtr($v, '-_', '+/'), true);
}
function secret(string $file): string { return trim((string)file_get_contents($file)); }
function token_new(string $secret): string {
    $payload = b64u(json_encode(['exp'=>time()+TOKEN_TTL,'scope'=>'music','n'=>bin2hex(random_bytes(8))]));
    return $payload . '.' . b64u(hash_hmac('sha256', $payload, $secret, true));
}
function token_ok(string $token, string $secret): bool {
    $p = explode('.', $token, 2);
    if (count($p) !== 2) return false;
    if (!hash_equals(b64u(hash_hmac('sha256', $p[0], $secret, true)), $p[1])) return false;
    $raw = b64ud($p[0]);
    $v = is_string($raw) ? json_decode($raw, true) : null;
    return is_array($v) && ($v['scope'] ?? '') === 'music' && (int)($v['exp'] ?? 0) >= time();
}
function bearer(): string {
    $h = trim((string)($_SERVER['HTTP_AUTHORIZATION'] ?? ''));
    return str_starts_with($h, 'Bearer ') ? trim(substr($h, 7)) : '';
}
function require_auth(string $secret): void {
    if (!token_ok(bearer(), $secret)) out(401, ['ok'=>false,'error'=>'auth required']);
}
function mime_for(string $path): string {
    return match (strtolower(pathinfo($path, PATHINFO_EXTENSION))) {
        'mp3'=>'audio/mpeg','m4a','mp4'=>'audio/mp4','aac'=>'audio/aac',
        'ogg','oga'=>'audio/ogg','opus'=>'audio/opus','flac'=>'audio/flac',
        'wav'=>'audio/wav','wma'=>'audio/x-ms-wma',default=>'application/octet-stream'
    };
}
function stream_range(string $path, string $name): never {
    if (!is_file($path)) { http_response_code(404); exit; }
    $size = filesize($path); if ($size === false) { http_response_code(500); exit; }
    $start=0; $end=max(0,$size-1); $status=200;
    if (preg_match('/bytes=(\d*)-(\d*)/', (string)($_SERVER['HTTP_RANGE'] ?? ''), $m)) {
        if ($m[1] !== '') $start=max(0,(int)$m[1]);
        if ($m[2] !== '') $end=min($end,(int)$m[2]);
        if ($start>$end || $start >= $size) { header("Content-Range: bytes */$size"); http_response_code(416); exit; }
        $status=206;
    }
    $length=$end-$start+1;
    http_response_code($status);
    header('Content-Type: '.mime_for($name));
    header('Accept-Ranges: bytes');
    header('Content-Length: '.$length);
    if ($status===206) header("Content-Range: bytes $start-$end/$size");
    $fh=fopen($path,'rb'); if ($fh===false) exit;
    fseek($fh,$start); $left=$length;
    while ($left>0 && !feof($fh)) {
        $chunk=fread($fh,min(1048576,$left));
        if (!is_string($chunk) || $chunk==='') break;
        echo $chunk; $left-=strlen($chunk); flush();
    }
    fclose($fh); exit;
}

$action = (string)($_GET['action'] ?? 'health');
$sec = secret($secretFile);

if ($action === 'health') out(200, ['ok'=>true,'service'=>'The One Music Cache','version'=>1]);

if ($action === 'login') {
    $ip=(string)($_SERVER['REMOTE_ADDR'] ?? 'unknown');
    $rate=load_json($rateFile);
    $now=time();
    $attempts=array_values(array_filter((array)($rate[$ip] ?? []), fn($t)=>is_int($t)&&$now-$t<900));
    if (count($attempts)>=8) out(429,['ok'=>false,'error'=>'too many attempts']);
    $pin=trim((string)(read_json()['pin'] ?? ''));
    if (!hash_equals(PIN_HASH, hash('sha256',$pin))) {
        $attempts[]=$now; $rate[$ip]=$attempts; save_json($rateFile,$rate); usleep(700000);
        out(403,['ok'=>false,'error'=>'invalid pin']);
    }
    unset($rate[$ip]); save_json($rateFile,$rate);
    out(200,['ok'=>true,'token'=>token_new($sec),'expires_in'=>TOKEN_TTL]);
}

if ($action === 'stream') {
    $token=trim((string)($_GET['token'] ?? ''));
    if (!token_ok($token,$sec)) { http_response_code(401); exit; }
    $device=safe_id((string)($_GET['device'] ?? ''));
    $stick=safe_id((string)($_GET['stick'] ?? ''));
    $path=safe_path((string)($_GET['path'] ?? ''));
    stream_range($files.'/'.key_for($device,$stick,$path).'.bin',$path);
}

require_auth($sec);

if ($action === 'catalog') {
    $sticks=[];
    foreach (glob($meta.'/*.json') ?: [] as $f) {
        $v=load_json($f); if ($v!==[]) $sticks[]=$v;
    }
    out(200,['ok'=>true,'sticks'=>$sticks]);
}

if ($action === 'status') {
    $b=read_json();
    $device=safe_id((string)($b['device_id'] ?? ''));
    $stick=safe_id((string)($b['stick_id'] ?? ''));
    $path=safe_path((string)($b['path'] ?? ''));
    out(200,['ok'=>true,'cached'=>is_file($files.'/'.key_for($device,$stick,$path).'.bin')]);
}

if ($action === 'upload') {
    $device=safe_id((string)($_POST['device_id'] ?? ''));
    $stick=safe_id((string)($_POST['stick_id'] ?? ''));
    $path=safe_path((string)($_POST['path'] ?? ''));
    $sha=strtolower(trim((string)($_POST['sha256'] ?? '')));
    if (!preg_match('/^[a-f0-9]{64}$/',$sha)) out(400,['ok'=>false,'error'=>'invalid hash']);
    $tmp=(string)($_FILES['file']['tmp_name'] ?? '');
    if ($tmp==='' || !is_uploaded_file($tmp)) out(400,['ok'=>false,'error'=>'file required']);
    $actual=hash_file('sha256',$tmp);
    if (!is_string($actual) || !hash_equals($sha,strtolower($actual))) out(400,['ok'=>false,'error'=>'hash mismatch']);
    $dest=$files.'/'.key_for($device,$stick,$path).'.bin';
    if (!@move_uploaded_file($tmp,$dest)) out(500,['ok'=>false,'error'=>'upload failed']);
    @chmod($dest,0600);
    out(200,['ok'=>true]);
}

if ($action === 'sync') {
    $b=read_json();
    $device=safe_id((string)($b['device_id'] ?? ''));
    $stick=safe_id((string)($b['stick_id'] ?? ''));
    $rows=[];
    foreach ((array)($b['files'] ?? []) as $item) {
        if (!is_array($item)) continue;
        $path=safe_path((string)($item['path'] ?? ''));
        $sha=strtolower(trim((string)($item['sha256'] ?? '')));
        if (!preg_match('/^[a-f0-9]{64}$/',$sha)) continue;
        $rows[]=[
            'path'=>$path,
            'name'=>basename($path),
            'folder'=>dirname($path)==='.'?'':dirname($path),
            'size'=>max(0,(int)($item['size'] ?? 0)),
            'sha256'=>$sha,
            'modified'=>trim((string)($item['modified'] ?? '')),
            'cached'=>is_file($files.'/'.key_for($device,$stick,$path).'.bin')
        ];
    }
    $doc=[
        'device_id'=>$device,
        'device_name'=>mb_substr(trim((string)($b['device_name'] ?? $device)),0,80),
        'stick_id'=>$stick,
        'stick_name'=>mb_substr(trim((string)($b['stick_name'] ?? $stick)),0,100),
        'updated_at'=>gmdate('c'),
        'files'=>$rows
    ];
    save_json($meta.'/'.$device.'__'.$stick.'.json',$doc);
    out(200,['ok'=>true,'files'=>count($rows)]);
}

out(404,['ok'=>false,'error'=>'unknown action']);

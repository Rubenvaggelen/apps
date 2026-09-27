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
function save_json(string $file, array $v): bool {
    $json = json_encode(
        $v,
        JSON_PRETTY_PRINT | JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE
    );
    if (!is_string($json)) return false;

    $tmp = $file . '.tmp.' . bin2hex(random_bytes(4));
    $written = @file_put_contents($tmp, $json, LOCK_EX);
    if ($written === false || $written !== strlen($json)) {
        @unlink($tmp);
        return false;
    }

    $check = @file_get_contents($tmp);
    if (!is_string($check) || strlen($check) !== strlen($json) || json_decode($check, true) === null) {
        @unlink($tmp);
        return false;
    }

    @chmod($tmp, 0600);
    if (!@rename($tmp, $file)) {
        @unlink($tmp);
        return false;
    }
    return true;
}
function safe_id(string $v): string {
    $v = trim($v);
    if ($v === '' || strlen($v) > 80 || !preg_match('/^[A-Za-z0-9._-]+$/', $v)) out(400, ['ok'=>false,'error'=>'invalid id']);
    return $v;
}
function safe_path(string $v): string {
    $v = ltrim(preg_replace('#/+#', '/', str_replace('\\', '/', trim($v))) ?? '', '/');
    if ($v === '' || strlen($v) > 500 || str_contains($v, "\0")) {
        out(400, ['ok'=>false,'error'=>'invalid path']);
    }

    foreach (explode('/', $v) as $segment) {
        if ($segment === '..' || $segment === '.') {
            out(400, ['ok'=>false,'error'=>'invalid path']);
        }
    }

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
function token_new(string $secret, string $scope='music'): string {
    $payload = b64u(json_encode(['exp'=>time()+TOKEN_TTL,'scope'=>$scope,'n'=>bin2hex(random_bytes(8))]));
    return $payload . '.' . b64u(hash_hmac('sha256', $payload, $secret, true));
}
function token_scope(string $token, string $secret): string {
    $p = explode('.', $token, 2);
    if (count($p) !== 2) return '';
    if (!hash_equals(b64u(hash_hmac('sha256', $p[0], $secret, true)), $p[1])) return '';
    $raw = b64ud($p[0]);
    $v = is_string($raw) ? json_decode($raw, true) : null;
    if (!is_array($v) || (int)($v['exp'] ?? 0) < time()) return '';
    return (string)($v['scope'] ?? '');
}
function token_ok(string $token, string $secret): bool {
    return token_scope($token,$secret) === 'music';
}
function token_read_ok(string $token, string $secret): bool {
    $scope=token_scope($token,$secret);
    return $scope === 'music' || $scope === 'music-read';
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

if ($action === 'health') out(200, ['ok'=>true,'service'=>'The One Music Cache','version'=>4]);

if ($action === 'browse-login') {
    out(200,['ok'=>true,'token'=>token_new($sec,'music-read'),'expires_in'=>TOKEN_TTL]);
}

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
    if (!token_read_ok($token,$sec)) { http_response_code(401); exit; }
    $device=safe_id((string)($_GET['device'] ?? ''));
    $stick=safe_id((string)($_GET['stick'] ?? ''));
    $path=safe_path((string)($_GET['path'] ?? ''));
    stream_range($files.'/'.key_for($device,$stick,$path).'.bin',$path);
}

if ($action === 'catalog') {
    $token=bearer();
    if (!token_read_ok($token,$sec)) out(401,['ok'=>false,'error'=>'auth required']);

    // Alle geautoriseerde The One Family-clients mogen bestaande/inactieve
    // Shared Media-catalogussen lezen. Zo blijven eerder gesynchroniseerde
    // bibliotheken zichtbaar op Main en Car tijdens herstel of als een bron
    // tijdelijk offline is.
    $includeInactive =
        ((string)($_GET['include_inactive'] ?? '')) === '1' &&
        token_read_ok($token,$sec);

    $sticks=[];
    foreach (glob($meta.'/*.json') ?: [] as $f) {
        $v=load_json($f);
        if ($v===[]) continue;

        // Oude metadata zonder online-veld heeft geen betrouwbare
        // aanwezigheidsstatus en wordt daarom als offline behandeld.
        // Zodra een aangesloten stick opnieuw synchroniseert, zet sync online=true.
        $online = array_key_exists('online',$v) && (bool)$v['online'];
        if (!$includeInactive && !$online) continue;

        $sticks[]=$v;
    }
    out(200,['ok'=>true,'sticks'=>$sticks]);
}

require_auth($sec);

if ($action === 'presence') {
    $b=read_json();
    $device=safe_id((string)($b['device_id'] ?? ''));

    $active=[];
    foreach ((array)($b['active_stick_ids'] ?? []) as $rawStick) {
        if (!is_string($rawStick)) continue;
        $active[]=safe_id($rawStick);
    }
    $active=array_values(array_unique($active));

    $changed=0;
    foreach (glob($meta.'/'.$device.'__*.json') ?: [] as $f) {
        $v=load_json($f);
        if ($v===[]) continue;

        $stick=(string)($v['stick_id'] ?? '');
        $online=in_array($stick,$active,true);
        if (!array_key_exists('online',$v) || (bool)$v['online'] !== $online) {
            $changed++;
        }

        $v['online']=$online;
        $v['presence_updated_at']=gmdate('c');
        save_json($f,$v);
    }

    out(200,[
        'ok'=>true,
        'active_sticks'=>count($active),
        'changed'=>$changed
    ]);
}

if ($action === 'status') {
    $b=read_json();
    $device=safe_id((string)($b['device_id'] ?? ''));
    $stick=safe_id((string)($b['stick_id'] ?? ''));
    $path=safe_path((string)($b['path'] ?? ''));
    out(200,['ok'=>true,'cached'=>is_file($files.'/'.key_for($device,$stick,$path).'.bin')]);
}

if ($action === 'upload-start') {
    $b=read_json();
    $device=safe_id((string)($b['device_id'] ?? ''));
    $stick=safe_id((string)($b['stick_id'] ?? ''));
    $path=safe_path((string)($b['path'] ?? ''));
    $sha=strtolower(trim((string)($b['sha256'] ?? '')));
    if (!preg_match('/^[a-f0-9]{64}$/',$sha)) out(400,['ok'=>false,'error'=>'invalid hash']);

    $part=$files.'/'.key_for($device,$stick,$path).'.'.$sha.'.part';
    if (@file_put_contents($part, '', LOCK_EX) === false) {
        out(500,['ok'=>false,'error'=>'cannot start upload']);
    }
    @chmod($part,0600);
    out(200,['ok'=>true,'offset'=>0]);
}

if ($action === 'upload-chunk') {
    $b=read_json();
    $device=safe_id((string)($b['device_id'] ?? ''));
    $stick=safe_id((string)($b['stick_id'] ?? ''));
    $path=safe_path((string)($b['path'] ?? ''));
    $sha=strtolower(trim((string)($b['sha256'] ?? '')));
    $offset=max(0,(int)($b['offset'] ?? 0));
    if (!preg_match('/^[a-f0-9]{64}$/',$sha)) out(400,['ok'=>false,'error'=>'invalid hash']);

    $encoded=(string)($b['data'] ?? '');
    $chunk=base64_decode($encoded,true);
    if (!is_string($chunk) || $chunk==='') out(400,['ok'=>false,'error'=>'invalid chunk']);

    $part=$files.'/'.key_for($device,$stick,$path).'.'.$sha.'.part';
    if (!is_file($part)) out(409,['ok'=>false,'error'=>'upload not started']);

    $current=filesize($part);
    if ($current===false || (int)$current!==$offset) {
        out(409,['ok'=>false,'error'=>'offset mismatch','expected'=>(int)($current===false?0:$current)]);
    }

    $fh=@fopen($part,'c+b');
    if ($fh===false) out(500,['ok'=>false,'error'=>'chunk open failed']);

    if (@fseek($fh,$offset,SEEK_SET)!==0) {
        @fclose($fh);
        out(500,['ok'=>false,'error'=>'chunk seek failed']);
    }

    $length=strlen($chunk);
    $written=0;
    while ($written<$length) {
        $n=@fwrite($fh,substr($chunk,$written));
        if ($n===false || $n===0) {
            @fclose($fh);
            out(500,[
                'ok'=>false,
                'error'=>'chunk write failed',
                'written'=>$written,
                'length'=>$length
            ]);
        }
        $written+=$n;
    }
    @fflush($fh);
    @fclose($fh);
    clearstatcache(true,$part);

    out(200,['ok'=>true,'offset'=>$offset+$written]);
}

if ($action === 'upload-finish') {
    $b=read_json();
    $device=safe_id((string)($b['device_id'] ?? ''));
    $stick=safe_id((string)($b['stick_id'] ?? ''));
    $path=safe_path((string)($b['path'] ?? ''));
    $sha=strtolower(trim((string)($b['sha256'] ?? '')));
    if (!preg_match('/^[a-f0-9]{64}$/',$sha)) out(400,['ok'=>false,'error'=>'invalid hash']);

    $part=$files.'/'.key_for($device,$stick,$path).'.'.$sha.'.part';
    if (!is_file($part)) out(409,['ok'=>false,'error'=>'upload not started']);

    $actual=hash_file('sha256',$part);
    if (!is_string($actual) || !hash_equals($sha,strtolower($actual))) {
        @unlink($part);
        out(400,['ok'=>false,'error'=>'hash mismatch']);
    }

    $dest=$files.'/'.key_for($device,$stick,$path).'.bin';
    if (!@rename($part,$dest)) out(500,['ok'=>false,'error'=>'finalize failed']);
    @chmod($dest,0600);

    $metaFile=$meta.'/'.$device.'__'.$stick.'.json';
    $doc=load_json($metaFile);
    if (isset($doc['files']) && is_array($doc['files'])) {
        foreach ($doc['files'] as &$row) {
            if (is_array($row) && (string)($row['path'] ?? '') === $path) {
                $row['cached']=true;
                $row['sha256']=$sha;
                break;
            }
        }
        unset($row);
        $doc['updated_at']=gmdate('c');
        save_json($metaFile,$doc);
    }

    out(200,['ok'=>true]);
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

    $metaFile=$meta.'/'.$device.'__'.$stick.'.json';
    $doc=load_json($metaFile);
    if (isset($doc['files']) && is_array($doc['files'])) {
        foreach ($doc['files'] as &$row) {
            if (is_array($row) && (string)($row['path'] ?? '') === $path) {
                $row['cached']=true;
                $row['sha256']=$sha;
                break;
            }
        }
        unset($row);
        $doc['updated_at']=gmdate('c');
        save_json($metaFile,$doc);
    }

    out(200,['ok'=>true]);
}

if ($action === 'sync-batch') {
    $b=read_json();
    $device=safe_id((string)($b['device_id'] ?? ''));
    $stick=safe_id((string)($b['stick_id'] ?? ''));
    $batchIndex=max(0,(int)($b['batch_index'] ?? 0));
    $batchTotal=max(1,(int)($b['batch_total'] ?? 1));
    if ($batchIndex >= $batchTotal || $batchTotal > 200) {
        out(400,['ok'=>false,'error'=>'invalid batch']);
    }

    $syncId=safe_id((string)($b['sync_id'] ?? 'default'));
    $tmpMeta=$meta.'/'.$device.'__'.$stick.'__'.$syncId.'.sync.json';
    if ($batchIndex===0) {
        $doc=[
            'device_id'=>$device,
            'device_name'=>mb_substr(trim((string)($b['device_name'] ?? $device)),0,80),
            'stick_id'=>$stick,
            'stick_name'=>mb_substr(trim((string)($b['stick_name'] ?? $stick)),0,100),
            'online'=>true,
            'presence_updated_at'=>gmdate('c'),
            'updated_at'=>gmdate('c'),
            'files'=>[]
        ];
    } else {
        $doc=load_json($tmpMeta);
        if ($doc===[]) out(409,['ok'=>false,'error'=>'batch not started']);
    }

    foreach ((array)($b['files'] ?? []) as $item) {
        if (!is_array($item)) continue;
        $path=safe_path((string)($item['path'] ?? ''));
        $sha=strtolower(trim((string)($item['sha256'] ?? '')));
        if ($sha !== '' && !preg_match('/^[a-f0-9]{64}$/',$sha)) continue;
        $doc['files'][]=[
            'path'=>$path,
            'name'=>basename($path),
            'folder'=>dirname($path)==='.'?'':dirname($path),
            'title'=>mb_substr(trim((string)($item['title'] ?? '')),0,240),
            'artist'=>mb_substr(trim((string)($item['artist'] ?? '')),0,240),
            'album'=>mb_substr(trim((string)($item['album'] ?? '')),0,240),
            'size'=>max(0,(int)($item['size'] ?? 0)),
            'sha256'=>$sha,
            'modified'=>trim((string)($item['modified'] ?? '')),
            'cached'=>is_file($files.'/'.key_for($device,$stick,$path).'.bin')
        ];
    }

    $doc['updated_at']=gmdate('c');
    if ($batchIndex + 1 >= $batchTotal) {
        if (!save_json($meta.'/'.$device.'__'.$stick.'.json',$doc)) {
            out(507,['ok'=>false,'error'=>'catalog storage full']);
        }
        @unlink($tmpMeta);
        out(200,['ok'=>true,'files'=>count($doc['files']),'complete'=>true]);
    }

    if (!save_json($tmpMeta,$doc)) {
        out(507,['ok'=>false,'error'=>'catalog storage full']);
    }
    out(200,['ok'=>true,'files'=>count($doc['files']),'complete'=>false]);
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
        if ($sha !== '' && !preg_match('/^[a-f0-9]{64}$/',$sha)) continue;
        $rows[]=[
            'path'=>$path,
            'name'=>basename($path),
            'folder'=>dirname($path)==='.'?'':dirname($path),
            'title'=>mb_substr(trim((string)($item['title'] ?? '')),0,240),
            'artist'=>mb_substr(trim((string)($item['artist'] ?? '')),0,240),
            'album'=>mb_substr(trim((string)($item['album'] ?? '')),0,240),
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
        'online'=>true,
        'presence_updated_at'=>gmdate('c'),
        'updated_at'=>gmdate('c'),
        'files'=>$rows
    ];
    save_json($meta.'/'.$device.'__'.$stick.'.json',$doc);
    out(200,['ok'=>true,'files'=>count($rows)]);
}

out(404,['ok'=>false,'error'=>'unknown action']);

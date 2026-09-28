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
$favoritesFile = $root . '/favorites.json';
$djQueueFile = $root . '/dj-queue.json';
$deviceRegistryFile = $home . '/the-one-remote-data/main-devices.json';
$deviceOwnerFile = $home . '/the-one-remote-data/main-device-owner.json';

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
function cleanup_stale_upload_parts(string $filesDir, int $maxAgeSeconds = 3600): int {
    $deleted = 0;
    $cutoff = time() - max(300, $maxAgeSeconds);
    foreach (glob($filesDir . '/*.part') ?: [] as $part) {
        $mtime = @filemtime($part);
        if ($mtime !== false && $mtime < $cutoff && @unlink($part)) {
            $deleted++;
        }
    }
    return $deleted;
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
function music_device_allowed(string $deviceId, string $devicesFile, string $ownerFile): bool {
    $devices = load_json($devicesFile);
    $rows = is_array($devices['devices'] ?? null) ? $devices['devices'] : [];
    $device = is_array($rows[$deviceId] ?? null) ? $rows[$deviceId] : null;

    $owner = load_json($ownerFile);
    $ownerId = trim((string)($owner['device_id'] ?? ''));
    if ($ownerId !== '' && hash_equals($ownerId, $deviceId)) return true;

    return is_array($device) && (bool)($device['music_rights'] ?? false);
}

function music_device_scope_allowed(
    string $deviceId,
    string $scope,
    string $devicesFile,
    string $ownerFile
): bool {
    $devices = load_json($devicesFile);
    $rows = is_array($devices['devices'] ?? null) ? $devices['devices'] : [];
    $device = is_array($rows[$deviceId] ?? null) ? $rows[$deviceId] : null;

    $owner = load_json($ownerFile);
    $ownerId = trim((string)($owner['device_id'] ?? ''));
    if ($ownerId !== '' && hash_equals($ownerId, $deviceId)) return true;
    if (!is_array($device)) return false;

    $legacy = (bool)($device['music_rights'] ?? false);
    $rights = is_array($device['access_rights'] ?? null)
        ? $device['access_rights']
        : [];
    return (bool)($rights[$scope] ?? $legacy);
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

function pool_file_for_sha(string $filesDir, string $sha): string {
    return $filesDir . '/sha-' . $sha . '.bin';
}

function cache_file_for(
    string $filesDir,
    string $device,
    string $stick,
    string $path
): string {
    return $filesDir . '/' . key_for($device, $stick, $path) . '.bin';
}

function same_inode(string $a, string $b): bool {
    $sa=@stat($a); $sb=@stat($b);
    return is_array($sa) && is_array($sb) &&
        ($sa['dev'] ?? null) === ($sb['dev'] ?? null) &&
        ($sa['ino'] ?? null) === ($sb['ino'] ?? null);
}

function link_replace(string $source, string $dest): bool {
    if (!is_file($source)) return false;
    if (is_file($dest) && same_inode($source,$dest)) return true;

    $tmp=$dest.'.link.'.bin2hex(random_bytes(4));
    if (!@link($source,$tmp)) {
        @unlink($tmp);
        return false;
    }
    @chmod($tmp,0600);

    if (!@rename($tmp,$dest)) {
        @unlink($tmp);
        return false;
    }
    @chmod($dest,0600);
    return true;
}

function ensure_cached_from_pool(
    string $filesDir,
    string $device,
    string $stick,
    string $path,
    string $sha
): bool {
    $dest=cache_file_for($filesDir,$device,$stick,$path);
    if (is_file($dest)) return true;
    if (!preg_match('/^[a-f0-9]{64}$/',$sha)) return false;

    $pool=pool_file_for_sha($filesDir,$sha);
    if (!is_file($pool)) return false;

    if (@link($pool,$dest) || is_file($dest)) {
        @chmod($dest,0600);
        return true;
    }
    return false;
}

function register_pool_link(string $filesDir, string $sha, string $dest): void {
    if (!preg_match('/^[a-f0-9]{64}$/',$sha) || !is_file($dest)) return;

    $pool=pool_file_for_sha($filesDir,$sha);
    if (is_file($pool)) {
        if (!same_inode($pool,$dest)) {
            link_replace($pool,$dest);
        }
        return;
    }

    if (@link($dest,$pool)) {
        @chmod($pool,0600);
    }
}

function catalog_device_name(array $stick): string {
    return mb_strtolower(trim((string)($stick['device_name'] ?? '')));
}

function catalog_total_count(array $stick): int {
    return is_array($stick['files'] ?? null) ? count($stick['files']) : 0;
}

function catalog_playable_count(array $stick, string $filesDir): int {
    $device = trim((string)($stick['device_id'] ?? ''));
    $stickId = trim((string)($stick['stick_id'] ?? ''));
    if ($device === '' || $stickId === '') return 0;

    $count = 0;
    foreach ((array)($stick['files'] ?? []) as $row) {
        if (!is_array($row)) continue;
        $path = trim((string)($row['path'] ?? ''));
        if ($path === '') continue;
        if (is_file($filesDir . '/' . key_for($device, $stickId, $path) . '.bin')) {
            $count++;
        }
    }
    return $count;
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

if ($action === 'health') out(200, ['ok'=>true,'service'=>'The One Music Cache','version'=>5]);

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

if ($action === 'dj-queue-add') {
    $token=bearer();
    if (!token_read_ok($token,$sec)) out(401,['ok'=>false,'error'=>'auth required']);

    $body=read_json();
    $requestDevice=trim((string)($body['request_device_id'] ?? ''));
    if ($requestDevice === '' ||
        !music_device_scope_allowed($requestDevice,'dj',$deviceRegistryFile,$deviceOwnerFile)) {
        out(403,['ok'=>false,'error'=>'DJ import not allowed']);
    }

    $device=safe_id((string)($body['device_id'] ?? ''));
    $stick=safe_id((string)($body['stick_id'] ?? ''));
    $path=safe_path((string)($body['path'] ?? ''));
    $source=$files.'/'.key_for($device,$stick,$path).'.bin';
    if (!is_file($source)) out(409,['ok'=>false,'error'=>'track not cached yet']);

    $name=trim((string)($body['name'] ?? basename($path)));
    if ($name === '') $name=basename($path);
    $title=trim((string)($body['title'] ?? $name));

    $queue=load_json($djQueueFile);
    $items=is_array($queue['items'] ?? null) ? $queue['items'] : [];
    $dedupe=hash('sha256',$device."\n".$stick."\n".$path);
    foreach ($items as $row) {
        if (!is_array($row)) continue;
        if (($row['status'] ?? '') === 'pending' && ($row['dedupe'] ?? '') === $dedupe) {
            out(200,['ok'=>true,'queued'=>true,'duplicate'=>true,'id'=>(string)($row['id'] ?? '')]);
        }
    }

    $id=bin2hex(random_bytes(12));
    $items[]=[
        'id'=>$id,
        'status'=>'pending',
        'dedupe'=>$dedupe,
        'device_id'=>$device,
        'stick_id'=>$stick,
        'path'=>$path,
        'name'=>mb_substr($name,0,240),
        'title'=>mb_substr($title,0,240),
        'requested_by'=>$requestDevice,
        'created_at'=>gmdate('c')
    ];
    $queue['items']=$items;
    if (!save_json($djQueueFile,$queue)) out(500,['ok'=>false,'error'=>'queue storage failed']);
    out(200,['ok'=>true,'queued'=>true,'id'=>$id]);
}

if ($action === 'dj-queue-list') {
    require_auth($sec);
    $queue=load_json($djQueueFile);
    $items=array_values(array_filter(
        is_array($queue['items'] ?? null) ? $queue['items'] : [],
        fn($row)=>is_array($row) && (($row['status'] ?? '') === 'pending')
    ));
    out(200,['ok'=>true,'items'=>$items]);
}

if ($action === 'dj-queue-ack') {
    require_auth($sec);
    $body=read_json();
    $id=trim((string)($body['id'] ?? ''));
    if ($id === '') out(400,['ok'=>false,'error'=>'id required']);
    $queue=load_json($djQueueFile);
    $items=is_array($queue['items'] ?? null) ? $queue['items'] : [];
    foreach ($items as &$row) {
        if (!is_array($row) || ($row['id'] ?? '') !== $id) continue;
        $row['status']='done';
        $row['completed_at']=gmdate('c');
        break;
    }
    unset($row);
    $queue['items']=$items;
    if (!save_json($djQueueFile,$queue)) out(500,['ok'=>false,'error'=>'queue storage failed']);
    out(200,['ok'=>true]);
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
        // Tijdelijke batchbestanden zijn géén echte Shared Media-bronnen.
        // Zonder deze filter verschijnt een lopende sync als een tweede USB-stick
        // (bijv. "Ruben • Ruben" met alleen de reeds verwerkte batches).
        if (str_ends_with($f, '.sync.json')) continue;

        $v=load_json($f);
        if ($v===[]) continue;

        // Oude metadata zonder online-veld heeft geen betrouwbare
        // aanwezigheidsstatus en wordt daarom als offline behandeld.
        // Zodra een aangesloten stick opnieuw synchroniseert, zet sync online=true.
        $online = array_key_exists('online',$v) && (bool)$v['online'];
        if (!$includeInactive && !$online) continue;

        $sticks[]=$v;
    }

    // Surface is de primaire Shared Media-bron. De vrijwel gelijke Ruben-bron
    // blijft op de achtergrond beschikbaar als fallback, maar wordt normaal
    // niet aan Main/Car getoond.
    $surfaceIndexes=[];
    $rubenIndexes=[];
    foreach ($sticks as $i => $stickRow) {
        $deviceName=catalog_device_name($stickRow);
        if ($deviceName === 'surface') $surfaceIndexes[]=$i;
        if ($deviceName === 'ruben') $rubenIndexes[]=$i;
    }

    $surfaceHealthy=false;
    foreach ($surfaceIndexes as $i) {
        $total=catalog_total_count($sticks[$i]);
        $playable=catalog_playable_count($sticks[$i],$files);
        // Alleen bij echte schade/leegte omschakelen; kleine sync-hiaten mogen
        // de primaire bron niet onnodig laten wisselen.
        if ($total > 0 && $playable >= max(1,(int)floor($total * 0.50))) {
            $surfaceHealthy=true;
            break;
        }
    }

    $rubenUsable=false;
    foreach ($rubenIndexes as $i) {
        if (catalog_playable_count($sticks[$i],$files) > 0) {
            $rubenUsable=true;
            break;
        }
    }

    if ($surfaceHealthy && $rubenIndexes !== []) {
        $sticks=array_values(array_filter(
            $sticks,
            fn($row)=>catalog_device_name($row) !== 'ruben'
        ));
    } elseif (!$surfaceHealthy && $rubenUsable && $surfaceIndexes !== []) {
        // Surface ontbreekt/leeg/corrupt: toon automatisch Ruben als fallback.
        $sticks=array_values(array_filter(
            $sticks,
            fn($row)=>catalog_device_name($row) !== 'surface'
        ));
    }

    out(200,['ok'=>true,'sticks'=>$sticks]);
}


if ($action === 'favorites-list') {
    $token=bearer();
    if (!token_read_ok($token,$sec)) out(401,['ok'=>false,'error'=>'auth required']);

    $requestDevice=safe_id((string)($_GET['request_device_id'] ?? ''));
    if (!music_device_scope_allowed($requestDevice,'favorites',$deviceRegistryFile,$deviceOwnerFile)) {
        out(403,['ok'=>false,'error'=>'favorites rights required']);
    }

    $doc=load_json($favoritesFile);
    $byDevice=is_array($doc['by_device'] ?? null) ? $doc['by_device'] : [];

    // Legacy favorites were previously global. They now belong only to The One
    // (the registered owner) so they never leak to another device.
    $owner=load_json($deviceOwnerFile);
    $ownerId=trim((string)($owner['device_id'] ?? ''));
    $bucket=is_array($byDevice[$requestDevice] ?? null)
        ? $byDevice[$requestDevice]
        : [];
    $items=is_array($bucket['items'] ?? null) ? $bucket['items'] : [];

    if (
        $items===[] &&
        $ownerId!=='' &&
        hash_equals($ownerId,$requestDevice) &&
        is_array($doc['items'] ?? null)
    ) {
        $items=$doc['items'];
    }

    out(200,['ok'=>true,'items'=>array_values($items)]);
}

if ($action === 'favorites-set') {
    $token=bearer();
    if (!token_read_ok($token,$sec)) out(401,['ok'=>false,'error'=>'auth required']);
    $b=read_json();
    $requestDevice=safe_id((string)($b['request_device_id'] ?? ''));
    if (!music_device_allowed($requestDevice,$deviceRegistryFile,$deviceOwnerFile)) {
        out(403,['ok'=>false,'error'=>'music rights required']);
    }

    $kind=strtolower(trim((string)($b['kind'] ?? '')));
    if (!in_array($kind,['mix','usb'],true)) out(400,['ok'=>false,'error'=>'invalid kind']);

    $title=mb_substr(trim((string)($b['title'] ?? '')),0,240);
    if ($title==='') out(400,['ok'=>false,'error'=>'title required']);
    $sourceLabel=mb_substr(trim((string)($b['source_label'] ?? '')),0,160);
    $device=trim((string)($b['device_id'] ?? ''));
    $stick=trim((string)($b['stick_id'] ?? ''));
    $path=trim((string)($b['path'] ?? ''));
    $url=trim((string)($b['url'] ?? ''));

    if ($kind==='usb') {
        $device=safe_id($device);
        $stick=safe_id($stick);
        $path=safe_path($path);
        $identity="usb\n".$device."\n".$stick."\n".$path;
    } else {
        if ($url==='' || !preg_match('#^https?://#i',$url)) out(400,['ok'=>false,'error'=>'url required']);
        if (strlen($url)>2000) out(400,['ok'=>false,'error'=>'url too long']);
        $identity="mix\n".$url;
        $device=''; $stick=''; $path='';
    }

    $id=hash('sha256',$identity);
    $doc=load_json($favoritesFile);
    $byDevice=is_array($doc['by_device'] ?? null) ? $doc['by_device'] : [];

    $owner=load_json($deviceOwnerFile);
    $ownerId=trim((string)($owner['device_id'] ?? ''));

    $bucket=is_array($byDevice[$requestDevice] ?? null)
        ? $byDevice[$requestDevice]
        : [];
    $existing=is_array($bucket['items'] ?? null) ? $bucket['items'] : [];

    // Migrate the old global list only into the owner's private list.
    if (
        $existing===[] &&
        $ownerId!=='' &&
        hash_equals($ownerId,$requestDevice) &&
        is_array($doc['items'] ?? null)
    ) {
        $existing=$doc['items'];
    }

    $items=[];
    foreach ($existing as $row) {
        if (!is_array($row)) continue;
        $rowId=(string)($row['id'] ?? '');
        if ($rowId!=='' && $rowId!==$id) $items[]=$row;
    }

    $favorite=filter_var($b['favorite'] ?? true,FILTER_VALIDATE_BOOLEAN);
    if ($favorite) {
        array_unshift($items,[
            'id'=>$id,
            'kind'=>$kind,
            'title'=>$title,
            'source_label'=>$sourceLabel,
            'device_id'=>$device,
            'stick_id'=>$stick,
            'path'=>$path,
            'url'=>$url,
            'added_at'=>gmdate('c')
        ]);
        if (count($items)>1000) $items=array_slice($items,0,1000);
    }

    $byDevice[$requestDevice]=[
        'items'=>$items,
        'updated_at'=>gmdate('c')
    ];

    // Deliberately drop the old global "items" field. Favorites are private
    // per device from this point forward.
    if (!save_json($favoritesFile,[
        'by_device'=>$byDevice,
        'updated_at'=>gmdate('c')
    ])) {
        out(507,['ok'=>false,'error'=>'favorites storage unavailable']);
    }

    out(200,['ok'=>true,'favorite'=>$favorite,'id'=>$id]);
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
    out(200,['ok'=>true,'cached'=>ensure_cached_from_pool($files,$device,$stick,$path,$sha)]);
}

if ($action === 'upload-start') {
    cleanup_stale_upload_parts($files);
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

    $dest=cache_file_for($files,$device,$stick,$path);
    $pool=pool_file_for_sha($files,$sha);

    if (is_file($pool)) {
        @unlink($part);
        if (!link_replace($pool,$dest)) {
            out(500,['ok'=>false,'error'=>'finalize dedupe link failed']);
        }
    } else {
        if (!@rename($part,$dest)) out(500,['ok'=>false,'error'=>'finalize failed']);
        @chmod($dest,0600);
        register_pool_link($files,$sha,$dest);
    }

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
    $dest=cache_file_for($files,$device,$stick,$path);
    $pool=pool_file_for_sha($files,$sha);

    if (is_file($pool)) {
        if (!link_replace($pool,$dest)) {
            out(500,['ok'=>false,'error'=>'upload dedupe link failed']);
        }
        @unlink($tmp);
    } else {
        if (!@move_uploaded_file($tmp,$dest)) out(500,['ok'=>false,'error'=>'upload failed']);
        @chmod($dest,0600);
        register_pool_link($files,$sha,$dest);
    }

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
    cleanup_stale_upload_parts($files);
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
            'cached'=>ensure_cached_from_pool($files,$device,$stick,$path,$sha)
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
            'cached'=>ensure_cached_from_pool($files,$device,$stick,$path,$sha)
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

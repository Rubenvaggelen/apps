<?php
declare(strict_types=1);

header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: no-store');

const MAIN_ADMIN_SHA256 = '616f55173c48091a11f9d643846e32f77f9f949896747c85cf953d931956c8fe';
const MAIN_OWNER_RECOVERY_SHA256 = '1ba50f63c061b1c2bf3fafdd2b4d655d75f595eb2c23d3e1da5fed386f5978dc';

$home = dirname((string)($_SERVER['DOCUMENT_ROOT'] ?? __DIR__));
$dataDir = $home . '/the-one-remote-data';
$devicesFile = $dataDir . '/main-devices.json';
$ownerFile = $dataDir . '/main-device-owner.json';

if (!is_dir($dataDir)) {
    @mkdir($dataDir, 0700, true);
}

function respond_devices(int $status, array $body): never {
    http_response_code($status);
    echo json_encode($body, JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE);
    exit;
}

function devices_body(): array {
    if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
        respond_devices(405, ['ok' => false, 'error' => 'POST required']);
    }
    $raw = file_get_contents('php://input') ?: '';
    $data = json_decode($raw, true);
    if (!is_array($data)) respond_devices(400, ['ok' => false, 'error' => 'Invalid JSON']);
    return $data;
}

function devices_load(string $file): array {
    if (!is_file($file)) return ['devices' => []];
    $raw = @file_get_contents($file);
    $state = is_string($raw) ? json_decode($raw, true) : null;
    if (!is_array($state)) return ['devices' => []];
    if (!isset($state['devices']) || !is_array($state['devices'])) $state['devices'] = [];
    return $state;
}

function devices_save(string $file, array $state): void {
    $tmp = $file . '.tmp.' . bin2hex(random_bytes(4));
    $json = json_encode($state, JSON_PRETTY_PRINT | JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE);
    if (@file_put_contents($tmp, $json, LOCK_EX) === false) {
        respond_devices(500, ['ok' => false, 'error' => 'Storage unavailable']);
    }
    @chmod($tmp, 0600);
    if (!@rename($tmp, $file)) {
        @unlink($tmp);
        respond_devices(500, ['ok' => false, 'error' => 'Storage unavailable']);
    }
}

function devices_admin(array $body): bool {
    $pin = trim((string)($body['pin'] ?? ''));
    return $pin !== '' && hash_equals(MAIN_ADMIN_SHA256, hash('sha256', $pin));
}

function devices_owner_id(string $file): string {
    if (!is_file($file)) return '';
    $raw = @file_get_contents($file);
    $data = is_string($raw) ? json_decode($raw, true) : null;
    return is_array($data) ? trim((string)($data['device_id'] ?? '')) : '';
}

function devices_save_owner(string $file, string $deviceId): void {
    $tmp = $file . '.tmp.' . bin2hex(random_bytes(4));
    $json = json_encode(['device_id' => $deviceId, 'claimed' => gmdate('c')], JSON_PRETTY_PRINT);
    if (@file_put_contents($tmp, $json, LOCK_EX) === false) {
        respond_devices(500, ['ok' => false, 'error' => 'Storage unavailable']);
    }
    @chmod($tmp, 0600);
    if (!@rename($tmp, $file)) {
        @unlink($tmp);
        respond_devices(500, ['ok' => false, 'error' => 'Storage unavailable']);
    }
}

function devices_owner_authorized(array $body, string $ownerFile): bool {
    if (!devices_admin($body)) return false;
    $deviceId = trim((string)($body['request_device_id'] ?? ''));
    $ownerId = devices_owner_id($ownerFile);
    return $deviceId !== '' && $ownerId !== '' && hash_equals($ownerId, $deviceId);
}

function clean_device_id(string $value): string {
    $value = trim($value);
    if ($value === '' || strlen($value) > 100 || !preg_match('/^[A-Za-z0-9._:-]+$/', $value)) {
        respond_devices(400, ['ok' => false, 'error' => 'Invalid device']);
    }
    return $value;
}

$action = (string)($_GET['action'] ?? 'health');

if ($action === 'health') {
    respond_devices(200, ['ok' => true, 'service' => 'The One Main Device Registry', 'version' => 1]);
}

$body = devices_body();

if ($action === 'heartbeat') {
    $deviceId = clean_device_id((string)($body['device_id'] ?? ''));
    $state = devices_load($devicesFile);
    $old = is_array($state['devices'][$deviceId] ?? null) ? $state['devices'][$deviceId] : [];
    $name = trim((string)($body['name'] ?? 'Android apparaat'));
    $platform = trim((string)($body['platform'] ?? 'Android'));
    $version = trim((string)($body['version'] ?? ''));

    $state['devices'][$deviceId] = [
        'device_id' => $deviceId,
        'name' => mb_substr($name !== '' ? $name : 'Android apparaat', 0, 100),
        'platform' => mb_substr($platform !== '' ? $platform : 'Android', 0, 40),
        'version' => mb_substr($version, 0, 40),
        'blocked' => (bool)($old['blocked'] ?? false),
        'registered' => (string)($old['registered'] ?? gmdate('c')),
        'last_seen' => time()
    ];
    devices_save($devicesFile, $state);
    $ownerId = devices_owner_id($ownerFile);
    respond_devices(200, [
        'ok' => true,
        'blocked' => (bool)$state['devices'][$deviceId]['blocked'],
        'owner' => $ownerId !== '' && hash_equals($ownerId, $deviceId)
    ]);
}

if ($action === 'claim_owner') {
    if (!devices_admin($body)) {
        usleep(300000);
        respond_devices(403, ['ok' => false, 'error' => 'Unauthorized']);
    }

    $model = strtoupper(trim((string)($body['request_model'] ?? '')));
    if ($model !== 'SM-S931B') {
        respond_devices(403, ['ok' => false, 'error' => 'Owner device required']);
    }

    $deviceId = clean_device_id((string)($body['request_device_id'] ?? ''));
    $state = devices_load($devicesFile);
    $device = $state['devices'][$deviceId] ?? null;
    if (!is_array($device) || (time() - (int)($device['last_seen'] ?? 0)) > 300) {
        respond_devices(409, ['ok' => false, 'error' => 'Device must register first']);
    }

    $ownerId = devices_owner_id($ownerFile);
    if ($ownerId !== '' && !hash_equals($ownerId, $deviceId)) {
        respond_devices(403, ['ok' => false, 'error' => 'Owner already assigned']);
    }
    if ($ownerId === '') {
        devices_save_owner($ownerFile, $deviceId);
    }
    respond_devices(200, ['ok' => true, 'owner' => true]);
}

if ($action === 'owner_status') {
    if (!devices_admin($body)) {
        usleep(300000);
        respond_devices(403, ['ok' => false, 'error' => 'Unauthorized']);
    }
    $deviceId = clean_device_id((string)($body['request_device_id'] ?? ''));
    $ownerId = devices_owner_id($ownerFile);
    respond_devices(200, [
        'ok' => true,
        'owner' => $ownerId !== '' && hash_equals($ownerId, $deviceId)
    ]);
}

if ($action === 'recover_owner') {
    if (!devices_admin($body)) {
        usleep(750000);
        respond_devices(403, ['ok' => false, 'error' => 'Unauthorized']);
    }

    $recovery = strtoupper(trim((string)($body['recovery_code'] ?? '')));
    if ($recovery === '' || !hash_equals(MAIN_OWNER_RECOVERY_SHA256, hash('sha256', $recovery))) {
        usleep(750000);
        respond_devices(403, ['ok' => false, 'error' => 'Invalid recovery code']);
    }

    $deviceId = clean_device_id((string)($body['request_device_id'] ?? ''));
    $state = devices_load($devicesFile);
    $device = $state['devices'][$deviceId] ?? null;
    if (!is_array($device)) {
        respond_devices(409, ['ok' => false, 'error' => 'Register this device first']);
    }

    $lastSeen = (int)($device['last_seen'] ?? 0);
    if ($lastSeen <= 0 || (time() - $lastSeen) > 300) {
        respond_devices(409, ['ok' => false, 'error' => 'Device must be online']);
    }

    devices_save_owner($ownerFile, $deviceId);
    respond_devices(200, ['ok' => true, 'owner' => true, 'recovered' => true]);
}

if (!devices_owner_authorized($body, $ownerFile)) {
    usleep(300000);
    respond_devices(403, ['ok' => false, 'error' => 'Owner device required']);
}

$state = devices_load($devicesFile);

if ($action === 'list') {
    $now = time();
    $devices = array_values(array_map(function ($d) use ($now) {
        $lastSeen = (int)($d['last_seen'] ?? 0);
        $d['online'] = $lastSeen > 0 && ($now - $lastSeen) <= 90;
        return $d;
    }, $state['devices']));
    usort($devices, fn($a, $b) => ((int)($b['last_seen'] ?? 0)) <=> ((int)($a['last_seen'] ?? 0)));
    respond_devices(200, ['ok' => true, 'devices' => $devices]);
}

if ($action === 'set_blocked') {
    $deviceId = clean_device_id((string)($body['device_id'] ?? ''));
    if (!isset($state['devices'][$deviceId]) || !is_array($state['devices'][$deviceId])) {
        respond_devices(404, ['ok' => false, 'error' => 'Device not found']);
    }
    $state['devices'][$deviceId]['blocked'] = filter_var($body['blocked'] ?? false, FILTER_VALIDATE_BOOLEAN);
    $state['devices'][$deviceId]['blocked_updated'] = gmdate('c');
    devices_save($devicesFile, $state);
    respond_devices(200, [
        'ok' => true,
        'device_id' => $deviceId,
        'blocked' => (bool)$state['devices'][$deviceId]['blocked']
    ]);
}

respond_devices(404, ['ok' => false, 'error' => 'Unknown action']);

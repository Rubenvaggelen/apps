<?php
declare(strict_types=1);

header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: no-store');

const MAIN_ADMIN_SHA256 = '616f55173c48091a11f9d643846e32f77f9f949896747c85cf953d931956c8fe';
const DEVICES_HUB_SYNC_SECRET_HASH = '90c09d64bb3d96b2cf7b1be1e27ac49096a0087577e1d2ac22cc53adbd55dae6';
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

function devices_owner_person_name(string $file): string {
    if (!is_file($file)) return '';
    $raw = @file_get_contents($file);
    $data = is_string($raw) ? json_decode($raw, true) : null;
    return is_array($data) ? trim((string)($data['person_name'] ?? '')) : '';
}

function devices_save_owner(string $file, string $deviceId, string $personName = ''): void {
    $tmp = $file . '.tmp.' . bin2hex(random_bytes(4));
    $json = json_encode([
        'device_id' => $deviceId,
        'person_name' => mb_substr(trim($personName), 0, 80),
        'claimed' => gmdate('c')
    ], JSON_PRETTY_PRINT | JSON_UNESCAPED_UNICODE);
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

function clean_access_scope(string $value): string {
    $scope = strtolower(trim($value));
    if (!in_array($scope, ['media_player', 'mixes', 'shared', 'favorites', 'dj', 'downloads', 'download_files'], true)) {
        respond_devices(400, ['ok' => false, 'error' => 'Invalid access scope']);
    }
    return $scope;
}

function device_access_rights(array $device): array {
    $legacy = (bool)($device['music_rights'] ?? false);
    $stored = is_array($device['access_rights'] ?? null) ? $device['access_rights'] : [];
    return [
        'media_player' => (bool)($stored['media_player'] ?? false),
        'mixes' => (bool)($stored['mixes'] ?? $legacy),
        'shared' => (bool)($stored['shared'] ?? $legacy),
        'favorites' => (bool)($stored['favorites'] ?? $legacy),
        'dj' => (bool)($stored['dj'] ?? false),
        // Nieuwe downloads blijft standaard privé. Legacy muziekrechten geven
        // dit beheerrecht bewust niet automatisch.
        'downloads' => (bool)($stored['downloads'] ?? false),
        // Los recht om bestanden echt naar het toestel te downloaden.
        // Standaard altijd uit, ook als iemand legacy muziekrechten heeft.
        'download_files' => (bool)($stored['download_files'] ?? false)
    ];
}

function device_access_requests(array $device): array {
    $stored = is_array($device['access_requests'] ?? null) ? $device['access_requests'] : [];
    $result = [];
    foreach (['mixes', 'shared', 'favorites', 'dj', 'downloads', 'download_files'] as $scope) {
        $row = is_array($stored[$scope] ?? null) ? $stored[$scope] : [];
        if (($row['status'] ?? '') === 'pending') {
            $result[$scope] = [
                'status' => 'pending',
                'requested_at' => (string)($row['requested_at'] ?? '')
            ];
        }
    }
    return $result;
}

$action = (string)($_GET['action'] ?? 'health');

if ($action === 'health') {
    respond_devices(200, ['ok' => true, 'service' => 'The One Main Device Registry', 'version' => 3]);
}

$body = devices_body();

if ($action === 'hub_device_list') {
    $secret = trim((string)($body['secret'] ?? ''));
    if (
        $secret === '' ||
        !hash_equals(DEVICES_HUB_SYNC_SECRET_HASH, hash('sha256', $secret))
    ) {
        usleep(250000);
        respond_devices(403, ['ok' => false, 'error' => 'Hub auth required']);
    }

    $state = devices_load($devicesFile);
    $ownerId = devices_owner_id($ownerFile);
    $devices = [];
    foreach ($state['devices'] as $d) {
        if (!is_array($d)) continue;
        $deviceId = trim((string)($d['device_id'] ?? ''));
        $owner = $ownerId !== '' && $deviceId !== '' && hash_equals($ownerId, $deviceId);
        $devices[] = [
            'device_id' => $deviceId,
            'name' => (string)($d['name'] ?? ''),
            'person_name' => (string)($d['person_name'] ?? ''),
            'device_role' => (string)($d['device_role'] ?? 'main'),
            'version' => (string)($d['version'] ?? ''),
            'last_seen' => (int)($d['last_seen'] ?? 0),
            'blocked' => (bool)($d['blocked'] ?? false),
            'owner' => $owner,
            'music_rights' => $owner || (bool)($d['music_rights'] ?? false),
            'access_rights' => $owner
                ? [
                    'media_player' => true,
                    'mixes' => true,
                    'shared' => true,
                    'favorites' => true,
                    'dj' => true,
                    'downloads' => true,
                    'download_files' => true
                ]
                : device_access_rights($d),
            'access_requests' => device_access_requests($d)
        ];
    }
    respond_devices(200, ['ok' => true, 'devices' => $devices]);
}

if ($action === 'registration_status') {
    respond_devices(200, [
        'ok' => true,
        'the_one_registered' => devices_owner_person_name($ownerFile) !== ''
    ]);
}

if ($action === 'heartbeat') {
    $deviceId = clean_device_id((string)($body['device_id'] ?? ''));
    $state = devices_load($devicesFile);
    $old = is_array($state['devices'][$deviceId] ?? null) ? $state['devices'][$deviceId] : [];
    $name = trim((string)($body['name'] ?? 'Android apparaat'));
    $personName = trim((string)($body['person_name'] ?? ''));
    $platform = trim((string)($body['platform'] ?? 'Android'));
    $version = trim((string)($body['version'] ?? ''));
    $deviceRole = strtolower(trim((string)($body['device_role'] ?? ($old['device_role'] ?? 'main'))));
    if (!in_array($deviceRole, ['main', 'car', 'windows'], true)) $deviceRole = 'main';

    $storedPersonName = $personName !== ''
        ? mb_substr($personName, 0, 80)
        : mb_substr((string)($old['person_name'] ?? ''), 0, 80);

    // Nieuwe Family-apparaten worden pas zichtbaar nadat de gebruiker één keer
    // zijn/haar naam heeft ingevuld.
    if ($storedPersonName === '') {
        respond_devices(409, [
            'ok' => false,
            'error' => 'person name required',
            'name_required' => true
        ]);
    }

    $existingRights = device_access_rights($old);
    $carFullAccess = $deviceRole === 'car';
    $scopedRights = $carFullAccess
        ? ['media_player' => true, 'mixes' => true, 'shared' => true, 'favorites' => true, 'dj' => false, 'downloads' => false, 'download_files' => false]
        : $existingRights;

    $state['devices'][$deviceId] = [
        'device_id' => $deviceId,
        'name' => mb_substr($name !== '' ? $name : 'Android apparaat', 0, 100),
        'person_name' => $storedPersonName,
        'device_role' => $deviceRole,
        'platform' => mb_substr($platform !== '' ? $platform : 'Android', 0, 40),
        'version' => mb_substr($version, 0, 40),
        'blocked' => (bool)($old['blocked'] ?? false),
        'music_rights' => $carFullAccess ? true : (bool)($old['music_rights'] ?? false),
        'access_rights' => $scopedRights,
        'access_requests' => $carFullAccess ? [] : device_access_requests($old),
        'registered' => (string)($old['registered'] ?? gmdate('c')),
        'last_seen' => time()
    ];
    devices_save($devicesFile, $state);
    $ownerId = devices_owner_id($ownerFile);
    $owner = $ownerId !== '' && hash_equals($ownerId, $deviceId);
    $rights = device_access_rights($state['devices'][$deviceId]);
    if ($owner) {
        $rights = ['media_player' => true, 'mixes' => true, 'shared' => true, 'favorites' => true, 'dj' => true, 'downloads' => true, 'download_files' => true];
    }
    respond_devices(200, [
        'ok' => true,
        'blocked' => (bool)$state['devices'][$deviceId]['blocked'],
        'owner' => $owner,
        'music_rights' => $owner || (bool)($state['devices'][$deviceId]['music_rights'] ?? false),
        'access_rights' => $rights,
        'access_requests' => device_access_requests($state['devices'][$deviceId]),
        'the_one_registered' => devices_owner_person_name($ownerFile) !== ''
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
    $personName = trim((string)($device['person_name'] ?? ''));
    if ($ownerId === '' || hash_equals($ownerId, $deviceId)) {
        devices_save_owner($ownerFile, $deviceId, $personName);
    }
    respond_devices(200, [
        'ok' => true,
        'owner' => true,
        'the_one_registered' => $personName !== ''
    ]);
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

    devices_save_owner(
        $ownerFile,
        $deviceId,
        trim((string)($device['person_name'] ?? ''))
    );
    respond_devices(200, ['ok' => true, 'owner' => true, 'recovered' => true]);
}

if ($action === 'pending_requests') {
    $requestDeviceId = clean_device_id((string)($body['request_device_id'] ?? ''));
    $ownerId = devices_owner_id($ownerFile);
    if ($ownerId === '' || !hash_equals($ownerId, $requestDeviceId)) {
        respond_devices(403, ['ok' => false, 'error' => 'Owner device required']);
    }

    $state = devices_load($devicesFile);
    $requests = [];
    foreach ($state['devices'] as $deviceId => $d) {
        if (!is_array($d)) continue;
        $personName = trim((string)($d['person_name'] ?? ''));
        $deviceName = trim((string)($d['name'] ?? 'Apparaat'));
        foreach (device_access_requests($d) as $scope => $row) {
            $requests[] = [
                'device_id' => (string)$deviceId,
                'person_name' => $personName,
                'device_name' => $deviceName,
                'scope' => (string)$scope,
                'requested_at' => (string)($row['requested_at'] ?? '')
            ];
        }
    }

    usort($requests, fn($a,$b) => strcmp(
        (string)($b['requested_at'] ?? ''),
        (string)($a['requested_at'] ?? '')
    ));
    respond_devices(200, ['ok' => true, 'requests' => $requests]);
}

if ($action === 'connection_status') {
    $requestDeviceId = clean_device_id((string)($body['request_device_id'] ?? ''));
    $ownerId = devices_owner_id($ownerFile);
    if ($ownerId === '' || !hash_equals($ownerId, $requestDeviceId)) {
        respond_devices(403, ['ok' => false, 'error' => 'Owner device required']);
    }

    $state = devices_load($devicesFile);
    $now = time();
    $devices = [];
    foreach ($state['devices'] as $d) {
        if (!is_array($d)) continue;
        $platform = trim((string)($d['platform'] ?? ''));
        if (stripos($platform, 'Windows') !== 0) continue;
        $lastSeen = (int)($d['last_seen'] ?? 0);
        $devices[] = [
            'device_id' => (string)($d['device_id'] ?? ''),
            'name' => (string)($d['name'] ?? 'Windows apparaat'),
            'online' => $lastSeen > 0 && ($now - $lastSeen) <= 70,
            'last_seen' => $lastSeen
        ];
    }
    respond_devices(200, ['ok' => true, 'devices' => $devices]);
}

if ($action === 'access_status') {
    $deviceId = clean_device_id((string)($body['device_id'] ?? ''));
    $scope = clean_access_scope((string)($body['scope'] ?? ''));
    $state = devices_load($devicesFile);
    $ownerId = devices_owner_id($ownerFile);
    $device = is_array($state['devices'][$deviceId] ?? null) ? $state['devices'][$deviceId] : [];
    $owner = $ownerId !== '' && hash_equals($ownerId, $deviceId);
    $rights = device_access_rights($device);
    $requests = device_access_requests($device);
    respond_devices(200, [
        'ok' => true,
        'allowed' => $owner || (bool)($rights[$scope] ?? false),
        'pending' => isset($requests[$scope]),
        'owner' => $owner,
        'scope' => $scope,
        'person_name' => (string)($device['person_name'] ?? '')
    ]);
}

if ($action === 'request_access') {
    $deviceId = clean_device_id((string)($body['device_id'] ?? ''));
    $scope = clean_access_scope((string)($body['scope'] ?? ''));
    $state = devices_load($devicesFile);
    if (!isset($state['devices'][$deviceId]) || !is_array($state['devices'][$deviceId])) {
        respond_devices(404, ['ok' => false, 'error' => 'Device not found']);
    }

    $ownerId = devices_owner_id($ownerFile);
    if ($ownerId !== '' && hash_equals($ownerId, $deviceId)) {
        respond_devices(200, ['ok' => true, 'allowed' => true, 'pending' => false, 'scope' => $scope]);
    }

    $rights = device_access_rights($state['devices'][$deviceId]);
    if ((bool)($rights[$scope] ?? false)) {
        respond_devices(200, ['ok' => true, 'allowed' => true, 'pending' => false, 'scope' => $scope]);
    }

    $requests = is_array($state['devices'][$deviceId]['access_requests'] ?? null)
        ? $state['devices'][$deviceId]['access_requests']
        : [];
    $requests[$scope] = [
        'status' => 'pending',
        'requested_at' => gmdate('c')
    ];
    $state['devices'][$deviceId]['access_requests'] = $requests;
    devices_save($devicesFile, $state);

    respond_devices(200, ['ok' => true, 'allowed' => false, 'pending' => true, 'scope' => $scope]);
}

if ($action === 'music_access') {
    $deviceId = clean_device_id((string)($body['device_id'] ?? ''));
    $state = devices_load($devicesFile);
    $ownerId = devices_owner_id($ownerFile);
    $device = $state['devices'][$deviceId] ?? null;
    $owner = $ownerId !== '' && hash_equals($ownerId, $deviceId);
    $allowed = $owner || (is_array($device) && (bool)($device['music_rights'] ?? false));
    respond_devices(200, [
        'ok' => true,
        'allowed' => $allowed,
        'owner' => $owner,
        'person_name' => is_array($device) ? (string)($device['person_name'] ?? '') : ''
    ]);
}

if (!devices_owner_authorized($body, $ownerFile)) {
    usleep(300000);
    respond_devices(403, ['ok' => false, 'error' => 'Owner device required']);
}

$state = devices_load($devicesFile);

if ($action === 'list') {
    $now = time();
    $ownerId = devices_owner_id($ownerFile);
    $devices = [];
    foreach ($state['devices'] as $d) {
        if (!is_array($d)) continue;
        $lastSeen = (int)($d['last_seen'] ?? 0);
        $deviceId = trim((string)($d['device_id'] ?? ''));
        $owner = $ownerId !== '' && $deviceId !== '' && hash_equals($ownerId, $deviceId);
        $personName = trim((string)($d['person_name'] ?? ''));

        // Oude installaties zonder naam blijven uit de beheerweergave. Zodra
        // dat apparaat de nieuwe versie opent, wordt eerst om een naam gevraagd.
        if (!$owner && $personName === '') continue;

        $d['online'] = $lastSeen > 0 && ($now - $lastSeen) <= 90;
        $d['owner'] = $owner;
        $d['music_rights'] = $owner || (bool)($d['music_rights'] ?? false);
        $d['access_rights'] = $owner
            ? ['media_player' => true, 'mixes' => true, 'shared' => true, 'favorites' => true, 'dj' => true, 'downloads' => true, 'download_files' => true]
            : device_access_rights($d);
        $d['access_requests'] = device_access_requests($d);
        $d['person_name'] = $personName;
        $devices[] = $d;
    }
    usort($devices, fn($a, $b) => ((int)($b['last_seen'] ?? 0)) <=> ((int)($a['last_seen'] ?? 0)));
    respond_devices(200, ['ok' => true, 'devices' => $devices]);
}

if ($action === 'set_access_right') {
    $deviceId = clean_device_id((string)($body['device_id'] ?? ''));
    $scope = clean_access_scope((string)($body['scope'] ?? ''));
    if (!isset($state['devices'][$deviceId]) || !is_array($state['devices'][$deviceId])) {
        respond_devices(404, ['ok' => false, 'error' => 'Device not found']);
    }

    $ownerId = devices_owner_id($ownerFile);
    $ownerTarget = $ownerId !== '' && hash_equals($ownerId, $deviceId);
    $enabled = filter_var($body['enabled'] ?? false, FILTER_VALIDATE_BOOLEAN);
    if ($ownerTarget && !$enabled) {
        respond_devices(409, ['ok' => false, 'error' => 'Owner keeps all access rights']);
    }

    $rights = device_access_rights($state['devices'][$deviceId]);
    $rights[$scope] = $ownerTarget ? true : $enabled;
    $state['devices'][$deviceId]['access_rights'] = $rights;

    $requests = is_array($state['devices'][$deviceId]['access_requests'] ?? null)
        ? $state['devices'][$deviceId]['access_requests']
        : [];
    unset($requests[$scope]);
    $state['devices'][$deviceId]['access_requests'] = $requests;
    $state['devices'][$deviceId]['access_rights_updated'] = gmdate('c');

    devices_save($devicesFile, $state);
    respond_devices(200, [
        'ok' => true,
        'device_id' => $deviceId,
        'scope' => $scope,
        'enabled' => (bool)$rights[$scope]
    ]);
}

if ($action === 'set_music_rights') {
    $deviceId = clean_device_id((string)($body['device_id'] ?? ''));
    if (!isset($state['devices'][$deviceId]) || !is_array($state['devices'][$deviceId])) {
        respond_devices(404, ['ok' => false, 'error' => 'Device not found']);
    }

    $ownerId = devices_owner_id($ownerFile);
    $ownerTarget = $ownerId !== '' && hash_equals($ownerId, $deviceId);
    $enabled = filter_var($body['enabled'] ?? false, FILTER_VALIDATE_BOOLEAN);
    if ($ownerTarget && !$enabled) {
        respond_devices(409, ['ok' => false, 'error' => 'Owner keeps music rights']);
    }

    $state['devices'][$deviceId]['music_rights'] = $ownerTarget ? true : $enabled;
    $state['devices'][$deviceId]['access_rights'] = [
        'media_player' => $ownerTarget ? true : $enabled,
        'mixes' => $ownerTarget ? true : $enabled,
        'shared' => $ownerTarget ? true : $enabled,
        'favorites' => $ownerTarget ? true : $enabled,
        'dj' => false,
        'downloads' => $ownerTarget ? true : false,
        'download_files' => $ownerTarget ? true : false
    ];
    $state['devices'][$deviceId]['access_requests'] = [];
    $state['devices'][$deviceId]['music_rights_updated'] = gmdate('c');
    devices_save($devicesFile, $state);
    respond_devices(200, [
        'ok' => true,
        'device_id' => $deviceId,
        'music_rights' => (bool)$state['devices'][$deviceId]['music_rights']
    ]);
}

if ($action === 'set_blocked') {
    $deviceId = clean_device_id((string)($body['device_id'] ?? ''));
    if (!isset($state['devices'][$deviceId]) || !is_array($state['devices'][$deviceId])) {
        respond_devices(404, ['ok' => false, 'error' => 'Device not found']);
    }

    $blocked = filter_var($body['blocked'] ?? false, FILTER_VALIDATE_BOOLEAN);
    $ownerId = devices_owner_id($ownerFile);
    if ($blocked && $ownerId !== '' && hash_equals($ownerId, $deviceId)) {
        respond_devices(409, ['ok' => false, 'error' => 'Owner device cannot be blocked']);
    }

    $state['devices'][$deviceId]['blocked'] = $blocked;
    $state['devices'][$deviceId]['blocked_updated'] = gmdate('c');
    devices_save($devicesFile, $state);
    respond_devices(200, [
        'ok' => true,
        'device_id' => $deviceId,
        'blocked' => (bool)$state['devices'][$deviceId]['blocked']
    ]);
}

respond_devices(404, ['ok' => false, 'error' => 'Unknown action']);

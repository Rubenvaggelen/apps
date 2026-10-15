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
    // Apparaatbeheer wordt uitsluitend toegestaan vanaf het geregistreerde
    // The One-eigenaarstoestel. Voor openen van Laptop > Apparaten beheren
    // is geen extra pincode meer nodig.
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
    if (!in_array($scope, ['media_player', 'mixes', 'shared', 'favorites', 'dj', 'run', 'downloads', 'download_files', 'cinema_android', 'series_android', 'tv_android'], true)) {
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
        'run' => (bool)($stored['run'] ?? false),
        // Nieuwe downloads blijft standaard privé. Legacy muziekrechten geven
        // dit beheerrecht bewust niet automatisch.
        'downloads' => (bool)($stored['downloads'] ?? false),
        // Los recht om bestanden echt naar het toestel te downloaden.
        // Standaard altijd uit, ook als iemand legacy muziekrechten heeft.
        'download_files' => (bool)($stored['download_files'] ?? false),
        'cinema_android' => (bool)($stored['cinema_android'] ?? false),
        'series_android' => (bool)($stored['series_android'] ?? false),
        'tv_android' => (bool)($stored['tv_android'] ?? false)
    ];
}

function device_access_requests(array $device): array {
    $stored = is_array($device['access_requests'] ?? null) ? $device['access_requests'] : [];
    $result = [];
    foreach (['media_player', 'mixes', 'shared', 'favorites', 'dj', 'run', 'downloads', 'download_files', 'cinema_android', 'series_android', 'tv_android'] as $scope) {
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
    if (isset($old['family_public_key'])) respond_devices(403, ['ok' => false, 'error' => 'Family identities require signatures']);
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
        ? ['media_player' => true, 'mixes' => true, 'shared' => true, 'favorites' => true, 'dj' => false, 'run' => false, 'downloads' => false, 'download_files' => false]
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
        $rights = ['media_player' => true, 'mixes' => true, 'shared' => true, 'favorites' => true, 'dj' => true, 'run' => true, 'downloads' => true, 'download_files' => true, 'cinema_android' => true, 'series_android' => true, 'tv_android' => true];
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
                'person_id' => (string)($d['person_id'] ?? ''),
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


/** User-facing people are names; app rights still belong to signed installations. */
function family_person_key(string $name): string {
    $normalized = preg_replace('/\\s+/u', ' ', trim($name));
    return mb_strtolower(is_string($normalized) ? $normalized : '', 'UTF-8');
}

/** Reuse or create a person's grouping ID without granting any app permissions. */
function family_link_person(array &$state, string $name, string $currentId): array {
    $key = family_person_key($name);
    $id = '';
    $exists = false;
    foreach ($state['devices'] as $deviceId => $record) {
        if (!is_array($record) || family_person_key((string)($record['person_name'] ?? '')) !== $key) continue;
        if ((string)$deviceId !== $currentId) $exists = true;
        if ($id === '' && trim((string)($record['person_id'] ?? '')) !== '') {
            $id = trim((string)$record['person_id']);
        }
    }
    if ($id === '') $id = 'person-' . bin2hex(random_bytes(12));
    // Backfill only a grouping label. Never copy any authorization bits.
    foreach ($state['devices'] as &$record) {
        if (!is_array($record)) continue;
        if (family_person_key((string)($record['person_name'] ?? '')) !== $key) continue;
        if (trim((string)($record['person_id'] ?? '')) === '') $record['person_id'] = $id;
    }
    unset($record);
    return [$id, $exists];
}

/** The One Family: app-install identities are SHA-256 fingerprints of their public keys. */
function family_scope_for_package(string $package): string {
    $packages = [
        'com.theone.cinema' => 'cinema_android',
        'com.theone.cinema.tv' => 'cinema_android',
        'com.theone.series' => 'series_android',
        'com.theone.series.tv' => 'series_android',
        'com.theone.tv.phone' => 'tv_android',
        'com.theone.tv' => 'tv_android'
    ];
    return $packages[$package] ?? '';
}

function family_public_key(string $encoded): array {
    $der = base64_decode($encoded, true);
    if ($der === false || strlen($der) < 70 || strlen($der) > 2048) {
        respond_devices(400, ['ok' => false, 'error' => 'Invalid public key']);
    }
    $pem = "-----BEGIN PUBLIC KEY-----\n" . chunk_split(base64_encode($der), 64, "\n") . "-----END PUBLIC KEY-----\n";
    $key = openssl_pkey_get_public($pem);
    $details = $key === false ? false : openssl_pkey_get_details($key);
    if ($details === false || ($details['type'] ?? -1) !== OPENSSL_KEYTYPE_EC
        || ($details['bits'] ?? 0) !== 256) {
        respond_devices(400, ['ok' => false, 'error' => 'P-256 public key required']);
    }
    return [$pem, 'fam-' . substr(hash('sha256', $der), 0, 48)];
}

function family_signed_request(array $data, string $pem, string $action, string $deviceId, string $scope): void {
    $nonce = (string)($data['nonce'] ?? '');
    $encodedSignature = (string)($data['signature'] ?? '');
    $binaryNonce = base64_decode($nonce, true);
    $signature = base64_decode($encodedSignature, true);
    if ($binaryNonce === false || strlen($binaryNonce) !== 32 ||
        $signature === false || strlen($signature) < 60 || strlen($signature) > 256) {
        respond_devices(400, ['ok' => false, 'error' => 'Invalid signed challenge']);
    }
    $message = $action . '|' . $deviceId . '|' . $scope . '|' . $nonce;
    if (openssl_verify($message, $signature, $pem, OPENSSL_ALGO_SHA256) !== 1) {
        respond_devices(403, ['ok' => false, 'error' => 'Device signature rejected']);
    }
}

if ($action === 'family_register') {
    $package = trim((string)($body['package'] ?? ''));
    $scope = family_scope_for_package($package);
    if ($scope === '') respond_devices(400, ['ok' => false, 'error' => 'Unknown app']);
    [$publicKey, $derivedId] = family_public_key((string)($body['public_key'] ?? ''));
    family_signed_request($body, $publicKey, 'register', $derivedId, $scope);
    $personName = preg_replace('/\\s+/u', ' ', trim((string)($body['person_name'] ?? '')));
    if (!is_string($personName) || mb_strlen($personName) < 2 ||
        mb_strlen($personName) > 80 || preg_match('/[\\x00-\\x1F\\x7F]/u', $personName)) {
        respond_devices(400, ['ok' => false, 'name_required' => true,
            'error' => 'Applicant name must contain 2 to 80 characters']);
    }
    $state = devices_load($devicesFile);
    $existing = $state['devices'][$derivedId] ?? null;
    if ($existing !== null && (!is_array($existing)
        || !hash_equals((string)($existing['family_public_key'] ?? ''), $publicKey)
        || (string)($existing['family_package'] ?? '') !== $package)) {
        respond_devices(409, ['ok' => false, 'error' => 'Installation conflict']);
    }
    $device = is_array($existing) ? $existing : [
        'device_id' => $derivedId,
        'name' => mb_substr($package . ' (' . trim((string)($body['device_name'] ?? ($body['platform'] ?? 'Android'))) . ')', 0, 100),
        'device_role' => 'family',
        'person_name' => mb_substr($personName, 0, 80),
        'platform' => mb_substr((string)($body['platform'] ?? 'Android'), 0, 40),
        'family_public_key' => $publicKey,
        'family_package' => $package,
        'music_rights' => false,
        'access_rights' => [],
        'access_requests' => [],
        'blocked' => false,
        'registered' => gmdate('c')
    ];
    if ((bool)($device['blocked'] ?? false)) respond_devices(403, ['ok' => false, 'error' => 'Device blocked']);
    $rights = device_access_rights($device);
    $requests = device_access_requests($device);
    // Refresh a still-pending request with the name actually typed by the
    // applicant. Never rewrite the name of an already approved installation.
    if (isset($requests[$scope]) && !(bool)($rights[$scope] ?? false)) {
        $device['person_name'] = $personName;
    }
    if (!(bool)($rights[$scope] ?? false) && !isset($requests[$scope])
        && !isset($device['family_denied_scopes'][$scope])) {
        $requests[$scope] = ['status' => 'pending', 'requested_at' => gmdate('c')];
    }
    $device['access_requests'] = $requests;
    // Approved/denied installations cannot silently change their owner name.
    // However, an old installation without a name can be named by its applicant.
    if (trim((string)($device['person_name'] ?? '')) === '') {
        $device['person_name'] = $personName;
    }
    [$personId, $existingPerson] = family_link_person(
        $state, (string)$device['person_name'], $derivedId
    );
    $device['person_id'] = $personId;
    $device['last_seen'] = time();
    $state['devices'][$derivedId] = $device;
    devices_save($devicesFile, $state);
    respond_devices(200, ['ok' => true, 'device_id' => $derivedId,
        'allowed' => (bool)($rights[$scope] ?? false), 'pending' => isset($requests[$scope]),
        'person_id' => $personId, 'person_exists' => $existingPerson]);
}

if ($action === 'family_check') {
    $deviceId = clean_device_id((string)($body['device_id'] ?? ''));
    $package = trim((string)($body['package'] ?? ''));
    $scope = family_scope_for_package($package);
    if ($scope === '') respond_devices(400, ['ok' => false, 'error' => 'Unknown app']);
    $state = devices_load($devicesFile);
    $device = $state['devices'][$deviceId] ?? null;
    if (!is_array($device) || !isset($device['family_public_key'])
        || (string)($device['family_package'] ?? '') !== $package) {
        respond_devices(404, ['ok' => false, 'error' => 'Installation not registered']);
    }
    family_signed_request($body, (string)$device['family_public_key'], 'check', $deviceId, $scope);
    $rights = device_access_rights($device);
    $requests = device_access_requests($device);
    $allowed = !(bool)($device['blocked'] ?? false) && (bool)($rights[$scope] ?? false);
    $device['last_seen'] = time();
    $state['devices'][$deviceId] = $device;
    devices_save($devicesFile, $state);
    respond_devices(200, ['ok' => true, 'allowed' => $allowed,
        'pending' => isset($requests[$scope]), 'server_time' => time(),
        'valid_seconds' => $allowed ? 86400 : 0]);
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
    $blocked = (bool)($device['blocked'] ?? false);
    respond_devices(200, [
        'ok' => true,
        'allowed' => !$blocked && ($owner || (bool)($rights[$scope] ?? false)),
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
    if ((bool)($state['devices'][$deviceId]['blocked'] ?? false)) {
        respond_devices(403, ['ok' => false, 'error' => 'Device blocked']);
    }

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

        // Unnamed legacy installs remain visible for owner-assisted naming.
        // An unnamed row never inherits an existing person's permissions.

        $d['online'] = $lastSeen > 0 && ($now - $lastSeen) <= 90;
        $d['owner'] = $owner;
        $d['music_rights'] = $owner || (bool)($d['music_rights'] ?? false);
        $d['access_rights'] = $owner
            ? ['media_player' => true, 'mixes' => true, 'shared' => true, 'favorites' => true, 'dj' => true, 'run' => true, 'downloads' => true, 'download_files' => true]
            : device_access_rights($d);
        $d['access_requests'] = device_access_requests($d);
        $d['person_name'] = $personName;
        $d['person_id'] = trim((string)($d['person_id'] ?? ''));
        $devices[] = $d;
    }
    usort($devices, fn($a, $b) => ((int)($b['last_seen'] ?? 0)) <=> ((int)($a['last_seen'] ?? 0)));
    respond_devices(200, ['ok' => true, 'devices' => $devices]);
}

if ($action === 'set_person_name') {
    // The existing owner PIN is required to attach an old installation to a person.
    if (!devices_admin($body)) {
        respond_devices(403, ['ok' => false, 'error' => 'Owner PIN required']);
    }
    $deviceId = clean_device_id((string)($body['device_id'] ?? ''));
    $personName = preg_replace('/\\s+/u', ' ', trim((string)($body['person_name'] ?? '')));
    if (!is_string($personName) || mb_strlen($personName) < 2 ||
        mb_strlen($personName) > 80 || preg_match('/[\\x00-\\x1F\\x7F]/u', $personName)) {
        respond_devices(400, ['ok' => false, 'name_required' => true]);
    }
    if (!isset($state['devices'][$deviceId]) || !is_array($state['devices'][$deviceId])) {
        respond_devices(404, ['ok' => false, 'error' => 'Device not found']);
    }
    $ownerTarget = devices_owner_id($ownerFile) === $deviceId;
    if ($ownerTarget && trim((string)($state['devices'][$deviceId]['person_name'] ?? '')) !== '') {
        respond_devices(409, ['ok' => false, 'error' => 'Owner name cannot be changed here']);
    }
    [$personId, $existingPerson] = family_link_person($state, $personName, $deviceId);
    $state['devices'][$deviceId]['person_name'] = $personName;
    $state['devices'][$deviceId]['person_id'] = $personId;
    $state['devices'][$deviceId]['person_name_updated'] = gmdate('c');
    devices_save($devicesFile, $state);
    respond_devices(200, ['ok' => true, 'device_id' => $deviceId,
        'person_name' => $personName, 'person_id' => $personId, 'person_exists' => $existingPerson]);
}

if ($action === 'set_access_right') {
    // New Family permissions need the authenticated owner *and* the existing admin credential.
    if (in_array((string)($body['scope'] ?? ''), ['cinema_android','series_android','tv_android'], true) && !devices_admin($body)) {
        respond_devices(403, ['ok' => false, 'error' => 'Owner PIN required for Family rights']);
    }
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
    // Een expliciete afwijzing mag niet automatisch telkens een nieuwe aanvraag worden.
    // De eigenaar kan het recht later alsnog handmatig inschakelen.
    if (isset($state['devices'][$deviceId]['family_public_key'])
        && in_array($scope, ['cinema_android', 'series_android', 'tv_android'], true)) {
        $denials = is_array($state['devices'][$deviceId]['family_denied_scopes'] ?? null)
            ? $state['devices'][$deviceId]['family_denied_scopes'] : [];
        if ($enabled) unset($denials[$scope]);
        else $denials[$scope] = gmdate('c');
        $state['devices'][$deviceId]['family_denied_scopes'] = $denials;
    }


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
    $existingScoped = device_access_rights($state['devices'][$deviceId]);
    $state['devices'][$deviceId]['access_rights'] = [
        'media_player' => $ownerTarget ? true : (bool)($existingScoped['media_player'] ?? false),
        'mixes' => $ownerTarget ? true : $enabled,
        'shared' => $ownerTarget ? true : $enabled,
        'favorites' => $ownerTarget ? true : $enabled,
        'dj' => false,
        'run' => $ownerTarget ? true : (bool)($existingScoped['run'] ?? false),
        'downloads' => $ownerTarget ? true : false,
        'download_files' => $ownerTarget ? true : false,
        'cinema_android' => $ownerTarget ? true : (bool)($existingScoped['cinema_android'] ?? false),
        'series_android' => $ownerTarget ? true : (bool)($existingScoped['series_android'] ?? false),
        'tv_android' => $ownerTarget ? true : (bool)($existingScoped['tv_android'] ?? false)
    ];
    // Een wijziging van de oudere muziekrechten mag onafhankelijke app-aanvragen
    // niet afhandelen of wissen zonder expliciete beslissing van de eigenaar.
    $pendingRequests = device_access_requests($state['devices'][$deviceId]);
    $state['devices'][$deviceId]['access_requests'] = array_intersect_key(
        $pendingRequests,
        array_flip(['cinema_android', 'series_android', 'tv_android'])
    );
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

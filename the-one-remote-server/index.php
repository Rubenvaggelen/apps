<?php
declare(strict_types=1);

header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: no-store');

const BOOTSTRAP_SHA256 = 'f1f8a2365aeff4d0a83177e9e6625c38c5ef0b1c45bab6d6fcec82e270044cda';

$home = dirname((string)($_SERVER['DOCUMENT_ROOT'] ?? __DIR__));
$dataDir = $home . '/the-one-remote-data';
$stateFile = $dataDir . '/device.json';

if (!is_dir($dataDir)) {
    @mkdir($dataDir, 0700, true);
}

function respond(int $status, array $body): never {
    http_response_code($status);
    echo json_encode($body, JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE);
    exit;
}

function body_json(): array {
    if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
        respond(405, ['ok' => false, 'error' => 'POST required']);
    }
    $raw = file_get_contents('php://input') ?: '';
    $data = json_decode($raw, true);
    if (!is_array($data)) respond(400, ['ok' => false, 'error' => 'Invalid JSON']);
    return $data;
}

function load_state(string $file): ?array {
    if (!is_file($file)) return null;
    $raw = @file_get_contents($file);
    if (!is_string($raw) || $raw === '') return null;
    $state = json_decode($raw, true);
    return is_array($state) ? $state : null;
}

function save_state(string $file, array $state): void {
    $tmp = $file . '.tmp.' . bin2hex(random_bytes(4));
    $json = json_encode($state, JSON_PRETTY_PRINT | JSON_UNESCAPED_SLASHES);
    if (@file_put_contents($tmp, $json, LOCK_EX) === false) {
        respond(500, ['ok' => false, 'error' => 'Storage unavailable']);
    }
    @chmod($tmp, 0600);
    if (!@rename($tmp, $file)) {
        @unlink($tmp);
        respond(500, ['ok' => false, 'error' => 'Storage unavailable']);
    }
}

function authorized(array $state, string $token): bool {
    if ($token === '' || strlen($token) < 32) return false;
    $hash = hash('sha256', $token);
    $expected = (string)($state['token_hash'] ?? '');
    return $expected !== '' && hash_equals($expected, $hash);
}

$action = (string)($_GET['action'] ?? 'health');

if ($action === 'health') {
    respond(200, ['ok' => true, 'service' => 'The One Remote Relay', 'version' => 1]);
}

if ($action === 'register') {
    $body = body_json();
    $bootstrap = trim((string)($body['bootstrap'] ?? ''));
    $token = trim((string)($body['token'] ?? ''));
    $deviceId = trim((string)($body['device_id'] ?? ''));

    if ($bootstrap === '' || !hash_equals(BOOTSTRAP_SHA256, hash('sha256', $bootstrap))) {
        respond(403, ['ok' => false, 'error' => 'Registration denied']);
    }
    if (strlen($token) < 32 || !preg_match('/^[A-Za-z0-9+\/_-]+$/', $token)) {
        respond(400, ['ok' => false, 'error' => 'Invalid token']);
    }
    if ($deviceId === '' || strlen($deviceId) > 80) {
        respond(400, ['ok' => false, 'error' => 'Invalid device']);
    }

    $state = load_state($stateFile);
    if (is_array($state) && !authorized($state, $token)) {
        respond(409, ['ok' => false, 'error' => 'Device already registered']);
    }

    $state = [
        'device_id' => $deviceId,
        'token_hash' => hash('sha256', $token),
        'last_seen' => time(),
        'command' => $state['command'] ?? null,
        'registered' => $state['registered'] ?? gmdate('c')
    ];
    save_state($stateFile, $state);
    respond(200, ['ok' => true, 'registered' => true]);
}

$body = body_json();
$state = load_state($stateFile);
if (!is_array($state)) respond(503, ['ok' => false, 'error' => 'Device not registered']);

$token = trim((string)($body['token'] ?? ''));
if (!authorized($state, $token)) {
    usleep(500000);
    respond(403, ['ok' => false, 'error' => 'Unauthorized']);
}

if ($action === 'poll') {
    $deviceId = trim((string)($body['device_id'] ?? ''));
    if ($deviceId !== (string)($state['device_id'] ?? '')) {
        respond(403, ['ok' => false, 'error' => 'Wrong device']);
    }
    $state['last_seen'] = time();
    save_state($stateFile, $state);
    respond(200, ['ok' => true, 'command' => $state['command'] ?? null]);
}

if ($action === 'ack') {
    $id = trim((string)($body['command_id'] ?? ''));
    $pending = is_array($state['command'] ?? null) ? $state['command'] : null;
    if ($pending !== null && hash_equals((string)($pending['id'] ?? ''), $id)) {
        $state['command'] = null;
        $state['last_seen'] = time();
        save_state($stateFile, $state);
    }
    respond(200, ['ok' => true]);
}

if ($action === 'command') {
    $command = strtolower(trim((string)($body['command'] ?? '')));
    if ($command !== 'sleep') {
        respond(400, ['ok' => false, 'error' => 'Unsupported command']);
    }

    $state['command'] = [
        'id' => bin2hex(random_bytes(16)),
        'name' => $command,
        'created' => time()
    ];
    save_state($stateFile, $state);
    respond(200, ['ok' => true, 'queued' => true]);
}

if ($action === 'status') {
    $lastSeen = (int)($state['last_seen'] ?? 0);
    respond(200, [
        'ok' => true,
        'online' => $lastSeen > 0 && (time() - $lastSeen) <= 35,
        'last_seen' => $lastSeen
    ]);
}

respond(404, ['ok' => false, 'error' => 'Unknown action']);

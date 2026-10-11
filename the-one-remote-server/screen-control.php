<?php
declare(strict_types=1);
/**
 * The One Family screen relay: one-time 20-second tickets.
 * Owner approval is checked with the existing paired Main owner identity.
 * Windows devices redeem tickets via HTTPS; no long-lived owner token enters LAN traffic.
 * All mutable state is stored outside public_html.
 */
header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: no-store');
header('X-Content-Type-Options: nosniff');
require_once __DIR__ . '/license-core.php';

function screen_answer(int $status, array $value): never {
    http_response_code($status);
    echo json_encode(['ok' => $status >= 200 && $status < 300] + $value, JSON_THROW_ON_ERROR);
    exit;
}
if (($_SERVER['REQUEST_METHOD'] ?? '') !== 'POST') screen_answer(405, ['error'=>'POST required']);
if ((int)($_SERVER['CONTENT_LENGTH'] ?? 0) > 4096) screen_answer(413, ['error'=>'Request too large']);
$payload = json_decode((string)file_get_contents('php://input'), true);
if (!is_array($payload)) screen_answer(400, ['error'=>'Invalid JSON']);

$action = strtolower((string)($_GET['action'] ?? ''));
$target = strtolower(trim((string)($payload['target'] ?? '')));
$command = strtoupper(trim((string)($payload['command'] ?? '')));
if (!in_array($target, ['ruben','surface','hub'], true) ||
    !in_array($command, ['ON','OFF'], true)) {
    screen_answer(400, ['error'=>'Unsupported target or action']);
}

try {
    if ($action === 'issue') {
        $device = one_license_device((string)($payload['device_id'] ?? ''));
        $install = one_license_installation_id((string)($payload['installation_id'] ?? ''));
        $ownerToken = (string)($payload['owner_token'] ?? '');
        $allowed = one_license_locked(static fn(array &$state, callable $dirty): bool =>
            !isset($state['retired_devices'][$device]) &&
            one_license_owner_authenticated($state, $device, $install, $ownerToken));
        if (!$allowed) screen_answer(403, ['error'=>'Paired owner required']);
    } elseif ($action !== 'redeem') {
        screen_answer(404, ['error'=>'Unknown action']);
    }

    $store = dirname(dirname(__DIR__)) . '/the-one-private-screen-control';
    if (!is_dir($store) && !mkdir($store, 0700, true) && !is_dir($store)) {
        throw new RuntimeException('Private screen storage inaccessible');
    }
    @chmod($store, 0700);
    $lock = fopen($store . '/tickets.lock', 'c');
    if ($lock === false || !flock($lock, LOCK_EX)) throw new RuntimeException('Ticket lock inaccessible');
    try {
        $file = $store . '/tickets.json';
        $tickets = is_file($file) ? json_decode((string)file_get_contents($file), true, 32, JSON_THROW_ON_ERROR) : [];
        if (!is_array($tickets)) throw new RuntimeException('Ticket storage damaged');
        $now = time();
        foreach ($tickets as $hash => $record) {
            if (!is_array($record) || (int)($record['expires'] ?? 0) < $now) unset($tickets[$hash]);
        }
        $result = [];
        if ($action === 'issue') {
            $ticket = bin2hex(random_bytes(32));
            $tickets[hash('sha256', $ticket)] = ['target'=>$target,'command'=>$command,'expires'=>$now+20];
            $result = ['ticket'=>$ticket,'expires_in'=>20];
        } else {
            $ticket = strtolower(trim((string)($payload['ticket'] ?? '')));
            if (!preg_match('/^[0-9a-f]{64}$/D', $ticket)) screen_answer(403, ['error'=>'Invalid ticket']);
            $key = hash('sha256', $ticket);
            $match = $tickets[$key] ?? null;
            if (!is_array($match) || !hash_equals((string)$match['target'], $target) ||
                !hash_equals((string)$match['command'], $command)) {
                screen_answer(403, ['error'=>'Ticket invalid or expired']);
            }
            unset($tickets[$key]);
            $result = ['accepted'=>true];
        }
        $tmp = $file . '.' . bin2hex(random_bytes(6)) . '.new';
        if (file_put_contents($tmp, json_encode($tickets, JSON_THROW_ON_ERROR), LOCK_EX) === false) {
            throw new RuntimeException('Ticket storage write failed');
        }
        @chmod($tmp, 0600);
        if (!rename($tmp, $file)) { @unlink($tmp); throw new RuntimeException('Ticket storage commit failed'); }
    } finally {
        flock($lock, LOCK_UN);
        fclose($lock);
    }
    screen_answer(200, $result);
} catch (InvalidArgumentException $e) {
    screen_answer(400, ['error'=>'Invalid request']);
} catch (Throwable $e) {
    error_log('The One screen control: ' . $e->getMessage());
    screen_answer(503, ['error'=>'Screen service temporarily unavailable']);
}

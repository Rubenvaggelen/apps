<?php
declare(strict_types=1);
/**
 * The One Family screen relay.
 * Owner can issue display power commands using Main's existing paired identity.
 * Each Windows client authenticates outbound polls with its private per-device key.
 * Only hashes of Windows keys are stored on the server. All tickets remain private.
 */
header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: no-store');
header('X-Content-Type-Options: nosniff');
require_once __DIR__ . '/license-core.php';
require_once __DIR__ . '/screen-agent-hashes.php';

function screen_answer(int $status, array $data): never {
    http_response_code($status);
    echo json_encode(['ok' => $status >= 200 && $status < 300] + $data,
        JSON_THROW_ON_ERROR | JSON_UNESCAPED_SLASHES);
    exit;
}
if (($_SERVER['REQUEST_METHOD'] ?? '') !== 'POST') screen_answer(405, ['error' => 'POST required']);
if ((int)($_SERVER['CONTENT_LENGTH'] ?? 0) > 4096) screen_answer(413, ['error' => 'Request too large']);
$payload = json_decode((string)file_get_contents('php://input'), true);
if (!is_array($payload)) screen_answer(400, ['error' => 'Invalid JSON']);
$action = strtolower((string)($_GET['action'] ?? ''));
$target = strtolower(trim((string)($payload['target'] ?? '')));
if (!in_array($target, ['ruben', 'surface', 'hub'], true)) {
    screen_answer(400, ['error' => 'Unknown target']);
}
$command = strtoupper(trim((string)($payload['command'] ?? '')));
$ownerActions = ['issue', 'status'];
$agentActions = ['poll', 'redeem', 'complete'];
if (!in_array($action, array_merge($ownerActions, $agentActions), true)) {
    screen_answer(404, ['error' => 'Unknown action']);
}
if ($action === 'issue' && !in_array($command, ['ON', 'OFF'], true)) {
    screen_answer(400, ['error' => 'Unsupported command']);
}
try {
    if (in_array($action, $ownerActions, true)) {
        $device = one_license_device((string)($payload['device_id'] ?? ''));
        $install = one_license_installation_id((string)($payload['installation_id'] ?? ''));
        $ownerToken = (string)($payload['owner_token'] ?? '');
        $authorized = one_license_locked(static fn(array &$state, callable $dirty): bool =>
            !isset($state['retired_devices'][$device]) &&
            one_license_owner_authenticated($state, $device, $install, $ownerToken));
        if (!$authorized) screen_answer(403, ['error' => 'Paired owner required']);
    }
    if (in_array($action, $agentActions, true)) {
        $key = strtolower(trim((string)($payload['agent_key'] ?? '')));
        $hashes = one_screen_agent_hashes();
        if (strlen($key) !== 64 || !ctype_xdigit($key) || !isset($hashes[$target]) ||
            !hash_equals($hashes[$target], hash('sha256', $key))) {
            screen_answer(403, ['error' => 'Agent authentication required']);
        }
    }
    $directory = dirname(dirname(__DIR__)) . '/the-one-private-screen-control';
    if (!is_dir($directory) && !mkdir($directory, 0700, true) && !is_dir($directory)) {
        throw new RuntimeException('Private directory unavailable');
    }
    @chmod($directory, 0700);
    $lock = fopen($directory . '/tickets.lock', 'c');
    if (!$lock || !flock($lock, LOCK_EX)) throw new RuntimeException('Screen lock unavailable');
    try {
        $file = $directory . '/tickets.json';
        $tickets = is_file($file) ? json_decode((string)file_get_contents($file), true, 32, JSON_THROW_ON_ERROR) : [];
        if (!is_array($tickets)) throw new RuntimeException('Ticket store invalid');
        $now = time();
        $dirty = false;
        foreach ($tickets as $hash => $record) {
            if (!is_array($record) || ($record['expires'] ?? 0) < $now) {
                unset($tickets[$hash]);
                $dirty = true;
            }
        }
        $response = [];
        if ($action === 'issue') {
            $ticket = bin2hex(random_bytes(32));
            $tickets[hash('sha256', $ticket)] = [
                'target' => $target,
                'command' => $command,
                'ticket' => $ticket,
                'expires' => $now + 50,
                'status' => 'pending'
            ];
            $dirty = true;
            $response = ['ticket' => $ticket, 'expires_in' => 50];
        } elseif ($action === 'poll') {
            foreach (array_reverse($tickets, true) as $record) {
                if (($record['target'] ?? '') === $target && ($record['status'] ?? '') === 'pending') {
                    $response = ['pending' => true, 'command' => $record['command'], 'ticket' => $record['ticket']];
                    break;
                }
            }
            if (!$response) $response = ['pending' => false];
        } else {
            $ticket = strtolower(trim((string)($payload['ticket'] ?? '')));
            if (strlen($ticket) !== 64 || !ctype_xdigit($ticket)) screen_answer(403, ['error' => 'Invalid ticket']);
            $key = hash('sha256', $ticket);
            $record = $tickets[$key] ?? null;
            if (!is_array($record) || !hash_equals((string)$record['target'], $target) ||
                !hash_equals((string)$record['ticket'], $ticket)) {
                screen_answer(403, ['error' => 'Ticket unavailable']);
            }
            if ($action === 'status') {
                $response = ['status' => $record['status'], 'command' => $record['command']];
            } elseif ($action === 'redeem') {
                if ($record['status'] !== 'pending' ||
                    !hash_equals((string)$record['command'], $command)) {
                    screen_answer(403, ['error' => 'Ticket already claimed']);
                }
                $tickets[$key]['status'] = 'executing';
                $dirty = true;
                $response = ['accepted' => true];
            } elseif ($action === 'complete') {
                if ($record['status'] !== 'executing' ||
                    !hash_equals((string)$record['command'], $command)) {
                    screen_answer(403, ['error' => 'Ticket not executing']);
                }
                $result = (string)($payload['result'] ?? '');
                if (!in_array($result, ['done', 'failed'], true)) {
                    screen_answer(400, ['error' => 'Invalid result']);
                }
                $tickets[$key]['status'] = $result;
                $dirty = true;
                $response = ['recorded' => true];
            }
        }
        if ($dirty) {
            $temp = $file . '.' . bin2hex(random_bytes(6)) . '.new';
            if (file_put_contents($temp, json_encode($tickets, JSON_THROW_ON_ERROR), LOCK_EX) === false) {
                throw new RuntimeException('Ticket write failed');
            }
            @chmod($temp, 0600);
            if (!rename($temp, $file)) { @unlink($temp); throw new RuntimeException('Ticket commit failed'); }
        }
    } finally {
        flock($lock, LOCK_UN);
        fclose($lock);
    }
    screen_answer(200, $response);
} catch (InvalidArgumentException $e) {
    screen_answer(400, ['error' => 'Invalid request']);
} catch (Throwable $e) {
    error_log('The One screen control: ' . $e->getMessage());
    screen_answer(503, ['error' => 'Screen service unavailable']);
}

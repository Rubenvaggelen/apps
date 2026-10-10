<?php
declare(strict_types=1);
header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: no-store');
header('X-Content-Type-Options: nosniff');
require_once __DIR__ . '/license-core.php';

function removal_answer(int $code, string $msg, array $more = []): never {
    http_response_code($code);
    echo json_encode(['ok' => $code >= 200 && $code < 300, 'message' => $msg] + $more, JSON_THROW_ON_ERROR);
    exit;
}
if (($_SERVER['REQUEST_METHOD'] ?? '') !== 'POST') removal_answer(405, 'POST vereist');
if ((int)($_SERVER['CONTENT_LENGTH'] ?? 0) > 8192) removal_answer(413, 'Aanvraag te groot');
$body = json_decode((string)file_get_contents('php://input'), true);
if (!is_array($body)) removal_answer(400, 'Ongeldige aanvraag');

try {
    $ownerDevice = one_license_device((string)($body['device_id'] ?? ''));
    $ownerInstall = one_license_installation_id((string)($body['installation_id'] ?? ''));
    $ownerToken = (string)($body['owner_token'] ?? '');
    $targetId = one_license_device((string)($body['target_device_id'] ?? ''));
    $expectedRegistered = trim((string)($body['expected_registered'] ?? ''));
    if (strlen($expectedRegistered) < 12 || strlen($expectedRegistered) > 64) {
        throw new InvalidArgumentException('Apparaatregistratie ontbreekt');
    }
    if (hash_equals($ownerDevice, $targetId)) removal_answer(409, 'Eigenaar mag niet worden verwijderd');
    $home = dirname((string)($_SERVER['DOCUMENT_ROOT'] ?? __DIR__));
    $dataDir = $home . '/the-one-remote-data';
    $devicesPath = $dataDir . '/main-devices.json';
    $ownerPath = $dataDir . '/main-device-owner.json';
    if (!is_file($devicesPath) || !is_file($ownerPath)) throw new RuntimeException('Device registry unavailable');
    $ownerRecord = json_decode((string)file_get_contents($ownerPath), true);
    $rootOwnerId = trim((string)($ownerRecord['device_id'] ?? ''));
    if ($rootOwnerId === '' || !hash_equals($rootOwnerId, $ownerDevice)) removal_answer(403, 'Eigenaar vereist');
    if (hash_equals($rootOwnerId, $targetId)) removal_answer(409, 'Eigenaar kan niet worden verwijderd');

    // Serialize all owner-requested removals, retaining a private rollback image.
    $lockPath = $dataDir . '/main-device-removal.lock';
    $lock = fopen($lockPath, 'c');
    if (!$lock || !flock($lock, LOCK_EX)) throw new RuntimeException('Cannot lock removals');
    try {
        $registryJson = (string)file_get_contents($devicesPath);
        $registry = json_decode($registryJson, true, 512, JSON_THROW_ON_ERROR);
        $devices = $registry['devices'] ?? null;
        if (!is_array($devices) || !isset($devices[$targetId]) || !is_array($devices[$targetId])) {
            removal_answer(404, 'Apparaat niet gevonden; vernieuw de lijst');
        }
        $target = $devices[$targetId];
        if ((string)($target['registered'] ?? '') !== $expectedRegistered) {
            removal_answer(409, 'Registratie is veranderd; vernieuw eerst de lijst');
        }
        // Other app families are not yet installation-bound in this licensing pilot.
        if (($target['device_role'] ?? 'main') !== 'main') {
            removal_answer(409, 'Verwijderen is voorlopig alleen voor Main-apparaten beschikbaar');
        }

        // Verify robust installation-bound owner token before touching either store.
        $authenticated = one_license_locked(static function(array &$state, callable $dirty) use ($ownerDevice, $ownerInstall, $ownerToken): bool {
            return one_license_owner_authenticated($state, $ownerDevice, $ownerInstall, $ownerToken);
        });
        if (!$authenticated) removal_answer(403, 'Beveiligde eigenaarkoppeling ontbreekt');

        $backupDir = $home . '/.the-one-remote-backups';
        if (!is_dir($backupDir) && !mkdir($backupDir, 0700, true) && !is_dir($backupDir)) {
            throw new RuntimeException('Cannot create backup directory');
        }
        $registryBackup = $backupDir . '/device-remove-' . gmdate('YmdTHis') . '-' . bin2hex(random_bytes(5)) . '.json';
        if (!copy($devicesPath, $registryBackup) ||
            !hash_equals(hash('sha256', $registryJson), (string)hash_file('sha256', $registryBackup))) {
            throw new RuntimeException('Registry backup verification failed');
        }
        chmod($registryBackup, 0600);
        // First revoke grants, queue entries, and grandfathering. Only then delete
        // the registry entry, to fail closed if the second write fails.
        $revokedCount = one_license_locked(static function(array &$state, callable $dirty) use ($ownerDevice, $ownerInstall, $ownerToken, $targetId): int {
            if (!one_license_owner_authenticated($state, $ownerDevice, $ownerInstall, $ownerToken)) {
                throw new RuntimeException('Owner token expired');
            }
            $revoked = 0;
            foreach (($state['grants'] ?? []) as &$grant) {
                if (($grant['device'] ?? '') !== $targetId) continue;
                if (($grant['status'] ?? '') !== 'revoked') {
                    $grant['status'] = 'revoked';
                    $grant['revoked_at'] = gmdate('c');
                    $revoked++;
                }
            }
            unset($grant);
            foreach (($state['requests'] ?? []) as &$req) {
                if (($req['device'] ?? '') !== $targetId) continue;
                if (in_array(($req['status'] ?? ''), ['pending', 'approved'], true)) {
                    $req['status'] = 'revoked';
                    $req['revoked_at'] = gmdate('c');
                }
            }
            unset($req);
            unset($state['legacy_main'][$targetId]);
            if (!is_array($state['retired_devices'] ?? null)) $state['retired_devices'] = [];
            $state['retired_devices'][$targetId] = ['removed_at' => gmdate('c'), 'reason' => 'owner_removed'];
            $dirty();
            return $revoked;
        });

        // Re-read to protect unrelated heartbeat writes from accidental overwrite.
        $currentRaw = (string)file_get_contents($devicesPath);
        $current = json_decode($currentRaw, true, 512, JSON_THROW_ON_ERROR);
        if (($current['devices'][$targetId]['registered'] ?? '') !== $expectedRegistered) {
            throw new RuntimeException('Registry changed before deletion. License already revoked; retry to remove row.');
        }
        if (!hash_equals($rootOwnerId, (string)(json_decode((string)file_get_contents($ownerPath), true)['device_id'] ?? ''))) {
            throw new RuntimeException('Owner changed during deletion');
        }
        unset($current['devices'][$targetId]);
        $newJson = json_encode($current, JSON_PRETTY_PRINT | JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE | JSON_THROW_ON_ERROR);
        $temp = $devicesPath . '.remove-' . bin2hex(random_bytes(4));
        if (file_put_contents($temp, $newJson, LOCK_EX) === false) throw new RuntimeException('Cannot stage registry deletion');
        chmod($temp, 0600);
        if (!hash_equals(hash('sha256', $currentRaw), (string)hash_file('sha256', $devicesPath))) {
            unlink($temp);
            throw new RuntimeException('Registry concurrent write; rights already revoked, retry deletion');
        }
        if (!rename($temp, $devicesPath)) throw new RuntimeException('Cannot publish registry deletion');
        $verified = json_decode((string)file_get_contents($devicesPath), true, 512, JSON_THROW_ON_ERROR);
        if (isset($verified['devices'][$targetId]) || count($verified['devices']) !== count($current['devices'])) {
            throw new RuntimeException('Registry verification failed');
        }
        removal_answer(200, 'Apparaat, toegangsrechten en licentie ingetrokken', [
            'removed_device_id' => $targetId, 'revoked_grants' => $revokedCount
        ]);
    } finally {
        flock($lock, LOCK_UN);
        fclose($lock);
    }
} catch (InvalidArgumentException $error) {
    removal_answer(400, $error->getMessage());
} catch (Throwable $error) {
    error_log('The One owner device removal failure: ' . $error->getMessage());
    removal_answer(503, 'Verwijderen niet volledig bevestigd. Controleer de apparatenlijst en probeer opnieuw.');
}

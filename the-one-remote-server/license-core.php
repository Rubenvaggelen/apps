<?php
declare(strict_types=1);

/**
 * The One Family: private licensing store shared by Main, Music Studio and Dev Hub.
 * No activation codes, credentials or license tokens are committed to Git.
 * The store must reside OUTSIDE both public document roots.
 */

function one_license_data_dir(): string {
    $custom = getenv('THE_ONE_LICENSE_DATA_DIR');
    if (is_string($custom) && $custom !== '') return $custom;
    // The licensing storage must be identical whether called by the Main API
    // or by the Dev Hub subdomain (which may have a different DOCUMENT_ROOT).
    // Code lives under $HOME/public_html/the-one-remote-api/license-core.php.
    $hostingHome = dirname(dirname(__DIR__));
    if (!is_dir($hostingHome)) throw new RuntimeException('Hosting root not available');
    return $hostingHome . '/the-one-private-licenses';
}

function one_license_path(): string {
    return one_license_data_dir() . '/licenses.json';
}

function one_license_default(): array {
    return [
        'schema' => 1, 'main_enforced' => false, 'studio_enforced' => false,
        'migration_at' => null, 'legacy_main' => [],
        'grants' => [], 'codes' => [], 'requests' => []
    ];
}

function one_license_storage(): void {
    $dir = one_license_data_dir();
    if (!is_dir($dir) && !mkdir($dir, 0700, true) && !is_dir($dir)) {
        throw new RuntimeException('License storage not writable');
    }
    @chmod($dir, 0700);
}

function one_license_read_raw(): array {
    $file = one_license_path();
    if (!is_file($file)) return one_license_default();
    $json = file_get_contents($file);
    $state = json_decode((string)$json, true);
    if (!is_array($state) || !isset($state['schema']) || $state['schema'] !== 1) {
        throw new RuntimeException('License store invalid: refusing to overwrite');
    }
    return $state;
}

function one_license_locked(callable $callback): mixed {
    one_license_storage();
    $lock = fopen(one_license_data_dir() . '/license.lock', 'c');
    if ($lock === false || !flock($lock, LOCK_EX)) throw new RuntimeException('Cannot lock license store');
    try {
        $state = one_license_read_raw();
        $changed = false;
        $setChanged = static function() use (&$changed): void { $changed = true; };
        $result = $callback($state, $setChanged);
        if ($changed) {
            $path = one_license_path();
            $tmp = $path . '.new.' . bin2hex(random_bytes(6));
            $json = json_encode($state, JSON_PRETTY_PRINT | JSON_UNESCAPED_SLASHES | JSON_THROW_ON_ERROR);
            if (file_put_contents($tmp, $json, LOCK_EX) === false) throw new RuntimeException('License store write failure');
            @chmod($tmp, 0600);
            if (!rename($tmp, $path)) {
                @unlink($tmp);
                throw new RuntimeException('License store commit failure');
            }
        }
        return $result;
    } finally {
        flock($lock, LOCK_UN);
        fclose($lock);
    }
}

function one_license_device(string $device): string {
    $device = trim($device);
    if (!preg_match('/^[A-Za-z0-9_.:-]{8,120}$/D', $device)) throw new InvalidArgumentException('Invalid device identifier');
    return $device;
}

function one_license_person(string $person): string {
    $person = trim($person);
    if ($person === '' || mb_strlen($person) > 90) throw new InvalidArgumentException('Enter a name');
    return $person;
}

function one_license_token(): string {
    return bin2hex(random_bytes(32));
}

function one_license_hash(string $token): string {
    return hash('sha256', $token);
}

function one_license_code(): string {
    // 160-bit, impossible to guess by practical online brute force.
    return 'ONE-' . implode('-', str_split(strtoupper(bin2hex(random_bytes(20))), 8));
}

function one_license_valid_code(string $code): bool {
    return (bool)preg_match('/^ONE-(?:[0-9A-F]{8}-){4}[0-9A-F]{8}$/D', strtoupper(trim($code)));
}

function one_license_find_grant(array $state, string $app, string $device, string $token, string $installationId): ?array {
    foreach ($state['grants'] as $grant) {
        if (($grant['app'] ?? '') !== $app || ($grant['device'] ?? '') !== $device) continue;
        if (($grant['status'] ?? '') !== 'active') continue;
        if (!hash_equals((string)($grant['installation_id'] ?? ''), $installationId)) continue;
        if (hash_equals((string)$grant['token_hash'], one_license_hash($token))) return $grant;
    }
    return null;
}

function one_license_issue_grant(array &$state, string $app, string $device, string $name, string $kind, string $licenseId, string $installationId): array {
    $token = one_license_token();
    $id = bin2hex(random_bytes(12));
    $state['grants'][$id] = [
        'id' => $id, 'app' => $app, 'device' => $device,
        'person' => $name, 'type' => $kind, 'license_id' => $licenseId,
        'installation_id' => $installationId,
        'status' => 'active', 'token_hash' => one_license_hash($token),
        'issued_at' => gmdate('c'), 'last_seen' => gmdate('c')
    ];
    return ['grant_id' => $id, 'token' => $token, 'allowed' => true];
}

function one_license_initialize_main(array &$state, string $registryFile): array {
    if ($state['main_enforced'] || $state['migration_at'] !== null) {
        throw new RuntimeException('Main migration already sealed');
    }
    if (!is_file($registryFile)) throw new RuntimeException('Existing Main device registry missing; do not enable licensing');
    $registry = json_decode((string)file_get_contents($registryFile), true);
    if (!is_array($registry) || !is_array($registry['devices'] ?? null)) {
        throw new RuntimeException('Cannot read existing Main devices safely');
    }
    foreach ($registry['devices'] as $id => $record) {
        if (!is_array($record) || ($record['device_role'] ?? 'main') !== 'main') continue;
        if (!preg_match('/^[A-Za-z0-9_.:-]{8,120}$/D', (string)$id)) continue;
        $state['legacy_main'][$id] = [
            'name' => mb_substr((string)($record['person_name'] ?? $record['name'] ?? 'Bestaand apparaat'), 0, 90),
            'migrated_at' => gmdate('c')
        ];
    }
    // An empty registry may mean a failed lookup; never lock out all users.
    if (count($state['legacy_main']) === 0) throw new RuntimeException('No existing Main installations found: migration aborted');
    $state['migration_at'] = gmdate('c');
    $state['main_enforced'] = true;
    return ['grandfathered' => count($state['legacy_main']), 'at' => $state['migration_at']];
}

function one_license_main_status(array &$state, string $device, string $token, string $installationId, int $firstInstalledMs): array {
    // Owner-retired identifiers can never regain access via pilot bypass,
    // old grandfathered device IDs, or an already-issued token.
    if (isset($state['retired_devices'][$device])) {
        return ['enabled' => true, 'allowed' => false, 'mode' => 'device_removed'];
    }
    if (!$state['main_enforced']) return ['enabled' => false, 'allowed' => true, 'mode' => 'not_enabled'];
    if ($token !== '') {
        $grant = one_license_find_grant($state, 'main', $device, $token, $installationId);
        if ($grant !== null) return ['enabled' => true, 'allowed' => true, 'mode' => $grant['type']];
    }
    if (isset($state['legacy_main'][$device])) {
        // Existing installations may bind ONCE; after binding a token is mandatory.
        $bound = false;
        foreach ($state['grants'] as $grant) {
            if (($grant['app'] ?? '') === 'main' && ($grant['device'] ?? '') === $device) {
                $bound = true;
                break;
            }
        }
        // Installed BEFORE the cutover? Upgrading retains Android's firstInstallTime.
        // Uninstall/reinstall resets firstInstallTime, even if an OS backup restores
        // SharedPreferences/device IDs. Never grandfather a fresh installation.
        $cutoff = $state['migration_at'] !== null ? strtotime($state['migration_at']) * 1000 : 0;
        if (!$bound && $cutoff > 0 && $firstInstalledMs > 0 && $firstInstalledMs < $cutoff) {
            $new = one_license_issue_grant($state, 'main', $device, (string)$state['legacy_main'][$device]['name'], 'grandfathered', 'legacy', $installationId);
            return ['enabled' => true, 'allowed' => true, 'mode' => 'grandfathered', 'credential' => $new];
        }
    }
    return ['enabled' => true, 'allowed' => false, 'mode' => 'activation_required'];
}

function one_license_redeem(array &$state, string $app, string $device, string $person, string $code, string $installationId): array {
    if (!in_array($app, ['main', 'studio'], true)) throw new InvalidArgumentException('Unknown application');
    if (!one_license_valid_code($code)) return ['allowed' => false, 'error' => 'invalid_code'];
    $digest = one_license_hash(strtoupper(trim($code)));
    foreach ($state['codes'] as $id => &$entry) {
        if ($entry['app'] !== $app || $entry['status'] !== 'active') continue;
        if (!hash_equals($entry['hash'], $digest)) continue;
        if ($entry['redeemed'] >= $entry['limit']) return ['allowed' => false, 'error' => 'code_already_used'];
        $entry['redeemed']++;
        if ($entry['redeemed'] >= $entry['limit']) $entry['status'] = 'used';
        $result = one_license_issue_grant($state, $app, $device, $person, 'code', $id, $installationId);
        return ['allowed' => true, 'credential' => $result];
    }
    unset($entry);
    return ['allowed' => false, 'error' => 'invalid_or_revoked_code'];
}

function one_license_create_code(array &$state, string $app, string $label, int $limit = 1): array {
    if (!in_array($app, ['main', 'studio'], true)) throw new InvalidArgumentException('Unknown application');
    if ($limit < 1 || $limit > 10) throw new InvalidArgumentException('Invalid device limit');
    $code = one_license_code();
    $id = bin2hex(random_bytes(12));
    $state['codes'][$id] = [
        'id' => $id, 'app' => $app, 'label' => mb_substr(trim($label), 0, 90),
        'hash' => one_license_hash($code), 'limit' => $limit, 'redeemed' => 0,
        'status' => 'active', 'created_at' => gmdate('c')
    ];
    // Raw activation code is returned once, never persisted.
    return ['code' => $code, 'license_id' => $id, 'app' => $app, 'limit' => $limit];
}

function one_license_request(array &$state, string $app, string $device, string $person, string $installationId): array {
    if (!in_array($app, ['main', 'studio', 'car', 'run', 'dj'], true)) throw new InvalidArgumentException('Unknown application');
    if (isset($state['retired_devices'][$device])) {
        return ['allowed' => false, 'status' => 'device_removed'];
    }
    if (!in_array($app, ['main', 'studio'], true)) throw new InvalidArgumentException('Unknown application');
    foreach ($state['requests'] as $id => $req) {
        if (($req['app'] ?? '') === $app && ($req['device'] ?? '') === $device &&
            ($req['installation_id'] ?? '') === $installationId &&
            in_array(($req['status'] ?? ''), ['pending', 'approved'], true)) {
            return ['request_id' => $id, 'status' => $req['status']];
        }
    }
    // Keep a bounded queue; prevent resource exhaustion via public submissions.
    if (count($state['requests']) >= 1000) throw new RuntimeException('Approval queue capacity reached');
    $id = bin2hex(random_bytes(12));
    $secret = one_license_token();
    $state['requests'][$id] = [
        'id' => $id, 'app' => $app, 'device' => $device, 'installation_id' => $installationId,
        'person' => $person, 'status' => 'pending', 'created_at' => gmdate('c'),
        'request_secret_hash' => one_license_hash($secret)
    ];
    // Applicant must keep this private proof. It is never available in owner lists.
    return ['request_id' => $id, 'request_secret' => $secret, 'status' => 'pending'];
}

function one_license_request_claim(array &$state, string $requestId, string $device, string $installationId, string $secret): array {
    $req = $state['requests'][$requestId] ?? null;
    if (!is_array($req) ||
        !hash_equals((string)($req['device'] ?? ''), $device) ||
        !hash_equals((string)($req['installation_id'] ?? ''), $installationId) ||
        strlen($secret) !== 64 ||
        !hash_equals((string)($req['request_secret_hash'] ?? ''), one_license_hash($secret))) {
        return ['allowed' => false, 'status' => 'unknown'];
    }
    if (($req['status'] ?? '') !== 'approved') {
        return ['allowed' => false, 'status' => (string)($req['status'] ?? 'unknown')];
    }
    $grant = one_license_issue_grant(
        $state, (string)$req['app'], $device, (string)$req['person'],
        'owner_approved', $requestId, $installationId
    );
    $state['requests'][$requestId]['status'] = 'claimed';
    $state['requests'][$requestId]['claimed_at'] = gmdate('c');
    return ['allowed' => true, 'status' => 'claimed', 'credential' => $grant];
}

function one_license_owner_pairing_create(array &$state): string {
    $code = 'OWNER-' . strtoupper(bin2hex(random_bytes(20)));
    $state['owner_pairing'] = [
        'hash' => one_license_hash($code),
        'expires_at' => time() + 600
    ];
    return $code;
}

function one_license_owner_pair(array &$state, string $code, string $device, string $installationId): array {
    $p = $state['owner_pairing'] ?? [];
    if (!is_array($p) || strlen($code) !== 46 ||
        (int)($p['expires_at'] ?? 0) < time() ||
        !hash_equals((string)($p['hash'] ?? ''), one_license_hash(strtoupper(trim($code))))) {
        return ['paired' => false];
    }
    $token = one_license_token();
    $id = bin2hex(random_bytes(12));
    $state['owner_tokens'][$id] = [
        'id' => $id, 'device' => $device, 'installation_id' => $installationId,
        'hash' => one_license_hash($token), 'created_at' => gmdate('c'),
        'revoked' => false
    ];
    unset($state['owner_pairing']);
    return ['paired' => true, 'owner_token' => $token];
}

function one_license_owner_authenticated(array $state, string $device, string $installationId, string $token): bool {
    if (strlen($token) !== 64) return false;
    foreach (($state['owner_tokens'] ?? []) as $owner) {
        if (!empty($owner['revoked'])) continue;
        if (!hash_equals((string)($owner['device'] ?? ''), $device)) continue;
        if (!hash_equals((string)($owner['installation_id'] ?? ''), $installationId)) continue;
        if (hash_equals((string)($owner['hash'] ?? ''), one_license_hash($token))) return true;
    }
    return false;
}

function one_license_owner_pending(array $state): array {
    $result = [];
    foreach ($state['requests'] as $req) {
        if (!in_array(($req['app'] ?? ''), ['main', 'car', 'run', 'dj'], true) || ($req['status'] ?? '') !== 'pending') continue;
        $result[] = [
            'id' => (string)$req['id'], 'app' => (string)$req['app'], 'device' => (string)$req['device'],
            'person' => (string)$req['person'], 'installation_id' => (string)$req['installation_id'],
            'created_at' => (string)$req['created_at'], 'status' => 'pending'
        ];
    }
    return $result;
}

function one_license_owner_approve(array &$state, string $requestId): bool {
    if (!isset($state['requests'][$requestId]) ||
        $state['requests'][$requestId]['status'] !== 'pending') return false;
    $state['requests'][$requestId]['status'] = 'approved';
    $state['requests'][$requestId]['approved_at'] = gmdate('c');
    return true;
}

function one_license_list_admin(array $state): array {
    $grants = array_map(static fn(array $g): array => array_diff_key($g, ['token_hash' => true]), array_values($state['grants']));
    $codes = array_map(static fn(array $c): array => array_diff_key($c, ['hash' => true]), array_values($state['codes']));
    return [
        'main_enforced' => (bool)$state['main_enforced'], 'studio_enforced' => (bool)$state['studio_enforced'],
        'grandfathered_count' => count($state['legacy_main']),
        'requests' => array_values(array_map(static fn(array $r): array => array_diff_key($r, ['request_secret_hash' => true]), $state['requests'])), 'grants' => $grants, 'codes' => $codes
    ];
}

function one_license_installation_id(string $installationId): string {
    if (!preg_match('/^[0-9a-fA-F-]{36}$/D', $installationId)) throw new InvalidArgumentException('Invalid installation identifier');
    return strtolower($installationId);
}

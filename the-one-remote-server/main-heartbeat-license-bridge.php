<?php
declare(strict_types=1);
/**
 * Licensing bridge for devices.php, not a replacement for the live Main/Car allowlists.
 * The caller MUST first classify Car/Windows and must only invoke this for Main.
 * Do not treat mere owner approval or a caller-supplied device id as authorization.
 */
require_once __DIR__ . '/license-core.php';

function one_license_main_heartbeat_granted(string $deviceId, string $installationId, string $token): bool {
    if (!preg_match('/^[A-Za-z0-9_.:-]{8,120}$/D', $deviceId)
        || !preg_match('/^[0-9a-fA-F-]{36}$/D', $installationId)
        || !preg_match('/^[a-fA-F0-9]{64}$/D', $token)) {
        return false;
    }
    try {
        return (bool)one_license_locked(static function(array &$state, callable $dirty) use ($deviceId, $installationId, $token): bool {
            // Nothing is implicitly approved while the migration is incomplete.
            if (empty($state['main_enforced'])) return false;
            return one_license_find_grant($state, 'main', $deviceId, $token, strtolower($installationId)) !== null;
        });
    } catch (Throwable $e) {
        // Fail closed without leaking license tokens into logs.
        error_log('The One Main registry license validation unavailable');
        return false;
    }
}

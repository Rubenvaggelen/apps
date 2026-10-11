<?php
declare(strict_types=1);
/**
 * DJ-specific bridge for the protected Main devices.php registry.
 * Do NOT exempt an Android client from Main licensing merely because it calls
 * itself "dj". Only an active, cryptographically installation-bound DJ license
 * may register the DJ role. No new Main, Car, or Windows behavior is changed.
 */
require_once __DIR__ . '/license-core.php';

function one_dj_license_heartbeat_granted(string $deviceId, string $installationId, string $token): bool {
    if (!preg_match('/^[A-Za-z0-9_.:-]{8,120}$/D', $deviceId) ||
        !preg_match('/^[0-9a-fA-F-]{36}$/D', $installationId) ||
        !preg_match('/^[a-fA-F0-9]{64}$/D', $token)) {
        return false;
    }
    try {
        return (bool)one_license_locked(static function(array &$state, callable $dirty) use
            ($deviceId,$installationId,$token): bool {
            if (isset($state['retired_devices'][$deviceId])) return false;
            return one_license_find_grant(
                $state, 'dj', $deviceId, strtolower($token), strtolower($installationId)
            ) !== null;
        });
    } catch (Throwable $error) {
        error_log('The One DJ registry license validation unavailable');
        return false;
    }
}

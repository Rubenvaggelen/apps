<?php
declare(strict_types=1);

// Safely patch a snapshot of the LIVE API; fail closed if the live source
// differs from the authenticated-owner Family API contract.
if (PHP_SAPI !== 'cli' || $argc !== 3) {
    fwrite(STDERR, "Usage: php patch-deny.php <copied-license-core> <copied-licenses-api>\n");
    exit(2);
}
[$script, $corePath, $apiPath] = $argv;
$core = file_get_contents($corePath);
$api = file_get_contents($apiPath);
if (!is_string($core) || !is_string($api)) {
    throw new RuntimeException('Staged API files missing');
}
foreach (['one_license_owner_authenticated', 'one_license_owner_pending', 'one_license_owner_approve'] as $required) {
    if (!str_contains($api, $required) && !str_contains($core, $required)) {
        throw new RuntimeException("Unsupported live licensing schema: $required");
    }
}
if (!str_contains($core, 'function one_license_owner_deny(')) {
    $marker = 'function one_license_list_admin(array $state): array {';
    if (substr_count($core, $marker) !== 1) throw new RuntimeException('Cannot locate unique core patch point');
    $fn = <<<'PHP'

/** Only cancel a still-pending request; issued licenses are never touched. */
function one_license_owner_deny(array &$state, string $requestId): bool {
    $request = $state['requests'][$requestId] ?? null;
    if (!is_array($request) || ($request['status'] ?? '') !== 'pending') return false;
    $state['requests'][$requestId]['status'] = 'denied';
    $state['requests'][$requestId]['denied_at'] = gmdate('c');
    return true;
}

PHP;
    $core = str_replace($marker, $fn . $marker, $core);
}
$oldGuard = "in_array((\$req['status'] ?? ''), ['pending', 'approved'], true))";
$newGuard = "in_array((\$req['status'] ?? ''), ['pending', 'approved', 'denied'], true))";
if (str_contains($core, $oldGuard)) {
    $core = str_replace($oldGuard, $newGuard, $core);
} elseif (!str_contains($core, $newGuard)) {
    throw new RuntimeException('Live pending/approved replay guard changed; manual review required');
}
$ownerList = "['owner_pair', 'owner_pending', 'owner_approve']";
$ownerListNew = "['owner_pair', 'owner_pending', 'owner_approve', 'owner_deny']";
if (str_contains($api, $ownerList)) {
    $api = str_replace($ownerList, $ownerListNew, $api);
} elseif (!str_contains($api, $ownerListNew)) {
    throw new RuntimeException('Live owner-action dispatch changed');
}
if (!str_contains($api, "if (\$action === 'owner_deny')")) {
    $marker = "            if (\$action === 'owner_approve') {";
    if (substr_count($api, $marker) !== 1) throw new RuntimeException('Missing unique owner-approve action');
    $handler = <<<'PHP'
            if ($action === 'owner_deny') {
                $id = (string)($body['request_id'] ?? '');
                if (!preg_match('/^[a-f0-9]{24}$/D', $id)) throw new InvalidArgumentException('Invalid request');
                $denied = one_license_owner_deny($state, $id);
                if ($denied) $dirty();
                return ['denied' => $denied];
            }

PHP;
    $api = str_replace($marker, $handler . $marker, $api);
}
if (substr_count($core, 'function one_license_owner_deny(') !== 1
    || substr_count($api, "if (\$action === 'owner_deny')") !== 1
    || !str_contains($api, 'one_license_owner_authenticated(')) {
    throw new RuntimeException('Owner approval guard/integrity invariant failed');
}
if (file_put_contents($corePath, $core) === false || file_put_contents($apiPath, $api) === false) {
    throw new RuntimeException('Cannot write staged patch');
}
echo "PASS: staged owner deny patch, replay prevention, existing license grants preserved\n";

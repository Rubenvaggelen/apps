<?php
declare(strict_types=1);
require_once dirname(__DIR__) . '/license-core.php';

function assert_issue(bool $ok, string $message): void {
    if (!$ok) throw new RuntimeException($message);
}
$state = one_license_default();
$device = 'surface-issuance-test';
$currentInstall = '99999999-9999-4999-8999-999999999999';
$otherInstall = '88888888-8888-4888-8888-888888888888';

// Portable v1.4.1 uses "music": it is activated by OWNER APPROVAL, not legacy "studio" codes.
$req = one_license_request($state, 'music', $device, 'License Test', $currentInstall);
assert_issue(count(one_license_owner_pending($state)) === 1, 'new Windows Studio request missing');
assert_issue(one_license_owner_approve($state, $req['request_id']), 'owner cannot approve new Studio license');
assert_issue(!one_license_owner_approve($state, $req['request_id']), 'double approval accepted');
$wrongClaim = one_license_request_claim($state, $req['request_id'], $device, $otherInstall, $req['request_secret']);
assert_issue(($wrongClaim['allowed'] ?? false) === false, 'license granted to wrong installation');
assert_issue(count($state['grants']) === 0, 'failed claim created grant');
$claimed = one_license_request_claim($state, $req['request_id'], $device, $currentInstall, $req['request_secret']);
assert_issue(($claimed['allowed'] ?? false) === true, 'approved Studio license did not activate');
$token = $claimed['credential']['token'] ?? '';
assert_issue(is_string($token) && strlen($token) === 64, 'grant credential malformed');
assert_issue(one_license_find_grant($state, 'music', $device, $token, $currentInstall) !== null, 'activated Studio grant missing');
assert_issue(one_license_find_grant($state, 'music', $device, $token, $otherInstall) === null, 'license leaked across installations');
assert_issue(count(one_license_owner_pending($state)) === 0, 'claimed license still pending');

// Legacy Studio code is a separate app identity and must not activate Music Studio Portable.
$code = one_license_create_code($state, 'studio', 'Legacy Studio', 1);
$legacy = one_license_redeem($state, 'studio', 'legacy-windows-test', 'License Test', $code['code'],
    '77777777-7777-4777-8777-777777777777');
assert_issue(($legacy['allowed'] ?? false) === true, 'legacy owner-issued Studio code cannot be redeemed');
$redeemedTwice = one_license_redeem($state, 'studio', 'legacy-windows-test', 'License Test', $code['code'],
    '66666666-6666-4666-8666-666666666666');
assert_issue(($redeemedTwice['allowed'] ?? false) === false, 'one-time code redeemed twice');
assert_issue(one_license_find_grant($state, 'music', $device, $legacy['credential']['token'],
    $currentInstall) === null, 'legacy code improperly activated modern Studio');
assert_issue(count($state['grants']) === 2, 'grant count changed unexpectedly');
echo "PASS: Studio approval/claim, cross-installation isolation and legacy code separation\n";

<?php
declare(strict_types=1);
$dir = sys_get_temp_dir() . '/one-approval-bridge-' . bin2hex(random_bytes(6));
mkdir($dir, 0700);
putenv('THE_ONE_LICENSE_DATA_DIR=' . $dir);
require __DIR__ . '/../the-one-remote-server/main-heartbeat-license-bridge.php';
function assert_approval(bool $ok, string $message): void {
    if (!$ok) throw new RuntimeException($message);
}
$device = 'new-main-installation-01';
$install = '12345678-1234-4234-8234-123456789abc';
$otherInstall = '87654321-1234-4234-8234-123456789abc';
$state = one_license_default();
$state['main_enforced'] = true;
$request = one_license_request($state, 'main', $device, 'Test device', $install);
$id = $request['request_id'];
$secret = $request['request_secret'];
assert_approval(count(one_license_owner_pending($state)) === 1, 'Pending request not visible');
assert_approval(!one_license_request_claim($state, $id, $device, $install, $secret)['allowed'], 'Claim accepted before approval');
assert_approval(!one_license_request_claim($state, $id, $device, $otherInstall, $secret)['allowed'], 'Other install accepted');
assert_approval(one_license_owner_approve($state, $id), 'Approval failed');
assert_approval(count(one_license_owner_pending($state)) === 0, 'Approved request is still pending');
assert_approval(!one_license_request_claim($state, $id, $device, $install, str_repeat('0', 64))['allowed'], 'Wrong secret accepted');
$claim = one_license_request_claim($state, $id, $device, $install, $secret);
assert_approval($claim['allowed'] && $claim['status'] === 'claimed', 'Valid approval claim failed');
assert_approval(!one_license_request_claim($state, $id, $device, $install, $secret)['allowed'], 'Claim could be reused');
$token = $claim['credential']['token'];
one_license_locked(static function(array &$current, callable $dirty) use ($state): void {
    $current = $state;
    $dirty();
});
assert_approval(one_license_main_heartbeat_granted($device, $install, $token), 'Approved installation blocked');
assert_approval(!one_license_main_heartbeat_granted($device, $otherInstall, $token), 'Other install accepted by heartbeat');
assert_approval(!one_license_main_heartbeat_granted('different-main-device', $install, $token), 'Other device accepted by heartbeat');
foreach (array_keys($state['grants']) as $id) $state['grants'][$id]['status'] = 'revoked';
one_license_locked(static function(array &$current, callable $dirty) use ($state): void {
    $current = $state;
    $dirty();
});
assert_approval(!one_license_main_heartbeat_granted($device, $install, $token), 'Revoked grant accepted');
@unlink($dir . '/licenses.json');
@unlink($dir . '/license.lock');
@rmdir($dir);
echo "Main approval to heartbeat integration passed\n";

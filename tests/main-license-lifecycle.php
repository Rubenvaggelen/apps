<?php
declare(strict_types=1);
$dir = sys_get_temp_dir() . '/one-license-' . bin2hex(random_bytes(6));
putenv('THE_ONE_LICENSE_DATA_DIR=' . $dir);
require dirname(__DIR__) . '/the-one-remote-server/license-core.php';

function assert_state(bool $cond, string $what): void {
    if (!$cond) throw new RuntimeException('FAILED: ' . $what);
    echo "OK " . $what . PHP_EOL;
}
$oldDevice = 'device-existing-main-123';
$otherDevice = 'device-another-main-456';
$oldInstall = '11111111-1111-4111-8111-111111111111';
$reinstalled = '22222222-2222-4222-8222-222222222222';
$friendInstall = '33333333-3333-4333-8333-333333333333';
$mainRegistry = $dir . '/main-devices.json';
@mkdir($dir, 0700, true);
file_put_contents($mainRegistry, json_encode(['devices' => [
    $oldDevice => ['person_name' => 'Legacy user', 'device_role' => 'main'],
    $otherDevice => ['person_name' => 'Second user', 'device_role' => 'main'],
    'car-ignored-1234' => ['person_name' => 'Car', 'device_role' => 'car']
]], JSON_THROW_ON_ERROR));

try {
    $s = one_license_default();
    $st = one_license_main_status($s, $oldDevice, '', $oldInstall, 0);
    assert_state(!$st['enabled'] && $st['allowed'], 'Main licensing defaults to OFF');
    $m = one_license_initialize_main($s, $mainRegistry);
    assert_state($m['grandfathered'] === 2 && $s['main_enforced'], 'migration snapshots only two existing Main devices');
    $before = (strtotime($s['migration_at']) - 3600) * 1000;
    $after = (strtotime($s['migration_at']) + 3600) * 1000;
    $old = one_license_main_status($s, $oldDevice, '', $oldInstall, $before);
    assert_state($old['allowed'] && $old['mode'] === 'grandfathered', 'existing installation grandfathered');
    $token = $old['credential']['token'];
    $again = one_license_main_status($s, $oldDevice, $token, $oldInstall, $before);
    assert_state($again['allowed'], 'normal update keeps existing license');
    $wrongInstance = one_license_main_status($s, $oldDevice, $token, $reinstalled, $after);
    assert_state(!$wrongInstance['allowed'], 'old token cannot activate reinstalled app with new install ID');
    $newInstall = one_license_main_status($s, $oldDevice, '', $reinstalled, $after);
    assert_state(!$newInstall['allowed'], 'reinstall resets permission despite same device ID');
    assert_state(!one_license_main_status($s, $otherDevice, '', $friendInstall, $after)['allowed'], 'other existing device freshly reinstalled also denied');
    $created = one_license_create_code($s, 'main', 'Reinstalled owner');
    $redeemed = one_license_redeem($s, 'main', $oldDevice, 'Reinstalled owner', $created['code'], $reinstalled);
    assert_state($redeemed['allowed'], 'owner can authorize reinstall using issued code');
    $newToken = $redeemed['credential']['token'];
    assert_state(one_license_main_status($s, $oldDevice, $newToken, $reinstalled, $after)['allowed'], 'fresh license works on new installation');
    $sameCode = one_license_redeem($s, 'main', $otherDevice, 'Other person', $created['code'], $friendInstall);
    assert_state(!$sameCode['allowed'], 'single-use code cannot be reused');
    $list = one_license_list_admin($s);
    assert_state(!str_contains(json_encode($list), $created['code']), 'raw code never stored in admin listing');
    assert_state(!str_contains(json_encode($list), $newToken), 'raw license token never stored in admin listing');
    $id = $redeemed['credential']['grant_id'];
    $s['grants'][$id]['status'] = 'revoked';
    assert_state(!one_license_main_status($s, $oldDevice, $newToken, $reinstalled, $after)['allowed'], 'revoked license is denied');
    $request = one_license_request($s, 'main', $oldDevice, 'Person');
    assert_state($request['status'] === 'pending', 'new activation request created');
    $requestAgain = one_license_request($s, 'main', $oldDevice, 'Person');
    assert_state($requestAgain['request_id'] === $request['request_id'], 'pending request deduplicated');
    echo 'ALL MAIN LICENSE LIFECYCLE TESTS PASSED' . PHP_EOL;
} finally {
    @unlink($mainRegistry);
    @rmdir($dir);
}

<?php
declare(strict_types=1);
$dir = sys_get_temp_dir() . '/one-heartbeat-test-' . bin2hex(random_bytes(6));
mkdir($dir, 0700);
putenv('THE_ONE_LICENSE_DATA_DIR=' . $dir);
require __DIR__ . '/../the-one-remote-server/main-heartbeat-license-bridge.php';
$device = 'new-main-device-0001';
$install = '11111111-2222-4333-8444-555555555555';
$token = str_repeat('a', 64);
function check_bridge(bool $ok, string $message): void {
    if (!$ok) { fwrite(STDERR, "FAIL: $message\n"); exit(1); }
}
check_bridge(!one_license_main_heartbeat_granted($device, $install, $token), 'No grant denied');
one_license_locked(static function(array &$state, callable $dirty) use($device,$install,$token): void {
    $state['grants']['test'] = [
        'app'=>'main', 'device'=>$device, 'status'=>'active',
        'installation_id'=>$install, 'token_hash'=>one_license_hash($token)
    ];
    $dirty();
});
check_bridge(one_license_main_heartbeat_granted($device, $install, $token), 'Valid active grant must authorize during pilot before cutover');
one_license_locked(static function(array &$state, callable $dirty) use($device,$install,$token): void {
    $state['main_enforced'] = true;
    $dirty();
});
check_bridge(one_license_main_heartbeat_granted($device, $install, $token), 'Approved grant accepted');
check_bridge(!one_license_main_heartbeat_granted($device, '99999999-2222-4333-8444-555555555555', $token), 'Other installation denied');
check_bridge(!one_license_main_heartbeat_granted($device, $install, str_repeat('b',64)), 'Wrong token denied');
check_bridge(!one_license_main_heartbeat_granted('other-main-device', $install, $token), 'Other device denied');
one_license_locked(static function(array &$state, callable $dirty): void {$state['grants']['test']['status']='revoked'; $dirty();});
check_bridge(!one_license_main_heartbeat_granted($device, $install, $token), 'Revoked grant denied');
@unlink($dir . '/licenses.json'); @unlink($dir . '/license.lock'); @rmdir($dir);
echo "Main heartbeat bridge tests passed\n";

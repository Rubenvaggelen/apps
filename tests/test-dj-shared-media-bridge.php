<?php
declare(strict_types=1);
$directory=sys_get_temp_dir().'/the-one-dj-bridge-'.bin2hex(random_bytes(6));
if (!mkdir($directory,0700,true)) throw new RuntimeException('temp failed');
putenv('THE_ONE_LICENSE_DATA_DIR='.$directory);
require __DIR__ . '/../the-one-remote-server/dj-heartbeat-license-bridge.php';
function check_dj_bridge(bool $value, string $message): void {
    if (!$value) throw new RuntimeException($message);
}
$device='dj-new-tablet-20261010';
$install='12345678-1234-4234-8234-123456789abc';
$wrongInstall='87654321-1234-4234-8234-123456789abc';
$token=str_repeat('a',64);
check_dj_bridge(!one_dj_license_heartbeat_granted($device,$install,$token),'Unlicensed DJ heartbeat was allowed');
one_license_locked(static function(array &$state, callable $dirty) use($device,$install,$token): void {
    $state['grants']['test-dj']=[
        'app'=>'dj','device'=>$device,'status'=>'active',
        'installation_id'=>$install,'token_hash'=>one_license_hash($token)
    ];
    $dirty();
});
check_dj_bridge(one_dj_license_heartbeat_granted($device,$install,$token),'Valid DJ owner grant denied');
check_dj_bridge(!one_dj_license_heartbeat_granted($device,$wrongInstall,$token),'Different DJ installation accepted old token');
check_dj_bridge(!one_dj_license_heartbeat_granted('dj-other-device',$install,$token),'Different DJ device used old token');
check_dj_bridge(!one_dj_license_heartbeat_granted($device,$install,str_repeat('b',64)),'Wrong DJ token accepted');
one_license_locked(static function(array &$state, callable $dirty): void {
    $state['grants']['test-dj']['status']='revoked';
    $dirty();
});
check_dj_bridge(!one_dj_license_heartbeat_granted($device,$install,$token),'Revoked DJ license allowed');
one_license_locked(static function(array &$state, callable $dirty) use($device,$install,$token): void {
    $state['grants']['test-dj']['status']='active';
    $state['retired_devices'][$device]=['removed_at'=>gmdate('c')];
    $dirty();
});
check_dj_bridge(!one_dj_license_heartbeat_granted($device,$install,$token),'Retired DJ device heartbeat allowed');
@unlink($directory.'/licenses.json');
@unlink($directory.'/license.lock');
@rmdir($directory);
echo "PASS: DJ heartbeat requires active license for exact device+installation; revoke and retirement deny\n";

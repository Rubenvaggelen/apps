<?php
declare(strict_types=1);
require __DIR__ . '/../the-one-remote-server/license-core.php';

function expect_family(bool $value, string $message): void {
    if (!$value) throw new RuntimeException($message);
}
$state = one_license_default();
$device = 'dj-unit-001';
$install = '12345678-1234-4234-8234-123456789abc';
$newInstall = '87654321-1234-4234-8234-123456789abc';
foreach (['dj','music'] as $app) {
    $request=one_license_request($state,$app,$device,'Studio Tablet',$install);
    expect_family(($request['status']??'')==='pending',"$app request not pending");
    expect_family(!one_license_request_claim($state,$request['request_id'],$device,$install,$request['request_secret'])['allowed'],"$app approved prematurely");
    $pending=one_license_owner_pending($state);
    expect_family(count(array_filter($pending,static fn($x)=>($x['app']??'')===$app))===1,"$app not visible in Main owner queue");
    expect_family(!one_license_request_claim($state,$request['request_id'],$device,$newInstall,$request['request_secret'])['allowed'],"$app new installation inherited old grant");
    expect_family(one_license_owner_approve($state,$request['request_id']),"$app owner approval failed");
    $claim=one_license_request_claim($state,$request['request_id'],$device,$install,$request['request_secret']);
    expect_family($claim['allowed']===true,"$app can't claim approved license");
    $token=$claim['credential']['token'];
    expect_family(one_license_find_grant($state,$app,$device,$token,$install)!==null,"$app approved token not valid");
    expect_family(one_license_find_grant($state,$app,$device,$token,$newInstall)===null,"$app reinstall inherited license");
    expect_family(one_license_find_grant($state,$app,'other-device',$token,$install)===null,"$app another device inherited license");
    foreach ($state['grants'] as &$grant) if (($grant['app']??'')===$app) $grant['status']='revoked';
    unset($grant);
    expect_family(one_license_find_grant($state,$app,$device,$token,$install)===null,"$app revocation did not take effect");
}
$state['retired_devices'][$device]=['removed_at'=>gmdate('c')];
expect_family((one_license_request($state,'dj',$device,'Studio Tablet',$newInstall)['status']??'')==='device_removed','Retired DJ registration accepted');
echo "PASS: DJ+Music owner queue, one-time approval, install isolation, revocation and retirement\n";
